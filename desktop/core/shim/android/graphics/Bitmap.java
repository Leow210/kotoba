package android.graphics;

import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Desktop stand-in for Bitmap over BufferedImage (covers, thumbnails and OCR input). */
public final class Bitmap {
    public enum Config { ARGB_8888, RGB_565 }
    public enum CompressFormat { JPEG, PNG, WEBP }
    final BufferedImage img;
    Bitmap(BufferedImage img){this.img=img;}
    public BufferedImage image(){return img;}
    public int getWidth(){return img.getWidth();}
    public int getHeight(){return img.getHeight();}
    public void recycle(){}
    public boolean isRecycled(){return false;}
    public int getPixel(int x,int y){return img.getRGB(x,y);}
    public void getPixels(int[] pixels,int offset,int stride,int x,int y,int w,int h){img.getRGB(x,y,w,h,pixels,offset,stride);}
    public void setPixels(int[] pixels,int offset,int stride,int x,int y,int w,int h){img.setRGB(x,y,w,h,pixels,offset,stride);}
    public void eraseColor(int c){for(int y=0;y<img.getHeight();y++)for(int x=0;x<img.getWidth();x++)img.setRGB(x,y,c);}

    public static Bitmap createBitmap(int w,int h,Config config){return new Bitmap(new BufferedImage(Math.max(1,w),Math.max(1,h),BufferedImage.TYPE_INT_ARGB));}
    public static Bitmap createBitmap(Bitmap src,int x,int y,int w,int h){
        BufferedImage out=new BufferedImage(Math.max(1,w),Math.max(1,h),BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g=out.createGraphics();g.drawImage(src.img,-x,-y,null);g.dispose();
        return new Bitmap(out);
    }
    /** With a Matrix: only the rotations and scales Kotoba uses. */
    public static Bitmap createBitmap(Bitmap src,int x,int y,int w,int h,Matrix m,boolean filter){
        Bitmap part=createBitmap(src,x,y,w,h);
        if(m==null||m.t.isIdentity())return part;
        java.awt.geom.Rectangle2D r=m.t.createTransformedShape(new java.awt.Rectangle(0,0,w,h)).getBounds2D();
        BufferedImage out=new BufferedImage(Math.max(1,(int)Math.round(r.getWidth())),Math.max(1,(int)Math.round(r.getHeight())),BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g=out.createGraphics();
        if(filter)g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        AffineTransform t=new AffineTransform();t.translate(-r.getX(),-r.getY());t.concatenate(m.t);
        g.drawImage(part.img,t,null);g.dispose();
        return new Bitmap(out);
    }
    public static Bitmap createScaledBitmap(Bitmap src,int w,int h,boolean filter){
        BufferedImage out=new BufferedImage(Math.max(1,w),Math.max(1,h),BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g=out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,filter?RenderingHints.VALUE_INTERPOLATION_BILINEAR:RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src.img,0,0,w,h,null);g.dispose();
        return new Bitmap(out);
    }

    public boolean compress(CompressFormat format,int quality,OutputStream out){
        try{
            BufferedImage rgb=new BufferedImage(img.getWidth(),img.getHeight(),BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g=rgb.createGraphics();g.setColor(java.awt.Color.WHITE);g.fillRect(0,0,rgb.getWidth(),rgb.getHeight());g.drawImage(img,0,0,null);g.dispose();
            if(format==CompressFormat.PNG)return ImageIO.write(rgb,"png",out);
            Iterator<ImageWriter> it=ImageIO.getImageWritersByFormatName("jpeg");
            ImageWriter w=it.next();ImageWriteParam p=w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);p.setCompressionQuality(quality/100f);
            try(MemoryCacheImageOutputStream o=new MemoryCacheImageOutputStream(out)){w.setOutput(o);w.write(null,new IIOImage(rgb,null,null),p);}
            w.dispose();
            return true;
        }catch(Exception e){return false;}
    }
}
