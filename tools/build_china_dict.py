#!/usr/bin/env python3
"""A Yomitan dictionary of China's province- and prefecture-level divisions, in the style of 全市区町村辞典:
a locator map per place, its province, population, area and a Chinese Wikipedia link.

  python3 tools/build_china_dict.py WORK_DIR OUT.zip

Data: Wikidata (divisions by type, their 行政区划代码, names, 2020 census population, area, zh Wikipedia article).
Maps: drawn here, all alike, from the boundary data at geo.datav.aliyun.com (keyed by the same codes): the province
with its prefecture-level divisions, the place in red, neighbouring provinces grey; provinces on the map of China.
"""
import json, os, re, ssl, sys, time, urllib.parse, urllib.request, zipfile
import certifi
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import Polygon
from pypinyin import pinyin, Style

CTX = ssl.create_default_context(cafile=certifi.where())
UA = 'KotobaCityDict/0.1 (https://github.com/Leow210/kotoba)'
PREF_TYPES = {'Q748149': '地级市', 'Q250811': '副省级市', 'Q788104': '自治州', 'Q1045608': '地区', 'Q288653': '盟'}


def http(url, data=None, accept='application/json'):
    for attempt in range(5):
        try:
            req = urllib.request.Request(url, data=data, headers={'User-Agent': UA, 'Accept': accept})
            with urllib.request.urlopen(req, timeout=60, context=CTX) as r:
                return r.read()
        except Exception as e:
            print('retry', attempt, url[:80], e, file=sys.stderr)
            time.sleep(4 * (attempt + 1))
    raise SystemExit('failed: ' + url)


def sparql(q):
    body = urllib.parse.urlencode({'query': q, 'format': 'json'}).encode()
    return json.loads(http('https://query.wikidata.org/sparql', body, 'application/sparql-results+json'))['results']['bindings']


def places():
    prov_types = [b['t']['value'].split('/')[-1] for b in sparql('SELECT ?t WHERE { ?t wdt:P279 wd:Q119608727 }')]
    types = {}
    for group in (list(PREF_TYPES), prov_types):
        vals = ' '.join('wd:' + t for t in group)
        for b in sparql(f'SELECT ?item ?type ?typeZh WHERE {{ VALUES ?type {{{vals}}} ?item wdt:P31 ?type . '
                        f'FILTER NOT EXISTS {{ ?item wdt:P576 ?x }} OPTIONAL {{ ?type rdfs:label ?typeZh FILTER(LANG(?typeZh)="zh-cn") }} }}'):
            k = b['item']['value'].split('/')[-1]
            t = b['type']['value'].split('/')[-1]
            # A sub-provincial city is also a prefecture-level city: keep the more specific label.
            if k not in types or t == 'Q250811':
                types[k] = (t, PREF_TYPES.get(t) or b.get('typeZh', {}).get('value', ''), 'pref' if t in PREF_TYPES else 'prov')
    data = {}
    ids = list(types)
    for i in range(0, len(ids), 100):
        chunk = ' '.join('wd:' + x for x in ids[i:i + 100])
        q = f'''SELECT ?item ?code ?zh ?zht ?pop ?popDate ?area ?article ?parent WHERE {{ VALUES ?item {{{chunk}}}
          OPTIONAL {{ ?item wdt:P442 ?code }} OPTIONAL {{ ?item rdfs:label ?zh FILTER(LANG(?zh)="zh-cn") }}
          OPTIONAL {{ ?item rdfs:label ?zht FILTER(LANG(?zht)="zh-tw") }}
          OPTIONAL {{ ?item p:P1082 ?ps . ?ps ps:P1082 ?pop . OPTIONAL {{ ?ps pq:P585 ?popDate }} }}
          OPTIONAL {{ ?item p:P2046/psn:P2046/wikibase:quantityAmount ?area }}
          OPTIONAL {{ ?item wdt:P131 ?parent }}
          OPTIONAL {{ ?article schema:about ?item ; schema:isPartOf <https://zh.wikipedia.org/> }} }}'''
        for b in sparql(q):
            k = b['item']['value'].split('/')[-1]
            d = data.setdefault(k, {'id': k, 'type': types[k][1], 'level': types[k][2], 'pops': {}})
            for f in ('code', 'zh', 'zht', 'article', 'area'):
                if f in b and f not in d:
                    d[f] = b[f]['value']
            if 'parent' in b:
                d.setdefault('parents', set()).add(b['parent']['value'].split('/')[-1])
            if 'pop' in b:
                d['pops'][b.get('popDate', {}).get('value', '')[:10]] = float(b['pop']['value'])
        print('wikidata', min(i + 100, len(ids)), '/', len(ids), flush=True)
        time.sleep(1)
    return data


