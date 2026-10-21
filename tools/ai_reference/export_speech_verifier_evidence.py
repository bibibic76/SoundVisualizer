"""Publish compact reviewable summary and exact compressed replay evidence.

Generated JSON/gzip contains classifications, timing and public-source paths /
hashes, no audio or model binaries. Refuses to overwrite an existing directory.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    baseline = args.baseline.read_bytes()
    if baseline.startswith(b"\x1f\x8b"):
        baseline = gzip.decompress(baseline)
    raw_report = args.report.read_bytes()
    report = json.loads(raw_report)
    if hashlib.sha256(baseline).hexdigest() != report["baseline_sha256"]:
        raise ValueError("Baseline does not match report")
    compact = {key: value for key, value in report.items() if key != "profiles"}
    compact["full_report_sha256"] = hashlib.sha256(raw_report).hexdigest()
    compact["profiles"] = {key: value["summary"] for key, value in report["profiles"].items()}
    compact["primary_exceptions"] = {
        key: [row for row in report["profiles"][key]["rows"]
              if row["reversions"] or (row["expected"] != "speech" and row["extra_speech_ms"] > 0)]
        for key in ("continuous-p0.9-cost2", "triggered160-p0.9-cost2")}
    args.output_dir.mkdir(parents=True, exist_ok=False)
    (args.output_dir / "baseline.json.gz").write_bytes(gzip.compress(baseline, mtime=0))
    (args.output_dir / "full-report.json.gz").write_bytes(gzip.compress(raw_report, mtime=0))
    (args.output_dir / "summary.json").write_text(json.dumps(compact, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
