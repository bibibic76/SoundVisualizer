#!/usr/bin/env python3
"""Compare current and official-style YAMNet frontends over a WAV directory."""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from classifier import ReferenceClassifier
from compare_logmel_frontends import (
    HOP,
    LOG_EPS,
    MELS,
    NFFT,
    WIN,
    Yamnet,
    _HANN,
    _TF_MEL,
    _fit,
    QAI_HUB_MODELS_COMMIT,
    TORCH_AUDIOSET_COMMIT,
    current_log_mel,
    official_log_mel,
    qualcomm_source_log_mel,
)
from preprocess import (
    REQUIRED_MONO_16K_SAMPLES,
    capture_samples_for_one_yamnet_window,
    create_mel_filter_bank,
    resample_mono_float_to_16k_custom,
)
from wav_io import load_wav_as_capture_mono

POSITIVE_PREFIXES = ("Gunshot, gunfire", "Machine gun", "Fusillade", "Cap gun")
_CURRENT_MEL = create_mel_filter_bank().astype(np.float64).T


def frontend_log_mels(mono16k: np.ndarray, repo: Path) -> dict[str, np.ndarray]:
    fitted = _fit(mono16k)
    frames = np.stack(
        [fitted[t * HOP : t * HOP + WIN] * _HANN for t in range(96)]
    )
    magnitude = np.abs(np.fft.rfft(frames, n=NFFT))
    power = magnitude * magnitude
    return {
        "current_power_integer_mel": current_log_mel(mono16k, repo),
        "magnitude_integer_mel": np.log(
            magnitude @ _CURRENT_MEL + LOG_EPS
        ).astype(np.float32),
        "power_tf_mel": np.log(power @ _TF_MEL + LOG_EPS).astype(np.float32),
        "qualcomm_source_magnitude_torch_mel_centered": qualcomm_source_log_mel(
            mono16k
        ),
        "official_magnitude_tf_mel": official_log_mel(mono16k),
    }


def mono16k_for_replay(path: Path) -> np.ndarray:
    mono, sample_rate, _ = load_wav_as_capture_mono(path)
    required = capture_samples_for_one_yamnet_window(sample_rate)
    if mono.size >= required:
        tail = mono[-required:]
    else:
        tail = np.zeros(required, dtype=np.float32)
        tail[-mono.size :] = mono
    return resample_mono_float_to_16k_custom(
        tail,
        sample_rate,
        REQUIRED_MONO_16K_SAMPLES,
    )


def mono16k_windows(path: Path, mode: str) -> list[np.ndarray]:
    if mode == "tail":
        return [mono16k_for_replay(path)]

    mono, sample_rate, _ = load_wav_as_capture_mono(path)
    destination_length = max(
        1,
        int(round(mono.size * 16000.0 / float(sample_rate))),
    )
    resampled = resample_mono_float_to_16k_custom(
        mono,
        sample_rate,
        destination_length,
    )
    return mean_nonoverlap_windows(resampled)


def mean_nonoverlap_windows(resampled: np.ndarray) -> list[np.ndarray]:
    """Return full windows, except that a wholly short file remains usable."""
    if resampled.size <= REQUIRED_MONO_16K_SAMPLES:
        # The frontend pads this only window. Once a file has a full window,
        # however, a short tail is excluded instead of receiving equal weight.
        return [resampled]
    full_window_count = resampled.size // REQUIRED_MONO_16K_SAMPLES
    return [
        resampled[start : start + REQUIRED_MONO_16K_SAMPLES]
        for start in range(
            0,
            full_window_count * REQUIRED_MONO_16K_SAMPLES,
            REQUIRED_MONO_16K_SAMPLES,
        )
    ]


def booster_score(classifier: ReferenceClassifier, features: np.ndarray) -> float:
    return float(
        np.asarray(
            classifier._booster.run(
                [classifier._booster_out],
                {
                    classifier._booster_in: np.asarray(
                        features,
                        dtype=np.float32,
                    ).reshape(1, -1)
                },
            )[0]
        ).reshape(-1)[0]
    )


def map_scores(classifier: ReferenceClassifier, scores: np.ndarray) -> dict:
    top_indices, top_probs = classifier._compute_top5(scores)
    top_index = int(top_indices[0])
    confidence = float(top_probs[0])
    display = classifier._class_names[top_index]
    top_index, confidence, display = classifier._prefer_danger_when_top_is_masked_by_game_mix(
        scores,
        top_index,
        confidence,
        display,
    )
    coarse = classifier._vote_coarse_from_top5(top_indices, top_probs, 3)
    top5 = [
        {
            "index": int(top_indices[i]),
            "name": classifier._class_names[int(top_indices[i])],
            "probability": float(top_probs[i]),
        }
        for i in range(5)
    ]
    return {
        "top1": top5[0]["name"],
        "top1_probability": top5[0]["probability"],
        "display": display,
        "confidence": confidence,
        "coarse": coarse,
        "top5": top5,
    }


