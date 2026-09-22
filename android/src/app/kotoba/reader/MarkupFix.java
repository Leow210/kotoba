package app.kotoba.reader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes Monokakido-style dictionary markup renderable in a browser:
 * - HTML can't have non-ASCII tag names (<用例>, <語義番号>), so they become <span data-name="…">;
 * - their CSS type selectors are rewritten to match;
 * - "@color name = #hex;" definitions (a Monokakido extension) are substituted into the rules.
 * Pure Java so it can be tested on a desktop JVM.
 */
public final class MarkupFix {
    static final Pattern OPEN=Pattern.compile("<([^\\x00-\\x7F\\s/>!?][^\\s/>]*)");
    static final Pattern CLOSE=Pattern.compile("</([^\\x00-\\x7F\\s/>][^\\s/>]*)\\s*>");

    static final Pattern AUDIO_OPEN=Pattern.compile("<audio(\\s*>|\\s+(?![^>]*\\bsrc=)[^>]*>)",Pattern.CASE_INSENSITIVE);
    public static String html(String html){
        // <audio> without src is used as a wrapper around 🔊 links; browsers never render its children.
        if(html.contains("<audio")&&!Pattern.compile("(?i)<audio[^>]*\\bsrc=").matcher(html).find()){
            html=AUDIO_OPEN.matcher(html).replaceAll("<span data-name=\"audio\">");
            html=html.replaceAll("(?i)</audio>","</span>");
        }
        if(!hasNonAscii(html))return html;
        String s=CLOSE.matcher(html).replaceAll("</span>");
        Matcher m=OPEN.matcher(s);
        StringBuffer out=new StringBuffer();
        while(m.find())m.appendReplacement(out,Matcher.quoteReplacement("<span data-name=\""+m.group(1)+"\""));
        m.appendTail(out);
        return out.toString();
    }

    static boolean hasNonAscii(String s){
        for(int i=0;i<s.length();i++){
            if(s.charAt(i)=='<'&&i+1<s.length()){
                char c=s.charAt(i+1)=='/'&&i+2<s.length()?s.charAt(i+2):s.charAt(i+1);
                if(c>0x7f)return true;
            }
        }
        return false;
    }

    static final Pattern COLOR_DEF=Pattern.compile("@color\\s+([\\w-]+)\\s*=\\s*([^;]+);");
    static final Pattern TYPE_SELECTOR=Pattern.compile("(^|[\\s>+~,(])([^\\x00-\\x7F][^\\s>+~,.#:\\[\\]{}()]*)");

    public static String css(String css){
        Map<String,String> colors=new LinkedHashMap<>();
        Matcher d=COLOR_DEF.matcher(css);
        while(d.find())colors.put(d.group(1),d.group(2).trim());
        String s=COLOR_DEF.matcher(css).replaceAll("");
        StringBuilder out=new StringBuilder(s.length()+256);
        int i=0;
        while(i<s.length()){
            int open=s.indexOf('{',i);
            if(open<0){out.append(s.substring(i));break;}
            String selector=s.substring(i,open);
            int close=s.indexOf('}',open);
            if(close<0)close=s.length()-1;
            String body=s.substring(open,close+1);
            if(selector.trim().startsWith("@")){
                // @media / @font-face etc.: keep as is (nested rules are rare in these files).
                out.append(selector).append(body);
            }else{
                String sel=TYPE_SELECTOR.matcher(selector).replaceAll("$1[data-name=\"$2\"]");
                sel=sel.replaceAll("(^|[\\s>+~,(])audio(?=[\\s>+~,.#:\\[{]|$)","$1[data-name=\"audio\"]");
                out.append(sel);
                String decl=body;
                for(Map.Entry<String,String> c:colors.entrySet())
                    decl=decl.replaceAll("(?<=[:\\s,])"+Pattern.quote(c.getKey())+"(?=\\s*[;!}\\s])",Matcher.quoteReplacement(c.getValue()));
                out.append(decl);
            }
            i=close+1;
        }
        return out.toString();
    }
}
