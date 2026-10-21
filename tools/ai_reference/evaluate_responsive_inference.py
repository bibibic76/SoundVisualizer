"""Controlled capture replay for onset-triggered borrowed-slot experiments.

Uses unchanged YAMNet/mapper/threshold/hysteresis. Simulates 512-frame capture
chunks, the production silence gate, and a declared fixed inference duration.
Activity onset is a PCM threshold marker, not a human-annotated semantic event.
No Android mixer, rendering, physical haptic timing, heat or power simulation.
"""
import argparse
import hashlib
import json
import math
from collections import Counter
from pathlib import Path

import numpy as np

from classifier import ReferenceClassifier
from compare_logmel_frontends import qualcomm_source_log_mel
from preprocess import capture_samples_for_one_yamnet_window, downmix_to_mono
from responsive_schedule import BorrowedSlots, ChangeDetector, ProductionSlots, RephasedSlots, RuntimeProfile, SilenceGate
from wav_io import load_wav_float_interleaved


def captures(interleaved, rate, channels):
    frames = interleaved.reshape(-1, channels)
    output = []
    for start in range(0, len(frames), 512):
        block = frames[start:start + 512]
        samples = block.astype(np.float64)
        end = start + len(block)
        output.append((end * 1000 / rate, end, float(np.max(np.abs(block))), float(np.mean(samples * samples))))
    return output


