#!/usr/bin/env python3
"""Compare the NumPy Qualcomm-source frontend with pinned upstream source."""
from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from compare_logmel_frontends import (
    FRAMES,
    LOG_EPS,
    MELS,
    N,
    SR,
    _TORCH_MEL,
    qualcomm_source_log_mel,
)

# log(mel + eps) <= log(eps) + 0.1 means mel energy is at most
# eps * (exp(0.1) - 1), about 1.05e-4. This isolates bins dominated by the
# numerical floor instead of relaxing bins that carry measurable energy.
LOG_FLOOR_MARGIN = 0.1
DEFAULT_SIGNAL_MAX_ATOL = 3e-3
# The two observed GitHub runner hardware paths produce 0.001414 and 0.004410
# maxima for the floor-heavy sine case (#150). Keep more than 2x headroom, but
# apply it only when both implementations remain inside the floor region.
DEFAULT_FLOOR_MAX_ATOL = 1e-2
DEFAULT_MEAN_ATOL = 5e-4


@dataclass(frozen=True)
class LogMelParityErrors:
    overall_max_abs: float
    signal_max_abs: float
    floor_max_abs: float
    mean_abs: float
    floor_bins: int
    total_bins: int


def measure_log_mel_parity(
    numpy_result: np.ndarray,
    torch_result: np.ndarray,
    floor_margin: float = LOG_FLOOR_MARGIN,
) -> LogMelParityErrors:
    """Separate near-log-floor FFT noise from bins carrying measurable energy.

    A bin is considered floor-only only when both implementations are within
    ``floor_margin`` above log(LOG_EPS). If either side crosses that boundary,
    the existing strict signal tolerance applies. Floor bins retain their own
    bounded max-error check, and the global mean check still covers every bin.
    """
    numpy_values = np.asarray(numpy_result, dtype=np.float64)
    torch_values = np.asarray(torch_result, dtype=np.float64)
    if numpy_values.shape != torch_values.shape:
        raise ValueError(
            f"shape mismatch: numpy={numpy_values.shape} torch={torch_values.shape}"
        )

    absolute_error = np.abs(numpy_values - torch_values)
    floor_ceiling = np.log(LOG_EPS) + floor_margin
    floor_mask = (numpy_values <= floor_ceiling) & (torch_values <= floor_ceiling)
    signal_mask = ~floor_mask

    def masked_max(mask: np.ndarray) -> float:
        return float(np.max(absolute_error[mask])) if np.any(mask) else 0.0

    return LogMelParityErrors(
        overall_max_abs=float(np.max(absolute_error)),
        signal_max_abs=masked_max(signal_mask),
        floor_max_abs=masked_max(floor_mask),
        mean_abs=float(np.mean(absolute_error)),
        floor_bins=int(np.count_nonzero(floor_mask)),
        total_bins=int(absolute_error.size),
    )


def log_mel_parity_failed(
    errors: LogMelParityErrors,
    signal_max_atol: float,
    floor_max_atol: float,
    mean_atol: float,
) -> bool:
    return (
        errors.signal_max_abs > signal_max_atol
        or errors.floor_max_abs > floor_max_atol
        or errors.mean_abs > mean_atol
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
    parser.add_argument(
        "--max-atol",
        type=float,
        default=DEFAULT_SIGNAL_MAX_ATOL,
        help="Maximum error outside the shared log-floor region",
    )
    parser.add_argument(
        "--floor-max-atol",
        type=float,
        default=DEFAULT_FLOOR_MAX_ATOL,
        help="Maximum error where both implementations remain near log(LOG_EPS)",
    )
    parser.add_argument("--mean-atol", type=float, default=DEFAULT_MEAN_ATOL)
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
        errors = measure_log_mel_parity(numpy_result, torch_result)
        mel_max_abs = float(
            np.max(np.abs(_TORCH_MEL.astype(np.float32) - torch_mel))
        )
        print(
            f"{name}: logmel overall_max_abs={errors.overall_max_abs:.9g} "
            f"signal_max_abs={errors.signal_max_abs:.9g} "
            f"floor_max_abs={errors.floor_max_abs:.9g} "
            f"mean_abs={errors.mean_abs:.9g} "
            f"floor_bins={errors.floor_bins}/{errors.total_bins} "
            f"mel_max_abs={mel_max_abs:.9g}"
        )
        failed |= (
            log_mel_parity_failed(
                errors,
                signal_max_atol=args.max_atol,
                floor_max_atol=args.floor_max_atol,
                mean_atol=args.mean_atol,
            )
            or mel_max_abs > args.mel_atol
        )

    if failed:
        print(
            "parity failed: "
            f"signal_max_atol={args.max_atol} "
            f"floor_max_atol={args.floor_max_atol} mean_atol={args.mean_atol} "
            f"mel_atol={args.mel_atol}",
            file=sys.stderr,
        )
        return 1
    print(
        "parity passed: "
        f"signal_max_atol={args.max_atol} "
        f"floor_max_atol={args.floor_max_atol} mean_atol={args.mean_atol} "
        f"mel_atol={args.mel_atol}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
