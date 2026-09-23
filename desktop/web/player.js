'use strict';
/*
 * Kotoba Player: the layer over mpv. Subtitles are drawn here so every character can be hovered: the word from
 * that point is looked up with the same lookup as the phone (longest match, conjugations, Korean analysis) and shown
 * in a popup with each dictionary's definition. The controls and shortcuts are simplified from IINA.
 */
const $=(id)=>document.getElementById(id);
const esc=(v)=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const videoPath=new URLSearchParams(location.search).get('video')||'';
const videoName=videoPath.split('/').pop().replace(/\.[^.]+$/,'');
const send=(m)=>{try{window.webkit.messageHandlers.player.postMessage(m);}catch(e){}};
async function api(route,body){
  const r=await fetch('/api/'+route,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body||{})});
  const j=await r.json();if(j.error)throw new Error(j.error);return j.data;
}
const store={get(k,d){try{const v=localStorage.getItem(k);return v===null?d:JSON.parse(v);}catch(e){return d;}},set(k,v){try{localStorage.setItem(k,JSON.stringify(v));}catch(e){}}};
const fmt=(s)=>{s=Math.max(0,Math.floor(s||0));const h=Math.floor(s/3600),m=Math.floor(s%3600/60),x=String(s%60).padStart(2,'0');return h?`${h}:${String(m).padStart(2,'0')}:${x}`:`${m}:${x}`;};

const st={t:0,d:0,paused:true,speed:1,vol:100,subs:[],audio:[],main:null,second:null,cues:[],cues2:[],lang:'',
  delay:0,autopause:store.get('player.autopause',false),apCue:null,shown:null,shown2:null,size:store.get('player.size',1),
  hoverPause:store.get('player.hoverPause',true),pausedByHover:false,loaded:false};

function osd(text){const o=$('osd');o.textContent=text;o.hidden=false;clearTimeout(osd.t);osd.t=setTimeout(()=>o.hidden=true,1300);}

// ---------- subtitle files ----------
function clean(s){return s.replace(/\{\\[^}]*\}/g,'').replace(/<[^>]+>/g,'').replace(/\\N/gi,'\n').replace(/&nbsp;/g,' ').replace(/&lt;/g,'<').replace(/&gt;/g,'>').replace(/&amp;/g,'&').trim();}
function seconds(s){s=s.trim().replace(',','.');const p=s.split(':').map(Number);return p.length===3?p[0]*3600+p[1]*60+p[2]:p.length===2?p[0]*60+p[1]:p[0];}
function parseSubs(text){
  text=text.replace(/\r/g,'');
  const cues=[];
  if(/^\[Script Info\]/m.test(text)||/^Dialogue:/m.test(text)){
    let fields=['Layer','Start','End','Style','Name','MarginL','MarginR','MarginV','Effect','Text'];
    for(const line of text.split('\n')){
      if(/^Format:/i.test(line)&&/Text\s*$/.test(line))fields=line.slice(7).split(',').map(x=>x.trim());
      if(!/^Dialogue:/i.test(line))continue;
      const parts=line.slice(9).split(',');
      const rec={};fields.forEach((f,i)=>rec[f]=i===fields.length-1?parts.slice(i).join(','):parts[i]);
      const t=clean(rec.Text||'');if(!t)continue;
      cues.push({start:seconds(rec.Start),end:seconds(rec.End),text:t});
    }
  }else{
    for(const block of text.split(/\n{2,}/)){
      const lines=block.split('\n');const at=lines.findIndex(l=>l.includes('-->'));if(at<0)continue;
      const [a,b]=lines[at].split('-->');const t=clean(lines.slice(at+1).join('\n'));if(!t)continue;
      cues.push({start:seconds(a),end:seconds(b.trim().split(/\s+/)[0]),text:t});
    }
  }
  cues.sort((x,y)=>x.start-y.start);
  cues.forEach((c,i)=>c.i=i);
  return cues;
}
async function loadTrack(track){
  if(!track)return [];
  const r=await api('video.sub',track.kind==='file'?{path:videoPath,file:track.file}:{path:videoPath,stream:track.stream});
  return parseSubs(r.text);
}
function cueAt(cues,t){
  let lo=0,hi=cues.length-1,best=-1;
  while(lo<=hi){const m=(lo+hi)>>1;if(cues[m].start<=t){best=m;lo=m+1;}else hi=m-1;}
  for(let i=best;i>=0&&i>best-4;i--)if(cues[i].end>t)return cues[i];
  return null;
}
const trackKey=(t)=>t?(t.kind==='file'?'f:'+t.file:'s:'+t.stream):'';
async function chooseTracks(mainKey,secondKey){
  const find=(k)=>st.subs.find(t=>trackKey(t)===k)||null;
  st.main=mainKey===''?null:find(mainKey);st.second=secondKey===''?null:find(secondKey);
  st.lang=(st.main&&store.get('player.lang:'+trackKey(st.main),st.main.lang))||'';
  $('sub-main').lang=st.lang==='zh'?'zh-Hant':st.lang;
  st.shown=st.shown2=null;
  try{[st.cues,st.cues2]=await Promise.all([loadTrack(st.main),loadTrack(st.second)]);}
  catch(e){osd(e.message);st.cues=st.cues||[];st.cues2=st.cues2||[];}
  store.set('player.tracks:'+videoPath,{main:trackKey(st.main),second:trackKey(st.second)});
  renderTranscript();render(true);
}

