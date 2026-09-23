package app.kotoba.reader;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Splits dictionary HTML into definition text and example text for full-text search.
 * Works on the loosely-formed markup that MDict files contain; it never throws.
 * An element's "names" are its tag, data-name and class tokens (Monokakido exports use all three).
 */
public final class HtmlText {
    static final Set<String> SKIP=new HashSet<>(Arrays.asList(
        "script","style","link","head","title","rt","rp","ルビg","ルビ仮名","ルビ","entry-index","m-entry-index",
        "sound","m-audio","audio","mdict-audio"));
    static final Set<String> HEAD=new HashSet<>(Arrays.asList(
        "見出部","yt-head","m-head","headg","headlineg","見出しg","見出g","head-g","熟語見出部","subheadwordg","子見出部","oyajig","subheadword"));
    static final Set<String> EXAMPLE=new HashSet<>(Arrays.asList(
        "用例","用例g","example","m-example-group","m-example-t","m-example-j","m-example-p","言い換え例文g","無礼例文g",
        "accent_example","用例訳","用例訓読g","用例訳g","訳文g"));
    static final Set<String> VOID=new HashSet<>(Arrays.asList(
        "area","base","br","col","embed","hr","img","input","link","meta","param","source","track","wbr"));
    static final Set<String> BLOCK=new HashSet<>(Arrays.asList(
        "div","p","br","li","tr","td","hr","h1","h2","h3","h4","table"));

    public final StringBuilder definitions=new StringBuilder(), examples=new StringBuilder();

    static final class Frame { final String tag; final int kind; Frame(String tag,int kind){this.tag=tag;this.kind=kind;} }
    // Kind bits: 1 = skipped, 2 = example
    public static HtmlText parse(String html){
        HtmlText out=new HtmlText();
        ArrayList<Frame> stack=new ArrayList<>();
        int kind=0,i=0,n=html.length();
        while(i<n){
            char c=html.charAt(i);
            if(c=='<'){
                if(html.startsWith("<!--",i)){int e=html.indexOf("-->",i+4);i=e<0?n:e+3;continue;}
                int e=tagEnd(html,i+1);
                if(e<0){i=n;break;}
                String inner=html.substring(i+1,e);
                i=e+1;
                if(inner.isEmpty()||inner.charAt(0)=='!'||inner.charAt(0)=='?')continue;
                if(inner.charAt(0)=='/'){
                    String tag=tagName(inner.substring(1));
                    for(int k=stack.size()-1;k>=0;k--){
                        if(stack.get(k).tag.equals(tag)){
                            while(stack.size()>k)stack.remove(stack.size()-1);
                            break;
                        }
                    }
                    kind=stack.isEmpty()?0:stack.get(stack.size()-1).kind;
                    if(BLOCK.contains(tag))out.space(kind);
                    continue;
                }
                String tag=tagName(inner);
                if(tag.isEmpty())continue;
                if(BLOCK.contains(tag))out.space(kind);
                boolean selfClosing=inner.endsWith("/")||VOID.contains(tag);
                if(selfClosing)continue;
                int next=kind;
                if((kind&1)==0){
                    for(String name:names(tag,inner)){
                        if(SKIP.contains(name)||HEAD.contains(name)){next|=1;break;}
                        if(EXAMPLE.contains(name))next|=2;
                    }
                    if(inner.contains("id=\"index\"")||inner.contains("id='index'"))next|=1;
                }
                stack.add(new Frame(tag,next));
                kind=next;
                if(stack.size()>400){stack.remove(0);}
            }else{
                int e=html.indexOf('<',i);
                if(e<0)e=n;
                if((kind&1)==0){
                    String text=entities(html.substring(i,e));
                    ((kind&2)!=0?out.examples:out.definitions).append(text);
                }
                i=e;
            }
        }
        return out;
    }

    void space(int kind){
        if((kind&1)!=0)return;
        StringBuilder b=(kind&2)!=0?examples:definitions;
        if(b.length()>0&&b.charAt(b.length()-1)!=' ')b.append(' ');
    }

    static int tagEnd(String html,int from){
        char quote=0;
        for(int i=from;i<html.length();i++){
            char c=html.charAt(i);
            if(quote!=0){if(c==quote)quote=0;}
            else if(c=='"'||c=='\'')quote=c;
            else if(c=='>')return i;
        }
        return -1;
    }

    static String tagName(String inner){
        int e=0;
        while(e<inner.length()){char c=inner.charAt(e);if(Character.isWhitespace(c)||c=='/'||c=='>')break;e++;}
        return inner.substring(0,e).toLowerCase(Locale.ROOT);
    }

