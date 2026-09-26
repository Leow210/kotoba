'use strict';
/*
 * Thai script trainer: the consonants by class, vowels, finals and tone rules in units, each with Learn (cards that
 * speak), Quiz (sound, class, which letter you heard, which tone) and Write (draw the letter from its name and sound,
 * then flip to compare). Data and audio: listening/thai-script (tools/thai/build_script.py).
 */
(function(){
  const store={get(k,d){try{const v=localStorage.getItem(k);return v===null?d:JSON.parse(v);}catch(e){return d;}},set(k,v){try{localStorage.setItem(k,JSON.stringify(v));}catch(e){}}};
  const base='/listen/thai-script/';
  const src=p=>p?base+p.split('/').map(encodeURIComponent).join('/'):'';
  let data=null,player=null;
  function play(...paths){
    const list=paths.filter(Boolean);if(!list.length)return;
    if(player)player.pause();
    let k=0;player=new Audio(src(list[0]));
    player.onended=()=>{k++;if(k<list.length){player.src=src(list[k]);player.play().catch(()=>{});}};
    player.play().catch(()=>{});
  }
  const shuffle=a=>{a=a.slice();for(let i=a.length-1;i>0;i--){const j=Math.floor(Math.random()*(i+1));[a[i],a[j]]=[a[j],a[i]];}return a;};
  const pick=(arr,n,not)=>shuffle(arr.filter(x=>!not.includes(x))).slice(0,n);
  const vowelGlyph=ch=>String(ch).replace(/–/g,'◌');// the dotted circle carries the vowel where the consonant goes
  const TONES=[['mid','mid','—'],['low','low','\\'],['falling','falling','^'],['high','high','/'],['rising','rising','v']];

  async function load(){
    if(data)return data;
    const r=await fetch(base+'script.json');
    if(!r.ok)throw new Error('The Thai script lessons aren’t on this device yet.');
    return data=await r.json();
  }
  const best=()=>store.get('thai.best',{}),learned=()=>store.get('thai.write',{});

  // ---------- units ----------
  async function openThaiScript(){
    const d=await load();
    const el=document.createElement('div');el.className='th-page';
    pushPage(el);
    function render(){
      const b=best(),w=learned();
      el.innerHTML=`<div class="bar"><button class="icon-btn" data-a="back" aria-label="Back">${icon('back')}</button><div class="title"><b>${esc(d.title)}</b><small>letters · vowels · tones</small></div></div>
        <div class="th-scroll"><p class="hint th-roman">${esc(d.romanization)}</p>
        <div class="th-classes">${Object.entries(d.classes).map(([k,c])=>`<div class="th-class ${k}"><b>${esc(c.name)} ${esc(c.th)}</b><small>${esc(c.rule)}</small></div>`).join('')}</div>
        ${d.units.map(u=>{const n=u.items.length,wn=u.items.filter(it=>w[it.ch||it.word]).length;
          return `<button class="th-unit" data-u="${u.id}"><b>${esc(u.title)}</b><small>${n} ${u.kind==='tone'?'syllables':u.kind==='vowel'?'vowels':u.kind==='syllable'?'examples':'letters'}${b[u.id]!=null?` · quiz best ${b[u.id]}/10`:''}${u.kind==='consonant'||u.kind==='vowel'?` · written ${wn}/${n}`:''}</small></button>`;}).join('')}</div>`;
      el.querySelector('[data-a="back"]').onclick=()=>popPage();
      el.querySelectorAll('[data-u]').forEach(b=>b.onclick=()=>openUnit(d,d.units.find(u=>u.id===b.dataset.u),render));
    }
    render();
  }

  // ---------- one unit: Learn · Quiz · Write ----------
  function openUnit(d,u,onBack){
    const el=document.createElement('div');el.className='th-page';
    const tabs=[['learn','Learn'],['quiz','Quiz']].concat(u.kind==='consonant'||u.kind==='vowel'?[['write','Write']]:[]);
    let tab='learn';
    el.innerHTML=`<div class="bar"><button class="icon-btn" data-a="back" aria-label="Back">${icon('back')}</button><div class="title"><b>${esc(u.title)}</b></div></div>
      <div class="th-tabs">${tabs.map(([k,l])=>`<button class="chip small" data-t="${k}">${l}</button>`).join('')}</div>
      <div class="th-scroll" data-f="body"></div>`;
    pushPage(el,{onClose:onBack});
    el.querySelector('[data-a="back"]').onclick=()=>popPage();
    const body=el.querySelector('[data-f="body"]');
    function show(){
      el.querySelectorAll('.th-tabs [data-t]').forEach(b=>b.classList.toggle('on',b.dataset.t===tab));
      ({learn,quiz,write})[tab](body,d,u);
    }
    el.querySelectorAll('.th-tabs [data-t]').forEach(b=>b.onclick=()=>{tab=b.dataset.t;show();});
    show();
  }

  function learn(body,d,u){
    const card=(it,i)=>{
      if(u.kind==='consonant')return `<button class="th-card ${it.cls}${it.rare?' rare':''}" data-i="${i}"><span class="th-glyph">${esc(it.ch)}</span><b>${esc(it.name)}</b>
        <small>${esc(it.init)} · final ${esc(it.final)}</small><span class="th-word">${esc(it.word)} <i>${esc(it.rom)}</i></span><small>${esc(it.mean)}${it.rare?' · rare':''}</small></button>`;
      if(u.kind==='vowel')return `<button class="th-card vowel ${it.len}" data-i="${i}"><span class="th-glyph">${esc(vowelGlyph(it.ch))}</span><b>${esc(it.sound)}</b><small>${esc(it.len)}</small>
        <span class="th-word">${esc(it.word)} <i>${esc(it.rom)}</i></span><small>${esc(it.mean)}</small></button>`;
      return `<button class="th-card tone t-${it.tone}" data-i="${i}"><span class="th-glyph small">${esc(it.word)}</span><b>${esc(it.rom)}</b><small class="th-tone-name">${esc(it.tone)} tone</small>
        <small>${esc(it.why||it.mean||'')}</small>${it.mean&&it.why?`<small>${esc(it.mean)}</small>`:''}</button>`;
    };
    body.innerHTML=`<p class="th-intro">${esc(u.intro)}</p>
      ${u.marks?`<div class="th-marks">${u.marks.map((m,i)=>`<button class="th-mark" data-m="${i}"><span class="th-glyph">◌${esc(m.ch)}</span><b>${esc(m.name)}</b><small>${esc(m.rom)}</small><small>${esc(m.rule)}</small></button>`).join('')}</div>`:''}
      ${u.kind==='tone'||u.kind==='syllable'?`<div class="th-legend">${TONES.map(([k])=>`<span class="t-${k}">${k}</span>`).join('')}</div>`:''}
      <div class="th-grid">${u.items.map(card).join('')}</div>`;
    body.querySelectorAll('[data-i]').forEach(b=>b.onclick=()=>{const it=u.items[+b.dataset.i];play(it.nameAudio,it.wordAudio);});
    body.querySelectorAll('[data-m]').forEach(b=>b.onclick=()=>play(u.marks[+b.dataset.m].nameAudio));
  }

  // ---------- quiz: ten questions from this unit ----------
  function questions(u){
    const qs=[];
    for(const it of shuffle(u.items).slice(0,10)){
      if(u.kind==='consonant'){
        const kind=['sound','class','hear'][Math.floor(Math.random()*3)];
        if(kind==='sound'){const others=pick([...new Set(u.items.map(x=>x.init))],3,[it.init]);qs.push({prompt:`<span class="th-glyph big ${it.cls}">${esc(it.ch)}</span>`,ask:'Which sound does it start a syllable with?',opts:shuffle([it.init,...others]),answer:it.init,after:it});}
        else if(kind==='class')qs.push({prompt:`<span class="th-glyph big">${esc(it.ch)}</span>`,ask:'Which class?',opts:['mid','high','low'],answer:it.cls,after:it});
        else{const others=pick(u.items.map(x=>x.ch),3,[it.ch]);qs.push({prompt:`<button class="th-listen" data-play>${icon('play')} Listen</button>`,ask:'Which letter did you hear?',opts:shuffle([it.ch,...others]),answer:it.ch,glyphs:true,audio:[it.nameAudio],after:it});}
      }else if(u.kind==='vowel'){
        if(Math.random()<0.5){const others=pick([...new Set(u.items.map(x=>x.sound))],3,[it.sound]);qs.push({prompt:`<span class="th-glyph big">${esc(vowelGlyph(it.ch))}</span>`,ask:'Which vowel sound?',opts:shuffle([it.sound,...others]),answer:it.sound,after:it});}
        else{const others=pick(u.items.map(x=>x.ch),3,[it.ch]);qs.push({prompt:`<button class="th-listen" data-play>${icon('play')} Listen</button>`,ask:`Which vowel is in the word you heard? (${esc(it.mean)})`,opts:shuffle([it.ch,...others]).map(vowelGlyph),answer:vowelGlyph(it.ch),glyphs:true,audio:[it.wordAudio],after:it});}
      }else{
        qs.push({prompt:`<span class="th-glyph big">${esc(it.word)}</span>`,ask:'Which tone?',opts:TONES.map(t=>t[0]),answer:it.tone,after:it,audio:[it.wordAudio],playAfter:true});
      }
    }
    return qs;
  }
  function quiz(body,d,u){
    const qs=questions(u);let k=0,score=0;
    function ask(){
      if(k>=qs.length){
        const b=best();b[u.id]=Math.max(b[u.id]||0,score);store.set('thai.best',b);
        body.innerHTML=`<div class="th-result"><b>${score} / ${qs.length}</b><small>${score===qs.length?'Perfect!':score>=8?'Nearly there.':'Have another go — the Learn tab speaks each one.'}</small><button class="btn primary" data-again>Again</button></div>`;
        body.querySelector('[data-again]').onclick=()=>quiz(body,d,u);return;
      }
      const q=qs[k];
      body.innerHTML=`<div class="th-quiz"><small class="th-count">${k+1} / ${qs.length}</small><div class="th-prompt">${q.prompt}</div><p>${q.ask}</p>
        <div class="th-opts ${q.glyphs?'glyphs':''}">${q.opts.map(o=>`<button class="th-opt" data-o="${esc(o)}">${esc(o)}</button>`).join('')}</div><div class="th-feedback" data-f="fb"></div></div>`;
      if(q.audio&&!q.playAfter)play(...q.audio);
      const p=body.querySelector('[data-play]');if(p)p.onclick=()=>play(...q.audio);
      body.querySelectorAll('[data-o]').forEach(b=>b.onclick=()=>{
        if(body.querySelector('.th-opt.right'))return;
        const ok=b.dataset.o===q.answer;if(ok)score++;
        body.querySelectorAll('[data-o]').forEach(x=>{if(x.dataset.o===q.answer)x.classList.add('right');else if(x===b)x.classList.add('wrong');});
        const it=q.after;
        body.querySelector('[data-f="fb"]').innerHTML=`${ok?'✓':'✗'} <b>${esc(it.ch?(u.kind==='vowel'?vowelGlyph(it.ch):it.ch):it.word)}</b> ${esc(it.name||it.sound||it.rom||'')} · ${esc(it.word||'')} ${esc(it.rom||'')}${it.why?` — ${esc(it.why)}`:''}<br><button class="btn small" data-next>Next</button>`;
        play(it.nameAudio,it.wordAudio);
        body.querySelector('[data-next]').onclick=()=>{k++;ask();};
      });
    }
    ask();
  }

  // ---------- write: draw the letter, then flip to compare ----------
  function write(body,d,u){
    let deck=shuffle(u.items),k=0;
    const w=learned();
    function next(){
      if(k>=deck.length){deck=shuffle(u.items);k=0;}
      const it=deck[k],glyph=u.kind==='vowel'?vowelGlyph(it.ch):it.ch;
      body.innerHTML=`<div class="th-write"><div class="th-ask"><b>${esc(it.name||('vowel '+it.sound))}</b><small>${u.kind==='consonant'?`${esc(it.cls)} class · ${esc(it.init)} · final ${esc(it.final)}`:`${esc(it.sound)} · ${esc(it.len)}`} · ${esc(it.word)} <i>${esc(it.rom)}</i> ${esc(it.mean)}</small>
          <button class="btn small" data-a="hear">${icon('play')} Hear</button></div>
        <div class="th-pad"><canvas data-f="pad"></canvas><div class="th-answer" data-f="answer" hidden>${esc(glyph)}</div><div class="th-guide" data-f="guide" hidden>${esc(glyph)}</div></div>
        <div class="th-write-btns"><button class="btn small" data-a="clear">Clear</button><button class="btn small" data-a="guide">Trace guide</button><button class="btn primary" data-a="flip">Show answer</button></div>
        <div class="th-write-btns" data-f="grade" hidden><button class="btn" data-a="again">Again</button><button class="btn primary" data-a="got">Got it</button></div>
        <small class="hint">${Object.keys(w).filter(x=>u.items.some(i=>(i.ch||i.word)===x)).length} of ${u.items.length} written correctly so far</small></div>`;
      const c=body.querySelector('[data-f="pad"]'),ctx=c.getContext('2d');
      const size=()=>{const r=c.getBoundingClientRect(),dpr=devicePixelRatio||1;c.width=r.width*dpr;c.height=r.height*dpr;ctx.scale(dpr,dpr);ctx.lineCap='round';ctx.lineJoin='round';ctx.lineWidth=Math.max(6,r.width/38);ctx.strokeStyle=getComputedStyle(document.body).getPropertyValue('--ink')||'#222';};
      size();
      let drawing=false;
      c.addEventListener('pointerdown',e=>{drawing=true;c.setPointerCapture(e.pointerId);const r=c.getBoundingClientRect();ctx.beginPath();ctx.moveTo(e.clientX-r.left,e.clientY-r.top);});
      c.addEventListener('pointermove',e=>{if(!drawing)return;const r=c.getBoundingClientRect();ctx.lineTo(e.clientX-r.left,e.clientY-r.top);ctx.stroke();});
      c.addEventListener('pointerup',()=>drawing=false);c.addEventListener('pointercancel',()=>drawing=false);
      const $b=a=>body.querySelector(`[data-a="${a}"]`);
      $b('hear').onclick=()=>play(it.nameAudio,it.wordAudio);
      $b('clear').onclick=()=>{ctx.clearRect(0,0,c.width,c.height);};
      $b('guide').onclick=()=>{const g=body.querySelector('[data-f="guide"]');g.hidden=!g.hidden;};
      $b('flip').onclick=()=>{body.querySelector('[data-f="answer"]').hidden=false;body.querySelector('[data-f="grade"]').hidden=false;$b('flip').hidden=true;play(it.nameAudio);};
      $b('again').onclick=()=>{deck.splice(Math.min(deck.length,k+3),0,it);k++;next();};
      $b('got').onclick=()=>{const m=learned();m[it.ch||it.word]=1;store.set('thai.write',m);k++;next();};
    }
    next();
  }

  window.openThaiScript=()=>openThaiScript().catch(e=>toast(e.message));
})();
