#!/usr/bin/env python3
"""The Russian course (A2 → B1, built around the cases) as a listening set: tools/russian/course.txt → listening/ru-course/.

Each unit starts "## id | level | name | grammar note"; each line after it is one sentence, "TAG sentence = English",
with a case label in brackets straight after a word (в Москве́[P], друзья́м[Dpl]). The labels show above the words, as
jyutping does over Cantonese (the player's "Cases" option). TAG is E, C, S, W or F (see course.txt), with a trailing f
for a woman speaking. Russian audio: OmniVoice base (TTS Fire's copy), every line in one voice cloned from a first clip;
English: tools/tts_kokoro.py --set ru-course --lang en.

  "/Users/leowoo/Documents/ChatGPT/TTS Fire/.venv-omnivoice/bin/python" tools/russian/build_course.py [--text-only]

Reruns keep clips already made (named by a hash of the text and the voice).
"""
import argparse, hashlib, json, os, re, subprocess, sys, tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

OUT = Path.home() / 'Library/Application Support/Kotoba/listening/ru-course'
SCENE = {'E': 'Everyday', 'C': 'Around town', 'S': 'Shops and food', 'W': 'Work and study', 'F': 'Friends and feelings'}
LEVEL = {'A2': 'A2 · Elementary', 'B1': 'B1 · Intermediate'}
CASE = {'N': 'nom', 'A': 'acc', 'G': 'gen', 'D': 'dat', 'I': 'ins', 'P': 'prep'}
REF = {'m': 'Приве́т! Меня́ зову́т Макси́м. Я живу́ в Москве́ и о́чень люблю́ свой го́род.',
       'f': 'Приве́т! Меня́ зову́т А́нна. Я живу́ в Москве́ и о́чень люблю́ свой го́род.'}
TAGGED = re.compile(r'^(.*?)\[([NAGDIP])(pl)?\](.*)$')


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
        sentence, english = rest.rsplit(' = ', 1)
        words, plain = [], []
        for tok in sentence.split():
            m = TAGGED.match(tok)
            if '[' in tok and not m:
                raise SystemExit(f'{path.name}:{n}: can\'t read "{tok}"')
            w, label = (m.group(1) + m.group(4), CASE[m.group(2)] + (' pl' if m.group(3) else '')) if m else (tok, '')
            if words:
                words.append({'w': ' ', 'j': '', 'g': ''})
            words.append({'w': w, 'j': label, 'g': ''})
            plain.append(w)
        female = tag.endswith('f')
        g['items'].append({'id': f"{g['id']}-{len(g['items']) + 1}", 'title': SCENE[tag[0]], 'titleEn': '', 'text': ' '.join(plain),
                           'roman': '', 'words': words, 'translation': english, 'audio': '', 'dur': 0,
                           'note': 'A woman speaking' if female else '', 'voice': 'f' if female else 'm'})
    return groups


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--text-only', action='store_true', help='write set.json without making audio')
    args = ap.parse_args()
    groups = parse(HERE / 'course.txt')
    OUT.mkdir(parents=True, exist_ok=True)
    old = {}
    if (OUT / 'set.json').exists():   # keep English audio already made for unchanged translations
        for g in json.loads((OUT / 'set.json').read_text())['groups']:
            for it in g['items']:
                if it.get('l1audio'):
                    old[it['translation']] = it['l1audio']
    items = [it for g in groups for it in g['items']]
    for it in items:
        it['audio'] = 'audio/' + hashlib.sha1((it['voice'] + it['text']).encode()).hexdigest()[:12] + '.m4a'
        if it['translation'] in old and (OUT / old[it['translation']]).exists():
            it['l1audio'] = old[it['translation']]
        if (OUT / it['audio']).exists():
            out = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', str(OUT / it['audio'])], capture_output=True, text=True).stdout
            it['dur'] = round(float(out.strip() or 0), 2)
    s = {'id': OUT.name, 'title': 'Русский · Cases in everyday Russian', 'subtitle': 'A2 to B1: every case, in sentences you would actually say, each word labelled with its case',
         'lang': 'ru', 'translationLang': 'en', 'l1': 'en', 'groupLabel': 'Units', 'roman': 'cases', 'groups': groups}

    def save():
        (OUT / 'set.json').write_text(json.dumps(s, ensure_ascii=False, indent=0))
    todo = [it for it in items if not (OUT / it['audio']).exists()]
    print(len(groups), 'units,', len(items), 'lines,', len(todo), 'to voice', flush=True)
    save()
    if args.text_only or not todo:
        return
    import soundfile as sf
    import thai_tts   # OmniVoice loading (TTS Fire's copy)
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
        audio = model.generate(text=it['text'], **kw)
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
