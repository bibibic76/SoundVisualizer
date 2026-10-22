"""Freeze #416 public candidate audio and review queue; never runs a model.

Requires numpy, soundfile, pyarrow. Audio stays in a new --audio-dir, evidence
in a separate new --output-dir. Does not claim human-reviewed holdout readiness.
"""
import argparse
from collections import Counter
import csv
import io
import json
from pathlib import Path
import re
from urllib.parse import quote
from urllib.request import urlopen

from verifier_corpus import POLICY_SHA, audit, ranked, select_speakers, sha

ESC = "33c8ce9eb2cf0b1c2f8bcf322eb349b6be34dbb6"
LIBROSA = "38f4b06556fa0ff1acda5e677d8ba05d1bc0fff0"
KOREAN = "1fe937899f828af822293d05e086200946088bdf"
PARQUET_SHA = "2f6382b902622ede1bf35e5a167a9b06b0f1b8c896e1947324b8b5180fca7502"
CATEGORIES = {"sneezing": "ambient", "coughing": "ambient", "breathing": "ambient",
              "laughing": "speech", "crying_baby": "speech"}
MUSIC = [
    ("Hungarian_Dance_number_5_-_Allegro_in_F_sharp_minor_(string_orchestra)", "us-army-strings", "Public domain (attribution statement)", "musopen:43805"),
    ("sorohanro_-_solo-trumpet-06", "mihai-sorohan", "CC BY 3.0", "freesound:77711"),
    ("drese-tempo", "stefan-balke", "CC BY 4.0", "librosa:drese"),
]


def get(url):
    with urlopen(url, timeout=45) as response:
        return response.read()


def save_json(path, value):
    with path.open("x", encoding="utf-8") as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def pcm_info(raw):
    import numpy as np
    import soundfile as sf
    samples, rate = sf.read(io.BytesIO(raw), dtype="float32", always_2d=True)
    if len(samples) == 0 or not np.isfinite(samples).all():
        raise ValueError("Empty/non-finite audio")
    header = f"{rate}/{samples.shape[1]}/float32le/".encode()
    return {"pcm_sha256": sha(header + samples.astype("<f4").tobytes()),
            "sample_rate": rate, "channels": samples.shape[1], "frames": len(samples),
            "duration_ms": len(samples) * 1000. / rate}


