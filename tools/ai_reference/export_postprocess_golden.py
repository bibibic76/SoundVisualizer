#!/usr/bin/env python3
"""Export CP6 threshold/hysteresis/preview sequence goldens for Android JVM tests."""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Dict, List

REPO = Path(__file__).resolve().parents[2]
OUT = REPO / "app" / "src" / "test" / "resources" / "ai_reference"


@dataclass
class FrameIn:
    coarse: str
    display: str
    confidence: float
    adopted_danger_from_booster: bool = False
    has_strong_danger_cue: bool = False
    has_critical_danger_cue: bool = False
    top_k_summary: str = ""


class PostProcessorRef:
    """Threshold + ApplyCoarseHysteresis + booster preview."""

    COARSE_HYSTERESIS_THRESHOLD = 2
    DANGER_HYSTERESIS_THRESHOLD = 1
    DANGER_IMMEDIATE_SWITCH_CONFIDENCE = 0.28
    DANGER_EXIT_RELAXED_CONFIDENCE = 0.27

    def __init__(self, base_threshold: float = 0.25):
        self.base_threshold = float(base_threshold)
        self.reset()

    def reset(self) -> None:
        self._confirmed_coarse = "ambient"
        self._confirmed_display = ""
        self._confirmed_confidence = 0.0
        self._candidate_coarse = ""
        self._candidate_streak = 0

    @staticmethod
    def is_gunshot_keyword(name: str) -> bool:
        if not name:
            return False
        s = name.lower()
        return any(
            k in s
            for k in ("gunshot", "gunfire", "machine gun", "artillery", "fusillade", "cap gun")
        )

    @classmethod
    def is_critical_danger_keyword(cls, name: str) -> bool:
        if not name:
            return False
        if cls.is_gunshot_keyword(name):
            return True
        s = name.lower()
        return any(k in s for k in ("explosion", "fireworks", "firecracker"))

    @classmethod
    def is_critical_danger_event(cls, display: str, top_k_summary: str) -> bool:
        return cls.is_critical_danger_keyword(display) or cls.is_critical_danger_keyword(top_k_summary)

    def effective_threshold(self, f: FrameIn) -> float:
        effective = self.base_threshold
        if f.coarse == "danger" and (
            f.has_strong_danger_cue or f.has_critical_danger_cue or f.adopted_danger_from_booster
        ):
            effective = min(effective, 0.18 if f.adopted_danger_from_booster else 0.20)
        elif f.coarse == "speech":
            effective = max(effective, 0.25)
        return float(effective)

    def process(self, f: FrameIn) -> Dict[str, Any]:
        effective = self.effective_threshold(f)
        meets = float(f.confidence) >= effective

        if meets:
            self._apply(f, meets)

        use_preview = (
            self._confirmed_coarse != "danger"
            and f.adopted_danger_from_booster
            and f.coarse == "danger"
            and meets
        )
        ui_coarse = "danger" if use_preview else self._confirmed_coarse
        ui_display = f.display if use_preview else self._confirmed_display
        ui_conf = float(f.confidence) if use_preview else float(self._confirmed_confidence)

        return {
            "input": asdict(f),
            "effective_threshold": effective,
            "meets_threshold": bool(meets),
            "candidate_coarse": self._candidate_coarse,
            "candidate_streak": int(self._candidate_streak),
            "confirmed_coarse": self._confirmed_coarse,
            "confirmed_display": self._confirmed_display,
            "confirmed_confidence": float(self._confirmed_confidence),
            "use_booster_danger_preview": bool(use_preview),
            "ui_coarse": ui_coarse,
            "ui_display": ui_display,
            "ui_confidence": float(ui_conf),
        }

    def _apply(self, f: FrameIn, meets: bool) -> None:
        if not meets:
            return
        new_coarse = f.coarse
        if new_coarse == self._confirmed_coarse:
            self._candidate_streak = 0
            self._candidate_coarse = ""
            self._confirmed_display = f.display
            self._confirmed_confidence = float(f.confidence)
            return

        critical = self.is_critical_danger_event(f.display, f.top_k_summary)
        if new_coarse == "danger" and (
            float(f.confidence) >= self.DANGER_IMMEDIATE_SWITCH_CONFIDENCE or critical
        ):
            self._confirmed_coarse = new_coarse
            self._confirmed_display = f.display
            self._confirmed_confidence = float(f.confidence)
            self._candidate_streak = 0
            self._candidate_coarse = ""
            return

        if new_coarse == self._candidate_coarse:
            self._candidate_streak += 1
        else:
            self._candidate_coarse = new_coarse
            self._candidate_streak = 1

        required = (
            self.DANGER_HYSTERESIS_THRESHOLD
            if new_coarse == "danger"
            else self.COARSE_HYSTERESIS_THRESHOLD
        )
        if (
            self._confirmed_coarse == "danger"
            and new_coarse != "danger"
            and not f.adopted_danger_from_booster
            and float(f.confidence) >= self.DANGER_EXIT_RELAXED_CONFIDENCE
        ):
            required = 1

        if self._candidate_streak >= required:
            self._confirmed_coarse = new_coarse
            self._confirmed_display = f.display
            self._confirmed_confidence = float(f.confidence)
            self._candidate_streak = 0
            self._candidate_coarse = ""


