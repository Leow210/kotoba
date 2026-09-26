#!/usr/bin/env python3
"""Mandarin translations for a Cantonese listening set, from the local Gemma model in Kotoba for Mac (Settings ›
Translation), a few lines per request through tools/mac.py. The book's English stays as translationEn.

  python3 tools/translate_set.py --set yue-life1

Kotoba for Mac must be running. Lines already translated into Mandarin are kept, so it can be rerun.
"""
import argparse, json, re, subprocess, sys
from pathlib import Path

DATA = Path.home() / 'Library/Application Support/Kotoba/listening'
MAC = Path(__file__).resolve().parent / 'mac.py'


def batch(lines):
    js = ('const T=' + json.dumps(lines, ensure_ascii=False) + ';const out=[];for(const t of T){try{const r=await api("translate",'
          '{text:t.text,from:"yue",to:"zh-Hant",engine:"gemma",context:t.en?("English: "+t.en):""});out.push(r.text||"");}'
          'catch(e){out.push("")}}return JSON.stringify(out)')
    r = subprocess.run(['perl', '-e', 'alarm 600; exec @ARGV', sys.executable, str(MAC), js], capture_output=True, text=True)
    try:
        return json.loads(r.stdout.strip())
    except ValueError:
        return [''] * len(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--set', required=True)
    args = ap.parse_args()
    out = DATA / args.set
    s = json.loads((out / 'set.json').read_text())
    todo = [it for g in s['groups'] for it in g['items'] if not it.get('zh')]
    print(len(todo), 'lines to translate', flush=True)
    for k in range(0, len(todo), 15):
        chunk = todo[k:k + 15]
        res = batch([{'text': it['text'], 'en': it.get('translationEn') or it.get('translation', '')} for it in chunk])
        for it, zh in zip(chunk, res):
            zh = re.sub(r'\s+', ' ', zh or '').strip()
            # Only a real Mandarin sentence: not empty, not mostly English (the model still loading answers nothing,
            # or echoes the English), and no notes or markup; those lines wait for a rerun.
            latin, han = len(re.findall(r'[A-Za-z]', zh)), len(re.findall(r'[\u4e00-\u9fff]', zh))
            if not zh or han == 0 or latin > han or re.search(r'[*$\\]|\(Wait|Note', zh):
                continue
            if not it.get('translationEn'):
                it['translationEn'] = it.get('translation', '')
            it['translation'] = zh
            it['zh'] = True
        (out / 'set.json').write_text(json.dumps(s, ensure_ascii=False, indent=0))
        print(min(k + 15, len(todo)), 'done', flush=True)
    s['translationLang'] = 'zh'
    (out / 'set.json').write_text(json.dumps(s, ensure_ascii=False, indent=0))


if __name__ == '__main__':
    main()
