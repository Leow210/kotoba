'use strict';
/* Cover images are cached by the WebView; bump after a cover changes. */
let coverVersion=Date.now();
on('comic-cover',r=>{coverVersion=Date.now();toast('Cover updated');if(typeof renderComicShelf==='function')renderComicShelf();});
/* Comics & manhwa: library, series pages, and a reader with seamless webtoon scrolling or LTR/RTL pages. */

let shelfKind='books';
const COMIC_DEFAULTS={mode:'',fit:'width',gap:false,separators:true,pageAnim:true};
function comicDefaults(){try{return {...COMIC_DEFAULTS,...JSON.parse(localStorage.getItem('comicDefaults')||'{}')};}catch(e){return {...COMIC_DEFAULTS};}}

// ---------- Reader tab: Books | Comics ----------
(function(){
  const head=document.querySelector('#screen-reader .head');
  const seg=document.createElement('div');
  seg.className='seg';seg.style.cssText='margin:0 16px 8px';seg.id='shelf-kind';
  seg.innerHTML='<button data-k="books" class="on">Books</button><button data-k="comics">Comics & manhwa</button>';
  head.after(seg);
  seg.querySelectorAll('[data-k]').forEach(b=>b.onclick=()=>{shelfKind=b.dataset.k;seg.querySelectorAll('[data-k]').forEach(x=>x.classList.toggle('on',x===b));renderShelf();});
  const original=renderShelf;
  window.renderShelf=renderShelf=async function(){
    $('add-books').textContent=shelfKind==='books'?'＋ Add':'＋ Add';
    $('add-books').onclick=shelfKind==='books'?()=>Kotoba.pickBooks():()=>addComicsMenu();
    if(shelfKind==='books')return original();
    return renderComicShelf();
  };
})();
on('comics-scanning',()=>toast('Scanning folder…',6000));
on('comics-added',r=>{toast(r.series?`Found ${r.series} ${r.series===1?'series':'series'} · ${r.chapters} chapters`:'No comics found in that folder',4000);if(tab==='reader'&&shelfKind==='comics')renderComicShelf();});

async function addComicsMenu(){
  await menuSheet('Add comics',[
    {label:'Choose a folder (Mihon downloads, series or chapter)',icon:'folder',run:()=>Kotoba.pickComicFolder()},
    {label:'Choose CBZ / ZIP files',icon:'book',run:()=>Kotoba.pickComicFiles()},
    {label:'Scan the app’s own comics folder',icon:'refresh',run:async()=>{const r=await api('comic.scanLocal');toast(r.series?`Found ${r.series} series · ${r.chapters} chapters`:`Nothing in ${r.path}`,4000);renderComicShelf();}},
  ]);
}

async function renderComicShelf(){
  const list=await api('comics');
  $('reader-sub').textContent=list.length?`${list.length} series`:'Comics and manhwa';
  if(!list.length){
    $('shelf').innerHTML=`<div class="empty"><span class="glyph">漫</span><h2>Add comics</h2>Pick your Mihon downloads folder, a series folder, or CBZ files. Nothing is copied — Kotoba reads them where they are.<br><br><button class="btn primary" id="comic-add">＋ Add comics</button></div>`;
    $('comic-add').onclick=()=>addComicsMenu();
    return;
  }
  $('shelf').innerHTML=`<div class="shelf">${list.map(s=>`<button class="book" data-series="${s.id}">
      <div class="cover"><img src="/comic/cover/${s.id}?v=${coverVersion}" alt="" loading="lazy" onerror="this.remove()"><span class="fmt">${esc((s.lang||'').toUpperCase()||'CB')}</span></div>
      <b>${esc(s.title)}</b><small>${esc(s.source?s.source+' · ':'')}${s.read}/${s.chapters} read</small><div class="bar-p"><i style="width:${s.chapters?Math.round(s.read/s.chapters*100):0}%"></i></div></button>`).join('')}</div>`;
  $('shelf').querySelectorAll('[data-series]').forEach(b=>b.onclick=handle(()=>openSeries(+b.dataset.series)));
}

