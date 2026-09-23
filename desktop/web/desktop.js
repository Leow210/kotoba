'use strict';
/*
 * Desktop bridge. The interface calls Kotoba.* exactly as on Android; here requests go to the local core server,
 * and pickers, links and Finder go to the Mac app (window.webkit.messageHandlers.kotoba).
 */
(function(){
  document.documentElement.classList.add('desktop');
  const mac=(msg)=>{try{window.webkit.messageHandlers.kotoba.postMessage(msg);return true;}catch(e){return false;}};
  const post=(route,body)=>fetch('/api/'+route,{method:'POST',headers:{'Content-Type':'application/json'},body:body||'{}'}).then(r=>r.text());
  const say=(m)=>window.__event&&window.__event(JSON.stringify({type:'toast',data:m}));
  const phoneOnly=()=>say('That’s on the phone app');
  window.Kotoba={
    call(id,route,body){post(route,body).then(t=>window.__reply(id,t)).catch(e=>window.__reply(id,JSON.stringify({error:String(e.message||e)})));},
    copy(text){if(!mac({type:'copy',text}))navigator.clipboard.writeText(text);},
    share(text){this.copy(text);say('Copied');},
    // TranslateGemma in Kotoba when chosen in Settings › Translation (desktop-after.js), else Google Translate in the browser.
    translate(text,context){if(window.__translateInApp)return window.__translateInApp(text,context);mac({type:'open',url:'https://translate.google.com/?sl=auto&tl=en&op=translate&text='+encodeURIComponent(text)});},
    pickFolder(){mac({type:'pickFolder'});},
    pickFolderAt(){mac({type:'pickFolder'});},
    exportFile(name,kind,body){
      post('export',JSON.stringify({name,kind,data:JSON.parse(body||'{}')})).then(t=>{
        const r=JSON.parse(t);if(r.error)throw new Error(r.error);
        say('Saved to Downloads: '+r.data.path.split('/').pop());mac({type:'reveal',path:r.data.path});
      }).catch(e=>say(e.message));
    },
    restoreBackup(){mac({type:'pickFile',purpose:'restore',extensions:['json']});},
    openResource(dict,name){mac({type:'open',url:location.origin+'/d/'+dict+'/'+encodeURI(name)});},
    setBars(){},exitApp(){},
    pickBooks(){mac({type:'pickFile',purpose:'books',extensions:['epub','txt'],multiple:true});},
    pickComicFolder(){mac({type:'pickFile',purpose:'comicFolder',folder:true});},
    pickComicFiles(){mac({type:'pickFile',purpose:'comicFiles',extensions:['cbz','zip'],multiple:true});},
    pickComicCover(series){window.__coverSeries=series;mac({type:'pickFile',purpose:'comicCover',extensions:['jpg','jpeg','png','webp']});},
    pickMihonBackup(){mac({type:'pickFile',purpose:'mihonBackup',extensions:['tachibk','proto','gz']});},
    scanPick:phoneOnly,
    pickSyncFolder(){mac({type:'pickFile',purpose:'sync',folder:true});},
    pickWordList(){mac({type:'pickFile',purpose:'wordlist',extensions:['txt','csv','tsv']});},
  };
  // The Mac app answers pickers with these.
  window.desktopPicked=(purpose,path)=>{
    if(purpose==='restore')post('restore',JSON.stringify({path})).then(t=>{const r=JSON.parse(t);if(r.error)say(r.error);else window.__event(JSON.stringify({type:'restored',data:r.data}));});
    if(purpose==='books'){
      // One or more books: each is copied into Kotoba's library, as on the phone.
      const paths=Array.isArray(path)?path:[path];
      Promise.all(paths.map(p=>post('book.importPath',JSON.stringify({path:p})).then(t=>JSON.parse(t)))).then(rs=>{
        window.__event(JSON.stringify({type:'books-imported',data:{added:rs.filter(r=>!r.error).map(r=>r.data),errors:rs.filter(r=>r.error).map(r=>r.error)}}));
      });
    }
    const done=(route,body,event)=>post(route,JSON.stringify(body)).then(t=>{const r=JSON.parse(t);if(r.error)say(r.error);else if(event)window.__event(JSON.stringify({type:event,data:r.data}));});
    if(purpose==='comicFolder')done('comic.scanPath',{path},'comics-added');
    if(purpose==='comicFiles')done('comic.addPaths',{paths:Array.isArray(path)?path:[path]},'comics-added');
    if(purpose==='comicCover')done('comic.setCoverPath',{id:window.__coverSeries,path},'comic-cover');
    if(purpose==='mihonBackup')done('comic.importBackupPath',{path},'mihon-imported');
    if(purpose==='sync')post('sync.setFolder',JSON.stringify({path})).then(t=>{const r=JSON.parse(t);if(r.error)say(r.error);else{say('Sync folder set');window.__event(JSON.stringify({type:'sync-status',data:r.data}));}});
    if(purpose==='wordlist')post('wordlist.importPath',JSON.stringify({path})).then(t=>{const r=JSON.parse(t);if(r.error)say(r.error);else window.__event(JSON.stringify({type:'wordlist-imported',data:r.data}));});
  };
  // Events from the core (import progress, toasts) arrive as Server-Sent Events.
  const es=new EventSource('/events');
  es.onmessage=(e)=>window.__event&&window.__event(e.data);
})();
