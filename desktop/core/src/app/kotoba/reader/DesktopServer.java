package app.kotoba.reader;

import android.content.Context;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Kotoba's dictionary and vocabulary core on the Mac: the Android app's own classes (Library, Store, Yomitan…) behind a
 * local web server that serves the same interface. Only 127.0.0.1 is served, and every request needs the session cookie
 * set when the Mac app first loads the page, so other pages in a browser can't use it.
 *
 * Usage: java -cp … app.kotoba.reader.DesktopServer <data dir> <android assets dir> <desktop web dir>
 * Prints "KOTOBA PORT <n> TOKEN <t>" once listening.
 */
public class DesktopServer {
    static final String ENTRY_CSP="default-src 'self' data:; script-src 'none'; style-src 'self' 'unsafe-inline' data:; img-src 'self' data:; media-src 'self' data:; font-src 'self' data:; object-src 'none'; base-uri 'none'";

    final File data,assets,web;
    final Routes routes;
    final Library library;
    final String token;
    final List<OutputStream> listeners=new CopyOnWriteArrayList<>();
    final Sync sync;
    final java.util.Map<String,Long> installLinks=new java.util.concurrent.ConcurrentHashMap<>();
    volatile int port;volatile boolean helperRunning;

    DesktopServer(File data,File assets,File web){
        this.data=data;this.assets=assets;this.web=web;
        Context context=new Context(data,assets);
        Store store=new Store(context);
        library=new Library(context,uri->FileChannel.open(Path.of(uri.startsWith("file://")?uri.substring(7):uri),StandardOpenOption.READ));
        WordLists wordlists=new WordLists(store.db);
        Extras extras=new Extras(library,new File(data,"extras"));
        routes=new Routes(library,store,wordlists,extras,new Routes.Host(){
            @Override public void event(String type,Object payload){DesktopServer.this.event(type,payload);}
        });
        sync=new Sync(store);
        routes.books=new Books(context,store.db);
        routes.ocr=new Ocr(context,store.db);
        routes.comics=new Comics(context,store.db,uri->FileChannel.open(Path.of(uri.startsWith("file://")?uri.substring(7):uri),StandardOpenOption.READ));
        byte[] t=new byte[18];new SecureRandom().nextBytes(t);
        StringBuilder b=new StringBuilder();for(byte x:t)b.append(String.format("%02x",x));
        token=b.toString();
    }

    /** Events to the page over Server-Sent Events (import progress, toasts). */
    void event(String type,Object payload){
        String json;
        try{json=new JSONObject().put("type",type).put("data",payload==null?JSONObject.NULL:payload).toString();}catch(Exception e){return;}
        byte[] msg=("data: "+json.replace("\n","\\n")+"\n\n").getBytes(StandardCharsets.UTF_8);
        for(OutputStream o:listeners){
            try{synchronized(o){o.write(msg);o.flush();}}catch(IOException e){listeners.remove(o);}
        }
    }

