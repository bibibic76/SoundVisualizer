"""Summarize fixed-manifest diagnostic Kotlin output; no threshold fitting."""
import argparse
import csv
import json
from collections import Counter, defaultdict
from pathlib import Path

LABELS = ('ambient', 'speech', 'danger')


def metrics(rows):
    cm = [[0]*3 for _ in LABELS]
    for row in rows:
        cm[LABELS.index(row['expected'])][LABELS.index(row['majority'])] += 1
    f1, recall = [], []
    for i in range(3):
        support = sum(cm[i])
        predicted = sum(row[i] for row in cm)
        f1.append(2*cm[i][i]/(support+predicted) if support+predicted else 0)
        recall.append(cm[i][i]/support if support else 0)
    n = sum(map(sum, cm))
    return dict(files=n, confusion=cm, macro_f1=sum(f1)/3,
                accuracy=sum(cm[i][i] for i in range(3))/n if n else 0,
                recall=dict(zip(LABELS, recall)))


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--manifest', type=Path, required=True)
    ap.add_argument('--frames', type=Path, required=True)
    ap.add_argument('--output', type=Path, required=True)
    args = ap.parse_args()
    data = json.loads(args.manifest.read_text())
    manifest = data['rows'] if isinstance(data, dict) else data
    counts = defaultdict(Counter)
    changes = defaultdict(list)
    production_reference_diff = 0
    previous = None
    baseline = None
    with args.frames.open() as src:
        for row in csv.DictReader(src, delimiter='\t'):
            idx, variant = int(row['file']), row['variant']
            counts[idx, variant][row['ui']] += 1
            key = (idx, row['time'])
            if variant == 'baseline':
                baseline, previous = row, key
            else:
                assert previous == key
                if any(row[k] != baseline[k] for k in ('ui', 'post', 'promoted', 'display')):
                    changes[variant].append(dict(row, baseline_ui=baseline['ui'], baseline_post=baseline['post']))
            if variant == 'no_firearm_veto_cue_floor':
                production_reference_diff += row['ui'] != row['python_ui']
    variants = sorted({v for _, v in counts})
    reports = {}
    all_rows = {}
    for variant in variants:
        rows = []
        for idx, item in enumerate(manifest):
            c = counts[idx, variant]
            assert sum(c.values()) > 0, (idx, variant)
            winner = max(('danger', 'speech', 'ambient'), key=lambda x: c[x])
            rows.append(dict(item, majority=winner, danger_seen=c['danger'] > 0, frames=sum(c.values()), ui_counts=dict(c)))
        primary = [r for r in rows if r['policy'] == 'primary' and r.get('expected') in LABELS]
        any_danger = {}
        for label in LABELS:
            group = [r for r in primary if r['expected'] == label]
            any_danger[label] = [sum(r['danger_seen'] for r in group), len(group)]
        legacy = [r for r in rows if r['policy'] == 'legacy_firearm']
        reports[variant] = dict(primary=metrics(primary), representative=metrics([r for r in primary if r['dataset']=='representative']),
            any_danger=any_danger, legacy_firearm_any=[sum(r['danger_seen'] for r in legacy),len(legacy)],
            changed_decision_frames=len(changes[variant]),
            changed_ui_frames=sum(r['ui'] != r['baseline_ui'] for r in changes[variant]))
        all_rows[variant] = rows
    for variant in variants:
        reports[variant]['changed_majority'] = [dict(path=a['path'], expected=a.get('expected'), policy=a['policy'], before=b['majority'], after=a['majority'])
            for a,b in zip(all_rows[variant], all_rows['baseline']) if a['majority'] != b['majority']]
    result = dict(labels=LABELS, production_reference_ui_difference_frames=production_reference_diff,
                  summary=reports, changes=changes, rows=all_rows)
    with args.output.open('x') as out:
        json.dump(result, out, ensure_ascii=False, indent=2)
    print(json.dumps(dict(production_reference_ui_difference_frames=production_reference_diff,
                          summary=reports), ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
