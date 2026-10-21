import copy
import unittest

from speech_verifier_policy import Block, decide, summarize_case


def parent(**overrides):
    return dict(snapshot_ms=1000., complete_ms=1025., raw="speech", meets_threshold=True,
                ui="ambient", candidate_streak=1, **overrides)


def frames(start=1024., count=7, probability=.99):
    return [Block(start + i * 32., start + (i + 1) * 32., start + (i + 1) * 32., probability)
            for i in range(count)]


class SpeechVerifierPolicyTest(unittest.TestCase):
    def test_three_fresh_blocks_not_three_old_scores(self):
        # First frame begins before parent completion; cannot count it.
        result, _, _ = decide(frames(), parent(), 1275.)
        self.assertEqual(result["start_ms"], 1154.)
        self.assertEqual(result["end_ms"], 1275.)

    def test_low_probability_resets_consecutive_requirement(self):
        blocks = frames(count=5)
        b = blocks[2]
        blocks[2] = Block(b.start, b.end, b.available, .1)
        self.assertIsNone(decide(blocks, parent(), 1275.)[0])

    def test_gap_cannot_count_as_contiguous_evidence(self):
        blocks = frames(count=4)
        self.assertIsNone(decide([blocks[1], blocks[3], *frames(1216., 1)], parent(), 1275.)[0])

    def test_capture_availability_not_audio_end_controls_acceptance(self):
        delayed = [Block(b.start, b.end, b.available + 150., b.probability) for b in frames()]
        self.assertIsNone(decide(delayed, parent(), 1275.)[0])

    def test_triggered_warmup_is_not_free_and_deadline_is_strict(self):
        history = frames(864., 5)
        preview, calls, late = decide(history + frames(), parent(), 1275., mode="triggered160", call_ms=32.)
        self.assertIsNone(preview)
        self.assertGreater(calls, 5)
        self.assertEqual(late, 1)

    def test_next_normal_danger_wins_even_if_earlier_than_planned(self):
        self.assertIsNone(decide(frames(), parent(), 1120.)[0])
        preview, _, _ = decide(frames(), parent(), 1175.)
        self.assertEqual(preview["end_ms"], 1175.)

    def test_noneligible_states_never_publish_or_modify_input(self):
        for changes in ({"ui": "danger"}, {"raw": "ambient"}, {"meets_threshold": False}, {"candidate_streak": 2}):
            event = parent()
            event.update(changes)
            original = copy.deepcopy(event)
            self.assertEqual(decide(frames(), event, 1275.), (None, 0, 0))
            self.assertEqual(event, original)

    def test_expiry_without_next_result_is_bounded(self):
        self.assertEqual(decide(frames(), parent(), float("inf"))[0]["end_ms"], 1275.)

    def test_below_threshold_holding_old_ambient_is_counted_as_reversion(self):
        event = parent()
        next_event = dict(event, snapshot_ms=1250., complete_ms=1275., meets_threshold=False)
        row = {"expected": "ambient", "baseline_events": [event, next_event],
               "baseline": {"first_valid_expected_ms": None, "activity_onset_ms": 900.}}
        preview = decide(frames(), event, 1275.)[0]
        metrics = summarize_case(row, [preview], 2000.)
        self.assertEqual(metrics["extra_speech_ms"], 121.)
        self.assertEqual(metrics["reversions"], 1)
        self.assertIsNone(metrics["candidate_first_ms"])

    def test_failed_verifier_preserves_baseline_detection(self):
        row = {"expected": "speech", "baseline_events": [],
               "baseline": {"first_valid_expected_ms": 489., "activity_onset_ms": 900.}}
        self.assertEqual(summarize_case(row, [], 2000.)["saved_ms"], 0.)

    def test_exposure_does_not_disappear_when_activity_marker_is_missing(self):
        row = {"expected": "ambient", "baseline_events": [],
               "baseline": {"first_valid_expected_ms": None, "activity_onset_ms": None}}
        preview = {"start_ms": 1100., "end_ms": 1200.}
        self.assertEqual(summarize_case(row, [preview], 2000., source_start=1150.)["extra_speech_ms"], 50.)


if __name__ == "__main__":
    unittest.main()
