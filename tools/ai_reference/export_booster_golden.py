#!/usr/bin/env python3
"""Export CP5 gunshot-booster goldens (score + adopt/reject) for Android tests."""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import onnxruntime as ort

import mapper
from classifier import ReferenceClassifier

REPO = Path(__file__).resolve().parents[2]
OUT = REPO / "app" / "src" / "test" / "resources" / "ai_reference"
ANDROID_TEST = REPO / "app" / "src" / "androidTest" / "assets" / "ai_reference"
MODEL_DIR = Path(
    "app/src/main/assets/ai"
)


def write_f32(path: Path, arr: np.ndarray) -> None:
    path.write_bytes(np.asarray(arr, dtype=np.float32).reshape(-1).astype("<f4").tobytes())


def one_hotish(d: dict[int, float]) -> np.ndarray:
    p = np.zeros(521, dtype=np.float32)
    for i, v in d.items():
        p[int(i)] = float(v)
    s = float(p.sum())
    p = p / s if s > 0 else np.full(521, 1.0 / 521, dtype=np.float32)
    return p


def booster_score(sess: ort.InferenceSession, probs: np.ndarray) -> float:
    x = np.zeros((1, 521), dtype=np.float32)
    x[0] = np.asarray(probs, dtype=np.float32).reshape(-1)[:521]
    out = sess.run(None, {"yamnet_scores": x})[0]
    return float(np.asarray(out).reshape(-1)[0])


