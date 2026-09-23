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
    translate(text){mac({type:'open',url:'https://translate.google.com/?sl=auto&tl=en&op=translate&text='+encodeURIComponent(text)});},
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
    pickBooks:phoneOnly,pickComicFolder:phoneOnly,pickComicFiles:phoneOnly,pickComicCover:phoneOnly,pickMihonBackup:phoneOnly,scanPick:phoneOnly,
    pickWordList(){mac({type:'pickFile',purpose:'wordlist',extensions:['txt','csv','tsv']});},
  };
  // The Mac app answers pickers with these.
  window.desktopPicked=(purpose,path)=>{
    if(purpose==='restore')post('restore',JSON.stringify({path})).then(t=>{const r=JSON.parse(t);if(r.error)say(r.error);else window.__event(JSON.stringify({type:'restored',data:r.data}));});
    if(purpose==='wordlist')post('wordlist.importPath',JSON.stringify({path})).then(t=>{const r=JSON.parse(t);if(r.error)say(r.error);else window.__event(JSON.stringify({type:'wordlist-imported',data:r.data}));});
  };
  // Events from the core (import progress, toasts) arrive as Server-Sent Events.
  const es=new EventSource('/events');
  es.onmessage=(e)=>window.__event&&window.__event(e.data);
})();
