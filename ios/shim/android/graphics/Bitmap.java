package android.graphics;

import java.io.OutputStream;

/**
 * iOS stand-in for Bitmap: ARGB pixels in memory. Decoding and encoding (JPEG, PNG) are done by the app's native side
 * (ImageIO) through {@link BitmapFactory} and {@link #compress}, which the Swift shell provides.
 */
public final class Bitmap {
    public enum Config { ARGB_8888, RGB_565 }
    public enum CompressFormat { JPEG, PNG, WEBP }

    final int width,height;
    final int[] pixels;

    Bitmap(int width,int height,int[] pixels){this.width=width;this.height=height;this.pixels=pixels;}

    public int getWidth(){return width;}
    public int getHeight(){return height;}
    public boolean isRecycled(){return false;}
    public void recycle(){}
    public int getPixel(int x,int y){return pixels[y*width+x];}
    public void getPixels(int[] out,int offset,int stride,int x,int y,int w,int h){
        for(int row=0;row<h;row++)System.arraycopy(pixels,(y+row)*width+x,out,offset+row*stride,w);
    }
    public void setPixels(int[] in,int offset,int stride,int x,int y,int w,int h){
        for(int row=0;row<h;row++)System.arraycopy(in,offset+row*stride,pixels,(y+row)*width+x,w);
    }
    public void eraseColor(int c){java.util.Arrays.fill(pixels,c);}

    public static Bitmap createBitmap(int w,int h,Config config){w=Math.max(1,w);h=Math.max(1,h);return new Bitmap(w,h,new int[w*h]);}
    public static Bitmap createBitmap(Bitmap src,int x,int y,int w,int h){return createBitmap(src,x,y,w,h,null,false);}
    public static Bitmap createBitmap(Bitmap src,int x,int y,int w,int h,Matrix m,boolean filter){
        Bitmap cut=new Bitmap(w,h,new int[w*h]);
        src.getPixels(cut.pixels,0,w,x,y,w,h);
        return m==null?cut:m.apply(cut);
    }
    public static Bitmap createScaledBitmap(Bitmap src,int w,int h,boolean filter){
        w=Math.max(1,w);h=Math.max(1,h);
        int[] out=new int[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)out[y*w+x]=src.pixels[Math.min(src.height-1,y*src.height/h)*src.width+Math.min(src.width-1,x*src.width/w)];
        return new Bitmap(w,h,out);
    }

    /** Encodes through the native side; the Swift shell installs {@link #encoder}. */
    public interface Encoder{byte[] encode(int[] argb,int width,int height,CompressFormat format,int quality);}
    public static Encoder encoder;
    public boolean compress(CompressFormat format,int quality,OutputStream out){
        if(encoder==null)return false;
        try{out.write(encoder.encode(pixels,width,height,format,quality));return true;}catch(java.io.IOException e){return false;}
    }
}
