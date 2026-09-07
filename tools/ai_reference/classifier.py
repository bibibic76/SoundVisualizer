"""
Reference implementation of the sound classifier pipeline.
Algorithmic fidelity over elegance — do not "improve" the behavior.
"""

from __future__ import annotations

import csv
import hashlib
import math
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import numpy as np
import onnxruntime as ort

import mapper
from preprocess import (
    DEFAULT_CAPTURE_SAMPLE_RATE,
    MEL_BINS,
    REQUIRED_MONO_16K_SAMPLES,
    SAMPLE_RATE,
    TIME_FRAMES,
    capture_samples_for_one_yamnet_window,
    compute_log_mel_spectrogram,
    create_hann_window,
    create_mel_filter_bank,
    downmix_to_mono,
    log_mel_to_tensor,
    mel_nonzero_ranges,
    resample_mono_float_to_16k_custom,
)


@dataclass
class InferenceResult:
    yamnet_class_index: int
    yamnet_display_name: str
    confidence: float
    coarse_class: str
    meets_threshold: bool
    inference_time_ms: float
    top_k_summary: Optional[str] = None
    adopted_danger_from_booster: bool = False


@dataclass
class FrameTrace:
    pre_hysteresis: InferenceResult
    confirmed_coarse: str
    confirmed_display: str
    confirmed_confidence: float
    ui_coarse: str
    ui_display: str
    ui_confidence: float
    use_booster_danger_preview: bool


