'use strict';
/*
 * Lyrics for the song that's playing — in Spotify, YouTube Music, NetEase… on the phone (media sessions), in Spotify,
 * Apple Music or YouTube Music (browser helper) on the Mac. The current line follows the song; tap a word to look it
 * up (the music can pause meanwhile), and a line can be translated or kept as a sentence card.
 */
(function(){
  const store={get(k,d){try{const v=localStorage.getItem(k);return v===null?d:JSON.parse(v);}catch(e){return d;}},set(k,v){try{localStorage.setItem(k,JSON.stringify(v));}catch(e){}}};
  const fmt=(s)=>{s=Math.max(0,Math.floor(s||0));return Math.floor(s/60)+':'+String(s%60).padStart(2,'0');};
  let page=null;

  /** Text as tappable characters/words, like a comic bubble: CJK and Thai by character, Korean by syllable, others by word. */
  function tappable(text){
    let html='',o=0;
    for(const part of text.split(/(\s+)/)){
      if(!part)continue;
      if(/^\s+$/.test(part)){html+=' ';o+=part.length;continue;}
      if(/[぀-ヿ㐀-鿿豈-﫿]/.test(part))html+=[...part].map((ch,i)=>`<span class="mu-w" data-o="${o+i}">${esc(ch)}</span>`).join('');
      else if(/[฀-๿]/.test(part))html+=[...part].map((ch,i)=>`<span class="mu-w" data-o="${o+i}" data-end="${o+part.length}">${esc(ch)}</span>`).join('');
      else if(/[가-힣]/.test(part))html+=`<span class="mu-word">`+[...part].map((ch,i)=>`<span class="mu-w" data-o="${o+i}" data-end="${o+part.length}">${esc(ch)}</span>`).join('')+`</span>`;
      else html+=`<span class="mu-w" data-o="${o}" data-t="${esc(part)}">${esc(part)}</span>`;
      o+=part.length;
    }
    return html;
  }
  function wordAt(text,w){
    if(w.dataset.t!=null)return w.dataset.t.replace(/^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$/gu,'');
    if(w.dataset.end!=null)return text.slice(+w.dataset.o,+w.dataset.end).replace(/[^\p{L}\p{N}]+$/gu,'');
    return text.slice(+w.dataset.o).replace(/\s+/g,'');
  }

  async function openMusic(){
    if(page&&page.isConnected){return;}
    const el=document.createElement('div');el.className='music-page';page=el;
    el.innerHTML=`<div class="bar"><button class="icon-btn" data-a="back" aria-label="Back">${icon('back')}</button>
        <div class="title"><b data-f="song">Nothing playing</b><small data-f="meta"></small></div>
        <button class="icon-btn" data-a="find" aria-label="Find lyrics">${icon('search')}</button></div>
      <div class="mu-tools"><button class="chip small" data-a="toggle">⏯</button><button class="chip small" data-a="earlier" title="The lyrics lag behind the song: move them earlier (½ s)">Earlier</button><span data-f="offset" class="mu-offset"></span><button class="chip small" data-a="later" title="The lyrics run ahead of the song: move them later (½ s)">Later</button>
        <button class="chip small" data-a="pause" title="Pause the music while a word is looked up"></button><button class="chip small" data-a="tr" title="Show translations under the lines"></button><button class="chip small" data-a="follow" title="Keep the current line in view"></button></div>
      <div class="mu-scroll" data-f="scroll"><div data-f="lines" class="mu-lines"><p class="hint mu-empty">Play a song in Spotify, YouTube Music, NetEase… and its lyrics appear here.</p></div></div>`;
    pushPage(el,{onClose:()=>{clearInterval(poll);cancelAnimationFrame(raf);page=null;}});
    const f=(n)=>el.querySelector(`[data-f="${n}"]`);
    let now=null,nowAt=0,song='',lyrics=null,active=-1,userScrolled=0,pausedByUs=false,lang='';
    const opts={pause:store.get('music.pauseLookup',true),tr:store.get('music.showTr',true),follow:true};
    const offsetKey=()=>'music.offset.'+song;
    let offset=0;
    function paintTools(){
      el.querySelector('[data-a="pause"]').textContent=(opts.pause?'✓ ':'')+'Pause on lookup';
      el.querySelector('[data-a="tr"]').textContent=(opts.tr?'✓ ':'')+'Translations';
      el.querySelector('[data-a="follow"]').textContent=(opts.follow?'✓ ':'')+'Follow';
      el.querySelector('[data-a="pause"]').classList.toggle('on',opts.pause);el.querySelector('[data-a="tr"]').classList.toggle('on',opts.tr);el.querySelector('[data-a="follow"]').classList.toggle('on',opts.follow);
      f('offset').textContent=offset?`${offset>0?'+':''}${offset.toFixed(1)} s`:'';
      el.classList.toggle('mu-hide-tr',!opts.tr);
    }
    paintTools();
    const position=()=>!now?0:now.position+(now.playing?(Date.now()-nowAt)/1000:0);

    async function refresh(){
      let n;try{n=await api('music.now');}catch(e){return;}
      if(!page)return;
      now=n;nowAt=Date.now();
      if(n.access===false){
        f('song').textContent='Allow Kotoba to see what’s playing';f('meta').textContent='';
        f('lines').innerHTML=`<div class="mu-empty"><p class="hint">Android shares the playing song (title, position) with apps you allow under Notification access. Kotoba only reads the song, and pauses it when you look a word up.</p><button class="btn primary" data-a="grant">Open Notification access</button></div>`;
        f('lines').querySelector('[data-a="grant"]').onclick=()=>api('music.access');
        song='';return;
      }
      if(!n.title){f('song').textContent='Nothing playing';f('meta').textContent='';return;}
      f('meta').textContent=[n.artist,n.app].filter(Boolean).join(' · ');
      el.querySelector('[data-a="toggle"]').textContent=n.playing?'⏸ Pause':'▶ Play';
      const key=n.title+'|'+n.artist;
      if(key!==song){song=key;offset=store.get(offsetKey(),0);paintTools();f('song').textContent=n.title;await load(false);}
    }
    async function load(again){
      f('lines').innerHTML='<p class="hint mu-empty">Looking for lyrics…</p>';lyrics=null;active=-1;
      let r;
      try{r=await api('lyrics.get',{title:now.title,artist:now.artist,album:now.album||'',duration:now.duration||0,refresh:!!again});}
      catch(e){f('lines').innerHTML=`<div class="mu-empty"><p class="hint">${esc(e.message)}</p><button class="btn" data-a="find2">Search lyrics…</button></div>`;f('lines').querySelector('[data-a="find2"]').onclick=findSheet;return;}
      if(!page||r.title!==now.title)return;
      render(r);
    }
    function render(r){
      lyrics=r;active=-1;
      if(!r.lines.length){
        f('lines').innerHTML=`<div class="mu-empty"><p class="hint">No lyrics found for this song.</p><button class="btn" data-a="find2">Search lyrics…</button></div>`;
        f('lines').querySelector('[data-a="find2"]').onclick=findSheet;return;
      }
      lang=textLang(r.lines.map(l=>l.text).join('\n'),'');
      f('lines').innerHTML=`<p class="hint mu-source">${esc(r.source)}${r.synced?'':' · not synced'}</p>`+r.lines.map((l,i)=>`<div class="mu-line" data-i="${i}"><div class="mu-text" lang="${lang}">${tappable(l.text)}</div>${l.tr?`<div class="mu-tr">${esc(l.tr)}</div>`:''}<div class="mu-acts"><button data-l="tr">${icon('share')} Translate</button><button data-l="card">${icon('star')} Sentence</button><button data-l="copy">${icon('copy')}</button></div></div>`).join('');
    }

    // Words: look up (pausing the music if chosen, and playing on when the lookup closes).
    f('lines').addEventListener('click',async(e)=>{
      const line=e.target.closest('.mu-line');if(!line||!lyrics)return;
      const l=lyrics.lines[+line.dataset.i];
      const act=e.target.closest('[data-l]');
      if(act){
        const a=act.dataset.l;
        const around=lyrics.lines.slice(Math.max(0,+line.dataset.i-2),+line.dataset.i+3).map(x=>x.text).join('\n');
        if(a==='tr')window.__translateInApp?window.__translateInApp(l.text,around):Kotoba.translate(l.text);
        if(a==='card')await saveSentence({text:l.text,back:l.tr||'',note:`♪ ${now.title} — ${now.artist}${l.t>=0?' · '+fmt(l.t):''}`});
        if(a==='copy'){Kotoba.copy(l.text);toast('Copied');}
        return;
      }
      const w=e.target.closest('.mu-w');
      // Only one line shows its buttons: tapping another line closes the last one.
      f('lines').querySelectorAll('.mu-line.open').forEach(x=>{if(x!==line)x.classList.remove('open');});
      // A tap beside the words: the song jumps to this line (unsynced lyrics just show the line's buttons).
      if(!w){
        if(l.t>=0&&now&&now.control){
          await api('music.control',{action:'seek',t:Math.max(0,l.t-offset)});
          if(now){now.position=Math.max(0,l.t-offset);nowAt=Date.now();}
          userScrolled=0;active=-2;setTimeout(refresh,400);
        }else line.classList.toggle('open');
        return;
      }
      const word=wordAt(l.text,w);if(!word)return;
      line.classList.add('open');
      line.querySelectorAll('.mu-w.on').forEach(x=>x.classList.remove('on'));
      if(w.dataset.end!=null)line.querySelectorAll('.mu-w').forEach(x=>{if(x.dataset.end===w.dataset.end&&+x.dataset.o>=+w.dataset.o)x.classList.add('on');});else w.classList.add('on');
      if(opts.pause&&now&&now.playing){pausedByUs=true;api('music.control',{action:'pause'}).then(r=>{now=r;nowAt=Date.now();}).catch(()=>{});}
      await lookupSheet(word,{context:l.text},{lang,book:`♪ ${now.title} — ${now.artist}`,onClose:()=>{
        line.querySelectorAll('.mu-w.on').forEach(x=>x.classList.remove('on'));
        if(pausedByUs&&!sheetStack.length){pausedByUs=false;api('music.control',{action:'play'}).then(r=>{now=r;nowAt=Date.now();}).catch(()=>{});}
      }});
    });

    // Following the song: the line at the current time, kept in view unless you've just scrolled yourself.
    f('scroll').addEventListener('wheel',()=>{userScrolled=Date.now();},{passive:true});
    f('scroll').addEventListener('touchmove',()=>{userScrolled=Date.now();},{passive:true});
    let raf=0;
    function tick(){
      raf=requestAnimationFrame(tick);
      if(!lyrics||!lyrics.synced||!lyrics.lines.length)return;
      const t=position()+offset;
      let i=-1;for(let k=0;k<lyrics.lines.length;k++){if(lyrics.lines[k].t<=t)i=k;else break;}
      if(i===active)return;
      const lines=f('lines').querySelectorAll('.mu-line');
      active=i;
      // Exactly one current line (after a jump the previous one mustn't stay lit).
      lines.forEach((x,k)=>{x.classList.toggle('past',k<i);x.classList.toggle('now',k===i);});
      if(i>=0&&lines[i]){if(opts.follow&&Date.now()-userScrolled>4000&&!sheetStack.length)lines[i].scrollIntoView({block:'center',behavior:'smooth'});}
    }
    tick();

    // Tools.
    el.querySelector('[data-a="back"]').onclick=()=>popPage();
    el.querySelector('[data-a="find"]').onclick=findSheet;
    el.querySelector('[data-a="toggle"]').onclick=handle(async()=>{now=await api('music.control',{action:'toggle'});nowAt=Date.now();refresh();});
    const nudge=(d)=>{offset=Math.round((offset+d)*10)/10;store.set(offsetKey(),offset);paintTools();active=-2;};
    el.querySelector('[data-a="earlier"]').onclick=()=>nudge(0.5);
    el.querySelector('[data-a="later"]').onclick=()=>nudge(-0.5);
    el.querySelector('[data-a="pause"]').onclick=()=>{opts.pause=!opts.pause;store.set('music.pauseLookup',opts.pause);paintTools();};
    el.querySelector('[data-a="tr"]').onclick=()=>{opts.tr=!opts.tr;store.set('music.showTr',opts.tr);paintTools();};
    el.querySelector('[data-a="follow"]').onclick=()=>{opts.follow=!opts.follow;userScrolled=0;paintTools();};

    /** When the automatic match is wrong or missing: search LRCLIB and NetEase by hand and pick one. */
    function findSheet(){
      if(!now||!now.title){toast('Play a song first');return;}
      const s=openSheet(`<div class="sheet-body"><input class="input" id="mu-q" value="${esc(now.title+' '+(now.artist||''))}"><div id="mu-res" style="margin-top:10px"></div></div>`,{title:'Find lyrics',tall:true});
      const q=s.sheet.querySelector('#mu-q'),res=s.sheet.querySelector('#mu-res');
      const go=handle(async()=>{
        res.innerHTML='<p class="hint">Searching…</p>';
        const list=await api('lyrics.search',{q:q.value.trim()});
        res.innerHTML=list.length?list.map((x,i)=>`<button class="row" data-i="${i}"><div class="hw">${esc(x.title)}</div><div class="hint" style="margin:0">${esc(x.artist)} · ${fmt(x.duration)} · ${x.source==='netease'?'NetEase':'LRCLIB'}${x.synced?'':' · not synced'}</div></button>`).join(''):'<p class="hint">Nothing found. Try the title in its original script, or fewer words.</p>';
        res.querySelectorAll('[data-i]').forEach(b=>b.onclick=handle(async()=>{const x=list[+b.dataset.i];closeSheet(s);render(await api('lyrics.pick',{title:now.title,artist:now.artist,source:x.source,id:x.id}));}));
      });
      q.addEventListener('keydown',e=>{if(e.key==='Enter')go();});
      go();
    }

    await refresh();
    const poll=setInterval(refresh,1000);
  }
  window.openMusic=openMusic;
  window.tappableText=tappable;window.tappableWord=wordAt;// Listening shows its lines the same way

  // Entry points: the Lyrics tile on the Dictionary home screen, and the Mac's Music tab (desktop-after.js).
})();
