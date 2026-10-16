#!/usr/bin/env python3
"""A Yomitan dictionary of South Korea's 시·군·구 and its 17 광역 units, in the style of 중국행정구역/全市区町村辞典:
a locator map per place, its province, population, area and a Korean Wikipedia link.

  python3 tools/build_korea_dict.py WORK_DIR OUT.zip

Data: Wikidata (names, population, area, ko Wikipedia article); boundaries: southkorea/southkorea-maps (KOSTAT 2018),
whose five-digit codes give each 구/군/시 its province. Cities made of 일반구 (수원시 → 장안구…) are drawn as the union of
their districts and get an entry of their own beside the districts.
"""
import json, os, re, sys, time, urllib.parse, urllib.request, zipfile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_china_dict import CTX, UA, rings, framing, nearby, draw, sparql, http  # noqa: E402

RAW = 'https://raw.githubusercontent.com/southkorea/southkorea-maps/master/kostat/2018/json/'
PROVINCES = {'11': '서울특별시', '21': '부산광역시', '22': '대구광역시', '23': '인천광역시', '24': '광주광역시', '25': '대전광역시',
             '26': '울산광역시', '29': '세종특별자치시', '31': '경기도', '32': '강원특별자치도', '33': '충청북도', '34': '충청남도',
             '35': '전북특별자치도', '36': '전라남도', '37': '경상북도', '38': '경상남도', '39': '제주특별자치도'}
# Names the same province has had; all become headwords.
OLD = {'강원특별자치도': ['강원도'], '전북특별자치도': ['전라북도']}
PQ = {'11': 'Q8684', '21': 'Q16520', '22': 'Q20927', '23': 'Q20934', '24': 'Q41283', '25': 'Q20921', '26': 'Q41278', '29': 'Q20929',
      '31': 'Q20937', '32': 'Q41071', '33': 'Q41066', '34': 'Q41070', '35': 'Q41157', '36': 'Q41161', '37': 'Q41154', '38': 'Q41151', '39': 'Q41164'}
CITY_GU = re.compile(r'^(.+?시)(.+구)$')
METRO = {'11', '21', '22', '23', '24', '25', '26', '29'}


def fetch(name, work):
    path = os.path.join(work, name)
    if not os.path.exists(path):
        req = urllib.request.Request(RAW + name, headers={'User-Agent': UA})
        with urllib.request.urlopen(req, timeout=120, context=CTX) as r:
            open(path, 'wb').write(r.read())
    return json.load(open(path))


def qid(b, k='item'):
    return b[k]['value'].split('/')[-1]


def wikidata(work, cities):
    """cities: {(city name, province code)} of the cities made of 일반구. Candidates for every place come from what Wikidata locates
    in each province; the districts of a city from what it locates in that city."""
    cache = os.path.join(work, 'wikidata.json')
    if os.path.exists(cache):
        return json.load(open(cache))
    cand = {}
    for pc, pq in PQ.items():
        for b in sparql(f'SELECT ?i ?l WHERE {{ ?i wdt:P131 wd:{pq} ; rdfs:label ?l . FILTER(LANG(?l)="ko" && REGEX(?l,"[시군구]$")) '
                        f'FILTER NOT EXISTS {{ ?i wdt:P576 ?e }} }}'):
            cand.setdefault((b['l']['value'], pc), set()).add(qid(b, 'i'))
        time.sleep(1)
    print('province-level candidates', len(cand), flush=True)
    sub = {}
    for city, pc in sorted(cities):
        for cq in cand.get((city, pc), ()):
            for b in sparql(f'SELECT ?g ?gl WHERE {{ ?g wdt:P131 wd:{cq} ; rdfs:label ?gl . FILTER(LANG(?gl)="ko" && REGEX(?gl,"구$")) FILTER NOT EXISTS {{ ?g wdt:P576 ?e }} }}'):
                sub.setdefault(f'{city}|{pc}', {}).setdefault(b['gl']['value'], []).append(qid(b, 'g'))
            time.sleep(0.5)
    print('cities with districts found', len(sub), flush=True)
    ids = set()
    for v in cand.values():
        ids |= v
    for v in sub.values():
        for g in v.values():
            ids |= set(g)
    ids |= set(PQ.values())
    ids = sorted(ids)
    info = {}
    for i in range(0, len(ids), 80):
        chunk = ' '.join('wd:' + x for x in ids[i:i + 80])
        q = f'''SELECT ?item ?ko ?en ?pop ?popDate ?area ?article WHERE {{ VALUES ?item {{{chunk}}}
          OPTIONAL {{ ?item rdfs:label ?ko FILTER(LANG(?ko)="ko") }} OPTIONAL {{ ?item rdfs:label ?en FILTER(LANG(?en)="en") }}
          OPTIONAL {{ ?item p:P1082 ?ps . ?ps ps:P1082 ?pop . OPTIONAL {{ ?ps pq:P585 ?popDate }} }}
          OPTIONAL {{ ?item p:P2046/psn:P2046/wikibase:quantityAmount ?area }}
          OPTIONAL {{ ?article schema:about ?item ; schema:isPartOf <https://ko.wikipedia.org/> }} }}'''
        for b in sparql(q):
            d = info.setdefault(qid(b), {'pops': {}})
            for f in ('ko', 'en', 'article', 'area'):
                if f in b and f not in d:
                    d[f] = b[f]['value']
            if 'pop' in b:
                d['pops'][b.get('popDate', {}).get('value', '')[:10]] = float(b['pop']['value'])
        print('attributes', min(i + 80, len(ids)), '/', len(ids), flush=True)
        time.sleep(1)
    out = {'cand': [[k[0], k[1], sorted(v)] for k, v in cand.items()],
           'sub': sub, 'info': info}
    json.dump(out, open(cache, 'w'), ensure_ascii=False)
    return out


