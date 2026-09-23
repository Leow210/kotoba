package app.kotoba.reader;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-dictionary extras exported from the Monokakido app by tools/export_extras.py:
 * furoku titles and pages the .mdd lacks, and the dictionary's own word selections (地名, 四字熟語, 重要語…).
 * They live in files/extras/<mdx name>/kotoba-extras.json, beside (never inside) the dictionary files.
 */
public class Extras {
    final Library library;
    final File root;
    final Map<Long,Object[]> cache=new HashMap<>();// dict → {mtime, manifest}

    public Extras(Library library,File root){this.library=library;this.root=root;}

    File folder(long dict) throws Exception {
        JSONArray rows=Store.rows(library.db,"SELECT mdx FROM dicts WHERE id=?",Long.toString(dict));
        if(rows.length()==0)return null;
        String mdx=java.net.URLDecoder.decode(rows.getJSONObject(0).getString("mdx").replace("+","%2B"),"UTF-8");
        String name=mdx.substring(Math.max(mdx.lastIndexOf('/'),mdx.lastIndexOf(':'))+1).replaceFirst("(?i)\\.mdx$","");
        return new File(root,name.toLowerCase(java.util.Locale.ROOT));
    }

    JSONObject manifest(long dict) throws Exception {
        File dir=folder(dict);
        File f=dir==null?null:new File(dir,"kotoba-extras.json");
        if(f==null||!f.isFile())return new JSONObject().put("furoku",new JSONArray()).put("lists",new JSONArray());
        Object[] c=cache.get(dict);
        if(c!=null&&(long)c[0]==f.lastModified())return (JSONObject)c[1];
        JSONObject m;
        try{m=new JSONObject(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));}
        catch(Exception e){
            // A half-copied or broken manifest only loses this dictionary's extras, never the .mdd furoku.
            android.util.Log.w("Kotoba","extras "+f,e);
            return new JSONObject().put("furoku",new JSONArray()).put("lists",new JSONArray());
        }
        cache.put(dict,new Object[]{f.lastModified(),m});
        return m;
    }

    /** Furoku in Monokakido's menu order with Japanese titles; .mdd pages the menu doesn't name come last. */
    public JSONObject appendix(long dict) throws Exception {
        JSONArray groups=manifest(dict).getJSONArray("furoku");
        Set<String> named=new HashSet<>();
        for(int i=0;i<groups.length();i++){
            JSONArray items=groups.getJSONObject(i).getJSONArray("items");
            for(int j=0;j<items.length();j++){
                String p=items.getJSONObject(j).optString("path");
                named.add(p.startsWith("files/")?p.substring(6):p);
            }
        }
        JSONArray other=new JSONArray(),all=library.appendix(dict);
        for(int i=0;i<all.length();i++){String n=all.getJSONObject(i).getString("name");if(!named.contains(n))other.put(n);}
        return new JSONObject().put("groups",groups).put("other",other);
    }

    public JSONArray counts() throws Exception {
        JSONArray out=new JSONArray(),dicts=Store.rows(library.db,"SELECT id FROM dicts WHERE status='ready'");
        for(int i=0;i<dicts.length();i++){
            long id=dicts.getJSONObject(i).getLong("id");
            JSONObject a=appendix(id);
            int n=a.getJSONArray("other").length();
            for(int g=0;g<a.getJSONArray("groups").length();g++)n+=a.getJSONArray("groups").getJSONObject(g).getJSONArray("items").length();
            if(n>0)out.put(new JSONObject().put("dict",id).put("n",n));
        }
        return out;
    }

    /**
     * The dictionary's own fonts (extras/<name>/files/fonts/*.ttf|otf), as @font-face rules named after each file:
     * Monokakido's 朝鮮語辞典 draws 315 characters (Korean hanja forms like 鄕 稱, rare hanja) from CHOUSENGO_Symbol
     * in the private-use area, which no other font has.
     */
    public String fontFaces(long dict){
        try{
            File dir=folder(dict);if(dir==null)return "";
            File[] fonts=new File(dir,"files/fonts").listFiles((d,n)->n.matches("(?i).+\\.(ttf|otf|woff2?)"));
            if(fonts==null)return "";
            StringBuilder css=new StringBuilder();
            for(File f:fonts){
                String n=f.getName(),family=n.substring(0,n.lastIndexOf('.'));
                css.append("@font-face{font-family:'").append(family).append("';src:url('/d/").append(dict).append("/files/fonts/").append(n).append("')}");
            }
            return css.toString();
        }catch(Exception e){return "";}
    }

    /** A file under the extras folder, for /d/<dict>/files/… URLs. */
    public byte[] file(long dict,String name) throws Exception {
        File dir=folder(dict);
        if(dir==null||name.contains(".."))return null;
        File f=new File(dir,name);
        return f.isFile()?Files.readAllBytes(f.toPath()):null;
    }

    static final String[] LEVELS={"10","9","8","7","6","5","4","3","準2","2","準1","1"};

    /** The dictionary's selections plus, for a kanji dictionary with Kanken levels, one list of them by level. */
    JSONArray allLists(long dict) throws Exception {
        JSONArray lists=new JSONArray(manifest(dict).getJSONArray("lists").toString());
        JSONArray levels=Store.rows(library.db,"SELECT DISTINCT level FROM kanji WHERE dict=? AND level!=''",Long.toString(dict));
        if(levels.length()>0){
            JSONArray sections=new JSONArray();
            for(String lv:LEVELS){
                JSONArray items=new JSONArray();
                JSONArray rows=Store.rows(library.db,"SELECT rec,char FROM kanji WHERE dict=? AND level=? ORDER BY sortkey,rec",Long.toString(dict),lv);
                for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);items.put(new JSONArray().put(r.getString("char")).put(r.getString("char")).put("rec:"+r.getLong("rec")));}
                if(items.length()>0)sections.put(new JSONObject().put("title",lv+"級").put("items",items));
            }
            lists.put(new JSONObject().put("title","検定級数").put("sections",sections));
        }
        return lists;
    }

    /** Every enabled dictionary's own selections, without their words. */
    public JSONArray lists() throws Exception {
        JSONArray out=new JSONArray(),dicts=Store.rows(library.db,"SELECT id,name FROM dicts WHERE status='ready' AND enabled=1 ORDER BY position,id");
        for(int i=0;i<dicts.length();i++){
            JSONObject d=dicts.getJSONObject(i);
            JSONArray lists=allLists(d.getLong("id"));
            for(int l=0;l<lists.length();l++){
                JSONObject list=lists.getJSONObject(l);
                JSONArray sections=list.getJSONArray("sections");
                int n=0;for(int s=0;s<sections.length();s++)n+=sections.getJSONObject(s).getJSONArray("items").length();
                out.put(new JSONObject().put("dict",d.getLong("id")).put("dictionary",d.getString("name")).put("index",l).put("title",list.getString("title")).put("count",n).put("sections",sections.length()));
            }
        }
        return out;
    }

    public JSONObject list(long dict,int index) throws Exception {
        return allLists(dict).getJSONObject(index);
    }

    /**
     * A dictionary list (or one category: section == prefix, or below it) as flashcards in a folder.
     * The back is the definition of that very entry: 愛の鞭 gets its own sub-entry, not the whole 愛 page.
     */
    public JSONObject toFolder(Store store,long dict,int index,String prefix,long folder,java.util.function.Consumer<Integer> progress) throws Exception {
        JSONObject list=list(dict,index);
        String dictName=Store.rows(library.db,"SELECT name FROM dicts WHERE id=?",Long.toString(dict)).getJSONObject(0).getString("name");
        JSONArray sections=list.getJSONArray("sections");
        Set<String> seen=new HashSet<>();
        int added=0,missing=0;
        for(int s=0;s<sections.length();s++){
            JSONObject sec=sections.getJSONObject(s);
            String t=sec.getString("title");
            if(prefix!=null&&!prefix.isEmpty()&&!t.equals(prefix)&&!t.startsWith(prefix+" › "))continue;
            JSONArray items=sec.getJSONArray("items");
            for(int i=0;i<items.length();i++){
                JSONArray it=items.getJSONArray(i);
                String word=it.getString(0),head=it.getString(1),anchor=it.getString(2);
                if(!seen.add(anchor.isEmpty()?word:anchor))continue;
                JSONObject r=resolve(dict,anchor,word);
                if(r.isNull("rec")){missing++;continue;}
                String back=definition(r.getLong("rec"),anchor.startsWith("rec:")?"":anchor);
                if(back.isEmpty()){missing++;continue;}
                store.saveItem(new JSONObject().put("folder_id",folder).put("headword",word).put("reading",reading(word,head)).put("back",back)
                    .put("dict",dict).put("dict_name",dictName).put("page",library.record(r.getLong("rec")).getString("key")).put("anchor",anchor.startsWith("rec:")?"":anchor)
                    .put("kind","wordlist").put("review",true));
                if(++added%100==0)progress.accept(added);
            }
        }
        return new JSONObject().put("added",added).put("missing",missing);
    }

    /** あいさつ【挨拶】 → あいさつ; 가격〔價格〕 → 價格 (hanja under the Korean word). */
    static String reading(String word,String head){
        int b=head.indexOf('【');
        if(b>0)return head.substring(0,b).replaceAll("[‐・▽▼]","");
        if(head.startsWith(word+"〔")&&head.endsWith("〕"))return head.substring(word.length()+1,head.length()-1);
        return "";
    }

    /** End of the element that starts at `start`, counting nested tags of the same name. */
    static int elementEnd(String html,int start){
        java.util.regex.Matcher name=java.util.regex.Pattern.compile("<([\\w-]+)").matcher(html);
        if(!name.find(start)||name.start()!=start)return html.length();
        String tag=name.group(1);
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("<(/?)"+java.util.regex.Pattern.quote(tag)+"(?=[\\s>/])[^>]*?(/?)>").matcher(html);
        int depth=0;
        for(m.region(start,html.length());m.find();){
            if(!m.group(2).isEmpty())continue;// <x/>
            depth+=m.group(1).isEmpty()?1:-1;
            if(depth==0)return m.end();
        }
        return html.length();
    }

    /** Plain definition text of an entry, or of the sub-entry an anchor points at (00506-4001: 相手変われど主変わらず). */
    String definition(long rec,String anchor) throws Exception {
        String html=MarkupFix.html(library.recordHtml(rec));
        int dash=anchor.indexOf('-');
        if(dash>0){
            int at=html.indexOf("id=\""+anchor+"\"");
            if(at>0){
                int start=html.lastIndexOf('<',at);
                html=html.substring(start,elementEnd(html,start));
            }
        }
        String text=HtmlText.parse(html).definitions.toString().replaceAll("\\s+"," ").trim();
        return text.length()>600?text.substring(0,600)+"…":text;
    }

    /** A selection's entry: by the page anchor when the export kept Monokakido's ids, else by headword. */
    public JSONObject resolve(long dict,String anchor,String word) throws Exception {
        if(anchor.startsWith("rec:"))return new JSONObject().put("rec",Long.parseLong(anchor.substring(4))).put("anchor","");
        // A sub-entry (idiom 00040-4002) that the dictionary also keeps as a record of its own (新明解: 愛の巣).
        if(anchor.indexOf('-')>0){
            JSONArray own=Store.rows(library.db,"SELECT id rec,key FROM records WHERE dict=? AND norm=? LIMIT 1",Long.toString(dict),HtmlText.normalize(word));
            if(own.length()>0)return own.getJSONObject(0).put("anchor","");
        }
        if(!anchor.isEmpty()){
            JSONObject r=library.reference(dict,anchor);
            if(!r.isNull("rec"))return r;
        }
        for(String w:new String[]{word,word.replaceAll("[（(].*?[）)]","").replaceAll("[・＝=]"," ").split(" ")[0]}){
            JSONArray keys=Store.rows(library.db,"SELECT rec,key FROM keys WHERE norm=? AND dict=? LIMIT 1",HtmlText.normalize(w),Long.toString(dict));
            if(keys.length()>0)return keys.getJSONObject(0).put("anchor",anchor);
        }
        return new JSONObject().put("rec",JSONObject.NULL);
    }
}
