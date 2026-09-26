#!/usr/bin/env python3
"""Checks a listening set's synthesized audio by ear, as far as a machine can: each clip goes through Whisper
(large-v3-turbo, TTS Fire's cached copy) and is compared with the line's text; lines that don't match well are listed,
worst first, for a listen or a remake (delete the clip and rerun the set's builder).

  "/Users/leowoo/Documents/ChatGPT/TTS Fire/.venv-omnivoice/bin/python" tools/check_tts.py th-course [--below 0.8]

Digits in the transcript (25 for двадцать пять) count as a mismatch, so glance at what's listed.
"""
import argparse, difflib, json, os, re, unicodedata
from pathlib import Path

DATA = Path.home() / 'Library/Application Support/Kotoba/listening'
LANG = {'th': 'thai', 'ru': 'russian', 'yue': 'cantonese', 'zh': 'chinese', 'ja': 'japanese', 'en': 'english'}


def norm(s):
    s = unicodedata.normalize('NFD', s.lower()).replace('́', '').replace('̀', '')
    s = unicodedata.normalize('NFC', s).replace('ё', 'е')
    return re.sub(r'[\W_]+', '', s)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('set')
    ap.add_argument('--below', type=float, default=0.8)
    ap.add_argument('--ids', default='', help='only these line ids, comma-separated')
    args = ap.parse_args()
    os.environ.setdefault('HF_HOME', '/Users/leowoo/Documents/ChatGPT/TTS Fire/omnivoice-cache')
    os.environ.setdefault('HF_HUB_OFFLINE', '1')
    from transformers import pipeline
    asr = pipeline('automatic-speech-recognition', 'openai/whisper-large-v3-turbo', device='mps')
    out = DATA / args.set
    s = json.loads((out / 'set.json').read_text())
    lang = LANG.get(s.get('lang'), None)
    bad = []
    items = [it for g in s['groups'] for it in g['items'] if it.get('audio') and (out / it['audio']).exists()
             and (not args.ids or it['id'] in args.ids.split(','))]
    for n, it in enumerate(items, 1):
        heard = asr(str(out / it['audio']), generate_kwargs={'language': lang} if lang else {})['text'].strip()
        score = difflib.SequenceMatcher(None, norm(it['text']), norm(heard)).ratio()
        if score < args.below:
            bad.append((score, it['id'], it['text'], heard))
        if n % 50 == 0:
            print(n, 'checked', flush=True)
    for score, iid, text, heard in sorted(bad):
        print(f'{score:.2f} {iid}\n  text:  {text}\n  heard: {heard}')
    print(len(items), 'clips,', len(bad), 'below', args.below)


if __name__ == '__main__':
    main()