def boundary(code, cache):
    path = os.path.join(cache, f'{code}.json')
    if not os.path.exists(path):
        # One try: a code DataV doesn't have is a missing map, not a reason to stop the build.
        try:
            req = urllib.request.Request(f'https://geo.datav.aliyun.com/areas_v3/bound/{code}.json', headers={'User-Agent': UA})
            with urllib.request.urlopen(req, timeout=60, context=CTX) as r:
                open(path, 'wb').write(r.read())
        except Exception as e:
            raise KeyError(f'no boundary {code}: {e}')
        time.sleep(0.2)
    return json.load(open(path))


def rings(geom):
    if geom['type'] == 'Polygon':
        return [geom['coordinates'][0]]
    return [p[0] for p in geom['coordinates']]


def ring_area(r):
    return abs(sum(x1 * y2 - x2 * y1 for (x1, y1), (x2, y2) in zip(r, r[1:] + r[:1]))) / 2


def framing(rings_, target, share):
    """The rings to frame a view on: the land that matters, not far-flung small islands (Hainan's South China Sea
    islands made 三亚 a speck), yet always the place itself (三沙市 is those islands)."""
    big = max((ring_area(r) for r in rings_), default=0)
    keep = [r for r in rings_ if ring_area(r) >= big * share]
    xs = [x for r in keep for x, _ in r]; ys = [y for r in keep for _, y in r]
    tx = [x for r in target for x, _ in r]; ty = [y for r in target for _, y in r]
    if keep and tx and (min(tx) < min(xs) or max(tx) > max(xs) or min(ty) < min(ys) or max(ty) > max(ys)):
        keep = keep + target
    return keep or rings_


