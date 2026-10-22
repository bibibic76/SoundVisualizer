"""Reproduce the pinned MIT model asset without executing remote code."""
import argparse
import hashlib
from pathlib import Path
from urllib.request import urlopen

SHA = "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3"
URL = "https://raw.githubusercontent.com/snakers4/silero-vad/5cd7945676eb32225748052e2e6a0580e4686a08/src/silero_vad/data/silero_vad.onnx"

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--output", type=Path, default=Path("app/src/main/assets/ai/silero_vad.onnx"))
    args = parser.parse_args()
    raw = args.source.read_bytes() if args.source else urlopen(URL, timeout=60).read()
    if hashlib.sha256(raw).hexdigest() != SHA:
        raise ValueError("Model hash mismatch")
    if args.output.exists():
        if args.output.read_bytes() != raw:
            raise ValueError("Refusing to overwrite a different asset")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("xb") as handle:
            handle.write(raw)
    print(f"Verified {len(raw)} bytes, SHA256 {SHA}")

if __name__ == "__main__":
    main()
