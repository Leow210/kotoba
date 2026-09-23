package android.graphics;

import java.awt.geom.AffineTransform;

/** Desktop stand-in for Matrix: rotation and scale. */
public class Matrix {
    final AffineTransform t=new AffineTransform();
    public boolean postRotate(float degrees){AffineTransform r=AffineTransform.getRotateInstance(Math.toRadians(degrees));t.preConcatenate(r);return true;}
    public boolean postScale(float sx,float sy){t.preConcatenate(AffineTransform.getScaleInstance(sx,sy));return true;}
    public boolean preRotate(float degrees){t.rotate(Math.toRadians(degrees));return true;}
    public void reset(){t.setToIdentity();}
}
