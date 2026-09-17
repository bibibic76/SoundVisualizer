#!/usr/bin/env python3
"""Regression tests for dataset diagnostic window selection."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from compare_logmel_dataset import mean_nonoverlap_windows
from preprocess import REQUIRED_MONO_16K_SAMPLES


class MeanNonoverlapWindowsTest(unittest.TestCase):
    def test_drops_partial_tail_when_full_window_exists(self) -> None:
        samples = np.arange(
            REQUIRED_MONO_16K_SAMPLES * 2 + 3000,
            dtype=np.float32,
        )

        windows = mean_nonoverlap_windows(samples)

        self.assertEqual(len(windows), 2)
        np.testing.assert_array_equal(
            windows[0],
            samples[:REQUIRED_MONO_16K_SAMPLES],
        )
        np.testing.assert_array_equal(
            windows[1],
            samples[
                REQUIRED_MONO_16K_SAMPLES : 2 * REQUIRED_MONO_16K_SAMPLES
            ],
        )

    def test_keeps_single_short_window_for_frontend_padding(self) -> None:
        samples = np.arange(3000, dtype=np.float32)

        windows = mean_nonoverlap_windows(samples)

        self.assertEqual(len(windows), 1)
        np.testing.assert_array_equal(windows[0], samples)


if __name__ == "__main__":
    unittest.main()