def nearby(rings_, target):
    """For a prefecture: the province's land near the place (within a few degrees, or three times the place's size),
    so a city on Hainan Island isn't shown at the scale of the South China Sea."""
    tx = [x for r in target for x, _ in r]; ty = [y for r in target for _, y in r]
    cx, cy = (min(tx) + max(tx)) / 2, (min(ty) + max(ty)) / 2
    reach = max(3.0, 3 * max(max(tx) - min(tx), max(ty) - min(ty)))
    keep = [r for r in rings_ if any(abs(x - cx) <= reach and abs(y - cy) <= reach for x, y in r[::max(1, len(r) // 50)])]
    return (keep or rings_) + target


def draw(path, target, context, focus, lat0):
    """target: rings in red; context: [(rings, color)] under it; focus: rings to frame the view on."""
    k = __import__('math').cos(__import__('math').radians(lat0))
    xs = [x * k for r in focus for x, _ in r]
    ys = [y for r in focus for _, y in r]
    x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
    pad = max(x1 - x0, y1 - y0) * 0.08
    fig = plt.figure(figsize=(3.2, 3.2), dpi=150)
    ax = fig.add_axes([0, 0, 1, 1]); ax.set_axis_off()
    for rs, fill, edge, lw in context + [(target, '#e0301e', '#8a1a10', 0.6)]:
        for r in rs:
            ax.add_patch(Polygon([(x * k, y) for x, y in r], closed=True, facecolor=fill, edgecolor=edge, linewidth=lw))
    w = max(x1 - x0, y1 - y0) / 2 + pad
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    ax.set_xlim(cx - w, cx + w); ax.set_ylim(cy - w, cy + w); ax.set_aspect('equal')
    fig.savefig(path, facecolor='white'); plt.close(fig)


ETHNIC = '|'.join(sorted(set('朝鲜 朝鮮 藏 彝 哈尼 傣 景颇 景頗 傈僳 白 苗 侗 布依 土家 羌 回 蒙古 哈萨克 哈薩克 柯尔克孜 柯爾克孜 壮 壯 黎 维吾尔 維吾爾 土 撒拉 东乡 東鄉 保安 纳西 納西 拉祜 佤 布朗 基诺 基諾 阿昌 德昂 独龙 獨龍 怒 普米 仡佬 水 毛南 仫佬 瑶 畲 京 裕固'.split()), key=len, reverse=True))


def short_names(name):
    out = []
    # 延边朝鲜族自治州 → 延边, 楚雄彝族自治州 → 楚雄: the place before its peoples' names (simplified and traditional).
    m = re.match(r'^(.+?)(?:(?:' + ETHNIC + r')族)+自治[州区區]$', name)
    if m:
        out.append(m.group(1))
    for suf in ('特别行政区', '特別行政區', '维吾尔自治区', '維吾爾自治區', '壮族自治区', '壯族自治區', '回族自治区', '回族自治區', '自治区', '自治區', '地区', '地區', '省', '市', '盟'):
        if name.endswith(suf) and len(name) - len(suf) >= 2:
            out.append(name[:-len(suf)])
            break
    return [n for n in out if n and n != name]


def reading(name):
    return ' '.join(s[0] for s in pinyin(name, style=Style.TONE, heteronym=False))


def main(work, out):
    os.makedirs(os.path.join(work, 'geo'), exist_ok=True)
    os.makedirs(os.path.join(work, 'img'), exist_ok=True)
    cache = os.path.join(work, 'wikidata.json')
    if os.path.exists(cache):
        data = json.load(open(cache))
        for d in data.values():
            d['parents'] = set(d.get('parents', []))
    else:
        data = places()
        json.dump({k: {**d, 'parents': sorted(d.get('parents', []))} for k, d in data.items()}, open(cache, 'w'), ensure_ascii=False)
    # Wikidata writes the codes several ways (11, 13 01, 130100, 130100000000): all as the 6-digit 行政区划代码.
    for d in data.values():
        digits = re.sub(r'\D', '', d.get('code') or '')
        d['code'] = (digits + '000000')[:6] if digits else ''
    # Entries with no code or no Chinese name are former divisions (吐鲁番地区, now 吐鲁番市): left out.
    data = {k: d for k, d in data.items() if d['code'] and d.get('zh')}
    by_code = {}
    for d in data.values():
        by_code.setdefault(d['code'], d)
    data = {d['id']: d for d in by_code.values()}
    print('places', len(data), 'with codes', len(by_code))
    china = boundary('100000_full', os.path.join(work, 'geo'))
    provinces = {str(f['properties']['adcode']): f for f in china['features'] if f['properties'].get('adcode')}
    grey = [(rings(f['geometry']), '#dcdcdc', '#9a9a9a', 0.3) for f in provinces.values()]
    terms, missing = [], []
    for d in sorted(data.values(), key=lambda d: d.get('code', '')):
        code = d.get('code', '')
        img = f'img/cn_{code or d["id"]}.png'
        path = os.path.join(work, img)
        try:
            if not re.fullmatch(r'\d{6}', code or ''):
                raise KeyError('no administrative code')
            if not os.path.exists(path):
                if d['level'] == 'prov':
                    f = provinces.get(code)
                    if not f: raise KeyError('no boundary')
                    whole = [r for ff in provinces.values() for r in rings(ff['geometry'])]
                    draw(path, rings(f['geometry']), grey, framing(whole, rings(f['geometry']), 0.005), 35)
                else:
                    pcode = code[:2] + '0000'
                    full = boundary(f'{pcode}_full', os.path.join(work, 'geo'))
                    subs = {str(ff['properties']['adcode']): ff for ff in full['features']}
                    me = subs.get(code)
                    if not me:
                        # A code Wikidata still has from before a change (海东地区 632100 → 海东市 630200): by name.
                        me = next((ff for ff in subs.values() if ff['properties'].get('name') in (d.get('zh'), d.get('zht'))), None)
                    if not me: raise KeyError('no boundary')
                    prov_rings = rings(provinces[pcode]['geometry'])
                    lat = sum(y for r in prov_rings for _, y in r) / sum(len(r) for r in prov_rings)
                    ctx = grey + [(rings(ff['geometry']), '#c7e7c4', '#6c9a6a', 0.4) for c2, ff in subs.items() if c2 != code]
                    draw(path, rings(me['geometry']), ctx, nearby(framing(prov_rings, rings(me['geometry']), 0.02), rings(me['geometry'])), lat)
        except Exception as e:
            missing.append((d.get('zh'), code, str(e)))
            img = None
        # Population: the census (2020) or the latest figure with a date.
        pop, when = None, ''
        if d['pops']:
            when = '2020-11-01' if '2020-11-01' in d['pops'] else max(d['pops'])
            pop = d['pops'][when]
        prov = next((by_code.get(p) for p in ()), None)
        parent = ''
        if d['level'] == 'pref':
            pp = by_code.get(code[:2] + '0000') if code else None
            parent = pp.get('zh', '') if pp else ''
        content = []
        if img:
            content.append({'tag': 'div', 'content': {'tag': 'img', 'path': img, 'alt': d.get('zh', ''), 'height': 12, 'sizeUnits': 'em',
                                                      'collapsed': False, 'collapsible': False, 'background': False}, 'style': {'marginBottom': '0.5em'}})
        content.append({'tag': 'div', 'content': ' · '.join(x for x in (parent, d['type']) if x)})
        if pop:
            year = when[:4] or ''
            label = '2020年第七次全国人口普查' if when.startswith('2020') else (f'{year}年' if year else '')
            content.append({'tag': 'div', 'content': f'人口 {int(pop):,}人' + (f'（{label}）' if label else '')})
        if d.get('area'):
            a = float(d['area']) / 1e6  # Wikidata's normalized amount is in square metres
            line = f'面积 {a:,.0f} km²' if a >= 100 else f'面积 {a:,.1f} km²'
            if pop and a > 0:
                dens = pop / a
                line += f' · 人口密度 {dens:,.1f}人/km²' if dens < 10 else f' · 人口密度 {dens:,.0f}人/km²'
            content.append({'tag': 'div', 'style': {'fontSize': '0.85em'}, 'content': line})
        if d.get('article'):
            content.append({'tag': 'div', 'content': {'tag': 'a', 'href': d['article'], 'content': '维基百科（中文）'}})
        gloss = [{'type': 'structured-content', 'content': content}]
        names = []
        for n in (d.get('zh'), d.get('zht')):
            if n and n not in names:
                names.append(n)
                names += [s for s in short_names(n) if s not in names]
        # Every name of a place (广州市, 广州, 廣州市) carries the full name's reading, so dictionary apps that fold
        # identical entries (Kotoba does) show one entry per place, whichever name was searched or hovered.
        full_reading = reading(d.get('zh') or names[0])
        for n in names:
            terms.append([n, full_reading, '', '', 0, gloss, 0, ''])
    print('terms', len(terms), 'maps missing', len(missing), missing[:10])
    index = {'title': '中国行政区划辞典（地级）', 'format': 3, 'revision': time.strftime('%Y-%m'), 'author': 'Kotoba (Wikidata, DataV)',
             'description': '中国的省级与地级行政区：位置图、所属省份、人口（2020年第七次全国人口普查）、面积、维基百科链接。'
                            '数据：Wikidata；边界：geo.datav.aliyun.com。'}
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as o:
        o.writestr('index.json', json.dumps(index, ensure_ascii=False))
        for i in range(0, len(terms), 2000):
            o.writestr(f'term_bank_{i // 2000 + 1}.json', json.dumps(terms[i:i + 2000], ensure_ascii=False))
        for n in sorted(os.listdir(os.path.join(work, 'img'))):
            o.write(os.path.join(work, 'img', n), 'img/' + n)
    print('wrote', out)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
