"""Within-candidate preview lead from latency CSV; not acoustic A/B improvement."""
import argparse
import csv
import json
from collections import defaultdict
from pathlib import Path

from summarize_ai_latency import distribution, summarize as stage_summary


def detail(row):
    return dict(item.split("=", 1) for item in row.get("detail", "").split(";") if "=" in item)


def summarize(rows):
    rows = list(rows)
    stages = stage_summary(rows)
    regular = defaultdict(list)
    previews, draws, expiries = [], {}, set()
    invalid = 0
    for row in rows:
        session = row.get("pipeline_session_id", "")
        key = (session, row.get("result_id", ""))
        info = detail(row)
        if row["stage"] == "result":
            if info.get("kind") == "preview":
                previews.append(row)
            elif info.get("kind") == "regular":
                regular[session].append(row)
        elif row["stage"] in ("color_draw", "hud_draw") and key[1]:
            draw_key = (*key, row["stage"])
            at = int(row["at_boot_ns"])
            draws[draw_key] = min(at, draws.get(draw_key, at))
        elif row["stage"] == "silero_expiry":
            expiries.add((row["session_id"], info.get("preview_result_id")))
    lead = {"color_draw": [], "hud_draw": []}
    censored = {name: 0 for name in lead}
    confirmed = retracted = unresolved = expired = following_after_expiry = 0
    for preview in previews:
        session, result_id = preview["pipeline_session_id"], preview["result_id"]
        info = detail(preview)
        published = int(preview["at_boot_ns"])
        parents = [r for r in regular[session] if r["result_id"] == info.get("parent_result_id")]
        # Never pair across sessions, mappings or missing/replaced parent records.
        if not parents or parents[0]["mapping_signature"] != preview["mapping_signature"]:
            unresolved += 1
            continue
        following = sorted((r for r in regular[session] if int(r["at_boot_ns"]) > published),
                           key=lambda r: int(r["at_boot_ns"]))
        expired += (session, result_id) in expiries
        if not following or following[0]["mapping_signature"] != preview["mapping_signature"]:
            unresolved += 1
            continue
        next_row = following[0]
        following_after_expiry += int(next_row["at_boot_ns"]) > int(info["expires_boot_ns"])
        if next_row["confirmed_coarse"] != "speech":
            retracted += 1
            continue
        confirmed += 1
        for stage in lead:
            a = draws.get((session, result_id, stage))
            b = draws.get((session, next_row["result_id"], stage))
            if a is None or b is None:
                censored[stage] += 1
            elif published <= a <= b:
                lead[stage].append(b - a)
            else:
                invalid += 1
    counters = []
    intervals = []
    for session, sequence in regular.items():
        sequence.sort(key=lambda r: int(r["at_boot_ns"]))
        for a, b in zip(sequence, sequence[1:]):
            if a["capture_session_id"] != b["capture_session_id"]:
                continue
            elapsed = int(b["at_boot_ns"]) - int(a["at_boot_ns"])
            if elapsed <= 0:
                invalid += 1
                continue
            intervals.append(elapsed)
        if len(sequence) > 1:
            a, b = sequence[0], sequence[-1]
            da, db = detail(a), detail(b)
            elapsed = int(b["at_boot_ns"]) - int(a["at_boot_ns"])
            calls = int(db["vad_calls"]) - int(da["vad_calls"])
            compute = int(db["vad_compute_ns"]) - int(da["vad_compute_ns"])
            if elapsed > 0 and calls >= 0 and compute >= 0:
                counters.append(dict(pipeline_session_id=session, seconds=elapsed / 1e9,
                    calls=calls, calls_per_second=calls / (elapsed / 1e9),
                    worker_wall_ms=compute / 1e6, worker_wall_ms_per_second=compute / (elapsed / 1e3),
                    resets_at_end=int(db["vad_resets"]), failed_at_end=db["vad_failed"]))
    return dict(stage_summary=stages, preview_results=len(previews), next_regular_speech=confirmed,
        next_regular_not_speech=retracted, unresolved=unresolved, expiry_reads=expired,
        next_regular_published_after_preview_expiry=following_after_expiry,
        observed_preview_lead={k: distribution(v) for k, v in lead.items()},
        censored_draw_pairs=censored, invalid_preview_intervals=invalid,
        regular_publication_interval=distribution(intervals), verifier_counters=counters,
        limitations="Within-candidate preview-to-next-regular draw lead, NOT baseline APK acoustic onset latency. "
        "Draw callback is not physical presentation. Missing draws are censored, never zero. "
        "An expiry read or late regular publication is not proof of a visible flicker. "
        "Worker elapsed wall time includes resampling/scheduling; not CPU utilization. Files are not pooled.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", nargs="+", type=Path)
    for path in parser.parse_args().csv:
        with path.open(newline="", encoding="utf-8") as handle:
            print(json.dumps(dict(file=str(path), **summarize(csv.DictReader(handle))), indent=2))
