import onnxruntime as ort, numpy as np, sys, time
from PIL import Image
chars=['blank']+open('kor_dict.txt',encoding='utf-8').read().split('\n')+[' ']
det=ort.InferenceSession('det.onnx');rec=ort.InferenceSession('kor.onnx')
def detect(img):
    W,H=img.size
    scale=min(1.0,960/max(W,H)) if max(W,H)<=1600 else min(1.0,960/W)
    w=max(32,int(round(W*scale/32))*32);h=max(32,int(round(H*scale/32))*32)
    a=np.asarray(img.convert('RGB').resize((w,h)),dtype=np.float32)[:,:,::-1]/255.0
    a=(a-[0.485,0.456,0.406])/[0.229,0.224,0.225]
    x=a.transpose(2,0,1)[None].astype(np.float32)
    prob=det.run(None,{det.get_inputs()[0].name:x})[0][0,0]
    mask=prob>0.3
    # connected components (4-conn) via flood fill
    lab=np.zeros(mask.shape,np.int32);n=0;boxes=[]
    ys,xs=np.nonzero(mask)
    for y0,x0 in zip(ys,xs):
        if lab[y0,x0]:continue
        n+=1;stack=[(y0,x0)];lab[y0,x0]=n;pts=[]
        while stack:
            y,x=stack.pop();pts.append((y,x))
            for dy,dx in((1,0),(-1,0),(0,1),(0,-1)):
                yy,xx=y+dy,x+dx
                if 0<=yy<mask.shape[0] and 0<=xx<mask.shape[1] and mask[yy,xx] and not lab[yy,xx]:lab[yy,xx]=n;stack.append((yy,xx))
        if len(pts)<10:continue
        py,px=zip(*pts);y1,y2,x1,x2=min(py),max(py)+1,min(px),max(px)+1
        score=prob[lab==n].mean()
        if score<0.6:continue
        bw,bh=x2-x1,y2-y1;d=bw*bh*1.5/(2*(bw+bh))
        boxes.append([ (x1-d)/w*W,(y1-d)/h*H,(x2+d)/w*W,(y2+d)/h*H,score])
    return boxes
def recognize(img,box):
    x1,y1,x2,y2=[max(0,v) for v in box[:4]]
    crop=img.crop((int(x1),int(y1),int(x2),int(y2))).convert('RGB')
    cw,ch=crop.size;tw=min(3200,max(16,int(np.ceil(48*cw/ch))))
    a=np.asarray(crop.resize((tw,48)),dtype=np.float32)[:,:,::-1]/255.0
    a=(a-0.5)/0.5;x=a.transpose(2,0,1)[None].astype(np.float32)
    out=rec.run(None,{rec.get_inputs()[0].name:x})[0][0]
    idx=out.argmax(1);prob=out.max(1);res=[];ps=[];last=-1
    for i,p in zip(idx,prob):
        if i!=last and i!=0:res.append(chars[i]);ps.append(p)
        last=i
    return ''.join(res),float(np.mean(ps)) if ps else 0
img=Image.open(sys.argv[1]);t=time.time()
bs=detect(img);print('boxes',len(bs),round(time.time()-t,2),'s')
for b in bs:print([int(v) for v in b[:4]],recognize(img,b))
print('total',round(time.time()-t,2))