def classify_probs(
    classifier: ReferenceClassifier,
    probabilities: np.ndarray,
    sigmoid_scores: np.ndarray,
    logits: np.ndarray,
) -> dict:
    result = map_scores(classifier, probabilities)
    result["sigmoid"] = map_scores(classifier, sigmoid_scores)
    result["booster_softmax_score"] = booster_score(classifier, probabilities)
    result["booster_sigmoid_score"] = booster_score(classifier, sigmoid_scores)
    result["booster_raw_logits_score"] = booster_score(classifier, logits)
    return result


def score_distribution(values: list[float]) -> dict[str, float]:
    quantiles = np.quantile(values, [0.0, 0.25, 0.5, 0.75, 1.0])
    return {
        "min": float(quantiles[0]),
        "q25": float(quantiles[1]),
        "median": float(quantiles[2]),
        "q75": float(quantiles[3]),
        "max": float(quantiles[4]),
    }


def summarize(rows: list[dict], frontend: str, label: str) -> dict:
    selected = [row[frontend] for row in rows if row["label"] == label]
    return {
        "count": len(selected),
        "top1": Counter(item["top1"] for item in selected).most_common(10),
        "softmax_coarse": dict(Counter(item["coarse"] for item in selected)),
        "sigmoid_coarse": dict(
            Counter(item["sigmoid"]["coarse"] for item in selected)
        ),
        "booster_softmax_score": score_distribution(
            [item["booster_softmax_score"] for item in selected]
        ),
        "booster_sigmoid_score": score_distribution(
            [item["booster_sigmoid_score"] for item in selected]
        ),
        "booster_raw_logits_score": score_distribution(
            [item["booster_raw_logits_score"] for item in selected]
        ),
    }


def precision_recall_f1(
    labels: np.ndarray,
    scores: np.ndarray,
    threshold: float,
) -> dict[str, float]:
    predicted = scores >= threshold
    true_positive = int(np.sum(predicted & (labels == 1)))
    false_positive = int(np.sum(predicted & (labels == 0)))
    false_negative = int(np.sum(~predicted & (labels == 1)))
    precision = (
        true_positive / (true_positive + false_positive)
        if true_positive + false_positive
        else 0.0
    )
    recall = (
        true_positive / (true_positive + false_negative)
        if true_positive + false_negative
        else 0.0
    )
    f1 = 2.0 * precision * recall / (precision + recall) if precision + recall else 0.0
    return {
        "threshold": float(threshold),
        "precision": float(precision),
        "recall": float(recall),
        "f1": float(f1),
    }


