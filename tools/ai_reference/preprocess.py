"""Preprocessing: mono downmix / resample / log-mel."""

from __future__ import annotations

import math
from functools import lru_cache
from typing import Tuple

import numpy as np

SAMPLE_RATE = 16000
MEL_BINS = 64
TIME_FRAMES = 96
WINDOW_LENGTH = 400
HOP_LENGTH = 160
FFT_SIZE = 512
MEL_FMIN = 125.0
MEL_FMAX = 7500.0
LOG_EPS = 0.001

REQUIRED_MONO_16K_SAMPLES = WINDOW_LENGTH + HOP_LENGTH * (TIME_FRAMES - 1)  # 15600
DEFAULT_CAPTURE_SAMPLE_RATE = 48000

FIR_TAPS = 96
FIR_CUTOFF_HZ = 7800.0
FIR_KAISER_BETA = 8.6
_MAX_CACHED_PHASES = 1024


def create_hann_window(length: int) -> np.ndarray:
    """CreateHannWindow — uses length (not length-1) in cos."""
    window = np.zeros(length, dtype=np.float32)
    if length <= 1:
        return window
    for n in range(length):
        window[n] = float(0.5 - 0.5 * math.cos(2.0 * math.pi * n / length))
    return window


def hz_to_mel(hz: float) -> float:
    return 2595.0 * math.log10(1.0 + hz / 700.0)


def mel_to_hz(mel: float) -> float:
    return 700.0 * (10.0 ** (mel / 2595.0) - 1.0)


