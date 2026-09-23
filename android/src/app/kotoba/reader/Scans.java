package app.kotoba.reader;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Camera scans: photos of a monitor or tablet, and shared or picked screenshots, kept in files/scans/.
 * Text is read with the same on-device OCR as comics; a crop limits it to the game's text box.
 */
public class Scans {
    static final int KEEP=40;// older scans are deleted
    static final int MAX_SIDE=2400;// photos are 4000+ px; detection runs at 1280 anyway, recognition gains nothing beyond this
    final File dir;
    final Ocr ocr;

    public Scans(File dir,Ocr ocr){this.dir=dir;this.ocr=ocr;dir.mkdirs();}

    public File file(String name){
        if(!name.matches("scan-\\d+\\.(jpg|png|webp)"))throw new IllegalArgumentException("Bad scan name");
        return new File(dir,name);
    }

    /** Copies a photo from the scanner's camera, or a shared or picked image, in. */
    public String importStream(InputStream in,String mime) throws Exception {
        String ext=mime!=null&&mime.contains("png")?"png":mime!=null&&mime.contains("webp")?"webp":"jpg";
        File f=new File(dir,"scan-"+System.currentTimeMillis()+"."+ext);
        try(InputStream i=in;OutputStream o=new FileOutputStream(f)){byte[] b=new byte[65536];int n;while((n=i.read(b))>0)o.write(b,0,n);}
        prune();
        return f.getName();
    }

    public void prune(){
        File[] all=dir.listFiles((d,n)->n.startsWith("scan-"));
        if(all==null||all.length<=KEEP)return;
        Arrays.sort(all,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
        for(int i=KEEP;i<all.length;i++)all[i].delete();
    }

    public JSONArray list() throws Exception {
        JSONArray out=new JSONArray();
        File[] all=dir.listFiles((d,n)->n.startsWith("scan-")&&new File(d,n).length()>0);
        if(all==null)return out;
        Arrays.sort(all,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
        for(File f:all)out.put(new JSONObject().put("name",f.getName()).put("time",f.lastModified()));
        return out;
    }

    public void delete(String name){file(name).delete();}

    /**
     * Reads the text in a scan. crop: [x,y,w,h] as fractions of the upright image, or null for all of it.
     * Boxes come back in fractions of the upright image too, so the page can draw them over its <img>.
     */
    public JSONObject read(String name,String lang,JSONArray crop) throws Exception {
        File f=file(name);
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(f.getPath(),bounds);
        if(bounds.outWidth<=0)throw new IllegalArgumentException("Unreadable image");
        // Decode near MAX_SIDE for the part being read, so a cropped text box keeps its detail.
        double part=crop==null?1:Math.max(crop.getDouble(2),crop.getDouble(3));
        int sample=1;
        while(Math.max(bounds.outWidth,bounds.outHeight)*part/(sample*2)>=MAX_SIDE)sample*=2;
        BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=sample;o.inPreferredConfig=Bitmap.Config.ARGB_8888;
        Bitmap bmp=BitmapFactory.decodeFile(f.getPath(),o);
        if(bmp==null)throw new IllegalArgumentException("Unreadable image");
        // Camera photos are stored sideways with an EXIF rotation; the WebView shows them upright, so read them upright.
        int rotate=0;
        try{
            switch(new ExifInterface(f.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)){
                case ExifInterface.ORIENTATION_ROTATE_90:rotate=90;break;
                case ExifInterface.ORIENTATION_ROTATE_180:rotate=180;break;
                case ExifInterface.ORIENTATION_ROTATE_270:rotate=270;break;
            }
        }catch(Exception ignored){}
        if(rotate!=0){Matrix m=new Matrix();m.postRotate(rotate);Bitmap r=Bitmap.createBitmap(bmp,0,0,bmp.getWidth(),bmp.getHeight(),m,true);bmp.recycle();bmp=r;}
        int W=bmp.getWidth(),H=bmp.getHeight();
        int cx=0,cy=0,cw=W,ch=H;
        if(crop!=null){
            cx=clamp((int)Math.round(crop.getDouble(0)*W),0,W-1);cy=clamp((int)Math.round(crop.getDouble(1)*H),0,H-1);
            cw=clamp((int)Math.round(crop.getDouble(2)*W),1,W-cx);ch=clamp((int)Math.round(crop.getDouble(3)*H),1,H-cy);
            Bitmap c=Bitmap.createBitmap(bmp,cx,cy,cw,ch);if(c!=bmp){bmp.recycle();bmp=c;}
        }
        if(Math.max(cw,ch)>MAX_SIDE){
            float s=MAX_SIDE/(float)Math.max(cw,ch);
            Bitmap c=Bitmap.createScaledBitmap(bmp,Math.round(cw*s),Math.round(ch*s),true);bmp.recycle();bmp=c;
        }
        float sx=cw/(float)bmp.getWidth(),sy=ch/(float)bmp.getHeight();
        long t=System.currentTimeMillis();
        JSONObject r=ocr.recognize(bmp,lang);
        // Back to fractions of the whole upright image.
        JSONArray blocks=r.getJSONArray("blocks");
        for(int i=0;i<blocks.length();i++){
            JSONObject b=blocks.getJSONObject(i);
            b.put("x",(cx+b.getDouble("x")*sx)/W).put("y",(cy+b.getDouble("y")*sy)/H).put("w",b.getDouble("w")*sx/W).put("h",b.getDouble("h")*sy/H);
            // Games put the speaker's name above the dialogue (ミオ / ねえ、見て！…): keep it on its own line.
            JSONArray ls=b.optJSONArray("lines");
            if(ls!=null&&ls.length()>1){
                String first=ls.getJSONObject(0).getString("text"),second=ls.getJSONObject(1).getString("text");
                boolean horizontal=ls.getJSONObject(0).getDouble("w")>ls.getJSONObject(0).getDouble("h");
                if(horizontal&&first.length()<=12&&first.length()*2<=second.length()&&b.getString("text").startsWith(first))
                    b.put("text",first+"\n"+b.getString("text").substring(first.length()).trim());
            }
            b.remove("lines");
        }
        return new JSONObject().put("blocks",blocks).put("ms",System.currentTimeMillis()-t);
    }

    static int clamp(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}
}
