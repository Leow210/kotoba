'use strict';
/*
 * Scanner: photograph a game or visual novel on another screen (or share/pick a screenshot), then tap its text.
 * The photo is read with the on-device OCR; "Select area" reads just the text box, which is faster and cleaner.
 */

// Chinese uses the same multilingual model as Japanese; the choice also picks which dictionaries a tap looks in.
const SCAN_LANGS=[['ja','日本語','Japanese'],['zh','中文','Chinese, simplified or traditional'],['ko','한국어','Korean'],['th','ไทย','Thai'],['ru','Русский','Russian']];
let scanPage=null;// the open scanner, so a new photo lands in it
on('scan-ready',r=>openScan(r.name));

/*
 * The scanner's own camera. The phone's camera app (the old route) also files every shot in the gallery,
 * so photos are taken here instead and go only to files/scans/. Resolves to the scan's name, or null.
 */
function openCamera(){
  return new Promise(resolve=>{
    let stream=null,done=false,track=null;
    const el=document.createElement('div');el.className='cam-page';
    el.innerHTML=`<video data-f="video" autoplay playsinline muted></video>
      <div class="cam-hint" data-f="hint">Starting the camera…</div>
      <div class="cam-bar">
        <button class="cam-side" data-a="close" aria-label="Close">${icon('back')}</button>
        <button class="cam-shutter" data-a="shoot" aria-label="Take photo" disabled></button>
        <button class="cam-side" data-a="torch" aria-label="Flashlight" hidden>${icon('torch')}</button>
      </div>`;
    const f=n=>el.querySelector(`[data-f="${n}"]`),video=f('video');
    const finish=name=>{
      if(done)return;done=true;
      if(stream)stream.getTracks().forEach(t=>t.stop());
      if(pageStack.length&&pageStack[pageStack.length-1].el===el)popPage();
      resolve(name);
    };
    pushPage(el,{onClose:()=>{if(!done){done=true;if(stream)stream.getTracks().forEach(t=>t.stop());resolve(null);}}});
    el.querySelector('[data-a="close"]').onclick=()=>finish(null);
    (async()=>{
      try{
        stream=await navigator.mediaDevices.getUserMedia({audio:false,video:{facingMode:{ideal:'environment'},width:{ideal:3840},height:{ideal:2160}}});
      }catch(e){
        f('hint').textContent='Kotoba needs camera access to take photos. Allow it in Android settings, or use Image to open a screenshot.';
        return;
      }
      if(done){stream.getTracks().forEach(t=>t.stop());return;}
      video.srcObject=stream;track=stream.getVideoTracks()[0];
      try{await track.applyConstraints({advanced:[{focusMode:'continuous'}]});}catch(e){}
      const caps=track.getCapabilities?track.getCapabilities():{};
      if(caps.torch){
        const t=el.querySelector('[data-a="torch"]');t.hidden=false;let on=false;
        t.onclick=async()=>{on=!on;try{await track.applyConstraints({advanced:[{torch:on}]});t.classList.toggle('on',on);}catch(e){}};
      }
      f('hint').textContent='Fill the frame with the text';
      el.querySelector('[data-a="shoot"]').disabled=false;
    })();
    el.querySelector('[data-a="shoot"]').onclick=handle(async()=>{
      if(!track||done)return;
      const btn=el.querySelector('[data-a="shoot"]');btn.disabled=true;el.classList.add('flash');
      let blob=null;
      // A full-resolution still where the WebView supports it; otherwise the current video frame.
      let source=video,w=video.videoWidth,h=video.videoHeight;
      if(window.ImageCapture){try{const b=await new ImageCapture(track).takePhoto();source=await createImageBitmap(b);w=source.width;h=source.height;}catch(e){}}
      // Stills come out at ~9 MB; 4000 px keeps enough detail for Select area at a third of the size.
      const k=Math.min(1,4000/Math.max(w,h));
      const c=document.createElement('canvas');c.width=Math.round(w*k);c.height=Math.round(h*k);
      c.getContext('2d').drawImage(source,0,0,c.width,c.height);
      blob=await new Promise(r=>c.toBlob(r,'image/jpeg',.9));
      const data=await new Promise((r,j)=>{const fr=new FileReader();fr.onload=()=>r(String(fr.result).split(',')[1]);fr.onerror=j;fr.readAsDataURL(blob);});
      const saved=await api('scan.save',{data,mime:blob.type||'image/jpeg'});
      finish(saved.name);
    });
  });
}
async function takeScanPhoto(){const name=await openCamera();if(name)openScan(name);}

