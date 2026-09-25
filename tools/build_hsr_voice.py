#!/usr/bin/env python3
"""Listening sets from Honkai: Star Rail's character voice-overs (the profile lines: greetings, chats, about others…),
in Chinese and Japanese, with each character's stories in Chinese, Japanese, Korean and English, from sr.yatta.moe.

  python3 tools/build_hsr_voice.py [--langs zh,ja] [--only 1001,1002]

Writes listening/hsr-zh and listening/hsr-ja (set.json, audio/<id>/<voice>.m4a, icons/<id>.png, stories.json), in the
same format as the Genshin sets. Combat lines have no audio on yatta and are left out. Characters with several
forms (March 7th, the Trailblazer's paths) become one group. Shares build_genshin_voice's helpers and politeness.
"""
import argparse, json, re, subprocess, sys, threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_genshin_voice as gv  # noqa: E402

BASE = 'https://sr.yatta.moe'
CACHE = gv.CACHE.parent / 'hsr-voice'
LANGS = {'zh': ('cn', 'CN', '崩坏：星穹铁道 · 角色语音', '开拓者'), 'ja': ('jp', 'JP', '崩壊：スターレイル · キャラクターボイス', '開拓者')}
STORY_LANGS = [('zh', 'cn', '开拓者'), ('ja', 'jp', '開拓者'), ('ko', 'kr', '개척자'), ('en', 'en', 'Trailblazer')]


def api(path):
    f = CACHE / 'api' / (path.replace('/', '__') + '.json')
    if f.exists():
        return json.loads(f.read_text())
    d = gv.fetch(f'{BASE}/api/v2/{path}', 2.5)
    if d is not None:
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(json.dumps(d, ensure_ascii=False))
    return d