def esc_credits(text):
    credits = {}
    for line in text.splitlines():
        found = re.search(r"\[(\d+-\d+-[A-Z])\.ogg\].* by (.+) \[([^\]]+)\]", line)
        if found:
            stem, author, license_name = found.groups()
            credits[stem] = {"author": author, "original_license": license_name, "attribution": line}
    return credits


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--korean-parquet", type=Path, required=True)
    parser.add_argument("--prior-manifest", type=Path, action="append", required=True)
    parser.add_argument("--prior-audio-root", type=Path, action="append", default=[])
    parser.add_argument("--audio-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    if args.audio_dir.exists() or args.output_dir.exists():
        parser.error("Output directories must be new")
    if sha(args.korean_parquet.read_bytes()) != PARQUET_SHA:
        parser.error("Korean parquet differs from pinned official test mirror")
    import pyarrow.parquet as pq
    import pyarrow
    import numpy as np
    import soundfile as sf
    table = pq.read_table(args.korean_parquet).to_pylist()
    if len(table) != 457 or len({r["speaker_id"] for r in table}) != 10:
        parser.error("Unexpected Korean source metadata")
    base = f"https://raw.githubusercontent.com/karolpiczak/ESC-50/{ESC}"
    metadata_raw, license_raw = get(base + "/meta/esc50.csv"), get(base + "/LICENSE")
    metadata = list(csv.DictReader(io.StringIO(metadata_raw.decode())))
    by_name = {r["filename"]: r for r in metadata}
    credits = esc_credits(license_raw.decode())
    old_paths, groups, prior_records = set(), set(), []
    declared_hashes = set()
    for manifest_path in args.prior_manifest:
        raw = manifest_path.read_bytes()
        prior = json.loads(raw)
        prior = prior["rows"] if isinstance(prior, dict) else prior
        prior_records.append({"name": manifest_path.name, "sha256": sha(raw), "rows": len(prior)})
        for row in prior:
            old_paths.add(Path(row["path"]))
            declared_hashes.update(row[key] for key in ("downloaded_sha256", "decoded_sha256") if row.get(key))
            if row.get("dataset", "").startswith("fsdd"):
                groups.add("fsdd:speaker:" + row["source_group"])
            if row.get("dataset", "").startswith("librosa"):
                groups.add("librosa:track:" + row["source_group"])
                stem_parts = row["source_group"].split("__")
                if len(stem_parts) >= 3 and stem_parts[0].isdigit():
                    groups.update({"freesound:" + stem_parts[0], "freesound:contributor:" + stem_parts[1].casefold()})
    for root in args.prior_audio_root:
        if not root.is_dir():
            parser.error(f"Prior audio root missing: {root}")
        old_paths.update(root.rglob("*.wav"))
    file_hashes, pcm_hashes, unreadable = set(declared_hashes), set(), []
    for path in sorted(old_paths):
        if not path.is_file():
            raise FileNotFoundError(path)
        raw = path.read_bytes()
        file_hashes.add(sha(raw))
        try:
            pcm_hashes.add(pcm_info(raw)["pcm_sha256"])
        except (RuntimeError, ValueError) as error:
            unreadable.append({"filename": path.name, "error": str(error)})
        if path.name in by_name:
            meta = by_name[path.name]
            groups.add("freesound:" + meta["src_file"])
            credit = credits.get("-".join(path.stem.split("-")[:3]))
            if credit:
                groups.add("freesound:contributor:" + credit["author"].casefold())
    exclusions = {"schema": 1, "manifests": prior_records, "audio_roots": [str(p) for p in args.prior_audio_root],
                  "scanned_files": len(old_paths), "unreadable_pcm": unreadable,
                  "groups": sorted(groups), "file_sha256": sorted(file_hashes), "pcm_sha256": sorted(pcm_hashes)}
    selected = []
    korean_by_id = {r["id"]: r for r in table}
    for row in select_speakers(table):
        selected.append({"id": "zeroth:" + row["id"], "dataset": "zeroth-korean",
            "source_id": row["id"], "stratum": "korean_read", "expected_hint": "speech", "language": "ko",
            "groups": ["zeroth:speaker:" + str(row["speaker_id"]), "zeroth:recording:" + row["id"]],
            "official_split": "test", "usage": "attribution_required", "license": "CC BY 4.0",
            "source_url": "https://www.openslr.org/40/", "mirror_revision": KOREAN,
            "attribution": "Zeroth Project — Lucas Jo / Atlas Guide, Wonkyum Lee / Gridspace; CC BY 4.0",
            "provenance_status": "mirror_pinned_upstream_bytes_unverified", "identity_status": "verified",
            "filename": row["id"] + ".flac"})
    seen_sources = set(groups)
    rejected = Counter()
    for category, hint in CATEGORIES.items():
        count = 0
        for row in ranked([r for r in metadata if r["category"] == category], lambda r: r["filename"]):
            stem = "-".join(Path(row["filename"]).stem.split("-")[:3])
            credit = credits.get(stem)
            if not credit:
                rejected["missing_attribution"] += 1
                continue
            identities = {"freesound:" + row["src_file"], "freesound:contributor:" + credit["author"].casefold()}
            if identities & seen_sources:
                rejected[category + ":prior_or_selected_group"] += 1
                continue
            seen_sources.update(identities)
            selected.append({"id": "esc:" + row["filename"], "dataset": "esc50", "source_id": row["src_file"],
                "stratum": category, "expected_hint": hint, "language": "not_applicable", "groups": sorted(identities),
                "usage": "research_only", "license": "ESC-50 dataset: CC BY-NC 3.0; original: " + credit["original_license"],
                "source_url": base + "/audio/" + row["filename"], "source_revision": ESC,
                "attribution": credit["attribution"], "provenance_status": "upstream_verified",
                "identity_status": "contributor_known_performer_unknown", "filename": row["filename"]})
            count += 1
            if count == 6:
                break
    for stem, artist, license_name, source_group in MUSIC:
        selected.append({"id": "librosa:" + stem, "dataset": "librosa", "source_id": source_group,
            "stratum": "instrumental_music", "expected_hint": "ambient", "language": "not_applicable",
            "groups": [source_group, "performer:" + artist], "usage": "attribution_required", "license": license_name,
            "source_url": f"https://raw.githubusercontent.com/librosa/data/{LIBROSA}/audio/{quote(stem)}.ogg",
            "source_revision": LIBROSA, "provenance_status": "upstream_verified", "identity_status": "verified",
            "filename": stem + ".ogg"})
    args.output_dir.mkdir(parents=True)
    args.audio_dir.mkdir(parents=True)
    save_json(args.output_dir / "exclusions.json", exclusions)
    save_json(args.output_dir / "selection.json", selected)  # BEFORE selected audio decode / downloads
    rows = []
    for index, item in enumerate(selected, 1):
        if Path(item["filename"]).name != item["filename"] or item["filename"] in (".", ".."):
            raise ValueError("Unsafe audio filename")
        if item["dataset"] == "zeroth-korean":
            raw = korean_by_id[item["source_id"]]["audio"]["bytes"]
        else:
            raw = get(item["source_url"])
        if item["dataset"] == "librosa":
            item = dict(item, attribution=get(item["source_url"].removesuffix(".ogg") + ".txt").decode())
        info = pcm_info(raw)
        if sha(raw) in file_hashes or info["pcm_sha256"] in pcm_hashes or set(item["groups"]) & groups:
            raise ValueError(f"Prior overlap: {item['id']}")
        (args.audio_dir / item["filename"]).write_bytes(raw)
        rows.append(dict(item, **info, file_sha256=sha(raw), role="holdout_reserved", reservation="holdout_reserved",
                         parent_ids=[], review={"status": "pending", "reviewer_ids": []}, events=[]))
        if index % 10 == 0 or index == len(selected):
            print(f"Prepared {index}/{len(selected)} without inference", flush=True)
    manifest = {"schema": 1, "policy_sha256": POLICY_SHA,
        "selection_sha256": sha((args.output_dir / "selection.json").read_bytes()),
        "exclusions_sha256": sha((args.output_dir / "exclusions.json").read_bytes()),
        "sources": {"zeroth_parquet_sha256": PARQUET_SHA, "esc_metadata_sha256": sha(metadata_raw),
                    "esc_license_sha256": sha(license_raw)},
        "versions": {"numpy": np.__version__, "soundfile": sf.__version__, "libsndfile": sf.__libsndfile_version__, "pyarrow": pyarrow.__version__},
        "rejected_selection_counts": dict(rejected),
        "unresolved": ["Historical audio has incomplete speaker/performer identities; exact hashes do not establish full independence",
                       "Near-duplicate / prior-model pretraining overlap not audited",
                       "No independently annotated natural Korean conversation or transition/Danger coverage",
                       "No train/calibration pool prepared; this is a reserved evaluation candidate pool"], "rows": rows}
    if unreadable:
        manifest["unresolved"].append("Some historical audio could not be PCM-hashed")
    save_json(args.output_dir / "manifest.json", manifest)
    report = audit(manifest, exclusions, selected)
    save_json(args.output_dir / "audit.json", report)
    # Separate task queue: reviewers must produce interval labels, not accept hints.
    save_json(args.output_dir / "review-queue.json", [{"id": r["id"], "filename": r["filename"],
              "duration_ms": r["duration_ms"], "review": r["review"], "events": []} for r in rows])
    print(json.dumps({k: v for k, v in report.items() if k not in ("errors", "blockers")}, ensure_ascii=False))
    if report["errors"]:
        raise ValueError(report["errors"])


if __name__ == "__main__":
    main()
