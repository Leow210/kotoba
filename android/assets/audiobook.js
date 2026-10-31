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
  const opts=Object.assign({speed:1,follow:true,pause:true,who:true},store.get('audiobook.opts',{}));
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

  async function openChapter(book,index){
    const chapter=book.chapters[index];
    const rows=await (await fetch(url(book.id,chapter.rows))).json();
    const posKey='audiobook.pos.'+book.id;
    const el=document.createElement('div');el.className='music-page ab-page';
    const lang=window.textLang?textLang(rows.map(r=>r.text).join('\n'),''):'ja';
    el.innerHTML=`<div class="bar"><button class="icon-btn" data-a="back" aria-label="Back">${icon('back')}</button><div class="title"><b>${esc(chapter.title)}</b><small>${esc(book.title)}</small></div></div>
      <div class="mu-tools ab-tools"><button class="chip small" data-a="prev">◀ Line</button><button class="chip small" data-a="toggle">▶ Play</button><button class="chip small" data-a="next">Line ▶</button>
        <button class="chip small" data-a="again" title="Play this line again">↺</button><button class="chip small" data-a="speed"></button>
        <button class="chip small" data-a="pause" title="Pause the audio while a word is looked up"></button><button class="chip small" data-a="follow" title="Keep the current line in view"></button><button class="chip small" data-a="who" title="Show who is speaking"></button></div>
      <div class="ab-seek"><span data-f="t">0:00</span><input type="range" min="0" max="1000" value="0" data-f="seek" aria-label="Position"><span data-f="d"></span></div>
      <audio preload="metadata" src="${url(book.id,chapter.audio)}"></audio>
      <div class="mu-scroll" data-f="scroll"><div class="mu-lines" data-f="lines"></div></div>`;
    const f=n=>el.querySelector(`[data-f="${n}"]`),audio=el.querySelector('audio');
    f('lines').innerHTML=rows.map((r,i)=>`<div class="mu-line" data-i="${i}">${r.who&&r.who!=='ナレーション'?`<div class="ab-who">${esc(r.who)}</div>`:''}<div class="mu-text" lang="${lang}">${tappableText(r.text)}</div><div class="mu-acts"><button data-l="card">${icon('star')} Sentence</button><button data-l="copy">${icon('copy')}</button></div></div>`).join('');
    pushPage(el);
    let active=-1,userScrolled=0,pausedByUs=false;
    const paint=()=>{
      el.querySelector('[data-a="speed"]').textContent=opts.speed+'×';
      el.querySelector('[data-a="pause"]').textContent=(opts.pause?'☑':'☐')+' Pause on lookup';
      el.querySelector('[data-a="follow"]').textContent=(opts.follow?'☑':'☐')+' Follow';
      el.querySelector('[data-a="who"]').textContent=(opts.who?'☑':'☐')+' Speaker';
      el.classList.toggle('ab-nowho',!opts.who);
    };
    paint();
    const at=t=>{let k=0;for(let i=0;i<rows.length;i++){if(rows[i].start<=t+.05)k=i;else break;}return k;};
    const go=i=>{i=Math.max(0,Math.min(rows.length-1,i));audio.currentTime=rows[i].start;audio.play();};
    audio.addEventListener('loadedmetadata',()=>{f('d').textContent=fmt(audio.duration);const p=store.get(posKey,{})[index];if(p&&p<audio.duration-5)audio.currentTime=p;audio.playbackRate=opts.speed;});
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
    audio.addEventListener('pause',()=>{el.querySelector('[data-a="toggle"]').textContent='▶ Play';});
    audio.addEventListener('ended',()=>{if(index+1<book.chapters.length)toast('Chapter finished');});
    f('seek').oninput=()=>{if(audio.duration)audio.currentTime=f('seek').value/1000*audio.duration;};
    f('scroll').addEventListener('wheel',()=>{userScrolled=Date.now();},{passive:true});
    f('scroll').addEventListener('touchmove',()=>{userScrolled=Date.now();},{passive:true});
    el.querySelector('[data-a="back"]').onclick=()=>{audio.pause();popPage();};
    el.querySelector('[data-a="toggle"]').onclick=()=>audio.paused?audio.play():audio.pause();
    el.querySelector('[data-a="prev"]').onclick=()=>go(at(audio.currentTime)-1);
    el.querySelector('[data-a="next"]').onclick=()=>go(at(audio.currentTime)+1);
    el.querySelector('[data-a="again"]').onclick=()=>go(at(audio.currentTime));
    el.querySelector('[data-a="speed"]').onclick=()=>{const s=[.75,.9,1,1.15,1.3];opts.speed=s[(s.indexOf(opts.speed)+1)%s.length];audio.playbackRate=opts.speed;saveOpts();paint();};
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
    });
  }
  window.audiobookList=audiobookList;window.openAudiobook=openBook;

  const css=document.createElement('style');
  css.textContent=`.ab-tools{position:sticky;top:0}.ab-seek{display:flex;gap:10px;align-items:center;padding:6px 16px;font-size:12px;color:var(--muted)}.ab-seek input{flex:1}
  .ab-who{font:600 12px var(--sans);color:var(--muted);margin-bottom:1px}.ab-nowho .ab-who{display:none}`;
  document.head.appendChild(css);
})();
