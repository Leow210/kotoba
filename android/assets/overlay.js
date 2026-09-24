'use strict';
/*
 * Screen text over games — the Mac's shortcut panel, and the phone's floating 文 button (ScreenText.java): the whole
 * Kotoba interface loads here with ?overlay=1, hidden, and
 * this layer shows the frozen screenshot with a box per line of text. A line opens the same sheet as a comic bubble:
 * tap words to look them up, translate, save a card (with its folder) or a sentence card with a crop of the screen.
 */
(function(){
  if(!/[?&]overlay=1/.test(location.search))return;
  document.documentElement.classList.add('game-overlay');
  // The Mac app listens on a WebKit handler; the phone's overlay window on its Java bridge.
  const post=(m)=>{try{if(window.webkit&&window.webkit.messageHandlers&&window.webkit.messageHandlers.overlay)window.webkit.messageHandlers.overlay.postMessage(m);else if(window.Kotoba&&Kotoba.overlay)Kotoba.overlay(JSON.stringify(m));}catch(e){}};
  // No hover on a phone: a tap looks up the word under the finger (the Mac clicks a line, and hovers with Shift for words).
  const touch=!document.documentElement.classList.contains('desktop');
  if(touch)document.documentElement.classList.add('game-overlay-touch');
  const LANGS=[['ja','日本語'],['ko','한국어'],['zh','中文'],['en','English']];
  const layer=document.createElement('div');layer.id='go-layer';layer.hidden=true;
  layer.innerHTML=`<img id="go-img" alt=""><div id="go-boxes"></div>
    <div id="go-bar"><b id="go-app"></b><span id="go-status"></span>
      <span class="go-langs">${LANGS.map(([k,v])=>`<button data-lang="${k}">${v}</button>`).join('')}</span>
      <button data-a="freeze" title="Show the screenshot instead of the live game">Freeze</button><button data-a="all">All text</button><button data-a="rescan" title="Take a new screenshot">Rescan</button><button data-a="close" title="Close (Esc, or the shortcut again)">✕</button></div>`;
  document.body.appendChild(layer);
  const $$=(s)=>layer.querySelector(s);
  let shot=null,lines=[];

  const status=(t)=>{$$('#go-status').textContent=t;};
  // Live (default): the game keeps moving under the boxes. Frozen: the screenshot covers it, for text that moves.
  let frozen=false;try{frozen=localStorage.getItem('overlay.frozen')==='1';}catch(e){}
  function paintFreeze(){layer.classList.toggle('frozen',frozen);const b=$$('[data-a="freeze"]');b.textContent=frozen?'Live':'Freeze';b.title=frozen?'Show the live game under the boxes':'Show the screenshot instead of the live game';}
  function paintLangs(){layer.querySelectorAll('[data-lang]').forEach(b=>b.classList.toggle('on',shot&&b.dataset.lang===shot.lang));}
  const scale=()=>shot?innerWidth/shot.w:1;

  function render(){
    const box=$$('#go-boxes');box.innerHTML='';
    const k=scale();
    lines.forEach((l,i)=>{
      const d=document.createElement('button');d.className='go-line';d.dataset.i=i;d.title=l.text;
      const pad=3;
      Object.assign(d.style,{left:(l.x*k-pad)+'px',top:(l.y*k-pad)+'px',width:(l.w*k+pad*2)+'px',height:(l.h*k+pad*2)+'px'});
      d.onclick=(e)=>{if(touch){const at=charAt(e.clientX,e.clientY);if(at){hoverAt=at.li+':'+at.ci;hoverLookup(at.li,at.ci);}}else openLine(i);};
      box.appendChild(d);
    });
    status(lines.length?(touch?`${lines.length} line${lines.length===1?'':'s'} · tap a word`:`${lines.length} line${lines.length===1?'':'s'} · click a line, or hold ${window.KotobaHover&&KotobaHover.get()!=='none'?KotobaHover.label():'Shift'} over a word`):'No text found. Try another language or Rescan.');
  }

  /** A crop of the screenshot around a line, for a sentence card's picture (at most 900 px wide). */
  function crop(l){
    return new Promise(res=>{
      const img=$$('#go-img');const pad=Math.max(12,l.h*0.6);
      const sx=Math.max(0,l.x-pad),sy=Math.max(0,l.y-pad),sw=Math.min(shot.w-sx,l.w+pad*2),sh=Math.min(shot.h-sy,l.h+pad*2);
      const f=Math.min(1,900/sw);const c=document.createElement('canvas');c.width=Math.round(sw*f);c.height=Math.round(sh*f);
      c.getContext('2d').drawImage(img,sx,sy,sw,sh,0,0,c.width,c.height);
      res(c.toDataURL('image/jpeg',0.8));
    });
  }
  function openLine(i){
    const l=lines[i];
    layer.querySelectorAll('.go-line.on').forEach(x=>x.classList.remove('on'));
    layer.querySelector(`.go-line[data-i="${i}"]`).classList.add('on');
    closeAllOverlays();
    ocrTextSheet(l.text,{lang:shot.lang==='en'?'':shot.lang,source:shot.app||'Screen',title:shot.app||'Screen text',context:lines.map(x=>x.text).join('\n'),image:()=>crop(l)});
  }
  function allText(){
    if(!lines.length)return;
    closeAllOverlays();
    // Lines in reading order: top to bottom (Vision gives them roughly so), joined for translation or saving.
    ocrTextSheet(lines.map(x=>x.text).join('\n'),{lang:shot.lang==='en'?'':shot.lang,source:shot.app||'Screen',title:(shot.app||'Screen')+' · all text',context:''});
  }

  /** Character boxes when the recognizer gives none (the phone's): the line split evenly, across or down. */
  function withChars(l){
    const n=Array.from(l.text).length;
    if(l.chars&&l.chars.length===n)return l;
    const down=l.h>l.w*1.5,chars=[];
    for(let i=0;i<n;i++)chars.push(down?[l.x,l.y+l.h*i/n,l.w,l.h/n]:[l.x+l.w*i/n,l.y,l.w/n,l.h]);
    return {...l,chars};
  }
  /** Boxes that overlap become one line (a bubble read as two, a name over its dialogue), in reading order. */
  function mergeOverlaps(ls){
    const out=ls.slice();
    const hit=(a,b)=>a.x<b.x+b.w&&b.x<a.x+a.w&&a.y<b.y+b.h&&b.y<a.y+a.h;
    const join=(a,b)=>{
      const ov=Math.min(a.y+a.h,b.y+b.h)-Math.max(a.y,b.y);
      const sameRow=ov>0.5*Math.min(a.h,b.h);
      const [p,q]=sameRow?(a.x<=b.x?[a,b]:[b,a]):(a.y<=b.y?[a,b]:[b,a]);
      const cjk=/[\u3040-\u30ff\u3400-\u9fff]/;
      const space=cjk.test(p.text.slice(-1))&&cjk.test(q.text[0])?'':' ';
      const x=Math.min(a.x,b.x),y=Math.min(a.y,b.y);
      return {x,y,w:Math.max(a.x+a.w,b.x+b.w)-x,h:Math.max(a.y+a.h,b.y+b.h)-y,text:p.text+space+q.text,
        chars:p.chars.concat(space?[[]]:[],q.chars),conf:Math.min(p.conf||1,q.conf||1)};
    };
    for(let changed=true;changed;){
      changed=false;
      for(let i=0;i<out.length&&!changed;i++)for(let j=i+1;j<out.length;j++)if(hit(out[i],out[j])){out[i]=join(out[i],out[j]);out.splice(j,1);changed=true;break;}
    }
    return out.sort((a,b)=>a.y-b.y||a.x-b.x);
  }

  // The popup can be dragged by its top (grab handle or title) and stays where it was left for the next ones.
  let sheetPos=null;try{sheetPos=JSON.parse(localStorage.getItem('overlay.sheetPos')||'null');}catch(e){}
  function placeSheet(sh){
    if(!sheetPos)return;
    // A lookup opened from inside a popup sits a little down and to the side of it, so both stay visible.
    const step=Math.max(0,sheetStack.indexOf(sheetStack.find(e=>e.sheet===sh))||0)*28;
    // Size first (within the screen), then the place slides so the whole popup stays on screen.
    let w=null,h=null;
    if(sheetPos.w){
      w=Math.max(260,Math.min(innerWidth-8,sheetPos.w));h=Math.max(180,Math.min(innerHeight-8,sheetPos.h));
      Object.assign(sh.style,{width:w+'px',height:h+'px',maxHeight:'none'});
    }
    if(sheetPos.x==null)return;
    const bw=w??sh.getBoundingClientRect().width,bh=h??sh.getBoundingClientRect().height;
    const x=Math.max(0,Math.min(innerWidth-bw-4,sheetPos.x-step)),y=Math.max(0,Math.min(innerHeight-bh-4,sheetPos.y+step));
    Object.assign(sh.style,{left:x+'px',top:y+'px',right:'auto',bottom:'auto'});
  }
  const saveSheet=()=>{try{localStorage.setItem('overlay.sheetPos',JSON.stringify(sheetPos));}catch(err){}};
  /** Every edge and corner resizes the popup (a side: width or height only; a corner: both); the size is kept like its place. */
  function resizable(sh){
    for(const dir of ['n','s','e','w','ne','nw','se','sw']){
      const grip=document.createElement('div');grip.className='go-rs go-rs-'+dir;sh.appendChild(grip);
      grip.addEventListener('pointerdown',e=>{
        e.preventDefault();e.stopPropagation();
        const r=sh.getBoundingClientRect(),sx=e.clientX,sy=e.clientY;
        sheetPos={...(sheetPos||{}),x:r.left,y:r.top,w:r.width,h:r.height};placeSheet(sh);
        try{grip.setPointerCapture(e.pointerId);}catch(err){}sh.classList.add('dragging');
        const move=(ev)=>{
          const dx=ev.clientX-sx,dy=ev.clientY-sy;let {left:x,top:y,width:w,height:h}=r;
          if(dir.includes('e'))w=r.width+dx;
          if(dir.includes('s'))h=r.height+dy;
          if(dir.includes('w')){w=Math.max(260,r.width-dx);x=r.right-w;}
          if(dir.includes('n')){h=Math.max(180,r.height-dy);y=r.bottom-h;}
          sheetPos={...sheetPos,x,y,w,h};placeSheet(sh);
        };
        const up=()=>{grip.removeEventListener('pointermove',move);grip.removeEventListener('pointerup',up);sh.classList.remove('dragging');saveSheet();};
        grip.addEventListener('pointermove',move);grip.addEventListener('pointerup',up);
      });
    }
  }
  function draggable(sh){
    placeSheet(sh);resizable(sh);
    const handle=(e)=>e.target.closest('.grab,.sheet-head')&&!e.target.closest('button,input,select,a');
    sh.addEventListener('pointerdown',e=>{
      if(!handle(e))return;
      e.preventDefault();
      const r=sh.getBoundingClientRect(),dx=e.clientX-r.left,dy=e.clientY-r.top;
      try{sh.setPointerCapture(e.pointerId);}catch(err){}sh.classList.add('dragging');
      const move=(ev)=>{sheetPos={...(sheetPos||{}),x:ev.clientX-dx,y:ev.clientY-dy};placeSheet(sh);};
      const up=()=>{sh.removeEventListener('pointermove',move);sh.removeEventListener('pointerup',up);sh.classList.remove('dragging');saveSheet();};
      sh.addEventListener('pointermove',move);sh.addEventListener('pointerup',up);
    });
  }
  new MutationObserver(ms=>ms.forEach(m=>m.addedNodes.forEach(n=>{if(n.classList&&n.classList.contains('sheet'))draggable(n);}))).observe(document.getElementById('sheets'),{childList:true});

  // Hold Shift (or the hover key chosen in Settings) over the picture: the word under the pointer lights up and its
  // entry opens beside it, as in the video player. Characters come with their own boxes from Vision.
  const hl=document.createElement('div');hl.id='go-hl';$$('#go-boxes').after(hl);
  let hoverAt='',hoverTimer=0,lastPoint=null;
  const hoverKey=(e)=>window.KotobaHover?KotobaHover.matches(e):e.shiftKey;
  function charAt(x,y){
    const k=scale();
    for(let li=0;li<lines.length;li++){
      const l=lines[li];const pad=4/k;
      if(x<l.x*k-4||x>(l.x+l.w)*k+4||y<l.y*k-4||y>(l.y+l.h)*k+4)continue;
      const cs=l.chars||[];let best=-1,bd=1e9;
      cs.forEach((c,i)=>{if(!c.length)return;const cx=(c[0]+c[2]/2)*k,d=Math.abs(cx-x);if(d<bd&&x>=c[0]*k-pad*k-2&&x<=(c[0]+c[2])*k+pad*k+2){bd=d;best=i;}});
      if(best<0&&cs.length){const f=(x-l.x*k)/(l.w*k);best=Math.max(0,Math.min(cs.length-1,Math.floor(f*cs.length)));}
      return best<0?null:{li,ci:best};
    }
    return null;
  }
  function paint(li,from,len){
    const l=lines[li],k=scale(),cs=(l.chars||[]).slice(from,from+len).filter(c=>c.length);
    if(!cs.length){hl.hidden=true;return;}
    const x0=Math.min(...cs.map(c=>c[0])),x1=Math.max(...cs.map(c=>c[0]+c[2])),y0=Math.min(...cs.map(c=>c[1])),y1=Math.max(...cs.map(c=>c[1]+c[3]));
    Object.assign(hl.style,{left:(x0*k-2)+'px',top:(y0*k-2)+'px',width:((x1-x0)*k+4)+'px',height:((y1-y0)*k+4)+'px'});hl.hidden=false;
  }
  async function hoverLookup(li,ci){
    const l=lines[li],chars=Array.from(l.text),text=chars.slice(ci).join('');
    if(!text.trim()||/^[\s\p{P}]/u.test(text)){hl.hidden=true;return;}
    const lang=shot.lang==='en'?'':shot.lang;
    const r=await api('lookup',{text:text.slice(0,24),lang}).catch(()=>null);
    if(hoverAt!==li+':'+ci)return;
    if(!r||!r.items.length){hl.hidden=true;return;}
    paint(li,ci,Array.from(r.matched||chars[ci]).length);
    closeAllOverlays();
    lookupSheet(text,{context:l.text},{book:shot.app||'Screen',lang,image:()=>crop(l)});
  }
  function hover(x,y,e){
    if(layer.hidden||!lines.length||!hoverKey(e))return;
    const at=charAt(x,y);if(!at)return;
    const key=at.li+':'+at.ci;if(key===hoverAt)return;
    hoverAt=key;clearTimeout(hoverTimer);
    hoverTimer=setTimeout(()=>hoverLookup(at.li,at.ci),90);
  }
  layer.addEventListener('mousemove',e=>{lastPoint={x:e.clientX,y:e.clientY};hover(e.clientX,e.clientY,e);});
  document.addEventListener('keydown',e=>{if(lastPoint&&!layer.hidden)hover(lastPoint.x,lastPoint.y,e);});

  window.gameOverlay={
    show(d){
      shot=d;lines=[];
      closeAllOverlays();
      $$('#go-img').src=d.image;$$('#go-boxes').innerHTML='';hl.hidden=true;hoverAt='';
      $$('#go-app').textContent=d.app||'Screen';status('Reading text…');paintLangs();
      paintFreeze();layer.hidden=false;
    },
    lines(l){lines=mergeOverlaps(l.filter(x=>x.text&&x.text.trim()).map(withChars));render();},
    // The screenshot only ever lives here, in memory; closing drops it.
    hide(){layer.hidden=true;closeAllOverlays();shot=null;lines=[];$$('#go-img').removeAttribute('src');$$('#go-boxes').innerHTML='';hl.hidden=true;},
  };
  function closeAllOverlays(){while(sheetStack.length)closeSheet(sheetStack[sheetStack.length-1]);while(pageStack.length)popPage(true);hideSelbar();}

  layer.addEventListener('click',e=>{
    const b=e.target.closest('[data-a],[data-lang]');if(!b)return;
    if(b.dataset.lang){if(shot){shot.lang=b.dataset.lang;paintLangs();status('Reading text…');$$('#go-boxes').innerHTML='';post({cmd:'lang',lang:b.dataset.lang});}return;}
    if(b.dataset.a==='close')post({cmd:'close'});
    if(b.dataset.a==='rescan')post({cmd:'rescan'});
    if(b.dataset.a==='all')allText();
    if(b.dataset.a==='freeze'){frozen=!frozen;try{localStorage.setItem('overlay.frozen',frozen?'1':'0');}catch(e){}paintFreeze();}
  });
  // Esc closes the top sheet or page first, then the overlay.
  document.addEventListener('keydown',e=>{
    if(e.key!=='Escape'||layer.hidden)return;
    e.preventDefault();e.stopPropagation();
    if(sheetStack.length)closeSheet();else if(pageStack.length)popPage();else post({cmd:'close'});
  },true);
  // The phone's Back key (sent by the overlay window).
  window.gameOverlayBack=()=>{if(sheetStack.length)closeSheet();else if(pageStack.length)popPage();else post({cmd:'close'});};
  addEventListener('resize',()=>{if(lines.length)render();});
  if(document.readyState==='complete')post({cmd:'ready'});else addEventListener('load',()=>post({cmd:'ready'}));
})();