// ---------- series page ----------
async function openSeries(id){
  const el=document.createElement('div');
  pushPage(el,{onResume:()=>render()});
  on('comic-cover',r=>{if(el.isConnected&&r.id===id)render();});
  let s;
  async function render(){
    s=await api('comic.series',{id});
    const next=s.chapters.find(c=>c.opened&&!c.read)||s.chapters.find(c=>!c.read)||s.chapters[0];
    el.innerHTML=`<div class="bar"><button class="icon-btn" data-a="back">${icon('back')}</button><div class="title"><b>${esc(s.title)}</b><small>${esc(s.source||'')}${s.source?' · ':''}${s.chapters.length} chapters</small></div><button class="icon-btn" data-a="more">${icon('more')}</button></div>
      <div class="scroll">
        <div style="display:flex;gap:14px;padding:16px">
          <div class="cover" style="width:110px;flex:none"><img src="/comic/cover/${s.id}?v=${coverVersion}" alt="" onerror="this.remove()"></div>
          <div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:10px;justify-content:flex-end">
            ${next?`<button class="btn primary" data-a="continue">${next.opened?'Continue':'Start'} · ${esc(next.name)}</button>`:''}
            <p class="hint" style="margin:0">${s.chapters.filter(c=>c.read).length} of ${s.chapters.length} read</p>
          </div>
        </div>
        <div class="section-label">Chapters<button data-a="order">${localStorage.getItem('chapterOrder')==='desc'?'Newest first':'Oldest first'}</button></div>
        <div data-f="chapters"></div>
      </div>`;
    el.querySelector('[data-a="back"]').onclick=()=>popPage();
    if(next)el.querySelector('[data-a="continue"]').onclick=handle(()=>openComic(s,next.id,next.read?0:next.page));
    el.querySelector('[data-a="order"]').onclick=()=>{localStorage.setItem('chapterOrder',localStorage.getItem('chapterOrder')==='desc'?'asc':'desc');render();};
    const chapters=localStorage.getItem('chapterOrder')==='desc'?[...s.chapters].reverse():s.chapters;
    const box=el.querySelector('[data-f="chapters"]');
    box.innerHTML=chapters.map(c=>`<button class="toc-row" data-c="${c.id}" style="${c.read?'color:var(--muted)':''}">${esc(c.name)}<small>${c.read?'✓ Read':c.opened&&c.pages?`Page ${c.page+1} of ${c.pages}`:c.pages?`${c.pages} pages`:''}</small></button>`).join('');
    box.querySelectorAll('[data-c]').forEach(b=>{
      const c=s.chapters.find(x=>x.id===+b.dataset.c);
      let timer=null;
      b.addEventListener('touchstart',()=>{timer=setTimeout(()=>{timer=null;chapterMenu(c);},500);},{passive:true});
      b.addEventListener('touchend',()=>{if(timer)clearTimeout(timer);},{passive:true});
      b.addEventListener('touchmove',()=>{if(timer)clearTimeout(timer);timer=null;},{passive:true});
      b.onclick=handle(()=>openComic(s,c.id,c.read?0:c.page));
    });
    el.querySelector('[data-a="more"]').onclick=handle(()=>menuSheet(s.title,[
      {label:'Rename',icon:'edit',run:async()=>{const t=await prompt2('Series title',s.title);if(!t)return;await api('comic.rename',{id,title:t});render();}},
      {label:'Change cover image…',icon:'book',run:()=>Kotoba.pickComicCover(id)},
      {label:'Use automatic cover',icon:'refresh',run:async()=>{await api('comic.resetCover',{id});coverVersion=Date.now();render();toast('Cover reset');}},
      {label:'Mark all as read',icon:'check',run:async()=>{await api('comic.read',{ids:s.chapters.map(c=>c.id),read:true});render();}},
      {label:'Mark all as unread',icon:'refresh',run:async()=>{await api('comic.read',{ids:s.chapters.map(c=>c.id),read:false});render();}},
      {label:'Page bookmarks',icon:'bookmark',run:async()=>{const marks=await api('comic.marks',{series:id});if(!marks.length){toast('No bookmarks yet');return;}const c=await menuSheet('Bookmarks',marks.map(m=>({label:m.chapter+' · page '+(m.page+1),icon:'bookmark',m})));if(c)openComic(s,c.m.chapter_id,c.m.page);}},
      '-',
      {label:'Remove from library',icon:'trash',danger:true,run:async()=>{if(!await confirm2('Remove “'+s.title+'”?','Your files are not deleted; only Kotoba’s list and progress.','Remove',true))return;await api('comic.delete',{id});popPage();renderComicShelf();}},
    ]));
  }
  async function chapterMenu(c){
    await menuSheet(c.name,[
      {label:c.read?'Mark as unread':'Mark as read',icon:'check',run:async()=>{await api('comic.read',{ids:[c.id],read:!c.read});render();}},
      {label:'Mark this and earlier as read',icon:'check',run:async()=>{const i=s.chapters.findIndex(x=>x.id===c.id);await api('comic.read',{ids:s.chapters.slice(0,i+1).map(x=>x.id),read:true});render();}},
    ]);
  }
  await render();
}

