/**
 * Dark mode for dictionary pages: no inverting filter. Light backgrounds are re-lit (hue kept, so tinted rows and badges
 * stay tinted), dark text and borders are lifted, and any text that would still be hard to read on its new background is
 * nudged until it passes. Dark line-art images on transparent backgrounds are inverted; photos and diagrams are left alone.
 */
function darkenDoc(doc){
  const root=doc.documentElement;
  if(root.dataset.kotobaDarkened)return;
  root.dataset.kotobaDarkened='1';
  const win=doc.defaultView;
  const parse=v=>{
    const m=/^rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:\s*[,/]\s*([\d.]+)(%?))?\s*\)$/.exec(v||'');
    if(!m)return null;
    let a=m[4]===undefined?1:parseFloat(m[4]);if(m[5])a/=100;
    return [+m[1],+m[2],+m[3],a];
  };
  const toHsl=([r,g,b])=>{
    r/=255;g/=255;b/=255;
    const mx=Math.max(r,g,b),mn=Math.min(r,g,b),l=(mx+mn)/2,d=mx-mn;
    if(!d)return [0,0,l];
    const s=d/(1-Math.abs(2*l-1));
    const h=mx===r?((g-b)/d+(g<b?6:0)):mx===g?(b-r)/d+2:(r-g)/d+4;
    return [h*60,s,l];
  };
  const fromHsl=(h,s,l)=>{
    const c=(1-Math.abs(2*l-1))*s,x=c*(1-Math.abs((h/60)%2-1)),m=l-c/2;
    const [r,g,b]=h<60?[c,x,0]:h<120?[x,c,0]:h<180?[0,c,x]:h<240?[0,x,c]:h<300?[x,0,c]:[c,0,x];
    return [Math.round((r+m)*255),Math.round((g+m)*255),Math.round((b+m)*255)];
  };
  const lum=([r,g,b])=>{const f=v=>{v/=255;return v<=.03928?v/12.92:Math.pow((v+.055)/1.055,2.4);};return .2126*f(r)+.7152*f(g)+.0722*f(b);};
  const ratio=(a,b)=>{const x=lum(a),y=lum(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);};
  const css=(c,a)=>a<1?`rgba(${c[0]},${c[1]},${c[2]},${a})`:`rgb(${c[0]},${c[1]},${c[2]})`;
  const SURFACE=[28,31,28];
  const mapBg=c=>{const [h,s,l]=toHsl(c);return l>.5?fromHsl(h,s*.6,.12+(1-l)*.5):c;};
  const mapFg=c=>{const [h,s,l]=toHsl(c);return l<.5?fromHsl(h,s,.92-l*.7):c;};
  const mapLine=c=>{const [h,s,l]=toHsl(c);return l>.5?fromHsl(h,s*.6,.12+(1-l)*.5):fromHsl(h,s,.55-l*.4);};
  const blend=(c,a,under)=>[0,1,2].map(i=>Math.round(c[i]*a+under[i]*(1-a)));
  const els=[doc.body,...doc.body.querySelectorAll('*')].slice(0,8000);
  const rows=els.map(el=>{
    const cs=win.getComputedStyle(el);
    return {el,color:parse(cs.color),bg:parse(cs.backgroundColor),parent:el.parentElement,
      borders:['Top','Right','Bottom','Left'].map(side=>parseFloat(cs['border'+side+'Width'])>0?parse(cs['border'+side+'Color']):null)};
  });
  const eff=new Map(),orig=new Map(),SIDES=['top','right','bottom','left'];
  for(const row of rows){
    const {el}=row,up=row.parent&&eff.has(row.parent)?eff.get(row.parent):SURFACE;
    let under=up;
    if(row.bg&&row.bg[3]>0){
      const m=mapBg(row.bg),a=row.bg[3];
      if(m!==row.bg){el.style.setProperty('background-color',css(m,a),'important');}
      under=a<1?blend(m,a,up):m;
    }
    eff.set(el,under);
    orig.set(el,row.color);
    row.borders.forEach((b,i)=>{
      if(!b||b[3]===0)return;
      el.style.setProperty('border-'+SIDES[i]+'-color',css(mapLine(b),b[3]),'important');
    });
    if(!row.color)continue;
    const parentColor=row.parent?orig.get(row.parent):null;
    const inherited=parentColor&&parentColor.join()===row.color.join();
    if(inherited&&under===up)continue;
    let fg=mapFg(row.color);
    if(ratio(fg,under)<4.5){
      const [h,s]=toHsl(fg),dark=lum(under)<.4;
      for(let l=toHsl(fg)[2],n=0;n<20&&ratio(fg,under)<4.5;n++){l=dark?Math.min(.95,l+.05):Math.max(.05,l-.05);fg=fromHsl(h,s,l);}
    }
    el.style.setProperty('color',css(fg,row.color[3]),'important');
  }
  // Dark line art with a see-through background would vanish on the dark page: invert just those images.
  const check=img=>{
    try{
      const w=img.naturalWidth,h=img.naturalHeight;if(!w||!h)return;
      const cv=doc.createElement('canvas');cv.width=cv.height=32;
      const g=cv.getContext('2d',{willReadFrequently:true});g.drawImage(img,0,0,32,32);
      const d=g.getImageData(0,0,32,32).data;let solid=0,lit=0,n=0;
      for(let i=0;i<d.length;i+=4){if(d[i+3]>128){solid++;lit+=lum([d[i],d[i+1],d[i+2]]);}n++;}
      const hasAlpha=solid<n*.9;
      if(hasAlpha&&solid&&lit/solid<.25)img.style.setProperty('filter','invert(1) hue-rotate(180deg)');
    }catch(e){}
  };
  doc.querySelectorAll('img').forEach(img=>{if(img.complete)check(img);else img.addEventListener('load',()=>check(img),{once:true});});
}
