'use strict';
/* Kotoba — offline dictionary and flashcards. All data lives on the phone; Java serves it through a bridge. */

// ---------- utilities ----------
const $=(id)=>document.getElementById(id);
const esc=(v)=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const icons={
  back:'<path d="M15 5l-7 7 7 7"/>',
  close:'<path d="M6 6l12 12M18 6 6 18"/>',
  star:'<path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z"/>',
  plus:'<path d="M12 5v14M5 12h14"/>',
  more:'<circle cx="5" cy="12" r="1.3"/><circle cx="12" cy="12" r="1.3"/><circle cx="19" cy="12" r="1.3"/>',
  search:'<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/>',
  book:'<path d="M4 5.5A1.5 1.5 0 0 1 5.5 4H11v16H5.5A1.5 1.5 0 0 1 4 18.5z"/><path d="M13 4h5.5A1.5 1.5 0 0 1 20 5.5v13a1.5 1.5 0 0 1-1.5 1.5H13z"/>',
  copy:'<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3"/>',
  share:'<path d="M12 3v12M7 8l5-5 5 5"/><path d="M5 13v6a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-6"/>',
  card:'<rect x="3" y="6" width="14" height="14" rx="2"/><path d="M7 3h12a2 2 0 0 1 2 2v12"/>',
  folder:'<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
  bookmark:'<path d="M7 3h10a1 1 0 0 1 1 1v17l-6-4-6 4V4a1 1 0 0 1 1-1z"/>',
  text:'<path d="M4 7V5h10v2M9 5v14M7 19h4M14 12v-1h6v1M17 11v8M15.5 19h3"/>',
  vertical:'<path d="M17 4v16M12 4v10M7 4v7"/>',
  expand:'<path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/>',
  grid:'<rect x="4" y="4" width="6.5" height="6.5" rx="1.2"/><rect x="13.5" y="4" width="6.5" height="6.5" rx="1.2"/><rect x="4" y="13.5" width="6.5" height="6.5" rx="1.2"/><rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1.2"/>',
  list:'<path d="M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01"/>',
  sort:'<path d="M7 4v16M3.5 16.5 7 20l3.5-3.5M13 6h8M13 12h6M13 18h4"/>',
  trash:'<path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"/>',
  move:'<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><path d="M10 13h6M13 10l3 3-3 3"/>',
  edit:'<path d="M4 20h4L19 9l-4-4L4 16z"/><path d="M13.5 6.5l4 4"/>',
  undo:'<path d="M9 14 4 9l5-5"/><path d="M4 9h11a5 5 0 0 1 0 10h-3"/>',
  check:'<path d="M5 12.5l4.5 4.5L19 7"/>',
  up:'<path d="M12 19V5M6 11l6-6 6 6"/>',
  down:'<path d="M12 5v14M6 13l6 6 6-6"/>',
  play:'<path d="M8 5v14l11-7z"/>',
  export:'<path d="M12 15V3M7 10l5 5 5-5"/><path d="M5 17v2a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-2"/>',
  shuffle:'<path d="M16 3h5v5"/><path d="M4 20 21 3"/><path d="M21 16v5h-5"/><path d="M15 15l6 6"/><path d="M4 4l5 5"/>',
  refresh:'<path d="M20 11a8 8 0 1 0-2.3 5.7"/><path d="M20 5v6h-6"/>',
  prev:'<path d="M15 5l-7 7 7 7"/>',
  next:'<path d="M9 5l7 7-7 7"/>',
};
const icon=(name,cls='i')=>`<svg class="${cls}" viewBox="0 0 24 24">${icons[name]||''}</svg>`;

const pending=new Map();let seq=0;
function api(route,body={}){
  return new Promise((resolve,reject)=>{
    const id=++seq;pending.set(id,{resolve,reject});
    try{Kotoba.call(id,route,JSON.stringify(body));}catch(e){pending.delete(id);reject(e);}
  });
}
window.__reply=(id,json)=>{
  const p=pending.get(id);if(!p)return;pending.delete(id);
  let r;try{r=JSON.parse(json);}catch(e){p.reject(e);return;}
  if(r.error!==undefined)p.reject(new Error(r.error));else p.resolve(r.data);
};
const listeners={};
function on(type,fn){(listeners[type]=listeners[type]||[]).push(fn);}
window.__event=(json)=>{const e=JSON.parse(json);(listeners[e.type]||[]).forEach(fn=>{try{fn(e.data);}catch(err){console.error(err);}});};
on('toast',m=>toast(m));

function toast(message,ms=2600){
  const t=$('toast');t.textContent=message;t.classList.add('show');
  clearTimeout(toast.timer);toast.timer=setTimeout(()=>t.classList.remove('show'),ms);
}
function handle(fn){return (...args)=>Promise.resolve().then(()=>fn(...args)).catch(e=>{console.error(e);toast(e.message||String(e));});}
const debounce=(fn,ms)=>{let t;return (...a)=>{clearTimeout(t);t=setTimeout(()=>fn(...a),ms);};};
const nfkc=(s)=>String(s||'').normalize('NFKD').replace(/[\u0300\u0301]/g,'').normalize('NFKC').toLowerCase().replace(/ё/g,'е').replace(/[\u30a1-\u30f6]/g,c=>String.fromCharCode(c.charCodeAt(0)-96));
const norm=(s)=>nfkc(s).replace(/[\s\u200b]/g,'');
const cleanKey=(s)=>norm(s).replace(/[▽▼▲△×〈〉〔〕【】［］\[\]()（）《》〘〙‐\-－―—━・･=＝‖|⚷⚶⚹＊*]/g,'');
function fmtInterval(sec){
  if(sec<3600)return Math.max(1,Math.round(sec/60))+'m';
  if(sec<86400)return Math.round(sec/3600)+'h';
  const d=sec/86400;
  if(d<31)return Math.round(d)+'d';
  if(d<365)return (d/30.4).toFixed(d<60?1:0).replace(/\.0$/,'')+'mo';
  return (d/365).toFixed(1).replace(/\.0$/,'')+'y';
}
function fmtDue(ts){
  const s=ts-Date.now()/1000;
  if(s<=0)return 'due';
  return 'in '+fmtInterval(s);
}

// ---------- settings ----------
const settings={zoom:1.25,vertical:false,theme:'light',new_per_day:'20',retention:'0.9',fulltext:true,front_reading:false,autoplay_entry:false,autoplay_review:'answer',audio_front:false};
function loadLocalSettings(){try{Object.assign(settings,JSON.parse(localStorage.getItem('settings')||'{}'));}catch(e){}}
function saveLocalSettings(){try{localStorage.setItem('settings',JSON.stringify(settings));}catch(e){}}
const THEME_BARS={light:['#f7f4ee',true],sepia:['#f1e8d6',true],dark:['#141614',false]};
function setBars(color,light){try{Kotoba.setBars(color,light);}catch(e){}}
function appBars(){const b=THEME_BARS[settings.theme]||THEME_BARS.light;setBars(b[0],b[1]);}
function applyTheme(){document.documentElement.dataset.theme=settings.theme;appBars();}
async function setSetting(key,value){settings[key]=value;saveLocalSettings();if(['new_per_day','retention'].includes(key))await api('setting',{key,value:String(value)});}

// ---------- navigation: tabs, pages, sheets ----------
let tab='search';
const pageStack=[];const sheetStack=[];
function showTab(name){
  tab=name;
  document.querySelectorAll('.screen').forEach(s=>s.hidden=s.id!=='screen-'+name);
  document.querySelectorAll('#tabbar button').forEach(b=>b.classList.toggle('on',b.dataset.tab===name));
  while(pageStack.length)popPage(true);
  if(name==='folders')renderFolders();
  if(name==='review')renderReviewHome();
  if(name==='library')renderLibrary();
  if(name==='reader')renderShelf();
  hideSelbar();
}
function pushPage(el,opts={}){
  el.classList.add('page','enter');
  if(opts.modal)el.classList.add('modal');
  $('pages').appendChild(el);
  pageStack.push({el,opts});
  requestAnimationFrame(()=>requestAnimationFrame(()=>el.classList.remove('enter')));
  $('selbar').classList.add('over-page');
  hideSelbar();
  return el;
}
function popPage(instant=false){
  const top=pageStack.pop();if(!top)return;
  top.opts.onClose&&top.opts.onClose();
  if(instant){top.el.remove();}
  else{top.el.classList.add(top.opts.modal?'enter':'leave');setTimeout(()=>top.el.remove(),230);}
  if(!pageStack.length)$('selbar').classList.remove('over-page');
  const next=pageStack[pageStack.length-1];next&&next.opts.onResume&&next.opts.onResume();
  if(!next)(tab==='folders'?renderFolders():tab==='review'?renderReviewHome():null);
  hideSelbar();
}
function openSheet(html,opts={}){
  const root=$('sheets');
  const scrim=document.createElement('div');scrim.className='scrim';
  const sheet=document.createElement('div');sheet.className='sheet'+(opts.tall?' tall':'');
  sheet.innerHTML=`<div class="grab"></div>${opts.title!==undefined?`<div class="sheet-head"><h2>${esc(opts.title)}</h2><button class="icon-btn" data-close aria-label="Close">${icon('close')}</button></div>`:''}${html}`;
  root.append(scrim,sheet);
  const entry={sheet,scrim,opts};sheetStack.push(entry);
  requestAnimationFrame(()=>requestAnimationFrame(()=>{scrim.classList.add('show');sheet.classList.add('show');}));
  scrim.onclick=()=>closeSheet(entry);
  sheet.querySelectorAll('[data-close]').forEach(b=>b.onclick=()=>closeSheet(entry));
  hideSelbar();
  return entry;
}
function closeSheet(entry){
  entry=entry||sheetStack[sheetStack.length-1];if(!entry)return;
  const i=sheetStack.indexOf(entry);if(i<0)return;sheetStack.splice(i,1);
  entry.scrim.classList.remove('show');entry.sheet.classList.remove('show');
  entry.opts.onClose&&entry.opts.onClose();
  setTimeout(()=>{entry.scrim.remove();entry.sheet.remove();},250);
}
function menuSheet(title,items){
  return new Promise(resolve=>{
    let chosen=null;
    const s=openSheet(`<div class="menu">${items.map((it,i)=>it==='-'?'<hr>':it.heading?`<div class="menu-label">${esc(it.heading)}</div>`:`<button data-i="${i}" class="${it.danger?'danger':''}">${it.icon?icon(it.icon):''}<span>${esc(it.label)}</span></button>`).join('')}</div>`,{title,onClose:()=>resolve(chosen)});
    s.sheet.querySelectorAll('[data-i]').forEach(b=>b.onclick=()=>{chosen=items[+b.dataset.i];closeSheet(s);chosen.run&&setTimeout(()=>handle(chosen.run)(),30);});
  });
}
function prompt2(title,value='',placeholder=''){
  return new Promise(resolve=>{
    let result=null;
    const s=openSheet(`<div class="sheet-body"><input class="input big" id="p2" value="${esc(value)}" placeholder="${esc(placeholder)}" maxlength="120"></div><div class="sheet-foot"><button class="btn wide" data-close>Cancel</button><button class="btn primary wide" id="p2ok">OK</button></div>`,{title,onClose:()=>resolve(result)});
    const input=s.sheet.querySelector('#p2');setTimeout(()=>{input.focus();input.select();},260);
    const ok=()=>{result=input.value.trim();closeSheet(s);};
    s.sheet.querySelector('#p2ok').onclick=ok;input.onkeydown=e=>{if(e.key==='Enter')ok();};
  });
}
function confirm2(title,message,okLabel='OK',danger=false){
  return new Promise(resolve=>{
    let result=false;
    const s=openSheet(`<div class="sheet-body"><p class="hint" style="font-size:15px;color:var(--ink2)">${esc(message)}</p></div><div class="sheet-foot"><button class="btn wide" data-close>Cancel</button><button class="btn ${danger?'danger':'primary'} wide" id="c2ok">${esc(okLabel)}</button></div>`,{title,onClose:()=>resolve(result)});
    s.sheet.querySelector('#c2ok').onclick=()=>{result=true;closeSheet(s);};
  });
}
window.appBack=()=>{
  if(!$('selbar').hidden){hideSelbar();clearSelections();return true;}
  if(sheetStack.length){closeSheet();return true;}
  if(pageStack.length){popPage();return true;}
  if(tab!=='search'){showTab('search');return true;}
  if($('q').value){$('q').value='';runSearch();return true;}
  return false;
};
document.querySelectorAll('#tabbar button').forEach(b=>b.onclick=()=>{
  if(b.dataset.tab===tab&&tab==='search'&&!pageStack.length){$('q').focus();$('q').select();return;}
  showTab(b.dataset.tab);
});

// ---------- dictionaries ----------
let dicts=[];
const dictById=(id)=>dicts.find(d=>d.id===Number(id));
async function loadDicts(){
  dicts=await api('dicts');
  renderDictChips();
}

// ---------- search ----------
const search={mode:'headword',dict:'',offset:0,items:[],version:0,more:false};
const GROUP_ORDER=['Japanese','Kanji','Pronunciation','Korean','Chinese','Thai','Russian','English'];
const GROUP_LABEL={Japanese:'Japanese 国語',Kanji:'Kanji 漢字',Pronunciation:'Pronunciation 発音',Korean:'Korean 韓',Chinese:'Chinese 中',Thai:'Thai タイ',Russian:'Russian 露',English:'English 英'};
function groupsPresent(){
  const g=[...new Set(dicts.filter(d=>d.enabled).map(d=>d.grp||'Japanese'))];
  return g.sort((a,b)=>(GROUP_ORDER.indexOf(a)+1||99)-(GROUP_ORDER.indexOf(b)+1||99)||a.localeCompare(b));
}
function renderDictChips(){
  const enabled=dicts.filter(d=>d.enabled);
  const groups=groupsPresent();
  if(search.dict.startsWith('g:')&&!groups.includes(search.dict.slice(2)))search.dict='';
  if(search.dict&&!search.dict.startsWith('g:')&&!enabled.some(d=>String(d.id)===String(search.dict)))search.dict='';
  $('dict-chips').hidden=enabled.length<2;
  const single=search.dict&&!search.dict.startsWith('g:')?dictById(search.dict):null;
  $('dict-chips').innerHTML=`<button class="chip small ${search.dict===''?'on':''}" data-dict="">All</button>`+
    (groups.length>1?groups.map(g=>`<button class="chip small ${search.dict==='g:'+g?'on':''}" data-dict="g:${esc(g)}">${esc(GROUP_LABEL[g]||g)}</button>`).join(''):'')+
    `<button class="chip small ${single?'on':''}" data-pick="1">${single?esc(shortName(single.name))+' ▾':'One dictionary ▾'}</button>`;
  $('dict-chips').querySelectorAll('[data-dict]').forEach(b=>b.onclick=()=>{search.dict=b.dataset.dict;renderDictChips();runSearch();});
  $('dict-chips').querySelector('[data-pick]').onclick=handle(async()=>{
    const items=[];
    for(const g of groupsPresent()){items.push({heading:GROUP_LABEL[g]||g});for(const d of enabled.filter(x=>(x.grp||'Japanese')===g))items.push({label:d.name,icon:'book',id:d.id});}
    const c=await menuSheet('Search one dictionary',items);
    if(!c)return;search.dict=String(c.id);renderDictChips();runSearch();
  });
}
function shortName(name){
  let n=String(name).replace(/　/g,' ').trim();
  if(/NHK/.test(n))return 'NHK';
  n=n.replace(/^(小学館|三省堂|研究社|大修館|旺文社)\s*/,'').replace(/^全訳\s*/,'').replace(/\s*第.版$/,'').replace(/^プログレッシブ\s*/,'');
  n=n.replace(/^新明解国語辞典.*/,'新明解').replace(/^漢検\s*漢字辞典.*/,'漢検').replace(/^大辞林.*/,'大辞林');
  if(n.length>4)n=n.replace(/辞典$/,'');
  return n.slice(0,12);
}
document.querySelectorAll('#modes [data-mode]').forEach(b=>b.onclick=()=>{
  search.mode=b.dataset.mode;
  document.querySelectorAll('#modes [data-mode]').forEach(x=>x.classList.toggle('on',x===b));
  runSearch();
});
$('q').addEventListener('input',()=>{$('q-clear').hidden=!$('q').value;debouncedSearch();});
$('q').addEventListener('keydown',e=>{if(e.key==='Enter'){e.preventDefault();$('q').blur();runSearch();remember();}});
$('q-clear').onclick=()=>{$('q').value='';$('q-clear').hidden=true;$('q').focus();runSearch();};
$('search-scroll').addEventListener('scroll',()=>$('search-head').classList.toggle('scrolled',$('search-scroll').scrollTop>4),{passive:true});
$('more').onclick=handle(()=>runSearch(true));
const debouncedSearch=debounce(()=>handle(runSearch)(),140);
function remember(){const q=$('q').value.trim();if(q)api('history.add',{q}).catch(()=>{});}

function groupResults(items){
  // Consecutive rows with the same headword become one row with a tag per dictionary.
  const groups=[];
  for(const it of items){
    const last=groups[groups.length-1];
    const k=norm(it.key);
    if(last&&last.norm===k&&search.mode!=='definition'&&search.mode!=='examples'&&!last.items.some(x=>x.dict===it.dict))last.items.push(it);
    else if(last&&last.norm===k&&last.items.some(x=>x.dict===it.dict&&x.rec===it.rec))continue;
    else groups.push({norm:k,key:it.key,items:[it]});
  }
  return groups;
}
function snippetHtml(s){return esc(s).replace(/\u0001/g,'<mark>').replace(/\u0002/g,'</mark>');}

