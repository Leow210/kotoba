#!/usr/bin/env python3
"""Export furoku titles/pages and dictionary word selections from the Monokakido Mac app for Kotoba.

    python3 tools/export_extras.py OUT_DIR
    adb push OUT_DIR/. /sdcard/Android/data/app.kotoba.reader/files/extras/

For every dictionary this writes OUT_DIR/<mdx stem>/kotoba-extras.json:
  furoku: [{title, items:[{title, path} | {title, anchor}]}]   Japanese titles in Monokakido's menu order.
          path is a resource of the .mdd, or a file copied next to the manifest (appendices the MDX export left out).
  lists:  [{title, sections:[{title, items:[[word, headline, anchor, sortkey?], …]}]}]   Selections such as 地名, 四字熟語, 重要語.
The app matches a folder to a dictionary by the .mdx file name, so the original dictionary files are never touched.
"""
import json, os, plistlib, re, shutil, struct, sys

ROOT = os.path.expanduser('~/Library/Group Containers/group.jp.monokakido.Dictionaries/Library/Application Support/com.dictionarystore/dictionaries')

# Monokakido product → [(content folder, mdx stem)]
DICTS = {
    'DAIJIRIN2': [('SANSEIDO-DAIJIRIN2', 'daijirin2')],
    'SMK8': [('SMK8', 'smk8')],
    'NHKACCENT2': [('NHK_ACCENT', 'nhk-accent')],
    'BUREIGO': [('BUREIGO', 'bureigo')],
    'CJ3': [('Shogakukan-CJ3', 'cj3-cnj'), ('Shogakukan-JCD3', 'cj3-jcn')],
    'Korean': [('CHOUSENGO-KJ', 'korean-krj'), ('CHOUSENGO-JK', 'korean-jkr')],
    'PRJ': [('Shogakukan-PRJ', 'prj-prj'), ('Shogakukan-PJR', 'prj-jpr')],
    'THAI': [('PTJ', 'thai')],
    'KANKENKJ2': [('KankenKJ2Data', 'kankenkj2')],
    'Kanjikai2': [('Kanjikai2Data', 'kanjikai2')],
}
# Page XML already extracted from Monokakido (file name = page id − 1), for appendix pages the MDX conversion dropped.
PAGES = {
    'CHOUSENGO-KJ': '/Volumes/T7/Monokakido Android Dictionaries/Korean/KRJ/pages',
}
# Whole-dictionary indexes: the app already browses these.
SKIP_LISTS = {'すべて', '五十音', 'アルファベット・その他', '五十音索引'}


class Headlines:
    """headline.headlinestore: (page, item) → headline text (UTF-16, NUL-terminated)."""
    def __init__(self, path):
        self.map = {}
        if not os.path.exists(path):
            return
        d = open(path, 'rb').read()
        _, _, n, rec_off, words_off, _, _, _ = struct.unpack('<8I', d[:32])
        for i in range(n):
            page, item, _, off = struct.unpack('<IHHI', d[rec_off + i * 24:rec_off + i * 24 + 12])
            s = words_off + off
            e = s
            while d[e:e + 2] != b'\0\0':
                e += 2
            self.map[(page, item)] = d[s:e].decode('utf-16le')

    def get(self, page, item):
        return self.map.get((page, item)) or self.map.get((page, 0), '')


def read_entries(path):
    if not os.path.exists(path):
        return None
    d = open(path, 'rb').read()
    if len(d) < 36:
        return None
    n = struct.unpack('<I', d[:4])[0]
    if len(d) == 4 + n * 8:
        start = 4  # 朝鮮語辞典
    elif len(d) == 36 + n * 8 and not d[4:36].strip(b'\0'):
        start = 36
    else:
        return None  # a different, sectioned format (e.g. 無礼語 五十音): not needed
    return [struct.unpack('<IH', d[start + i * 8:start + i * 8 + 6]) for i in range(n)]


