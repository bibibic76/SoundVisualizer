#!/usr/bin/env python3
"""Numerically compare the NumPy official-style frontend with TensorFlow signals."""
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
    HOP,
    LOG_EPS,
    MELS,
    N,
    NFFT,
    SR,
    WIN,
    _TF_MEL,
    official_log_mel,
)


def tensorflow_log_mel(samples: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    import tensorflow as tf

    waveform = tf.convert_to_tensor(np.asarray(samples, dtype=np.float32).reshape(-1)[:N])
    waveform = tf.pad(waveform, [[0, N - tf.shape(waveform)[0]]])
    stft = tf.signal.stft(
        waveform,
        frame_length=WIN,
        frame_step=HOP,
        fft_length=NFFT,
        window_fn=tf.signal.hann_window,
        pad_end=False,
    )
    magnitude = tf.abs(stft)
    mel = tf.signal.linear_to_mel_weight_matrix(
        num_mel_bins=MELS,
        num_spectrogram_bins=NFFT // 2 + 1,
        sample_rate=SR,
        lower_edge_hertz=125.0,
        upper_edge_hertz=7500.0,
        dtype=tf.float32,
    )
    log_mel = tf.math.log(tf.matmul(magnitude, mel) + LOG_EPS)
    return log_mel.numpy(), mel.numpy()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--atol", type=float, default=2e-4)
    args = parser.parse_args()

    rng = np.random.default_rng(7)
    resources = args.repo / "app/src/test/resources/ai_reference"
    cases = {
        "silence": np.zeros(N, dtype=np.float32),
        "sine_1khz": (0.5 * np.sin(2 * np.pi * 1000 * np.arange(N) / SR)).astype(np.float32),
        "white_noise": (0.2 * rng.standard_normal(N)).astype(np.float32),
        "e2e_gunshot": np.fromfile(resources / "e2e_gunshot_mono16k.bin", dtype="<f4"),
        "e2e_alarm": np.fromfile(resources / "e2e_alarm_mono16k.bin", dtype="<f4"),
    }

    failed = False
    for name, samples in cases.items():
        numpy_result = official_log_mel(samples)
        tensorflow_result, tensorflow_mel = tensorflow_log_mel(samples)
        if tensorflow_result.shape != (FRAMES, MELS):
            raise AssertionError(f"{name}: unexpected TensorFlow shape {tensorflow_result.shape}")
        max_abs = float(np.max(np.abs(numpy_result - tensorflow_result)))
        mean_abs = float(np.mean(np.abs(numpy_result - tensorflow_result)))
        mel_max_abs = float(np.max(np.abs(_TF_MEL.astype(np.float32) - tensorflow_mel)))
        print(
            f"{name}: logmel max_abs={max_abs:.9g} mean_abs={mean_abs:.9g} "
            f"mel_max_abs={mel_max_abs:.9g}"
        )
        failed |= max_abs > args.atol or mel_max_abs > args.atol

    if failed:
        print(f"parity failed: tolerance={args.atol}", file=sys.stderr)
        return 1
    print(f"parity passed: tolerance={args.atol}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