def booster_discrimination(rows: list[dict], frontend: str, score_key: str) -> dict:
    labels = np.asarray([row["label"] == "positive" for row in rows], dtype=np.int32)
    scores = np.asarray([row[frontend][score_key] for row in rows], dtype=np.float64)
    positive_scores = scores[labels == 1]
    negative_scores = scores[labels == 0]
    comparisons = positive_scores[:, None] - negative_scores[None, :]
    auc = float(np.mean((comparisons > 0.0) + 0.5 * (comparisons == 0.0)))
    candidates = [
        precision_recall_f1(labels, scores, float(threshold))
        for threshold in np.unique(scores)
    ]
    best = max(candidates, key=lambda item: (item["f1"], item["threshold"]))
    return {
        "auc": auc,
        "best_full_dataset": best,
        "at_recorded_training_threshold_0_28": precision_recall_f1(
            labels,
            scores,
            0.28,
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--wav-dir", type=Path, required=True)
    parser.add_argument("--json", type=Path)
    parser.add_argument(
        "--window-mode",
        choices=("tail", "mean-nonoverlap"),
        default="tail",
        help="Replay the last app-sized window or average all non-overlapping windows",
    )
    args = parser.parse_args()

    model_dir = args.repo / "app/src/main/assets/ai"
    yamnet = Yamnet(args.repo)
    classifier = ReferenceClassifier(model_dir)
    paths = sorted(args.wav_dir.glob("*.wav"))
    rows = []
    for path in paths:
        windows = mono16k_windows(path, args.window_mode)
        log_mel_windows: dict[str, list[np.ndarray]] = {}
        for window in windows:
            for frontend, log_mel in frontend_log_mels(window, args.repo).items():
                log_mel_windows.setdefault(frontend, []).append(log_mel)
        label = "positive" if path.stem.startswith(POSITIVE_PREFIXES) else "negative"
        row = {"file": path.name, "label": label, "windows": len(windows)}
        for frontend, log_mels in log_mel_windows.items():
            logits_per_window = np.stack(
                [yamnet.logits(log_mel) for log_mel in log_mels]
            )
            logits = logits_per_window.mean(axis=0)
            probabilities = np.stack(
                [yamnet.softmax(values) for values in logits_per_window]
            ).mean(axis=0)
            sigmoid_scores = np.stack(
                [yamnet.sigmoid(values) for values in logits_per_window]
            ).mean(axis=0)
            row[frontend] = classify_probs(
                classifier,
                probabilities,
                sigmoid_scores,
                logits,
            )
            log_mel_values = np.stack(log_mels)
            row[f"{frontend}_stats"] = {
                "min": float(log_mel_values.min()),
                "max": float(log_mel_values.max()),
                "mean": float(log_mel_values.mean()),
                "std": float(log_mel_values.std()),
            }
        rows.append(row)

    frontends = list(frontend_log_mels(np.zeros(REQUIRED_MONO_16K_SAMPLES), args.repo))

    report = {
        "source_provenance": {
            "qualcomm_recipe": {
                "repository": "https://github.com/qualcomm/ai-hub-models",
                "commit": QAI_HUB_MODELS_COMMIT,
                "entrypoint": "src/qai_hub_models/models/yamnet/app.py::preprocessing_yamnet_from_source",
            },
            "torch_audioset": {
                "repository": "https://github.com/w-hc/torch_audioset",
                "commit": TORCH_AUDIOSET_COMMIT,
                "functions": [
                    "torch_audioset/data/torch_input_processing.py::WaveformToInput",
                    "torch_audioset/data/torch_input_processing.py::VGGishLogMelSpectrogram",
                ],
            },
            "parity_scope": "verify_torchaudio_logmel_parity.py compares the NumPy port with WaveformToInput imported from the pinned torch_audioset commit under pinned torch/torchaudio versions",
        },
        "limitations": [
            "official_log_mel is a NumPy port; verify_tensorflow_logmel_parity.py checks its numerical parity separately",
            "the packaged ONNX metadata does not identify its source revision, so Qualcomm recipe parity does not prove which exact source tree exported the artifact",
            "positive/negative labels are inferred only from filename prefixes and are not three-class ground truth",
            "Booster activation/input variants are diagnostic only; the training feature contract is not yet proven",
            "coarse is the top-5 mapper vote only, not the app's final UI result after Booster, safety promotion, thresholding, and hysteresis",
            "mean-nonoverlap drops a trailing partial window when at least one full window exists; a file shorter than one window is zero-padded by the frontend",
            "mean-nonoverlap windowing is a diagnostic approximation, not a recovered training pipeline",
            "Booster discrimination metrics use the full training corpus and are not held-out performance estimates",
        ],
        "window_mode": args.window_mode,
        "files": len(rows),
        "top1_changed": sum(
            row[frontends[0]]["top1"] != row[frontends[-1]]["top1"] for row in rows
        ),
        "coarse_changed": sum(
            row[frontends[0]]["coarse"] != row[frontends[-1]]["coarse"] for row in rows
        ),
        "comparisons": {
            f"{left}_vs_{right}": {
                "top1_changed": sum(
                    row[left]["top1"] != row[right]["top1"] for row in rows
                ),
                "coarse_changed": sum(
                    row[left]["coarse"] != row[right]["coarse"] for row in rows
                ),
            }
            for left, right in (
                (
                    "current_power_integer_mel",
                    "qualcomm_source_magnitude_torch_mel_centered",
                ),
                (
                    "qualcomm_source_magnitude_torch_mel_centered",
                    "official_magnitude_tf_mel",
                ),
            )
        },
        "summary": {
            frontend: {
                label: summarize(rows, frontend, label)
                for label in ("positive", "negative")
            }
            for frontend in frontends
        },
        "booster_discrimination": {
            frontend: {
                score_key: booster_discrimination(rows, frontend, score_key)
                for score_key in (
                    "booster_softmax_score",
                    "booster_sigmoid_score",
                    "booster_raw_logits_score",
                )
            }
            for frontend in frontends
        },
        "rows": rows,
    }
    rendered = json.dumps(report, indent=2, ensure_ascii=False)
    if args.json:
        args.json.write_text(rendered, encoding="utf-8")
    print(
        json.dumps(
            {
                key: report[key]
                for key in (
                    "limitations",
                    "source_provenance",
                    "window_mode",
                    "files",
                    "top1_changed",
                    "coarse_changed",
                    "comparisons",
                    "summary",
                    "booster_discrimination",
                )
            },
            indent=2,
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
