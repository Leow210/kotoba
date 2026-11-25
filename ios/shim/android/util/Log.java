package android.util;

/** Desktop stand-in: Android log calls go to standard error. */
public final class Log {
    private Log(){}
    public static int w(String tag,String msg,Throwable t){System.err.println(tag+": "+msg+(t==null?"":" — "+t));return 0;}
    public static int w(String tag,String msg){return w(tag,msg,null);}
    public static int i(String tag,String msg){System.err.println(tag+": "+msg);return 0;}
    public static int d(String tag,String msg){return i(tag,msg);}
}
