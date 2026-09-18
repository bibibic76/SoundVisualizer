#!/usr/bin/env python3
"""Regression tests for the floor-aware torchaudio parity acceptance rule."""
from __future__ import annotations

import unittest

import numpy as np

from compare_logmel_frontends import LOG_EPS
from verify_torchaudio_logmel_parity import (
    DEFAULT_FLOOR_MAX_ATOL,
    DEFAULT_MEAN_ATOL,
    DEFAULT_SIGNAL_MAX_ATOL,
    log_mel_parity_failed,
    measure_log_mel_parity,
)

LOG_FLOOR = float(np.log(LOG_EPS))


def failed(left: np.ndarray, right: np.ndarray) -> bool:
    return log_mel_parity_failed(
        measure_log_mel_parity(left, right),
        signal_max_atol=DEFAULT_SIGNAL_MAX_ATOL,
        floor_max_atol=DEFAULT_FLOOR_MAX_ATOL,
        mean_atol=DEFAULT_MEAN_ATOL,
    )


class FloorAwareParityTest(unittest.TestCase):
    def test_allows_isolated_bounded_error_when_both_values_remain_at_floor(self) -> None:
        left = np.full(100, LOG_FLOOR + 0.02)
        right = left.copy()
        right[0] += 0.005

        errors = measure_log_mel_parity(left, right)

        self.assertEqual(errors.floor_bins, 100)
        self.assertAlmostEqual(errors.floor_max_abs, 0.005)
        self.assertFalse(failed(left, right))

    def test_keeps_existing_strict_max_for_signal_bins(self) -> None:
        left = np.full(100, LOG_FLOOR + 0.2)
        right = left.copy()
        right[0] += 0.004

        errors = measure_log_mel_parity(left, right)

        self.assertEqual(errors.floor_bins, 0)
        self.assertAlmostEqual(errors.signal_max_abs, 0.004)
        self.assertTrue(failed(left, right))

    def test_boundary_mismatch_is_treated_as_signal(self) -> None:
        left = np.full(100, LOG_FLOOR + 0.099)
        right = left.copy()
        right[0] += 0.004

        errors = measure_log_mel_parity(left, right)

        self.assertEqual(errors.floor_bins, 99)
        self.assertAlmostEqual(errors.signal_max_abs, 0.004)
        self.assertTrue(failed(left, right))

    def test_floor_error_still_has_a_maximum_bound(self) -> None:
        left = np.full(100, LOG_FLOOR + 0.02)
        right = left.copy()
        right[0] += 0.011

        self.assertTrue(failed(left, right))

    def test_global_mean_still_catches_broad_floor_drift(self) -> None:
        left = np.full(100, LOG_FLOOR + 0.02)
        right = left + 0.001

        errors = measure_log_mel_parity(left, right)

        self.assertLess(errors.floor_max_abs, DEFAULT_FLOOR_MAX_ATOL)
        self.assertGreater(errors.mean_abs, DEFAULT_MEAN_ATOL)
        self.assertTrue(failed(left, right))


if __name__ == "__main__":
    unittest.main()
