#!/usr/bin/env python3
"""Policy parity tests shared with the Android YAMNet classifier."""
from __future__ import annotations

import unittest

import mapper
from classifier import ReferenceClassifier


class StrongDangerCuePolicyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.classifier = ReferenceClassifier.__new__(ReferenceClassifier)
        self.classifier._class_names = ["Music", "Gunshot, gunfire", "Siren", "Wind", "Footsteps"]

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

    def test_firearm_does_not_veto_independent_cue_at_policy_floor(self) -> None:
        cue = self.classifier._try_pick_best_non_gunshot_strong_danger_display(
            [0, 1, 2, 3, 4], [.6, .2, .05, .04, .03], 5
        )
        self.assertEqual((2, .05), cue)

    def test_firearm_cannot_lend_evidence_to_weaker_non_firearm_cue(self) -> None:
        cue = self.classifier._try_pick_best_non_gunshot_strong_danger_display(
            [0, 1, 2, 3, 4], [.6, .2, .049, .04, .03], 5
        )
        self.assertIsNone(cue)

    def test_firearm_itself_is_never_selected_for_promotion(self) -> None:
        cue = self.classifier._try_pick_best_non_gunshot_strong_danger_display(
            [0, 1, 3, 4, -1], [.6, .2, .04, .03, -1], 5
        )
        self.assertIsNone(cue)


class ThreeClassMapperPolicyTest(unittest.TestCase):
    def test_approved_attention_labels_vote_but_are_not_safety_cues(self) -> None:
        classifier = ReferenceClassifier.__new__(ReferenceClassifier)
        for label in (
            "Reversing beeps", "Train horn", "Train whistle", "Foghorn", "Bicycle bell",
            "Emergency vehicle", "Skidding", "Tire squeal", "Slam",
        ):
            with self.subTest(label=label):
                self.assertEqual("danger", mapper.map_display_name_to_coarse(label))
                self.assertFalse(classifier._is_strong_danger_keyword(label))
                self.assertEqual("ambient", mapper.map_display_name_to_coarse(label + "-like sound"))

    def test_instrument_exception_does_not_change_vocals_or_general_vehicles(self) -> None:
        for label in ("Singing bowl", "Truck", "Car", "Bus", "Train", "Doorbell", "Honk", "Whistle"):
            self.assertEqual("ambient", mapper.map_display_name_to_coarse(label))
        for label in ("Singing", "Choir", "Screaming", "Shout", "Laughter", "Crying, sobbing"):
            self.assertEqual("speech", mapper.map_display_name_to_coarse(label))

    def test_former_gunshot_proxies_are_ambient(self) -> None:
        for label in ("Plop", "Gargling"):
            self.assertEqual("ambient", mapper.map_display_name_to_coarse(label))

    def test_attention_nature_and_tool_labels_are_danger(self) -> None:
        for label in ("Chainsaw", "Thunder", "Thunderstorm"):
            with self.subTest(label=label):
                self.assertEqual("danger", mapper.map_display_name_to_coarse(label))

    def test_partial_matches_remain_ambient(self) -> None:
        for label in ("Chainsaw-like sound", "Distant thunder", "Rain", "Wind"):
            with self.subTest(label=label):
                self.assertEqual("ambient", mapper.map_display_name_to_coarse(label))


if __name__ == "__main__":
    unittest.main()
