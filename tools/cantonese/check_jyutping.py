#!/usr/bin/env python3
"""Compares hand-written jyutping in a Cantonese content file with PyCantonese, character by character, and lists the
disagreements to review (particle tones and colloquial readings are often right where PyCantonese differs).
Run with a Python that has pycantonese:  ~/Library/Caches/Kotoba/yue-venv/bin/python tools/cantonese/check_jyutping.py FILE"""
import json, re, sys
import pycantonese

def lines(d):
    for s in d.get('sections', []):
        for g in s['groups']:
            for it in g['items']:
                yield it

d = json.load(open(sys.argv[1]))
bad = 0
for it in lines(d):
    han = [c for c in it['text'] if re.match(r'[㐀-鿿\U00020000-\U0002ffff]', c)]
    syl = re.findall(r'[a-z]+[1-6]', it['roman'])
    ref = []
    for w, j in pycantonese.characters_to_jyutping(it['text']):
        if j is None:
            continue
        ref += re.findall(r'[a-z]+[1-6]', j)
    if len(syl) != len(han):
        print(f"COUNT {it['text']}  {len(han)} chars vs {len(syl)} syllables: {it['roman']}"); bad += 1; continue
    diffs = [f'{c}:{a}≠{b}' for c, a, b in zip(han, syl, ref) if a != b] if len(ref) == len(han) else ['(pycantonese length differs)']
    if diffs:
        print(it['text'], ' '.join(diffs)); bad += 1
print(bad, 'lines to review')