def kowiki(articles, work):
    """Population from the 주민등록 figure in each Korean Wikipedia infobox (더 최신 than Wikidata's): {article URL: (people, 'YYYY년 M월')}."""
    cache = os.path.join(work, 'kowiki.json')
    got = json.load(open(cache)) if os.path.exists(cache) else {}
    todo = [a for a in articles if a not in got]
    for i in range(0, len(todo), 40):
        batch = todo[i:i + 40]
        titles = '|'.join(urllib.parse.unquote(a.rsplit('/wiki/', 1)[-1]).replace('_', ' ') for a in batch)
        url = 'https://ko.wikipedia.org/w/api.php?' + urllib.parse.urlencode({'action': 'query', 'prop': 'revisions', 'rvprop': 'content', 'rvslots': 'main',
                                                                              'titles': titles, 'redirects': 1, 'format': 'json', 'formatversion': 2})
        j = json.loads(http(url))
        redirect = {x['from']: x['to'] for x in j['query'].get('redirects', [])}
        text = {}
        for pg in j['query']['pages']:
            if 'revisions' in pg:
                text[pg['title']] = pg['revisions'][0]['slots']['main']['content']
        for a in batch:
            t = urllib.parse.unquote(a.rsplit('/wiki/', 1)[-1]).replace('_', ' ')
            w = text.get(redirect.get(t, t), '')
            pop = re.search(r'\|\s*(?:총인구|인구)\s*=\s*([\d,]+)', w)
            yr = re.search(r'\|\s*(?:총인구_조사연도|인구_조사연도|인구년도)\s*=\s*(\d{4})', w)
            mo = re.search(r'\|\s*인구월\s*=\s*(\d{1,2})', w)
            got[a] = [int(pop.group(1).replace(',', '')), (yr.group(1) + '년' + (f' {int(mo.group(1))}월' if mo else '')) if yr else ''] if pop else None
        print('wikipedia', min(i + 40, len(todo)), '/', len(todo), flush=True)
        time.sleep(0.5)
    json.dump(got, open(cache, 'w'), ensure_ascii=False)
    return got


def best(qids, info):
    """The item most likely to be the place itself: with a population and a Korean Wikipedia article."""
    return max(qids, key=lambda q: (bool(info.get(q, {}).get('pops')), bool(info.get(q, {}).get('article')), bool(info.get(q, {}).get('area'))))


def pop_of(d):
    if not d or not d['pops']:
        return None, ''
    when = max(d['pops'])
    return d['pops'][when], (when[:4] + '년') if when else ''


