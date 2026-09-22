package app.kotoba.reader;

import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** EPUB (2 and 3) and plain-text book parsing. Pure Java so it can be tested on a desktop JVM. */
public final class BookParser {
    public static final class Spine { public String href,id,mediaType;public long size;public boolean linear=true; }
    public static final class Toc { public String title,href;public int level; Toc(String t,String h,int l){title=t;href=h;level=l;} }
    public static final class Book {
        public String format="epub",title="",author="",language="",direction="",cover="",writing="";
        public final List<Spine> spine=new ArrayList<>();
        public final List<Toc> toc=new ArrayList<>();
        /** TXT only: decoded text and chapter boundaries (start offsets). */
        public String text;public final List<int[]> ranges=new ArrayList<>();public String encoding="";
    }

    // ---------------------------------------------------------------- EPUB

    public static Book epub(ZipSource zip) throws Exception {
        Book book=new Book();
        String container=new String(zip.bytes("META-INF/container.xml"),StandardCharsets.UTF_8);
        Matcher rf=Pattern.compile("full-path=[\"']([^\"']+)[\"']").matcher(container);
        if(!rf.find())throw new Exception("This EPUB has no package file.");
        String opfPath=rf.group(1);
        String base=opfPath.contains("/")?opfPath.substring(0,opfPath.lastIndexOf('/')+1):"";
        Document opf=xml(zip.bytes(opfPath));
        Element root=opf.getDocumentElement();
        book.title=firstText(root,"title");
        book.author=firstText(root,"creator");
        if(book.author.matches("(?i)<?unknown>?|anonymous|n/a"))book.author="";
        book.language=firstText(root,"language").toLowerCase(java.util.Locale.ROOT);
        Map<String,String[]> manifest=new HashMap<>();// id → {href, media-type, properties}
        String coverId="";
        for(Element item:elements(root,"item")){
            String href=resolve(base,item.getAttribute("href"));
            manifest.put(item.getAttribute("id"),new String[]{href,item.getAttribute("media-type"),item.getAttribute("properties")});
            if(item.getAttribute("properties").contains("cover-image"))book.cover=href;
        }
        for(Element meta:elements(root,"meta"))if("cover".equals(meta.getAttribute("name")))coverId=meta.getAttribute("content");
        if(book.cover.isEmpty()&&manifest.containsKey(coverId))book.cover=manifest.get(coverId)[0];
        if(book.cover.isEmpty())for(String[] m:manifest.values())if(m[1].startsWith("image/")&&m[0].toLowerCase().contains("cover")){book.cover=m[0];break;}
        List<Element> spines=elements(root,"spine");
        String ncxId="";
        if(!spines.isEmpty()){
            Element s=spines.get(0);
            book.direction=s.getAttribute("page-progression-direction");
            ncxId=s.getAttribute("toc");
            for(Element ref:elements(s,"itemref")){
                String[] m=manifest.get(ref.getAttribute("idref"));
                if(m==null)continue;
                Spine sp=new Spine();sp.href=m[0];sp.id=ref.getAttribute("idref");sp.mediaType=m[1];
                sp.linear=!"no".equals(ref.getAttribute("linear"));
                ZipSource.Entry e=zip.find(sp.href);sp.size=e==null?0:e.size;
                if(e!=null)book.spine.add(sp);
            }
        }
        if(book.spine.isEmpty())throw new Exception("This EPUB has no readable chapters.");
        // Table of contents: EPUB 3 nav first, then EPUB 2 NCX.
        for(String[] m:manifest.values()){
            if(m[2].contains("nav")&&book.toc.isEmpty()){try{navToc(zip,m[0],book);}catch(Exception ignored){}}
        }
        if(book.toc.isEmpty()){
            String ncx=manifest.containsKey(ncxId)?manifest.get(ncxId)[0]:"";
            if(ncx.isEmpty())for(String[] m:manifest.values())if(m[1].contains("dtbncx")){ncx=m[0];break;}
            if(!ncx.isEmpty())try{ncxToc(zip,ncx,book);}catch(Exception ignored){}
        }
        // Writing direction hint from the stylesheets (vertical Japanese books).
        for(String[] m:manifest.values()){
            if(!m[1].equals("text/css"))continue;
            try{
                String css=new String(zip.bytes(m[0]),StandardCharsets.UTF_8);
                if(css.matches("(?s).*writing-mode\\s*:\\s*vertical-rl.*")||css.matches("(?s).*-epub-writing-mode\\s*:\\s*vertical-rl.*")){book.writing="vertical";break;}
            }catch(Exception ignored){}
        }
        if(book.writing.isEmpty()&&"rtl".equals(book.direction)&&book.language.startsWith("ja"))book.writing="vertical";
        return book;
    }

