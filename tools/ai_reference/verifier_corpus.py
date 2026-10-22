"""Validation of #416 corpus lineage and annotation readiness; stdlib only."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path

POLICY_SHA = "2202c0769605a0075017405e04ac8cb5c4ed9550695b645c2fdf6e6746acd3ab"
ROLES = {"train", "calibration", "holdout_reserved"}
LABELS = {"speech", "ambient", "danger", "uncertain"}
REQUIRED_STRATA = {"korean_read", "korean_conversation", "sneezing", "coughing",
                   "breathing", "laughing", "crying_baby", "instrumental_music",
                   "singing", "mixed_tv_game", "ambient_to_speech", "speech_to_danger"}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def ranked(items, key):
    return sorted(items, key=lambda item: sha(("verifier-corpus-416/" + str(key(item))).encode()))


def select_speakers(rows, per_speaker=5):
    groups = defaultdict(list)
    for row in rows:
        groups[str(row["speaker_id"])].append(row)
    return [row for speaker in sorted(groups)
            for row in ranked(groups[speaker], lambda r: r["id"])[:per_speaker]]


def valid_events(row):
    """Reviewed intervals form a full partition, including silence/uncertainty.

    Presence of reviewer IDs is a workflow check, not proof that a human listened.
    """
    review = row.get("review", {})
    if review.get("status") != "adjudicated" or len(set(review.get("reviewer_ids", []))) < 2:
        return False
    duration = row.get("duration_ms", 0)
    if not isinstance(duration, (int, float)) or not math.isfinite(duration) or duration <= 0:
        return False
    events = row.get("events", [])
    cursor = 0.
    if not events:
        return False
    for event in events:
        a, b = event.get("start_ms"), event.get("end_ms")
        if (not all(isinstance(t, (int, float)) and math.isfinite(t) for t in (a, b))
                or abs(a - cursor) > .001 or b <= a or b > duration + .001
                or event.get("coarse") not in LABELS):
            return False
        cursor = b
    return abs(cursor - duration) <= .001


def audit(manifest, exclusions, selection=None):
    errors, blockers = [], []
    rows = manifest["rows"]
    if manifest.get("policy_sha256") != POLICY_SHA:
        errors.append("Product policy hash differs; review labels before proceeding")
    if not rows:
        errors.append("Empty corpus")
    ids = [r["id"] for r in rows]
    if len(ids) != len(set(ids)):
        errors.append("Duplicate row IDs")
    prior_groups = set(exclusions["groups"])
    prior_hashes = set(exclusions["file_sha256"]) | set(exclusions["pcm_sha256"])
    owners = defaultdict(set)
    row_ids = set(ids)
    by_id = {r["id"]: r for r in rows}
    visiting, visited = set(), set()

    def visit(identity):
        if identity in visiting:
            errors.append(f"Cyclic audio lineage: {identity}")
            return
        if identity in visited or identity not in by_id:
            return
        visiting.add(identity)
        for parent in by_id[identity].get("parent_ids", []):
            visit(parent)
        visiting.remove(identity)
        visited.add(identity)

    for identity in by_id:
        visit(identity)
    if selection is not None:
        for frozen in selection:
            current = by_id.get(frozen["id"])
            if current is None:
                errors.append(f"{frozen['id']}: frozen candidate removed")
                continue
            if current["role"] != "holdout_reserved":
                errors.append(f"{frozen['id']}: frozen evaluation reservation reassigned")
            for key in ("groups", "source_id", "dataset", "source_url", "filename", "stratum", "usage"):
                if current.get(key) != frozen.get(key):
                    errors.append(f"{frozen['id']}: frozen {key} changed")
    hash_rows = defaultdict(list)
    for row in rows:
        role = row.get("role")
        groups = set(row.get("groups", []))
        if role not in ROLES or not groups:
            errors.append(f"{row['id']}: missing role / lineage")
        hashes = [row.get("file_sha256"), row.get("pcm_sha256")]
        for h in hashes:
            if h:
                hash_rows[h].append(row["id"])
        if any(not isinstance(h, str) or len(h) != 64 or any(c not in '0123456789abcdef' for c in h) for h in hashes):
            errors.append(f"{row['id']}: malformed hashes")
        if role == "holdout_reserved" and (groups & prior_groups or set(hashes) & prior_hashes):
            errors.append(f"{row['id']}: overlaps previous experiments")
        if row.get("official_split") == "test" and role != "holdout_reserved":
            errors.append(f"{row['id']}: official test cannot become training/calibration")
        if row.get("reservation") == "holdout_reserved" and role != "holdout_reserved":
            errors.append(f"{row['id']}: frozen holdout cannot be reassigned")
        if role == "train" and row.get("usage") != "attribution_required":
            errors.append(f"{row['id']}: training use has not been cleared")
        if not all(row.get(k) for k in ("source_url", "license", "attribution")):
            errors.append(f"{row['id']}: missing source/license/attribution")
        for identity in groups | {f"hash:{h}" for h in hashes}:
            owners[identity].add(role)
        parents = row.get("parent_ids", [])
        if not set(parents) <= row_ids:
            errors.append(f"{row['id']}: missing parent")
        for parent_id in parents:
            parent = next((r for r in rows if r["id"] == parent_id), None)
            if parent and (not set(parent["groups"]) <= groups or role != parent["role"]):
                errors.append(f"{row['id']}: derivative must inherit every parent group and role")
        if not valid_events(row):
            blockers.append(f"{row['id']}: annotations need two reviewers and complete intervals")
        elif any(e["coarse"] == "uncertain" for e in row["events"]):
            blockers.append(f"{row['id']}: uncertain intervals remain")
        if row.get("provenance_status") != "upstream_verified":
            blockers.append(f"{row['id']}: original-source provenance not fully verified")
        if row.get("identity_status") != "verified":
            blockers.append(f"{row['id']}: speaker/performer identity needs review")
        if row.get("usage") == "research_only":
            blockers.append(f"{row['id']}: noncommercial material; product use not cleared")
    errors.extend(f"Cross-split identity: {identity}" for identity, roles in owners.items() if len(roles) > 1)
    errors.extend(f"Duplicate audio: {sorted(set(ids))}" for ids in hash_rows.values() if len(set(ids)) > 1)
    coverage = Counter(r["stratum"] for r in rows)
    missing = sorted(REQUIRED_STRATA - set(coverage))
    blockers.extend(f"Missing stratum: {name}" for name in missing)
    blockers.extend(manifest.get("unresolved", []))
    return {"schema": 1, "structurally_valid": not errors, "ready_for_evaluation": not errors and not blockers,
            "rows": len(rows), "roles": dict(Counter(r["role"] for r in rows)),
            "coverage": dict(sorted(coverage.items())), "reviewed_rows": sum(valid_events(r) for r in rows),
            "missing_strata": missing, "errors": errors, "blockers": blockers,
            "limitations": "Exact hashes and declared lineage only; not proof of near-duplicate absence or pretraining disjointness."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--exclusions", type=Path, required=True)
    parser.add_argument("--selection", type=Path, required=True)
    parser.add_argument("--require-ready", action="store_true")
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    raw_exclusions = args.exclusions.read_bytes()
    if sha(raw_exclusions) != manifest["exclusions_sha256"]:
        parser.error("Exclusion inventory changed")
    raw_selection = args.selection.read_bytes()
    if sha(raw_selection) != manifest["selection_sha256"]:
        parser.error("Frozen selection changed")
    report = audit(manifest, json.loads(raw_exclusions), json.loads(raw_selection))
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(2 if report["errors"] or (args.require_ready and not report["ready_for_evaluation"]) else 0)


if __name__ == "__main__":
    main()
