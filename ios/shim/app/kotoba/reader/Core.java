package app.kotoba.reader;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The iPad app's entry into the shared core: the same wiring as the Mac's DesktopServer, without a server. The Swift
 * shell calls {@link #call} for each request the interface makes (what the Mac sends to /api/<route>) and {@link #file}
 * for the pages, pictures and book chapters it asks for by address. The routes the Mac answers itself (folders, sync,
 * exports) are answered here the same way, over paths the shell has made readable.
 */
public final class Core {
    /** Events for the page (import progress, toasts): the JSON the interface's window.__event takes. */
    public interface Listener { void event(String json); }

    /** Apple's text recognizer, supplied by the shell: the JSON {w,h,lines:[{x,y,w,h,text,conf}]} for an image, or throws. */
    public interface Recognizer { String recognize(byte[] image,String lang) throws Exception; }

    private final Routes routes;
    private final Listener listener;
    private final Store store;
    private final Library library;
    private final Sync sync;
    private final String deviceName;

    public Core(String dataDir,String assetsDir,String deviceName,Listener listener){
        this.listener=listener;this.deviceName=deviceName;
        File data=new File(dataDir);
        int lib=dataDir.indexOf("/Library/");
        if(lib>0)container=dataDir.substring(0,lib);
        Context context=new Context(data,new File(assetsDir));
        store=new Store(context);
        library=new Library(context,Core::open);
        WordLists wordlists=new WordLists(store.db);
        Extras extras=new Extras(library,new File(data,"extras"));
        routes=new Routes(library,store,wordlists,extras,new Routes.Host(){
            @Override public void event(String type,Object payload){Core.this.event(type,payload);}
        });
        sync=new Sync(store);
        routes.books=new Books(context,store.db);
        routes.ocr=new Ocr(context,store.db);
        routes.listening=new Listening(new File(data,"listening"));
        routes.comics=new Comics(context,store.db,Core::open);
    }

    static java.nio.channels.FileChannel open(String uri) throws java.io.IOException {
        return new FileInputStream(current(uri.startsWith("file://")?uri.substring(7):uri)).getChannel();
    }

    /** This install's app folder (…/Data/Application/<id>); see current(). */
    static String container="";
    private static final java.util.regex.Pattern OLD_CONTAINER=java.util.regex.Pattern.compile("^(?:/private)?/var/mobile/Containers/Data/Application/[0-9A-Fa-f-]{36}(/.*)$");

    /**
     * A file saved by path under an earlier install, found under this one. iOS can give the app a new folder
     * (…/Data/Application/<new id>) when it's reinstalled, which left dictionaries imported in place (大辞林 and the other
     * MDX files in Documents) pointing at a folder that no longer exists.
     */
    static String current(String path){
        if(container.isEmpty()||new File(path).exists())return path;
        java.util.regex.Matcher m=OLD_CONTAINER.matcher(path);
        if(!m.matches())return path;
        String moved=container+m.group(1);
        return new File(moved).exists()?moved:path;
    }

    /** Installs Apple's recognizer for comic and screen text (the first reading; every language it knows). */
    public void setRecognizer(final Recognizer recognizer){
        Ocr.external=new Ocr.LineReader(){
            public boolean handles(String lang){return recognizer!=null&&!"paddle".equals(store.setting("ocr_engine",""));}
            public List<Ocr.Line> read(byte[] image,String lang,int[] size) throws Exception {
                JSONObject r=new JSONObject(recognizer.recognize(image,lang));
                size[0]=(int)Math.round(r.getDouble("w"));size[1]=(int)Math.round(r.getDouble("h"));
                List<Ocr.Line> lines=new ArrayList<>();
                JSONArray ls=r.getJSONArray("lines");
                for(int i=0;i<ls.length();i++){
                    JSONObject o=ls.getJSONObject(i);
                    Ocr.Line l=new Ocr.Line();
                    l.x1=(float)o.getDouble("x");l.y1=(float)o.getDouble("y");l.x2=l.x1+(float)o.getDouble("w");l.y2=l.y1+(float)o.getDouble("h");
                    l.text=o.getString("text");l.conf=(float)o.getDouble("conf");
                    lines.add(l);
                }
                return lines;
            }
        };
    }

    void event(String type,Object payload){
        try{listener.event(new JSONObject().put("type",type).put("data",payload==null?JSONObject.NULL:payload).toString());}catch(Exception ignored){}
    }

    /** One request from the interface: a route and its JSON body; the reply is {"data":…} or {"error":…}. */
    public String call(String route,String body){
        try{
            JSONObject d=body==null||body.isEmpty()?new JSONObject():new JSONObject(body);
            Object result=own(route,d);
            if(result==NONE)result=routes.route(route,d);
            return new JSONObject().put("data",result==null?JSONObject.NULL:result).toString();
        }catch(Throwable e){
            // "trace" (where it happened) goes to the log; the interface shows only "error".
            try{return new JSONObject().put("error",e.getMessage()==null?e.toString():e.getMessage()).put("trace",trace(e)).toString();}catch(Exception x){return "{\"error\":\"failed\"}";}
        }
    }

    /** An exception and where it was thrown (and its causes), for the log. */
    static String trace(Throwable e){
        StringBuilder t=new StringBuilder();
        for(Throwable c=e;c!=null;c=c.getCause()==c?null:c.getCause()){
            if(c!=e)t.append("\ncaused by ");
            t.append(c);
            StackTraceElement[] s=c.getStackTrace();
            for(int i=0;i<Math.min(14,s.length);i++)t.append("\n  at ").append(s[i]);
        }
        return t.toString();
    }

    private static final Object NONE=new Object();

    /** The routes the Mac's DesktopServer answers itself, over plain paths. NONE when the shared routes should answer. */
    private Object own(String route,JSONObject d) throws Exception {
        switch(route){
            case "library.scan":{
                File root=new File(d.getString("tree"));
                JSONArray found=new JSONArray();
                routes.scanFiles(root,"",0,found,library.dictionaries(),Core::open);
                return found;
            }
            case "library.relink":return new JSONObject().put("fixed",0).put("missing",0);
            case "library.folders":return new JSONArray();
            case "mihon.status":case "mihon.refresh":case "mihon.setFolder":
                return new JSONObject().put("folder","").put("available",false).put("backup","").put("scanned",0);
            case "sync.status":return syncStatus();
            case "sync.setFolder":store.setSetting("sync_folder",d.getString("path"));return syncNow();
            case "sync.now":return syncNow();
            case "wordlist.importPath":{
                File f=new File(d.getString("path"));
                return routes.wordlists.importText(f.getName(),new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));
            }
            case "restore":
                return routes.store.restore(new JSONObject(new String(Files.readAllBytes(new File(d.getString("path")).toPath()),StandardCharsets.UTF_8)));
            case "export.data":{
                String[] r=routes.export(d.getString("kind"),d.optJSONObject("data")==null?new JSONObject():d.getJSONObject("data"));
                if(r==null)throw new Exception("Unknown export");
                return new JSONObject().put("text",r[0]).put("mime",r[1]);
            }
            default:return NONE;
        }
    }

    // ---------- sync (a folder the shell keeps readable, e.g. in iCloud Drive) ----------

    JSONObject syncStatus() throws Exception {
        String folder=store.setting("sync_folder","");
        JSONArray devices=new JSONArray();
        File[] files=folder.isEmpty()?null:new File(folder).listFiles((dir,n)->n.matches("kotoba-[0-9a-f]+\\.json")&&!n.equals(sync.fileName()));
        if(files!=null)for(File f:files)devices.put(new JSONObject().put("file",f.getName()).put("modified",f.lastModified()));
        return new JSONObject().put("folder",folder).put("device",sync.deviceId()).put("name",deviceName)
            .put("last",Long.parseLong(store.setting("sync_last","0"))).put("devices",devices);
    }

    synchronized JSONObject syncNow() throws Exception {
        String path=store.setting("sync_folder","");
        if(path.isEmpty())return new JSONObject().put("skipped",true);
        final File dir=new File(path);
        if(!dir.isDirectory())throw new Exception("The sync folder isn’t there: "+path);
        JSONObject r=sync.run(new Sync.Folder(){
            @Override public List<String> names(){String[] n=dir.list();return n==null?new ArrayList<String>():java.util.Arrays.asList(n);}
            @Override public long modified(String name){return new File(dir,name).lastModified();}
            @Override public byte[] read(String name) throws Exception {return Files.readAllBytes(new File(dir,name).toPath());}
            @Override public void write(String name,byte[] data) throws Exception {
                // Written beside and renamed, so a sync service never picks up half a file.
                File tmp=new File(dir,"."+name+".tmp");app.kotoba.ios.Pool.writeFile(tmp.toPath(),data);
                File to=new File(dir,name);
                if(to.exists())to.delete();
                if(!tmp.renameTo(to))throw new Exception("Couldn't write "+name);
            }
        },deviceName,library.syncDicts());
        if(r.optBoolean("changed"))event("synced",r);
        return r.put("status",syncStatus());
    }

    /** Runs a sync if a folder is set; used on a timer by the shell. Never throws: returns what went wrong (for the log), or null. */
    public String syncQuietly(){try{syncNow();return null;}catch(Throwable e){return trace(e);}}

    /** A dictionary page or resource: {mime, bytes, entry-flag-or-null}, or null. kind is "d", "comic", "book" or "listen". */
    public Object[] file(String kind,String rest){
        try{
            if(kind.equals("d")){
                int slash=rest.indexOf('/');
                if(slash<0)return null;
                return routes.dictFile(Long.parseLong(rest.substring(0,slash)),rest.substring(slash+1));
            }
            if(kind.equals("comic")){Object[] f=routes.comicFile(rest);return f==null?null:new Object[]{f[1],f[0],null};}
            if(kind.equals("book")){Object[] f=routes.bookFile(rest);return f==null?null:new Object[]{f[1],f[0],f[1].toString().contains("html")?"entry":null};}
        }catch(Exception e){return null;}
        return null;
    }
}