def clean(text, nickname):
    t = gv.clean(text, 'M', nickname)
    return re.sub(r'<[^>]+>', '', t).strip()  # <i>, <unbreak>, colour tags


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--langs', default='zh,ja')
    ap.add_argument('--only', default='')
    args = ap.parse_args()
    langs = [l for l in args.langs.split(',') if l in LANGS]
    only = {x for x in args.only.split(',') if x}
    avatars = api('cn/avatar')['data']['items']
    ids = sorted((k for k in avatars if not only or k in only), key=lambda k: (avatars[k].get('release') or 0, int(k)))
    outs = {l: gv.DATA / f'hsr-{l}' for l in langs}
    for o in outs.values():
        o.mkdir(parents=True, exist_ok=True)
    pool = ThreadPoolExecutor(6)
    groups = {l: {} for l in langs}          # name → group, in each set
    stories = {}
    factions = {l: [] for l in langs}

    def grab(url, raw):
        b = gv.get_asset(url)
        if b:
            raw.parent.mkdir(parents=True, exist_ok=True)
            raw.write_bytes(b)

    def write(lang):
        out = outs[lang]
        # A character without clips in this language (yatta has no Japanese audio for the Fate collab) is left out.
        gs = sorted((g for g in groups[lang].values() if g['items']), key=lambda g: (g['order'], g['release'], int(g['id'])))
        s = {'id': out.name, 'title': LANGS[lang][2], 'subtitle': 'Honkai: Star Rail character voice-overs (profile lines) · sr.yatta.moe',
             'lang': lang, 'translationLang': 'en', 'groupLabel': 'Characters', 'groups': gs}
        (out / 'set.json.tmp').write_text(json.dumps(s, ensure_ascii=False, indent=0))
        (out / 'set.json.tmp').replace(out / 'set.json')
        (out / 'stories.json.tmp').write_text(json.dumps({'langs': [l for l, _, _ in STORY_LANGS], 'groups': stories}, ensure_ascii=False))
        (out / 'stories.json.tmp').replace(out / 'stories.json')

    for n, aid in enumerate(ids):
        detail = {l: ((api(f'{code}/avatar/{aid}') or {}).get('data') or {}) for l, code, _ in STORY_LANGS}
        en_voice = {str(v.get('audio')): v for v in ((detail['en'].get('script') or {}).get('voice') or []) if v.get('audio')}
        name_zh = detail['zh'].get('name') or avatars[aid].get('name', aid)
        for lang in langs:
            code, audio_lang, _, nick = LANGS[lang]
            d = detail[lang]
            voices = [v for v in ((d.get('script') or {}).get('voice') or []) if v.get('audio') and v.get('text')]
            if not voices:
                continue
            name = clean(d.get('name') or name_zh, nick)
            g = groups[lang].get(name)
            if g is None:
                faction = clean((d.get('fetter') or {}).get('faction') or '', nick) or ('その他' if lang == 'ja' else '其他')
                faction_en = clean((detail['en'].get('fetter') or {}).get('faction') or '', 'Trailblazer')
                if faction not in factions[lang]:
                    factions[lang].append(faction)
                g = groups[lang][name] = {'id': aid, 'name': name, 'nameEn': clean(detail['en'].get('name') or '', 'Trailblazer'), 'icon': '',
                                          'section': faction, 'sectionEn': faction_en, 'order': factions[lang].index(faction),
                                          'release': avatars[aid].get('release') or 0, 'items': []}
            have = {it['id'] for it in g['items']}
            out = outs[lang]
            jobs = []
            for v in voices:
                vid = str(v['audio'])
                if vid in have:
                    continue
                raw = CACHE / 'ogg' / audio_lang / aid / f'{vid}.ogg'
                dest = out / 'audio' / aid / f'{vid}.m4a'
                if not raw.exists() and not dest.exists():
                    jobs.append(pool.submit(grab, f'{BASE}/hsr/assets/Audio/{audio_lang}/{aid}/{vid}.ogg', raw))
            for j in jobs:
                j.result()
            for v in voices:
                vid = str(v['audio'])
                if vid in have:
                    continue
                raw = CACHE / 'ogg' / audio_lang / aid / f'{vid}.ogg'
                dest = out / 'audio' / aid / f'{vid}.m4a'
                if not dest.exists():
                    if not raw.exists():
                        continue
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    # A clip cut short (a full disk, a dropped connection) is thrown away, to be fetched again next run.
                if subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(raw), '-ac', '1', '-c:a', 'aac', '-b:a', '64k', str(dest)]).returncode != 0:
                    raw.unlink(missing_ok=True)
                    dest.unlink(missing_ok=True)
                    continue
                en = en_voice.get(vid) or {}
                g['items'].append({'id': vid, 'title': clean(v.get('title'), nick), 'titleEn': clean(en.get('title'), 'Trailblazer'),
                                   'text': clean(v['text'], nick), 'translation': clean(en.get('text'), 'Trailblazer'),
                                   'audio': f'audio/{aid}/{vid}.m4a', 'dur': gv.duration(dest), 'note': ''})
                have.add(vid)
            icon = out / 'icons' / f'{g["id"]}.png'
            if not icon.exists():
                src = CACHE / 'icons' / f'{g["id"]}.png'
                if not src.exists():
                    b = gv.get_asset(f'{BASE}/hsr/assets/UI/avatar/medium/{avatars[g["id"]].get("icon") or g["id"]}.png')
                    if b:
                        src.parent.mkdir(parents=True, exist_ok=True)
                        src.write_bytes(b)
                if src.exists():
                    icon.parent.mkdir(parents=True, exist_ok=True)
                    icon.write_bytes(src.read_bytes())
            g['icon'] = f'icons/{g["id"]}.png' if icon.exists() else ''
        # Stories, once per character (the first form's id, as its group).
        gid = next((groups[l][k]['id'] for l in langs for k in groups[l] if groups[l][k]['id'] == aid), None)
        if gid and gid not in stories:
            st = {'names': {}, 'sections': {}}
            for l, code, nick in STORY_LANGS:
                st['names'][l] = clean(detail[l].get('name') or '', nick)
                secs = [{'title': clean(s.get('title'), nick), 'text': clean(s.get('text'), nick), 'note': ''}
                        for s in ((detail[l].get('script') or {}).get('story') or []) if s.get('text')]
                if secs:
                    st['sections'][l] = secs
            if st['sections']:
                stories[gid] = st
        for lang in langs:
            write(lang)
        print(f'[{n + 1}/{len(ids)}] {name_zh} ' + ' '.join(f'{l}:{sum(len(g["items"]) for g in groups[l].values())}' for l in langs), flush=True)
    print('done')


if __name__ == '__main__':
    main()
