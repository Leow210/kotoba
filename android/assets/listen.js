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
  const defaults={repeats:2,gap:1.2,speed:1,text:'after',tr:false,pause:true,chain:'next',end:'loop',roman:'show',gloss:true,mode:'passive',l1repeats:1,l1speed:1,sayPause:'auto',thinkPause:'auto'};
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
      ${g.note?`<p class="ls-group-note">${esc(g.note)}</p>`:''}
      <div class="ls-scroll"><div class="ls-lines">${g.items.map((it,i)=>`<button class="ls-line" data-i="${i}"><span class="ls-line-title">${esc(it.title)}${it.titleEn?`<small>${esc(it.titleEn)}</small>`:''}</span><span class="ls-line-text">${esc(it.text)}</span>${it.roman?`<span class="ls-line-roman">${romanHtml(it.roman)}</span>`:''}${it.translation?`<span class="ls-line-tr">${esc(it.translation)}</span>`:''}</button>`).join('')}</div></div>`;
    pushPage(el);
    el.querySelector('[data-a="back"]').onclick=()=>popPage();
    const queue=g.items.map(it=>({g,it}));
    // Play all: this character, then the next ones in the set (the player can stop after each character instead).
    el.querySelector('[data-a="play"]').onclick=()=>{const k=set.groups.indexOf(g);openPlayer(set,chain(set.groups.slice(k).concat(set.groups.slice(0,k))),0,g.name+' onward');};
    el.querySelector('[data-a="shuffle"]').onclick=()=>openPlayer(set,shuffle(queue),0,g.name+' · shuffled');
    // A line: from there to the end of this character, then on through the next ones (or stop, per the player's option).
    el.querySelectorAll('[data-i]').forEach(b=>b.onclick=()=>{const k=set.groups.indexOf(g);openPlayer(set,chain(set.groups.slice(k).concat(set.groups.slice(0,k))),+b.dataset.i,g.name+' onward');});
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

  // ---------- romanization (jyutping) over each word, tones coloured; the word-by-word gloss under ----------
  const toneOf=syl=>{const m=/([1-6])$/.exec(syl);return m?m[1]:'';};
  const romanHtml=r=>esc(r).replace(/[a-z]+[1-6]/gi,m=>`<span class="tn t${toneOf(m)}">${m}</span>`);
  /** Words as ruby (tappable characters, their jyutping above), or the whole line's jyutping under it. */
  function wordsHtml(it){
    const cps=[...it.text],off=[];let o=0;for(const c of cps){off.push(o);o+=c.length;}
    const span=k=>`<span class="mu-w" data-o="${off[k]}">${esc(cps[k])}</span>`;
    if(!it.words||!it.words.length)return cps.map((_,k)=>span(k)).join('');
    let k=0,html='';
    for(const w of it.words){
      const n=[...w.w].length,inner=cps.slice(k,k+n).map((_,j)=>span(k+j)).join('');
      const romanized=/[a-z][1-6]/i.test(w.j||'');
      html+=romanized?`<ruby>${inner}<rt>${romanHtml(w.j)}</rt></ruby>`:inner;
      k+=n;
    }
    while(k<cps.length){html+=span(k);k++;}
    return html;
  }
  const LANG_NAME={yue:['粵','Cantonese'],zh:['普','Mandarin'],ja:['日','Japanese'],ko:['韓','Korean'],th:['泰','Thai'],en:['英','English']};
  const lookupLang=l=>l==='yue'?'zh':l;// Cantonese: the Chinese dictionaries (CantoDict, CC-CEDICT…)

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
        <div class="ls-roman" data-f="roman"></div>
        <div class="ls-gloss" data-f="gloss"></div>
        <div class="ls-tr" data-f="tr"></div>
        <div class="ls-note" data-f="note"></div>
        <div class="ls-cue" data-f="cue"></div>
      </div>
      ${set.l1?`<div class="ls-modes" data-f="modes"></div>`:''}
      <div class="ls-progress"><i data-f="bar"></i></div>
      <div class="ls-controls"><span class="ls-ctl-space"></span><button class="icon-btn" data-a="prev" aria-label="Previous line">${icon('prev')}</button><button class="ls-play" data-a="toggle" aria-label="Play or pause"></button><button class="icon-btn" data-a="next" aria-label="Next line">${icon('next')}</button><button class="icon-btn ls-loop" data-a="loop" aria-label="Loop"></button></div>
      <button class="ls-opts-toggle" data-a="opts"></button>
      <div class="ls-opts" data-f="opts"></div>`;
    let i=Math.max(0,Math.min(start,queue.length-1)),rep=0,playing=true,heard=false,timer=0,pausedByLookup=false,closed=false;
    const audio=new Audio();audio.preload='auto';
    // Where each line sits: which character run (in order of play) and which of its lines. A shuffled queue mixes
    // characters, so it only counts lines.
    const runs=[];let runCount=0;
    for(let x=0;x<queue.length;x++){
      if(x===0||queue[x].g!==queue[x-1].g)runCount++;
      runs.push({n:runCount-1,k:x&&queue[x].g===queue[x-1].g?runs[x-1].k+1:0});
    }
    for(let x=queue.length-1;x>=0;x--)runs[x].len=x+1<queue.length&&runs[x+1].n===runs[x].n?runs[x+1].len:runs[x].k+1;
    const chained=runCount===new Set(queue.map(q=>q.g)).size;
    const unit=({Lessons:'Lesson',Topics:'Topic',Characters:'Character'})[set.groupLabel]||'Character';
    const f=n=>el.querySelector(`[data-f="${n}"]`);
    pushPage(el,{onClose:()=>{closed=true;clearTimeout(timer);audio.pause();audio.src='';}});
    el.querySelector('[data-a="back"]').onclick=()=>popPage();

    // The options fold away (shown as a one-line summary) so the line has the room.
    // Loop, as in a music player: 🔁 the whole queue (start over at the end), 🔂 this character, or off.
    const loopMode=()=>opts.chain==='loop'?'one':opts.end!=='stop'?'all':'off';
    function paintLoop(){
      const m=loopMode(),b=el.querySelector('[data-a="loop"]');
      // A line icon like the others: two arrows in a loop, with a small 1 for "this character"; grey when off.
      b.innerHTML=`<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M17 2l3 3-3 3"/><path d="M4 11V9a4 4 0 0 1 4-4h12"/><path d="M7 22l-3-3 3-3"/><path d="M20 13v2a4 4 0 0 1-4 4H4"/>${m==='one'?'<path d="M11.2 10.2l1.3-1v6" stroke-width="1.6"/>':''}</svg>`;
      b.classList.toggle('off',m==='off');b.classList.toggle('on',m!=='off');
      b.title=m==='one'?'Looping this character':m==='all'?'Looping: starts over at the end':'Not looping';
    }
    el.querySelector('[data-a="loop"]').onclick=()=>{
      const m=loopMode();
      if(m==='all'){opts.chain='loop';}
      else if(m==='one'){opts.chain='next';opts.end='stop';}
      else{opts.chain='next';opts.end='loop';}
      saveOpts();paintOpts();showOpts();paintLoop();toast(el.querySelector('[data-a="loop"]').title,1200);
    };
    const showOpts=()=>{
      f('opts').hidden=!opts.open;
      const gap=({0:'none',0.5:'brief',1.2:'short',2:'long'})[opts.gap]||opts.gap,T=(LANG_NAME[set.lang]||[''])[0],L=(LANG_NAME[set.l1]||[''])[0];
      const parts=set.l1?[`${T} ×${opts.repeats} · ${opts.speed}×`,`${L} ×${opts.l1repeats||1} · ${opts.l1speed||1}×`,`pause ${gap}`,`translation ${opts.tr?'on':'off'}`]
        :[`×${opts.repeats}`,`pause ${gap}`,`speed ${opts.speed}`,`text ${opts.text==='after'?'after hearing':opts.text}`,`English ${opts.tr?'on':'off'}`];
      el.querySelector('[data-a="opts"]').textContent=(opts.open?'▾ ':'▸ ')+parts.join(' · ');
    };
    el.querySelector('[data-a="opts"]').onclick=()=>{opts.open=!opts.open;saveOpts();showOpts();};
    function paintOpts(){
      const chip=(key,val,label)=>`<button class="chip small ${opts[key]===val?'on':''}" data-o="${key}" data-v="${val}">${label}</button>`;
      const speeds=k=>`${chip(k,0.8,'0.8')}${chip(k,1,'1')}${chip(k,1.25,'1.25')}${chip(k,1.5,'1.5')}${chip(k,2,'2')}`;
      // Two languages: each its own repeats and speed, and the two pauses the modes use.
      const T=LANG_NAME[set.lang]||['',''],L=LANG_NAME[set.l1]||['',''];
      f('opts').innerHTML=(set.l1?`<div class="ls-opt-head">${T[0]} ${T[1]}</div>`:'')+`<div><span>Hear each</span>${chip('repeats',1,'×1')}${chip('repeats',2,'×2')}${chip('repeats',3,'×3')}</div>
        <div><span>Speed</span>${speeds('speed')}</div>
        ${set.l1?`<div class="ls-opt-head">${L[0]} ${L[1]}</div>
        <div><span>Hear each</span>${chip('l1repeats',1,'×1')}${chip('l1repeats',2,'×2')}</div>
        <div><span>Speed</span>${speeds('l1speed')}</div>
        <div class="ls-opt-head">Pauses</div>
        <div><span>說 Time to say it</span>${chip('sayPause','auto','auto')}${chip('sayPause',4,'4 s')}${chip('sayPause',6,'6 s')}${chip('sayPause',8,'8 s')}</div>
        <div><span>懂 Time to understand</span>${chip('thinkPause','auto','auto')}${chip('thinkPause',2,'2 s')}${chip('thinkPause',3,'3 s')}${chip('thinkPause',5,'5 s')}</div>`:''}
        <div><span>Pause to repeat</span>${chip('gap',0,'none')}${chip('gap',0.5,'brief')}${chip('gap',1.2,'short')}${chip('gap',2,'long')}</div>
        ${set.l1?`<div class="ls-opt-head">Display</div>`:''}
        <div><span>Text</span>${chip('text','show','show')}${chip('text','after','after hearing')}${chip('text','hide','hide')}</div>
        <div><span>${set.l1?'Translation':'English'}</span>${chip('tr',true,'show')}${chip('tr',false,'hide')}</div>
        ${set.l1?`<div class="ls-opt-head">Playback</div>`:''}
        <div><span>After a ${unit.toLowerCase()}</span>${chip('chain','next','next one')}${chip('chain','loop','again')}${chip('chain','stop','stop')}</div>
        <div><span>At the end</span>${chip('end','loop','start over')}${chip('end','stop','stop')}</div>
        <div><span>Pause on lookup</span>${chip('pause',true,'on')}${chip('pause',false,'off')}</div>
        ${set.roman?`<div><span>Jyutping</span>${chip('roman','show','show')}${chip('roman','after','after hearing')}${chip('roman','hide','hide')}</div>
        <div><span>Word gloss</span>${chip('gloss',true,'show')}${chip('gloss',false,'hide')}</div>`:''}
        ${set.lang==='ja'?`<div><span>Pitch accent</span>${chip('accent',true,'show')}${chip('accent',false,'hide')}</div>`:''}`;
      f('opts').querySelectorAll('[data-o]').forEach(b=>b.onclick=()=>{
        const k=b.dataset.o,raw=b.dataset.v;opts[k]=raw==='true'?true:raw==='false'?false:isNaN(+raw)?raw:+raw;
        saveOpts();paintOpts();paintText();showOpts();audio.playbackRate=opts.speed;
        if(k==='accent'||k==='roman'||k==='gloss')paint();
        if(['repeats','l1repeats','sayPause','thinkPause','gap'].includes(k)){steps=buildSteps(queue[i].it);si=Math.min(si,steps.length);}
        paintLoop();
      });
    }
    function paintText(){
      const {it}=queue[i];
      const mode=set.l1?opts.mode||'passive':'plain';
      // Production: the Mandarin prompt shows, the Cantonese once it's been said. Comprehension: nothing until the
      // Mandarin answer. Otherwise the Text / Translation options.
      // The transcript shows after the first hearing of the target line in every mode (in 懂 too: the meaning, the
      // Mandarin, still waits for its turn).
      const hidden=!revealed&&(mode==='produce'||mode==='comprehend'?!heard:opts.text==='hide'||(opts.text==='after'&&!heard));
      f('text').classList.toggle('hidden',hidden);
      f('tr').hidden=!it.translation||(mode==='produce'?false:mode==='comprehend'?!heardL1:!opts.tr||hidden);
      const romanHidden=hidden||opts.roman==='after'&&!heard&&!revealed;
      f('roman').hidden=romanHidden;f('gloss').hidden=romanHidden;
      f('text').classList.toggle('roman-hidden',romanHidden);
    }
    function paint(){
      const {g,it}=queue[i];
      const r=runs[i];
      f('pos').textContent=r&&chained?`${unit} ${r.n+1} / ${runCount} · line ${r.k+1} / ${r.len} · ${i+1} / ${queue.length}`:`${i+1} / ${queue.length}`;
      // (a line titled like its group — a particle, a topic — doesn't repeat it)
      const sameTitle=it.title===g.name;
      f('who').innerHTML=`${g.icon?`<img src="${url(set.id,g.icon)}" alt="">`:''}<span><b>${esc(g.name)}</b>${sameTitle?'':` · ${esc(it.title)}`}${it.titleEn||g.nameEn?`<small>${esc(g.nameEn||'')}${it.titleEn&&!sameTitle?` · ${esc(it.titleEn)}`:''}</small>`:''}</span>`;
      const romanOn=set.roman&&opts.roman!=='hide';
      f('text').innerHTML=set.roman&&it.words&&it.words.length&&romanOn?wordsHtml(it):window.tappableText?tappableText(it.text):esc(it.text);
      f('text').classList.toggle('acc',set.lang==='ja'&&!!opts.accent||!!(romanOn&&it.words&&it.words.length));
      // A line without word-by-word jyutping has it underneath; the gloss (I · be · Fung) under that.
      f('roman').innerHTML=romanOn&&it.roman&&!(it.words&&it.words.length)?romanHtml(it.roman):'';
      f('gloss').innerHTML=set.roman&&opts.gloss!==false&&it.words&&it.words.some(w=>w.g)?it.words.filter(w=>/\S/.test(w.w)&&!/^[。，？！、…「」,.?!]+$/.test(w.w)).map(w=>`<span><b>${esc(w.w)}</b>${esc(w.g||'')}</span>`).join(''):'';
      if(set.lang==='ja'&&opts.accent){const at=i;accents([it.text],set.id).then(r=>{if(at===i&&el.isConnected)f('text').innerHTML=accentHtml(it.text,r[0]);}).catch(e=>toast(e.message));}
      f('tr').textContent=it.translation||'';
      f('note').textContent=it.note||'';
      f('bar').style.width=`${(i+1)/queue.length*100}%`;
      paintText();
    }
    function paintPlay(){el.querySelector('[data-a="toggle"]').innerHTML=playing?'<svg viewBox="0 0 24 24"><path d="M8 5h3v14H8zM13 5h3v14h-3z" fill="currentColor"/></svg>':'<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z" fill="currentColor"/></svg>';}

    // ---------- playback: each line is a sequence of steps, set by the mode ----------
    // plain: Cantonese ×N · passive: Mandarin → Cantonese ×N · produce: Mandarin → pause to say it → Cantonese ·
    // comprehend: Cantonese → pause to understand → Mandarin. Between hearings, the pause to repeat.
    let steps=[],si=0,heardL1=false,revealed=false;
    const hasL1=it=>!!it.l1audio;
    const repeatWait=it=>{const cap=opts.gap>1.5?14:opts.gap>=1?9:4;return (opts.gap?Math.min((it.dur||2)*opts.gap/opts.speed,cap)+(opts.gap<1?0.3:0.6):0.7)*1000;};
    function buildSteps(it){
      const T={play:'target'},mode=set.l1?opts.mode||'passive':'plain';
      const reps=[];for(let r=0;r<Math.max(1,opts.repeats);r++){if(r)reps.push({wait:repeatWait(it)});reps.push(T);}
      // The translation language: heard ×1–2 (a short breath between), at its own speed.
      const Ls=[];for(let r=0;r<Math.max(1,opts.l1repeats||1);r++){if(r)Ls.push({wait:500});Ls.push({play:'l1'});}
      // 說: time to say it (auto: from the line's length, 4–6 s); 懂: time to understand it (auto: 3–4 s).
      const say=opts.sayPause==='auto'||!opts.sayPause?Math.round(Math.min(6000,Math.max(4000,(it.dur||2)*1500+1500))):opts.sayPause*1000;
      const think=opts.thinkPause==='auto'||!opts.thinkPause?Math.round(Math.min(4000,Math.max(3000,(it.dur||2)*800+2000))):opts.thinkPause*1000;
      if(mode==='passive')return (hasL1(it)?Ls.concat([{wait:600}]):[]).concat(reps,[{wait:repeatWait(it)}]);
      if(mode==='produce')return Ls.concat([{wait:say,say:true}],reps,[{wait:repeatWait(it)}]);
      if(mode==='comprehend')return reps.concat([{wait:think,think:true}],Ls,[{wait:900}]);
      return reps.concat([{wait:repeatWait(it)}]);
    }
    function load(n,autoplay){
      clearTimeout(timer);audio.pause();
      i=(n+queue.length)%queue.length;rep=0;heard=false;heardL1=false;revealed=false;
      steps=buildSteps(queue[i].it);si=0;
      paint();
      if(autoplay&&playing)run();
    }
    function run(){
      clearTimeout(timer);
      if(!playing||closed)return;
      if(si>=steps.length){lineDone();return;}
      const st=steps[si],it=queue[i].it;
      f('cue').textContent=st.say?'說 Say it in Cantonese':st.think?'懂 What does it mean?':'';
      if(st.wait){timer=setTimeout(()=>{si++;run();},st.wait);return;}
      const src=st.play==='l1'?it.l1audio:it.audio;
      // No recording yet (still to be voiced): about as long as it would take to say.
      if(!src){timer=setTimeout(()=>played(st),Math.max(1500,[...(st.play==='l1'?it.translation||'':it.text)].length*260));return;}
      audio.src=url(set.id,src);audio.playbackRate=st.play==='l1'?opts.l1speed||1:opts.speed;
      audio.play().catch(()=>{playing=false;paintPlay();});
    }
    function played(st){
      const {g,it}=queue[i];
      if(st.play==='target'){
        rep++;heard=true;
        const done=store.get('listen.done.'+set.id,{});(done[g.id]=done[g.id]||{})[it.id]=1;store.set('listen.done.'+set.id,done);
      }else heardL1=true;
      paintText();si++;run();
    }
    audio.addEventListener('ended',()=>{if(!closed&&steps[si]&&steps[si].play)played(steps[si]);});
    function lineDone(){
      const {g}=queue[i];
      f('cue').textContent='';
      const last=i+1>=queue.length,newChar=last||queue[i+1].g!==g;
      // This character again: back to its first line in the queue.
      if(newChar&&opts.chain==='loop'){let s0=i;while(s0>0&&queue[s0-1].g===g)s0--;load(s0,true);}
      else if(last&&opts.end!=='stop')load(0,true);                        // start over from the top
      else if(last){playing=false;paintPlay();toast('That was the last line');}
      else if(newChar&&opts.chain==='stop'){playing=false;paintPlay();load(i+1,false);toast(`End of ${g.name} · press play for ${queue[i].g.name}`);}
      else load(i+1,true);
    }
    // Pause and resume where it was (mid-clip, or at the step it had reached).
    function resume(){
      playing=true;paintPlay();
      const st=steps[si];
      if(st&&st.play&&audio.src&&audio.currentTime>0&&!audio.ended)audio.play().catch(()=>{});
      else run();
    }
    function pause(){playing=false;paintPlay();clearTimeout(timer);audio.pause();}
    el.querySelector('[data-a="toggle"]').onclick=()=>playing?pause():resume();
    el.querySelector('[data-a="prev"]').onclick=()=>load(i-1,true);
    el.querySelector('[data-a="next"]').onclick=()=>load(i+1,true);
    // Tap the text: hidden → shown; a word → looked up (the line waits meanwhile).
    f('text').addEventListener('click',e=>{
      // Tapping the blurred line shows it now (whatever the mode or the Text option), until the next line.
      if(f('text').classList.contains('hidden')){revealed=true;paintText();return;}
      const w=e.target.closest('.mu-w');if(w)lookWord(w);
    });
    hoverWords(f('text'),w=>{if(!f('text').classList.contains('hidden'))lookWord(w);});
    async function lookWord(w){
      if(!window.tappableWord)return;
      const {g,it}=queue[i];
      const word=tappableWord(it.text,w);if(!word)return;
      f('text').querySelectorAll('.mu-w.on').forEach(x=>x.classList.remove('on'));
      if(w.dataset.end!=null)f('text').querySelectorAll('.mu-w').forEach(x=>{if(x.dataset.end===w.dataset.end&&+x.dataset.o>=+w.dataset.o)x.classList.add('on');});else w.classList.add('on');
      if(opts.pause&&playing){pausedByLookup=true;pause();}
      await lookupSheet(word,{context:it.text},{lang:lookupLang(set.lang),book:`${set.title} · ${g.name} · ${it.title}`,anchor:w.getBoundingClientRect(),onClose:()=>{
        f('text').querySelectorAll('.mu-w.on').forEach(x=>x.classList.remove('on'));
        if(pausedByLookup&&!sheetStack.length){pausedByLookup=false;resume();}
      }});
    }
    // A sentence card: the line, its English on the back, and the voice itself to play in review.
    const addCard=handle(async()=>{
      const {g,it}=queue[i];
      if(playing)pause();
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
      else if(e.key==='r'||e.key==='R'){const k=steps.findIndex(x=>x.play==='target');si=Math.max(0,k);playing=true;paintPlay();audio.pause();run();}
    };
    addEventListener('keydown',onKey);
    // The three ways to practise (sets with a Mandarin track): hear it, say it, understand it.
    function paintModes(){
      if(!set.l1)return;
      const m=opts.mode||'passive';
      const M=[['passive','聽','Passive','普 → 粵 → repeat'],['produce','說','Produce','普 → you say it → 粵'],['comprehend','懂','Understand','粵 → you get it → 普']];
      f('modes').innerHTML=M.map(([k,g,l,sub])=>`<button class="ls-mode ${m===k?'on':''}" data-m="${k}"><b>${g}</b><span>${l}</span><small>${sub}</small></button>`).join('');
      f('modes').querySelectorAll('[data-m]').forEach(b=>b.onclick=()=>{opts.mode=b.dataset.m;saveOpts();paintModes();load(i,playing);});
    }
    paintModes();
    paintOpts();showOpts();paintPlay();paintLoop();load(i,true);
  }

  window.openListening=openListening;
})();
