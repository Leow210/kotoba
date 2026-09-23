'use strict';
/* Book reader: shelf, EPUB/TXT reading (horizontal/vertical, scroll/paged), lookup, highlights, bookmarks. */

const READER_DEFAULTS={fontSize:19,lineHeight:1.8,margin:22,font:'book',rtheme:'light',writing:'auto',mode:'scroll',pageDir:'auto',pageBreaks:false,pageAnim:true,tapLookup:true};
function readerDefaults(){try{return {...READER_DEFAULTS,...JSON.parse(localStorage.getItem('readerDefaults')||'{}')};}catch(e){return {...READER_DEFAULTS};}}
const RTHEMES={light:{bg:'#fbf8f1',fg:'#22261f'},sepia:{bg:'#f1e7d2',fg:'#3b3122'},dark:{bg:'#121412',fg:'#d9d6cc'},black:{bg:'#000000',fg:'#c9c6bc'}};

// ---------- shelf ----------
on('books-imported',r=>{
  if(r.added.length)toast(`Added ${r.added.length} ${r.added.length===1?'book':'books'}`);
  r.errors.forEach(e=>toast(e,5000));
  if(tab==='reader')renderShelf();
});
$('add-books').onclick=()=>Kotoba.pickBooks();

async function renderShelf(){
  const list=await api('books');
  $('reader-sub').textContent=list.length?`${list.length} ${list.length===1?'book':'books'}`:'Books you’re reading';
  if(!list.length){
    $('shelf').innerHTML=`<div class="empty"><span class="glyph">書</span><h2>Add a book</h2>EPUB and TXT books in Japanese, Korean, Thai and Russian. Select any word to look it up and save it as a card.<br><br><button class="btn primary" id="shelf-add">＋ Add books</button></div>`;
    $('shelf-add').onclick=()=>Kotoba.pickBooks();
    return;
  }
  const recent=list.filter(b=>b.opened).slice(0,1)[0];
  $('shelf').innerHTML=(recent?`<div class="section-label">Continue reading</div>`:'')+
    (recent?`<div style="padding:0 16px 6px"><button class="folder-row" data-book="${recent.id}" style="border:1px solid var(--line);border-radius:14px;background:var(--surface)"><span class="fi">${icon('book')}</span><span class="fb"><b>${esc(recent.title)}</b><small>${Math.round(recent.progress*100)}% · ${esc(recent.author||'')}</small></span></button></div>`:'')+
    `<div class="section-label">All books</div><div class="shelf">${list.map(b=>`<button class="book" data-book="${b.id}">
      <div class="cover">${b.has_cover?`<img src="/book/${b.id}/cover" alt="" loading="lazy">`:`<div class="ph">${esc(b.title)}</div>`}<span class="fmt">${esc((b.lang||b.format).toUpperCase().slice(0,2))}</span></div>
      <b>${esc(b.title)}</b><small>${esc(b.author||'')}</small><div class="bar-p"><i style="width:${Math.round(b.progress*100)}%"></i></div></button>`).join('')}</div>`;
  $('shelf').querySelectorAll('[data-book]').forEach(el=>{
    let timer=null;const id=+el.dataset.book;
    el.addEventListener('touchstart',()=>{timer=setTimeout(()=>{timer=null;bookMenu(id,list.find(b=>b.id===id));},500);},{passive:true});
    el.addEventListener('touchend',()=>{if(timer)clearTimeout(timer);},{passive:true});
    el.addEventListener('touchmove',()=>{if(timer)clearTimeout(timer);timer=null;},{passive:true});
    el.oncontextmenu=(e)=>{e.preventDefault();};
    el.onclick=handle(()=>openBook(id));
  });
}
async function bookMenu(id,b){
  await menuSheet(b.title,[
    {label:'Open',icon:'book',run:()=>openBook(id)},
    {label:'Rename',icon:'edit',run:async()=>{const t=await prompt2('Book title',b.title);if(!t)return;await api('book.rename',{id,title:t});renderShelf();}},
    {label:'Export highlights',icon:'export',run:()=>Kotoba.exportFile(b.title+' — highlights.md','highlights',JSON.stringify({book:id}))},
    '-',
    {label:'Remove from library',icon:'trash',danger:true,run:async()=>{if(!await confirm2('Remove “'+b.title+'”?','Its highlights and bookmarks are removed too.','Remove',true))return;await api('book.delete',{id});renderShelf();}},
  ]);
}

/** Selected text without printed furigana (<rt>/<rp>). */
function plainSelection(sel){
  try{
    if(!sel.rangeCount)return '';
    const frag=sel.getRangeAt(0).cloneContents();
    frag.querySelectorAll&&frag.querySelectorAll('rt,rp').forEach(x=>x.remove());
    return frag.textContent.replace(/\s+/g,' ').trim();
  }catch(e){return sel.toString().trim();}
}

// ---------- positions ----------
function nodePath(root,node){
  const path=[];let n=node;
  while(n&&n!==root){const p=n.parentNode;if(!p)return null;path.unshift([...p.childNodes].indexOf(n));n=p;}
  return n===root?path:null;
}
function resolvePath(root,path){
  let n=root;
  for(const i of path||[]){if(!n||!n.childNodes[i])return null;n=n.childNodes[i];}
  return n;
}
function firstText(node){
  if(!node)return null;
  if(node.nodeType===3)return node;
  const w=node.ownerDocument.createTreeWalker(node,NodeFilter.SHOW_TEXT);
  let t;while((t=w.nextNode()))if(t.nodeValue.trim())return t;
  return null;
}
function rangeFromPoint(doc,x,y){
  if(doc.caretRangeFromPoint)return doc.caretRangeFromPoint(x,y);
  const p=doc.caretPositionFromPoint&&doc.caretPositionFromPoint(x,y);
  if(!p)return null;const r=doc.createRange();r.setStart(p.offsetNode,p.offset);return r;
}

