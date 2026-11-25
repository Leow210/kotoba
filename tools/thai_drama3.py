#!/usr/bin/env python3
"""Thai male voices designed in Fish Drama 3 (no reference recording), for comparing with OmniVoice/MiniMax.

Each design: a seed + a bracketed voice description makes an audition clip; that clip then becomes the
voice reference for the ten benchmark sentences so the speaker stays the same across them.

  "/Users/leowoo/Documents/ChatGPT/TTS Fire/.venv-drama3/bin/python" tools/thai_drama3.py [--out DIR] [--names a b]
Needs the T7 mounted (model at /Volumes/T7/LLM Models/fish-audio-drama-3-4bit).
"""
import argparse, json, os, sys, time
from pathlib import Path
os.environ.setdefault('HF_HUB_OFFLINE', '1'); os.environ.setdefault('TOKENIZERS_PARALLELISM', 'false')
sys.path.insert(0, str(Path(__file__).resolve().parent))
from thai_tts import normalize  # same number/abbreviation clean-up as the OmniVoice run

AUDITION = 'ผมเข้าไปกินข้าวกับเพื่อนนะครับ วันนี้อากาศดีมาก'
VOICES = {
    'warm':   (41001, '[Thai man in his 30s, warm mid-low voice, friendly, clear and natural, standard Bangkok Thai]'),
    'deep':   (41002, '[Thai man in his 40s, deep calm baritone, steady and unhurried, clear Bangkok Thai]'),
    'young':  (41003, '[Young Thai man in his 20s, bright clear voice, energetic and casual, standard Thai]'),
    'ikebo':  (41005, '[Handsome Thai man in his late 20s, イケボ, smooth seductive low voice, slightly breathy, intimate and confident, gentle whisper-like softness]'),
    'teacher':(41004, '[Thai male language teacher, clear measured voice, slow careful pronunciation, distinct tones]'),
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--model', default='/Volumes/T7/LLM Models/fish-audio-drama-3-4bit')
    ap.add_argument('--out', default=str(Path.home() / 'Library/Caches/Kotoba/tts-samples/thai-drama3'))
    ap.add_argument('--redo', nargs='+', help='only redo these ids (e.g. th-05), keeping the shortest of --tries seeds')
    ap.add_argument('--tries', type=int, default=4)
    ap.add_argument('--names', nargs='+', choices=sorted(VOICES))
    args = ap.parse_args()
    if not Path(args.model).is_dir():
        sys.exit(f'Model not found: {args.model} (connect the T7)')
    import mlx.core as mx, numpy as np, soundfile as sf
    from mlx_audio.tts.utils import load_model
    mx.set_memory_limit(30 * 1024**3); mx.set_cache_limit(256 * 1024**2)
    bench = Path(__file__).resolve().parent / 'tts-benchmarks.json'
    items = [x for x in json.loads(bench.read_text())['languages'] if x['lang'] == 'th'][0]['items']
    model = load_model(args.model); sr = model.sample_rate

    def run(text, seed, ref=None, ref_text=None):
        for attempt in range(3):
            mx.random.seed(seed + attempt)
            kw = dict(ref_audio=ref, ref_text=ref_text) if ref else {}
            chunks, tokens = [], 0
            for r in model.generate(text=text, max_tokens=768, temperature=.7, top_p=.7, top_k=30, stream=True, **kw):
                chunks.append(np.asarray(r.audio, dtype=np.float32).reshape(-1)); tokens += r.token_count
            mx.synchronize()
            a = np.concatenate(chunks)
            if len(a) > sr * 0.8 and tokens < 768:
                return a
        return a

    for name, (seed, cue) in VOICES.items():
        if args.names and name not in args.names:
            continue
        d = Path(args.out) / name; d.mkdir(parents=True, exist_ok=True)
        t0 = time.time()
        ref = d / 'reference.wav'
        if not args.redo:
            sf.write(ref, run(f'{cue} {AUDITION}', seed), sr, subtype='PCM_16')
            print(name, 'audition done', flush=True)
        for it in items:
            if args.redo and it['id'] in args.redo:
                text = normalize(it['text']); best = None
                for k in range(args.tries):
                    a = run(text, seed + 100 * (k + 1), str(ref), AUDITION)
                    print(name, it['id'], 'try', k, round(len(a) / sr, 1), 's', flush=True)
                    if len(a) > sr * 0.8 and (best is None or len(a) < len(best)):
                        best = a
                sf.write(d / f"{it['id']}.wav", best, sr, subtype='PCM_16')
                continue
            if args.redo:
                continue
            text = normalize(it['text'])
            sf.write(d / f"{it['id']}.wav", run(text, seed, str(ref), AUDITION), sr, subtype='PCM_16')
            print(name, it['id'], text, flush=True)
        print(name, round(time.time() - t0), 's', flush=True)
        mx.clear_cache()


if __name__ == '__main__':
    main()
