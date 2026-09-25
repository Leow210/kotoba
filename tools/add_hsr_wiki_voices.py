#!/usr/bin/env python3
"""Adds Star Rail characters whose voice clips yatta doesn't have in a language (the Fate collab in Japanese) to a
listening set, from the Honkai: Star Rail fan wiki (honkai-star-rail.fandom.com): each character's
"<Name>/Voice-Overs/<Language>" page lists the lines (title, text, audio file); the English page gives the translation.

  python3 tools/add_hsr_wiki_voices.py [--set hsr-ja] [--language Japanese] [--code JA] 1014:Saber 1015:Archer …

Only the profile lines (the page's Interactive section), like the rest of the set. Polite: one request at a time.
"""
import argparse, html, json, re, subprocess, sys, urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_genshin_voice as gv  # noqa: E402
import build_hsr_voice as hv  # noqa: E402

WIKI = 'https://honkai-star-rail.fandom.com/api.php'
CACHE = hv.CACHE / 'wiki'


def wikitext(title):
    f = CACHE / (re.sub(r'[^\w.-]+', '_', title) + '.txt')
    if f.exists():
        return f.read_text()
    d = gv.fetch(f'{WIKI}?action=parse&format=json&prop=wikitext&page={urllib.parse.quote(title)}', 1.5)
    text = ((d or {}).get('parse') or {}).get('wikitext', {}).get('*', '')
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text(text)
    return text


def file_url(name):
    d = gv.fetch(f'{WIKI}?action=query&format=json&prop=imageinfo&iiprop=url&titles={urllib.parse.quote("File:" + name)}', 1.5)
    page = next(iter(((d or {}).get('query') or {}).get('pages', {}).values()), {})
    return (page.get('imageinfo') or [{}])[0].get('url')


def plain(t):
    t = re.sub(r'\[\[(?:[^|\]]*\|)?([^\]]*)\]\]', r'\1', t)       # [[link|text]] → text
    t = re.sub(r'\{\{[^{}]*\}\}', '', t)                           # templates
    t = re.sub(r"'{2,}", '', t).replace('<br>', '\n').replace('<br />', '\n')
    t = re.sub(r'<[^>]+>', '', t)
    return html.unescape(t).strip()


def entries(text, character):
    """The Interactive section's lines: key → {title, subtitle, file, tx}."""
    part = text.split('==Combat==')[0]
    out = {}
    for key, field, value in re.findall(r'^\|\s*(vo_\d+_\d+)_(title|subtitle|file|tx)\s*=\s*(.*)$', part, re.M):
        out.setdefault(key, {})[field] = value.strip()
    for e in out.values():
        if 'file' in e:
            e['file'] = e['file'].replace('{character}', character.replace(' ', '_'))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--set', default='hsr-ja')
    ap.add_argument('--language', default='Japanese')
    ap.add_argument('--code', default='JA')
    ap.add_argument('--section', default='異世界')
    ap.add_argument('--section-en', default='Other World')
    ap.add_argument('--files', type=Path, action='append', default=None, help='folder with the saved .ogg files (default ~/Downloads)')
    ap.add_argument('chars', nargs='+', help='avatarId:Wiki name')
    args = ap.parse_args()
    args.files = args.files or [Path.home() / 'Downloads']
    out = gv.DATA / args.set
    s = json.loads((out / 'set.json').read_text())
    lang = s.get('lang', 'ja')
    code = {'ja': 'jp', 'zh': 'cn', 'ko': 'kr'}.get(lang, lang)
    nick = hv.LANGS.get(lang, ('', '', '', '開拓者'))[3]
    orders = {g['section']: g.get('order', 0) for g in s['groups']}
    order = orders.get(args.section, max(orders.values() or [0]) + 1)
    for spec in args.chars:
        aid, name = spec.split(':', 1)
        local = entries(wikitext(f'{name}/Voice-Overs/{args.language}'), name)
        english = entries(wikitext(f'{name}/Voice-Overs'), name)
        detail = (hv.api(f'{code}/avatar/{aid}') or {}).get('data') or {}
        items = []
        for key in sorted(local):
            e = local[key]
            if not e.get('file') or not e.get('tx'):
                continue
            fname = e['file'].replace('{language}', args.code)
            vid = 'w' + re.sub(r'\D', '', key)
            dest = out / 'audio' / aid / f'{vid}.m4a'
            if not dest.exists():
                # The wiki's file server only answers browsers (Cloudflare), so the files come from a folder you saved
                # them to (Downloads by default), under the wiki's own names.
                # Browsers save Fandom's space-containing file names with underscores.
                names = (fname, fname.replace(' ', '_'), fname.replace('_', ' '))
                raw = next((d / n for d in args.files for n in names if (d / n).exists()), None)
                if raw is None:
                    print('  missing', fname)
                    continue
                dest.parent.mkdir(parents=True, exist_ok=True)
                subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(raw), '-ac', '1', '-c:a', 'aac', '-b:a', '64k', str(dest)], check=True)
            en = english.get(key, {})
            items.append({'id': vid, 'title': plain(e.get('title', '')), 'titleEn': plain(e.get('subtitle') or en.get('title', '')),
                          'text': hv.clean(plain(e['tx']), nick), 'translation': plain(en.get('tx', '')),
                          'audio': f'audio/{aid}/{vid}.m4a', 'dur': gv.duration(dest), 'note': ''})
        if not items:
            print(name, 'no lines')
            continue
        icon = out / 'icons' / f'{aid}.png'
        src = hv.CACHE / 'icons' / f'{aid}.png'
        if not icon.exists() and src.exists():
            icon.parent.mkdir(parents=True, exist_ok=True)
            icon.write_bytes(src.read_bytes())
        g = {'id': aid, 'name': hv.clean(detail.get('name') or name, nick), 'nameEn': name, 'icon': f'icons/{aid}.png' if icon.exists() else '',
             'section': args.section, 'sectionEn': args.section_en, 'order': order, 'release': 0, 'items': items, 'source': 'Star Rail wiki'}
        s['groups'] = [x for x in s['groups'] if x['id'] != aid] + [g]
        print(f'{g["name"]} ({name}): {len(items)} lines')
    s['groups'].sort(key=lambda g: (g.get('order', 0), g.get('release', 0), int(re.sub(r'\D', '', g['id']) or 0)))
    (out / 'set.json').write_text(json.dumps(s, ensure_ascii=False, indent=0))


if __name__ == '__main__':
    main()
