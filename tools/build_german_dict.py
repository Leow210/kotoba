#!/usr/bin/env python3
"""A German–English Yomitan dictionary from Wiktionary, with an index of every inflected form.

  python3 tools/build_german_dict.py kaikki-German.jsonl OUT.zip

Data: kaikki.org's extract of the English Wiktionary's German entries
(https://kaikki.org/dictionary/German/kaikki.org-dictionary-German.jsonl), CC BY-SA 4.0.

The zip is an ordinary Yomitan dictionary (term banks: one page per word, a block per part of speech with its
principal parts, numbered senses, examples, synonyms, and a collapsed conjugation / declension table) plus one extra
bank, form_bank_N.json, rows [form, lemma, grammar]: gegangen → gehen "past participle", Häuser → Haus "nominative /
genitive / accusative plural". Kotoba reads it into its forms table, so a conjugated, declined or compared word finds
its dictionary form, with the grammar named. Forms come from two places that disagree here and there, so both are used:
Wiktionary's own "inflection of" pages (compact labels: "strong nominative feminine singular") and the inflection
tables of each lemma (which also hold the forms nobody wrote a page for: superlatives, subjunctives, separable verbs
as "stehe auf").
"""
import collections
import json
import re
import sys
import zipfile

TITLE = '[DE-EN] German–English Wiktionary'

POS = {'noun': 'noun', 'verb': 'verb', 'adj': 'adjective', 'adv': 'adverb', 'name': 'proper-noun', 'num': 'numeral',
       'phrase': 'phrase', 'intj': 'interjection', 'pron': 'pronoun', 'suffix': 'suffix', 'prefix': 'prefix',
       'proverb': 'proverb', 'prep': 'preposition', 'conj': 'conjunction', 'det': 'determiner',
       'contraction': 'contraction', 'prep_phrase': 'phrase', 'article': 'article', 'particle': 'particle',
       'postp': 'postposition', 'character': 'letter', 'symbol': 'symbol', 'interfix': 'interfix', 'circumfix': 'circumfix',
       'punct': 'punctuation'}

# A sense that only says "<inflection> of <lemma>" becomes a row of the form index, not a page.
INFL = {'singular', 'plural', 'nominative', 'genitive', 'dative', 'accusative', 'participle', 'imperative',
        'comparative', 'superlative', 'present', 'past', 'preterite', 'subjunctive', 'infinitive', 'first-person',
        'second-person', 'third-person', 'indicative'}
# Rows of a lemma's inflection table that are not forms of the word.
SKIP_FORM_TAGS = {'table-tags', 'inflection-template', 'multiword-construction', 'includes-article',
                  'error-unrecognized-form', 'error-unknown-tag', 'subordinate-clause'}
# Grammar chips (strong/weak, gender…) that the heading already carries, so a sense doesn't repeat them.
SENSE_TAGS_HIDDEN = {'masculine', 'feminine', 'neuter', 'strong', 'weak', 'mixed', 'irregular', 'plural', 'singular',
                     'no-plural', 'plural-only', 'proper-noun', 'form-of', 'transitive-or-intransitive', 'inflection-template'}
REGISTER = {'rare', 'obsolete', 'archaic', 'dated', 'colloquial', 'informal', 'nonstandard', 'proscribed', 'Austria',
            'Switzerland', 'dialectal', 'regional', 'Southern-Germany', 'poetic', 'alternative', 'uncommon',
            'Liechtenstein', 'vernacular', 'literary', 'Upper-German', 'Northwest-German', 'Swiss-German'}

# Table tags → words, in the order a grammar label reads.
ORDER = ['strong', 'weak', 'mixed', 'definite', 'indefinite', 'nominative', 'genitive', 'dative', 'accusative',
         'masculine', 'feminine', 'neuter', 'singular', 'plural', 'first-person', 'second-person', 'third-person',
         'present', 'preterite', 'past', 'perfect', 'pluperfect', 'future', 'future-i', 'future-ii', 'indicative',
         'subjunctive-i', 'subjunctive-ii', 'subjunctive', 'imperative', 'infinitive-zu', 'infinitive', 'participle',
         'comparative', 'superlative', 'predicative', 'diminutive']
