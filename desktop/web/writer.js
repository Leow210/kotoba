'use strict';
/*
 * Write (Mac): a small word processor for writing in the language you're learning. Documents are HTML files in
 * ~/Documents/Kotoba. Beside the page: the dictionary (for the selected word), a 類語 brainstormer (type a word, browse
 * the thesaurus entries) and a grammar check by the local Gemma model. Vertical writing and furigana (<ruby>).
 */
(function(){
  const mac=(msg)=>{try{window.webkit.messageHandlers.kotoba.postMessage(msg);}catch(e){}};
  const store={get(k,d){try{const v=localStorage.getItem(k);return v===null?d:JSON.parse(v);}catch(e){return d;}},set(k,v){try{localStorage.setItem(k,JSON.stringify(v));}catch(e){}}};

  // ---------- tab and screen ----------
  const videoBtn=document.querySelector('.tabbar [data-tab="video"]')||document.querySelector('.tabbar [data-tab="reader"]');
  const writeBtn=document.createElement('button');writeBtn.dataset.tab='write';
  writeBtn.innerHTML=`<svg class="i" viewBox="0 0 24 24"><path d="M4 20h4L19 9l-4-4L4 16z"/><path d="M13.5 6.5l4 4"/></svg>Write`;
  videoBtn.after(writeBtn);
  writeBtn.onclick=()=>showTab('write');
  const screen=document.createElement('section');screen.className='screen';screen.id='screen-write';screen.hidden=true;
  (document.getElementById('screen-video')||document.getElementById('screen-reader')).after(screen);
  const originalShow=window.showTab;
  window.showTab=showTab=function(name){
    if(tab==='write'&&name!=='write')saveNow();
    originalShow(name);
    if(name==='write'&&!current)renderHome();
  };

  let current=null;// {id,title,vertical,lang,dirty}

  // ---------- documents ----------
  const fmtDate=(t)=>{const d=new Date(t),now=new Date();return d.toDateString()===now.toDateString()?d.toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'}):d.toLocaleDateString();};
  async function renderHome(){
    current=null;
    const docs=await api('doc.list').catch(()=>[]);
    screen.classList.remove('editing');
    screen.innerHTML=`<div class="head head-row"><div><h1>Write</h1><p class="sub">Documents in ~/Documents/Kotoba, with the dictionary, 類語 and a grammar check beside the page</p></div>
        <div><button class="btn small" data-a="folder">Show in Finder</button> <button class="btn small primary" data-a="new">＋ New document</button></div></div>
      <div class="scroll"><div class="wr-home">${docs.length?docs.map((d,i)=>`<button class="wr-card" data-i="${i}"><b>${esc(d.title)}</b><span class="wr-prev${d.vertical?' v':''}">${esc(d.preview)||'<i>Empty</i>'}</span><small>${fmtDate(d.updated)}</small></button>`).join('')
        :`<div class="empty"><span class="glyph">書</span><h2>Write something</h2>A diary entry, a message, an essay. Select a word to see it in your dictionaries, brainstorm alternatives in 類語例解, and let Gemma check the grammar.</div>`}</div></div>`;
    screen.querySelector('[data-a="new"]').onclick=handle(()=>openDoc(null));
    screen.querySelector('[data-a="folder"]').onclick=handle(async()=>{const r=await api('doc.folder');mac({type:'reveal',path:r.path});});
    screen.querySelectorAll('[data-i]').forEach(b=>{
      b.onclick=handle(()=>openDoc(docs[+b.dataset.i].id));
      b.oncontextmenu=handle(async e=>{e.preventDefault();const d=docs[+b.dataset.i];
        if(await confirm2('Delete “'+d.title+'”?','It moves to the .trash folder in ~/Documents/Kotoba.','Delete',true)){await api('doc.delete',{id:d.id});renderHome();}});
    });
  }

  const FONT_SIZES=[15,16,17,18,20,22,24,28];
  async function openDoc(id){
    const d=id?await api('doc.get',{id}):{id:'',title:'',html:'',vertical:false,lang:'ja'};
    current={id:d.id,title:d.title,vertical:d.vertical,lang:d.lang||'ja',dirty:false,saving:null};
    screen.classList.add('editing');
    screen.innerHTML=`<div class="wr">
      <div class="wr-main">
        <div class="wr-bar">
          <button class="icon-btn" data-a="home" title="All documents">${icon('back')}</button>
          <input class="wr-title" placeholder="Title" value="${esc(d.title)}" maxlength="80">
          <div class="wr-tools">
            <button data-cmd="bold" title="Bold (⌘B)"><b>B</b></button><button data-cmd="italic" title="Italic (⌘I)"><i>I</i></button><button data-cmd="underline" title="Underline (⌘U)"><u>U</u></button>
            <span class="sep"></span>
            <button data-block="h2" title="Heading">見出し</button><button data-block="p" title="Body text">本文</button><button data-cmd="insertUnorderedList" title="List">•</button>
            <span class="sep"></span>
            <button data-a="ruby" title="Furigana for the selected word (⌘R)">ルビ</button><button data-a="vertical" title="Vertical writing (縦書き)">縦</button>
            <span class="sep"></span>
            <button data-a="smaller" title="Smaller text">A−</button><button data-a="bigger" title="Bigger text">A+</button>
          </div>
        </div>
        <div class="wr-scroll"><div class="wr-doc" contenteditable="true" spellcheck="false" lang="${esc(current.lang)}"></div></div>
        <div class="wr-foot"><span class="wr-status"></span><span class="wr-count"></span></div>
      </div>
      <aside class="wr-side">
        <div class="wr-tabs"><button data-p="dict">辞書</button><button data-p="thes">類語</button><button data-p="gram">文法</button></div>
        <div class="wr-panel" data-panel="dict"></div>
        <div class="wr-panel" data-panel="thes"></div>
        <div class="wr-panel" data-panel="gram"></div>
      </aside>
    </div>`;
    const ed=editor();
    ed.innerHTML=d.html||'<p><br></p>';
    applyLayout();
    wireEditor(ed);
    buildPanels();
    showPanel(store.get('write.panel','dict'));
    updateCount();
    if(!id)screen.querySelector('.wr-title').focus();else placeCaretEnd(ed);
  }
  const editor=()=>screen.querySelector('.wr-doc');
  function applyLayout(){
    const ed=editor();if(!ed)return;
    ed.classList.toggle('vertical',!!current.vertical);
    screen.querySelector('.wr-scroll').classList.toggle('vertical',!!current.vertical);
    ed.style.fontSize=store.get('write.fontSize',18)+'px';
    screen.querySelector('[data-a="vertical"]').classList.toggle('on',!!current.vertical);
    // Vertical text starts at the right edge.
    if(current.vertical){const s=screen.querySelector('.wr-scroll');requestAnimationFrame(()=>s.scrollLeft=s.scrollWidth);}
  }
  function placeCaretEnd(el){el.focus();const r=document.createRange();r.selectNodeContents(el);r.collapse(false);const s=getSelection();s.removeAllRanges();s.addRange(r);}

  // ---------- saving ----------
  const setStatus=(t)=>{const s=screen.querySelector('.wr-status');if(s)s.textContent=t;};
  function markDirty(){if(!current)return;current.dirty=true;setStatus('Editing…');saveSoon();updateCount();}
  const saveSoon=debounce(()=>saveNow(),1200);
  async function saveNow(){
    if(!current||!current.dirty)return;
    const ed=editor();if(!ed)return;
    const title=(screen.querySelector('.wr-title').value.trim())||firstLine(ed)||'Untitled';
    const text=plainText();
    current.dirty=false;
    // Nothing written yet: don't leave empty "Untitled" files behind.
    if(!current.id&&!text.trim()&&!screen.querySelector('.wr-title').value.trim())return;
    current.lang=textLang(text,'');ed.lang=current.lang;
    try{
      const r=await api('doc.save',{id:current.id,title,html:cleanHtml(ed),vertical:!!current.vertical,lang:current.lang});
      current.id=r.id;current.title=r.title;setStatus('Saved');
    }catch(e){current.dirty=true;setStatus('Not saved: '+e.message);}
  }
  window.addEventListener('blur',()=>saveNow());
  function firstLine(ed){return (ed.innerText||'').trim().split('\n')[0].slice(0,40);}
  function cleanHtml(ed){
    const c=ed.cloneNode(true);
    c.querySelectorAll('mark.wr-hit').forEach(m=>m.replaceWith(...m.childNodes));
    return c.innerHTML.replace(/<p><br><\/p>$/,'');
  }
  /** The text without furigana (the ruby text would read as part of the sentence). */
  function plainText(){
    const ed=editor();if(!ed)return '';
    ed.classList.add('no-rt');const t=ed.innerText;ed.classList.remove('no-rt');
    return t.replace(/ /g,' ');
  }
  function updateCount(){
    const n=plainText().replace(/\s/g,'').length;
    const c=screen.querySelector('.wr-count');if(c)c.textContent=`${n.toLocaleString()} 字`;
  }

  // ---------- the editor ----------
  function wireEditor(ed){
    const title=screen.querySelector('.wr-title');
    title.addEventListener('input',markDirty);
    title.addEventListener('keydown',e=>{if(e.key==='Enter'){e.preventDefault();placeCaretEnd(ed);}});
    ed.addEventListener('input',markDirty);
    // Pasted text arrives plain (no web page styles).
    ed.addEventListener('paste',e=>{
      const text=e.clipboardData&&e.clipboardData.getData('text/plain');if(text==null)return;
      e.preventDefault();document.execCommand('insertText',false,text);
    });
    ed.addEventListener('keydown',e=>{
      if(e.metaKey&&e.key==='s'){e.preventDefault();current.dirty=true;saveNow();}
      if(e.metaKey&&e.key==='r'){e.preventDefault();handle(furigana)();}
    });
    screen.querySelector('[data-a="home"]').onclick=handle(async()=>{await saveNow();renderHome();});
    screen.querySelectorAll('[data-cmd]').forEach(b=>b.onmousedown=e=>{e.preventDefault();document.execCommand(b.dataset.cmd);markDirty();});
    screen.querySelectorAll('[data-block]').forEach(b=>b.onmousedown=e=>{e.preventDefault();document.execCommand('formatBlock',false,b.dataset.block);markDirty();});
    screen.querySelector('[data-a="ruby"]').onmousedown=e=>{e.preventDefault();handle(furigana)();};
    screen.querySelector('[data-a="vertical"]').onclick=()=>{current.vertical=!current.vertical;applyLayout();markDirty();};
    const size=(d)=>{const i=Math.max(0,Math.min(FONT_SIZES.length-1,FONT_SIZES.indexOf(store.get('write.fontSize',18))+d));store.set('write.fontSize',FONT_SIZES[i]);applyLayout();};
    screen.querySelector('[data-a="smaller"]').onclick=()=>size(-1);
    screen.querySelector('[data-a="bigger"]').onclick=()=>size(1);
  }
  let lastSelected='';
  const onEditorSelection=debounce(()=>{
    const ed=editor();if(!ed||tab!=='write')return;
    const sel=getSelection();if(!sel.rangeCount||!ed.contains(sel.anchorNode))return;
    const text=sel.toString().trim();
    if(!text||text.length>30||/\n/.test(text)||text===lastSelected)return;
    lastSelected=text;
    if(panel==='thes')brainstorm(text);else if(panel==='dict')dictLookup(text);
  },350);
  // The selected word goes to the side panel (dictionary, or 類語 when that's open).
  document.addEventListener('selectionchange',onEditorSelection);
  // For tools/mac.py tests.
  window.__writer={readingOf:(t)=>readingOf(t),dictLookup:(t)=>dictLookup(t),brainstorm:(t)=>brainstorm(t),checkGrammar:(t)=>checkGrammar(t),selectText:(a,b)=>selectText(a,b),state:()=>({current,panel,lastSelected})};

  /** Furigana: the selected word gets a reading (the dictionary's, to confirm or change); okurigana stay outside. */
  async function furigana(){
    const ed=editor();const sel=getSelection();
    if(!sel.rangeCount||!ed.contains(sel.anchorNode)){toast('Select a word in the text first');return;}
    const range=sel.getRangeAt(0);
    const inRuby=(n)=>{n=n&&n.nodeType===3?n.parentElement:n;return n&&n.closest&&n.closest('ruby');};
    const ruby=inRuby(range.startContainer);
    if(ruby&&ed.contains(ruby)){
      // Furigana already there: change it, or clear it to remove.
      const base=[...ruby.childNodes].filter(n=>!(n.nodeType===1&&/^(RT|RP)$/.test(n.tagName))).map(n=>n.textContent).join('');
      const rt=ruby.querySelector('rt');
      const r=await prompt2('Furigana for '+base,rt?rt.textContent:'','Leave empty to remove');
      if(r===null)return;
      if(!r){ruby.replaceWith(document.createTextNode(base));}else if(rt)rt.textContent=r;
      markDirty();return;
    }
    const text=sel.toString();
    if(!text.trim()){toast('Select a word in the text first');return;}
    if(text.length>20||/\n/.test(text)){toast('Select one word at a time for furigana');return;}
    const saved=range.cloneRange();
    const guess=await readingOf(text.trim()).catch(()=>'');
    const reading=await prompt2('Furigana for '+text.trim(),guess,'ふりがな');
    if(!reading)return;
    // 考える + かんがえる → 考(かんが)える: the shared kana ending stays plain text.
    let base=text.trim(),read=reading.trim(),tail='';
    while(base.length>1&&read.length>1&&/[぀-ゟ]/.test(base.slice(-1))&&kata2hira(base.slice(-1))===kata2hira(read.slice(-1))){tail=base.slice(-1)+tail;base=base.slice(0,-1);read=read.slice(0,-1);}
    const s=getSelection();s.removeAllRanges();s.addRange(saved);ed.focus();
    document.execCommand('insertHTML',false,`<ruby>${esc(base)}<rt>${esc(read)}</rt></ruby>${esc(tail)}`);
    markDirty();
  }
  const kata2hira=(s)=>s.replace(/[ァ-ヶ]/g,c=>String.fromCharCode(c.charCodeAt(0)-0x60));
  /** The reading a Japanese dictionary gives for this spelling (the first headword that is exactly it). */
  async function readingOf(word){
    const r=await api('search',{q:word,mode:'headword',dict:'',offset:0});
    // The reading most Japanese dictionaries give (one sub-entry can be filed under another word: 素人考え under 考え).
    const votes=new Map();
    for(const it of r.items||[]){
      if(!it.exact||((dictById(it.dict)||{}).grp||'Japanese')!=='Japanese')continue;
      const p=kata2hira(String(it.page||'').replace(/[‐・\-=＝\s]/g,''));
      if(/^[\u3040-\u309fー]+$/.test(p))votes.set(p,(votes.get(p)||0)+1);
    }
    const best=[...votes].sort((x,y)=>y[1]-x[1])[0];
    if(best)return best[0];
    return '';
  }

  // ---------- side panel ----------
  let panel='dict';
  function showPanel(name){
    panel=name;store.set('write.panel',name);
    screen.querySelectorAll('.wr-tabs [data-p]').forEach(b=>b.classList.toggle('on',b.dataset.p===name));
    screen.querySelectorAll('.wr-panel').forEach(p=>p.hidden=p.dataset.panel!==name);
    const sel=getSelection();const ed=editor();
    const text=sel.rangeCount&&ed&&ed.contains(sel.anchorNode)?sel.toString().trim():'';
    if(text&&text.length<=30){lastSelected=text;if(name==='thes')brainstorm(text);if(name==='dict')dictLookup(text);}
  }
  function buildPanels(){
    screen.querySelectorAll('.wr-tabs [data-p]').forEach(b=>b.onclick=()=>showPanel(b.dataset.p));
    const P=(n)=>screen.querySelector(`[data-panel="${n}"]`);
    P('dict').innerHTML=`<div class="wr-search"><input type="search" placeholder="Look up a word" data-q="dict"></div><div class="wr-out" data-out="dict"><p class="wr-hint">Select a word in your text, or type one here.</p></div>`;
    P('thes').innerHTML=`<div class="wr-search"><input type="search" placeholder="類語: type a word (思う, 美しい…)" data-q="thes"></div><div class="wr-out" data-out="thes"><p class="wr-hint">Type a word, or select one in your text, to see its entries in your thesaurus dictionaries (${esc(thesaurusGroups().map(g=>g.split('/')[0]).join(', ')||'none found')}). Double-click a word in an entry to brainstorm from it; select one and press <b>Use</b> to put it in your text.</p></div>`;
    P('gram').innerHTML=`<div class="wr-gram-actions"><button class="btn small primary" data-a="check-all">Check the document</button><button class="btn small" data-a="check-sel">Check selection</button></div><div class="wr-out" data-out="gram"><p class="wr-hint">Gemma 4 (on this Mac) reads your text for grammar, particles, conjugation, spelling and unnatural phrasing. Nothing leaves your computer. The first check loads the model (about a minute).</p></div>`;
    const dq=P('dict').querySelector('input'),tq=P('thes').querySelector('input');
    dq.addEventListener('keydown',e=>{if(e.key==='Enter'&&dq.value.trim())dictLookup(dq.value.trim());});
    tq.addEventListener('keydown',e=>{if(e.key==='Enter'&&tq.value.trim())brainstorm(tq.value.trim());});
    P('gram').querySelector('[data-a="check-all"]').onclick=handle(()=>checkGrammar(plainText()));
    P('gram').querySelector('[data-a="check-sel"]').onclick=handle(()=>{
      const sel=getSelection();const ed=editor();
      const t=sel.rangeCount&&ed.contains(sel.anchorNode)?sel.toString():'';
      if(!t.trim()){toast('Select some text first');return;}
      return checkGrammar(t);
    });
  }

  /** An entry page in the side panel, focused on the word like the look-up sheet. */
  async function entryFrame(box,it,key,opts={}){
    const frame=document.createElement('iframe');frame.className='wr-frame';box.appendChild(frame);
    await new Promise(res=>{frame.onload=res;frame.src=`/d/${it.dict}/${it.rec}.entry`;});
    const wired=frameSetup(frame,{dict:it.dict,autoHeight:true,min:60,zoom:0.95,
      // In the brainstormer a linked word (思う in 考える's entry) becomes the next word to brainstorm.
      onLink:async(href,a)=>{
        if(opts.onWord&&a&&a.textContent.trim()&&a.textContent.trim().length<=20&&!href.startsWith('#')){opts.onWord(a.textContent.trim().replace(/[\[［].*$/,''));return;}
        const ref=await api('reference',{dict:it.dict,ref:href});if(ref.rec)openEntry({rec:ref.rec,dict:it.dict,key:ref.key||'',anchor:ref.anchor||''});},
      source:()=>({dict:it.dict,dictName:it.dictionary,key,page:it.page})});
    if(!wired)return frame;
    if(opts.focus!==false){const f=findFocus(wired.doc,key,'');if(!kanjiHead(it.dict,key))applyFocus(wired.doc,f.focus,f.units);}
    // Double-click a word in a thesaurus entry: brainstorm from that word.
    if(opts.onWord)wired.doc.addEventListener('dblclick',()=>{const w=wired.doc.getSelection().toString().trim();if(w&&w.length<=20)setTimeout(()=>opts.onWord(w),10);});
    frame.kotobaDoc=wired.doc;
    setTimeout(()=>wired.resize(),40);setTimeout(()=>wired.resize(),400);
    return frame;
  }

  let dictToken=0;
  async function dictLookup(text){
    const out=screen.querySelector('[data-out="dict"]');if(!out)return;
    const q=screen.querySelector('[data-q="dict"]');if(q&&document.activeElement!==q)q.value=text;
    const token=++dictToken;
    const r=await api('lookup',{text,lang:''}).catch(()=>({items:[]}));
    if(token!==dictToken)return;
    if(!r.items.length){out.innerHTML=`<p class="wr-hint">No headword for “${esc(text)}”.</p>`;return;}
    out.innerHTML=`<div class="wr-word"><b>${esc(r.key)}</b>${r.explain?`<small>${esc(r.matched)} → ${esc(r.key)} · ${esc(r.explain)}</small>`:''}</div>
      <div class="wr-chips">${r.items.map((it,i)=>`<button class="chip small${i?'':' on'}" data-t="${i}">${esc(shortName(it.dictionary))}${r.items.filter(x=>x.dict===it.dict).length>1&&it.page?' · '+esc(String(it.page).slice(0,8)):''}</button>`).join('')}</div>
      <div class="wr-entry"></div>
      <div class="wr-row-actions"><button class="btn small" data-a="open">${icon('book')} Open</button><button class="btn small" data-a="save">${icon('star')} Save</button><button class="btn small" data-a="thes">類語</button></div>`;
    let index=0;
    const show=async(i)=>{index=i;out.querySelectorAll('[data-t]').forEach(b=>b.classList.toggle('on',+b.dataset.t===i));const box=out.querySelector('.wr-entry');box.innerHTML='';await entryFrame(box,r.items[i],r.key);};
    out.querySelectorAll('[data-t]').forEach(b=>b.onclick=()=>show(+b.dataset.t));
    out.querySelector('[data-a="open"]').onclick=()=>openEntry({...r.items[index],key:r.key,alternatives:r.items});
    out.querySelector('[data-a="save"]').onclick=handle(()=>saveLookupResult(r.items[index],r.key,sentenceOf(text),r.items,true,current&&current.title?'✎ '+current.title:''));
    out.querySelector('[data-a="thes"]').onclick=()=>{showPanel('thes');brainstorm(r.key);};
    await show(0);
  }
  function sentenceOf(word){const t=plainText();const i=t.indexOf(word);if(i<0)return '';const a=t.lastIndexOf('。',i)+1,b=t.indexOf('。',i);return t.slice(a,b<0?undefined:b+1).trim().slice(0,300);}

  // ---------- 類語 brainstormer ----------
  function thesaurusGroups(){return [...new Set(dicts.filter(d=>d.enabled&&/\/類語$/.test(d.grp||'')).map(d=>d.grp))];}
  let thesToken=0;const thesHistory=[];
  async function brainstorm(word){
    const out=screen.querySelector('[data-out="thes"]');if(!out)return;
    const q=screen.querySelector('[data-q="thes"]');if(q&&document.activeElement!==q)q.value=word;
    const token=++thesToken;
    out.innerHTML='<p class="wr-hint">Looking…</p>';
    const groups=thesaurusGroups();
    if(!groups.length){out.innerHTML='<p class="wr-hint">No thesaurus dictionary found. Put one (e.g. 類語例解辞典) in a “類語” group in the Library.</p>';return;}
    // A conjugated word (考えた) is looked up as its dictionary form.
    const base=await api('lookup',{text:word,lang:''}).then(r=>r.items.length&&r.matched===word?r.key:word).catch(()=>word);
    const heads=[],seen=new Set();
    for(const g of groups){
      const r=await api('search',{q:base,mode:'headword',dict:'g:'+g,offset:0}).catch(()=>({items:[]}));
      for(const it of r.items||[])if(!seen.has(it.rec)&&(it.exact||heads.length<3)){seen.add(it.rec);heads.push(it);}
    }
    // Entries that list the word among others (a 類語 group), from the full text.
    const mentions=[];
    for(const g of groups){
      const r=await api('search',{q:base,mode:'definition',dict:'g:'+g,offset:0}).catch(()=>({items:[]}));
      // Real entries only: one-character pages (が, だ: the dictionary's own index pages) aren't 類語 groups.
      for(const it of r.items||[]){const k=String(it.key||it.page||'').replace(/[【】\[\]（）()・\s]/g,'');if(!seen.has(it.rec)&&k.length>=2&&k!==base){seen.add(it.rec);mentions.push(it);}}
    }
    if(token!==thesToken)return;
    if(thesHistory[thesHistory.length-1]!==base)thesHistory.push(base);
    const back=thesHistory.length>1?`<button class="chip small" data-a="back">← ${esc(thesHistory[thesHistory.length-2])}</button>`:'';
    if(!heads.length&&!mentions.length){out.innerHTML=`<div class="wr-word">${back}<b>${esc(base)}</b></div><p class="wr-hint">Not in your thesaurus. Try the dictionary form or a simpler word.</p>`;wireBack(out);return;}
    out.innerHTML=`<div class="wr-word">${back}<b>${esc(base)}</b><small>${heads.length} ${heads.length===1?'entry':'entries'}${mentions.length?` · mentioned in ${mentions.length}${mentions.length>=50?'+':''}`:''}</small></div>
      <div class="wr-use" hidden><span></span><button class="btn small primary" data-a="use">Use</button><button class="btn small" data-a="go">類語 of this</button></div>
      <div class="wr-thes"></div>`;
    wireBack(out);
    const list=out.querySelector('.wr-thes');
    const all=[...heads.map(it=>({it,head:true})),...mentions.slice(0,20).map(it=>({it,head:false}))];
    all.forEach(({it,head},i)=>{
      if(!head&&(i===0||all[i-1].head)){const l=document.createElement('div');l.className='wr-sub';l.textContent=`Entries that mention ${base}`;list.appendChild(l);}
      const sec=document.createElement('details');sec.className='wr-thes-item';sec.open=head||!heads.length&&i<2;
      const title=String(it.keys||it.key||'').split('\u0001')[0]||it.page;
      sec.innerHTML=`<summary><b>${esc(title)}</b><small>${esc(shortName(it.dictionary))}${head?'':' · mentions '+esc(base)}</small></summary><div class="wr-entry"></div>`;
      list.appendChild(sec);
      let loaded=false;
      const load=()=>{if(loaded)return;loaded=true;entryFrame(sec.querySelector('.wr-entry'),it,head?base:title,{focus:head,onWord:(w)=>brainstorm(w)}).then(f=>watchFrameSelection(f,out));};
      if(sec.open)load();
      sec.addEventListener('toggle',()=>{if(sec.open)load();});
    });
  }
  function wireBack(out){
    const b=out.querySelector('[data-a="back"]');
    if(b)b.onclick=()=>{thesHistory.pop();const w=thesHistory.pop();if(w)brainstorm(w);};
  }
  /** A word selected in a thesaurus entry can replace the selection in your text (or go in at the cursor). */
  let savedRange=null;
  document.addEventListener('selectionchange',()=>{
    const ed=editor();if(!ed)return;
    const sel=getSelection();if(sel.rangeCount&&ed.contains(sel.anchorNode))savedRange=sel.getRangeAt(0).cloneRange();
  });
  function watchFrameSelection(frame,out){
    const doc=frame&&frame.kotobaDoc;if(!doc)return;
    doc.addEventListener('selectionchange',()=>{
      const w=doc.getSelection().toString().trim();
      const bar=out.querySelector('.wr-use');if(!bar)return;
      if(!w||w.length>30){return;}
      bar.hidden=false;bar.querySelector('span').textContent=w;
      bar.querySelector('[data-a="use"]').onclick=()=>useWord(w);
      bar.querySelector('[data-a="go"]').onclick=()=>brainstorm(w);
    });
  }
  function useWord(w){
    const ed=editor();if(!ed)return;
    ed.focus();
    const sel=getSelection();sel.removeAllRanges();
    if(savedRange&&ed.contains(savedRange.startContainer))sel.addRange(savedRange);else{const r=document.createRange();r.selectNodeContents(ed);r.collapse(false);sel.addRange(r);}
    document.execCommand('insertText',false,w);
    markDirty();toast('Inserted '+w,1200);
  }

  // ---------- grammar ----------
  async function checkGrammar(text){
    const out=screen.querySelector('[data-out="gram"]');
    if(!text.trim()){toast('Nothing to check yet');return;}
    const started=Date.now();
    out.innerHTML=`<p class="wr-hint wr-busy">Gemma 4 is reading ${text.replace(/\s/g,'').length.toLocaleString()} characters…</p>`;
    const tick=setInterval(()=>{const p=out.querySelector('.wr-busy');if(!p){clearInterval(tick);return;}const s=Math.round((Date.now()-started)/1000);p.textContent=`Gemma 4 is reading… ${s} s${s>15?' (loading the model the first time takes about a minute)':''}`;},1000);
    let r;
    try{r=await api('grammar.check',{text,lang:textLang(text,'')});}
    catch(e){clearInterval(tick);out.innerHTML=`<p class="wr-hint">${esc(e.message)}</p>`;return;}
    clearInterval(tick);
    const issues=r.issues;
    const render=()=>{
      const open=issues.filter(x=>!x.done);
      out.innerHTML=`<p class="wr-hint">${open.length?`${open.length} suggestion${open.length===1?'':'s'}`:'No problems found'} · ${(r.ms/1000).toFixed(1)} s. The model can be wrong: check with the dictionary when unsure.</p>`+
        issues.map((x,i)=>x.done?'':`<div class="wr-issue" data-i="${i}">
          <div class="wr-fix"><s>${esc(x.original)}</s> → <b>${esc(x.suggestion)}</b>${x.type?`<span class="tag muted">${esc(x.type)}</span>`:''}</div>
          ${x.explanation?`<p>${esc(x.explanation)}</p>`:''}
          <div class="wr-row-actions"><button class="btn small primary" data-a="apply">Apply</button><button class="btn small" data-a="show">Show</button><button class="btn small" data-a="skip">Ignore</button></div></div>`).join('');
      out.querySelectorAll('.wr-issue').forEach(el=>{
        const x=issues[+el.dataset.i];
        el.querySelector('[data-a="show"]').onclick=()=>{if(!selectText(x.original,x.context))toast('That text has changed');};
        el.querySelector('[data-a="skip"]').onclick=()=>{x.done=true;render();};
        el.querySelector('[data-a="apply"]').onclick=()=>{
          if(!selectText(x.original,x.context)){toast('That text has changed');return;}
          document.execCommand('insertText',false,x.suggestion);x.done=true;markDirty();render();
        };
      });
    };
    render();
  }
  /** Selects the text in the editor (skipping furigana), preferring the occurrence inside its context. */
  function selectText(part,context){
    const ed=editor();if(!ed)return false;
    const nodes=[];let text='';
    const walker=document.createTreeWalker(ed,NodeFilter.SHOW_TEXT,{acceptNode:n=>n.parentElement.closest('rt,rp')?NodeFilter.FILTER_REJECT:NodeFilter.FILTER_ACCEPT});
    let n;while((n=walker.nextNode())){nodes.push([n,text.length]);text+=n.nodeValue;}
    let at=-1;
    // The occurrence inside the quoted context (the same mistake can appear twice).
    if(context){const ci=indexIgnoringSpace(text,context);if(ci>=0)at=text.indexOf(part,ci);}
    if(at<0)at=text.indexOf(part);
    if(at<0)return false;
    const locate=(pos)=>{for(let i=nodes.length-1;i>=0;i--)if(nodes[i][1]<=pos)return [nodes[i][0],pos-nodes[i][1]];return [nodes[0][0],0];};
    const [sn,so]=locate(at),[en,eo]=locate(at+part.length);
    const r=document.createRange();r.setStart(sn,so);r.setEnd(en,eo);
    ed.focus();const sel=getSelection();sel.removeAllRanges();sel.addRange(r);
    const rect=r.getBoundingClientRect(),box=screen.querySelector('.wr-scroll').getBoundingClientRect();
    if(rect.top<box.top||rect.bottom>box.bottom||rect.left<box.left||rect.right>box.right)(sn.parentElement||ed).scrollIntoView({block:'center',inline:'center'});
    return true;
  }
  function indexIgnoringSpace(text,context){
    // Where context starts in text, allowing the model's copy to differ in whitespace.
    const c=context.replace(/\s+/g,'');if(!c)return -1;
    for(let start=0;start<text.length;start++){
      let i=start,j=0;
      while(i<text.length&&j<c.length){if(/\s/.test(text[i])){i++;continue;}if(text[i]!==c[j])break;i++;j++;}
      if(j===c.length)return start;
    }
    return -1;
  }
})();
