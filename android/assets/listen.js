'use strict';
/*
 * Listening (Glossika-style): sets of spoken lines, each heard a few times with a pause to repeat it, then the next.
 * A set is split into groups (Genshin: characters, under their regions); a group's lines play in order or shuffled.
 * The line's text can show, show after the first hearing (listen first), or stay hidden; its words are tappable
 * like lyrics, and the English goes underneath when wanted.
 */
(function(){
  const store={get(k,d){try{const v=localStorage.getItem(k);return v===null?d:JSON.parse(v);}catch(e){return d;}},set(k,v){try{localStorage.setItem(k,JSON.stringify(v));}catch(e){}}};
  const url=(set,p)=>`/listen/${encodeURIComponent(set)}/`+String(p).split('/').map(encodeURIComponent).join('/');
  const defaults={repeats:2,gap:1.2,speed:1,text:'after',tr:false,pause:true,chain:'next'};
  const opts=Object.assign({},defaults,store.get('listen.opts',{}));
  if(opts.speed===0.9)opts.speed=1;// no longer offered
  const saveOpts=()=>store.set('listen.opts',opts);

  // ---------- the sets ----------
  async function openListening(){
    const sets=await api('listen.sets');
    if(sets.length===1)return openSet(sets[0].id);
    const el=document.createElement('div');el.className='ls-page';
    el.innerHTML=`<div class="bar"><button class="icon-btn" data-a="back" aria-label="Back">${icon('back')}</button><div class="title"><b>Listening</b><small>hear it, repeat it, shadow it</small></div></div>
      <div class="ls-scroll">${sets.length?sets.map(s=>`<button class="ls-set" data-set="${esc(s.id)}">${s.cover?`<img src="${url(s.id,s.cover)}" alt="">`:'<span class="ls-glyph">聴</span>'}<span><b>${esc(s.title)}</b><small>${esc(s.subtitle)}</small><small>${s.groups} groups · ${s.lines.toLocaleString()} lines</small></span></button>`).join('')
        :`<p class="hint ls-empty">No listening sets on this device yet. Sets are folders in Kotoba’s <b>listening</b> folder (on the phone: Android/data/app.kotoba.reader/files/listening).</p>`}</div>`;
    pushPage(el);
    el.querySelector('[data-a="back"]').onclick=()=>popPage();
    el.querySelectorAll('[data-set]').forEach(b=>b.onclick=handle(()=>openSet(b.dataset.set)));
  }

  // ---------- one set: its groups (characters), by section (region) ----------
  async function openSet(id){
    const set=await api('listen.set',{id});
    const el=document.createElement('div');el.className='ls-page';
    const total=set.groups.reduce((n,g)=>n+g.items.length,0);
    el.innerHTML=`<div class="bar"><button class="icon-btn" data-a="back" aria-label="Back">${icon('back')}</button><div class="title"><b>${esc(set.title)}</b><small>${set.groups.length} ${esc((set.groupLabel||'groups').toLowerCase())} · ${total.toLocaleString()} lines</small></div>
        <button class="icon-btn" data-a="playall" aria-label="Play every character in turn" title="Play every character in turn">${icon('play')}</button>
        <button class="icon-btn" data-a="shuffle" aria-label="Play everything shuffled" title="Play everything, shuffled">${icon('shuffle')}</button></div>
      <div class="ls-find"><div class="field small">${icon('search')}<input type="search" placeholder="Find a character" data-f="q" autocomplete="off"></div></div>
      <div class="ls-scroll" data-f="list"></div>`;
    pushPage(el);
    el.querySelector('[data-a="back"]').onclick=()=>popPage();
    el.querySelector('[data-a="shuffle"]').onclick=()=>openPlayer(set,shuffle(set.groups.flatMap(g=>g.items.map(it=>({g,it})))),0,'Everything, shuffled');
    el.querySelector('[data-a="playall"]').onclick=()=>openPlayer(set,chain(set.groups),0,'Every character');
    const list=el.querySelector('[data-f="list"]'),q=el.querySelector('[data-f="q"]');
    const done=store.get('listen.done.'+set.id,{});
    function render(){
      const needle=q.value.trim().toLowerCase();
      const groups=set.groups.filter(g=>!needle||(g.name+' '+(g.nameEn||'')).toLowerCase().includes(needle));
      const sections=[];
      for(const g of groups){let s=sections.find(x=>x.name===g.section);if(!s)sections.push(s={name:g.section||'',en:g.sectionEn||'',groups:[]});s.groups.push(g);}
      list.innerHTML=sections.map(s=>`${s.name?`<div class="section-label ls-sec">${esc(s.name)}${s.en?`<span>${esc(s.en)}</span>`:''}<button class="ls-sec-play" data-sec="${esc(s.name)}" title="Play these characters in turn">${icon('play')} Play</button></div>`:''}<div class="ls-grid">${s.groups.map(g=>{
        const heard=Object.keys(done[g.id]||{}).length;
        return `<button class="ls-group" data-g="${esc(g.id)}">${g.icon?`<img src="${url(set.id,g.icon)}" alt="" loading="lazy">`:'<span class="ls-glyph">声</span>'}<b>${esc(g.name)}</b><small>${esc(g.nameEn||'')}</small><small>${heard?`${heard}/`:''}${g.items.length} lines</small></button>`;}).join('')}</div>`).join('')||'<p class="hint ls-empty">No one by that name.</p>';
      list.querySelectorAll('[data-g]').forEach(b=>b.onclick=()=>openGroup(set,set.groups.find(g=>g.id===b.dataset.g)));
      // A region or faction: its characters one after another.
      list.querySelectorAll('[data-sec]').forEach(b=>b.onclick=()=>{const gs=set.groups.filter(g=>g.section===b.dataset.sec);openPlayer(set,chain(gs),0,b.dataset.sec);});
    }
    q.addEventListener('input',render);
    render();
  }

  // ---------- one group (a character): the lines, by title ----------
  function openGroup(set,g){
    const el=document.createElement('div');el.className='ls-page';
    el.innerHTML=`<div class="bar"><button class="icon-btn" data-a="back" aria-label="Back">${icon('back')}</button>${g.icon?`<img class="ls-bar-icon" src="${url(set.id,g.icon)}" alt="">`:''}<div class="title"><b>${esc(g.name)}</b><small>${esc(g.nameEn||'')} · ${g.items.length} lines</small></div></div>
      <div class="ls-actions"><button class="btn primary" data-a="play">${icon('play')} Play all</button><button class="btn" data-a="shuffle">${icon('shuffle')} Shuffle</button><button class="btn" data-a="story" hidden>${icon('book')} Story</button></div>
      <div class="ls-scroll"><div class="ls-lines">${g.items.map((it,i)=>`<button class="ls-line" data-i="${i}"><span class="ls-line-title">${esc(it.title)}${it.titleEn?`<small>${esc(it.titleEn)}</small>`:''}</span><span class="ls-line-text">${esc(it.text)}</span>${it.translation?`<span class="ls-line-tr">${esc(it.translation)}</span>`:''}</button>`).join('')}</div></div>`;
    pushPage(el);
    el.querySelector('[data-a="back"]').onclick=()=>popPage();
    const queue=g.items.map(it=>({g,it}));
    // Play all: this character, then the next ones in the set (the player can stop after each character instead).
    el.querySelector('[data-a="play"]').onclick=()=>{const k=set.groups.indexOf(g);openPlayer(set,chain(set.groups.slice(k).concat(set.groups.slice(0,k))),0,g.name+' onward');};
    el.querySelector('[data-a="shuffle"]').onclick=()=>openPlayer(set,shuffle(queue),0,g.name+' · shuffled');
    el.querySelectorAll('[data-i]').forEach(b=>b.onclick=()=>openPlayer(set,queue,+b.dataset.i,g.name));
    // The character's stories, when the set has them (stories.json beside it).
    loadStories(set).then(st=>{
      const sg=st&&st.groups&&st.groups[g.id];if(!sg)return;
      const b=el.querySelector('[data-a="story"]');b.hidden=false;b.onclick=()=>openStory(set,g,sg,0);
    });
  }

  // ---------- a character's stories, in Chinese, Japanese, Korean or English ----------
  const storyCache={};
  function loadStories(set){
    if(!(set.id in storyCache))storyCache[set.id]=fetch(url(set.id,'stories.json')).then(r=>r.ok?r.json():null).catch(()=>null);
    return storyCache[set.id];
  }
  const LANG_LABEL={zh:'中文',ja:'日本語',ko:'한국어',en:'English'};
  function storyLang(set,sg){
    const langs=Object.keys(LANG_LABEL).filter(l=>sg.sections[l]);
    const lang=store.get('listen.storyLang','');
    return langs.includes(lang)?lang:langs.includes(set.lang)?set.lang:langs[0];
  }
  // Chip labels: 角色故事3 → 故事3, Character Story 3 → Story 3…
  const shortTitle=t=>String(t).replace(/^角色(故事|詳細|详细)/,'$1').replace(/^キャラクター(ストーリー|詳細)/,'$1').replace(/^캐릭터\s*(스토리|상세)/,'$1').replace(/^Character\s+(Story|Details)/i,'$1').trim();
  function openStory(set,g,sg,startAt=0){
    const langs=Object.keys(LANG_LABEL).filter(l=>sg.sections[l]);
    let lang=storyLang(set,sg);
    const el=document.createElement('div');el.className='ls-page ls-story';
    el.innerHTML=`<div class="bar"><button class="icon-btn" data-a="back" aria-label="Back">${icon('back')}</button>${g.icon?`<img class="ls-bar-icon" src="${url(set.id,g.icon)}" alt="">`:''}<div class="title"><b data-f="name"></b><small>Character stories</small></div></div>
      <div class="ls-langs" data-f="langs"></div>
      <div class="ls-toc" data-f="toc"></div>
      <div class="ls-scroll" data-f="scroll"><div class="ls-story-body" data-f="body"></div></div>`;
    pushPage(el);
    el.querySelector('[data-a="back"]').onclick=()=>popPage();
    const f=n=>el.querySelector(`[data-f="${n}"]`);
    let paras=[],accentRun=null;
    function render(){
      f('name').textContent=sg.names[lang]||g.name;
      f('langs').innerHTML=langs.map(l=>`<button class="chip small ${l===lang?'on':''}" data-l="${l}">${LANG_LABEL[l]}</button>`).join('')+
        (lang==='ja'?`<button class="chip small ls-acc-chip ${store.get('listen.accent',false)?'on':''}" data-acc title="Pitch accent over each word, from the NHK accent dictionary">アクセント</button>`:'');
      const accChip=f('langs').querySelector('[data-acc]');
      if(accChip)accChip.onclick=()=>{store.set('listen.accent',!store.get('listen.accent',false));const k=current();render();jump(k,true);};
      // Another language: the same entry stays in view.
      f('langs').querySelectorAll('[data-l]').forEach(b=>b.onclick=()=>{const k=current();lang=b.dataset.l;store.set('listen.storyLang',lang);render();jump(k,true);});
      f('toc').innerHTML=sg.sections[lang].map((s,k)=>`<button class="chip small" data-k="${k}">${esc(shortTitle(s.title))}</button>`).join('');
      f('toc').querySelectorAll('[data-k]').forEach(c=>c.onclick=()=>jump(+c.dataset.k));
      paras=[];
      f('body').lang=lang==='zh'?'zh-CN':lang;
      const withAccent=lang==='ja'&&store.get('listen.accent',false);
      f('body').classList.toggle('acc',withAccent);
      f('body').innerHTML=sg.sections[lang].map(sec=>`<section><h3>${esc(sec.title)}</h3>${sec.note?`<p class="ls-story-note">${esc(sec.note)}</p>`:''}${sec.text.split(/\n+/).filter(Boolean).map(p=>{paras.push(p);return `<p class="ls-para" data-p="${paras.length-1}">${window.tappableText?tappableText(p):esc(p)}</p>`;}).join('')}</section>`).join('');
      if(withAccent){
        // A few paragraphs at a time, from the entry in view onward (then the ones before it), each shown as it's ready.
        const ps=[...paras],shown=lang,token={};accentRun=token;
        const firstPara=()=>{const sec=sections()[current()];const p=sec&&sec.querySelector('.ls-para');return p?+p.dataset.p:0;};
        const start=firstPara(),order=[...ps.keys()].slice(start).concat([...ps.keys()].slice(0,start));
        (async()=>{
          for(let b=0;b<order.length;b+=4){
            if(accentRun!==token||lang!==shown||!el.isConnected)return;
            const idx=order.slice(b,b+4);
            const res=await accents(idx.map(n=>ps[n]),set.id);
            if(accentRun!==token||lang!==shown)return;
            idx.forEach((n,j)=>{const p=f('body').querySelector(`.ls-para[data-p="${n}"]`);if(p)p.innerHTML=accentHtml(ps[n],res[j]);});
          }
        })().catch(e=>{toast(e.message);store.set('listen.accent',false);});
      }
    }
    // Tap a word to look it up, in the language shown.
    f('body').addEventListener('click',e=>{const w=e.target.closest('.mu-w');if(w)lookWord(w);});
    hoverWords(f('body'),w=>lookWord(w));
    async function lookWord(w){
      const p=w.closest('.ls-para');if(!p||!window.tappableWord)return;
      const text=paras[+p.dataset.p],word=tappableWord(text,w);if(!word)return;
      f('body').querySelectorAll('.mu-w.on').forEach(x=>x.classList.remove('on'));
      if(w.dataset.end!=null)p.querySelectorAll('.mu-w').forEach(x=>{if(x.dataset.end===w.dataset.end&&+x.dataset.o>=+w.dataset.o)x.classList.add('on');});else w.classList.add('on');
      await lookupSheet(word,{context:text},{lang,book:`${set.title} · ${sg.names[lang]||g.name}`,anchor:w.getBoundingClientRect(),onClose:()=>f('body').querySelectorAll('.mu-w.on').forEach(x=>x.classList.remove('on'))});
    }
    const sections=()=>[...f('body').querySelectorAll('section')];
    function jump(k,instant){
      const sec=sections()[Math.min(k,sections().length-1)];if(!sec)return;
      f('scroll').scrollTo({top:sec.offsetTop-8,behavior:instant?'auto':'smooth'});mark(k);
    }
    // Which entry is in view (the last one whose heading has passed the top).
    function current(){
      const top=f('scroll').scrollTop+40;let k=0;
      sections().forEach((s,n)=>{if(s.offsetTop<=top)k=n;});return k;
    }
    function mark(k){
      f('toc').querySelectorAll('[data-k]').forEach(c=>c.classList.toggle('on',+c.dataset.k===k));
      const on=f('toc').querySelector('.on');if(on)on.scrollIntoView({block:'nearest',inline:'nearest'});
    }
    f('scroll').addEventListener('scroll',()=>mark(current()),{passive:true});
    render();
    requestAnimationFrame(()=>jump(startAt,true));
  }

  // ---------- pitch accent (Japanese): NHK's notation over each word, like furigana ----------
  const accentCache=new Map(),accentFiles={};
  /** Words with their accent for each text: from the set's accents.json when it has them (worked out on the Mac),
   *  otherwise one request for all that aren't known yet. */
  async function accents(texts,setId){
    if(setId){
      if(!(setId in accentFiles))accentFiles[setId]=fetch(url(setId,'accents.json')).then(r=>r.ok?r.json():{}).catch(()=>({}));
      const file=await accentFiles[setId];
      for(const t of texts)if(!accentCache.has(t)&&file[t])accentCache.set(t,file[t]);
    }
    const need=[...new Set(texts.filter(t=>!accentCache.has(t)))];
    if(need.length){const r=await api('accent.text',{texts:need});need.forEach((t,i)=>accentCache.set(t,r.results[i]));}
    return texts.map(t=>accentCache.get(t));
  }
  /** The text as tappable characters (as tappableText does), each word under its accent; a particle shows high after a
   *  flat word and low after a drop. */
  function accentHtml(text,words){
    const cps=[...text],off=[];let o=0;for(const c of cps){off.push(o);o+=c.length;}
    const span=k=>cps[k]===' '?' ':`<span class="mu-w" data-o="${off[k]}">${esc(cps[k])}</span>`;
    let html='',k=0,prev=null;
    for(const w of words||[]){
      if(w.s<k)continue;
      while(k<w.s){html+=span(k);k++;}
      const inner=cps.slice(w.s,w.e).map((_,j)=>span(w.s+j)).join('');
      let rt='';
      if(w.accent)rt=pitchHtml(w.accent);
      else if(w.particle&&prev&&prev.accent&&prev.e===w.s){
        const high=!prev.accent.includes('＼');
        rt=`<span class="pitch">${[...w.kana].map(ch=>`<span class="${high?'hi':''}">${esc(ch)}</span>`).join('')}</span>`;
      }
      html+=rt?`<ruby>${inner}<rt>${rt}</rt></ruby>`:inner;
      k=w.e;prev=w;
    }
    while(k<cps.length){html+=span(k);k++;}
    return html;
  }

  /** Mac: holding the hover key (Shift) over a word looks it up, as in books and videos; the last popup gives way. */
  function hoverWords(container,look){
    if(!window.KotobaHover)return;
    let last=null,timer=0;
    container.addEventListener('mousemove',e=>{
      if(!KotobaHover.matches(e)){last=null;clearTimeout(timer);return;}
      const w=e.target.closest('.mu-w');if(!w||w===last)return;
      last=w;clearTimeout(timer);
      timer=setTimeout(()=>{sheetStack.filter(x=>x.sheet.classList.contains('floating')).forEach(x=>closeSheet(x));look(w);},150);
    });
  }

  /** Characters' lines one character after another. */
  const chain=gs=>gs.flatMap(g=>g.items.map(it=>({g,it})));
  function shuffle(a){a=a.slice();for(let i=a.length-1;i>0;i--){const j=Math.floor(Math.random()*(i+1));[a[i],a[j]]=[a[j],a[i]];}return a;}

  // ---------- the player: each line heard `repeats` times with room to repeat it, then the next ----------
  function openPlayer(set,queue,start,label){
    const el=document.createElement('div');el.className='ls-page ls-player';
    el.innerHTML=`<div class="bar"><button class="icon-btn" data-a="back" aria-label="Back">${icon('back')}</button><div class="title"><b>${esc(label)}</b><small data-f="pos"></small></div>
        <button class="icon-btn" data-a="card" aria-label="Keep the whole line as a sentence card" title="Keep the whole line as a sentence card">${icon('star')}</button></div>
      <div class="ls-stage">
        <div class="ls-who" data-f="who"></div>
        <div class="ls-text" data-f="text"></div>
        <div class="ls-tr" data-f="tr"></div>
        <div class="ls-note" data-f="note"></div>
      </div>
      <div class="ls-progress"><i data-f="bar"></i></div>
      <div class="ls-controls"><button class="icon-btn" data-a="prev" aria-label="Previous line">${icon('prev')}</button><button class="ls-play" data-a="toggle" aria-label="Play or pause"></button><button class="icon-btn" data-a="next" aria-label="Next line">${icon('next')}</button></div>
      <button class="ls-opts-toggle" data-a="opts"></button>
      <div class="ls-opts" data-f="opts"></div>`;
    let i=Math.max(0,Math.min(start,queue.length-1)),rep=0,playing=true,heard=false,timer=0,pausedByLookup=false,closed=false;
    const audio=new Audio();audio.preload='auto';
    const f=n=>el.querySelector(`[data-f="${n}"]`);
    pushPage(el,{onClose:()=>{closed=true;clearTimeout(timer);audio.pause();audio.src='';}});
    el.querySelector('[data-a="back"]').onclick=()=>popPage();

    // The options fold away (shown as a one-line summary) so the line has the room.
    const showOpts=()=>{f('opts').hidden=!opts.open;el.querySelector('[data-a="opts"]').textContent=(opts.open?'▾ ':'▸ ')+`×${opts.repeats} · pause ${({0:'none',0.5:'brief',1.2:'short',2:'long'})[opts.gap]||opts.gap} · speed ${opts.speed} · text ${opts.text==='after'?'after hearing':opts.text} · English ${opts.tr?'on':'off'}`;};
    el.querySelector('[data-a="opts"]').onclick=()=>{opts.open=!opts.open;saveOpts();showOpts();};
    function paintOpts(){
      const chip=(key,val,label)=>`<button class="chip small ${opts[key]===val?'on':''}" data-o="${key}" data-v="${val}">${label}</button>`;
      f('opts').innerHTML=`<div><span>Hear each</span>${chip('repeats',1,'×1')}${chip('repeats',2,'×2')}${chip('repeats',3,'×3')}</div>
        <div><span>Pause to repeat</span>${chip('gap',0,'none')}${chip('gap',0.5,'brief')}${chip('gap',1.2,'short')}${chip('gap',2,'long')}</div>
        <div><span>Speed</span>${chip('speed',0.8,'0.8')}${chip('speed',1,'1')}${chip('speed',1.25,'1.25')}${chip('speed',1.5,'1.5')}${chip('speed',2,'2')}</div>
        <div><span>Text</span>${chip('text','show','show')}${chip('text','after','after hearing')}${chip('text','hide','hide')}</div>
        <div><span>English</span>${chip('tr',true,'show')}${chip('tr',false,'hide')}</div>
        <div><span>After a character</span>${chip('chain','next','next one')}${chip('chain','stop','stop')}</div>
        <div><span>Pause on lookup</span>${chip('pause',true,'on')}${chip('pause',false,'off')}</div>
        ${set.lang==='ja'?`<div><span>Pitch accent</span>${chip('accent',true,'show')}${chip('accent',false,'hide')}</div>`:''}`;
      f('opts').querySelectorAll('[data-o]').forEach(b=>b.onclick=()=>{
        const k=b.dataset.o,raw=b.dataset.v;opts[k]=raw==='true'?true:raw==='false'?false:isNaN(+raw)?raw:+raw;
        saveOpts();paintOpts();paintText();showOpts();audio.playbackRate=opts.speed;
        if(k==='accent')paint();
      });
    }
    function paintText(){
      const {it}=queue[i];
      const hidden=opts.text==='hide'||(opts.text==='after'&&!heard);
      f('text').classList.toggle('hidden',hidden);
      f('tr').hidden=!opts.tr||!it.translation||hidden;// the English only once the Chinese is showing
    }
    function paint(){
      const {g,it}=queue[i];
      f('pos').textContent=`${i+1} / ${queue.length}`;
      f('who').innerHTML=`${g.icon?`<img src="${url(set.id,g.icon)}" alt="">`:''}<span><b>${esc(g.name)}</b> · ${esc(it.title)}${it.titleEn?`<small>${esc(g.nameEn||'')} · ${esc(it.titleEn)}</small>`:''}</span>`;
      f('text').innerHTML=window.tappableText?tappableText(it.text):esc(it.text);
      f('text').classList.toggle('acc',set.lang==='ja'&&!!opts.accent);
      if(set.lang==='ja'&&opts.accent){const at=i;accents([it.text],set.id).then(r=>{if(at===i&&el.isConnected)f('text').innerHTML=accentHtml(it.text,r[0]);}).catch(e=>toast(e.message));}
      f('tr').textContent=it.translation||'';
      f('note').textContent=it.note||'';
      f('bar').style.width=`${(i+1)/queue.length*100}%`;
      paintText();
    }
    function paintPlay(){el.querySelector('[data-a="toggle"]').innerHTML=playing?'<svg viewBox="0 0 24 24"><path d="M8 5h3v14H8zM13 5h3v14h-3z" fill="currentColor"/></svg>':'<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z" fill="currentColor"/></svg>';}

    function load(n,autoplay){
      clearTimeout(timer);
      i=(n+queue.length)%queue.length;rep=0;heard=false;
      audio.src=url(set.id,queue[i].it.audio);audio.playbackRate=opts.speed;
      paint();
      if(autoplay&&playing)audio.play().catch(()=>{playing=false;paintPlay();});
    }
    // After each hearing: room to say it yourself (as long as the line, times the pause setting), then again or on.
    audio.addEventListener('ended',()=>{
      if(closed)return;
      rep++;heard=true;paintText();
      const {g,it}=queue[i];
      const done=store.get('listen.done.'+set.id,{});(done[g.id]=done[g.id]||{})[it.id]=1;store.set('listen.done.'+set.id,done);
      // Room to repeat it: as long as the line (times the setting), but a long speech doesn't need its whole length.
      const cap=opts.gap>1.5?14:opts.gap>=1?9:4;
      const wait=(opts.gap?Math.min((it.dur||audio.duration||2)*opts.gap/opts.speed,cap)+(opts.gap<1?0.3:0.6):0.7)*1000;
      timer=setTimeout(()=>{
        if(!playing||closed)return;
        if(rep<opts.repeats){audio.currentTime=0;audio.play().catch(()=>{});}
        else if(i+1>=queue.length){playing=false;paintPlay();toast('That was the last line');}
        else if(opts.chain==='stop'&&queue[i+1].g!==g){playing=false;paintPlay();load(i+1,false);toast(`End of ${g.name} · press play for ${queue[i].g.name}`);}
        else load(i+1,true);
      },wait);
    });
    el.querySelector('[data-a="toggle"]').onclick=()=>{
      playing=!playing;paintPlay();
      if(playing){if(audio.ended||audio.paused){if(rep>=opts.repeats)load(i+1,true);else audio.play().catch(()=>{});}}
      else{clearTimeout(timer);audio.pause();}
    };
    el.querySelector('[data-a="prev"]').onclick=()=>load(i-1,true);
    el.querySelector('[data-a="next"]').onclick=()=>load(i+1,true);
    // Tap the text: hidden → shown; a word → looked up (the line waits meanwhile).
    f('text').addEventListener('click',e=>{
      if(f('text').classList.contains('hidden')){heard=true;paintText();return;}
      const w=e.target.closest('.mu-w');if(w)lookWord(w);
    });
    hoverWords(f('text'),w=>{if(!f('text').classList.contains('hidden'))lookWord(w);});
    async function lookWord(w){
      if(!window.tappableWord)return;
      const {g,it}=queue[i];
      const word=tappableWord(it.text,w);if(!word)return;
      f('text').querySelectorAll('.mu-w.on').forEach(x=>x.classList.remove('on'));
      if(w.dataset.end!=null)f('text').querySelectorAll('.mu-w').forEach(x=>{if(x.dataset.end===w.dataset.end&&+x.dataset.o>=+w.dataset.o)x.classList.add('on');});else w.classList.add('on');
      if(opts.pause&&playing){pausedByLookup=true;playing=false;clearTimeout(timer);audio.pause();paintPlay();}
      await lookupSheet(word,{context:it.text},{lang:set.lang,book:`${set.title} · ${g.name} · ${it.title}`,anchor:w.getBoundingClientRect(),onClose:()=>{
        f('text').querySelectorAll('.mu-w.on').forEach(x=>x.classList.remove('on'));
        if(pausedByLookup&&!sheetStack.length){pausedByLookup=false;playing=true;paintPlay();if(rep>=opts.repeats)load(i+1,true);else audio.play().catch(()=>{});}
      }});
    }
    // A sentence card: the line, its English on the back, and the voice itself to play in review.
    const addCard=handle(async()=>{
      const {g,it}=queue[i];
      if(playing){playing=false;clearTimeout(timer);audio.pause();paintPlay();}
      await saveSentence({text:it.text,back:it.translation||'',note:`${set.title} · ${g.name} · ${it.title}`,
        clips:[{dict:0,path:`${set.id}/${it.audio}`,url:url(set.id,it.audio),dictionary:`${g.name} · ${it.title}`,label:g.nameEn||g.name}]});
    });
    el.querySelector('[data-a="card"]').onclick=addCard;
    // Keys on the Mac: space plays/pauses, ← → move between lines, R hears it again.
    const onKey=e=>{
      if(!el.isConnected){removeEventListener('keydown',onKey);return;}
      if(sheetStack.length||/INPUT|TEXTAREA/.test((e.target.tagName||'')))return;
      if(e.key===' '){e.preventDefault();el.querySelector('[data-a="toggle"]').click();}
      else if(e.key==='ArrowRight')load(i+1,true);
      else if(e.key==='ArrowLeft')load(i-1,true);
      else if(e.key==='r'||e.key==='R'){clearTimeout(timer);rep=Math.max(0,rep-1);audio.currentTime=0;playing=true;paintPlay();audio.play().catch(()=>{});}
    };
    addEventListener('keydown',onKey);
    paintOpts();showOpts();paintPlay();load(i,true);
  }

  window.openListening=openListening;
})();
