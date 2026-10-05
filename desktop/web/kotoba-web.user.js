// ==UserScript==
// @name         Kotoba Web Lookup
// @namespace    app.kotoba.desktop
// @version      0.1.0
// @description  Hold Shift over Japanese, Chinese, Korean, Thai or Russian text on any page to look it up in Kotoba for Mac, like Yomitan: popups inside popups, ＋ Card. Firefox and Chrome (Tampermonkey).
// @match        *://*/*
// @exclude      *://127.0.0.1/*
// @exclude      *://localhost/*
// @exclude      https://www.youtube.com/watch*
// @exclude      https://music.youtube.com/*
// @exclude      https://www.netflix.com/*
// @exclude      https://www.viki.com/*
// @exclude      https://www.gagaoolala.com/*
// @run-at       document-idle
// @updateURL    none
// @downloadURL  none
// @noframes
// @grant        GM_xmlhttpRequest
// @grant        GM_getValue
// @grant        GM_setValue
// @grant        GM_registerMenuCommand
// @connect      127.0.0.1
// ==/UserScript==

/*
 * Page lookup, like Yomitan: with Shift held, the text under the pointer is sent to Kotoba for Mac (which must be open)
 * and its popup opens beside the word: your dictionaries' entries, frequency, known/card state, ＋ Card (the sentence
 * becomes the example, the page title and address the note). Hold Shift over a word inside an entry for another popup.
 * Only the text under the pointer is read, and only while Shift is held. The video helper (kotoba-video.user.js) covers
 * streaming sites' captions, so those sites are left out here. Tampermonkey's menu turns it off for a site.
 * The key and address are filled in when Kotoba makes the install link (Video › Browser page lookup).
 */
