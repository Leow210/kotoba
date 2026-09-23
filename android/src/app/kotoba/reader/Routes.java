package app.kotoba.reader;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The requests the interface makes that don't depend on the platform: search, entries, cards, review, word lists,
 * dictionary import and exports. The Android app and the desktop app both answer through here, so they behave alike;
 * each handles its own platform requests (file pickers, books, comics, camera) before falling back to this.
 */
public class Routes {
    /** Events back to the page (import progress, toasts), and keeping the machine awake during imports. */
    public interface Host {
        void event(String type,Object data);
        default void keepAwake(boolean on){}
    }

    public final Library library;
    public final Store store;
    public final WordLists wordlists;
    public final Extras extras;
    final Host host;
    final ExecutorService importer=Executors.newSingleThreadExecutor();
    public final AtomicBoolean cancelImport=new AtomicBoolean(false);
    public volatile boolean importing=false;

    public Routes(Library library,Store store,WordLists wordlists,Extras extras,Host host){
        this.library=library;this.store=store;this.wordlists=wordlists;this.extras=extras;this.host=host;
    }

    public Object route(String route,JSONObject d) throws Exception {
        switch(route){
            case "dicts":return library.dictionaries();
            case "search":{
                String q=d.optString("q","");
                JSONObject result=library.search(q,d.optString("mode","headword"),d.optString("dict",""),d.optInt("offset",0));
                if(d.optInt("offset",0)==0&&!q.trim().isEmpty()){
                    result.put("kanji",library.kanji(q));
                    if(d.optString("mode","headword").equals("headword"))result.put("forms",library.forms(q));
                }
                return result;
            }
            case "forms":return library.forms(d.getString("q"));
            case "audio":return library.audioFor(d.getString("key"),d.optString("reading",""),d.optLong("dict",0));
            case "exact":return library.exact(d.getString("key"),null);
            case "freq":return library.frequencies(d.getString("key"),d.optString("reading",""));
            case "freq.list":return library.freqList(d.getLong("dict"),d.optLong("from",0),d.optInt("offset",0),d.optInt("limit",150));
            case "lookup":return library.lookup(d.getString("text"),d.optString("lang",""));
            case "record":{
                JSONObject r=library.record(d.getLong("rec"));
                r.put("saved",store.savedFor(r.getLong("dict"),r.getString("key")));
                return r;
            }
            case "resolve":{
                long rec=library.findRecord(d.getLong("dict"),d.getString("page"));
                return new JSONObject().put("rec",rec==0?JSONObject.NULL:rec);
            }
            case "browse":return library.browse(d.getLong("dict"),d.optString("dir","from"),d.optString("norm",""),d.optLong("id",0),d.optString("prefix",""),d.optInt("limit",120),d.optBoolean("kanji",false));
            case "kanji.grid":return library.kanjiGrid(d.getLong("dict"),d.optString("level",""),d.optInt("strokes",0),d.optString("radical",""),d.optString("flag",""));
            case "random":return library.random(d.optLong("dict",0));
            case "neighbors":return library.neighbors(d.getLong("rec"));
            case "reference":return library.reference(d.getLong("dict"),d.getString("ref"));
            case "history":return store.history();
            case "history.add":store.remember(d.optString("q",""));return null;
            case "history.clear":store.clearHistory();return null;
            case "dict.update":library.updateDictionary(d);return null;
            case "dict.reorder":library.reorder(d.getJSONArray("ids"));return null;
            case "dict.delete":{
                if(importing)throw new Exception("Wait for the current import to finish.");
                library.delete(d.getLong("id"));return null;
            }
            case "library.import":startImport(d.getJSONArray("items"),d.optBoolean("fulltext",true));return null;
            case "library.cancel":cancelImport.set(true);return null;
            case "library.status":return new JSONObject().put("importing",importing);
            case "wordlists":return wordlists.lists();
            case "wordlist.items":if("abc".equals(d.optString("sort")))wordlists.fillSortKeys(d.getLong("id"),library::readingOf);
                return wordlists.items(d.getLong("id"),d.optInt("offset",0),d.optInt("limit",200),d.optString("q",""),d.optString("sort",""));
            case "wordlist.delete":wordlists.delete(d.getLong("id"));return null;
            case "wordlist.rename":wordlists.rename(d.getLong("id"),d.getString("name"));return null;
            case "wordlist.toFolder":return wordListToFolder(d.getLong("id"),d.getLong("folder"));
            case "gloss":{
                JSONArray words=d.getJSONArray("words"),out=new JSONArray();
                for(int i=0;i<words.length()&&i<200;i++)out.put(library.gloss(words.getString(i)));
                return out;
            }
            case "rank":{JSONObject r=wordlists.rank(d.getString("word"));return r==null?JSONObject.NULL:r;}
            case "appendix":return extras.appendix(d.getLong("dict"));
            case "appendix.counts":return extras.counts();
            case "dictlists":return extras.lists();
            case "dictlist":return extras.list(d.getLong("dict"),d.getInt("index"));
            case "dictlist.toFolder":return extras.toFolder(store,d.getLong("dict"),d.getInt("index"),d.optString("section",""),d.getLong("folder"),n->host.event("toast","Added "+n+" cards…"));
            case "dictlist.resolve":return extras.resolve(d.getLong("dict"),d.optString("anchor"),d.optString("word"));
            case "folders":return store.folders();
            case "folder.save":return store.saveFolder(d);
            case "folder.study":store.setStudy(d.getLong("id"),d.getBoolean("study"));return null;
            case "folder.delete":store.deleteFolder(d.getLong("id"),d.optBoolean("items",false));return null;
            case "folder.reorder":store.reorderFolders(d.getJSONArray("ids"));return null;
            case "items":return store.items(d);
            case "item.similar":return store.similar(d.getString("headword"),d.optString("reading",""));
            case "item":return store.item(d.getLong("id"));
            case "item.save":return store.saveItem(d);
            case "item.delete":store.deleteItems(d.getJSONArray("ids"));return null;
            case "item.move":store.moveItems(d.getJSONArray("ids"),d.getLong("folder"),d.optBoolean("copy",false));return null;
            case "item.review":store.setReview(d.getJSONArray("ids"),d.getBoolean("review"));return null;
            case "item.reset":store.resetProgress(d.getJSONArray("ids"));return null;
            case "queue":return store.queue(d.optLong("folder",0));
            case "answer":return store.answer(d.getLong("id"),d.getInt("rating"));
            case "undo":return store.undo();
            case "stats":return store.stats().put("library",library.stats());
            case "settings":return store.settings();
            case "setting":store.setSetting(d.getString("key"),d.getString("value"));return null;
            default:throw new Exception("Unknown request: "+route);
        }
    }