async function runSearch(append=false){
  const q=$('q').value;
  const version=++search.version;
  if(!append){search.offset=0;}else search.offset+=50;
  if(!q.trim()&&!search.dict){
    search.items=[];$('results').innerHTML='';$('more').hidden=true;$('kanji-strip').hidden=true;$('forms').hidden=true;
    await renderSearchEmpty();return;
  }
  if(!q.trim()&&search.dict&&!search.dict.startsWith('g:')){
    // One dictionary with an empty query: its whole index, like a paper dictionary.
    $('search-empty').innerHTML='';$('more').hidden=true;$('kanji-strip').hidden=true;$('forms').hidden=true;
    await mountBrowse($('results'),+search.dict,{},$('search-scroll'));
    return;
  }
  $('search-empty').innerHTML='';
  if(!append&&search.mode!=='headword')$('results').innerHTML='<div class="loading"><div class="spinner"></div></div>';
  const data=await api('search',{q,mode:search.mode,dict:search.dict,offset:search.offset});
  if(version!==search.version)return;
  search.items=append?search.items.concat(data.items):data.items;search.more=data.more;
  if(!append){renderKanjiStrip(data.kanji||[]);renderForms(data.forms||[]);}
  const groups=groupResults(search.items);
  $('results').innerHTML=groups.map((g,i)=>{
    const first=g.items[0];
    const page=first.page&&norm(first.page)!==norm(first.key)?`<span class="pg">${esc(first.page)}</span>`:'';
    const tags=g.items.map(it=>`<span class="tag ${it.kind==='kanji'?'kanji':''}">${esc(shortName(it.dictionary))}</span>`).join('');
    const snip=first.snippet?`<div class="snip">${snippetHtml(first.snippet)}</div>`:'';
    return `<button class="row" data-g="${i}"><div class="line"><div class="hw">${esc(first.key)}${page}</div><div class="meta">${tags}</div></div>${snip}</button>`;
  }).join('');
  if(!groups.length&&!append&&(data.forms||[]).length){$('results').innerHTML='';}
  else if(!groups.length){
    const hint=search.mode==='headword'?'Try “Contains”, or search in definitions.':'Try a shorter phrase or another dictionary.';
    $('results').innerHTML=`<div class="empty"><span class="glyph">無</span><h2>No matches</h2>${esc(data.note||hint)}</div>`;
  }
  $('results').querySelectorAll('[data-g]').forEach(b=>b.onclick=handle(()=>{
    const g=groups[+b.dataset.g];remember();
    const hl=(search.mode==='definition'||search.mode==='examples')?q:'';
    openEntry({...g.items[0],key:g.items[0].key,alternatives:g.items,highlight:hl,highlightIn:search.mode});
  }));
  $('more').hidden=!data.more;
  if(!append)$('search-scroll').scrollTop=0;
}
function renderForms(forms){
  const box=$('forms');
  if(!forms.length||search.mode!=='headword'){box.hidden=true;box.innerHTML='';return;}
  box.hidden=false;
  box.innerHTML=`<div class="section-label">Dictionary form</div>`+forms.map((f,i)=>{
    const tags=f.items.filter((x,j,a)=>a.findIndex(y=>y.dict===x.dict)===j).map(it=>`<span class="tag">${esc(shortName(it.dictionary))}</span>`).join('');
    return `<button class="row form-row" data-fm="${i}"><div class="line"><div class="hw">${esc(f.base)}</div><div class="meta">${tags}</div></div><div class="chain">${esc(f.chain)}</div><div class="explain">${esc(f.explain)}</div></button>`;
  }).join('');
  box.querySelectorAll('[data-fm]').forEach(b=>b.onclick=handle(()=>{
    const f=forms[+b.dataset.fm];remember();
    const alts=f.items.filter((x,j,a)=>a.findIndex(y=>y.dict===x.dict)===j);
    openEntry({...alts[0],key:f.base,alternatives:alts});
  }));
}
function renderKanjiStrip(kanji){
  const strip=$('kanji-strip');
  if(!kanji.length||search.mode!=='headword'){strip.hidden=true;return;}
  strip.hidden=false;
  strip.innerHTML=kanji.map((k,i)=>`<button data-k="${i}">${esc(k.char)}<small>KANJI</small></button>`).join('');
  strip.querySelectorAll('[data-k]').forEach(b=>b.onclick=handle(()=>{
    const k=kanji[+b.dataset.k];
    openEntry({...k.entries[0],alternatives:k.entries});
  }));
}
async function renderSearchEmpty(){
  const box=$('search-empty');
  if(!dicts.length){
    box.innerHTML=`<div class="empty"><span class="glyph">辞</span><h2>Add your dictionaries</h2>Import MDX dictionaries from your phone — for example the <b>Monokakido_Ciyue</b> folder in Downloads.<br><br><button class="btn primary" id="go-import">${icon('folder')} Choose dictionary folder</button></div>`;
    $('go-import').onclick=()=>{showTab('library');pickFolder();};
    return;
  }
  const history=await api('history').catch(()=>[]);
  const browse=`<div class="section-label">Browse a dictionary<button id="random-any" style="display:inline-flex;align-items:center;gap:5px">${icon('shuffle','i sm')} Random word</button></div><div class="history">${dicts.filter(d=>d.enabled).map(d=>`<button class="chip" data-browse="${d.id}">${esc(shortName(d.name))}</button>`).join('')}</div>`;
  box.innerHTML=(history.length?`<div class="section-label">Recent<button id="clear-history">Clear</button></div><div class="history">${history.map(h=>`<button class="chip" data-h="${esc(h.query)}">${esc(h.query)}</button>`).join('')}</div>`:
    `<div class="empty" style="padding-bottom:10px"><span class="glyph">言</span><h2>Look something up</h2>Type a word, reading or phrase.<br>Kana, kanji, Hangul, Thai and Cyrillic all work.</div>`)+browse;
  box.querySelectorAll('[data-browse]').forEach(b=>b.onclick=handle(()=>openBrowse(+b.dataset.browse)));
  $('random-any').onclick=handle(async()=>{const r=await api('random',{dict:search.dict&&!search.dict.startsWith('g:')?+search.dict:0});openEntry({rec:r.rec,dict:r.dict,key:r.key});});
  box.querySelectorAll('[data-h]').forEach(b=>b.onclick=()=>{$('q').value=b.dataset.h;$('q-clear').hidden=false;runSearch();});
  const clear=$('clear-history');if(clear)clear.onclick=handle(async()=>{await api('history.clear');renderSearchEmpty();});
}
window.externalLookup=(text)=>{
  closeAllOverlays();showTab('search');
  $('q').value=text.trim().slice(0,100);$('q-clear').hidden=false;
  search.mode='headword';document.querySelectorAll('#modes [data-mode]').forEach(x=>x.classList.toggle('on',x.dataset.mode==='headword'));
  handle(async()=>{await runSearch();remember();
    const groups=groupResults(search.items);
    if(groups.length&&norm(groups[0].key)===norm(text))openEntry({...groups[0].items[0],alternatives:groups[0].items});
  })();
};
function closeAllOverlays(){while(sheetStack.length)closeSheet();while(pageStack.length)popPage(true);}