def replay(mono, chunks, rate, scheduler, classifier, cache, start_ms, stop_ms, expected, duration_ms, adaptive, confirmation=False, fallback=False, source_onset=None, consistent=False, activity=False, runtime_profile=None):
    gate = SilenceGate()
    detector = ChangeDetector()
    classifier.reset_state()
    need = capture_samples_for_one_yamnet_window(rate)
    capture_index = 0
    captured = 0
    pending = None
    timeline = [(0.0, "ambient")]
    predictions = 0
    first_correct = None
    confirmation_source = None
    active_since = None
    last_capture = -math.inf
    early_attempts = early_accepted = early_rejected = deadline_aborts = 0
    simulated_busy_ms = 0.0

    def sustained(now):
        return active_since is not None and now - active_since >= 100 and now - last_capture <= 100
    onset = source_onset if source_onset is not None else next((time for time, _, peak, _ in chunks if time >= start_ms and peak > .01), None)
    cutoff = chunks[-1][0]
    while True:
        capture_time = chunks[capture_index][0] if capture_index < len(chunks) else math.inf
        complete_time = pending[0] if pending else math.inf
        now, kind = min((capture_time, 0), (complete_time, 1), (scheduler.deadline, 2))
        if now > cutoff:
            break
        if kind == 0:
            time, captured, peak, power = chunks[capture_index]
            last_capture = time
            if peak <= .01:
                active_since = None
            elif active_since is None:
                active_since = time
            gate.capture(time, peak)
            change = detector.update(time, peak, power)
            if adaptive and change and captured >= need:
                scheduler.on_change(time)
            capture_index += 1
        elif kind == 1:
            _, snapshot_ms, result, old_regular, speculative_target, inference_cost = pending
            rejected = (speculative_target is not None and (
                not result.meets_threshold or result.coarse_class != speculative_target[0] or
                (consistent and (result.yamnet_display_name != speculative_target[1] or result.confidence < speculative_target[2]))
                or (activity and not sustained(now))
            ))
            if fallback and rejected:
                early_rejected += 1
                scheduler.regular = max(old_regular, scheduler.last_finished + 100.0)
                scheduler.early = None
                scheduler.borrowed = False
                gate.complete(snapshot_ms)
                pending = None
                confirmation_source = None
                continue
            if speculative_target is not None:
                early_accepted += 1
            classifier._apply_coarse_hysteresis(result)
            ui = classifier._confirmed_coarse
            timeline.append((now, ui))
            if (onset is not None and first_correct is None and snapshot_ms >= onset and
                    ui == expected and result.coarse_class == expected and result.meets_threshold):
                first_correct = now - onset
            gate.complete(snapshot_ms)
            pending = None
            if (confirmation and (not fallback or inference_cost <= 25) and
                    result.meets_threshold and result.coarse_class != ui and
                    result.coarse_class != "danger" and classifier._candidate_streak == 1 and
                    (not consistent or (ui == "ambient" and result.coarse_class == "speech"))):
                scheduler.on_change(now)
                if scheduler.early is not None:
                    confirmation_source = result
        else:
            if (scheduler.early is not None and isinstance(scheduler, ProductionSlots) and
                    not scheduler.can_start_early(now)):
                scheduler.early = None
                scheduler.borrowed = False
                deadline_aborts += 1
                continue
            if activity and scheduler.early is not None and not sustained(now):
                scheduler.early = None
                scheduler.borrowed = False
                continue
            run = captured >= need and gate.is_open(now)
            inference_cost = 0.0
            if run:
                if pending is not None:
                    raise AssertionError("Overlapping inference")
                if captured not in cache:
                    cache[captured] = classifier.classify_mono_capture_once(
                        mono[captured - need:captured], rate, apply_hysteresis=False,
                    )[0]
                result = cache[captured]
                speculative_target = None
                if fallback and scheduler.early is not None:
                    speculative_target = (
                        classifier._candidate_coarse,
                        confirmation_source.yamnet_display_name if confirmation_source else None,
                        confirmation_source.confidence if confirmation_source else 0,
                    )
                inference_cost = runtime_profile.duration(now, speculative_target is not None) if runtime_profile else duration_ms
                if speculative_target is not None:
                    early_attempts += 1
                pending = (now + inference_cost, now, result, scheduler.regular, speculative_target, inference_cost)
                predictions += 1
                simulated_busy_ms += inference_cost
            scheduler.complete(now, inference_cost)
    timeline.append((cutoff, timeline[-1][1]))
    held = Counter()
    danger_ms = 0.0
    for (time, ui), (end, _) in zip(timeline, timeline[1:]):
        overlap = max(0.0, min(end, stop_ms) - max(time, start_ms))
        held[ui] += overlap
        if ui == "danger":
            danger_ms += max(0.0, end - max(time, start_ms))
    return {
        "first_valid_expected_ms": first_correct if expected in ("speech", "danger") else None,
        "activity_onset_ms": onset, "predictions": predictions, "advances": scheduler.advances,
        "majority": max(("ambient", "speech", "danger"), key=lambda label: (held[label], label == "danger", label == "speech")),
        "danger_ms_with_tail": danger_ms, "danger_seen": danger_ms > 0,
        "early_attempts": early_attempts, "early_accepted": early_accepted,
        "early_rejected": early_rejected, "deadline_aborts": deadline_aborts,
        "simulated_busy_ms": simulated_busy_ms,
    }


