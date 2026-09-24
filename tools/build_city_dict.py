#!/usr/bin/env python3
"""Adds 2025 census figures and Japanese Wikipedia links to the Yomitan dictionary 全市区町村辞典 (SalwynnJP).

  python3 tools/build_city_dict.py ORIGINAL.zip CENSUS.xlsx OUT.zip

ORIGINAL.zip  the dictionary as published ([JA Pictures] 全市区町村辞典.zip): per municipality a locator map
              (img/japan_city_<code>[_seirei|_sicho|_gun].png) and its prefecture.
CENSUS.xlsx   令和7年国勢調査 人口速報集計 第1表 (e-Stat statInfId 000040454825): population, 2020 population,
              area and density for every prefecture and municipality, by the 5-digit municipality code.
The latest population comes from Japanese Wikipedia's Template:自治体人口/<都道府県>, which its municipality articles
use: each prefecture's monthly 推計人口 (or 住民基本台帳人口), with its date. Wikipedia links come from Wikidata (P429,
the municipality code with its check digit); names Wikidata doesn't cover (subprefectures, 郡) are checked against
Japanese Wikipedia directly.
"""
import json, re, ssl, sys, time, urllib.parse, urllib.request, zipfile
import certifi, openpyxl

CTX = ssl.create_default_context(cafile=certifi.where())

UA = 'KotobaCityDict/0.1 (https://github.com/Leow210/kotoba)'


def get(url, data=None, raw=False):
    req = urllib.request.Request(url, data=data, headers={'User-Agent': UA, 'Accept': 'application/json'})
    with urllib.request.urlopen(req, timeout=180, context=CTX) as r:
        body = r.read().decode('utf-8')
    return body if raw else json.loads(body)


def census(path):
    """code -> {name, pref, pop, pop2020, area, density}; also (pref, name) -> code."""
    wb = openpyxl.load_workbook(path, read_only=True, data_only=True)
    rows, by_name = {}, {}
    for r in wb.worksheets[0].iter_rows(min_row=14, values_only=True):
        if not r or not r[2] or '_' not in str(r[2]):
            continue
        code, name = str(r[2]).split('_', 1)
        pref = str(r[1]).split('_', 1)[1] if r[1] and '_' in str(r[1]) else ''
        rows[code] = {'name': name, 'pref': pref, 'pop': r[3], 'pop2020': r[6], 'rate': r[8], 'area': r[10], 'density': r[11]}
        by_name[(pref, name)] = code
    return rows, by_name


def wikidata_links():
    """5-digit code -> Japanese Wikipedia URL."""
    q = '''SELECT ?code ?article WHERE { ?item wdt:P429 ?code .
      ?article schema:about ?item ; schema:isPartOf <https://ja.wikipedia.org/> . }'''
    d = get('https://query.wikidata.org/sparql?format=json&query=' + urllib.parse.quote(q))
    out = {}
    for b in d['results']['bindings']:
        code = re.sub(r'\D', '', b['code']['value'])[:5]
        out.setdefault(code, b['article']['value'])
    return out


def wiki_titles_exist(titles):
    """Which of these Japanese Wikipedia titles exist (following redirects): title -> URL."""
    found = {}
    titles = list(titles)
    for i in range(0, len(titles), 50):
        chunk = titles[i:i + 50]
        d = get('https://ja.wikipedia.org/w/api.php?action=query&format=json&redirects=1&titles=' + urllib.parse.quote('|'.join(chunk)))
        q = d.get('query', {})
        norm = {n['from']: n['to'] for n in q.get('normalized', [])}
        redir = {n['from']: n['to'] for n in q.get('redirects', [])}
        exists = {p['title'] for p in q.get('pages', {}).values() if 'missing' not in p}
        for t in chunk:
            t2 = redir.get(norm.get(t, t), norm.get(t, t))
            if t2 in exists:
                found[t] = 'https://ja.wikipedia.org/wiki/' + urllib.parse.quote(t2.replace(' ', '_'))
        time.sleep(0.5)
    return found


def estimates(prefs):
    """(pref, name) -> (population, date, source) from Template:自治体人口/<pref> on Japanese Wikipedia."""
    out = {}
    for pref in prefs:
        title = 'Template:自治体人口/' + pref
        try:
            text = get('https://ja.wikipedia.org/w/index.php?title=' + urllib.parse.quote(title) + '&action=raw', raw=True)
        except Exception as e:
            print('no template for', pref, e)
            continue
        date = (re.search(r'\|\s*date\s*=\s*([^\n|]+)', text) or [None, ''])[1].strip()
        src = re.sub(r'\[\[|\]\]', '', (re.search(r'\|\s*source\s*=\s*([^\n|]+)', text) or [None, ''])[1]).strip()
        for name, n in re.findall(r'\|\s*([^=|\n#]+?)\s*=\s*(\d+)\s*$', text, re.M):
            out[(pref, name)] = (int(n), date, src)
        time.sleep(0.3)
    return out


