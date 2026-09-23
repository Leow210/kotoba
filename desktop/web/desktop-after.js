'use strict';
// Desktop adjustments once the app's own scripts have loaded.
(function(){
  const mac=(msg)=>{try{window.webkit.messageHandlers.kotoba.postMessage(msg);}catch(e){}};
  // Dictionaries on the Mac are ordinary folders; there is no app-private folder to scan.
  if(typeof renderLibrary==='function'){
    const original=renderLibrary;
    window.renderLibrary=renderLibrary=async function(){
      await original();
      const local=document.getElementById('lib-local');if(local){local.nextElementSibling&&local.nextElementSibling.remove();local.remove();}
    };
  }
  if(typeof renderSearchEmpty==='function'){
    const original=renderSearchEmpty;
    window.renderSearchEmpty=renderSearchEmpty=async function(){
      await original();
      const e=document.querySelector('#search-empty .empty');
      if(e&&!dicts.length)e.innerHTML=`<span class="glyph">辞</span><h2>Add your dictionaries</h2>Choose a folder of MDX dictionaries (e.g. Monokakido_Ciyue) or Yomitan .zip dictionaries. Files are read where they are.<br><br><button class="btn primary" id="go-import">${icon('folder')} Choose dictionary folder</button>`;
      const b=document.getElementById('go-import');if(b)b.onclick=()=>{showTab('library');pickFolder();};
    };
  }
  // Books keep the Reader tab (comics stay on the phone); videos get a tab of their own.
  const kind=document.getElementById('shelf-kind');if(kind)kind.remove();
  if(typeof shelfKind!=='undefined')shelfKind='books';
  const readerBtn=document.querySelector('.tabbar [data-tab="reader"]');
  const videoBtn=document.createElement('button');videoBtn.dataset.tab='video';
  videoBtn.innerHTML=`<svg class="i" viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="14" rx="2.5"/><path d="M10 9.5v5l4.5-2.5z"/></svg>Video`;
  readerBtn.after(videoBtn);
  videoBtn.onclick=()=>showTab('video');
  const screen=document.createElement('section');screen.className='screen';screen.id='screen-video';screen.hidden=true;
  document.getElementById('screen-reader').after(screen);
  screen.innerHTML=`<div class="head head-row"><div><h1>Video</h1><p class="sub">Hover a subtitle to look words up</p></div><button class="btn small primary" id="open-video">＋ Open video…</button></div><div class="scroll" id="video-home"></div>`;
  document.getElementById('open-video').onclick=()=>mac({type:'openVideo'});
  // Keyboard page turns in the book reader (the phone has none): ←/→ follow the book's direction, like a paper book.
  function readerKey(e){
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
      :`<div class="empty"><span class="glyph">映</span><h2>Watch with subtitles</h2>Open a video (MKV, MP4…). Subtitle files beside it, or in a <b>subtitles</b> folder next to it, and the tracks inside it are found automatically.<br><br>Hover a word in the subtitles to look it up; <b>A</b> / <b>D</b> go to the previous / next line, <b>S</b> replays it, and <b>P</b> pauses after every line.</div>`;
    box.querySelectorAll('[data-v]').forEach(b=>b.onclick=()=>mac({type:'openVideo',path:recent[+b.dataset.v].path}));
  }
  const originalShow=showTab;
  window.showTab=showTab=function(name){originalShow(name);if(name==='video')renderVideos();};
  window.addEventListener('focus',()=>{if(tab==='video')renderVideos();});
})();
