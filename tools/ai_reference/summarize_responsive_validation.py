"""Publish compact review evidence without committing audio or local absolute paths."""
import argparse
import hashlib
import json
from pathlib import Path


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def portable(value):
    if isinstance(value, list):
        return [portable(item) for item in value]
    if isinstance(value, dict):
        return {key: (Path(item).name if key == "path" else portable(item))
                for key, item in value.items()}
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--new-manifest", type=Path, required=True)
    parser.add_argument("--new-report", type=Path, required=True)
    parser.add_argument("--previous-report", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Refusing to overwrite evidence")
    manifest = json.loads(args.new_manifest.read_bytes())
    output = dict(app_under_test="0bbc2ea8fbfb1e81cb2a78626cc00099b34a4ac8",
                  unchanged_app_code=True,
                  new_audio_manifest_sha256=sha(args.new_manifest),
                  selection_and_sources=portable(manifest), reports={})
    tools = Path(__file__).parent
    output["harness_sha256"] = {name: sha(tools / name) for name in (
        "responsive_schedule.py", "evaluate_responsive_inference.py",
        "evaluate_responsive_runtime.py", "prepare_responsive_validation.py")}
    for label, path in (("new_audio", args.new_report), ("previous_representative", args.previous_report)):
        report = json.loads(path.read_bytes())
        compact = {key: value for key, value in report.items() if key != "profiles"}
        compact["full_report_sha256"] = sha(path)
        compact["profiles"] = {}
        for name, profile in report["profiles"].items():
            slowdowns = []
            for row in profile["rows"]:
                old = row["baseline"]["first_valid_expected_ms"]
                new = row["confirm_active"]["first_valid_expected_ms"]
                if old is not None and new is not None and new > old + .001:
                    slowdowns.append(dict(path=Path(row["path"]).name, expected=row["expected"],
                                          phase_ms=row["phase_ms"], extra_delay_ms=new - old))
            compact["profiles"][name] = dict(summary=portable(profile["summary"]),
                                            by_dataset=portable(profile["by_dataset"]),
                                            paired_latency_regressions=slowdowns)
        output["reports"][label] = compact
    with args.output.open("x", encoding="utf-8") as handle:
        json.dump(output, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


if __name__ == "__main__":
    main()
