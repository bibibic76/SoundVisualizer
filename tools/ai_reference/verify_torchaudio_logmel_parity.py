#!/usr/bin/env python3
"""Compare the NumPy Qualcomm-source frontend with pinned upstream source."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from compare_logmel_frontends import (
    FRAMES,
    MELS,
    N,
    SR,
    _TORCH_MEL,
    qualcomm_source_log_mel,
)


def torch_audioset_log_mel(samples: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    import torch
    from torch_audioset.data.torch_input_processing import WaveformToInput

    fitted = np.zeros(N, dtype=np.float32)
    source = np.asarray(samples, dtype=np.float32).reshape(-1)[:N]
    fitted[: source.size] = source
    waveform = torch.from_numpy(fitted).reshape(1, -1)
    transform = WaveformToInput()
    patches, _ = transform.wavform_to_log_mel(waveform, SR)
    log_mel = patches[0, 0]
    mel = transform.mel_trans_ope.mel_scale.fb
    return log_mel.detach().numpy(), mel.detach().numpy()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--max-atol", type=float, default=3e-3)
    parser.add_argument("--mean-atol", type=float, default=5e-4)
    parser.add_argument("--mel-atol", type=float, default=1e-5)
    args = parser.parse_args()

    rng = np.random.default_rng(7)
    resources = args.repo / "app/src/test/resources/ai_reference"
    cases = {
        "silence": np.zeros(N, dtype=np.float32),
        "sine_1khz": (
            0.5 * np.sin(2 * np.pi * 1000 * np.arange(N) / SR)
        ).astype(np.float32),
        "white_noise": (0.2 * rng.standard_normal(N)).astype(np.float32),
        "e2e_gunshot": np.fromfile(
            resources / "e2e_gunshot_mono16k.bin",
            dtype="<f4",
        ),
        "e2e_alarm": np.fromfile(
            resources / "e2e_alarm_mono16k.bin",
            dtype="<f4",
        ),
    }

    failed = False
    for name, samples in cases.items():
        numpy_result = qualcomm_source_log_mel(samples)
        torch_result, torch_mel = torch_audioset_log_mel(samples)
        if torch_result.shape != (FRAMES, MELS):
            raise AssertionError(f"{name}: unexpected torchaudio shape {torch_result.shape}")
        max_abs = float(np.max(np.abs(numpy_result - torch_result)))
        mean_abs = float(np.mean(np.abs(numpy_result - torch_result)))
        mel_max_abs = float(
            np.max(np.abs(_TORCH_MEL.astype(np.float32) - torch_mel))
        )
        print(
            f"{name}: logmel max_abs={max_abs:.9g} mean_abs={mean_abs:.9g} "
            f"mel_max_abs={mel_max_abs:.9g}"
        )
        failed |= (
            max_abs > args.max_atol
            or mean_abs > args.mean_atol
            or mel_max_abs > args.mel_atol
        )

    if failed:
        print(
            "parity failed: "
            f"max_atol={args.max_atol} mean_atol={args.mean_atol} "
            f"mel_atol={args.mel_atol}",
            file=sys.stderr,
        )
        return 1
    print(
        "parity passed: "
        f"max_atol={args.max_atol} mean_atol={args.mean_atol} "
        f"mel_atol={args.mel_atol}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