// ---------- drawing ----------
function chars(text,cls){
  // One span per character so the pointer can find where a word starts; line breaks stay.
  let i=0;return Array.from(text).map(ch=>ch==='\n'?'<br>':`<span class="${cls}" data-i="${i++}">${esc(ch)}</span>`).join('');
}
function flat(text){return Array.from(text).filter(c=>c!=='\n');}
function render(force){
  const t=st.t+st.delay;
  const c=st.cues.length?cueAt(st.cues,t):null,c2=st.cues2.length?cueAt(st.cues2,t):null;
  if(force||c!==st.shown){
    st.shown=c;
    $('sub-main').innerHTML=c?chars(c.text,'ch'):'';
    $('sub-main').dataset.cue=c?c.i:'';
    if(!pop.pinned)hidePop();
    markTranscript(c);
  }
  if(force||c2!==st.shown2){st.shown2=c2;$('sub-second').textContent=c2?c2.text:'';}
  // Pause at the end of each line, once, when that's on.
  if(st.autopause&&!st.paused){
    if(c&&c!==st.apCue&&t<c.end-0.08)st.apCue=c;
    if(st.apCue&&t>=st.apCue.end-0.06){send({cmd:'pause'});st.apCue=null;}
  }
}
document.documentElement.style.setProperty('--sub-size',st.size);

// ---------- state from the app ----------
let saveTick=0;
window.playerState=(t,d,paused,speed,vol)=>{
  st.t=t;st.d=d;st.paused=paused;st.speed=speed;st.vol=vol;
  $('play').textContent=paused?'▶':'❚❚';
  $('time').textContent=`${fmt(t)} / ${fmt(d)}`;
  if(!seeking&&d>0)$('seek').value=String(Math.round(t/d*1000));
  $('speed').textContent=(Math.round(speed*100)/100)+'×';
  if(document.activeElement!==$('volume'))$('volume').value=String(vol);
  render(false);
  if(++saveTick%100===0)remember();
};
window.playerLoaded=async(info)=>{
  st.audio=info.audio||[];st.d=info.duration||0;
  $('audio-btn').hidden=st.audio.length<2;
  send({cmd:'title',title:videoName});
  const resume=store.get('pos:'+videoPath,0);
  if(!st.loaded&&resume>10&&(!st.d||resume<st.d-20)){send({cmd:'seek',t:resume});osd('Resumed at '+fmt(resume));}
  st.loaded=true;
  send({cmd:'play'});
  try{st.subs=(await api('video.tracks',{path:videoPath})).subs.filter(s=>s.kind==='file'||s.text);}catch(e){st.subs=[];osd(e.message);}
  const saved=store.get('player.tracks:'+videoPath,null);
  if(saved)return chooseTracks(saved.main,saved.second);
  // First time: a study-language track (your generated file first), with English underneath if there is one.
  const study=st.subs.find(s=>s.generated)||st.subs.find(s=>['ja','zh','ko','th','ru'].includes(s.lang))||st.subs[0];
  const en=st.subs.find(s=>s.lang==='en'&&s!==study);
  await chooseTracks(trackKey(study),trackKey(en));
  if(!st.subs.length)osd('No subtitles found beside this video');
};
window.playerEnded=()=>{remember();};
window.playerClosing=()=>remember();
function remember(){
  if(!videoPath||!st.d)return;
  store.set('pos:'+videoPath,st.t>st.d-20?0:st.t);
  const recent=store.get('recentVideos',[]).filter(v=>v.path!==videoPath);
  recent.unshift({path:videoPath,title:videoName,t:st.t,d:st.d,when:Date.now()});
  store.set('recentVideos',recent.slice(0,40));
}

