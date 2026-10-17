#!/usr/bin/env python3
"""Japanese audio for the Thai course's translations with OmniVoice (base model, TTS Fire's copy), one male voice
cloned from a first clip. Writes l1/<unit>/<id>.m4a and marks the set "l1": "ja". Reruns skip clips already made.

  "/Users/leowoo/Documents/ChatGPT/TTS Fire/.venv-omnivoice/bin/python" tools/thai/voice_ja.py [--limit N]
"""
import argparse, json, os, subprocess, sys, tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import thai_tts  # noqa: E402
import soundfile as sf  # noqa: E402

OUT = Path.home() / 'Library/Application Support/Kotoba/listening/th-course'
REF = 'こんにちは、私の名前はケンです。お会いできてうれしいです。今日はとてもいい天気ですね。'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--limit', type=int, default=0)
    args = ap.parse_args()
    s = json.loads((OUT / 'set.json').read_text())
    model = thai_tts.load('base')
    ref = OUT / 'voice-ja.wav'
    if not ref.exists():
        sf.write(ref, model.generate(text=REF, instruct='male')[0], 24000)
    clone = model.create_voice_clone_prompt(ref_audio=str(ref), ref_text=REF)
    n = 0
    for g in s['groups']:
        for it in g['items']:
            rel = f"l1/{g['id']}/{it['id']}.m4a"
            dest = OUT / rel
            if not dest.exists():
                if args.limit and n >= args.limit:
                    continue
                audio = model.generate(text=it['translation'], voice_clone_prompt=clone)
                with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as f:
                    sf.write(f.name, audio[0], 24000)
                dest.parent.mkdir(parents=True, exist_ok=True)
                subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', f.name, '-ac', '1', '-c:a', 'aac', '-b:a', '64k', str(dest)])
                os.unlink(f.name)
                n += 1
                if n % 25 == 0:
                    print(n, 'voiced', flush=True)
            it['l1audio'] = rel
    s['l1'] = 'ja'
    (OUT / 'set.json').write_text(json.dumps(s, ensure_ascii=False, indent=0))
    print('done', n)


if __name__ == '__main__':
    main()
