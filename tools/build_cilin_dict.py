#!/usr/bin/env python3
"""Yomitan thesaurus dictionaries from 同义词词林 扩展版 (Tongyici Cilin, extended by Harbin Institute of Technology).

  python3 tools/build_cilin_dict.py cilin.txt OUT_DIR       # → OUT_DIR/[ZH-ZH Thesaurus] 同義詞詞林 (繁體).zip and (简体).zip

cilin.txt: https://raw.githubusercontent.com/nlpscott/tyccl/master/lib/cilin.txt (the gem's code is MIT; the word list is HIT's
and meant for research, so these zips are for your own use). Each line is a code and its words: Aa01A01= 人士 人物 …
The code reads big class (A), middle class (a), small class (01), word group (A), line (01); the mark says whether the
words mean the same (=), are related (#) or stand alone (@).

The traditional edition also has each word under its simplified spelling.
Each word's entry lists every group it is in (synonyms, linked so a click goes to that word), then the nearby lines of
the same word group. The names land in the 類語 group on import ("[ZH-…Thesaurus]" → Chinese/類語) for the Mac editor's brainstormer.
"""
import json, os, sys, zipfile
from collections import defaultdict
from opencc import OpenCC
from pypinyin import pinyin, Style

SRC, OUT = sys.argv[1:3]
BIG = {'A': '人', 'B': '物', 'C': '時間與空間', 'D': '抽象事物', 'E': '特徵', 'F': '動作', 'G': '心理活動',
       'H': '活動', 'I': '現象與狀態', 'J': '關聯', 'K': '助語', 'L': '敬語'}
MARK = {'=': '同義', '#': '相關', '@': '獨立'}
NEAR_CAP = 30

lines = []  # (code, mark, [words])
for raw in open(SRC, encoding='utf-8'):
    raw = raw.strip()
    if not raw: continue
    head, _, rest = raw.partition(' ')
    lines.append((head[:7], head[7], rest.split()))
paragraph = defaultdict(list)  # the first five characters: one word group
for i, (code, mark, words) in enumerate(lines): paragraph[code[:5]].append(i)
membership = defaultdict(list)  # word → line indexes
for i, (_, _, words) in enumerate(lines):
    for w in dict.fromkeys(words): membership[w].append(i)


def build(convert, title, script):
    cc = OpenCC('s2t') if convert else None
    t = (lambda s: cc.convert(s)) if cc else (lambda s: s)
    cache = {}
    def conv(w):
        if w not in cache: cache[w] = t(w)
        return cache[w]
    def link(w):
        return {'tag': 'a', 'href': '?query=' + conv(w), 'content': conv(w)}
    def words_html(ws, skip):
        out = []
        for w in ws:
            if w == skip: continue
            if out: out.append('　')
            out.append(link(w))
        return out
    bank, seq = [], 0
    for word in sorted(membership):
        blocks = []
        for i in membership[word]:
            code, mark, ws = lines[i]
            others = [w for w in dict.fromkeys(ws) if w != word]
            near = []
            if mark != '@':
                for j in paragraph[code[:5]]:
                    if j == i: continue
                    near += [w for w in dict.fromkeys(lines[j][2]) if w != word and w not in others and w not in near]
            body = [{'tag': 'div', 'style': {'fontWeight': 'bold'},
                     'content': f'{MARK[mark]} · {BIG.get(code[0], "")} · {code}'}]
            if others: body.append({'tag': 'div', 'content': words_html(others, word)})
            elif mark == '@': body.append({'tag': 'div', 'content': '（沒有同義詞）' if convert else '（没有同义词）'})
            if near:
                body.append({'tag': 'div', 'style': {'marginTop': '0.3em', 'opacity': 0.75},
                             'content': ['近義：' if convert else '近义：', *words_html(near[:NEAR_CAP], word)]})
            blocks.append({'tag': 'div', 'style': {'marginBottom': '0.7em'}, 'content': body})
        term = conv(word)
        try: reading = ' '.join(x[0] for x in pinyin(term, style=Style.TONE)) if any('一' <= c <= '鿿' for c in term) else ''
        except Exception: reading = ''
        defs = [{'type': 'structured-content', 'content': blocks}]
        bank.append([term, reading, '', '', 0, defs, seq, ''])
        seq += 1
        # The traditional edition also answers to the simplified spelling (美丽 → 美麗), so a word picked in either script is found.
        if convert and term != word:
            bank.append([word, reading, '', '', 0, defs, seq, ''])
            seq += 1
    path = os.path.join(OUT, f'[ZH-ZH Thesaurus] 同義詞詞林 ({script}).zip')
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('index.json', json.dumps({
            'title': title, 'revision': 'cilin-ext', 'format': 3, 'author': '梅家駒 等；哈爾濱工業大學擴展',
            'description': '同义词词林 扩展版 — grouped synonyms with nearby word groups', 'sequenced': True}, ensure_ascii=False))
        for n in range(0, len(bank), 10000):
            z.writestr(f'term_bank_{n // 10000 + 1}.json', json.dumps(bank[n:n + 10000], ensure_ascii=False))
    print(path, len(bank), 'entries')

os.makedirs(OUT, exist_ok=True)
build(True, '同義詞詞林（繁體）', '繁體')
build(False, '同义词词林（简体）', '简体')