// ---------- hover lookup ----------
const pop={el:$('pop'),pinned:false,key:null,res:null,hideT:0,cue:null};
let hoverT=0,hoverAt=null;
function onHover(e){
  const span=e.target.closest('.ch');if(!span)return;
  const lineEl=span.closest('[data-cue]');if(!lineEl)return;
  const cue=st.cues[+lineEl.dataset.cue];if(!cue)return;
  const at=lineEl.dataset.cue+':'+span.dataset.i;
  if(at===hoverAt)return;
  hoverAt=at;
  clearTimeout(hoverT);
  hoverT=setTimeout(()=>lookupAt(lineEl,cue,+span.dataset.i),70);
  if(st.hoverPause&&!st.paused&&lineEl.id==='sub-main'){send({cmd:'pause'});st.pausedByHover=true;}
}
async function lookupAt(lineEl,cue,i){
  const all=flat(cue.text);
  const text=all.slice(i,i+24).join('');
  if(!text.trim()||/^[\s\p{P}]/u.test(text))return;
  let res;
  try{res=await api('lookup',{text,lang:st.lang==='en'?'':st.lang});}catch(e){return;}
  if(hoverAt!==lineEl.dataset.cue+':'+i)return;
  lineEl.querySelectorAll('.ch.hl').forEach(x=>x.classList.remove('hl'));
  if(!res.items.length){if(!pop.pinned)hidePop();return;}
  const n=Array.from(res.matched||text[0]).length;
  const spans=[...lineEl.querySelectorAll('.ch')].slice(i,i+n);
  spans.forEach(x=>x.classList.add('hl'));
  res.written=spans.map(x=>x.textContent).join('');
  showPop(res,cue,spans[0].getBoundingClientRect(),spans[spans.length-1].getBoundingClientRect());
}
function leaveSubs(){
  hoverAt=null;clearTimeout(hoverT);
  clearTimeout(pop.hideT);
  pop.hideT=setTimeout(()=>{if(!pop.pinned&&!pop.el.matches(':hover')){hidePop();resumeAfterHover();}},260);
}
function resumeAfterHover(){if(st.pausedByHover){st.pausedByHover=false;send({cmd:'play'});}}
function hidePop(){
  pop.el.hidden=true;pop.pinned=false;pop.key=null;
  document.querySelectorAll('.ch.hl').forEach(x=>x.classList.remove('hl'));
}
const shortName=(n)=>String(n).replace(/\s*[\[(（][\d\-. v]+[\])）]\s*$/,'').replace(/^(小学館|三省堂|研究社|大修館|旺文社)\s*/,'').replace(/\s*第.版$/,'').slice(0,14);
async function showPop(res,cue,r1,r2){
  const key=res.key+'|'+res.matched;
  pop.res=res;pop.cue=cue;
  if(pop.key!==key){
    pop.key=key;
    // One definition per dictionary, in your dictionary order.
    const byDict=[];for(const it of res.items)if(!byDict.some(x=>x.dict===it.dict)&&it.kind!=='kanji')byDict.push(it);
    const items=byDict.slice(0,4);
    // The word as the subtitle writes it (門口), with the dictionary's form when that differs (门口, 食べる).
    const shown=res.written&&res.written!==res.key?res.written:res.key;
    pop.el.innerHTML=`<div class="p-head"><b class="p-word">${esc(shown)}</b>${shown!==res.key?`<span class="p-key">${esc(res.key)}</span>`:''}<span class="p-freq"></span><span class="p-saved"></span></div>
      ${res.explain?`<div class="p-explain">${esc(res.explain)}</div>`:''}
      <div class="p-defs">${items.map((it,n)=>`<div class="p-def" data-n="${n}"><div class="p-dict">${esc(shortName(it.dictionary))}${it.page&&it.page!==it.key?` · ${esc(it.page)}`:''}</div><div class="p-text">…</div></div>`).join('')}</div>
      <div class="p-full" hidden></div>
      <div class="p-actions"><button data-p="card">＋ Card</button><button data-p="full">Full entry</button><button data-p="main">Open in Kotoba</button><button data-p="copy">Copy</button></div>`;
    pop.items=items;
    api('gloss.rec',{recs:items.map(x=>x.rec),max:360}).then(g=>{
      if(pop.key!==key)return;
      g.forEach((x,n)=>{const el=pop.el.querySelector(`.p-def[data-n="${n}"] .p-text`);if(el)el.textContent=x.text||'—';});
      place(r1,r2);
    }).catch(()=>{});
    Promise.all([api('freq',{key:res.key,reading:''}),dictGroups()]).then(([f,groups])=>{
      if(pop.key!==key)return;
      // Only frequency lists in the subtitle's language (JPDB ranks mean nothing for a Cantonese word).
      const want={ja:'Japanese',zh:'Chinese',ko:'Korean',th:'Thai',ru:'Russian'}[st.lang];
      const fq=f.find(x=>x.mode==='freq'&&want&&(groups[x.dict]||'').split('/')[0]===want);
      if(fq)pop.el.querySelector('.p-freq').innerHTML=`${bars(fq.value)}<span>${esc(fq.display)}</span>`;
    }).catch(()=>{});
    api('item.similar',{headword:res.key,reading:''}).then(s=>{
      if(pop.key!==key||!s.length)return;
      pop.el.querySelector('.p-saved').textContent='★ '+s[0].folder;
    }).catch(()=>{});
  }
  pop.el.hidden=false;
  place(r1,r2);
}
let groupsCache=null;
function dictGroups(){return groupsCache||(groupsCache=api('dicts').then(ds=>Object.fromEntries(ds.map(d=>[d.id,d.grp||'Japanese']))));}
function bars(rank){const l=!rank?0:rank<=2000?4:rank<=8000?3:rank<=25000?2:1;return `<span class="fbars l${l}"><i></i><i></i><i></i><i></i></span>`;}
function place(r1,r2){
  const el=pop.el,W=innerWidth,H=innerHeight;
  const w=el.offsetWidth,h=el.offsetHeight;
  const cx=(r1.left+r2.right)/2;
  let left=Math.min(W-w-12,Math.max(12,cx-w/2));
  let top=r1.top-h-14;if(top<12)top=Math.min(H-h-12,r1.bottom+14);
  el.style.left=left+'px';el.style.top=top+'px';
}
pop.el.addEventListener('mouseenter',()=>clearTimeout(pop.hideT));
pop.el.addEventListener('mouseleave',()=>{if(!pop.pinned)leaveSubs();});
pop.el.addEventListener('click',async(e)=>{
  pop.pinned=true;
  const b=e.target.closest('[data-p]');if(!b)return;
  const res=pop.res,it=pop.items&&pop.items[0];
  if(b.dataset.p==='copy'){navigator.clipboard.writeText(res.key).catch(()=>{});osd('Copied');}
  if(b.dataset.p==='main')send({cmd:'lookupInMain',word:res.key});
  if(b.dataset.p==='full'&&it){
    const full=pop.el.querySelector('.p-full');
    full.hidden=!full.hidden;
    if(!full.hidden)full.innerHTML=pop.items.map((x,n)=>`<button class="p-tab ${n===0?'on':''}" data-rec="${x.rec}" data-dict="${x.dict}">${esc(shortName(x.dictionary))}</button>`).join('')+`<iframe src="/d/${it.dict}/${it.rec}.entry"></iframe>`;
  }
  if(b.classList.contains('p-tab')){
    pop.el.querySelectorAll('.p-tab').forEach(x=>x.classList.toggle('on',x===b));
    pop.el.querySelector('.p-full iframe').src=`/d/${b.dataset.dict}/${b.dataset.rec}.entry`;
  }
  if(b.dataset.p==='card'&&it){
    try{
      const g=(await api('gloss.rec',{recs:[it.rec],max:2000}))[0];
      const reading=it.page&&it.page!==res.key&&/^[぀-ヿ가-힣a-zāáǎàēéěèīíǐìōóǒòūúǔùü\s]+$/i.test(it.page)?it.page:'';
      const similar=await api('item.similar',{headword:res.key,reading});
      if(similar.length&&!confirm(`“${res.key}” is already a card (${similar[0].dict_name||'notes'} · ${similar[0].folder}). Save another?`))return;
      await api('item.save',{folder_id:store.get('player.folder',1),headword:st.lang==='zh'&&res.written?res.written:res.key,reading,back:g.text,dict:it.dict,dict_name:it.dictionary,page:it.page||res.key,
        kind:'entry',context:(pop.cue?pop.cue.text:'').replace(/\n/g,' '),note:`${videoName} · ${fmt(pop.cue?pop.cue.start:st.t)}`,review:true});
      pop.el.querySelector('.p-saved').textContent='★ saved';osd('Saved “'+res.key+'”');
    }catch(err){osd(err.message);}
  }
});
for(const id of ['sub-main','t-list']){
  $(id).addEventListener('mousemove',onHover);
  $(id).addEventListener('mouseleave',leaveSubs);
}
document.addEventListener('mousedown',(e)=>{if(!pop.el.hidden&&!e.target.closest('#pop')&&!e.target.closest('.ch')){hidePop();resumeAfterHover();}});

