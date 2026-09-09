#!/usr/bin/env python3
"""Regenerate Android JVM test golden bins from preprocess.py (read-only vs the ONNX models)."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import numpy as np

from preprocess import (
    REQUIRED_MONO_16K_SAMPLES,
    SAMPLE_RATE,
    compute_log_mel_spectrogram,
)

REPO = Path(__file__).resolve().parents[2]
OUT = REPO / "app" / "src" / "test" / "resources" / "ai_reference"


def main() -> None:
    n = REQUIRED_MONO_16K_SAMPLES
    t = np.arange(n, dtype=np.float64) / SAMPLE_RATE
    mono = (
        0.35 * np.sin(2 * np.pi * 440.0 * t)
        + 0.20 * np.sin(2 * np.pi * 880.0 * t)
        + 0.15 * np.sin(2 * np.pi * 220.0 * t)
        + 0.05 * np.sin(2 * np.pi * 3000.0 * t)
    ).astype(np.float32)
    state = 123456789
    dither = np.empty(n, dtype=np.float32)
    for i in range(n):
        state = (1664525 * state + 1013904223) & 0xFFFFFFFF
        dither[i] = ((state / 0xFFFFFFFF) - 0.5) * 1e-4
    mono = (mono + dither).astype(np.float32)
    logmel = compute_log_mel_spectrogram(mono)

    OUT.mkdir(parents=True, exist_ok=True)

    def write_f32(path: Path, arr: np.ndarray) -> dict:
        arr = np.asarray(arr, dtype=np.float32).reshape(-1)
        path.write_bytes(arr.astype("<f4").tobytes())
        return {
            "path": path.name,
            "count": int(arr.size),
            "min": float(arr.min()),
            "max": float(arr.max()),
            "mean": float(arr.mean()),
            "std": float(arr.std()),
            "sha256": hashlib.sha256(arr.astype("<f4").tobytes()).hexdigest(),
        }

    meta = {
        "description": "Golden fixtures from tools/ai_reference/preprocess.py",
        "sample_rate": SAMPLE_RATE,
        "mono": write_f32(OUT / "mono16k_golden.bin", mono),
        "logmel": write_f32(OUT / "logmel_golden.bin", logmel),
        "shape_logical": [1, 1, 96, 64],
        "layout": "time * 64 + mel",
    }
    (OUT / "meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    print(json.dumps(meta, indent=2))


if __name__ == "__main__":
    main()
