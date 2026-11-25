'use strict';
/*
 * "Ask" on the Mac: questions about a word or sentence (Claude through your API key, or the local model).
 * The same panel is used by the main window (a sheet) and the video player (a popup). Nothing is sent until a
 * question is chosen or typed.
 */
(function(){
  const esc=(v)=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const ENGINES={api:'Claude Haiku',local:'Gemma (on this Mac)'};
  const scriptLang=(t)=>/[가-힣]/.test(t)?'ko':/[぀-ヿ]/.test(t)?'ja':/[㐀-鿿]/.test(t)?'zh':'';
  /** A selection is a word when it is short, on one line and has no sentence punctuation. */
  function kindOf(text){
    const t=String(text||'').trim();
    return t.length<=14&&!/[\n。！？!?….,，、]/.test(t)&&t.split(/\s+/).length<=2?'word':'sentence';
  }
  function suggestions(kind,lang){
    const ja=lang==='ja';
    return kind==='word'?[
      ['Meaning here','What does this word mean in this sentence?'],
      ['Nuance','What is its nuance, and how does it differ from similar words?'],
      ['Examples','Give three short example sentences using it, with translations.'],
      ...(ja||!lang?[]:[['In Japanese','What is the most natural Japanese equivalent here, and why?']]),
    ]:[
      ['Explain','Explain what this sentence means, including any idioms or implied meaning.'],
      ['Grammar','Break down the grammar of this sentence piece by piece.'],
      ['Key words','List the important words and expressions in it, with readings and meanings.'],
      ...(ja||!lang?[['Simpler','Rephrase this sentence more simply.']]:[['In Japanese','Translate this into natural Japanese, and note any choice that depends on who is speaking.']]),
    ];
  }
  /** The model's **bold** and "- " lists; everything else is shown as written. */
  const format=(t)=>esc(t).replace(/^#{1,6}\s+(.*)$/gm,'<b>$1</b>').replace(/\*\*([^*\n]+)\*\*/g,'<b>$1</b>').replace(/^(\s*)[-*]\s+/gm,'$1• ');

  /**
   * Builds the panel in host. o: {api, text, context, kind, lang, source, onSize, onClose}
   */
  function panel(host,o){
    const text=String(o.text||'').trim(),context=String(o.context||'').trim();
    const kind=o.kind||kindOf(text),lang=o.lang||scriptLang(context||text);
    const root=document.createElement('div');root.className='ask';
    root.innerHTML=`<div class="ask-subject" lang="${esc(lang)}"><div class="ask-text ${kind}">${esc(text)}</div>${context&&context!==text&&kind==='word'?`<div class="ask-context">${esc(context)}</div>`:''}</div>
      <div class="ask-log" aria-live="polite"></div>
      <div class="ask-chips">${suggestions(kind,lang).map(([l],i)=>`<button type="button" data-q="${i}">${esc(l)}</button>`).join('')}</div>
      <form class="ask-form"><input type="text" maxlength="2000" autocomplete="off" placeholder="Ask about this ${kind}…" aria-label="Your question"><button type="submit">Ask</button></form>
      <div class="ask-foot"><select class="ask-engine" title="Answer with">${Object.entries(ENGINES).map(([k,v])=>`<option value="${k}">${v}</option>`).join('')}</select><span class="ask-note"></span></div>`;
    host.append(root);
    const q=(s)=>root.querySelector(s),log=q('.ask-log'),input=q('input'),engine=q('.ask-engine'),note=q('.ask-note');
    const turns=[];let busy=false,cfg=null;
    const size=()=>o.onSize&&o.onSize();
    const lock=(on)=>{busy=on;root.querySelectorAll('.ask-chips button,.ask-form button').forEach(b=>b.disabled=on);};
    const hint=()=>{
      if(!cfg)return;
      note.textContent=engine.value==='api'&&!cfg.hasKey?'No API key yet: add it in Settings › Ask.':engine.value==='local'&&!cfg.localFound?'The local model isn’t available: is the drive plugged in?':'';
    };
    const ready=o.api('ask.config',{}).then(c=>{cfg=c;engine.value=c.engine;hint();}).catch(()=>{});
    engine.onchange=()=>{hint();size();};
    async function send(question){
      question=question.trim();if(!question||busy)return;
      lock(true);await ready;
      const mine=document.createElement('div');mine.className='ask-q';mine.textContent=question;
      const reply=document.createElement('div');reply.className='ask-a busy';reply.lang=cfg&&cfg.answer==='English'?lang:{Japanese:'ja',Chinese:'zh',Korean:'ko'}[cfg&&cfg.answer]||lang;
      const use=engine.value;
      reply.textContent=use==='local'&&cfg&&!cfg.running?'Loading the model… (the first time takes a little while)':'Thinking…';
      log.append(mine,reply);input.value='';size();mine.scrollIntoView({block:'nearest'});
      try{
        const r=await o.api('ask',{text,context,kind,lang,source:o.source||'',engine:use,turns:[...turns,{q:question}]});
        turns.push({q:question,a:r.text});
        reply.classList.remove('busy');reply.innerHTML=format(r.text);
        if(use==='local'&&cfg)cfg.running=true;
        note.textContent=`${(r.ms/1000).toFixed(1)} s${r.cost!==undefined?' · about $'+r.cost.toFixed(4):''}`;
      }catch(e){
        reply.classList.remove('busy');reply.classList.add('failed');reply.textContent=e.message;
        if(!input.value)input.value=question;
      }
      lock(false);size();
      if(root.isConnected)mine.scrollIntoView({block:'start',behavior:'smooth'});
    }
    const asks=suggestions(kind,lang);
    root.querySelectorAll('[data-q]').forEach(b=>b.onclick=()=>send(asks[+b.dataset.q][1]));
    q('form').onsubmit=(e)=>{e.preventDefault();send(input.value);};
    // Typing here must not reach the page's own shortcuts (page turning, play/pause).
    input.addEventListener('keydown',(e)=>{
      e.stopPropagation();
      // Return while an input method is composing (Japanese, Chinese, Korean) only confirms the text.
      if(e.key==='Enter'&&(e.isComposing||e.keyCode===229)){e.preventDefault();return;}
      if(e.key==='Escape'&&!e.isComposing&&o.onClose){e.preventDefault();o.onClose();}
    });
    return {focus:()=>input.focus()};
  }
  window.KotobaAsk={panel,kindOf};

  // ---------- the main window: the sheet, and its settings ----------
  if(typeof openSheet!=='function')return;
  if(typeof icons==='object')icons.ask='<path d="M4 5h16v11H9l-5 4z"/><path d="M10 9a2 2 0 1 1 2.8 1.8c-.5.3-.8.6-.8 1.2m0 1.6v.1"/>';
  window.__askAbout=(o)=>{
    const text=String(o.text||'').trim();if(!text)return;
    const s=openSheet('<div class="sheet-body ask-sheet"></div>',{title:'Ask',tall:true});
    panel(s.sheet.querySelector('.ask-sheet'),{...o,api,onClose:()=>closeSheet(s)});
    return s;
  };
  async function renderAskSettings(){
    const home=document.getElementById('settings-home');if(!home)return;
    const cfg=await api('ask.config',{}).catch(()=>null);if(!cfg)return;
    home.querySelectorAll('.ask-settings').forEach(x=>x.remove());
    const label=document.createElement('div');label.className='section-label ask-settings';label.textContent='Ask';
    const box=document.createElement('div');box.className='settings ask-settings';
    box.innerHTML=`<div class="switch-row"><div><b>Answer with</b><small>${cfg.engine==='api'?'Claude Haiku through Anthropic’s API: the word or sentence you ask about, the text around it and your questions are sent to Anthropic. A question usually costs well under a tenth of a cent.':'The local model the translator uses: free and offline, slower to start, and it needs the drive.'}</small></div>
        <div class="chips">${Object.entries(ENGINES).map(([k,v])=>`<button class="chip small ${cfg.engine===k?'on':''}" data-ask-engine="${k}">${v}</button>`).join('')}</div></div>
      <div class="switch-row"><div><b>Answer language</b><small>The language explanations are written in.</small></div>
        <div class="chips"><select data-ask-answer>${cfg.languages.map(l=>`<option ${l===cfg.answer?'selected':''}>${l}</option>`).join('')}</select></div></div>
      <div class="switch-row cloud-row"><div><b>Claude API key</b><small>Create one at platform.claude.com › API keys and paste it here. It is kept in a private file on this Mac and left out of backups.</small></div>
        <div class="chips"><input type="password" data-ask-key placeholder="${cfg.hasKey?'API key saved — paste to replace':'API key'}" autocomplete="off"><button class="chip small" data-ask-save>Save</button>${cfg.hasKey?'<button class="chip small" data-ask-remove>Remove</button>':''}</div></div>`;
    const first=[...home.children].find(x=>!x.classList.contains('translation-settings'));
    home.insertBefore(label,first||null);label.after(box);
    box.querySelectorAll('[data-ask-engine]').forEach(b=>b.onclick=handle(async()=>{await api('ask.set',{engine:b.dataset.askEngine});renderAskSettings();}));
    box.querySelector('[data-ask-answer]').onchange=handle(async e=>{await api('ask.set',{answer:e.target.value});});
    box.querySelector('[data-ask-save]').onclick=handle(async()=>{
      const key=box.querySelector('[data-ask-key]').value.trim();if(!key){toast('Paste a key first');return;}
      await api('ask.set',{key,engine:'api'});toast('Saved');renderAskSettings();
    });
    const remove=box.querySelector('[data-ask-remove]');
    if(remove)remove.onclick=handle(async()=>{await api('ask.set',{key:''});toast('Key removed');renderAskSettings();});
  }
  if(typeof renderLibrary==='function'){
    const original=renderLibrary;
    window.renderLibrary=renderLibrary=async function(){await original();renderAskSettings();};
  }
  renderAskSettings();
})();
