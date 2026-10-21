"""Alternate fresh JVMs to compare frontend jars without timing assertions in CI."""
import argparse
import json
import statistics
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-classpath", required=True)
    parser.add_argument("--candidate-classpath", required=True)
    parser.add_argument("--rounds", type=int, default=5)
    parser.add_argument("--iterations", type=int, default=1000)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.rounds < 1 or args.iterations < 1:
        parser.error("rounds and iterations must be positive")
    if args.output is not None and args.output.exists():
        parser.error("output already exists")
    results = {"baseline": [], "candidate": []}
    checksums = set()
    for round_index in range(args.rounds):
        arms = ["baseline", "candidate"] if round_index % 2 == 0 else ["candidate", "baseline"]
        for arm in arms:
            output = subprocess.check_output([
                "java", "-cp", getattr(args, arm + "_classpath"),
                "com.example.soundvisualizer.ai.FrontendBenchmarkKt", str(args.iterations),
            ], text=True)
            measurements = {}
            for line in output.splitlines():
                if "medianMs=" in line:
                    name, *fields = line.split()
                    measurements[name] = {k: float(v) for k, v in (field.split("=") for field in fields)}
                elif line.startswith("checksum="):
                    checksums.add(line.split()[0])
            if set(measurements) != {"resample48k", "logmel", "frontend48k"}:
                raise RuntimeError(f"Missing benchmark output: {output}")
            results[arm].append(measurements)
    if len(checksums) != 1:
        raise RuntimeError(f"Benchmark outputs differ: {checksums}")
    report = {"runs": results, "median_of_runs": {}}
    for arm, runs in results.items():
        report["median_of_runs"][arm] = {
            name: {key: statistics.median(run[name][key] for run in runs) for key in runs[0][name]}
            for name in runs[0]
        }
    if args.output is not None:
        with args.output.open("x") as output:
            json.dump(report, output, indent=2)
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