class ReferenceClassifier:
    RING_SECONDS = 2
    MAX_RING_SIZE = 131072

    COARSE_HYSTERESIS_THRESHOLD = 2
    DANGER_HYSTERESIS_THRESHOLD = 1
    DANGER_IMMEDIATE_SWITCH_CONFIDENCE = 0.28
    DANGER_EXIT_RELAXED_CONFIDENCE = 0.27

    def __init__(self, model_dir: str | Path):
        self.model_dir = Path(model_dir)
        self._hann = create_hann_window(400)
        self._mel = create_mel_filter_bank()
        self._mel_starts, self._mel_ends = mel_nonzero_ranges(self._mel)

        self._ring = np.zeros(self.MAX_RING_SIZE, dtype=np.float32)
        self._ring_head = 0
        self._ring_tail = 0
        self._ring_count = 0

        self._confirmed_coarse = "ambient"
        self._confirmed_display = ""
        self._confirmed_confidence = 0.0
        self._candidate_coarse = ""
        self._candidate_streak = 0
        self._last_predict_result = "오디오 대기… | ambient | —"

        yamnet_path = self.model_dir / "yamnet.onnx"
        if not yamnet_path.is_file():
            raise FileNotFoundError(yamnet_path)
        # Session cwd/dir: onnxruntime resolves external data relative to model path
        so = ort.SessionOptions()
        so.intra_op_num_threads = 1
        so.inter_op_num_threads = 1
        self._yamnet = ort.InferenceSession(str(yamnet_path), sess_options=so, providers=["CPUExecutionProvider"])
        self._yamnet_in = self._yamnet.get_inputs()[0].name
        self._yamnet_out = self._yamnet.get_outputs()[0].name

        self._class_names = self._load_class_names(self.model_dir / "yamnet_class_map.csv")

        self._booster = None
        self._booster_in = None
        self._booster_out = None
        booster_path = self.model_dir / "gunshot_booster.onnx"
        if booster_path.is_file():
            self._booster = ort.InferenceSession(
                str(booster_path), sess_options=so, providers=["CPUExecutionProvider"]
            )
            self._booster_in = self._booster.get_inputs()[0].name
            self._booster_out = self._booster.get_outputs()[0].name

        # Note: three_class_score_head.onnx exists but is not loaded in the ctor.

    @staticmethod
    def _load_class_names(path: Path) -> List[str]:
        names = [f"class_{i}" for i in range(521)]
        if not path.is_file():
            return names
        with path.open("r", encoding="utf-8", newline="") as f:
            reader = csv.reader(f)
            for parts in reader:
                if not parts:
                    continue
                try:
                    index = int(parts[0].strip().strip('"'))
                except ValueError:
                    continue
                if index < 0 or index >= 521:
                    continue
                name = parts[2].strip() if len(parts) >= 3 else parts[1].strip()
                if len(name) >= 2 and name[0] == '"' and name[-1] == '"':
                    name = name[1:-1].replace('""', '"')
                if name:
                    names[index] = name
        return names

    # ---------- ring / ingest (realtime path) ----------

    def reset_state(self) -> None:
        self._ring.fill(0)
        self._ring_head = 0
        self._ring_tail = 0
        self._ring_count = 0
        self._confirmed_coarse = "ambient"
        self._confirmed_display = ""
        self._confirmed_confidence = 0.0
        self._candidate_coarse = ""
        self._candidate_streak = 0
        self._last_predict_result = "오디오 대기… | ambient | —"

    def ingest_mono(self, mono: np.ndarray, capture_sample_rate: int = DEFAULT_CAPTURE_SAMPLE_RATE) -> None:
        mono = np.asarray(mono, dtype=np.float32).reshape(-1)
        if mono.size == 0:
            return
        if capture_sample_rate <= 0:
            capture_sample_rate = DEFAULT_CAPTURE_SAMPLE_RATE
        cap = max(DEFAULT_CAPTURE_SAMPLE_RATE * self.RING_SECONDS, capture_sample_rate * self.RING_SECONDS)

        for i in range(mono.size):
            self._ring[self._ring_tail] = mono[i]
            self._ring_tail = (self._ring_tail + 1) & (self.MAX_RING_SIZE - 1)
            if self._ring_count < self.MAX_RING_SIZE:
                self._ring_count += 1
            else:
                self._ring_head = (self._ring_head + 1) & (self.MAX_RING_SIZE - 1)

        while self._ring_count > cap:
            self._ring_head = (self._ring_head + 1) & (self.MAX_RING_SIZE - 1)
            self._ring_count -= 1

    def _copy_ring_tail_right_padded(self, count: int) -> np.ndarray:
        destination = np.zeros(count, dtype=np.float32)
        take = min(self._ring_count, count)
        dst_start = count - take
        if take > 0:
            ring_start = (self._ring_tail - take) & (self.MAX_RING_SIZE - 1)
            for i in range(take):
                destination[dst_start + i] = self._ring[(ring_start + i) & (self.MAX_RING_SIZE - 1)]
        return destination

    # ---------- core inference ----------

    def predict_from_mono_16k(
        self, mono_audio: np.ndarray, confidence_threshold: float = 0.25
    ) -> Tuple[InferenceResult, Dict[str, Any]]:
        """PredictFromMono16k. Returns result + diagnostics."""
        diag: Dict[str, Any] = {}
        t0 = time.perf_counter()

        log_mel = compute_log_mel_spectrogram(
            mono_audio, self._hann, self._mel, self._mel_starts, self._mel_ends
        )
        tensor = log_mel_to_tensor(log_mel)
        diag["log_mel_flat"] = log_mel
        diag["tensor"] = tensor
        diag["tensor_stats"] = {
            "shape": list(tensor.shape),
            "min": float(tensor.min()),
            "max": float(tensor.max()),
            "mean": float(tensor.mean()),
            "std": float(tensor.std()),
            "sha256_16": hashlib.sha256(tensor.tobytes()).hexdigest()[:16],
        }

        outputs = self._yamnet.run([self._yamnet_out], {self._yamnet_in: tensor})
        logits = np.asarray(outputs[0], dtype=np.float32).reshape(-1)
        infer_ms = (time.perf_counter() - t0) * 1000.0

        probs = self._softmax(logits)
        diag["logits"] = logits
        diag["probs"] = probs
        diag["softmax_sum"] = float(probs.sum())

        top_idx, top_probs = self._compute_top5(probs)
        max_index = int(top_idx[0])
        conf = float(top_probs[0])
        display = self._class_names[max_index]

        max_index, conf, display = self._prefer_danger_when_top_is_masked_by_game_mix(
            probs, max_index, conf, display
        )

        # Recompute top5 after possible remapping? We keep the original top5 arrays
        # PreferDanger only changes maxIndex/conf/display used later; Vote still uses original top5.
        coarse = self._vote_coarse_from_top5(top_idx, top_probs, 3)
        coarse_conf = conf
        danger_evidence = self._sum_coarse_probability_from_top5(top_idx, top_probs, 5, "danger")
        has_strong_danger_cue = self._has_strong_danger_cue_in_top5(top_idx, 5)
        has_critical_danger_cue = self._has_critical_danger_cue_in_top5(top_idx, 5)
        yamnet_coarse = coarse
        adopted_danger_from_booster = False
        gunshot_score = None
        booster_reason = "booster_unavailable"

        if self._booster is not None:
            gunshot_score = self._predict_gunshot_booster_score(probs)
            gunshot_evidence = self._sum_gunshot_probability_from_top5(top_idx, top_probs, 5)
            has_gunshot_cue = self._has_gunshot_cue_in_top5(top_idx, 5)

            block_booster = (
                yamnet_coarse == "speech"
                or self._is_speech_like_display(display)
                or self._is_silence_like_display(display)
                or conf < 0.12
            )

            adopt = False
            if block_booster:
                booster_reason = "blocked_speech_silence_or_low_conf"
            elif has_gunshot_cue:
                adopt = gunshot_score >= 0.20 and gunshot_evidence >= 0.05
                booster_reason = (
                    f"gunshot_cue score={gunshot_score:.4f} evidence={gunshot_evidence:.4f} adopt={adopt}"
                )
            elif self._is_game_mix_mask_display(display) or has_strong_danger_cue:
                adopt = gunshot_score >= 0.50 or (gunshot_score >= 0.40 and gunshot_evidence >= 0.04)
                booster_reason = (
                    f"game_mix_or_strong_danger score={gunshot_score:.4f} "
                    f"evidence={gunshot_evidence:.4f} adopt={adopt}"
                )
            else:
                adopt = gunshot_score >= 0.45 and gunshot_evidence >= 0.10
                booster_reason = (
                    f"default score={gunshot_score:.4f} evidence={gunshot_evidence:.4f} adopt={adopt}"
                )

            if adopt:
                adopted_danger_from_booster = True
                coarse = "danger"
                coarse_conf = max(coarse_conf, max(gunshot_score, gunshot_evidence))
                gun_idx, gun_prob = self._try_pick_best_gunshot_display(probs)
                if gun_idx >= 0:
                    max_index = gun_idx
                    display = self._class_names[gun_idx]
                    coarse_conf = max(coarse_conf, gun_prob)

        effective_threshold = confidence_threshold
        if coarse == "danger" and (has_strong_danger_cue or has_critical_danger_cue or adopted_danger_from_booster):
            effective_threshold = min(effective_threshold, 0.18 if adopted_danger_from_booster else 0.20)
        elif coarse == "speech":
            effective_threshold = max(effective_threshold, 0.25)

        ok = coarse_conf >= effective_threshold
        top_k = self._format_top5(top_idx, top_probs, 3)

        # coarse vote scores for reporting (top3)
        vote_scores = self._coarse_vote_scores(top_idx, top_probs, 3)

        result = InferenceResult(
            yamnet_class_index=max_index,
            yamnet_display_name=display,
            confidence=float(coarse_conf),
            coarse_class=coarse,
            meets_threshold=bool(ok),
            inference_time_ms=float(infer_ms),
            top_k_summary=top_k,
            adopted_danger_from_booster=bool(adopted_danger_from_booster),
        )

        diag["top5"] = [
            {"index": int(top_idx[i]), "name": self._class_names[int(top_idx[i])], "prob": float(top_probs[i])}
            for i in range(5)
            if top_idx[i] >= 0
        ]
        diag["vote_scores"] = vote_scores
        diag["yamnet_coarse_before_booster"] = yamnet_coarse
        diag["gunshot_score"] = None if gunshot_score is None else float(gunshot_score)
        diag["booster_accepted"] = bool(adopted_danger_from_booster)
        diag["booster_reason"] = booster_reason
        diag["effective_threshold"] = float(effective_threshold)
        diag["danger_evidence"] = float(danger_evidence)

        return result, diag

    def predict_sound_type(
        self, capture_sample_rate: int = DEFAULT_CAPTURE_SAMPLE_RATE, confidence_threshold: float = 0.25
    ) -> Tuple[str, Optional[FrameTrace], Dict[str, Any]]:
        """PredictSoundType (ring → resample → infer → hysteresis)."""
        resample_from = 48000 if capture_sample_rate == DEFAULT_CAPTURE_SAMPLE_RATE else capture_sample_rate
        n = capture_samples_for_one_yamnet_window(resample_from)
        min_ring = n
        if self._ring_count < min_ring:
            self._last_predict_result = "오디오 축적 중… | ambient | —"
            return self._last_predict_result, None, {"status": "accumulating", "ring_count": self._ring_count}

        capture_tail = self._copy_ring_tail_right_padded(n)
        mono16 = resample_mono_float_to_16k_custom(capture_tail, resample_from, REQUIRED_MONO_16K_SAMPLES)
        r, diag = self.predict_from_mono_16k(mono16, confidence_threshold)
        diag["mono16k"] = mono16
        diag["capture_tail_len"] = int(n)
        diag["capture_sample_rate"] = int(resample_from)

        if r.yamnet_class_index < 0:
            self._last_predict_result = "AI 에러"
            return self._last_predict_result, None, diag

        self._apply_coarse_hysteresis(r)

        use_booster_danger_preview = (
            self._confirmed_coarse != "danger"
            and r.adopted_danger_from_booster
            and r.coarse_class == "danger"
            and r.meets_threshold
        )
        display_for_ui = r.yamnet_display_name if use_booster_danger_preview else self._confirmed_display
        coarse_for_ui = "danger" if use_booster_danger_preview else self._confirmed_coarse
        confidence_for_ui = r.confidence if use_booster_danger_preview else self._confirmed_confidence

        translated = mapper.translate_to_korean(display_for_ui)
        if confidence_for_ui < confidence_threshold:
            result_text = f"{translated} | {coarse_for_ui} | {confidence_for_ui * 100.0:.1f}% (저신뢰)"
        else:
            result_text = f"{translated} | {coarse_for_ui} | {confidence_for_ui * 100.0:.1f}%"
        self._last_predict_result = result_text

        trace = FrameTrace(
            pre_hysteresis=r,
            confirmed_coarse=self._confirmed_coarse,
            confirmed_display=self._confirmed_display,
            confirmed_confidence=self._confirmed_confidence,
            ui_coarse=coarse_for_ui,
            ui_display=display_for_ui,
            ui_confidence=confidence_for_ui,
            use_booster_danger_preview=use_booster_danger_preview,
        )
        return result_text, trace, diag

    def classify_mono_capture_once(
        self,
        mono_capture: np.ndarray,
        capture_sample_rate: int,
        confidence_threshold: float = 0.25,
        apply_hysteresis: bool = True,
    ) -> Tuple[InferenceResult, Optional[FrameTrace], Dict[str, Any]]:
        """
        One-shot path matching realtime preprocess:
        mono@captureSR → linear resample to 15600@16k → PredictFromMono16k → optional hysteresis.
        """
        mono_capture = np.asarray(mono_capture, dtype=np.float32).reshape(-1)
        need = capture_samples_for_one_yamnet_window(capture_sample_rate)
        if mono_capture.size >= need:
            tail = mono_capture[-need:]
        else:
            tail = np.zeros(need, dtype=np.float32)
            if mono_capture.size > 0:
                tail[-mono_capture.size :] = mono_capture

        mono16 = resample_mono_float_to_16k_custom(tail, capture_sample_rate, REQUIRED_MONO_16K_SAMPLES)
        r, diag = self.predict_from_mono_16k(mono16, confidence_threshold)
        diag["mono16k"] = mono16
        diag["capture_sample_rate"] = int(capture_sample_rate)
        diag["capture_mono_len"] = int(mono_capture.size)

        trace = None
        if apply_hysteresis:
            self._apply_coarse_hysteresis(r)
            use_preview = (
                self._confirmed_coarse != "danger"
                and r.adopted_danger_from_booster
                and r.coarse_class == "danger"
                and r.meets_threshold
            )
            trace = FrameTrace(
                pre_hysteresis=r,
                confirmed_coarse=self._confirmed_coarse,
                confirmed_display=self._confirmed_display,
                confirmed_confidence=self._confirmed_confidence,
                ui_coarse="danger" if use_preview else self._confirmed_coarse,
                ui_display=r.yamnet_display_name if use_preview else self._confirmed_display,
                ui_confidence=r.confidence if use_preview else self._confirmed_confidence,
                use_booster_danger_preview=use_preview,
            )
        return r, trace, diag

    def classify_stream(
        self,
        mono_capture: np.ndarray,
        capture_sample_rate: int,
        step_ms: int = 250,
        confidence_threshold: float = 0.25,
    ) -> List[Dict[str, Any]]:
        """Simulate MainWindow 250ms predict loop over a capture-rate mono stream."""
        self.reset_state()
        mono_capture = np.asarray(mono_capture, dtype=np.float32).reshape(-1)
        step = max(1, int(capture_sample_rate * step_ms / 1000.0))
        frames_out: List[Dict[str, Any]] = []
        pos = 0
        while pos < mono_capture.size:
            end = min(pos + step, mono_capture.size)
            self.ingest_mono(mono_capture[pos:end], capture_sample_rate)
            text, trace, diag = self.predict_sound_type(capture_sample_rate, confidence_threshold)
            frames_out.append(
                {
                    "pos": pos,
                    "text": text,
                    "trace": trace,
                    "diag_keys": list(diag.keys()),
                    "final": None
                    if trace is None
                    else {
                        "pre": asdict(trace.pre_hysteresis),
                        "post_coarse": trace.confirmed_coarse,
                        "ui_coarse": trace.ui_coarse,
                        "ui_confidence": trace.ui_confidence,
                    },
                }
            )
            pos = end
        return frames_out

    # ---------- hysteresis ----------

    def _apply_coarse_hysteresis(self, r: InferenceResult) -> None:
        if not r.meets_threshold:
            return

        new_coarse = r.coarse_class
        if new_coarse == self._confirmed_coarse:
            self._candidate_streak = 0
            self._candidate_coarse = ""
            self._confirmed_display = r.yamnet_display_name
            self._confirmed_confidence = r.confidence
            return

        if new_coarse == "danger" and (
            r.confidence >= self.DANGER_IMMEDIATE_SWITCH_CONFIDENCE or self._is_critical_danger_event(r)
        ):
            self._confirmed_coarse = new_coarse
            self._confirmed_display = r.yamnet_display_name
            self._confirmed_confidence = r.confidence
            self._candidate_streak = 0
            self._candidate_coarse = ""
            return

        if new_coarse == self._candidate_coarse:
            self._candidate_streak += 1
        else:
            self._candidate_coarse = new_coarse
            self._candidate_streak = 1

        required = (
            self.DANGER_HYSTERESIS_THRESHOLD if new_coarse == "danger" else self.COARSE_HYSTERESIS_THRESHOLD
        )
        if (
            self._confirmed_coarse == "danger"
            and new_coarse != "danger"
            and not r.adopted_danger_from_booster
            and r.confidence >= self.DANGER_EXIT_RELAXED_CONFIDENCE
        ):
            required = 1

        if self._candidate_streak >= required:
            self._confirmed_coarse = new_coarse
            self._confirmed_display = r.yamnet_display_name
            self._confirmed_confidence = r.confidence
            self._candidate_streak = 0
            self._candidate_coarse = ""

    # ---------- helpers (ported) ----------

    @staticmethod
    def _softmax(logits: np.ndarray) -> np.ndarray:
        if logits.size == 0:
            return logits
        m = float(np.max(logits))
        ex = np.exp(logits.astype(np.float64) - m)
        s = float(ex.sum())
        if s <= 0 or math.isnan(s):
            return np.full(logits.shape, 1.0 / logits.size, dtype=np.float32)
        return (ex / s).astype(np.float32)

    def _compute_top5(self, probs: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
        out_indices = np.full(5, -1, dtype=np.int32)
        out_probs = np.full(5, -1.0, dtype=np.float32)
        n = min(probs.size, len(self._class_names))
        for i in range(n):
            p = float(probs[i])
            for j in range(5):
                if p > float(out_probs[j]):
                    for k in range(4, j, -1):
                        out_probs[k] = out_probs[k - 1]
                        out_indices[k] = out_indices[k - 1]
                    out_probs[j] = p
                    out_indices[j] = i
                    break
        return out_indices, out_probs

    def _vote_coarse_from_top5(self, top_indices, top_probs, k: int) -> str:
        scores = self._coarse_vote_scores(top_indices, top_probs, k)
        if scores["danger"] >= scores["speech"] and scores["danger"] >= scores["ambient"]:
            return "danger"
        if scores["speech"] >= scores["ambient"]:
            return "speech"
        return "ambient"

    def _coarse_vote_scores(self, top_indices, top_probs, k: int) -> Dict[str, float]:
        danger = speech = ambient = 0.0
        limit = min(5, k)
        for idx in range(limit):
            i = int(top_indices[idx])
            if i < 0:
                continue
            c = mapper.map_display_name_to_coarse(self._class_names[i])
            p = float(top_probs[idx])
            if c == "danger":
                danger += p
            elif c == "speech":
                speech += p
            else:
                ambient += p
        return {"danger": danger, "speech": speech, "ambient": ambient}

    def _sum_coarse_probability_from_top5(self, top_indices, top_probs, k: int, target: str) -> float:
        s = 0.0
        limit = min(5, k)
        for idx in range(limit):
            i = int(top_indices[idx])
            if i < 0:
                continue
            if mapper.map_display_name_to_coarse(self._class_names[i]) == target:
                s += float(top_probs[idx])
        return s

    def _sum_gunshot_probability_from_top5(self, top_indices, top_probs, k: int) -> float:
        s = 0.0
        for idx in range(min(5, k)):
            i = int(top_indices[idx])
            if i < 0:
                continue
            if self._is_gunshot_keyword(self._class_names[i]):
                s += float(top_probs[idx])
        return s

    def _has_gunshot_cue_in_top5(self, top_indices, k: int) -> bool:
        for idx in range(min(5, k)):
            i = int(top_indices[idx])
            if i >= 0 and self._is_gunshot_keyword(self._class_names[i]):
                return True
        return False

    def _has_strong_danger_cue_in_top5(self, top_indices, k: int) -> bool:
        for idx in range(min(5, k)):
            i = int(top_indices[idx])
            if i >= 0 and self._is_strong_danger_keyword(self._class_names[i]):
                return True
        return False

    def _has_critical_danger_cue_in_top5(self, top_indices, k: int) -> bool:
        for idx in range(min(5, k)):
            i = int(top_indices[idx])
            if i >= 0 and self._is_critical_danger_keyword(self._class_names[i]):
                return True
        return False

    @staticmethod
    def _is_gunshot_keyword(name: str) -> bool:
        if not name:
            return False
        s = name.lower()
        return any(
            k in s
            for k in ("gunshot", "gunfire", "machine gun", "artillery", "fusillade", "cap gun")
        )

    @staticmethod
    def _is_speech_like_display(display: str) -> bool:
        if not display:
            return False
        s = display.lower()
        return any(
            k in s
            for k in (
                "speech",
                "conversation",
                "narration",
                "speaking",
                "babbling",
                "whisper",
                "singing",
                "choir",
                "laughter",
                "crying",
                "sobbing",
                "shout",
            )
        )

    @staticmethod
    def _is_silence_like_display(display: str) -> bool:
        if not display:
            return False
        s = display.lower()
        return "silence" in s or "quiet" in s or "background noise" in s

    def _is_game_mix_mask_display(self, display: str) -> bool:
        if not display:
            return False
        if self._is_speech_like_display(display) or self._is_silence_like_display(display):
            return False
        if mapper.is_generic_sound_effect_label(display):
            return True
        s = display.lower()
        return "music" in s or "video game" in s

    def _try_pick_best_gunshot_display(self, probs: np.ndarray) -> Tuple[int, float]:
        best_i, best_p = -1, 0.0
        n = min(probs.size, len(self._class_names))
        for i in range(n):
            if not self._is_gunshot_keyword(self._class_names[i]):
                continue
            if float(probs[i]) > best_p:
                best_p = float(probs[i])
                best_i = i
        return best_i, best_p

    def _is_strong_danger_keyword(self, name: str) -> bool:
        if not name:
            return False
        if self._is_gunshot_keyword(name):
            return True
        s = name.lower()
        return any(k in s for k in ("explosion", "fireworks", "firecracker", "siren", "alarm"))

    def _is_critical_danger_keyword(self, name: str) -> bool:
        if not name:
            return False
        if self._is_gunshot_keyword(name):
            return True
        s = name.lower()
        return any(k in s for k in ("explosion", "fireworks", "firecracker"))

    def _is_critical_danger_event(self, r: InferenceResult) -> bool:
        display = r.yamnet_display_name or ""
        top = r.top_k_summary or ""
        return self._is_critical_danger_keyword(display) or self._is_critical_danger_keyword(top)

    def _format_top5(self, top_indices, top_probs, k: int) -> str:
        parts = []
        for idx in range(min(5, k)):
            i = int(top_indices[idx])
            if i < 0:
                continue
            parts.append(f"{self._class_names[i]} {float(top_probs[idx]) * 100.0:.1f}%")
        return " > ".join(parts)

    def _prefer_danger_when_top_is_masked_by_game_mix(
        self, probs: np.ndarray, max_index: int, conf: float, display: str
    ) -> Tuple[int, float, str]:
        if max_index < 0 or max_index >= len(self._class_names) or probs.size != len(self._class_names):
            return max_index, conf, display
        if not self._is_game_mix_mask_display(display):
            return max_index, conf, display

        best_danger_idx = -1
        best_danger_prob = 0.0
        n = min(probs.size, len(self._class_names))
        for i in range(n):
            if mapper.map_display_name_to_coarse(self._class_names[i]) != "danger":
                continue
            if float(probs[i]) > best_danger_prob:
                best_danger_prob = float(probs[i])
                best_danger_idx = i
        if best_danger_idx < 0:
            return max_index, conf, display

        top_prob = float(probs[max_index])
        bar = max(0.06, top_prob * 0.22)
        if best_danger_prob < bar:
            return max_index, conf, display
        return best_danger_idx, best_danger_prob, self._class_names[best_danger_idx]

    def _predict_gunshot_booster_score(self, probs: np.ndarray) -> float:
        assert self._booster is not None
        x = np.zeros((1, 521), dtype=np.float32)
        n = min(521, probs.size)
        x[0, :n] = probs[:n]
        out = self._booster.run([self._booster_out], {self._booster_in: x})[0]
        return float(np.asarray(out).reshape(-1)[0])

    def model_info(self) -> Dict[str, Any]:
        yin = self._yamnet.get_inputs()[0]
        yout = self._yamnet.get_outputs()[0]
        info = {
            "yamnet": {
                "path": str(self.model_dir / "yamnet.onnx"),
                "input": {"name": yin.name, "shape": yin.shape, "type": yin.type},
                "output": {"name": yout.name, "shape": yout.shape, "type": yout.type},
                "external_data": (self.model_dir / "yamnet.data").is_file(),
                "class_map_count": len(self._class_names),
            },
            "booster": None,
        }
        if self._booster is not None:
            bin_ = self._booster.get_inputs()[0]
            bout = self._booster.get_outputs()[0]
            info["booster"] = {
                "path": str(self.model_dir / "gunshot_booster.onnx"),
                "input": {"name": bin_.name, "shape": bin_.shape, "type": bin_.type},
                "output": {"name": bout.name, "shape": bout.shape, "type": bout.type},
            }
        return info
