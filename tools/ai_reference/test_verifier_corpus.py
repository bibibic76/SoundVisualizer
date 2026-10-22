import copy
import hashlib
from pathlib import Path
import unittest

from verifier_corpus import POLICY_SHA, REQUIRED_STRATA, audit, select_speakers, valid_events
from mapper import map_display_name_to_coarse


def row(name="a", role="holdout_reserved", group=None):
    return {"id": name, "role": role, "groups": [group or name],
            "file_sha256": hashlib.sha256(name.encode()).hexdigest(),
            "pcm_sha256": hashlib.sha256((name + "pcm").encode()).hexdigest(),
            "stratum": "korean_read", "duration_ms": 1000., "events": [],
            "review": {"status": "pending", "reviewer_ids": []},
            "source_url": "https://example.org/" + name, "license": "CC BY 4.0", "attribution": "Example",
            "usage": "attribution_required", "identity_status": "verified", "provenance_status": "upstream_verified"}


def run_audit(rows, exclusions=None, selection=None):
    return audit({"policy_sha256": POLICY_SHA, "rows": rows},
                 exclusions or {"groups": [], "file_sha256": [], "pcm_sha256": []}, selection)


class CorpusTest(unittest.TestCase):
    def test_product_policy_is_frozen_and_human_vocals_are_not_all_negative(self):
        path = Path(__file__).resolve().parents[2] / "app/src/main/java/com/example/soundvisualizer/ai/YamnetThreeClassMapper.kt"
        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), POLICY_SHA)
        for label in ("Speech", "Laughter", "Singing", "Baby cry, infant cry"):
            self.assertEqual(map_display_name_to_coarse(label), "speech")
        for label in ("Sneeze", "Cough", "Breathing", "Music", "Singing bowl"):
            self.assertEqual(map_display_name_to_coarse(label), "ambient")

    def test_selection_is_order_independent_and_limited_per_speaker(self):
        source = [{"id": f"{s}_{i}", "speaker_id": s} for s in range(3) for i in range(7)]
        chosen = select_speakers(source)
        self.assertEqual(chosen, select_speakers(list(reversed(source))))
        self.assertEqual(len(chosen), 15)
        self.assertEqual({r["speaker_id"] for r in chosen}, {0, 1, 2})

    def test_pending_annotations_never_count_as_ready(self):
        report = run_audit([row()])
        self.assertTrue(report["structurally_valid"])
        self.assertFalse(report["ready_for_evaluation"])
        self.assertEqual(report["reviewed_rows"], 0)

    def test_speaker_leak_is_rejected_even_with_distinct_audio(self):
        self.assertTrue(run_audit([row("a", "train", "same-speaker"), row("b", group="same-speaker")])["errors"])

    def test_previous_original_or_decoded_hash_cannot_be_holdout(self):
        for key, value in (("groups", "a"), ("file_sha256", row()["file_sha256"]), ("pcm_sha256", row()["pcm_sha256"])):
            excluded = {"groups": [], "file_sha256": [], "pcm_sha256": []}
            excluded[key] = [value]
            self.assertTrue(run_audit([row()], excluded)["errors"])

    def test_official_test_cannot_become_training(self):
        item = dict(row(role="train"), official_split="test")
        self.assertTrue(run_audit([item])["errors"])

    def test_frozen_reservation_cannot_be_removed_or_reassigned(self):
        frozen = row()
        self.assertTrue(run_audit([], selection=[frozen])["errors"])
        self.assertTrue(run_audit([dict(frozen, role="calibration")], selection=[frozen])["errors"])

    def test_reencoding_identity_hash_cannot_count_as_a_second_original(self):
        a, b = row("a"), row("b")
        b["pcm_sha256"] = a["pcm_sha256"]
        self.assertTrue(run_audit([a, b])["errors"])

    def test_mixture_must_inherit_both_parent_groups_and_same_split(self):
        a, b, mix = row("a"), row("b", "train"), row("mix")
        mix.update(parent_ids=["a", "b"], groups=["a", "b"])
        self.assertTrue(run_audit([a, b, mix])["errors"])
        b["role"] = "holdout_reserved"
        mix["groups"] = ["a"]
        self.assertTrue(run_audit([a, b, mix])["errors"])

    def test_noncommercial_audio_is_not_automatically_training_eligible(self):
        self.assertTrue(run_audit([dict(row(role="train"), usage="research_only")])["errors"])

    def test_cyclic_lineage_does_not_establish_source_independence(self):
        a, b = row("a"), row("b")
        a.update(parent_ids=["b"], groups=["a", "b"])
        b.update(parent_ids=["a"], groups=["a", "b"])
        self.assertTrue(any("Cyclic" in error for error in run_audit([a, b])["errors"]))

    def test_intervals_require_reviewers_full_coverage_and_finite_boundaries(self):
        item = row()
        item.update(review={"status": "adjudicated", "reviewer_ids": ["reviewer-a", "reviewer-b"]},
                    events=[{"start_ms": 0., "end_ms": 1000., "coarse": "speech"}])
        self.assertTrue(valid_events(item))
        for value in (float("nan"), -1., 10.):
            bad = copy.deepcopy(item)
            bad["events"][0]["start_ms"] = value
            self.assertFalse(valid_events(bad))
        bad = copy.deepcopy(item)
        bad["review"]["reviewer_ids"] = ["same", "same"]
        self.assertFalse(valid_events(bad))

    def test_uncertain_cannot_silently_become_negative_ground_truth(self):
        items = []
        for name in REQUIRED_STRATA:
            item = dict(row(name), stratum=name,
                        review={"status": "adjudicated", "reviewer_ids": ["a", "b"]},
                        events=[{"start_ms": 0., "end_ms": 1000., "coarse": "uncertain"}])
            items.append(item)
        report = run_audit(items)
        self.assertTrue(report["structurally_valid"])
        self.assertFalse(report["ready_for_evaluation"])


if __name__ == "__main__":
    unittest.main()
