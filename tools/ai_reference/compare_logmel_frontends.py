"""Compare the current app log-mel frontend with the official YAMNet frontend."""
from __future__ import annotations

import argparse
import csv
import math
import sys
import wave
from pathlib import Path

import numpy as np
import onnxruntime as ort

SR = 16000
N = 15600
WIN, HOP, NFFT = 400, 160, 512
FRAMES, MELS = 96, 64
LOG_EPS = 0.001


def _hann_periodic(n: int = WIN) -> np.ndarray:
    return 0.5 - 0.5 * np.cos(2.0 * np.pi * np.arange(n) / n)


def _hz_to_mel_htk(f):
    return 1127.0 * np.log1p(np.asarray(f, dtype=np.float64) / 700.0)


def tf_linear_to_mel_weight_matrix(
    num_mel=MELS,
    num_bins=NFFT // 2 + 1,
    sr=SR,
    lo=125.0,
    hi=7500.0,
) -> np.ndarray:
    """Calculate the TensorFlow-style [linear bins, mel bins] weight matrix."""
    lin = np.linspace(0.0, sr / 2.0, num_bins)[1:]
    spec_mel = _hz_to_mel_htk(lin)[:, None]
    edges = np.linspace(_hz_to_mel_htk(lo), _hz_to_mel_htk(hi), num_mel + 2)
    lower, center, upper = edges[:-2], edges[1:-1], edges[2:]
    weights = np.maximum(
        0.0,
        np.minimum(
            (spec_mel - lower) / (center - lower),
            (upper - spec_mel) / (upper - center),
        ),
    )
    return np.pad(weights, [[1, 0], [0, 0]])


_TF_MEL = tf_linear_to_mel_weight_matrix()
_HANN = _hann_periodic()


def _fit(mono16k: np.ndarray) -> np.ndarray:
    fitted = np.zeros(N, dtype=np.float64)
    source = np.asarray(mono16k, dtype=np.float64).reshape(-1)[:N]
    fitted[: source.size] = source
    return fitted


def official_log_mel(mono16k: np.ndarray) -> np.ndarray:
    """Return the NumPy port of the official YAMNet log-mel frontend as [96, 64]."""
    fitted = _fit(mono16k)
    frames = np.stack(
        [fitted[t * HOP : t * HOP + WIN] * _HANN for t in range(FRAMES)]
    )
    magnitude = np.abs(np.fft.rfft(frames, n=NFFT))
    return np.log(magnitude @ _TF_MEL + LOG_EPS).astype(np.float32)


def current_log_mel(mono16k: np.ndarray, repo: Path) -> np.ndarray:
    """Return the repository's current reference frontend as [96, 64]."""
    sys.path.insert(0, str(repo / "tools"))
    from ai_reference import preprocess as pp

    return pp.compute_log_mel_spectrogram(
        _fit(mono16k).astype(np.float32)
    ).reshape(FRAMES, MELS)


class Yamnet:
    def __init__(self, repo: Path):
        assets = repo / "app/src/main/assets/ai"
        self.session = ort.InferenceSession(str(assets / "yamnet.onnx"))
        with open(assets / "yamnet_class_map.csv", encoding="utf-8") as file:
            self.names = [row["display_name"] for row in csv.DictReader(file)]

    def logits(self, log_mel: np.ndarray) -> np.ndarray:
        return self.session.run(
            None,
            {"audio": log_mel.reshape(1, 1, FRAMES, MELS)},
        )[0].reshape(-1).astype(np.float32)

    @staticmethod
    def softmax(logits: np.ndarray) -> np.ndarray:
        logits = np.asarray(logits, dtype=np.float64).reshape(-1)
        probabilities = np.exp(logits - logits.max())
        return probabilities / probabilities.sum()

    def probs(self, log_mel: np.ndarray) -> np.ndarray:
        return self.softmax(self.logits(log_mel))

    def top(self, log_mel: np.ndarray, k: int = 3) -> str:
        probabilities = self.probs(log_mel)
        return ", ".join(
            f"{self.names[i]} {probabilities[i]:.2f}"
            for i in np.argsort(-probabilities)[:k]
        )


def read_wav_mono16k(path: Path) -> np.ndarray:
    """Read 16-bit PCM WAV and linearly resample it to 16 kHz mono."""
    with wave.open(str(path)) as wav:
        channels = wav.getnchannels()
        rate = wav.getframerate()
        width = wav.getsampwidth()
        raw = wav.readframes(wav.getnframes())
    if width != 2:
        raise ValueError(f"{path}: only 16-bit PCM is supported (sampwidth={width})")
    samples = np.frombuffer(raw, dtype="<i2").astype(np.float64) / 32768.0
    samples = samples.reshape(-1, channels).mean(axis=1)
    if rate != SR:
        target_times = np.arange(0, len(samples) / rate, 1.0 / SR)
        samples = np.interp(target_times, np.arange(len(samples)) / rate, samples)
    return samples


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("wavs", nargs="*", type=Path)
    args = parser.parse_args()
    model = Yamnet(args.repo)

    cases: list[tuple[str, np.ndarray]] = []
    if args.wavs:
        cases = [(path.name, read_wav_mono16k(path)) for path in args.wavs]
    else:
        rng = np.random.default_rng(7)
        time = np.arange(N) / SR
        cases = [
            ("sine 1kHz", 0.5 * np.sin(2 * math.pi * 1000 * time)),
            ("white noise 0.05", 0.05 * rng.standard_normal(N)),
            ("white noise 0.8", 0.8 * rng.standard_normal(N)),
            (
                "burst 0.8",
                np.concatenate(
                    [
                        np.zeros(4000),
                        0.8
                        * rng.standard_normal(N - 4000)
                        * np.exp(-np.arange(N - 4000) / 600.0),
                    ]
                ),
            ),
        ]
        resources = args.repo / "app/src/test/resources/ai_reference"
        for name in ["e2e_gunshot_mono16k", "e2e_alarm_mono16k"]:
            cases.append((name, np.fromfile(resources / f"{name}.bin", dtype="<f4")))

    for name, samples in cases:
        current = current_log_mel(samples, args.repo)
        official = official_log_mel(samples)
        print(f"== {name}")
        print(
            f"  current  log-mel[{current.min():6.2f},{current.max():6.2f}] "
            f"| {model.top(current)}"
        )
        print(
            f"  official log-mel[{official.min():6.2f},{official.max():6.2f}] "
            f"| {model.top(official)}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