WORD = {'first-person': '1st person', 'second-person': '2nd person', 'third-person': '3rd person',
        'subjunctive-i': 'subjunctive I', 'subjunctive-ii': 'subjunctive II', 'infinitive-zu': 'zu-infinitive',
        'future-i': 'future', 'future-ii': 'future perfect', 'preterite': 'preterite', 'pluperfect': 'pluperfect'}


def grammar_label(tags):
    """'first-person singular present indicative' style label from the tags of one inflection-table row."""
    t = set(tags)
    if 'participle' in t:
        return ('present participle' if 'present' in t else 'past participle') + (' (adjective)' if 'predicative' in t else '')
    words = []
    for k in ORDER:
        if k not in t:
            continue
        if k in ('definite', 'indefinite'):
            continue
        if k == 'past' and ('subjunctive' in t or 'subjunctive-ii' in t or 'subjunctive-i' in t):
            continue
        if k == 'subjunctive' and ('subjunctive-i' in t or 'subjunctive-ii' in t):
            continue
        if k == 'infinitive' and 'infinitive-zu' in t:
            continue
        words.append(WORD.get(k, k))
    if 'past' in t and 'subjunctive' in t and not ({'subjunctive-i', 'subjunctive-ii'} & t):
        words.append('(past)')
    label = ' '.join(words)
    reg = [r for r in tags if r in REGISTER]
    if reg:
        label += ' (' + ', '.join(r.replace('-', ' ') for r in reg) + ')'
    return label


def form_of_label(sense, lemma):
    g = sense.get('glosses') or []
    label = g[-1] if len(g) > 1 else (g[0] if g else '')
    label = re.sub(r'^inflection of [^:]*:\s*', '', label)
    # "dative of er; him, to him (indirect object)": only the grammar, not the meaning that follows.
    label = re.sub(r'\s+of ' + re.escape(lemma) + r'(?:[:;,.(].*)?$', '', label).strip()
    reg = [r for r in sense.get('tags', []) if r in REGISTER]
    if reg:
        label += ' (' + ', '.join(r.replace('-', ' ') for r in reg) + ')'
    return label


def head_text(entry, word):
    out = []
    for h in entry.get('head_templates', []):
        e = (h.get('expansion') or '').strip()
        if not e or e == word:
            continue
        # The diminutives run to a dozen dialect spellings; one is plenty.
        e = re.sub(r',? diminutive .*(?=\)$)', '', e)
        e = re.sub(r'\s+', ' ', e)
        if e not in out:
            out.append(e[:400])
    return out[:2]


def gender_of(entry):
    g = []
    for s in entry.get('senses', []):
        for t in s.get('tags', []):
            k = {'masculine': 'm', 'feminine': 'f', 'neuter': 'n'}.get(t)
            if k and k not in g:
                g.append(k)
    if not g:
        for h in entry.get('head_templates', []):
            m = re.search(r'\s([mfn])(?:\s|$)', ' ' + (h.get('expansion') or '')[len(entry['word']):len(entry['word']) + 6])
            if m and m.group(1) not in g:
                g.append(m.group(1))
    return ''.join(sorted(g, key='mfn'.index))


def span(text, style=None):
    node = {'tag': 'span', 'content': text}
    if style:
        node['style'] = style
    return node


GREY = {'color': '#72766f', 'fontSize': '0.85em'}


def sense_nodes(entry):
    """Numbered senses; a sense with sub-senses becomes a nested list."""
    items = []
    last_parent = None
    for s in entry.get('senses', []):
        g = s.get('glosses') or []
        if not g or all(x.startswith('inflection of') for x in g):
            continue
        quals = [t.replace('-', ' ') for t in s.get('tags', []) if t not in SENSE_TAGS_HIDDEN and not re.match(r'^class-\d', t)]
        body = []
        if quals:
            body.append(span('(' + ', '.join(quals[:4]) + ') ', GREY))
        body.append(g[-1])
        extra = []
        ex = [e for e in s.get('examples', []) if e.get('text') and e.get('english') and e.get('type') != 'quotation']
        for e in ex[:2]:
            extra.append({'tag': 'div', 'style': {'marginLeft': '0.2em', 'fontSize': '0.92em'},
                          'content': [span(e['text'].strip()), span(' — ' + e['english'].strip(), GREY)]})
        syn = [x['word'] for x in s.get('synonyms', []) if x.get('word')][:5]
        ant = [x['word'] for x in s.get('antonyms', []) if x.get('word')][:3]
        if syn:
            extra.append({'tag': 'div', 'content': span('syn. ' + ', '.join(syn), GREY)})
        if ant:
            extra.append({'tag': 'div', 'content': span('ant. ' + ', '.join(ant), GREY)})
        li = {'tag': 'li', 'content': body + extra}
        if len(g) > 1:
            if last_parent is not None and last_parent[0] == g[0]:
                last_parent[1].append(li)
            else:
                sub = [li]
                items.append({'tag': 'li', 'content': [g[0], {'tag': 'ul', 'content': sub}]})
                last_parent = (g[0], sub)
        else:
            last_parent = None
            items.append(li)
    return items