// ---------- entry structure (works on each dictionary's own markup) ----------
const UNIT=new Set(['項目','子項目','句項目','subhead','m-body','entry','dic-item','熟語','jyukugog','親字g','派生m','子見出g']);
const HEAD=new Set(['見出部','m-head','headg','headlineg','見出しg','見出g','head-g','熟語見出部','subheadwordg','子見出部','oyajig','jyukugohyokig','subheadword','head','親字td']);
const WORD=new Set(['標準表記','句表記','headword','subheadword','見出し','見出','熟語見出','jyukugohyoki','thai','oyajicharacter','表記','見出語','kanji','cn','親字-常用','親字-常用外','親字-表外','親字-重要']);
const READING=new Set(['見出仮名','pron','pinyin','発音','m-headword-pron-kana','熟語読','jyukugoyomi','yomi','表音表記']);
const SENSE=new Set(['語義g','meaningg','m-meaning-group','meaning','語義','imisub','熟語語義','語義g2','parag','言い換えg','無礼例文g']);
const EXAMPLE=new Set(['用例g','用例','example','m-example-group','慣用句g','諺g','言い換え例文g']);
const SKIPTEXT='rt,rp,[data-name="ルビG"],[data-name="ルビ仮名"],[data-name="entry-index"],m-entry-index,#index,m-audio,sound,script,style';
function names(el){
  const out=[el.localName];
  const dn=el.getAttribute&&el.getAttribute('data-name');if(dn)out.push(dn.toLowerCase());
  if(el.classList)for(const c of el.classList)out.push(c.toLowerCase());
  return out;
}
const isIn=(set,el)=>names(el).some(n=>set.has(n));
function textOf(el){
  const c=el.cloneNode(true);
  c.querySelectorAll(SKIPTEXT).forEach(x=>x.remove());
  return c.textContent.replace(/[ \t\r\n\u3000]+/g,' ').trim();
}
function allMatching(root,set){
  const out=[];const walker=root.ownerDocument.createTreeWalker(root,NodeFilter.SHOW_ELEMENT);
  let n=walker.currentNode;
  while(n){if(n!==root&&isIn(set,n))out.push(n);n=walker.nextNode();}
  return out;
}
function unitsOf(doc){
  const units=allMatching(doc.body,UNIT).filter(u=>!u.closest('[data-name="entry-index"],m-entry-index,#index'));
  return units;
}
function ownDescendants(unit,set){
  // Matches inside this unit but not inside a nested unit.
  return allMatching(unit,set).filter(el=>{let p=el.parentElement;while(p&&p!==unit){if(isIn(UNIT,p))return false;p=p.parentElement;}return true;});
}
function headOf(unit){
  return ownDescendants(unit,HEAD)[0]||null;
}
function unitInfo(unit,fallbackKey){
  const head=headOf(unit);
  const segments=new Set();
  const addText=(s)=>{const k=cleanKey(s);if(k)segments.add(k);};
  if(head){
    head.querySelectorAll('*').forEach(el=>addText(textOf(el)));
    addText(textOf(head));
    textOf(head).split(/[【】〔〕［］\[\]()（）・,，、\/／\s]+/).forEach(addText);
  }
  let word='',reading='';
  if(head){
    const w=[...head.querySelectorAll('*')].find(el=>isIn(WORD,el)&&!isIn(READING,el)&&textOf(el));
    const r=[...head.querySelectorAll('*')].find(el=>isIn(READING,el)&&textOf(el));
    if(w)word=textOf(w).replace(/\s+/g,'');
    if(r)reading=textOf(r).replace(/\s+/g,'');
    // Kana readings carry morpheme separators (た・べる); Latin/Thai romanization keeps its own spacing.
    if(/^[\u3040-\u30ff・･‐\-=＝]+$/.test(reading))reading=reading.replace(/[・･‐\-=＝]/g,'');
    if(!word&&!reading)word=textOf(head).slice(0,40);
  }
  if(!word&&reading){word=reading;reading='';}
  // Kana headword with a 【kanji】 spelling (NHK: たべる【食べる】): the spelling is the word, the kana its reading.
  const spelled=head&&textOf(head).match(/【([^】]+)】/);
  if(spelled&&/^[\u3040-\u30ff・ー]+$/.test(word)){reading=reading||word;word=spelled[1].split(/[・，,]/)[0].replace(/[▽▼×〈〉]/g,'').trim()||word;}
  word=word.replace(/^【|】$/g,'');
  if(!word)word=fallbackKey||'';
  if(cleanKey(word)===cleanKey(reading))reading='';
  return {unit,head,segments,word,reading,label:head?textOf(head).slice(0,60):word};
}
/** A single kanji looked up in a kanji dictionary shows its whole page rather than one focused part. */
function kanjiHead(dict,key){
  const d=dictById(dict);
  return !!(d&&d.kind==='kanji'&&[...cleanKey(key||'')].length===1);
}
function findFocus(doc,key,anchor){
  const units=unitsOf(doc);
  if(anchor){
    const target=doc.getElementById(anchor)||doc.getElementById(String(anchor).replace(/^#/,''));
    if(target){
      let u=target;while(u&&u!==doc.body&&!isIn(UNIT,u))u=u.parentElement;
      return {units,focus:u&&u!==doc.body?u:null,target};
    }
  }
  const k=cleanKey(key);
  // Entries whose main part sits directly on the page (with sub-entries as units) focus the page itself.
  if(units.length&&k){
    const pageHead=ownDescendants(doc.body,HEAD)[0];
    if(pageHead&&!units.some(u=>u.contains(pageHead))&&unitInfo(doc.body).segments.has(k))return {units,focus:doc.body};
  }
  if(units.length<2)return {units,focus:units[0]||null};
  if(!k)return {units,focus:null};
  for(const u of units){const info=unitInfo(u);if(info.segments.has(k))return {units,focus:u};}
  for(const u of units){const info=unitInfo(u);if([...info.segments].some(s=>s.startsWith(k)))return {units,focus:u};}
  return {units,focus:null};
}
function applyFocus(doc,focus,units){
  doc.querySelectorAll('.kotoba-hidden').forEach(el=>el.classList.remove('kotoba-hidden'));
  if(!focus)return 0;
  let hidden=0;
  // Hide everything outside the focused unit (keeping ancestors so the dictionary's CSS context survives).
  let node=focus;
  while(node&&node!==doc.body){
    const parent=node.parentElement;if(!parent)break;
    for(const sib of parent.children){
      if(sib===node||sib.localName==='link'||sib.localName==='style')continue;
      sib.classList.add('kotoba-hidden');
    }
    node=parent;
  }
  // Hide nested units (subwords) inside the focused unit.
  for(const u of units){if(u!==focus&&focus.contains(u)){u.classList.add('kotoba-hidden');}}
  hidden=units.filter(u=>u!==focus&&u.classList.contains('kotoba-hidden')||!focus.contains(u)&&u!==focus&&!u.contains(focus)).length;
  return hidden;
}
function partsOf(unit){
  const parts=[];
  const head=headOf(unit);
  if(head)parts.push({kind:'Heading',el:[head],text:textOf(head)});
  const senses=ownDescendants(unit,SENSE).filter((s,i,arr)=>!arr.some(o=>o!==s&&o.contains(s)));
  for(const s of senses){
    const els=[s];
    let sib=s.nextElementSibling;
    while(sib&&!isIn(SENSE,sib)&&!isIn(UNIT,sib)&&(isIn(EXAMPLE,sib)||!textOf(sib))){if(textOf(sib))els.push(sib);sib=sib.nextElementSibling;}
    const text=els.map(textOf).join(' ');
    if(text)parts.push({kind:'Meaning',el:els,text});
  }
  if(parts.length<=(head?1:0)){
    const body=[...unit.children].filter(c=>c!==head&&!isIn(UNIT,c)&&textOf(c));
    if(body.length)parts.push({kind:'Definition',el:body,text:body.map(textOf).join(' ')});
  }
  return parts;
}
function stylesheetLinks(doc){return [...doc.querySelectorAll('link[rel~="stylesheet"]')].map(l=>`<link rel="stylesheet" href="${esc(l.getAttribute('href'))}">`).join('');}
function shallow(el){
  const c=el.cloneNode(false);c.removeAttribute('id');c.classList.remove('kotoba-hidden');if(!c.className)c.removeAttribute('class');return c;
}
function partsHtml(doc,unit,parts){
  // Rebuild the ancestor chain so the dictionary's CSS selectors still apply on the card.
  let outer=null,inner=null;
  const chain=[];let n=unit;while(n&&n!==doc.body){chain.unshift(n);n=n.parentElement;}
  for(const a of chain){const c=shallow(a);if(!outer)outer=c;else inner.appendChild(c);inner=c;}
  if(!inner)return '';
  for(const p of parts)for(const el of p.el){
    // Keep each part's intermediate wrappers between the unit and the part.
    const path=[];let x=el.parentElement;while(x&&x!==unit){path.unshift(x);x=x.parentElement;}
    let target=inner;for(const w of path){const c=shallow(w);target.appendChild(c);target=c;}
    const clone=el.cloneNode(true);clone.classList.remove('kotoba-hidden');clone.querySelectorAll('.kotoba-hidden').forEach(h=>h.classList.remove('kotoba-hidden'));
    target.appendChild(clone);
  }
  return stylesheetLinks(doc)+outer.outerHTML;
}

// ---------- entry page ----------
/**
 * Finger-slide selection. `slide`: a sideways drag along the text selects (vertical drag for vertical text) while
 * the other direction still scrolls. Always: double-tap and keep the finger down, then drag, to extend a selection.
 */
function enableSlideSelect(doc,slide){
  let st=null,lastTap={t:0,x:0,y:0},mode='';
  // Sideways sliding must keep scrolling wide content: tables, appendix pages, anything wider than the screen.
  const slideAllowed=(target)=>{
    if(doc.documentElement.classList.contains('kotoba-appendix'))return false;
    const se=doc.scrollingElement||doc.documentElement;
    if(se.scrollWidth>se.clientWidth+4)return false;
    for(let n=target&&target.nodeType===1?target:target&&target.parentElement;n&&n!==doc.body;n=n.parentElement){
      if(n.localName==='table'||n.localName==='pre')return false;
      if(n.scrollWidth>n.clientWidth+4){const ox=getComputedStyle(n).overflowX;if(ox==='auto'||ox==='scroll')return false;}
    }
    return true;
  };
  const caret=(x,y)=>{const r=doc.caretRangeFromPoint&&doc.caretRangeFromPoint(x,y);return r&&r.startContainer.nodeType===3?r:null;};
  doc.addEventListener('touchstart',e=>{
    if(e.touches.length!==1){st=null;return;}
    const t=e.touches[0];const now=Date.now();
    const dbl=now-lastTap.t<350&&Math.hypot(t.clientX-lastTap.x,t.clientY-lastTap.y)<40;
    st={x:t.clientX,y:t.clientY,anchor:caret(t.clientX,t.clientY),dbl,slide:slide&&slideAllowed(e.target)};mode='';
  },{passive:true});
  doc.addEventListener('touchmove',e=>{
    if(!st||!st.anchor)return;
    const t=e.touches[0];const dx=t.clientX-st.x,dy=t.clientY-st.y;
    if(!mode){
      const vertical=/^vertical/.test(getComputedStyle(doc.documentElement).writingMode||'');
      const along=vertical?Math.abs(dy):Math.abs(dx),across=vertical?Math.abs(dx):Math.abs(dy);
      if(st.dbl&&Math.hypot(dx,dy)>6)mode='drag';
      else if(st.slide&&along>12&&along>across*2)mode='slide';
      else if(Math.hypot(dx,dy)>12){st=null;return;}
      if(!mode)return;
    }
    e.preventDefault();
    const focus=caret(t.clientX,t.clientY);if(!focus)return;
    const sel=doc.getSelection();
    // Double-tap-drag keeps the word already selected and extends from its start.
    let a=st.anchor;
    if(mode==='drag'&&sel.rangeCount&&!st.fixed){const r=sel.getRangeAt(0);st.fixed={node:r.startContainer,off:r.startOffset};}
    const an=st.fixed?st.fixed.node:a.startContainer,ao=st.fixed?st.fixed.off:a.startOffset;
    try{sel.setBaseAndExtent(an,ao,focus.startContainer,focus.startOffset);}catch(err){}
  },{passive:false});
  doc.addEventListener('touchend',e=>{
    const t=e.changedTouches[0];
    if(mode){e.preventDefault();lastTap={t:0,x:0,y:0};}
    else lastTap={t:Date.now(),x:t.clientX,y:t.clientY};
    st=null;mode='';
  },{passive:false});
}

/** Selects the dictionary word at a point; the selection bar then offers Look up, Search, Save… */
async function selectWordAt(doc,x,y){
  const p=pointText(doc,x,y);if(!p)return;
  let len=0;
  try{const r=await api('lookup',{text:p.chunk});len=r.items.length?r.matched.length:0;}catch(e){}
  // Unspaced scripts: the tapped character may be inside a word (表|現された) — try earlier starts too.
  if(/[\u3040-\u30ff\u3400-\u9fff\u0e00-\u0e7f]/.test(p.node.nodeValue[p.off]||'')){
    const text=p.node.nodeValue;
    for(let back=1;back<=4&&p.off-back>=0;back++){
      const c=text[p.off-back];if(!/[\p{L}\p{N}]/u.test(c))break;
      try{
        const r=await api('lookup',{text:text.slice(p.off-back)+p.chunk.slice(text.length-p.off)});
        const m=r.items.length?r.matched.length:0;
        if(m>back&&m>=len+back){len=m;p.off-=back;p.chunk=text.slice(p.off,p.off+30);back=0;break;}
      }catch(e){}
    }
  }
  if(!len){const m=p.chunk.match(/^[\p{L}\p{N}\p{M}]+/u);len=m?Math.min(m[0].length,12):1;}
  // Build the range across text nodes, skipping printed furigana.
  const range=doc.createRange();range.setStart(p.node,p.off);
  let remaining=len,node=p.node,start=p.off;
  const w=doc.createTreeWalker(doc.body,NodeFilter.SHOW_TEXT);w.currentNode=node;
  const inRuby=(n)=>n.parentElement&&n.parentElement.closest('rt,rp');
  while(node&&remaining>0){
    const avail=node.nodeValue.length-start;
    if(avail>=remaining){range.setEnd(node,start+remaining);remaining=0;break;}
    remaining-=avail;range.setEnd(node,node.nodeValue.length);
    do{node=w.nextNode();}while(node&&inRuby(node));
    start=0;
  }
  const sel=doc.getSelection();sel.removeAllRanges();sel.addRange(range);
}
function pointText(doc,x,y){
  const r=doc.caretRangeFromPoint?doc.caretRangeFromPoint(x,y):null;
  if(!r||r.startContainer.nodeType!==3)return null;
  let node=r.startContainer,off=Math.min(r.startOffset,node.nodeValue.length-1);
  const inRuby=(n)=>n.parentElement&&n.parentElement.closest('rt,rp');
  if(inRuby(node)){const ruby=node.parentElement.closest('ruby');const w0=ruby&&doc.createTreeWalker(ruby,NodeFilter.SHOW_TEXT);let b=w0&&w0.nextNode();while(b&&inRuby(b))b=w0.nextNode();if(!b)return null;node=b;off=0;}
  const text=node.nodeValue;
  if(off<0||!/[\p{L}\p{N}]/u.test(text[off]||'')){if(off>0&&/[\p{L}\p{N}]/u.test(text[off-1]))off--;else return null;}
  if(/[\uac00-\ud7a3\u0400-\u04ffA-Za-z]/.test(text[off])){while(off>0&&/[\p{L}\p{N}]/u.test(text[off-1]))off--;}
  let chunk=text.slice(off);const w=doc.createTreeWalker(doc.body,NodeFilter.SHOW_TEXT);w.currentNode=node;let t;
  while(chunk.length<30&&(t=w.nextNode()))if(!inRuby(t))chunk+=t.nodeValue;
  return {node,off,chunk:chunk.slice(0,30).trim()};
}
/** Text starting at the word under a point (skipping printed furigana), for look-ups. */
function textAtPoint(doc,x,y){
  const r=doc.caretRangeFromPoint?doc.caretRangeFromPoint(x,y):null;
  if(!r||r.startContainer.nodeType!==3)return '';
  let node=r.startContainer,off=Math.min(r.startOffset,node.nodeValue.length-1);
  const inRuby=(n)=>n.parentElement&&n.parentElement.closest('rt,rp');
  if(inRuby(node))return '';
  const text=node.nodeValue;
  if(off<0||!/[\p{L}\p{N}]/u.test(text[off]||'')){if(off>0&&/[\p{L}\p{N}]/u.test(text[off-1]))off--;else return '';}
  if(/[\uac00-\ud7a3\u0400-\u04ffA-Za-z]/.test(text[off])){while(off>0&&/[\p{L}\p{N}]/u.test(text[off-1]))off--;}
  let chunk=text.slice(off);const w=doc.createTreeWalker(doc.body,NodeFilter.SHOW_TEXT);w.currentNode=node;let t;
  while(chunk.length<30&&(t=w.nextNode()))if(!inRuby(t))chunk+=t.nodeValue;
  return chunk.slice(0,30).trim();
}
let audioPlayer=null;
function playAudio(dict,href){
  const clean=href.replace(/^sound:\/\//i,'').replace(/^\/+/,'').replace(/\\/g,'/');
  const url=`/d/${dict}/`+clean.split('/').map(encodeURIComponent).join('/');
  if(audioPlayer)audioPlayer.pause();
  audioPlayer=new Audio(url);
  audioPlayer.play().catch(e=>toast('Couldn’t play this sound ('+e.message+')'));
}
function playClip(clip){playAudio(clip.dict,clip.path);}
/** NHK notation (タベ＼ル, ツメコム━) → kana with a line over high morae and a corner at the pitch drop. */
function pitchHtml(accent){
  const text=String(accent||'').replace(/[○◯]/g,'');
  if(!text)return '';
  const morae=[];let drop=-1,flat=false;
  for(const ch of text){
    if(ch==='＼'||ch==='\\'||ch==='ꜜ'){drop=morae.length;continue;}
    if(ch==='━'||ch==='—'||ch==='‾'){flat=true;continue;}
    if(/[ゃゅょぁぃぅぇぉゎャュョァィゥェォヮ]/.test(ch)&&morae.length){morae[morae.length-1]+=ch;continue;}
    morae.push(ch);
  }
  if(!morae.length)return esc(text);
  if(drop<0&&!flat)flat=true;
  // Tokyo pattern: first mora low unless the drop comes right after it; high until the drop.
  const high=morae.map((m,i)=>drop===1?i===0:(i>0&&(drop<0||i<drop)));
  if(morae.length===1)high[0]=drop===1;
  return `<span class="pitch">${morae.map((m,i)=>`<span class="${high[i]?'hi':''}${i===drop-1?' dn':''}${i>0&&high[i]&&!high[i-1]?' up':''}">${esc(m)}</span>`).join('')}${flat&&drop<0?'<span class="flat" title="heiban">━</span>':''}</span>`;
}
function clipButtons(clips,id){
  return `<div class="clip-row" id="${id}">${clips.map((c,i)=>`<button class="audio-pill" data-rvclip="${i}" aria-label="Play pronunciation">${SPEAKER_SVG}${c.accent?pitchHtml(c.accent):''}</button>`).join('')}</div>`;
}
function itemAudio(it){try{return JSON.parse(it.audio||'[]');}catch(e){return [];}}
const SPEAKER_SVG='<svg viewBox="0 0 24 24" width="1em" height="1em" aria-hidden="true"><path d="M4 9.5h3.5L12 5v14l-4.5-4.5H4z" fill="currentColor"/><path d="M15.5 8.5a5 5 0 0 1 0 7M18 6a8.5 8.5 0 0 1 0 12" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>';
const AUDIO_RE=/\.(aac|mp3|m4a|ogg|oga|opus|wav|spx)(#.*)?$/i;

/**
 * Vertical text: hanging indents written for horizontal text (text-indent:-1em plus a left margin) push the first
 * character above the top edge in vertical-rl. Give the indent room at the line start (top) instead, and as a last
 * resort pad the page by whatever still starts above it.
 */
function fitVertical(doc){
  const px=v=>parseFloat(v)||0;
  doc.querySelectorAll('body *').forEach(el=>{
    const cs=getComputedStyle(el);
    if(cs.display==='inline'||cs.display==='none')return;
    const hang=-px(cs.textIndent);
    if(hang<=0)return;
    const room=px(cs.marginTop)+px(cs.paddingTop);
    if(room>=hang-.5)return;
    if(Math.abs(px(cs.marginLeft)-hang)<1)el.style.marginLeft='0';
    el.style.marginTop=(px(cs.marginTop)+hang-room)+'px';
  });
  let top=0;
  const walker=doc.createTreeWalker(doc.body,NodeFilter.SHOW_TEXT),range=doc.createRange();
  for(let n=walker.nextNode(),i=0;n&&i<4000;n=walker.nextNode(),i++){
    if(!n.textContent.trim())continue;
    range.selectNodeContents(n);
    for(const r of range.getClientRects())if(r.height)top=Math.min(top,r.top);
  }
  if(top<-.5)doc.body.style.paddingTop=(px(getComputedStyle(doc.body).paddingTop)-top+2)+'px';
}

function frameSetup(frame,opts){
  // Wires a dictionary-page iframe: sizing, zoom, links, audio, selection.
  const doc=frame.contentDocument;if(!doc||!doc.body)return null;
  doc.documentElement.style.zoom=String(opts.zoom||settings.zoom);
  // Dictionaries that are vertical by design (無礼語) get the vertical layout whatever the setting.
  opts.nativeVertical=/^vertical/.test(getComputedStyle(doc.body).writingMode||'');
  if(opts.nativeVertical){
    opts.vertical=true;opts.autoHeight=false;
    // Negative block-end margins meant for another renderer make columns overlap in Chromium.
    doc.querySelectorAll('body *').forEach(el=>{const cs=getComputedStyle(el);if(cs.display==='block'&&parseFloat(cs.marginLeft)<0)el.style.marginLeft='0';});
  }
  doc.documentElement.classList.toggle('kotoba-vertical',!!opts.vertical);
  if(opts.vertical)fitVertical(doc);
  // One consistent speaker button for every audio link (some pages reference icons their files don't contain).
  doc.querySelectorAll('a[href]').forEach(a=>{
    const h=a.getAttribute('href')||'';
    if(/^sound:\/\//i.test(h)||AUDIO_RE.test(h)){a.classList.add('kotoba-audio');a.innerHTML=SPEAKER_SVG;a.setAttribute('aria-label','Play pronunciation');}
  });
  doc.documentElement.classList.toggle('kotoba-dark',settings.theme==='dark');
  // Body rect already includes CSS zoom; documentElement.scrollHeight never shrinks below the frame itself.
  const resize=()=>{if(opts.autoHeight===false)return;const h=Math.ceil(doc.body.getBoundingClientRect().bottom+8);frame.style.height=Math.max(opts.min||60,h)+'px';};
  resize();
  try{new ResizeObserver(resize).observe(doc.body);}catch(e){}
  doc.querySelectorAll('img').forEach(img=>img.addEventListener('load',resize));
  doc.addEventListener('click',e=>{
    const a=e.target.closest('a');
    if(!a)return;
    e.preventDefault();
    const href=a.getAttribute('href')||'';
    if(!href)return;
    if(/^sound:\/\//i.test(href)||AUDIO_RE.test(href)){playAudio(opts.dict,href);return;}
    if(/^https?:/i.test(href)){toast('Web links are disabled in offline mode');return;}
    if(/^move\?/.test(href)||/^javascript:/i.test(href))return;
    opts.onLink&&handle(opts.onLink)(href,a);
  },true);
  doc.addEventListener('selectionchange',()=>selectionChanged(doc,opts));
  enableSlideSelect(doc,true);
  // Double-tap selects the word under the finger (sized to the longest dictionary match), like a long-press.
  doc.addEventListener('dblclick',e=>{
    if(e.target.closest('a'))return;
    e.preventDefault();
    handle(()=>selectWordAt(doc,e.clientX,e.clientY))();
  });
  return {doc,resize};
}

function entryPage(){
  const el=document.createElement('div');
  el.className='entry-page';
  el.innerHTML=`<div class="bar">
      <button class="icon-btn" data-act="back" aria-label="Back">${icon('back')}</button>
      <div class="title"><b data-f="title"></b><small data-f="dict"></small></div>
      <button class="icon-btn" data-act="bookmark" aria-label="Save to a folder">${icon('star')}</button>
      <button class="icon-btn" data-act="more" aria-label="More">${icon('more')}</button>
    </div>
    <div class="dict-tabs" data-f="tabs" hidden></div>
    <div class="entry-scroll" data-f="scroll">
      <iframe class="entry-frame" data-f="frame" title="Dictionary entry"></iframe>
      <div class="focus-note" data-f="focusnote" hidden></div>
      <div class="contents" data-f="contents" hidden></div>
      <div class="browse" data-f="browse"></div>
    </div>`;
  return el;
}

async function openEntry(target,existing){
  // target: {rec, dict, key, anchor?, alternatives?, highlight?}
  const state={...target,history:[]};
  const el=existing||pushPage(entryPage());
  const f=(n)=>el.querySelector(`[data-f="${n}"]`);
  el.querySelector('[data-act="back"]').onclick=()=>popPage();
  let current=null;// {doc, units, focus, info}

  async function load(t,pushHistory){
    if(pushHistory&&state.rec)state.history.push({rec:state.rec,dict:state.dict,key:state.key,anchor:state.anchor,alternatives:state.alternatives});
    const askedWhole=t.whole;
    Object.assign(state,t);state.whole=!!t.whole;
    const rec=await api('record',{rec:state.rec});
    state.dict=rec.dict;state.page=rec.key;state.dictName=rec.dictionary;state.saved=rec.saved||[];
    if(!state.key)state.key=rec.key;
    f('title').textContent=state.key;f('dict').textContent=rec.dictionary;
    renderTabs();
    const frame=f('frame');
    const vertical=settings.vertical;
    f('scroll').classList.toggle('vertical',vertical);
    frame.style.height=vertical?'':'120px';
    await new Promise((resolve)=>{frame.onload=resolve;frame.src=`/d/${state.dict}/${state.rec}.entry`;});
    const frameOpts={dict:state.dict,vertical,autoHeight:!vertical,min:120,onLink:onLink,source:()=>({...state,info:current&&current.info})};
    const wired=frameSetup(frame,frameOpts);
    if(!wired){toast('Could not display this entry');return;}
    if(frameOpts.nativeVertical&&!vertical){f('scroll').classList.add('vertical');frame.style.height='';}
    state.vertical=frameOpts.vertical;
    const doc=wired.doc;
    const {units,focus,target:anchorEl}=findFocus(doc,state.key,state.anchor);
    current={doc,units,focus,resize:wired.resize};
    // A kanji itself in a kanji dictionary: the whole entry is the point (readings, meanings, compounds).
    if(askedWhole===undefined&&kanjiHead(state.dict,state.key))state.whole=true;
    renderFocus();
    if(state.highlight)highlight(doc,state.highlight);
    if(anchorEl&&!focus){anchorEl.scrollIntoView({block:'center'});anchorEl.classList.add('kotoba-flash');}
    else if(anchorEl&&anchorEl!==focus){setTimeout(()=>{scrollToEl(anchorEl);anchorEl.classList.add('kotoba-flash');},50);}
    else f('scroll').scrollTop=0;
    updateBookmark();
    renderBrowse();
    if(settings.autoplay_entry){
      const scope=(!state.whole&&current.focus)||current.doc.body;
      const link=[...scope.querySelectorAll('a[href]')].find(a=>!a.closest('.kotoba-hidden')&&(/^sound:\/\//i.test(a.getAttribute('href'))||AUDIO_RE.test(a.getAttribute('href'))));
      if(link)playAudio(state.dict,link.getAttribute('href'));
    }
  }
  function scrollToEl(node){
    const frameTop=f('frame').offsetTop;
    const rect=node.getBoundingClientRect();
    f('scroll').scrollTop=frameTop+rect.top-80;
  }
  function renderFocus(){
    const {doc,units}=current;
    const focus=state.whole?null:current.focus;
    applyFocus(doc,focus,units);
    const shown=focus||units[0]||doc.body;
    current.info=unitInfo(shown,state.key);
    const others=units.filter(u=>u!==focus&&!(focus&&(focus.contains(u)||u.contains(focus))));
    const nested=focus?units.filter(u=>u!==focus&&focus.contains(u)):[];
    const hiddenCount=focus?others.length+nested.length:0;
    f('focusnote').hidden=!(hiddenCount||state.whole&&current.focus);
    f('focusnote').innerHTML=state.whole?`<button>Show only “${esc(unitInfo(current.focus,state.key).word||state.key)}”</button>`:`<button>Show whole page · ${hiddenCount} more ${hiddenCount===1?'entry':'entries'}</button>`;
    f('focusnote').firstElementChild.onclick=()=>{state.whole=!state.whole;renderFocus();current.resize();};
    // Contents: other entries on this page (subwords, phrases).
    const list=units.filter(u=>u!==current.focus);
    const infos=list.map(u=>unitInfo(u)).filter(i=>i.word);
    f('contents').hidden=!infos.length||state.whole;
    f('contents').innerHTML=`<div class="section-label">On this page · ${infos.length}</div>`+infos.slice(0,300).map((i,n)=>`<button class="row" data-u="${n}"><div class="hw">${esc(i.word)}${i.reading?`<span class="pg">${esc(i.reading)}</span>`:''}</div></button>`).join('');
    f('contents').querySelectorAll('[data-u]').forEach(b=>b.onclick=()=>{
      const info=infos[+b.dataset.u];
      state.history.push({rec:state.rec,dict:state.dict,key:state.key,anchor:state.anchor,alternatives:state.alternatives});
      state.key=info.word;current.focus=info.unit;state.whole=false;
      f('title').textContent=info.word;renderFocus();updateBookmark();current.resize();f('scroll').scrollTop=0;
    });
    setTimeout(()=>current.resize(),30);
  }
  function highlight(doc,query){
    const q=norm(query);if(!q)return;
    const walker=doc.createTreeWalker(doc.body,NodeFilter.SHOW_TEXT);
    let node,first=null;const hits=[];
    while((node=walker.nextNode())){
      if(node.parentElement.closest('.kotoba-hidden'))continue;
      const t=node.nodeValue;const i=nfkc(t).indexOf(nfkc(query).trim());
      if(i>=0)hits.push([node,i,nfkc(query).trim().length]);
      if(hits.length>40)break;
    }
    for(const [n,i,len] of hits){
      try{const r=doc.createRange();r.setStart(n,i);r.setEnd(n,Math.min(n.length,i+len));const m=doc.createElement('mark');m.className='kotoba-hit';r.surroundContents(m);if(!first)first=m;}catch(e){}
    }
    if(first)setTimeout(()=>scrollToEl(first),60);
  }
  async function renderTabs(){
    let alts=state.alternatives;
    if(!alts||!alts.some(a=>a.rec===state.rec)){
      alts=await api('exact',{key:state.key}).catch(()=>[]);
      if(!alts.some(a=>a.rec===state.rec))alts=[{rec:state.rec,dict:state.dict,dictionary:state.dictName,key:state.key}].concat(alts);
      state.alternatives=alts;
    }
    const seen=new Set();const tabs=alts.filter(a=>{const k=a.dict+':'+a.rec;if(seen.has(k))return false;seen.add(k);return true;});
    f('tabs').hidden=tabs.length<2;
    const dup=(a)=>tabs.filter(b=>b.dict===a.dict).length>1;
    f('tabs').innerHTML=tabs.map((a,i)=>`<button class="chip small ${a.rec===state.rec?'on':''}" data-t="${i}">${esc(shortName(a.dictionary))}${dup(a)&&a.page?' · '+esc(String(a.page).slice(0,10)):''}</button>`).join('');
    f('tabs').querySelectorAll('[data-t]').forEach(b=>b.onclick=handle(()=>{const a=tabs[+b.dataset.t];if(a.rec===state.rec)return;return load({rec:a.rec,dict:a.dict,key:state.key,anchor:'',alternatives:state.alternatives,highlight:state.highlight},false);}));
    const on=f('tabs').querySelector('.on');if(on)on.scrollIntoView({inline:'center',block:'nearest'});
  }
  async function renderBrowse(){
    const n=await api('neighbors',{rec:state.rec}).catch(()=>({}));
    const b=f('browse');
    b.innerHTML=`<button data-b="prev" ${n.prev?'':'disabled'}>${icon('prev')}<span>${esc(n.prev?n.prev.key:'')}</span></button><button data-b="next" ${n.next?'':'disabled'}><span>${esc(n.next?n.next.key:'')}</span>${icon('next')}</button>`;
    b.querySelectorAll('[data-b]').forEach(x=>x.onclick=handle(()=>{const t=n[x.dataset.b];if(t)return load({rec:t.rec,dict:state.dict,key:t.key,anchor:'',alternatives:null,highlight:''},true);}));
  }
  function updateBookmark(){
    const info=current&&current.info;
    const saved=(state.saved||[]).filter(s=>!info||cleanKey(s.headword)===cleanKey(info.word)||cleanKey(s.headword)===cleanKey(state.key));
    el.querySelector('[data-act="bookmark"]').classList.toggle('on',saved.length>0);
  }
  async function onLink(href){
    if(href.startsWith('#')){
      const id=href.slice(1);const t=current.doc.getElementById(id);
      if(t){
        if(t.closest('.kotoba-hidden')){
          let u=t;while(u&&u!==current.doc.body&&!isIn(UNIT,u))u=u.parentElement;
          if(u&&u!==current.doc.body){state.history.push({rec:state.rec,dict:state.dict,key:state.key,anchor:state.anchor,alternatives:state.alternatives});current.focus=u;state.key=unitInfo(u).word;f('title').textContent=state.key;renderFocus();updateBookmark();}
          else{state.whole=true;renderFocus();}
        }
        setTimeout(()=>{scrollToEl(t);t.classList.add('kotoba-flash');},60);
        return;
      }
    }
    const r=await api('reference',{dict:state.dict,ref:href});
    if(!r.rec){toast('That reference isn’t in this dictionary');return;}
    const sameRec=r.rec===state.rec;
    const t={rec:r.rec,dict:state.dict,key:r.key||'',anchor:r.anchor||'',alternatives:null,highlight:''};
    if(sameRec&&t.anchor){const node=current.doc.getElementById(t.anchor);if(node){return onLink('#'+t.anchor);}}
    await load(t,true);
  }
  el.querySelector('[data-act="bookmark"]').onclick=handle(()=>saveFromEntry(true));
  el.querySelector('[data-act="more"]').onclick=handle(()=>menuSheet(state.key,[
    {label:'Larger text',icon:'text',run:()=>{settings.zoom=Math.min(2.6,+(settings.zoom+0.15).toFixed(2));saveLocalSettings();current.doc.documentElement.style.zoom=settings.zoom;setTimeout(current.resize,30);toast('Text size '+Math.round(settings.zoom*100)+'%',1200);}},
    {label:'Smaller text',icon:'text',run:()=>{settings.zoom=Math.max(0.7,+(settings.zoom-0.15).toFixed(2));saveLocalSettings();current.doc.documentElement.style.zoom=settings.zoom;setTimeout(current.resize,30);toast('Text size '+Math.round(settings.zoom*100)+'%',1200);}},
    {label:settings.vertical?'Horizontal text':'Vertical text (縦書き)',icon:'vertical',run:()=>{settings.vertical=!settings.vertical;saveLocalSettings();return load({rec:state.rec,dict:state.dict,key:state.key,anchor:state.anchor,alternatives:state.alternatives,highlight:state.highlight,whole:state.whole},false);}},
    {label:state.whole?'Show only this entry':'Show whole page',icon:'expand',run:()=>{state.whole=!state.whole;renderFocus();}},
    ...(current.units.length>1?[{label:'Other entries on this page…',icon:'book',run:async()=>{
      const infos=current.units.filter(u=>u!==current.focus).map(u=>unitInfo(u)).filter(i=>i.word);
      const c=await menuSheet('On this page',infos.slice(0,200).map(i=>({label:i.word+(i.reading?'  '+i.reading:''),icon:'book',info:i})));
      if(!c)return;state.history.push({rec:state.rec,dict:state.dict,key:state.key,anchor:state.anchor,alternatives:state.alternatives});
      state.key=c.info.word;current.focus=c.info.unit;state.whole=false;f('title').textContent=c.info.word;renderFocus();updateBookmark();}}]:[]),
    {label:'Browse '+shortName(state.dictName)+' from here',icon:'book',run:()=>openBrowse(state.dict,{rec:state.rec,key:state.page})},
    '-',
    {label:'Search definitions for “'+state.key+'”',icon:'search',run:()=>{closeAllOverlays();showTab('search');$('q').value=state.key;$('q-clear').hidden=false;search.mode='definition';document.querySelectorAll('#modes [data-mode]').forEach(x=>x.classList.toggle('on',x.dataset.mode==='definition'));runSearch();}},
    {label:'Copy word',icon:'copy',run:()=>{Kotoba.copy(current.info.word||state.key);toast('Copied');}},
    {label:'Copy entry text',icon:'copy',run:()=>{Kotoba.copy(textOf(current.focus&&!state.whole?current.focus:current.doc.body));toast('Copied');}},
  ]));

  async function saveFromEntry(asCard){
    if(!current)return;
    const info=current.info;
    const unit=(!state.whole&&current.focus)||current.units[0]||current.doc.body;
    const parts=partsOf(unit);
    const existing=(state.saved||[]).find(s=>cleanKey(s.headword)===cleanKey(info.word));
    await openSaveSheet({
      review:asCard,
      headword:info.word||state.key,reading:info.reading,
      dict:state.dict,dict_name:state.dictName,page:state.page,anchor:unit.id||'',kind:unit===current.units[0]?'entry':'subentry',
      doc:current.doc,unit,parts,existing,
      onSaved:async()=>{const r=await api('record',{rec:state.rec});state.saved=r.saved||[];updateBookmark();},
    });
  }
  el.__entry={state,reload:()=>load({...state},false)};
  await load(state,false);
  return el;
}

// ---------- browsing a whole dictionary ----------
function indexLetters(sample){
  const t=sample.join('');
  if(/[\u3040-\u30ff]/.test(t))return ['あ','か','さ','た','な','は','ま','や','ら','わ'];
  if(/[\uac00-\ud7a3]/.test(t))return ['가','나','다','라','마','바','사','아','자','차','카','타','파','하'];
  if(/[\u0e00-\u0e7f]/.test(t))return ['ก','ข','ค','ง','จ','ช','ด','ต','ท','น','บ','ป','พ','ม','ย','ร','ล','ว','ส','ห','อ'];
  if(/[\u0400-\u04ff]/.test(t))return 'абвгдежзиклмнопрстуфхцчшэюя'.split('');
  if(/[a-z]/i.test(t))return 'abcdefghijklmnopqrstuvwxyz'.split('');
  return [];
}
async function openBrowse(dictId,start={}){
  const d=dictById(dictId);if(!d){toast('Dictionary not found');return;}
  const el=document.createElement('div');el.className='browse-page';
  el.innerHTML=`<div class="bar"><button class="icon-btn" data-a="back">${icon('back')}</button><div class="title"><b>${esc(d.name)}</b><small>${d.entries.toLocaleString()} pages · browse</small></div><button class="icon-btn" data-a="random" aria-label="Random word">${icon('shuffle')}</button></div>
    <div class="browse-body"><div class="scroll" data-f="list"></div></div>`;
  pushPage(el);
  el.querySelector('[data-a="back"]').onclick=()=>popPage();
  el.querySelector('[data-a="random"]').onclick=handle(async()=>{const r=await api('random',{dict:d.id});openEntry({rec:r.rec,dict:r.dict,key:r.key});});
  const list=el.querySelector('[data-f="list"]');
  await mountBrowse(list,dictId,start,list);
}
/**
 * A dictionary's headwords in index order, scrolling both ways without limit, with a jump box and an index rail.
 * `scroller` is the element that scrolls (the list itself on the browse page, the search screen inline).
 */
async function mountBrowse(container,dictId,start={},scroller){
  const d=dictById(dictId);if(!d)return;
  const kanjiMode=d.kind==='kanji';
  container.innerHTML=`<div class="browse-head"><div class="field small">${icon('search')}<input data-f="jump" type="search" placeholder="Jump to…" autocomplete="off" autocapitalize="off"></div><button class="icon-btn" data-f="random" aria-label="Random word">${icon('shuffle')}</button>${kanjiMode?`<button class="btn small" data-f="grid">Kanji grid</button>`:''}</div><div data-f="rows"></div><div class="index-rail" data-f="rail"></div>`;
  const f=(n)=>container.querySelector(`[data-f="${n}"]`);
  const list=f('rows');
  let rows=[],loading=false,atStart=false,atEnd=false,current=start.rec||0;
  f('random').onclick=handle(async()=>{const r=await api('random',{dict:d.id});openEntry({rec:r.rec,dict:r.dict,key:r.key});});
  if(kanjiMode)f('grid').onclick=handle(()=>openKanjiGrid(d.id));
  const rowHtml=(r)=>`<button class="brow ${r.rec===current?'cur':''}" data-rec="${r.rec}" data-key="${esc(r.key)}">${esc(r.key)}${r.strokes?`<small class="kmeta">${r.strokes}画${r.level?' · '+esc(levelLabel(r.level)):''}</small>`:''}</button>`;
  function render(keepAnchor){
    const anchor=keepAnchor?list.querySelector(`[data-rec="${keepAnchor}"]`):null;const top=anchor?anchor.getBoundingClientRect().top:0;
    list.innerHTML=(atStart?'<div class="brow-edge">— start of dictionary —</div>':'')+rows.map(rowHtml).join('')+(atEnd?'<div class="brow-edge">— end of dictionary —</div>':'');
    if(anchor){const a=list.querySelector(`[data-rec="${keepAnchor}"]`);if(a)scroller.scrollTop+=a.getBoundingClientRect().top-top;}
  }
  async function jump(prefix,rec){
    const r=await api('browse',{dict:d.id,dir:'from',prefix:prefix||'',limit:120,kanji:kanjiMode});
    rows=r.items;atStart=false;atEnd=rows.length<120;
    const targetRec=rec&&rows.some(x=>x.rec===rec)?rec:(rows[0]&&rows[0].rec);
    render();
    await more('before');
    const target=list.querySelector(`[data-rec="${targetRec}"]`);
    if(target)scroller.scrollTop+=target.getBoundingClientRect().top-scroller.getBoundingClientRect().top-(prefix?90:0);
  }
  async function more(dir){
    if(loading||(dir==='before'?atStart:atEnd)||!rows.length)return;
    loading=true;
    try{
      const edge=dir==='before'?rows[0]:rows[rows.length-1];
      const r=await api('browse',{dict:d.id,dir,norm:edge.norm,id:edge.rec,limit:120,kanji:kanjiMode});
      if(dir==='before'){if(r.items.length<120)atStart=true;rows=r.items.concat(rows);if(rows.length>900){rows=rows.slice(0,900);atEnd=false;}render(edge.rec);}
      else{if(r.items.length<120)atEnd=true;rows=rows.concat(r.items);if(rows.length>900){rows=rows.slice(rows.length-900);atStart=false;render(edge.rec);}else render();}
    }finally{loading=false;}
  }
  const onScroll=()=>{
    if(!container.isConnected||!list.isConnected){scroller.removeEventListener('scroll',onScroll);return;}
    const lr=list.getBoundingClientRect(),sr=scroller.getBoundingClientRect();
    if(lr.top>sr.top-600)more('before');
    if(lr.bottom-sr.bottom<900)more('after');
  };
  scroller.addEventListener('scroll',onScroll,{passive:true});
  list.onclick=handle(e=>{const b=e.target.closest('[data-rec]');if(!b)return;current=+b.dataset.rec;list.querySelectorAll('.brow.cur').forEach(x=>x.classList.remove('cur'));b.classList.add('cur');return openEntry({rec:current,dict:d.id,key:b.dataset.key});});
  f('jump').addEventListener('input',debounce(()=>handle(()=>jump(f('jump').value))(),200));
  const first=await api('browse',{dict:d.id,dir:'from',prefix:'',limit:40,kanji:kanjiMode});
  const letters=first.kanji?['1','2','3','4','5','6','7','8','9','10','11','12','13','14','15','16','18','20','24']:indexLetters(first.items.map(i=>i.key));
  f('rail').hidden=!letters.length;
  f('rail').innerHTML=letters.map(l=>`<button data-l="${esc(l)}">${esc(l)}</button>`).join('');
  const railJump=(e)=>{const t=document.elementFromPoint(e.touches[0].clientX,e.touches[0].clientY);if(t&&t.dataset&&t.dataset.l)handle(()=>jump(t.dataset.l))();};
  f('rail').addEventListener('touchmove',e=>{e.preventDefault();railJump(e);},{passive:false});
  f('rail').addEventListener('click',e=>{const t=e.target.closest('[data-l]');if(t)handle(()=>jump(t.dataset.l))();});
  await jump(start.key||'',start.rec);
}

// ---------- kanji grid ----------
const levelLabel=(l)=>/^\d+$/.test(l)?l+'級':l+'級';
const LEVEL_ORDER=['10','9','8','7','6','5','4','3','準2','2','準1','1'];
// Kangxi radical i (1–214) is U+2F00+i-1; its stroke count follows from its number.
const KANGXI_STROKES=[[1,1],[7,2],[30,3],[61,4],[95,5],[118,6],[147,7],[167,8],[176,9],[187,10],[195,11],[201,12],[205,13],[209,14],[211,15],[212,16],[214,17]];
// Variant shapes dictionaries use as radicals (亻 for 人, 氵 for 水…), with their own stroke counts.
const RADICAL_VARIANTS={'⺅':2,'亻':2,'⺉':2,'刂':2,'𠆢':2,'ハ':2,'⺋':2,'⺃':1,'乚':1,'⺆':2,'⺈':2,'⺊':2,'⺌':3,'⺍':3,'⺗':4,'⺣':4,'灬':4,'⺡':3,'氵':3,'⺘':3,'扌':3,'⺖':3,'忄':3,'⺾':3,'艹':3,'艸':6,'⻌':3,'⻍':3,'辶':3,'⻏':3,'⻖':3,'阝':3,'⺨':3,'犭':3,'⺭':4,'礻':4,'⻂':5,'衤':5,'⺲':5,'罒':5,'⺫':5,'⺟':4,'⺤':4,'爫':4,'⺹':4,'耂':4,'⺩':4,'王':4,'⺝':4,'月':4,'⺮':6,'⺶':6,'⺷':6,'⺺':6,'⻊':7,'⻗':8,'⻟':8,'⻠':8,'⻣':9,'⻤':9,'⻭':15,'⻩':12,'⻰':16,'⻲':16,'旡':4,'无':4,'尢':3,'尣':3,'丬':3,'爿':4,'⺪':3,'彑':3,'彐':3,'⺕':3,'⺄':1,'⺁':2,'巜':3,'𡿨':1,'丷':2,'⺧':4,'⺻':6,'⻎':3,'⻀':3,'囗':3,'⺐':3,'⺓':3,'⻞':8,'⻝':8,'⻡':9,'⻢':10,'⻥':11,'⻦':11,'⻧':11,'⻨':11,'⻪':12,'⻫':14,'⻬':14,'⻮':15,'⻯':16,'⻱':16,'⻳':17,'⺔':3,'⺙':4,'⺢':4,'⻃':6,'⻄':6,'訁':7,'⻘':8,'川':3,'𦥑':6};
// The Kangxi radical a variant shape stands for, so a radical filter survives switching dictionaries.
const RADICAL_BASE={'⺅':'人','亻':'人','𠆢':'人','⺉':'刀','刂':'刀','⺡':'水','氵':'水','⺢':'水','⺘':'手','扌':'手','⺖':'心','忄':'心','⺗':'心','⺾':'艸','艹':'艸','⻌':'辵','⻍':'辵','辶':'辵','⺨':'犬','犭':'犬','⺭':'示','礻':'示','⻂':'衣','衤':'衣','⺩':'玉','王':'玉','⺌':'小','⺍':'小','⺣':'火','灬':'火','⺤':'爪','爫':'爪','⺲':'网','罒':'网','⺫':'网','⺹':'老','耂':'老','⺮':'竹','⻊':'足','⻟':'食','⻠':'食','⻞':'食','訁':'言','⺙':'攴','⺔':'彐','彑':'彐','⺕':'彐','川':'巛','⻃':'襾','⻄':'襾','⻘':'靑','⺃':'乙','乚':'乙','⺋':'卩','ハ':'八','丷':'八','𦥑':'臼','⻏':'邑','⻖':'阜','阝':'阜','⺝':'月','⺟':'母','⻭':'齒','⻩':'黃','⻰':'龍','⻲':'龜','⻤':'鬼','⻣':'骨','⻢':'馬','⻥':'魚','⻦':'鳥','⻨':'麥'};
function radicalBase(r){return RADICAL_BASE[r]||r.normalize('NFKC');}
let RADICAL_STROKES=null;
function radicalStrokes(r){
  if(!RADICAL_STROKES){
    RADICAL_STROKES={...RADICAL_VARIANTS};
    for(let i=1;i<=214;i++){const ch=String.fromCharCode(0x2F00+i-1);const st=KANGXI_STROKES.filter(([from])=>from<=i).pop()[1];RADICAL_STROKES[ch]=st;RADICAL_STROKES[ch.normalize('NFKC')]=st;}
  }
  return RADICAL_STROKES[r]||RADICAL_STROKES[r.normalize('NFKC')]||0;
}

/** Radical picker: every radical in the dictionary as a grid grouped by stroke count, with a stroke row to jump. */
function pickRadical(radicals,current){
  return new Promise(resolve=>{
    const groups=new Map();
    for(const x of radicals){const st=radicalStrokes(x.v);if(!groups.has(st))groups.set(st,[]);groups.get(st).push(x);}
    const order=[...groups.keys()].sort((a,b)=>(a||99)-(b||99));
    const el=document.createElement('div');el.className='wl-page';
    el.innerHTML=`<div class="bar"><button class="icon-btn" data-a="back">${icon('back')}</button><div class="title"><b>部首 Radical</b><small>${radicals.length} radicals · by stroke count</small></div></div>
      <div class="chips rad-strokes">${order.map(st=>`<button class="chip small" data-st="${st}">${st?st+'画':'その他'}</button>`).join('')}</div>
      <div class="scroll" data-f="scroll">${order.map(st=>`<div class="section-label wl-section" data-sec="${st}">${st?st+'画':'その他'}</div><div class="rad-grid">${groups.get(st).map(x=>`<button class="rad-cell ${x.v===current?'on':''}" data-r="${esc(x.v)}"><span>${esc(x.v)}</span><small>${x.n}</small></button>`).join('')}</div>`).join('')}<div style="height:24px"></div></div>`;
    let picked=null;
    pushPage(el,{onClose:()=>resolve(picked)});
    const scroll=el.querySelector('[data-f="scroll"]');
    el.querySelector('[data-a="back"]').onclick=()=>popPage();
    el.querySelectorAll('[data-st]').forEach(b=>b.onclick=()=>{const h=el.querySelector(`[data-sec="${b.dataset.st}"]`);scroll.scrollTop=h.offsetTop-scroll.offsetTop;});
    el.querySelectorAll('[data-r]').forEach(b=>b.onclick=()=>{picked=b.dataset.r;popPage();});
    if(current){const c=el.querySelector('.rad-cell.on');if(c)requestAnimationFrame(()=>c.scrollIntoView({block:'center'}));}
  });
}

async function openKanjiGrid(dictId){
  const kanjiDicts=dicts.filter(d=>d.kind==='kanji'&&d.enabled);
  let dict=dictId||(kanjiDicts[0]&&kanjiDicts[0].id);
  if(!dict){toast('No kanji dictionary imported');return;}
  const filter={level:'',strokes:0,radical:'',flag:''};
  const el=document.createElement('div');el.className='grid-page';
  el.innerHTML=`<div class="bar"><button class="icon-btn" data-a="back">${icon('back')}</button><div class="title"><b>Kanji grid</b><small data-f="count"></small></div></div>
    <div class="grid-filters" data-f="filters"></div><div class="scroll"><div class="kgrid" data-f="grid"></div></div>`;
  pushPage(el);
  el.querySelector('[data-a="back"]').onclick=()=>popPage();
  const f=(n)=>el.querySelector(`[data-f="${n}"]`);
  let facets=null;const radicalPicks={};
  async function load(){
    const r=await api('kanji.grid',{dict,...filter});
    facets=facets&&facets.dict===dict?facets:{...r.facets,dict};
    f('count').textContent=`${r.total.toLocaleString()} kanji · ${shortName(dictById(dict).name)}`;
    const chip=(k,v,label,n)=>`<button class="chip small ${String(filter[k])===String(v)?'on':''}" data-k="${k}" data-v="${esc(v)}">${esc(label)}${n!=null?` <span class="n">${n}</span>`:''}</button>`;
    const levels=[...facets.levels].sort((a,b)=>LEVEL_ORDER.indexOf(a.v)-LEVEL_ORDER.indexOf(b.v));
    f('filters').innerHTML=
      (kanjiDicts.length>1?`<div class="chips">${kanjiDicts.map(d=>`<button class="chip small ${d.id===dict?'on':''}" data-dict="${d.id}">${esc(shortName(d.name))}</button>`).join('')}</div>`:'')+
      (levels.length?`<div class="chips">${chip('level','','All levels')}${levels.map(l=>chip('level',l.v,levelLabel(l.v),l.n)).join('')}</div>`:'')+
      `<div class="chips">${chip('flag','','All')}${facets.flags.filter(x=>x.n).map(x=>chip('flag',x.v,{常:'常用',教:'教育',人:'人名'}[x.v]||x.v,x.n)).join('')}${chip('strokes',0,'Any strokes')}${facets.strokes.map(x=>chip('strokes',x.v,x.v+'画',x.n)).join('')}</div>`+
      `<div class="chips"><button class="chip small ${filter.radical?'on':''}" data-rad="1">部首 ${filter.radical?esc(filter.radical):'· Any radical'} ▾</button>${filter.radical?chip('radical','','✕ Clear radical'):''}</div>`;
    f('filters').querySelectorAll('[data-k]').forEach(b=>b.onclick=handle(()=>{const k=b.dataset.k;filter[k]=k==='strokes'?+b.dataset.v:b.dataset.v;return load();}));
    f('filters').querySelector('[data-rad]').onclick=handle(async()=>{
      const r=await pickRadical(facets.radicals,filter.radical);
      if(r==null)return;filter.radical=r;radicalPicks[dict]=r;return load();
    });
    f('filters').querySelectorAll('[data-dict]').forEach(b=>b.onclick=handle(async()=>{
      // Keep every filter the other dictionary also has (1画, 常用, 氵 ↔ 水…); drop the rest.
      dict=+b.dataset.dict;
      const next=(await api('kanji.grid',{dict,strokes:999})).facets;// strokes ≥ 999 matches nothing: facets only
      if(filter.level&&!next.levels.some(x=>x.v===filter.level))filter.level='';
      if(filter.strokes&&!next.strokes.some(x=>+x.v===+filter.strokes))filter.strokes=0;
      if(filter.flag&&!next.flags.some(x=>x.v===filter.flag&&x.n))filter.flag='';
      if(filter.radical){
        // The form picked earlier in this dictionary, else the same form, else its biggest equivalent (漢検 ⺡ 269 vs 水 7).
        const base=radicalBase(filter.radical),mine=radicalPicks[dict];
        const same=next.radicals.filter(x=>radicalBase(x.v)===base).sort((a,b)=>b.n-a.n);
        const m=(mine&&radicalBase(mine)===base&&same.find(x=>x.v===mine))||same.find(x=>x.v===filter.radical&&x.n>=same[0].n/4)||same[0];
        filter.radical=m?m.v:'';
      }
      facets={...next,dict};
      return load();
    }));
    f('grid').innerHTML=r.cells.map(c=>`<button class="kcell" data-rec="${c.rec}" data-ch="${esc(c.char)}"><span>${esc(c.char)}</span><small>${c.strokes||''}${c.level?' · '+esc(levelLabel(c.level)):''}</small></button>`).join('')||'<div class="empty">No kanji match these filters.</div>';
  }
  f('grid').onclick=handle(e=>{const b=e.target.closest('[data-rec]');if(!b)return;return openEntry({rec:+b.dataset.rec,dict,key:b.dataset.ch});});
  await load();
}

// ---------- text selection ----------
let selection={text:'',doc:null,opts:null};
function selectionChanged(doc,opts){
  const sel=doc.getSelection();const text=sel?sel.toString().trim():'';
  if(!text){if(selection.doc===doc)hideSelbar();return;}
  selection={text,doc,opts:opts||{},context:sentenceAround(sel)};
  showSelbar();
}
document.addEventListener('selectionchange',()=>{
  const active=document.activeElement;
  if(active&&(active.tagName==='INPUT'||active.tagName==='TEXTAREA'))return;
  const sel=document.getSelection();const text=sel?sel.toString().trim():'';
  if(text){selection={text,doc:document,opts:{},context:sentenceAround(sel)};showSelbar();}
  else if(selection.doc===document)hideSelbar();
});
function sentenceAround(sel){
  try{
    const range=sel.getRangeAt(0);
    let block=range.startContainer.nodeType===3?range.startContainer.parentElement:range.startContainer;
    while(block&&block.parentElement&&textOf(block).length<30)block=block.parentElement;
    const full=block?textOf(block):'';
    const text=sel.toString().trim();
    const i=full.indexOf(text);if(i<0)return '';
    const before=full.slice(0,i),after=full.slice(i+text.length);
    const start=Math.max(before.search(/[^。！？!?．.\n「」]*$/),0);
    const endMatch=after.search(/[。！？!?．\n」]/);
    return (before.slice(start)+text+(endMatch<0?after:after.slice(0,endMatch+1))).trim().slice(0,300);
  }catch(e){return '';}
}
function showSelbar(){
  const bar=$('selbar');
  bar.innerHTML=[
    ['lookup','search','Look up'],...(selection.opts&&selection.opts.reader?[['highlight','edit','Highlight']]:[['search','book','Search']]),['copy','copy','Copy'],['card','star','Save'],['translate','share','Translate'],['share','share','Share'],
  ].map(([a,i,l])=>`<button data-sel="${a}">${icon(i)}${l}</button>`).join('');
  bar.hidden=false;
  bar.querySelectorAll('[data-sel]').forEach(b=>{
    b.addEventListener('pointerdown',e=>e.preventDefault());
    b.onclick=()=>window.selectionAction(b.dataset.sel);
  });
}
function hideSelbar(){$('selbar').hidden=true;}
function clearSelections(){
  try{document.getSelection().removeAllRanges();}catch(e){}
  document.querySelectorAll('iframe').forEach(fr=>{try{fr.contentDocument.getSelection().removeAllRanges();}catch(e){}});
}
function currentSelection(){
  if(selection.text)return selection;
  for(const fr of document.querySelectorAll('iframe')){
    try{const t=fr.contentDocument.getSelection().toString().trim();if(t)return {text:t,doc:fr.contentDocument,opts:{},context:sentenceAround(fr.contentDocument.getSelection())};}catch(e){}
  }
  const t=document.getSelection().toString().trim();
  return t?{text:t,doc:document,opts:{},context:''}:null;
}
window.selectionAction=(action)=>handle(async()=>{
  const sel=currentSelection();if(!sel||!sel.text)return;
  const text=sel.text.slice(0,2000);
  hideSelbar();
  if(action==='highlight'){if(!readerHooks.highlight())toast('Highlights work inside books');return;}
  if(action==='copy'){Kotoba.copy(text);toast('Copied');clearSelections();return;}
  if(action==='share'){Kotoba.share(text);return;}
  if(action==='translate'){Kotoba.translate(text);clearSelections();return;}
  if(action==='search'){clearSelections();closeAllOverlays();showTab('search');$('q').value=text.slice(0,100);$('q-clear').hidden=false;search.mode='headword';document.querySelectorAll('#modes [data-mode]').forEach(x=>x.classList.toggle('on',x.dataset.mode==='headword'));await runSearch();remember();return;}
  if(action==='lookup'){await lookupSheet(text,sel);return;}
  if(action==='card'){
    const src=sel.opts&&sel.opts.source?sel.opts.source():null;
    if(text.length<=24){
      const r=await api('lookup',{text});
      if(r.items.length&&r.matched.length>=Math.min(text.length,2)){
        clearSelections();
        await saveLookupResult(r.items[0],r.key,sel.context&&sel.context!==text?sel.context:'',r.items,true,sel.opts&&sel.opts.reader?readerHooks.bookTitle:'');
        return;
      }
    }
    clearSelections();
    await openSaveSheet({review:true,kind:'selection',headword:src?(src.info&&src.info.word)||src.key:text.slice(0,60),reading:'',back:text,dict:src?src.dict:0,dict_name:src?src.dictName:'',page:src?src.page:'',context:''});
  }
})();

async function lookupSheet(text,sel,extra={}){
  const r=await api('lookup',{text,lang:extra.lang||''});
  if(!r.items.length){
    const s=openSheet(`<div class="sheet-body"><p class="hint">No headword starts with “${esc(text.slice(0,40))}”.</p></div><div class="sheet-foot"><button class="btn wide" id="lk-search">${icon('search')} Search definitions</button><button class="btn primary wide" id="lk-card">${icon('star')} Save</button></div>`,{title:'Look up',onClose:extra.onClose});
    s.sheet.querySelector('#lk-search').onclick=()=>{closeSheet(s);closeAllOverlays();showTab('search');$('q').value=text.slice(0,100);search.mode='definition';document.querySelectorAll('#modes [data-mode]').forEach(x=>x.classList.toggle('on',x.dataset.mode==='definition'));runSearch();};
    s.sheet.querySelector('#lk-card').onclick=()=>{closeSheet(s);openSaveSheet({review:true,kind:'selection',headword:text.slice(0,60),back:'',context:sel&&sel.context||''});};
    return;
  }
  let index=0;
  const items=r.items;
  const formNote=r.explain?`<div class="form-note"><b>${esc(r.matched)}</b> → ${esc(r.key)} · ${esc(r.explain)}</div>`:'';
  const s=openSheet(`${formNote}<div class="dict-tabs" id="lk-tabs" ${items.length<2?'hidden':''}></div><div class="sheet-body" style="padding:0"><iframe class="lookup-frame" id="lk-frame"></iframe></div><div class="sheet-foot"><button class="btn wide" id="lk-open">${icon('book')} Open</button><button class="btn primary wide" id="lk-card">${icon('star')} Save</button></div>`,{title:r.key,tall:false,onClose:extra.onClose});
  const frame=s.sheet.querySelector('#lk-frame');
  let wired=null,focusUnit=null;
  const show=async(i)=>{
    index=i;const it=items[i];
    s.sheet.querySelector('#lk-tabs').innerHTML=items.map((x,n)=>`<button class="chip small ${n===i?'on':''}" data-t="${n}">${esc(shortName(x.dictionary))}</button>`).join('');
    s.sheet.querySelectorAll('#lk-tabs [data-t]').forEach(b=>b.onclick=()=>show(+b.dataset.t));
    await new Promise(res=>{frame.onload=res;frame.src=`/d/${it.dict}/${it.rec}.entry`;});
    wired=frameSetup(frame,{dict:it.dict,autoHeight:false,onLink:async(href)=>{const ref=await api('reference',{dict:it.dict,ref:href});if(ref.rec){closeSheet(s);openEntry({rec:ref.rec,dict:it.dict,key:ref.key||'',anchor:ref.anchor||''});}},source:()=>({dict:it.dict,dictName:it.dictionary,key:r.key,page:it.page,info:focusUnit?unitInfo(focusUnit,r.key):null})});
    if(!wired)return;
    const f=findFocus(wired.doc,r.key,'');focusUnit=f.focus;if(!kanjiHead(it.dict,r.key))applyFocus(wired.doc,f.focus,f.units);
    frame.style.height=Math.min(window.innerHeight*0.55,Math.max(160,wired.doc.body.getBoundingClientRect().bottom+10))+'px';
  };
  s.sheet.querySelector('#lk-open').onclick=()=>{closeSheet(s);const it=items[index];openEntry({...it,key:r.key,alternatives:items});};
  s.sheet.querySelector('#lk-card').onclick=handle(()=>{const it=items[index];closeSheet(s);return saveLookupResult(it,r.key,sel&&sel.context||'',items,true,extra.book||'');});
  await show(0);
}

/** Loads an entry into a hidden frame to extract its focused unit and parts, then opens the save sheet. */
async function loadEntryDoc(rec,dict,key){
  const frame=document.createElement('iframe');
  frame.style.cssText='position:absolute;left:-9999px;top:0;width:360px;height:600px;visibility:hidden';
  document.body.appendChild(frame);
  await new Promise(res=>{frame.onload=res;frame.src=`/d/${dict}/${rec}.entry`;});
  const doc=frame.contentDocument;
  const f=findFocus(doc,key,'');
  const unit=f.focus||f.units[0]||doc.body;
  return {frame,doc,unit,units:f.units,info:unitInfo(unit,key),parts:partsOf(unit)};
}
async function saveLookupResult(item,key,context,alternatives,review=true,source=''){
  const loaded=await loadEntryDoc(item.rec,item.dict,key);
  const rec=await api('record',{rec:item.rec});
  await openSaveSheet({review,headword:loaded.info.word||key,reading:loaded.info.reading,dict:item.dict,dict_name:item.dictionary,page:rec.key,anchor:loaded.unit.id||'',kind:'entry',doc:loaded.doc,unit:loaded.unit,parts:loaded.parts,context,alternatives,note:source?'📖 '+source:'',onClose:()=>loaded.frame.remove()});
}

// ---------- save sheet (bookmark / flashcard) ----------
async function openSaveSheet(o){
  const folders=await api('folders');
  let folderId=Number(localStorage.getItem('lastFolder')||1);
  if(!folders.some(f=>f.id===folderId))folderId=1;
  if(o.existing)folderId=o.existing.folder_id;
  if(o.item)folderId=o.item.folder_id;
  let parts=o.parts||[];
  let checked=parts.map(()=>true);
  let edited=!!o.back&&!parts.length;
  let source={dict:o.dict||0,dict_name:o.dict_name||'',page:o.page||'',anchor:o.anchor||'',kind:o.kind||'entry',doc:o.doc,unit:o.unit};
  const composeText=()=>parts.filter((p,i)=>checked[i]&&p.kind!=='Heading').map(p=>p.text).join('\n')||parts.filter((p,i)=>checked[i]).map(p=>p.text).join('\n');
  const html=`<div class="sheet-body">
    <label class="f">Word</label><input class="input big" id="sv-word" maxlength="500">
    <label class="f">Reading</label><input class="input" id="sv-reading" maxlength="200" placeholder="Optional">
    <div id="sv-source-wrap"><label class="f">Definition from</label><div class="chips" id="sv-sources" style="margin-top:6px"></div></div>
    <div id="sv-parts-wrap"><label class="f">Keep these parts</label><div class="parts" id="sv-parts"></div></div>
    <label class="f">Card back</label><textarea class="textarea" id="sv-back" rows="4" placeholder="Definition, translation or notes for the back of the card"></textarea>
    <p class="hint" id="sv-edit-hint" hidden>You edited the text, so the card shows your wording instead of the dictionary layout. <button id="sv-restore" style="color:var(--accent);font-weight:600">Use dictionary text</button></p>
    <label class="f">Example / context</label><textarea class="textarea" id="sv-context" rows="2" style="min-height:56px" placeholder="Optional sentence"></textarea>
    <label class="f">Note</label><textarea class="textarea" id="sv-note" rows="2" style="min-height:56px" placeholder="Optional"></textarea>
    <div id="sv-audio-wrap" hidden><label class="f">Pronunciation audio</label><div id="sv-audio" class="clips"></div></div>
    <label class="f">Folder</label><div class="chips" id="sv-folders" style="margin-top:6px;flex-wrap:wrap"></div>
    <p class="hint">Everything in a folder is part of that folder’s deck. Choose which decks you study in Review.</p>
  </div>
  <div class="sheet-foot">${o.existing?`<button class="btn danger" id="sv-remove">${icon('trash')}</button>`:''}<button class="btn wide" data-close>Cancel</button><button class="btn primary wide" id="sv-save">${icon('check')} Save</button></div>`;
  const s=openSheet(html,{title:o.existing||o.item?'Saved word':'Save to folder',tall:true,onClose:o.onClose});
  const q=(id)=>s.sheet.querySelector('#'+id);
  q('sv-word').value=o.headword||'';q('sv-reading').value=o.reading||'';
  q('sv-context').value=o.context||'';q('sv-note').value=o.note||'';

  const renderParts=()=>{
    q('sv-parts-wrap').hidden=!parts.length;
    q('sv-parts').innerHTML=parts.map((p,i)=>`<label class="part ${checked[i]?'':'off'}"><input type="checkbox" data-p="${i}" ${checked[i]?'checked':''}><div><small>${esc(p.kind)}${p.kind==='Meaning'?' '+(parts.slice(0,i+1).filter(x=>x.kind==='Meaning').length):''}</small>${esc(p.text.length>220?p.text.slice(0,220)+'…':p.text)}</div></label>`).join('');
    q('sv-parts').querySelectorAll('[data-p]').forEach(c=>c.onchange=()=>{checked[+c.dataset.p]=c.checked;c.closest('.part').classList.toggle('off',!c.checked);if(!edited)q('sv-back').value=composeText();});
  };
  const renderFolders=()=>{
    q('sv-folders').innerHTML=folders.map(f=>`<button class="chip small ${f.id===folderId?'on':''}" data-fo="${f.id}">${esc(f.name)}</button>`).join('')+`<button class="chip small" id="sv-newfolder">${icon('plus')} New</button>`;
    q('sv-folders').querySelectorAll('[data-fo]').forEach(b=>b.onclick=()=>{folderId=+b.dataset.fo;renderFolders();});
    q('sv-newfolder').onclick=handle(async()=>{const name=await prompt2('New folder','', 'e.g. JLPT N1, Thai verbs');if(!name)return;const r=await api('folder.save',{name});folders.push({id:r.id,name});folderId=r.id;renderFolders();});
  };
  const renderSources=async()=>{
    let alts=o.alternatives;
    if(!alts&&o.headword&&source.dict)alts=await api('exact',{key:o.headword}).catch(()=>[]);
    alts=(alts||[]).filter((a,i,arr)=>arr.findIndex(b=>b.dict===a.dict)===i);
    if(source.dict&&!alts.some(a=>a.dict===source.dict))alts.unshift({dict:source.dict,dictionary:source.dict_name,rec:0});
    q('sv-source-wrap').hidden=alts.length<2;
    q('sv-sources').innerHTML=alts.map((a,i)=>`<button class="chip small ${a.dict===source.dict?'on':''}" data-src="${i}">${esc(shortName(a.dictionary))}</button>`).join('');
    q('sv-sources').querySelectorAll('[data-src]').forEach(b=>b.onclick=handle(async()=>{
      const a=alts[+b.dataset.src];if(a.dict===source.dict||!a.rec)return;
      const loaded=await loadEntryDoc(a.rec,a.dict,q('sv-word').value||o.headword);
      const rec=await api('record',{rec:a.rec});
      source={dict:a.dict,dict_name:a.dictionary,page:rec.key,anchor:loaded.unit.id||'',kind:'entry',doc:loaded.doc,unit:loaded.unit,frame:loaded.frame};
      parts=loaded.parts;checked=parts.map(()=>true);edited=false;
      if(loaded.info.reading&&!q('sv-reading').value)q('sv-reading').value=loaded.info.reading;
      renderParts();q('sv-back').value=composeText();q('sv-edit-hint').hidden=true;renderSources();
    }));
  };
  // Audio: clips from every dictionary with sound for this word (NHK first), chosen per card.
  let clips=[],chosenClips=o.item?itemAudio(o.item):null;
  const clipKey=(c)=>c.dict+':'+c.path;
  const renderAudio=async(word)=>{
    clips=word?await api('audio',{key:word,reading:q('sv-reading').value.trim(),dict:source.dict||0}).catch(()=>[]):[];
    for(const c of (chosenClips||[]))if(!clips.some(x=>clipKey(x)===clipKey(c)))clips.unshift(c);
    if(chosenClips===null)chosenClips=clips.length?[clips[0]]:[];
    q('sv-audio-wrap').hidden=!clips.length;
    q('sv-audio').innerHTML=clips.map((c,i)=>`<div class="clip ${chosenClips.some(x=>clipKey(x)===clipKey(c))?'on':''}"><button class="clip-play" data-play="${i}" aria-label="Play">${icon('play')}</button><button class="clip-pick" data-clip="${i}"><b>${esc(shortName(c.dictionary))}</b>${c.accent?`<span class="clip-accent">${pitchHtml(c.accent)}</span>`:`<small>${esc(c.label||c.path)}</small>`}</button></div>`).join('')+`<p class="hint">Tap ▶ to listen, tap a clip to attach or remove it.</p>`;
    q('sv-audio').querySelectorAll('[data-play]').forEach(b=>b.onclick=()=>playClip(clips[+b.dataset.play]));
    q('sv-audio').querySelectorAll('[data-clip]').forEach(b=>b.onclick=()=>{const c=clips[+b.dataset.clip];if(chosenClips.some(x=>clipKey(x)===clipKey(c)))chosenClips=chosenClips.filter(x=>clipKey(x)!==clipKey(c));else chosenClips.push(c);b.parentElement.classList.toggle('on');});
  };
  renderParts();renderFolders();renderSources();renderAudio(o.headword||'');
  q('sv-word').addEventListener('change',()=>renderAudio(q('sv-word').value.trim()));
  q('sv-back').value=o.back!==undefined&&o.back!==''?o.back:composeText();
  if(o.item&&o.item.back_html)edited=false;
  q('sv-back').oninput=()=>{edited=true;q('sv-edit-hint').hidden=!parts.length;};
  if(q('sv-restore'))q('sv-restore').onclick=()=>{edited=false;q('sv-back').value=composeText();q('sv-edit-hint').hidden=true;};
  if(q('sv-remove'))q('sv-remove').onclick=handle(async()=>{if(!await confirm2('Remove saved word?','“'+o.existing.headword+'” will be removed from '+o.existing.folder+'.','Remove',true))return;await api('item.delete',{ids:[o.existing.id]});closeSheet(s);toast('Removed');o.onSaved&&o.onSaved();});
  q('sv-save').onclick=handle(async()=>{
    const headword=q('sv-word').value.trim();
    if(!headword){toast('Add the word first');return;}
    const selected=parts.filter((p,i)=>checked[i]);
    let back_html=o.item&&!edited?o.item.back_html||'':'';
    if(!edited&&selected.length&&source.doc)back_html=partsHtml(source.doc,source.unit,selected);
    const back=q('sv-back').value.trim();
    if(!back&&!back_html){toast('Add something for the back of the card');return;}
    const data={id:o.item?o.item.id:(o.existing?o.existing.id:0),folder_id:folderId,headword,reading:q('sv-reading').value.trim(),back,back_html:edited?'':back_html,note:q('sv-note').value.trim(),context:q('sv-context').value.trim(),dict:source.dict||0,dict_name:source.dict_name||'',page:source.page||'',anchor:source.anchor||'',kind:source.kind||'entry',review:o.item?!!o.item.review:true,audio:JSON.stringify((chosenClips||[]).map(c=>({dict:c.dict,path:c.path,dictionary:c.dictionary,label:c.label,accent:c.accent||''})))};
    await api('item.save',data);
    try{localStorage.setItem('lastFolder',String(folderId));}catch(e){}
    closeSheet(s);
    toast('Saved to '+(folders.find(f=>f.id===folderId)||{name:'folder'}).name);
    refreshBadge();
    o.onSaved&&o.onSaved();
  });
  return s;
}

// ---------- vocabulary ----------
async function renderFolders(){
  const folders=await api('folders');
  const total=folders.reduce((a,f)=>a+f.count,0);
  $('folders-sub').textContent=total?`${total} saved ${total===1?'word':'words'} · ${folders.length} ${folders.length===1?'folder':'folders'}`:'Folders of words you’ve kept';
  $('folder-list').innerHTML=`<button class="folder-row" data-f="0"><span class="fi">${icon('bookmark')}</span><span class="fb"><b>All saved words</b><small>${total} items</small></span></button>`+
    folders.map(f=>`<div class="folder-row"><button class="fi" data-f="${f.id}">${icon('folder')}</button><button class="fb" data-f="${f.id}" style="text-align:left"><b>${esc(f.name)}</b><small>${f.count} ${f.count===1?'word':'words'}${f.fresh?` · ${f.fresh} new`:''}${f.due?` · ${f.due} due`:''}</small></button><label class="toggle" title="Study this folder"><input type="checkbox" data-study="${f.id}" ${f.study?'checked':''}><span></span></label></div>`).join('')+
    `<p class="hint" style="padding:0 18px">Switch on the folders you want to study — they become your review decks.</p>`+
    `<div style="padding:16px"><button class="btn wide" id="new-folder" style="width:100%">${icon('plus')} New folder</button></div>
     <div style="padding:0 16px 24px;display:flex;gap:10px"><button class="btn small wide" id="new-word">${icon('edit')} Add your own word</button></div>`;
  $('folder-list').querySelectorAll('[data-f]').forEach(b=>b.onclick=handle(()=>openFolder(+b.dataset.f,folders)));
  $('folder-list').querySelectorAll('[data-study]').forEach(c=>c.onchange=handle(async()=>{await api('folder.study',{id:+c.dataset.study,study:c.checked});toast(c.checked?'Studying this folder':'Folder paused in review',1400);refreshBadge();}));
  $('new-folder').onclick=handle(async()=>{const name=await prompt2('New folder','','e.g. JLPT N1, Thai verbs');if(!name)return;await api('folder.save',{name});renderFolders();});
  $('new-word').onclick=handle(()=>openSaveSheet({review:true,kind:'custom',headword:'',back:'',onSaved:renderFolders}));
}

async function openFolder(folderId,folders){
  const folder=folderId?folders.find(f=>f.id===folderId):{id:0,name:'All saved words'};
  const el=document.createElement('div');
  el.innerHTML=`<div class="bar"><button class="icon-btn" data-a="back">${icon('back')}</button><div class="title"><b>${esc(folder.name)}</b><small data-f="count"></small></div><button class="icon-btn" data-a="view" aria-label="List or grid"></button><button class="icon-btn" data-a="review" aria-label="Review this folder">${icon('card')}</button><button class="icon-btn" data-a="more">${icon('more')}</button></div>
    <div class="searchline"><div class="field">${icon('search')}<input data-f="q" type="search" placeholder="Filter" autocomplete="off"></div></div>
    <div class="chips" style="padding:6px 14px 8px" data-f="filters"></div>
    <div class="scroll" data-f="list"></div>
    <div class="selectbar" data-f="selectbar" hidden></div>`;
  pushPage(el);
  const f=(n)=>el.querySelector(`[data-f="${n}"]`);
  let filter='',sort='updated',items=[],selecting=false,view='list';const selected=new Set();
  try{view=localStorage.getItem('folderView')||'list';}catch(e){}
  const viewBtn=el.querySelector('[data-a="view"]');
  const setViewIcon=()=>{viewBtn.innerHTML=icon(view==='list'?'grid':'list');viewBtn.setAttribute('aria-label',view==='list'?'Show as grid':'Show as list');};
  setViewIcon();
  viewBtn.onclick=()=>{view=view==='list'?'grid':'list';try{localStorage.setItem('folderView',view);}catch(e){}setViewIcon();render();};
  el.querySelector('[data-a="back"]').onclick=()=>{if(selecting){selecting=false;selected.clear();render();return;}popPage();};
  el.querySelector('[data-a="review"]').onclick=handle(()=>startReview(folderId));
  el.querySelector('[data-a="more"]').onclick=handle(()=>menuSheet(folder.name,[
    {label:'Select items',icon:'check',run:()=>{selecting=true;render();}},
    {label:'Sort: '+(sort==='updated'?'recent first':sort==='headword'?'A → Z':'by due date')+' (change)',icon:'refresh',run:()=>{sort=sort==='updated'?'headword':sort==='headword'?'due':'updated';load();}},
    '-',
    {label:'Export for Anki (TSV)',icon:'export',run:()=>Kotoba.exportFile(`kotoba-${folder.name}.txt`,'tsv',JSON.stringify({folder:folderId,html:true}))},
    {label:'Export spreadsheet (CSV)',icon:'export',run:()=>Kotoba.exportFile(`kotoba-${folder.name}.csv`,'csv',JSON.stringify({folder:folderId}))},
    ...(folderId>1?['-',
      {label:'Rename folder',icon:'edit',run:async()=>{const name=await prompt2('Rename folder',folder.name);if(!name)return;await api('folder.save',{id:folderId,name});folder.name=name;el.querySelector('.title b').textContent=name;}},
      {label:'Delete folder',icon:'trash',danger:true,run:async()=>{
        const choice=await menuSheet('Delete “'+folder.name+'”?',[{label:'Delete folder, move words to Inbox',icon:'move',v:'move'},{label:'Delete folder and its words',icon:'trash',danger:true,v:'all'}]);
        if(!choice)return;await api('folder.delete',{id:folderId,items:choice.v==='all'});popPage();toast('Folder deleted');}},
    ]:[]),
  ]));
  f('q').oninput=debounce(()=>load(),150);
  const renderFilters=()=>{
    f('filters').innerHTML=[['','All'],['due','Due'],['new','New'],['suspended','Suspended']].map(([v,l])=>`<button class="chip small ${filter===v?'on':''}" data-v="${v}">${l}</button>`).join('');
    f('filters').querySelectorAll('[data-v]').forEach(b=>b.onclick=()=>{filter=b.dataset.v;renderFilters();load();});
  };
  const stateBadge=(it)=>{
    if(!it.review)return '<span class="state mark">Suspended</span>';
    if(it.state===0)return '<span class="state new">New</span>';
    if(it.due<=Date.now()/1000)return '<span class="state due">Due</span>';
    if(it.state===1||it.state===3)return '<span class="state learn">Learning</span>';
    return `<span class="state rev">${fmtDue(it.due)}</span>`;
  };
  function render(){
    f('count').textContent=`${items.length} ${items.length===1?'item':'items'}`;
    f('list').innerHTML=!items.length?'':view==='grid'?`<div class="wl-grid">${items.map((it,i)=>`<button class="wl-cell item-cell ${selected.has(it.id)?'selected':''}" data-i="${i}">${selecting?`<span class="check">${icon('check')}</span>`:''}<span>${esc(it.headword)}</span>${it.reading&&it.reading!==it.headword?`<small>${esc(it.reading)}</small>`:''}</button>`).join('')}</div>`:'';
    if(items.length&&view==='list')f('list').innerHTML=items.map((it,i)=>`<button class="item-row ${selected.has(it.id)?'selected':''}" data-i="${i}">${selecting?`<span class="check">${icon('check')}</span>`:''}<span class="ib"><span class="hw">${esc(it.headword)}</span>${it.reading?`<span class="rd">${esc(it.reading)}</span>`:''}<p>${esc(it.back||it.context||'')}</p><span class="meta" style="display:flex;gap:5px;margin-top:6px;flex-wrap:wrap">${stateBadge(it)}${it.dict_name?`<span class="tag muted">${esc(shortName(it.dict_name))}</span>`:''}${!folderId?`<span class="tag muted">${esc(it.folder)}</span>`:''}</span></span></button>`).join('');
    if(!items.length)f('list').innerHTML=
      `<div class="empty"><span class="glyph">空</span><h2>Nothing here yet</h2>Tap ☆ or ＋ on any dictionary entry to keep it here.</div>`;
    f('list').querySelectorAll('[data-i]').forEach(b=>{
      const it=items[+b.dataset.i];
      let timer=null;
      b.addEventListener('touchstart',()=>{timer=setTimeout(()=>{timer=null;if(!selecting){selecting=true;selected.add(it.id);render();}},480);},{passive:true});
      b.addEventListener('touchend',()=>{if(timer)clearTimeout(timer);},{passive:true});
      b.addEventListener('touchmove',()=>{if(timer)clearTimeout(timer);timer=null;},{passive:true});
      b.onclick=handle(()=>{
        if(selecting){selected.has(it.id)?selected.delete(it.id):selected.add(it.id);if(!selected.size)selecting=false;render();return;}
        return openItem(it.id,load);
      });
    });
    const bar=f('selectbar');bar.hidden=!selecting;
    if(selecting){
      bar.innerHTML=`<button data-s="all">${icon('check')}All</button><button data-s="move">${icon('move')}Move</button><button data-s="copy">${icon('copy')}Copy to</button><button data-s="card">${icon('card')}Suspend</button><button data-s="delete" class="danger">${icon('trash')}Delete</button>`;
      bar.querySelectorAll('[data-s]').forEach(b=>b.onclick=handle(async()=>{
        const ids=[...selected];const a=b.dataset.s;
        if(a==='all'){if(selected.size===items.length)selected.clear();else items.forEach(i=>selected.add(i.id));render();return;}
        if(!ids.length){toast('Select some items first');return;}
        if(a==='move'||a==='copy'){
          const all=await api('folders');
          const choice=await menuSheet(a==='move'?'Move to':'Copy to',all.map(x=>({label:x.name,icon:'folder',id:x.id})));
          if(!choice)return;await api('item.move',{ids,folder:choice.id,copy:a==='copy'});toast(a==='move'?'Moved':'Copied');
        }
        if(a==='card'){const any=items.filter(i=>selected.has(i.id)).some(i=>!i.review);await api('item.review',{ids,review:any});toast(any?'Added to review':'Removed from review');}
        if(a==='delete'){if(!await confirm2('Delete '+ids.length+' '+(ids.length===1?'item':'items')+'?','This also deletes their review history.','Delete',true))return;await api('item.delete',{ids});toast('Deleted');}
        selecting=false;selected.clear();await load();refreshBadge();
      }));
    }
  }
  async function load(){
    items=await api('items',{folder:folderId,q:f('q').value,filter,sort});
    render();
  }
  renderFilters();await load();
  pageStack[pageStack.length-1].opts.onResume=load;
}

async function openItem(id,after){
  const it=await api('item',{id});
  const el=document.createElement('div');
  el.innerHTML=`<div class="bar"><button class="icon-btn" data-a="back">${icon('back')}</button><div class="title"><b>${esc(it.headword)}</b><small>${esc(it.folder)}${it.dict_name?' · '+esc(it.dict_name):''}</small></div>${it.dict?`<button class="icon-btn" data-a="open" aria-label="Open in dictionary">${icon('book')}</button>`:''}<button class="icon-btn" data-a="edit" aria-label="Edit">${icon('edit')}</button><button class="icon-btn" data-a="more">${icon('more')}</button></div>
    <div class="review-card">
      <div class="front"><div class="word">${esc(it.headword)}</div>${it.reading?`<div class="ctx" style="margin-top:6px">${esc(it.reading)}</div>`:''}${itemAudio(it).length?clipButtons(itemAudio(it),'item-audio'):''}</div>
      <div class="answer"><iframe class="card-frame" data-f="frame"></iframe>
      ${it.context?`<div class="note">${esc(it.context)}</div>`:''}${it.note?`<div class="note">${esc(it.note)}</div>`:''}</div>
      <div style="padding:16px 18px" class="hint">${it.review?(it.state===0?'New — not reviewed yet.':`Next review ${fmtDue(it.due)} · ${it.reps} ${it.reps===1?'review':'reviews'}${it.lapses?` · forgotten ${it.lapses}×`:''}`):'Suspended — not shown in review.'}</div>
    </div>`;
  pushPage(el,{onClose:after});
  const frame=el.querySelector('[data-f="frame"]');
  frame.onload=()=>frameSetup(frame,{dict:it.dict,min:40,onLink:async(href)=>{if(!it.dict)return;const r=await api('reference',{dict:it.dict,ref:href});if(r.rec)openEntry({rec:r.rec,dict:it.dict,key:r.key||'',anchor:r.anchor||''});}});
  frame.src=`/d/${it.dict||0}/item-${it.id}.card`;
  el.querySelector('[data-a="back"]').onclick=()=>popPage();
  el.querySelectorAll('[data-rvclip]').forEach(b=>{const cl=itemAudio(it);b.onclick=()=>playClip(cl[+b.dataset.rvclip]);});
  const openSource=async()=>{
    const r=await api('resolve',{dict:it.dict,page:it.page});
    if(!r.rec){toast('The source dictionary isn’t installed any more');return;}
    openEntry({rec:r.rec,dict:it.dict,key:it.headword,anchor:it.anchor});
  };
  const openBtn=el.querySelector('[data-a="open"]');if(openBtn)openBtn.onclick=handle(openSource);
  const edit=()=>openSaveSheet({item:it,existing:null,review:!!it.review,headword:it.headword,reading:it.reading,back:it.back,note:it.note,context:it.context,dict:it.dict,dict_name:it.dict_name,page:it.page,anchor:it.anchor,kind:it.kind,
    onSaved:async()=>{popPage(true);await openItem(id,after);}});
  el.querySelector('[data-a="edit"]').onclick=handle(edit);
  el.querySelector('[data-a="more"]').onclick=handle(()=>menuSheet(it.headword,[
    {label:it.review?'Suspend (keep saved, skip in review)':'Resume in review',icon:'card',run:async()=>{await api('item.review',{ids:[it.id],review:!it.review});toast(it.review?'Suspended':'Back in review');popPage(true);refreshBadge();await openItem(id,after);}},
    {label:'Move to folder…',icon:'move',run:async()=>{const all=await api('folders');const c=await menuSheet('Move to',all.map(x=>({label:x.name,icon:'folder',id:x.id})));if(!c)return;await api('item.move',{ids:[it.id],folder:c.id});toast('Moved to '+c.label);popPage();}},
    {label:'Reset review progress',icon:'refresh',run:async()=>{if(!await confirm2('Reset progress?','The card becomes new again.','Reset'))return;await api('item.reset',{ids:[it.id]});toast('Reset');}},
    {label:'Copy',icon:'copy',run:()=>{Kotoba.copy(it.headword+(it.reading?' 【'+it.reading+'】':'')+'\n'+it.back);toast('Copied');}},
    '-',
    {label:'Delete',icon:'trash',danger:true,run:async()=>{if(!await confirm2('Delete “'+it.headword+'”?','Its review history is deleted too.','Delete',true))return;await api('item.delete',{ids:[it.id]});popPage();toast('Deleted');refreshBadge();}},
  ]));
}

// ---------- review ----------
let reviewFolder=0;
async function refreshBadge(){
  try{const q=await api('queue',{folder:0});const n=q.counts.review+q.counts.learning+q.counts.new;$('due-badge').textContent=n?String(n>999?'999+':n):'';}catch(e){}
}
async function renderReviewHome(){
  reviewFolder=0;
  const [q,stats,folders]=await Promise.all([api('queue',{folder:0}),api('stats'),api('folders')]);
  const c=q.counts;const total=c.new+c.learning+c.review;
  $('review-sub').textContent=stats.streak?`${stats.streak}-day streak · ${stats.reviewed_today} reviewed today`:`${stats.reviewed_today} reviewed today`;
  const max=Math.max(1,...stats.forecast);
  const days=['Today','Tmrw'];const now=new Date();
  for(let i=2;i<7;i++){const d=new Date(now.getTime()+i*86400000);days.push(d.toLocaleDateString(undefined,{weekday:'short'}));}
  $('review-home').innerHTML=`
    <div class="stat-grid"><div class="stat new"><b>${c.new}</b><small>New</small></div><div class="stat learn"><b>${c.learning}</b><small>Learning</small></div><div class="stat due"><b>${c.review}</b><small>To review</small></div></div>
    <div style="padding:14px 16px 4px"><button class="btn primary" id="start-review" style="width:100%;height:56px;font-size:17px" ${total?'':'disabled'}>${total?'Study selected decks':'All caught up'}</button>
    ${!total&&q.next_due?`<p class="hint" style="text-align:center">Next card ${fmtDue(q.next_due)}.</p>`:''}
    ${!stats.cards?`<p class="hint" style="text-align:center">Save words with ☆ on any dictionary entry — each folder becomes a deck.</p>`:''}</div>
    <div class="section-label">Decks<span style="text-transform:none;letter-spacing:0;font-weight:500">tick the ones you’re studying</span></div>
    <div class="decks">${folders.map(f=>`<div class="deck ${f.study?'':'off'}"><label class="toggle"><input type="checkbox" data-deck="${f.id}" ${f.study?'checked':''}><span></span></label><div class="db"><b>${esc(f.name)}</b><small>${f.count} words · <span style="color:var(--easy)">${f.fresh} new</span> · <span style="color:var(--good)">${f.due} due</span></small></div><button class="btn small" data-deck-start="${f.id}" ${f.due+f.fresh?'':'disabled'}>Study</button></div>`).join('')}</div>
    <div class="cardbox"><div class="section-label" style="padding:0 0 4px">Next 7 days</div><div class="forecast">${stats.forecast.map((n,i)=>`<div><em>${n||''}</em><i style="height:${Math.round(n/max*60)}px"></i><span>${days[i]}</span></div>`).join('')}</div></div>
    <div class="cardbox" style="display:flex;justify-content:space-around;text-align:center">
      <div><b style="font:600 22px var(--serif)">${stats.cards}</b><br><small class="hint">cards</small></div>
      <div><b style="font:600 22px var(--serif)">${stats.mature}</b><br><small class="hint">mature (21d+)</small></div>
      <div><b style="font:600 22px var(--serif)">${stats.items}</b><br><small class="hint">saved items</small></div>
    </div>
    <div class="settings" style="margin-top:4px">
      <div class="switch-row"><div><b>New cards per day</b><small>${c.new_total} new waiting</small></div><div class="stepper"><button data-np="-5">−</button><span id="np">${c.new_limit}</span><button data-np="5">+</button></div></div>
      <div class="switch-row"><div><b>Target recall</b><small>Higher means more frequent reviews</small></div><div class="stepper"><button data-rt="-0.01">−</button><span id="rt">${Math.round(Number(settings.retention)*100)}%</span><button data-rt="0.01">+</button></div></div>
    </div><div style="height:20px"></div>`;
  $('review-home').querySelectorAll('[data-deck]').forEach(c=>c.onchange=handle(async()=>{await api('folder.study',{id:+c.dataset.deck,study:c.checked});renderReviewHome();refreshBadge();}));
  $('review-home').querySelectorAll('[data-deck-start]').forEach(b=>b.onclick=handle(()=>startReview(+b.dataset.deckStart)));
  $('start-review').onclick=handle(()=>startReview(reviewFolder));
  $('review-home').querySelectorAll('[data-np]').forEach(b=>b.onclick=handle(async()=>{const v=Math.max(0,Math.min(500,c.new_limit+ +b.dataset.np));await setSetting('new_per_day',v);renderReviewHome();refreshBadge();}));
  $('review-home').querySelectorAll('[data-rt]').forEach(b=>b.onclick=handle(async()=>{const v=Math.max(0.8,Math.min(0.97,+(Number(settings.retention)+ +b.dataset.rt).toFixed(2)));await setSetting('retention',v);$('rt').textContent=Math.round(v*100)+'%';}));
  const n=c.review+c.learning+c.new;$('due-badge').textContent=reviewFolder===0&&n?String(n):$('due-badge').textContent;
}

async function startReview(folder){
  const el=document.createElement('div');el.className='review-page';
  el.innerHTML=`<div class="bar"><button class="icon-btn" data-a="close">${icon('close')}</button><div class="title"><div class="counts" data-f="counts"></div></div><button class="icon-btn" data-a="undo" aria-label="Undo">${icon('undo')}</button><button class="icon-btn" data-a="more">${icon('more')}</button></div>
    <div class="review-card" data-f="card"></div><div data-f="actions"></div>`;
  pushPage(el,{modal:true,onClose:()=>{refreshBadge();if(tab==='review')renderReviewHome();}});
  const f=(n)=>el.querySelector(`[data-f="${n}"]`);
  let current=null,revealed=false,answered=0;
  el.querySelector('[data-a="close"]').onclick=()=>popPage();
  el.querySelector('[data-a="undo"]').onclick=handle(async()=>{await api('undo');toast('Undone',1200);await next();});
  el.querySelector('[data-a="more"]').onclick=handle(()=>{
    if(!current||!current.item)return;
    const it=current.item;
    return menuSheet(it.headword,[
      {label:'Edit card',icon:'edit',run:()=>openSaveSheet({item:it,review:true,headword:it.headword,reading:it.reading,back:it.back,note:it.note,context:it.context,dict:it.dict,dict_name:it.dict_name,page:it.page,anchor:it.anchor,kind:it.kind,onSaved:next})},
      ...(it.dict?[{label:'Open in dictionary',icon:'book',run:async()=>{const r=await api('resolve',{dict:it.dict,page:it.page});if(r.rec)openEntry({rec:r.rec,dict:it.dict,key:it.headword,anchor:it.anchor});else toast('Source dictionary not installed');}}]:[]),
      {label:'Suspend this card',icon:'card',run:async()=>{await api('item.review',{ids:[it.id],review:false});toast('Suspended');await next();}},
    ]);
  });
  function renderCounts(c){
    const st=current&&current.item?current.item.state:-1;
    f('counts').innerHTML=`<span class="n ${st===0?'cur':''}">${c.new}</span><span class="l ${st===1||st===3?'cur':''}">${c.learning}</span><span class="r ${st===2?'cur':''}">${c.review}</span>`;
  }
  function wireAudio(clips,auto){
    if(!clips.length)return;
    el.querySelectorAll('[data-rvclip]').forEach(b=>b.onclick=(e)=>{e.stopPropagation();playClip(clips[+b.dataset.rvclip]);});
    if(auto)playClip(clips[0]);
  }
  async function next(){
    current=await api('queue',{folder});revealed=false;
    renderCounts(current.counts);
    if(!current.item){
      f('card').innerHTML=`<div class="empty" style="padding-top:80px"><span class="glyph">了</span><h2>${answered?'Session complete':'Nothing due'}</h2>${answered?`You reviewed ${answered} ${answered===1?'card':'cards'}.`:''}${current.next_due?`<br>Next card ${fmtDue(current.next_due)}.`:''}</div>`;
      f('actions').innerHTML=`<div class="reveal"><button class="btn" id="done">Done</button></div>`;
      el.querySelector('#done').onclick=()=>popPage();
      return;
    }
    renderCard();
  }
  function renderCard(){
    const it=current.item;
    const clips=itemAudio(it);
    const audioBtn=clips.length?clipButtons(clips,'rv-audio'):'';
    const front=`<div class="front"><div class="word">${esc(it.headword)}</div>${audioBtn&&(settings.audio_front||revealed)?audioBtn:''}${settings.front_reading&&it.reading?`<div class="ctx">${esc(it.reading)}</div>`:''}${it.context&&!revealed?`<div class="ctx">${esc(it.context).replace(new RegExp(esc(it.headword).replace(/[.*+?^${}()|[\]\\]/g,'\\$&'),'g'),'<b>$&</b>')}</div>`:''}</div>`;
    if(!revealed){
      f('card').innerHTML=front;
      f('actions').innerHTML=`<div class="reveal"><button class="btn primary" id="reveal">Show answer</button></div>`;
      el.querySelector('#reveal').onclick=()=>{revealed=true;renderCard();};
      f('card').onclick=(e)=>{if(e.target.closest('button,a'))return;revealed=true;renderCard();};
      wireAudio(clips,settings.autoplay_review==='front');
      return;
    }
    f('card').onclick=null;
    f('card').innerHTML=front+`<div class="answer">${it.reading&&!settings.front_reading?`<div class="reading">${esc(it.reading)}</div>`:''}<iframe class="card-frame" id="rv-frame"></iframe>${it.context?`<div class="note">${esc(it.context)}</div>`:''}${it.note?`<div class="note">${esc(it.note)}</div>`:''}${it.dict_name?`<div style="text-align:center;margin-top:10px"><span class="tag muted">${esc(it.dict_name)}</span></div>`:''}</div>`;
    const frame=el.querySelector('#rv-frame');
    frame.onload=()=>frameSetup(frame,{dict:it.dict,min:40,onLink:()=>{}});
    frame.src=`/d/${it.dict||0}/item-${it.id}.card`;
    wireAudio(clips,settings.autoplay_review==='answer');
    const labels=['Again','Hard','Good','Easy'];
    f('actions').innerHTML=`<div class="grades">${labels.map((l,i)=>`<button class="g${i+1}" data-g="${i+1}">${l}<small>${fmtInterval(current.intervals[i])}</small></button>`).join('')}</div>`;
    el.querySelectorAll('[data-g]').forEach(b=>b.onclick=handle(async()=>{
      el.querySelectorAll('[data-g]').forEach(x=>x.disabled=true);
      await api('answer',{id:it.id,rating:+b.dataset.g});answered++;
      await next();
    }));
  }
  await next();
}

// ---------- library & settings ----------
let importState=null;
on('folder',uri=>handle(async()=>{await scanAndChoose(uri);})());
on('import',p=>{importState=p;renderImportProgress();});
on('import-error',e=>{toast((e.title?e.title+': ':'')+(e.error||e),5000);});
async function autoOrder(force){
  // Until the user reorders by hand: group order (Japanese, Kanji, Pronunciation…), larger dictionaries first.
  let manual=false;try{manual=localStorage.getItem('manualOrder')==='1';}catch(e){}
  if(manual&&!force)return;
  const rank=(g)=>{const i=GROUP_ORDER.indexOf(g);return i<0?99:i;};
  const ids=[...dicts].sort((a,b)=>rank(a.grp)-rank(b.grp)||b.entries-a.entries).map(d=>d.id);
  await api('dict.reorder',{ids});await loadDicts();
}
on('import-done',async r=>{
  importState=null;
  await loadDicts();await autoOrder(false);
  if(tab==='library')renderLibrary();
  toast(r.cancelled?'Import cancelled':`Imported ${r.done} ${r.done===1?'dictionary':'dictionaries'}${r.failed?` · ${r.failed} failed`:''}`,4000);
  if(!$('q').value)renderSearchEmpty();
});
on('restored',r=>{toast(`Restored ${r.added} items${r.skipped?` (${r.skipped} already present)`:''}`,4000);renderFolders();refreshBadge();});
function pickFolder(){Kotoba.pickFolder();}

async function scanAndChoose(uri){
  const s=openSheet(`<div class="sheet-body"><div class="loading"><div class="spinner"></div><p>Looking for dictionaries…</p></div></div>`,{title:'Import dictionaries',tall:true});
  let found;
  try{found=uri==='local'?(await api('library.scanLocal')).items:await api('library.scan',{tree:uri});}catch(e){closeSheet(s);throw e;}
  const body=s.sheet.querySelector('.sheet-body');
  if(!found.length){body.innerHTML=`<p class="hint">No .mdx files in that folder. Choose the folder that contains your dictionary folders (for example Download/Monokakido_Ciyue).</p>`;return;}
  const chosen=found.map(f=>!f.imported);
  const size=(n)=>n>1e9?(n/1e9).toFixed(1)+' GB':n>1e6?Math.round(n/1e6)+' MB':Math.round(n/1e3)+' KB';
  body.innerHTML=`<p class="hint" style="margin-top:0">Found ${found.length} ${found.length===1?'dictionary':'dictionaries'}. Files stay where they are — keep them in this folder.</p>`+
    found.map((f,i)=>`<label class="cand"><input type="checkbox" data-c="${i}" ${chosen[i]?'checked':''}><span><b>${esc(f.title)}</b><small>${esc(f.folder||f.name)} · ${size(f.size)}${f.mdd.length?' · with images/audio':''}${f.imported?' · <span style="color:var(--warn)">already imported</span>':''}</small></span></label>`).join('')+
    `<div class="switch-row"><div><b>Definition & example search</b><small>Builds a full-text index. Takes longer and uses more space.</small></div><label class="toggle"><input type="checkbox" id="imp-ft" ${settings.fulltext?'checked':''}><span></span></label></div>`;
  const foot=document.createElement('div');foot.className='sheet-foot';foot.innerHTML=`<button class="btn wide" data-close>Cancel</button><button class="btn primary wide" id="imp-go">Import</button>`;
  s.sheet.appendChild(foot);foot.querySelector('[data-close]').onclick=()=>closeSheet(s);
  body.querySelectorAll('[data-c]').forEach(c=>c.onchange=()=>chosen[+c.dataset.c]=c.checked);
  foot.querySelector('#imp-go').onclick=handle(async()=>{
    const items=found.filter((f,i)=>chosen[i]);
    if(!items.length){toast('Choose at least one dictionary');return;}
    settings.fulltext=body.querySelector('#imp-ft').checked;saveLocalSettings();
    // Smaller dictionaries first so something is usable quickly.
    items.sort((a,b)=>a.size-b.size);
    await api('library.import',{items,fulltext:settings.fulltext});
    importState={title:items[0].title,index:0,count:items.length,stage:'Starting',done:0,total:1};
    closeSheet(s);showTab('library');
  });
}
function renderImportProgress(){
  const box=$('import-progress');if(!box)return;
  if(!importState){box.innerHTML='';return;}
  const p=importState;const pct=p.total?Math.min(100,Math.round(p.done/p.total*100)):0;
  box.innerHTML=`<div class="import-card"><b>Importing ${p.index+1} of ${p.count}: ${esc(p.title)}</b><small>${esc(p.stage)} · ${pct}%</small><div class="progress"><i style="width:${pct}%"></i></div><div style="display:flex;justify-content:space-between;align-items:center;margin-top:10px"><small>You can keep using dictionaries that are already imported.</small><button class="btn small" id="imp-cancel">Cancel</button></div></div>`;
  $('imp-cancel').onclick=handle(async()=>{await api('library.cancel');toast('Cancelling…');});
}

async function renderLibrary(){
  await loadDicts();
  const status=await api('library.status');
  if(!status.importing)importState=null;
  const stats=await api('stats');
  $('library-sub').textContent=dicts.length?`${dicts.length} ${dicts.length===1?'dictionary':'dictionaries'} · ${stats.library.keys.toLocaleString()} headwords`:'Dictionaries and settings';
  $('library-home').innerHTML=`<div id="import-progress"></div>
    <div class="section-label">Dictionaries<button id="lib-add">${icon('plus','i')}</button></div>
    <div id="dict-list">${dicts.length?dicts.map((d,i)=>`<div class="dict-row ${d.enabled?'':'off'}"><button class="db" data-d="${i}" style="text-align:left"><b>${esc(d.name)}</b><small>${esc(GROUP_LABEL[d.grp]||d.grp)} · ${d.keys.toLocaleString()} headwords${d.resources?' · media':''}</small></button><label class="toggle"><input type="checkbox" data-en="${i}" ${d.enabled?'checked':''}><span></span></label></div>`).join(''):
      `<div class="empty" style="padding:20px 28px">No dictionaries yet.</div>`}</div>
    <div style="padding:14px 16px"><button class="btn primary" id="lib-import" style="width:100%">${icon('folder')} Import from a folder…</button><p class="hint">Pick the folder with your .mdx/.mdd files, e.g. <b>Download/Monokakido_Ciyue</b>. Tap a dictionary to reorder, rename or set it as a kanji dictionary.</p><button class="btn small" id="lib-local" style="margin-top:6px">Scan the app’s own folder</button><p class="hint">For dictionaries copied over USB into <b>Android/data/app.kotoba.reader/files</b>.</p></div>
    <div class="section-label">Reading</div>
    <div class="settings">
      <div class="switch-row"><div><b>Entry text size</b><small>Also adjustable from any entry’s ⋯ menu</small></div><div class="stepper"><button data-z="-0.1">−</button><span id="zv">${Math.round(settings.zoom*100)}%</span><button data-z="0.1">+</button></div></div>
      <div class="switch-row"><div><b>Vertical text (縦書き)</b><small>Show entries in vertical writing</small></div><label class="toggle"><input type="checkbox" id="set-vertical" ${settings.vertical?'checked':''}><span></span></label></div>
      <div class="switch-row"><div><b>Theme</b></div><div class="chips">${['light','sepia','dark'].map(t=>`<button class="chip small ${settings.theme===t?'on':''}" data-theme-set="${t}">${t[0].toUpperCase()+t.slice(1)}</button>`).join('')}</div></div>
      <div class="switch-row"><div><b>Show reading on card front</b><small>Otherwise the reading appears with the answer</small></div><label class="toggle"><input type="checkbox" id="set-front" ${settings.front_reading?'checked':''}><span></span></label></div>
    </div>
    <div class="section-label">Audio</div>
    <div class="settings">
      <div class="switch-row"><div><b>Auto-play in entries</b><small>Play the first pronunciation when an entry opens</small></div><label class="toggle"><input type="checkbox" id="set-ap-entry" ${settings.autoplay_entry?'checked':''}><span></span></label></div>
      <div class="switch-row"><div><b>Auto-play in review</b><small>When a card’s attached audio plays by itself</small></div><div class="chips">${[['off','Off'],['front','Question'],['answer','Answer']].map(([v,l])=>`<button class="chip small ${settings.autoplay_review===v?'on':''}" data-apr="${v}">${l}</button>`).join('')}</div></div>
      <div class="switch-row"><div><b>Play button on the question side</b><small>Otherwise audio appears with the answer (listening practice when on)</small></div><label class="toggle"><input type="checkbox" id="set-audio-front" ${settings.audio_front?'checked':''}><span></span></label></div>
    </div>
    <div class="section-label">Your data</div>
    <div class="settings">
      <div class="switch-row"><div><b>Back up vocabulary</b><small>Folders, cards and review history (JSON)</small></div><button class="btn small" id="backup">Save…</button></div>
      <div class="switch-row"><div><b>Restore backup</b><small>Merges into what’s here; duplicates are skipped</small></div><button class="btn small" id="restore">Open…</button></div>
      <div class="switch-row"><div><b>Export everything for Anki</b><small>Tab-separated, with dictionary formatting</small></div><button class="btn small" id="export-all">Export…</button></div>
      <div class="switch-row"><div><b>Export as spreadsheet</b><small>CSV for Excel, Sheets or Numbers</small></div><button class="btn small" id="export-csv">Export…</button></div>
    </div>
    <p class="hint" style="text-align:center;padding:10px 20px 30px">Kotoba 0.3 · works fully offline · nothing leaves your phone</p>`;
  renderImportProgress();
  if(status.importing&&!importState)$('import-progress').innerHTML='<div class="import-card"><b>Import in progress…</b></div>';
  $('lib-add').onclick=pickFolder;$('lib-import').onclick=pickFolder;$('lib-local').onclick=handle(()=>scanAndChoose('local'));
  $('dict-list').querySelectorAll('[data-en]').forEach(c=>c.onchange=handle(async()=>{const d=dicts[+c.dataset.en];await api('dict.update',{id:d.id,enabled:c.checked});await loadDicts();c.closest('.dict-row').classList.toggle('off',!c.checked);}));
  $('dict-list').querySelectorAll('[data-d]').forEach(b=>b.onclick=handle(()=>dictMenu(+b.dataset.d)));
  $('library-home').querySelectorAll('[data-z]').forEach(b=>b.onclick=()=>{settings.zoom=Math.max(0.7,Math.min(2.6,+(settings.zoom+ +b.dataset.z).toFixed(2)));saveLocalSettings();$('zv').textContent=Math.round(settings.zoom*100)+'%';});
  $('set-vertical').onchange=e=>{settings.vertical=e.target.checked;saveLocalSettings();};
  $('set-front').onchange=e=>{settings.front_reading=e.target.checked;saveLocalSettings();};
  $('set-ap-entry').onchange=e=>{settings.autoplay_entry=e.target.checked;saveLocalSettings();};
  $('set-audio-front').onchange=e=>{settings.audio_front=e.target.checked;saveLocalSettings();};
  $('library-home').querySelectorAll('[data-apr]').forEach(b=>b.onclick=()=>{settings.autoplay_review=b.dataset.apr;saveLocalSettings();$('library-home').querySelectorAll('[data-apr]').forEach(x=>x.classList.toggle('on',x===b));});
  $('library-home').querySelectorAll('[data-theme-set]').forEach(b=>b.onclick=()=>{settings.theme=b.dataset.themeSet;saveLocalSettings();applyTheme();renderLibrary();});
  $('backup').onclick=()=>Kotoba.exportFile(`kotoba-backup-${new Date().toISOString().slice(0,10)}.json`,'backup','{}');
  $('restore').onclick=()=>Kotoba.restoreBackup();
  $('export-all').onclick=()=>Kotoba.exportFile('kotoba-anki.txt','tsv',JSON.stringify({folder:0,html:true}));
  $('export-csv').onclick=()=>Kotoba.exportFile('kotoba-vocabulary.csv','csv','{}');
}
async function dictMenu(i){
  const d=dicts[i];
  await menuSheet(d.name,[
    {label:'Move up',icon:'up',run:async()=>{if(i===0)return;try{localStorage.setItem('manualOrder','1');}catch(e){}const ids=dicts.map(x=>x.id);[ids[i-1],ids[i]]=[ids[i],ids[i-1]];await api('dict.reorder',{ids});renderLibrary();}},
    {label:'Move down',icon:'down',run:async()=>{if(i===dicts.length-1)return;try{localStorage.setItem('manualOrder','1');}catch(e){}const ids=dicts.map(x=>x.id);[ids[i+1],ids[i]]=[ids[i],ids[i+1]];await api('dict.reorder',{ids});renderLibrary();}},
    {label:'Sort all by group and size',icon:'refresh',run:async()=>{try{localStorage.removeItem('manualOrder');}catch(e){}await autoOrder(true);renderLibrary();}},
    {label:'Rename',icon:'edit',run:async()=>{const name=await prompt2('Rename dictionary',d.name);if(!name)return;await api('dict.update',{id:d.id,name});renderLibrary();}},
    {label:d.kind==='kanji'?'Treat as a word dictionary':'Treat as a kanji dictionary',icon:'text',run:async()=>{await api('dict.update',{id:d.id,kind:d.kind==='kanji'?'term':'kanji'});toast(d.kind==='kanji'?'Now a word dictionary':'Now shown in the kanji strip');renderLibrary();}},
    {label:'Group: '+(GROUP_LABEL[d.grp]||d.grp)+' (change)',icon:'folder',run:async()=>{
      const names=[...new Set(GROUP_ORDER.concat(dicts.map(x=>x.grp)))].filter(Boolean);
      const c=await menuSheet('Group for '+d.name,names.map(n=>({label:GROUP_LABEL[n]||n,icon:n===d.grp?'check':'folder',v:n})).concat([{label:'New group…',icon:'plus',v:''}]));
      if(!c)return;let g=c.v;if(!g){g=await prompt2('New group','','e.g. Classical, Slang');if(!g)return;}
      await api('dict.update',{id:d.id,grp:g});toast('Moved to '+(GROUP_LABEL[g]||g));renderLibrary();}},
    {label:'Browse this dictionary',icon:'book',run:()=>openBrowse(d.id)},
    {label:'Appendix / 付録',icon:'book',run:async()=>{const a=await api('appendix',{dict:d.id});if(!a.length){toast('This dictionary has no appendix pages');return;}openAppendix(d.id);}},
    ...(d.kind==='kanji'?[{label:'Kanji grid',icon:'expand',run:()=>openKanjiGrid(d.id)}]:[]),
    '-',
    {label:'Remove from library',icon:'trash',danger:true,run:async()=>{if(!await confirm2('Remove '+d.name+'?','The dictionary files on your phone are not deleted, and your saved words stay. You can import it again later.','Remove',true))return;await api('dict.delete',{id:d.id});toast('Removed');renderLibrary();}},
  ]);
}

// ---------- start ----------
(async function init(){
  loadLocalSettings();applyTheme();
  try{
    const server=await api('settings');
    if(server.new_per_day)settings.new_per_day=server.new_per_day;
    if(server.retention)settings.retention=server.retention;
  }catch(e){}
  await loadDicts().catch(e=>toast(e.message));
  renderSearchEmpty();
  refreshBadge();
  const status=await api('library.status').catch(()=>({}));
  if(status.importing)showTab('library');
})();