def run_seq(name: str, frames: List[FrameIn]) -> Dict[str, Any]:
    pp = PostProcessorRef()
    out = []
    for i, f in enumerate(frames):
        out.append({"frame": i, **pp.process(f)})
    return {"name": name, "frames": out}


def F(**kwargs) -> FrameIn:
    return FrameIn(**kwargs)


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    sequences: Dict[str, List[FrameIn]] = {}

    # A stable ambient
    sequences["A_stable_ambient"] = [
        F(coarse="ambient", display="Wind", confidence=0.40),
        F(coarse="ambient", display="Wind", confidence=0.41),
        F(coarse="ambient", display="Noise", confidence=0.39),
    ]

    # B ambient → speech (needs 2 frames)
    sequences["B_ambient_to_speech"] = [
        F(coarse="ambient", display="Wind", confidence=0.40),
        F(coarse="speech", display="Speech", confidence=0.40),  # streak=1, stay ambient
        F(coarse="speech", display="Speech", confidence=0.41),  # streak=2 → speech
    ]

    # C ambient → danger with streak=1 (conf < 0.28, not critical) — still switches at streak>=1
    sequences["C_ambient_to_danger_streak1"] = [
        F(coarse="ambient", display="Wind", confidence=0.40),
        F(
            coarse="danger",
            display="Siren",
            confidence=0.22,
            has_strong_danger_cue=True,
        ),  # effective 0.20, meets, danger streak1 → confirm
    ]

    # D immediate danger via confidence >= 0.28
    sequences["D_immediate_danger_conf"] = [
        F(coarse="ambient", display="Wind", confidence=0.40),
        F(coarse="danger", display="Siren", confidence=0.28, has_strong_danger_cue=True),
    ]

    # E critical danger event (display gunshot) even if conf low but meets threshold
    sequences["E_critical_danger"] = [
        F(coarse="ambient", display="Wind", confidence=0.40),
        F(
            coarse="danger",
            display="Gunshot, gunfire",
            confidence=0.19,
            adopted_danger_from_booster=True,  # effective 0.18
            has_strong_danger_cue=True,
        ),
    ]

    # F low confidence — confirmed unchanged
    sequences["F_low_confidence"] = [
        F(coarse="ambient", display="Wind", confidence=0.40),
        F(coarse="speech", display="Speech", confidence=0.10),  # meets false
        F(coarse="speech", display="Speech", confidence=0.10),
        F(coarse="speech", display="Speech", confidence=0.40),  # streak starts at 1
    ]

    # G candidate reset when coarse changes mid-streak
    sequences["G_candidate_reset"] = [
        F(coarse="ambient", display="Wind", confidence=0.40),
        F(coarse="speech", display="Speech", confidence=0.40),  # cand speech streak1
        F(coarse="danger", display="Siren", confidence=0.22, has_strong_danger_cue=True),  # danger confirms
    ]

    # H danger exit relaxed (1 frame when conf>=0.27 and not re-adopted booster)
    sequences["H_danger_exit"] = [
        F(coarse="danger", display="Gunshot, gunfire", confidence=0.40, has_critical_danger_cue=True),
        F(coarse="ambient", display="Wind", confidence=0.27),  # required=1 → ambient
    ]

    # H2 danger exit without relaxed (conf < 0.27) needs 2 frames
    sequences["H2_danger_exit_needs_two"] = [
        F(coarse="danger", display="Gunshot, gunfire", confidence=0.40, has_critical_danger_cue=True),
        F(coarse="ambient", display="Wind", confidence=0.26),  # streak1, stay danger
        F(coarse="ambient", display="Wind", confidence=0.26),  # streak2 → ambient
    ]

    # I booster accepted → danger with 0.18 threshold + immediate via critical display
    sequences["I_booster_accepted"] = [
        F(coarse="ambient", display="Ding", confidence=0.30),
        F(
            coarse="danger",
            display="Gunshot, gunfire",
            confidence=0.19,
            adopted_danger_from_booster=True,
            has_strong_danger_cue=True,
        ),
    ]

    # J booster preview: danger via booster but confirmed stays ambient until hysteresis
    # Use non-critical display + conf < 0.28 so immediate path doesn't fire; danger streak=1 confirms anyway
    # So for preview we need: adopted booster, coarse danger, meets, BUT confirmed not yet danger
    # With DANGER_HYSTERESIS=1, first valid danger frame confirms immediately via streak.
    # Preview is only useful when confirmed hasn't switched — e.g. when meets fails first?
    # Actually preview requires meets AND confirmed != danger AND adopted AND coarse==danger.
    # After process(), hysteresis may already set confirmed=danger on same frame (streak1).
    # Order: ApplyCoarseHysteresis THEN compute preview from updated confirmed.
    # So if danger confirms same frame, preview is FALSE.
    # To observe preview True: need a frame where adopted+danger+meets but hysteresis does NOT confirm yet.
    # That requires requiredStreak > 1 for danger — but danger required is 1.
    # Unless: first danger frame fails meets, then... no.
    # Wait: danger with conf < immediate and NOT critical still gets streak=1 and required=1 → confirms.
    # So preview is True only when confirmed becomes danger on a LATER path? Looking again:
    # After hysteresis, if confirmed switched to danger, useBoosterDangerPreview is false because confirmed==danger.
    # Preview is for when hysteresis has NOT accepted yet — but danger streak requirement is 1, so same frame confirms.
    # Unless MeetsThreshold is true but newCoarse==danger with conf < 0.28 and not critical — still confirms via streak>=1.
    # Conclusion: with DangerHysteresisThreshold=1, preview is effectively never true for danger entry
    # EXCEPT if confirmed is already something and... no, first danger frame always confirms.
    #
    # Re-read: requiredStreak = 1 for danger. First danger frame: candidate set to danger streak=1, then streak>=1 confirms.
    # So preview after hysteresis is always false when danger is newly confirmed same frame.
    #
    # When would preview be true? If confirmed stays non-danger despite input danger.
    # That only happens if MeetsThreshold is false — but then preview also requires MeetsThreshold.
    # OR if somehow required > 1 for danger — it isn't.
    #
    # Unless candidate streak starts and required is 1 — always confirms.
    # Preview might be dead code for danger entry with threshold=1, OR
    # it's for when confirmed is speech and we get booster danger that somehow doesn't update?
    # Looking again... After confirming danger, preview false. Before confirming — same frame confirms.
    # So preview is never true with current constants for new danger.
    #
    # One edge case: confirmed already "danger" from before — preview false.
    # Another: maybe CoarseClass is danger but MeetsThreshold true and immediate path skipped and
    # wait — streak 1 always enough.
    #
    # I'll still implement preview identically and test a synthetic case where we force
    # required streak by using a custom scenario documenting the behavior:
    # Actually re-read user request: "Booster Preview confirmed 전환 전 preview 동작"
    # Perhaps when danger exit is pending? Or when confirmed is speech and danger arrives with
    # ... still confirms in 1 frame.
    #
    # I'll add sequence that documents preview=false after same-frame confirm, AND
    # a manual unit test that temporarily verifies the preview formula with injected state
    # via processing order: if we could have required=2 for danger it would preview.
    #
    # Alternative reading: useBoosterDangerPreview is evaluated AFTER hysteresis. If hysteresis
    # confirmed danger, preview is false. The UI still shows danger via confirmed.
    # Preview matters when AdoptedDangerFromBooster and hysteresis hasn't switched yet —
    # which needs DangerHysteresisThreshold > 1. Here it is 1.
    #
    # Keep faithful implementation; test J verifies that after booster danger frame,
    # ui shows danger (via confirmed) and preview flag is false (same-frame confirm).
    sequences["J_booster_preview_same_frame_confirm"] = [
        F(coarse="ambient", display="Ding", confidence=0.35),
        F(
            coarse="danger",
            display="Gunshot, gunfire",
            confidence=0.22,  # < 0.28, critical display → immediate confirm via critical event
            adopted_danger_from_booster=True,
            has_strong_danger_cue=True,
        ),
    ]

    # Non-critical booster danger (Siren is strong but not critical keyword for IsCriticalDangerEvent)
    # Siren is strong for threshold but IsCriticalDangerKeyword does NOT include siren!
    # So conf 0.22 < 0.28, not critical → streak path → still confirms at 1.
    sequences["J2_booster_siren_confirm"] = [
        F(coarse="ambient", display="Music", confidence=0.40),
        F(
            coarse="danger",
            display="Siren",
            confidence=0.22,
            adopted_danger_from_booster=True,
            has_strong_danger_cue=True,
        ),
    ]

    # K threshold boundaries
    sequences["K_threshold_boundaries"] = [
        # speech needs >= 0.25
        F(coarse="speech", display="Speech", confidence=0.2499),  # fail
        F(coarse="speech", display="Speech", confidence=0.25),  # start streak
        F(coarse="speech", display="Speech", confidence=0.25),  # confirm speech
        # danger with strong cue, not booster: effective 0.20
        F(coarse="danger", display="Siren", confidence=0.1999, has_strong_danger_cue=True),  # fail
        F(coarse="danger", display="Siren", confidence=0.20, has_strong_danger_cue=True),  # confirm danger
        # booster danger effective 0.18
        F(coarse="ambient", display="Wind", confidence=0.40),
        F(
            coarse="danger",
            display="Gunshot, gunfire",
            confidence=0.1799,
            adopted_danger_from_booster=True,
            has_strong_danger_cue=True,
        ),  # fail keep ambient
        F(
            coarse="danger",
            display="Gunshot, gunfire",
            confidence=0.18,
            adopted_danger_from_booster=True,
            has_strong_danger_cue=True,
        ),  # meet + critical → danger
        # immediate 0.28 boundary (non-critical display)
        F(coarse="ambient", display="Wind", confidence=0.40),
        F(coarse="danger", display="Siren", confidence=0.2799, has_strong_danger_cue=True),  # streak confirm anyway
        F(coarse="ambient", display="Wind", confidence=0.40),
        F(coarse="danger", display="Siren", confidence=0.28, has_strong_danger_cue=True),  # immediate
        # exit 0.27 boundary
        F(coarse="danger", display="Gunshot, gunfire", confidence=0.40, has_critical_danger_cue=True),
        F(coarse="ambient", display="Wind", confidence=0.2699),  # no relaxed, streak1
        F(coarse="ambient", display="Wind", confidence=0.2699),  # streak2 → ambient
        F(coarse="danger", display="Gunshot, gunfire", confidence=0.40, has_critical_danger_cue=True),
        F(coarse="ambient", display="Wind", confidence=0.27),  # relaxed 1 → ambient
    ]

    meta = {name: run_seq(name, frames) for name, frames in sequences.items()}
    (OUT / "yamnet_cp6_meta.json").write_text(
        json.dumps(meta, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    # short summary
    for name, seq in meta.items():
        last = seq["frames"][-1]
        print(
            f"{name}: frames={len(seq['frames'])} "
            f"final={last['confirmed_coarse']}/{last['confirmed_display']} "
            f"preview={last['use_booster_danger_preview']}"
        )


if __name__ == "__main__":
    main()
