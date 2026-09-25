package app.kotoba.reader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tokyo pitch accent for Japanese text, from the NHK 日本語発音アクセント新辞典 (when it's installed): the text is split
 * into words the way lookups do (longest dictionary match, conjugations traced back to the dictionary form), and each
 * word gets NHK's notation (タベ＼ル). A conjugated word takes the accent of the matching form in NHK's conjugation
 * table (ない, ます, stem, て, ば, よう: 食べた ← タ＼ベ + た → タ＼ベタ; 食べました ← タベマ＼ス → タベマ＼シタ), with the
 * usual rule for たい. Particles are left to the page: high after a flat word, low after a drop.
 */
public final class Accent {
    final Library lib;
    final java.util.Map<String,String[]> entries=new java.util.HashMap<>();// NHK rec → [reading, notation, con_table…]
    public Accent(Library lib){this.lib=lib;}

    static final Pattern ACCENT=Pattern.compile("<accent_text>(.*?)</accent_text>|<accent class=\"accent\">(.*?)</accent>",Pattern.DOTALL);
    static final Pattern KANA=Pattern.compile("[\u3041-\u3096ー]+");
    static final Pattern PLAIN=Pattern.compile("[\u3041-\u3096\u30a1-\u30faー＼゜\u309a━]+");
    static final Pattern TAI=Pattern.compile("た(い|か|く|け)");
    static final Pattern HEAD=Pattern.compile("<headword>(.*?)</headword>",Pattern.DOTALL);
    static final String PARTICLES="は が を に へ で と も の から まで より や か ね よ な って けど し ば";

    long nhk() throws Exception {
        JSONArray r=Store.rows(lib.db,"SELECT id FROM dicts WHERE status='ready' AND (title LIKE '%NHK%' OR title LIKE '%アクセント%') ORDER BY id LIMIT 1");
        return r.length()==0?0:r.getJSONObject(0).getLong("id");
    }

    /** Each text's words: [{s, e (code points), surface, kana, accent (NHK notation) | particle}]. */
    public JSONObject annotate(JSONArray texts) throws Exception {
        long nhk=nhk();
        if(nhk==0)throw new Exception("Install the NHK accent dictionary (NHK 日本語発音アクセント新辞典) to see pitch accents.");
        JSONArray out=new JSONArray();
        for(int t=0;t<texts.length();t++){
            String text=texts.getString(t);
            JSONArray w=done.get(text);
            if(w==null){w=words(text,nhk);if(done.size()>4000)done.clear();done.put(text,w);}
            out.put(w);
        }
        return new JSONObject().put("results",out);
    }
    final java.util.Map<String,JSONArray> done=new java.util.HashMap<>();

    /**
     * Works out every Japanese line and story paragraph of a listening set once and saves them beside it
     * (accents.json: text → words), so a phone shows accents at once. Runs in the background; progress in status().
     */
    public synchronized JSONObject export(java.io.File dir) throws Exception {
        if(exporting!=null&&exporting.isAlive())return status();
        long nhk=nhk();
        if(nhk==0)throw new Exception("Install the NHK accent dictionary first.");
        java.util.LinkedHashSet<String> texts=new java.util.LinkedHashSet<>();
        java.io.File setFile=new java.io.File(dir,"set.json"),storyFile=new java.io.File(dir,"stories.json");
        JSONObject set=new JSONObject(java.nio.file.Files.readString(setFile.toPath()));
        if("ja".equals(set.optString("lang"))){
            JSONArray gs=set.getJSONArray("groups");
            for(int i=0;i<gs.length();i++){JSONArray it=gs.getJSONObject(i).getJSONArray("items");for(int k=0;k<it.length();k++)texts.add(it.getJSONObject(k).getString("text"));}
        }
        if(storyFile.isFile()){
            JSONObject gs=new JSONObject(java.nio.file.Files.readString(storyFile.toPath())).getJSONObject("groups");
            for(java.util.Iterator<String> ids=gs.keys();ids.hasNext();){// keys(): Android's JSONObject has no keySet()
                String id=ids.next();
                JSONArray secs=gs.getJSONObject(id).getJSONObject("sections").optJSONArray("ja");
                for(int k=0;secs!=null&&k<secs.length();k++)for(String p:secs.getJSONObject(k).getString("text").split("\n+"))if(!p.isEmpty())texts.add(p);
            }
        }
        progress=new int[]{0,texts.size()};
        exporting=new Thread(()->{
            try{
                JSONObject out=new JSONObject();
                java.io.File file=new java.io.File(dir,"accents.json");
                if(file.isFile())out=new JSONObject(java.nio.file.Files.readString(file.toPath()));
                int n=0;
                for(String t:texts){
                    if(!out.has(t))synchronized(lib){out.put(t,words(t,nhk));}
                    progress[0]=++n;
                    if(n%200==0||n==texts.size())java.nio.file.Files.writeString(file.toPath(),out.toString());
                }
            }catch(Exception e){progress[1]=-1;}
        },"accent-export");
        exporting.setDaemon(true);exporting.start();
        return status();
    }
    Thread exporting;int[] progress={0,0};
    public JSONObject status() throws Exception {return new JSONObject().put("done",progress[0]).put("total",progress[1]).put("running",exporting!=null&&exporting.isAlive());}

    JSONArray words(String text,long nhk) throws Exception {
        JSONArray out=new JSONArray();
        int[] cps=text.codePoints().toArray();
        for(int i=0;i<cps.length;){
            int c=cps[i];
            if(!(Library.han(c)||Library.kana(c)||c>=0x30A0&&c<=0x30FF)){i++;continue;}
            String window=new String(cps,i,Math.min(10,cps.length-i));// longer words are rare, and short lookups are quick
            // A particle right after a word (私は, 本を) is the particle, not the start of another word (はな).
            String one=new String(cps,i,1),two=i+1<cps.length?new String(cps,i,2):"";
            String particle=(" "+PARTICLES+" ").contains(" "+two+" ")?two:(" "+PARTICLES+" ").contains(" "+one+" ")?one:null;
            if(particle!=null&&prevIsWord(out,i)){
                int pl=particle.codePointCount(0,particle.length());
                out.put(new JSONObject().put("s",i).put("e",i+pl).put("surface",particle).put("kana",particle).put("particle",true));
                i+=pl;continue;
            }
            JSONObject r=lib.lookup(window,"ja");
            String matched=r.optString("matched","");
            int len=matched.isEmpty()?0:matched.codePointCount(0,matched.length());
            if(r.getJSONArray("items").length()==0||len==0){i++;continue;}
            String[] a=null;
            try{a=accent(r,matched,nhk);}catch(Exception ignored){}
            // Another dictionary form of the same text that NHK does list (降っていた: 降る, not the old 降つ).
            JSONArray forms=r.optJSONArray("forms");
            for(int f=0;a==null&&forms!=null&&f<forms.length();f++){
                JSONObject fo=forms.getJSONObject(f);JSONArray fi=fo.optJSONArray("items");
                if(fi==null||fi.length()==0)continue;
                JSONObject alt=new JSONObject().put("items",fi).put("key",fi.getJSONObject(0).optString("key",matched));
                try{a=accent(alt,matched,nhk);if(a!=null)r=alt;}catch(Exception ignored){}
            }
            // A phrase NHK doesn't list (雨が降る): just its first word, up to the particle inside it.
            if(a==null&&len>1){
                int cut=-1;int[] mc=matched.codePoints().toArray();
                for(int k=1;k<mc.length&&cut<0;k++)if((" "+PARTICLES+" ").contains(" "+new String(mc,k,1)+" ")&&!Library.kana(mc[k-1]))cut=k;
                if(cut>0){
                    JSONObject r2=lib.lookup(new String(cps,i,cut),"ja");
                    String m2=r2.optString("matched","");
                    if(r2.getJSONArray("items").length()>0&&!m2.isEmpty()){r=r2;matched=m2;len=m2.codePointCount(0,m2.length());try{a=accent(r,matched,nhk);}catch(Exception ignored){}}
                }
            }
            JSONObject w=new JSONObject().put("s",i).put("e",i+len).put("surface",matched).put("base",r.optString("key",matched));
            if(a!=null)w.put("kana",a[0]).put("accent",a[1]);
            out.put(w);
            i+=len;
        }
        return out;
    }
    /** A particle directly after a word (私は, 本を): taken as the particle, not as the start of another word. */
    static boolean prevIsWord(JSONArray out,int i) throws Exception {
        if(out.length()==0)return false;
        JSONObject p=out.getJSONObject(out.length()-1);
        return p.getInt("e")==i&&!p.optBoolean("particle");
    }

    /** [kana of the surface, its notation], or null when NHK doesn't have the word. */
    String[] accent(JSONObject r,String surface,long nhk) throws Exception {
        JSONArray items=r.getJSONArray("items");
        // The reading the dictionaries give first (昨日: きのう, not さくじつ), from a kana page name (た・べる).
        // (the reading most dictionaries agree on: an entry for a compound like 遊び友達 can come first)
        java.util.Map<String,Integer> votes=new java.util.LinkedHashMap<>();
        for(int i=0;i<items.length();i++){
            JSONObject it=items.getJSONObject(i);if(it.optLong("dict")==nhk||!r.optString("key").equals(it.optString("key")))continue;
            String pg=hira(it.optString("page","")).replaceAll("[・‐-]","");
            if(KANA.matcher(pg).matches())votes.merge(pg,1,Integer::sum);
        }
        String want="";int most=0;
        for(java.util.Map.Entry<String,Integer> v:votes.entrySet())if(v.getValue()>most){most=v.getValue();want=v.getKey();}
        String[] e=null;
        for(int i=0;i<items.length();i++){
            JSONObject it=items.getJSONObject(i);if(it.optLong("dict")!=nhk)continue;
            String[] c=entry(it.getLong("rec"));
            if(c==null||c[0].startsWith("〜"))continue;// 〜友達: a compound's entry, not the word's
            if(e==null)e=c;
            if(!want.isEmpty()&&hira(c[0]).replaceAll("[・‐-]","").equals(want)){e=c;break;}
        }
        // NHK files the word under another reading only (昨日: さくじつ): look for the reading the dictionaries give.
        if(!want.isEmpty()&&(e==null||!hira(e[0]).replaceAll("[・‐-]","").equals(want))){
            JSONArray alt=lib.lookup(want,"ja").getJSONArray("items");
            for(int i=0;i<alt.length();i++){
                JSONObject it=alt.getJSONObject(i);if(it.optLong("dict")!=nhk)continue;
                String[] c=entry(it.getLong("rec"));
                if(c!=null&&hira(c[0]).replaceAll("[・‐-]","").equals(want)){e=c;break;}
            }
        }
        if(e==null)return null;
        if(e==null||e.length<2)return null;
        String base=r.optString("key",surface),reading=hira(e[0]);
        String kana=surfaceKana(surface,base,reading);
        // A reading shorter than the kanji it covers is another word's (図書館司書 ≠ しこ).
        if(kana==null||morae(kana).size()<surface.codePoints().filter(Library::han).count())return null;
        List<String> sm=morae(kana);
        // The notation whose kana shares the longest start with the surface (the word itself, then its forms).
        String best=null;int bestL=-1;
        for(int i=1;i<e.length;i++){
            String n=hira(e[i]);
            int l=common(morae(n),sm);
            if(l>bestL){bestL=l;best=n;}
        }
        if(best==null)return null;
        List<String> bm=morae(best);
        int drop=dropOf(best);
        int result;
        if(bestL==sm.size()&&bm.size()==sm.size())result=drop;                  // the form itself
        else if(bestL<sm.size()&&TAI.matcher(kana).find(Math.max(0,kana.length()-kana.length()+Math.max(0,bestL-1)))){
            // 〜たい behaves like an adjective: an accented verb drops on its た (タベタ＼イ); a flat one stays flat.
            int base0=dropOf(hira(e[1]));
            Matcher tm=TAI.matcher(kana);tm.find(Math.max(0,bestL-1));int ta=tm.start();
            result=base0<0?-1:morae(kana.substring(0,ta+1)).size();
        }
        else if(drop>=0&&drop<=bestL)result=drop;                               // the drop falls within the shared part
        else if(drop<0)result=-1;                                               // flat stays flat
        else result=Math.max(1,bestL);
        StringBuilder b=new StringBuilder();
        for(int i=0;i<sm.size();i++){b.append(sm.get(i));if(i+1==result)b.append('＼');}
        return new String[]{kana,b.toString()};
    }

    /** NHK's reading and notations for an entry (accent_text first, then the conjugation table). */
    String[] entry(long rec) throws Exception {
        String key=Long.toString(rec);
        if(entries.containsKey(key))return entries.get(key);
        String html=lib.recordHtml(rec);
        List<String> out=new ArrayList<>();
        Matcher h=HEAD.matcher(html);
        out.add(h.find()?strip(h.group(1)):"");
        Matcher m=ACCENT.matcher(html);
        while(m.find()){
            String n=strip(m.group(1)!=null?m.group(1):m.group(2)).replaceAll("[🔊○◯\\s・]","");
            // Only the word's own notations (not compounds like 〜＼トモダチ).
            if(!n.isEmpty()&&PLAIN.matcher(n).matches()&&!out.contains(n))out.add(n);
        }
        String[] e=out.size()<2?null:out.toArray(new String[0]);
        entries.put(key,e);
        return e;
    }
    static String strip(String html){return HtmlText.entities(html.replaceAll("<sound>.*?</sound>","").replaceAll("<[^>]*>","")).trim();}

    /** The surface's kana: the dictionary form's reading with its ending swapped (食べる たべる → 食べた たべた). */
    static String surfaceKana(String surface,String base,String reading){
        String s=hira(surface);
        if(s.codePoints().allMatch(c->Library.kana(c)))return s;
        if(reading.isEmpty())return null;
        int p=0;while(p<Math.min(surface.length(),base.length())&&surface.charAt(p)==base.charAt(p))p++;
        String baseTail=hira(base.substring(p)),surfTail=hira(surface.substring(p));
        if(!baseTail.codePoints().allMatch(c->Library.kana(c))||!surfTail.codePoints().allMatch(c->Library.kana(c)))return null;
        if(!reading.endsWith(baseTail))return null;
        return reading.substring(0,reading.length()-baseTail.length())+surfTail;
    }
    static String hira(String s){
        StringBuilder b=new StringBuilder();
        for(char c:s.toCharArray())b.append(c>=0x30A1&&c<=0x30F6?(char)(c-0x60):c);
        return b.toString();
    }
    static List<String> morae(String k){
        List<String> m=new ArrayList<>();
        for(char c:k.toCharArray()){
            if("ゃゅょぁぃぅぇぉゎ".indexOf(c)>=0&&!m.isEmpty()){m.set(m.size()-1,m.get(m.size()-1)+c);continue;}
            if(c=='＼'||c=='━')continue;
            // NHK's nasal カ゚ is が as written.
            if(c=='゜'||c=='\u309a'){if(!m.isEmpty()){String l=m.get(m.size()-1);int i="かきくけこ".indexOf(l.charAt(0));if(i>=0)m.set(m.size()-1,"がぎぐげご".charAt(i)+l.substring(1));}continue;}
            m.add(String.valueOf(c));
        }
        return m;
    }
    static int dropOf(String n){int i=n.indexOf('＼');return i<0?-1:morae(n.substring(0,i)).size();}
    /** Morae in common from the start; NHK's ー stands for the long vowel as written (エーガ = えいが, コー = こう). */
    static int common(List<String> a,List<String> b){int i=0;while(i<a.size()&&i<b.size()&&same(a.get(i),b.get(i)))i++;return i;}
    static boolean same(String x,String y){
        if(x.equals(y))return true;
        return x.equals("ー")&&"あいうえお".contains(y)||y.equals("ー")&&"あいうえお".contains(x);
    }
}
