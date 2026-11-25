#!/usr/bin/env python3
"""A Russian–English Yomitan dictionary from Wiktionary, with an index of every inflected form (stress marked).

  python3 tools/build_russian_dict.py kaikki-Russian.jsonl OUT.zip

Data: kaikki.org's extract of the English Wiktionary's Russian entries
(https://kaikki.org/dictionary/Russian/kaikki.org-dictionary-Russian.jsonl), CC BY-SA 4.0.

Like build_german_dict.py: term banks (one page per word: stressed headword, senses, examples, and a collapsed table of
forms) plus form_bank_N.json, rows [form, lemma, grammar]: отца́ → отец "genitive singular", шла́ → идти "past feminine
singular", сде́ланная → сделать "past passive participle, feminine nominative". Kotoba reads the bank into its forms table,
so any declined or conjugated word finds its dictionary form, its grammar, and where the stress falls.
Forms come from each word's own declension / conjugation table, from Wiktionary's form pages ("genitive singular of
оте́ц"), and from the tables of participle pages (сде́ланный declines like an adjective).
"""
import collections
import json
import os
import re
import sys
import unicodedata
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_german_dict import POS, span, GREY, sense_nodes, head_text, form_of_label, uniq, table  # noqa: E402

TITLE = '[RU-EN] Russian–English Wiktionary'

# Tags of rows that aren't forms of the word itself.
SKIP = {'table-tags', 'inflection-template', 'class', 'romanization', 'canonical', 'adjective', 'relational',
        'diminutive', 'augmentative', 'pejorative', 'endearing', 'perfective', 'imperfective', 'alternative',
        'noun-from-verb', 'error-unrecognized-form', 'error-unknown-tag', 'multiword-construction', 'abstract-noun',
        'iterative', 'semelfactive', 'reflexive', 'transliteration', 'Latin'}
# Head-line forms (outside the tables) worth indexing: the genitive and plural a noun's heading gives, comparatives.
HEAD_OK = {'genitive', 'plural', 'nominative', 'singular', 'comparative', 'superlative', 'short-form'}
CYRILLIC_WORD = re.compile('^[\u0400-\u04ff\u0301\u0300-]*[\u0400-\u04ff][\u0400-\u04ff\u0301\u0300-]*$')
INFL = {'singular', 'plural', 'nominative', 'genitive', 'dative', 'accusative', 'instrumental', 'prepositional',
        'locative', 'partitive', 'vocative', 'participle', 'imperative', 'comparative', 'superlative', 'present',
        'past', 'future', 'first-person', 'second-person', 'third-person', 'short-form', 'adverbial'}
OLD_LETTERS = re.compile('[ѣіѳѵѢІѲѴ\u0308]|ъ$')  # pre-1918 spelling: братъ, человѣкъ, ея̈
CASES = ['nominative', 'genitive', 'dative', 'accusative', 'instrumental', 'prepositional', 'locative', 'partitive',
         'vocative']
PERSON = {'first-person': '1st person', 'second-person': '2nd person', 'third-person': '3rd person'}


def strip_stress(s):
    return unicodedata.normalize('NFC', unicodedata.normalize('NFD', s).replace('́', '').replace('̀', ''))


def grammar_label(tags):
    """'genitive singular', '1st person singular present', 'past feminine singular', 'past passive participle'…"""
    t = set(tags)
    dated = ' (dated)' if 'dated' in t else ''
    if 'participle' in t:
        if 'adverbial' in t:
            return ('present' if 'present' in t else 'past') + ' adverbial participle' + dated
        voice = 'passive' if 'passive' in t else 'active'
        return ('present ' if 'present' in t else 'past ') + voice + ' participle' + dated
    words = []
    for k in ('comparative', 'superlative'):
        if k in t:
            words.append(k)
    if 'short-form' in t:
        words.append('short form')
    for p in ('first-person', 'second-person', 'third-person'):
        if p in t:
            words.append(PERSON[p])
    for c in CASES:
        if c in t:
            words.append(c)
    if 'past' in t:
        words.append('past')
    genders = [g for g in ('masculine', 'feminine', 'neuter') if g in t]
    if genders and not ('past' in t and 'plural' in t):  # бежа́ли: the past plural has no gender
        words.append('/'.join(genders))
    for n in ('singular', 'plural'):
        if n in t:
            words.append(n)
    for k in ('present', 'future'):
        if k in t:
            words.append(k)
    if 'imperative' in t:
        words.append('imperative')
    for a in ('animate', 'inanimate'):
        if a in t:
            words.append('(' + a + ')')
    label = ' '.join(words)
    return label + dated if label else ''