// ---------- transcript ----------
function renderTranscript(){
  $('t-list').innerHTML=st.cues.map(c=>`<div class="t-row" data-cue="${c.i}"><button class="t-time" data-seek="${c.start}">${fmt(c.start)}</button><div class="t-text">${chars(c.text,'ch')}</div></div>`).join('');
}
function markTranscript(c){
  if($('transcript').hidden)return;
  $('t-list').querySelectorAll('.t-row.on').forEach(x=>x.classList.remove('on'));
  if(!c)return;
  const row=$('t-list').querySelector(`.t-row[data-cue="${c.i}"]`);
  if(row){row.classList.add('on');if(!$('t-list').matches(':hover'))row.scrollIntoView({block:'center',behavior:'smooth'});}
}
$('t-list').addEventListener('click',(e)=>{const b=e.target.closest('[data-seek]');if(b)send({cmd:'seek',t:+b.dataset.seek-st.delay+0.01});});
// The transcript's rows look words up in their own line.
$('t-list').addEventListener('mousemove',(e)=>{const row=e.target.closest('.t-row');if(row)row.querySelector('.t-text').dataset.cue=row.dataset.cue;},true);

// ---------- controls ----------
let seeking=false;
$('seek').addEventListener('input',()=>{seeking=true;$('time').textContent=`${fmt($('seek').value/1000*st.d)} / ${fmt(st.d)}`;});
$('seek').addEventListener('change',()=>{send({cmd:'seek',t:$('seek').value/1000*st.d});seeking=false;});
$('volume').addEventListener('input',()=>send({cmd:'volume',v:+$('volume').value}));
function lineJump(dir){
  const t=st.t+st.delay;
  if(!st.cues.length)return send({cmd:'seekBy',t:dir*5});
  let target;
  if(dir<0){const cur=cueAt(st.cues,t);const before=st.cues.filter(c=>c.start<(cur?cur.start:t)-0.05);target=cur&&t-cur.start>1.2?cur:before[before.length-1];}
  else target=st.cues.find(c=>c.start>t+0.05);
  if(target){st.apCue=null;send({cmd:'seek',t:Math.max(0,target.start-st.delay+0.01)});send({cmd:'play'});}
}
function replay(){const c=cueAt(st.cues,st.t+st.delay)||st.cues.filter(c=>c.start<=st.t+st.delay).pop();if(c){st.apCue=null;send({cmd:'seek',t:Math.max(0,c.start-st.delay+0.01)});send({cmd:'play'});}}
function setAutopause(v){st.autopause=v;store.set('player.autopause',v);$('autopause').classList.toggle('on',v);osd(v?'Pause after each line: on':'Pause after each line: off');}
$('autopause').classList.toggle('on',st.autopause);
function menu(anchor,title,items){
  const m=$('menu');
  m.innerHTML=`<div class="m-title">${esc(title)}</div>`+items.map((it,n)=>it==='-'?'<hr>':it.heading?`<div class="m-head">${esc(it.heading)}</div>`:`<button data-m="${n}" class="${it.on?'on':''}">${it.on?'✓ ':''}${esc(it.label)}</button>`).join('');
  m.hidden=false;
  const r=anchor.getBoundingClientRect();
  m.style.left=Math.min(innerWidth-m.offsetWidth-10,Math.max(10,r.right-m.offsetWidth))+'px';
  m.style.top=Math.max(10,r.top-m.offsetHeight-8)+'px';
  m.onclick=(e)=>{const b=e.target.closest('[data-m]');if(!b)return;const it=items[+b.dataset.m];m.hidden=true;it.run&&it.run();};
}
document.addEventListener('mousedown',(e)=>{if(!e.target.closest('#menu')&&!e.target.closest('[data-a="subs"],[data-a="audio"],[data-a="speed"]'))$('menu').hidden=true;});
const LANGS=[['ja','日本語'],['zh','中文 / 粵語'],['ko','한국어'],['th','ไทย'],['ru','Русский'],['en','Other (no lookup)']];
function subsMenu(anchor){
  const key1=trackKey(st.main),key2=trackKey(st.second);
  menu(anchor,'Subtitles',[
    {heading:'Main (hover to look up)'},
    ...st.subs.map(t=>({label:t.label,on:trackKey(t)===key1,run:()=>chooseTracks(trackKey(t),key2===trackKey(t)?'':key2)})),
    {label:'None',on:!st.main,run:()=>chooseTracks('',key2)},
    {heading:'Second line'},
    ...st.subs.map(t=>({label:t.label,on:trackKey(t)===key2,run:()=>chooseTracks(key1,trackKey(t))})),
    {label:'None',on:!st.second,run:()=>chooseTracks(key1,'')},
    {heading:'Language of the main line'},
    ...LANGS.map(([v,l])=>({label:l,on:st.lang===v,run:()=>{st.lang=v;if(st.main)store.set('player.lang:'+trackKey(st.main),v);$('sub-main').lang=v==='zh'?'zh-Hant':v;}})),
    '-',
    {label:`Bigger (now ${Math.round(st.size*100)}%)`,run:()=>setSize(st.size+0.1)},{label:'Smaller',run:()=>setSize(st.size-0.1)},
    {label:`Later by 0.1 s (delay ${st.delay.toFixed(1)} s) — X`,run:()=>setDelay(st.delay+0.1)},{label:'Earlier by 0.1 s — Z',run:()=>setDelay(st.delay-0.1)},
    {label:st.hoverPause?'✓ Pause while hovering subtitles':'Pause while hovering subtitles',run:()=>{st.hoverPause=!st.hoverPause;store.set('player.hoverPause',st.hoverPause);}},
  ]);
}
function setSize(v){st.size=Math.max(0.5,Math.min(2.5,+v.toFixed(2)));store.set('player.size',st.size);document.documentElement.style.setProperty('--sub-size',st.size);osd('Subtitles '+Math.round(st.size*100)+'%');}
function setDelay(v){st.delay=Math.round(v*10)/10;osd('Subtitle delay '+st.delay.toFixed(1)+' s');render(true);}
const SPEEDS=[0.5,0.75,0.85,1,1.1,1.25,1.5,2];
function setSpeed(v){send({cmd:'speed',v});osd(v+'×');}
function stepSpeed(dir){const i=SPEEDS.findIndex(s=>s>=st.speed-0.001);setSpeed(SPEEDS[Math.max(0,Math.min(SPEEDS.length-1,(i<0?3:i)+dir))]);}
$('bar').addEventListener('click',(e)=>{
  const b=e.target.closest('[data-a]');if(!b)return;
  const a=b.dataset.a;
  if(a==='toggle')send({cmd:'toggle'});
  if(a==='prev')lineJump(-1);
  if(a==='next')lineJump(1);
  if(a==='replay')replay();
  if(a==='autopause')setAutopause(!st.autopause);
  if(a==='subs')subsMenu(b);
  if(a==='audio')menu(b,'Audio',st.audio.map(t=>({label:[t.title,t.lang].filter(Boolean).join(' · ')||'Track '+t.id,on:t.selected,run:()=>{send({cmd:'audio',id:t.id});st.audio.forEach(x=>x.selected=x===t);}})));
  if(a==='speed')menu(b,'Speed',SPEEDS.map(v=>({label:v+'×',on:Math.abs(v-st.speed)<0.001,run:()=>setSpeed(v)})));
  if(a==='transcript')toggleTranscript();
  if(a==='fullscreen')send({cmd:'fullscreen'});
});
$('transcript').addEventListener('click',(e)=>{if(e.target.closest('[data-a="transcript"]'))toggleTranscript();});
function toggleTranscript(){$('transcript').hidden=!$('transcript').hidden;document.body.classList.toggle('with-transcript',!$('transcript').hidden);markTranscript(st.shown);}