(() => {
  'use strict';
  if (document.getElementById('kotoba-web-pop')) return;
  const KEY = '__KOTOBA_KEY__', PORT = '__KOTOBA_PORT__';
  const store = { get: (k, d) => { try { const v = localStorage.getItem(k); return v === null ? d : v; } catch (e) { return d; } }, set: (k, v) => { try { localStorage.setItem(k, v); } catch (e) {} } };
  // Sites it's switched off for (Tampermonkey's storage, shared by every site).
  const offList = () => { try { return JSON.parse(GM_getValue('kotoba.web.off', '[]')); } catch (e) { return []; } };
  let off = offList().includes(location.hostname);
  try {
    GM_registerMenuCommand(off ? `Kotoba: turn on for ${location.hostname}` : `Kotoba: turn off for ${location.hostname}`, () => {
      const list = offList().filter(h => h !== location.hostname); if (!off) list.push(location.hostname);
      GM_setValue('kotoba.web.off', JSON.stringify(list)); location.reload();
    });
  } catch (e) {}
  if (off) return;
  // YouTube only accepts HTML through a Trusted Types policy in Chrome; Firefox takes plain strings.
  const policy = (() => {
    if (!window.trustedTypes || !trustedTypes.createPolicy) return null;
    for (const name of ['kotoba-helper', 'default']) { try { return trustedTypes.createPolicy(name, { createHTML: v => v }); } catch (e) {} }
    return null;
  })();
  const H = v => policy ? policy.createHTML(v) : v;

  // ---------- talking to Kotoba for Mac ----------
  function kotoba(route, body) {
    return new Promise((resolve, reject) => {
      GM_xmlhttpRequest({
        method: 'POST', url: `http://127.0.0.1:${PORT}/helper/${route}`, data: JSON.stringify(body || {}), timeout: 8000,
        headers: { 'Content-Type': 'application/json', 'X-Kotoba-Key': KEY },
        onload: r => {
          if (r.status === 403) return reject(new Error('This helper isn’t paired with Kotoba any more. Reinstall it from Kotoba › Video.'));
          try { const j = JSON.parse(r.responseText); j.error ? reject(new Error(j.error)) : resolve(j.data); } catch (e) { reject(new Error('Unexpected answer from Kotoba')); }
        },
        onerror: () => reject(new Error('Open Kotoba for Mac to look words up.')),
        ontimeout: () => reject(new Error('Kotoba didn’t answer in time.')),
      });
    });
  }


  const style = document.createElement('style');
  style.textContent = `
    .kotoba-pop { position: fixed; z-index: 2147483646; width: min(300px, 80vw); max-height: min(62vh, 520px); overflow: auto; pointer-events: auto;
      background: #fcfaf5; color: #1d211f; border-radius: 14px; box-shadow: 0 12px 40px rgba(0,0,0,.45); padding: 9px 12px 9px;
      font: 13px/1.5 -apple-system,BlinkMacSystemFont,sans-serif; user-select: text; -webkit-user-select: text; text-align: left; }
    .kotoba-pop[hidden] { display: none; }
    /* Compact by default (first dictionary, two lines, ＋ Card); .k-big shows everything. */
    .kotoba-pop.k-big { width: min(440px, 86vw); padding: 13px 15px 11px; font-size: 14px; line-height: 1.55; }
    .kotoba-pop .k-head { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }
    .kotoba-pop .k-word { font: 600 19px/1.3 "Hiragino Mincho ProN","Songti TC","AppleMyungjo",serif; }
    .kotoba-pop.k-big .k-word { font-size: 25px; }
    .kotoba-pop .k-key { font: 13px "Hiragino Mincho ProN","Songti TC",serif; color: #72766f; }
    .kotoba-pop.k-big .k-key { font-size: 15px; }
    .kotoba-pop .k-freq { font-size: 11.5px; color: #72766f; }
    .kotoba-pop .k-saved { margin-left: auto; color: #a8841e; font-size: 12px; font-weight: 600; }
    .kotoba-pop .k-size { align-self: center; margin-left: auto; border: 0; background: #ece7dc; color: #3d433f; border-radius: 7px; width: 26px; height: 26px; font-size: 17px; line-height: 26px; padding: 0; cursor: pointer; }
    .kotoba-pop .k-saved:not(:empty) + .k-size { margin-left: 6px; }
    .kotoba-pop .k-known { align-self: center; margin-left: 6px; border: 0; background: #ece7dc; color: #9a9d96; border-radius: 7px; width: 26px; height: 26px; font-size: 15px; font-weight: 700; line-height: 26px; padding: 0; cursor: pointer; }
    .kotoba-pop .k-known.on { background: #2f6b55; color: #fff; }
    .kotoba-pop .k-known + .k-size { margin-left: 6px; }
    .kotoba-pop .k-close { align-self: center; margin-left: 4px; border: 0; background: transparent; color: #72766f; border-radius: 7px; width: 26px; height: 26px; font-size: 21px; line-height: 24px; padding: 0; cursor: pointer; }
    .kotoba-pop .k-close:hover { background: #ece7dc; color: #1d211f; }
    .kotoba-pop .k-alt { margin-left: 8px; border: 1px solid #cfe0d6; background: #fff; color: #2f6b55; border-radius: 10px; padding: 0 8px; font: 600 11.5px/1.7 -apple-system,BlinkMacSystemFont,sans-serif; cursor: pointer; }
    .kotoba-pop .k-explain { color: #2f6b55; font-size: 12px; }
    .kotoba-pop .k-tabs { display: flex; gap: 5px; flex-wrap: wrap; margin-top: 7px; }
    .kotoba-pop .k-tab { font: 700 11px/1.9 -apple-system,BlinkMacSystemFont,sans-serif; letter-spacing: .02em; border: 0; background: transparent; border-radius: 9px; padding: 0 8px; color: #72766f; cursor: pointer; }
    .kotoba-pop .k-tab:hover { background: #ece7dc; }
    .kotoba-pop .k-tab.on { background: #e2ece5; color: #2f6b55; }
    .kotoba-pop:not(.k-big) .k-tabs { flex-wrap: nowrap; overflow-x: auto; scrollbar-width: none; margin-top: 5px; gap: 3px; }
    .kotoba-pop:not(.k-big) .k-tabs::-webkit-scrollbar { display: none; }
    .kotoba-pop:not(.k-big) .k-tab { flex: none; font-size: 10.5px; padding: 0 6px; line-height: 1.8; }
    .kotoba-pop:not(.k-big) .k-more { display: none; }
    .kotoba-pop .k-entry { margin: 4px -6px 0; }
    .kotoba-pop .k-entry iframe { display: block; width: 100%; height: 60px; border: 0; background: transparent; }
    .kotoba-pop.k-big .k-entry { margin-top: 6px; border-top: 1px solid #ebe5da; padding-top: 4px; }
    .kotoba-pop.k-child { z-index: 2147483647; box-shadow: 0 10px 34px rgba(0,0,0,.5); outline: 1px solid #e3ddd1; }
    .kotoba-pop .k-actions { display: flex; gap: 6px; margin-top: 7px; flex-wrap: wrap; }
    .kotoba-pop.k-big .k-actions { margin-top: 11px; }
    .kotoba-pop .k-actions button { font: 600 12px -apple-system,BlinkMacSystemFont,sans-serif; border: 0; background: #e2ece5; color: #2f6b55; border-radius: 8px; padding: 4px 10px; cursor: pointer; }
    .kotoba-pop.k-big .k-actions button { font-size: 12.5px; border-radius: 9px; padding: 6px 11px; }
    .kotoba-pop .k-actions button:first-child { background: #2f6b55; color: #fff; }
    .kotoba-pop .k-newfolder { display: inline-flex; gap: 4px; }
    .kotoba-pop .k-newfolder input { font: 12px -apple-system,BlinkMacSystemFont,sans-serif; border: 1px solid #9fc1ae; border-radius: 8px; padding: 3px 7px; width: 130px; outline: none; background: #fff; color: #1d211f; }
    .kotoba-pop .k-newfolder button { font: 600 12px -apple-system,BlinkMacSystemFont,sans-serif; border: 0; background: #2f6b55; color: #fff; border-radius: 8px; padding: 4px 10px; cursor: pointer; }
    .kotoba-pop .k-folder { font: 600 12px -apple-system,BlinkMacSystemFont,sans-serif; border: 1px solid #cfdcd3; background: #fff; color: #2f6b55; border-radius: 8px; padding: 3px 6px; max-width: 150px; cursor: pointer; }
    .kotoba-pop .k-note { color: #72766f; font-size: 13px; }
  `;
  document.documentElement.appendChild(style);

  const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  let stayOpen = true, pinned = false;
  // Where "the word" is, for messages and ＋ Card: popup messages hang from the last popup position.
  const text = { lang: '', getBoundingClientRect: () => (main.rect ? { left: main.rect.left, right: main.rect.right, top: main.rect.top, bottom: main.rect.bottom } : { left: 20, right: 20, top: 20, bottom: 20 }) };
  const pop = document.createElement('div');
  pop.id = 'kotoba-web-pop'; pop.className = 'kotoba-pop'; pop.hidden = true;
  (document.body || document.documentElement).appendChild(pop);
  let lookupAt = '';
  function showMessage(message, anchor) {
    closeFrom(1);
    pop.innerHTML = H(`<div class="k-note">${esc(message)}</div>`); pop.classList.remove('k-big');
    pop.hidden = false; main.rect = anchor.getBoundingClientRect(); place(main);
  }
  function hidePop() {
    closeFrom(1);
    if (pop.hidden) return;
    pop.hidden = true; pinned = false; main.res = null; lookupAt = '';
    try { CSS.highlights.delete('kotoba-page'); } catch (e) {}
  }
  const hideAuto = () => { if (!stayOpen) hidePop(); };
  const inPopup = t => !!(t && t.closest && t.closest('.kotoba-pop'));

  const shortName = n => String(n).replace(/\s*[\[(（][\d\-. v]+[\])）]\s*$/, '').replace(/^(小学館|三省堂|研究社|大修館|旺文社)\s*/, '').replace(/\s*第.版$/, '').slice(0, 16);
  let groups = null;
  // Other readings of the same form (걸었다고: 걷다 or 걸다); only the context can decide.
  const alts = res => (res.forms || []).filter(f => f.base && f.base !== res.key && f.items && f.items.length).slice(0, 2);
  const big = () => store.get('kotoba.popBig', '0') === '1';
  // The folder new cards go to: chosen in the popup, remembered across sites (Tampermonkey's storage, not the page's).
  const cardFolder = () => { try { return +GM_getValue('kotoba.folder', 1) || 1; } catch (e) { return +store.get('kotoba.folder', '1') || 1; } };
  const setCardFolder = (id) => { try { GM_setValue('kotoba.folder', id); } catch (e) { store.set('kotoba.folder', String(id)); } };
  let foldersCache = null;
  /** "＋ New folder…": a name box in place of the menu; Enter makes the folder in Kotoba and picks it, Esc goes back. */
  function newFolderBox(sel) {
    const box = document.createElement('span'); box.className = 'k-newfolder';
    box.innerHTML = H('<input placeholder="New folder name" maxlength="100"><button>Add</button>');
    sel.replaceWith(box);
    const input = box.querySelector('input'); input.focus();
    const done = (id) => { box.replaceWith(sel); if (id) setCardFolder(id); fillFolders(sel, true); };
    const add = async () => {
      const name = input.value.trim(); if (!name) { done(null); return; }
      try { const r = await kotoba('folder.save', { name }); done(r.id); } catch (err) { input.value = ''; input.placeholder = err.message; }
    };
    box.querySelector('button').addEventListener('click', e => { e.stopPropagation(); add(); });
    input.addEventListener('keydown', e => { e.stopPropagation(); if (e.key === 'Enter') add(); if (e.key === 'Escape') done(null); });
  }
  function fillFolders(sel, fresh) {
    if (!foldersCache || fresh) foldersCache = kotoba('folders').catch(() => null);
    foldersCache.then(list => {
      if (!list || !sel.isConnected) return;
      const now = cardFolder(), id = list.some(f => f.id === now) ? now : (list[0] || { id: 1 }).id;
      sel.innerHTML = H(list.map(f => `<option value="${f.id}"${f.id === id ? ' selected' : ''}>${esc(f.name)}</option>`).join('') + '<option value="new">＋ New folder…</option>');
    });
  }

  // Popups stack: stack[0] is the caption word's; a word hovered inside an entry opens the next one.

  const main = { el: pop, level: 0 };
  const stack = [main];
  function closeFrom(level) { while (stack.length > level) stack.pop().el.remove(); }
  function popupAt(level) {
    closeFrom(level + 1);
    if (stack[level]) return stack[level];
    const el = document.createElement('div'); el.className = 'kotoba-pop k-child'; el.hidden = true;
    (document.fullscreenElement || document.body).appendChild(el);
    const P = { el, level }; stack[level] = P; wire(P); return P;
  }
  function showPop(res, rect, P) {
    P.res = res; P.rect = rect; closeFrom(P.level + 1);
    const byDict = []; for (const it of res.items) if (!byDict.some(x => x.dict === it.dict) && it.kind !== 'kanji') byDict.push(it);
    const items = byDict.slice(0, 5);
    const written = res.written && res.written !== res.key ? res.written : res.key;
    P.el.innerHTML = H(`<div class="k-head"><b class="k-word">${esc(written)}</b>${written !== res.key ? `<span class="k-key">${esc(res.key)}</span>` : ''}<span class="k-freq"></span><span class="k-saved"></span><button class="k-known" data-k="known" title="Mark as known">✓</button><button class="k-size" data-k="size" title="Bigger / smaller popup">${big() ? '⤡' : '⤢'}</button><button class="k-close" data-k="close" title="Close (Esc)">×</button></div>
      ${res.explain || alts(res).length ? `<div class="k-explain">${esc(res.explain || '')}${alts(res).map((f, j) => `<button class="k-alt" data-k="alt" data-j="${j}" title="${esc(f.explain || '')}">or ${esc(f.base)}</button>`).join('')}</div>` : ''}
      <div class="k-tabs">${items.map((it, n) => `<button class="k-tab${n ? '' : ' on'}" data-k="tab" data-n="${n}">${esc(shortName(it.dictionary))}</button>`).join('')}</div>
      <div class="k-entry"></div>
      <div class="k-actions"><button data-k="card">＋ Card</button><select class="k-folder" title="Folder for new cards"><option value="${cardFolder()}">…</option></select><button class="k-more" data-k="open">Open in Kotoba</button><button class="k-more" data-k="copy">Copy</button></div>`);
    P.el.classList.toggle('k-big', big());
    P.items = items;
    fillFolders(P.el.querySelector('.k-folder'), false);
    P.el.hidden = false; place(P);
    const kb = P.el.querySelector('.k-known');
    kotoba('known.get', { word: res.key, dict: items[0] ? items[0].dict : 0, lang: P.lang || '' }).then(k => paintKnown(kb, k)).catch(() => { kb.hidden = true; });
    showEntry(P, 0);
    Promise.all([kotoba('freq', { key: res.key, reading: '' }), groups || (groups = kotoba('dicts').then(ds => Object.fromEntries(ds.map(d => [d.id, (d.grp || 'Japanese').split('/')[0]]))))]).then(([f, g]) => {
      if (P.res !== res) return;
      // Only frequency lists in the word's language.
      const want = { ja: 'Japanese', zh: 'Chinese', ko: 'Korean', th: 'Thai', ru: 'Russian' }[P.lang];
      const fq = f.find(x => x.mode === 'freq' && g[x.dict] === want);
      if (fq) P.el.querySelector('.k-freq').textContent = `#${fq.display}`;
    }).catch(() => {});
    kotoba('item.similar', { headword: written, reading: '' }).then(s => { if (P.res === res && s.length) P.el.querySelector('.k-saved').textContent = '★ ' + s[0].folder; }).catch(() => {});
  }

  // The entry as the dictionary lays it out, with its styles and images sent by Kotoba (the page can't load them itself).
  const entries = new Map();
  function entryHtml(rec) {
    if (!entries.has(rec)) { entries.set(rec, kotoba('entry', { rec }).then(r => r.html).catch(e => { entries.delete(rec); throw e; })); if (entries.size > 60) entries.delete(entries.keys().next().value); }
    return entries.get(rec);
  }
  async function showEntry(P, n) {
    const it = P.items && P.items[n]; if (!it) return;
    P.el.querySelectorAll('.k-tab').forEach(t => t.classList.toggle('on', +t.dataset.n === n));
    const box = P.el.querySelector('.k-entry'), res = P.res;
    let html;
    try { html = await entryHtml(it.rec); } catch (e) { if (P.res === res) box.textContent = e.message; return; }
    if (P.res !== res) return;
    const f = document.createElement('iframe');
    f.setAttribute('sandbox', 'allow-same-origin');
    box.replaceChildren(f);
    f.addEventListener('load', () => {
      const d = f.contentDocument; if (!d || !d.body) return;
      wireFrame(P, f);
      fitFrame(P); setTimeout(() => fitFrame(P), 250);
      // A page with several words (大辞林 けんのう: 献納, 権能) opens at the heading of the one looked up.
      const clean = v => String(v || '').replace(/[\s・‐\-▽▼×]/g, '');
      const want = clean(res.key), heads = [...d.querySelectorAll('[data-name*="見出"],[data-name*="表記"],[data-name="headword"],.yt-word,.headword,h1,h2,h3')];
      const h = want && heads.find(e => clean(e.textContent).includes(want));
      if (h) { const r = h.getBoundingClientRect(); if (r.top > 40) f.contentWindow.scrollTo(0, r.top - 8); }
    });
    f.srcdoc = H(html.replace('</head>', '<style>body.kotoba-entry{padding:2px 6px 8px!important;font-size:14.5px}::highlight(kotoba){background-color:rgba(124,194,160,.5)}</style></head>'));
  }
  function fitFrame(P) {
    const f = P.el.querySelector('.k-entry iframe'); if (!f || !f.contentDocument) return;
    const cap = P.el.classList.contains('k-big') ? Math.min(440, innerHeight * .5) : 170;
    f.style.height = `${Math.min(cap, f.contentDocument.documentElement.scrollHeight)}px`;
    place(P);
  }
  function caretAt(d, x, y) {
    if (d.caretPositionFromPoint) { const c = d.caretPositionFromPoint(x, y); return c && { node: c.offsetNode, off: c.offset }; }
    if (d.caretRangeFromPoint) { const r = d.caretRangeFromPoint(x, y); return r && { node: r.startContainer, off: r.startOffset }; }
    return null;
  }
  // Hovering a word in an entry with Shift opens the next popup for it.
  let lastFrame = null, frameTimer = 0, frameTok = null;
  function wireFrame(P, f) {
    const d = f.contentDocument; let last = null;
    const probe = (x, y, key) => {
      if (!key) return;
      const c = caretAt(d, x, y); if (!c || !c.node || c.node.nodeType !== 3) return;
      if (last && last.node === c.node && last.off === c.off) return;
      last = c;
      clearTimeout(frameTimer); frameTimer = setTimeout(() => lookupInFrame(P, f, c.node, c.off), 90);
    };
    d.addEventListener('mousemove', e => { lastFrame = { f, x: e.clientX, y: e.clientY, probe }; probe(e.clientX, e.clientY, e.shiftKey); });
    d.addEventListener('keydown', e => { if (e.key === 'Shift' && lastFrame && lastFrame.f === f) probe(lastFrame.x, lastFrame.y, true); });
    d.addEventListener('click', e => { pinned = true; const a = e.target.closest && e.target.closest('a[href]'); if (a) e.preventDefault(); });
    // Or select a word in the entry: the next popup looks up exactly what was selected.
    d.addEventListener('mouseup', () => setTimeout(() => {
      const sel = d.getSelection(); if (!sel || sel.isCollapsed || !sel.rangeCount) return;
      const str = sel.toString().replace(/\s+/g, ''); if (!str || str.length > 24) return;
      pinned = true;
      lookupRange(P, f, sel.getRangeAt(0).cloneRange(), str);
    }, 10));
  }
  async function lookupRange(P, f, range, str) {
    const lang = textLang(str);
    const tok = {}; frameTok = tok;
    let res; try { res = await kotoba('lookup', { text: str, lang: lang === 'zh' || lang === 'th' ? lang : '' }); } catch (e) { return; }
    if (frameTok !== tok) return;
    if (!res.items.length) { showMessage(`No entry for “${str}”`, text); return; }
    try { f.contentWindow.CSS.highlights.set('kotoba', new f.contentWindow.Highlight(range)); } catch (e) {}
    try { f.contentDocument.getSelection().removeAllRanges(); } catch (e) {}
    res.written = str; res.line = main.res && main.res.line;
    const fr = f.getBoundingClientRect(), rects = range.getClientRects();
    const a = rects[0] || range.getBoundingClientRect(), z = rects[rects.length - 1] || a;
    const C = popupAt(P.level + 1); C.lang = lang;
    showPop(res, { left: a.left + fr.left, right: z.right + fr.left, top: a.top + fr.top, bottom: z.bottom + fr.top }, C);
  }
  function textLang(t) {
    const c = t.trim()[0] || '';
    if (/[가-힣]/u.test(c)) return 'ko';
    if (/[ぁ-ヿ]/u.test(c)) return 'ja';
    if (/[ก-๛]/u.test(c)) return 'th';
    if (/[А-ӿ]/u.test(c)) return 'ru';
    if (/[一-鿿]/u.test(c)) return '' === 'zh' ? 'zh' : 'ja';
    return '';
  }
  async function lookupInFrame(P, f, node, off) {
    const d = f.contentDocument;
    // Up to 24 characters from the pointer on, across text nodes (ruby readings left out).
    const walker = d.createTreeWalker(d.body, NodeFilter.SHOW_TEXT, { acceptNode: n => n.parentElement && n.parentElement.closest('rt,rp,script,style') ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT });
    walker.currentNode = node;
    const parts = []; let str = '', n = node, o = off;
    while (n && str.length < 24) { const t = n.nodeValue.slice(o); if (t) parts.push({ n, o, len: t.length }); str += t; n = walker.nextNode(); o = 0; }
    str = str.slice(0, 24);
    if (!str.trim() || /^[\s\p{P}\d]/u.test(str)) return;
    const tok = {}; frameTok = tok;
    const lang = textLang(str);
    let res; try { res = await kotoba('lookup', { text: str, lang: lang === 'zh' || lang === 'th' ? lang : '' }); } catch (e) { return; }
    if (frameTok !== tok || !res.items.length) return;
    const range = d.createRange(); range.setStart(parts[0].n, parts[0].o);
    let rem = (res.matched || str[0]).length;
    for (const p of parts) { if (rem <= p.len) { range.setEnd(p.n, p.o + rem); break; } rem -= p.len; }
    try { f.contentWindow.CSS.highlights.set('kotoba', new f.contentWindow.Highlight(range)); } catch (e) {}
    res.written = range.toString(); res.line = main.res && main.res.line;
    const fr = f.getBoundingClientRect(), rects = range.getClientRects();
    const a = rects[0] || range.getBoundingClientRect(), z = rects[rects.length - 1] || a;
    const C = popupAt(P.level + 1); C.lang = lang;
    showPop(res, { left: a.left + fr.left, right: z.right + fr.left, top: a.top + fr.top, bottom: z.bottom + fr.top }, C);
  }
  function place(P) {
    const r = P.rect; if (!r) return;
    const el = P.el, w = el.offsetWidth, h = el.offsetHeight;
    if (P.level) {
      el.style.left = `${Math.min(innerWidth - w - 10, Math.max(10, r.left - 20))}px`;
      let top = r.bottom + 8; if (top + h > innerHeight - 10) top = Math.max(10, r.top - h - 8);
      el.style.top = `${top}px`; return;
    }
    el.style.left = `${Math.min(innerWidth - w - 10, Math.max(10, (r.left + r.right) / 2 - w / 2))}px`;
    let top = r.top - h - 12; if (top < 10) top = Math.min(innerHeight - h - 10, r.bottom + 12);
    el.style.top = `${top}px`;
  }

  function wire(P) {
    const el = P.el;
    el.addEventListener('mouseleave', e => { if (!pinned && !inPopup(e.relatedTarget) ) hideAuto(); });
    // The site mustn't treat clicks and keys in the popup as player controls.
    for (const type of ['click', 'mousedown', 'mouseup', 'dblclick', 'keydown']) el.addEventListener(type, e => e.stopPropagation());
    el.addEventListener('click', e => { if (e.target.closest('.k-folder,.k-newfolder')) { pinned = true; return; } onPopClick(P, e); });
    // Opening the folder menu mustn't close the popup (the menu sits outside it), and the choice is remembered.
    el.addEventListener('mousedown', e => { const f = e.target.closest('.k-folder'); if (f) { pinned = true; fillFolders(f, true); } });
    el.addEventListener('change', e => { const f = e.target.closest('.k-folder'); if (!f) return; if (f.value === 'new') newFolderBox(f); else setCardFolder(+f.value); });
  }
  wire(main);
  function paintKnown(b, k) { b.classList.toggle('on', !!k.known); b.title = k.how === 'card' ? 'Known (a learned card)' : k.known ? 'Marked known — click to unmark' : 'Mark as known'; }
  async function onPopClick(P, e) {
    pinned = true;
    const b = e.target.closest('[data-k]'); if (!b || !P.res) return;
    const res = P.res, it = P.items && P.items[0];
    if (b.dataset.k === 'close') {
      // × closes this popup and the ones opened from it.
      if (P.level) { closeFrom(P.level); const f = stack[P.level - 1].el.querySelector('.k-entry iframe'); try { f.contentWindow.CSS.highlights.delete('kotoba'); } catch (err) {} }
      else hidePop();
      return;
    }
    if (b.dataset.k === 'known') {
      kotoba('known.set', { word: res.key, dict: it ? it.dict : 0, lang: P.lang || '', known: !b.classList.contains('on') }).then(k => paintKnown(b, k)).catch(err => showMessage(err.message, text));
      return;
    }
    if (b.dataset.k === 'tab') { closeFrom(P.level + 1); showEntry(P, +b.dataset.n); return; }
    if (b.dataset.k === 'alt') {
      const f = alts(res)[+b.dataset.j]; if (!f) return;
      const now = (res.forms || []).find(x => x.base === res.key) || { base: res.key, explain: res.explain, items: res.items };
      showPop({ ...res, key: f.base, explain: f.explain || '', items: (f.items || []).concat(f.extra || []), forms: [f, now, ...(res.forms || []).filter(x => x !== f && x !== now)] }, P.rect, P);
      return;
    }
    if (b.dataset.k === 'size') {
      store.set('kotoba.popBig', big() ? '0' : '1');
      for (const Q of stack) { Q.el.classList.toggle('k-big', big()); const s = Q.el.querySelector('.k-size'); if (s) s.textContent = big() ? '⤡' : '⤢'; fitFrame(Q); place(Q); }
      return;
    }
    if (b.dataset.k === 'copy') { navigator.clipboard.writeText(res.written || res.key).catch(() => {}); b.textContent = 'Copied'; }
    if (b.dataset.k === 'open') kotoba('show', { word: res.key }).catch(err => showMessage(err.message, text));
    if (b.dataset.k === 'card' && it) {
      try {
        const g = (await kotoba('gloss.rec', { recs: [it.rec], max: 2000 }))[0];
        const headword = P.lang === 'zh' && res.written && !P.level ? res.written : res.key;
        const reading = it.page && it.page !== res.key && /^[぀-ヿ가-힣a-zāáǎàēéěèīíǐìōóǒòūúǔùü\s0-9]+$/i.test(it.page) ? it.page : '';
                await kotoba('item.save', { folder_id: +(P.el.querySelector('.k-folder') || {}).value || cardFolder(), headword, reading, back: g.text, dict: it.dict, dict_name: it.dictionary, page: it.page || res.key,
          kind: 'entry', context: res.line || '', note: `${document.title} · ${location.href}`, review: true });
        P.el.querySelector('.k-saved').textContent = '★ saved'; b.textContent = 'Saved';
      } catch (err) { showMessage(err.message, text); }
    }
  }


  // ---------- hovering the page ----------
  const hl = document.createElement('style');
  hl.textContent = '::highlight(kotoba-page){background-color:rgba(124,194,160,.5);}';
  document.documentElement.appendChild(hl);
  let shift = false, at = null, pageTimer = 0, lastSeen = null, tok = null;
  const skip = n => !!(n.parentElement && n.parentElement.closest('rt,rp,script,style,textarea,input,select,noscript,[contenteditable="true"],.kotoba-pop'));
  /** The text node and offset under the pointer, only when the pointer is on the glyph itself (not merely nearest to it). */
  function pointedChar(x, y) {
    const c = caretAt(document, x, y);
    if (!c || !c.node || c.node.nodeType !== 3 || skip(c.node)) return null;
    const len = c.node.nodeValue.length;
    for (const off of [c.off, c.off - 1]) {
      if (off < 0 || off >= len) continue;
      const r = document.createRange(); r.setStart(c.node, off); r.setEnd(c.node, off + 1);
      for (const b of r.getClientRects()) if (x >= b.left - 1 && x <= b.right + 1 && y >= b.top - 1 && y <= b.bottom + 1) return { node: c.node, off };
    }
    return null;
  }
  const blockOf = n => { const e = n.parentElement; return e && (e.closest('p,li,td,th,dd,dt,blockquote,h1,h2,h3,h4,h5,h6,article,section,div') || e); };
  async function probe() {
    if (!shift || !at) return;
    const p = pointedChar(at.x, at.y);
    if (!p) return;
    if (lastSeen && lastSeen.node === p.node && lastSeen.off === p.off) return;
    lastSeen = p;
    // Up to 24 characters from the pointer on, across text nodes (ruby readings left out).
    const walker = document.createTreeWalker(blockOf(p.node) || document.body, NodeFilter.SHOW_TEXT, { acceptNode: n => skip(n) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT });
    walker.currentNode = p.node;
    const parts = []; let str = '', n = p.node, o = p.off;
    while (n && str.length < 24) { const t = n.nodeValue.slice(o); if (t) parts.push({ n, o, len: t.length }); str += t; n = walker.nextNode(); o = 0; }
    str = str.slice(0, 24);
    // Only the scripts Kotoba has dictionaries for; Latin text is left alone.
    if (!str.trim() || !/^[\u3040-\u30ff\u3400-\u9fff\uf900-\ufaff\uac00-\ud7af\u0e00-\u0e7f\u0400-\u04ff]/.test(str)) return;
    const lang = textLang(str);
    const mine = tok = {};
    let res; try { res = await kotoba('lookup', { text: str, lang: lang === 'zh' || lang === 'th' ? lang : '' }); } catch (e) { if (tok === mine) showMessage(e.message, { getBoundingClientRect: () => ({ left: at.x, right: at.x, top: at.y, bottom: at.y }) }); return; }
    if (tok !== mine || !res.items.length) return;
    const range = document.createRange(); range.setStart(parts[0].n, parts[0].o);
    let rem = Array.from(res.matched || str[0]).join('').length;
    for (const q of parts) { if (rem <= q.len) { range.setEnd(q.n, q.o + rem); break; } rem -= q.len; }
    try { CSS.highlights.set('kotoba-page', new Highlight(range)); } catch (e) {}
    res.written = range.toString();
    const block = blockOf(p.node), whole = block ? block.textContent.replace(/\s+/g, ' ').trim() : '';
    const i = whole.indexOf(res.written);
    res.line = whole.length > 160 && i >= 0 ? whole.slice(Math.max(0, i - 70), i + 90) : whole.slice(0, 200);
    const rects = range.getClientRects(), a = rects[0] || range.getBoundingClientRect(), z = rects[rects.length - 1] || a;
    main.lang = lang;
    showPop(res, { left: a.left, right: z.right, top: a.top, bottom: z.bottom }, main);
  }
  addEventListener('mousemove', e => {
    shift = e.shiftKey; at = { x: e.clientX, y: e.clientY };
    if (!shift || inPopup(e.target)) return;
    clearTimeout(pageTimer); pageTimer = setTimeout(probe, 70);
  }, true);
  addEventListener('keydown', e => {
    if (e.key === 'Shift') { shift = true; if (lastFrame && lastFrame.f.isConnected && lastFrame.f.matches(':hover')) lastFrame.probe(lastFrame.x, lastFrame.y, true); else { lastSeen = null; clearTimeout(pageTimer); pageTimer = setTimeout(probe, 40); } }
    if (e.key === 'Escape') hidePop();
  }, true);
  addEventListener('keyup', e => { if (e.key === 'Shift') shift = false; }, true);
  // A click elsewhere closes the popups; a click inside one keeps them (see wire).
  addEventListener('mousedown', e => { if (!pop.hidden && !inPopup(e.target)) hidePop(); }, true);
  // Dragging across text with Shift held looks up exactly the selection.
  addEventListener('mouseup', e => {
    if (!e.shiftKey || inPopup(e.target)) return;
    setTimeout(async () => {
      const sel = getSelection(); if (!sel || sel.isCollapsed || !sel.rangeCount) return;
      const str = sel.toString().replace(/\s+/g, ''); if (!str || str.length > 24) return;
      const range = sel.getRangeAt(0).cloneRange(), lang = textLang(str), mine = tok = {};
      let res; try { res = await kotoba('lookup', { text: str, lang: lang === 'zh' || lang === 'th' ? lang : '' }); } catch (err) { return; }
      if (tok !== mine || !res.items.length) return;
      sel.removeAllRanges(); pinned = true;
      res.written = str; res.line = (blockOf(range.startContainer) || {}).textContent ? blockOf(range.startContainer).textContent.replace(/\s+/g, ' ').trim().slice(0, 200) : '';
      const rects = range.getClientRects(), a = rects[0] || range.getBoundingClientRect(), z = rects[rects.length - 1] || a;
      main.lang = lang; showPop(res, { left: a.left, right: z.right, top: a.top, bottom: z.bottom }, main);
    }, 10);
  }, true);
})();