window.openScan=async function(name){
  if(scanPage){scanPage.show(name);return;}
  let lang='ja';try{lang=localStorage.getItem('scanLang')||'ja';}catch(e){}
  let current=null,result=null,selecting=false,reading=0;
  const el=document.createElement('div');el.className='scan-page';
  el.innerHTML=`<div class="bar"><button class="icon-btn" data-a="back">${icon('back')}</button><div class="title"><b>Scan text</b><small data-f="status">Take a photo of the text</small></div>
      <button class="chip small scan-lang" data-a="lang"></button><button class="icon-btn" data-a="all" aria-label="All text">${icon('text')}</button><button class="icon-btn" data-a="more">${icon('more')}</button></div>
    <div class="scan-view" data-f="view">
      <div class="scan-empty" data-f="empty"><span class="glyph">写</span><p>Photograph a game, visual novel or any screen in Japanese, Chinese, Korean, Thai or Russian, or open a screenshot. Then tap the text to look words up and save cards.</p><p class="hint">Tip: fill the frame with the text box and hold the phone straight to the screen. Dimming the room lights cuts glare.</p></div>
      <div class="scan-stage" data-f="stage" hidden><img data-f="img" alt=""><div class="scan-layer" data-f="layer"></div></div>
    </div>
    <div class="scan-foot">
      <button class="btn wide primary" data-a="camera">${icon('card')} Photo</button>
      <button class="btn wide" data-a="pick">${icon('folder')} Image</button>
      <button class="btn wide" data-a="select">${icon('expand')} Select area</button>
    </div>`;
  pushPage(el,{onClose:()=>{scanPage=null;}});
  const f=(n)=>el.querySelector(`[data-f="${n}"]`);
  const status=t=>{f('status').textContent=t;};
  const langBtn=el.querySelector('[data-a="lang"]');
  const setLang=()=>{langBtn.textContent=(SCAN_LANGS.find(l=>l[0]===lang)||SCAN_LANGS[0])[1]+' ▾';};setLang();
  langBtn.onclick=handle(async()=>{
    const c=await menuSheet('Language of the text',SCAN_LANGS.map(([v,label,note])=>({label:`${label} · ${note}`,icon:v===lang?'check':'text',v})));
    if(!c||c.v===lang)return;
    lang=c.v;try{localStorage.setItem('scanLang',lang);}catch(e){}setLang();if(current)await read(null);
  });
  el.querySelector('[data-a="back"]').onclick=()=>popPage();
  el.querySelector('[data-a="camera"]').onclick=handle(takeScanPhoto);
  el.querySelector('[data-a="pick"]').onclick=()=>Kotoba.scanPick();
  const selBtn=el.querySelector('[data-a="select"]');
  const setSelecting=v=>{selecting=v&&!!current;selBtn.classList.toggle('on',selecting);el.classList.toggle('selecting',selecting);if(selecting)status('Drag over the text you want to read');};
  selBtn.onclick=()=>setSelecting(!selecting);
  el.querySelector('[data-a="all"]').onclick=handle(()=>{
    if(!result||!result.blocks.length){toast('No text found yet');return;}
    const s=openSheet(`<div class="sheet-body ocr-list">${result.blocks.map((b,n)=>`<button class="toc-row" data-n="${n}">${esc(b.text)}</button>`).join('')}</div>
      <div class="sheet-foot"><button class="btn wide" id="sc-copy">${icon('copy')} Copy all</button><button class="btn wide" id="sc-tr">${icon('share')} Translate</button></div>`,{title:'All text'});
    const all=result.blocks.map(b=>b.text).join('\n');
    s.sheet.querySelectorAll('[data-n]').forEach(b=>b.onclick=()=>{closeSheet(s);sheetFor(result.blocks[+b.dataset.n].text);});
    s.sheet.querySelector('#sc-copy').onclick=()=>{Kotoba.copy(all);toast('Copied');};
    s.sheet.querySelector('#sc-tr').onclick=()=>Kotoba.translate(all);
  });
  el.querySelector('[data-a="more"]').onclick=handle(async()=>{
    const list=await api('scan.list');
    const when=t=>new Date(t).toLocaleString([], {month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'});
    const c=await menuSheet('Scans',[
      ...list.slice(0,15).map(x=>({label:(x.name===current?'• ':'')+when(x.time),icon:'book',name:x.name})),
      ...(current?['-',{label:'Delete this scan',icon:'trash',danger:true,del:true}]:[]),
    ]);
    if(!c)return;
    if(c.del){await api('scan.delete',{name:current});current=null;result=null;f('stage').hidden=true;f('empty').hidden=false;status('Take a photo of the text');return;}
    show(c.name);
  });
  const sheetFor=text=>ocrTextSheet(text,{lang,source:'Scan',title:'Scanned text'});

  function draw(){
    const layer=f('layer');
    layer.innerHTML=(result?result.blocks:[]).map((b,n)=>`<button class="ocr-box" data-n="${n}" style="left:${b.x*100}%;top:${b.y*100}%;width:${b.w*100}%;height:${b.h*100}%" aria-label="${esc(b.text)}"></button>`).join('');
  }
  f('layer').addEventListener('click',e=>{
    if(selecting)return;
    const b=e.target.closest('.ocr-box');if(!b)return;
    sheetFor(result.blocks[+b.dataset.n].text);
  });

  async function read(crop){
    const n=++reading;
    status(crop?'Reading the selected area…':'Reading text…');
    el.classList.add('busy');
    try{
      const r=await api('scan.read',{name:current,lang,crop});
      if(n!==reading)return;
      // A selected area replaces the boxes inside it and keeps the rest.
      if(crop&&result){
        const inside=b=>b.x+b.w/2>=crop[0]&&b.x+b.w/2<=crop[0]+crop[2]&&b.y+b.h/2>=crop[1]&&b.y+b.h/2<=crop[1]+crop[3];
        result={blocks:result.blocks.filter(b=>!inside(b)).concat(r.blocks)};
      }else result=r;
      draw();
      status(result.blocks.length?`${result.blocks.length} text ${result.blocks.length===1?'block':'blocks'} · tap one`:'No text found. Try Select area, or a straighter photo');
      // A selected text box is usually one line of dialogue: open it straight away.
      if(crop&&r.blocks.length){
        const cjk=lang==='ja'||lang==='zh';
        const text=[...r.blocks].sort((a,b)=>cjk&&a.h>a.w*1.5?b.x-a.x:a.y-b.y||a.x-b.x).map(b=>b.text).join(cjk?'':' ');
        sheetFor(text);
      }
    }catch(e){if(n===reading)status('Couldn’t read the text');throw e;}
    finally{if(n===reading)el.classList.remove('busy');}
  }

  // Select area: drag a rectangle over the photo; its text is read at full resolution.
  (function(){
    const layer=f('layer');let start=null,box=null;
    const pos=e=>{const r=layer.getBoundingClientRect();return [Math.min(1,Math.max(0,(e.clientX-r.left)/r.width)),Math.min(1,Math.max(0,(e.clientY-r.top)/r.height))];};
    layer.addEventListener('pointerdown',e=>{
      if(!selecting)return;
      e.preventDefault();layer.setPointerCapture(e.pointerId);
      start=pos(e);box=document.createElement('div');box.className='scan-crop';layer.appendChild(box);
    });
    layer.addEventListener('pointermove',e=>{
      if(!start)return;
      const p=pos(e),x=Math.min(p[0],start[0]),y=Math.min(p[1],start[1]);
      Object.assign(box.style,{left:x*100+'%',top:y*100+'%',width:Math.abs(p[0]-start[0])*100+'%',height:Math.abs(p[1]-start[1])*100+'%'});
    });
    const end=e=>{
      if(!start)return;
      const p=pos(e),crop=[Math.min(p[0],start[0]),Math.min(p[1],start[1]),Math.abs(p[0]-start[0]),Math.abs(p[1]-start[1])];
      start=null;box&&box.remove();box=null;
      if(crop[2]<.03||crop[3]<.02)return;
      setSelecting(false);
      handle(()=>read(crop))();
    };
    layer.addEventListener('pointerup',end);layer.addEventListener('pointercancel',()=>{start=null;box&&box.remove();box=null;});
  })();

  function show(n){
    current=n;result=null;draw();setSelecting(false);
    f('empty').hidden=true;f('stage').hidden=false;
    const img=f('img');
    img.onload=()=>handle(()=>read(null))();
    img.src='/scan/'+encodeURIComponent(n);
  }
  scanPage={show};
  if(name)show(name);
  else takeScanPhoto();
};

// The Scan tool is a tile on the Dictionary home screen (renderSearchEmpty).
