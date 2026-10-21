"""Idealized #407 isolated preview replay. No CPU/memory contention simulation.

Regular ticks are NEVER shifted or consumed; only the optional UI preview uses
an early observation. Late results are discarded without changing hysteresis.
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
from evaluate_responsive_inference import captures, paired_summary, replay
from evaluate_responsive_runtime import profile_for
from preprocess import capture_samples_for_one_yamnet_window, downmix_to_mono
from responsive_schedule import ProductionSlots, SilenceGate
from wav_io import load_wav_float_interleaved


def held_metrics(timeline, cutoff, start, stop):
    held = Counter()
    danger = 0.0
    speech = 0.0
    for (time, ui), (end, _) in zip(timeline, [*timeline[1:], (cutoff, timeline[-1][1])]):
        held[ui] += max(0.0, min(end, stop) - max(time, start))
        if ui == "danger":
            danger += max(0.0, end - max(time, start))
        if ui == "speech":
            speech += max(0.0, end - max(time, start))
    return dict(majority=max(("ambient", "speech", "danger"),
                            key=lambda k: (held[k], k == "danger", k == "speech")),
                danger_ms_with_tail=danger, danger_seen=danger > 0,
                speech_ms_with_tail=speech, speech_seen=speech > 0)


def isolated_replay(mono, chunks, rate, classifier, cache, start, stop, expected, profile):
    classifier.reset_state()
    scheduler = ProductionSlots(profile=profile, followup=False)
    gate = SilenceGate()
    need = capture_samples_for_one_yamnet_window(rate)
    index = captured = version = predictions = attempts = accepted = late = 0
    normal = optional = scheduled = preview = None
    active_since = None
    last_capture = -math.inf
    first = regular_first = None
    regular_ui = "ambient"
    preview_visible = False
    timeline = [(0.0, regular_ui)]
    regular_timeline = [(0.0, regular_ui)]
    cutoff = chunks[-1][0]
    onset = next((t for t, _, peak, _ in chunks if t >= start and peak > .01), None)

    def active(now):
        return active_since is not None and now - active_since >= 100 and now - last_capture <= 100

    def predict():
        if captured not in cache:
            cache[captured] = classifier.classify_mono_capture_once(mono[captured - need:captured], rate,
                                                                  apply_hysteresis=False)[0]
        return cache[captured]

    while True:
        now, kind = min((chunks[index][0] if index < len(chunks) else math.inf, 0),
                        (normal[0] if normal else math.inf, 1),
                        (optional[0] if optional else math.inf, 2),
                        (preview[0] if preview else math.inf, 3),
                        (scheduler.deadline, 4), (scheduled[0] if scheduled else math.inf, 5))
        if now > cutoff:
            break
        if kind == 0:
            _, captured, peak, _ = chunks[index]
            last_capture = now
            active_since = None if peak <= .01 else (now if active_since is None else active_since)
            gate.capture(now, peak)
            if preview is not None and active(now) != preview_visible:
                preview_visible = active(now)
                timeline.append((now, "speech" if preview_visible else regular_ui))
            index += 1
        elif kind == 1:
            _, snapshot, frame, duration = normal
            classifier._apply_coarse_hysteresis(frame)
            regular_ui = classifier._confirmed_coarse
            regular_timeline.append((now, regular_ui))
            timeline.append((now, regular_ui))
            if onset is not None and snapshot >= onset and regular_ui == expected and frame.coarse_class == expected and frame.meets_threshold:
                if regular_first is None:
                    regular_first = now - onset
                if first is None:
                    first = now - onset
            gate.complete(snapshot)
            normal = None
            if (scheduled is None and optional is None and duration <= 25 and
                    frame.meets_threshold and frame.coarse_class == "speech" and regular_ui == "ambient" and
                    classifier._candidate_streak == 1 and now + 100 + duration + 100 <= scheduler.regular):
                not_before = now + 100
                scheduled = (not_before + profile.lateness(not_before, True), scheduler.regular,
                             version, frame)
        elif kind == 2:
            _, expiry, epoch, source, frame = optional
            if now >= expiry or epoch != version:
                late += 1
            elif (active(now) and frame.meets_threshold and frame.coarse_class == "speech" and
                  frame.yamnet_display_name == source.yamnet_display_name and frame.confidence >= source.confidence):
                accepted += 1
                preview = (expiry,)
                preview_visible = True
                timeline.append((now, "speech"))
                if first is None and expected == "speech" and onset is not None:
                    first = now - onset
            optional = None
        elif kind == 3:
            preview = None
            preview_visible = False
            timeline.append((now, regular_ui))
        elif kind == 4:
            version += 1
            scheduled = None
            if preview:
                preview = None
                preview_visible = False
                timeline.append((now, regular_ui))
            duration = 0
            if captured >= need and gate.is_open(now):
                assert normal is None, "Regular overlap"
                frame = predict()
                duration = profile.duration(now, False)
                normal = (now + duration, now, frame, duration)
                predictions += 1
            scheduler.complete(now, duration)
        else:
            _, expiry, epoch, source = scheduled
            scheduled = None
            if epoch == version and now < expiry and active(now) and gate.is_open(now):
                attempts += 1
                frame = predict()
                optional = (now + profile.duration(now, True), expiry, epoch, source, frame)
    return dict(first_valid_expected_ms=first if expected in ("speech", "danger") else None,
                activity_onset_ms=onset, predictions=predictions + attempts, advances=attempts,
                **held_metrics(timeline, cutoff, start, stop), regular_predictions=predictions,
                regular_first_valid_expected_ms=regular_first if expected in ("speech", "danger") else None,
                regular_metrics=held_metrics(regular_timeline, cutoff, start, stop),
                early_attempts=attempts, early_accepted=accepted, late_drops=late)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--model-dir", type=Path)
    parser.add_argument("--compact-report-only", type=Path, help="Publish summary of an existing full replay; no inference")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--profiles", nargs="+", default=["fixed25", "variable", "early-spike"])
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Output must be new")
    if args.compact_report_only:
        full = args.compact_report_only.read_bytes()
        report = json.loads(full)
        report["full_report_sha256"] = hashlib.sha256(full).hexdigest()
        for profile in report["profiles"].values():
            profile.pop("rows")
        with args.output.open("x", encoding="utf-8") as handle:
            json.dump(report, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        return
    if args.manifest is None or args.model_dir is None:
        parser.error("Replay requires --manifest and --model-dir")
    raw = args.manifest.read_bytes()
    manifest = json.loads(raw)
    manifest = manifest["rows"] if isinstance(manifest, dict) else manifest
    manifest = [r for r in manifest if r["policy"] == "primary"]
    profiles = {name: profile_for(name) for name in args.profiles}
    reports = {name: dict(rows=[]) for name in profiles}
    classifier = ReferenceClassifier(args.model_dir, load_booster=False, log_mel_provider=qualcomm_source_log_mel)
    for index, item in enumerate(manifest, 1):
        source, rate, channels = load_wav_float_interleaved(item["path"])
        for phase in (0, 100, 200):
            start = 1500 + phase
            samples = np.concatenate((np.zeros(round(rate * start / 1000) * channels, np.float32),
                                      source, np.zeros(round(rate * 1.5) * channels, np.float32)))
            mono = downmix_to_mono(samples, channels)
            chunks = captures(samples, rate, channels)
            stop = start + source.size / channels * 1000 / rate
            cache = {}
            for name, profile in profiles.items():
                baseline = replay(mono, chunks, rate, ProductionSlots(profile=profile), classifier, cache,
                                  start, stop, item["expected"], profile.fixed_ms, False, runtime_profile=profile)
                candidate = isolated_replay(mono, chunks, rate, classifier, cache, start, stop, item["expected"], profile)
                assert baseline["predictions"] == candidate["regular_predictions"]
                assert baseline["first_valid_expected_ms"] == candidate["regular_first_valid_expected_ms"]
                assert all(baseline[k] == v for k, v in candidate["regular_metrics"].items())
                reports[name]["rows"].append(dict(item, phase_ms=phase, baseline=baseline, isolated=candidate))
        if index % 10 == 0 or index == len(manifest):
            print(f"isolated replay {index}/{len(manifest)}", flush=True)
    for report in reports.values():
        report["summary"] = paired_summary(report["rows"], "isolated")
        report["regular_invariance_conditions"] = len(report["rows"])
        report["late_drops"] = sum(r["isolated"]["late_drops"] for r in report["rows"])
        report["majority_regressions"] = [dict(filename=Path(r["path"]).name, phase_ms=r["phase_ms"])
                                          for r in report["rows"] if r["baseline"]["majority"] == r["expected"] and
                                          r["isolated"]["majority"] != r["expected"]]
        ambient = [r for r in report["rows"] if r["expected"] == "ambient"]
        report["ambient_speech_exposure"] = {
            arm: dict(conditions=sum(r[arm]["speech_seen"] for r in ambient),
                      duration_ms=sum(r[arm]["speech_ms_with_tail"] for r in ambient))
            for arm in ("baseline", "isolated")
        }
        report["new_ambient_speech_conditions"] = [dict(filename=Path(r["path"]).name, phase_ms=r["phase_ms"])
                                                   for r in ambient if not r["baseline"]["speech_seen"] and
                                                   r["isolated"]["speech_seen"]]
    output = dict(manifest_sha256=hashlib.sha256(raw).hexdigest(), profiles=reports,
                  limitations="Idealized ready second session, no CPU/cache/RAM/thermal contention. "
                              "Regular schedule equivalence is NOT a device latency guarantee. PCM onset marker, not semantic ground truth.")
    with args.output.open("x", encoding="utf-8") as handle:
        json.dump(output, handle, ensure_ascii=False, indent=2)
    for name, report in reports.items():
        print(name, json.dumps({k: v for k, v in report.items() if k != "rows"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
