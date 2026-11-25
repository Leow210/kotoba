package android.graphics;

/** iOS stand-in for Canvas: fills and nearest-neighbour bitmap copies. */
public class Canvas {
    private final Bitmap target;
    public Canvas(Bitmap target){this.target=target;}
    public void drawColor(int argb){target.eraseColor(argb);}
    public void drawBitmap(Bitmap src,Rect from,Rect to,Paint paint){
        Rect f=from!=null?from:new Rect(0,0,src.width,src.height);
        for(int y=to.top;y<to.bottom;y++)for(int x=to.left;x<to.right;x++){
            if(x<0||y<0||x>=target.width||y>=target.height)continue;
            int sx=f.left+(x-to.left)*f.width()/Math.max(1,to.width()),sy=f.top+(y-to.top)*f.height()/Math.max(1,to.height());
            if(sx<0||sy<0||sx>=src.width||sy>=src.height)continue;
            target.pixels[y*target.width+x]=src.pixels[sy*src.width+sx];
        }
    }
}
