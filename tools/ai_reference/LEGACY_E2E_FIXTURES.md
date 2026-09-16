# Realtime E2E fixture sources

`e2e_gunshot_*` and `e2e_alarm_*` were added in commit `c18b890` as synthetic
44.1kHz stereo inputs. The historic generator placed 16k samples at rounded
44.1k positions and nearest-filled the gaps so the former linear resampler
could round-trip bit-exactly.

Those legacy binaries were not provenance-backed semantic source audio and
could not be regenerated from their own PCM under the FIR preprocessing
contract. They have now been replaced by derived fixtures generated from the
checksum-locked sources documented below.

The historic generator named these source paths:

- `data/preprocessed/Gunshot, gunfire_short-explosion-1694.wav`
- `data/preprocessed/Alarm_classic-alarm-995.wav`

The source WAVs are Mixkit sound effects and are not committed because the
license permits using them in projects but does not make this repository a
stock-audio redistribution channel. Their catalog pages, license, recovered
training-data filenames, and exact checksums are recorded in
`realtime_e2e_sources.json`.

The FIR semantic fixture uses Mixkit asset 2779 (`8-bit explosion gun`) for the
gunshot case. The historic asset 1694 no longer exercises the Booster adoption
path after FIR preprocessing, while asset 2779 preserves the intended
`ambient -> Booster accepted -> danger` regression contract. The alarm case now
expects the real alarm's danger result instead of preserving the legacy
synthetic fixture's misleading ambient label. Its current `Gunshot, gunfire`
display is retained as diagnostic metadata but is deliberately not asserted:
that relabeling is a known Booster false positive, not the alarm fixture's
semantic contract. It is tracked in GitHub issue #116. Booster thresholds and
mapping rules are not changed by fixture generation.

To regenerate the derived semantic fixtures from an authorized local copy:

```bash
python tools/ai_reference/export_realtime_e2e_golden.py \
  --source-dir /path/to/gunshot_booster_training_data/data
```

The generator validates every WAV against the committed SHA-256 before loading
the models or writing output. Raw inputs under `data/preprocessed/` are ignored
by Git; only the derived float fixtures and provenance metadata are committed.
