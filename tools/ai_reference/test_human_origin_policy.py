import unittest
from human_origin_policy import expected_for
from mapper import map_display_name_to_coarse


class HumanOriginPolicyTest(unittest.TestCase):
    def test_all_related_categories_use_policy_not_individual_filenames(self):
        for category in ("sneezing", "coughing", "breathing", "laughing", "crying_baby", "singing"):
            self.assertEqual(expected_for({"category": category, "expected": "ambient"}), "speech")
            self.assertEqual(expected_for({"stratum": category, "expected_hint": "ambient"}), "speech")

    def test_exact_human_labels_do_not_remap_machines_or_danger(self):
        for label in ("Sneeze", "Cough", "Breathing"):
            self.assertEqual(map_display_name_to_coarse(label), "speech")
        for label in ("Breathing machine", "Cough-like sound", "Whimper (dog)", "Singing bowl"):
            self.assertEqual(map_display_name_to_coarse(label), "ambient")
        for label in ("Siren", "Alarm", "Gunshot, gunfire", "Smoke detector, smoke alarm", "Chainsaw"):
            self.assertEqual(map_display_name_to_coarse(label), "danger")


if __name__ == "__main__":
    unittest.main()
