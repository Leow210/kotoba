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
            JSONObject c=cached(title,artist);
            if(c!=null)return c;
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

    /** This song's cached lyrics, by artist and title, or by title alone (a .lrc imported without an artist). */
    JSONObject cached(String title,String artist) throws Exception {
        for(String k:new String[]{key(title,artist),key(title,"")}){
            JSONArray hit=Store.rows(store.db,"SELECT data FROM lyrics_cache WHERE key=?",k);
            if(hit.length()>0)return new JSONObject(hit.getJSONObject(0).getString("data")).put("cached",true).put("title",title).put("artist",artist);
        }
        return null;
    }

    void save(String key,JSONObject r){
        android.content.ContentValues v=new android.content.ContentValues();
        v.put("key",key);v.put("data",r.toString());v.put("fetched",System.currentTimeMillis());
        store.db.insertWithOnConflict("lyrics_cache",null,v,android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
    }

    /**
     * A .lrc (or plain text) file becomes a song's lyrics, offline. Title and artist come from its [ti:] and [ar:]
     * tags, else from the file name ("Artist - Title.lrc", or just "Title.lrc"). Kept under artist|title and, so a
     * player that spells the artist another way still finds it, under the title alone.
     */
    public JSONObject importLrc(String fileName,String text) throws Exception {
        if(text.startsWith("\uFEFF"))text=text.substring(1);
        String name=fileName==null?"":fileName.replaceAll("(?i)\\.(lrc|txt)$","").trim();
        String title=tag(text,"ti"),artist=tag(text,"ar");
        if(title.isEmpty()){
            int d=name.indexOf(" - ");
            if(d>0){if(artist.isEmpty())artist=name.substring(0,d).trim();title=name.substring(d+3).trim();}else title=name;
        }
        if(title.isEmpty())throw new Exception("This file has no title (name it “Artist - Title.lrc”)");
        JSONArray lines=parseLrc(text,null);
        boolean synced=lines.length()>0;
        if(!synced)lines=plainLines(text.replaceAll("(?m)^\\[[a-zA-Z]+:[^\\]]*\\]\\s*$","").replaceAll("\\[[^\\]]*\\]",""));
        if(lines.length()==0)throw new Exception("No lyrics in this file");
        JSONObject r=new JSONObject().put("source","File").put("synced",synced).put("lines",lines).put("title",title).put("artist",artist);
        save(key(title,artist),r);
        if(!artist.isEmpty())save(key(title,""),r);
        return r;
    }
    static String tag(String lrc,String name){
        java.util.regex.Matcher m=Pattern.compile("(?m)^\\[(?i:"+name+"):([^\\]]*)\\]").matcher(lrc);
        return m.find()?m.group(1).trim():"";
    }

    static final java.util.concurrent.atomic.AtomicBoolean prefetching=new java.util.concurrent.atomic.AtomicBoolean(false);
    /**
     * Fetch and cache lyrics for a list of songs ("Artist - Title" or just "Title", one per line) while online, so they
     * show offline. Songs already cached are skipped. progress gets {done,total,found,cached,missing[]} after each song.
     */
    public void prefetch(String list,java.util.function.Consumer<JSONObject> progress){
        if(!prefetching.compareAndSet(false,true))return;
        try{
            List<String[]> songs=new ArrayList<>();
            for(String l:list.split("\\r?\\n")){
                l=l.trim();if(l.isEmpty())continue;
                int d=l.indexOf(" - ");if(d<0)d=l.indexOf(" – ");
                songs.add(d>0?new String[]{l.substring(d+3).trim(),l.substring(0,d).trim(),l}:new String[]{l,"",l});
            }
            int done=0,found=0,had=0;JSONArray missing=new JSONArray();
            for(String[] sg:songs){
                try{
                    if(cached(sg[0],sg[1])!=null)had++;
                    else{
                        JSONObject r=get(sg[0],sg[1],"",0,false);
                        if(r.getJSONArray("lines").length()>0)found++;else missing.put(sg[2]);
                        Thread.sleep(300);
                    }
                }catch(Exception e){missing.put(sg[2]);}
                done++;
                try{progress.accept(new JSONObject().put("done",done).put("total",songs.size()).put("found",found).put("cached",had).put("missing",missing));}catch(Exception ignored){}
            }
        }finally{prefetching.set(false);}
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
        // Another artist: out, unless the names are in different scripts (Atom Chanakan / อะตอม ชนกันต์ may be the same person).
        if(!similar(candArtist,artist))d+=scripts(candArtist).equals(scripts(artist))?10:2;
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
    /** Lyrics in a non-Latin script when the title or artist is written in one (kana/kanji, Hangul, Thai, Cyrillic). */
    static boolean fits(JSONObject r,String title,String artist){
        String want=scripts(title+" "+artist);
        if(want.isEmpty())return true;
        StringBuilder text=new StringBuilder();
        JSONArray ls=r.optJSONArray("lines");
        if(ls!=null)for(int i=0;i<ls.length();i++)text.append(ls.optJSONObject(i).optString("text")).append('\n');
        // Any of those scripts will do: 二十三 (NetEase's title for IU's 스물셋) has Korean lyrics. What's ruled out is a
        // translation into a Latin-script language (a Vietnamese version of 米津玄師's Lemon).
        return !scripts(text.toString()).isEmpty();
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

    // NetEase answers its web clients: a browser's agent and referrer. (Its older /api/search/get/web now returns an
    // encrypted blob; /api/cloudsearch/pc still answers in plain JSON.)
    static final String NE_HEADERS="Referer: https://music.163.com/\nUser-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15";
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
        JSONObject r=new JSONObject(http("https://music.163.com/api/cloudsearch/pc?type=1&limit=8&s="+enc(q),NE_HEADERS));
        List<JSONObject> out=new ArrayList<>();
        JSONArray songs=r.optJSONObject("result")==null?null:r.getJSONObject("result").optJSONArray("songs");
        if(songs==null)return out;
        for(int i=0;i<songs.length();i++){
            JSONObject s=songs.getJSONObject(i);
            StringBuilder artists=new StringBuilder();
            JSONArray ar=s.optJSONArray("ar");if(ar==null)ar=s.optJSONArray("artists");
            if(ar!=null)for(int j=0;j<ar.length();j++){if(j>0)artists.append(", ");artists.append(ar.getJSONObject(j).optString("name"));}
            out.add(new JSONObject().put("source","netease").put("id",s.getLong("id")).put("title",s.optString("name")).put("artist",artists.toString())
                .put("duration",s.optDouble("dt",s.optDouble("duration",0))/1000.0).put("synced",true));
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

    // ---------- a YouTube / YouTube Music playlist as a list of songs ----------

    static final String YT_HEADERS="User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15\nAccept-Language: en\nCookie: CONSENT=YES+1; SOCS=CAI";
    /**
     * The songs of a public or unlisted playlist (a music.youtube.com or youtube.com link, or the list id), one
     * "Artist - Title" per line. Read from the playlist's page and its continuations; a private playlist can't be read
     * (make it unlisted, or paste its songs by hand).
     */
    public String playlist(String link) throws Exception {
        java.util.regex.Matcher m=Pattern.compile("[?&]list=([A-Za-z0-9_-]+)").matcher(link);
        String id=m.find()?m.group(1):link.trim();
        if(!id.matches("[A-Za-z0-9_-]{10,}"))throw new Exception("That doesn’t look like a playlist link");
        String html=http("https://www.youtube.com/playlist?list="+id,YT_HEADERS);
        int a=html.indexOf("var ytInitialData = ");
        if(a<0)throw new Exception("Couldn’t read that playlist (is it private?)");
        a+=20;int b=html.indexOf(";</script>",a);
        JSONObject data=new JSONObject(html.substring(a,b));
        Matcher vm=Pattern.compile("\"INNERTUBE_CONTEXT_CLIENT_VERSION\":\"([^\"]+)\"").matcher(html);
        String ver=vm.find()?vm.group(1):"2.20250101.00.00";
        java.util.LinkedHashSet<String> songs=new java.util.LinkedHashSet<>();
        String token=null;
        for(int page=0;page<30;page++){
            int before=songs.size();
            String[] tok=new String[1];
            collectSongs(data,songs,tok);
            token=tok[0];
            if(token==null||songs.size()==before&&page>0)break;
            data=new JSONObject(post("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",
                new JSONObject().put("context",new JSONObject().put("client",new JSONObject().put("clientName","WEB").put("clientVersion",ver).put("hl","en"))).put("continuation",token).toString(),YT_HEADERS));
        }
        if(songs.isEmpty())throw new Exception("No songs found in that playlist (is it private?)");
        return String.join("\n",songs);
    }
    static void collectSongs(Object o,java.util.Set<String> out,String[] token) throws Exception {
        if(o instanceof JSONArray){JSONArray a=(JSONArray)o;for(int i=0;i<a.length();i++)collectSongs(a.get(i),out,token);return;}
        if(!(o instanceof JSONObject))return;
        JSONObject j=(JSONObject)o;
        JSONObject pv=j.optJSONObject("playlistVideoRenderer");
        if(pv!=null){
            JSONArray t=pv.optJSONObject("title")==null?null:pv.getJSONObject("title").optJSONArray("runs");
            JSONObject by=pv.optJSONObject("shortBylineText");
            JSONArray br=by==null?null:by.optJSONArray("runs");
            if(t!=null&&t.length()>0)addSong(out,t.getJSONObject(0).optString("text"),br!=null&&br.length()>0?br.getJSONObject(0).optString("text"):"");
        }
        JSONObject lv=j.optJSONObject("lockupViewModel");
        if(lv!=null&&lv.optJSONObject("metadata")!=null){
            JSONObject md=lv.getJSONObject("metadata").optJSONObject("lockupMetadataViewModel");
            if(md!=null&&md.optJSONObject("title")!=null){
                String by="";
                try{by=md.getJSONObject("metadata").getJSONObject("contentMetadataViewModel").getJSONArray("metadataRows").getJSONObject(0).getJSONArray("metadataParts").getJSONObject(0).getJSONObject("text").optString("content");}catch(Exception ignored){}
                addSong(out,md.getJSONObject("title").optString("content"),by);
            }
        }
        for(String k:new String[]{"continuationItemRenderer","continuationItemViewModel"}){
            JSONObject c=j.optJSONObject(k);
            if(c!=null&&token[0]==null){
                java.util.regex.Matcher m=Pattern.compile("\"token\":\"([^\"]+)\"").matcher(c.toString());
                if(m.find())token[0]=m.group(1);
            }
        }
        java.util.Iterator<String> it=j.keys();
        while(it.hasNext()){String k=it.next();if(!k.equals("playlistVideoRenderer")&&!k.equals("lockupViewModel"))collectSongs(j.get(k),out,token);}
    }
    /** "Artist - Topic" channels are the artist; a title that already starts with "Artist - " is kept as it is. */
    static void addSong(java.util.Set<String> out,String title,String channel){
        title=title.trim();if(title.isEmpty()||title.equals("[Private video]")||title.equals("[Deleted video]"))return;
        String artist=channel.replaceAll("(?i)\\s*-\\s*Topic$","").replaceAll("(?i)VEVO$","").trim();
        out.add(title.contains(" - ")||artist.isEmpty()||title.toLowerCase().contains(artist.toLowerCase())?title:artist+" - "+title);
    }

    static String post(String url,String json,String header) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(8000);c.setReadTimeout(15000);c.setRequestMethod("POST");c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/json");
        if(header!=null)for(String h:header.split("\n")){int i=h.indexOf(':');if(i>0)c.setRequestProperty(h.substring(0,i).trim(),h.substring(i+1).trim());}
        try(java.io.OutputStream o=c.getOutputStream()){o.write(json.getBytes(StandardCharsets.UTF_8));}
        if(c.getResponseCode()>=400)throw new Exception("YouTube answered "+c.getResponseCode());
        try(InputStream in=c.getInputStream()){
            ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[16384];int n;
            while((n=in.read(buf))>0)b.write(buf,0,n);
            return new String(b.toByteArray(),StandardCharsets.UTF_8);
        }
    }

    static String http(String url,String header) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(8000);c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent",AGENT);
        if(header!=null)for(String h:header.split("\n")){int i=h.indexOf(':');if(i>0)c.setRequestProperty(h.substring(0,i).trim(),h.substring(i+1).trim());}
        int code=c.getResponseCode();
        if(code>=400)throw new Exception("Lyrics service answered "+code);
        try(InputStream in=c.getInputStream()){
            ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[16384];int n;
            while((n=in.read(buf))>0)b.write(buf,0,n);
            return new String(b.toByteArray(),StandardCharsets.UTF_8);
        }
    }
}
