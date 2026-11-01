'use strict';
/*
 * Audiobooks: a chapter's audio beside its written script, one row per passage (the way Lyrics shows a song).
 * The current row is lit and followed; tap a word to look it up (the audio waits), tap beside the words to play from
 * that row. A book is a folder in listening/audiobooks/ with a book.json and, per chapter, an audio file and a rows
 * file ([{start, text, who}]); audiobook_viewer.py (TTS Fire) writes them. Books are listed on the Listening screen.
 */
(function(){
  const store={get(k,d){try{const v=localStorage.getItem(k);return v===null?d:JSON.parse(v);}catch(e){return d;}},set(k,v){try{localStorage.setItem(k,JSON.stringify(v));}catch(e){}}};
  const url=(book,p)=>`/listen/audiobooks/${encodeURIComponent(book)}/`+String(p).split('/').map(encodeURIComponent).join('/');
  const fmt=s=>{s=Math.max(0,Math.floor(s));const h=Math.floor(s/3600),m=Math.floor(s/60)%60;return (h?h+':'+String(m).padStart(2,'0'):m)+':'+String(s%60).padStart(2,'0');};
  const opts=Object.assign({speed:1,follow:true,pause:true,who:true,presets:[0.75,1,1.25,1.5,2,2.5,3,4]},store.get('audiobook.opts',{}));
  const MIN_SPEED=0.5,MAX_SPEED=4;
  const clampSpeed=v=>Math.min(MAX_SPEED,Math.max(MIN_SPEED,Math.round(v*100)/100));
  const speedText=v=>(Math.round(v*100)/100)+'×';
  const saveOpts=()=>store.set('audiobook.opts',opts);

  const audiobookList=()=>api('audiobook.list').catch(()=>[]);

  function openBook(book){
    const el=document.createElement('div');el.className='ls-page';
    const pos=store.get('audiobook.pos.'+book.id,{});
    el.innerHTML=`<div class="bar"><button class="icon-btn" data-a="back" aria-label="Back">${icon('back')}</button><div class="title"><b>${esc(book.title)}</b><small>${esc(book.author||'')}</small></div></div>
      <div class="ls-scroll"><div class="ls-lines">${book.chapters.map((c,i)=>`<button class="ls-line" data-i="${i}"><span class="ls-line-title">${i+1}${pos[i]?` · ${fmt(pos[i])} in`:''}</span><span class="ls-line-text">${esc(c.title)}</span></button>`).join('')}</div></div>`;
    pushPage(el);
    el.querySelector('[data-a="back"]').onclick=()=>popPage();
    el.querySelectorAll('[data-i]').forEach(b=>b.onclick=handle(()=>openChapter(book,+b.dataset.i)));
  }

  /** The phone plays through the system player (exact seeking); this gives it the parts of an <audio> element the page uses. */
  function nativeAudio(path){
    const A=window.KotobaAudio,et=new EventTarget();
    if(window.__abAudio&&window.__abAudio.release)window.__abAudio.release();// the old player goes first: releasing it later would take the new one with it
    const ok=A.load(path);let timer=0,rate=1;
    const fire=t=>et.dispatchEvent(new Event(t));
    const stop=()=>{clearInterval(timer);timer=0;};
    const start=()=>{if(timer)return;timer=setInterval(()=>{
      if(window.__abAudio!==o){stop();return;}
      fire('timeupdate');
      if(A.ended()){stop();fire('pause');fire('ended');}
    },200);};
    const o={
      get currentTime(){return A.position();},set currentTime(v){A.seek(v);fire('timeupdate');},
      get duration(){return A.duration();},get paused(){return !A.playing();},
      get playbackRate(){return rate;},set playbackRate(v){rate=v;A.rate(v);},preservesPitch:true,
      error:ok?null:{message:A.error()},
      play(){A.rate(rate);A.play();start();fire('play');return Promise.resolve();},
      pause(){A.pause();fire('pause');},
      release(){stop();A.release();},
      addEventListener:(t,fn,opt)=>et.addEventListener(t,fn,opt),removeEventListener:(t,fn)=>et.removeEventListener(t,fn)
    };
    window.__abAudio=o;
    setTimeout(()=>fire('loadedmetadata'),30);
    return o;
  }

  async function openChapter(book,index){
    const chapter=book.chapters[index];
    const rows=await (await fetch(url(book.id,chapter.rows))).json();
    const posKey='audiobook.pos.'+book.id;
    const el=document.createElement('div');el.className='music-page ab-page';
    const lang=window.textLang?textLang(rows.map(r=>r.text).join('\n'),''):'ja';
    el.innerHTML=`<div class="bar"><button class="icon-btn" data-a="back" aria-label="Back">${icon('back')}</button><div class="title"><b>${esc(chapter.title)}</b><small>${esc(book.title)}</small></div></div>
      <div class="mu-tools ab-tools"><button class="chip small" data-a="prev">◀ Line</button><button class="chip small" data-a="toggle">▶ Play</button><button class="chip small" data-a="next">Line ▶</button>
        <button class="chip small" data-a="again" title="Play this line again">↺</button><span class="ab-speed"><button class="chip small" data-a="slower" title="Slower ( [ )">−</button><input type="range" data-f="spd" min="${MIN_SPEED}" max="${MAX_SPEED}" step="0.05" aria-label="Playback speed"><button class="chip small" data-a="faster" title="Faster ( ] )">+</button><button class="chip small ab-speed-now" data-a="speed" title="Speed presets (double-click: back to 1×)"></button></span>
        <button class="chip small" data-a="pause" title="Pause the audio while a word is looked up"></button><button class="chip small" data-a="follow" title="Keep the current line in view"></button><button class="chip small" data-a="who" title="Show who is speaking"></button></div>
      <div class="ab-presets" data-f="presets" hidden></div>
      <div class="ab-seek"><span data-f="t">0:00</span><input type="range" min="0" max="1000" value="0" data-f="seek" aria-label="Position"><span data-f="d"></span></div>
      ${window.KotobaAudio?'':`<audio preload="metadata" src="${url(book.id,chapter.audio)}"></audio>`}
      <div class="mu-scroll" data-f="scroll"><div class="mu-lines" data-f="lines"></div></div>`;
    const f=n=>el.querySelector(`[data-f="${n}"]`),audio=window.KotobaAudio?nativeAudio(`audiobooks/${book.id}/${chapter.audio}`):el.querySelector('audio');
    f('lines').innerHTML=rows.map((r,i)=>`<div class="mu-line" data-i="${i}">${r.who&&r.who!=='ナレーション'?`<div class="ab-who">${esc(r.who)}</div>`:''}<div class="mu-text" lang="${lang}">${tappableText(r.text)}</div><div class="mu-acts"><button data-l="card">${icon('star')} Sentence</button><button data-l="copy">${icon('copy')}</button></div></div>`).join('');
    pushPage(el);
    let active=-1,userScrolled=0,pausedByUs=false;
    const paint=()=>{
      el.querySelector('[data-a="speed"]').textContent=speedText(opts.speed);
      f('spd').value=opts.speed;
      f('presets').innerHTML=opts.presets.map(p=>`<button class="chip small${p===opts.speed?' on':''}" data-p="${p}">${speedText(p)}</button>`).join('')+`<button class="chip small" data-a="savepreset" title="Add the current speed to these presets, or remove it again">${opts.presets.includes(opts.speed)?'− Remove':'＋ Save'} ${speedText(opts.speed)}</button>`;
      el.querySelector('[data-a="pause"]').textContent=(opts.pause?'☑':'☐')+' Pause on lookup';
      el.querySelector('[data-a="follow"]').textContent=(opts.follow?'☑':'☐')+' Follow';
      el.querySelector('[data-a="who"]').textContent=(opts.who?'☑':'☐')+' Speaker';
      el.classList.toggle('ab-nowho',!opts.who);
    };
    paint();
    const at=t=>{let k=0;for(let i=0;i<rows.length;i++){if(rows[i].start<=t+.05)k=i;else break;}return k;};
    const play=()=>{const r=audio.play();if(r&&r.catch)r.catch(()=>{});};
    const go=i=>{i=Math.max(0,Math.min(rows.length-1,i));audio.currentTime=rows[i].start;userScrolled=0;play();};
    audio.addEventListener('loadedmetadata',()=>{f('d').textContent=fmt(audio.duration);const p=store.get(posKey,{})[index];if(p&&p<audio.duration-5)audio.currentTime=p;audio.playbackRate=opts.speed;audio.preservesPitch=true;});
    audio.addEventListener('play',()=>{audio.playbackRate=opts.speed;});
    let saved=0;
    audio.addEventListener('timeupdate',()=>{
      f('t').textContent=fmt(audio.currentTime);
      if(audio.duration&&document.activeElement!==f('seek'))f('seek').value=Math.round(audio.currentTime/audio.duration*1000);
      el.querySelector('[data-a="toggle"]').textContent=audio.paused?'▶ Play':'⏸ Pause';
      if(Date.now()-saved>3000){saved=Date.now();const p=store.get(posKey,{});p[index]=Math.floor(audio.currentTime);store.set(posKey,p);}
      const i=at(audio.currentTime);if(i===active)return;
      const lines=f('lines').querySelectorAll('.mu-line');active=i;
      lines.forEach((x,k)=>{x.classList.toggle('past',k<i);x.classList.toggle('now',k===i);});
      if(opts.follow&&Date.now()-userScrolled>4000&&!sheetStack.length)lines[i].scrollIntoView({block:'center',behavior:'smooth'});
    });
    const savePos=()=>{if(audio.duration&&audio.currentTime>0){const p=store.get(posKey,{});p[index]=Math.floor(audio.currentTime);store.set(posKey,p);}};
    audio.addEventListener('pause',()=>{el.querySelector('[data-a="toggle"]').textContent='▶ Play';savePos();});
    document.addEventListener('visibilitychange',()=>{if(document.hidden)savePos();});
    audio.addEventListener('ended',()=>{if(index+1<book.chapters.length)toast('Chapter finished');});
    f('seek').oninput=()=>{if(audio.duration)audio.currentTime=f('seek').value/1000*audio.duration;};
    f('scroll').addEventListener('wheel',()=>{userScrolled=Date.now();},{passive:true});
    f('scroll').addEventListener('touchmove',()=>{userScrolled=Date.now();},{passive:true});
    el.querySelector('[data-a="back"]').onclick=()=>{audio.pause();savePos();if(audio.release)audio.release();popPage();};
    el.querySelector('[data-a="toggle"]').onclick=()=>audio.paused?play():audio.pause();
    el.querySelector('[data-a="prev"]').onclick=()=>go(at(audio.currentTime)-1);
    el.querySelector('[data-a="next"]').onclick=()=>go(at(audio.currentTime)+1);
    el.querySelector('[data-a="again"]').onclick=()=>go(at(audio.currentTime));
    const setSpeed=v=>{opts.speed=clampSpeed(v);audio.playbackRate=opts.speed;audio.preservesPitch=true;saveOpts();paint();};
    f('spd').oninput=()=>setSpeed(+f('spd').value);
    el.querySelector('[data-a="slower"]').onclick=()=>setSpeed(opts.speed-0.1);
    el.querySelector('[data-a="faster"]').onclick=()=>setSpeed(opts.speed+0.1);
    const nowBtn=el.querySelector('[data-a="speed"]');
    nowBtn.onclick=()=>{f('presets').hidden=!f('presets').hidden;};
    nowBtn.ondblclick=()=>{f('presets').hidden=true;setSpeed(1);};
    f('presets').addEventListener('click',e=>{
      const p=e.target.closest('[data-p]');if(p)return setSpeed(+p.dataset.p);
      if(e.target.closest('[data-a="savepreset"]')){
        opts.presets=opts.presets.includes(opts.speed)?opts.presets.filter(x=>x!==opts.speed):[...opts.presets,opts.speed].sort((a,b)=>a-b);
        saveOpts();paint();
      }
    });
    el.querySelector('[data-a="pause"]').onclick=()=>{opts.pause=!opts.pause;saveOpts();paint();};
    el.querySelector('[data-a="follow"]').onclick=()=>{opts.follow=!opts.follow;userScrolled=0;saveOpts();paint();};
    el.querySelector('[data-a="who"]').onclick=()=>{opts.who=!opts.who;saveOpts();paint();};

    f('lines').addEventListener('click',async e=>{
      const line=e.target.closest('.mu-line');if(!line)return;
      const r=rows[+line.dataset.i],act=e.target.closest('[data-l]');
      if(act){
        if(act.dataset.l==='card')await saveSentence({text:r.text,note:`📖 ${book.title} · ${chapter.title} · ${fmt(r.start)}`});
        else{Kotoba.copy(r.text);toast('Copied');}
        return;
      }
      const w=e.target.closest('.mu-w');
      f('lines').querySelectorAll('.mu-line.open').forEach(x=>{if(x!==line)x.classList.remove('open');});
      if(!w){go(+line.dataset.i);return;}
      const word=tappableWord(r.text,w);if(!word)return;
      line.classList.add('open');
      line.querySelectorAll('.mu-w.on').forEach(x=>x.classList.remove('on'));
      if(w.dataset.end!=null)line.querySelectorAll('.mu-w').forEach(x=>{if(x.dataset.end===w.dataset.end&&+x.dataset.o>=+w.dataset.o)x.classList.add('on');});else w.classList.add('on');
      if(opts.pause&&!audio.paused){pausedByUs=true;audio.pause();}
      await lookupSheet(word,{context:r.text},{lang,book:`📖 ${book.title} · ${chapter.title}`,onClose:()=>{
        line.querySelectorAll('.mu-w.on').forEach(x=>x.classList.remove('on'));
        if(pausedByUs&&!sheetStack.length){pausedByUs=false;audio.play();}
      }});
    });
    document.addEventListener('keydown',function keys(e){
      if(!el.isConnected){document.removeEventListener('keydown',keys);return;}
      if(/INPUT|TEXTAREA|SELECT/.test(e.target.tagName)||sheetStack.length)return;
      if(e.code==='Space'){e.preventDefault();audio.paused?audio.play():audio.pause();}
      else if(e.key==='ArrowLeft')go(at(audio.currentTime)-1);else if(e.key==='ArrowRight')go(at(audio.currentTime)+1);
      else if(e.key==='['||e.key===']'){setSpeed(opts.speed+(e.key===']'?0.1:-0.1));toast(speedText(opts.speed),900);}
      else if(e.key==='\\'){setSpeed(1);toast('1×',900);}
    });
  }
  window.audiobookList=audiobookList;window.openAudiobook=openBook;

  const css=document.createElement('style');
  css.textContent=`.ab-tools{position:sticky;top:0}.ab-seek{display:flex;gap:10px;align-items:center;padding:6px 16px;font-size:12px;color:var(--muted)}.ab-seek input{flex:1}
  .ab-speed{display:inline-flex;gap:4px;align-items:center}.ab-speed input{width:110px}.ab-speed-now{min-width:48px}
  .ab-presets{display:flex;flex-wrap:wrap;gap:6px;padding:6px 14px;border-bottom:1px solid var(--line)}.ab-presets .chip.on{background:var(--accent);color:var(--accent-ink)}
  .ab-who{font:600 12px var(--sans);color:var(--muted);margin-bottom:1px}.ab-nowho .ab-who{display:none}`;
  document.head.appendChild(css);
})();
