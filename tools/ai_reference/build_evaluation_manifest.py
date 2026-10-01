#!/usr/bin/env python3
"""Build a read-only catalog for external WAV evaluation corpora.

This tool never renames, copies, or edits an input WAV.  It writes a CSV whose
stable logical names let evaluation code avoid depending on contributor-made
filenames.  The CSV is deliberately separate from the source audio so the raw
audio can remain outside the repository.

The legacy Gunshot Booster directory is *not* independent three-class ground
truth.  A small set of names establishes a Danger category (gunshot, alarm,
siren, and explosion).  The remaining legacy background files are cataloged as
Ambient according to the product owner's explicit evaluation annotation.
"""

from __future__ import annotations

import argparse
import csv
import re
import unicodedata
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


CSV_COLUMNS = (
    "sample_id",
    "logical_filename",
    "source_set",
    "source_filename",
    "expected_coarse",
    "category",
    "label_status",
    "notes",
)


@dataclass(frozen=True)
class SourceSample:
    source_set: str
    path: Path
    expected_coarse: str
    category: str
    label_status: str
    notes: str = ""


def normalized_name(path: Path) -> str:
    return unicodedata.normalize("NFC", path.stem).casefold()


def representative_sample(path: Path) -> SourceSample:
    """Infer the contributor-declared coarse label from a representative prefix."""
    name = normalized_name(path)
    if name.startswith(("ambient_", "anbient_")):
        coarse = "ambient"
    elif name.startswith("speech_"):
        coarse = "speech"
    elif name.startswith(("danger_", "dager_")):
        coarse = "danger"
    else:
        return SourceSample(
            "representative", path, "", "unclassified", "review_required",
            "Filename has no recognized Ambient/Speech/Danger prefix.",
        )

    category_rules = (
        ("footstep", "footstep"),
        ("무음", "silence"),
        ("nonvocalmusic", "non_vocal_music"),
        ("보컬있는음악", "vocal_music"),
        ("게임효과음", "game_effect"),
        ("말소리+게임음", "speech_with_game_audio"),
        ("말소리+배경음악", "speech_with_music"),
        ("깨끗한말소리", "clean_speech"),
        ("두 명 이상 대화", "conversation"),
        ("firealarm", "fire_alarm"),
        ("smokedetector", "smoke_detector"),
        ("alarm", "alarm"),
        ("siren", "siren"),
        ("ambulance", "ambulance_siren"),
        ("airhorn", "air_horn"),
        ("carhorn", "car_horn"),
        ("rain", "rain"),
        ("snowstorm", "snow_storm"),
        ("stream", "stream"),
        ("wave", "wave"),
        ("bird", "bird"),
        ("교통", "traffic"),
        ("drum", "drum"),
        ("기침", "cough"),
        ("딸꾹질", "hiccup"),
        ("박수", "clap"),
        ("풍선터짐", "balloon_pop"),
        ("문쾅닫기", "door_slam"),
        ("접시깨짐", "dishes_breaking"),
        ("금속충격", "metal_impact"),
        ("차량 배기음", "vehicle_exhaust"),
    )
    category = next((value for token, value in category_rules if token in name), "other")
    notes = ""
    if name.startswith(("anbient_", "dager_")):
        notes = "Source filename prefix typo normalized only in this catalog."
    return SourceSample("representative", path, coarse, category, "declared_by_filename", notes)


def legacy_sample(path: Path) -> SourceSample:
    """Classify only legacy names whose semantic category is explicit."""
    name = normalized_name(path)
    rules = (
        ("gunshot, gunfire", "gunshot"),
        ("machine gun", "machine_gun"),
        ("fusillade", "fusillade"),
        ("cap gun", "cap_gun"),
        ("explosion", "explosion"),
        ("alarm", "alarm"),
        ("ambulance", "ambulance_siren"),
        ("police car", "police_siren"),
        ("siren", "siren"),
    )
    category = next((value for token, value in rules if name.startswith(token)), None)
    if category is not None:
        return SourceSample("legacy_booster", path, "danger", category, "declared_by_filename")
    return SourceSample(
        "legacy_booster", path, "ambient", "background", "annotated_by_product_owner",
        "Ambient annotation supplied for this legacy background group; the group remains non-independent Booster data.",
    )


def wavs(directory: Path) -> Iterable[Path]:
    return sorted(
        (path for path in directory.iterdir() if path.is_file() and path.suffix.casefold() == ".wav"),
        key=lambda path: unicodedata.normalize("NFC", path.name).casefold(),
    )


def build_samples(representative_dir: Path, legacy_dir: Path) -> list[SourceSample]:
    return [*(representative_sample(path) for path in wavs(representative_dir)), *(legacy_sample(path) for path in wavs(legacy_dir))]


def safe_slug(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", value.casefold()).strip("-") or "unknown"


def write_manifest(samples: Iterable[SourceSample], output: Path) -> None:
    counters: defaultdict[tuple[str, str, str], int] = defaultdict(int)
    with output.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=CSV_COLUMNS)
        writer.writeheader()
        for sample in samples:
            class_part = sample.expected_coarse or "review"
            key = (sample.source_set, class_part, sample.category)
            counters[key] += 1
            number = counters[key]
            sample_id = "-".join(
                (safe_slug(sample.source_set), safe_slug(class_part), safe_slug(sample.category), f"{number:03d}")
            )
            writer.writerow(
                {
                    "sample_id": sample_id,
                    "logical_filename": f"{sample_id}.wav",
                    "source_set": sample.source_set,
                    "source_filename": sample.path.name,
                    "expected_coarse": sample.expected_coarse,
                    "category": sample.category,
                    "label_status": sample.label_status,
                    "notes": sample.notes,
                }
            )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--representative-dir", type=Path, required=True)
    parser.add_argument("--legacy-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    for directory in (args.representative_dir, args.legacy_dir):
        if not directory.is_dir():
            parser.error(f"not a directory: {directory}")
    samples = build_samples(args.representative_dir, args.legacy_dir)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    write_manifest(samples, args.output)
    print(f"Wrote {len(samples)} catalog rows to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
