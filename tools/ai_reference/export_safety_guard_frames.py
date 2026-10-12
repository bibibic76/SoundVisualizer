"""Cache fixed-cadence YAMNet probabilities for read-only Kotlin guard ablation.

Input JSON is a list of {path, ...metadata} or an existing replay report with rows.
No audio is copied or changed. Not a simulation of capture timing or silence gating.
"""
import argparse
import json
from pathlib import Path

import numpy as np

from classifier import ReferenceClassifier
from compare_logmel_frontends import qualcomm_source_log_mel
from preprocess import capture_samples_for_one_yamnet_window
from wav_io import load_wav_as_capture_mono


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--manifest', type=Path, required=True)
    ap.add_argument('--model-dir', type=Path, required=True)
    ap.add_argument('--output', type=Path, required=True)
    args = ap.parse_args()
    data = json.loads(args.manifest.read_text())
    manifest = data['rows'] if isinstance(data, dict) else data
    classifier = ReferenceClassifier(args.model_dir, load_booster=False, log_mel_provider=qualcomm_source_log_mel)
    with args.output.open('x') as out:
        for index, item in enumerate(manifest):
            mono, rate, _ = load_wav_as_capture_mono(Path(item['path']))
            if mono.size < capture_samples_for_one_yamnet_window(rate):
                mono = np.concatenate((mono, np.zeros(rate, dtype=np.float32)))
            classifier.reset_state()
            step = max(1, int(rate * .250))
            frames = 0
            for pos in range(0, mono.size, step):
                classifier.ingest_mono(mono[pos:pos+step], rate)
                _, trace, diag = classifier.predict_sound_type(rate)
                if trace is None:
                    continue
                values = ','.join(format(float(p), '.9g') for p in diag['probs'])
                out.write(f'{index}\t{min(pos+step, mono.size)/rate}\t{trace.ui_coarse}\t{values}\n')
                frames += 1
            if not frames:
                raise ValueError(f'No inference frames: {item["path"]}')
            if (index+1) % 100 == 0:
                print(f'{index+1}/{len(manifest)}', flush=True)


if __name__ == '__main__':
    main()