// ---------- the comic reader ----------
async function openComic(series,chapterId,startPage=0){
  const settings={...comicDefaults(),...(series.settings||{})};
  if(!settings.mode)settings.mode=series.lang==='ja'?'rtl':'webtoon';
  const chapters=series.chapters;
  const el=document.createElement('div');el.className='comic-page bare';
  el.innerHTML=`<div class="comic-view" data-f="view"></div>
    <div class="rd-top"><button class="icon-btn" data-a="back">${icon('back')}</button><div class="title"><b>${esc(series.title)}</b><small data-f="chname"></small></div>
      <button class="icon-btn" data-a="search" aria-label="Search dictionary">${icon('search')}</button><button class="icon-btn" data-a="mark" aria-label="Bookmark page">${icon('bookmark')}</button><button class="icon-btn" data-a="settings" aria-label="Reading mode">${icon('text')}</button><button class="icon-btn" data-a="hide" aria-label="Hide controls">${icon('down')}</button></div>
    <div class="rd-bottom"><div class="rd-row"><button class="icon-btn" data-a="prevch">${icon('prev')}</button><input type="range" min="0" max="0" value="0" data-f="slider" aria-label="Page"><button class="icon-btn" data-a="nextch">${icon('next')}</button></div><div class="rd-meta"><span data-f="pagelabel"></span><span data-f="modelabel"></span></div><button class="rd-hide" data-a="hide2">Hide controls ⌄</button></div>
    <div class="rd-status" data-f="status"></div>
    <button class="ocr-fab" data-a="ocrpage" aria-label="All text on this page" hidden>☰</button><button class="ocr-fab main" data-a="ocr" aria-label="Text layer">文</button>`;
  pushPage(el,{onClose:()=>{saveProgress(true);appBars();}});
  setBars('#000000',false);
  const f=(n)=>el.querySelector(`[data-f="${n}"]`);
  const view=f('view');
  const state={chapter:chapterId,page:startPage,loaded:[],observer:null,unloader:null,counts:{}};
  const idx=(id)=>chapters.findIndex(c=>c.id===id);
  const toggleChrome=(show)=>el.classList.toggle('bare',show===undefined?!el.classList.contains('bare'):!show);
  el.querySelector('[data-a="back"]').onclick=()=>popPage();
  el.querySelector('[data-a="hide"]').onclick=()=>toggleChrome(false);
  el.querySelector('[data-a="hide2"]').onclick=()=>toggleChrome(false);
  const rtl=()=>settings.mode==='rtl';

  async function pageCount(id){
    if(state.counts[id]==null)state.counts[id]=(await api('comic.pages',{chapter:id})).count;
    return state.counts[id];
  }
  let saveTimer=null;
  function saveProgress(now){
    clearTimeout(saveTimer);
    const run=()=>{const n=state.counts[state.chapter]||0;api('comic.progress',{chapter:state.chapter,page:state.page,read:n>0&&state.page>=n-1}).catch(()=>{});};
    if(now)run();else saveTimer=setTimeout(run,600);
  }
  function updateUi(){
    const c=chapters[idx(state.chapter)];const n=state.counts[state.chapter]||0;
    f('chname').textContent=c?c.name:'';
    f('pagelabel').textContent=n?`Page ${state.page+1} of ${n}`:'';
    f('modelabel').textContent=({webtoon:'Webtoon',ltr:'Pages → LTR',rtl:'Pages ← RTL'})[settings.mode];
    f('status').textContent=n?`${state.page+1}/${n}`:'';
    const sl=f('slider');sl.max=Math.max(0,n-1);sl.value=state.page;sl.style.direction=rtl()?'rtl':'ltr';
    el.querySelector('.rd-row').style.flexDirection=rtl()?'row-reverse':'row';
    el.querySelector('[data-a="prevch"]').innerHTML=icon(rtl()?'next':'prev');
    el.querySelector('[data-a="nextch"]').innerHTML=icon(rtl()?'prev':'next');
  }

  // ----- webtoon: one continuous strip; chapters are appended as you reach the end -----
  function imgFor(ch,i){
    const im=document.createElement('img');
    im.className='cpage';im.dataset.ch=ch;im.dataset.i=i;im.alt='';im.decoding='async';
    im.dataset.src=`/comic/${ch}/${i}`;
    const ratio=state.ratio&&state.ratio[ch+':'+i];
    im.style.aspectRatio=ratio?String(ratio):'800 / 2200';
    im.onload=()=>{if(!state.ratio)state.ratio={};const r=im.naturalWidth/im.naturalHeight;state.ratio[ch+':'+i]=r;im.style.aspectRatio=String(r);};
    return im;
  }
  async function appendChapter(ch,prepend=false){
    if(state.loaded.includes(ch))return;
    const n=await pageCount(ch);
    const frag=document.createDocumentFragment();
    if(settings.separators){
      const sep=document.createElement('div');sep.className='chapter-sep';sep.dataset.ch=ch;
      sep.textContent=chapters[idx(ch)].name;frag.appendChild(sep);
    }
    for(let i=0;i<n;i++){const im=imgFor(ch,i);if(settings.gap)im.classList.add('gap');frag.appendChild(im);}
    if(prepend){
      const first=view.firstElementChild;const before=view.scrollHeight;
      view.insertBefore(frag,first);state.loaded.unshift(ch);
      view.scrollTop+=view.scrollHeight-before;
    }else{view.appendChild(frag);state.loaded.push(ch);}
    view.querySelectorAll(`img.cpage[data-ch="${ch}"]`).forEach(im=>state.observer.observe(im));
  }
  function setupWebtoon(){
    view.className='comic-view webtoon';view.innerHTML='';state.loaded=[];
    // Load images well before they scroll into view; release ones far away to keep memory flat.
    state.observer=new IntersectionObserver(entries=>{
      for(const e of entries){
        const im=e.target;
        if(e.isIntersecting){if(!im.getAttribute('src'))im.src=im.dataset.src;}
        else if(Math.abs(e.boundingClientRect.top)>window.innerHeight*8&&im.getAttribute('src')){im.removeAttribute('src');}
      }
    },{root:view,rootMargin:'250% 0px 250% 0px'});
  }
  function currentFromScroll(){
    // The page crossing the middle of the screen is the current one.
    const y=window.innerHeight/2;
    const el2=document.elementFromPoint(window.innerWidth/2,y);
    const im=el2&&el2.closest&&el2.closest('img.cpage');
    if(!im)return;
    const ch=+im.dataset.ch,i=+im.dataset.i;
    if(ch!==state.chapter||i!==state.page){
      if(ch!==state.chapter){saveProgress(true);}
      state.chapter=ch;state.page=i;updateUi();saveProgress();
    }
    // Near the end of the last loaded chapter: append the next one (seamless series scrolling).
    const lastCh=state.loaded[state.loaded.length-1];
    const nextIdx=idx(lastCh)+1;
    if(ch===lastCh&&i>=(state.counts[ch]||0)-3&&nextIdx<chapters.length)appendChapter(chapters[nextIdx].id);
    // Near the top of the first loaded chapter: prepend the previous one.
    const firstCh=state.loaded[0];const prevIdx=idx(firstCh)-1;
    if(ch===firstCh&&i<=0&&view.scrollTop<window.innerHeight&&prevIdx>=0&&!state.prepending){state.prepending=true;appendChapter(chapters[prevIdx].id,true).finally(()=>state.prepending=false);}
  }
  async function openWebtoon(ch,page){
    setupWebtoon();
    await appendChapter(ch);
    state.chapter=ch;state.page=page;
    const target=view.querySelector(`img.cpage[data-ch="${ch}"][data-i="${page}"]`);
    if(target&&page>0){target.src=target.dataset.src;await new Promise(r=>{if(target.complete)r();else{target.onload=(e=>{const r0=target.naturalWidth/target.naturalHeight;target.style.aspectRatio=String(r0);r();});setTimeout(r,1500);}});
      // Pages above the target need their sizes before the jump is exact; load them first.
      const above=[...view.querySelectorAll(`img.cpage[data-ch="${ch}"]`)].slice(0,page);
      await Promise.all(above.map(im=>new Promise(r=>{if(im.complete&&im.getAttribute('src'))return r();im.src=im.dataset.src;im.addEventListener('load',()=>r(),{once:true});im.addEventListener('error',()=>r(),{once:true});setTimeout(r,3000);})));
      target.scrollIntoView({block:'start'});}
    else view.scrollTop=0;
    updateUi();
  }

  // ----- paged (LTR / RTL): one image per screen, native swipe with snapping -----
  async function openPaged(ch,page){
    view.className='comic-view paged'+(rtl()?' rtl':'')+(settings.fit==='height'?' fit-height':settings.fit==='original'?' fit-original':'');
    view.innerHTML='';state.loaded=[ch];
    const n=await pageCount(ch);
    for(let i=0;i<n;i++){
      const slide=document.createElement('div');slide.className='slide';
      const im=imgFor(ch,i);im.style.aspectRatio='';im.src=im.dataset.src;im.loading=Math.abs(i-page)<3?'eager':'lazy';
      slide.appendChild(im);view.appendChild(slide);
    }
    state.chapter=ch;state.page=Math.min(page,n-1);
    requestAnimationFrame(()=>{goToPage(state.page,false);updateUi();});
  }
  function goToPage(i,animate=true){
    const w=view.clientWidth;
    view.scrollTo({left:(rtl()?-1:1)*i*w,behavior:animate&&settings.pageAnim?'smooth':'instant'});
  }
  function pagedIndex(){return Math.round(Math.abs(view.scrollLeft)/view.clientWidth);}
  async function turn(dir){
    if(settings.mode==='webtoon'){view.scrollBy({top:dir*(view.clientHeight*0.85),behavior:'smooth'});return;}
    const n=state.counts[state.chapter]||0;const i=pagedIndex()+dir;
    if(i>=n){const k=idx(state.chapter)+1;if(k<chapters.length){saveProgress(true);await openPaged(chapters[k].id,0);}else toast('Last chapter');return;}
    if(i<0){const k=idx(state.chapter)-1;if(k>=0){const pc=await pageCount(chapters[k].id);await openPaged(chapters[k].id,pc-1);}return;}
    goToPage(i);
  }
  view.addEventListener('scroll',()=>{
    scheduleOcr();
    if(settings.mode==='webtoon'){currentFromScroll();return;}
    const i=pagedIndex();if(i!==state.page){state.page=i;updateUi();saveProgress();}
  },{passive:true});

  // Taps: middle toggles the controls; sides turn pages (reading-direction aware); double tap zooms.
  let lastTap=0;
  view.addEventListener('click',e=>{
    const now=Date.now();
    if(now-lastTap<300){lastTap=0;zoomAt(e);return;}
    lastTap=now;
    const x=e.clientX,w=view.clientWidth;
    setTimeout(()=>{
      if(!lastTap)return;
      if(!el.classList.contains('bare')){toggleChrome(false);return;}
      if(zoom>1){toggleChrome(true);return;}// zoomed in: sides pan, they don't turn pages
      if(x<w*0.25)turn(rtl()?1:-1);
      else if(x>w*0.75)turn(rtl()?-1:1);
      else toggleChrome(true);
    },280);
  });
  // Zoom is real layout (images get wider and the view scrolls), so the text layer keeps working and sheets stay normal size.
  let zoom=1;
  function scroller(){return settings.mode==='webtoon'?view:(view.children[pagedIndex()]||view);}
  function setZoom(z,cx,cy){
    // Below 1 the pages get smaller than the screen (more of a webtoon at once); above 1 they're wider and the view pans.
    z=Math.max(0.25,Math.min(4,z));if(Math.abs(z-1)<0.02)z=1;if(Math.abs(z-zoom)<0.001)return;
    const sc=scroller(),r=sc.getBoundingClientRect();
    const mx=(cx??r.left+r.width/2)-r.left,my=(cy??r.top+r.height/2)-r.top;
    const px=(sc.scrollLeft+mx)/zoom,py=(sc.scrollTop+my)/zoom;
    const old=zoom;zoom=z;
    view.style.setProperty('--cz',z);view.classList.toggle('zoomed',z>1);view.classList.toggle('shrunk',z<1);
    if(settings.mode==='webtoon'){sc.scrollLeft=px*z-mx;sc.scrollTop=py*z-my;}
    else{sc.scrollLeft=px*z-mx;sc.scrollTop=py*z-my;}
    if(old===1||z===1)requestAnimationFrame(placeLayers);else placeLayers();
  }
  function zoomAt(e){setZoom(zoom>1?1:2.2,e.clientX,e.clientY);}
  // Keyboard zoom on the Mac (⌘+ / ⌘− / ⌘0): d is 1, -1 or 0 (back to fit).
  el.kotobaZoom=(d)=>{setZoom(d===0?1:zoom*(d>0?1.25:0.8));toast(zoom===1?'Fit to screen':`Zoom ${Math.round(zoom*100)}%`,900);};
  // Pinch
  let pinch=null;
  const dist=(t)=>Math.hypot(t[0].clientX-t[1].clientX,t[0].clientY-t[1].clientY);
  view.addEventListener('touchstart',e=>{if(e.touches.length===2){pinch={d:dist(e.touches),z:zoom};lastTap=0;}},{passive:true});
  view.addEventListener('touchmove',e=>{
    if(!pinch||e.touches.length!==2)return;
    e.preventDefault();
    const t=e.touches;setZoom(pinch.z*dist(t)/pinch.d,(t[0].clientX+t[1].clientX)/2,(t[0].clientY+t[1].clientY)/2);
  },{passive:false});
  view.addEventListener('touchend',e=>{if(e.touches.length<2&&pinch){pinch=null;if(zoom<1.08)setZoom(1);}},{passive:true});

  // ----- OCR text layer: recognized text boxes over the artwork, tap one to look it up -----
  const ocrData=new Map();const ocrQueue=[];let ocrBusy=false,ocrTimer=null;
  const pageKey=(im)=>im.dataset.ch+':'+im.dataset.i;
  function ocrToggle(on){
    settings.ocr=on;api('comic.settings',{id:series.id,settings}).catch(()=>{});
    el.classList.toggle('ocr-on',on);el.querySelector('[data-a="ocrpage"]').hidden=!on;
    if(on){toast('Text layer on · tap a speech bubble to look it up',2000);scheduleOcr();}
  }
  // Runs on every scroll event, so it stays cheap: the text boxes live inside the scrolling page and move with it,
  // so they're only placed again when the layout changes (zoom, resize, an image loading), not here.
  function scheduleOcr(){if(!settings.ocr)return;clearTimeout(ocrTimer);ocrTimer=setTimeout(ocrRefresh,250);}
  function ocrRefresh(){
    if(!settings.ocr)return;
    const vr=view.getBoundingClientRect();
    const near=[...view.querySelectorAll('img.cpage')].filter(im=>{const r=im.getBoundingClientRect();return r.bottom>vr.top-vr.height&&r.top<vr.bottom+vr.height&&r.right>vr.left-vr.width&&r.left<vr.right+vr.width;});
    // Current page first, then the ones around it.
    const cy=vr.top+vr.height/2,cx=vr.left+vr.width/2;
    near.sort((a,b)=>{const ra=a.getBoundingClientRect(),rb=b.getBoundingClientRect();return Math.hypot(ra.left+ra.width/2-cx,ra.top+ra.height/2-cy)-Math.hypot(rb.left+rb.width/2-cx,rb.top+rb.height/2-cy);});
    for(const im of near){
      const k=pageKey(im),d=ocrData.get(k);
      if(d===undefined){ocrData.set(k,null);ocrQueue.push(im);}
      else if(d&&!(im._layer&&im._layer.isConnected))drawLayer(im,d);
    }
    pumpOcr();
  }
  async function pumpOcr(){
    if(ocrBusy)return;ocrBusy=true;
    try{
      while(ocrQueue.length&&settings.ocr){
        const im=ocrQueue.shift();const k=pageKey(im);
        f('status').textContent='Reading text…';
        try{const r=await api('ocr.page',{chapter:+im.dataset.ch,page:+im.dataset.i,lang:series.lang==='ja'?'ja':'ko'});ocrData.set(k,r);
          const live=view.querySelector(`img.cpage[data-ch="${im.dataset.ch}"][data-i="${im.dataset.i}"]`);if(live)drawLayer(live,r);}
        catch(e){ocrData.delete(k);toast('Text recognition failed: '+(e.message||e),4000);break;}
      }
    }finally{ocrBusy=false;updateUi();}
  }
  // Background text recognition (PaddleOCR-VL on the GPU) waits while you use the reader: touches anywhere in the app
  // (the page, a bubble's sheet, translation, a dictionary pop-up), and all the time a sheet is open.
  let lastPing=0;
  const interacting=()=>{const now=Date.now();if(now-lastPing<300)return;lastPing=now;if(window.Kotoba&&Kotoba.interacting)try{Kotoba.interacting();}catch(e){}};
  const touchTypes=['touchstart','touchmove','scroll','wheel','pointerdown','keydown'];
  for(const type of touchTypes)document.addEventListener(type,interacting,{passive:true,capture:true});
  const sheetPing=setInterval(()=>{
    if(!el.isConnected){clearInterval(sheetPing);for(const type of touchTypes)document.removeEventListener(type,interacting,{capture:true});return;}
    // A sheet open, or a dictionary entry opened from a bubble on top of the reader.
    if(document.querySelector('.sheet')||[...document.querySelectorAll('#pages > .page')].pop()!==el)interacting();
  },800);
  // PaddleOCR-VL reads the bubbles again in the background; its better text replaces the first reading when it's ready.
  on('ocr-refined',async(e)=>{
    if(!el.isConnected)return;
    const k=e.chapter+':'+e.page;if(!ocrData.get(k))return;
    try{
      const r=await api('ocr.page',{chapter:e.chapter,page:e.page,lang:series.lang==='ja'?'ja':'ko'});ocrData.set(k,r);
      const live=view.querySelector(`img.cpage[data-ch="${e.chapter}"][data-i="${e.page}"]`);if(live&&settings.ocr)drawLayer(live,r);
    }catch(err){}
  });
  function drawLayer(im,r){
    if(im._layer)im._layer.remove();
    const layer=document.createElement('div');layer.className='ocr-layer'+(r.refining?' refining':'');
    layer.innerHTML=r.blocks.map((b,n)=>`<button class="ocr-box" data-n="${n}" style="left:${b.x/r.w*100}%;top:${b.y/r.h*100}%;width:${b.w/r.w*100}%;height:${b.h/r.h*100}%" aria-label="${esc(b.text)}"></button>`).join('');
    layer.onclick=e=>{const b=e.target.closest('.ocr-box');if(!b)return;e.stopPropagation();bubbleSheet(r.blocks[+b.dataset.n].text,r);};
    im.parentElement.appendChild(layer);im._layer=layer;placeLayer(im);
  }
  function placeLayer(im){
    const l=im._layer;if(!l)return;
    l.style.left=im.offsetLeft+'px';l.style.top=im.offsetTop+'px';l.style.width=im.offsetWidth+'px';l.style.height=im.offsetHeight+'px';
  }
  function placeLayers(){view.querySelectorAll('img.cpage').forEach(im=>{if(im._layer)placeLayer(im);});}
  // Rotation or a window resize changes the images' size; the boxes follow.
  const onResize=()=>{if(!el.isConnected){removeEventListener('resize',onResize);return;}if(settings.ocr)requestAnimationFrame(placeLayers);};
  addEventListener('resize',onResize);
  view.addEventListener('load',()=>{if(settings.ocr)requestAnimationFrame(placeLayers);},true);
  el.querySelector('[data-a="ocr"]').onclick=e=>{e.stopPropagation();ocrToggle(!settings.ocr);};
  el.querySelector('[data-a="ocrpage"]').onclick=handle(async e=>{
    e.stopPropagation();
    const k=state.chapter+':'+state.page;
    let r=ocrData.get(k);if(!r){toast('Reading text…',1000);r=await api('ocr.page',{chapter:state.chapter,page:state.page,lang:series.lang==='ja'?'ja':'ko'});ocrData.set(k,r);}
    if(!r.blocks.length){toast('No text found on this page');return;}
    const s=openSheet(`<div class="sheet-body ocr-list">${r.blocks.map((b,n)=>`<button class="toc-row" data-n="${n}">${esc(b.text)}</button>`).join('')}</div>
      <div class="sheet-foot"><button class="btn wide" id="op-copy">${icon('copy')} Copy all</button><button class="btn wide" id="op-tr">${icon('share')} Translate all</button></div>`,{title:`Page ${state.page+1} text`});
    const all=r.blocks.map(b=>b.text).join('\n');
    s.sheet.querySelectorAll('[data-n]').forEach(b=>b.onclick=()=>{closeSheet(s);bubbleSheet(r.blocks[+b.dataset.n].text,r);});
    s.sheet.querySelector('#op-copy').onclick=()=>{Kotoba.copy(all);toast('Copied');};
    s.sheet.querySelector('#op-tr').onclick=()=>Kotoba.translate(all);
  });

  // The page's other bubbles go along as context for translation (Hy-MT2 on the Mac uses them: 손이 맵네 is "hits hard").
  function bubbleSheet(text,page){return ocrTextSheet(text,{lang:series.lang||'ko',source:series.title,title:'Speech bubble',context:page?page.blocks.map(b=>b.text).join('\n'):''});}

  // Controls
  f('slider').oninput=()=>{
    const i=+f('slider').value;
    if(settings.mode==='webtoon'){const t=view.querySelector(`img.cpage[data-ch="${state.chapter}"][data-i="${i}"]`);if(t)t.scrollIntoView({block:'start'});}
    else goToPage(i,false);
  };
  el.querySelector('[data-a="nextch"]').onclick=handle(async()=>{const k=idx(state.chapter)+1;if(k>=chapters.length){toast('Last chapter');return;}saveProgress(true);await open(chapters[k].id,0);});
  el.querySelector('[data-a="prevch"]').onclick=handle(async()=>{const k=idx(state.chapter)-1;if(k<0){toast('First chapter');return;}saveProgress(true);await open(chapters[k].id,0);});
  el.querySelector('[data-a="mark"]').onclick=handle(async()=>{
    const c=await menuSheet(`Page ${state.page+1}`,[{label:'Bookmark this page',icon:'bookmark',id:'mark'},{label:'Use this page as the series cover',icon:'book',id:'cover'}]);
    if(!c)return;
    if(c.id==='mark'){await api('comic.mark',{chapter:state.chapter,page:state.page});toast('Page bookmarked',1200);}
    else{await api('comic.coverFromPage',{chapter:state.chapter,page:state.page});coverVersion=Date.now();toast('Cover updated',1200);}
  });
  el.querySelector('[data-a="settings"]').onclick=handle(()=>modeSheet());
  el.querySelector('[data-a="search"]').onclick=handle(()=>dictionarySearchSheet('',series.title));
  function modeSheet(){
    const seg=(key,opts)=>`<div class="seg" style="min-width:210px">${opts.map(([v,l])=>`<button data-k="${key}" data-v="${v}" class="${String(settings[key])===String(v)?'on':''}">${l}</button>`).join('')}</div>`;
    const s=openSheet(`<div class="sheet-body">
      <div class="switch-row"><div><b>Reading mode</b><small>Webtoon scrolls every chapter as one strip</small></div></div>
      ${seg('mode',[['webtoon','Webtoon ↕'],['ltr','Pages →'],['rtl','Pages ←']])}
      <div class="switch-row"><div><b>Fit</b><small>For page modes</small></div>${seg('fit',[['width','Width'],['height','Height'],['original','Original']])}</div>
      <div class="switch-row"><div><b>Chapter titles between chapters</b><small>In webtoon mode</small></div><label class="toggle"><input type="checkbox" id="cm-sep" ${settings.separators?'checked':''}><span></span></label></div>
      <div class="switch-row"><div><b>Small gap between images</b><small>Off = seamless strip</small></div><label class="toggle"><input type="checkbox" id="cm-gap" ${settings.gap?'checked':''}><span></span></label></div>
      <div class="switch-row"><div><b>Page turn animation</b></div><label class="toggle"><input type="checkbox" id="cm-anim" ${settings.pageAnim?'checked':''}><span></span></label></div>
      <button class="btn small" id="cm-default" style="margin-top:10px">Use for all series</button></div>`,{title:'Reading'});
    const save=()=>api('comic.settings',{id:series.id,settings});
    s.sheet.querySelectorAll('[data-k]').forEach(b=>b.onclick=handle(async()=>{
      settings[b.dataset.k]=b.dataset.v;s.sheet.querySelectorAll(`[data-k="${b.dataset.k}"]`).forEach(x=>x.classList.toggle('on',x===b));
      await save();await open(state.chapter,state.page);
    }));
    s.sheet.querySelector('#cm-sep').onchange=handle(async e=>{settings.separators=e.target.checked;await save();await open(state.chapter,state.page);});
    s.sheet.querySelector('#cm-gap').onchange=handle(async e=>{settings.gap=e.target.checked;await save();view.querySelectorAll('img.cpage').forEach(i=>i.classList.toggle('gap',settings.gap));});
    s.sheet.querySelector('#cm-anim').onchange=handle(e=>{settings.pageAnim=e.target.checked;return save();});
    s.sheet.querySelector('#cm-default').onclick=()=>{try{localStorage.setItem('comicDefaults',JSON.stringify({...settings,mode:''}));}catch(e){}toast('Saved as default');};
  }
  async function open(ch,page){
    await pageCount(ch);
    zoom=1;view.style.setProperty('--cz',1);view.classList.remove('zoomed','shrunk');
    if(settings.mode==='webtoon')await openWebtoon(ch,page);else await openPaged(ch,page);
    updateUi();saveProgress(true);
    if(settings.ocr){el.classList.add('ocr-on');el.querySelector('[data-a="ocrpage"]').hidden=false;scheduleOcr();}
  }
  window.__comic={state,settings,turn,open,view};
  await open(chapterId,startPage);
  toggleChrome(true);setTimeout(()=>toggleChrome(false),1800);
}

