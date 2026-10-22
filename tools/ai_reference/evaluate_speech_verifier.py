"""#414 pinned Silero feasibility experiment over immutable #407 baseline events.

No app model, thresholds, schedule or postprocessor are changed. Reused corpus
and fixed-time simulation are NOT an accuracy holdout or device benchmark.
"""
import argparse
import gzip
import hashlib
import json
import math
from pathlib import Path
import platform
import time

import numpy as np
import onnxruntime as ort

from preprocess import _fir_table_for_sample_rate, FIR_TAPS, downmix_to_mono
from speech_verifier_policy import Block, decide, eligible, summarize_case
from wav_io import load_wav_float_interleaved

MODEL_SHA = "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3"
TRACE_SHA = "0ad4cc4ab51d3ddea8753b58d334290d10ad0034d60f76f13b6dea8dd9d6f5b5"
MODES = ("continuous", "triggered160")
THRESHOLDS = (.9, .75, .5)  # .9 primary; other values are sensitivity only
COSTS = (2., 8., 32.)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def trace_bytes(path):
    raw = Path(path).read_bytes()
    return gzip.decompress(raw) if raw.startswith(b"\x1f\x8b") else raw


def audio_blocks(mono, rate):
    """Same FIR coefficients as app reference, but wait for right-hand taps.

    Bounded 512x96 gather avoids allocating a full-file FIR matrix. Availability
    includes the last sample needed plus its native 512-frame capture return.
    Do not pad an incomplete block or read past the recording's final sample.
    """
    if rate <= 0:
        raise ValueError("Invalid sample rate")
    coefficients, phase_count = _fir_table_for_sample_rate(rate) if rate > 16000 else (None, None)
    for start in range(0, math.floor(len(mono) * 16000 / rate) - 511, 512):
        numerator = np.arange(start, start + 512, dtype=np.int64) * rate
        centers = numerator // 16000
        if rate > 16000:
            phases = ((numerator % 16000) * phase_count + 8000) // 16000 % phase_count
            indices = centers[:, None] + np.arange(FIR_TAPS) - FIR_TAPS // 2 + 1
            needed = int(indices[-1, -1]) + 1
            if needed > len(mono):
                break
            samples = np.where(indices >= 0, mono[np.clip(indices, 0, len(mono) - 1)], 0.).astype(np.float64)
            data = np.einsum("ij,ij->i", coefficients[phases].astype(np.float64), samples).astype(np.float32)
        elif rate < 16000:
            needed = int(centers[-1]) + 2
            if needed > len(mono):
                break
            alpha = (numerator % 16000) / 16000.
            data = ((1. - alpha) * mono[centers] + alpha * mono[centers + 1]).astype(np.float32)
        else:
            needed = start + 512
            data = mono[start:start + 512]
        returned = min(len(mono), math.ceil(needed / 512) * 512)
        yield Block(start / 16., (start + 512) / 16., returned * 1000. / rate, 0.), data


class Silero:
    """Numpy adapter implementing the pinned official ONNX state contract."""
    def __init__(self, path):
        options = ort.SessionOptions()
        options.intra_op_num_threads = options.inter_op_num_threads = 1
        before = time.perf_counter_ns()
        self.session = ort.InferenceSession(str(path), sess_options=options, providers=["CPUExecutionProvider"])
        self.load_ms = (time.perf_counter_ns() - before) / 1e6
        self.timings = []
        self.reset()

    def reset(self):
        self.state = np.zeros((2, 1, 128), np.float32)
        self.context = np.zeros((1, 64), np.float32)

    def score(self, data):
        if data.shape != (512,):
            raise ValueError("Expected 512 samples")
        joined = np.concatenate((self.context, data.reshape(1, 512)), axis=1)
        before = time.perf_counter_ns()
        probability, self.state = self.session.run(None, {"input": joined, "state": self.state,
                                                        "sr": np.array(16000, dtype=np.int64)})
        self.timings.append((time.perf_counter_ns() - before) / 1e6)
        self.context = joined[:, -64:].copy()
        return float(probability[0, 0])


