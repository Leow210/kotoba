// ==UserScript==
// @name         Kotoba Video Text
// @namespace    app.kotoba.desktop
// @version      0.9.3
// @description  Look up YouTube and GagaOOLala subtitles in Kotoba for Mac: hold Shift over a word; YouTube Music's song goes to Kotoba's lyrics. Works in Firefox and Chrome (Tampermonkey).
// @match        https://www.youtube.com/watch*
// @match        https://www.gagaoolala.com/*/videos/*
// @match        https://music.youtube.com/*
// @match        https://www.viki.com/*
// @run-at       document-idle
// @noframes
// @grant        GM_xmlhttpRequest
// @grant        GM_getValue
// @grant        GM_setValue
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

  // YouTube Music: nothing to hover; the song and its position go to Kotoba (Music), which shows synced lyrics and can
  // pause, play or seek it back through the answer.
  if (location.hostname === 'music.youtube.com') {
    const report = () => {
      const v = document.querySelector('video');
      const md = navigator.mediaSession && navigator.mediaSession.metadata;
      const title = (md && md.title) || (document.querySelector('ytmusic-player-bar .title') || {}).textContent || '';
      if (!v || !title) return;
      const byline = ((document.querySelector('ytmusic-player-bar .byline') || {}).textContent || '').split('•').map(x => x.trim());
      kotoba('music.report', { app: 'YouTube Music', title: title.trim(), artist: (md && md.artist) || byline[0] || '', album: (md && md.album) || byline[1] || '',
        duration: isFinite(v.duration) ? v.duration : 0, position: v.currentTime, playing: !v.paused }).then(r => {
        const c = r && r.command; if (!c) return;
        if (c.action === 'pause') v.pause();
        else if (c.action === 'play') v.play();
        else if (c.action === 'seek' && c.t >= 0) v.currentTime = c.t;
        else if (c.action === 'toggle') v.paused ? v.play() : v.pause();
      }).catch(() => {});
    };
    setInterval(report, 1000);
    return;
  }
  const youtube = location.hostname === 'www.youtube.com';
  const sourceSelector = youtube ? '.ytp-caption-window-container' : '.bmpui-ui-subtitle-overlay';
  const store = { get: (k, d) => { try { const v = localStorage.getItem(k); return v === null ? d : v; } catch (e) { return d; } }, set: (k, v) => { try { localStorage.setItem(k, v); } catch (e) {} } };
  let enabled = store.get('kotoba.videoText.enabled', 'true') !== 'false';
  let language = store.get('kotoba.videoText.language', 'auto');
  let pauseOnLookup = store.get('kotoba.videoText.pause', 'true') !== 'false';
  let concealed = null, originalVisibility = '', originalPriority = '', lastText = '';

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

  const ui = document.createElement('div');
  ui.id = 'kotoba-video-ui';
  ui.innerHTML = H(`<div id="kotoba-video-toolbar">
      <button type="button" id="kotoba-video-toggle" aria-pressed="true" title="Show the caption as text you can look up (hold Shift over a word)">文 Kotoba</button>
      <button type="button" id="kotoba-video-pause" aria-pressed="true" title="Pause the video while a word is shown">⏸ on lookup</button>
      <button type="button" id="kotoba-video-live" aria-pressed="false" title="Subtitles from the show's audio, made on your Mac (Qwen3-ASR on the T7) and kept for rewatching">🎙 Live subs</button>
      <select id="kotoba-video-lang" aria-label="Subtitle language"><option value="auto">Auto language</option><option value="ja">日本語</option><option value="zh">中文</option><option value="ko">한국어</option><option value="th">ไทย</option><option value="ru">Русский</option></select>
    </div><div id="kotoba-video-line"><span id="kotoba-video-text"></span></div>`);
  document.body.appendChild(ui);
  const pop = document.createElement('div');
  pop.id = 'kotoba-video-pop'; pop.className = 'kotoba-pop'; pop.hidden = true;
  const toggle = ui.querySelector('#kotoba-video-toggle'), pauseBtn = ui.querySelector('#kotoba-video-pause');
  const select = ui.querySelector('#kotoba-video-lang'), text = ui.querySelector('#kotoba-video-text');
  const liveBtn = ui.querySelector('#kotoba-video-live');

  // ---------- live subtitles (made on the Mac from the show's audio) ----------
  // While on, the video's time goes to Kotoba several times a second; Kotoba captures the browser's sound, turns each
  // spoken line into text and places it on this episode's timeline. The lines come back here and show at their time,
  // like a subtitle track; an episode heard before shows its lines at once.
  let live = store.get('kotoba.live.' + location.hostname, 'false') === 'true';
  let liveLines = [], liveRev = -1, liveKey = '';
  const episodeKey = () => location.hostname + location.pathname.replace(/\/$/, '');
  function liveTick() {
    const v = largestVideo();
    if (!v) return;
    const key = episodeKey();
    if (key !== liveKey) { liveKey = key; liveLines = []; liveRev = -1; }
    if (!live && liveRev >= 0 && !liveLines.length) return;
    const lang = language === 'auto' ? (guessLanguage(liveLines.map(l => l.text).join('')) || 'ko') : language;
    kotoba('captions.report', { key, title: document.title, time: v.currentTime, playing: !v.paused && !v.ended, rate: v.playbackRate || 1,
      live, lang, since: liveRev }).then(r => {
      if (!r || key !== liveKey) return;
      if (r.lines) liveLines = r.lines;
      if (typeof r.rev === 'number') liveRev = r.rev;
    }).catch(() => {});
  }
  // The latest line that has begun, held until the next one starts (a live line only arrives a second or so after it
  // ends) for at most 3.5 s past its end.
  function liveLineAt(t) {
    let best = null;
    for (const l of liveLines) { if (l.t0 <= t + 0.15) best = l; else break; }
    return best && t <= best.t1 + 3.5 ? best.text : '';
  }
  setInterval(liveTick, 250);
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
    // Not innerText: once the site's caption is hidden (below), innerText reads as empty.
    let out = '';
    const walk = el => {
      for (const c of el.childNodes) {
        if (c.nodeType === 3) out += c.nodeValue;
        else if (c.nodeType === 1) {
          if (c.tagName === 'BR') { out += '\n'; continue; }
          const block = /^(DIV|P|LI)$/.test(c.tagName);
          if (block && out && !out.endsWith('\n')) out += '\n';
          walk(c);
          if (block && out && !out.endsWith('\n')) out += '\n';
        }
      }
    };
    walk(node);
    return out.replace(/[ \t]+/g, ' ').replace(/ *\n */g, '\n').trim();
  }
  function largestVideo() {
    return Array.from(document.querySelectorAll('video'))
      .filter(video => { const r = video.getBoundingClientRect(); return r.width > 160 && r.height > 90; })
      .sort((a, b) => { const x = a.getBoundingClientRect(), y = b.getBoundingClientRect(); return y.width * y.height - x.width * x.height; })[0];
  }
  // One span per character, so the pointer can find where a word starts.
  function drawCaption(value) {
    let i = 0;
    text.innerHTML = H(Array.from(value).map(ch => ch === '\n' ? '<br>' : `<span class="k-ch" data-i="${i++}">${esc(ch)}</span>`).join(''));
    text.lang = guessLanguage(value);
  }

  function update() {
    const host = document.fullscreenElement || document.body;
    if (ui.parentElement !== host) host.appendChild(ui);
    for (const P of stack) if (P.el.parentElement !== host) host.appendChild(P.el);
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
    // Live subtitles (or lines saved from an earlier watch) take the place of the site's own.
    if (enabled && (live || liveLines.length)) {
      const current = liveLineAt(video.currentTime);
      if (current !== lastText && !pinned) { lastText = current; drawCaption(current); hidePop(); }
      return;
    }
    const source = document.querySelector(sourceSelector);
    if (!enabled || !source) { showNative(); if (!pinned) { text.textContent = ''; lastText = ''; } return; }
    const current = readCaption(source);
    hideNative(source);
    // While a word is shown, the line it came from stays put.
    if (current !== lastText && !pinned) { lastText = current; drawCaption(current); hidePop(); }
  }

  // ---------- hover lookup ----------
  let shift = false, hoverSpan = null, lookupAt = '', lookupTimer = 0, pinned = false, pausedByUs = false;
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
  /** i: the character to start at; only: a selected stretch to look up instead of the longest word from i. */
  async function lookup(i, only) {
    const chars = flat(), from = only || chars.slice(i, i + 24).join('');
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
    const a = spans[0].getBoundingClientRect(), z = spans[spans.length - 1].getBoundingClientRect();
    main.lang = text.lang;
    showPop(res, { left: a.left, right: z.right, top: a.top, bottom: z.bottom }, main);
    const v = videoEl();
    if (pauseOnLookup && v && !v.paused) { v.pause(); pausedByUs = true; }
  }
  function showMessage(message, anchor) {
    closeFrom(1);
    pop.innerHTML = H(`<div class="k-note">${esc(message)}</div>`); pop.classList.remove('k-big');
    pop.hidden = false; main.rect = anchor.getBoundingClientRect(); place(main);
  }
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
    if (/[一-鿿]/u.test(c)) return main.lang === 'zh' ? 'zh' : 'ja';
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
  function hidePop() {
    closeFrom(1);
    if (pop.hidden) return;
    pop.hidden = true; pinned = false; main.res = null; lookupAt = '';
    text.querySelectorAll('.k-hl').forEach(x => x.classList.remove('k-hl'));
    const v = videoEl();
    if (pausedByUs && v && v.paused) v.play();
    pausedByUs = false;
  }
  const inPopup = t => !!(t && t.closest && t.closest('.kotoba-pop'));

  // Drag across part of the line to look up exactly that (a shorter word than Shift picks, a stem, a single hanja).
  text.addEventListener('mouseup', e => {
    e.stopPropagation();
    setTimeout(() => {
      const sel = getSelection(); if (!sel || sel.isCollapsed || !sel.rangeCount) return;
      const str = sel.toString().replace(/\s+/g, ''); if (!str || str.length > 24) return;
      const r = sel.getRangeAt(0), start = r.startContainer.nodeType === 3 ? r.startContainer.parentElement : r.startContainer;
      const span = start && (start.closest ? start.closest('.k-ch') : null) || text.querySelector('.k-ch');
      if (!span || !text.contains(span)) return;
      pinned = true; lookupAt = '';
      lookup(+span.dataset.i, str).then(() => sel.removeAllRanges());
    }, 10);
  });
  for (const type of ['mousedown', 'click']) text.addEventListener(type, e => e.stopPropagation());
  text.addEventListener('mousemove', e => { shift = e.shiftKey; const s = e.target.closest('.k-ch'); if (s) { hoverSpan = s; trigger(); } });
  text.addEventListener('mouseleave', e => { hoverSpan = null; if (inPopup(e.relatedTarget)) return; setTimeout(() => { if (!pinned && !document.querySelector('.kotoba-pop:hover')) hidePop(); }, 300); });
  // Holding Shift while already over a word looks it up without moving.
  addEventListener('keydown', e => {
    if (e.key === 'Shift') { shift = true; if (lastFrame && lastFrame.f.isConnected && lastFrame.f.matches(':hover')) lastFrame.probe(lastFrame.x, lastFrame.y, true); else trigger(); }
    if (e.key === 'Escape') hidePop();
  }, true);
  addEventListener('keyup', e => { if (e.key === 'Shift') shift = false; }, true);
  addEventListener('mousedown', e => { if (!pop.hidden && !inPopup(e.target) && !text.contains(e.target)) hidePop(); }, true);
  function wire(P) {
    const el = P.el;
    el.addEventListener('mouseleave', e => { if (!pinned && !inPopup(e.relatedTarget) && !(e.relatedTarget && text.contains(e.relatedTarget))) hidePop(); });
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
      // × closes this popup and the ones opened from it; the first one also resumes the video.
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
        const headword = text.lang === 'zh' && res.written && !P.level ? res.written : res.key;
        const reading = it.page && it.page !== res.key && /^[぀-ヿ가-힣a-zāáǎàēéěèīíǐìōóǒòūúǔùü\s0-9]+$/i.test(it.page) ? it.page : '';
        const v = videoEl(), t = v ? Math.floor(v.currentTime) : 0;
        await kotoba('item.save', { folder_id: +(P.el.querySelector('.k-folder') || {}).value || cardFolder(), headword, reading, back: g.text, dict: it.dict, dict_name: it.dictionary, page: it.page || res.key,
          kind: 'entry', context: res.line || '', note: `${document.title.replace(/ - YouTube$/, '')} · ${Math.floor(t / 60)}:${String(t % 60).padStart(2, '0')}`, review: true });
        P.el.querySelector('.k-saved').textContent = '★ saved'; b.textContent = 'Saved';
      } catch (err) { showMessage(err.message, text); }
    }
  }

  toggle.addEventListener('click', () => { enabled = !enabled; store.set('kotoba.videoText.enabled', String(enabled)); toggle.setAttribute('aria-pressed', String(enabled)); if (!enabled) hidePop(); update(); });
  pauseBtn.addEventListener('click', () => { pauseOnLookup = !pauseOnLookup; store.set('kotoba.videoText.pause', String(pauseOnLookup)); pauseBtn.setAttribute('aria-pressed', String(pauseOnLookup)); });
  select.addEventListener('change', () => { language = select.value; store.set('kotoba.videoText.language', language); text.lang = guessLanguage(lastText); });
  liveBtn.addEventListener('click', () => {
    live = !live; store.set('kotoba.live.' + location.hostname, String(live)); liveBtn.setAttribute('aria-pressed', String(live));
    liveTick(); if (!live) { lastText = ''; text.textContent = ''; }
  });
  liveBtn.setAttribute('aria-pressed', String(live));
  toggle.setAttribute('aria-pressed', String(enabled));
  pauseBtn.setAttribute('aria-pressed', String(pauseOnLookup));
  addEventListener('pagehide', showNative);
  setInterval(update, 200);
  update();
})();
