#!/usr/bin/env python3
"""Re-voice the Thai course (listening/th-course) with Fish Drama 3: the 'warm' male design from tools/thai_drama3.py
and a matching woman for the few female lines. set.json is not changed except each clip's duration; the old audio is
moved once to audio-omnivoice-backup/. A take far longer than the old OmniVoice clip (Drama 3 sometimes rambles) is
retried with other seeds; the shortest valid take is kept. Reruns skip lines already done (progress in d3-done.json).

  "/Users/leowoo/Documents/ChatGPT/TTS Fire/.venv-drama3/bin/python" tools/thai/build_course_d3.py [--limit N]
"""
import argparse, json, os, shutil, subprocess, sys, tempfile
from pathlib import Path
os.environ.setdefault('HF_HUB_OFFLINE', '1'); os.environ.setdefault('TOKENIZERS_PARALLELISM', 'false')
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import thai_drama3, thai_tts

OUT = Path.home() / 'Library/Application Support/Kotoba/listening/th-course'
WOMAN = (42001, '[Thai woman in her late 20s, warm clear friendly voice, natural standard Bangkok Thai]')
AUDITION_F = 'สวัสดีค่ะ ดิฉันชื่อฝน ยินดีที่ได้รู้จักค่ะ วันนี้อากาศดีมาก'


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('--limit', type=int)
    ap.add_argument('--model', default='/Volumes/T7/LLM Models/fish-audio-drama-3-4bit')
    args = ap.parse_args()
    import mlx.core as mx, numpy as np, soundfile as sf
    from mlx_audio.tts.utils import load_model
    mx.set_memory_limit(30 * 1024**3); mx.set_cache_limit(256 * 1024**2)
    setj = json.loads((OUT / 'set.json').read_text())
    items = [i for g in setj['groups'] for i in g['items']]
    backup = OUT / 'audio-omnivoice-backup'
    if not backup.exists():
        shutil.copytree(OUT / 'audio', backup)
    if not (OUT / 'set-omnivoice-backup.json').exists():
        shutil.copy(OUT / 'set.json', OUT / 'set-omnivoice-backup.json')
    doneP = OUT / 'd3-done.json'
    done = set(json.loads(doneP.read_text())) if doneP.exists() else set()
    model = load_model(args.model); sr = model.sample_rate

    def run(text, seed, ref=None, ref_text=None):
        for attempt in range(3):
            mx.random.seed(seed + attempt)
            kw = dict(ref_audio=ref, ref_text=ref_text) if ref else {}
            chunks, tokens = [], 0
            for r in model.generate(text=text, max_tokens=768, temperature=.7, top_p=.7, top_k=30, stream=True, **kw):
                chunks.append(np.asarray(r.audio, dtype=np.float32).reshape(-1)); tokens += r.token_count
            mx.synchronize(); a = np.concatenate(chunks)
            if len(a) > sr * 0.5 and tokens < 768:
                return a
        return a

    mseed, mcue = thai_drama3.VOICES['warm']
    refs = {'m': (Path.home() / 'Library/Caches/Kotoba/tts-samples/thai-drama3/warm/reference.wav', thai_drama3.AUDITION)}
    fref = OUT / 'voice-f-d3.wav'
    if not fref.exists():
        sf.write(fref, run(f'{WOMAN[1]} {AUDITION_F}', WOMAN[0]), sr, subtype='PCM_16')
    refs['f'] = (fref, AUDITION_F)
    todo = [i for i in items if i['audio'] not in done][:args.limit]
    print(len(items), 'lines,', len(todo), 'to do', flush=True)
    for n, it in enumerate(todo):
        ref, rtext = refs[it['voice']]
        text = thai_tts.normalize(it['text'])
        limit = it['dur'] * 1.8 + 1.0   # old OmniVoice length as the yardstick
        best = None
        for k in range(4):
            a = run(text, mseed + 1000 * k + n, str(ref), rtext)
            if best is None or len(a) < len(best):
                best = a
            if len(a) / sr <= limit:
                break
        with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as f:
            sf.write(f.name, best, sr, subtype='PCM_16')
        dest = OUT / it['audio']
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', f.name, '-ac', '1', '-c:a', 'aac', '-b:a', '64k', str(dest)])
        os.unlink(f.name)
        it['dur'] = round(len(best) / sr, 2)
        done.add(it['audio'])
        print(n + 1, it['text'], it['dur'], 's', '(long)' if it['dur'] > limit else '', flush=True)
        if (n + 1) % 10 == 0:
            doneP.write_text(json.dumps(sorted(done))); (OUT / 'set.json').write_text(json.dumps(setj, ensure_ascii=False, indent=0))
        mx.clear_cache() if (n + 1) % 25 == 0 else None
    doneP.write_text(json.dumps(sorted(done))); (OUT / 'set.json').write_text(json.dumps(setj, ensure_ascii=False, indent=0))
    print('done')


if __name__ == '__main__':
    main()