    static void navToc(ZipSource zip,String path,Book book) throws Exception {
        String base=path.contains("/")?path.substring(0,path.lastIndexOf('/')+1):"";
        Document doc=xml(zip.bytes(path));
        Element tocNav=null;
        for(Element nav:elements(doc.getDocumentElement(),"nav")){
            String type=nav.getAttribute("epub:type")+nav.getAttributeNS("http://www.idpf.org/2007/ops","type");
            if(type.contains("toc")){tocNav=nav;break;}
            if(tocNav==null)tocNav=nav;
        }
        if(tocNav==null)return;
        for(Element ol:children(tocNav,"ol")){walkNav(ol,0,base,book);break;}
    }
    static void walkNav(Element ol,int level,String base,Book book){
        for(Element li:children(ol,"li")){
            for(Element a:children(li,"a"))book.toc.add(new Toc(clean(a.getTextContent()),resolve(base,a.getAttribute("href")),level));
            for(Element span:children(li,"span"))if(children(li,"a").isEmpty())book.toc.add(new Toc(clean(span.getTextContent()),"",level));
            for(Element sub:children(li,"ol"))walkNav(sub,level+1,base,book);
        }
    }
    static void ncxToc(ZipSource zip,String path,Book book) throws Exception {
        String base=path.contains("/")?path.substring(0,path.lastIndexOf('/')+1):"";
        Document doc=xml(zip.bytes(path));
        for(Element map:elements(doc.getDocumentElement(),"navMap")){walkNcx(map,0,base,book);break;}
    }
    static void walkNcx(Element parent,int level,String base,Book book){
        for(Element point:children(parent,"navPoint")){
            String title="";String src="";
            for(Element label:children(point,"navLabel"))title=clean(label.getTextContent());
            for(Element content:children(point,"content"))src=content.getAttribute("src");
            book.toc.add(new Toc(title,resolve(base,src),level));
            walkNcx(point,level+1,base,book);
        }
    }

    // ---------------------------------------------------------------- TXT

    static final Pattern HEADING=Pattern.compile("^[\\s\\u3000]*(?:"
        +"第[0-9０-９一二三四五六七八九十百千〇零]+[章話回部節巻幕]"
        +"|제\\s*[0-9]+\\s*[장화부권]"
        +"|[0-9]+\\s*[장화]\\b"
        +"|บทที่\\s*[0-9๐-๙]+"
        +"|(?:Глава|ГЛАВА|глава)\\s+[0-9IVXLCА-Яа-я]+"
        +"|(?:Chapter|CHAPTER)\\s+[0-9IVXLC]+"
        +"|プロローグ|エピローグ|序章|終章|프롤로그|에필로그|Пролог|Эпилог|บทนำ|บทส่งท้าย"
        +")[^\\n]{0,40}$",Pattern.MULTILINE);

    public static Book txt(byte[] bytes,String fileName){
        Book book=new Book();
        book.format="txt";
        String[] decoded=decode(bytes);
        book.text=decoded[0].replace("\r\n","\n").replace('\r','\n');
        book.encoding=decoded[1];
        String name=fileName.replaceFirst("\\.[^.]+$","");
        book.title=name;
        Matcher m=HEADING.matcher(book.text);
        List<int[]> heads=new ArrayList<>();
        while(m.find())heads.add(new int[]{m.start(),m.end()});
        if(heads.size()>=2){
            if(heads.get(0)[0]>200)book.ranges.add(new int[]{0,heads.get(0)[0]});
            for(int i=0;i<heads.size();i++){
                int start=heads.get(i)[0],end=i+1<heads.size()?heads.get(i+1)[0]:book.text.length();
                book.ranges.add(new int[]{start,end});
            }
        }else{
            // No headings: split at paragraph breaks roughly every 15,000 characters.
            int start=0;
            while(start<book.text.length()){
                int end=Math.min(book.text.length(),start+15000);
                if(end<book.text.length()){int nl=book.text.indexOf('\n',end);end=nl<0?book.text.length():nl+1;}
                book.ranges.add(new int[]{start,end});
                start=end;
            }
        }
        for(int i=0;i<book.ranges.size();i++){
            int[] r=book.ranges.get(i);
            String first=book.text.substring(r[0],Math.min(r[1],r[0]+200)).trim();
            int nl=first.indexOf('\n');if(nl>0)first=first.substring(0,nl);
            String title=heads.size()>=2?first.trim():"Part "+(i+1);
            if(title.length()>60)title=title.substring(0,60)+"…";
            Spine s=new Spine();s.href="txt/"+i+".xhtml";s.id="c"+i;s.mediaType="application/xhtml+xml";s.size=r[1]-r[0];
            book.spine.add(s);
            book.toc.add(new Toc(title.isEmpty()?"Part "+(i+1):title,s.href,0));
        }
        book.language=guessLanguage(book.text);
        if(book.language.equals("ja"))book.writing="";
        return book;
    }

