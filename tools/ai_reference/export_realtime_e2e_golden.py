#!/usr/bin/env python3
"""
Export realtime E2E fixtures: 44.1kHz stereo interleaved PCM → Android realtime path goldens.

Capture mono@44100 is constructed from the validated mono16k window so that
ResampleMonoFloatTo16kCustom round-trips bit-exactly (place + fill),
preserving silence/gunshot/alarm reference semantics through the 44.1k path.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from classifier import ReferenceClassifier
from preprocess import (
    REQUIRED_MONO_16K_SAMPLES,
    capture_samples_for_one_yamnet_window,
    compute_log_mel_spectrogram,
    resample_mono_float_to_16k_custom,
)
from wav_io import load_wav_as_capture_mono

REPO = Path(__file__).resolve().parents[2]
ROOT = Path(__file__).resolve().parent
OUT_TEST = REPO / "app" / "src" / "test" / "resources" / "ai_reference"
OUT_ANDROID = REPO / "app" / "src" / "androidTest" / "assets" / "ai_reference"
MODEL_DIR = Path(
    "app/src/main/assets/ai"
)

CAPTURE_SR = 44100

SAMPLE_WAVS = {
    "silence": ROOT / ".out" / "silence_1s_16k.wav",
    "gunshot": Path(
        "data/preprocessed/"
        "Gunshot, gunfire_short-explosion-1694.wav"
    ),
    "alarm": Path(
        "data/preprocessed/"
        "Alarm_classic-alarm-995.wav"
    ),
}


def write_f32(path: Path, arr: np.ndarray) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(np.asarray(arr, dtype=np.float32).reshape(-1).astype("<f4").tobytes())


def mono16k_to_capture44100(mono16: np.ndarray, n_dst: int) -> np.ndarray:
    """
    Place each 16k sample at round(i * 44100/16000) and nearest-fill gaps so that
    resample_mono_float_to_16k_custom(..., 44100) recovers mono16 exactly.
    """
    mono16 = np.asarray(mono16, dtype=np.float32).reshape(-1)
    assert mono16.size == REQUIRED_MONO_16K_SAMPLES
    out = np.full(n_dst, np.nan, dtype=np.float32)
    for i in range(mono16.size):
        j = int(round(i * CAPTURE_SR / 16000.0))
        if 0 <= j < n_dst:
            out[j] = mono16[i]
    idx = np.where(~np.isnan(out))[0]
    assert idx.size > 0
    filled = out.copy()
    for j in range(n_dst):
        if np.isnan(filled[j]):
            k = idx[np.argmin(np.abs(idx - j))]
            filled[j] = out[k]
    # Verify round-trip
    rt = resample_mono_float_to_16k_custom(filled, CAPTURE_SR, REQUIRED_MONO_16K_SAMPLES)
    err = float(np.max(np.abs(rt - mono16)))
    assert err == 0.0, f"round-trip failed maxAbs={err}"
    return filled


def mono_to_stereo_interleaved(mono: np.ndarray) -> np.ndarray:
    mono = np.asarray(mono, dtype=np.float32).reshape(-1)
    stereo = np.empty(mono.size * 2, dtype=np.float32)
    stereo[0::2] = mono
    stereo[1::2] = mono
    return stereo


def main() -> None:
    cases = {}
    need = capture_samples_for_one_yamnet_window(CAPTURE_SR)

    for name, wav in SAMPLE_WAVS.items():
        mono_src, file_sr, _ch = load_wav_as_capture_mono(wav)
        clf0 = ReferenceClassifier(MODEL_DIR)
        clf0.reset_state()
        _r0, _t0, diag0 = clf0.classify_mono_capture_once(
            mono_src, file_sr, confidence_threshold=0.25, apply_hysteresis=True
        )
        mono16k = np.asarray(diag0["mono16k"], dtype=np.float32)
        mono441 = mono16k_to_capture44100(mono16k, need)
        stereo = mono_to_stereo_interleaved(mono441)

        clf = ReferenceClassifier(MODEL_DIR)
        clf.reset_state()
        result, trace, diag = clf.classify_mono_capture_once(
            mono441, CAPTURE_SR, confidence_threshold=0.25, apply_hysteresis=True
        )

        mono16_check = np.asarray(diag["mono16k"], dtype=np.float32)
        assert float(np.max(np.abs(mono16_check - mono16k))) == 0.0

        logmel = compute_log_mel_spectrogram(mono16_check)
        probs = np.asarray(diag["probs"], dtype=np.float32)

        case = {
            "name": name,
            "capture_sample_rate": CAPTURE_SR,
            "channels": 2,
            "stereo_float_count": int(stereo.size),
            "mono_capture_samples": int(mono441.size),
            "capture_window_samples": int(need),
            "mono16k_samples": int(mono16_check.size),
            "pre_booster_coarse": diag.get("yamnet_coarse_before_booster"),
            "gunshot_score": float(diag["gunshot_score"])
            if diag.get("gunshot_score") is not None
            else None,
            "booster_accepted": bool(diag.get("booster_accepted")),
            "post_booster_coarse": result.coarse_class,
            "post_booster_display": result.yamnet_display_name,
            "post_booster_confidence": float(result.confidence),
            "meets_threshold": bool(result.meets_threshold),
            "ui_coarse": trace.ui_coarse if trace else result.coarse_class,
            "ui_display": trace.ui_display if trace else result.yamnet_display_name,
            "ui_confidence": float(trace.ui_confidence) if trace else float(result.confidence),
            "confirmed_coarse": trace.confirmed_coarse if trace else None,
            "top5": diag.get("top5"),
            "yamnet_argmax": int(np.argmax(probs)),
            "fixture_note": "mono44100 place+fill from validated mono16k (exact resample round-trip)",
        }

        for out_dir in (OUT_TEST, OUT_ANDROID):
            write_f32(out_dir / f"e2e_{name}_stereo44100.bin", stereo)
            write_f32(out_dir / f"e2e_{name}_mono16k.bin", mono16_check)
            write_f32(out_dir / f"e2e_{name}_logmel.bin", logmel.astype(np.float32).reshape(-1))
            write_f32(out_dir / f"e2e_{name}_probs.bin", probs)

        cases[name] = case
        print(
            f"{name}: stereo={stereo.size} "
            f"pre={case['pre_booster_coarse']} boost={case['booster_accepted']} "
            f"ui={case['ui_coarse']}/{case['ui_display']} "
            f"score={case['gunshot_score']}"
        )

    meta = {
        "description": "Realtime E2E: 44.1k stereo → CP1..CP6 vs Python classify_mono_capture_once",
        "capture_sample_rate": CAPTURE_SR,
        "required_mono_16k": REQUIRED_MONO_16K_SAMPLES,
        "capture_window_samples": need,
        "cases": cases,
    }
    for out_dir in (OUT_TEST, OUT_ANDROID):
        out_dir.mkdir(parents=True, exist_ok=True)
        (out_dir / "yamnet_e2e_meta.json").write_text(
            json.dumps(meta, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
        )

    print(f"Wrote E2E fixtures to {OUT_TEST} and {OUT_ANDROID}")


if __name__ == "__main__":
    main()
