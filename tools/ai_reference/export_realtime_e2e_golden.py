#!/usr/bin/env python3
"""Regenerate semantic E2E fixtures only from provenance-backed audio sources.

The committed gunshot/alarm E2E binaries are legacy linear-round-trip fixtures.
They are deliberately never used as an input by this generator: without their
original WAV files they cannot be regenerated as semantic FIR fixtures.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np

from preprocess import (
    REQUIRED_MONO_16K_SAMPLES,
    capture_samples_for_one_yamnet_window,
    compute_log_mel_spectrogram,
)
from wav_io import load_wav_as_capture_mono

REPO = Path(__file__).resolve().parents[2]
OUT_TEST = REPO / "app" / "src" / "test" / "resources" / "ai_reference"
OUT_ANDROID = REPO / "app" / "src" / "androidTest" / "assets" / "ai_reference"
MODEL_DIR = Path("app/src/main/assets/ai")

CAPTURE_SR = 44100
CAPTURE_RESAMPLE_TAPS = 192
CAPTURE_RESAMPLE_CUTOFF_HZ = 7800.0
CAPTURE_RESAMPLE_KAISER_BETA = 12.0
EXPECTED_SEMANTICS = {
    "silence": ("ambient", False, "ambient"),
    "gunshot": ("ambient", True, "danger"),
    "alarm": ("ambient", False, "ambient"),
}
SOURCE_WAVS = {
    "gunshot": REPO / "data" / "preprocessed" / "Gunshot, gunfire_short-explosion-1694.wav",
    "alarm": REPO / "data" / "preprocessed" / "Alarm_classic-alarm-995.wav",
}


def write_f32(path: Path, arr: np.ndarray) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(np.asarray(arr, dtype=np.float32).reshape(-1).astype("<f4").tobytes())


def source_missing_error(names: tuple[str, ...]) -> RuntimeError | None:
    missing = [str(SOURCE_WAVS[name]) for name in names if name in SOURCE_WAVS and not SOURCE_WAVS[name].is_file()]
    if not missing:
        return None
    return RuntimeError(
        "Refusing to overwrite semantic E2E goldens: provenance-backed source WAV(s) are missing:\n"
        + "\n".join(f"  - {path}" for path in missing)
        + "\nThe existing gunshot/alarm E2E binaries are legacy linear-round-trip fixtures, "
        "not FIR semantic sources. Add the original WAV with source/license/checksum metadata first."
    )


def band_limited_capture_resample(source: np.ndarray, source_rate: int, dest_length: int) -> np.ndarray:
    """Deterministic 192-tap Kaiser-windowed sinc conversion to 44.1k capture PCM."""
    source = np.asarray(source, dtype=np.float32).reshape(-1)
    destination = np.zeros(dest_length, dtype=np.float32)
    if source.size == 0:
        return destination
    if source_rate == CAPTURE_SR:
        destination[: min(source.size, dest_length)] = source[:dest_length]
        return destination

    half_taps = CAPTURE_RESAMPLE_TAPS // 2
    offsets = np.arange(-half_taps + 1, half_taps + 1, dtype=np.int64)
    normalized_cutoff = min(0.999, 2.0 * CAPTURE_RESAMPLE_CUTOFF_HZ / source_rate)
    window_denominator = np.i0(CAPTURE_RESAMPLE_KAISER_BETA)
    for start in range(0, dest_length, 256):
        stop = min(dest_length, start + 256)
        positions = np.arange(start, stop, dtype=np.float64) * source_rate / CAPTURE_SR
        centers = np.floor(positions).astype(np.int64)
        indices = centers[:, None] + offsets[None, :]
        time = indices.astype(np.float64) - positions[:, None]
        radius = np.minimum(1.0, np.abs(time) / half_taps)
        window = np.i0(CAPTURE_RESAMPLE_KAISER_BETA * np.sqrt(1.0 - radius * radius)) / window_denominator
        weights = normalized_cutoff * np.sinc(normalized_cutoff * time) * window
        weights /= weights.sum(axis=1, keepdims=True)
        valid = (indices >= 0) & (indices < source.size)
        samples = source[np.clip(indices, 0, source.size - 1)]
        destination[start:stop] = np.sum(weights * np.where(valid, samples, 0.0), axis=1, dtype=np.float64)
    return destination


def source_capture(name: str, capture_length: int) -> tuple[np.ndarray, dict[str, object]]:
    if name == "silence":
        return np.zeros(capture_length, dtype=np.float32), {
            "type": "procedural_silence", "sample_rate": CAPTURE_SR, "sha256": None,
        }
    path = SOURCE_WAVS[name]
    mono, sample_rate, channels = load_wav_as_capture_mono(path)
    return band_limited_capture_resample(mono, sample_rate, capture_length), {
        "type": "wav", "path": str(path.relative_to(REPO)), "sample_rate": sample_rate,
        "channels": channels, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "capture_resampler": {"taps": CAPTURE_RESAMPLE_TAPS, "window": "Kaiser", "beta": CAPTURE_RESAMPLE_KAISER_BETA, "cutoff_hz": CAPTURE_RESAMPLE_CUTOFF_HZ},
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixtures", nargs="+", choices=("silence", "gunshot", "alarm"), default=("silence", "gunshot", "alarm"))
    names = tuple(parser.parse_args().fixtures)
    missing = source_missing_error(names)
    if missing is not None:
        raise missing

    from classifier import ReferenceClassifier

    capture_length = capture_samples_for_one_yamnet_window(CAPTURE_SR)
    generated: dict[str, tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, dict[str, object]]] = {}
    for name in names:
        mono_capture, provenance = source_capture(name, capture_length)
        classifier = ReferenceClassifier(MODEL_DIR)
        classifier.reset_state()
        result, trace, diag = classifier.classify_mono_capture_once(mono_capture, CAPTURE_SR, confidence_threshold=0.25, apply_hysteresis=True)
        expected_pre, expected_boost, expected_ui = EXPECTED_SEMANTICS[name]
        assert diag["yamnet_coarse_before_booster"] == expected_pre, diag
        assert bool(diag["booster_accepted"]) == expected_boost, diag
        assert (trace.ui_coarse if trace else result.coarse_class) == expected_ui, diag

        mono16 = np.asarray(diag["mono16k"], dtype=np.float32)
        logmel = compute_log_mel_spectrogram(mono16)
        probs = np.asarray(diag["probs"], dtype=np.float32)
        stereo = np.empty(capture_length * 2, dtype=np.float32)
        stereo[0::2] = mono_capture
        stereo[1::2] = mono_capture
        generated[name] = (stereo, mono16, logmel, probs, {
            "source": provenance, "pre_booster_coarse": diag["yamnet_coarse_before_booster"],
            "gunshot_score": float(diag["gunshot_score"]), "booster_accepted": bool(diag["booster_accepted"]),
            "ui_coarse": trace.ui_coarse if trace else result.coarse_class,
            "ui_display": trace.ui_display if trace else result.yamnet_display_name,
        })

    # Do not write anything until every source and semantic assertion has passed.
    for out_dir in (OUT_TEST, OUT_ANDROID):
        for name, (stereo, mono16, logmel, probs, _meta) in generated.items():
            write_f32(out_dir / f"e2e_{name}_stereo44100.bin", stereo)
            write_f32(out_dir / f"e2e_{name}_mono16k.bin", mono16)
            write_f32(out_dir / f"e2e_{name}_logmel.bin", logmel)
            write_f32(out_dir / f"e2e_{name}_probs.bin", probs)
        meta_path = out_dir / "yamnet_e2e_meta.json"
        existing = json.loads(meta_path.read_text(encoding="utf-8")) if meta_path.is_file() else {}
        cases = dict(existing.get("cases", {}))
        cases.update({name: values[-1] for name, values in generated.items()})
        (out_dir / "yamnet_e2e_meta.json").write_text(
            json.dumps(
                {
                    "capture_sample_rate": CAPTURE_SR,
                    "required_mono_16k": REQUIRED_MONO_16K_SAMPLES,
                    "cases": cases,
                },
                indent=2,
            ) + "\n",
            encoding="utf-8",
        )


if __name__ == "__main__":
    main()