def paired_summary(rows, candidate):
    primary = [row for row in rows if row["policy"] == "primary"]
    result = {}
    for name in ("baseline", candidate):
        arm = [row[name] for row in primary]
        labels = ("ambient", "speech", "danger")
        matrix = {label: {predicted: 0 for predicted in labels} for label in labels}
        for row in primary:
            matrix[row["expected"]][row[name]["majority"]] += 1
        per_class = {}
        for label in labels:
            correct = matrix[label][label]
            support = sum(matrix[label].values())
            predicted = sum(matrix[expected][label] for expected in labels)
            per_class[label] = {
                "support": support, "recall": correct / support if support else None,
                "precision": correct / predicted if predicted else None,
                "f1": 2 * correct / (support + predicted) if support + predicted else 0,
            }
        result[name] = {
            "majority_correct": sum(item["majority"] == row["expected"] for item, row in zip(arm, primary)),
            "predictions": sum(row[name]["predictions"] for row in rows),
            "ambient_any_danger": sum(row[name]["danger_seen"] for row in primary if row["expected"] == "ambient"),
            "ambient_danger_ms": sum(row[name]["danger_ms_with_tail"] for row in primary if row["expected"] == "ambient"),
            "confusion": matrix, "per_class": per_class,
            "macro_f1": sum(value["f1"] for value in per_class.values()) / 3,
        }
    for label in ("speech", "danger"):
        group = [row for row in primary if row["expected"] == label and row["baseline"]["activity_onset_ms"] is not None]
        pairs = [row for row in group if row["baseline"]["first_valid_expected_ms"] is not None and row[candidate]["first_valid_expected_ms"] is not None]
        baseline = [row["baseline"]["first_valid_expected_ms"] for row in pairs]
        candidate_delays = [row[candidate]["first_valid_expected_ms"] for row in pairs]
        differences = [a - b for a, b in zip(baseline, candidate_delays)]
        result[label] = {
            "cases": len(group), "paired_detected": len(pairs),
            "baseline_missed": sum(row["baseline"]["first_valid_expected_ms"] is None for row in group),
            "candidate_missed": sum(row[candidate]["first_valid_expected_ms"] is None for row in group),
            "lost": sum(row["baseline"]["first_valid_expected_ms"] is not None and row[candidate]["first_valid_expected_ms"] is None for row in group),
            "gained": sum(row["baseline"]["first_valid_expected_ms"] is None and row[candidate]["first_valid_expected_ms"] is not None for row in group),
            "improved": sum(delta > .001 for delta in differences),
            "worsened": sum(delta < -.001 for delta in differences),
            "baseline_median_p95_ms": np.percentile(baseline, [50, 95]).tolist() if pairs else None,
            "candidate_median_p95_ms": np.percentile(candidate_delays, [50, 95]).tolist() if pairs else None,
            "saved_median_ms": float(np.median(differences)) if pairs else None,
            "worst_regression_ms": max([0.0, *(-delta for delta in differences)]),
        }
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--dataset", action="append")
    parser.add_argument("--phase-ms", nargs="+", type=int, default=[0, 100, 200])
    parser.add_argument("--inference-ms", type=int, default=25)
    parser.add_argument("--runtime-profile", choices=["fixed", "variable", "wake-jitter", "early-spike", "mixed"])
    parser.add_argument("--limit", type=int)
    parser.add_argument("--background", type=Path, help="Optional 0dB RMS diagnostic mix; source files remain unchanged")
    parser.add_argument("--sample-rate", type=int, help="Explicitly restrict diagnostic subset to one native sample rate")
    parser.add_argument("--expected", nargs="+", choices=["ambient", "speech", "danger"])
    parser.add_argument("--arms", nargs="+", default=["advance50", "advance100", "rephase50", "confirm100", "confirm_safe100", "confirm_consistent", "confirm_active", "onset_confirm50"])
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Output already exists")
    if args.inference_ms < 0 or any(phase < 0 for phase in args.phase_ms):
        parser.error("Times must be nonnegative")
    if args.runtime_profile and args.arms != ["confirm_active"]:
        parser.error("Production runtime profiles support only --arms confirm_active")
    raw = args.manifest.read_bytes()
    manifest = json.loads(raw)
    if isinstance(manifest, dict):
        manifest = manifest["rows"]
    if args.dataset:
        manifest = [row for row in manifest if row["dataset"] in args.dataset]
    if args.limit:
        manifest = manifest[:args.limit]
    if args.expected:
        manifest = [row for row in manifest if row["expected"] in args.expected]
    for row in manifest:
        if not Path(row["path"]).is_file():
            raise FileNotFoundError(row["path"])
    classifier = ReferenceClassifier(args.model_dir, load_booster=False, log_mel_provider=qualcomm_source_log_mel)
    background = None
    if args.background:
        background, background_rate, background_channels = load_wav_float_interleaved(args.background)
        background = downmix_to_mono(background, background_channels)
        background_rms = float(np.sqrt(np.mean(background.astype(np.float64) ** 2)))
        if background_rms == 0:
            raise ValueError("Silent background")
        background = background * (.08 / background_rms)
    rows = []
    excluded = []
    for index, item in enumerate(manifest, 1):
        source, rate, channels = load_wav_float_interleaved(item["path"])
        if args.sample_rate and rate != args.sample_rate:
            excluded.append(item["path"])
            continue
        if background is not None and rate != background_rate:
            raise ValueError("Use --sample-rate to match background native rate; no extra resampling is allowed")
        if background is not None:
            source = downmix_to_mono(source, channels)
            channels = 1
            rms = float(np.sqrt(np.mean(source.astype(np.float64) ** 2)))
            if rms > 0:
                source = source * (.08 / rms)
        for phase in args.phase_ms:
            prefix = 1500 + phase
            interleaved = np.concatenate((
                np.zeros(round(rate * prefix / 1000) * channels, dtype=np.float32), source,
                np.zeros(round(rate * 1.5) * channels, dtype=np.float32),
            ))
            source_onset = None
            if background is not None:
                activity = captures(interleaved, rate, channels)
                source_onset = next((time for time, _, peak, _ in activity if time >= prefix and peak > .01), None)
                interleaved += np.resize(background, interleaved.size)
            mono = downmix_to_mono(interleaved, channels)
            chunks = captures(interleaved, rate, channels)
            stop = prefix + source.size / channels * 1000 / rate
            cache = {}
            row = {**item, "phase_ms": phase}
            configurations = {
                "baseline": (BorrowedSlots(), False, False, False),
                "advance50": (BorrowedSlots(delay_ms=50), True, False, False),
                "advance100": (BorrowedSlots(delay_ms=100), True, False, False),
                "rephase50": (RephasedSlots(delay_ms=50), True, False, False),
                "confirm100": (BorrowedSlots(delay_ms=0, followup=False), False, True, False),
                "confirm_safe100": (BorrowedSlots(delay_ms=0, followup=False), False, True, True),
                "confirm_consistent": (BorrowedSlots(delay_ms=0, followup=False), False, True, True),
                "confirm_active": (BorrowedSlots(delay_ms=0, followup=False), False, True, True),
                "onset_confirm50": (BorrowedSlots(delay_ms=50, followup=False), True, True, False),
            }
            for name in ["baseline", *args.arms]:
                scheduler, adaptive, confirmation, fallback = configurations[name]
                profile = RuntimeProfile(args.runtime_profile, args.inference_ms) if args.runtime_profile else None
                if profile:
                    scheduler = ProductionSlots(profile=profile, followup=False)
                row[name] = replay(mono, chunks, rate, scheduler, classifier, cache,
                                   prefix, stop, item["expected"], args.inference_ms, adaptive, confirmation, fallback, source_onset,
                                   name in ("confirm_consistent", "confirm_active"), name == "confirm_active", profile)
            rows.append(row)
        if index % 20 == 0:
            print(f"responsive: {index}/{len(manifest)} files", flush=True)
    report = {
        "manifest_sha256": hashlib.sha256(raw).hexdigest(), "inference_ms": args.inference_ms,
        "runtime_profile": args.runtime_profile,
        "scheduler": "production-rephased" if args.runtime_profile else "legacy-fixed-slot-diagnostic",
        "model_sha256": {name: hashlib.sha256((args.model_dir / name).read_bytes()).hexdigest()
                         for name in ("yamnet.onnx", "yamnet.data", "yamnet_class_map.csv")},
        "background": str(args.background) if args.background else None,
        "background_sha256": hashlib.sha256(args.background.read_bytes()).hexdigest() if args.background else None,
        "excluded_sample_rate": excluded,
        "phase_ms": args.phase_ms, "summary": {arm: paired_summary(rows, arm) for arm in args.arms},
        "limitations": "Activity-marker latency in controlled file replay, not Android or semantic event latency. "
                        "Compute time and wake-up jitter are declared simulation assumptions, not measured runtime. "
                        "Dataset provenance is in the manifest; no training-disjoint or real-world guarantee.",
        "rows": rows,
    }
    with args.output.open("x", encoding="utf-8") as output:
        json.dump(report, output, ensure_ascii=False, indent=2)
    print(json.dumps(report["summary"], indent=2))


if __name__ == "__main__":
    main()
