package app.kotoba.reader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Time-synced lyrics for the song that's playing: LRCLIB (an open database of .lrc lyrics) first, then NetEase Cloud
 * Music's lyric service, which is strongest for Chinese, Cantonese and Japanese songs and often carries a translation.
 * The only thing in Kotoba that goes online; each song is fetched once and kept in the cache.
 */
public class Lyrics {
    static final String AGENT="Kotoba/0.3 (https://github.com/Leow210/kotoba)";
    final Store store;

    public Lyrics(Store store){
        this.store=store;
        store.db.execSQL("CREATE TABLE IF NOT EXISTS lyrics_cache(key TEXT PRIMARY KEY,data TEXT NOT NULL,fetched INTEGER NOT NULL)");
    }

    static String key(String title,String artist){return (norm(artist)+"|"+norm(title));}
    static String norm(String s){
        // Titles carry extras the lyric sites don't: "(feat. X)", "- Remastered 2011", "【MV】", "(Official Video)".
        return s==null?"":s.toLowerCase().replaceAll("[\\(\\[（【].*?[\\)\\]）】]","").replaceAll("\\s+-\\s+.*$","").replaceAll("[\\s\\p{P}]+"," ").trim();
    }

    /** {source, synced, lines:[{t (s), text, tr?}], title, artist} or {lines:[]} when nothing was found. */
    public JSONObject get(String title,String artist,String album,double duration,boolean refresh) throws Exception {
        String key=key(title,artist);
        if(!refresh){
            JSONArray hit=Store.rows(store.db,"SELECT data FROM lyrics_cache WHERE key=?",key);
            if(hit.length()>0)return new JSONObject(hit.getJSONObject(0).getString("data")).put("cached",true);
        }
        JSONObject r=null;
        try{r=lrclib(title,artist,album,duration);}catch(Exception ignored){}
        // NetEase when LRCLIB has nothing, only plain text, or no translation for a CJK song.
        if(r==null||!r.optBoolean("synced")){
            JSONObject n=null;
            try{n=netease(title,artist,duration);}catch(Exception ignored){}
            if(n!=null&&(r==null||n.optBoolean("synced")))r=n;
        }
        if(r==null)r=new JSONObject().put("lines",new JSONArray()).put("source","");
        r.put("title",title).put("artist",artist);
        if(r.getJSONArray("lines").length()>0){
            android.content.ContentValues v=new android.content.ContentValues();
            v.put("key",key);v.put("data",r.toString());v.put("fetched",System.currentTimeMillis());
            store.db.insertWithOnConflict("lyrics_cache",null,v,android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
        }
        return r;
    }

    /** Candidates for a search typed by hand (when the automatic match is wrong or missing). */
    public JSONArray search(String query) throws Exception {
        JSONArray out=new JSONArray();
        try{
            JSONArray a=new JSONArray(http("https://lrclib.net/api/search?q="+enc(query),null));
            for(int i=0;i<Math.min(8,a.length());i++){
                JSONObject s=a.getJSONObject(i);
                out.put(new JSONObject().put("source","lrclib").put("id",s.optLong("id")).put("title",s.optString("trackName")).put("artist",s.optString("artistName"))
                    .put("duration",s.optDouble("duration",0)).put("synced",!s.optString("syncedLyrics","").isEmpty()));
            }
        }catch(Exception ignored){}
        try{
            for(JSONObject s:neteaseSearch(query))out.put(s);
        }catch(Exception ignored){}
        return out;
    }

    /** A chosen search result becomes this song's lyrics. */
    public JSONObject pick(String title,String artist,String source,long id) throws Exception {
        JSONObject r;
        if(source.equals("lrclib"))r=fromLrclib(new JSONObject(http("https://lrclib.net/api/get/"+id,null)));
        else r=neteaseLyrics(id);
        if(r==null)throw new Exception("No lyrics there");
        r.put("title",title).put("artist",artist);
        android.content.ContentValues v=new android.content.ContentValues();
        v.put("key",key(title,artist));v.put("data",r.toString());v.put("fetched",System.currentTimeMillis());
        store.db.insertWithOnConflict("lyrics_cache",null,v,android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
        return r;
    }

    // ---------- LRCLIB ----------

    JSONObject lrclib(String title,String artist,String album,double duration) throws Exception {
        StringBuilder u=new StringBuilder("https://lrclib.net/api/get?track_name=").append(enc(title)).append("&artist_name=").append(enc(artist));
        if(album!=null&&!album.isEmpty())u.append("&album_name=").append(enc(album));
        if(duration>0)u.append("&duration=").append(Math.round(duration));
        try{
            JSONObject r=fromLrclib(new JSONObject(http(u.toString(),null)));
            if(r!=null&&fits(r,title,artist))return r;
        }catch(Exception ignored){}
        // Search: by title and artist, then by title alone (the artist may be written another way: Atom Chanakan /
        // อะตอม ชนกันต์). Each candidate is scored; lyrics in another script than the song's (a Vietnamese version of a
        // Japanese song) don't count.
        List<JSONObject> cands=new ArrayList<>();
        for(String q:new String[]{"track_name="+enc(clean(title))+"&artist_name="+enc(artist),"q="+enc(clean(title))}){
            try{JSONArray a=new JSONArray(http("https://lrclib.net/api/search?"+q,null));for(int i=0;i<a.length();i++)cands.add(a.getJSONObject(i));}catch(Exception ignored){}
        }
        JSONObject best=null;double bd=1e9;
        for(JSONObject s:cands){
            JSONObject r=fromLrclib(s);if(r==null)continue;
            double d=score(r,s.optString("trackName"),s.optString("artistName"),s.optDouble("duration",0),title,artist,duration);
            if(d<bd){bd=d;best=r;}
        }
        return best==null||bd>8?null:best;
    }

    /** Lower is better: seconds off the song's length, plus penalties for another script, artist or title, unsynced. */
    static double score(JSONObject r,String candTitle,String candArtist,double candDuration,String title,String artist,double duration){
        double d=duration>0&&candDuration>0?Math.abs(candDuration-duration):2;
        if(!fits(r,title,artist))d+=20;
        if(!similar(candArtist,artist))d+=3;
        if(!norm(candTitle).contains(norm(clean(title)))&&!norm(clean(title)).contains(norm(candTitle)))d+=4;
        if(!r.optBoolean("synced"))d+=1.5;
        return d;
    }
    static boolean similar(String a,String b){
        String x=norm(a),y=norm(b);
        if(x.isEmpty()||y.isEmpty())return true;
        if(x.contains(y)||y.contains(x))return true;
        for(String t:y.split(" "))if(t.length()>1&&x.contains(t))return true;
        return false;
    }
    /** Lyrics written in the scripts the title or artist use (kana/kanji, Hangul, Thai, Cyrillic), when they use any. */
    static boolean fits(JSONObject r,String title,String artist){
        String want=scripts(title+" "+artist);
        if(want.isEmpty())return true;
        StringBuilder text=new StringBuilder();
        JSONArray ls=r.optJSONArray("lines");
        if(ls!=null)for(int i=0;i<ls.length();i++)text.append(ls.optJSONObject(i).optString("text")).append('\n');
        String have=scripts(text.toString());
        for(char c:want.toCharArray())if(have.indexOf(c)>=0)return true;
        return false;
    }
    /** c: Chinese/Japanese, k: Korean, t: Thai, r: Cyrillic. */
    static String scripts(String s){
        StringBuilder out=new StringBuilder();
        boolean c=false,k=false,t=false,r=false;
        for(int i=0;i<s.length();i++){
            char ch=s.charAt(i);
            if(ch>=0x3040&&ch<=0x30ff||ch>=0x3400&&ch<=0x9fff)c=true;
            else if(ch>=0xac00&&ch<=0xd7a3)k=true;
            else if(ch>=0x0e00&&ch<=0x0e7f)t=true;
            else if(ch>=0x0400&&ch<=0x04ff)r=true;
        }
        if(c)out.append('c');if(k)out.append('k');if(t)out.append('t');if(r)out.append('r');
        return out.toString();
    }
    static JSONObject fromLrclib(JSONObject s) throws Exception {
        String synced=s.optString("syncedLyrics","");
        if(!synced.isEmpty()){JSONArray lines=parseLrc(synced,null);if(lines.length()>0)return new JSONObject().put("source","LRCLIB").put("synced",true).put("lines",lines);}
        String plain=s.optString("plainLyrics","");
        if(plain.isEmpty())return null;
        return new JSONObject().put("source","LRCLIB").put("synced",false).put("lines",plainLines(plain));
    }

    // ---------- NetEase Cloud Music ----------

    static final String NE_HEADERS="Referer: https://music.163.com/";
    JSONObject netease(String title,String artist,double duration) throws Exception {
        List<JSONObject> found=neteaseSearch(clean(title)+" "+artist);
        if(found.isEmpty())found=neteaseSearch(clean(title));
        JSONObject best=null;double bd=1e9;
        for(JSONObject s:found.subList(0,Math.min(4,found.size()))){
            JSONObject r;try{r=neteaseLyrics(s.getLong("id"));}catch(Exception e){continue;}
            if(r==null)continue;
            double d=score(r,s.optString("title"),s.optString("artist"),s.optDouble("duration",0),title,artist,duration);
            if(d<bd){bd=d;best=r;}
        }
        return best==null||bd>8?null:best;
    }
    List<JSONObject> neteaseSearch(String q) throws Exception {
        JSONObject r=new JSONObject(http("https://music.163.com/api/search/get/web?type=1&limit=8&s="+enc(q),NE_HEADERS));
        List<JSONObject> out=new ArrayList<>();
        JSONArray songs=r.optJSONObject("result")==null?null:r.getJSONObject("result").optJSONArray("songs");
        if(songs==null)return out;
        for(int i=0;i<songs.length();i++){
            JSONObject s=songs.getJSONObject(i);
            StringBuilder artists=new StringBuilder();
            JSONArray ar=s.optJSONArray("artists");
            if(ar!=null)for(int j=0;j<ar.length();j++){if(j>0)artists.append(", ");artists.append(ar.getJSONObject(j).optString("name"));}
            out.add(new JSONObject().put("source","netease").put("id",s.getLong("id")).put("title",s.optString("name")).put("artist",artists.toString())
                .put("duration",s.optDouble("duration",0)/1000.0).put("synced",true));
        }
        return out;
    }
    JSONObject neteaseLyrics(long id) throws Exception {
        JSONObject r=new JSONObject(http("https://music.163.com/api/song/lyric?id="+id+"&lv=1&kv=1&tv=-1",NE_HEADERS));
        String lrc=r.optJSONObject("lrc")==null?"":r.getJSONObject("lrc").optString("lyric","");
        String tr=r.optJSONObject("tlyric")==null?"":r.getJSONObject("tlyric").optString("lyric","");
        if(lrc.trim().isEmpty())return null;
        JSONArray lines=parseLrc(lrc,tr.trim().isEmpty()?null:tr);
        if(lines.length()==0)return new JSONObject().put("source","NetEase").put("synced",false).put("lines",plainLines(lrc.replaceAll("\\[[^\\]]*\\]","")));
        return new JSONObject().put("source","NetEase").put("synced",true).put("lines",lines);
    }

    // ---------- LRC ----------

    static final Pattern STAMP=Pattern.compile("\\[(\\d{1,3}):(\\d{1,2}(?:[.:]\\d{1,3})?)\\]");
    /** [mm:ss.xx]text lines (several stamps per line allowed), sorted; translations matched by time. */
    static JSONArray parseLrc(String lrc,String translation) throws Exception {
        java.util.TreeMap<Double,String> byTime=stamps(lrc),tr=translation==null?new java.util.TreeMap<>():stamps(translation);
        JSONArray out=new JSONArray();
        for(java.util.Map.Entry<Double,String> e:byTime.entrySet()){
            String text=e.getValue().trim();
            // Credit lines NetEase puts first (作词 : …, 作曲 : …) aren't lyrics.
            if(text.isEmpty()||text.matches("^(作词|作曲|编曲|制作人|词|曲|Lyrics|Composer|Arranger)\\s*[:：].*"))continue;
            JSONObject l=new JSONObject().put("t",Math.round(e.getKey()*100)/100.0).put("text",text);
            String t=tr.get(e.getKey());
            if(t!=null&&!t.trim().isEmpty()&&!t.trim().equals(text))l.put("tr",t.trim());
            out.put(l);
        }
        return out;
    }
    static java.util.TreeMap<Double,String> stamps(String lrc){
        java.util.TreeMap<Double,String> m=new java.util.TreeMap<>();
        for(String line:lrc.split("\\r?\\n")){
            Matcher s=STAMP.matcher(line);List<Double> times=new ArrayList<>();int end=0;
            while(s.find()&&s.start()==end){times.add(Integer.parseInt(s.group(1))*60+Double.parseDouble(s.group(2).replace(':','.')));end=s.end();}
            if(times.isEmpty())continue;
            String text=line.substring(end);
            for(double t:times)m.put(t,text);
        }
        return m;
    }
    static JSONArray plainLines(String plain) throws Exception {
        JSONArray out=new JSONArray();
        for(String l:plain.split("\\r?\\n"))if(!l.trim().isEmpty())out.put(new JSONObject().put("t",-1).put("text",l.trim()));
        return out;
    }

    static String clean(String title){return title==null?"":title.replaceAll("[\\(\\[（【].*?[\\)\\]）】]","").replaceAll("\\s+-\\s+.*$","").trim();}
    static String enc(String s){try{return URLEncoder.encode(s==null?"":s,"UTF-8");}catch(Exception e){return "";}}

    static String http(String url,String header) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(8000);c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent",AGENT);
        if(header!=null){int i=header.indexOf(':');c.setRequestProperty(header.substring(0,i).trim(),header.substring(i+1).trim());}
        int code=c.getResponseCode();
        if(code>=400)throw new Exception("Lyrics service answered "+code);
        try(InputStream in=c.getInputStream()){
            ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[16384];int n;
            while((n=in.read(buf))>0)b.write(buf,0,n);
            return new String(b.toByteArray(),StandardCharsets.UTF_8);
        }
    }
}
