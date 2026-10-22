"""Summarize optional latency-*.csv; receive-to-draw, NOT acoustic onset-to-photon."""
import argparse
import csv
import json
from collections import Counter
from pathlib import Path


def distribution(values):
    if not values:
        return {"n": 0, "median_ms": None, "p95_ms": None}
    values = sorted(values)
    def percentile(p):
        at = (len(values) - 1) * p
        lo = int(at)
        return (values[lo] + (values[min(lo + 1, len(values) - 1)] - values[lo]) * (at - lo)) / 1e6
    return {"n": len(values), "median_ms": percentile(.5), "p95_ms": percentile(.95)}


def summarize(rows):
    results = {}
    observed = {}
    stages = Counter()
    ended = False
    dropped = None
    excluded_previews = 0
    for row in rows:
        if row["schema"] != "1":
            raise ValueError("Unsupported latency schema")
        stages[row["stage"]] += 1
        if row["stage"] == "recording_end":
            ended = True
            dropped = int(row["detail"].split(";", 1)[0].split("=", 1)[1])
        if not row["result_id"]:
            continue
        key = (row["pipeline_session_id"], row["result_id"])
        if row["stage"] == "result":
            # A preview inherits its parent's YAMNet input stamp. Counting it as
            # another inference would mislabel VAD waiting as regular publication.
            if "kind=preview" in row.get("detail", "").split(";"):
                excluded_previews += 1
                continue
            results[key] = row
        else:
            observed.setdefault(key, {}).setdefault(row["stage"], int(row["at_boot_ns"]))
    intervals = {name: [] for name in ("read_return_to_snapshot", "snapshot_copy", "preprocessing",
        "inference", "decision", "publication", "publish_to_color_read", "publish_to_color_draw",
        "publish_to_hud_poll", "publish_to_hud_draw", "hud_poll_to_draw")}
    filtered = invalid = 0
    for key, row in results.items():
        read, begin, end, pre, infer, confirm, publish = (int(row[name]) for name in (
            "read_return_boot_ns", "snapshot_begin_boot_ns", "snapshot_end_boot_ns",
            "preprocessed_boot_ns", "inferred_boot_ns", "confirmed_boot_ns", "at_boot_ns"))
        if not (0 < begin <= end <= pre <= infer <= confirm <= publish):
            invalid += 1
            continue
        filtered += row["input_may_be_filtered"] == "true"
        if 0 < read <= end:
            # A capture can arrive while the snapshot lock is being acquired.
            intervals["read_return_to_snapshot"].append(end - read)
        for name, a, b in (("snapshot_copy", begin, end), ("preprocessing", end, pre),
                           ("inference", pre, infer), ("decision", infer, confirm),
                           ("publication", confirm, publish)):
            intervals[name].append(b - a)
        seen = observed.get(key, {})
        for name in ("color_read", "color_draw", "hud_poll", "hud_draw"):
            if name in seen:
                delta = seen[name] - publish
                if delta >= 0:
                    intervals[f"publish_to_{name}"].append(delta)
                else:
                    invalid += 1
        if "hud_poll" in seen and "hud_draw" in seen and seen["hud_draw"] >= seen["hud_poll"]:
            intervals["hud_poll_to_draw"].append(seen["hud_draw"] - seen["hud_poll"])
    return {"results": len(results), "excluded_preview_results": excluded_previews,
            "stages": dict(stages), "invalid_intervals": invalid,
            "recording_closed": ended, "dropped_events": dropped, "input_may_be_filtered_results": filtered,
            "missing_color_draw": sum("color_draw" not in observed.get(k, {}) for k in results),
            "missing_hud_draw": sum("hud_draw" not in observed.get(k, {}) for k in results),
            "intervals": {k: distribution(v) for k, v in intervals.items()},
            "limitations": "Regular inference stages exclude provisional preview results with inherited input stamps. "
            "Missing draws are censored (hidden, replaced, paused, or dropped), not zero latency. "
            "Read return is not microphone/event onset. Draw callback is not physical presentation. "
            "Conditional observed-result distributions are not end-to-end detection performance."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", nargs="+", type=Path)
    args = parser.parse_args()
    # Separate recording files must not be pooled: process/session counters can restart.
    for path in args.csv:
        with path.open(newline="", encoding="utf-8") as source:
            print(json.dumps({"file": str(path), **summarize(csv.DictReader(source))}, ensure_ascii=False, indent=2))