    Object route(String route,JSONObject d) throws Exception {
        switch(route){
            case "library.scan":{
                // Desktop folders are plain paths.
                File root=new File(d.getString("tree"));
                JSONArray found=new JSONArray();
                routes.scanFiles(root,"",0,found,library.dictionaries(),uri->FileChannel.open(Path.of(uri),StandardOpenOption.READ));
                return found;
            }
            case "library.relink":return new JSONObject().put("fixed",0).put("missing",0);
            case "library.folders":return new JSONArray();
            case "mihon.status":return mihonStatus();
            case "mihon.setFolder":routes.store.setSetting("mihon_folder",d.getString("path"));return mihonRefresh(true);
            case "mihon.refresh":return mihonRefresh(true);
            case "sync.status":return syncStatus();
            case "sync.setFolder":routes.store.setSetting("sync_folder",d.getString("path"));return syncNow();
            case "sync.now":return syncNow();
            case "helper.link":{
                // A one-time link for installing the Firefox helper (see /kotoba-video.user.js).
                byte[] r=new byte[12];new SecureRandom().nextBytes(r);
                StringBuilder b=new StringBuilder();for(byte v:r)b.append(String.format("%02x",v));
                installLinks.put(b.toString(),System.currentTimeMillis());
                return new JSONObject().put("url","http://127.0.0.1:"+port+"/kotoba-video.user.js?once="+b).put("helperPort",HELPER_PORT).put("helperRunning",helperRunning);
            }
            case "video.tracks":return videoTracks(new File(d.getString("path")));
            case "video.sub":return new JSONObject().put("text",videoSub(new File(d.getString("path")),d.optInt("stream",-1),d.optString("file","")));
            case "video.ocr":return videoOcr(new File(d.getString("path")),d.getDouble("time"),d.optString("lang","ja"));
            case "wordlist.importPath":{
                File f=new File(d.getString("path"));
                return routes.wordlists.importText(f.getName(),new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));
            }
            case "restore":{
                JSONObject backup=new JSONObject(Files.readString(Path.of(d.getString("path"))));
                return routes.store.restore(backup);
            }
            case "export":{
                // Exports go to ~/Downloads (the Mac app reveals them).
                String[] r=routes.export(d.getString("kind"),d.optJSONObject("data")==null?new JSONObject():d.getJSONObject("data"));
                if(r==null)throw new Exception("Unknown export");
                String name=d.getString("name").replaceAll("[/\\\\:]","_");
                File dir=new File(System.getProperty("user.home"),"Downloads");dir.mkdirs();
                File out=new File(dir,name);
                for(int i=2;out.exists();i++){int dot=name.lastIndexOf('.');out=new File(dir,dot>0?name.substring(0,dot)+" "+i+name.substring(dot):name+" "+i);}
                Files.writeString(out.toPath(),r[0]);
                return new JSONObject().put("path",out.getPath());
            }
            default:return routes.route(route,d);
        }
    }

    // ---------- Mihon ----------

    /** The Mihon folder mirrored from the phone (Syncthing → T7): downloads/<source>/<series>/<chapter>.cbz and autobackup/. */
    File mihonFolder(){
        String p=routes.store.setting("mihon_folder","/Volumes/T7/Mihon");
        return new File(p);
    }

    JSONObject mihonStatus() throws Exception {
        File root=mihonFolder();
        return new JSONObject().put("folder",root.getPath()).put("available",new File(root,"downloads").isDirectory())
            .put("backup",routes.store.setting("mihon_backup","")).put("scanned",Long.parseLong(routes.store.setting("mihon_scanned","0")));
    }

    /**
     * Picks up new series and chapters, then the newest Mihon backup if it hasn't been read yet (titles, categories,
     * read chapters). Quietly does nothing while the drive isn't plugged in.
     */
    synchronized JSONObject mihonRefresh(boolean force) throws Exception {
        File root=mihonFolder(),downloads=new File(root,"downloads");
        if(!downloads.isDirectory())return mihonStatus().put("changed",false);
        long newest=newestChange(downloads,0);
        boolean changed=false;
        if(force||newest>Long.parseLong(routes.store.setting("mihon_scanned","0"))){
            JSONObject r=routes.comics.scanFiles(downloads);
            routes.store.setSetting("mihon_scanned",Long.toString(Math.max(newest,System.currentTimeMillis())));
            changed=r.optInt("chapters")>0;
        }
        File[] backups=new File(root,"autobackup").listFiles((dir,n)->n.endsWith(".tachibk"));
        if(backups!=null&&backups.length>0){
            java.util.Arrays.sort(backups,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
            File b=backups[0];
            String key=b.getName()+"|"+b.lastModified();
            if(force||!key.equals(routes.store.setting("mihon_backup",""))){
                routes.comics.importBackup(MihonBackup.parse(Files.readAllBytes(b.toPath())));
                routes.store.setSetting("mihon_backup",key);changed=true;
            }
        }
        if(changed)event("comics-added",new JSONObject().put("series",routes.comics.list().length()).put("chapters",0).put("quiet",true));
        return mihonStatus().put("changed",changed);
    }

    /** Latest modification under the downloads folder (series and chapter level only; cheap). */
    static long newestChange(File dir,int depth){
        long m=dir.lastModified();
        if(depth<2){File[] kids=dir.listFiles(File::isDirectory);if(kids!=null)for(File k:kids)m=Math.max(m,newestChange(k,depth+1));}
        return m;
    }

    // ---------- sync ----------

    JSONObject syncStatus() throws Exception {
        String folder=routes.store.setting("sync_folder","");
        JSONArray devices=new JSONArray();
        File[] files=folder.isEmpty()?null:new File(folder).listFiles((dir,n)->n.matches("kotoba-[0-9a-f]+\\.json")&&!n.equals(sync.fileName()));
        if(files!=null)for(File f:files)devices.put(new JSONObject().put("file",f.getName()).put("modified",f.lastModified()));
        return new JSONObject().put("folder",folder).put("device",sync.deviceId()).put("name",deviceName())
            .put("last",Long.parseLong(routes.store.setting("sync_last","0"))).put("devices",devices);
    }

    static String deviceName(){
        try{String h=InetAddress.getLocalHost().getHostName().replaceFirst("\\.local$","");return h.isEmpty()?"Mac":h;}catch(Exception e){return "Mac";}
    }

    synchronized JSONObject syncNow() throws Exception {
        String path=routes.store.setting("sync_folder","");
        if(path.isEmpty())return new JSONObject().put("skipped",true);
        File dir=new File(path);
        if(!dir.isDirectory())throw new Exception("The sync folder isn’t there: "+path);
        JSONObject r=sync.run(new Sync.Folder(){
            @Override public java.util.List<String> names(){String[] n=dir.list();return n==null?new java.util.ArrayList<>():java.util.Arrays.asList(n);}
            @Override public long modified(String name){return new File(dir,name).lastModified();}
            @Override public byte[] read(String name) throws Exception {return Files.readAllBytes(new File(dir,name).toPath());}
            @Override public void write(String name,byte[] data) throws Exception {
                // Written beside and renamed, so a sync tool never picks up half a file.
                File tmp=new File(dir,"."+name+".tmp");Files.write(tmp.toPath(),data);
                Files.move(tmp.toPath(),new File(dir,name).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
        },deviceName(),library.syncDicts());
        if(r.optBoolean("changed"))event("synced",r);
        return r.put("status",syncStatus());
    }

    // ---------- video subtitles ----------

    static final String[] SUB_EXT={"srt","vtt","ass","ssa"};
    static String tool(String name){
        for(String dir:new String[]{"/opt/homebrew/bin","/usr/local/bin","/usr/bin"}){File f=new File(dir,name);if(f.canExecute())return f.getPath();}
        return name;
    }

    /**
     * Subtitles for a video: files beside it (Name.srt, Name.zh-HK.srt, subtitles/Name.srt…) and the text tracks
     * inside it (ffprobe). Each has a label and a language guess; picture-based tracks (PGS) can't be read as text.
     */
    JSONObject videoTracks(File video) throws Exception {
        JSONArray subs=new JSONArray();
        String base=video.getName().replaceFirst("\\.[^.]+$","");
        File dir=video.getParentFile();
        java.util.List<File> candidates=new java.util.ArrayList<>();
        for(File folder:new File[]{dir,new File(dir,"subtitles"),new File(dir,"Subtitles"),new File(dir,"subs"),new File(dir,"Subs")}){
            File[] files=folder.listFiles();if(files==null)continue;
            java.util.Arrays.sort(files);
            for(File f:files){
                String n=f.getName(),lower=n.toLowerCase(java.util.Locale.ROOT);
                if(!n.startsWith(base))continue;
                for(String ext:SUB_EXT)if(lower.endsWith("."+ext)){candidates.add(f);break;}
            }
        }
        for(File f:candidates){
            String middle=f.getName().substring(base.length()).replaceFirst("\\.[^.]+$","").replaceFirst("^[._ -]+","");
            subs.put(new JSONObject().put("kind","file").put("file",f.getPath()).put("label",(middle.isEmpty()?"Subtitles":middle)+" · "+f.getName().replaceFirst(".*\\.","").toUpperCase()+" file")
                .put("lang",langOf(middle)).put("generated",f.getParentFile().equals(dir)&&middle.toLowerCase().matches(".*(zh-hk|yue|qwen).*")));
        }
        try{
            Process p=new ProcessBuilder(tool("ffprobe"),"-v","error","-select_streams","s","-show_entries","stream=index,codec_name:stream_tags=language,title","-of","json",video.getPath()).redirectErrorStream(true).start();
            String out=new String(readAll(p.getInputStream()),StandardCharsets.UTF_8);p.waitFor();
            JSONArray streams=new JSONObject(out).optJSONArray("streams");
            if(streams!=null)for(int i=0;i<streams.length();i++){
                JSONObject st=streams.getJSONObject(i);JSONObject tags=st.optJSONObject("tags");
                String lang=tags==null?"":tags.optString("language",""),title=tags==null?"":tags.optString("title","");
                String codec=st.optString("codec_name");
                boolean text=codec.matches("subrip|ass|ssa|webvtt|mov_text|text");
                subs.put(new JSONObject().put("kind","stream").put("stream",st.getInt("index")).put("codec",codec).put("text",text)
                    .put("label",(title.isEmpty()?LANG_NAMES.getOrDefault(lang,lang.isEmpty()?"Track":lang):title)+" · built in"+(text?"":" (picture, can't be read)"))
                    .put("lang",langOf(lang+" "+title)));
            }
        }catch(Exception e){/* no ffprobe: files only */}
        return new JSONObject().put("subs",subs);
    }

    static final java.util.Map<String,String> LANG_NAMES=java.util.Map.ofEntries(
        java.util.Map.entry("eng","English"),java.util.Map.entry("jpn","日本語"),java.util.Map.entry("kor","한국어"),java.util.Map.entry("chi","中文"),java.util.Map.entry("zho","中文"),
        java.util.Map.entry("tha","ไทย"),java.util.Map.entry("vie","Tiếng Việt"),java.util.Map.entry("spa","Español"),java.util.Map.entry("ind","Indonesia"),java.util.Map.entry("rus","Русский"),
        java.util.Map.entry("yue","粵語"),java.util.Map.entry("fre","Français"),java.util.Map.entry("ger","Deutsch"),java.util.Map.entry("por","Português"),java.util.Map.entry("ara","العربية"));

    /** Kotoba's lookup language for a subtitle track: ja, zh, ko, th, ru, or "" (other). */
    static String langOf(String s){
        String x=s.toLowerCase(java.util.Locale.ROOT);
        if(x.matches(".*\\b(ja|jp|jpn|japanese)\\b.*|.*日本.*"))return "ja";
        if(x.matches(".*\\b(zh|chi|zho|yue|cmn|chs|cht|hk|tw|chinese|cantonese|mandarin)\\b.*|.*(zh-hk|zh-tw|zh-cn|中文|粵|繁|简).*"))return "zh";
        if(x.matches(".*\\b(ko|kor|korean)\\b.*|.*한국.*"))return "ko";
        if(x.matches(".*\\b(th|tha|thai)\\b.*"))return "th";
        if(x.matches(".*\\b(ru|rus|russian)\\b.*"))return "ru";
        if(x.matches(".*\\b(en|eng|english)\\b.*"))return "en";
        return "";
    }

    /** A subtitle track's text: a file as is, or a built-in track converted to SRT by ffmpeg (cached). */
    String videoSub(File video,int stream,String file) throws Exception {
        if(!file.isEmpty()){
            byte[] b=Files.readAllBytes(Path.of(file));
            return BookParser.decode(b)[0];
        }
        File cache=new File(data,"subcache");cache.mkdirs();
        String key=Integer.toHexString((video.getPath()+"|"+video.length()+"|"+video.lastModified()).hashCode())+"-"+stream+".srt";
        File out=new File(cache,key);
        if(!out.isFile()||out.length()==0){
            Process p=new ProcessBuilder(tool("ffmpeg"),"-v","error","-y","-i",video.getPath(),"-map","0:"+stream,"-f","srt",out.getPath()).redirectErrorStream(true).start();
            String err=new String(readAll(p.getInputStream()),StandardCharsets.UTF_8);
            if(p.waitFor()!=0){out.delete();throw new Exception("Couldn’t read that subtitle track"+(err.isEmpty()?"":": "+err.trim().split("\n")[0]));}
        }
        return Files.readString(out.toPath());
    }

    /** Read the lower part of a local video frame with the same OCR used for comic pages. */
    JSONObject videoOcr(File video,double time,String lang) throws Exception {
        if(!video.isFile())throw new IllegalArgumentException("Video file not found");
        if(!java.util.Set.of("ja","zh","ko","th","ru").contains(lang))lang="ja";
        if(!Double.isFinite(time)||time<0)throw new IllegalArgumentException("Invalid video position");
        Process p=new ProcessBuilder(tool("ffmpeg"),"-v","error","-ss",Double.toString(time),"-i",video.getPath(),
            "-an","-sn","-frames:v","1","-vf","crop=iw:ih*0.38:0:ih*0.62","-f","image2pipe","-vcodec","mjpeg","pipe:1")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        byte[] image;
        try(InputStream in=p.getInputStream()){image=readAll(in);}
        if(p.waitFor()!=0||image.length==0)throw new Exception("Couldn’t read this video frame");
        JSONObject recognized=routes.ocr.recognize(image,lang);
        JSONArray blocks=recognized.getJSONArray("blocks");
        StringBuilder text=new StringBuilder();
        for(int i=0;i<blocks.length();i++){
            String line=blocks.getJSONObject(i).optString("text","").trim();
            if(!line.isEmpty()){if(text.length()>0)text.append('\n');text.append(line);}
        }
        return new JSONObject().put("text",text.toString()).put("time",time);
    }

    // ---------- Firefox helper ----------

    /** The helper's fixed address (the main server's port changes every launch). 127.0.0.1 only. */
    static final int HELPER_PORT=47823;
    /** Requests the helper may make: looking words up and saving cards, nothing else. */
    static final java.util.Set<String> HELPER_ROUTES=java.util.Set.of("lookup","gloss.rec","freq","item.similar","item.save","folders","dicts");

    String helperKey(){
        String k=routes.store.setting("helper_key","");
        if(k.isEmpty()){byte[] r=new byte[24];new SecureRandom().nextBytes(r);StringBuilder b=new StringBuilder();for(byte v:r)b.append(String.format("%02x",v));k=b.toString();routes.store.setSetting("helper_key",k);}
        return k;
    }

    /**
     * POST /helper/<route> with the header X-Kotoba-Key: the userscript's requests from YouTube and GagaOOLala (through
     * Tampermonkey, so no browser page can make them). "show" opens a word in Kotoba's window.
     */
    void handleHelper(HttpExchange x) throws IOException {
        try{
            String path=x.getRequestURI().getPath();
            if(path.equals("/helper/ping")){send(x,200,"application/json","{\"data\":\"kotoba\"}".getBytes(StandardCharsets.UTF_8),null);return;}
            if(!x.getRequestMethod().equals("POST")||!helperKey().equals(x.getRequestHeaders().getFirst("X-Kotoba-Key"))){send(x,403,"text/plain",new byte[0],null);return;}
            String route=path.startsWith("/helper/")?path.substring(8):"";
            String body=new String(readAll(x.getRequestBody()),StandardCharsets.UTF_8).trim();
            JSONObject d=new JSONObject(body.isEmpty()?"{}":body);
            String reply;
            try{
                Object result;
                if(route.equals("show")){event("helper-show",new JSONObject().put("word",d.getString("word")));result=null;}
                else if(HELPER_ROUTES.contains(route))result=route(route,d);
                else throw new Exception("Not available to the helper: "+route);
                reply=new JSONObject().put("data",result==null?JSONObject.NULL:result).toString();
            }catch(Throwable e){reply=new JSONObject().put("error",e.getMessage()==null?e.toString():e.getMessage()).toString();}
            send(x,200,"application/json",reply.getBytes(StandardCharsets.UTF_8),null);
        }catch(Throwable e){try{send(x,500,"text/plain",new byte[0],null);}catch(IOException ignored){}}
    }

    boolean authorized(HttpExchange x){
        String cookie=x.getRequestHeaders().getFirst("Cookie");
        return cookie!=null&&cookie.contains("kotoba="+token);
    }

    void handle(HttpExchange x) throws IOException {
        try{
            String path=x.getRequestURI().getPath();
            // The Firefox helper, installed by Tampermonkey before it has a Kotoba cookie. It carries the helper key,
            // so it's only served through a one-time link the Video tab makes (helper.link), valid for ten minutes.
            if(path.equals("/kotoba-video.user.js")){
                String q=x.getRequestURI().getRawQuery();
                String once=q!=null&&q.startsWith("once=")?q.substring(5):"";
                Long made=installLinks.remove(once);
                File script=new File(web,"kotoba-video.user.js");
                if(made==null||System.currentTimeMillis()-made>600_000||!script.isFile()){send(x,404,"text/plain","This install link has expired. Make a new one in Kotoba › Video.".getBytes(StandardCharsets.UTF_8),null);return;}
                String js=Files.readString(script.toPath()).replace("__KOTOBA_KEY__",helperKey()).replace("__KOTOBA_PORT__",Integer.toString(HELPER_PORT));
                send(x,200,"text/javascript",js.getBytes(StandardCharsets.UTF_8),null);
                return;
            }
            // The Mac app opens /?t=<token> once; the cookie then covers every request, including the entry frames.
            if(path.equals("/")&&("t="+token).equals(x.getRequestURI().getRawQuery())){
                x.getResponseHeaders().add("Set-Cookie","kotoba="+token+"; Path=/; HttpOnly; SameSite=Strict");
            }else if(!authorized(x)){send(x,403,"text/plain","Open Kotoba from the app.".getBytes(StandardCharsets.UTF_8),null);return;}

            if(path.startsWith("/api/")&&x.getRequestMethod().equals("POST")){
                String route=path.substring(5);
                String body=new String(readAll(x.getRequestBody()),StandardCharsets.UTF_8);
                String reply;
                try{
                    Object result=route(route,body.isEmpty()?new JSONObject():new JSONObject(body));
                    reply=new JSONObject().put("data",result==null?JSONObject.NULL:result).toString();
                }catch(Throwable e){
                    if(!(e instanceof Exception)||e.getMessage()==null)e.printStackTrace();
                    reply=new JSONObject().put("error",e.getMessage()==null?e.toString():e.getMessage()).toString();
                }
                send(x,200,"application/json",reply.getBytes(StandardCharsets.UTF_8),null);
                return;
            }
            if(path.equals("/events")){
                x.getResponseHeaders().add("Content-Type","text/event-stream");
                x.getResponseHeaders().add("Cache-Control","no-store");
                x.sendResponseHeaders(200,0);
                OutputStream o=x.getResponseBody();
                o.write(": ok\n\n".getBytes(StandardCharsets.UTF_8));o.flush();
                listeners.add(o);
                return;// kept open
            }
            if(path.startsWith("/d/")){
                String rest=path.substring(3);int slash=rest.indexOf('/');
                if(slash<0){send(x,404,"text/plain",new byte[0],null);return;}
                Object[] f=routes.dictFile(Long.parseLong(rest.substring(0,slash)),rest.substring(slash+1));
                if(f==null){send(x,404,"text/plain",("Missing: "+rest).getBytes(StandardCharsets.UTF_8),null);return;}
                send(x,200,(String)f[0],(byte[])f[1],f[2]!=null?ENTRY_CSP:null);
                return;
            }
            if(path.startsWith("/comic/")){
                Object[] f=routes.comicFile(path.substring(7));
                if(f==null){send(x,404,"text/plain",new byte[0],null);return;}
                send(x,200,(String)f[1],(byte[])f[0],null);
                return;
            }
            if(path.startsWith("/book/")){
                Object[] f=routes.bookFile(path.substring(6));
                if(f==null){send(x,404,"text/plain",new byte[0],null);return;}
                String mime=(String)f[1];
                send(x,200,mime,(byte[])f[0],mime.contains("html")?ENTRY_CSP:null);
                return;
            }
            if(path.startsWith("/file/")){
                // Local files the player needs (subtitles next to a video), by absolute path.
                File f=new File(URLDecoder.decode(path.substring(5),StandardCharsets.UTF_8));
                if(!f.isFile()){send(x,404,"text/plain",new byte[0],null);return;}
                send(x,200,Library.mime(f.getName()).equals("application/octet-stream")?"text/plain":Library.mime(f.getName()),Files.readAllBytes(f.toPath()),null);
                return;
            }
            String asset=path.equals("/")?"index.html":path.substring(1);
            if(!asset.matches("[a-zA-Z0-9_.-]+")){send(x,404,"text/plain",new byte[0],null);return;}
            File f=new File(web,asset);
            if(!f.isFile())f=new File(assets,asset);
            if(!f.isFile()){send(x,404,"text/plain",new byte[0],null);return;}
            byte[] bytes=Files.readAllBytes(f.toPath());
            String mime=asset.endsWith(".js")?"text/javascript":asset.endsWith(".css")?"text/css":asset.endsWith(".svg")?"image/svg+xml":asset.endsWith(".woff2")?"font/woff2":"text/html";
            if(asset.equals("index.html")){
                // The desktop bridge (Kotoba.call → fetch) loads before the app's own scripts.
                String html=new String(bytes,StandardCharsets.UTF_8);
                int at=html.indexOf("<script");
                String inject="<link rel=\"stylesheet\" href=\"/desktop.css\"><script src=\"/hover-modifier.js\"></script><script src=\"/desktop.js\"></script>";
                html=at<0?html+inject:html.substring(0,at)+inject+html.substring(at);
                html=html.replace("</body>","<script src=\"/desktop-after.js\"></script></body>");
                bytes=html.getBytes(StandardCharsets.UTF_8);
            }
            send(x,200,mime,bytes,null);
        }catch(Throwable e){
            e.printStackTrace();
            try{send(x,500,"text/plain",String.valueOf(e.getMessage()).getBytes(StandardCharsets.UTF_8),null);}catch(IOException ignored){}
        }
    }

    static void send(HttpExchange x,int status,String mime,byte[] bytes,String csp) throws IOException {
        x.getResponseHeaders().add("Content-Type",mime+(mime.startsWith("text/")||mime.contains("json")||mime.contains("javascript")?"; charset=utf-8":""));
        x.getResponseHeaders().add("Cache-Control",status==200&&!mime.startsWith("text/html")&&!mime.equals("application/json")&&!mime.contains("javascript")?"max-age=3600":"no-store");
        x.getResponseHeaders().add("X-Content-Type-Options","nosniff");
        if(csp!=null)x.getResponseHeaders().add("Content-Security-Policy",csp);
        x.sendResponseHeaders(status,bytes.length==0?-1:bytes.length);
        if(bytes.length>0)try(OutputStream o=x.getResponseBody()){o.write(bytes);}
        else x.close();
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[65536];int n;
        while((n=in.read(b))>0)out.write(b,0,n);
        return out.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        // The core runs behind a native Swift window; Java only needs image decoding, not an AWT app.
        System.setProperty("java.awt.headless","true");
        File data=new File(args.length>0?args[0]:System.getProperty("user.home")+"/Library/Application Support/Kotoba");
        File assets=new File(args.length>1?args[1]:"android/assets");
        File web=new File(args.length>2?args[2]:"desktop/web");
        DesktopServer s=new DesktopServer(data,assets,web);
        HttpServer http=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),args.length>3?Integer.parseInt(args[3]):0),64);
        http.createContext("/",s::handle);
        http.setExecutor(Executors.newFixedThreadPool(16));
        http.start();
        // Sync every 30 seconds while the app is open (only reads/writes when something changed).
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread th=new Thread(r,"sync");th.setDaemon(true);return th;})
            .scheduleWithFixedDelay(()->{
                try{s.syncNow();}catch(Exception e){System.err.println("sync: "+e.getMessage());}
                try{s.mihonRefresh(false);}catch(Exception e){System.err.println("mihon: "+e.getMessage());}
            },5,30,java.util.concurrent.TimeUnit.SECONDS);
        s.port=http.getAddress().getPort();
        s.routes.upgradeIndexesLater();
        try{
            HttpServer helper=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),HELPER_PORT),16);
            helper.createContext("/helper/",s::handleHelper);
            helper.setExecutor(Executors.newFixedThreadPool(4));
            helper.start();s.helperRunning=true;
        }catch(IOException e){System.err.println("helper port "+HELPER_PORT+" is busy: "+e.getMessage());}
        System.out.println("KOTOBA PORT "+http.getAddress().getPort()+" TOKEN "+s.token);
        System.out.flush();
        // The Mac app holds our stdin open; when it quits or crashes, stdin closes and the core goes with it.
        if(System.getenv("KOTOBA_STANDALONE")==null){
            Thread watch=new Thread(()->{
                try{while(System.in.read()>=0){}}catch(IOException ignored){}
                System.exit(0);
            },"parent-watch");
            watch.setDaemon(true);watch.start();
        }
    }
}
