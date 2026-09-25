#!/usr/bin/env python3
"""A listening set from Genshin Impact's character voice-overs (the profile lines, no combat), from gi.yatta.moe.

  python3 tools/build_genshin_voice.py [--lang chs] [--out DIR] [--only 10000046,10000030]

Writes DIR/set.json and, per character, audio/<id>/<line>.m4a (AAC, which WebKit and Android both play) and
icons/<id>.png. Each line has its title and text in the voice's language and in English (for the translation).
Profile lines have four-digit voice ids (chat, weather, about others, birthday, ascension…); combat lines have six.

Polite: one connection, a pause between requests, everything cached (CACHE) so a rerun only fetches what's new.
set.json is rewritten after each character, so the set can be used while the rest downloads.
"""
import argparse, json, os, re, ssl, subprocess, sys, threading, time, urllib.error, urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

try:
    import certifi
    CTX = ssl.create_default_context(cafile=certifi.where())
except ImportError:
    CTX = ssl.create_default_context()

BASE = 'https://gi.yatta.moe'
UA = 'KotobaListening/0.1 (personal study; single connection)'
CACHE = Path.home() / 'Library/Caches/Kotoba/genshin-voice'
DATA = Path.home() / 'Library/Application Support/Kotoba/listening'
LANGS = {'chs': ('zh', '原神 · 角色语音', 'CHS'), 'jp': ('ja', '原神 · キャラクターボイス', 'JP'), 'kr': ('ko', '원신 · 캐릭터 보이스', 'KR')}
REGIONS = {'MONDSTADT': ('蒙德', 'Mondstadt'), 'LIYUE': ('璃月', 'Liyue'), 'INAZUMA': ('稻妻', 'Inazuma'), 'SUMERU': ('须弥', 'Sumeru'),
           'FONTAINE': ('枫丹', 'Fontaine'), 'NATLAN': ('纳塔', 'Natlan'), 'NODKRAI': ('挪德卡莱', 'Nod-Krai'),
           'SNEZHNAYA': ('至冬', 'Snezhnaya'), 'MAINACTOR': ('旅行者', 'Traveler'), 'RANGER': ('其他', 'Other')}
# Region headings in the set's own language (Chinese names above).
REGION_NAMES = {'ja': {'MONDSTADT': 'モンド', 'LIYUE': '璃月', 'INAZUMA': '稲妻', 'SUMERU': 'スメール', 'FONTAINE': 'フォンテーヌ', 'NATLAN': 'ナタ',
                       'NODKRAI': 'ナド・クライ', 'SNEZHNAYA': 'スネージナヤ', 'MAINACTOR': '旅人', 'RANGER': 'その他'},
                'ko': {'MONDSTADT': '몬드', 'LIYUE': '리월', 'INAZUMA': '이나즈마', 'SUMERU': '수메르', 'FONTAINE': '폰타인', 'NATLAN': '나타',
                       'NODKRAI': '노드크라이', 'SNEZHNAYA': '스네즈나야', 'MAINACTOR': '여행자', 'RANGER': '기타'}}
NICKNAME = {'zh': '旅行者', 'ja': '旅人', 'ko': '여행자'}


def region_name(code, lang):
    zh, en = REGIONS[code]
    return REGION_NAMES.get(lang, {}).get(code, zh), en


# yatta's other codes: the Fatui are Snezhnayan; Skirk (OMNI_SCOURGE) and Aloy (RANGER) come from outside Teyvat.
REGION_ALIAS = {'FATUI': 'SNEZHNAYA', 'SNEZHNAYA_STAR': 'SNEZHNAYA', 'NODKRAI_ZIBAI': 'NODKRAI', 'OMNI_SCOURGE': 'RANGER', 'HVISION': 'RANGER'}


def region_of(code):
    code = (code or '').upper()
    code = REGION_ALIAS.get(code, code)
    return code if code in REGIONS else 'RANGER'
last = [0.0]