// ---------- the reader ----------
async function openBook(id){
  const book=await api('book.open',{id});
  const meta=book.meta;
  const spine=meta.spine;
  const settings={...readerDefaults(),...book.settings};
  let saved=null;try{saved=book.position?JSON.parse(book.position):null;}catch(e){}
  const sizes=spine.map(s=>Math.max(1,s.size||1));const total=sizes.reduce((a,b)=>a+b,0);
  const before=(i)=>sizes.slice(0,i).reduce((a,b)=>a+b,0);
  const el=document.createElement('div');el.className='reader-page bare';
  el.innerHTML=`<iframe class="book-frame" data-f="frame" title="Book"></iframe>
    <div class="rd-top"><button class="icon-btn" data-a="back">${icon('back')}</button><div class="title"><b>${esc(book.title)}</b><small data-f="chapter"></small></div>
      <button class="icon-btn" data-a="search" aria-label="Search the dictionary">${icon('search')}</button><button class="icon-btn" data-a="toc" aria-label="Contents">${icon('book')}</button><button class="icon-btn" data-a="mark" aria-label="Bookmark">${icon('bookmark')}</button><button class="icon-btn" data-a="aa" aria-label="Display settings">${icon('text')}</button><button class="icon-btn" data-a="hide" aria-label="Hide controls">${icon('down')}</button></div>
    <div class="rd-bottom"><div class="rd-row"><button class="icon-btn" data-a="prevch" aria-label="Previous chapter">${icon('prev')}</button><input type="range" min="0" max="1000" value="0" data-f="slider" aria-label="Position in chapter"><button class="icon-btn" data-a="nextch" aria-label="Next chapter">${icon('next')}</button></div><div class="rd-meta"><span data-f="chname"></span><span data-f="pct"></span></div><button class="rd-hide" data-a="hide2">Hide controls ⌄</button></div>
    <div class="rd-status" data-f="status"></div><button class="rd-chapter-pill" data-f="pill" hidden></button>`;
  let clearHoverWiring=()=>{};
  pushPage(el,{onClose:()=>{clearHoverWiring();savePosition(true);renderShelf();appBars();}});
  const f=(n)=>el.querySelector(`[data-f="${n}"]`);
  const frame=f('frame');
  const state={chapter:0,doc:null,vertical:false,paged:false,highlights:[],loading:false};
  const bookmarkIcon=el.querySelector('[data-a="mark"]');

  el.querySelector('[data-a="back"]').onclick=()=>popPage();
  el.querySelector('[data-a="hide"]').onclick=()=>toggleChrome(false);
  el.querySelector('[data-a="hide2"]').onclick=()=>toggleChrome(false);
  const chromeShown=()=>!el.classList.contains('bare');
  /** Pages advance right-to-left for vertical text and RTL books unless the reader chose otherwise. */
  const rtl=()=>settings.pageDir==='rtl'||(settings.pageDir==='auto'&&(state.vertical||meta.direction==='rtl'));
  function applyDirectionUi(){
    const r=rtl();
    f('slider').style.direction=r?'rtl':'ltr';
    el.querySelector('.rd-row').style.flexDirection=r?'row-reverse':'row';
    el.querySelector('[data-a="prevch"]').innerHTML=icon(r?'next':'prev');
    el.querySelector('[data-a="nextch"]').innerHTML=icon(r?'prev':'next');
  }
  const toggleChrome=(show)=>{el.classList.toggle('bare',show===undefined?!el.classList.contains('bare'):!show);};

  function chapterTitle(i){
    const href=spine[i]&&spine[i].href;
    let t='';for(const x of meta.toc){const h=x.href.split('#')[0];if(h===href){t=x.title;break;}}
    if(!t){for(let k=i;k>=0&&!t;k--)for(const x of meta.toc)if(x.href.split('#')[0]===spine[k].href){t=x.title;break;}}
    return t||`Chapter ${i+1}`;
  }
  /** Page width for vertical paged mode: the widest multiple of the line pitch that fits. */
  function vStep(){
    const pitch=settings.fontSize*settings.lineHeight;
    const usable=frame.clientWidth-2*settings.margin;
    return Math.max(pitch,Math.floor(usable/pitch)*pitch);
  }
  /** Right/left edges of the text columns currently near the viewport (vertical writing). */
  function columns(){
    const doc=state.doc;if(!doc)return [];
    const W=frame.clientWidth;const cols=[];
    const w=doc.createTreeWalker(doc.body,NodeFilter.SHOW_TEXT);let t;
    const r=doc.createRange();
    while((t=w.nextNode())){
      const pe=t.parentElement;if(!pe||pe.closest('rt,rp')||!t.nodeValue.trim())continue;
      const box=pe.getBoundingClientRect();if(box.right<-W||box.left>2*W)continue;
      r.selectNodeContents(t);
      for(const q of r.getClientRects()){if(q.width<1||q.right<-W||q.left>2*W)continue;cols.push({left:q.left,right:q.right});}
    }
    // Merge rects that belong to the same column.
    cols.sort((a,b)=>b.right-a.right);
    const merged=[];
    for(const c of cols){const m=merged.find(x=>c.right<=x.right+2&&c.right>=x.left-1);if(m){m.left=Math.min(m.left,c.left);m.right=Math.max(m.right,c.right);}else merged.push({...c});}
    return merged.sort((a,b)=>b.right-a.right);
  }
  function updateMask(){
    let mask=el.querySelector('.vpage-mask'),maskR=el.querySelector('.vpage-mask-r');
    if(!(state.paged&&state.vertical)){if(mask)mask.remove();if(maskR)maskR.remove();return;}
    if(!mask){mask=document.createElement('div');mask.className='vpage-mask';el.insertBefore(mask,f('status'));}
    if(!maskR){maskR=document.createElement('div');maskR.className='vpage-mask-r';el.insertBefore(maskR,f('status'));}
    const th=RTHEMES[settings.rtheme]||RTHEMES.light;
    const W=frame.clientWidth,m=settings.margin,cols=columns();
    // Left: cover any column that doesn't fit completely on this page.
    const cut=cols.filter(c=>c.left<m-1&&c.right>0);
    const width=cut.length?Math.max(...cut.map(c=>c.right))+2:0;
    // Right: cover the previous page's last column showing in the margin, but keep this page's furigana.
    const atStart=Math.abs(scroller().scrollLeft)<2;
    const first=cols.find(c=>c.right<=W-m+settings.fontSize*0.5);
    const rightEdge=atStart||!first?W:Math.min(W,first.right+settings.fontSize*0.62);
    const css=`position:absolute;top:0;bottom:0;background:${th.bg};pointer-events:none;z-index:1;`;
    mask.style.cssText=css+`left:0;width:${Math.max(0,Math.min(W,width))}px`;
    maskR.style.cssText=css+`right:0;width:${Math.max(0,W-rightEdge)}px`;
  }
  function scroller(){return state.doc&&(state.doc.scrollingElement||state.doc.documentElement);}
  function fraction(){
    const s=scroller();if(!s)return 0;
    if(state.paged){
      if(state.vertical){const max=s.scrollWidth-s.clientWidth;return max>0?Math.min(1,Math.abs(s.scrollLeft)/max):0;}
      return s.scrollWidth>s.clientWidth?s.scrollLeft/(s.scrollWidth-s.clientWidth):0;
    }
    if(state.vertical){const max=s.scrollWidth-s.clientWidth;return max>0?Math.min(1,Math.abs(s.scrollLeft)/max):0;}
    const max=s.scrollHeight-s.clientHeight;return max>0?s.scrollTop/max:0;
  }
  function setFraction(x){
    const s=scroller();if(!s)return;
    if(state.paged){
      if(state.vertical){const step=vStep();s.scrollLeft=-Math.round((s.scrollWidth-s.clientWidth)*x/step)*step;}
      else{const page=frame.clientWidth;s.scrollLeft=Math.round((s.scrollWidth-s.clientWidth)*x/page)*page;}
      return;
    }
    if(state.vertical)s.scrollLeft=-(s.scrollWidth-s.clientWidth)*x;else s.scrollTop=(s.scrollHeight-s.clientHeight)*x;
  }
  function updateStatus(){
    const fr=fraction();
    const pct=(before(state.chapter)+fr*sizes[state.chapter])/total;
    const sc=scroller();
    let pageInfo='';
    if(sc){const page=state.paged?(state.vertical?vStep():frame.clientWidth):(state.vertical?frame.clientWidth:frame.clientHeight);
      const len=state.vertical?sc.scrollWidth-(state.paged?frame.clientWidth-vStep():0):state.paged?sc.scrollWidth:sc.scrollHeight;
      const pages=Math.max(1,Math.round(len/page));pageInfo=`${Math.min(pages,Math.floor(fr*(pages-1)+0.5)+1)}/${pages} · `;}
    f('status').textContent=pageInfo+Math.round(pct*100)+'%';
    f('pct').textContent=`${Math.round(pct*100)}% of book`;
    f('slider').value=Math.round(fr*1000);
    const s=scroller();
    const atEnd=fr>0.995||(!state.vertical&&!state.paged&&s&&s.scrollHeight<=s.clientHeight+2);
    const pill=f('pill');
    pill.hidden=!(atEnd&&!state.paged&&state.chapter<spine.length-1);
    pill.textContent=`Next: ${chapterTitle(state.chapter+1)} ›`;
    return pct;
  }
  /** Where the reader is: first visible text position, as a path from <body>. */
  function currentAnchor(){
    const doc=state.doc;if(!doc)return null;
    const m=settings.margin;
    const pts=state.vertical?[[frame.clientWidth-m-6,m+8],[frame.clientWidth-m-30,m+30]]:[[m+4,m+10],[m+20,m+40]];
    for(const [x,y] of pts){
      const r=rangeFromPoint(doc,x,y);
      if(r&&r.startContainer&&doc.body.contains(r.startContainer)){
        const node=r.startContainer.nodeType===3?r.startContainer:firstText(r.startContainer);
        const path=node&&nodePath(doc.body,node);
        if(path)return {path,offset:node===r.startContainer?r.startOffset:0};
      }
    }
    return null;
  }
  let saveTimer=null;
  function savePosition(now){
    clearTimeout(saveTimer);
    const run=()=>{
      if(!state.doc)return;
      const pct=updateStatus();
      const pos={chapter:state.chapter,anchor:currentAnchor(),fraction:fraction()};
      api('book.position',{id,position:JSON.stringify(pos),progress:pct}).catch(()=>{});
    };
    if(now)run();else saveTimer=setTimeout(run,700);
  }
  function scrollToRange(range){
    const s=scroller();const rect=range.getBoundingClientRect();
    if(!rect||(!rect.width&&!rect.height&&!rect.top&&!rect.left))return;
    const m=settings.margin;
    if(state.paged){
      if(state.vertical){const step=vStep();const off=(frame.clientWidth-m)-rect.right+Math.abs(s.scrollLeft);s.scrollLeft=-Math.max(0,Math.floor(off/step))*step;}
      else{const page=frame.clientWidth;s.scrollLeft=Math.floor((s.scrollLeft+rect.left)/page)*page;}
    }else if(state.vertical){s.scrollLeft+=rect.right-(frame.clientWidth-m);}
    else s.scrollTop+=rect.top-m;
  }
  function restoreAnchor(anchor){
    if(!anchor||!state.doc)return false;
    const node=resolvePath(state.doc.body,anchor.path);
    if(!node)return false;
    const r=state.doc.createRange();
    try{r.setStart(node,Math.min(anchor.offset||0,node.nodeType===3?node.length:0));r.collapse(true);}catch(e){return false;}
    scrollToRange(r);return true;
  }

  function readerCss(){
    const th=RTHEMES[settings.rtheme]||RTHEMES.light;
    const m=settings.margin,fs=settings.fontSize,lh=settings.lineHeight;
    const font=settings.font==='serif'?'"Noto Serif CJK JP","Noto Serif CJK KR","Noto Serif Thai","Noto Serif",serif':settings.font==='sans'?'"Noto Sans CJK JP","Noto Sans CJK KR","Noto Sans Thai","Roboto",sans-serif':null;
    let css=`html,body{-webkit-user-select:none;user-select:none}
body *{-webkit-user-select:text;user-select:text}
img{-webkit-user-select:none;user-select:none}
.kotoba-pagebreak{pointer-events:none;-webkit-user-select:none;user-select:none;border-top:1px dashed rgba(128,128,120,.45);border-right:1px dashed rgba(128,128,120,.45)}
.kotoba-pagebreak::after{content:attr(data-page);position:absolute;font:11px sans-serif;color:rgba(128,128,120,.8);right:4px;top:2px}
html{font-size:${fs}px!important;background:${th.bg}!important;-webkit-text-size-adjust:100%}
body{color:${th.fg}!important;background:${th.bg}!important;line-height:${lh}!important;margin:0!important;box-sizing:border-box!important}
p,div,li,span,dd,blockquote{line-height:inherit!important}
a{color:inherit}
${font?`body,p,div,span,h1,h2,h3,h4,li{font-family:${font}!important}`:''}
${settings.rtheme==='dark'||settings.rtheme==='black'?`body *{color:inherit!important;background-color:transparent!important;border-color:rgba(255,255,255,.2)!important}img{opacity:.9}`:''}`;
    if(state.forceWriting==='horizontal')css+=`html,body,body *{writing-mode:horizontal-tb!important;-webkit-writing-mode:horizontal-tb!important}`;
    if(state.forceWriting==='vertical')css+=`html,body{writing-mode:vertical-rl!important;-webkit-writing-mode:vertical-rl!important}`;
    if(state.vertical)css+=`html{writing-mode:vertical-rl!important;-webkit-writing-mode:vertical-rl!important}`;
    if(state.paged&&!state.vertical){
      css+=`html{height:100%!important;overflow:hidden!important}
body{height:100vh!important;padding:${m+6}px ${m}px!important;column-width:calc(100vw - ${2*m}px)!important;column-gap:${2*m}px!important;column-fill:auto!important;overflow:visible!important;width:auto!important;max-width:none!important}
img,svg{max-height:calc(100vh - ${2*m+12}px)!important;object-fit:contain}`;
    }else if(state.paged&&state.vertical){
      // Vertical text pages move horizontally (right to left); each page is a whole number of text columns.
      css+=`html{overflow:hidden!important;height:100%!important}
body{height:100vh!important;padding:${m+10}px ${m}px ${m+26}px!important;max-height:none!important}
img,svg{max-height:calc(100vh - ${2*m+36}px)!important;max-width:${vStep()}px!important;object-fit:contain}`;
    }else if(state.vertical){
      css+=`body{height:100vh!important;padding:${m+10}px ${m}px ${m+26}px!important;max-height:none!important}
img,svg{max-height:calc(100vh - ${2*m}px)!important}`;
    }else{
      css+=`body{padding:${m+30}px ${m}px ${m+70}px!important;max-width:none!important}`;
    }
    return css;
  }
  function applyStyle(){
    const doc=state.doc;if(!doc)return;
    let st=doc.getElementById('kotoba-reader');
    if(!st){st=doc.createElement('style');st.id='kotoba-reader';(doc.head||doc.documentElement).appendChild(st);}
    // Decide the writing direction from the book itself unless the reader overrides it.
    state.forceWriting=settings.writing==='auto'?'':settings.writing;
    st.textContent=readerCss();
    if(settings.writing==='auto'){
      const wm=getComputedStyle(doc.body).writingMode||'';
      state.vertical=/^vertical/.test(wm)||/^vertical/.test(getComputedStyle(doc.documentElement).writingMode||'')||(meta.writing==='vertical'&&false);
    }else state.vertical=settings.writing==='vertical';
    st.textContent=readerCss();
    el.dataset.rtheme=settings.rtheme;
    const th=RTHEMES[settings.rtheme]||RTHEMES.light;
    setBars(th.bg,settings.rtheme==='light'||settings.rtheme==='sepia');
  }

  async function loadChapter(i,where){
    i=Math.max(0,Math.min(spine.length-1,i));
    const token=state.loadToken=(state.loadToken||0)+1;
    state.loading=true;frame.classList.remove('ready');state.target=null;
    state.chapter=i;state.paged=settings.mode==='paged';
    await new Promise(res=>{frame.onload=res;frame.src=`/book/${id}/${spine[i].href.split('/').map(encodeURIComponent).join('/')}`;});
    if(token!==state.loadToken)return;// a newer chapter load has started
    const doc=frame.contentDocument;
    if(!doc||!doc.body){state.loading=false;toast('Couldn’t open this chapter');return;}
    state.doc=doc;
    applyStyle();
    wireDoc(doc);
    await applyHighlights();
    if(token!==state.loadToken)return;
    // Let layout settle (fonts, images) before positioning.
    await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
    if(where&&where.anchor&&restoreAnchor(where.anchor)){}
    else if(where&&where.fragment){const t=doc.getElementById(where.fragment);if(t){const r=doc.createRange();r.selectNodeContents(t);scrollToRange(r);}}
    else if(where&&typeof where.fraction==='number')setFraction(where.fraction);
    else if(where&&where.end)setFraction(1);
    else setFraction(0);
    drawPageBreaks();applyDirectionUi();updateMask();
    f('chapter').textContent=chapterTitle(i);f('chname').textContent=`${chapterTitle(i)} · ${i+1}/${spine.length}`;
    frame.classList.add('ready');state.loading=false;
    updateStatus();updateBookmarkIcon();
    savePosition(true);
  }

  /** Optional separators every screenful in scroll mode, like page edges in a printed book. */
  function drawPageBreaks(){
    const doc=state.doc;if(!doc)return;
    doc.querySelectorAll('.kotoba-pagebreak').forEach(x=>x.remove());
    if(state.paged||!settings.pageBreaks)return;
    const s=scroller();
    const page=state.vertical?frame.clientWidth:frame.clientHeight;
    const length=state.vertical?s.scrollWidth:s.scrollHeight;
    const pages=Math.ceil(length/page);
    doc.body.style.position='relative';
    for(let k=1;k<pages&&k<2000;k++){
      const d=doc.createElement('div');d.className='kotoba-pagebreak';d.setAttribute('aria-hidden','true');d.dataset.page=k+1;
      if(state.vertical){d.style.cssText=`position:absolute;top:0;bottom:0;width:0;right:${k*page}px;`;}
      else d.style.cssText=`position:absolute;left:0;right:0;height:0;top:${k*page-(doc.body.getBoundingClientRect().top+s.scrollTop)}px;`;
      doc.body.appendChild(d);
    }
  }
  // ---------- paging ----------
  function turn(dir){
    // dir: +1 forward in reading order, -1 back.
    const s=scroller();if(!s||state.loading)return;
    if(state.paged){
      if(state.vertical){
        const max=s.scrollWidth-s.clientWidth;const W=frame.clientWidth,m=settings.margin;
        const cur=Math.abs(s.scrollLeft);
        if(dir>0&&cur>=max-2)return nextChapter();
        if(dir<0&&cur<=2)return prevChapter(true);
        const cols=columns();let delta;
        if(dir>0){
          // Next page starts with the first column that didn't fully fit.
          const next=cols.find(c=>c.left<m-1);
          delta=next?(W-m)-next.right:W-2*m;
        }else{
          // Previous page: the columns just right of the current first column, as many as fit.
          const first=cols.find(c=>c.right<=W-m+settings.fontSize*0.5);
          const target=first?first.right:W-m;
          const before=cols.filter(c=>c.left>=target-1).sort((a,b)=>a.left-b.left);
          let span=0,edge=target;
          for(const c of before){if(c.right-target>W-2*m)break;edge=c.right;}
          delta=-(edge-target);
          if(!delta)delta=-(W-2*m);
        }
        const next=Math.max(0,Math.min(max,cur+delta));
        flip(dir);s.scrollTo({left:-next,behavior:'instant'});
        requestAnimationFrame(updateMask);
        clearTimeout(state.targetTimer);state.targetTimer=setTimeout(()=>{state.target=null;},600);
      }else{
        const page=frame.clientWidth;const max=s.scrollWidth-s.clientWidth;
        const cur=state.target!=null?state.target:s.scrollLeft;
        if(dir>0&&cur>=max-2)return nextChapter();
        if(dir<0&&cur<=2)return prevChapter(true);
        state.target=Math.max(0,Math.min(max,Math.round(cur/page+dir)*page));
        flip(dir);s.scrollTo({left:state.target,behavior:settings.pageAnim?'smooth':'instant'});
        clearTimeout(state.targetTimer);state.targetTimer=setTimeout(()=>{state.target=null;},600);
      }
    }else if(state.vertical){
      const max=s.scrollWidth-s.clientWidth;
      if(dir>0&&Math.abs(s.scrollLeft)>=max-2)return nextChapter();
      if(dir<0&&Math.abs(s.scrollLeft)<=2)return prevChapter(true);
      s.scrollBy({left:-dir*(frame.clientWidth-settings.margin*2-20),behavior:'smooth'});
    }else{
      const max=s.scrollHeight-s.clientHeight;
      if(dir>0&&s.scrollTop>=max-2)return nextChapter();
      if(dir<0&&s.scrollTop<=2)return prevChapter(true);
      s.scrollBy({top:dir*(frame.clientHeight-80),behavior:'smooth'});
    }
    savePosition();
  }
  /** A small page-turn cue: the page tilts toward the turning edge while it slides. */
  function flip(dir){
    if(!settings.pageAnim)return;
    const toLeft=(dir>0)!==rtl();
    frame.animate([{transform:'perspective(1400px) rotateY(0deg)',transformOrigin:toLeft?'left center':'right center'},
      {transform:`perspective(1400px) rotateY(${toLeft?7:-7}deg)`,transformOrigin:toLeft?'left center':'right center',offset:.45},
      {transform:'perspective(1400px) rotateY(0deg)',transformOrigin:toLeft?'left center':'right center'}],{duration:320,easing:'ease-out'});
  }
  function nextChapter(){if(state.chapter<spine.length-1)return loadChapter(state.chapter+1,{fraction:0});toast('End of book');}
  function prevChapter(toEnd){if(state.chapter>0)return loadChapter(state.chapter-1,toEnd?{end:true}:{fraction:0});}
  el.querySelector('[data-a="nextch"]').onclick=handle(()=>nextChapter());
  el.querySelector('[data-a="prevch"]').onclick=handle(()=>prevChapter(false));
  f('pill').onclick=handle(()=>nextChapter());
  f('slider').oninput=()=>{setFraction(f('slider').value/1000);updateStatus();};
  f('slider').onchange=()=>savePosition(true);

  // ---------- interaction inside the page ----------
  function wireDoc(doc){
    clearHoverWiring();
    if(window.KotobaHover){
      let point=null,target=null,timer=0;
      const reset=(forgetPoint=false)=>{clearTimeout(timer);target=null;if(forgetPoint)point=null;};
      const hover=(x,y,active)=>{
        point={x,y};
        if(!active||state.doc!==doc||state.loading||document.querySelector('.sheet.show')){reset();return;}
        const range=rangeFromPoint(doc,x,y);
        if(!range||range.startContainer.nodeType!==3){reset();return;}
        const node=range.startContainer,offset=range.startOffset;
        if(target&&target.node===node&&target.offset===offset)return;
        target={node,offset};clearTimeout(timer);
        timer=setTimeout(()=>{
          if(state.doc===doc&&!state.loading&&!document.querySelector('.sheet.show'))tapLookup(doc,x,y);
        },180);
      };
      const move=e=>hover(e.clientX,e.clientY,KotobaHover.matches(e));
      const down=e=>{if(KotobaHover.isKey(e)&&point)hover(point.x,point.y,true);};
      const up=e=>{if(KotobaHover.isKey(e))reset();};
      const leave=()=>reset(true);
      doc.addEventListener('mousemove',move);
      doc.addEventListener('keydown',down);document.addEventListener('keydown',down);
      doc.addEventListener('keyup',up);document.addEventListener('keyup',up);
      doc.addEventListener('mouseleave',leave);window.addEventListener('blur',leave);
      clearHoverWiring=()=>{
        leave();doc.removeEventListener('mousemove',move);
        doc.removeEventListener('keydown',down);document.removeEventListener('keydown',down);
        doc.removeEventListener('keyup',up);document.removeEventListener('keyup',up);
        doc.removeEventListener('mouseleave',leave);window.removeEventListener('blur',leave);
      };
    }
    const s=scroller();
    doc.addEventListener('scroll',()=>{updateStatus();savePosition();if(state.paged&&state.vertical)updateMask();},{passive:true});
    doc.addEventListener('selectionchange',()=>{
      const sel=doc.getSelection();const text=sel?plainSelection(sel):'';
      if(!text){if(selection.doc===doc)hideSelbar();return;}
      selection={text,doc,opts:{reader:true,source:()=>({key:'',dictName:'',info:null})},context:sentenceAround(sel),range:sel.getRangeAt(0).cloneRange()};
      showSelbar();
    });
    let touch=null;
    doc.addEventListener('touchstart',e=>{const t=e.touches[0];touch={x:t.clientX,y:t.clientY,time:Date.now(),frac:fraction()};},{passive:true});
    doc.addEventListener('touchend',e=>{
      if(!touch)return;
      const t=e.changedTouches[0];const dx=t.clientX-touch.x,dy=t.clientY-touch.y,dt=Date.now()-touch.time;
      const horizontal=Math.abs(dx)>50&&Math.abs(dx)>Math.abs(dy)*1.5&&dt<700;
      if(horizontal&&(state.paged||!state.vertical)&&!doc.getSelection().toString()){
        // Swipe toward the "previous" side turns forward: left swipe in LTR, right swipe in RTL.
        turn((dx<0)!==rtl()?1:-1);touch.swiped=true;return;
      }
      // Infinite scroll: pulling past the end of a chapter continues into the next one.
      if(!state.paged&&dt<1200){
        const pull=state.vertical?dx:-dy;
        if(touch.frac>=0.995&&fraction()>=0.995&&pull>90)nextChapter();
        else if(touch.frac<=0.005&&fraction()<=0.005&&pull<-90)prevChapter(true);
      }
    },{passive:true});
    doc.addEventListener('click',e=>{
      if(touch&&touch.swiped){touch.swiped=false;return;}
      const a=e.target.closest('a[href]');
      if(a){e.preventDefault();followLink(a.getAttribute('href'));return;}
      const mark=e.target.closest('mark.kotoba-hl');
      if(mark){highlightMenu(+mark.dataset.id);return;}
      if(doc.getSelection().toString().trim())return;
      const x=e.clientX,y=e.clientY,w=frame.clientWidth;
      // With the controls open, a tap on the page just closes them.
      if(chromeShown()){toggleChrome(false);return;}
      if(state.paged&&(x<w*0.22||x>w*0.78)){
        const forward=rtl()?x<w*0.22:x>w*0.78;
        turn(forward?1:-1);return;
      }
      // A tap on a word opens the dictionary right away.
      if((window.KotobaHover?KotobaHover.clickLookup():settings.tapLookup)&&tapLookup(doc,x,y))return;
      toggleChrome(true);
    });
  }
  function followLink(href){
    if(/^https?:/i.test(href)){toast('Web links are disabled in offline mode');return;}
    const base=spine[state.chapter].href;
    const dir=base.includes('/')?base.slice(0,base.lastIndexOf('/')+1):'';
    const [path,frag]=href.split('#');
    const target=path?resolveHref(dir,path):base;
    const idx=spine.findIndex(s=>s.href===target);
    if(idx<0){toast('Link target isn’t in this book');return;}
    if(idx===state.chapter&&frag){const t=state.doc.getElementById(frag);if(t){const r=state.doc.createRange();r.selectNodeContents(t);scrollToRange(r);t.classList.add('kotoba-flash');savePosition();}return;}
    loadChapter(idx,frag?{fragment:frag}:{fraction:0});
  }
  function resolveHref(dir,href){
    const parts=(dir+decodeURIComponent(href)).split('/');const out=[];
    for(const p of parts){if(!p||p==='.')continue;if(p==='..')out.pop();else out.push(p);}
    return out.join('/');
  }

  /** Tap on a word: look up the longest dictionary match starting there. */
  function tapLookup(doc,x,y){
    const r=rangeFromPoint(doc,x,y);
    if(!r||r.startContainer.nodeType!==3)return false;
    let node=r.startContainer;let off=r.startOffset;
    const inRuby=(n)=>n.parentElement&&n.parentElement.closest('rt,rp');
    // A tap on printed furigana looks up the word it annotates.
    if(inRuby(node)){const ruby=node.parentElement.closest('ruby');const base=ruby&&firstText(ruby);if(!base||inRuby(base))return false;node=base;off=0;}
    const text=node.nodeValue;
    // caretRangeFromPoint gives the gap nearest the tap; use the character under the finger.
    if(off>=text.length)off=text.length-1;
    if(off<0)return false;
    // Only a tap that lands on a character counts; taps on margins or blank lines fall through.
    const hit=(o)=>{if(o<0||o>=text.length)return false;const pr=doc.createRange();pr.setStart(node,o);pr.setEnd(node,o+1);
      for(const r of pr.getClientRects())if(x>=r.left-2&&x<=r.right+2&&y>=r.top-2&&y<=r.bottom+2)return true;return false;};
    if(!hit(off)){if(hit(off-1))off--;else return false;}
    const ch=text[off];
    if(!ch||!/[\p{L}\p{N}]/u.test(ch))return false;
    // Space-separated scripts (Korean, Russian, Latin) start at the word; Japanese/Chinese/Thai at the tapped character.
    if(/[가-힣Ѐ-ӿA-Za-z]/.test(ch)){while(off>0&&/[\p{L}\p{N}]/u.test(text[off-1]))off--;}
    // Collect up to 30 characters across following text nodes.
    let chunk=text.slice(off);const walker=doc.createTreeWalker(doc.body,NodeFilter.SHOW_TEXT);walker.currentNode=node;
    let t;while(chunk.length<30&&(t=walker.nextNode()))if(!inRuby(t))chunk+=t.nodeValue;
    chunk=chunk.slice(0,30);
    handle(async()=>{
      const res=await api('lookup',{text:chunk});
      if(!res.items.length){toast('No dictionary entry here',1500);return;}
      // Mark the matched characters.
      try{
        const len=res.matched.length;const range=doc.createRange();range.setStart(node,off);
        let remaining=len,cur=node,start=off;
        while(cur&&remaining>0){const avail=cur.nodeValue.length-start;if(avail>=remaining){range.setEnd(cur,start+remaining);remaining=0;}else{remaining-=avail;cur=walker.nextNode&&null;break;}}
        if(remaining>0)range.setEnd(node,node.nodeValue.length);
        const W=frame.contentWindow;
        if(W.CSS&&W.CSS.highlights&&W.Highlight){W.CSS.highlights.set('kotoba-tap',new W.Highlight(range));}
        const sel={text:res.matched,doc,opts:{reader:true},context:contextFor(range)};
        await lookupSheet(res.matched,sel,{onClose:()=>{try{W.CSS.highlights.delete('kotoba-tap');}catch(e){}},book:book.title});
      }catch(e){console.error(e);await lookupSheet(res.matched,{context:''});}
    })();
    return true;
  }
  function contextFor(range){
    try{const sel=range.startContainer.ownerDocument.getSelection();const saved=sel.rangeCount?sel.getRangeAt(0):null;sel.removeAllRanges();sel.addRange(range);const c=sentenceAround(sel);sel.removeAllRanges();if(saved)sel.addRange(saved);return c;}catch(e){return '';}
  }

  // ---------- highlights ----------
  function rangeToPoints(range){
    const doc=state.doc;
    const sn=range.startContainer.nodeType===3?range.startContainer:firstText(range.startContainer);
    const en=range.endContainer.nodeType===3?range.endContainer:firstText(range.endContainer);
    return {start:{path:nodePath(doc.body,sn),offset:sn===range.startContainer?range.startOffset:0},end:{path:nodePath(doc.body,en),offset:en===range.endContainer?range.endOffset:0}};
  }
  function wrapRange(range,cls,idv){
    const doc=state.doc;
    const nodes=[];const w=doc.createTreeWalker(range.commonAncestorContainer.nodeType===3?range.commonAncestorContainer.parentNode:range.commonAncestorContainer,NodeFilter.SHOW_TEXT);
    let t;while((t=w.nextNode()))if(range.intersectsNode(t)&&t.nodeValue.length)nodes.push(t);
    for(const n of nodes){
      let s=0,e=n.nodeValue.length;
      if(n===range.startContainer)s=range.startOffset;
      if(n===range.endContainer)e=range.endOffset;
      if(e<=s)continue;
      const r=doc.createRange();r.setStart(n,s);r.setEnd(n,e);
      const m=doc.createElement('mark');m.className='kotoba-hl '+cls;m.dataset.id=idv;
      try{r.surroundContents(m);}catch(err){}
    }
  }
  async function applyHighlights(){
    state.highlights=await api('highlights',{book:id}).catch(()=>[]);
    const doc=state.doc;
    // Resolve every highlight in this chapter against the original DOM before wrapping any of them.
    const ranges=[];
    for(const h of state.highlights.filter(h=>h.chapter===state.chapter)){
      try{
        const s=JSON.parse(h.start),e=JSON.parse(h.end);
        const sn=resolvePath(doc.body,s.path),en=resolvePath(doc.body,e.path);
        if(!sn||!en)continue;
        const r=doc.createRange();r.setStart(sn,Math.min(s.offset,sn.length||0));r.setEnd(en,Math.min(e.offset,en.length||0));
        ranges.push([r,h]);
      }catch(err){}
    }
    for(const [r,h] of ranges)wrapRange(r,h.color+(h.note?' note':''),h.id);
  }
  function stripHighlights(){state.doc.querySelectorAll('mark.kotoba-hl').forEach(m=>{const p=m.parentNode;while(m.firstChild)p.insertBefore(m.firstChild,m);p.removeChild(m);p.normalize();});}
  async function addHighlight(range,text){
    // Positions are stored against the unhighlighted DOM so they stay valid.
    const anchor=currentAnchor();
    stripHighlights();
    const pts=rangeToPoints(range);
    if(!pts.start.path||!pts.end.path){toast('Couldn’t highlight this selection');await applyHighlights();return;}
    await api('highlight.save',{book:id,chapter:state.chapter,start:JSON.stringify(pts.start),end:JSON.stringify(pts.end),text:text.slice(0,5000),color:localStorage.getItem('hlColor')||'yellow'});
    try{state.doc.getSelection().removeAllRanges();}catch(e){}
    await applyHighlights();
    restoreAnchor(anchor);
    toast('Highlighted',1200);
  }
  async function highlightMenu(hid){
    const h=state.highlights.find(x=>x.id===hid);if(!h)return;
    const colors={yellow:'#ffd600',green:'#6ec878',blue:'#6eaaf0',pink:'#fa82aa'};
    const s=openSheet(`<div class="sheet-body"><p style="font:16px/1.7 var(--serif);margin:0 0 8px">${esc(h.text)}</p>
      <div class="hl-colors">${Object.entries(colors).map(([k,c])=>`<button data-c="${k}" class="${h.color===k?'on':''}" style="background:${c}"></button>`).join('')}</div>
      <label class="f">Note</label><textarea class="textarea" id="hl-note" rows="3">${esc(h.note)}</textarea></div>
      <div class="sheet-foot"><button class="btn danger" id="hl-del">${icon('trash')}</button><button class="btn wide" id="hl-look">${icon('search')} Look up</button><button class="btn primary wide" id="hl-save">Save</button></div>`,{title:'Highlight'});
    let color=h.color;
    s.sheet.querySelectorAll('[data-c]').forEach(b=>b.onclick=()=>{color=b.dataset.c;localStorage.setItem('hlColor',color);s.sheet.querySelectorAll('[data-c]').forEach(x=>x.classList.toggle('on',x===b));});
    const refresh=async()=>{const a=currentAnchor();stripHighlights();await applyHighlights();restoreAnchor(a);};
    s.sheet.querySelector('#hl-save').onclick=handle(async()=>{await api('highlight.save',{id:h.id,color,note:s.sheet.querySelector('#hl-note').value.trim()});closeSheet(s);await refresh();});
    s.sheet.querySelector('#hl-del').onclick=handle(async()=>{await api('highlight.delete',{id:h.id});closeSheet(s);await refresh();});
    s.sheet.querySelector('#hl-look').onclick=handle(async()=>{closeSheet(s);await lookupSheet(h.text.slice(0,40),{context:h.text});});
  }
  readerHooks.highlight=()=>{
    if(!selection.range||selection.doc!==state.doc)return false;
    handle(()=>addHighlight(selection.range,selection.text))();
    return true;
  };
  readerHooks.active=()=>pageStack.length&&pageStack[pageStack.length-1].el===el;
  readerHooks.bookTitle=book.title;

  // ---------- bookmarks, contents, settings ----------
  async function updateBookmarkIcon(){
    const marks=await api('bookmarks',{book:id}).catch(()=>[]);
    const fr=fraction();
    const here=marks.find(m=>m.chapter===state.chapter&&Math.abs(JSON.parse(m.position).fraction-fr)<0.03);
    bookmarkIcon.classList.toggle('on',!!here);
    bookmarkIcon.dataset.mark=here?here.id:'';
  }
  bookmarkIcon.onclick=handle(async()=>{
    if(bookmarkIcon.dataset.mark){await api('bookmark.delete',{id:+bookmarkIcon.dataset.mark});toast('Bookmark removed',1200);}
    else{
      const a=currentAnchor();let label=chapterTitle(state.chapter);
      if(a){const n=resolvePath(state.doc.body,a.path);if(n&&n.nodeValue)label+=' — '+n.nodeValue.slice(a.offset,a.offset+40).trim();}
      await api('bookmark.save',{book:id,chapter:state.chapter,position:JSON.stringify({chapter:state.chapter,anchor:a,fraction:fraction()}),label,progress:(before(state.chapter)+fraction()*sizes[state.chapter])/total});
      toast('Bookmarked',1200);
    }
    updateBookmarkIcon();
  });
  el.querySelector('[data-a="toc"]').onclick=handle(()=>contentsSheet('toc'));
  el.querySelector('[data-a="search"]').onclick=handle(()=>dictionarySearchSheet('',book.title));
  async function contentsSheet(which){
    const [marks,hls]=await Promise.all([api('bookmarks',{book:id}),api('highlights',{book:id})]);
    const s=openSheet(`<div style="padding:0 16px 8px"><div class="seg"><button data-t="toc">Contents</button><button data-t="marks">Bookmarks · ${marks.length}</button><button data-t="hls">Highlights · ${hls.length}</button></div></div><div class="sheet-body" style="padding:0" data-f="list"></div>`,{title:book.title,tall:true});
    const list=s.sheet.querySelector('[data-f="list"]');
    const render=(t)=>{
      s.sheet.querySelectorAll('[data-t]').forEach(b=>b.classList.toggle('on',b.dataset.t===t));
      if(t==='toc'){
        const toc=meta.toc.length?meta.toc:spine.map((x,i)=>({title:`Chapter ${i+1}`,href:x.href,level:0}));
        list.innerHTML=toc.map((x,i)=>{const idx=spine.findIndex(sp=>sp.href===x.href.split('#')[0]);return `<button class="toc-row ${idx===state.chapter?'cur':''}" data-i="${i}" style="padding-left:${18+x.level*18}px">${esc(x.title||'—')}${idx>=0?`<small>${Math.round(before(idx)/total*100)}%</small>`:''}</button>`;}).join('');
        list.querySelectorAll('[data-i]').forEach(b=>b.onclick=handle(()=>{const x=toc[+b.dataset.i];const [p,frag]=x.href.split('#');const idx=spine.findIndex(sp=>sp.href===p);if(idx<0)return;closeSheet(s);return loadChapter(idx,frag?{fragment:frag}:{fraction:0});}));
        const cur=list.querySelector('.cur');if(cur)cur.scrollIntoView({block:'center'});
      }else if(t==='marks'){
        list.innerHTML=marks.length?marks.map((m,i)=>`<button class="toc-row" data-i="${i}">${esc(m.label)}<small>${Math.round(m.progress*100)}%</small></button>`).join(''):'<div class="empty">No bookmarks yet. Tap 🔖 while reading.</div>';
        list.querySelectorAll('[data-i]').forEach(b=>b.onclick=handle(()=>{const m=marks[+b.dataset.i];closeSheet(s);return loadChapter(m.chapter,JSON.parse(m.position));}));
      }else{
        list.innerHTML=hls.length?hls.map((h,i)=>`<button class="toc-row" data-i="${i}"><span style="border-left:4px solid ${({yellow:'#ffd600',green:'#6ec878',blue:'#6eaaf0',pink:'#fa82aa'})[h.color]||'#ffd600'};padding-left:8px;display:block">${esc(h.text.slice(0,160))}</span>${h.note?`<small>${esc(h.note)}</small>`:''}<small>${esc(chapterTitle(h.chapter))}</small></button>`).join('')+`<div style="padding:14px"><button class="btn small" id="hl-export">${icon('export')} Export highlights</button></div>`:'<div class="empty">No highlights yet. Select text and tap Highlight.</div>';
        list.querySelectorAll('[data-i]').forEach(b=>b.onclick=handle(()=>{const h=hls[+b.dataset.i];closeSheet(s);return loadChapter(h.chapter,{anchor:JSON.parse(h.start)});}));
        const ex=list.querySelector('#hl-export');if(ex)ex.onclick=()=>Kotoba.exportFile(book.title+' — highlights.md','highlights',JSON.stringify({book:id}));
      }
    };
    s.sheet.querySelectorAll('[data-t]').forEach(b=>b.onclick=()=>render(b.dataset.t));
    render(which);
  }
  el.querySelector('[data-a="aa"]').onclick=handle(()=>displaySheet());
  function displaySheet(){
    const row=(label,control)=>`<div class="switch-row"><div><b>${label}</b></div>${control}</div>`;
    const seg=(key,opts)=>`<div class="seg" style="min-width:200px">${opts.map(([v,l])=>`<button data-k="${key}" data-v="${v}" class="${String(settings[key])===String(v)?'on':''}">${l}</button>`).join('')}</div>`;
    const step=(key,label,unit)=>`<div class="stepper"><button data-step="${key}" data-d="-1">−</button><span data-show="${key}">${label}</span><button data-step="${key}" data-d="1">+</button></div>`;
    const s=openSheet(`<div class="sheet-body">
      ${row('Text size',step('fontSize',settings.fontSize+'px'))}
      ${row('Line spacing',step('lineHeight',settings.lineHeight.toFixed(1)))}
      ${row('Margins',step('margin',settings.margin+'px'))}
      ${row('Font',seg('font',[['book','Book'],['serif','Serif'],['sans','Sans']]))}
      ${row('Theme',seg('rtheme',[['light','Light'],['sepia','Sepia'],['dark','Dark'],['black','Black']]))}
      ${row('Writing',seg('writing',[['auto','Auto'],['horizontal','横'],['vertical','縦']]))}
      ${row('Layout',seg('mode',[['scroll','Scroll'],['paged','Pages']]))}
      ${row('Page direction',seg('pageDir',[['auto','Auto'],['ltr','→ LTR'],['rtl','← RTL']]))}
      <div class="switch-row"><div><b>Page turn animation</b><small>Slide and tilt when turning pages</small></div><label class="toggle"><input type="checkbox" id="rd-anim" ${settings.pageAnim?'checked':''}><span></span></label></div>
      <div class="switch-row"><div><b>Page separators</b><small>In scroll mode, mark each screenful like a printed page</small></div><label class="toggle"><input type="checkbox" id="rd-breaks" ${settings.pageBreaks?'checked':''}><span></span></label></div>
      ${window.KotobaHover?'':`<div class="switch-row"><div><b>Tap a word to look it up</b><small>Otherwise tap toggles the menu; you can always select text</small></div><label class="toggle"><input type="checkbox" id="rd-tap" ${settings.tapLookup?'checked':''}><span></span></label></div>`}
      <button class="btn small" id="rd-default" style="margin-top:10px">Use these settings for all books</button>
    </div>`,{title:'Display'});
    const apply=async(relayout)=>{
      const anchor=currentAnchor();
      await api('book.settings',{id,settings});
      if(relayout){await loadChapter(state.chapter,{anchor});}
      else{applyStyle();await new Promise(r=>requestAnimationFrame(r));restoreAnchor(anchor);updateStatus();}
    };
    const limits={fontSize:[12,40,1],lineHeight:[1.2,2.6,0.1],margin:[4,64,4]};
    s.sheet.querySelectorAll('[data-step]').forEach(b=>b.onclick=handle(()=>{
      const k=b.dataset.step;const [lo,hi,st]=limits[k];
      settings[k]=Math.max(lo,Math.min(hi,+(settings[k]+st*+b.dataset.d).toFixed(1)));
      s.sheet.querySelector(`[data-show="${k}"]`).textContent=k==='lineHeight'?settings[k].toFixed(1):settings[k]+'px';
      return apply(false);
    }));
    s.sheet.querySelectorAll('[data-k]').forEach(b=>b.onclick=handle(()=>{
      const k=b.dataset.k;settings[k]=b.dataset.v;
      s.sheet.querySelectorAll(`[data-k="${k}"]`).forEach(x=>x.classList.toggle('on',x===b));
      if(k==='pageDir'){applyDirectionUi();return api('book.settings',{id,settings});}
      return apply(k==='mode'||k==='writing');
    }));
    s.sheet.querySelector('#rd-anim').onchange=handle(e=>{settings.pageAnim=e.target.checked;return api('book.settings',{id,settings});});
    s.sheet.querySelector('#rd-breaks').onchange=handle(async e=>{settings.pageBreaks=e.target.checked;await api('book.settings',{id,settings});drawPageBreaks();});
    const tapSetting=s.sheet.querySelector('#rd-tap');
    if(tapSetting)tapSetting.onchange=handle(e=>{settings.tapLookup=e.target.checked;return api('book.settings',{id,settings});});
    s.sheet.querySelector('#rd-default').onclick=()=>{try{localStorage.setItem('readerDefaults',JSON.stringify(settings));}catch(e){}toast('Saved as default');};
  }

  frame.addEventListener('load',()=>{try{frame.contentWindow.addEventListener('resize',debounce(()=>{drawPageBreaks();},300));}catch(e){}});
  // Handle for debugging from DevTools.
  // Keyboard text size on the Mac (⌘+ / ⌘− / ⌘0), the same setting as Display › Text size, kept at the same place.
  async function fontStep(d){
    const size=d===0?readerDefaults().fontSize:Math.max(12,Math.min(40,settings.fontSize+d));
    if(size===settings.fontSize)return;
    const anchor=currentAnchor();
    settings.fontSize=size;
    applyStyle();await new Promise(r=>requestAnimationFrame(r));restoreAnchor(anchor);updateStatus();
    toast(`Text ${size}px`,900);
    await api('book.settings',{id,settings});
  }
  window.__reader={state,settings,loadChapter,turn,applyStyle,currentAnchor,restoreAnchor,fraction,rtl,fontStep};
  // Open where the reader left off (or at the start).
  const first=saved&&saved.chapter<spine.length?saved.chapter:Math.max(0,spine.findIndex(s=>s.linear!==false));
  await loadChapter(first,saved?{anchor:saved.anchor,fraction:saved.fraction}:{fraction:0});
  toggleChrome(true);setTimeout(()=>{if(!el.classList.contains('bare'))toggleChrome(false);},2200);
}
/** Dictionary search in a sheet, so a book (or comic) stays exactly where it was. */
async function dictionarySearchSheet(initial,source){
  const s=openSheet(`<div style="padding:0 16px 8px"><div class="field"><svg class="i" viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/></svg><input id="ds-q" type="search" placeholder="Look up a word" autocomplete="off" autocapitalize="off" enterkeyhint="search"></div></div><div class="sheet-body" style="padding:0" id="ds-results"></div>`,{title:'Dictionary',tall:true});
  const input=s.sheet.querySelector('#ds-q'),box=s.sheet.querySelector('#ds-results');
  input.value=initial||'';
  const run=debounce(handle(async()=>{
    const q=input.value.trim();if(!q){box.innerHTML='';return;}
    const r=await api('search',{q,mode:'headword',dict:''});
    const forms=(r.forms||[]).map((f,i)=>`<button class="row" data-f="${i}"><div class="line"><div class="hw">${esc(f.base)}</div></div><div class="snip" style="color:var(--accent)">${esc(f.chain)} · ${esc(f.explain)}</div></button>`).join('');
    const groups=groupResults(r.items);
    box.innerHTML=forms+groups.slice(0,40).map((g,i)=>`<button class="row" data-g="${i}"><div class="line"><div class="hw">${esc(g.items[0].key)}</div><div class="meta">${g.items.map(x=>`<span class="tag">${esc(shortName(x.dictionary))}</span>`).join('')}</div></div></button>`).join('')||'<div class="empty">No matches</div>';
    box.querySelectorAll('[data-f]').forEach(b=>b.onclick=handle(()=>{const f=r.forms[+b.dataset.f];closeSheet(s);return openEntry({...f.items[0],key:f.base,alternatives:f.items});}));
    box.querySelectorAll('[data-g]').forEach(b=>b.onclick=handle(()=>{const g=groups[+b.dataset.g];closeSheet(s);return openEntry({...g.items[0],alternatives:g.items});}));
  }),160);
  input.addEventListener('input',run);
  setTimeout(()=>input.focus(),280);
  if(initial)run();
}
const readerHooks={highlight:()=>false,active:()=>false,bookTitle:''};
