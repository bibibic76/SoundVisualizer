import unittest
from summarize_ai_latency import summarize


class LatencySummaryTest(unittest.TestCase):
    def row(self, stage="result", at=170):
        return dict(schema="1", stage=stage, at_boot_ns=str(at), pipeline_session_id="2", result_id="3",
            read_return_boot_ns="100", snapshot_begin_boot_ns="110", snapshot_end_boot_ns="120",
            preprocessed_boot_ns="130", inferred_boot_ns="150", confirmed_boot_ns="160",
            input_may_be_filtered="false", detail="")

    def test_out_of_order_rows_join_by_result_not_queue_order_and_do_not_zero_fill(self):
        result = summarize([self.row("color_draw", 190), self.row(), self.row("hud_poll", 200)])
        self.assertEqual(1, result["intervals"]["publish_to_color_draw"]["n"])
        self.assertAlmostEqual(.00002, result["intervals"]["publish_to_color_draw"]["median_ms"])
        self.assertEqual(1, result["missing_hud_draw"])
        self.assertIsNone(result["intervals"]["publish_to_hud_draw"]["median_ms"])

    def test_incomplete_or_invalid_clock_intervals_are_explicit(self):
        row = self.row()
        row["inferred_boot_ns"] = "129"
        result = summarize([row])
        self.assertEqual(1, result["invalid_intervals"])
        self.assertFalse(result["recording_closed"])
        self.assertIsNone(result["dropped_events"])
        self.assertEqual(0, result["intervals"]["inference"]["n"])


if __name__ == "__main__":
    unittest.main()
