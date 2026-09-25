#!/usr/bin/env python3
"""Puts each character of an existing Genshin listening set under its region heading again (after the region names
in build_genshin_voice.py change), without downloading anything: python3 tools/fix_genshin_regions.py [set]"""
import json, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_genshin_voice as v  # noqa: E402

out = v.DATA / (sys.argv[1] if len(sys.argv) > 1 else 'genshin-zh')
avatars = v.api('chs/avatar')['data']['items']
s = json.loads((out / 'set.json').read_text())
for g in s['groups']:
    region = v.region_of(avatars.get(g['id'], {}).get('region'))
    g['section'], g['sectionEn'] = v.REGIONS[region]
    g['order'] = list(v.REGIONS).index(region)
s['groups'].sort(key=lambda g: (g.get('order', 0), g['id']))
(out / 'set.json').write_text(json.dumps(s, ensure_ascii=False, indent=0))
print('regrouped', len(s['groups']))
