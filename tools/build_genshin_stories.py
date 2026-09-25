#!/usr/bin/env python3
"""Genshin Impact character stories (角色详细, 角色故事1–5, the Vision, a special item…) in Chinese, Japanese, Korean
and English, from gi.yatta.moe, beside a listening set so each character's page can show them.

  python3 tools/build_genshin_stories.py [--set genshin-zh]

Writes <set>/stories.json: {"langs": [...], "groups": {avatarId: {"names": {lang: name}, "sections": {lang: [{title,
text, note}]}}}}. Shares build_genshin_voice's cache and politeness (one request at a time, 2.5 s apart).
"""
import argparse, json, re, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_genshin_voice as voice  # noqa: E402

LANGS = [('zh', 'chs'), ('ja', 'jp'), ('ko', 'kr'), ('en', 'en')]


def clean(text, nickname):
    t = voice.clean(text, 'M', nickname)
    t = re.sub(r'\{RUBY#\[[A-Z]\][^}]*\}', '', t)  # Japanese ruby markup: the reading is dropped, the kanji stay
    return t


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--set', default='genshin-zh')
    args = ap.parse_args()
    out = voice.DATA / args.set
    out.mkdir(parents=True, exist_ok=True)
    nick = {'zh': '旅行者', 'ja': '旅人', 'ko': '여행자', 'en': 'Traveler'}
    avatars = {lang: voice.api(f'{code}/avatar')['data']['items'] for lang, code in LANGS}
    ids = sorted((k for k in avatars['zh'] if k.isdigit()), key=lambda k: (avatars['zh'][k].get('release') or 0, k))
    groups = {}
    if (out / 'stories.json').exists():
        groups = json.loads((out / 'stories.json').read_text()).get('groups', {})
    for n, aid in enumerate(ids):
        g = {'names': {}, 'sections': {}}
        for lang, code in LANGS:
            g['names'][lang] = avatars[lang].get(aid, {}).get('name', '')
            story = ((voice.api(f'{code}/avatarFetter/{aid}') or {}).get('data') or {}).get('story') or {}
            secs = []
            for s in story.values():
                text = clean(s.get('text'), nick[lang])
                if not text:
                    continue
                secs.append({'title': clean(s.get('title'), nick[lang]), 'text': text, 'note': clean(s.get('tips'), nick[lang])})
            if secs:
                g['sections'][lang] = secs
        if g['sections']:
            groups[aid] = g
        tmp = out / 'stories.json.tmp'
        tmp.write_text(json.dumps({'langs': [l for l, _ in LANGS], 'groups': groups}, ensure_ascii=False))
        tmp.replace(out / 'stories.json')
        print(f'[{n + 1}/{len(ids)}] {g["names"]["zh"]} {sum(len(v) for v in g["sections"].values())} sections', flush=True)
    print('done')


if __name__ == '__main__':
    main()
