#!/usr/bin/env python3
"""Generate compact, source-independent FIR parity goldens for JVM tests."""

from __future__ import annotations

import json
import math
from pathlib import Path

import numpy as np

from preprocess import resample_mono_float_to_16k_custom

REPO = Path(__file__).resolve().parents[2]
OUT = REPO / "app" / "src" / "test" / "resources" / "ai_reference"
DESTINATION_LENGTH = 1024


def deterministic_signal(sample_rate: int, length: int) -> np.ndarray:
    """LCG noise + chirp + impulse; independent of external audio assets."""
    state = (0x6D2B79F5 ^ sample_rate) & 0xFFFFFFFF
    values = np.empty(length, dtype=np.float32)
    duration = max(1.0, length / sample_rate)
    for index in range(length):
        state = (1664525 * state + 1013904223) & 0xFFFFFFFF
        noise = (state / 4294967296.0) * 2.0 - 1.0
        seconds = index / sample_rate
        phase = 2.0 * math.pi * (300.0 * seconds + 0.5 * (7200.0 / duration) * seconds * seconds)
        values[index] = np.float32(0.12 * noise + 0.18 * math.sin(phase))
    values[length // 3] += np.float32(0.5)
    return values


def write_f32(path: Path, values: np.ndarray) -> None:
    path.write_bytes(np.asarray(values, dtype="<f4").tobytes())


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    cases = {}
    for sample_rate in (44100, 48000):
        source_length = math.ceil(DESTINATION_LENGTH * sample_rate / 16000.0) + 96
        source = deterministic_signal(sample_rate, source_length)
        output = resample_mono_float_to_16k_custom(source, sample_rate, DESTINATION_LENGTH)
        capture_name = f"resample_parity_{sample_rate}_capture.bin"
        output_name = f"resample_parity_{sample_rate}_mono16k.bin"
        write_f32(OUT / capture_name, source)
        write_f32(OUT / output_name, output)
        cases[str(sample_rate)] = {"capture": capture_name, "mono16k": output_name, "source_samples": source_length, "output_samples": DESTINATION_LENGTH}
    (OUT / "resample_parity_meta.json").write_text(
        json.dumps({"signal": "LCG seeded noise + 300-7500Hz chirp + impulse", "cases": cases}, indent=2) + "\n", encoding="utf-8"
    )


if __name__ == "__main__":
    main()
