"""Host-only #414 display experiment. Never mutates the regular classifier.

Times are simulated milliseconds. No Android scheduling/CPU isolation claim.
This file uses only the standard library so the temporal rules can run in CI.
"""
from dataclasses import dataclass
import math


@dataclass(frozen=True)
class Block:
    start: float
    end: float
    available: float
    probability: float


def eligible(event):
    return (event["raw"] == "speech" and event["meets_threshold"]
            and event["ui"] == "ambient" and event["candidate_streak"] == 1)


def decide(blocks, event, next_complete, *, threshold=.9, consecutive=3,
           call_ms=2., mode="continuous"):
    """Return one preview or None, plus attempted calls / late work.

For triggered160, blocks are already scored with a reset state starting at
the first warmup block. Continuous blocks start at recording time zero.
"""
    if (not 0 <= threshold <= 1 or consecutive < 1 or call_ms < 0
            or not math.isfinite(call_ms) or mode not in ("continuous", "triggered160")):
        raise ValueError("Invalid policy")
    if not eligible(event):
        return None, 0, 0
    trigger = event["complete_ms"]
    deadline = min(event["snapshot_ms"] + 250., next_complete)
    expiry = min(event["snapshot_ms"] + 275., next_complete)
    if deadline <= trigger:
        return None, 0, 0
    clock = 0. if mode == "continuous" else trigger
    streak = calls = late = 0
    previous_end = None
    for block in blocks:
        if block.available >= deadline or (mode == "triggered160" and clock >= deadline):
            break
        clock = max(clock, block.available) + call_ms
        calls += 1
        if clock >= deadline:
            late += 1
            break
        if block.start < trigger:
            continue  # history may prepare state but cannot confirm fresh Speech
        if previous_end is not None and abs(block.start - previous_end) > 1e-6:
            streak = 0
        previous_end = block.end
        streak = streak + 1 if block.probability >= threshold else 0
        if streak >= consecutive:
            return {"start_ms": clock, "end_ms": expiry,
                    "parent_snapshot_ms": event["snapshot_ms"],
                    "fresh_end_ms": block.end, "probability": block.probability}, calls, late
    return None, calls, late


def summarize_case(row, previews, cutoff, *, source_start=0.):
    """Compare the same baseline against bounded Speech-only display intervals.

    Each preview ends no later than the next normal completion. Therefore it
    cannot replace a normal Danger result or postpone any baseline detection.
    Ambient exposure is a file-label proxy, not annotated false-alarm truth.
    """
    events = row["baseline_events"]
    first = row["baseline"]["first_valid_expected_ms"]
    onset = row["baseline"]["activity_onset_ms"]
    baseline_first = first
    exposure = reversions = reversion_ms = 0
    sustained_first = first
    for preview in previews:
        a, b = preview["start_ms"], min(cutoff, preview["end_ms"])
        if b <= a:
            continue
        exposure += max(0., b - max(a, source_start))
        if row["expected"] == "speech" and onset is not None and a >= onset:
            first = min(first if first is not None else math.inf, a - onset)
        next_event = next((e for e in events if e["complete_ms"] >= b), None)
        if next_event is None or next_event["complete_ms"] > b or next_event["ui"] != "speech":
            reversions += 1
            reversion_ms += b - a
        elif row["expected"] == "speech" and onset is not None and a >= onset:
            sustained_first = min(sustained_first if sustained_first is not None else math.inf, a - onset)
    # Danger's first detection and held duration are identical by construction:
    # preview parents must be Ambient and expire before the next regular result.
    return {"baseline_first_ms": baseline_first, "candidate_first_ms": first,
            "saved_ms": baseline_first - first if baseline_first is not None and first is not None else None,
            "saved_without_reverted_preview_ms": baseline_first - sustained_first if baseline_first is not None and sustained_first is not None else None,
            "accepted": len(previews), "extra_speech_ms": exposure,
            "reversions": reversions, "reverted_preview_ms": reversion_ms}
