#!/usr/bin/env python3
"""
Sound classifier reference harness.

Usage:
  python run_reference.py /path/to/sample.wav
  python run_reference.py --silence
  python run_reference.py sample.wav --save-npy --json out.json --stream
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict
from pathlib import Path
from typing import Any, Dict, Optional

import numpy as np

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from classifier import ReferenceClassifier
from preprocess import REQUIRED_MONO_16K_SAMPLES, SAMPLE_RATE
from wav_io import load_wav_as_capture_mono, write_silence_wav

DEFAULT_MODEL_DIR = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "assets" / "ai"


def _to_jsonable(obj: Any) -> Any:
    if isinstance(obj, np.ndarray):
        return {
            "_ndarray": True,
            "shape": list(obj.shape),
            "dtype": str(obj.dtype),
        }
    if isinstance(obj, (np.floating, np.integer)):
        return obj.item()
    if isinstance(obj, Path):
        return str(obj)
    if isinstance(obj, dict):
        return {k: _to_jsonable(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [_to_jsonable(v) for v in obj]
    return obj


def build_report(
    wav_path: Path,
    clf: ReferenceClassifier,
    mono: np.ndarray,
    sr: int,
    channels: int,
    result,
    trace,
    diag: Dict[str, Any],
    confidence_threshold: float,
) -> Dict[str, Any]:
    mono16 = diag.get("mono16k")
    tensor = diag.get("tensor")
    probs = diag.get("probs")

    input_info = {
        "path": str(wav_path),
        "sample_rate": sr,
        "channels": channels,
        "duration_sec": float(mono.size / sr) if sr > 0 else 0.0,
        "mono_samples": int(mono.size),
    }

    preprocess = {
        "required_mono_16k_samples": REQUIRED_MONO_16K_SAMPLES,
        "resampled_samples": int(mono16.size) if mono16 is not None else None,
        "tensor_shape": list(tensor.shape) if tensor is not None else None,
        "tensor_min": float(tensor.min()) if tensor is not None else None,
        "tensor_max": float(tensor.max()) if tensor is not None else None,
        "tensor_mean": float(tensor.mean()) if tensor is not None else None,
        "tensor_sha256_16": diag.get("tensor_stats", {}).get("sha256_16"),
    }

    yamnet = {
        "softmax_sum": diag.get("softmax_sum"),
        "top5": diag.get("top5"),
        "yamnet_coarse_before_booster": diag.get("yamnet_coarse_before_booster"),
    }

    coarse = {
        "vote_scores": diag.get("vote_scores"),
        "selected_pre_booster": diag.get("yamnet_coarse_before_booster"),
        "selected_pre_hysteresis": result.coarse_class,
    }

    booster = {
        "gunshot_score": diag.get("gunshot_score"),
        "accepted": diag.get("booster_accepted"),
        "reason": diag.get("booster_reason"),
    }

    final = {
        "pre_hysteresis": asdict(result),
        "post_hysteresis": None
        if trace is None
        else {
            "confirmed_coarse": trace.confirmed_coarse,
            "confirmed_display": trace.confirmed_display,
            "confirmed_confidence": trace.confirmed_confidence,
            "ui_coarse": trace.ui_coarse,
            "ui_display": trace.ui_display,
            "ui_confidence": trace.ui_confidence,
            "use_booster_danger_preview": trace.use_booster_danger_preview,
        },
        "confidence_threshold": confidence_threshold,
        "effective_threshold": diag.get("effective_threshold"),
        "label_text": clf._last_predict_result if hasattr(clf, "_last_predict_result") else None,
    }

    return {
        "model_info": clf.model_info(),
        "input": input_info,
        "preprocess": preprocess,
        "yamnet": yamnet,
        "coarse": coarse,
        "booster": booster,
        "final": final,
    }


def save_checkpoints(out_dir: Path, diag: Dict[str, Any]) -> Dict[str, str]:
    out_dir.mkdir(parents=True, exist_ok=True)
    paths = {}
    if "mono16k" in diag:
        p = out_dir / "ckpt1_mono16k.npy"
        np.save(p, diag["mono16k"])
        paths["checkpoint1_mono16k"] = str(p)
    if "log_mel_flat" in diag:
        p = out_dir / "ckpt2_logmel_96x64.npy"
        np.save(p, diag["log_mel_flat"].reshape(96, 64))
        paths["checkpoint2_logmel"] = str(p)
    if "probs" in diag:
        p = out_dir / "ckpt3_yamnet_probs_521.npy"
        np.save(p, diag["probs"])
        paths["checkpoint3_probs"] = str(p)
    if "vote_scores" in diag:
        p = out_dir / "ckpt4_coarse_vote.json"
        p.write_text(json.dumps(diag["vote_scores"], indent=2), encoding="utf-8")
        paths["checkpoint4_coarse"] = str(p)
    if diag.get("gunshot_score") is not None:
        p = out_dir / "ckpt5_gunshot_score.npy"
        np.save(p, np.array([diag["gunshot_score"]], dtype=np.float32))
        paths["checkpoint5_booster"] = str(p)
    return paths


def print_human(report: Dict[str, Any]) -> None:
    print("=== Input ===")
    for k, v in report["input"].items():
        print(f"  {k}: {v}")
    print("=== Preprocess ===")
    for k, v in report["preprocess"].items():
        print(f"  {k}: {v}")
    print("=== YAMNet ===")
    print(f"  softmax_sum: {report['yamnet']['softmax_sum']}")
    for item in report["yamnet"]["top5"] or []:
        print(f"  top: {item['name']}  p={item['prob']:.4f}")
    print("=== 3-Class ===")
    vs = report["coarse"]["vote_scores"] or {}
    print(f"  Ambient score: {vs.get('ambient')}")
    print(f"  Speech score:  {vs.get('speech')}")
    print(f"  Danger score:  {vs.get('danger')}")
    print(f"  selected (pre-hysteresis): {report['coarse']['selected_pre_hysteresis']}")
    print("=== Booster ===")
    for k, v in report["booster"].items():
        print(f"  {k}: {v}")
    print("=== Final ===")
    pre = report["final"]["pre_hysteresis"]
    print(f"  pre-hysteresis: {pre['coarse_class']} conf={pre['confidence']:.4f} meets={pre['meets_threshold']}")
    print(f"  display: {pre['yamnet_display_name']}")
    post = report["final"]["post_hysteresis"]
    if post:
        print(
            f"  post-hysteresis: {post['confirmed_coarse']} "
            f"conf={post['confirmed_confidence']:.4f} ui={post['ui_coarse']}"
        )


def main() -> int:
    ap = argparse.ArgumentParser(description="Sound classifier reference harness")
    ap.add_argument("wav", nargs="?", help="Input WAV path")
    ap.add_argument(
        "--model-dir",
        type=Path,
        default=DEFAULT_MODEL_DIR,
        help="Directory containing yamnet.onnx, yamnet.data, gunshot_booster.onnx, yamnet_class_map.csv",
    )
    ap.add_argument("--threshold", type=float, default=0.25, help="Realtime PredictSoundType threshold (default 0.25)")
    ap.add_argument("--json", type=Path, default=None, help="Write machine-readable JSON")
    ap.add_argument("--save-npy", action="store_true", help="Save golden npy checkpoints under .out/")
    ap.add_argument("--stream", action="store_true", help="Simulate 250ms streaming hysteresis over the file")
    ap.add_argument("--silence", action="store_true", help="Generate synthetic silence WAV and run")
    ap.add_argument("--deterministic-check", action="store_true", help="Run twice and compare tensor checksum")
    args = ap.parse_args()

    if args.silence:
        wav_path = ROOT / ".out" / "silence_1s_16k.wav"
        wav_path.parent.mkdir(parents=True, exist_ok=True)
        write_silence_wav(wav_path, duration_s=1.2, sample_rate=SAMPLE_RATE)
    elif args.wav:
        wav_path = Path(args.wav)
    else:
        ap.error("Provide a WAV path or --silence")
        return 2

    if not wav_path.is_file():
        print(f"error: file not found: {wav_path}", file=sys.stderr)
        return 1

    print(f"[ref] model_dir={args.model_dir}")
    clf = ReferenceClassifier(args.model_dir)
    info = clf.model_info()
    print(f"[ref] yamnet in={info['yamnet']['input']} out={info['yamnet']['output']} external_data={info['yamnet']['external_data']}")
    print(f"[ref] booster={info['booster']}")

    mono, sr, ch = load_wav_as_capture_mono(wav_path)
    print(f"[ref] loaded mono={mono.size} sr={sr} ch={ch}")

    if args.stream:
        frames = clf.classify_stream(mono, sr, step_ms=250, confidence_threshold=args.threshold)
        # Use last successful frame diagnostics via one-shot for checkpoints
        clf.reset_state()
        result, trace, diag = clf.classify_mono_capture_once(
            mono, sr, confidence_threshold=args.threshold, apply_hysteresis=True
        )
        report = build_report(wav_path, clf, mono, sr, ch, result, trace, diag, args.threshold)
        report["stream_frames"] = len(frames)
        report["stream_last"] = frames[-1]["final"] if frames else None
    else:
        result, trace, diag = clf.classify_mono_capture_once(
            mono, sr, confidence_threshold=args.threshold, apply_hysteresis=True
        )
        # Update last label text like PredictSoundType UI formatting
        if trace is not None:
            from mapper import translate_to_korean

            translated = translate_to_korean(trace.ui_display)
            if trace.ui_confidence < args.threshold:
                clf._last_predict_result = (
                    f"{translated} | {trace.ui_coarse} | {trace.ui_confidence * 100.0:.1f}% (저신뢰)"
                )
            else:
                clf._last_predict_result = (
                    f"{translated} | {trace.ui_coarse} | {trace.ui_confidence * 100.0:.1f}%"
                )
        report = build_report(wav_path, clf, mono, sr, ch, result, trace, diag, args.threshold)

    if args.deterministic_check:
        r2, _, d2 = clf.classify_mono_capture_once(
            mono, sr, confidence_threshold=args.threshold, apply_hysteresis=False
        )
        same = diag["tensor_stats"]["sha256_16"] == d2["tensor_stats"]["sha256_16"]
        same = same and np.allclose(diag["probs"], d2["probs"])
        report["deterministic"] = bool(same)
        print(f"[ref] deterministic={same}")

    print_human(report)

    if args.save_npy:
        ckpt_dir = ROOT / ".out" / "checkpoints"
        paths = save_checkpoints(ckpt_dir, diag)
        report["checkpoints"] = paths
        print("=== Checkpoints ===")
        for k, v in paths.items():
            print(f"  {k}: {v}")

    json_path = args.json or (ROOT / ".out" / "reference_output.json")
    json_path = Path(json_path)
    json_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps(_to_jsonable(report), indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"[ref] wrote {json_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
