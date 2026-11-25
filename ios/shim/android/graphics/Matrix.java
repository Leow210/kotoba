package android.graphics;

/** iOS stand-in for Matrix: quarter-turn rotations and scaling, which is all the core asks for. */
public class Matrix {
    private int quarterTurns;
    private float sx=1,sy=1;
    public boolean postRotate(float degrees){quarterTurns=(quarterTurns+Math.round(degrees/90f))&3;return true;}
    public boolean preRotate(float degrees){return postRotate(degrees);}
    public boolean postScale(float x,float y){sx*=x;sy*=y;return true;}
    public void reset(){quarterTurns=0;sx=sy=1;}

    Bitmap apply(Bitmap src){
        Bitmap b=src;
        for(int i=0;i<quarterTurns;i++){
            int w=b.width,h=b.height;int[] out=new int[w*h];
            for(int y=0;y<h;y++)for(int x=0;x<w;x++)out[x*h+(h-1-y)]=b.pixels[y*w+x];
            b=new Bitmap(h,w,out);
        }
        if(sx!=1||sy!=1)b=Bitmap.createScaledBitmap(b,Math.round(b.width*Math.abs(sx)),Math.round(b.height*Math.abs(sy)),true);
        return b;
    }
}
