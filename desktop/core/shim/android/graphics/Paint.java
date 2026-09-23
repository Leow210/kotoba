package android.graphics;

/** Desktop stand-in: only bitmap filtering matters here. */
public class Paint {
    public static final int FILTER_BITMAP_FLAG=2,ANTI_ALIAS_FLAG=1;
    final boolean filter;
    public Paint(){this(0);}
    public Paint(int flags){filter=(flags&FILTER_BITMAP_FLAG)!=0;}
}