def fetch(url, pause, binary=False):
    wait = last[0] + pause - time.time()
    if wait > 0:
        time.sleep(wait)
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers={'User-Agent': UA})
            with urllib.request.urlopen(req, timeout=60, context=CTX) as r:
                body = r.read()
            last[0] = time.time()
            return body if binary else json.loads(body)
        except urllib.error.HTTPError as e:
            last[0] = time.time()
            if e.code == 404:
                return None
            if e.code in (403, 429):
                sys.exit(f'{e.code} from {url}: stopping, to be polite')
            time.sleep(5 * (attempt + 1))
        except Exception:
            time.sleep(5 * (attempt + 1))
    return None


def get_asset(url):
    """A static file (no shared pause: a few of these run at once). One that keeps hanging is skipped; a rerun gets it."""
    for attempt in range(2):
        try:
            req = urllib.request.Request(url, headers={'User-Agent': UA})
            with urllib.request.urlopen(req, timeout=60, context=CTX) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            if e.code in (403, 429):
                print(f'{e.code} from {url}: stopping, to be polite', flush=True)
                os._exit(1)
            time.sleep(5 * (attempt + 1))
        except Exception:
            time.sleep(5 * (attempt + 1))
    return None


def api(path):
    f = CACHE / 'api' / (path.replace('/', '__') + '.json')
    if f.exists():
        return json.loads(f.read_text())
    d = fetch(f'{BASE}/api/v2/{path}', 2.5)
    if d is not None:
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(json.dumps(d, ensure_ascii=False))
    return d


def clean(text, traveler='M', nickname='旅行者'):
    t = str(text or '')
    if t.startswith('#'):
        t = t[1:]
    t = re.sub(r'\{F#([^}]*)\}\{M#([^}]*)\}', lambda m: m.group(2 if traveler == 'M' else 1), t)
    t = re.sub(r'\{M#([^}]*)\}\{F#([^}]*)\}', lambda m: m.group(1 if traveler == 'M' else 2), t)
    t = re.sub(r'\{LAYOUT_MOBILE#[^}]*\}\{LAYOUT_PC#([^}]*)\}\{LAYOUT_PS#[^}]*\}', r'\1', t)
    t = t.replace('{NICKNAME}', nickname)
    t = re.sub(r'\{RUBY#\[[A-Z]\][^}]*\}|\{RUBY_B#[^}]*\}|\{RUBY_E#\}', '', t)  # furigana markup (Genshin, Star Rail): the reading goes, the kanji stay
    t = re.sub(r'</?color[^>]*>|</?i>|</?b>', '', t)
    t = t.replace('\\n', '\n')
    return t.strip()