def plain_headline(s):
    """{C:阿城}{P: Āchéng} → (阿城, 阿城 Āchéng); {O:あ} is an index letter; {T:กับ} is Thai text."""
    k = re.match(r'^\uff0f[^\uff0f]*\uff0f([^\uff0f]*)\uff0f(.*)$', s)  # 朝鮮語辞典: ／1／가능-하다／可能－
    if k:
        word = re.sub(r'[-‐⁰¹²³⁴⁵⁶⁷⁸⁹]', '', k.group(1))
        return word, word + (f'〔{k.group(2)}〕' if k.group(2) else '')
    s = re.sub(r'\{O:[^}]*\}', '', s)
    main = re.search(r'\{[CT]:([^}]*)\}', s)
    text = re.sub(r'\{[A-Z]+:\s*([^}]*)\}', r'\1 ', s).strip()
    return (main.group(1) if main else text), re.sub(r'\s+', ' ', text)


def anchor(page, item):
    return f'{page:05d}' + (f'-{item:04X}' if item else '')


def clean_title(t):
    m = re.match(r'^\uff0f[^\uff0f]*\uff0f(.+?)\uff0f', t)  # ／8／{R:🄰} 春秋時代／index/A_s.png
    if m:
        t = m.group(1)
    t = re.sub(r'\{R:([^}]*)\}\s*', r'\1 ', t)
    t = re.sub(r'\{IMG:number(\d+)\}\s*', r'\1 ', t)
    t = re.sub(r'\{C:([^}]*)\}', r'\1', t)
    return re.sub(r'\{[A-Z]+:[^}]*\}\s*', '', t).strip()


