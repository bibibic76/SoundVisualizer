"""Read-only fixed-cadence replay of a {path, expected, policy, dataset} manifest.

Uses current reference frontend/mapper/postprocessor. No Android mixer, silence
gate, inference duration, renderer or haptic simulation. Source files have no
event-time annotations, so this report does not estimate detection latency.
"""
import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path

import numpy as np

from classifier import ReferenceClassifier
from compare_logmel_frontends import qualcomm_source_log_mel
from preprocess import capture_samples_for_one_yamnet_window
from wav_io import load_wav_as_capture_mono

LABELS = ("ambient", "speech", "danger")


def metrics(rows):
    matrix = {expected: {predicted: 0 for predicted in LABELS} for expected in LABELS}
    for row in rows:
        matrix[row["expected"]][row["majority"]] += 1
    per_class = {}
    for label in LABELS:
        true_positive = matrix[label][label]
        support = sum(matrix[label].values())
        predicted = sum(matrix[expected][label] for expected in LABELS)
        precision = true_positive / predicted if predicted else 0.0
        recall = true_positive / support if support else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        per_class[label] = dict(support=support, precision=precision, recall=recall, f1=f1)
    return {
        "files": len(rows), "confusion": matrix, "per_class": per_class,
        "accuracy": sum(matrix[label][label] for label in LABELS) / len(rows) if rows else None,
        "macro_f1": sum(item["f1"] for item in per_class.values()) / len(LABELS),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--step-ms", type=int, default=250)
    parser.add_argument("--phase-ms", type=int, default=0)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.step_ms <= 0 or args.phase_ms < 0:
        parser.error("step-ms must be positive and phase-ms must be nonnegative")
    if args.output.exists():
        parser.error("output already exists")
    raw_manifest = args.manifest.read_bytes()
    data = json.loads(raw_manifest)
    manifest = data["rows"] if isinstance(data, dict) else data
    for item in manifest:
        if not Path(item["path"]).is_file():
            raise FileNotFoundError(item["path"])
        if item["policy"] == "primary" and item["expected"] not in LABELS:
            raise ValueError(f"Invalid primary label: {item}")
    classifier = ReferenceClassifier(args.model_dir, load_booster=False, log_mel_provider=qualcomm_source_log_mel)
    rows = []
    for index, item in enumerate(manifest, 1):
        mono, rate, channels = load_wav_as_capture_mono(item["path"])
        need = capture_samples_for_one_yamnet_window(rate)
        if mono.size < need:
            mono = np.concatenate((mono, np.zeros(rate, dtype=np.float32)))
        mono = np.concatenate((np.zeros(round(rate * args.phase_ms / 1000), dtype=np.float32), mono))
        step = max(1, round(rate * args.step_ms / 1000))
        classifier.reset_state()
        counts = Counter()
        first_expected = None
        danger_held_ms = 0.0
        previous_time = None
        previous_ui = None
        for start in range(0, mono.size, step):
            end = min(start + step, mono.size)
            if end < need:
                continue
            _, trace, _ = classifier.classify_mono_capture_once(mono[end - need:end], rate)
            if trace is None:
                raise RuntimeError(f"No prediction: {item['path']}")
            time_ms = end * 1000 / rate
            if previous_ui == "danger":
                danger_held_ms += time_ms - previous_time
            previous_time, previous_ui = time_ms, trace.ui_coarse
            counts[trace.ui_coarse] += 1
            if first_expected is None and trace.ui_coarse == item.get("expected"):
                first_expected = time_ms
        if not counts:
            raise RuntimeError(f"No frames: {item['path']}")
        rows.append({
            **item, "sample_rate": rate, "channels": channels,
            "majority": max(LABELS, key=lambda label: (counts[label], label == "danger", label == "speech")),
            "ui_counts": dict(counts), "frames": sum(counts.values()),
            "danger_seen": counts["danger"] > 0,
            "danger_held_ms": danger_held_ms,
            "first_expected_result_from_replay_start_ms": first_expected,
        })
        if index % 100 == 0:
            print(f"{args.step_ms}ms: {index}/{len(manifest)}", flush=True)
    primary = [row for row in rows if row["policy"] == "primary"]
    summary = {"primary": metrics(primary)}
    for expected in ("ambient", "danger"):
        group = [row for row in primary if row["expected"] == expected]
        summary[expected + "_any_danger"] = dict(files=len(group), seen=sum(row["danger_seen"] for row in group))
    summary["inferences"] = sum(row["frames"] for row in rows)
    report = {
        "step_ms": args.step_ms, "phase_ms": args.phase_ms,
        "manifest_sha256": hashlib.sha256(raw_manifest).hexdigest(),
        "model_sha256": {name: hashlib.sha256((args.model_dir / name).read_bytes()).hexdigest()
                         for name in ("yamnet.onnx", "yamnet.data", "yamnet_class_map.csv")},
        "limitations": "Offline windows only; no event annotations or Android timing. First expected result is not event latency. "
                        "Danger held time ends at the final replay sample and excludes post-file decay. Reused corpus is not independent holdout.",
        "summary": summary, "rows": rows,
    }
    with args.output.open("x", encoding="utf-8") as output:
        json.dump(report, output, ensure_ascii=False, indent=2)
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
