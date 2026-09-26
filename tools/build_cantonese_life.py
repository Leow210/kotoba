#!/usr/bin/env python3
"""A Cantonese listening set from Open Cantonese's book "Cantonese Life 1" (opencantonese.org, free to use for learning).

  python3 tools/build_cantonese_life.py [--out DIR]

Follows the book's own links (units → lessons → pages, one request a second, cached in CACHE) and keeps every
dialogue line and grammar example: the characters, jyutping, the word-by-word gloss when the book gives one, the
English, and the book's recording when it has one (lessons with audio). Lines without a recording are listed in
tts.json for tools/tts_minimax.py. Writes listening/yue-life1 (set.json, audio/…); one group per lesson, under its unit.
Personal study use: the set stays on your devices and out of the repository.
"""
import argparse, json, re, subprocess, sys, time, urllib.parse, urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_genshin_voice as gv  # noqa: E402  (helpers: fetch pause, duration, data folder)

BASE = 'https://opencantonese.org'
ROOT = '/books/cantonese-life-1'
CACHE = gv.CACHE.parent / 'cantonese-life'
UA = 'Mozilla/5.0 (KotobaStudy; personal Cantonese study)'
SKIP = ('exercises', 'activities', 'print', 'chinese-character-guide', 'pronunciation-guide', 'grammar-index')
JYUT = re.compile(r"^[a-z]+[1-6](?:[\s,.?!…'’\-]+[a-z]+[1-6])*[\s,.?!…。，？！]*$", re.I)
last = [0.0]


def get(path):
    f = CACHE / 'pages' / (re.sub(r'[^\w.-]+', '_', path.strip('/')) + '.txt')
    if f.exists():
        return f.read_text()
    wait = last[0] + 1.0 - time.time()
    if wait > 0:
        time.sleep(wait)
    req = urllib.request.Request(BASE + path, headers={'User-Agent': UA})
    with urllib.request.urlopen(req, timeout=60, context=gv.CTX) as r:
        html = r.read().decode('utf-8')
    last[0] = time.time()
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text(html)
    return html


def flight(html):
    """The page's React data (self.__next_f), parsed line by line into JSON values."""
    chunks = re.findall(r'self\.__next_f\.push\(\[1,"(.*?)"\]\)</script>', html, re.S)
    text = ''.join(json.loads('"' + c + '"') for c in chunks)
    out = []
    for line in text.split('\n'):
        m = re.match(r'^[0-9a-f]+:(\[.*|\{.*)$', line)
        if m:
            try:
                out.append(json.loads(m.group(1)))
            except ValueError:
                pass
    return out


def node(v):
    return isinstance(v, list) and len(v) == 4 and v[0] == '$' and isinstance(v[3], dict)


def text(v):
    if isinstance(v, str):
        return v
    if isinstance(v, (int, float)):
        return str(v)
    if node(v):
        return text(v[3].get('children', ''))
    if isinstance(v, list):
        return ''.join(text(x) for x in v)
    return ''


def find(v, test):
    """Descendant props dicts that pass test, in order."""
    out = []
    def walk(x):
        if node(x):
            if test(x[3]):
                out.append(x[3])
            walk(x[3].get('children'))
        elif isinstance(x, list):
            for y in x:
                walk(y)
    walk(v)
    return out


def lines(tree, page):
    """Dialogue bubbles (characters, jyutping and gloss per word) and grammar examples (three lines split by <br>)."""
    out = []
    def walk(x):
        if node(x):
            p = x[3]
            if 'left' in p or 'right' in p:                         # a dialogue bubble
                tables = find(x, lambda q: isinstance(q.get('chinese'), list))
                if tables:
                    t = tables[0]
                    urls = find(x, lambda q: isinstance(q.get('url'), str))
                    kids = p.get('children') or []
                    tr = text(kids[-1]).strip() if isinstance(kids, list) and kids else ''
                    words = [{'w': c, 'j': j, 'g': g} for c, j, g in zip(t['chinese'], t['jyutping'], t.get('english') or [''] * len(t['chinese']))]
                    out.append({'text': ''.join(t['chinese']), 'roman': ' '.join(w['j'] for w in words if w['j'].strip()),
                                'words': words, 'translation': tr, 'url': urls[0]['url'] if urls else '', 'page': page, 'kind': 'dialogue'})
                return
            kids = p.get('children')
            if x[1] == 'p' and isinstance(kids, list) and sum(1 for k in kids if node(k) and k[1] == 'br') >= 2:
                segs, cur = [], []
                for k in kids:
                    if node(k) and k[1] == 'br':
                        segs.append(cur); cur = []
                    else:
                        cur.append(k)
                segs.append(cur)
                parts = [re.sub(r'\s+', ' ', text(s)).strip() for s in segs]
                parts = [q for q in parts if q]
                if len(parts) >= 3 and re.search(r'[一-鿿]', parts[0]) and JYUT.match(parts[1]):
                    urls = find(x, lambda q: isinstance(q.get('url'), str))
                    out.append({'text': parts[0].replace(' ', ''), 'roman': parts[1], 'words': [], 'translation': parts[2],
                                'url': urls[0]['url'] if urls else '', 'page': page, 'kind': 'example'})
                    return
            walk(kids)
        elif isinstance(x, list):
            for y in x:
                walk(y)
    for v in tree:
        walk(v)
    return out