    static ArrayList<String> names(String tag,String inner){
        ArrayList<String> names=new ArrayList<>();
        names.add(tag);
        String dataName=attribute(inner,"data-name");
        if(dataName!=null)names.add(dataName.toLowerCase(Locale.ROOT));
        String cls=attribute(inner,"class");
        if(cls!=null)for(String part:cls.split("\\s+"))if(!part.isEmpty())names.add(part.toLowerCase(Locale.ROOT));
        return names;
    }

    static String attribute(String inner,String name){
        int i=0;
        while(true){
            i=inner.indexOf(name,i);
            if(i<0)return null;
            boolean start=i>0&&Character.isWhitespace(inner.charAt(i-1));
            int j=i+name.length();
            while(j<inner.length()&&inner.charAt(j)==' ')j++;
            if(start&&j<inner.length()&&inner.charAt(j)=='='){
                j++;
                while(j<inner.length()&&inner.charAt(j)==' ')j++;
                if(j>=inner.length())return "";
                char q=inner.charAt(j);
                if(q=='"'||q=='\''){int e=inner.indexOf(q,j+1);return e<0?inner.substring(j+1):inner.substring(j+1,e);}
                int e=j;while(e<inner.length()&&!Character.isWhitespace(inner.charAt(e)))e++;
                return inner.substring(j,e);
            }
            i=j;
        }
    }

    static String entities(String s){
        if(s.indexOf('&')<0)return s;
        StringBuilder b=new StringBuilder(s.length());
        for(int i=0;i<s.length();i++){
            char c=s.charAt(i);
            if(c=='&'){
                int e=s.indexOf(';',i);
                if(e>i&&e-i<12){
                    String name=s.substring(i+1,e);String value=null;
                    switch(name){case "amp":value="&";break;case "lt":value="<";break;case "gt":value=">";break;case "quot":value="\"";break;case "apos":value="'";break;case "nbsp":value=" ";break;default:
                        try{
                            if(name.startsWith("#x")||name.startsWith("#X"))value=new String(Character.toChars(Integer.parseInt(name.substring(2),16)));
                            else if(name.startsWith("#"))value=new String(Character.toChars(Integer.parseInt(name.substring(1))));
                        }catch(RuntimeException ignored){}
                    }
                    if(value!=null){b.append(value);i=e;continue;}
                }
            }
            b.append(c);
        }
        return b.toString();
    }

    /** Search normalization shared by keys and full text: NFKC, lower case, katakana→hiragana, no whitespace. */
    public static String normalize(String value){
        String s=Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        // Korean dictionaries hyphenate compounds (떠-올리다); the hyphen isn't part of the word.
        boolean hangul=false;
        for(int i=0;i<s.length()&&!hangul;i++){char c=s.charAt(i);hangul=c>=0xac00&&c<=0xd7a3;}
        // Keep a leading/trailing hyphen: it marks endings and prefixes (-다며, -시-).
        if(hangul)s=s.replaceAll("(?<=\\S)[-‐](?=\\S)","");
        StringBuilder result=new StringBuilder(s.length());
        for(int i=0;i<s.length();){
            int c=s.codePointAt(i);i+=Character.charCount(c);
            if(Character.isWhitespace(c)||Character.isSpaceChar(c)||c==0x200b)continue;
            // Daijirin/SMK mark non-jōyō characters with ▽/▼; they are not part of the word.
            if(c==0x25bd||c==0x25bc||c==0x25b3||c==0x25b2)continue;
            // Russian stress marks and ё are ignored so чита́ть matches читать.
            if(c==0x0301||c==0x0300)continue;
            if(c==0x0451||c==0x0450)c=0x0435;
            if(c==0x045d)c=0x0438;
            result.appendCodePoint(c>=0x30a1&&c<=0x30f6?c-96:c);
        }
        return result.toString();
    }

    /** One FTS token per code point, so phrase queries become substring queries. */
    public static String tokens(String normalized,int limit){
        StringBuilder b=new StringBuilder(Math.min(normalized.length(),limit)*2);
        int count=0;
        for(int i=0;i<normalized.length()&&count<limit;){
            int c=normalized.codePointAt(i);i+=Character.charCount(c);
            if(c<128&&!Character.isLetterOrDigit(c))continue;
            if(b.length()>0)b.append(' ');
            b.appendCodePoint(c);count++;
        }
        return b.toString();
    }
}
