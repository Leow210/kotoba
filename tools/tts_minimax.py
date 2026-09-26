#!/usr/bin/env python3
"""Voices listening-set lines with MiniMax text-to-speech (Cantonese: language_boost "Chinese,Yue").

  python3 tools/tts_minimax.py --voices                       # the system voices (Cantonese ones marked)
  python3 tools/tts_minimax.py --sample "你食咗飯未呀？" [--voice V …] # a clip per voice, to compare by ear
  python3 tools/tts_minimax.py --set yue-life1 [--voice V]    # every line of the set without a recording

The API key comes from $MINIMAX_API_KEY or ~/Library/Application Support/Kotoba/minimax.json
({"key": "...", "host": "api.minimax.io", "group_id": ""}; host api.minimaxi.com with a group id for mainland accounts).
It never goes into the repository. Clips are converted to AAC like the other sets; each line notes its voice.
"""
import argparse, json, os, subprocess, sys, tempfile, time, urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_genshin_voice as gv  # noqa: E402

CONFIG = gv.DATA.parent / 'minimax.json'
DEFAULT_VOICE = 'Cantonese_ProfessionalHost（F)'


def config():
    c = json.loads(CONFIG.read_text()) if CONFIG.exists() else {}
    key = os.environ.get('MINIMAX_API_KEY') or c.get('key')
    if not key:
        sys.exit(f'No MiniMax API key: set MINIMAX_API_KEY or put {{"key": "..."}} in {CONFIG}')
    return key, c.get('host', 'api.minimax.io'), c.get('group_id', '')


def post(path, body):
    key, host, group = config()
    url = f'https://{host}{path}' + (f'?GroupId={group}' if group else '')
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={'Authorization': f'Bearer {key}', 'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=120, context=gv.CTX) as r:
        d = json.loads(r.read())
    base = d.get('base_resp') or {}
    if base.get('status_code', 0) != 0:
        raise RuntimeError(f"MiniMax: {base.get('status_msg')} ({base.get('status_code')})")
    return d


def speak(text, voice, model, speed=1.0):
    """MP3 bytes for the text."""
    d = post('/v1/t2a_v2', {'model': model, 'text': text, 'stream': False, 'language_boost': 'Chinese,Yue', 'output_format': 'hex',
                            'voice_setting': {'voice_id': voice, 'speed': speed, 'vol': 1, 'pitch': 0},
                            'audio_setting': {'sample_rate': 32000, 'bitrate': 128000, 'format': 'mp3', 'channel': 1}})
    return bytes.fromhex(d['data']['audio'])


def to_m4a(mp3, dest):
    with tempfile.NamedTemporaryFile(suffix='.mp3', delete=False) as f:
        f.write(mp3)
    dest.parent.mkdir(parents=True, exist_ok=True)
    ok = subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', f.name, '-ac', '1', '-c:a', 'aac', '-b:a', '64k', str(dest)]).returncode == 0
    os.unlink(f.name)
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--voices', action='store_true')
    ap.add_argument('--sample')
    ap.add_argument('--set')
    ap.add_argument('--voice', action='append')
    ap.add_argument('--model', default='speech-2.8-hd')
    ap.add_argument('--speed', type=float, default=1.0)
    args = ap.parse_args()
    if args.voices:
        d = post('/v1/get_voice', {'voice_type': 'system'})
        for v in d.get('system_voice') or []:
            vid = v.get('voice_id', '')
            print(('★ ' if 'antonese' in vid or '粤' in vid or '粵' in vid else '  ') + vid, '·', v.get('voice_name', ''), '·', ' '.join(v.get('description') or []))
        return
    voices = args.voice or [DEFAULT_VOICE]
    if args.sample:
        out = gv.CACHE.parent / 'tts-samples'
        for v in voices:
            dest = out / (v.replace('/', '_') + '.m4a')
            to_m4a(speak(args.sample, v, args.model, args.speed), dest)
            print(dest)
        return
    if args.set:
        out = gv.DATA / args.set
        s = json.loads((out / 'set.json').read_text())
        todo = json.loads((out / 'tts.json').read_text()) if (out / 'tts.json').exists() else []
        by_id = {it['id']: it for g in s['groups'] for it in g['items']}
        done = 0
        for t in todo:
            it = by_id.get(t['id'])
            if not it or it.get('audio'):
                continue
            dest = out / t['audio']
            if not dest.exists():
                for attempt in range(3):
                    try:
                        if to_m4a(speak(t['text'], voices[0], args.model, args.speed), dest):
                            break
                    except Exception as e:
                        print('  retry', t['id'], e, flush=True)
                        time.sleep(5 * (attempt + 1))
            if dest.exists():
                it['audio'], it['dur'], it['note'] = t['audio'], gv.duration(dest), f'Voice: MiniMax ({voices[0]})'
                done += 1
                if done % 20 == 0:
                    (out / 'set.json').write_text(json.dumps(s, ensure_ascii=False, indent=0))
                    print(f'{done} voiced', flush=True)
        (out / 'set.json').write_text(json.dumps(s, ensure_ascii=False, indent=0))
        print('done', done, 'lines voiced')


if __name__ == '__main__':
    main()