    /** Chapter of a TXT book as XHTML: one paragraph per line, blank lines kept as spacing. */
    public static String txtChapter(Book book,int index){
        int[] r=book.ranges.get(index);
        String part=book.text.substring(r[0],r[1]);
        StringBuilder b=new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><!DOCTYPE html><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><meta charset=\"utf-8\"/><title>")
            .append(escape(book.toc.get(index).title)).append("</title></head><body class=\"kotoba-txt\">");
        boolean first=true;
        for(String line:part.split("\n",-1)){
            String t=line.trim();
            if(t.isEmpty()){b.append("<p class=\"blank\"> </p>");continue;}
            if(first&&HEADING.matcher(line).matches()){b.append("<h2>").append(escape(t)).append("</h2>");first=false;continue;}
            first=false;
            b.append("<p>").append(escape(line.replaceAll("^[ \\t]+",""))).append("</p>");
        }
        return b.append("</body></html>").toString();
    }

    /** Decodes text in the most plausible encoding: BOM, strict UTF-8, then legacy CJK/Thai/Cyrillic code pages. */
    public static String[] decode(byte[] b){
        if(b.length>=3&&(b[0]&0xff)==0xEF&&(b[1]&0xff)==0xBB&&(b[2]&0xff)==0xBF)return new String[]{new String(b,3,b.length-3,StandardCharsets.UTF_8),"UTF-8"};
        if(b.length>=2&&(b[0]&0xff)==0xFF&&(b[1]&0xff)==0xFE)return new String[]{new String(b,2,b.length-2,StandardCharsets.UTF_16LE),"UTF-16LE"};
        if(b.length>=2&&(b[0]&0xff)==0xFE&&(b[1]&0xff)==0xFF)return new String[]{new String(b,2,b.length-2,StandardCharsets.UTF_16BE),"UTF-16BE"};
        String best=null,bestName="UTF-8";double bestScore=-1;
        for(String name:new String[]{"UTF-8","Shift_JIS","EUC-JP","MS949","x-windows-949","windows-949","EUC-KR","windows-874","TIS-620","windows-1251","KOI8-R","GB18030"}){
            Charset cs;
            try{cs=Charset.forName(name);}catch(Exception e){continue;}
            CharsetDecoder d=cs.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
            String s;
            try{s=d.decode(ByteBuffer.wrap(b,0,Math.min(b.length,400000))).toString();}catch(Exception e){continue;}
            double score=plausibility(s);
            int bad=0;for(int i=0;i<s.length();i++)if(s.charAt(i)=='\uFFFD')bad++;
            if(name.equals("UTF-8")&&bad==0){best=s;bestName=name;break;}
            if(score>bestScore){bestScore=score;best=s;bestName=name;}
        }
        return new String[]{new String(b,Charset.forName(bestName)),bestName};
    }

    /** How much a decoding looks like real text rather than mojibake. */
    static double plausibility(String s){
            // Plausibility: real text is mostly kana / common Hangul / Thai / Cyrillic; mojibake is rare syllables and kanji soup.
            java.nio.charset.CharsetEncoder ksx=Charset.forName("EUC-KR").newEncoder();
            int bad=0,total=0;double good=0;
            for(int i=0;i<s.length();i++){
                char c=s.charAt(i);
                if(c=='\uFFFD'){bad++;continue;}
                if(Character.isWhitespace(c))continue;
                total++;
                if(c>=0x0e00&&c<=0x0e7f){
                    // Thai combining marks must follow a consonant; mojibake scatters them anywhere.
                    boolean mark=c==0x0e31||(c>=0x0e34&&c<=0x0e3a)||(c>=0x0e47&&c<=0x0e4e);
                    char p=i>0?s.charAt(i-1):' ';
                    boolean afterLetter=(p>=0x0e01&&p<=0x0e2e)||(mark&&((p>=0x0e31&&p<=0x0e3a)||(p>=0x0e47&&p<=0x0e4e)));
                    // Letters that are rare in real Thai but common in mojibake (ฃ ฅ ฆ ฌ ฎ ฏ ฐ ฑ ฒ ฝ ฦ ฯ ๅ ฿ ๏ ๚ ๛).
                    boolean rare="\u0e03\u0e05\u0e06\u0e0c\u0e0e\u0e0f\u0e10\u0e11\u0e12\u0e1d\u0e26\u0e2f\u0e45\u0e3f\u0e4f\u0e5a\u0e5b".indexOf(c)>=0;
                    good+=mark&&!afterLetter?-2:(c>0x0e5b?-1:rare?-0.5:1);
                }
                else if((c>=0x3040&&c<=0x30ff)||(c>=0x0400&&c<=0x04ff))good+=1;
                else if(c>=0xac00&&c<=0xd7a3)good+=ksx.canEncode(c)?1.1:-1;
                else if(c>=0x4e00&&c<=0x9fff)good+=0.3;
                else if(c<128&&Character.isLetterOrDigit(c))good+=0.5;
                else if(c>=0xff61&&c<=0xff9f)good-=1;// half-width katakana: classic mojibake
                else if("。、！？「」『』（）…・ー，．：；“”‘’«»—–-.,!?\"'()".indexOf(c)>=0)good+=0.5;
            }
            return total==0?0:good/total-bad*5.0/Math.max(1,total);
    }

    static String guessLanguage(String text){
        int ja=0,ko=0,th=0,ru=0,zh=0;
        for(int i=0;i<Math.min(text.length(),20000);i++){
            char c=text.charAt(i);
            if(c>=0x3040&&c<=0x30ff)ja++;else if(c>=0xac00&&c<=0xd7a3)ko++;else if(c>=0x0e00&&c<=0x0e7f)th++;else if(c>=0x0400&&c<=0x04ff)ru++;else if(c>=0x4e00&&c<=0x9fff)zh++;
        }
        int max=Math.max(Math.max(ja,ko),Math.max(Math.max(th,ru),zh));
        if(max==0)return "";
        return max==ja?"ja":max==ko?"ko":max==th?"th":max==ru?"ru":ja>0?"ja":"zh";
    }

    // ---------------------------------------------------------------- helpers

    static String resolve(String base,String href){
        if(href==null)return "";
        String h=href.trim();
        String frag="";
        int hash=h.indexOf('#');
        if(hash>=0){frag=h.substring(hash);h=h.substring(0,hash);}
        try{h=URLDecoder.decode(h.replace("+","%2B"),"UTF-8");}catch(Exception ignored){}
        if(h.isEmpty())return frag;
        String path=h.startsWith("/")?h.substring(1):base+h;
        ArrayList<String> parts=new ArrayList<>();
        for(String p:path.split("/")){
            if(p.isEmpty()||p.equals("."))continue;
            if(p.equals("..")){if(!parts.isEmpty())parts.remove(parts.size()-1);continue;}
            parts.add(p);
        }
        return String.join("/",parts)+frag;
    }

    static String clean(String s){return s==null?"":s.replaceAll("\\s+"," ").trim();}
    static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}

    static final Pattern ENTITY=Pattern.compile("&([a-zA-Z][a-zA-Z0-9]*);");
    /** Parses XML leniently: HTML named entities that XML doesn't define are converted first. */
    static Document xml(byte[] bytes) throws Exception {
        String s=new String(bytes,StandardCharsets.UTF_8);
        if(s.startsWith("\uFEFF"))s=s.substring(1);
        Matcher m=ENTITY.matcher(s);StringBuffer out=new StringBuffer();
        while(m.find()){
            String name=m.group(1);String rep;
            switch(name){case "amp":case "lt":case "gt":case "quot":case "apos":rep="&"+name+";";break;
                case "nbsp":rep="&#160;";break;case "mdash":rep="&#8212;";break;case "ndash":rep="&#8211;";break;case "hellip":rep="&#8230;";break;
                case "laquo":rep="&#171;";break;case "raquo":rep="&#187;";break;case "copy":rep="&#169;";break;default:rep=" ";}
            m.appendReplacement(out,Matcher.quoteReplacement(rep));
        }
        m.appendTail(out);
        s=out.toString().replaceFirst("(?is)<!DOCTYPE[^>\\[]*(\\[[^\\]]*\\])?>","");
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        try{f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);}catch(Exception ignored){}
        f.setExpandEntityReferences(false);
        DocumentBuilder db=f.newDocumentBuilder();
        return db.parse(new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)));
    }

    static String local(Node n){String l=n.getLocalName();if(l==null){l=n.getNodeName();int c=l.indexOf(':');if(c>=0)l=l.substring(c+1);}return l;}
    static List<Element> elements(Element root,String name){
        ArrayList<Element> out=new ArrayList<>();
        NodeList all=root.getElementsByTagName("*");
        for(int i=0;i<all.getLength();i++){Node n=all.item(i);if(local(n).equals(name))out.add((Element)n);}
        return out;
    }
    static List<Element> children(Element parent,String name){
        ArrayList<Element> out=new ArrayList<>();
        for(Node n=parent.getFirstChild();n!=null;n=n.getNextSibling())if(n.getNodeType()==Node.ELEMENT_NODE&&local(n).equals(name))out.add((Element)n);
        return out;
    }
    static String firstText(Element root,String name){List<Element> l=elements(root,name);return l.isEmpty()?"":clean(l.get(0).getTextContent());}
}
