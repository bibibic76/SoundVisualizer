#!/usr/bin/env python3
"""Export CP4 mapper goldens: handcrafted branches + silence/gunshot/alarm samples."""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np

import mapper
from classifier import ReferenceClassifier
from wav_io import load_wav_as_capture_mono

REPO = Path(__file__).resolve().parents[2]
OUT_DIR = REPO / "app" / "src" / "test" / "resources" / "ai_reference"
MODEL_DIR = Path(
    "app/src/main/assets/ai"
)
ROOT = Path(__file__).resolve().parent

SAMPLE_WAVS = {
    "silence": ROOT / ".out" / "silence_1s_16k.wav",
    "gunshot": Path(
        "data/preprocessed/"
        "Gunshot, gunfire_short-explosion-1694.wav"
    ),
    "alarm": Path(
        "data/preprocessed/"
        "Alarm_classic-alarm-995.wav"
    ),
}


def write_f32(path: Path, arr: np.ndarray) -> None:
    path.write_bytes(np.asarray(arr, dtype=np.float32).reshape(-1).astype("<f4").tobytes())


def classify_probs(clf: ReferenceClassifier, probs: np.ndarray) -> dict:
    probs = np.asarray(probs, dtype=np.float32).reshape(-1)
    assert probs.size == 521
    top_idx, top_probs = clf._compute_top5(probs)
    max_index = int(top_idx[0])
    conf = float(top_probs[0])
    display = clf._class_names[max_index]
    before = (max_index, conf, display)
    max_index, conf, display = clf._prefer_danger_when_top_is_masked_by_game_mix(
        probs, max_index, conf, display
    )
    scores = clf._coarse_vote_scores(top_idx, top_probs, 3)
    coarse = clf._vote_coarse_from_top5(top_idx, top_probs, 3)
    return {
        "coarse": coarse,
        "display": display,
        "confidence": conf,
        "yamnet_class_index": max_index,
        "game_mix_preference_applied": (max_index, conf, display) != before,
        "vote_scores": {
            "ambient": float(scores["ambient"]),
            "speech": float(scores["speech"]),
            "danger": float(scores["danger"]),
        },
        "top5": [
            {
                "index": int(top_idx[i]),
                "name": clf._class_names[int(top_idx[i])],
                "prob": float(top_probs[i]),
            }
            for i in range(5)
            if int(top_idx[i]) >= 0
        ],
    }


def one_hotish(indices_probs: dict[int, float]) -> np.ndarray:
    p = np.zeros(521, dtype=np.float32)
    for i, v in indices_probs.items():
        p[int(i)] = float(v)
    s = float(p.sum())
    if s <= 0:
        p[:] = 1.0 / 521.0
    else:
        p /= s
    return p


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    clf = ReferenceClassifier(MODEL_DIR)
    assert len(clf._class_names) == 521

    cases = {
        "ambient": one_hotish({277: 0.55, 132: 0.20, 507: 0.10}),
        "speech": one_hotish({0: 0.60, 2: 0.15, 132: 0.05}),
        "danger": one_hotish({421: 0.50, 420: 0.20, 132: 0.05}),
        "default_ambient": one_hotish({507: 0.70, 508: 0.10}),
        "vote_over_top1": one_hotish({132: 0.30, 0: 0.25, 2: 0.24, 507: 0.05}),
        "priority_tie": one_hotish({421: 0.20, 0: 0.20, 132: 0.20, 507: 0.05}),
        "proxy_plop": one_hotish({488: 0.55, 132: 0.10}),
        "game_mix": one_hotish({498: 0.40, 421: 0.10, 132: 0.05, 507: 0.05}),
    }

    handcrafted = {}
    for name, probs in cases.items():
        write_f32(OUT_DIR / f"cp4_{name}_probs.bin", probs)
        handcrafted[name] = classify_probs(clf, probs)

    samples = {}
    for name, wav in SAMPLE_WAVS.items():
        if not wav.is_file():
            print(f"WARN missing {wav}")
            continue
        mono, sr, _ch = load_wav_as_capture_mono(wav)
        _result, _trace, diag = clf.classify_mono_capture_once(
            mono, sr, confidence_threshold=0.25, apply_hysteresis=False
        )
        write_f32(OUT_DIR / f"cp4_{name}_probs.bin", diag["probs"])
        samples[name] = classify_probs(clf, diag["probs"])

    mapping_checks = {
        k: mapper.map_display_name_to_coarse(k)
        for k in (
            "Gunshot, gunfire",
            "Speech",
            "Wind",
            "Noise",
            "Plop",
            "Sound effect",
            "Alarm",
            "Rain",
            "Train",
            "Gargling",
            "Music",
            "Silence",
        )
    }

    meta = {
        "num_classes": 521,
        "mapping_checks": mapping_checks,
        "handcrafted": handcrafted,
        "samples": samples,
        "class_map_filled": sum(1 for n in clf._class_names if not n.startswith("class_")),
    }
    (OUT_DIR / "yamnet_cp4_meta.json").write_text(
        json.dumps(meta, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    print(json.dumps(meta, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
