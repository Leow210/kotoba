#!/usr/bin/env python3
"""The Thai script trainer's data and audio: tools/thai/script.json → listening/thai-script/ (script.json with audio
paths, audio/*.m4a). Letter names (กอ ไก่), example words, vowel examples, tone-mark names and tone syllables are
voiced by OmniVoice (base model, TTS Fire's copy) after tools/thai_tts.py's text clean-up. Reruns skip clips made.

  "/Users/leowoo/Documents/ChatGPT/TTS Fire/.venv-omnivoice/bin/python" tools/thai/build_script.py [--limit N]
"""
import argparse, hashlib, json, os, subprocess, sys, tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import thai_tts  # noqa: E402

OUT = Path.home() / 'Library/Application Support/Kotoba/listening/thai-script'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--limit', type=int, default=0)
    ap.add_argument('--instruct', default='male')
    args = ap.parse_args()
    d = json.loads((HERE / 'script.json').read_text())
    OUT.mkdir(parents=True, exist_ok=True)
    jobs = {}  # text → relative path

    def clip(text):
        if not text:
            return ''
        rel = 'audio/' + hashlib.sha1(text.encode()).hexdigest()[:12] + '.m4a'
        jobs[text] = rel
        return rel
    for u in d['units']:
        for it in u['items']:
            if it.get('name'):
                it['nameAudio'] = clip(it['name'])
            it['wordAudio'] = clip(it.get('word', ''))
        for m in u.get('marks', []):
            m['nameAudio'] = clip(m['name'])
    (OUT / 'script.json').write_text(json.dumps(d, ensure_ascii=False, indent=1))
    todo = [(t, r) for t, r in jobs.items() if not (OUT / r).exists()]
    if args.limit:
        todo = todo[:args.limit]
    print(len(jobs), 'clips,', len(todo), 'to make', flush=True)
    if not todo:
        return
    import soundfile as sf
    model = thai_tts.load('base')
    for n, (text, rel) in enumerate(todo):
        audio = model.generate(text=thai_tts.normalize(text), instruct=args.instruct)
        with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as f:
            sf.write(f.name, audio[0], 24000)
        (OUT / rel).parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', f.name, '-ac', '1', '-c:a', 'aac', '-b:a', '64k', str(OUT / rel)])
        os.unlink(f.name)
        print(n + 1, text, flush=True)


if __name__ == '__main__':
    main()