def create_mel_filter_bank(
    mel_bins: int = MEL_BINS,
    fft_size: int = FFT_SIZE,
    sample_rate: int = SAMPLE_RATE,
    f_min: float = MEL_FMIN,
    f_max: float = MEL_FMAX,
) -> np.ndarray:
    """CreateMelFilterBank → float[mel_bins, freq_bins]."""
    freq_bins = (fft_size // 2) + 1
    weights = np.zeros((mel_bins, freq_bins), dtype=np.float32)

    mel_min = hz_to_mel(f_min)
    mel_max = hz_to_mel(f_max)

    mel_points = np.zeros(mel_bins + 2, dtype=np.float64)
    for i in range(mel_points.shape[0]):
        mel_points[i] = mel_min + (mel_max - mel_min) * i / (mel_bins + 1)

    bins = np.zeros(mel_bins + 2, dtype=np.int32)
    for i in range(mel_points.shape[0]):
        hz = mel_to_hz(float(mel_points[i]))
        b = int(math.floor((fft_size + 1) * hz / sample_rate))
        b = max(0, min(freq_bins - 1, b))
        bins[i] = b

    for m in range(mel_bins):
        f0 = int(bins[m])
        f1 = int(bins[m + 1])
        f2 = int(bins[m + 2])
        if f1 <= f0 or f2 <= f1:
            continue
        for k in range(f0, f1):
            weights[m, k] = float(k - f0) / float(f1 - f0)
        for k in range(f1, f2):
            weights[m, k] = float(f2 - k) / float(f2 - f1)

    return weights


def mel_nonzero_ranges(mel_filter_bank: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
    """Precompute start/end bins like the ctor."""
    mel_bins, freq_bins = mel_filter_bank.shape
    starts = np.zeros(mel_bins, dtype=np.int32)
    ends = np.zeros(mel_bins, dtype=np.int32)
    for m in range(mel_bins):
        start = 0
        while start < freq_bins and mel_filter_bank[m, start] == 0.0:
            start += 1
        last = freq_bins - 1
        while last >= start and mel_filter_bank[m, last] == 0.0:
            last -= 1
        starts[m] = start
        ends[m] = min(freq_bins, last + 1)
    return starts, ends


def downmix_to_mono(samples: np.ndarray, channels: int) -> np.ndarray:
    """
    DownmixToMono for float samples shaped (frames * channels,) interleaved
    or (frames, channels).
    """
    flat = np.asarray(samples, dtype=np.float32).reshape(-1)
    if channels <= 0 or flat.size == 0:
        return np.zeros(0, dtype=np.float32)

    frames = flat.size // channels
    flat = flat[: frames * channels]

    if channels == 1:
        return flat.copy()

    if channels == 8:
        mono = np.zeros(frames, dtype=np.float32)
        eps = 1e-12
        for f in range(frames):
            o = f * channels
            fl = flat[o + 0]
            fr = flat[o + 1]
            fc = flat[o + 2]
            lr = 0.5 * (fl + fr)
            e_fc = fc * fc
            e_lr = lr * lr
            w = e_fc / (e_fc + e_lr + eps)
            mono[f] = w * fc + (1.0 - w) * lr
        return mono

    # average across channels
    reshaped = flat.reshape(frames, channels)
    return reshaped.mean(axis=1).astype(np.float32)


def resample_mono_float_to_16k_custom(
    source: np.ndarray,
    source_sample_rate: int,
    dest_length: int = REQUIRED_MONO_16K_SAMPLES,
) -> np.ndarray:
    """Fixed-length 16kHz resample with a cached 96-tap Kaiser FIR anti-alias filter.

    This independently computes the same FIR design as Android: 7.8kHz cutoff,
    beta 8.6, source-rate-specific fractional phases, and zero extension beyond
    the source window. 16kHz input remains a direct copy.
    """
    source = np.asarray(source, dtype=np.float32).reshape(-1)
    source_length = int(source.shape[0])
    destination = np.zeros(dest_length, dtype=np.float32)

    if source_sample_rate == SAMPLE_RATE:
        copy_len = min(source_length, dest_length)
        if copy_len > 0:
            destination[:copy_len] = source[:copy_len]
        return destination

    if source_sample_rate < SAMPLE_RATE:
        factor = float(source_sample_rate) / float(SAMPLE_RATE)
        for i in range(dest_length):
            source_position = i * factor
            index1 = int(source_position)
            index2 = index1 + 1
            if index1 >= source_length:
                continue
            alpha = float(source_position - index1)
            value1 = float(source[index1])
            value2 = float(source[index2]) if index2 < source_length else value1
            destination[i] = (1.0 - alpha) * value1 + alpha * value2
        return destination

    coefficients, phase_count = _fir_table_for_sample_rate(int(source_sample_rate))
    output_indices = np.arange(dest_length, dtype=np.int64)
    position_numerators = output_indices * int(source_sample_rate)
    centers = position_numerators // SAMPLE_RATE
    if phase_count == 1:
        phases = np.zeros(dest_length, dtype=np.int64)
    else:
        phases = (
            (position_numerators % SAMPLE_RATE) * phase_count + SAMPLE_RATE // 2
        ) // SAMPLE_RATE % phase_count
    tap_offsets = np.arange(FIR_TAPS, dtype=np.int64) - (FIR_TAPS // 2) + 1
    source_indices = centers[:, None] + tap_offsets[None, :]
    valid = (source_indices >= 0) & (source_indices < source_length)
    clipped_indices = np.clip(source_indices, 0, source_length - 1)
    samples = np.where(valid, source[clipped_indices], 0.0).astype(np.float64)
    weights = coefficients[phases].astype(np.float64)
    destination[:] = np.einsum("ij,ij->i", weights, samples).astype(np.float32)
    return destination


@lru_cache(maxsize=None)
def _fir_table_for_sample_rate(source_sample_rate: int) -> tuple[np.ndarray, int]:
    """Build source-rate phase tables once; this is intentionally independent of Kotlin."""
    if source_sample_rate <= 0:
        raise ValueError("source_sample_rate must be positive")
    exact_phase_count = SAMPLE_RATE // math.gcd(source_sample_rate, SAMPLE_RATE)
    phase_count = min(exact_phase_count, _MAX_CACHED_PHASES)
    table = np.empty((phase_count, FIR_TAPS), dtype=np.float32)
    for phase in range(phase_count):
        fraction = float(phase) / float(phase_count)
        values = []
        for tap in range(FIR_TAPS):
            sample_offset = tap - (FIR_TAPS // 2) + 1
            time = float(sample_offset) - fraction
            radius = min(1.0, abs(time) / float(FIR_TAPS // 2))
            window = _bessel_i0(FIR_KAISER_BETA * math.sqrt(1.0 - radius * radius))
            window /= _bessel_i0(FIR_KAISER_BETA)
            normalized_cutoff = 2.0 * FIR_CUTOFF_HZ / float(source_sample_rate)
            values.append(normalized_cutoff * _sinc(normalized_cutoff * time) * window)
        scale = sum(values)
        for tap, value in enumerate(values):
            table[phase, tap] = value / scale
    return table, phase_count


def _phase_index(remainder: int, phase_count: int) -> int:
    if phase_count == 1:
        return 0
    return ((remainder * phase_count + SAMPLE_RATE // 2) // SAMPLE_RATE) % phase_count


def _sinc(value: float) -> float:
    if abs(value) < 1e-12:
        return 1.0
    return math.sin(math.pi * value) / (math.pi * value)


def _bessel_i0(value: float) -> float:
    term = 1.0
    total = 1.0
    k = 1
    while True:
        term *= (value * value / 4.0) / float(k * k)
        total += term
        if term < total * 1e-15:
            return total
        k += 1


def capture_samples_for_one_yamnet_window(capture_sample_rate: int) -> int:
    return int(math.ceil(REQUIRED_MONO_16K_SAMPLES * float(capture_sample_rate) / float(SAMPLE_RATE)))


def compute_log_mel_spectrogram(
    mono_16k: np.ndarray,
    hann_window: np.ndarray | None = None,
    mel_filter_bank: np.ndarray | None = None,
    mel_starts: np.ndarray | None = None,
    mel_ends: np.ndarray | None = None,
) -> np.ndarray:
    """
    ComputeLogMelSpectrogram
    Returns flat float32 length TimeFrames*MelBins, layout time * MelBins + mel
    (row-major for DenseTensor [1,1,96,64]).
    """
    if hann_window is None:
        hann_window = create_hann_window(WINDOW_LENGTH)
    if mel_filter_bank is None:
        mel_filter_bank = create_mel_filter_bank()
    if mel_starts is None or mel_ends is None:
        mel_starts, mel_ends = mel_nonzero_ranges(mel_filter_bank)

    required = REQUIRED_MONO_16K_SAMPLES
    preprocessed = np.zeros(required, dtype=np.float32)
    mono = np.asarray(mono_16k, dtype=np.float32).reshape(-1)
    copy_len = min(mono.shape[0], required)
    if copy_len > 0:
        preprocessed[:copy_len] = mono[:copy_len]

    freq_bins = (FFT_SIZE // 2) + 1
    log_mel = np.zeros(TIME_FRAMES * MEL_BINS, dtype=np.float32)

    for t in range(TIME_FRAMES):
        start = t * HOP_LENGTH
        frame = np.zeros(FFT_SIZE, dtype=np.float32)
        for i in range(WINDOW_LENGTH):
            frame[i] = preprocessed[start + i] * hann_window[i]
        # remaining FFT bins already 0

        spectrum = np.fft.fft(frame.astype(np.float64), n=FFT_SIZE)
        power = np.empty(freq_bins, dtype=np.float32)
        for k in range(freq_bins):
            c = spectrum[k]
            power[k] = float(c.real * c.real + c.imag * c.imag)

        for mel in range(MEL_BINS):
            mel_sum = 0.0
            start_bin = int(mel_starts[mel])
            end_bin = int(mel_ends[mel])
            for k in range(start_bin, end_bin):
                mel_sum += float(mel_filter_bank[mel, k]) * float(power[k])
            value = math.log(mel_sum + LOG_EPS)
            log_mel[t * MEL_BINS + mel] = float(value)

    return log_mel


def log_mel_to_tensor(log_mel_flat: np.ndarray) -> np.ndarray:
    """Shape [1,1,96,64] float32."""
    arr = np.asarray(log_mel_flat, dtype=np.float32).reshape(TIME_FRAMES, MEL_BINS)
    return arr.reshape(1, 1, TIME_FRAMES, MEL_BINS)
