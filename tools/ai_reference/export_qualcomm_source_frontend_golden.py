#!/usr/bin/env python3
"""Export separate Kotlin parity fixtures for the pinned Qualcomm source frontend."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

import numpy as np

from compare_logmel_frontends import (
    QAI_HUB_MODELS_COMMIT,
    TORCH_AUDIOSET_COMMIT,
    qualcomm_source_log_mel,
)

REPO = Path(__file__).resolve().parents[2]
RESOURCES = REPO / "app" / "src" / "test" / "resources" / "ai_reference"


def read_f32(path: Path) -> np.ndarray:
    return np.fromfile(path, dtype="<f4")


def write_f32(path: Path, values: np.ndarray) -> dict:
    array = np.asarray(values, dtype=np.float32).reshape(-1)
    encoded = array.astype("<f4").tobytes()
    path.write_bytes(encoded)
    return {
        "path": path.name,
        "count": int(array.size),
        "min": float(array.min()),
        "max": float(array.max()),
        "mean": float(array.mean()),
        "std": float(array.std()),
        "sha256": hashlib.sha256(encoded).hexdigest(),
    }


def main() -> None:
    inputs = {
        "golden": RESOURCES / "mono16k_golden.bin",
        "silence": RESOURCES / "e2e_silence_mono16k.bin",
        "gunshot": RESOURCES / "e2e_gunshot_mono16k.bin",
        "alarm": RESOURCES / "e2e_alarm_mono16k.bin",
    }
    cases = {}
    for name, input_path in inputs.items():
        output_name = f"qualcomm_source_{name}_logmel.bin"
        cases[name] = {
            "input": input_path.name,
            "output": write_f32(
                RESOURCES / output_name,
                qualcomm_source_log_mel(read_f32(input_path)),
            ),
        }

    metadata = {
        "description": "Kotlin parity fixtures from the pinned Qualcomm-recipe source frontend NumPy port",
        "source_provenance": {
            "qai_hub_models_commit": QAI_HUB_MODELS_COMMIT,
            "torch_audioset_commit": TORCH_AUDIOSET_COMMIT,
            "upstream_parity_check": "tools/ai_reference/verify_torchaudio_logmel_parity.py",
        },
        "shape_logical": [1, 1, 96, 64],
        "layout": "time * 64 + mel",
        "cases": cases,
    }
    (RESOURCES / "qualcomm_source_frontend_meta.json").write_text(
        json.dumps(metadata, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(metadata, indent=2))


if __name__ == "__main__":
    main()
