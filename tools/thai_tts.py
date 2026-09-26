#!/usr/bin/env python3
"""Thai speech with OmniVoice (the Thai fine-tune hotdogs/omnivoice-thai on the T7, or the base k2-fsa/OmniVoice from
TTS Fire), after writing out what a model would otherwise guess at: numbers (1,250 → หนึ่งพันสองร้อยห้าสิบ), ฯลฯ
(และอื่นๆ), พ.ศ., the abbreviation mark ฯ, and ๆ (the word said twice). Spaces stay: in Thai they mark phrase breaks.

  "…/TTS Fire/.venv-omnivoice/bin/python" tools/thai_tts.py --samples [--model thai|base] [--out DIR]
"""
import argparse, json, os, re, sys
from pathlib import Path

FIRE = Path('/Users/leowoo/Documents/ChatGPT/TTS Fire')
THAI = Path('/Volumes/T7/omnivoice-thai')
ABBR = {'ฯลฯ': 'และอื่นๆ', 'พ.ศ.': 'พุทธศักราช', 'ค.ศ.': 'คริสต์ศักราช', 'กทม.': 'กรุงเทพมหานคร', 'ร.ร.': 'โรงเรียน', 'ม.': 'มหาวิทยาลัย'}


def normalize(text):
    from pythainlp.util import num_to_thaiword
    from pythainlp.tokenize import word_tokenize
    t = text
    for a, b in ABBR.items():
        t = t.replace(a, b)
    t = re.sub(r'(?<=[฀-๿])ฯ', '', t)                       # กรุงเทพฯ → กรุงเทพ (the short form, as read)
    t = re.sub(r'\d[\d,]*', lambda m: num_to_thaiword(int(m.group().replace(',', ''))), t)
    # ๆ: the word before it, again.
    if 'ๆ' in t:
        words, out = word_tokenize(t, keep_whitespace=True), []
        for w in words:
            if w.strip() == 'ๆ':
                prev = next((x for x in reversed(out) if x.strip()), '')
                out.append(prev)
            else:
                out.append(w)
        t = ''.join(out)
    return re.sub(r'\s+', ' ', t).strip()


def load(which):
    import torch
    from omnivoice import OmniVoice
    os.environ.setdefault('HF_HOME', str(FIRE / 'omnivoice-cache'))
    os.environ.setdefault('HF_HUB_OFFLINE', '1')
    src = str(THAI) if which == 'thai' else 'k2-fsa/OmniVoice'
    return OmniVoice.from_pretrained(src, device_map='mps', dtype=torch.float32, attn_implementation='eager', load_asr=False)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--samples', action='store_true')
    ap.add_argument('--model', default='thai', choices=['thai', 'base'])
    ap.add_argument('--instruct', default='male')
    ap.add_argument('--out', default=str(Path.home() / 'Library/Caches/Kotoba/tts-samples'))
    args = ap.parse_args()
    import soundfile as sf
    if args.samples:
        bench = Path(__file__).resolve().parent / 'tts-benchmarks.json'
        items = [x for x in json.loads(bench.read_text())['languages'] if x['lang'] == 'th'][0]['items']
        model = load(args.model)
        out = Path(args.out) / f'thai-omnivoice-{args.model}-clean'
        out.mkdir(parents=True, exist_ok=True)
        for it in items:
            text = normalize(it['text'])
            audio = model.generate(text=text, instruct=args.instruct)
            sf.write(out / f"{it['id']}.wav", audio[0], 24000)
            print(it['id'], text, round(len(audio[0]) / 24000, 1), 's', flush=True)


if __name__ == '__main__':
    main()
