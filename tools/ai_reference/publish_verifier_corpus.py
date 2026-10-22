"""Publish validated metadata evidence, never audio. New directory only."""
import argparse
import json
from pathlib import Path

from verifier_corpus import audit, sha


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    files = {name: (args.source_dir / name).read_bytes() for name in
             ("manifest.json", "exclusions.json", "selection.json", "review-queue.json")}
    manifest = json.loads(files["manifest.json"])
    for key in ("exclusions", "selection"):
        if manifest[key + "_sha256"] != sha(files[key + ".json"]):
            raise ValueError(f"Changed {key}")
    report = audit(manifest, json.loads(files["exclusions.json"]), json.loads(files["selection.json"]))
    if report["errors"]:
        raise ValueError(report["errors"])
    args.output_dir.mkdir(parents=True, exist_ok=False)
    for name, raw in files.items():
        (args.output_dir / name).write_bytes(raw)
    (args.output_dir / "audit.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"rows": report["rows"], "ready_for_evaluation": report["ready_for_evaluation"],
                      "manifest_sha256": sha(files["manifest.json"])}, ensure_ascii=False))


if __name__ == "__main__":
    main()
