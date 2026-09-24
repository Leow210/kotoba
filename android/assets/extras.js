'use strict';
/* Word lists, dictionary appendices (furoku), Mihon backup import, and quick links on the search screen. */

// ---------- word lists ----------
on('wordlist-imported',r=>{toast(`Imported ${r.count.toLocaleString()} words`,3000);if(tab==='folders')renderFolders();openWordList(r.id);});
on('mihon-imported',r=>{
  toast(`Mihon backup: ${r.matched} of ${r.manga} series matched · ${r.chapters} chapters updated${r.unmatched.length?` · ${r.unmatched.length} not downloaded`:''}`,6000);
  if(tab==='reader'&&shelfKind==='comics')renderComicShelf();
});

(function(){
  // Vocabulary tab: the dictionaries' own selections (地名, 四字熟語, 重要語…), then imported word lists.
  const original=renderFolders;
  window.renderFolders=renderFolders=async function(){
    await original();
    const [lists,dictLists]=await Promise.all([api('wordlists').catch(()=>[]),api('dictlists').catch(()=>[])]);
    const box=document.createElement('div');
    box.innerHTML=`<div class="section-label">Word lists<button id="wl-import">＋ Import</button></div>`+
      (lists.length?lists.map(l=>`<button class="folder-row" data-wl="${l.id}"><span class="fi">${icon('text')}</span><span class="fb"><b>${esc(l.name)}</b><small>${l.count.toLocaleString()} words${l.ranked?' · ranked':''}</small></span></button>`).join(''):
        `<p class="hint" style="padding:0 18px 20px">Import frequency lists, JLPT/TOPIK lists or any TXT/CSV with one word per line (optionally rank, reading, meaning). Browse them with definitions, then turn them into a deck.</p>`);
    if(dictLists.length){
      const byDict=new Map();dictLists.forEach(l=>{if(!byDict.has(l.dict))byDict.set(l.dict,[]);byDict.get(l.dict).push(l);});
      box.innerHTML+=`<div class="section-label">From your dictionaries</div>`+[...byDict.values()].map(ls=>
        `<div class="dl-dict">${esc(shortName(ls[0].dictionary))}</div><div class="history dl-chips">${ls.map(l=>`<button class="chip" data-dl="${l.dict}:${l.index}">${esc(l.title)} <small>${l.count.toLocaleString()}</small></button>`).join('')}</div>`).join('')+'<div style="height:20px"></div>';
    }
    $('folder-list').appendChild(box);
    box.querySelector('#wl-import').onclick=()=>Kotoba.pickWordList();
    box.querySelectorAll('[data-wl]').forEach(b=>b.onclick=handle(()=>openWordList(+b.dataset.wl)));
    box.querySelectorAll('[data-dl]').forEach(b=>b.onclick=handle(()=>{const [d,i]=b.dataset.dl.split(':').map(Number);return openDictList(d,i);}));
  };
})();

/**
 * One page for both kinds of word list. src.fetch(offset,query,sort,rowsSoFar) → {rows:[{word, reading?, rank?, section?}], done}.
 * List or grid; the list's own order or あいう order.
 */
