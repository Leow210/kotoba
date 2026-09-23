'use strict';
// Desktop adjustments once the app's own scripts have loaded.
(function(){
  const mac=(msg)=>{try{window.webkit.messageHandlers.kotoba.postMessage(msg);}catch(e){}};
  const libraryBtn=document.querySelector('.tabbar [data-tab="library"]');
  const settingsBtn=document.createElement('button');settingsBtn.dataset.tab='settings';
  settingsBtn.innerHTML=`<svg class="i" viewBox="0 0 24 24"><path d="M12 3v2m0 14v2M3 12h2m14 0h2M5.6 5.6 7 7m10 10 1.4 1.4M18.4 5.6 17 7M7 17l-1.4 1.4"/><circle cx="12" cy="12" r="4"/></svg>Settings`;
  libraryBtn.after(settingsBtn);
  settingsBtn.onclick=()=>showTab('settings');
  const settingsScreen=document.createElement('section');settingsScreen.className='screen';settingsScreen.id='screen-settings';settingsScreen.hidden=true;
  settingsScreen.innerHTML=`<div class="head"><h1>Settings</h1><p class="sub">Reading, audio, data and sync</p></div><div class="scroll"><div id="settings-home"></div></div>`;
  document.getElementById('screen-library').after(settingsScreen);
  function moveSettings(){
    const library=document.getElementById('library-home'),home=document.getElementById('settings-home');
    let node=[...library.querySelectorAll('.section-label')].find(x=>x.textContent.trim()==='Reading');
    home.replaceChildren();
    while(node){const next=node.nextSibling;home.appendChild(node);node=next;}
    if(!dicts.length)document.getElementById('library-sub').textContent='Dictionaries';
  }
  // Dictionaries on the Mac are ordinary folders; there is no app-private folder to scan.
  if(typeof renderLibrary==='function'){
    const original=renderLibrary;
    window.renderLibrary=renderLibrary=async function(){
      await original();
      const local=document.getElementById('lib-local');if(local){local.nextElementSibling&&local.nextElementSibling.remove();local.remove();}
      const reading=[...document.querySelectorAll('#library-home .section-label')].find(x=>x.textContent.trim()==='Reading');
      const box=reading&&reading.nextElementSibling;
      if(box&&box.classList.contains('settings')){
        const values=['none','shift','alt','ctrl','meta'];
        const row=document.createElement('div');row.className='switch-row';
        row.innerHTML=`<div><b>Hover lookup key</b><small>Hold the key over a book word or subtitle to open its dictionary. “No key” opens on hover.</small></div>
          <div class="chips">${values.map(v=>`<button class="chip small ${KotobaHover.get()===v?'on':''}" data-hover-key="${v}" aria-pressed="${KotobaHover.get()===v}">${KotobaHover.choices[v].label}</button>`).join('')}</div>`;
        box.appendChild(row);
        row.querySelectorAll('[data-hover-key]').forEach(b=>b.onclick=()=>{
          KotobaHover.set(b.dataset.hoverKey);
          row.querySelectorAll('[data-hover-key]').forEach(x=>{const on=x===b;x.classList.toggle('on',on);x.setAttribute('aria-pressed',String(on));});
          updateVideoHelp();
        });
        const clickRow=document.createElement('div');clickRow.className='switch-row';
        clickRow.innerHTML=`<div><b>Click a book word to look it up</b><small>Off by default. You can still click and drag to select text.</small></div>
          <label class="toggle"><input type="checkbox" id="desktop-click-lookup" ${KotobaHover.clickLookup()?'checked':''}><span></span></label>`;
        box.appendChild(clickRow);
        clickRow.querySelector('input').onchange=e=>KotobaHover.setClickLookup(e.target.checked);
      }
      moveSettings();
      renderTranslationSettings();
    };
  }
  // ---------- translation (TranslateGemma on this Mac, or Google Translate in the browser) ----------
  const googleCode=c=>({'zh-Hans':'zh-CN','zh-Hant':'zh-TW'}[c]||c);
  const langOptions=(cfg,value,withAuto)=>Object.entries(cfg.languages).filter(([k])=>withAuto||k!=='auto').map(([k,v])=>`<option value="${k}" ${k===value?'selected':''}>${esc(v)}</option>`).join('');
  async function renderTranslationSettings(){
    const home=document.getElementById('settings-home');if(!home)return;
    const cfg=await api('translate.config',{}).catch(()=>null);if(!cfg)return;
    home.querySelectorAll('.translation-settings').forEach(x=>x.remove());
    const label=document.createElement('div');label.className='section-label translation-settings';label.textContent='Translation';
    const box=document.createElement('div');box.className='settings translation-settings';
    const status=cfg.engine!=='gemma'?'Translate opens Google Translate in your browser.'
      :!cfg.serverFound?'llama-server isn’t installed (brew install llama.cpp).'
      :cfg.modelFound?`Runs on this Mac from ${esc(cfg.model)}. The first translation takes a few seconds to load the model; it unloads after 10 idle minutes.`
      :`The model isn’t at ${esc(cfg.model)} — plug in the drive, or finish downloading it.`;
    box.innerHTML=`<div class="switch-row"><div><b>Translate with</b><small>${status}</small></div>
        <div class="chips"><button class="chip small ${cfg.engine==='gemma'?'on':''}" data-engine="gemma">TranslateGemma 12B</button><button class="chip small ${cfg.engine!=='gemma'?'on':''}" data-engine="google">Google (browser)</button></div></div>
      <div class="switch-row"><div><b>Languages</b><small>“Detect” picks the source from the text (Hangul → Korean, kana → Japanese…).</small></div>
        <div class="chips"><select data-tr="from">${langOptions(cfg,cfg.from,true)}</select> → <select data-tr="to">${langOptions(cfg,cfg.to,false)}</select></div></div>`;
    home.prepend(label,box);
    box.querySelectorAll('[data-engine]').forEach(b=>b.onclick=handle(async()=>{await api('translate.set',{engine:b.dataset.engine});renderTranslationSettings();}));
    box.querySelectorAll('[data-tr]').forEach(s=>s.onchange=handle(()=>api('translate.set',{[s.dataset.tr]:s.value})));
  }
  window.__translateInApp=handle(async(text)=>{
    const cfg=await api('translate.config',{}).catch(()=>null);
    if(!cfg||cfg.engine!=='gemma'){
      const sl=cfg&&cfg.from!=='auto'?googleCode(cfg.from):'auto',tl=cfg?googleCode(cfg.to):'en';
      mac({type:'open',url:`https://translate.google.com/?sl=${sl}&tl=${tl}&op=translate&text=`+encodeURIComponent(text)});return;
    }
    const s=openSheet(`<div class="sheet-body translate-sheet">
        <div class="tr-langs"><select data-tr="from">${langOptions(cfg,cfg.from,true)}</select><span>→</span><select data-tr="to">${langOptions(cfg,cfg.to,false)}</select></div>
        <div class="tr-src">${esc(text)}</div><div class="tr-out" aria-live="polite"></div></div>
      <div class="sheet-foot"><button class="btn wide" data-a="copy">${icon('copy')} Copy translation</button></div>`,{title:'Translation'});
    const out=s.sheet.querySelector('.tr-out');let result='';
    async function run(){
      const from=s.sheet.querySelector('[data-tr="from"]').value,to=s.sheet.querySelector('[data-tr="to"]').value;
      out.classList.add('busy');out.textContent=cfg.running?'Translating…':'Loading TranslateGemma… (first time takes a few seconds)';
      try{const r=await api('translate',{text,from,to});result=r.text;cfg.running=true;out.classList.remove('busy');out.textContent=r.text;
        if(from==='auto')out.dataset.note=`${cfg.languages[r.from]||r.from} → ${cfg.languages[r.to]||r.to}`;}
      catch(e){out.classList.remove('busy');out.textContent=e.message;}
    }
    s.sheet.querySelectorAll('[data-tr]').forEach(x=>x.onchange=()=>{api('translate.set',{[x.dataset.tr]:x.value});run();});
    s.sheet.querySelector('[data-a="copy"]').onclick=()=>{if(result){Kotoba.copy(result);toast('Copied');}};
    run();
  });
  if(typeof renderSearchEmpty==='function'){
    const original=renderSearchEmpty;
    window.renderSearchEmpty=renderSearchEmpty=async function(){
      await original();
      const e=document.querySelector('#search-empty .empty');
      if(e&&!dicts.length)e.innerHTML=`<span class="glyph">辞</span><h2>Add your dictionaries</h2>Choose a folder of MDX dictionaries (e.g. Monokakido_Ciyue) or Yomitan .zip dictionaries. Files are read where they are.<br><br><button class="btn primary" id="go-import">${icon('folder')} Choose dictionary folder</button>`;
      const b=document.getElementById('go-import');if(b)b.onclick=()=>{showTab('library');pickFolder();};
    };
  }
  // The Reader tab keeps books and comics (the comics come from the phone's Mihon folder); videos get a tab of their own.
  const readerBtn=document.querySelector('.tabbar [data-tab="reader"]');
  const videoBtn=document.createElement('button');videoBtn.dataset.tab='video';
  videoBtn.innerHTML=`<svg class="i" viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="14" rx="2.5"/><path d="M10 9.5v5l4.5-2.5z"/></svg>Video`;
  readerBtn.after(videoBtn);
  videoBtn.onclick=()=>showTab('video');
  const screen=document.createElement('section');screen.className='screen';screen.id='screen-video';screen.hidden=true;
  document.getElementById('screen-reader').after(screen);
  const hoverInstruction=()=>KotobaHover.get()==='none'?'Hover a subtitle to look words up':`Hold ${KotobaHover.label()} and hover a subtitle to look words up`;
  function updateVideoHelp(){const e=screen.querySelector('.head .sub');if(e)e.textContent=hoverInstruction();}
  screen.innerHTML=`<div class="head head-row"><div><h1>Video</h1><p class="sub"></p></div><div><button class="btn small" id="copy-browser-helper">Browser subtitle helper</button> <button class="btn small primary" id="open-video">＋ Open video…</button></div></div><div class="scroll" id="video-home"></div>`;
  updateVideoHelp();
  document.getElementById('open-video').onclick=()=>mac({type:'openVideo'});
  // The browser helper (Firefox or Chrome): a one-time install link (it carries the key the helper uses to ask Kotoba for lookups).
  document.getElementById('copy-browser-helper').onclick=handle(async()=>{
    const r=await api('helper.link');
    Kotoba.copy(r.url);
    if(!r.helperRunning)toast('Another app is using port '+r.helperPort+'; quit it and reopen Kotoba for the helper to work.',6000);
    else toast('Install link copied (valid 10 minutes). Paste it into Firefox’s or Chrome’s address bar; Tampermonkey offers to install.',6000);
  });
  // "Open in Kotoba" from the helper: the word opens here and the window comes forward.
  on('helper-show',r=>{mac({type:'activate'});window.externalLookup&&window.externalLookup(r.word);});
  // Keyboard page turns in the book reader (the phone has none): ←/→ follow the book's direction, like a paper book.
  function readerKey(e){
    // ⌘+ / ⌘− / ⌘0: zoom in the comic reader, text size in the book reader.
    if(e.metaKey&&!e.ctrlKey&&!e.altKey&&['=','+','-','_','0'].includes(e.key)){
      const top=[...document.querySelectorAll('#pages > .page')].pop();
      const d=e.key==='0'?0:(e.key==='-'||e.key==='_')?-1:1;
      if(top&&top.kotobaZoom){e.preventDefault();top.kotobaZoom(d);}
      else if(top&&top.classList.contains('reader-page')&&window.__reader&&window.__reader.fontStep){e.preventDefault();window.__reader.fontStep(d);}
      return;
    }
    const r=window.__reader;
    if(!r||!document.querySelector('.reader-page')||e.target.closest&&e.target.closest('input,textarea'))return;
    if(document.querySelector('.sheet'))return;
    let dir=0;
    if(e.key==='ArrowLeft')dir=r.rtl()?1:-1;
    else if(e.key==='ArrowRight')dir=r.rtl()?-1:1;
    else if(e.key===' '||e.key==='PageDown'||e.key==='ArrowDown')dir=e.shiftKey?-1:1;
    else if(e.key==='PageUp'||e.key==='ArrowUp')dir=-1;
    if(!dir)return;
    e.preventDefault();r.turn(dir);
  }
  document.addEventListener('keydown',readerKey);
  // Book pages are frames; keys pressed while one has focus come here too.
  new MutationObserver(()=>document.querySelectorAll('.reader-page iframe').forEach(f=>{
    if(f.__keys)return;f.__keys=true;
    f.addEventListener('load',()=>{try{f.contentDocument.addEventListener('keydown',readerKey);}catch(e){}});
    try{if(f.contentDocument)f.contentDocument.addEventListener('keydown',readerKey);}catch(e){}
  })).observe(document.getElementById('pages'),{childList:true,subtree:true});
  const fmt=(s)=>{s=Math.floor(s||0);const h=Math.floor(s/3600),m=Math.floor(s%3600/60);return h?`${h}:${String(m).padStart(2,'0')}:${String(s%60).padStart(2,'0')}`:`${m}:${String(s%60).padStart(2,'0')}`;};
  function renderVideos(){
    let recent=[];try{recent=JSON.parse(localStorage.getItem('recentVideos')||'[]');}catch(e){}
    const box=document.getElementById('video-home');
    box.innerHTML=recent.length?`<div class="section-label">Recent</div>`+recent.map((v,i)=>`<button class="row video-row" data-v="${i}"><div class="line"><div class="hw">${esc(v.title)}</div><div class="meta"><span class="tag muted">${fmt(v.t)} / ${fmt(v.d)}</span></div></div><div class="progress"><i style="width:${v.d?Math.min(100,v.t/v.d*100):0}%"></i></div><small class="vpath">${esc(v.path)}</small></button>`).join('')
      :`<div class="empty"><span class="glyph">映</span><h2>Watch with subtitles</h2>Open a video (MKV, MP4…). Subtitle files beside it, or in a <b>subtitles</b> folder next to it, and the tracks inside it are found automatically. For subtitles printed into the picture, choose <b>OCR hardcoded subtitles</b> from the 字幕 menu in the player.<br><br>For YouTube and GagaOOLala in Firefox or Chrome, install the subtitle helper above (needs Tampermonkey). Turn on the site's captions, then hold <b>Shift</b> over a word: Kotoba shows its definitions and can save a card, as long as this app is open.<br><br>${esc(hoverInstruction())}; <b>A</b> / <b>D</b> go to the previous / next line, <b>S</b> replays it, and <b>P</b> pauses after every line.</div>`;
    box.querySelectorAll('[data-v]').forEach(b=>b.onclick=()=>mac({type:'openVideo',path:recent[+b.dataset.v].path}));
  }
  const originalShow=showTab;
  window.showTab=showTab=function(name){originalShow(name);if(name==='video')renderVideos();if(name==='settings')renderLibrary();};
  window.addEventListener('focus',()=>{if(tab==='video')renderVideos();});
})();