def apply_booster(clf: ReferenceClassifier, probs: np.ndarray, score: float) -> dict:
    """Mirror Android GunshotBoosterDecision using classifier helpers (no full predict)."""
    probs = np.asarray(probs, dtype=np.float32).reshape(-1)
    top_idx, top_probs = clf._compute_top5(probs)
    max_index = int(top_idx[0])
    conf = float(top_probs[0])
    display = clf._class_names[max_index]
    max_index, conf, display = clf._prefer_danger_when_top_is_masked_by_game_mix(
        probs, max_index, conf, display
    )
    coarse = clf._vote_coarse_from_top5(top_idx, top_probs, 3)
    pre = {
        "coarse": coarse,
        "display": display,
        "confidence": conf,
        "yamnet_class_index": max_index,
        "top5": [
            {
                "index": int(top_idx[i]),
                "name": clf._class_names[int(top_idx[i])],
                "prob": float(top_probs[i]),
            }
            for i in range(5)
            if int(top_idx[i]) >= 0
        ],
    }

    evidence = clf._sum_gunshot_probability_from_top5(top_idx, top_probs, 5)
    has_gunshot_cue = clf._has_gunshot_cue_in_top5(top_idx, 5)
    has_strong = clf._has_strong_danger_cue_in_top5(top_idx, 5)

    block = (
        coarse == "speech"
        or clf._is_speech_like_display(display)
        or clf._is_silence_like_display(display)
        or conf < 0.12
    )
    adopt = False
    if block:
        reason = "blocked_speech_silence_or_low_conf"
    elif has_gunshot_cue:
        adopt = score >= 0.20 and evidence >= 0.05
        reason = f"gunshot_cue score={score:.4f} evidence={evidence:.4f} adopt={adopt}"
    elif clf._is_game_mix_mask_display(display) or has_strong:
        adopt = score >= 0.50 or (score >= 0.40 and evidence >= 0.04)
        reason = (
            f"game_mix_or_strong_danger score={score:.4f} evidence={evidence:.4f} adopt={adopt}"
        )
    else:
        adopt = score >= 0.45 and evidence >= 0.10
        reason = f"default score={score:.4f} evidence={evidence:.4f} adopt={adopt}"

    post_coarse, post_display, post_index, post_conf = coarse, display, max_index, conf
    if adopt:
        post_coarse = "danger"
        post_conf = max(post_conf, max(score, evidence))
        gun_idx, gun_prob = clf._try_pick_best_gunshot_display(probs)
        if gun_idx >= 0:
            post_index = gun_idx
            post_display = clf._class_names[gun_idx]
            post_conf = max(post_conf, gun_prob)

    return {
        "gunshot_score": float(score),
        "gunshot_evidence": float(evidence),
        "accepted": bool(adopt),
        "reason": reason,
        "has_gunshot_cue": bool(has_gunshot_cue),
        "has_strong_danger_cue": bool(has_strong),
        "pre": pre,
        "post": {
            "coarse": post_coarse,
            "display": post_display,
            "confidence": float(post_conf),
            "yamnet_class_index": int(post_index),
        },
    }


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    ANDROID_TEST.mkdir(parents=True, exist_ok=True)
    clf = ReferenceClassifier(MODEL_DIR)
    sess = ort.InferenceSession(
        str(MODEL_DIR / "gunshot_booster.onnx"), providers=["CPUExecutionProvider"]
    )

    cases: dict[str, np.ndarray] = {}

    # Real sample probs from CP4 fixtures if present
    for name in ("silence", "gunshot", "alarm"):
        p = OUT / f"cp4_{name}_probs.bin"
        if p.is_file():
            cases[name] = np.frombuffer(p.read_bytes(), dtype="<f4")

    # Handcrafted decision branches (probs for cue/evidence structure)
    cases["speech_block"] = one_hotish({0: 0.70, 2: 0.10, 132: 0.05})  # Speech

    # Low conf (<0.12) with ambient-only top5 (avoid Speech classes filling equal-mass slots)
    low = np.zeros(521, dtype=np.float32)
    for i in (507, 508, 500, 501, 502, 277, 279, 321, 514, 515, 516, 520):
        low[i] = 1.0
    low[507] = 1.05  # conf ≈ 1.05/12.05 ≈ 0.087
    cases["low_conf_block"] = (low / low.sum()).astype(np.float32)

    # Gunshot cue: after PreferDanger, conf must stay >= 0.12 so cue path is reachable
    # Music top + Gunshot above prefer bar → display remapped, conf=gunshot mass
    cases["gunshot_cue"] = one_hotish({132: 0.40, 421: 0.15, 507: 0.10, 498: 0.08})

    # Strong danger (Alarm) without gunshot keyword in top5
    cases["strong_alarm"] = one_hotish({382: 0.15, 132: 0.30, 507: 0.20})

    # Default path: Noise top, no gunshot/strong/game-mix display after prefer
    cases["default_path"] = one_hotish({507: 0.40, 508: 0.15, 500: 0.10})

    # Game-mix Sound effect, no gunshot in top5
    cases["game_mix_no_cue"] = one_hotish({498: 0.35, 132: 0.20, 507: 0.10})

    results = {}
    for name, probs in cases.items():
        write_f32(OUT / f"cp5_{name}_probs.bin", probs)
        score = booster_score(sess, probs)
        write_f32(OUT / f"cp5_{name}_score.bin", np.array([score], dtype=np.float32))
        results[name] = apply_booster(clf, probs, score)

    # Synthetic boundary decision fixtures (fixed score, fixed crafted pre via probs)
    # These store only decision metadata with injected scores for JVM unit tests.
    boundaries = {}
    # Use gunshot_cue probs structure; inject artificial scores around thresholds
    base = cases["gunshot_cue"]
    for tag, score in (
        ("cue_below_score", 0.1999),
        ("cue_at_score", 0.20),
        ("cue_above_score", 0.2001),
        ("cue_evidence_below", 0.50),  # will check evidence separately in JVM with patched evidence
    ):
        boundaries[tag] = apply_booster(clf, base, float(score))

    # Injected decision-only cases documented for Kotlin (score + pre fields)
    # strong path boundaries on strong_alarm probs
    base_s = cases["strong_alarm"]
    for tag, score in (
        ("strong_below_40", 0.3999),
        ("strong_at_40_low_evidence", 0.40),  # evidence may be 0 → reject unless >=0.50
        ("strong_at_50", 0.50),
        ("strong_49_with_evidence", 0.49),
    ):
        boundaries[tag] = apply_booster(clf, base_s, float(score))

    base_d = cases["default_path"]
    for tag, score in (
        ("default_below", 0.4499),
        ("default_at", 0.45),
        ("default_above", 0.4501),
    ):
        boundaries[tag] = apply_booster(clf, base_d, float(score))

    meta = {
        "booster_model": str(MODEL_DIR / "gunshot_booster.onnx"),
        "cases": results,
        "boundaries": boundaries,
        "notes": {
            "silence_block": "silence sample must reject even if score high",
            "gunshot_adopt": "gunshot sample pre ambient → post danger",
        },
    }
    (OUT / "yamnet_cp5_meta.json").write_text(
        json.dumps(meta, indent=2, ensure_ascii=False), encoding="utf-8"
    )

    # androidTest fixtures: silence/gunshot probs+scores + meta subset
    for name in ("silence", "gunshot", "alarm"):
        if name not in cases:
            continue
        for suffix in ("probs", "score"):
            src = OUT / f"cp5_{name}_{suffix}.bin"
            if src.is_file():
                (ANDROID_TEST / src.name).write_bytes(src.read_bytes())
    (ANDROID_TEST / "yamnet_cp5_meta.json").write_text(
        json.dumps(
            {
                "cases": {
                    k: results[k]
                    for k in ("silence", "gunshot", "alarm")
                    if k in results
                }
            },
            indent=2,
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )

    # Print summary
    for k, v in results.items():
        print(
            f"{k}: score={v['gunshot_score']:.6f} accept={v['accepted']} "
            f"{v['pre']['coarse']}→{v['post']['coarse']} reason={v['reason']}"
        )


if __name__ == "__main__":
    main()
