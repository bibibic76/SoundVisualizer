import unittest

from prepare_responsive_validation import select_esc


class SelectionTest(unittest.TestCase):
    def test_selection_is_deterministic_and_excludes_prior_source_not_just_file(self):
        rows = [dict(category="clapping", src_file=source, filename=name)
                for source, name in (("old", "old-other-take.wav"), ("new", "a.wav"),
                                     ("new", "b.wav"), ("new2", "c.wav"))]
        selected = select_esc(rows, {"old"})
        self.assertEqual({"new", "new2"}, {r["src_file"] for r in selected})
        self.assertEqual(2, len(selected))
        self.assertEqual(selected, select_esc(list(reversed(rows)), {"old"}))

    def test_exhausted_category_is_not_replaced_by_previously_seen_audio(self):
        self.assertEqual([], select_esc([dict(category="car_horn", src_file="old", filename="x")], {"old"}))


if __name__ == "__main__":
    unittest.main()
