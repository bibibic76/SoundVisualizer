#!/usr/bin/env python3
"""Policy parity tests shared with the Android YAMNet classifier."""
from __future__ import annotations

import unittest

from classifier import ReferenceClassifier


class StrongDangerCuePolicyTest(unittest.TestCase):
    def test_approved_attention_cues_are_strong(self) -> None:
        classifier = ReferenceClassifier.__new__(ReferenceClassifier)
        for label in (
            "Vehicle horn, car horn, honking",
            "Air horn, truck horn",
            "Burst, pop",
            "Boom",
            "Bang",
            "Smash, crash",
            "Breaking",
            "Shatter",
        ):
            with self.subTest(label=label):
                self.assertTrue(classifier._is_strong_danger_keyword(label))

    def test_proxies_and_partial_matches_are_not_strong(self) -> None:
        classifier = ReferenceClassifier.__new__(ReferenceClassifier)
        for label in ("Plop", "Gargling", "Breaking news", "Booming music"):
            with self.subTest(label=label):
                self.assertFalse(classifier._is_strong_danger_keyword(label))


if __name__ == "__main__":
    unittest.main()