// Clicking the picture plays/pauses; double-click is full screen.
$('hit').addEventListener('click',()=>{if(!pop.el.hidden){hidePop();resumeAfterHover();return;}send({cmd:'toggle'});});
$('hit').addEventListener('dblclick',()=>send({cmd:'fullscreen'}));

// The control bar shows on movement and hides while playing.
let idleT=0;
function wake(){document.body.classList.remove('idle');clearTimeout(idleT);idleT=setTimeout(()=>{if(!st.paused&&!$('bar').matches(':hover')&&$('menu').hidden)document.body.classList.add('idle');},2400);}
document.addEventListener('mousemove',wake);wake();

send({cmd:'ready'});

document.addEventListener('keydown',(e)=>{
  if(e.target.tagName==='INPUT'&&e.target.type!=='range')return;
  const k=e.key.length===1?e.key.toLowerCase():e.key;
  const run={' ':()=>send({cmd:'toggle'}),'ArrowLeft':()=>send({cmd:'seekBy',t:e.shiftKey?-1:-5}),'ArrowRight':()=>send({cmd:'seekBy',t:e.shiftKey?1:5}),
    'ArrowUp':()=>send({cmd:'volume',v:Math.min(130,st.vol+5)}),'ArrowDown':()=>send({cmd:'volume',v:Math.max(0,st.vol-5)}),
    a:()=>lineJump(-1),d:()=>lineJump(1),s:replay,r:replay,p:()=>setAutopause(!st.autopause),f:()=>send({cmd:'fullscreen'}),m:()=>send({cmd:'mute'}),
    '[':()=>stepSpeed(-1),']':()=>stepSpeed(1),z:()=>setDelay(st.delay-0.1),x:()=>setDelay(st.delay+0.1),t:toggleTranscript,
    '=':()=>setSize(st.size+0.1),'-':()=>setSize(st.size-0.1),'.':()=>send({cmd:'frame'}),
    Escape:()=>{if(!pop.el.hidden){hidePop();resumeAfterHover();}else if(!$('menu').hidden)$('menu').hidden=true;}}[k];
  if(run){e.preventDefault();run();wake();}
});
