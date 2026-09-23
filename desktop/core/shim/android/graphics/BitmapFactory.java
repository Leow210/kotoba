package android.graphics;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import javax.imageio.ImageIO;

/**
 * Desktop stand-in for BitmapFactory. Java reads JPEG/PNG/GIF/BMP; WebP and AVIF (common in Mihon downloads)
 * go through macOS's own image converter (sips).
 */
public final class BitmapFactory {
    private BitmapFactory(){}
    public static class Options {
        public boolean inJustDecodeBounds;public int inSampleSize=1;public int outWidth,outHeight;public Bitmap.Config inPreferredConfig;public String outMimeType;
    }
    static BufferedImage read(byte[] data){
        try{BufferedImage b=ImageIO.read(new ByteArrayInputStream(data));if(b!=null)return b;}catch(Exception ignored){}
        File in=null,out=null;
        try{
            in=File.createTempFile("kotoba-img",".bin");out=File.createTempFile("kotoba-img",".png");
            Files.write(in.toPath(),data);
            Process p=new ProcessBuilder("/usr/bin/sips","-s","format","png",in.getPath(),"--out",out.getPath()).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if(p.waitFor()!=0)return null;
            return ImageIO.read(out);
        }catch(Exception e){return null;}
        finally{if(in!=null)in.delete();if(out!=null)out.delete();}
    }
    public static Bitmap decodeByteArray(byte[] data,int offset,int length){return decodeByteArray(data,offset,length,null);}
    public static Bitmap decodeByteArray(byte[] data,int offset,int length,Options o){
        byte[] d=offset==0&&length==data.length?data:java.util.Arrays.copyOfRange(data,offset,offset+length);
        BufferedImage b=read(d);
        if(b==null)return null;
        if(o!=null){o.outWidth=b.getWidth();o.outHeight=b.getHeight();if(o.inJustDecodeBounds)return null;}
        Bitmap bm=new Bitmap(b);
        int s=o==null?1:Math.max(1,o.inSampleSize);
        return s==1?bm:Bitmap.createScaledBitmap(bm,Math.max(1,b.getWidth()/s),Math.max(1,b.getHeight()/s),true);
    }
    public static Bitmap decodeFile(String path){return decodeFile(path,null);}
    public static Bitmap decodeFile(String path,Options o){
        try{byte[] d=Files.readAllBytes(new File(path).toPath());return decodeByteArray(d,0,d.length,o);}catch(Exception e){return null;}
    }
}
