'use strict';
/*
 * Review statistics, as in Anki: a 30-day heatmap at the foot of the Review tab, and a Stats screen with the whole
 * calendar and tabs (Today, Calendar, Reviews, Forecast, Cards, Intervals, Hours, Buttons, Difficulty, Retention).
 * Days roll over at 4am, like the scheduler. Everything is worked out here from api('stats.raw').
 */
(function(){
  const DAY=86400000,ROLL=4*3600000;
  /** The study day of a time (seconds): days since 1970 in local time, starting at 4am. */
  const dayOf=s=>{const d=new Date(s*1000-ROLL);return Math.floor(Date.UTC(d.getFullYear(),d.getMonth(),d.getDate())/DAY);};
  const today=()=>dayOf(Date.now()/1000);
  const dateOf=n=>new Date(n*DAY);// UTC date of a day number
  const fmtDate=n=>dateOf(n).toLocaleDateString(undefined,{timeZone:'UTC',month:'short',day:'numeric',year:'numeric'});
  const pct=(a,b)=>b?Math.round(a/b*100)+'%':'—';
  const kind=r=>r[2]===0?'new':r[2]===1?'learn':r[2]===3?'relearn':r[3]>=21?'mature':'young';
  const COLORS={new:'var(--st-new)',learn:'var(--st-learn)',relearn:'var(--st-relearn)',young:'var(--st-young)',mature:'var(--st-mature)',suspended:'var(--st-susp)'};
  const LABEL={new:'New',learn:'Learning',relearn:'Relearning',young:'Young',mature:'Mature',suspended:'Suspended'};

  function byDay(reviews){const m=new Map();for(const r of reviews){const d=dayOf(r[0]);m.set(d,(m.get(d)||0)+1);}return m;}
  function level(n,max){if(!n)return 0;const f=n/Math.max(1,max);return f>0.75?4:f>0.5?3:f>0.25?2:1;}

  /** A heatmap from day `from` to `to`: columns are weeks (Monday on top), a square per day. */
  function heatmap(counts,from,to,{small=false}={}){
    const max=Math.max(1,...[...counts.entries()].filter(([d])=>d>=from&&d<=to).map(([,n])=>n));
    const start=from-((dateOf(from).getUTCDay()+6)%7);// back to Monday
    let cols='';
    for(let w=start;w<=to;w+=7){
      let cells='';
      for(let d=w;d<w+7;d++){
        if(d<from||d>to){cells+='<i class="st-hm-x"></i>';continue;}
        const n=counts.get(d)||0;
        cells+=`<i class="st-hm-${level(n,max)}${d===today()?' st-hm-today':''}" title="${fmtDate(d)}: ${n} review${n===1?'':'s'}"></i>`;
      }
      cols+=`<div>${cells}</div>`;
    }
    return `<div class="st-hm${small?' small':''}">${cols}</div>`;
  }
  function streaks(counts){
    let cur=0,d=today();if(!counts.get(d))d--;while(counts.get(d)){cur++;d--;}
    const days=[...counts.keys()].sort((a,b)=>a-b);let best=0,run=0,prev=null;
    for(const x of days){run=prev===x-1?run+1:1;best=Math.max(best,run);prev=x;}
    return {cur,best};
  }

  // ---------- the strip at the foot of the Review tab ----------
  async function reviewStrip(box){
    const raw=await api('stats.raw',{folder:0}).catch(()=>null);if(!raw||!box.isConnected)return;
    const counts=byDay(raw.reviews),t=today(),from=t-29;
    let total=0,days=0;for(let d=from;d<=t;d++){const n=counts.get(d)||0;total+=n;if(n)days++;}
    const {cur}=streaks(counts);
    box.innerHTML=`<button class="cardbox st-strip" data-a="stats"><div class="section-label" style="padding:0 0 6px">Last 30 days<span>Stats ›</span></div>${heatmap(counts,from,t,{small:true})}
      <div class="st-strip-sum"><span><b>${total.toLocaleString()}</b> reviews</span><span><b>${days}</b>/30 days</span><span><b>${cur}</b>-day streak</span><span><b>${Math.round(total/30)}</b>/day</span></div></button>`;
    box.querySelector('[data-a="stats"]').onclick=()=>openStats();
  }

  // ---------- the Stats screen ----------
  const TABS=[['today','Today'],['calendar','Calendar'],['reviews','Reviews'],['future','Forecast'],['cards','Cards'],['intervals','Intervals'],['hours','Hours'],['buttons','Buttons'],['difficulty','Difficulty'],['retention','Retention']];
  async function openStats(tab='calendar'){
    const folders=await api('folders').catch(()=>[]);
    const el=document.createElement('div');el.className='st-page';
    el.innerHTML=`<div class="bar"><button class="icon-btn" data-a="back" aria-label="Back">${icon('back')}</button><div class="title"><b>Statistics</b><small data-f="sub"></small></div>
        <select class="st-deck" data-f="deck"><option value="0">All decks</option>${folders.map(f=>`<option value="${f.id}">${esc(f.name)}</option>`).join('')}</select></div>
      <div class="st-tabs" data-f="tabs">${TABS.map(([k,l])=>`<button class="chip small" data-t="${k}">${l}</button>`).join('')}</div>
      <div class="st-scroll"><div class="st-body" data-f="body"></div></div>`;
    pushPage(el);
    el.querySelector('[data-a="back"]').onclick=()=>popPage();
    const f=n=>el.querySelector(`[data-f="${n}"]`);
    let raw=null,range=30,year=null;
    async function load(){raw=await api('stats.raw',{folder:+f('deck').value});f('sub').textContent=`${raw.reviews.length.toLocaleString()} reviews · ${raw.cards.length.toLocaleString()} cards`;show();}
    function show(){
      f('tabs').querySelectorAll('[data-t]').forEach(b=>b.classList.toggle('on',b.dataset.t===tab));
      f('body').innerHTML=RENDER[tab]();
      f('body').querySelectorAll('[data-range]').forEach(b=>b.onclick=()=>{range=+b.dataset.range;show();});
      f('body').querySelectorAll('[data-year]').forEach(b=>b.onclick=()=>{year=+b.dataset.year;show();});
    }
    const rangeChips=(opts=[[30,'1 month'],[90,'3 months'],[365,'1 year'],[0,'All']])=>`<div class="st-range">${opts.map(([v,l])=>`<button class="chip small ${range===v?'on':''}" data-range="${v}">${l}</button>`).join('')}</div>`;
    const inRange=r=>!range||dayOf(r[0])>today()-range;
    const ok=r=>r[1]>1;

    /** Bars: each {label, parts:{kind:n}} → stacked columns with a legend. */
    function bars(cols,kinds,{title='',every=1}={}){
      const max=Math.max(1,...cols.map(c=>kinds.reduce((a,k)=>a+(c.parts[k]||0),0)));
      return `${title?`<h3>${title}</h3>`:''}<div class="st-bars">${cols.map((c,i)=>{
        const tot=kinds.reduce((a,k)=>a+(c.parts[k]||0),0);
        return `<div title="${esc(c.tip||c.label)}: ${tot}"><span class="st-stack" style="height:${tot/max*100}%">${kinds.map(k=>c.parts[k]?`<i style="flex:${c.parts[k]};background:${COLORS[k]||k}"></i>`:'').join('')}</span><small>${i%every===0?esc(c.label):''}</small></div>`;}).join('')}</div>
        ${kinds.length>1?`<div class="st-legend">${kinds.map(k=>`<span><i style="background:${COLORS[k]||k}"></i>${LABEL[k]||k}</span>`).join('')}</div>`:''}`;
    }
    const table=(rows,head=false)=>`<table class="st-table">${rows.map((r,n)=>`<tr${head&&!n?' class="st-head"':''}>${r.map((c,i)=>i?`<td>${c}</td>`:`<th>${c}</th>`).join('')}</tr>`).join('')}</table>`;
    const tiles=list=>`<div class="st-tiles">${list.map(([n,l])=>`<div><b>${n}</b><small>${l}</small></div>`).join('')}</div>`;

    const RENDER={
      today(){
        const t=today(),rs=raw.reviews.filter(r=>dayOf(r[0])===t);
        const k={};rs.forEach(r=>k[kind(r)]=(k[kind(r)]||0)+1);
        const again=rs.filter(r=>r[1]===1).length;
        const rev=rs.filter(r=>r[2]===2);
        const counts=byDay(raw.reviews),{cur,best}=streaks(counts);
        return tiles([[rs.length,'reviews today'],[pct(rs.length-again,rs.length),'answered correctly'],[again,'Again'],[cur,'day streak'],[best,'longest streak'],[raw.reviews.length.toLocaleString(),'reviews in all']])+
          table([['New',k.new||0],['Learning',k.learn||0],['Relearning',k.relearn||0],['Young reviews',k.young||0],['Mature reviews',k.mature||0],['Review cards remembered',pct(rev.filter(ok).length,rev.length)]]);
      },
      calendar(){
        const counts=byDay(raw.reviews),t=today();
        const years=[...new Set([...counts.keys()].map(d=>dateOf(d).getUTCFullYear()))].sort();
        const thisYear=dateOf(t).getUTCFullYear();if(!years.includes(thisYear))years.push(thisYear);
        const y=year&&years.includes(year)?year:thisYear;
        const from=Math.floor(Date.UTC(y,0,1)/DAY),to=Math.min(Math.floor(Date.UTC(y,11,31)/DAY),t);
        // Days studied count from the first review (not from 1 January of a year you started midway).
        const first=Math.max(from,Math.min(to,...[...counts.keys()].filter(d=>d>=from)));
        let total=0,days=0;for(let d=first;d<=to;d++){const n=counts.get(d)||0;total+=n;if(n)days++;}
        const span=to-first+1,{cur,best}=streaks(counts);
        return `<div class="st-range">${years.map(v=>`<button class="chip small ${v===y?'on':''}" data-year="${v}">${v}</button>`).join('')}</div>
          <div class="st-hm-wrap">${heatmap(counts,from,to)}</div>
          <div class="st-legend st-hm-legend"><span>Less</span>${[0,1,2,3,4].map(l=>`<i class="st-hm-${l}"></i>`).join('')}<span>More</span></div>`+
          tiles([[pct(days,span),`days studied (${days} of ${span})`],[days?Math.round(total/days):0,'average on days studied'],[Math.round(total/span*10)/10,'average over the period'],[cur,'current streak'],[best,'longest streak'],[total.toLocaleString(),`reviews in ${y}`]]);
      },
      reviews(){
        const t=today(),n=range||Math.max(30,t-Math.min(t,...raw.reviews.map(r=>dayOf(r[0])))+1);
        const width=n>120?7:1,cols=[];
        for(let d=t-n+1;d<=t;d+=width){
          const parts={};raw.reviews.forEach(r=>{const x=dayOf(r[0]);if(x>=d&&x<d+width){const k=kind(r);parts[k]=(parts[k]||0)+1;}});
          cols.push({label:dateOf(d).toLocaleDateString(undefined,{timeZone:'UTC',month:'numeric',day:'numeric'}),tip:width>1?'Week of '+fmtDate(d):fmtDate(d),parts});
        }
        const rs=raw.reviews.filter(inRange),studied=new Set(rs.map(r=>dayOf(r[0]))).size;
        return rangeChips()+bars(cols,['new','learn','relearn','young','mature'],{every:Math.ceil(cols.length/8)})+
          tiles([[rs.length.toLocaleString(),'reviews'],[studied,'days studied'],[studied?Math.round(rs.length/studied):0,'per day studied'],[rs.filter(r=>r[2]===0).length,'new cards seen']]);
      },
      future(){
        const n=range||365,t=today(),cols=[],due=raw.cards.filter(c=>c[7]===1&&c[0]>0);
        const width=n>120?7:1;let total=0;
        for(let d=t;d<t+n;d+=width){
          const parts={};due.forEach(c=>{const x=Math.max(t,dayOf(c[1]));if(x>=d&&x<d+width){const k=c[0]===2?(c[3]>=21?'mature':'young'):'learn';parts[k]=(parts[k]||0)+1;total++;}});
          cols.push({label:d===t?'Today':'+'+(d-t),tip:width>1?'Week of '+fmtDate(d):fmtDate(d),parts});
        }
        const tomorrow=due.filter(c=>dayOf(c[1])===t+1).length,overdue=due.filter(c=>dayOf(c[1])<t).length;
        return rangeChips([[30,'1 month'],[90,'3 months'],[365,'1 year']])+bars(cols,['learn','young','mature'],{every:Math.ceil(cols.length/8)})+
          tiles([[total,'due in this period'],[tomorrow,'due tomorrow'],[overdue,'overdue'],[Math.round(total/n*10)/10,'per day']]);
      },
      cards(){
        const k={new:0,learn:0,relearn:0,young:0,mature:0,suspended:0};
        raw.cards.forEach(c=>{if(!c[7])k.suspended++;else if(c[0]===0)k.new++;else if(c[0]===1)k.learn++;else if(c[0]===3)k.relearn++;else if(c[3]>=21)k.mature++;else k.young++;});
        const total=raw.cards.length;
        const pieSlices=[];let at=0;for(const [key,n] of Object.entries(k)){if(!n)continue;pieSlices.push(`${COLORS[key]} ${at/total*360}deg ${(at+n)/total*360}deg`);at+=n;}
        return `<div class="st-cards"><div class="st-pie" style="background:conic-gradient(${pieSlices.join(',')||'var(--line) 0 360deg'})"></div>${table(Object.entries(k).map(([key,n])=>[`<i class="st-dot" style="background:${COLORS[key]}"></i>${LABEL[key]}`,n,pct(n,total)]))}</div>
          <p class="hint">Young: in review, stable for less than 21 days. Mature: 21 days or more.</p>`+tiles([[total,'cards'],[raw.cards.reduce((a,c)=>a+c[6],0),'times forgotten (lapses)'],[raw.cards.reduce((a,c)=>a+c[5],0).toLocaleString(),'reviews of these cards']]);
      },
      intervals(){
        const iv=raw.cards.filter(c=>c[7]===1&&c[0]===2&&c[2]).map(c=>Math.max(1,Math.round((c[1]-c[2])/86400)));
        const edges=[1,2,3,4,5,7,10,14,21,30,45,60,90,120,180,270,365,730,Infinity];
        const cols=edges.slice(0,-1).map((e,i)=>({label:edges[i+1]===Infinity?e+'+':e+'',tip:`${e}–${edges[i+1]===Infinity?'':edges[i+1]-1} days`,parts:{young:iv.filter(x=>x>=e&&x<edges[i+1]&&x<21).length,mature:iv.filter(x=>x>=e&&x<edges[i+1]&&x>=21).length}}));
        const sorted=iv.slice().sort((a,b)=>a-b);
        return bars(cols,['young','mature'],{title:'Days between reviews, now'})+
          tiles([[iv.length?Math.round(iv.reduce((a,b)=>a+b,0)/iv.length):0,'average interval (days)'],[sorted.length?sorted[Math.floor(sorted.length/2)]:0,'median'],[sorted.length?sorted[sorted.length-1]:0,'longest']]);
      },
      hours(){
        const rs=raw.reviews.filter(inRange),h=Array.from({length:24},()=>({n:0,ok:0}));
        rs.forEach(r=>{const x=new Date(r[0]*1000).getHours();h[x].n++;if(ok(r))h[x].ok++;});
        const max=Math.max(1,...h.map(x=>x.n));
        return rangeChips()+`<h3>Reviews by hour · bar: count, number: answered correctly</h3><div class="st-bars st-hours">${h.map((x,i)=>`<div title="${i}:00 – ${x.n} reviews, ${pct(x.ok,x.n)} correct"><em>${x.n?pct(x.ok,x.n):''}</em><span class="st-stack" style="height:${x.n/max*100}%"><i style="flex:1;background:var(--st-young)"></i></span><small>${i%3===0?i:''}</small></div>`).join('')}</div>`;
      },
      buttons(){
        const rs=raw.reviews.filter(inRange);
        const groups=[['Learning',r=>r[2]===0||r[2]===1||r[2]===3],['Young',r=>r[2]===2&&r[3]<21],['Mature',r=>r[2]===2&&r[3]>=21]];
        const names=['Again','Hard','Good','Easy'];
        return rangeChips()+table([['','Again','Hard','Good','Easy','Correct'],...groups.map(([label,test])=>{
          const g=rs.filter(test),c=[1,2,3,4].map(v=>g.filter(r=>r[1]===v).length);
          return [label,...c.map(n=>`${n}<small> ${pct(n,g.length)}</small>`),pct(g.length-c[0],g.length)];
        })],true)+`<p class="hint">Correct: anything but Again. ${names.join(' · ')}.</p>`;
      },
      difficulty(){
        const cs=raw.cards.filter(c=>c[7]===1&&c[0]>0);
        const dcols=Array.from({length:10},(_,i)=>({label:(i*10+10)+'%',tip:`Difficulty ${i*10}–${i*10+10}%`,parts:{young:cs.filter(c=>Math.min(9,Math.floor((c[4]-1)/9*10))===i).length}}));
        const now=raw.now,R=c=>{const t=Math.max(0,(now-c[2])/86400);return c[3]>0?Math.pow(1+19/81*t/c[3],-0.5):1;};
        const rv=cs.filter(c=>c[0]===2&&c[2]),avgR=rv.length?rv.reduce((a,c)=>a+R(c),0)/rv.length:0;
        const scols=[1,3,7,14,30,60,120,365,Infinity].map((e,i,a)=>({label:i?`${a[i-1]}+`:'<1',parts:{mature:cs.filter(c=>c[3]<e&&(i?c[3]>=a[i-1]:true)).length}}));
        return bars(dcols,['young'],{title:'Difficulty (FSRS): how hard each card is for you'})+
          bars(scols,['mature'],{title:'Stability (days until recall falls to 90%)'})+
          tiles([[Math.round(avgR*100)+'%','average chance of recalling a review card now'],[Math.round(raw.retention*100)+'%','target recall (Review settings)'],[cs.length?Math.round(cs.reduce((a,c)=>a+(c[4]-1)/9,0)/cs.length*100)+'%':'—','average difficulty']]);
      },
      retention(){
        const t=today();
        const periods=[['Today',t],['Yesterday',t-1,t-1],['Last 7 days',t-6],['Last 30 days',t-29],['Last year',t-364],['All time',-1e9]];
        return `<h3>True retention: review cards remembered (not Again)</h3>`+table([['','Young','Mature','Total','Reviews'],...periods.map(([label,from,to=t])=>{
          const rs=raw.reviews.filter(r=>{const d=dayOf(r[0]);return r[2]===2&&d>=from&&d<=to;});
          const y=rs.filter(r=>r[3]<21),m=rs.filter(r=>r[3]>=21);
          return [label,pct(y.filter(ok).length,y.length),pct(m.filter(ok).length,m.length),pct(rs.filter(ok).length,rs.length),rs.length];
        })],true)+`<p class="hint">Your target recall is ${Math.round(raw.retention*100)}%; if retention sits well below it, cards are coming back too late (or are too hard), well above it, you could raise intervals by lowering the target.</p>`;
      },
    };
    f('tabs').querySelectorAll('[data-t]').forEach(b=>b.onclick=()=>{tab=b.dataset.t;show();});
    f('deck').onchange=handle(load);
    await load();
  }

  window.reviewStrip=reviewStrip;window.openStats=openStats;
})();
