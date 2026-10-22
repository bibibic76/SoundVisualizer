"""#421 diagnostic re-evaluation: recompute YAMNet with approved global policy.

Uses #415's fixed Silero rules. New #419 candidates remain unreviewed diagnostic
proxies. Never promotes them to human-reviewed/final holdout data.
"""
import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path

import numpy as np
import soundfile as sf
import mapper

from classifier import ReferenceClassifier
from compare_logmel_frontends import qualcomm_source_log_mel
from evaluate_responsive_inference import captures, replay
from evaluate_speech_verifier import MODEL_SHA, Silero, audio_blocks, digest, score_blocks
from human_origin_policy import POLICY_ID, expected_for
from responsive_schedule import BorrowedSlots
from speech_verifier_policy import decide, eligible, summarize_case


def summarize(rows):
    positive = [r for r in rows if r["expected"] == "speech"]
    paired = [r for r in positive if r["saved_ms"] is not None]
    ambient = [r for r in rows if r["expected"] == "ambient"]
    def percentile(key, group=paired):
        values = [r[key] for r in group if r[key] is not None]
        return np.percentile(values, [50, 95]).tolist() if values else None
    return {"conditions": len(rows), "sources": len({r["id"] for r in rows}),
        "speech_conditions": len(positive), "paired_detected": len(paired),
        "baseline_first_display_and_confirmed_median_p95_ms": percentile("baseline_first_ms"),
        "candidate_first_display_median_p95_ms": percentile("candidate_first_ms"),
        "candidate_regular_confirmed_median_p95_ms": percentile("baseline_first_ms"),
        "paired_saved_median_p95_ms": percentile("saved_ms"),
        "stable_paired_saved_median_p95_ms": percentile("saved_without_reverted_preview_ms"),
        "improved": sum(r["saved_ms"] > .001 for r in paired),
        "same": sum(abs(r["saved_ms"]) <= .001 for r in paired),
        "worsened": sum(r["saved_ms"] < -.001 for r in paired),
        "baseline_missed": sum(r["baseline_first_ms"] is None for r in positive),
        "candidate_missed": sum(r["candidate_first_ms"] is None for r in positive),
        "lost": sum(r["baseline_first_ms"] is not None and r["candidate_first_ms"] is None for r in positive),
        "gained": sum(r["baseline_first_ms"] is None and r["candidate_first_ms"] is not None for r in positive),
        "ambient_baseline_speech_ms": sum(r["baseline_speech_ms"] for r in ambient),
        "ambient_candidate_speech_ms": sum(r["baseline_speech_ms"] + r["extra_speech_ms"] for r in ambient),
        "ambient_added_speech_ms": sum(r["extra_speech_ms"] for r in ambient),
        "ambient_new_speech_conditions": sum(r["extra_speech_ms"] > 0 and r["baseline_speech_ms"] == 0 for r in ambient),
        "ambient_affected": [{k: r[k] for k in ("id", "category", "phase_ms", "extra_speech_ms")} for r in ambient if r["extra_speech_ms"] > 0],
        "retractions": sum(r["reversions"] for r in rows),
        "retracted_preview_ms": sum(r["reverted_preview_ms"] for r in rows),
        "yamnet_calls": sum(r["yamnet_calls"] for r in rows),
        "vad_calls": sum(r["vad_calls"] for r in rows),
        "late_windows": sum(r["late_windows"] for r in rows),
        "accepted": sum(r["accepted"] for r in rows),
        "danger": {"conditions": sum(r["expected"] == "danger" for r in rows),
            "baseline_missed": sum(r["expected"] == "danger" and r["baseline_first_ms"] is None for r in rows),
            "lost": 0, "delay_increase_ms": 0, "regular_timeline_unchanged": True,
            "limitation": "By construction in a replay with independent compute, not device CPU isolation"}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--legacy-manifest", type=Path, required=True)
    parser.add_argument("--new-manifest", type=Path, required=True)
    parser.add_argument("--new-audio-dir", type=Path, required=True)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--vad-model", type=Path, required=True)
    parser.add_argument("--rate", choices=["native", "48000"], default="native")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--extra-danger-manifest", type=Path)
    parser.add_argument("--only-extra-danger", action="store_true")
    args = parser.parse_args()
    if args.output.exists() or digest(args.vad_model) != MODEL_SHA:
        parser.error("New output / pinned VAD model required")
    legacy = json.loads(args.legacy_manifest.read_text())["rows"]
    new = json.loads(args.new_manifest.read_text())["rows"]
    items = []
    for row in legacy:
        items.append(dict(row, id="legacy:" + row["filename"], cohort="legacy124",
                          original_expected=row["expected"], source_hash=row.get("decoded_sha256", row["downloaded_sha256"])))
    for row in new:
        items.append(dict(row, path=str(args.new_audio_dir / row["filename"]), category=row["stratum"],
                          cohort="new83_unreviewed", original_expected=row["expected_hint"], source_hash=row["file_sha256"]))
    for row in items:
        row["expected"] = expected_for(row)
    if args.only_extra_danger:
        items = []
    if args.extra_danger_manifest:
        for row in json.loads(args.extra_danger_manifest.read_text()):
            if row["dataset"] == "representative" and row["expected"] == "danger":
                items.append(dict(row, id="representative:" + Path(row["path"]).name,
                    category="danger_representative", cohort="representative_danger25", original_expected="danger",
                    source_hash=digest(row["path"])))
    yamnet = ReferenceClassifier(args.model_dir, load_booster=False, log_mel_provider=qualcomm_source_log_mel)
    vad = Silero(args.vad_model)
    profiles = {f"{mode}-cost{cost:g}": [] for mode in ("continuous", "triggered160") for cost in (2., 8., 32.)}
    total_audio_ms = 0.
    for index, item in enumerate(items, 1):
        if digest(item["path"]) != item["source_hash"]:
            raise ValueError("Source hash mismatch: " + item["id"])
        source, native_rate = sf.read(item["path"], dtype="float32", always_2d=True)
        source = np.mean(source, axis=1, dtype=np.float64).astype(np.float32)
        rate = native_rate
        if args.rate != "native" and rate != 48000:
            # Declared linear playback conversion, not Android mixer parity.
            positions = np.arange(math.floor(len(source) * 48000 / rate)) * (rate / 48000.)
            source = np.interp(positions, np.arange(len(source)), source).astype(np.float32)
            rate = 48000
        for phase in (0, 100, 200):
            start = 1500 + phase
            stop = start + len(source) * 1000. / rate
            mono = np.concatenate((np.zeros(round(rate * start / 1000), np.float32), source,
                                   np.zeros(round(rate * 1.5), np.float32)))
            cutoff = len(mono) * 1000. / rate
            total_audio_ms += cutoff
            chunks = captures(mono, rate, 1)
            events = []
            baseline = replay(mono, chunks, rate, BorrowedSlots(), yamnet, {}, start, stop,
                              item["expected"], 25., False, diagnostic_events=events)
            old_policy = None
            if item["expected"] == "danger":
                current_mapper = mapper.map_display_name_to_coarse
                try:
                    mapper.map_display_name_to_coarse = lambda label: "ambient" if label.lower() in {"sneeze", "cough", "breathing"} else current_mapper(label)
                    old_policy = replay(mono, chunks, rate, BorrowedSlots(), yamnet, {}, start, stop, "danger", 25., False)
                finally:
                    mapper.map_display_name_to_coarse = current_mapper
            baseline_row = {"expected": item["expected"], "baseline_events": events, "baseline": baseline}
            blocks = list(audio_blocks(mono, rate))
            continuous = score_blocks(vad, blocks)
            windows = []
            for i, event in enumerate(events):
                if not eligible(event):
                    continue
                next_complete = events[i + 1]["complete_ms"] if i + 1 < len(events) else math.inf
                received = sum(b.available <= event["complete_ms"] for b, _ in blocks)
                chosen = [(b, data) for b, data in blocks[max(0, received - 5):]
                          if b.available < min(event["snapshot_ms"] + 250., next_complete)]
                windows.append((event, next_complete, score_blocks(vad, chosen)))
            for mode in ("continuous", "triggered160"):
                for cost in (2., 8., 32.):
                    previews = []
                    calls = len(blocks) if mode == "continuous" else 0
                    late = 0
                    for event, next_complete, triggered in windows:
                        preview, attempted, dropped = decide(continuous if mode == "continuous" else triggered,
                            event, next_complete, threshold=.9, call_ms=cost, mode=mode)
                        if mode != "continuous":
                            calls += attempted
                        late += dropped
                        if preview:
                            # This must end before any later regular result,
                            # particularly Danger. No result is injected into baseline.
                            assert preview["end_ms"] <= next_complete
                            previews.append(preview)
                    metrics = summarize_case(baseline_row, previews, cutoff, source_start=start)
                    profiles[f"{mode}-cost{cost:g}"].append({
                        "id": item["id"], "cohort": item["cohort"], "category": item["category"],
                        "expected": item["expected"], "original_expected": item["original_expected"],
                        "phase_ms": phase, "capture_rate": rate, "original_rate": native_rate,
                        "baseline_speech_ms": baseline["speech_ms_with_tail"], "yamnet_calls": baseline["predictions"],
                        "vad_calls": calls, "late_windows": late, "previews": previews, **metrics})
                    if old_policy is not None:
                        profiles[f"{mode}-cost{cost:g}"][-1]["old_policy_danger_first_ms"] = old_policy["first_valid_expected_ms"]
        if index % 10 == 0 or index == len(items):
            print(f"{args.rate}: recomputed {index}/{len(items)} sources", flush=True)
    reports = {}
    for key, rows in profiles.items():
        reports[key] = {"summary": summarize(rows), "rows": rows,
            "by_cohort": {c: summarize([r for r in rows if r["cohort"] == c]) for c in sorted({r["cohort"] for r in rows})},
            "former189_speech": summarize([r for r in rows if r["cohort"] == "legacy124" and r["original_expected"] == "speech"]),
            "by_category": {c: summarize([r for r in rows if r["category"] == c]) for c in sorted({r["category"] for r in rows})}}
    output = {"policy": POLICY_ID, "policy_mapper_sha256": digest(Path(__file__).with_name("mapper.py")),
        "legacy_manifest_sha256": digest(args.legacy_manifest), "new_manifest_sha256": digest(args.new_manifest),
        "baseline_recomputed": True, "regular_compute_assumed_ms": 25, "rate": args.rate,
        "audio_seconds_including_silence_and_phases": total_audio_ms / 1000.,
        "model_sha256": {n: digest(args.model_dir / n) for n in ("yamnet.onnx", "yamnet.data", "yamnet_class_map.csv")},
        "vad_sha256": MODEL_SHA, "host_vad_call_median_p95_ms": np.percentile(vad.timings, [50, 95]).tolist(),
        "profiles": reports,
        "limitations": "Diagnostic file-label/PCM-onset proxies, reused legacy audio and unreviewed new files. Independent compute assumed. 48k conversion is linear, not Android mixer parity. No device latency/CPU/thermal or independent final accuracy claim."}
    with args.output.open("x", encoding="utf-8") as handle:
        json.dump(output, handle, indent=2)
        handle.write("\n")
    for key in ("continuous-cost2", "triggered160-cost2"):
        print(key, json.dumps(reports[key]["summary"]))


if __name__ == "__main__":
    main()
