import sys
exec(open('ocr/evalocr.py').read().split("tot=0")[0].replace("H=os.path.dirname(os.path.abspath(__file__))","H='ocr'"))
from ocr.vert import column_to_line
def recimg(crop):
    cw,ch=crop.size;tw=min(3200,max(16,int(np.ceil(48*cw/ch))))
    x=np.asarray(crop.resize((tw,48),Image.BILINEAR),dtype=np.float32)[:,:,::-1]/255.0
    x=((x-0.5)/0.5).transpose(2,0,1);pw=max(320,tw);pad=np.zeros((3,48,pw),np.float32);pad[:,:,:tw]=x
    o=rec.run(None,{rec.get_inputs()[0].name:pad[None]})[0][0]
    idx=o.argmax(1);pr=o.max(1);t=[];ps=[];last=-1
    for i,p in zip(idx,pr):
        if i!=last and i!=0 and i<len(chars):t.append(chars[i]);ps.append(p)
        last=i
    return ''.join(t),float(np.mean(ps)) if ps else 0
def fit(t,c,exp):
    n=len(t.replace('…','..'))
    if not n:return 0
    return c*min(n,exp)/max(n,exp)
for f in a.imgs:
    img=Image.open(f).convert('RGB')
    _,kept,dropped=page(f)
    allb=[k[0] for k in kept+dropped]
    vw=sorted(k[0][2]-k[0][0] for k in kept if k[0][3]-k[0][1]>=(k[0][2]-k[0][0])*1.5)
    med=vw[len(vw)//2] if vw else 0
    def ruby(l):
        w=l[2]-l[0]
        return w<med*0.6
        for o in allb:
            if o is l:continue
            ow=o[2]-o[0]
            if ow>w*1.6 and o[3]-o[1]>(l[3]-l[1]) and -w*0.6<l[0]-o[2]<w*0.8 and min(l[3],o[3])-max(l[1],o[1])>0.5*(l[3]-l[1]):return True
        return False
    for l,t,c in kept+dropped:
        x1,y1,x2,y2=[int(v) for v in l]
        if y2-y1<(x2-x1)*1.5:continue
        if ruby(l):print('   ruby',t);continue
        crop=img.crop((x1,y1,x2,y2));exp=max(1,(y2-y1)/(x2-x1)*1.6)
        s,sc=recimg(column_to_line(crop))
        r=(t,c);st=(s,sc)
        best=max([r,st],key=lambda k:fit(k[0],k[1],exp))
        if best[1]<0.5 or not any(ch.isalnum() for ch in best[0]):continue
        print('%-14s exp%.1f rot=%s(%.2f) stitch=%s(%.2f)'%(best[0],exp,t,c,s,sc))