async function wordListPage(src){
  let view='list',sort='default',rows=[],shown=0,loading=false,done=false,query='',gen=0;
  try{view=localStorage.getItem('wlView')||'list';}catch(e){}
  const el=document.createElement('div');el.className='wl-page';
  el.innerHTML=`<div class="bar"><button class="icon-btn" data-a="back">${icon('back')}</button><div class="title"><b>${esc(src.title)}</b><small>${esc(src.sub)}</small></div><button class="icon-btn" data-a="sort" aria-label="Sort">${icon('sort')}</button><button class="icon-btn" data-a="view" aria-label="List or grid"></button>${src.menu?`<button class="icon-btn" data-a="more">${icon('more')}</button>`:''}</div>
    <div class="searchline"><div class="field">${icon('search')}<input data-f="q" type="search" placeholder="Find in this list" autocomplete="off"></div></div>
    <div class="chips" style="padding:2px 14px 8px" data-f="sorts"></div>
    <div class="chips wl-jump" data-f="jump" hidden></div>
    <div class="scroll" data-f="scroll"><div data-f="rows"></div></div>`;
  pushPage(el);
  const f=(n)=>el.querySelector(`[data-f="${n}"]`);
  el.querySelector('[data-a="back"]').onclick=()=>popPage();
  const viewBtn=el.querySelector('[data-a="view"]');
  const setViewIcon=()=>{viewBtn.innerHTML=icon(view==='list'?'grid':'list');viewBtn.setAttribute('aria-label',view==='list'?'Show as grid':'Show as list');};
  setViewIcon();
  viewBtn.onclick=()=>{view=view==='list'?'grid':'list';try{localStorage.setItem('wlView',view);}catch(e){}setViewIcon();render(true);};
  const renderSorts=()=>{
    f('sorts').innerHTML=[['default',src.defaultLabel||'List order'],['abc','あいう · A–Z']].map(([v,l])=>`<button class="chip small ${sort===v?'on':''}" data-v="${v}">${l}</button>`).join('');
    f('sorts').querySelectorAll('[data-v]').forEach(b=>b.onclick=()=>{if(sort===b.dataset.v)return;sort=b.dataset.v;renderSorts();reset();});
  };
  renderSorts();
  el.querySelector('[data-a="sort"]').onclick=()=>{sort=sort==='default'?'abc':'default';renderSorts();reset();};
  if(src.menu)el.querySelector('[data-a="more"]').onclick=handle(()=>src.menu(el));
  f('q').addEventListener('input',debounce(()=>{query=f('q').value.trim();reset();},200));
  const CHUNK=300;
  // The line under a word: its reading, or its hanja for Korean (가격〔價格〕 → 價格), as Monokakido shows it.
  const sub=r=>{const t=(r.reading||'').trim();if(!t||t===r.word)return '';const m=t.match(/^[〔〖（《［(\[]([^〔〖（《［(\[]*)[〕〗）》］)\]]$/);return m?m[1]:t;};
  function cell(r,i){
    const s=sub(r);
    if(view==='grid')return `<button class="wl-cell" data-i="${i}"><span>${esc(r.word)}</span>${s?`<small class="wl-sub">${esc(s)}</small>`:''}</button>`;
    return `<button class="row wl-row" data-i="${i}"><div class="hw">${r.rank!=null&&sort==='default'?`<span class="rank">${r.rank}</span>`:''}${esc(r.word)}${s?` <small class="wl-rd">${esc(s)}</small>`:''}</div></button>`;
  }
  // Rows go in chunks so a 20,000-word selection stays quick; section headers only in the list's own order.
  function render(fresh){
    const box=f('rows');
    if(fresh){box.innerHTML='';shown=0;}
    if(!rows.length){box.innerHTML='<div class="empty">No words</div>';return;}
    const until=Math.min(rows.length,shown+CHUNK);
    let html='',grid='';
    const flush=()=>{if(grid){html+=`<div class="wl-${view==='grid'?'grid':'list'}">${grid}</div>`;grid='';}};
    for(let i=shown;i<until;i++){
      const r=rows[i];
      if(sort==='default'&&!query&&r.section&&(i===0||rows[i-1].section!==r.section)){flush();html+=`<div class="section-label wl-section" data-sec="${i}">${esc(r.section)}</div>`;}
      grid+=cell(r,i);
    }
    flush();
    box.insertAdjacentHTML('beforeend',html);
    shown=until;
  }
  // Lists with sections (専門用語 › 医学, 音楽… or 地名 › 北海道…) get a row of section chips to jump straight there.
  function renderJump(){
    const bar=f('jump'),starts=[];
    if(sort==='default'&&!query&&done)rows.forEach((r,i)=>{if(r.section&&(i===0||rows[i-1].section!==r.section))starts.push([r.section,i]);});
    bar.hidden=starts.length<2;
    if(bar.hidden){bar.innerHTML='';return;}
    bar.innerHTML=starts.map(([t,i])=>`<button class="chip small" data-j="${i}">${esc(t)}</button>`).join('');
    bar.querySelectorAll('[data-j]').forEach(b=>b.onclick=()=>{
      const i=+b.dataset.j;
      while(shown<=i)render(false);
      const h=f('rows').querySelector(`[data-sec="${i}"]`);if(h)f('scroll').scrollTop=h.offsetTop-f('scroll').offsetTop;
    });
  }
  function reset(){gen++;rows=[];done=false;loading=false;f('scroll').scrollTop=0;load();}
  async function load(){
    if(loading)return;
    if(done){if(shown<rows.length)render(false);return;}
    loading=true;const g=gen;
    try{
      const batch=await src.fetch(rows.length,query,sort,rows);
      if(g!==gen)return;// sort or search changed while this was loading
      const first=!rows.length;
      rows=rows.concat(batch.rows);done=batch.done;render(first);renderJump();
    }finally{if(g===gen)loading=false;}
  }
  f('scroll').addEventListener('scroll',()=>{const s=f('scroll');if(s.scrollHeight-s.scrollTop-s.clientHeight<900)load();},{passive:true});
  f('rows').onclick=handle(async e=>{const b=e.target.closest('[data-i]');if(b)return src.open(rows[+b.dataset.i]);});
  await load();
}

async function openWordList(id){
  const lists=await api('wordlists');const list=lists.find(l=>l.id===id);if(!list)return;
  const sub=()=>`${list.count.toLocaleString()} words`;
  await wordListPage({title:list.name,sub:sub(),defaultLabel:list.ranked?'By rank':'List order',
    async fetch(offset,q,sort,prev){
      const limit=q?300:120;
      const last=prev.length?prev[prev.length-1].pos:0;
      const batch=await api('wordlist.items',{id,offset:q?0:sort==='abc'?offset:last,limit,q,sort});
      return {rows:batch.map(r=>({...r,rank:list.ranked?r.pos:null})),done:!!q||batch.length<limit};
    },
    async open(r){
      if(!r.rec){const g=(await api('gloss',{words:[r.word]}))[0];r.rec=g.rec;r.dict=g.dict;}
      if(r.rec)return openEntry({rec:r.rec,dict:r.dict,key:r.word});
      return lookupSheet(r.word,{context:''});
    },
    menu:(el)=>menuSheet(list.name,[
      {label:'Add all words to a folder (deck)',icon:'folder',run:async()=>{
        const folders=await api('folders');
        const c=await menuSheet('Add to folder',folders.map(x=>({label:x.name,icon:'folder',id:x.id})).concat([{label:'New folder named “'+list.name+'”',icon:'plus',id:-1}]));
        if(!c)return;let folder=c.id;
        if(folder===-1)folder=(await api('folder.save',{name:list.name})).id;
        toast('Adding words… this can take a minute for long lists',4000);
        const r=await api('wordlist.toFolder',{id,folder});
        toast(`Added ${r.added.toLocaleString()} cards${r.missing?` · ${r.missing} words had no dictionary entry`:''}`,5000);
      }},
      {label:'Rename',icon:'edit',run:async()=>{const n=await prompt2('Word list name',list.name);if(!n)return;await api('wordlist.rename',{id,name:n});list.name=n;el.querySelector('.title b').textContent=n;}},
      '-',
      {label:'Delete word list',icon:'trash',danger:true,run:async()=>{if(!await confirm2('Delete “'+list.name+'”?','Cards already made from it are kept.','Delete',true))return;await api('wordlist.delete',{id});popPage();renderFolders();}},
    ]),
  });
}

/** A dictionary's own selection, e.g. 大辞林 › 地名. Items: [word, headline, anchor, sortkey?]. */
async function openDictList(dictId,index){
  const d=dictById(dictId);
  const list=await api('dictlist',{dict:dictId,index});
  const all=[];
  for(const s of list.sections)for(const [word,head,anchor,sortkey] of s.items){
    // Headline あいさつ【挨拶】 → reading あいさつ; Chinese 阿城 Āchéng → pinyin; Korean 가격〔價格〕 → hanja.
    const reading=head.includes('【')?head.slice(0,head.indexOf('【')):head.startsWith(word)?head.slice(word.length).trim():'';
    all.push({word,reading,anchor,sortkey,section:s.title});
  }
  const dictName=shortName(d?d.name:'');
  const deck={index,title:list.title};
  if(list.sections.length<2)return dictRowsPage(dictId,list.title,dictName,all,null,{...deck,prefix:''});
  // Categories (専門用語 › 医学, 地名 › 東北地方 › 青森) become a grid to drill into, each with its word count.
  const root={title:list.title,children:new Map(),rows:[],count:0};
  for(const r of all){
    let node=root;node.count++;
    for(const part of (r.section||'').split(' › ').filter(Boolean)){
      if(!node.children.has(part))node.children.set(part,{title:part,children:new Map(),rows:[],count:0});
      node=node.children.get(part);node.count++;
    }
    node.rows.push(r);
  }
  openDictCategory(dictId,root,[dictName],all,deck,[]);
}

/** Cards for a whole dictionary list or one category of it, in a folder named after it. */
async function makeDictCards(dictId,deck,prefix,count){
  const name=[deck.title,...(prefix?prefix.split(' › '):[])].join(' › ');
  const folders=await api('folders');
  const c=await menuSheet(`Make ${count.toLocaleString()} flashcards`,[{label:'New folder “'+name+'”',icon:'plus',id:-1},'-',...folders.map(x=>({label:x.name,icon:'folder',id:x.id}))]);
  if(!c)return;
  let folder=c.id;
  if(folder===-1)folder=(await api('folder.save',{name})).id;
  toast(count>300?'Making cards… this can take a minute':'Making cards…',4000);
  const r=await api('dictlist.toFolder',{dict:dictId,index:deck.index,section:prefix,folder});
  toast(`Added ${r.added.toLocaleString()} cards${r.missing?` · ${r.missing} without a definition`:''}`,5000);
  refreshBadge();
}

function openDictCategory(dictId,node,trail,all,deck,path){
  const kids=[...node.children.values()];
  const direct=node.rows.length;
  const el=document.createElement('div');el.className='wl-page';
  el.innerHTML=`<div class="bar"><button class="icon-btn" data-a="back">${icon('back')}</button><div class="title"><b>${esc(node.title)}</b><small>${esc(trail.join(' › '))} · ${kids.length} categories · ${node.count.toLocaleString()} words</small></div><button class="icon-btn" data-a="cards" aria-label="Make flashcards">${icon('card')}</button></div>
    <div class="scroll"><div class="wl-grid">
      <button class="wl-cell cat-cell all" data-c="all"><span>すべて</span><small>${node.count.toLocaleString()}</small></button>
      ${direct&&kids.length?`<button class="wl-cell cat-cell" data-c="direct"><span>${esc(node.title)}</span><small>${direct.toLocaleString()}</small></button>`:''}
      ${kids.map((k,i)=>`<button class="wl-cell cat-cell" data-c="${i}"><span>${esc(k.title)}</span><small>${k.count.toLocaleString()}${k.children.size?` · ${k.children.size} ▸`:''}</small></button>`).join('')}
    </div></div>`;
  pushPage(el);
  el.querySelector('[data-a="back"]').onclick=()=>popPage();
  const rowsOf=n=>n.rows.concat(...[...n.children.values()].map(rowsOf));
  el.querySelector('[data-a="cards"]').onclick=handle(()=>makeDictCards(dictId,deck,path.join(' › '),node.count));
  el.querySelectorAll('[data-c]').forEach(b=>b.onclick=handle(()=>{
    const c=b.dataset.c,sub=[...trail,node.title];
    if(c==='all')return dictRowsPage(dictId,node.title,trail.join(' › '),trail.length===1?all:rowsOf(node),trail.length===1?null:node.title,{...deck,prefix:path.join(' › ')});
    if(c==='direct')return dictRowsPage(dictId,node.title,trail.join(' › '),node.rows,null,{...deck,prefix:path.join(' › ')});
    const k=kids[+c];
    if(k.children.size)return openDictCategory(dictId,k,sub,all,deck,[...path,k.title]);
    return dictRowsPage(dictId,k.title,sub.join(' › '),k.rows,null,{...deck,prefix:[...path,k.title].join(' › ')});
  }));
}

/** Words of a dictionary list (or one category of it). strip: the category the section titles start with, dropped from headers. */
async function dictRowsPage(dictId,title,trail,rows,strip,deck){
  const d=dictById(dictId);
  if(strip)rows=rows.map(r=>({...r,section:r.section.split(' › ').slice(r.section.split(' › ').indexOf(strip)+1).join(' › ')}));
  const collator=new Intl.Collator(d&&/[한-힣]|朝鮮|韓/.test(d.name)?'ko':'ja');
  // Idioms without a reading carry one from the exporter (青は藍より… → あおは藍より…). Bracketed notes are not readings:
  // アンティゴネ〖Antigonē〗 files under アンティゴネ, not under A.
  const bare=t=>{let x=t,y;do{y=x;x=x.replace(/[〖〔（《(［\[][^〖〔（《(［\[〗〕）》)］\]]*[〗〕）》)］\]]/g,'');}while(x!==y);return x.trim();};
  const key=r=>(r.sortkey||bare(r.reading)||r.word).replace(/^[〜~\-‐…]+/,'');
  let sorted=null;
  await wordListPage({title,sub:`${trail} · ${rows.length.toLocaleString()} words`,
    async fetch(offset,q,sort){
      let out=rows;
      if(q){const n=q.toLowerCase();out=out.filter(r=>r.word.toLowerCase().includes(n)||(r.reading||'').toLowerCase().includes(n));}
      // あいう order lists each entry once (人名 files a person under several fields).
      const byKey=list=>{const seen=new Set();return list.filter(r=>!r.anchor||!seen.has(r.anchor)&&seen.add(r.anchor)).sort((a,b)=>collator.compare(key(a),key(b)));};
      if(sort==='abc'){if(!q){sorted=sorted||byKey(rows);out=sorted;}else out=byKey(out);}
      return {rows:out,done:true};
    },
    async open(r){
      const x=await api('dictlist.resolve',{dict:dictId,anchor:r.anchor,word:r.word});
      if(x.rec)return openEntry({rec:x.rec,dict:dictId,key:r.word,anchor:x.anchor||''});
      return lookupSheet(r.word,{context:''});
    },
    menu:deck?()=>menuSheet(title,[{label:`Make flashcards (${rows.length.toLocaleString()} words)`,icon:'card',run:()=>makeDictCards(dictId,deck,deck.prefix,rows.length)}]):null,
  });
}

// ---------- furoku (appendices) ----------
// Furoku in the dictionary's own menu order with its Japanese titles (tools/export_extras.py); pages
// without a menu entry follow under その他.
async function openAppendix(dictId){
  const d=dictById(dictId);
  const a=await api('appendix',{dict:dictId});
  const fileTitle=(n)=>decodeURIComponent(n.split('/').pop()).replace(/\.(html?|pdf)$/i,'').replace(/^(appendix|付録|凡例)[_-]?/i,'').replace(/_/g,' ');
  const groups=a.groups.map(g=>({title:g.title,items:g.items}));
  if(a.other.length)groups.push({title:groups.length?'その他':'',items:a.other.map(n=>({title:fileTitle(n),path:n}))});
  const items=groups.flatMap(g=>g.items);
  const pages=items.filter(x=>x.path&&!/\.pdf$/i.test(x.path)).length,pdfs=items.filter(x=>/\.pdf$/i.test(x.path||'')).length;
  const el=document.createElement('div');el.className='wl-page';
  let n=0;
  el.innerHTML=`<div class="bar"><button class="icon-btn" data-a="back">${icon('back')}</button><div class="title"><b>${esc(shortName(d.name))} · 付録</b><small>${pages} pages${pdfs?` · ${pdfs} PDF${pdfs===1?"":"s"}`:''}</small></div></div>
    <div class="scroll">${groups.map(g=>`${g.title?`<div class="section-label wl-section">${esc(g.title)}</div>`:''}<div class="wl-grid appendix-grid">${g.items.map(x=>`<button class="wl-cell" data-i="${n++}"><span>${esc(x.title)}</span>${/\.pdf$/i.test(x.path||'')?'<small>PDF</small>':''}</button>`).join('')}</div>`).join('')}<div style="height:24px"></div></div>`;
  pushPage(el);
  el.querySelector('[data-a="back"]').onclick=()=>popPage();
  el.querySelectorAll('[data-i]').forEach(b=>b.onclick=handle(async()=>{
    const x=items[+b.dataset.i];
    if(x.anchor){const r=await api('reference',{dict:dictId,ref:x.anchor});if(r.rec)return openEntry({rec:r.rec,dict:dictId,key:x.title,anchor:r.anchor||''});return toast('Entry not found');}
    if(/\.pdf$/i.test(x.path))return Kotoba.openResource(dictId,x.path);
    return openAppendixPage(dictId,x.path,x.title);
  }));
}
async function openAppendixPage(dictId,name,label){
  const el=document.createElement('div');el.className='entry-page';
  el.innerHTML=`<div class="bar"><button class="icon-btn" data-a="back">${icon('back')}</button><div class="title"><b>${esc(label)}</b><small>${esc(shortName(dictById(dictId).name))} · 付録</small></div></div><div class="entry-scroll"><iframe class="entry-frame" title="Appendix"></iframe></div>`;
  pushPage(el);
  el.querySelector('[data-a="back"]').onclick=()=>popPage();
  const frame=el.querySelector('iframe'),scroll=el.querySelector('.entry-scroll');
  const load=(path,hash)=>new Promise(res=>{frame.onload=()=>{
    const doc=frame.contentDocument;
    if(doc)doc.documentElement.classList.add('kotoba-appendix');
    const opts={dict:dictId,min:200,zoom:1,onLink:async(href)=>{
      const [file,frag]=href.split('#');
      // Links inside the page (#usg_0001, NHK's bare "1_0") scroll to their target.
      const local=(!file&&frag)||(!/[./:]/.test(file)&&!frag&&file);
      const target=local&&(doc.getElementById(local)||doc.querySelector(`[name="${CSS.escape(local)}"]`));
      if(target){target.scrollIntoView({block:'start',inline:'start'});return;}
      if(/\.html?$/i.test(file)&&!/^entry:/i.test(href)){const base=path.includes('/')?path.slice(0,path.lastIndexOf('/')+1):'';await load(base+file,frag);return;}
      const r=await api('reference',{dict:dictId,ref:href});if(r.rec)openEntry({rec:r.rec,dict:dictId,key:r.key||'',anchor:r.anchor||''});else toast('Link target not found');
    }};
    frameSetup(frame,opts);
    // Vertical pages (漢検, 漢辞海, 新明解…) fill the screen like vertical entries instead of a 200px strip.
    scroll.classList.toggle('vertical',!!opts.vertical);
    if(opts.vertical)frame.style.height='';
    if(hash){const t=doc.getElementById(hash);if(t)t.scrollIntoView({block:'start',inline:'start'});}
    res();
  };frame.src=`/d/${dictId}/`+path.split('/').map(encodeURIComponent).join('/');});
  await load(name);
}

// ---------- quick links on the search screen ----------
(function(){
  const original=renderSearchEmpty;
  window.renderSearchEmpty=renderSearchEmpty=async function(){
    await original();
    const box=$('search-empty');if(!dicts.length)return;
    const counts=await api('appendix.counts').catch(()=>[]);
    const withFuroku=counts.filter(c=>c.n>0&&dictById(c.dict));
    const div=document.createElement('div');
    // The kanji grid, word lists and other tools are tiles at the top now; the dictionaries' appendices stay here.
    if(!withFuroku.length)return;
    div.innerHTML=`<div class="section-label">Appendices (付録)</div><div class="history">
      ${withFuroku.map(c=>`<button class="chip" data-furoku="${c.dict}">付録 ${esc(shortName(dictById(c.dict).name))}</button>`).join('')}</div>`;
    box.appendChild(div);
    div.querySelectorAll('[data-furoku]').forEach(b=>b.onclick=handle(()=>openAppendix(+b.dataset.furoku)));
  };
})();

// ---------- Mihon backup + categories on the comics shelf ----------
(function(){
  const original=addComicsMenu;
  window.addComicsMenu=addComicsMenu=async function(){
    await menuSheet('Add comics',[
      {label:'Choose a folder (Mihon downloads, series or chapter)',icon:'folder',run:()=>Kotoba.pickComicFolder()},
      {label:'Choose CBZ / ZIP files',icon:'book',run:()=>Kotoba.pickComicFiles()},
      {label:'Import a Mihon backup (read progress, categories)',icon:'refresh',run:()=>Kotoba.pickMihonBackup()},
      {label:'Scan the app’s own comics folder',icon:'refresh',run:async()=>{const r=await api('comic.scanLocal');toast(r.series?`Found ${r.series} series · ${r.chapters} chapters`:`Nothing in ${r.path}`,4000);renderComicShelf();}},
    ]);
  };
  const shelf=renderComicShelf;
  let category='';
  window.renderComicShelf=renderComicShelf=async function(){
    await shelf();
    const list=await api('comics');
    const cats=[...new Set(list.flatMap(s=>(s.category||'').split(', ').filter(Boolean)))];
    if(!cats.length)return;
    const bar=document.createElement('div');bar.className='chips';bar.style.cssText='padding:4px 16px 8px';
    bar.innerHTML=[['','All'],...cats.map(c=>[c,c])].map(([v,l])=>`<button class="chip small ${category===v?'on':''}" data-c="${esc(v)}">${esc(l)}</button>`).join('');
    $('shelf').prepend(bar);
    bar.querySelectorAll('[data-c]').forEach(b=>b.onclick=()=>{category=b.dataset.c;renderComicShelf();});
    if(category)$('shelf').querySelectorAll('[data-series]').forEach(el=>{const s=list.find(x=>x.id===+el.dataset.series);el.hidden=!(s&&(s.category||'').split(', ').includes(category));});
  };
})();
