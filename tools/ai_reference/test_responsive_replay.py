"""Deterministic timing tests independent of trained-model outputs."""
import unittest

import numpy as np

from classifier import InferenceResult, ReferenceClassifier
from evaluate_responsive_inference import captures, replay
from responsive_schedule import BorrowedSlots, ProductionSlots, RuntimeProfile


class ScriptedClassifier(ReferenceClassifier):
    def __init__(self, reject_early=False, early_danger=False):
        self.reject_early = reject_early
        self.early_danger = early_danger
        self.reset_state()

    def reset_state(self):
        self._confirmed_coarse = "ambient"
        self._confirmed_display = ""
        self._confirmed_confidence = 0.0
        self._candidate_coarse = ""
        self._candidate_streak = 0

    def classify_mono_capture_once(self, mono, rate, apply_hysteresis=False):
        # Input encodes capture time in the event interval. No wall clock or call count.
        now = float(mono[-1]) * 10_000
        label = "speech" if now >= 1650 else "ambient"
        if 1800 <= now < 1950 and self.reject_early:
            label = "danger" if self.early_danger else "ambient"
        if self.early_danger and now >= 1950:
            label = "danger"
        return InferenceResult(0, label.title(), .5, label, True, 0), None, {}


class ResponsiveReplayTest(unittest.TestCase):
    def evaluate(self, adaptive, reject_early=False, early_danger=False, cost=25, profile=None, events=None):
        rate = 16_000
        mono = np.zeros(64_000, dtype=np.float32)
        mono[24_000:48_000] = np.arange(24_000, 48_000, dtype=np.float32) / rate / 10
        return replay(
            mono, captures(mono, rate, 1), rate,
            ProductionSlots(profile=profile, followup=False) if profile else BorrowedSlots(delay_ms=0, followup=False),
            ScriptedClassifier(reject_early, early_danger), {}, 1500, 3000,
            "danger" if early_danger else "speech", cost, False, adaptive, adaptive,
            consistent=bool(profile), activity=bool(profile), runtime_profile=profile,
            diagnostic_events=events,
        )

    def test_diagnostic_observation_does_not_change_results(self):
        events = []
        self.assertEqual(self.evaluate(False), self.evaluate(False, events=events))
        first = next(e for e in events if e["raw"] == "speech")
        confirmed = next(e for e in events if e["ui"] == "speech")
        self.assertEqual("ambient", first["ui"])
        self.assertEqual(250, confirmed["complete_ms"] - first["complete_ms"])
        self.assertEqual(25, first["complete_ms"] - first["snapshot_ms"])

    def test_unchanged_two_confirmations_can_finish_125ms_earlier(self):
        baseline = self.evaluate(False)
        candidate = self.evaluate(True)
        self.assertAlmostEqual(125, baseline["first_valid_expected_ms"] - candidate["first_valid_expected_ms"])

    def test_failed_early_confirmation_preserves_original_candidate_and_slot(self):
        baseline = self.evaluate(False, reject_early=True)
        candidate = self.evaluate(True, reject_early=True)
        self.assertEqual(baseline["first_valid_expected_ms"], candidate["first_valid_expected_ms"])
        self.assertGreater(candidate["predictions"], baseline["predictions"])

    def test_speculative_danger_does_not_change_baseline_danger_time(self):
        baseline = self.evaluate(False, reject_early=True, early_danger=True)
        candidate = self.evaluate(True, reject_early=True, early_danger=True)
        self.assertEqual(baseline["first_valid_expected_ms"], candidate["first_valid_expected_ms"])

    def test_slow_inference_disables_speculation(self):
        baseline = self.evaluate(False, cost=50)
        candidate = self.evaluate(True, cost=50)
        self.assertEqual(baseline, candidate)

    def test_production_fixed_slow_costs_disable_speculation(self):
        for cost in (26, 50, 100, 200, 400):
            with self.subTest(cost=cost):
                profile = RuntimeProfile("fixed", cost)
                self.assertEqual(self.evaluate(False, profile=profile), self.evaluate(True, profile=profile))

    def test_late_early_wakeup_aborts_without_delaying_regular_tick(self):
        profile = RuntimeProfile("wake-jitter", 20)
        baseline = self.evaluate(False, profile=profile)
        candidate = self.evaluate(True, profile=profile)
        self.assertEqual(baseline["first_valid_expected_ms"], candidate["first_valid_expected_ms"])
        self.assertEqual(0, candidate["early_attempts"])
        self.assertGreater(candidate["deadline_aborts"], 0)

    def test_unexpected_early_cost_can_delay_even_accepted_speech(self):
        profile = RuntimeProfile("early-spike")
        baseline = self.evaluate(False, profile=profile)
        candidate = self.evaluate(True, profile=profile)
        self.assertGreater(candidate["first_valid_expected_ms"], baseline["first_valid_expected_ms"])
        self.assertEqual(1, candidate["early_accepted"])

    def test_unexpected_early_cost_can_delay_danger_fallback(self):
        profile = RuntimeProfile("early-spike")
        baseline = self.evaluate(False, True, True, profile=profile)
        candidate = self.evaluate(True, True, True, profile=profile)
        self.assertAlmostEqual(170, candidate["first_valid_expected_ms"] - baseline["first_valid_expected_ms"])
        self.assertGreater(candidate["early_rejected"], 0)


if __name__ == "__main__":
    unittest.main()