def links(html):
    """Book pages linked from a page, in order, without #anchors."""
    seen, out = set(), []
    for h in re.findall(r'href="(' + re.escape(ROOT) + r'/unit-[^"#]*)', html):
        h = h.rstrip('/')
        if h not in seen and not any(s in h for s in SKIP):
            seen.add(h)
            out.append(h)
    return out


def title(html):
    m = re.search(r'<title>(.*?)</title>', html, re.S)
    return (m.group(1).split(' | ')[0].strip() if m else '')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out')
    args = ap.parse_args()
    out = Path(args.out) if args.out else gv.DATA / 'yue-life1'
    out.mkdir(parents=True, exist_ok=True)
    book = get(ROOT)
    units = sorted({re.match(r'(' + re.escape(ROOT) + r'/unit-\d+)', h).group(1) for h in links(book)}, key=lambda u: int(u.rsplit('-', 1)[1]))
    groups, tts = [], []
    for u in units:
        uhtml = get(u)
        utitle = title(uhtml)
        # A unit's lessons (unit 0 has pages directly).
        lessons = [h for h in links(uhtml) if re.fullmatch(re.escape(u) + r'/lesson-\d+', h)] or [u]
        for lesson in lessons:
            lhtml = get(lesson)
            ltitle = title(lhtml)
            pages = [h for h in links(lhtml) if h.startswith(lesson + '/') or (lesson == u and h.startswith(u + '/'))]
            items, seen = [], set()
            for pg in pages:
                phtml = get(pg)
                ptitle = title(phtml)
                for ln in lines(flight(phtml), ptitle):
                    if ln['text'] in seen or len(ln['text']) < 2:
                        continue
                    seen.add(ln['text'])
                    items.append(ln)
            if not items:
                continue
            gid = re.sub(r'\W+', '-', lesson.replace(ROOT + '/', '')).strip('-')
            gitems = []
            for n, ln in enumerate(items):
                iid = f'{gid}-{n + 1}'
                audio = ''
                if ln['url']:
                    dest = out / 'audio' / gid / f'{n + 1}.m4a'
                    if not dest.exists():
                        raw = CACHE / 'mp3' / ln['url'].strip('/').replace('/', '_')
                        if not raw.exists():
                            b = gv.get_asset(BASE + urllib.parse.quote(ln['url']))
                            if b:
                                raw.parent.mkdir(parents=True, exist_ok=True)
                                raw.write_bytes(b)
                        if raw.exists():
                            dest.parent.mkdir(parents=True, exist_ok=True)
                            if subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(raw), '-ac', '1', '-c:a', 'aac', '-b:a', '64k', str(dest)]).returncode != 0:
                                dest.unlink(missing_ok=True)
                    if dest.exists():
                        audio = f'audio/{gid}/{n + 1}.m4a'
                item = {'id': iid, 'title': ln['page'], 'titleEn': '', 'text': ln['text'], 'roman': ln['roman'], 'words': ln['words'],
                        'translation': ln['translation'], 'audio': audio, 'dur': gv.duration(out / audio) if audio else 0,
                        'note': 'Recording: Cantonese Life 1' if audio else '', 'kind': ln['kind']}
                if not audio:
                    tts.append({'group': gid, 'id': iid, 'text': ln['text'], 'roman': ln['roman'], 'audio': f'audio/{gid}/{n + 1}.m4a'})
                gitems.append(item)
            groups.append({'id': gid, 'name': ltitle, 'nameEn': '', 'icon': '', 'section': utitle, 'sectionEn': '',
                           'order': len(groups), 'items': gitems})
            print(f'{utitle} · {ltitle}: {len(gitems)} lines ({sum(1 for i in gitems if i["audio"])} recorded)', flush=True)
    s = {'id': out.name, 'title': '粵語 · Cantonese Life 1', 'subtitle': 'Open Cantonese · dialogues and grammar examples, jyutping and word glosses',
         'lang': 'yue', 'translationLang': 'en', 'groupLabel': 'Lessons', 'roman': 'jyutping', 'groups': groups}
    (out / 'set.json').write_text(json.dumps(s, ensure_ascii=False, indent=0))
    (out / 'tts.json').write_text(json.dumps(tts, ensure_ascii=False, indent=0))
    print('done', out, sum(len(g['items']) for g in groups), 'lines,', len(tts), 'need TTS')


if __name__ == '__main__':
    main()
