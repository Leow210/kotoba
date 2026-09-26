#!/usr/bin/env python3
"""A listening set from an Anki deck of Pimsleur Thai flashcards (English front; Thai, romanization and a recording on
the back): listening/th-pimsleur, the deck's own native recordings as the Thai audio, English as the known language.

  python3 tools/thai/build_pimsleur.py ~/Downloads/Pimsleur_Thai.apkg

Personal study use: the set stays on your devices and out of the repository. English audio: tools/tts_kokoro.py --lang en.
"""
import html, json, re, sqlite3, subprocess, sys, tempfile, zipfile
from pathlib import Path

OUT = Path.home() / 'Library/Application Support/Kotoba/listening/th-pimsleur'


def plain(s):
    return html.unescape(re.sub(r'<[^>]+>', '', s)).strip()


def main(apkg):
    z = zipfile.ZipFile(apkg)
    media = json.loads(z.read('media'))                  # number → file name
    by_name = {v: k for k, v in media.items()}
    with tempfile.NamedTemporaryFile(suffix='.anki2', delete=False) as f:
        f.write(z.read('collection.anki21' if 'collection.anki21' in z.namelist() else 'collection.anki2'))
    db = sqlite3.connect(f.name)
    rows = db.execute('SELECT id, flds FROM notes ORDER BY id').fetchall()
    OUT.mkdir(parents=True, exist_ok=True)
    items = []
    for n, (nid, flds) in enumerate(rows):
        front, back, audio = (flds.split('\x1f') + ['', '', ''])[:3]
        lines = [plain(x) for x in re.split(r'<br\s*/?>', back) if plain(x)]
        if not lines:
            continue
        thai, rom = lines[0], (lines[1] if len(lines) > 1 else '')
        # Male speech only: the few female forms (ค่ะ, ดิฉัน, cards marked (f)) are left out.
        if '(f)' in plain(front) or re.search(r'ค่ะ|คะ|ดิฉัน', thai):
            continue
        rom = rom.replace('’', '').strip()
        m = re.search(r'\[sound:([^\]]+)\]', audio)
        rel = ''
        if m and m.group(1) in by_name:
            rel = f'audio/{n + 1}.m4a'
            dest = OUT / rel
            if not dest.exists():
                with tempfile.NamedTemporaryFile(suffix='.mp3', delete=False) as a:
                    a.write(z.read(by_name[m.group(1)]))
                dest.parent.mkdir(parents=True, exist_ok=True)
                subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', a.name, '-ac', '1', '-c:a', 'aac', '-b:a', '64k', str(dest)])
        items.append({'id': str(n + 1), 'title': '', 'text': thai, 'roman': rom, 'words': [], 'translation': plain(front),
                      'audio': rel if rel and (OUT / rel).exists() else '', 'dur': 0, 'note': ''})
    # Durations, and groups of 25 in the deck's order (Pimsleur's lesson order).
    for it in items:
        if it['audio']:
            out = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', str(OUT / it['audio'])], capture_output=True, text=True).stdout
            try: it['dur'] = round(float(out.strip()), 2)
            except ValueError: pass
    groups = []
    for k in range(0, len(items), 25):
        chunk = items[k:k + 25]
        name = f'Cards {k + 1}–{k + len(chunk)}'
        for it in chunk:
            it['title'] = name
        groups.append({'id': f'p{k // 25 + 1}', 'name': name, 'nameEn': '', 'icon': '', 'section': 'Pimsleur Thai 1', 'sectionEn': '',
                       'order': 0, 'items': chunk})
    s = {'id': OUT.name, 'title': 'ไทย · Pimsleur Thai 1', 'subtitle': 'Words and phrases from the Pimsleur Thai 1 flashcards, with their native recordings',
         'lang': 'th', 'translationLang': 'en', 'l1': 'en', 'groupLabel': 'Topics', 'roman': 'pimsleur', 'groups': groups}
    (OUT / 'set.json').write_text(json.dumps(s, ensure_ascii=False, indent=0))
    print(len(items), 'cards,', sum(1 for i in items if i['audio']), 'with recordings')


if __name__ == '__main__':
    main(sys.argv[1])
