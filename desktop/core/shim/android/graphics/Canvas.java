package android.graphics;

import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** Desktop stand-in for Canvas: fill and draw one bitmap into another (OCR's column-to-row layout). */
public class Canvas {
    final Bitmap target;
    public Canvas(Bitmap target){this.target=target;}
    public void drawColor(int argb){
        Graphics2D g=target.img.createGraphics();g.setColor(new java.awt.Color(argb,true));g.fillRect(0,0,target.getWidth(),target.getHeight());g.dispose();
    }
    public void drawBitmap(Bitmap src,Rect from,Rect to,Paint paint){
        Graphics2D g=target.img.createGraphics();
        if(paint==null||paint.filter)g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        Rect f=from==null?new Rect(0,0,src.getWidth(),src.getHeight()):from;
        g.drawImage(src.img,to.left,to.top,to.right,to.bottom,f.left,f.top,f.right,f.bottom,null);
        g.dispose();
    }
}
