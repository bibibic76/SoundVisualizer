#!/usr/bin/env python3
"""Export YAMNet CP3 golden (logits + Softmax probs) from yamnet.onnx."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort

from preprocess import log_mel_to_tensor

REPO = Path(__file__).resolve().parents[2]
FIX = REPO / "app" / "src" / "test" / "resources" / "ai_reference"
ANDROID_TEST_ASSETS = REPO / "app" / "src" / "androidTest" / "assets" / "ai_reference"
DEFAULT_MODEL_DIR = Path(
    "app/src/main/assets/ai"
)


def write_f32(path: Path, arr: np.ndarray) -> dict:
    arr = np.asarray(arr, dtype=np.float32).reshape(-1)
    path.write_bytes(arr.astype("<f4").tobytes())
    return {
        "count": int(arr.size),
        "min": float(arr.min()),
        "max": float(arr.max()),
        "mean": float(arr.mean()),
        "sum": float(arr.sum()),
        "argmax": int(arr.argmax()),
        "sha256": hashlib.sha256(arr.astype("<f4").tobytes()).hexdigest(),
    }


def softmax(logits: np.ndarray) -> np.ndarray:
    m = float(logits.max())
    ex = np.exp(logits.astype(np.float64) - m)
    return (ex / ex.sum()).astype(np.float32)


def main() -> None:
    model_dir = DEFAULT_MODEL_DIR
    logmel = np.frombuffer((FIX / "logmel_golden.bin").read_bytes(), dtype="<f4")
    tensor = log_mel_to_tensor(logmel)
    sess = ort.InferenceSession(str(model_dir / "yamnet.onnx"), providers=["CPUExecutionProvider"])
    logits = np.asarray(sess.run(None, {"audio": tensor})[0], dtype=np.float32).reshape(-1)
    probs = softmax(logits)

    FIX.mkdir(parents=True, exist_ok=True)
    ANDROID_TEST_ASSETS.mkdir(parents=True, exist_ok=True)

    meta = {
        "model": str(model_dir / "yamnet.onnx"),
        "logits": write_f32(FIX / "yamnet_logits_golden.bin", logits),
        "probs": write_f32(FIX / "yamnet_probs_golden.bin", probs),
        "top5": [
            {"index": int(i), "prob": float(probs[i])}
            for i in np.argsort(-probs)[:5]
        ],
    }
    (FIX / "yamnet_cp3_meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")

    for name in (
        "logmel_golden.bin",
        "yamnet_logits_golden.bin",
        "yamnet_probs_golden.bin",
        "yamnet_cp3_meta.json",
    ):
        src = FIX / name
        if src.is_file():
            (ANDROID_TEST_ASSETS / name).write_bytes(src.read_bytes())

    print(json.dumps(meta, indent=2))


if __name__ == "__main__":
    main()