def gender_aspect(entry):
    tags = set()
    for f in entry.get('forms', []):
        if 'canonical' in f.get('tags', []):
            tags |= set(f['tags'])
        if 'class' in f.get('tags', []):
            tags |= set(f.get('form', '').split())
    for s in entry.get('senses', []):
        tags |= set(s.get('tags', []))
    out = []
    for k, v in (('masculine', 'm'), ('feminine', 'f'), ('neuter', 'n')):
        if k in tags and entry['pos'] in ('noun', 'name'):
            out.append(v)
    g = ''.join(out)
    asp = ''
    if entry['pos'] == 'verb':
        asp = 'impf' if 'imperfective' in tags and 'perfective' not in tags else 'pf' if 'perfective' in tags and 'imperfective' not in tags else 'impf/pf' if 'perfective' in tags else ''
    return g, asp


def stressed_head(entry):
    for f in entry.get('forms', []):
        if 'canonical' in f.get('tags', []):
            return f['form']
    return entry['word']


def forms_table(entry):
    """A collapsed list of the word's forms, grouped by grammar."""
    rows = collections.OrderedDict()
    for f in entry.get('forms', []):
        if f.get('source') not in ('declension', 'conjugation') or set(f.get('tags', [])) & SKIP or 'dated' in f.get('tags', []):
            continue
        label = grammar_label(f.get('tags', []))
        if not label:
            continue
        cell = rows.setdefault(label, [])
        if f['form'] not in cell:
            cell.append(f['form'])
    if not rows:
        return None
    name = 'Conjugation' if entry['pos'] == 'verb' else 'Declension'
    return {'tag': 'details', 'content': [{'tag': 'summary', 'content': name},
                                          table([[k, ', '.join(v)] for k, v in rows.items()])]}


def entry_content(entry):
    head = head_text(entry, entry['word'])
    ipa = next((s['ipa'] for s in entry.get('sounds', []) if s.get('ipa')), '')
    top = [span('; '.join(head) if head else stressed_head(entry))]
    if ipa:
        top.append(span('  ' + ipa, GREY))
    senses = sense_nodes(entry)
    if not senses:
        return None
    content = [{'tag': 'div', 'content': top}, {'tag': 'ol', 'content': senses}]
    t = forms_table(entry)
    if t:
        content.append(t)
    return {'type': 'structured-content', 'content': content}


def is_form_page(d):
    senses = d.get('senses') or []
    return bool(senses) and all(s.get('form_of') for s in senses) and any(set(s.get('tags', [])) & INFL for s in senses)


def table_forms(entry):
    """(form, label) for every row of the word's tables and the useful forms of its heading."""
    for f in entry.get('forms', []):
        tg = set(f.get('tags', []))
        form = f.get('form', '').strip()
        if not form or tg & SKIP or not CYRILLIC_WORD.match(form) or OLD_LETTERS.search(strip_stress(form)):
            continue
        if f.get('source') not in ('declension', 'conjugation', 'inflection') and not (tg and tg <= HEAD_OK | {'dated', 'animate', 'inanimate'}):
            continue
        if ' ' in form:  # future with бу́ду, "бо́лее …": not one word
            continue
        # Personal pronoun pages share one table of every person (вы's table holds её, мною): only the form pages count.
        if entry['pos'] == 'pron' and tg & {'first-person', 'second-person', 'third-person'}:
            continue
        label = grammar_label(tg)
        if label:
            yield form, label