PRON = [('first-person', 'singular', 'ich'), ('second-person', 'singular', 'du'), ('third-person', 'singular', 'er/sie/es'),
        ('first-person', 'plural', 'wir'), ('second-person', 'plural', 'ihr'), ('third-person', 'plural', 'sie/Sie')]
CASES = ['nominative', 'genitive', 'dative', 'accusative']


def table(rows, header=None):
    trs = []
    if header:
        trs.append({'tag': 'tr', 'content': [{'tag': 'th', 'content': h} for h in header]})
    for r in rows:
        trs.append({'tag': 'tr', 'content': [{'tag': 'th' if i == 0 else 'td', 'content': c} for i, c in enumerate(r)]})
    return {'tag': 'table', 'content': trs}


def uniq(seq):
    out = []
    for x in seq:
        if x not in out:
            out.append(x)
    return out


def conjugation(entry):
    rows_src = [f for f in entry.get('forms', []) if f.get('source') == 'conjugation'
                and not (set(f.get('tags', [])) & SKIP_FORM_TAGS)]
    if not rows_src:
        return None
    def pick(*need, none=()):
        need = set(need)
        got = [f['form'] for f in rows_src if need <= set(f['tags']) and not (set(none) & set(f['tags']))]
        return ' / '.join(uniq(got))
    rows = []
    for name, tags in (('Present', ('indicative', 'present')), ('Preterite', ('indicative', 'preterite')),
                       ('Subjunctive I', ('subjunctive-i',)), ('Subjunctive II', ('subjunctive-ii',))):
        cells = [pick(p, n, *tags) for p, n, _ in PRON]
        if any(cells):
            rows.append([name] + cells)
    if not rows:
        return None
    parts = []
    inf = pick('infinitive', none=('infinitive-zu',))
    zu = pick('infinitive-zu')
    pres = pick('participle', 'present')
    past = pick('participle', 'past')
    aux = pick('auxiliary')
    imp_s, imp_p = pick('imperative', 'singular'), pick('imperative', 'plural')
    summary = []
    for label, val in (('infinitive', inf), ('zu-infinitive', zu), ('present participle', pres), ('past participle', past),
                       ('auxiliary', aux), ('imperative', ' · '.join(x for x in (imp_s, imp_p) if x))):
        if val:
            summary.append([label, val])
    content = [table(rows, ['', 'ich', 'du', 'er/sie/es', 'wir', 'ihr', 'sie/Sie'])]
    if summary:
        content.append(table(summary))
    return {'tag': 'details', 'content': [{'tag': 'summary', 'content': 'Conjugation'}] + content}


def declension(entry):
    src = [f for f in entry.get('forms', []) if f.get('source') == 'declension'
           and not (set(f.get('tags', [])) & SKIP_FORM_TAGS)]
    if not src:
        return None
    def pick(case, number):
        got = [f['form'] for f in src if {case, number} <= set(f['tags'])]
        return ' / '.join(uniq(got))
    rows = [[c, pick(c, 'singular'), pick(c, 'plural')] for c in CASES]
    if not any(r[1] or r[2] for r in rows):
        return None
    return {'tag': 'details', 'content': [{'tag': 'summary', 'content': 'Declension'}, table(rows, ['', 'singular', 'plural'])]}


def entry_content(entry):
    word = entry['word']
    head = head_text(entry, word)
    ipa = ''
    for s in entry.get('sounds', []):
        if s.get('ipa'):
            ipa = s['ipa']
            break
    top = []
    if head:
        top.append(span('; '.join(head)))
    if ipa:
        top.append(span('  ' + ipa, GREY))
    content = []
    if top:
        content.append({'tag': 'div', 'content': top})
    senses = sense_nodes(entry)
    if not senses:
        return None
    content.append({'tag': 'ol', 'content': senses})
    pos = entry['pos']
    extra = conjugation(entry) if pos == 'verb' else declension(entry) if pos in ('noun', 'name') else None
    if extra:
        content.append(extra)
    return {'type': 'structured-content', 'content': content}