class Export:
    def __init__(self, product, folder, stem, out):
        self.product = product
        self.contents = os.path.join(ROOT, product, 'Contents')
        self.folder, self.stem = folder, stem
        self.base = os.path.join(self.contents, folder)
        self.out = os.path.join(out, stem)
        self.furoku, self.lists = [], []
        self.headlines = Headlines(os.path.join(self.base, 'headline', 'headline.headlinestore'))
        self.short = Headlines(os.path.join(self.base, 'headline', 'short-headline.headlinestore'))
        self.mdd = None  # resource names already in the .mdd, when known

    # ----- paths -----
    def local(self, target):
        """A menu target inside this dictionary's folder → path relative to it (None for the sibling dictionary)."""
        if target.startswith(self.folder + '/'):
            return target[len(self.folder) + 1:]
        if '/' in target.split('.')[0] and target.split('/')[0] in [f for p in DICTS.values() for f, _ in p]:
            return None
        return target

    def page_path(self, rel):
        """Copies a page the .mdd lacks (with its images) and returns the path the app serves it at."""
        src = os.path.join(self.base, rel)
        if not os.path.exists(src):
            return None
        dst_rel = re.sub(r'\.xml$', '.html', rel)
        dst = os.path.join(self.out, 'files', dst_rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if rel.endswith(('.xml', '.html', '.htm')):
            h = open(src, encoding='utf-8').read()
            h = re.sub(r'href="([^"#:]+)\.xml', r'href="\1.html', h)
            open(dst, 'w', encoding='utf-8').write(h)
            for ref in re.findall(r'(?:src|href)="([^"#:$]+)"', h):
                if ref.endswith('.xml'):
                    continue
                rs = os.path.normpath(os.path.join(os.path.dirname(src), ref))
                rd = os.path.normpath(os.path.join(os.path.dirname(dst), ref))
                if os.path.isfile(rs) and rd.startswith(os.path.join(self.out, 'files')):
                    os.makedirs(os.path.dirname(rd), exist_ok=True)
                    shutil.copyfile(rs, rd)
        else:
            shutil.copyfile(src, dst)
        return dst_rel

    # ----- menus -----
    def add_page(self, group, title, rel, copy):
        path = self.page_path(rel) if copy else rel
        if not path:
            return
        self.group(group).append({'title': clean_title(title), 'path': path})

    def group(self, title):
        for g in self.furoku:
            if g['title'] == title:
                return g['items']
        self.furoku.append({'title': title, 'items': []})
        return self.furoku[-1]['items']

    def list_items(self, rel):
        rows = read_entries(os.path.join(self.base, rel))
        if not rows:
            return []
        out, seen = [], set()
        for page, item in rows:
            a = anchor(page, item)
            if a in seen:
                continue
            seen.add(a)
            raw = self.headlines.get(page, item)
            word, long_ = plain_headline(raw)
            long_ = long_.replace('［', '【').replace('］', '】') if re.match(r'^[^［]+［[^］]+］', long_) else long_
            short = plain_headline(self.short.get(page, item))[0] if self.short.map else ''
            word = short or re.sub(r'^.*?【(.*?)】.*$', r'\1', word)
            key = self.sort_key(word, long_, page)
            index = re.search(r'\{O:([^}]*)\}', raw)  # 日中: 赤い高粱{O:あ} is filed under あ
            if not key and index and self.KANJI.match(word):
                key = index.group(1) + word
            out.append([word, long_, a] + ([key] if key else []))
        return out

    KANJI = re.compile(r'[\u3400-\u9fff\uf900-\ufaff々〆]')

    def sort_key(self, word, head, page):
        """A kana key for あいう order when the headline has no reading (idioms under their page: 青は藍より… → あおは藍より…)."""
        if '【' in head or not self.KANJI.search(word):
            return ''
        parent = plain_headline(self.headlines.get(page, 0))[1].replace('［', '【').replace('］', '】')
        if '【' not in parent:
            return ''
        reading = re.sub(r'[\s‐・▽▼]', '', parent[:parent.index('【')])
        forms = re.sub(r'[▽▼（）()]', '', parent[parent.index('【') + 1:parent.rindex('】')]).split('・')
        for f in sorted(forms, key=len, reverse=True):
            if f and word.startswith(f):
                return reading + word[len(f):]
        return reading + word

    # ----- 朝鮮語辞典: Matrix/Table plists (Entries of {Title, PlistName} | {EntryID}, or EntriesFileName) -----
    def matrix(self, node, title, owner, trail=()):
        """owner None: this screen is a menu (索引); a child that holds words becomes a list, and its sub-screens its sections."""
        if node.get('EntriesFileName'):
            items = self.list_items('appendix/' + node['EntriesFileName'])
            if items:
                if owner is None:
                    self.lists.append({'title': title, 'sections': [{'title': '', 'items': items}]})
                else:
                    owner['sections'].append({'title': ' › '.join(trail), 'items': items})
            return
        if node.get('ContentsType') == 'PDF' and node.get('FileName'):
            self.add_page(title, title, 'appendix/' + node['FileName'] + '.pdf', True)
            return
        for child in node.get('Entries', []):
            if child.get('EntryID'):
                page = self.export_page(child['EntryID'])
                if page:
                    label = (child['KatsuyouNumber'] + ' ' if child.get('KatsuyouNumber') else '') + page[0]
                    self.group(title).append({'title': label, 'path': page[1]})
                continue
            name, sub_title = child.get('PlistName'), clean_title(child.get('Title', ''))
            p = os.path.join(self.base, 'appendix', f'{name}.plist')
            if not name or not os.path.exists(p):
                continue
            sub = plistlib.load(open(p, 'rb'))
            if owner is None and sub.get('Entries') and not any(c.get('EntryID') for c in sub['Entries']):
                lst = {'title': sub_title, 'sections': []}
                self.matrix(sub, sub_title, lst)
                if lst['sections']:
                    self.lists.append(lst)
            elif owner is None:
                self.matrix(sub, sub_title, None)
            else:
                self.matrix(sub, sub_title, owner, trail + (sub_title,))

    def export_page(self, entry_id):
        """An appendix page by Monokakido page id ('101177-0000-19') → (title, files/… path), from PAGES."""
        src_dir = PAGES.get(self.folder)
        if not src_dir or not os.path.isdir(src_dir):
            return None
        pid = int(entry_id.split('-')[0])
        for n in (pid - 1, pid):
            f = os.path.join(src_dir, f'{n:010d}.xml')
            if os.path.exists(f):
                h = open(f, encoding='utf-8').read()
                if n == pid - 1 or f'id="{pid}' in h:
                    break
        else:
            return None
        m = re.search(r'class="AppendixHeadline">(.*?)</div>', h) or re.search(r'class="見出し">(.*?)</span>', h)
        title = re.sub(r'<[^>]+>', '', m.group(1)).strip() if m else str(pid)
        h = re.sub(r'href="([^"/]+\.css)"', r'href="../\1"', h)
        rel = f'pages/{pid}.html'
        os.makedirs(os.path.join(self.out, 'files', 'pages'), exist_ok=True)
        open(os.path.join(self.out, 'files', rel), 'w', encoding='utf-8').write(h)
        return title, rel

    def walk(self, node, path, owner, copy):
        """path: menu titles above node; owner: the list that .entries leaves go into (None at menu level)."""
        if isinstance(node, list):
            for n in node:
                self.walk(n, path, owner, copy)
            return
        if not node:
            return
        title = clean_title(node.get('title') or node.get('name') or '')
        target = node.get('file') or node.get('contents') or ''
        if node.get('entry'):
            if self.folder != DICTS[self.product][0][0]:
                return
            page = self.export_page(node['entry'])
            if page:
                self.group(path[-1] if path else '付録').append({'title': title or page[0], 'path': page[1]})
                return
            self.group(path[-1] if path else '付録').append({'title': title, 'anchor': node['entry'].split('-')[0]})
            return
        rel = self.local(target) if target else None
        if target and rel is None:
            return
        if rel and '.' not in rel.rsplit('/', 1)[-1] and os.path.exists(os.path.join(self.base, 'appendix', rel + '.plist')):
            if self.folder == DICTS[self.product][0][0]:  # 朝鮮語辞典's own menu screens
                self.matrix(plistlib.load(open(os.path.join(self.base, 'appendix', rel + '.plist'), 'rb')), title, None)
            return
        if rel and rel.endswith('.plist'):
            p = os.path.join(self.base, rel)
            if not os.path.exists(p):
                return
            sub = plistlib.load(open(p, 'rb'))
            if owner is None and title in SKIP_LISTS:
                return
            if owner is None:
                owner = {'title': title, 'sections': []}
                self.walk(sub.get('children', sub) if isinstance(sub, dict) else sub, path + [title], owner, copy)
                if owner['sections']:
                    self.lists.append(owner)
            else:
                self.walk(sub.get('children', sub) if isinstance(sub, dict) else sub, path + [title], owner, copy)
            return
        if rel and rel.endswith('.entries'):
            if title in SKIP_LISTS:
                return
            items = self.list_items(rel)
            if not items:
                return
            if owner is None:
                self.lists.append({'title': title, 'sections': [{'title': '', 'items': items}]})
            else:
                sec = ' › '.join(path[path.index(owner['title']) + 1:] + [title]) if owner['title'] in path else title
                owner['sections'].append({'title': sec, 'items': items})
            return
        if rel and re.search(r'\.(html?|xml|pdf)$', rel):
            self.add_page(path[-1] if path else '付録', title, rel, copy)
            return
        if 'children' in node:
            self.walk(node['children'], path + [title] if title else path, owner, copy)

    # ----- kanji dictionaries: index/*.idx trees -----
    @staticmethod
    def idx_strings(path):
        d = open(path, 'rb').read()
        out, i = [], 0
        while i < len(d) - 4:
            n = int.from_bytes(d[i:i + 4], 'little')
            if 2 <= n < 600 and n % 2 == 0 and i + 4 + n <= len(d):
                try:
                    s = d[i + 4:i + 4 + n].decode('utf-16le')
                    if not any(ord(c) < 32 for c in s):
                        out.append(s)
                        i += 4 + n
                        continue
                except UnicodeDecodeError:
                    pass
            i += 2
        return out

    def idx_pairs(self, name):
        """(group, title, target) from an index tree: runs of titles followed by as many targets."""
        strings = self.idx_strings(os.path.join(self.base, 'index', name))
        is_target = lambda s: re.search(r'\.(html?|pdf|idx)$', s) is not None
        pairs, groups, titles, i = [], [], [], 0
        while i < len(strings):
            if not is_target(strings[i]):
                titles.append(strings[i]); i += 1; continue
            targets = []
            while i < len(strings) and is_target(strings[i]):
                targets.append(strings[i]); i += 1
            extra = len(titles) - len(targets)
            if extra > 0:
                groups.extend(titles[:extra]); titles = titles[extra:]
            group = groups.pop(0) if groups else ''
            pairs += [(group, t, f) for t, f in zip(titles[-len(targets):], targets)]
            titles = []
        return pairs

    def kanji_menu(self):
        # Top level: group = 凡例/付録; a sub-tree gets its own group named after it.
        for g, title, target in self.idx_pairs('Top.idx'):
            if target.endswith('.idx'):
                if re.match(r'(Oyaji|Jyukugo|Others)_', target):
                    continue
                for g2, t2, f2 in self.idx_pairs(target):
                    group = clean_title(title) + (f' · {g2}' if g2 else '')
                    if f2.endswith('.idx'):
                        for _, t3, f3 in self.idx_pairs(f2):
                            if not f3.endswith('.idx'):
                                self.add_page(group, f'{clean_title(t2)} {clean_title(t3)}', 'index/' + f3, False)
                    else:
                        self.add_page(group, t2, 'index/' + f2, False)
            else:
                self.add_page(g, title, 'index/' + target, False)
        # Kanken: 四字熟語, 故事・ことわざ, 熟字訓・当て字 as selections (word／reading records).
        for g, title, target in self.idx_pairs('Top.idx'):
            if target.startswith('Jyukugo_') and target != 'Jyukugo_All.idx':
                text = open(os.path.join(self.base, 'index', target), 'rb').read().decode('utf-16le', 'replace')
                recs = re.findall('／[^／\x00]*／([^／\x00]+)／([^／\x00]+)／', text)
                seen, items = set(), []
                for w, r in recs:
                    if (w, r) not in seen:
                        seen.add((w, r)); items.append([w, f'{r}【{w}】', ''])
                if items:
                    self.lists.append({'title': clean_title(title), 'sections': [{'title': '', 'items': items}]})

    def run(self, product):
        os.makedirs(self.out, exist_ok=True)
        if os.path.exists(os.path.join(self.base, 'index', 'Top.idx')):
            self.kanji_menu()
        for menu in ('Root.plist', 'Appendix.plist'):
            p = os.path.join(self.contents, menu)
            if os.path.exists(p):
                self.walk(plistlib.load(open(p, 'rb')), [], None, True)
        # Pages the .mdd already has are served from it; copied ones say so with a files/ prefix.
        for g in self.furoku:
            for it in g['items']:
                if 'path' in it and os.path.exists(os.path.join(self.out, 'files', it['path'])):
                    it['path'] = 'files/' + it['path']
        for css in os.listdir(self.base):
            if css.endswith('.css'):
                os.makedirs(os.path.join(self.out, 'files'), exist_ok=True)
                shutil.copyfile(os.path.join(self.base, css), os.path.join(self.out, 'files', css))
        manifest = {'version': 1, 'dictionary': product, 'furoku': self.furoku, 'lists': self.lists}
        json.dump(manifest, open(os.path.join(self.out, 'kotoba-extras.json'), 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
        n = sum(len(s['items']) for l in self.lists for s in l['sections'])
        print(f"{self.stem}: {sum(len(g['items']) for g in self.furoku)} furoku pages, {len(self.lists)} lists ({n} words)")


if __name__ == '__main__':
    out = sys.argv[1]
    for product, parts in DICTS.items():
        for folder, stem in parts:
            if os.path.isdir(os.path.join(ROOT, product, 'Contents', folder)):
                Export(product, folder, stem, out).run(product)
