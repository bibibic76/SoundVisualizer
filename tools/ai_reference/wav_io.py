"""WAV I/O helpers for the reference harness (stdlib + numpy only)."""

from __future__ import annotations

import wave
from pathlib import Path
from typing import Tuple

import numpy as np

from preprocess import downmix_to_mono


def load_wav_float_interleaved(path: str | Path) -> Tuple[np.ndarray, int, int]:
    """
    Load WAV as interleaved float32 samples in [-1, 1], original sample rate & channels.
    Supports PCM16 / PCM32 / float32 WAV via stdlib wave.
    """
    path = Path(path)
    with wave.open(str(path), "rb") as wf:
        channels = wf.getnchannels()
        sample_rate = wf.getframerate()
        sampwidth = wf.getsampwidth()
        nframes = wf.getnframes()
        raw = wf.readframes(nframes)

    if sampwidth == 2:
        ints = np.frombuffer(raw, dtype="<i2")
        samples = ints.astype(np.float32) / 32768.0
    elif sampwidth == 4:
        # Could be PCM32 or IEEE float — inspect format tag via wave module is limited.
        # Try float first if values look like floats; else PCM32.
        as_f = np.frombuffer(raw, dtype="<f4")
        as_i = np.frombuffer(raw, dtype="<i4")
        # Heuristic: if abs max of float interpretation is reasonable (< 100), treat as float
        if np.isfinite(as_f).all() and float(np.max(np.abs(as_f))) <= 8.0:
            samples = as_f.astype(np.float32)
        else:
            samples = as_i.astype(np.float32) / 2147483648.0
    elif sampwidth == 3:
        # 24-bit PCM
        b = np.frombuffer(raw, dtype=np.uint8).reshape(-1, 3)
        # little-endian signed
        vals = (
            b[:, 0].astype(np.int32)
            | (b[:, 1].astype(np.int32) << 8)
            | (b[:, 2].astype(np.int32) << 16)
        )
        vals = np.where(vals >= 0x800000, vals - 0x1000000, vals)
        samples = vals.astype(np.float32) / 8388608.0
    elif sampwidth == 1:
        ints = np.frombuffer(raw, dtype=np.uint8).astype(np.int32) - 128
        samples = ints.astype(np.float32) / 128.0
    else:
        raise ValueError(f"Unsupported sampwidth={sampwidth} for {path}")

    # Truncate to full frames
    frames = samples.size // channels
    samples = samples[: frames * channels]
    return samples.astype(np.float32), int(sample_rate), int(channels)


def load_wav_as_capture_mono(path: str | Path) -> Tuple[np.ndarray, int, int]:
    """Load WAV → DownmixToMono at original sample rate."""
    interleaved, sr, ch = load_wav_float_interleaved(path)
    mono = downmix_to_mono(interleaved, ch)
    return mono, sr, ch


def write_silence_wav(path: str | Path, duration_s: float = 1.0, sample_rate: int = 16000) -> Path:
    """Synthetic silence for smoke tests (written under tools/ai_reference only)."""
    path = Path(path)
    n = int(duration_s * sample_rate)
    with wave.open(str(path), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(b"\x00\x00" * n)
    return path
