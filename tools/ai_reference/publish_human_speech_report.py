"""Package diagnostic raw rows losslessly and generate reviewable summaries."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native", type=Path, required=True)
    parser.add_argument("--rate48", type=Path, required=True)
    parser.add_argument("--danger48", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    summaries = {}
    for name, path in (("native", args.native), ("48k", args.rate48), ("danger48k", args.danger48)):
        raw = path.read_bytes()
        data = json.loads(raw)
        packed = gzip.compress(raw, mtime=0)
        target = args.output_dir / (name + ".json.gz")
        target.write_bytes(packed)
        profiles = {}
        for key, report in data["profiles"].items():
            profiles[key] = {k: v for k, v in report.items() if k != "rows"}
            profiles[key]["retracted_cases"] = [{k: r[k] for k in ("id", "category", "phase_ms", "saved_ms", "saved_without_reverted_preview_ms", "reversions", "reverted_preview_ms", "previews")} for r in report["rows"] if r["reversions"]]
        original_danger = data["profiles"]["continuous-cost2"]["rows"]
        policy_comparisons = [r for r in original_danger if "old_policy_danger_first_ms" in r]
        summaries[name] = {k: v for k, v in data.items() if k != "profiles"}
        summaries[name].update(profiles=profiles, raw_sha256=hashlib.sha256(raw).hexdigest(),
            gzip_sha256=hashlib.sha256(packed).hexdigest(), raw_artifact=target.name,
            old_policy_danger_comparisons=len(policy_comparisons),
            old_policy_danger_changed=[dict(id=r["id"], phase_ms=r["phase_ms"],
                old=r["old_policy_danger_first_ms"], new=r["baseline_first_ms"])
                for r in policy_comparisons if r["old_policy_danger_first_ms"] != r["baseline_first_ms"]])
    (args.output_dir / "summary.json").write_text(json.dumps(summaries, indent=2) + "\n")


if __name__ == "__main__":
    main()
