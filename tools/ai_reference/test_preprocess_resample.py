"""Reference acceptance tests for the anti-aliased capture-to-16k resampler."""

from __future__ import annotations

import math
import unittest

import numpy as np

from preprocess import SAMPLE_RATE, resample_mono_float_to_16k_custom


class AntiAliasedResampleTest(unittest.TestCase):
    def test_frequency_acceptance_at_44100_and_48000(self) -> None:
        for source_rate in (44100, 48000):
            self._assert_gain_at_least(source_rate, 7000.0, -1.5)
            self._assert_gain_at_least(source_rate, 7500.0, -3.0)
            self._assert_gain_at_most(source_rate, 8500.0, -20.0)
            self._assert_gain_at_most(source_rate, 9000.0, -45.0)
            self._assert_gain_at_most(source_rate, 10000.0, -45.0)
            self._assert_gain_at_most(source_rate, 15000.0, -60.0)

    def test_16k_passthrough_is_bit_exact(self) -> None:
        source = np.asarray([float(index) for index in range(15600)], dtype=np.float32)
        output = resample_mono_float_to_16k_custom(source, SAMPLE_RATE)
        self.assertTrue(np.array_equal(source, output))

    def test_source_window_edges_are_zero_extended(self) -> None:
        output = resample_mono_float_to_16k_custom(np.ones(512, dtype=np.float32), 48000, 64)
        self.assertTrue(np.isfinite(output).all())
        self.assertGreater(float(output[0]), 0.0)
        self.assertLess(float(output[0]), 1.0)
        self.assertAlmostEqual(1.0, float(output[32]), places=5)

    def test_deterministic_signal_is_repeatable_at_44100_and_48000(self) -> None:
        for source_rate in (44100, 48000):
            source = self._deterministic_signal(
                source_rate, math.ceil(1024 * source_rate / SAMPLE_RATE) + 96
            )
            first = resample_mono_float_to_16k_custom(source, source_rate, 1024)
            second = resample_mono_float_to_16k_custom(source, source_rate, 1024)
            self.assertTrue(np.array_equal(first, second))

    def _assert_gain_at_least(self, source_rate: int, frequency_hz: float, minimum_db: float) -> None:
        gain = self._tone_gain_db(source_rate, frequency_hz)
        self.assertGreaterEqual(gain, minimum_db, f"{source_rate}Hz {frequency_hz}Hz: {gain}dB")

    def _assert_gain_at_most(self, source_rate: int, frequency_hz: float, maximum_db: float) -> None:
        gain = self._tone_gain_db(source_rate, frequency_hz)
        self.assertLessEqual(gain, maximum_db, f"{source_rate}Hz {frequency_hz}Hz: {gain}dB")

    @staticmethod
    def _tone_gain_db(source_rate: int, input_frequency_hz: float) -> float:
        destination_length = 4096
        source_length = math.ceil(destination_length * source_rate / SAMPLE_RATE) + 96
        source = np.asarray(
            [math.cos(2.0 * math.pi * input_frequency_hz * index / source_rate)
             for index in range(source_length)],
            dtype=np.float32,
        )
        output = resample_mono_float_to_16k_custom(source, source_rate, destination_length)

        alias_frequency = input_frequency_hz % SAMPLE_RATE
        if alias_frequency > SAMPLE_RATE / 2.0:
            alias_frequency = SAMPLE_RATE - alias_frequency
        indices = np.arange(128, destination_length - 128, dtype=np.float64)
        phase = 2.0 * math.pi * alias_frequency * indices / SAMPLE_RATE
        cosine = np.cos(phase)
        sine = np.sin(phase)
        values = output[128:-128].astype(np.float64)
        cosine_coefficient = float(np.dot(values, cosine) / np.dot(cosine, cosine))
        sine_denominator = float(np.dot(sine, sine))
        sine_coefficient = 0.0 if sine_denominator < 1e-12 else float(np.dot(values, sine) / sine_denominator)
        amplitude = math.sqrt(cosine_coefficient * cosine_coefficient + sine_coefficient * sine_coefficient)
        return 20.0 * math.log10(max(amplitude, 1e-300))

    @staticmethod
    def _deterministic_signal(sample_rate: int, length: int) -> np.ndarray:
        state = (0x6D2B79F5 ^ sample_rate) & 0xFFFFFFFF
        values = np.empty(length, dtype=np.float32)
        duration = max(1.0, length / sample_rate)
        for index in range(length):
            state = (1664525 * state + 1013904223) & 0xFFFFFFFF
            noise = (state / 4294967296.0) * 2.0 - 1.0
            seconds = index / sample_rate
            phase = 2.0 * math.pi * (
                300.0 * seconds + 0.5 * (7200.0 / duration) * seconds * seconds
            )
            values[index] = np.float32(0.12 * noise + 0.18 * math.sin(phase))
        values[length // 3] += np.float32(0.5)
        return values


if __name__ == "__main__":
    unittest.main()
