package android.graphics;

/** iOS stand-in: the Swift shell installs {@link #decoder} (ImageIO) to turn bytes into pixels. */
public final class BitmapFactory {
    public static class Options {
        public boolean inJustDecodeBounds;public int inSampleSize=1;public int outWidth,outHeight;public Bitmap.Config inPreferredConfig;public String outMimeType;
    }

    /** Pixels and size of an image, decoded no larger than {@code sample}-fold down; null when unreadable. */
    public interface Decoder{
        /** @return {width, height, argb...} or null. With boundsOnly, only {width, height}. */
        int[] decode(byte[] data,int sample,boolean boundsOnly);
    }
    public static Decoder decoder;

    public static Bitmap decodeByteArray(byte[] data,int offset,int length){return decodeByteArray(data,offset,length,null);}
    public static Bitmap decodeByteArray(byte[] data,int offset,int length,Options o){
        if(decoder==null)return null;
        byte[] part=offset==0&&length==data.length?data:java.util.Arrays.copyOfRange(data,offset,offset+length);
        boolean bounds=o!=null&&o.inJustDecodeBounds;
        int[] r=decoder.decode(part,o==null?1:Math.max(1,o.inSampleSize),bounds);
        if(r==null)return null;
        if(o!=null){o.outWidth=r[0];o.outHeight=r[1];}
        if(bounds)return null;
        int[] px=new int[r[0]*r[1]];
        System.arraycopy(r,2,px,0,px.length);
        return new Bitmap(r[0],r[1],px);
    }
    public static Bitmap decodeFile(String path){return decodeFile(path,null);}
    public static Bitmap decodeFile(String path,Options o){
        try{byte[] b=java.nio.file.Files.readAllBytes(new java.io.File(path).toPath());return decodeByteArray(b,0,b.length,o);}
        catch(java.io.IOException e){return null;}
    }
}