// Recognized text (a speech bubble, a game's text box): tap a word to look it up (conjugations are analysed);
// OCR mistakes can be fixed first. opts: {lang, source (card/lookup source name), title}.
function ocrTextSheet(text,opts={}){
  const cjk=/[\u3040-\u30ff\u3400-\u9fff\uf900-\ufaff]/;
  const render=()=>{
    let html='',o=0;
    for(const part of text.split(/(\s+)/)){
      if(!part){continue;}
      if(/^\s+$/.test(part)){html+=part.includes('\n')?'<br>':' ';o+=part.length;continue;}
      if(cjk.test(part))html+=[...part].map((ch,i)=>`<span class="ocr-w" data-o="${o+i}">${esc(ch)}</span>`).join('');
    // Thai has no spaces between words: every character is tappable and lookup reads from there to the end of the phrase.
    else if(/[\u0e00-\u0e7f]/.test(part))html+=`<span class="ocr-word">`+[...part].map((ch,i)=>`<span class="ocr-w" data-o="${o+i}" data-thai="${o+part.length}">${esc(ch)}</span>`).join('')+`</span>`;
      // Korean: OCR often drops spaces (빨리집에가서), so each syllable is tappable; lookup reads from there to the end of the word.
      else if(/[\uac00-\ud7a3]/.test(part))html+=`<span class="ocr-word">`+[...part].map((ch,i)=>`<span class="ocr-w" data-o="${o+i}" data-end="${o+part.length}">${esc(ch)}</span>`).join('')+`</span>`;
      else html+=`<span class="ocr-w" data-o="${o}" data-t="${esc(part)}">${esc(part)}</span>`;
      o+=part.length;
    }
    return html;
  };
  const s=openSheet(`<div class="sheet-body"><p class="ocr-text" lang="${opts.lang||'ja'}">${render()}</p><p class="hint">Tap a word to look it up.</p></div>
    <div class="sheet-foot"><button class="btn wide" id="ob-edit">${icon('edit')} Fix</button><button class="btn wide" id="ob-copy">${icon('copy')} Copy</button><button class="btn wide" id="ob-tr">${icon('share')} Translate</button><button class="btn primary wide" id="ob-card">${icon('star')} Save</button></div>`,{title:opts.title||'Text'});
  const wire=()=>s.sheet.querySelectorAll('.ocr-w').forEach(w=>w.onclick=handle(()=>{
    const word=w.dataset.thai!=null?text.slice(+w.dataset.o,+w.dataset.thai)
      :w.dataset.t!=null?w.dataset.t.replace(/^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$/gu,'')
      :w.dataset.end!=null?text.slice(+w.dataset.o,+w.dataset.end).replace(/[^\p{L}\p{N}]+$/gu,'')
      :text.slice(+w.dataset.o).replace(/\s+/g,'');
    if(!word)return;
    s.sheet.querySelectorAll('.ocr-w.on').forEach(x=>x.classList.remove('on'));
    // Highlight what is being looked up: the tapped syllable to the end of its word (Korean) or the tapped token.
    if(w.dataset.end!=null)s.sheet.querySelectorAll('.ocr-w').forEach(x=>{if(x.dataset.end===w.dataset.end&&+x.dataset.o>=+w.dataset.o)x.classList.add('on');});
    else w.classList.add('on');
    return lookupSheet(word,{context:text},{book:opts.source||'',lang:opts.lang||''});
  }));
  wire();
  s.sheet.querySelector('#ob-edit').onclick=handle(async()=>{const t=await prompt2('Fix recognized text',text);if(t==null||!t.trim())return;text=t.trim();s.sheet.querySelector('.ocr-text').innerHTML=render();wire();});
  s.sheet.querySelector('#ob-copy').onclick=()=>{Kotoba.copy(text);toast('Copied');};
  // The Mac's in-app translator takes context; the phone's Translate hands the text to the Google Translate app.
  s.sheet.querySelector('#ob-tr').onclick=()=>window.__translateInApp?window.__translateInApp(text,opts.context||''):Kotoba.translate(text);
  s.sheet.querySelector('#ob-card').onclick=()=>{closeSheet(s);openSaveSheet({review:true,kind:'selection',headword:text.slice(0,60),back:'',context:text});};
}