def facts(c, est):
    """The population lines for one place: the latest estimate, then the 2025 census."""
    lines = []
    if est:
        n, date, src = est
        lines.append(f"人口 {n:,}人（{src}・{date}）" if date else f"人口 {n:,}人")
    if c.get('pop'):
        rate = c.get('rate')
        change = f'、2020年比 {rate:+.1f}%' if isinstance(rate, (int, float)) else ''
        lines.append(f"2025年国勢調査 {int(c['pop']):,}人{change}")
    parts = []
    if isinstance(c.get('area'), (int, float)):
        parts.append(f"面積 {c['area']:,.2f} km²")
    if isinstance(c.get('density'), (int, float)):
        parts.append(f"人口密度 {c['density']:,.1f}人/km²")
    if parts:
        lines.append(' · '.join(parts))
    return lines


def main(src, xlsx, out):
    rows, by_name = census(xlsx)
    est = estimates(sorted({r['pref'] for r in rows.values() if r['pref']}))
    print(f'estimates {len(est)}')
    links = wikidata_links()
    print(f'census rows {len(rows)}, Wikipedia links by code {len(links)}')
    z = zipfile.ZipFile(src)
    terms = json.loads(z.read('term_bank_1.json'))
    # Places without a municipality code of their own: look their pages up by name.
    by_title = {}
    extra = set()
    for e in terms:
        s = json.dumps(e, ensure_ascii=False)
        m = re.search(r'japan_city_(\d{5})(_\w+)?\.png', s)
        kind = (m.group(2) or '') if m else ''
        if kind in ('_sicho', '_gun'):
            extra.add(e[0])
            pref = pref_of(e)
            if kind == '_gun' and pref:
                extra.add(f'{e[0]} ({pref})')
    by_title = wiki_titles_exist(sorted(extra))
    stats = {'pop': 0, 'link': 0, 'none': 0}
    for e in terms:
        s = json.dumps(e, ensure_ascii=False)
        m = re.search(r'japan_city_(\d{5})(_\w+)?\.png', s)
        code, kind = (m.group(1), m.group(2) or '') if m else ('', '')
        pref = pref_of(e)
        c, url = None, None
        if kind == '_seirei':
            code = by_name.get((pref, e[0]), code)  # the whole designated city (札幌市), not its first ward
        if kind in ('', '_seirei'):
            c = rows.get(code)
            url = links.get(code)
        elif kind == '_sicho':
            url = by_title.get(e[0])
        elif kind == '_gun':
            # 郡 names repeat across provinces (上川郡): prefer the page named with its prefecture.
            url = by_title.get(f'{e[0]} ({pref})') or by_title.get(e[0])
        e_now = est.get((pref, c['name'])) if c else None
        add = []
        for i, line in enumerate(facts(c or {}, e_now)):
            add.append({'tag': 'div', 'data': {'japan': 'population' if i == 0 else 'census'}, 'style': {} if i == 0 else {'fontSize': '0.85em'}, 'content': line})
        if c and (c.get('pop') or e_now):
            stats['pop'] += 1
            if e_now: stats['estimate'] = stats.get('estimate', 0) + 1
        if url:
            add.append({'tag': 'div', 'content': {'tag': 'a', 'href': url, 'content': 'Wikipedia（日本語）'}})
            stats['link'] += 1
        if not add:
            stats['none'] += 1
        for g in e[5]:
            if isinstance(g, dict) and g.get('type') == 'structured-content':
                g['content'].extend(add)
                break
    print(stats)
    index = json.loads(z.read('index.json'))
    index['title'] = '全市区町村辞典（人口・Wikipedia）'
    index['revision'] = '2026-09 census2025'
    index['description'] = (index.get('description', '') + '／人口: 各都道府県の推計人口（Wikipedia Template:自治体人口）・令和7年国勢調査 人口速報集計（総務省統計局）'
                            '／Wikipedia links via Wikidata').strip('／')
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as o:
        o.writestr('index.json', json.dumps(index, ensure_ascii=False))
        o.writestr('term_bank_1.json', json.dumps(terms, ensure_ascii=False))
        for n in z.namelist():
            if n not in ('index.json', 'term_bank_1.json'):
                o.writestr(n, z.read(n))
    print('wrote', out)


def pref_of(e):
    for g in e[5]:
        if isinstance(g, dict) and g.get('type') == 'structured-content':
            for c in g['content']:
                if isinstance(c, dict) and (c.get('data') or {}).get('japan') == 'headword':
                    return c.get('content') or ''
    return ''


if __name__ == '__main__':
    main(*sys.argv[1:4])
