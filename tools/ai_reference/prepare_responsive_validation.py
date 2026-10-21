"""Freeze additional diagnostic audio BEFORE evaluating the unchanged #407 arm.

Downloads pinned public sources to a NEW directory. No audio is committed.
Requires soundfile==0.13.1 for Ogg decoding; no normalization or extra resampling.
This is new-to-this-experiment data, not a claim about YAMNet's training overlap.
"""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
from urllib.parse import quote
from urllib.request import urlopen


ESC = "33c8ce9eb2cf0b1c2f8bcf322eb349b6be34dbb6"
LIBROSA = "38f4b06556fa0ff1acda5e677d8ba05d1bc0fff0"
FSDD = "26eb9aaf76e81b692f806f9140c2d2777410d7a1"
LIBROSA_TRACKS = {
    "198-209-0000": "speech",
    "3436-172162-0000": "speech",
    "5703-47212-0000": "speech",
    "admiralbob77_-_Choice_-_Drum-bass": "ambient",
    "Kevin_MacLeod_-_Vibe_Ace": "ambient",
    "147793__setuniman__sweet-waltz-0i-22mi": "ambient",
    "Karissa_Hobbs_-_Lets_Go_Fishin": "ambient",
}
# Approved current coarse policy, NOT old ESC proxy labels. Non-speech vocals
# and alarm clocks are not turned into conversational-Speech ground truth.
DANGER = {"car_horn", "chainsaw", "fireworks", "glass_breaking", "siren", "thunderstorm"}
AMBIENT = {"airplane", "can_opening", "church_bells", "clapping", "coughing",
           "crackling_fire", "door_wood_knock", "engine", "footsteps", "hand_saw",
           "helicopter", "keyboard_typing", "rain", "sneezing", "train",
           "washing_machine", "water_drops"}


def get(url):
    with urlopen(url, timeout=60) as response:
        return response.read()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def select_esc(metadata, excluded_sources, per_category=3):
    selected = []
    seen = set(excluded_sources)
    for category in sorted(DANGER | AMBIENT):
        candidates = [r for r in metadata if r["category"] == category]
        candidates.sort(key=lambda r: digest(("407-validation-v1/" + r["filename"]).encode()))
        chosen = []
        for row in candidates:
            if row["src_file"] not in seen:
                chosen.append(row)
                seen.add(row["src_file"])
            if len(chosen) == per_category:
                break
        selected.extend(chosen)
    return selected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--previous-manifest", type=Path, required=True)
    parser.add_argument("--previous-esc-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    args.output_dir.mkdir(exist_ok=False, parents=True)
    previous_raw = args.previous_manifest.read_bytes()
    previous = json.loads(previous_raw)
    previous = previous.get("rows", []) if isinstance(previous, dict) else previous
    old_paths = [Path(row["path"]) for row in previous]
    old_esc = {p.name for p in old_paths if "esc50" in str(p).lower()}
    old_esc.update(p.name for p in args.previous_esc_dir.glob("*.wav"))
    excluded_sources = {name.split("-")[1] for name in old_esc}
    previous_hashes = {digest(p.read_bytes()) for p in old_paths}
    metadata_url = f"https://raw.githubusercontent.com/karolpiczak/ESC-50/{ESC}/meta/esc50.csv"
    metadata_raw = get(metadata_url)
    (args.output_dir / "esc50.csv").write_bytes(metadata_raw)
    metadata = list(csv.DictReader(io.StringIO(metadata_raw.decode())))
    plan = []
    selected = select_esc(metadata, excluded_sources)
    coverage = {category: sum(r["category"] == category for r in selected)
                for category in sorted(DANGER | AMBIENT)}
    print(f"Unseen source-group coverage (target up to 3/category): {coverage}", flush=True)
    for item in selected:
        filename = item["filename"]
        plan.append(dict(dataset="esc50_new", filename=filename, category=item["category"],
                         expected="danger" if item["category"] in DANGER else "ambient",
                         source_group=item["src_file"], license="CC BY-NC 3.0 (ESC-50)",
                         url=f"https://raw.githubusercontent.com/karolpiczak/ESC-50/{ESC}/audio/{filename}"))
    for stem, expected in LIBROSA_TRACKS.items():
        plan.append(dict(dataset="librosa_new", filename=stem + ".ogg", expected=expected,
                         category="read_speech" if expected == "speech" else "music",
                         source_group=stem, license="See downloaded per-track attribution",
                         url=f"https://raw.githubusercontent.com/librosa/data/{LIBROSA}/audio/{quote(stem)}.ogg"))
    # Official FSDD test index 0; all digits and all six speakers. Deliberately
    # includes very short speech, rather than selecting successful predictions.
    for speaker in ("george", "jackson", "lucas", "nicolas", "theo", "yweweler"):
        for digit in range(10):
            filename = f"{digit}_{speaker}_0.wav"
            plan.append(dict(dataset="fsdd_new", filename=filename, expected="speech",
                             category="short_digit", source_group=speaker,
                             license="CC BY-SA 4.0",
                             url=f"https://raw.githubusercontent.com/Jakobovski/free-spoken-digit-dataset/{FSDD}/recordings/{filename}"))
    (args.output_dir / "selection-before-inference.json").write_text(json.dumps(plan, indent=2))
    import soundfile as sf
    rows = []
    for index, item in enumerate(plan):
        raw = get(item["url"])
        if digest(raw) in previous_hashes:
            raise ValueError(f"Exact prior audio overlap: {item['filename']}")
        path = args.output_dir / item["filename"]
        path.write_bytes(raw)
        row = dict(item, path=str(path), policy="primary", downloaded_sha256=digest(raw))
        if path.suffix == ".ogg":
            attribution = get(item["url"].removesuffix(".ogg") + ".txt")
            path.with_suffix(".txt").write_bytes(attribution)
            samples, rate = sf.read(path, dtype="float32", always_2d=True)
            # Keep original download. Separate PCM16 decoding copy, no gain,
            # truncation, time stretch or extra resampling.
            decoded = path.with_suffix(".wav")
            sf.write(decoded, samples, rate, subtype="PCM_16")
            row["path"] = str(decoded)
            row["decoded_sha256"] = digest(decoded.read_bytes())
        rows.append(row)
        if index % 20 == 0:
            print(f"downloaded {index + 1}/{len(plan)}", flush=True)
    report = dict(previous_manifest_sha256=digest(previous_raw),
                  esc_metadata_sha256=digest(metadata_raw),
                  excluded_esc_source_groups=sorted(excluded_sources),
                  new_esc_category_coverage=coverage,
                  exact_previous_audio_overlap=0,
                  selection="407-validation-v1; source-disjoint ESC, predetermined librosa and FSDD",
                  limitations="New-to-experiment diagnostic, not training-disjoint or representative of Korean conversation.",
                  rows=rows)
    output = args.output_dir / "manifest.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"Frozen {len(rows)} files: {output}")


if __name__ == "__main__":
    main()