def main(src, out):
    lemmas = collections.OrderedDict()      # word → [entries]
    pairs = collections.OrderedDict()       # (form, lemma) → [labels] from "inflection of" pages
    n = 0
    with open(src, encoding='utf8') as f:
        for line in f:
            d = json.loads(line)
            n += 1
            senses = d.get('senses') or []
            if senses and all(s.get('form_of') for s in senses) and any(set(s.get('tags', [])) & INFL for s in senses):
                for s in senses:
                    lemma = s['form_of'][0]['word']
                    label = form_of_label(s, lemma)
                    if label:
                        labels = pairs.setdefault((d['word'], lemma), [])
                        if label not in labels:
                            labels.append(label)
                continue
            lemmas.setdefault(d['word'], []).append(d)
    print(n, 'entries,', len(lemmas), 'lemmas,', len(pairs), 'form pages', file=sys.stderr)

    # ---- term banks
    terms = []
    tags_used = {}
    seq = 0
    for word, entries in lemmas.items():
        for e in entries:
            c = entry_content(e)
            if not c:
                continue
            pos = POS.get(e['pos'], e['pos'])
            g = gender_of(e) if e['pos'] in ('noun', 'name') else ''
            tags = [pos] + ([g] if g else [])
            for t in tags:
                tags_used[t] = 'gender' if t in ('m', 'f', 'n', 'mf', 'mn', 'fn', 'mfn') else 'partOfSpeech'
            seq += 1
            terms.append([word, '', ' '.join(tags), '', 0, [c], seq, ''])
    have = set(t[0] for t in terms)
    print(len(terms), 'term rows', file=sys.stderr)

    # ---- form index
    forms = collections.OrderedDict()
    for (form, lemma), labels in pairs.items():
        if lemma in have and form != lemma:
            forms[(form, lemma)] = list(labels)
    from_tables = 0
    for word, entries in lemmas.items():
        if word not in have:
            continue
        for e in entries:
            for fm in e.get('forms', []):
                tg = fm.get('tags', [])
                form = fm['form'].strip()
                if set(tg) & SKIP_FORM_TAGS or fm.get('source') in ('declension', 'conjugation') and not tg:
                    continue
                if 'alternative' in tg and not fm.get('source'):
                    label = 'alternative form'
                else:
                    label = grammar_label(tg)
                if not label:
                    continue
                # "am schönsten": the form is the word after am.
                if form.startswith('am '):
                    form = form[3:]
                if not form or form == word or form in ('-', '–') or form.startswith('no-table') or re.match(r'^de-[a-z]+$', form):
                    continue
                if ' ' in form and e['pos'] != 'verb':
                    continue
                # Tables of pronouns, articles and determiners list neighbouring words (ich's table holds er, sie, es):
                # their forms come only from the "inflection of" pages.
                if e['pos'] in ('pron', 'article', 'det', 'num'):
                    continue
                key = (form, word)
                if key in pairs:
                    continue
                labels = forms.setdefault(key, [])
                if label not in labels:
                    labels.append(label)
                    from_tables += 1
    print(len(forms), 'forms', from_tables, 'labels from tables', file=sys.stderr)

    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        z.writestr('index.json', json.dumps({
            'title': TITLE, 'format': 3, 'revision': 'wiktionary-2026-09', 'sequenced': True,
            'author': 'Wiktionary contributors, via kaikki.org (Tatu Ylonen\'s wiktextract)',
            'description': 'German–English dictionary from the English Wiktionary: principal parts, senses, examples, '
                           'synonyms, conjugation and declension tables, and an index of every inflected form.',
            'attribution': 'Text from Wiktionary, CC BY-SA 4.0 (https://en.wiktionary.org). Extracted with wiktextract (https://kaikki.org).',
            'sourceLanguage': 'de', 'targetLanguage': 'en'}, ensure_ascii=False))
        notes = {'m': 'masculine', 'f': 'feminine', 'n': 'neuter', 'mf': 'masculine or feminine', 'mn': 'masculine or neuter',
                 'fn': 'feminine or neuter', 'mfn': 'any gender', 'proper-noun': 'proper noun'}
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
