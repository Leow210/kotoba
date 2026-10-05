#!/usr/bin/env python3
"""Builds the preset word lists in android/assets/preset-*.txt (HSK 3.0 levels, TOPIK I/II).

Sources (download first, pass the paths):
  HSK:   https://raw.githubusercontent.com/drkameleon/complete-hsk-vocabulary/main/complete.json   (MIT; meanings from CC-CEDICT, CC BY-SA)
  TOPIK: https://raw.githubusercontent.com/julienshim/combined_korean_vocabulary_list/HEAD/results.tsv  (levels)
         https://raw.githubusercontent.com/KamWithK/KrDictionaries/master/dictionaries/krdict_korean_english.json  (English; krdict, CC BY-SA 2.0 KR)
usage: build_preset_lists.py complete.json results.tsv krdict_korean_english.json
"""
import csv, json, re, sys, os
hsk_path, tsv_path, kr_path = sys.argv[1:4]
OUT = os.path.join(os.path.dirname(__file__), '..', 'android', 'assets')

def write(name, header, rows):
    with open(os.path.join(OUT, f'preset-{name}.txt'), 'w', encoding='utf-8') as f:
        f.write(header + '\n')
        for r in rows: f.write('\t'.join(r) + '\n')
    print(name, len(rows))

# ---- HSK 3.0 (2021 standard): level N = words first introduced at N; 7-9 is one band ----
hsk = json.load(open(hsk_path, encoding='utf-8'))
for lv in range(1, 8):
    rows = []
    for e in hsk:
        if f'new-{lv}' not in e['level']: continue
        f0 = e['forms'][0]
        mean = '; '.join(f0['meanings'][:3])
        rows.append((e['simplified'], f0['transcriptions']['pinyin'].replace(' ', ''), mean, e.get('frequency', 10**9)))
    rows.sort(key=lambda r: r[3])
    write(f'hsk3-{lv}', f'# HSK 3.0 {"7-9" if lv == 7 else "level " + str(lv)} (words new at this level). drkameleon/complete-hsk-vocabulary, MIT; meanings CC-CEDICT CC BY-SA',
          [r[:3] for r in rows])

# ---- TOPIK ----
def gloss(e):
    en = e['entries']
    if en and en[0].strip() and not en[0].strip().isdigit(): return re.sub(r'\s+', ' ', en[0]).strip()
    out = []  # numbered senses: "1", ".\r\n\t household", korean def, english def
    for x in en:
        m = re.match(r'^\.\s*(.+)$', x.strip(), re.S)
        if m:
            g = re.sub(r'\s+', ' ', m.group(1)).strip()
            if g and g not in out: out.append(g)
    return '; '.join(out[:3])
kr = {}
for e in json.load(open(kr_path, encoding='utf-8')):
    kr.setdefault((e['word'], e['hanja'].strip()), e)
    kr.setdefault((e['word'], 'h' + e['homonym'].strip()), e)
    kr.setdefault((e['word'], ''), e)
levels = {'초급': ('topik1', 'TOPIK I (초급, beginner)'), '중급': ('topik2', 'TOPIK II (중급, intermediate)')}
out = {k: [] for k in levels}
seen = set()
for r in csv.DictReader(open(tsv_path, encoding='utf-8'), delimiter='\t'):
    lv = r['topik_level']
    if lv not in levels: continue
    raw = r['word'].strip()
    m = re.match(r'^(.*?)(\d+)$', raw)
    word, hom = (m.group(1), str(int(m.group(2)))) if m else (raw, '')
    word = word.strip('-')
    if not word or word in seen: continue
    seen.add(word)
    hj = r['hanja'].strip()
    e = (hj and kr.get((word, hj))) or kr.get((word, 'h' + hom)) or kr.get((word, ''))
    hanja = hj or (e['hanja'] if e else '')
    out[lv].append((word, f'〔{hanja}〕' if hanja else '', gloss(e) if e else ''))
for lv, (name, title) in levels.items():
    write(name, f'# {title}. Levels: TOPIK institute list via julienshim/combined_korean_vocabulary_list; English: krdict (CC BY-SA 2.0 KR). The institute publishes only these two tiers',
          out[lv])
