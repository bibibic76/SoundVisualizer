import unittest
from summarize_silero_latency import summarize


def row(stage, result, at, coarse="ambient", info="", session="1"):
    result_row = dict(schema="1", stage=stage, result_id=str(result), pipeline_session_id=session,
        session_id=session, at_boot_ns=str(int(at * 1e6)), detail=info, mapping_signature="default",
        capture_session_id="1", read_return_boot_ns="1", snapshot_begin_boot_ns="1",
        snapshot_end_boot_ns="2", preprocessed_boot_ns="3", inferred_boot_ns="4",
        confirmed_boot_ns="5", input_may_be_filtered="false", confirmed_coarse=coarse)
    return result_row


def regular(result, at, coarse="ambient", session="1"):
    return row("result", result, at, coarse, "kind=regular;vad_calls=0;vad_compute_ns=0;vad_resets=0;vad_failed=false", session)


class PreviewSummaryTest(unittest.TestCase):
    def sample(self):
        return [regular(1, 100), row("result", 2, 200, info="kind=preview;parent_result_id=1;expires_boot_ns=400000000"),
                row("color_draw", 2, 210), regular(3, 350, "speech"), row("color_draw", 3, 360)]

    def test_preview_is_not_confirmed_but_observed_draw_lead_is_measured(self):
        result = summarize(self.sample())
        self.assertEqual(150, result["observed_preview_lead"]["color_draw"]["median_ms"])
        self.assertEqual(1, result["censored_draw_pairs"]["hud_draw"])
        self.assertEqual(1, result["next_regular_speech"])

    def test_retraction_and_missing_future_are_not_counted_as_improvements(self):
        rows = self.sample()
        rows[3]["confirmed_coarse"] = "ambient"
        self.assertEqual(1, summarize(rows)["next_regular_not_speech"])
        self.assertEqual(0, summarize(rows)["observed_preview_lead"]["color_draw"]["n"])
        self.assertEqual(1, summarize(rows[:3])["unresolved"])

    def test_sessions_and_mapping_are_never_cross_joined(self):
        rows = self.sample()
        rows[3]["pipeline_session_id"] = "2"
        self.assertEqual(1, summarize(rows)["unresolved"])
        rows = self.sample()
        rows[3]["mapping_signature"] = "edited"
        self.assertEqual(1, summarize(rows)["unresolved"])


if __name__ == "__main__":
    unittest.main()
