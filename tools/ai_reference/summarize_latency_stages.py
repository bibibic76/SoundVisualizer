"""Decompose baseline replay latency. Not Android timing or semantic onset truth."""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np


def summarize(report):
    groups = {}
    for label in ("speech", "danger"):
        cases = [r for r in report["rows"] if r["expected"] == label]
        stages = []
        missed = 0
        for row in cases:
            onset = row["baseline"]["activity_onset_ms"]
            events = row["baseline_events"]
            eligible = [e for e in events if onset is not None and e["snapshot_ms"] >= onset
                        and e["raw"] == label and e["meets_threshold"]]
            confirmed = next((e for e in eligible if e["ui"] == label), None)
            if confirmed is None:
                missed += 1
                continue
            first = eligible[0]
            # These paired components sum exactly to first confirmed latency.
            stages.append((first["snapshot_ms"] - onset,
                           first["complete_ms"] - first["snapshot_ms"],
                           confirmed["complete_ms"] - first["complete_ms"]))
        values = np.asarray(stages)
        names = ("onset_to_first_eligible_snapshot", "first_compute_assumption",
                 "first_eligible_completion_to_confirmation")
        groups[label] = {
            "conditions": len(cases), "detected": len(stages), "missed": missed,
            "components_median_p95_ms": {
                name: np.percentile(values[:, i], [50, 95]).tolist()
                for i, name in enumerate(names)
            } if stages else {},
            "confirmation_wait_at_least_250ms": sum(s[2] >= 250 for s in stages),
            "total_median_p95_ms": np.percentile(values.sum(axis=1), [50, 95]).tolist() if stages else [],
        }
    return {"manifest_sha256": report["manifest_sha256"], "model_sha256": report["model_sha256"],
            "phase_ms": report["phase_ms"], "inference_ms": report["inference_ms"],
            "limitations": "Repeated diagnostic corpus, native rates, silence-to-file only; "
            "first component mixes model evidence and sampling phase, not pure model latency. "
            "Component percentiles must not be added. No Android/render/CPU contention measurement.",
            "groups": groups}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    raw = args.input.read_bytes()
    result = summarize(json.loads(raw))
    result["source_report_sha256"] = hashlib.sha256(raw).hexdigest()
    with args.output.open("x") as out:
        json.dump(result, out, indent=2)
    print(json.dumps(result["groups"], indent=2))
