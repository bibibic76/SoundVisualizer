"""Freeze-and-test #407 under declared compute/wakeup stress, not device timing.

Reuses model outputs only for identical capture snapshots. Each arm has separate
hysteresis/gate/scheduling state; profiles are deterministic on wall-time buckets.
"""
import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path

import numpy as np

from classifier import ReferenceClassifier
from compare_logmel_frontends import qualcomm_source_log_mel
from evaluate_responsive_inference import captures, paired_summary, replay
from preprocess import downmix_to_mono
from responsive_schedule import ProductionSlots, RuntimeProfile
from wav_io import load_wav_float_interleaved


def profile_for(name):
    if name.startswith("fixed"):
        value = float(name.removeprefix("fixed"))
        if value < 0:
            raise ValueError("Negative cost")
        return RuntimeProfile("fixed", value)
    if name not in ("variable", "wake-jitter", "early-spike", "mixed"):
        raise ValueError(f"Unknown profile: {name}")
    return RuntimeProfile(name, 20)


def summarize(rows):
    result = paired_summary(rows, "confirm_active")
    result["paired_majority_regressions"] = [
        {k: row[k] for k in ("path", "dataset", "phase_ms", "expected")}
        for row in rows if row["baseline"]["majority"] == row["expected"] and
        row["confirm_active"]["majority"] != row["expected"]
    ]
    result["new_ambient_danger_conditions"] = sum(
        row["expected"] == "ambient" and not row["baseline"]["danger_seen"] and
        row["confirm_active"]["danger_seen"] for row in rows
    )
    for arm in ("baseline", "confirm_active"):
        result[arm]["runtime"] = {key: sum(row[arm][key] for row in rows)
                                   for key in ("early_attempts", "early_accepted", "early_rejected",
                                               "deadline_aborts", "simulated_busy_ms")}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--dataset", action="append")
    parser.add_argument("--profiles", nargs="+", default=["fixed25", "fixed50", "fixed100", "fixed200", "fixed400", "variable", "wake-jitter", "early-spike", "mixed"])
    parser.add_argument("--phase-ms", nargs="+", type=int, default=[0, 100, 200])
    args = parser.parse_args()
    if args.output.exists() or any(phase < 0 for phase in args.phase_ms):
        parser.error("Output must be new; phases must be nonnegative")
    profiles = {name: profile_for(name) for name in args.profiles}
    raw = args.manifest.read_bytes()
    manifest = json.loads(raw)
    if isinstance(manifest, dict):
        manifest = manifest["rows"]
    manifest = [row for row in manifest if row["policy"] == "primary" and
                (not args.dataset or row["dataset"] in args.dataset)]
    if not manifest:
        parser.error("Empty selection")
    classifier = ReferenceClassifier(args.model_dir, load_booster=False, log_mel_provider=qualcomm_source_log_mel)
    reports = {name: {"rows": []} for name in profiles}
    for index, item in enumerate(manifest, 1):
        source, rate, channels = load_wav_float_interleaved(item["path"])
        for phase in args.phase_ms:
            prefix = 1500 + phase
            interleaved = np.concatenate((np.zeros(round(rate * prefix / 1000) * channels, dtype=np.float32),
                                          source, np.zeros(round(rate * 1.5) * channels, dtype=np.float32)))
            mono = downmix_to_mono(interleaved, channels)
            chunks = captures(interleaved, rate, channels)
            stop = prefix + source.size / channels * 1000 / rate
            cache = {}
            for name, profile in profiles.items():
                row = {**item, "phase_ms": phase}
                for arm in ("baseline", "confirm_active"):
                    experiment = arm == "confirm_active"
                    row[arm] = replay(mono, chunks, rate, ProductionSlots(profile=profile, followup=False),
                                      classifier, cache, prefix, stop, item["expected"], profile.fixed_ms,
                                      False, experiment, experiment, consistent=experiment,
                                      activity=experiment, runtime_profile=profile)
                reports[name]["rows"].append(row)
        if index % 10 == 0 or index == len(manifest):
            print(f"runtime replay {index}/{len(manifest)}", flush=True)
    for report in reports.values():
        report["summary"] = summarize(report["rows"])
        report["by_dataset"] = {dataset: summarize([r for r in report["rows"] if r["dataset"] == dataset])
                                for dataset in sorted({r["dataset"] for r in manifest})}
    output = dict(manifest_sha256=hashlib.sha256(raw).hexdigest(),
                  file_support=dict(Counter((r["expected"]) for r in manifest)),
                  dataset_support=dict(Counter(r["dataset"] for r in manifest)),
                  model_sha256={name: hashlib.sha256((args.model_dir / name).read_bytes()).hexdigest()
                                for name in ("yamnet.onnx", "yamnet.data", "yamnet_class_map.csv")},
                  phase_ms=args.phase_ms, scheduler="production-rephased", profiles=reports,
                  limitations="Synthetic compute and wake lateness, not Android timing, CPU or power. "
                              "Onset is a PCM marker, not annotated semantic event. "
                              "early-spike deliberately assumes only speculative calls become slow.")
    with args.output.open("x", encoding="utf-8") as handle:
        json.dump(output, handle, ensure_ascii=False, indent=2)
    for name, report in reports.items():
        s = report["summary"]
        print(json.dumps(dict(profile=name, speech=s["speech"], danger=s["danger"],
                              majority_regressions=len(s["paired_majority_regressions"]),
                              new_ambient_danger=s["new_ambient_danger_conditions"],
                              runtime=s["confirm_active"]["runtime"]), ensure_ascii=False))


if __name__ == "__main__":
    main()