    // ---------- exports ----------

    /** {text, mime} for an export the platform then saves (Android: a save-as picker; desktop: a save panel). */
    public String[] export(String kind,JSONObject data) throws Exception {
        switch(kind){
            case "backup":return new String[]{store.backup().toString(1),"application/json"};
            case "tsv":return new String[]{store.exportTsv(data.optLong("folder",0),data.optBoolean("html",true)),"text/tab-separated-values"};
            case "csv":return new String[]{store.exportCsv(data.optLong("folder",0)),"text/csv"};
            case "text":return new String[]{data.optString("text",""),"text/plain"};
            case "pleco":{
                java.util.Set<Long> zh=new java.util.HashSet<>();
                JSONArray all=library.dictionaries();
                for(int i=0;i<all.length();i++){JSONObject x=all.getJSONObject(i);if(x.getString("grp").equals("Chinese")||x.getString("grp").startsWith("Chinese/"))zh.add(x.getLong("id"));}
                return new String[]{store.exportPleco(data.optLong("folder",0),zh),"text/plain"};
            }
            default:return null;
        }
    }

    // ---------- dictionary pages and resources ----------

    /** An entry as a whole HTML page (the dictionary's markup made renderable, with the base stylesheet). */
    public String entryPage(long rec) throws Exception {
        String html=MarkupFix.html(library.recordHtml(rec));
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            +"<link rel=\"stylesheet\" href=\"/entry-base.css\"></head><body class=\"kotoba-entry\">"+html+"</body></html>";
    }

