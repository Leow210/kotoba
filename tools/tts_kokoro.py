#!/usr/bin/env python3
"""Mandarin audio for a listening set's translations, made on the Mac with Kokoro-82M-v1.1-zh (hexgrad), so the
player can play Mandarin → Cantonese (and back). Run with the Kokoro environment:

  ~/Library/Caches/Kotoba/kokoro-venv/bin/python tools/tts_kokoro.py --set yue-particles [--voice zm_010]

Each line's `translation` (Mandarin) becomes l1/<group>/<id>.m4a and the set is marked "l1": "zh". Traditional
characters are read through a simplified copy (Kokoro's Mandarin front end knows simplified best); the text shown
doesn't change. Lines already voiced are skipped, so it can be rerun as sets grow.
"""
import argparse, json, os, re, subprocess, sys, tempfile
from pathlib import Path

import numpy as np
import soundfile as sf

DATA = Path.home() / 'Library/Application Support/Kotoba/listening'
T2S = Path(__file__).resolve().parents[1] / 'android/assets/t2s.txt'
REPO = 'hexgrad/Kokoro-82M-v1.1-zh'


def simplifier():
    cp = [ord(c) for c in T2S.read_text(encoding='utf-8')]
    table = {cp[i]: cp[i + 1] for i in range(0, len(cp) - 1, 2)}
    return lambda s: s.translate(table)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--set', required=True)
    ap.add_argument('--voice', default='')
    ap.add_argument('--lang', default='zh', choices=['zh', 'en'], help='the translation language to voice')
    ap.add_argument('--speed', type=float, default=1.0)
    args = ap.parse_args()
    from kokoro import KModel, KPipeline
    if args.lang == 'en':
        # English: Kokoro's own English model (hexgrad/Kokoro-82M), a male American voice by default.
        voice = args.voice or 'am_michael'
        pipe = KPipeline(lang_code='a', repo_id='hexgrad/Kokoro-82M')
        simp = lambda t: t
    else:
        voice = args.voice or 'zm_010'
        model = KModel(repo_id=REPO).to('cpu').eval()
        en = KPipeline(lang_code='a', repo_id=REPO, model=False)
        pipe = KPipeline(lang_code='z', repo_id=REPO, model=model, en_callable=lambda t: next(en(t)).phonemes)
        simp = simplifier()
    out = DATA / args.set
    s = json.loads((out / 'set.json').read_text())
    n = 0
    for g in s['groups']:
        for it in g['items']:
            text = (it.get('translation') or '').strip()
            if not text or it.get('l1audio'):
                continue
            # Speak just the sentence: no bracketed asides or trailing notes.
            say = simp(re.sub(r'[（(][^）)]*[）)]', '', text)).strip()
            if not say:
                continue
            rel = f"l1/{g['id']}/{it['id']}.m4a"
            dest = out / rel
            if not dest.exists():
                audio = np.concatenate([r.audio for r in pipe(say, voice=voice, speed=args.speed)])
                with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as f:
                    sf.write(f.name, audio, 24000)
                dest.parent.mkdir(parents=True, exist_ok=True)
                ok = subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', f.name, '-ac', '1', '-c:a', 'aac', '-b:a', '64k', str(dest)]).returncode == 0
                os.unlink(f.name)
                if not ok:
                    continue
            it['l1audio'] = rel
            n += 1
            if n % 25 == 0:
                (out / 'set.json').write_text(json.dumps(s, ensure_ascii=False, indent=0))
                print(n, 'voiced', flush=True)
    s['l1'] = args.lang
    (out / 'set.json').write_text(json.dumps(s, ensure_ascii=False, indent=0))
    print('done', n, 'lines voiced')


if __name__ == '__main__':
    main()
