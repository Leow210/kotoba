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
  // The Reader tab (books and comics live on the phone) becomes Video.
  const tabBtn=document.querySelector('.tabbar [data-tab="reader"]');
  if(tabBtn)tabBtn.innerHTML=`<svg class="i" viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="14" rx="2.5"/><path d="M10 9.5v5l4.5-2.5z"/></svg>Video`;
  const screen=document.getElementById('screen-reader');
  screen.innerHTML=`<div class="head head-row"><div><h1>Video</h1><p class="sub">Hover a subtitle to look words up</p></div><button class="btn small primary" id="open-video">＋ Open video…</button></div><div class="scroll" id="video-home"></div>`;
  document.getElementById('open-video').onclick=()=>mac({type:'openVideo'});
  const fmt=(s)=>{s=Math.floor(s||0);const h=Math.floor(s/3600),m=Math.floor(s%3600/60);return h?`${h}:${String(m).padStart(2,'0')}:${String(s%60).padStart(2,'0')}`:`${m}:${String(s%60).padStart(2,'0')}`;};
  function renderVideos(){
    let recent=[];try{recent=JSON.parse(localStorage.getItem('recentVideos')||'[]');}catch(e){}
    const box=document.getElementById('video-home');
    box.innerHTML=recent.length?`<div class="section-label">Recent</div>`+recent.map((v,i)=>`<button class="row video-row" data-v="${i}"><div class="line"><div class="hw">${esc(v.title)}</div><div class="meta"><span class="tag muted">${fmt(v.t)} / ${fmt(v.d)}</span></div></div><div class="progress"><i style="width:${v.d?Math.min(100,v.t/v.d*100):0}%"></i></div><small class="vpath">${esc(v.path)}</small></button>`).join('')
      :`<div class="empty"><span class="glyph">映</span><h2>Watch with subtitles</h2>Open a video (MKV, MP4…). Subtitle files beside it, or in a <b>subtitles</b> folder next to it, and the tracks inside it are found automatically.<br><br>Hover a word in the subtitles to look it up; <b>A</b> / <b>D</b> go to the previous / next line, <b>S</b> replays it, and <b>P</b> pauses after every line.</div>`;
    box.querySelectorAll('[data-v]').forEach(b=>b.onclick=()=>mac({type:'openVideo',path:recent[+b.dataset.v].path}));
  }
  const originalShow=showTab;
  window.showTab=showTab=function(name){originalShow(name);if(name==='reader')renderVideos();};
  window.addEventListener('focus',()=>{if(tab==='reader')renderVideos();});
})();
