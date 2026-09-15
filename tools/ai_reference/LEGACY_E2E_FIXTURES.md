# Legacy realtime E2E fixtures

`e2e_gunshot_*` and `e2e_alarm_*` were added in commit `c18b890` as synthetic
44.1kHz stereo inputs. The historic generator placed 16k samples at rounded
44.1k positions and nearest-filled the gaps so the former linear resampler
could round-trip bit-exactly.

They are retained for their existing tests, but are not provenance-backed
semantic source audio and must not be regenerated from their own PCM under the
FIR preprocessing contract.

The historic generator named these missing source paths:

- `data/preprocessed/Gunshot, gunfire_short-explosion-1694.wav`
- `data/preprocessed/Alarm_classic-alarm-995.wav`

Neither file, an acquisition procedure, a checksum, nor license/source metadata
exists in the current repository or reachable Git history. Before regenerating
semantic gunshot/alarm fixtures, add the original source files with that
provenance and run `export_realtime_e2e_golden.py`. The generator refuses to
overwrite gunshot/alarm outputs while their respective source is absent.
