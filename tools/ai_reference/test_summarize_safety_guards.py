import unittest
from summarize_safety_guards import metrics


class SafetyGuardMetricsTest(unittest.TestCase):
    def test_confusion_orientation_and_macro_f1(self):
        rows = [dict(expected='ambient', majority='danger'),
                dict(expected='speech', majority='speech'),
                dict(expected='danger', majority='danger')]
        result = metrics(rows)
        self.assertEqual([[0, 0, 1], [0, 1, 0], [0, 0, 1]], result['confusion'])
        self.assertAlmostEqual((0 + 1 + 2/3)/3, result['macro_f1'])
        self.assertEqual(1, result['recall']['danger'])

    def test_empty_group_is_explicitly_zero(self):
        result = metrics([])
        self.assertEqual(0, result['files'])
        self.assertEqual(0, result['macro_f1'])


if __name__ == '__main__':
    unittest.main()
