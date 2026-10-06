#!/usr/bin/env python3
"""Replay an annotated external WAV catalog through the reference UI path.

The runner reads source WAVs in place: it never copies, renames, or edits
them.  Every arm disables the Gunshot Booster and uses the reference
classifier's 250 ms stream, mapper, threshold, and hysteresis behavior.

It does not port Android's AiSilenceGate or emulate AudioPlaybackCapture.  The
result is therefore an offline frontend/mapper/postprocess comparison, not a
Galaxy runtime accuracy claim.
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Callable, Iterable

import numpy as np

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from classifier import ReferenceClassifier
from compare_logmel_frontends import qualcomm_source_log_mel
from preprocess import capture_samples_for_one_yamnet_window
from wav_io import load_wav_as_capture_mono


COARSE_CLASSES = ("ambient", "speech", "danger")
SOURCE_SETS = ("representative", "legacy_booster")


@dataclass(frozen=True)
class Sample:
    sample_id: str
    source_set: str
    source_filename: str
    expected_coarse: str
    category: str


def load_manifest(path: Path) -> list[Sample]:
    required = {"sample_id", "source_set", "source_filename", "expected_coarse", "category"}
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames is None or not required.issubset(reader.fieldnames):
            raise ValueError(f"{path} must contain columns: {', '.join(sorted(required))}")
        samples = [
            Sample(
                sample_id=row["sample_id"],
                source_set=row["source_set"],
                source_filename=row["source_filename"],
                expected_coarse=row["expected_coarse"],
                category=row["category"],
            )
            for row in reader
        ]
    ids = [sample.sample_id for sample in samples]
    if len(ids) != len(set(ids)):
        raise ValueError("sample_id values must be unique")
    for sample in samples:
        if sample.source_set not in SOURCE_SETS:
            raise ValueError(f"{sample.sample_id}: unknown source_set {sample.source_set!r}")
        if sample.expected_coarse not in COARSE_CLASSES:
            raise ValueError(f"{sample.sample_id}: expected_coarse must be one of {COARSE_CLASSES}")
    return samples


def provider_for(frontend: str) -> Callable[[np.ndarray], np.ndarray] | None:
    if frontend == "current":
        return None
    if frontend == "qualcomm_candidate":
        return qualcomm_source_log_mel
    raise ValueError(f"unknown frontend {frontend!r}")


def summarize(rows: Iterable[dict]) -> dict:
    rows = list(rows)
    by_expected: dict[str, list[dict]] = defaultdict(list)
    for row in rows:
        by_expected[row["expected_coarse"]].append(row)

    per_class = {}
    confusion = {}
    for expected in COARSE_CLASSES:
        group = by_expected[expected]
        detected = sum(row["expected_ui_seen"] for row in group)
        resolved = [row for row in group if row["last_ui_coarse"] is not None]
        per_class[expected] = {
            "samples": len(group),
            "expected_ui_seen": detected,
            "expected_ui_seen_rate": detected / len(group) if group else None,
            "inference_available": len(resolved),
        }
        confusion[expected] = dict(Counter(row["last_ui_coarse"] or "no_inference" for row in group))

    return {
        "samples": len(rows),
        "per_expected_class": per_class,
        "last_ui_confusion": confusion,
    }


def evaluate(
    samples: list[Sample],
    roots: dict[str, Path],
    model_dir: Path,
    frontend: str,
    tail_silence_ms: int,
) -> dict:
    classifier = ReferenceClassifier(
        model_dir,
        load_booster=False,
        log_mel_provider=provider_for(frontend),
    )
    rows = []
    for sample in samples:
        wav_path = roots[sample.source_set] / sample.source_filename
        if not wav_path.is_file():
            raise FileNotFoundError(f"{sample.sample_id}: {wav_path}")
        mono, sample_rate, channels = load_wav_as_capture_mono(wav_path)
        # AudioPlaybackCapture remains active after a short event ends.  Feed
        # deterministic silence only when the source cannot fill even one
        # YAMNet window on its own.  Longer clips already receive regular
        # realtime ticks while their source PCM is present.
        needs_tail = mono.size < capture_samples_for_one_yamnet_window(sample_rate)
        tail_samples = int(round(sample_rate * tail_silence_ms / 1000.0)) if needs_tail else 0
        replay_mono = np.concatenate((mono, np.zeros(tail_samples, dtype=np.float32)))
        frames = classifier.classify_stream(replay_mono, sample_rate, step_ms=250)
        finals = [frame["final"] for frame in frames if frame["final"] is not None]
        ui_sequence = []
        for final in finals:
            ui = final["ui_coarse"]
            if not ui_sequence or ui_sequence[-1] != ui:
                ui_sequence.append(ui)
        rows.append(
            {
                **asdict(sample),
                "sample_rate": sample_rate,
                "channels": channels,
                "duration_sec": mono.size / sample_rate,
                "tail_silence_ms": tail_silence_ms if needs_tail else 0,
                "inference_frames": len(finals),
                "last_ui_coarse": finals[-1]["ui_coarse"] if finals else None,
                "expected_ui_seen": sample.expected_coarse in ui_sequence,
                "ui_sequence": ui_sequence,
            }
        )
    return {"frontend": frontend, "summary": summarize(rows), "rows": rows}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--representative-dir", type=Path, required=True)
    parser.add_argument("--legacy-dir", type=Path, required=True)
    parser.add_argument(
        "--frontend",
        choices=("current", "qualcomm_candidate"),
        action="append",
        help="May be passed more than once; defaults to both arms.",
    )
    parser.add_argument(
        "--model-dir",
        type=Path,
        default=REPO / "app/src/main/assets/ai",
    )
    parser.add_argument(
        "--tail-silence-ms",
        type=int,
        default=1000,
        help="Deterministic post-file silence to model an ongoing capture stream (default: 1000).",
    )
    parser.add_argument("--json", type=Path, required=True)
    args = parser.parse_args()
    if args.tail_silence_ms < 0:
        parser.error("--tail-silence-ms must not be negative")

    roots = {"representative": args.representative_dir, "legacy_booster": args.legacy_dir}
    for source_set, root in roots.items():
        if not root.is_dir():
            parser.error(f"{source_set} directory does not exist: {root}")
    samples = load_manifest(args.manifest)
    frontends = args.frontend or ["current", "qualcomm_candidate"]
    report = {
        "limitations": [
            "offline WAV replay does not reproduce Galaxy AudioPlaybackCapture PCM distribution",
            "the runner applies mapper, threshold, and hysteresis but does not emulate Android AiSilenceGate",
            "all arms disable and do not load Gunshot Booster",
            "legacy Booster corpus is diagnostic data, not an independent holdout",
        ],
        "samples": len(samples),
        "tail_silence_ms": args.tail_silence_ms,
        "frontends": [
            evaluate(samples, roots, args.model_dir, frontend, args.tail_silence_ms)
            for frontend in frontends
        ],
    }
    args.json.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    for arm in report["frontends"]:
        print(arm["frontend"], json.dumps(arm["summary"], ensure_ascii=False))
    print(f"wrote {args.json}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