def main(src, out):
    lemmas = collections.OrderedDict()   # word → [entries]
    pairs = collections.OrderedDict()    # (form, lemma) → [labels]
    participle_pages = []                # (entry, lemma, label): participle pages decline on their own
    n = 0
    with open(src, encoding='utf8') as f:
        for line in f:
            d = json.loads(line)
            n += 1
            if d.get('lang_code') not in (None, 'ru'):
                continue
            if is_form_page(d):
                for s in d['senses']:
                    lemma = strip_stress(s['form_of'][0]['word'])
                    if OLD_LETTERS.search(lemma) or OLD_LETTERS.search(strip_stress(d['word'])):
                        continue
                    # The page's grammar tags, read the same way as the tables; its gloss only when it has none.
                    label = grammar_label(s.get('tags', []))
                    if not label:
                        label = re.sub(r'\s+of\s.*$', '', form_of_label(s, s['form_of'][0]['word'])).strip()
                    if not label:
                        continue
                    labels = pairs.setdefault((d['word'], lemma), [])
                    if label not in labels:
                        labels.append(label)
                    if 'participle' in s.get('tags', []) and d.get('forms'):
                        participle_pages.append((d, lemma, label))
                continue
            if OLD_LETTERS.search(d['word']):
                continue
            # шел, дает: pages that only say "alternative spelling of шёл". Kotoba reads е and ё alike anyway.
            senses = d.get('senses') or []
            if senses and all(s.get('alt_of') for s in senses) and all(
                    strip_stress(a['word']).replace('ё', 'е') == d['word'].replace('ё', 'е') for s in senses for a in s['alt_of']):
                continue
            lemmas.setdefault(d['word'], []).append(d)
    print(n, 'entries,', len(lemmas), 'lemmas,', len(pairs), 'form pages,', len(participle_pages), 'participle pages', file=sys.stderr)

    terms, tags_used, seq = [], {}, 0
    for word, entries in lemmas.items():
        for e in entries:
            c = entry_content(e)
            if not c:
                continue
            pos = POS.get(e['pos'], e['pos'])
            g, asp = gender_aspect(e)
            tags = [pos] + [x for x in (g, asp) if x]
            for t in tags:
                tags_used[t] = 'gender' if t in ('m', 'f', 'n', 'mf', 'mn', 'fn', 'mfn') else 'aspect' if t in ('impf', 'pf', 'impf/pf') else 'partOfSpeech'
            seq += 1
            terms.append([word, stressed_head(e), ' '.join(tags), '', 0, [c], seq, ''])
    have = set(t[0] for t in terms)
    print(len(terms), 'term rows', file=sys.stderr)

    # form → lemma → labels. Keys keep the stressed spelling; Kotoba matches them without stress.
    forms = collections.OrderedDict()
    def add(form, lemma, label):
        if lemma not in have or strip_stress(form) == lemma or not CYRILLIC_WORD.match(form):
            return
        labels = forms.setdefault((form, lemma), [])
        words = set(label.replace(' (dated)', '').split())
        # "genitive" from the heading next to "genitive singular" from the table; a dated label that reads the same.
        for i, old in enumerate(labels):
            ow = set(old.replace(' (dated)', '').split())
            if words <= ow:
                return
            if ow < words or ow == words:
                labels[i] = label.replace(' (dated)', '') if ow == words else label
                return
        labels.append(label)
    for word, entries in lemmas.items():
        if word not in have:
            continue
        for e in entries:
            # Pronoun and determiner tables are reliable here (unlike German's), and numerals decline too.
            for form, label in table_forms(e):
                add(form, word, label)
    # Form pages add what the tables don't have (variant spellings, irregular plurals listed only there).
    stressed_of = {}
    for (form, lemma) in forms:
        stressed_of.setdefault((strip_stress(form), lemma), form)
    for (form, lemma), labels in pairs.items():
        sf = stressed_of.get((strip_stress(form), lemma), form)
        for l in labels:
            add(sf, lemma, l)
    # Participles: сде́ланная → сделать "past passive participle, feminine nominative".
    for d, lemma, label in participle_pages:
        for form, l2 in table_forms(d):
            add(form, lemma, label + ', ' + l2)
    print(len(forms), 'forms', file=sys.stderr)

    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        z.writestr('index.json', json.dumps({
            'title': TITLE, 'format': 3, 'revision': 'wiktionary-2026-10', 'sequenced': True,
            'author': 'Wiktionary contributors, via kaikki.org (Tatu Ylonen\'s wiktextract)',
            'description': 'Russian–English dictionary from the English Wiktionary: stressed headwords, senses, examples, '
                           'declension and conjugation tables, and an index of every inflected form with its stress.',
            'attribution': 'Text from Wiktionary, CC BY-SA 4.0 (https://en.wiktionary.org). Extracted with wiktextract (https://kaikki.org).',
            'sourceLanguage': 'ru', 'targetLanguage': 'en'}, ensure_ascii=False))
        notes = {'m': 'masculine', 'f': 'feminine', 'n': 'neuter', 'mf': 'masculine or feminine', 'mn': 'masculine or neuter',
                 'fn': 'feminine or neuter', 'mfn': 'any gender', 'impf': 'imperfective', 'pf': 'perfective',
                 'impf/pf': 'imperfective and perfective', 'proper-noun': 'proper noun'}
        z.writestr('tag_bank_1.json', json.dumps([[t, c, 0, notes.get(t, t), 0] for t, c in sorted(tags_used.items())], ensure_ascii=False))
        for i in range(0, len(terms), 4000):
            z.writestr('term_bank_%d.json' % (i // 4000 + 1), json.dumps(terms[i:i + 4000], ensure_ascii=False, separators=(',', ':')))
        rows = [[form, lemma, ' | '.join(labels[:6])] for (form, lemma), labels in sorted(forms.items())]
        for i in range(0, len(rows), 100000):
            z.writestr('form_bank_%d.json' % (i // 100000 + 1), json.dumps(rows[i:i + 100000], ensure_ascii=False, separators=(',', ':')))
    print('wrote', out, file=sys.stderr)


if __name__ == '__main__':
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    main(sys.argv[1], sys.argv[2])
