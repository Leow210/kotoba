import numpy as np
from PIL import Image
def column_to_line(crop):
    """Vertical text column → characters laid out upright, left to right."""
    g=np.asarray(crop.convert('L'),dtype=np.float32)
    h,w=g.shape
    bg=np.median(g)
    ink=np.abs(g-bg)>60 if True else None
    rows=ink.sum(1)
    on=rows>max(1,0.02*w)
    segs=[];s=None
    for i,v in enumerate(on):
        if v and s is None:s=i
        if not v and s is not None:segs.append([s,i]);s=None
    if s is not None:segs.append([s,h])
    if not segs:return crop.rotate(90,expand=True)
    # merge pieces of one character (二, 三, radicals) up to about a square cell
    merged=[segs[0]]
    for a,b in segs[1:]:
        pa,pb=merged[-1]
        if b-pa<=w*0.95 and (pb-pa<w*0.55 or b-a<w*0.3 and a-pb<w*0.15):merged[-1]=[pa,b]
        else:merged.append([a,b])
    cell=w
    out=Image.new('RGB',(cell*len(merged)+cell//4*(len(merged)+1),cell),tuple(int(bg) for _ in range(3)))
    x=cell//4
    for a,b in merged:
        piece=crop.crop((0,a,w,b))
        ph=b-a
        if ph>cell:piece=piece.resize((max(1,int(w*cell/ph)),cell));
        out.paste(piece,(x+(cell-piece.width)//2,(cell-piece.height)//2))
        x+=cell+cell//4
    return out
