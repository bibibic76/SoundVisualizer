"""Feed the same app-window FIR output to two compiled Kotlin frontend jars.

The comparator checks every log-mel float bit before any YAMNet inference.
Source WAVs are read in place. No model outputs or fixtures are regenerated.
"""
import argparse
import json
import struct
import subprocess
from pathlib import Path

import numpy as np

from preprocess import capture_samples_for_one_yamnet_window, resample_mono_float_to_16k_custom
from wav_io import load_wav_as_capture_mono


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--classpath", required=True, help="Parity harness jar and Kotlin stdlib; exclude frontend jars")
    parser.add_argument("--baseline-jar", type=Path, required=True)
    parser.add_argument("--candidate-jar", type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    rows = manifest["rows"] if isinstance(manifest, dict) else manifest
    paths = [Path(row["path"]) for row in rows]
    for path in paths:
        if not path.is_file():
            raise FileNotFoundError(path)
    process = subprocess.Popen([
        "java", "-cp", args.classpath, "FrontendParityKt",
        str(args.baseline_jar), str(args.candidate_jar),
    ], stdin=subprocess.PIPE)
    try:
        for index, path in enumerate(paths, 1):
            mono, rate, _ = load_wav_as_capture_mono(path)
            need = capture_samples_for_one_yamnet_window(rate)
            if mono.size < need:
                mono = np.concatenate((mono, np.zeros(rate, dtype=np.float32)))
            step = round(rate * 0.250)
            for start in range(0, mono.size, step):
                end = min(start + step, mono.size)
                if end < need:
                    continue
                window = resample_mono_float_to_16k_custom(mono[end - need:end], rate)
                process.stdin.write(struct.pack("<I", window.size))
                process.stdin.write(window.astype("<f4").tobytes())
            if index % 100 == 0:
                print(f"parity: {index}/{len(paths)} WAV", flush=True)
        process.stdin.close()
        if process.wait() != 0:
            raise RuntimeError("Kotlin frontend parity failed")
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait()


if __name__ == "__main__":
    main()
