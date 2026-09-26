#!/usr/bin/env python3
"""The Thai course (Beginner → B1) as a listening set: tools/thai/course_*.txt → listening/th-course/.

Each unit starts "## id | level | name | grammar note"; each line after it is one sentence:
  TAG thai~roman~gloss thai~roman~gloss / … = English
TAG is T (travel), B (BL), S (student) or E (everyday), with a trailing f for a woman speaking; "/" is a phrase break,
a space in the Thai. Thai audio: OmniVoice base (TTS Fire's copy), after tools/thai_tts.py's clean-up, every line in
one voice cloned from a first clip (one male, one female voice). English: tools/tts_kokoro.py --set th-course --lang en.

  "/Users/leowoo/Documents/ChatGPT/TTS Fire/.venv-omnivoice/bin/python" tools/thai/build_course.py [--text-only]

Reruns keep clips already made (named by a hash of the Thai and the voice), so lines can be edited and added.
"""
import argparse, hashlib, json, os, re, subprocess, sys, tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import thai_tts  # noqa: E402

OUT = Path.home() / 'Library/Application Support/Kotoba/listening/th-course'
SCENE = {'T': 'Travelling in Thailand', 'B': 'BL Thai', 'S': 'Student Thai', 'E': 'Everyday life'}
LEVEL = {'A1': 'A1 · Beginner', 'A2': 'A2 · Elementary', 'B1': 'B1 · Intermediate'}
# The voices every line is cloned from: a short sentence made once with OmniVoice's own male or female voice.
REF = {'m': 'สวัสดีครับ ผมชื่อเล็ก ยินดีที่ได้รู้จักครับ วันนี้อากาศดีมากเลย', 'f': 'สวัสดีค่ะ ดิฉันชื่อฝน ยินดีที่ได้รู้จักค่ะ วันนี้อากาศดีมากเลย'}
TOKEN = re.compile(r'(/)|([^\s~/]+)~([^~]+)~(.*?)(?=\s+(?:/|[^\s~]+~)|\s*$)')


def parse(path):
    groups, g = [], None
    for n, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
        line = line.strip()
        if not line or line.startswith('# '):
            continue
        if line.startswith('## '):
            uid, level, name, note = [x.strip() for x in line[3:].split('|', 3)]
            g = {'id': uid, 'name': name, 'nameEn': '', 'icon': '', 'section': LEVEL[level], 'sectionEn': '', 'order': 0,
                 'note': note, 'items': []}
            groups.append(g)
            continue
        tag, rest = line.split(' ', 1)
        thai_part, english = rest.rsplit(' = ', 1)
        words, text, roman, pos = [], '', [], 0
        for m in TOKEN.finditer(thai_part):
            if thai_part[pos:m.start()].strip():
                raise SystemExit(f'{path.name}:{n}: can\'t read "{thai_part[pos:m.start()].strip()}"')
            pos = m.end()
            if m.group(1):
                text += ' '
                words.append({'w': ' ', 'j': '', 'g': ''})
                continue
            text += m.group(2)
            roman.append(m.group(3))
            words.append({'w': m.group(2), 'j': m.group(3), 'g': m.group(4).strip()})
        if thai_part[pos:].strip() or not words:
            raise SystemExit(f'{path.name}:{n}: can\'t read the line')
        female = tag.endswith('f')
        g['items'].append({'id': f"{g['id']}-{len(g['items']) + 1}", 'title': SCENE[tag[0]], 'titleEn': '', 'text': text,
                           'roman': ' '.join(roman), 'words': words, 'translation': english, 'audio': '', 'dur': 0,
                           'note': 'A woman speaking' if female else '', 'voice': 'f' if female else 'm'})
    return groups


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--text-only', action='store_true', help='write set.json without making audio')
    args = ap.parse_args()
    groups = [g for p in sorted(HERE.glob('course_*.txt')) for g in parse(p)]
    OUT.mkdir(parents=True, exist_ok=True)
    old = {}
    if (OUT / 'set.json').exists():   # keep English audio already made for unchanged translations
        for g in json.loads((OUT / 'set.json').read_text())['groups']:
            for it in g['items']:
                if it.get('l1audio'):
                    old[it['translation']] = it['l1audio']
    for g in groups:
        for it in g['items']:
            it['audio'] = 'audio/' + hashlib.sha1((it['voice'] + it['text']).encode()).hexdigest()[:12] + '.m4a'
            if it['translation'] in old and (OUT / old[it['translation']]).exists():
                it['l1audio'] = old[it['translation']]
    s = {'id': OUT.name, 'title': 'ไทย · Thai course', 'subtitle': 'Beginner to B1: grammar step by step, for travel, BL, student life and every day',
         'lang': 'th', 'translationLang': 'en', 'l1': 'en', 'groupLabel': 'Units', 'roman': 'paiboon', 'groups': groups}

    def save():
        (OUT / 'set.json').write_text(json.dumps(s, ensure_ascii=False, indent=0))
    items = [it for g in groups for it in g['items']]
    for it in items:   # clips from earlier runs: their length
        if (OUT / it['audio']).exists():
            out = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', str(OUT / it['audio'])], capture_output=True, text=True).stdout
            it['dur'] = round(float(out.strip() or 0), 2)
    todo = [it for it in items if not (OUT / it['audio']).exists()]
    print(len(groups), 'units,', len(items), 'lines,', len(todo), 'to voice', flush=True)
    save()
    if args.text_only or not todo:
        return
    import soundfile as sf
    model = thai_tts.load('base')
    refs = {}
    for v in ('m', 'f'):
        ref = OUT / f'voice-{v}.wav'
        if not ref.exists():
            audio = model.generate(text=REF[v], instruct='male' if v == 'm' else 'female')
            sf.write(ref, audio[0], 24000)
        refs[v] = model.create_voice_clone_prompt(ref_audio=str(ref), ref_text=REF[v]) if hasattr(model, 'create_voice_clone_prompt') else None
    for n, it in enumerate(todo):
        v = it['voice']
        kw = {'voice_clone_prompt': refs[v]} if refs[v] is not None else {'ref_audio': str(OUT / f'voice-{v}.wav'), 'ref_text': REF[v]}
        audio = model.generate(text=thai_tts.normalize(it['text']), **kw)
        with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as f:
            sf.write(f.name, audio[0], 24000)
        dest = OUT / it['audio']
        dest.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', f.name, '-ac', '1', '-c:a', 'aac', '-b:a', '64k', str(dest)])
        os.unlink(f.name)
        it['dur'] = round(len(audio[0]) / 24000, 2)
        print(n + 1, it['text'], it['dur'], 's', flush=True)
        if (n + 1) % 20 == 0:
            save()
    save()
    print('done')


if __name__ == '__main__':
    main()
