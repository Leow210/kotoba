"""Desktop mirror of Ocr.java for tuning. python3 evalocr.py [--det 960] [--out dir] lang img..."""
import onnxruntime as ort, numpy as np, sys, os, time, argparse
from PIL import Image, ImageDraw, ImageFont
from scipy import ndimage
H=os.path.dirname(os.path.abspath(__file__))
ap=argparse.ArgumentParser();ap.add_argument('lang');ap.add_argument('imgs',nargs='+')
ap.add_argument('--det',type=int,default=960);ap.add_argument('--tile',type=float,default=1.5)
ap.add_argument('--thresh',type=float,default=0.3);ap.add_argument('--box',type=float,default=0.6)
ap.add_argument('--unclip',type=float,default=1.5);ap.add_argument('--minconf',type=float,default=0.5)
ap.add_argument('--gap',type=float,default=0.9);ap.add_argument('--split',action='store_true')
ap.add_argument('--hx',type=float,default=1.0);ap.add_argument('--minw',type=int,default=320);ap.add_argument('--out',default=None);ap.add_argument('--quiet',action='store_true')
a=ap.parse_args()
model='ko' if a.lang=='ko' else 'ja'
chars=['']+open(f'{H}/{"kor_dict.txt" if model=="ko" else "ja_dict.txt"}',encoding='utf-8').read().split('\n')+[' ']
det=ort.InferenceSession(f'{H}/det.onnx');rec=ort.InferenceSession(f'{H}/{"kor.onnx" if model=="ko" else "ja.onnx"}')
MEAN=np.array([0.485,0.456,0.406]);STD=np.array([0.229,0.224,0.225])

def detect(img):
    W,Hh=img.size
    scale=min(1.0,a.det/max(W,Hh))
    w=max(32,int(round(W*scale/32))*32);h=max(32,int(round(Hh*scale/32))*32)
    x=np.asarray(img.resize((w,h),Image.BILINEAR),dtype=np.float32)[:,:,::-1]/255.0
    x=((x-MEAN)/STD).transpose(2,0,1)[None].astype(np.float32)
    prob=det.run(None,{det.get_inputs()[0].name:x})[0][0,0]
    mask=prob>a.thresh
    lab,n=ndimage.label(mask)
    boxes=[]
    for i,sl in enumerate(ndimage.find_objects(lab)):
        comp=lab[sl]==i+1
        if comp.sum()<10:continue
        score=prob[sl][comp].mean()
        if score<a.box:continue
        pieces=[(sl[0].start,sl[0].stop,sl[1].start,sl[1].stop)]
        if a.split:pieces=split(prob[sl]*comp,sl)
        for y1,y2,x1,x2 in pieces:
            bw,bh=x2-x1,y2-y1;d=bw*bh*a.unclip/(2*(bw+bh))
            dx=d*a.hx if bh>=bw*1.5 else d
            boxes.append([(x1-dx)/w*W,(y1-d)/h*Hh,(x2+dx)/w*W,(y2+d)/h*Hh])
    return boxes

def split(p,sl):
    """A component spanning several touching lines: cut at rows (or columns) where the text probability dips."""
    y0,x0=sl[0].start,sl[1].start
    hh,ww=p.shape
    out=[]
    vert=hh>ww*1.5
    prof=(p>a.thresh).sum(1 if not vert else 0).astype(float)
    L=len(prof);thick=min(hh,ww)
    # expected single line thickness unknown: split where profile drops below 35% of its max
    cut=prof<0.35*prof.max()
    segs=[];s=None
    for i in range(L):
        if not cut[i] and s is None:s=i
        if cut[i] and s is not None:segs.append((s,i));s=None
    if s is not None:segs.append((s,L))
    segs=[g for g in segs if g[1]-g[0]>=3]
    if len(segs)<=1:return [(y0,y0+hh,x0,x0+ww)]
    for s,e in segs:
        if not vert:
            rows=p[s:e];cols=np.nonzero((rows>a.thresh).any(0))[0]
            out.append((y0+s,y0+e,x0+cols.min(),x0+cols.max()+1))
        else:
            c=p[:,s:e];rows=np.nonzero((c>a.thresh).any(1))[0]
            out.append((y0+rows.min(),y0+rows.max()+1,x0+s,x0+e))
    return out

def recognize(img,b):
    x1,y1,x2,y2=[int(v) for v in b]
    crop=img.crop((x1,y1,x2,y2))
    if crop.size[1]>=crop.size[0]*1.5:crop=crop.rotate(90,expand=True)
    cw,ch=crop.size;tw=min(3200,max(16,int(np.ceil(48*cw/ch))))
    x=np.asarray(crop.resize((tw,48),Image.BILINEAR),dtype=np.float32)[:,:,::-1]/255.0
    x=((x-0.5)/0.5).transpose(2,0,1).astype(np.float32)
    pw=max(a.minw,tw);pad=np.zeros((3,48,pw),np.float32);pad[:,:,:tw]=x
    o=rec.run(None,{rec.get_inputs()[0].name:pad[None]})[0][0]
    idx=o.argmax(1);pr=o.max(1);t=[];ps=[];last=-1
    for i,p in zip(idx,pr):
        if i!=last and i!=0 and i<len(chars):t.append(chars[i]);ps.append(p)
        last=i
    return ''.join(t),float(np.mean(ps)) if ps else 0

def page(path):
    img=Image.open(path).convert('RGB');W,Hh=img.size
    tile=int(W*a.tile) if Hh>W*2 else Hh;ov=W//4 if Hh>W*2 else 0
    lines=[];y0=0
    while True:
        th=min(tile,Hh-y0)
        for b in detect(img.crop((0,y0,W,y0+th))):
            cy=(b[1]+b[3])/2
            if y0>0 and cy<ov/2:continue
            if y0+th<Hh and cy>th-ov/2:continue
            lines.append([b[0],b[1]+y0,b[2],b[3]+y0])
        if y0+th>=Hh:break
        y0+=tile-ov
    kept=[];dropped=[]
    for l in lines:
        l=[max(0,l[0]),max(0,l[1]),min(W,l[2]),min(Hh,l[3])]
        if l[2]-l[0]<4 or l[3]-l[1]<4:continue
        t,c=recognize(img,l)
        (kept if t.strip() and c>=a.minconf else dropped).append((l,t,c))
    return img,kept,dropped

tot=0;t0=time.time()
for f in a.imgs:
    img,kept,dropped=page(f);tot+=len(kept)
    if not a.quiet:
        print('==',f,len(kept),'lines')
        for l,t,c in sorted(kept,key=lambda k:k[0][1]):print('  %4d %4d %.2f %s'%(l[0],l[1],c,t))
        for l,t,c in dropped:print('  DROP %4d %4d %.2f %s'%(l[0],l[1],c,t))
    if a.out:
        os.makedirs(a.out,exist_ok=True);d=ImageDraw.Draw(img)
        for l,t,c in kept:d.rectangle(l,outline=(255,0,0),width=3)
        for l,t,c in dropped:d.rectangle(l,outline=(0,0,255),width=3)
        img.save(os.path.join(a.out,os.path.basename(f)))
print('total lines',tot,'time %.1fs'%(time.time()-t0))