def duration(path):
    out = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', str(path)], capture_output=True, text=True).stdout
    try:
        return round(float(out.strip()), 2)
    except ValueError:
        return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--lang', default='chs', choices=sorted(LANGS))
    ap.add_argument('--out')
    ap.add_argument('--only', default='')
    ap.add_argument('--traveler', default='M', choices=['M', 'F'])
    args = ap.parse_args()
    lang, title, audio_lang = LANGS[args.lang]
    out = Path(args.out) if args.out else DATA / f'genshin-{lang}'
    out.mkdir(parents=True, exist_ok=True)
    avatars = api(f'{args.lang}/avatar')['data']['items']
    avatars_en = api('en/avatar')['data']['items']
    only = {x for x in args.only.split(',') if x}
    ids = sorted((k for k in avatars if k.isdigit() and (not only or k in only)), key=lambda k: (avatars[k].get('release') or 0, k))
    groups = []
    old = {}
    if (out / 'set.json').exists():
        old = {g['id']: g for g in json.loads((out / 'set.json').read_text()).get('groups', [])}

    def write():
        s = {'id': out.name, 'title': title, 'subtitle': 'Genshin Impact character voice-overs (profile lines, no combat) · gi.yatta.moe',
             'lang': lang, 'translationLang': 'en', 'groupLabel': 'Characters',
             'groups': sorted(list({**old, **{g['id']: g for g in groups}}.values()), key=lambda g: (g.get('order', 0), g['id']))}
        tmp = out / 'set.json.tmp'
        tmp.write_text(json.dumps(s, ensure_ascii=False, indent=0))
        tmp.replace(out / 'set.json')

    # Downloads run ahead of the characters being put together: a feeder thread reads each character's lines and
    # queues its clips (six at a time overall), so one slow clip doesn't hold up everyone after it.
    pool = ThreadPoolExecutor(6)
    prepared = {}
    ready = threading.Condition()

    def grab(aid, vid, raw):
        b = get_asset(f'{BASE}/assets/Audio/{audio_lang}/{aid}/{vid}.ogg')
        if b:
            raw.parent.mkdir(parents=True, exist_ok=True)
            raw.write_bytes(b)

    def feed():
        for aid in ids:
            fet = (api(f'{args.lang}/avatarFetter/{aid}') or {}).get('data') or {}
            fet_en = (api(f'en/avatarFetter/{aid}') or {}).get('data') or {}
            quotes = [q for q in (fet.get('quotes') or {}).values() if q.get('audio') and q.get('text')]
            futures = []
            for q in quotes:
                vid = str(q['audio']).split(',')[0].strip()
                raw = CACHE / 'ogg' / audio_lang / aid / f'{vid}.ogg'
                if re.fullmatch(r'\d{4}', vid) and not raw.exists() and not (out / 'audio' / aid / f'{vid}.m4a').exists():
                    futures.append(pool.submit(grab, aid, vid, raw))
            with ready:
                prepared[aid] = (fet, fet_en, quotes, futures)
                ready.notify_all()
    threading.Thread(target=feed, daemon=True).start()

    for n, aid in enumerate(ids):
        a, a_en = avatars[aid], avatars_en.get(aid, {})
        with ready:
            ready.wait_for(lambda: aid in prepared)
            fet, fet_en, quotes, futures = prepared.pop(aid)
        for fu in futures:
            fu.result()
        en_by_audio = {q.get('audio'): q for q in (fet_en.get('quotes') or {}).values()}
        items = []
        for q in quotes:
            vid = str(q['audio']).split(',')[0].strip()
            if not re.fullmatch(r'\d{4}', vid):  # combat and field lines have six-digit ids
                continue
            dest = out / 'audio' / aid / f'{vid}.m4a'
            if not dest.exists():
                raw = CACHE / 'ogg' / audio_lang / aid / f'{vid}.ogg'
                if not raw.exists():
                    continue
                dest.parent.mkdir(parents=True, exist_ok=True)
                # A clip cut short (a full disk, a dropped connection) is thrown away, to be fetched again next run.
                if subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(raw), '-ac', '1', '-c:a', 'aac', '-b:a', '64k', str(dest)]).returncode != 0:
                    raw.unlink(missing_ok=True)
                    dest.unlink(missing_ok=True)
                    continue
            en = en_by_audio.get(q['audio']) or {}
            nick = NICKNAME.get(lang, '旅行者')
            items.append({'id': vid, 'title': clean(q.get('title'), args.traveler, nick), 'titleEn': clean(en.get('title'), args.traveler, 'Traveler'),
                          'text': clean(q['text'], args.traveler, nick), 'translation': clean(en.get('text'), args.traveler, 'Traveler'),
                          'audio': f'audio/{aid}/{vid}.m4a', 'dur': duration(dest), 'note': clean(q.get('tips'), args.traveler, nick)})
        if not items:
            continue
        icon = out / 'icons' / f'{aid}.png'
        if not icon.exists() and a.get('icon'):
            b = fetch(f"{BASE}/assets/UI/{a['icon']}.png", 0.6, binary=True)
            if b:
                icon.parent.mkdir(parents=True, exist_ok=True)
                icon.write_bytes(b)
        region = region_of(a.get('region'))
        rz, ren = region_name(region, lang)
        groups.append({'id': aid, 'name': a.get('name', aid), 'nameEn': a_en.get('name', ''), 'icon': f'icons/{aid}.png' if icon.exists() else '',
                       'section': rz, 'sectionEn': ren, 'order': list(REGIONS).index(region) if region in REGIONS else 99,
                       'rank': a.get('rank'), 'element': a.get('element'), 'items': items})
        write()
        print(f'[{n + 1}/{len(ids)}] {a.get("name")} {len(items)} lines', flush=True)
    write()
    print('done', out)


if __name__ == '__main__':
    main()
