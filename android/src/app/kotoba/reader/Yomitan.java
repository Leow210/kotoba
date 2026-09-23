package app.kotoba.reader;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Yomitan dictionaries (ZIP of index.json, term/kanji/meta/tag banks, styles.css and media).
 * This class is the format itself: a streaming JSON reader (banks run to 75 MB each) and the HTML
 * rendering of entries, matching how Yomitan draws structured content so each dictionary's styles.css applies.
 * Pure Java, so it runs in the desktop tests; Library does the storage.
 */
public final class Yomitan {
    private Yomitan(){}

    public static final Object NULL=new Object(){public String toString(){return "null";}};

    // ---------- JSON ----------

    /** Minimal JSON reader: objects become LinkedHashMap, arrays ArrayList, numbers Double, null NULL. */
    public static final class Json {
        final Reader in;final char[] buf=new char[1<<16];int pos,len;
        public Json(Reader in){this.in=in;}
        int peek() throws IOException {
            if(pos==len){len=in.read(buf,0,buf.length);pos=0;if(len<=0){len=0;return -1;}}
            return buf[pos];
        }
        int next() throws IOException {int c=peek();if(c>=0)pos++;return c;}
        int skipSpace() throws IOException {int c;while((c=peek())==' '||c=='\n'||c=='\r'||c=='\t'||c=='\uFEFF')next();return c;}
        void expect(char want) throws IOException {int c=skipSpace();if(c!=want)throw new IOException("Bad JSON: expected '"+want+"' but found "+(c<0?"end of file":"'"+(char)c+"'"));next();}

        /** Streams the elements of a top-level array, one parsed value at a time. */
        public void eachInArray(Consumer<Object> each) throws IOException {
            expect('[');
            if(skipSpace()==']'){next();return;}
            while(true){
                each.accept(value());
                int c=skipSpace();next();
                if(c==']')return;
                if(c!=',')throw new IOException("Bad JSON array");
            }
        }

        public Object value() throws IOException {
            int c=skipSpace();
            switch(c){
                case '{':{
                    next();Map<String,Object> m=new LinkedHashMap<>();
                    if(skipSpace()=='}'){next();return m;}
                    while(true){
                        if(skipSpace()!='"')throw new IOException("Bad JSON object key");
                        String k=string();expect(':');m.put(k,value());
                        int d=skipSpace();next();
                        if(d=='}')return m;
                        if(d!=',')throw new IOException("Bad JSON object");
                    }
                }
                case '[':{
                    next();List<Object> a=new ArrayList<>();
                    if(skipSpace()==']'){next();return a;}
                    while(true){
                        a.add(value());
                        int d=skipSpace();next();
                        if(d==']')return a;
                        if(d!=',')throw new IOException("Bad JSON array");
                    }
                }
                case '"':return string();
                case 't':word("true");return Boolean.TRUE;
                case 'f':word("false");return Boolean.FALSE;
                case 'n':word("null");return NULL;
                case -1:throw new IOException("Unexpected end of JSON");
                default:{
                    StringBuilder b=new StringBuilder();
                    while((c=peek())=='-'||c=='+'||c=='.'||c=='e'||c=='E'||(c>='0'&&c<='9'))b.append((char)next());
                    if(b.length()==0)throw new IOException("Bad JSON value '"+(char)c+"'");
                    try{return Double.parseDouble(b.toString());}catch(NumberFormatException e){throw new IOException("Bad JSON number "+b);}
                }
            }
        }
        void word(String w) throws IOException {for(int i=0;i<w.length();i++)if(next()!=w.charAt(i))throw new IOException("Bad JSON literal");}
        String string() throws IOException {
            next();// opening quote
            StringBuilder b=new StringBuilder();
            while(true){
                int c=next();
                if(c<0)throw new IOException("Unterminated JSON string");
                if(c=='"')return b.toString();
                if(c!='\\'){b.append((char)c);continue;}
                int e=next();
                switch(e){
                    case 'n':b.append('\n');break;case 't':b.append('\t');break;case 'r':b.append('\r');break;
                    case 'b':b.append('\b');break;case 'f':b.append('\f');break;
                    case 'u':{int v=0;for(int i=0;i<4;i++){int h=Character.digit(next(),16);if(h<0)throw new IOException("Bad \\u escape");v=v*16+h;}b.append((char)v);break;}
                    default:b.append((char)e);
                }
            }
        }
    }

    public static Object parse(String json) throws IOException {return new Json(new java.io.StringReader(json)).value();}

