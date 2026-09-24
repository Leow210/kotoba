#!/usr/bin/env python3
"""Live subtitles for a video playing in the browser (Viki shows without subtitles in the language spoken).

Kotoba for Mac captures the browser's audio and streams it here on stdin; each spoken line comes back on stdout as
one JSON object {"start": ms, "end": ms, "text": "..."}, the times being wall-clock milliseconds of the audio (the
core maps them to the video's own time). Runs in the Subtitle_Generator environment on the T7, with its models:
Silero VAD finds where lines end, Qwen3-ASR (1.7B, 8-bit, MLX) transcribes them.

stdin frames: uint32 sample count, float64 wall-clock ms of the first sample, then the samples (float32, 16 kHz mono).
"""
import argparse, json, os, re, struct, sys, time

os.environ.setdefault('HF_HOME', '/Volumes/T7/subtitle-generator-qwen/huggingface')
os.environ.setdefault('HF_HUB_OFFLINE', '1')

import numpy as np  # noqa: E402
from mlx_audio.stt import load  # noqa: E402
from mlx_audio.vad import load as load_vad  # noqa: E402

SR = 16000
LANGS = {'ko': 'Korean', 'ja': 'Japanese', 'zh': 'Chinese', 'yue': 'Cantonese', 'th': 'Thai', 'ru': 'Russian', 'en': 'English'}


def say(obj):
    sys.stdout.write(json.dumps(obj, ensure_ascii=False) + '\n')
    sys.stdout.flush()


def junk(text):
    t = text.strip()
    if not t or re.fullmatch(r'[\W_]+', t):
        return True
    # A single syllable repeated (음음음, 아아아) or runaway repetition.
    if re.search(r'(.{1,3})\1{5,}', t):
        return True
    return False


def cues(start, end, text):
    """A long line becomes a few subtitle cues, split at sentence ends in proportion to their length."""
    parts = [p.strip() for p in re.split(r'(?<=[.!?。！？…])\s+', text) if p.strip()]
    if len(parts) < 2 or end - start < 6000:
        return [(start, end, text.strip())]
    total = sum(len(p) for p in parts)
    out, t = [], start
    for p in parts:
        d = (end - start) * len(p) / total
        out.append((t, t + d, p))
        t += d
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--language', default='ko')
    ap.add_argument('--model', default='mlx-community/Qwen3-ASR-1.7B-8bit')
    ap.add_argument('--vad-threshold', type=float, default=0.35)
    ap.add_argument('--silence-ms', type=int, default=350, help='a pause this long ends a line')
    ap.add_argument('--max-line', type=float, default=12.0, help='seconds before a line is cut anyway')
    args = ap.parse_args()
    language = LANGS.get(args.language, args.language)

    t0 = time.time()
    vad = load_vad('mlx-community/silero-vad')
    asr = load(args.model)
    asr.generate(np.zeros(SR, dtype=np.float32), language=language, temperature=0.0)  # warm up
    say({'ready': True, 'language': language, 'load_s': round(time.time() - t0, 1)})

    buf = np.zeros(0, dtype=np.float32)
    wall0 = None          # wall-clock ms of buf[0]
    since_vad = 0         # samples received since the last VAD pass
    stdin = sys.stdin.buffer

    def transcribe(a, b):
        """Samples a..b of buf as one line."""
        nonlocal buf
        piece = buf[max(0, a - SR // 10):min(len(buf), b + SR // 10)]
        if len(piece) < SR // 3:
            return
        r = asr.generate(piece, language=language, temperature=0.0)
        text = getattr(r, 'text', str(r)).strip()
        if junk(text):
            return
        s, e = wall0 + a * 1000 / SR, wall0 + b * 1000 / SR
        for cs, ce, ct in cues(s, e, text):
            say({'start': round(cs), 'end': round(ce), 'text': ct})

    def flush_all():
        """The stream stopped (a pause, a seek): finish what was being said and start afresh."""
        nonlocal buf, wall0
        if len(buf) > SR // 2:
            for sp in spans(buf):
                transcribe(*sp)
        buf = np.zeros(0, dtype=np.float32)
        wall0 = None

    def spans(a):
        found = vad.get_speech_timestamps(a, sample_rate=SR, threshold=args.vad_threshold, min_speech_duration_ms=250,
                                          min_silence_duration_ms=args.silence_ms, speech_pad_ms=120, return_seconds=False)
        return [(int(x['start']), int(x['end'])) for x in found]

    while True:
        head = stdin.read(12)
        if len(head) < 12:
            break
        n, wall = struct.unpack('<Id', head)
        raw = stdin.read(n * 4)
        if len(raw) < n * 4:
            break
        chunk = np.frombuffer(raw, dtype=np.float32)
        # A gap in the audio (paused or seeked): what came before is finished.
        if wall0 is not None and abs(wall - (wall0 + len(buf) * 1000 / SR)) > 700:
            flush_all()
        if wall0 is None:
            wall0 = wall
        buf = np.concatenate([buf, chunk])
        since_vad += n
        if since_vad < SR * 0.4 or len(buf) < SR:
            continue
        since_vad = 0
        found = spans(buf)
        done_until = 0
        tail = len(buf) - int(SR * args.silence_ms / 1000)
        for a, b in found:
            if b <= tail:                      # followed by enough silence: the line is over
                transcribe(a, b)
                done_until = b
            elif b - a > SR * args.max_line:  # talking on and on: cut it here
                transcribe(a, int(a + SR * args.max_line))
                done_until = int(a + SR * args.max_line)
        # Keep only what's still undecided (plus a little before it), so the buffer stays short.
        keep_from = done_until if done_until else max(0, len(buf) - SR * 20)
        if not found and len(buf) > SR * 5:
            keep_from = len(buf) - SR       # silence or music: keep the last second
        if keep_from > 0:
            buf = buf[keep_from:]
            wall0 += keep_from * 1000 / SR


if __name__ == '__main__':
    main()