def score_blocks(model, blocks):
    model.reset()
    return [Block(b.start, b.end, b.available, model.score(data)) for b, data in blocks]


def bootstrap(rows):
    """Group all three phases of a source file. Does not fix speaker dependence."""
    groups = {}
    for row in rows:
        if row["expected"] == "speech" and row["saved_ms"] is not None:
            groups.setdefault(row["filename"], []).append(row["saved_ms"])
    if not groups:
        return None
    values = list(groups.values())
    rng = np.random.default_rng(414)
    medians = [np.median([v for i in rng.integers(0, len(values), len(values)) for v in values[i]])
               for _ in range(2000)]
    return np.percentile(medians, [2.5, 97.5]).tolist()


def summary(rows):
    speech = [r for r in rows if r["expected"] == "speech"]
    ambient = [r for r in rows if r["expected"] == "ambient"]
    pairs = [r for r in speech if r["saved_ms"] is not None]
    savings = [r["saved_ms"] for r in pairs]
    median = float(np.median(savings)) if savings else None
    lost = sum(r["baseline_first_ms"] is not None and r["candidate_first_ms"] is None for r in speech)
    worsened = sum(r["saved_ms"] < -.001 for r in pairs)
    added = sum(r["extra_speech_ms"] for r in ambient)
    return {"conditions": len(rows), "speech_conditions": len(speech), "paired_detected": len(pairs),
            "baseline_missed": sum(r["baseline_first_ms"] is None for r in speech),
            "candidate_missed": sum(r["candidate_first_ms"] is None for r in speech),
            "lost": lost, "improved": sum(r["saved_ms"] > .001 for r in pairs), "worsened": worsened,
            "same": sum(abs(r["saved_ms"]) <= .001 for r in pairs),
            "paired_saved_median_p95_ms": np.percentile(savings, [50, 95]).tolist() if savings else None,
            "paired_saved_median_bootstrap95_ms": bootstrap(rows),
            "saved_without_reverted_preview_median_p95_ms": np.percentile([r["saved_without_reverted_preview_ms"] for r in pairs], [50, 95]).tolist() if pairs else None,
            "baseline_latency_median_p95_ms": np.percentile([r["baseline_first_ms"] for r in pairs], [50, 95]).tolist() if pairs else None,
            "candidate_latency_median_p95_ms": np.percentile([r["candidate_first_ms"] for r in pairs], [50, 95]).tolist() if pairs else None,
            "ambient_extra_speech_ms": added,
            "ambient_extra_exposure_conditions": sum(r["extra_speech_ms"] > 0 for r in ambient),
            "ambient_new_exposure_conditions": sum(r["extra_speech_ms"] > 0 and not r["baseline_speech_seen"] for r in ambient),
            "reversions": sum(r["reversions"] for r in rows),
            "reverted_preview_ms": sum(r["reverted_preview_ms"] for r in rows),
            "accepted": sum(r["accepted"] for r in rows),
            "vad_calls": sum(r["vad_calls"] for r in rows), "late_windows": sum(r["late_windows"] for r in rows),
            "danger_extra_speech_ms": sum(r["extra_speech_ms"] for r in rows if r["expected"] == "danger"),
            "development_screen_pass": median is not None and median >= 100 and lost == 0 and worsened == 0 and added == 0,
            "not_a_release_gate": "Danger regular timeline identical by construction; no device contention simulated. Reused data, coarse file labels."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--historical-414", action="store_true", help="Acknowledge frozen pre-421 labels; use reevaluate_human_speech.py for current policy")
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--vad-model", type=Path, required=True)
    parser.add_argument("--audio-root", type=Path, help="Resolve original filenames here instead of trace paths")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.historical_414:
        parser.error("Historical policy only: acknowledge --historical-414, or run reevaluate_human_speech.py")
    if args.output.exists():
        parser.error("Output must be new")
    raw_trace = trace_bytes(args.baseline)
    if digest(args.vad_model) != MODEL_SHA or hashlib.sha256(raw_trace).hexdigest() != TRACE_SHA:
        parser.error("Input does not match the preregistered model / baseline hash")
    trace = json.loads(raw_trace)
    if len(trace["rows"]) != 372 or trace["inference_ms"] != 25:
        parser.error("Unexpected trace")
    model = Silero(args.vad_model)
    reports = {f"{mode}-p{threshold:g}-cost{cost:g}": []
               for mode in MODES for threshold in THRESHOLDS for cost in COSTS}
    total_seconds = 0.
    for index, row in enumerate(trace["rows"], 1):
        path = args.audio_root / Path(row["path"]).name if args.audio_root else Path(row["path"])
        source_hash = row.get("decoded_sha256", row["downloaded_sha256"])
        if digest(path) != source_hash:
            raise ValueError(f"Source mismatch: {path}")
        source, rate, channels = load_wav_float_interleaved(path)
        start = 1500 + row["phase_ms"]
        mono = np.concatenate((np.zeros(round(rate * start / 1000), np.float32),
                               downmix_to_mono(source, channels), np.zeros(round(rate * 1.5), np.float32)))
        cutoff = len(mono) * 1000. / rate
        total_seconds += cutoff / 1000.
        blocks = list(audio_blocks(mono, rate))
        continuous = score_blocks(model, blocks)
        windows = []
        events = row["baseline_events"]
        for i, event in enumerate(events):
            if not eligible(event):
                continue
            next_complete = events[i + 1]["complete_ms"] if i + 1 < len(events) else math.inf
            # Start with up to 160ms fully received history. Keep intervening
            # partially fresh blocks for state continuity, but not confirmation.
            received = sum(b.available <= event["complete_ms"] for b, _ in blocks)
            chosen = [(b, data) for b, data in blocks[max(0, received - 5):]
                      if b.available < min(event["snapshot_ms"] + 250., next_complete)]
            triggered = score_blocks(model, chosen)
            windows.append((event, next_complete, triggered))
        for mode in MODES:
            for threshold in THRESHOLDS:
                for cost in COSTS:
                    previews = []
                    calls = len(blocks) if mode == "continuous" else 0
                    late = 0
                    for event, next_complete, triggered in windows:
                        preview, attempted, dropped = decide(continuous if mode == "continuous" else triggered,
                            event, next_complete, threshold=threshold, call_ms=cost, mode=mode)
                        if mode != "continuous":
                            calls += attempted
                        late += dropped
                        if preview:
                            previews.append(preview)
                    metrics = summarize_case(row, previews, cutoff, source_start=start)
                    reports[f"{mode}-p{threshold:g}-cost{cost:g}"].append({
                        "filename": row["filename"], "source_sha256": source_hash,
                        "dataset": row["dataset"], "category": row["category"], "expected": row["expected"],
                        "phase_ms": row["phase_ms"], "baseline_speech_seen": row["baseline"]["speech_seen"],
                        "vad_calls": calls, "late_windows": late, "previews": previews, **metrics})
        if index % 30 == 0 or index == len(trace["rows"]):
            print(f"verifier: {index}/{len(trace['rows'])}", flush=True)
    output = {"schema": 1, "model_sha256": MODEL_SHA, "baseline_sha256": TRACE_SHA,
              "manifest_sha256": trace["manifest_sha256"], "source_count": len({r["filename"] for r in trace["rows"]}),
              "audio_seconds_including_silence_and_repeated_phases": total_seconds,
              "host": {"platform": platform.platform(), "onnxruntime": ort.__version__, "numpy": np.__version__,
                       "session_load_ms": model.load_ms, "ort_call_count": len(model.timings),
                       "ort_call_median_p95_ms": np.percentile(model.timings, [50, 95]).tolist()},
              "profiles": {name: {"summary": summary(rows), "rows": rows} for name, rows in reports.items()},
              "limitations": "Reused development audio, no semantic onset labels. Fixed regular clock, independent worker assumed. Not Android, power, thermal, CPU/RSS or display timing."}
    with args.output.open("x", encoding="utf-8") as handle:
        json.dump(output, handle, indent=2)
        handle.write("\n")
    for name, report in output["profiles"].items():
        print(name, json.dumps(report["summary"]))


if __name__ == "__main__":
    main()