    /**
     * /d/<dict>/<name>: an entry (<rec>.entry), a saved card (item-<id>.card), or a resource (image, audio, CSS, appendix).
     * Returns {mime, bytes, "entry" when it's an HTML page needing the entry CSP} or null when missing.
     */
    public Object[] dictFile(long dict,String name) throws Exception {
        if(name.startsWith("item-")&&name.endsWith(".card")){
            JSONObject item=store.item(Long.parseLong(name.substring(5,name.length()-5)));
            String body=item.getString("back_html").isEmpty()?"<div class=\"kotoba-plain\">"+Store.escape(item.getString("back")).replace("\n","<br>")+"</div>":item.getString("back_html");
            String page="<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><link rel=\"stylesheet\" href=\"/entry-base.css\"></head><body class=\"kotoba-entry kotoba-card\">"+body+"</body></html>";
            return new Object[]{"text/html",page.getBytes(StandardCharsets.UTF_8),"entry"};
        }
        if(name.endsWith(".entry")){
            long rec=Long.parseLong(name.substring(0,name.length()-6));
            return new Object[]{"text/html",entryPage(rec).getBytes(StandardCharsets.UTF_8),"entry"};
        }
        byte[] bytes=name.startsWith("files/")?extras.file(dict,name):library.resource(dict,name);
        if(bytes==null)bytes=extras.file(dict,"files/"+name.substring(name.lastIndexOf('/')+1));
        if(bytes==null)return null;
        String mime=Library.mime(name);
        if(mime.equals("text/css")&&!library.isYomitan(dict))bytes=MarkupFix.css(new String(bytes,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        if(mime.equals("text/html")){
            // Appendix pages: same treatment as entries (no scripts, renderable markup, base styles).
            String h=MarkupFix.html(new String(bytes,StandardCharsets.UTF_8));
            h=h.replaceAll("(?is)<script\\b.*?</script>","");
            if(!h.contains("entry-base.css"))h="<link rel=\"stylesheet\" href=\"/entry-base.css\">"+h.replaceFirst("(?i)<body([^>]*)>","<body$1 class=\"kotoba-entry\">");
            return new Object[]{mime,h.getBytes(StandardCharsets.UTF_8),"entry"};
        }
        return new Object[]{mime,bytes,null};
    }

    // ---------- importing ----------

    /** MDX (with matching MDD files) and Yomitan ZIPs in a folder and its subfolders, read by path. */
    public void scanFiles(File dir,String path,int depth,JSONArray found,JSONArray imported,Library.Opener opener) throws Exception {
        File[] files=dir.listFiles();if(files==null)return;
        java.util.Arrays.sort(files);
        for(File f:files){
            if(f.isDirectory()){if(depth<3&&!f.getName().startsWith("."))scanFiles(f,path.isEmpty()?f.getName():path+"/"+f.getName(),depth+1,found,imported,opener);continue;}
            String name=f.getName();
            if(name.toLowerCase(Locale.ROOT).endsWith(".zip")){addYomitan(f.getPath(),name,path,f.length(),found,imported,opener);continue;}
            if(!name.toLowerCase(Locale.ROOT).endsWith(".mdx"))continue;
            String base=name.substring(0,name.length()-4);
            JSONArray mdd=new JSONArray();long size=f.length();
            for(File g:files){
                String n=g.getName().toLowerCase(Locale.ROOT),b=base.toLowerCase(Locale.ROOT);
                if(n.equals(b+".mdd")||(n.startsWith(b+".")&&n.endsWith(".mdd")&&n.substring(b.length()+1,n.length()-4).matches("\\d+"))){mdd.put(g.getPath());size+=g.length();}
            }
            String title=base;
            try(MdictFile m=new MdictFile(opener.open(f.getPath()),false)){if(!m.title().isEmpty())title=m.title();}catch(Exception e){title=base+" — "+e.getMessage();}
            boolean already=false;
            for(int i=0;i<imported.length();i++){JSONObject d=imported.getJSONObject(i);if(d.getString("mdx").equals(f.getPath())||d.getString("title").equals(title))already=true;}
            found.put(new JSONObject().put("name",base).put("title",title).put("folder",path).put("mdx",f.getPath()).put("mdd",mdd).put("size",size).put("imported",already));
        }
    }

    /** A Yomitan dictionary ZIP (index.json + banks); other ZIPs are ignored. */
    public void addYomitan(String uri,String file,String folder,long size,JSONArray found,JSONArray imported,Library.Opener opener) throws Exception {
        String title;
        try(ZipSource z=new ZipSource(opener.open(uri))){title=Library.yomitanTitle(z);}catch(Exception e){return;}
        if(title==null)return;
        boolean already=false;
        for(int i=0;i<imported.length();i++){JSONObject d=imported.getJSONObject(i);if(d.getString("mdx").equals(uri)||d.getString("title").equals(title))already=true;}
        String base=file.replaceFirst("(?i)\\.zip$","");
        found.put(new JSONObject().put("name",base).put("title",title).put("folder",folder).put("mdx",uri).put("mdd",new JSONArray()).put("size",size).put("imported",already).put("format","yomitan"));
    }

    void startImport(JSONArray items,boolean fulltext) throws Exception {
        if(importing)throw new Exception("An import is already running.");
        importing=true;cancelImport.set(false);
        host.keepAwake(true);
        importer.execute(()->{
            int done=0,failed=0;
            try{
                for(int i=0;i<items.length();i++){
                    if(cancelImport.get())break;
                    JSONObject item=items.getJSONObject(i);
                    String title=item.optString("title",item.optString("name"));
                    final int index=i;
                    try{
                        ArrayList<String> mdd=new ArrayList<>();
                        JSONArray m=item.optJSONArray("mdd");
                        if(m!=null)for(int k=0;k<m.length();k++)mdd.add(m.getString(k));
                        Library.Progress progress=new Library.Progress(){
                            long last=0;
                            @Override public void update(String stage,long a,long b){
                                long t=System.currentTimeMillis();
                                if(t-last<250&&a<b)return;
                                last=t;
                                try{host.event("import",new JSONObject().put("title",title).put("index",index).put("count",items.length()).put("stage",stage).put("done",a).put("total",b));}catch(Exception ignored){}
                            }
                            @Override public boolean cancelled(){return cancelImport.get();}
                        };
                        if("yomitan".equals(item.optString("format")))library.importYomitan(item.getString("name"),item.getString("mdx"),fulltext,progress);
                        else library.importDictionary(item.getString("name"),item.getString("mdx"),mdd,fulltext,progress);
                        done++;
                    }catch(Throwable e){
                        failed++;
                        host.event("import-error",new JSONObject().put("title",title).put("error",e.getMessage()==null?e.toString():e.getMessage()));
                    }
                }
                host.event("import-done",new JSONObject().put("done",done).put("failed",failed).put("cancelled",cancelImport.get()));
            }catch(Exception e){host.event("import-error",e.getMessage());}
            finally{importing=false;host.keepAwake(false);}
        });
    }

    /** Every word of a list becomes a card in a folder, with a short definition from the dictionaries. */
    JSONObject wordListToFolder(long list,long folder) throws Exception {
        int added=0,missing=0,offset=0;
        while(true){
            JSONArray items=wordlists.items(list,offset,300,"");
            if(items.length()==0)break;
            for(int i=0;i<items.length();i++){
                JSONObject it=items.getJSONObject(i);offset=it.getInt("pos");
                JSONObject g=library.gloss(it.getString("word"));
                String back=!it.optString("note").isEmpty()?it.getString("note"):g.optString("text","");
                if(back.isEmpty()){missing++;continue;}
                JSONObject data=new JSONObject().put("folder_id",folder).put("headword",it.getString("word")).put("reading",it.optString("reading")).put("back",back)
                    .put("dict",g.optLong("dict",0)).put("dict_name",g.optString("dictionary")).put("page",g.optString("page")).put("kind","wordlist").put("review",true);
                store.saveItem(data);added++;
            }
            if(offset%50==0)host.event("toast","Added "+added+"…");
        }
        return new JSONObject().put("added",added).put("missing",missing);
    }
}
