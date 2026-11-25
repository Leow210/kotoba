'use strict';
/*
 * The iPad bridge. The interface calls Kotoba.* exactly as on Android and the Mac; here requests go to the translated core
 * inside the app (through the Swift shell), and pickers, links and sharing go to the shell too.
 */
(function(){
  document.documentElement.classList.add('desktop','ipad');
  // Touch has no hover: a tap on a book word looks it up, as on the phone.
  try{if(localStorage.getItem('desktop.clickLookup')===null)localStorage.setItem('desktop.clickLookup','true');}catch(e){}
  const native=(msg)=>{try{window.webkit.messageHandlers.kotoba.postMessage(msg);return true;}catch(e){return false;}};
  const waiting=new Map();let seq=0;
  // The shell answers a request by calling window.__nativeReply(id, jsonText).
  window.__nativeReply=(id,text)=>{const w=waiting.get(id);if(w){waiting.delete(id);w(text);}};
  const post=(route,body)=>new Promise(resolve=>{const id=++seq;waiting.set(id,resolve);native({type:'call',id,route,body:body||'{}'});});
  const say=(m)=>window.__event&&window.__event(JSON.stringify({type:'toast',data:m}));
  const phoneOnly=()=>say('That’s on the phone app');
  window.Kotoba={
    call(id,route,body){
      // Waking Syncthing is the phone's step; on the iPad the folder is iCloud Drive's, so Sync goes straight on.
      if(route==='sync.wake'){setTimeout(()=>window.__reply(id,JSON.stringify({data:{started:false}})),0);return;}
      post(route,body).then(t=>window.__reply(id,t)).catch(e=>window.__reply(id,JSON.stringify({error:String(e.message||e)})));},
    copy(text){native({type:'copy',text});},
    share(text){native({type:'share',text});},
    translate(text,context){if(window.__translateInApp)return window.__translateInApp(text,context);native({type:'open',url:'https://translate.google.com/?sl=auto&tl=en&op=translate&text='+encodeURIComponent(text)});},
    pickFolder(){native({type:'pickFolder'});},
    pickFolderAt(){native({type:'pickFolder'});},
    exportFile(name,kind,body){
      post('export.data',JSON.stringify({kind,data:JSON.parse(body||'{}')})).then(t=>{
        const r=JSON.parse(t);if(r.error)throw new Error(r.error);
        native({type:'export',name,mime:r.data.mime||'text/plain',text:r.data.text});
      }).catch(e=>say(e.message));
    },
    restoreBackup(){native({type:'pickFile',purpose:'restore',extensions:['json']});},
    openUrl(url){if(/^https?:\/\//i.test(url))native({type:'open',url});},
    openResource(dict,name){native({type:'open',url:location.origin+'/d/'+dict+'/'+encodeURI(name)});},
    // The strips above and below the page (status bar, home indicator) take the colour of the theme or the open book.
    setBars(color,light){native({type:'bars',color:String(color||''),light:!!light});},
    exitApp(){},
    pickBooks(){native({type:'pickFile',purpose:'books',extensions:['epub','txt','text','md','markdown'],multiple:true});},
    pickComicFolder(){native({type:'pickFile',purpose:'comicFolder',folder:true});},
    pickComicFiles(){native({type:'pickFile',purpose:'comicFiles',extensions:['cbz','zip'],multiple:true});},
    pickComicCover(series){window.__coverSeries=series;native({type:'pickFile',purpose:'comicCover',extensions:['jpg','jpeg','png','webp']});},
    pickMihonBackup(){native({type:'pickFile',purpose:'mihonBackup',extensions:['tachibk','proto','gz']});},
    scanPick:phoneOnly,
    pickSyncFolder(){native({type:'pickFile',purpose:'sync',folder:true});},
    pickLyrics(){native({type:'pickFile',purpose:'lyrics',extensions:['lrc','txt'],multiple:true});},
    pickWordList(){native({type:'pickFile',purpose:'wordlist',extensions:['txt','csv','tsv']});},
  };
  // The shell answers pickers with these (files copied or opened in place by the shell; paths are inside the app's reach).
  const json=(t)=>JSON.parse(t);
  window.desktopPicked=(purpose,path)=>{
    if(purpose==='restore')post('restore',JSON.stringify({path})).then(t=>{const r=json(t);if(r.error)say(r.error);else window.__event(JSON.stringify({type:'restored',data:r.data}));});
    if(purpose==='books'){
      const paths=Array.isArray(path)?path:[path];
      Promise.all(paths.map(p=>post('book.importPath',JSON.stringify({path:p})).then(json))).then(rs=>{
        window.__event(JSON.stringify({type:'books-imported',data:{added:rs.filter(r=>!r.error).map(r=>r.data),errors:rs.filter(r=>r.error).map(r=>r.error)}}));
      });
    }
    const done=(route,body,event)=>post(route,JSON.stringify(body)).then(t=>{const r=json(t);if(r.error)say(r.error);else if(event)window.__event(JSON.stringify({type:event,data:r.data}));});
    if(purpose==='comicFolder')done('comic.scanPath',{path},'comics-added');
    if(purpose==='comicFiles')done('comic.addPaths',{paths:Array.isArray(path)?path:[path]},'comics-added');
    if(purpose==='comicCover')done('comic.setCoverPath',{id:window.__coverSeries,path},'comic-cover');
    if(purpose==='mihonBackup')done('comic.importBackupPath',{path},'mihon-imported');
    if(purpose==='sync')post('sync.setFolder',JSON.stringify({path})).then(t=>{const r=json(t);if(r.error)say(r.error);else{say('Sync folder set');window.__event(JSON.stringify({type:'sync-status',data:r.data}));}});
    if(purpose==='lyrics')post('lyrics.importPaths',JSON.stringify({paths:Array.isArray(path)?path:[path]})).then(t=>{const r=json(t);if(r.error)say(r.error);else window.__event(JSON.stringify({type:'lyrics-imported',data:r.data}));});
    if(purpose==='wordlist')post('wordlist.importPath',JSON.stringify({path})).then(t=>{const r=json(t);if(r.error)say(r.error);else window.__event(JSON.stringify({type:'wordlist-imported',data:r.data}));});
  };

  // Text size buttons in the book's top bar (the Tт sheet still has the exact setting); each press is 2 px.
  setInterval(()=>{
    document.querySelectorAll('.reader-page .rd-top').forEach(top=>{
      if(top.querySelector('.ipad-font'))return;
      const aa=top.querySelector('[data-a="aa"]');if(!aa)return;
      const make=(label,title,delta)=>{
        const b=document.createElement('button');b.className='icon-btn ipad-font';b.setAttribute('aria-label',title);b.innerHTML=`<span>${label}</span>`;
        b.onclick=e=>{e.stopPropagation();if(window.__reader&&window.__reader.fontStep)window.__reader.fontStep(delta);};
        return b;
      };
      aa.before(make('A−','Smaller text',-2),make('A+','Larger text',2));
    });
  },700);
})();