    // ---------- small accessors ----------

    static String str(Object o){return o==null||o==NULL?"":o instanceof Double?num((Double)o):String.valueOf(o);}
    static String num(double d){return d==Math.rint(d)&&Math.abs(d)<1e15?Long.toString((long)d):Double.toString(d);}
    @SuppressWarnings("unchecked") static List<Object> list(Object o){return o instanceof List?(List<Object>)o:new ArrayList<>();}
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object o){return o instanceof Map?(Map<String,Object>)o:null;}
    static Object at(List<Object> l,int i){return i<l.size()?l.get(i):null;}

    // ---------- index.json ----------

    public static final class Index {
        public String title="",revision="",author="",description="",attribution="",sourceLanguage="",targetLanguage="";
        public int format=3;
    }
    public static Index index(String json) throws IOException {
        Map<String,Object> m=map(parse(json));
        if(m==null)throw new IOException("index.json is not an object");
        Index x=new Index();
        x.title=str(m.get("title")).trim();x.revision=str(m.get("revision"));x.author=str(m.get("author"));
        x.description=str(m.get("description"));x.attribution=str(m.get("attribution"));
        x.sourceLanguage=str(m.get("sourceLanguage"));x.targetLanguage=str(m.get("targetLanguage"));
        Object f=m.containsKey("format")?m.get("format"):m.get("version");
        if(f instanceof Double)x.format=((Double)f).intValue();
        if(x.title.isEmpty())throw new IOException("index.json has no title");
        return x;
    }

    // ---------- tags ----------

    public static final class Tag {
        public final String name,category,notes;public final double order;
        public Tag(String name,String category,double order,String notes){this.name=name;this.category=category;this.order=order;this.notes=notes;}
    }
    /** tag_bank row: [name, category, order, notes, score]. */
    public static Tag tag(Object row){
        List<Object> r=list(row);
        Object o=at(r,2);
        return new Tag(str(at(r,0)),str(at(r,1)),o instanceof Double?(Double)o:0,str(at(r,3)));
    }

    // ---------- terms ----------

    /** term_bank row (format 3): [term, reading, definitionTags, rules, score, glossary, sequence, termTags]. Format 1 lacks the last two. */
    public static final class Term {
        public String term,reading,defTags,rules,termTags;public double score;public long sequence;public List<Object> glossary;
    }
    public static Term term(Object row){
        List<Object> r=list(row);
        Term t=new Term();
        t.term=str(at(r,0)).trim();t.reading=str(at(r,1)).trim();
        t.defTags=str(at(r,2));t.rules=str(at(r,3));
        Object s=at(r,4);t.score=s instanceof Double?(Double)s:0;
        Object g=at(r,5);
        if(g instanceof List)t.glossary=list(g);
        else{t.glossary=new ArrayList<>();for(int i=5;i<r.size();i++)t.glossary.add(r.get(i));}// format 1: glossary items follow inline
        Object q=at(r,6);t.sequence=q instanceof Double?((Double)q).longValue():0;
        t.termTags=str(at(r,7));
        if(t.reading.equals(t.term))t.reading="";
        return t;
    }

    /** One definition block (one term_bank row) as HTML: its tags, then its glossary as a list when there are several items. */
    public static String senseHtml(Term t,Map<String,Tag> tags){
        StringBuilder b=new StringBuilder("<div class=\"yt-sense\">");
        tagsHtml(t.defTags,tags,b);
        List<Object> items=new ArrayList<>();
        for(Object g:t.glossary)if(!(g instanceof List))items.add(g);// [uninflected, [rules]] deinflection items are not definitions
        if(items.size()==1){b.append("<div class=\"yt-gloss\">");gloss(items.get(0),b);b.append("</div>");}
        else if(items.size()>=4&&items.stream().allMatch(g->g instanceof String&&((String)g).length()<=16&&((String)g).indexOf('\n')<0)){
            // Many short glosses (JMnedict's readings for a surname, JMdict-style synonyms) read better on one line.
            b.append("<div class=\"yt-gloss yt-inline\">");
            for(int i=0;i<items.size();i++){if(i>0)b.append("<span class=\"yt-sep\"> · </span>");b.append(text((String)items.get(i)));}
            b.append("</div>");
        }
        else if(items.size()>1){
            b.append("<ol class=\"yt-gloss\">");
            for(Object g:items){b.append("<li>");gloss(g,b);b.append("</li>");}
            b.append("</ol>");
        }
        return b.append("</div>").toString();
    }

    static void tagsHtml(String names,Map<String,Tag> tags,StringBuilder b){
        if(names==null||names.trim().isEmpty())return;
        b.append("<span class=\"yt-tags\">");
        for(String n:names.trim().split("\\s+")){
            Tag tag=tags==null?null:tags.get(n);
            String cat=tag==null?"":tag.category;
            b.append("<span class=\"yt-tag\"");
            if(!cat.isEmpty())b.append(" data-category=\"").append(attr(cat)).append('"');
            if(tag!=null&&!tag.notes.isEmpty())b.append(" title=\"").append(attr(tag.notes)).append('"');
            b.append('>').append(text(n)).append("</span>");
        }
        b.append("</span>");
    }

    /** Term tags (on the headword) as HTML chips. */
    public static String termTagsHtml(String names,Map<String,Tag> tags){StringBuilder b=new StringBuilder();tagsHtml(names,tags,b);return b.toString();}

    /** A glossary item: plain text, {type:text}, {type:image}, or {type:structured-content}. */
    static void gloss(Object g,StringBuilder b){
        if(g instanceof String){plain((String)g,b);return;}
        Map<String,Object> m=map(g);
        if(m==null){plain(str(g),b);return;}
        String type=str(m.get("type"));
        if(type.equals("text"))plain(str(m.get("text")),b);
        else if(type.equals("image"))image(m,b);
        else if(type.equals("structured-content"))node(m.get("content"),b,"");
        else plain(str(m.get("text")),b);
    }

    /** Plain text keeps its line breaks (many converted dictionaries are one text block with \n). */
    static void plain(String s,StringBuilder b){
        String t=s.replaceAll("\\n{3,}","\n\n").trim();
        b.append("<span class=\"yt-text\">").append(text(t).replace("\n","<br>")).append("</span>");
    }

    static final java.util.Set<String> TAGS=new java.util.HashSet<>(java.util.Arrays.asList(
        "br","ruby","rt","rp","table","thead","tbody","tfoot","tr","td","th","span","div","ol","ul","li","details","summary","img","a"));

    /** Structured content, rendered like Yomitan's structured-content-generator: gloss-sc-<tag> classes and data-sc-<key> attributes. */
    static void node(Object n,StringBuilder b,String parentLang){
        if(n==null||n==NULL)return;
        if(n instanceof String){b.append(text((String)n).replace("\n","<br>"));return;}
        if(n instanceof List){for(Object c:(List<?>)n)node(c,b,parentLang);return;}
        Map<String,Object> m=map(n);
        if(m==null){b.append(text(str(n)));return;}
        String tag=str(m.get("tag")).toLowerCase(Locale.ROOT);
        if(!TAGS.contains(tag)){node(m.get("content"),b,parentLang);return;}
        if(tag.equals("br")){b.append("<br>");return;}
        if(tag.equals("img")){image(m,b);return;}
        // Wide tables scroll sideways inside their own box instead of widening the page.
        if(tag.equals("table"))b.append("<div class=\"gloss-sc-table-container\">");
        b.append('<').append(tag);
        // span/div are most of the markup and nothing styles them by class; the rest keep Yomitan's gloss-sc-* class.
        if(!tag.equals("span")&&!tag.equals("div"))b.append(" class=\"gloss-sc-").append(tag).append('"');
        Map<String,Object> data=map(m.get("data"));
        if(data!=null)for(Map.Entry<String,Object> e:data.entrySet()){
            String k=e.getKey();if(k.isEmpty())continue;
            b.append(" data-sc-").append(attrName(k)).append("=\"").append(attr(str(e.getValue()))).append('"');
            // Converters of Monokakido-style dictionaries name their parts (見出部, 語義…); Kotoba's entry logic reads data-name.
            if(k.equals("name"))b.append(" data-name=\"").append(attr(str(e.getValue()))).append('"');
        }
        String style=style(map(m.get("style")));
        if(tag.equals("td")||tag.equals("th")){
            Object cs=m.get("colSpan"),rs=m.get("rowSpan");
            if(cs instanceof Double)b.append(" colspan=\"").append(((Double)cs).intValue()).append('"');
            if(rs instanceof Double)b.append(" rowspan=\"").append(((Double)rs).intValue()).append('"');
        }
        if(!style.isEmpty())b.append(" style=\"").append(attr(style)).append('"');
        // lang is inherited in HTML, so it is only written where it changes.
        String lang=str(m.get("lang"));
        if(!lang.isEmpty()&&!lang.equals(parentLang))b.append(" lang=\"").append(attr(lang)).append('"');
        if(lang.isEmpty())lang=parentLang;
        String title=str(m.get("title"));if(!title.isEmpty())b.append(" title=\"").append(attr(title)).append('"');
        if(tag.equals("details")&&Boolean.TRUE.equals(m.get("open")))b.append(" open");
        if(tag.equals("a")){
            String href=str(m.get("href"));
            b.append(" href=\"").append(attr(link(href))).append('"');
            if(href.matches("(?i)https?:.*"))b.append(" data-external=\"1\"");
        }
        b.append('>');
        node(m.get("content"),b,lang);
        b.append("</").append(tag).append('>');
        if(tag.equals("table"))b.append("</div>");
    }

    /** Yomitan's internal links (?query=言葉&wildcards=off) become entry:// links, which Kotoba resolves like MDX cross-references. */
    static String link(String href){
        if(href.startsWith("?")||href.startsWith("/search?")){
            for(String part:href.substring(href.indexOf('?')+1).split("&")){
                if(part.startsWith("query=")){
                    String q=part.substring(6);
                    try{q=java.net.URLDecoder.decode(q,"UTF-8");}catch(Exception ignored){}
                    return "entry://"+q;
                }
            }
        }
        return href;
    }

    /** {tag:img} and {type:image}: sized in em like Yomitan, served from the ZIP. */
    static void image(Map<String,Object> m,StringBuilder b){
        String path=str(m.get("path"));
        if(path.isEmpty())return;
        String units=str(m.get("sizeUnits"));if(units.isEmpty())units="px";
        Object w=m.get("width"),h=m.get("height"),pw=m.get("preferredWidth"),ph=m.get("preferredHeight");
        if(pw instanceof Double)w=pw;if(ph instanceof Double)h=ph;
        StringBuilder st=new StringBuilder();
        boolean hasW=w instanceof Double,hasH=h instanceof Double;
        if(hasW)st.append("width:").append(num((Double)w)).append(units).append(';');
        if(hasW&&hasH)st.append("height:auto;aspect-ratio:").append(num((Double)w)).append('/').append(num((Double)h)).append(';');
        else if(hasH)st.append("height:").append(num((Double)h)).append(units).append(';');
        String va=str(m.get("verticalAlign"));if(!va.isEmpty())st.append("vertical-align:").append(va).append(';');
        if("pixelated".equals(str(m.get("imageRendering")))||Boolean.TRUE.equals(m.get("pixelated")))st.append("image-rendering:pixelated;");
        String border=str(m.get("border"));if(!border.isEmpty())st.append("border:").append(border).append(';');
        String radius=str(m.get("borderRadius"));if(!radius.isEmpty())st.append("border-radius:").append(radius).append(';');
        String alt=str(m.get("alt")),title=str(m.get("title")),desc=str(m.get("description"));
        boolean block=!"em".equals(units)||(h instanceof Double&&(Double)h>2);
        b.append("<span class=\"gloss-image").append(block?" yt-image-block":"").append("\">");
        b.append("<img loading=\"lazy\" src=\"").append(attr(src(path))).append("\" alt=\"").append(attr(alt.isEmpty()?desc:alt)).append('"');
        if(!title.isEmpty())b.append(" title=\"").append(attr(title)).append('"');
        if(st.length()>0)b.append(" style=\"").append(attr(st.toString())).append('"');
        b.append('>');
        if(!desc.isEmpty()&&block)b.append("<span class=\"gloss-image-description\">").append(text(desc)).append("</span>");
        b.append("</span>");
    }

    /** Image path relative to the entry page (/d/<dict>/<rec>.entry), so /d/<dict>/<path> serves it from the ZIP. */
    static String src(String path){
        StringBuilder b=new StringBuilder();
        for(String part:path.replace('\\','/').replaceAll("^/+","").split("/")){
            if(b.length()>0)b.append('/');
            try{b.append(java.net.URLEncoder.encode(part,"UTF-8").replace("+","%20"));}catch(Exception e){b.append(part);}
        }
        return b.toString();
    }

    /** Structured-content style object → CSS (camelCase → kebab-case; numbers stay as given, as Yomitan does for these keys). */
    static String style(Map<String,Object> s){
        if(s==null)return "";
        StringBuilder b=new StringBuilder();
        for(Map.Entry<String,Object> e:s.entrySet()){
            String v=str(e.getValue()).trim();
            if(v.isEmpty()||v.contains(";")||v.contains("\"")||v.toLowerCase(Locale.ROOT).contains("url("))continue;
            String k=e.getKey();
            // textEmphasis etc. keep their names; margins given as bare numbers are em in Yomitan.
            if(e.getValue() instanceof Double&&k.matches("(?i)(margin|padding).*"))v=v+"em";
            b.append(kebab(k)).append(':').append(v).append(';');
        }
        return b.toString();
    }
    static String kebab(String k){
        StringBuilder b=new StringBuilder();
        for(char c:k.toCharArray()){if(Character.isUpperCase(c))b.append('-').append(Character.toLowerCase(c));else if(Character.isLetterOrDigit(c)||c=='-')b.append(c);}
        return b.toString();
    }
    /** data key → attribute suffix, as the DOM's dataset does (scFooBar → data-sc-foo-bar), keeping non-ASCII keys (データ). */
    static String attrName(String k){
        StringBuilder b=new StringBuilder();
        for(int i=0;i<k.length();i++){
            char c=k.charAt(i);
            if(c>='A'&&c<='Z'){if(i>0)b.append('-');b.append((char)(c+32));}
            else if(c=='"'||c=='\''||c=='>'||c=='<'||c=='='||c=='/'||Character.isWhitespace(c))b.append('_');
            else b.append(c);
        }
        return b.toString();
    }

    public static String text(String s){
        StringBuilder b=new StringBuilder(s.length()+16);
        for(int i=0;i<s.length();i++){
            char c=s.charAt(i);
            switch(c){case '&':b.append("&amp;");break;case '<':b.append("&lt;");break;case '>':b.append("&gt;");break;default:b.append(c);}
        }
        return b.toString();
    }
    public static String attr(String s){return text(s).replace("\"","&quot;");}

    // ---------- whole entries ----------

    /**
     * The entry page body: a heading (word, reading, term tags) and the stored definition blocks.
     * Dictionaries that print their own heading (あ【亜】, め–つぎ【芽接ぎ】) keep it; ours is then hidden but stays
     * in the page, so focusing and card-making find the word and reading the same way for every dictionary.
     */
    public static String entryHtml(String term,String reading,String termTags,String senses){
        boolean dup=printsOwnHeading(senses,term,reading);
        StringBuilder b=new StringBuilder("<div class=\"yt-entry\">");
        b.append("<div class=\"yt-head").append(dup?" yt-head-dup":"").append("\">");
        b.append("<span class=\"yt-word\">").append(text(term)).append("</span>");
        if(!reading.isEmpty())b.append("<span class=\"yt-reading\">").append(text(reading)).append("</span>");
        b.append(termTags);
        b.append("</div>").append(senses).append("</div>");
        return b.toString();
    }

    static boolean printsOwnHeading(String html,String term,String reading){
        String t=html.replaceAll("(?s)<rt\\b.*?</rt>","").replaceAll("<[^>]+>","").replace("&amp;","&");
        t=t.replaceAll("[\\s\u3000‐\\-–—・･=＝▽▼△▲]","");
        String start=t.substring(0,Math.min(60,t.length()));
        String tn=term.replaceAll("[\\s‐\\-–—・･]","");
        String rn=reading.replaceAll("[\\s‐\\-–—・･]","");
        return (!tn.isEmpty()&&start.contains(tn))||(!rn.isEmpty()&&start.startsWith(rn));
    }

    // ---------- kanji ----------

    /** kanji_bank row: [character, onyomi, kunyomi, tags, meanings, stats]; format 1 lists meanings inline. */
    public static final class Kanji {
        public String character,onyomi,kunyomi,tags;public List<String> meanings=new ArrayList<>();public Map<String,String> stats=new LinkedHashMap<>();
    }
    public static Kanji kanji(Object row){
        List<Object> r=list(row);
        Kanji k=new Kanji();
        k.character=str(at(r,0)).trim();k.onyomi=str(at(r,1));k.kunyomi=str(at(r,2));k.tags=str(at(r,3));
        Object m=at(r,4);
        if(m instanceof List)for(Object x:list(m))k.meanings.add(str(x));
        else for(int i=4;i<r.size();i++)if(r.get(i) instanceof String)k.meanings.add(str(r.get(i)));
        Map<String,Object> st=map(at(r,5));
        if(st!=null)for(Map.Entry<String,Object> e:st.entrySet())k.stats.put(e.getKey(),str(e.getValue()));
        return k;
    }
    public static String kanjiHtml(Kanji k,Map<String,Tag> tags){
        StringBuilder b=new StringBuilder("<div class=\"yt-entry yt-kanji\"><div class=\"yt-head\"><span class=\"yt-word yt-kanji-char\">").append(text(k.character)).append("</span>");
        tagsHtml(k.tags,tags,b);
        b.append("</div><table class=\"yt-kanji-table\">");
        if(!k.onyomi.trim().isEmpty())b.append("<tr><th>音</th><td class=\"yt-reading\">").append(text(k.onyomi.trim().replace(" ","・"))).append("</td></tr>");
        if(!k.kunyomi.trim().isEmpty())b.append("<tr><th>訓</th><td>").append(text(k.kunyomi.trim().replace(" ","・"))).append("</td></tr>");
        b.append("</table>");
        if(!k.meanings.isEmpty()){
            b.append("<ol class=\"yt-gloss yt-sense\">");
            for(String m:k.meanings)b.append("<li>").append(text(m)).append("</li>");
            b.append("</ol>");
        }
        if(!k.stats.isEmpty()){
            b.append("<table class=\"yt-kanji-stats\">");
            for(Map.Entry<String,String> e:k.stats.entrySet()){
                Tag t=tags==null?null:tags.get(e.getKey());
                String label=t!=null&&!t.notes.isEmpty()?t.notes:e.getKey();
                b.append("<tr><th>").append(text(label)).append("</th><td>").append(text(e.getValue())).append("</td></tr>");
            }
            b.append("</table>");
        }
        return b.append("</div>").toString();
    }
    /** Stroke count from a kanji entry's stats, when the dictionary gives one. */
    public static int strokes(Kanji k){
        for(Map.Entry<String,String> e:k.stats.entrySet()){
            if(e.getKey().toLowerCase(Locale.ROOT).matches("strokes?|stroke_count|strokecount|画数|総画数")){
                try{return Integer.parseInt(e.getValue().replaceAll("\\D.*$",""));}catch(Exception ignored){}
            }
        }
        return 0;
    }

    // ---------- term meta (frequency, pitch, IPA) ----------

    public static final class Meta {
        public String term,mode,reading="",display="";public double value=Double.NaN;
    }
    /**
     * term_meta_bank row: [term, "freq"|"pitch"|"ipa", data]. Frequency data is a number, a string, {value, displayValue},
     * or any of those under {reading, frequency}. Pitch keeps the downstep positions ("0,2") as its display.
     */
    public static Meta meta(Object row){
        List<Object> r=list(row);
        Meta m=new Meta();
        m.term=str(at(r,0)).trim();m.mode=str(at(r,1));
        Object d=at(r,2);
        Map<String,Object> dm=map(d);
        if(m.mode.equals("freq")){
            if(dm!=null&&dm.containsKey("reading")){m.reading=str(dm.get("reading"));d=dm.get("frequency");dm=map(d);}
            if(dm!=null){d=dm.get("value");m.display=str(dm.get("displayValue"));}
            if(d instanceof Double)m.value=(Double)d;
            else if(d instanceof String){
                String s=((String)d).trim();
                java.util.regex.Matcher x=java.util.regex.Pattern.compile("\\d+(?:\\.\\d+)?").matcher(s);
                if(x.find())m.value=Double.parseDouble(x.group());
                if(m.display.isEmpty())m.display=s;
            }
            if(m.display.isEmpty()&&!Double.isNaN(m.value))m.display=num(m.value);
        }else if(m.mode.equals("pitch")&&dm!=null){
            m.reading=str(dm.get("reading"));
            StringBuilder p=new StringBuilder();
            for(Object o:list(dm.get("pitches"))){
                Map<String,Object> pm=map(o);if(pm==null)continue;
                Object pos=pm.get("position");
                if(p.length()>0)p.append(',');
                p.append(pos instanceof Double?num((Double)pos):str(pos));
            }
            m.display=p.toString();
        }else if(m.mode.equals("ipa")&&dm!=null){
            m.reading=str(dm.get("reading"));
            StringBuilder p=new StringBuilder();
            for(Object o:list(dm.get("transcriptions"))){Map<String,Object> t=map(o);if(t==null)continue;if(p.length()>0)p.append(", ");p.append(str(t.get("ipa")));}
            m.display=p.toString();
        }
        return m;
    }
}
