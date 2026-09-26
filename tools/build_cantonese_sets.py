#!/usr/bin/env python3
"""Listening sets from the hand-written Cantonese content in tools/cantonese/ (particles.json → yue-particles,
mandarin.json → yue-mandarin): characters, jyutping, the Mandarin translation (shown, and voiced by tts_kokoro.py),
English kept for reference, and a note per group. Cantonese audio comes from tts_minimax.py (listed in tts.json).

  python3 tools/build_cantonese_sets.py
"""
import json
from pathlib import Path

DATA = Path.home() / 'Library/Application Support/Kotoba/listening'
SRC = Path(__file__).resolve().parent / 'cantonese'
SETS = {'particles.json': 'yue-particles', 'mandarin.json': 'yue-mandarin'}


def build(src, set_id):
    d = json.loads((SRC / src).read_text())
    out = DATA / set_id
    out.mkdir(parents=True, exist_ok=True)
    old = {}
    if (out / 'set.json').exists():   # keep audio already made
        for g in json.loads((out / 'set.json').read_text())['groups']:
            for it in g['items']:
                old[it['id']] = it
    groups, tts = [], []
    for s in d['sections']:
        for g in s['groups']:
            items = []
            for n, it in enumerate(g['items']):
                iid = f"{g['id']}-{n + 1}"
                prev = old.get(iid, {}) if old.get(iid, {}).get('text') == it['text'] else {}
                item = {'id': iid, 'title': g['name'], 'titleEn': g.get('nameEn', ''), 'text': it['text'], 'roman': it['roman'], 'words': [],
                        'translation': it.get('mandarin') or it.get('translation', ''), 'translationEn': it.get('translation', ''),
                        'audio': prev.get('audio', ''), 'dur': prev.get('dur', 0), 'l1audio': prev.get('l1audio', ''),
                        'note': g.get('note', '') if n == 0 else ''}
                if not item['audio']:
                    tts.append({'group': g['id'], 'id': iid, 'text': it['text'], 'roman': it['roman'], 'audio': f"audio/{g['id']}/{n + 1}.m4a"})
                items.append(item)
            groups.append({'id': g['id'], 'name': g['name'], 'nameEn': g.get('nameEn', ''), 'icon': '', 'section': s['name'],
                           'sectionEn': s.get('nameEn', ''), 'order': len(groups), 'note': g.get('note', ''), 'items': items})
    st = {'id': set_id, 'title': d['title'], 'subtitle': d['subtitle'], 'lang': 'yue', 'translationLang': 'zh', 'l1': 'zh',
          'groupLabel': 'Topics', 'roman': 'jyutping', 'groups': groups}
    (out / 'set.json').write_text(json.dumps(st, ensure_ascii=False, indent=0))
    (out / 'tts.json').write_text(json.dumps(tts, ensure_ascii=False, indent=0))
    print(set_id, sum(len(g['items']) for g in groups), 'lines,', len(tts), 'to voice in Cantonese')


if __name__ == '__main__':
    for src, sid in SETS.items():
        build(src, sid)
