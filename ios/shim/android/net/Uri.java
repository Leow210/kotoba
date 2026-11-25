package android.net;

/** Desktop stand-in for Uri: Kotoba on the Mac only uses file:// and plain paths. */
public final class Uri {
    final String s;
    private Uri(String s){this.s=s;}
    public static Uri parse(String s){return new Uri(s);}
    public static String decode(String s){try{return java.net.URLDecoder.decode(s.replace("+","%2B"),"UTF-8");}catch(Exception e){return s;}}
    public static String encode(String s){try{return java.net.URLEncoder.encode(s,"UTF-8").replace("+","%20");}catch(Exception e){return s;}}
    public String getPath(){return s.startsWith("file://")?s.substring(7):s;}
    public String getScheme(){int i=s.indexOf(':');return i<0?null:s.substring(0,i);}
    public String getLastPathSegment(){String p=getPath();int i=p.lastIndexOf('/');return i<0?p:p.substring(i+1);}
    @Override public String toString(){return s;}
    @Override public boolean equals(Object o){return o instanceof Uri&&((Uri)o).s.equals(s);}
    @Override public int hashCode(){return s.hashCode();}
}
