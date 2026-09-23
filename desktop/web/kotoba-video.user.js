// ==UserScript==
// @name         Kotoba Video Text
// @namespace    app.kotoba.desktop
// @version      0.3.1
// @description  Look up YouTube and GagaOOLala subtitles in Kotoba for Mac: hold Shift over a word.
// @match        https://www.youtube.com/watch*
// @match        https://www.gagaoolala.com/*/videos/*
// @run-at       document-idle
// @noframes
// @grant        GM_xmlhttpRequest
// @connect      127.0.0.1
// ==/UserScript==

/*
 * Draws the site's current caption as text you can hover. Holding Shift over a word asks Kotoba for Mac (which
 * must be open) for that word: the popup shows your dictionaries' definitions, frequency and whether it's already
 * a card, and ＋ Card saves it with the caption line as the example. Only the caption text shown on the page is read.
 * The key and address are filled in when Kotoba makes the install link (Video › Firefox subtitle helper).
 */
(() => {
  'use strict';
  if (document.getElementById('kotoba-video-ui')) return;
  const KEY = '__KOTOBA_KEY__', PORT = '__KOTOBA_PORT__';

  const youtube = location.hostname === 'www.youtube.com';
  const sourceSelector = youtube ? '.ytp-caption-window-container' : '.bmpui-ui-subtitle-overlay';
  const store = { get: (k, d) => { try { const v = localStorage.getItem(k); return v === null ? d : v; } catch (e) { return d; } }, set: (k, v) => { try { localStorage.setItem(k, v); } catch (e) {} } };
  let enabled = store.get('kotoba.videoText.enabled', 'true') !== 'false';
  let language = store.get('kotoba.videoText.language', 'auto');
  let pauseOnLookup = store.get('kotoba.videoText.pause', 'true') !== 'false';
  let concealed = null, originalVisibility = '', originalPriority = '', lastText = '';

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

  // ---------- layout ----------
  const style = document.createElement('style');
  style.textContent = `
    #kotoba-video-ui { position: fixed; z-index: 2147483645; pointer-events: none; font-family: -apple-system, BlinkMacSystemFont, sans-serif; }
    #kotoba-video-ui * { box-sizing: border-box; }
    #kotoba-video-toolbar { display: flex; align-items: center; gap: 5px; justify-content: flex-end; }
    #kotoba-video-toolbar button, #kotoba-video-toolbar select { pointer-events: auto; border: 1px solid rgba(255,255,255,.25); border-radius: 7px; background: rgba(19,23,21,.88); color: #fff; font: 12px -apple-system,BlinkMacSystemFont,sans-serif; padding: 4px 7px; cursor: pointer; }
    #kotoba-video-toolbar button[aria-pressed="true"] { color: #a8e1c4; }
    #kotoba-video-line { position: absolute; left: 0; right: 0; bottom: 24px; text-align: center; pointer-events: none; }
    #kotoba-video-text { display: inline-block; max-width: 94%; padding: 3px 8px; border-radius: 6px; background: rgba(0,0,0,.58); color: white; font: 600 28px/1.4 -apple-system,BlinkMacSystemFont,"PingFang TC","Hiragino Sans","Apple SD Gothic Neo",sans-serif; text-shadow: 0 1px 3px #000; white-space: pre-wrap; user-select: text !important; -webkit-user-select: text !important; cursor: text; pointer-events: auto; }
    #kotoba-video-text:empty { display: none; }
    #kotoba-video-text .k-ch { border-radius: 4px; }
    #kotoba-video-text .k-ch.k-hl { background: rgba(124,194,160,.5); box-shadow: 0 0 0 2px rgba(124,194,160,.5); }
    #kotoba-video-pop { position: fixed; z-index: 2147483646; width: min(300px, 80vw); max-height: min(62vh, 520px); overflow: auto; pointer-events: auto;
      background: rgba(252,250,245,.98); color: #1d211f; border-radius: 14px; box-shadow: 0 12px 40px rgba(0,0,0,.45); padding: 9px 12px 9px;
      font: 13px/1.5 -apple-system,BlinkMacSystemFont,sans-serif; user-select: text; -webkit-user-select: text; text-align: left; }
    #kotoba-video-pop[hidden] { display: none; }
    /* Compact by default (first dictionary, two lines, ＋ Card); .k-big shows everything. */
    #kotoba-video-pop.k-big { width: min(440px, 86vw); padding: 13px 15px 11px; font-size: 14px; line-height: 1.55; }
    #kotoba-video-pop .k-head { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }
    #kotoba-video-pop .k-word { font: 600 19px/1.3 "Hiragino Mincho ProN","Songti TC","AppleMyungjo",serif; }
    #kotoba-video-pop.k-big .k-word { font-size: 25px; }
    #kotoba-video-pop .k-key { font: 13px "Hiragino Mincho ProN","Songti TC",serif; color: #72766f; }
    #kotoba-video-pop.k-big .k-key { font-size: 15px; }
    #kotoba-video-pop .k-freq { font-size: 11.5px; color: #72766f; }
    #kotoba-video-pop .k-saved { margin-left: auto; color: #a8841e; font-size: 12px; font-weight: 600; }
    #kotoba-video-pop .k-size { align-self: center; margin-left: auto; border: 0; background: #ece7dc; color: #3d433f; border-radius: 7px; width: 26px; height: 26px; font-size: 17px; line-height: 26px; padding: 0; cursor: pointer; }
    #kotoba-video-pop .k-saved:not(:empty) + .k-size { margin-left: 6px; }
    #kotoba-video-pop .k-close { align-self: center; margin-left: 4px; border: 0; background: transparent; color: #72766f; border-radius: 7px; width: 26px; height: 26px; font-size: 21px; line-height: 24px; padding: 0; cursor: pointer; }
    #kotoba-video-pop .k-close:hover { background: #ece7dc; color: #1d211f; }
    #kotoba-video-pop .k-alt { margin-left: 8px; border: 1px solid #cfe0d6; background: #fff; color: #2f6b55; border-radius: 10px; padding: 0 8px; font: 600 11.5px/1.7 -apple-system,BlinkMacSystemFont,sans-serif; cursor: pointer; }
    #kotoba-video-pop .k-explain { color: #2f6b55; font-size: 12px; }
    #kotoba-video-pop .k-dict { font-size: 11px; font-weight: 700; color: #2f6b55; margin-top: 5px; }
    #kotoba-video-pop.k-big .k-dict { margin-top: 8px; }
    #kotoba-video-pop:not(.k-big) .k-more { display: none; }
    #kotoba-video-pop .k-text { font: 13.5px/1.5 "Hiragino Mincho ProN","Songti TC","AppleMyungjo",serif; color: #2c302d; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
    #kotoba-video-pop.k-big .k-text { font-size: 15px; line-height: 1.6; -webkit-line-clamp: 5; }
    #kotoba-video-pop .k-actions { display: flex; gap: 6px; margin-top: 7px; flex-wrap: wrap; }
    #kotoba-video-pop.k-big .k-actions { margin-top: 11px; }
    #kotoba-video-pop .k-actions button { font: 600 12px -apple-system,BlinkMacSystemFont,sans-serif; border: 0; background: #e2ece5; color: #2f6b55; border-radius: 8px; padding: 4px 10px; cursor: pointer; }
    #kotoba-video-pop.k-big .k-actions button { font-size: 12.5px; border-radius: 9px; padding: 6px 11px; }
    #kotoba-video-pop .k-actions button:first-child { background: #2f6b55; color: #fff; }
    #kotoba-video-pop .k-note { color: #72766f; font-size: 13px; }
  `;
  document.documentElement.appendChild(style);

  const ui = document.createElement('div');
  ui.id = 'kotoba-video-ui';
  ui.innerHTML = `<div id="kotoba-video-toolbar">
      <button type="button" id="kotoba-video-toggle" aria-pressed="true" title="Show the caption as text you can look up (hold Shift over a word)">文 Kotoba</button>
      <button type="button" id="kotoba-video-pause" aria-pressed="true" title="Pause the video while a word is shown">⏸ on lookup</button>
      <select id="kotoba-video-lang" aria-label="Subtitle language"><option value="auto">Auto language</option><option value="ja">日本語</option><option value="zh">中文</option><option value="ko">한국어</option><option value="th">ไทย</option><option value="ru">Русский</option></select>
    </div><div id="kotoba-video-line"><span id="kotoba-video-text"></span></div>`;
  document.body.appendChild(ui);
  const pop = document.createElement('div');
  pop.id = 'kotoba-video-pop'; pop.hidden = true;
  const toggle = ui.querySelector('#kotoba-video-toggle'), pauseBtn = ui.querySelector('#kotoba-video-pause');
  const select = ui.querySelector('#kotoba-video-lang'), text = ui.querySelector('#kotoba-video-text');
  select.value = language;
  const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  function guessLanguage(value) {
    if (language !== 'auto') return language;
    if (/[가-힣]/u.test(value)) return 'ko';
    if (/[ぁ-ヿ]/u.test(value)) return 'ja';
    if (/[ก-๛]/u.test(value)) return 'th';
    if (/[А-ӿ]/u.test(value)) return 'ru';
    if (/[一-鿿]/u.test(value)) return 'zh';
    return '';
  }

  // ---------- the site's caption, redrawn as text ----------
  function showNative() {
    if (!concealed) return;
    if (originalVisibility) concealed.style.setProperty('visibility', originalVisibility, originalPriority);
    else concealed.style.removeProperty('visibility');
    concealed = null;
  }
  function hideNative(node) {
    if (node === concealed) return;
    showNative();
    concealed = node;
    originalVisibility = node.style.getPropertyValue('visibility');
    originalPriority = node.style.getPropertyPriority('visibility');
    node.style.setProperty('visibility', 'hidden', 'important');
  }
  function readCaption(node) {
    if (!node) return '';
    if (youtube) return Array.from(node.querySelectorAll('.ytp-caption-segment')).map(part => part.textContent).join('\n').trim();
    return node.innerText.trim();
  }
  function largestVideo() {
    return Array.from(document.querySelectorAll('video'))
      .filter(video => { const r = video.getBoundingClientRect(); return r.width > 160 && r.height > 90; })
      .sort((a, b) => { const x = a.getBoundingClientRect(), y = b.getBoundingClientRect(); return y.width * y.height - x.width * x.height; })[0];
  }
  // One span per character, so the pointer can find where a word starts.
  function drawCaption(value) {
    let i = 0;
    text.innerHTML = Array.from(value).map(ch => ch === '\n' ? '<br>' : `<span class="k-ch" data-i="${i++}">${esc(ch)}</span>`).join('');
    text.lang = guessLanguage(value);
  }

  function update() {
    const host = document.fullscreenElement || document.body;
    if (ui.parentElement !== host) host.appendChild(ui);
    if (pop.parentElement !== host) host.appendChild(pop);
    const video = largestVideo();
    if (!video) { ui.hidden = true; showNative(); return; }
    const rect = video.getBoundingClientRect();
    if (rect.bottom < 0 || rect.top > innerHeight) { ui.hidden = true; showNative(); return; }
    ui.hidden = false;
    const width = Math.min(rect.width * .94, 1100);
    ui.style.left = `${Math.max(0, rect.left + (rect.width - width) / 2)}px`;
    ui.style.top = `${Math.max(0, rect.bottom - Math.max(90, rect.height * .16))}px`;
    ui.style.width = `${width}px`;
    ui.style.height = `${Math.max(80, Math.min(130, rect.height * .16))}px`;
    text.style.fontSize = `${Math.max(18, Math.min(34, rect.width / 32))}px`;
    const source = document.querySelector(sourceSelector);
    if (!enabled || !source) { showNative(); if (!pinned) { text.textContent = ''; lastText = ''; } return; }
    const current = readCaption(source);
    hideNative(source);
    // While a word is shown, the line it came from stays put.
    if (current !== lastText && !pinned) { lastText = current; drawCaption(current); hidePop(); }
  }

  // ---------- hover lookup ----------
  let shift = false, hoverSpan = null, lookupAt = '', lookupTimer = 0, pinned = false, pausedByUs = false, shown = null;
  const flat = () => Array.from(lastText).filter(c => c !== '\n');
  function videoEl() { return largestVideo(); }
  function trigger() {
    if (!hoverSpan || !shift || !text.contains(hoverSpan)) return;
    const at = lastText + '|' + hoverSpan.dataset.i;
    if (at === lookupAt) return;
    lookupAt = at;
    clearTimeout(lookupTimer);
    lookupTimer = setTimeout(() => lookup(+hoverSpan.dataset.i), 60);
  }
  async function lookup(i) {
    const chars = flat(), from = chars.slice(i, i + 24).join('');
    if (!from.trim() || /^[\s\p{P}]/u.test(from)) return;
    const lang = text.lang === 'en' ? '' : text.lang;
    let res;
    try { res = await kotoba('lookup', { text: from, lang }); }
    catch (e) { showMessage(e.message, hoverSpan); return; }
    text.querySelectorAll('.k-hl').forEach(x => x.classList.remove('k-hl'));
    if (!res.items.length) { hidePop(); return; }
    const n = Array.from(res.matched || from[0]).length;
    const spans = [...text.querySelectorAll('.k-ch')].slice(i, i + n);
    spans.forEach(x => x.classList.add('k-hl'));
    res.written = spans.map(x => x.textContent).join('');
    res.line = lastText.replace(/\n/g, ' ');
    showPop(res, spans[0]);
  }
  function showMessage(message, anchor) {
    pop.innerHTML = `<div class="k-note">${esc(message)}</div>`; pop.classList.remove('k-big');
    pop.hidden = false; pop.anchor = anchor; place(anchor);
  }
  const shortName = n => String(n).replace(/\s*[\[(（][\d\-. v]+[\])）]\s*$/, '').replace(/^(小学館|三省堂|研究社|大修館|旺文社)\s*/, '').replace(/\s*第.版$/, '').slice(0, 16);
  let groups = null;
  // Other readings of the same form (걸었다고: 걷다 or 걸다); only the context can decide.
  const alts = res => (res.forms || []).filter(f => f.base && f.base !== res.key && f.items && f.items.length).slice(0, 2);
  async function showPop(res, anchor) {
    shown = res;
    const byDict = []; for (const it of res.items) if (!byDict.some(x => x.dict === it.dict) && it.kind !== 'kanji') byDict.push(it);
    const items = byDict.slice(0, 4);
    const written = res.written && res.written !== res.key ? res.written : res.key;
    pop.innerHTML = `<div class="k-head"><b class="k-word">${esc(written)}</b>${written !== res.key ? `<span class="k-key">${esc(res.key)}</span>` : ''}<span class="k-freq"></span><span class="k-saved"></span><button class="k-size" data-k="size" title="Bigger / smaller popup">${big() ? '⤡' : '⤢'}</button><button class="k-close" data-k="close" title="Close (Esc)">×</button></div>
      ${res.explain || alts(res).length ? `<div class="k-explain">${esc(res.explain || '')}${alts(res).map((f, j) => `<button class="k-alt" data-k="alt" data-j="${j}" title="${esc(f.explain || '')}">or ${esc(f.base)}</button>`).join('')}</div>` : ''}
      ${items.map((it, n) => `<div class="${n ? 'k-more' : ''}"><div class="k-dict">${esc(shortName(it.dictionary))}${it.page && it.page !== it.key ? ' · ' + esc(it.page) : ''}</div><div class="k-text" data-n="${n}">…</div></div>`).join('')}
      <div class="k-actions"><button data-k="card">＋ Card</button><button class="k-more" data-k="open">Open in Kotoba</button><button class="k-more" data-k="copy">Copy</button></div>`;
    pop.classList.toggle('k-big', big());
    pop.items = items;
    pop.hidden = false; pop.anchor = anchor; place(anchor);
    const v = videoEl();
    if (pauseOnLookup && v && !v.paused) { v.pause(); pausedByUs = true; }
    kotoba('gloss.rec', { recs: items.map(x => x.rec), max: 360 }).then(g => {
      if (shown !== res) return;
      g.forEach((x, n) => { const el = pop.querySelector(`.k-text[data-n="${n}"]`); if (el) el.textContent = x.text || '—'; });
      place(anchor);
    }).catch(() => {});
    Promise.all([kotoba('freq', { key: res.key, reading: '' }), groups || (groups = kotoba('dicts').then(ds => Object.fromEntries(ds.map(d => [d.id, (d.grp || 'Japanese').split('/')[0]]))))]).then(([f, g]) => {
      if (shown !== res) return;
      // Only frequency lists in the caption's language.
      const want = { ja: 'Japanese', zh: 'Chinese', ko: 'Korean', th: 'Thai', ru: 'Russian' }[text.lang];
      const fq = f.find(x => x.mode === 'freq' && g[x.dict] === want);
      if (fq) pop.querySelector('.k-freq').textContent = `#${fq.display}`;
    }).catch(() => {});
    kotoba('item.similar', { headword: written, reading: '' }).then(s => { if (shown === res && s.length) pop.querySelector('.k-saved').textContent = '★ ' + s[0].folder; }).catch(() => {});
  }
  const big = () => store.get('kotoba.popBig', '0') === '1';
  function place(anchor) {
    if (!anchor) return;
    const r = anchor.getBoundingClientRect(), w = pop.offsetWidth, h = pop.offsetHeight;
    pop.style.left = `${Math.min(innerWidth - w - 10, Math.max(10, r.left + r.width / 2 - w / 2))}px`;
    let top = r.top - h - 12; if (top < 10) top = Math.min(innerHeight - h - 10, r.bottom + 12);
    pop.style.top = `${top}px`;
  }
  function hidePop() {
    if (pop.hidden) return;
    pop.hidden = true; pinned = false; shown = null; lookupAt = '';
    text.querySelectorAll('.k-hl').forEach(x => x.classList.remove('k-hl'));
    const v = videoEl();
    if (pausedByUs && v && v.paused) v.play();
    pausedByUs = false;
  }

  text.addEventListener('mousemove', e => { shift = e.shiftKey; const s = e.target.closest('.k-ch'); if (s) { hoverSpan = s; trigger(); } });
  text.addEventListener('mouseleave', () => { hoverSpan = null; setTimeout(() => { if (!pinned && !pop.matches(':hover')) hidePop(); }, 300); });
  pop.addEventListener('mouseleave', () => { if (!pinned) hidePop(); });
  // Holding Shift while already over a word looks it up without moving.
  addEventListener('keydown', e => { if (e.key === 'Shift') { shift = true; trigger(); } if (e.key === 'Escape') hidePop(); }, true);
  addEventListener('keyup', e => { if (e.key === 'Shift') shift = false; }, true);
  addEventListener('mousedown', e => { if (!pop.hidden && !pop.contains(e.target) && !text.contains(e.target)) hidePop(); }, true);
  // The site mustn't treat clicks and keys in the popup as player controls.
  for (const type of ['click', 'mousedown', 'mouseup', 'dblclick', 'keydown']) pop.addEventListener(type, e => e.stopPropagation());
  pop.addEventListener('click', async e => {
    pinned = true;
    const b = e.target.closest('[data-k]'); if (!b || !shown) return;
    const res = shown, it = pop.items && pop.items[0];
    if (b.dataset.k === 'close') { hidePop(); return; }
    if (b.dataset.k === 'alt') {
      const f = alts(res)[+b.dataset.j]; if (!f) return;
      const now = (res.forms || []).find(x => x.base === res.key) || { base: res.key, explain: res.explain, items: res.items };
      showPop({ ...res, key: f.base, explain: f.explain || '', items: (f.items || []).concat(f.extra || []), forms: [f, now, ...(res.forms || []).filter(x => x !== f && x !== now)] }, pop.anchor);
      return;
    }
    if (b.dataset.k === 'size') {
      store.set('kotoba.popBig', big() ? '0' : '1');
      pop.classList.toggle('k-big', big()); b.textContent = big() ? '⤡' : '⤢';
      place(pop.anchor); return;
    }
    if (b.dataset.k === 'copy') { navigator.clipboard.writeText(res.written || res.key).catch(() => {}); b.textContent = 'Copied'; }
    if (b.dataset.k === 'open') kotoba('show', { word: res.key }).catch(err => showMessage(err.message, text));
    if (b.dataset.k === 'card' && it) {
      try {
        const g = (await kotoba('gloss.rec', { recs: [it.rec], max: 2000 }))[0];
        const headword = text.lang === 'zh' && res.written ? res.written : res.key;
        const reading = it.page && it.page !== res.key && /^[぀-ヿ가-힣a-zāáǎàēéěèīíǐìōóǒòūúǔùü\s0-9]+$/i.test(it.page) ? it.page : '';
        const v = videoEl(), t = v ? Math.floor(v.currentTime) : 0;
        await kotoba('item.save', { folder_id: 1, headword, reading, back: g.text, dict: it.dict, dict_name: it.dictionary, page: it.page || res.key,
          kind: 'entry', context: res.line, note: `${document.title.replace(/ - YouTube$/, '')} · ${Math.floor(t / 60)}:${String(t % 60).padStart(2, '0')}`, review: true });
        pop.querySelector('.k-saved').textContent = '★ saved'; b.textContent = 'Saved';
      } catch (err) { showMessage(err.message, text); }
    }
  });

  toggle.addEventListener('click', () => { enabled = !enabled; store.set('kotoba.videoText.enabled', String(enabled)); toggle.setAttribute('aria-pressed', String(enabled)); if (!enabled) hidePop(); update(); });
  pauseBtn.addEventListener('click', () => { pauseOnLookup = !pauseOnLookup; store.set('kotoba.videoText.pause', String(pauseOnLookup)); pauseBtn.setAttribute('aria-pressed', String(pauseOnLookup)); });
  select.addEventListener('change', () => { language = select.value; store.set('kotoba.videoText.language', language); text.lang = guessLanguage(lastText); });
  toggle.setAttribute('aria-pressed', String(enabled));
  pauseBtn.setAttribute('aria-pressed', String(pauseOnLookup));
  addEventListener('pagehide', showNative);
  setInterval(update, 200);
  update();
})();
