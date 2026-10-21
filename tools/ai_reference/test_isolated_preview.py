import unittest

import numpy as np

from evaluate_isolated_preview import isolated_replay
from evaluate_responsive_inference import captures, replay
from responsive_schedule import ProductionSlots, RuntimeProfile
from test_responsive_replay import ScriptedClassifier


class IsolatedPreviewTest(unittest.TestCase):
    def evaluate(self, profile, danger=False):
        rate = 16_000
        mono = np.zeros(64_000, np.float32)
        mono[24_000:48_000] = np.arange(24_000, 48_000, dtype=np.float32) / rate / 10
        chunks = captures(mono, rate, 1)
        classifier = ScriptedClassifier(reject_early=danger, early_danger=danger)
        cache = {}
        expected = "danger" if danger else "speech"
        baseline = replay(mono, chunks, rate, ProductionSlots(profile=profile), classifier, cache,
                          1500, 3000, expected, profile.fixed_ms, False, runtime_profile=profile)
        candidate = isolated_replay(mono, chunks, rate, classifier, cache, 1500, 3000, expected, profile)
        self.assertEqual(baseline["first_valid_expected_ms"], candidate["regular_first_valid_expected_ms"])
        self.assertEqual(baseline["predictions"], candidate["regular_predictions"])
        for key, value in candidate["regular_metrics"].items():
            self.assertEqual(baseline[key], value)
        return baseline, candidate

    def test_fast_preview_does_not_consume_original_regular_slot(self):
        baseline, candidate = self.evaluate(RuntimeProfile("fixed", 25))
        self.assertEqual(125, baseline["first_valid_expected_ms"] - candidate["first_valid_expected_ms"])
        self.assertGreater(candidate["predictions"], baseline["predictions"])

    def test_slow_preview_does_not_delay_regular_speech(self):
        baseline, candidate = self.evaluate(RuntimeProfile("early-spike"))
        self.assertEqual(baseline["first_valid_expected_ms"], candidate["first_valid_expected_ms"])
        self.assertEqual(0, candidate["early_accepted"])
        self.assertGreater(candidate["late_drops"], 0)

    def test_slow_preview_does_not_delay_regular_danger(self):
        baseline, candidate = self.evaluate(RuntimeProfile("early-spike"), danger=True)
        self.assertEqual(baseline["first_valid_expected_ms"], candidate["first_valid_expected_ms"])

    def test_declared_runtime_profiles_preserve_regular_state(self):
        for profile in (RuntimeProfile("fixed", 400), RuntimeProfile("variable"),
                        RuntimeProfile("wake-jitter", 20), RuntimeProfile("mixed")):
            with self.subTest(profile=profile):
                self.evaluate(profile)


if __name__ == "__main__":
    unittest.main()