def main(work, out):
    os.makedirs(os.path.join(work, 'img'), exist_ok=True)
    muni = fetch('skorea-municipalities-2018-geo.json', work)['features']
    prov = fetch('skorea-provinces-2018-geo.json', work)['features']
    feats = {f['properties']['code']: f for f in muni if f['properties']['code'][:2] != '29'}  # 세종 is its own province entry
    pfeats = {f['properties']['code']: f for f in prov}
    # 수원시장안구 → city 수원시, district 장안구 (a 구 whose boundary name carries its city).
    split = {c: CITY_GU.match(f['properties']['name']) for c, f in feats.items() if c[:2] not in METRO}
    split = {c: m.groups() for c, m in split.items() if m}
    cities = {(city, c[:2]) for c, (city, gu) in split.items()}
    data = wikidata(work, cities)
    info = data['info']
    cand = {(l, p): set(v) for l, p, v in data['cand']}
    sub = data['sub']
    country = [r for f in pfeats.values() for r in rings(f['geometry'])]
    grey = [(country, '#dcdcdc', '#9a9a9a', 0.3)]
    places = []

    def pick(label, pcs):
        ids = set()
        for pc in pcs:
            ids |= cand.get((label, pc), set())
        return best(sorted(ids), info) if ids else None

    for code, f in sorted(feats.items()):
        name = f['properties']['name']
        pc = code[:2]
        en = f['properties'].get('name_eng', '')
        if code in split:
            city, gu = split[code]
            gq = best(sub.get(f'{city}|{pc}', {}).get(gu, []), info) if sub.get(f'{city}|{pc}', {}).get(gu) else None
            places.append({'name': f'{city} {gu}', 'gu': gu, 'code': code, 'kind': 'gu-in-city', 'parent': city, 'prov': pc, 'q': gq, 'rings': rings(f['geometry']), 'en': en})
        else:
            kind = 'gu' if name.endswith('구') else ('gun' if name.endswith('군') else 'si')
            # 군위군 moved from 경상북도 to 대구광역시 in 2023: found under whichever province Wikidata puts it in.
            q = pick(name, [pc]) or pick(name, [x for x in PQ if x != pc])
            places.append({'name': name, 'code': code, 'kind': kind, 'parent': PROVINCES[pc], 'prov': pc, 'q': q, 'rings': rings(f['geometry']), 'en': en})
    for city, pc in sorted(cities):
        codes = [c for c, (ct, g) in split.items() if ct == city and c[:2] == pc]
        rs = [r for c in codes for r in rings(feats[c]['geometry'])]
        places.append({'name': city, 'code': codes[0][:4] + '0', 'kind': 'si', 'parent': PROVINCES[pc], 'prov': pc, 'q': pick(city, [pc]), 'rings': rs, 'en': ''})
    for pc, f in sorted(pfeats.items()):
        places.append({'name': PROVINCES[pc], 'code': pc, 'kind': 'prov', 'parent': '', 'prov': pc, 'q': PQ[pc], 'rings': rings(f['geometry']), 'en': f['properties'].get('name_eng', '')})
    print('places', len(places), flush=True)
    wp = kowiki([info[p['q']]['article'] for p in places if p['q'] and info.get(p['q'], {}).get('article')], work)
    by_prov = {}
    for p in places:
        if p['kind'] != 'prov':
            by_prov.setdefault(p['prov'], []).append(p)
    terms, missing, nopop = [], [], []
    for p in places:
        d = info.get(p['q'], {}) if p['q'] else {}
        img = f'img/kr_{p["code"]}.png'
        path = os.path.join(work, img)
        try:
            if not os.path.exists(path):
                if p['kind'] == 'prov':
                    draw(path, p['rings'], grey, framing(country, p['rings'], 0.01), 36)
                else:
                    prings = [r for f in [pfeats[p['prov']]] for r in rings(f['geometry'])]
                    lat = sum(y for r in prings for _, y in r) / sum(len(r) for r in prings)
                    others = [(q['rings'], '#c7e7c4', '#6c9a6a', 0.4) for q in by_prov[p['prov']] if q['kind'] in ('si', 'gun', 'gu') and q['code'] != p['code']]
                    draw(path, p['rings'], grey + others, nearby(framing(prings, p['rings'], 0.02), p['rings']), lat)
        except Exception as e:
            missing.append((p['name'], str(e)))
            img = None
        pop, year = pop_of(d)
        if d.get('article') and wp.get(d['article']):
            pop, year = wp[d['article']]
        if not pop and p['kind'] in ('si', 'gun', 'gu-in-city'):
            # Wikidata doesn't place a few cities (목포시, 여수시…) in their province: their article, by title.
            url = 'https://ko.wikipedia.org/wiki/' + urllib.parse.quote(p.get('gu') or p['name'])
            got = kowiki([url], work).get(url)
            if got:
                pop, year = got
                d = dict(d, article=url)
        if not pop:
            nopop.append(p['name'])
        kindlabel = {'prov': '광역자치단체', 'si': '시', 'gun': '군', 'gu': '자치구', 'gu-in-city': '일반구'}[p['kind']]
        if p['kind'] == 'prov':
            kindlabel = '특별시' if p['name'].endswith('특별시') else '광역시' if p['name'].endswith('광역시') else '특별자치시' if p['name'].endswith('특별자치시') else '특별자치도' if p['name'].endswith('특별자치도') else '도'
        if p['kind'] == 'gu' and p['prov'] not in METRO:
            kindlabel = '구'
        en = d.get('en') or p['en']
        content = []
        if img:
            content.append({'tag': 'div', 'content': {'tag': 'img', 'path': img, 'alt': p['name'], 'height': 12, 'sizeUnits': 'em', 'collapsed': False, 'collapsible': False, 'background': False},
                            'style': {'marginBottom': '0.5em'}})
        parent = p['parent'] if p['kind'] != 'gu-in-city' else f'{PROVINCES[p["prov"]]} {p["parent"]}'
        content.append({'tag': 'div', 'content': ' · '.join(x for x in (parent, kindlabel) if x)})
        if en:
            content.append({'tag': 'div', 'style': {'fontSize': '0.85em'}, 'content': en})
        if pop:
            content.append({'tag': 'div', 'content': f'인구 {int(pop):,}명' + (f'（{year}）' if year else '')})
        if d.get('area'):
            a = float(d['area']) / 1e6
            line = f'면적 {a:,.0f} km²' if a >= 100 else f'면적 {a:,.1f} km²'
            if pop and a > 0:
                dens = pop / a
                line += f' · 인구밀도 {dens:,.1f}명/km²' if dens < 10 else f' · 인구밀도 {dens:,.0f}명/km²'
            content.append({'tag': 'div', 'style': {'fontSize': '0.85em'}, 'content': line})
        if d.get('article'):
            content.append({'tag': 'div', 'content': {'tag': 'a', 'href': d['article'], 'content': '위키백과（한국어）'}})
        gloss = [{'type': 'structured-content', 'content': content}]
        names = [p['name']]
        if p['kind'] == 'gu-in-city':
            names += [p['gu'], p['name'].replace(' ', '')]
        # 수원시 → 수원, 강원특별자치도 → 강원도, 강남구 stays: the short form of a 시/군 without the suffix, when it isn't ambiguous.
        short = re.sub(r'(특별자치시|특별자치도|특별시|광역시|시|군)$', '', p['name'])
        if p['kind'] in ('si', 'gun', 'prov') and len(short) >= 2 and short not in names:
            names.append(short)
        names += [x for x in OLD.get(p['name'], []) if x not in names]
        reading = p['name']
        if p['kind'] == 'gu':
            # 중구 exists in seven cities: each keeps its own entry, found by 중구, 서울 중구 or 서울특별시 중구.
            reading = f'{PROVINCES[p["prov"]]} {p["name"]}'
            short = re.sub(r'(특별자치시|특별시|광역시)$', '', PROVINCES[p['prov']]) if p['prov'] in METRO else ''
            names += [x for x in (reading, f'{short} {p["name"]}' if short else '') if x and x not in names]
        for n in names:
            terms.append([n, reading, '', '', 0, gloss, 0, ''])
    print('places', len(places), 'terms', len(terms), 'maps missing', len(missing), missing[:10])
    print('without population', len(nopop), nopop[:40])
    index = {'title': '한국 행정구역 사전（시·군·구）', 'format': 3, 'revision': time.strftime('%Y-%m'), 'author': 'Kotoba (Wikidata, KOSTAT via southkorea-maps)',
             'description': '대한민국의 광역자치단체와 시·군·구: 위치도, 소속, 인구, 면적, 위키백과 링크. 데이터: Wikidata; 경계: southkorea/southkorea-maps (통계청 2018).'}
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as o:
        o.writestr('index.json', json.dumps(index, ensure_ascii=False))
        for i in range(0, len(terms), 2000):
            o.writestr(f'term_bank_{i // 2000 + 1}.json', json.dumps(terms[i:i + 2000], ensure_ascii=False))
        for n in sorted(os.listdir(os.path.join(work, 'img'))):
            o.write(os.path.join(work, 'img', n), 'img/' + n)
    print('wrote', out)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
