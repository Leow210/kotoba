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
    public Books books;// set by the platform (both apps have the book reader)
    public Comics comics;// and the comic reader
    public Listening listening;// listening sets (Glossika-style)
    Accent accent;
    public Ocr ocr;// with its text layer
    Lyrics lyrics;// synced lyrics for the song that's playing (the one online feature)
    /** Japanese lyrics with simplified-Chinese kanji (NetEase converts some): their Japanese kanji put back. */
    JSONObject japaneseLyrics(JSONObject r) throws Exception {
        JSONArray lines=r==null?null:r.optJSONArray("lines");
        if(lines==null)return r;
        int kana=0;
        for(int i=0;i<lines.length();i++){String t=lines.getJSONObject(i).optString("text");for(char c:t.toCharArray())if(c>=0x3041&&c<=0x30FF)kana++;}
        if(kana<8)return r;// not Japanese
        for(int i=0;i<lines.length();i++){
            JSONObject l=lines.getJSONObject(i);
            l.put("text",library.japaneseKanji(l.optString("text")));
        }
        return r;
    }
    Lyrics lyrics(){if(lyrics==null)lyrics=new Lyrics(store);return lyrics;}
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
                boolean hideThesaurus=d.optBoolean("hideThesaurus",false);
                JSONObject result=library.search(q,d.optString("mode","headword"),d.optString("dict",""),d.optInt("offset",0),hideThesaurus);
                if(d.optInt("offset",0)==0&&!q.trim().isEmpty()){
                    result.put("kanji",library.kanji(q));
                    if(d.optString("mode","headword").equals("headword")){
                        result.put("forms",library.forms(q));
                        if(!hideThesaurus)result.put("the2",library.the2Index(q));
                    }
                }
                return result;
            }
            // ---------- lyrics (the song playing in Spotify, YouTube Music, NetEase…) ----------
            case "accent.text":{if(accent==null)accent=new Accent(library);return accent.annotate(d.getJSONArray("texts"));}
            case "accent.export":{if(accent==null)accent=new Accent(library);return accent.export(new java.io.File(listening.root,d.getString("set")));}
            case "accent.status":return accent==null?new JSONObject().put("running",false):accent.status();
            case "listen.sets":return listening==null?new JSONArray():listening.sets();
            case "listen.set":return listening.set(d.getString("id"));
            case "lyrics.get":return japaneseLyrics(lyrics().get(d.optString("title"),d.optString("artist"),d.optString("album",""),d.optDouble("duration",0),d.optBoolean("refresh",false)));
            case "ja.kanji":return new JSONObject().put("text",library.japaneseKanji(d.getString("text")));// simplified → Japanese kanji
            case "lyrics.search":return lyrics().search(d.getString("q"));
            case "lyrics.pick":return japaneseLyrics(lyrics().pick(d.optString("title"),d.optString("artist"),d.getString("source"),d.getLong("id")));
            case "the2.index":return library.the2Index(d.optString("q",""));
            case "the2.matches":return library.the2Matches(d.getLong("rec"),d.optString("q",""));
            case "forms":return library.forms(d.getString("q"));
            case "audio":return library.audioFor(d.getString("key"),d.optString("reading",""),d.optLong("dict",0));
            case "exact":return library.exact(d.getString("key"),null);
            case "freq":return library.frequencies(d.getString("key"),d.optString("reading",""));
            case "freq.list":return library.freqList(d.getLong("dict"),d.optLong("from",0),d.optInt("offset",0),d.optInt("limit",150));
            case "lookup":{
                // 類語 OFF (the search chip): thesaurus pages are left out of every popup too, and don't take part in
                // finding the word (so the text isn't matched to a phrase only a thesaurus lists).
                boolean hide="1".equals(store.setting("hide_thesaurus",""));
                library.skipThesaurus.set(hide);
                try{
                    JSONObject r=library.lookup(d.getString("text"),d.optString("lang",""));
                    if(hide)library.dropThesaurus(r);
                    return r;
                }finally{library.skipThesaurus.remove();}
            }
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
            case "books":return books.list();
            case "book.open":return books.open(d.getLong("id"));
            case "book.position":books.savePosition(d.getLong("id"),d.getString("position"),d.optDouble("progress",0));return null;
            case "book.settings":books.saveSettings(d.getLong("id"),d.getJSONObject("settings"));return null;
            case "book.rename":books.rename(d.getLong("id"),d.getString("title"));return null;
            case "book.delete":books.delete(d.getLong("id"));return null;
            case "book.importPath":{
                File f=new File(d.getString("path"));
                if(f.length()>300_000_000)throw new Exception("That book is too large.");
                return books.importBook(java.nio.file.Files.readAllBytes(f.toPath()),f.getName());
            }
            case "highlights":return books.highlights(d.getLong("book"));
            case "highlight.save":return books.saveHighlight(d);
            case "highlight.delete":books.deleteHighlight(d.getLong("id"));return null;
            case "bookmarks":return books.bookmarks(d.getLong("book"));
            case "bookmark.save":return books.saveBookmark(d);
            case "bookmark.delete":books.deleteBookmark(d.getLong("id"));return null;
            case "comics":return comics.list();
            case "comic.series":return comics.series(d.getLong("id"));
            case "comic.settings":comics.saveSettings(d.getLong("id"),d.getJSONObject("settings"));return null;
            case "comic.rename":comics.rename(d.getLong("id"),d.getString("title"));return null;
            case "comic.delete":comics.delete(d.getLong("id"));return null;
            case "comic.coverFromPage":comics.coverFromPage(d.getLong("chapter"),d.getInt("page"));return null;
            case "comic.resetCover":comics.setCover(d.getLong("id"),null);return null;
            case "comic.pages":return new JSONObject().put("count",comics.pages(d.getLong("chapter")).size());
            case "comic.progress":comics.progress(d.getLong("chapter"),d.getInt("page"),d.optBoolean("read",false));return null;
            case "comic.read":comics.markRead(d.getJSONArray("ids"),d.getBoolean("read"));return null;
            case "comic.marks":return comics.marks(d.getLong("series"));
            case "comic.mark":return comics.addMark(d.getLong("chapter"),d.getInt("page"),d.optString("label",""));
            case "comic.unmark":comics.deleteMark(d.getLong("id"));return null;
            case "ocr.page":return ocr.page(comics,d.getLong("chapter"),d.getInt("page"),d.optString("lang","ko"),d.optBoolean("refresh",false),host);
            case "ocr.clear":ocr.clear(d.getLong("chapter"));return null;
            // All text of a comic chapter (pages not read yet are read now), for the known-words estimate.
            case "comic.text":{
                long ch=d.getLong("chapter");String lang=d.optString("lang","ko");
                int n=comics.pages(ch).size();StringBuilder t=new StringBuilder();
                for(int i=0;i<n;i++){t.append(ocr.pageText(comics,ch,i,lang));host.event("progress",new JSONObject().put("what","comic.text").put("done",i+1).put("total",n));}
                return new JSONObject().put("text",t.toString()).put("pages",n);
            }
            // ---------- known words ----------
            case "known.get":{String w=d.getString("word");return knownInfo(w,Library.langOfWord(w,d.optString("lang",library.langOfDict(d.optLong("dict",0)))));}
            case "known.set":{
                String w=d.getString("word"),lang=Library.langOfWord(w,d.optString("lang",library.langOfDict(d.optLong("dict",0))));
                store.setKnown(w,lang,d.optBoolean("known",true));
                return knownInfo(w,lang);
            }
            case "known.list":return store.knownList(d.getString("lang"),d.optString("q",""),d.optInt("offset",0));
            case "known.stats":return knownStats();
            case "known.estimate":return estimate(d.getString("text"),d.getString("lang"));
            // By path (the Mac, and files in the phone's own folder).
            case "comic.scanPath":return comics.scanFiles(new File(d.getString("path")));
            case "comic.addPaths":{
                JSONArray paths=d.getJSONArray("paths");
                java.util.List<android.net.Uri> uris=new ArrayList<>();java.util.List<String> names=new ArrayList<>();
                for(int i=0;i<paths.length();i++){File f=new File(paths.getString(i));uris.add(android.net.Uri.parse(f.getPath()));names.add(f.getName());}
                return comics.addArchives(uris,names);
            }
            case "comic.importBackupPath":{
                File f=new File(d.getString("path"));
                return comics.importBackup(MihonBackup.parse(java.nio.file.Files.readAllBytes(f.toPath())));
            }
            case "comic.setCoverPath":comics.setCover(d.getLong("id"),java.nio.file.Files.readAllBytes(new File(d.getString("path")).toPath()));return null;
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
            case "gloss.rec":{
                JSONArray recs=d.getJSONArray("recs"),out=new JSONArray();
                for(int i=0;i<recs.length()&&i<12;i++){
                    long rec=recs.getLong(i);
                    try{out.put(new JSONObject().put("rec",rec).put("text",library.glossText(rec,d.optInt("max",400))));}catch(Exception e){out.put(new JSONObject().put("rec",rec).put("text",""));}
                }
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
            case "folder.newPerDay":store.setNewPerDay(d.getLong("id"),d.getInt("n"));return null;
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
            case "stats.raw":return store.statsRaw(d.optLong("folder",0));
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
            case "highlights":return new String[]{books.exportHighlights(data.getLong("book")),"text/markdown"};
            case "pleco":{
                java.util.Set<Long> zh=new java.util.HashSet<>();
                JSONArray all=library.dictionaries();
                for(int i=0;i<all.length();i++){JSONObject x=all.getJSONObject(i);if(x.getString("grp").equals("Chinese")||x.getString("grp").startsWith("Chinese/"))zh.add(x.getLong("id"));}
                return new String[]{store.exportPleco(data.optLong("folder",0),zh),"text/plain"};
            }
            default:return null;
        }
    }

    // ---------- known words ----------
    /** Headwords of learned cards in this language (normalized). */
    java.util.Set<String> learned(String lang) throws Exception {
        java.util.Set<String> out=new java.util.HashSet<>();
        JSONArray cards=store.learnedCards();
        for(int i=0;i<cards.length();i++){
            JSONObject c=cards.getJSONObject(i);
            String w=c.getString("headword");
            if(Library.langOfWord(w,library.langOfDict(c.optLong("dict",0))).equals(lang))out.add(HtmlText.normalize(w));
        }
        return out;
    }
    /** Every word counted as known: marked known, or a learned card not marked unknown. */
    java.util.Set<String> knownSet(String lang) throws Exception {
        java.util.Set<String> out=learned(lang);
        out.removeAll(store.markedUnknown(lang));
        out.addAll(store.markedKnown(lang));
        return out;
    }
    JSONObject knownInfo(String word,String lang) throws Exception {
        int m=store.marked(word,lang);
        String how=m==1?"marked":m==0?"":learned(lang).contains(HtmlText.normalize(word))?"card":"";
        return new JSONObject().put("word",word).put("lang",lang).put("known",!how.isEmpty()).put("how",how);
    }
    JSONObject knownStats() throws Exception {
        JSONObject out=new JSONObject();
        for(String lang:new String[]{"ja","ko","zh","th","ru"}){
            java.util.Set<String> learned=learned(lang),marked=store.markedKnown(lang),all=knownSet(lang);
            if(all.isEmpty()&&learned.isEmpty())continue;
            out.put(lang,new JSONObject().put("total",all.size()).put("marked",marked.size()).put("cards",learned.size()));
        }
        return out;
    }
    /**
     * How much of a text you'd know: the share of its words (counting repeats) that are known, and the unknown ones by
     * how often they occur, for a quick look before reading.
     */
    JSONObject estimate(String text,String lang) throws Exception {
        java.util.LinkedHashMap<String,Object[]> words=library.textWords(text,lang);
        java.util.Set<String> known=knownSet(lang);
        int total=0,knownTokens=0;java.util.List<JSONObject> unknown=new ArrayList<>();
        for(java.util.Map.Entry<String,Object[]> e:words.entrySet()){
            int n=(Integer)e.getValue()[0];total+=n;
            if(known.contains(HtmlText.normalize(e.getKey())))knownTokens+=n;
            else unknown.add(new JSONObject().put("word",e.getKey()).put("form",e.getValue()[1]).put("count",n));
        }
        unknown.sort((a,b)->Integer.compare(b.optInt("count"),a.optInt("count")));
        JSONArray top=new JSONArray();for(int i=0;i<Math.min(150,unknown.size());i++)top.put(unknown.get(i));
        return new JSONObject().put("lang",lang).put("tokens",total).put("known",knownTokens).put("unique",words.size()).put("unknownUnique",unknown.size())
            .put("pct",total==0?0:Math.round(knownTokens*1000.0/total)/10.0).put("unknown",top);
    }

    // ---------- dictionary pages and resources ----------

    /** An entry as a whole HTML page (the dictionary's markup made renderable, with the base stylesheet). */
    public String entryPage(long rec) throws Exception {
        String html=MarkupFix.html(library.recordHtml(rec));
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            +"<link rel=\"stylesheet\" href=\"/entry-base.css\">"+fontStyle(library.record(rec).getLong("dict"))+"</head><body class=\"kotoba-entry\">"+html+"</body></html>";
    }
    /** The dictionary's own fonts (see Extras.fontFaces), or nothing. */
    String fontStyle(long dict){
        String css=extras==null?"":extras.fontFaces(dict);
        return css.isEmpty()?"":"<style>"+css+"</style>";
    }

    /**
     * /d/<dict>/<name>: an entry (<rec>.entry), a saved card (item-<id>.card), or a resource (image, audio, CSS, appendix).
     * Returns {mime, bytes, "entry" when it's an HTML page needing the entry CSP} or null when missing.
     */
    public Object[] dictFile(long dict,String name) throws Exception {
        if(name.startsWith("item-")&&name.endsWith(".card")){
            JSONObject item=store.item(Long.parseLong(name.substring(5,name.length()-5)));
            String body=item.getString("back_html").isEmpty()?"<div class=\"kotoba-plain\">"+Store.escape(item.getString("back")).replace("\n","<br>")+"</div>":item.getString("back_html");
            String page="<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><link rel=\"stylesheet\" href=\"/entry-base.css\">"+fontStyle(dict)+"</head><body class=\"kotoba-entry kotoba-card\">"+body+"</body></html>";
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

    /** /comic/<chapter>/<page> or /comic/cover/<series>: {bytes, mime} or null. */
    public Object[] comicFile(String rest) throws Exception {
        String[] p=rest.split("/");
        if(p.length!=2)return null;
        if(p[0].equals("cover")){byte[] b=comics.cover(Long.parseLong(p[1]));return b==null?null:new Object[]{b,"image/jpeg"};}
        return comics.page(Long.parseLong(p[0]),Integer.parseInt(p[1]));
    }

    /** /book/<id>/<path>: a chapter or image from inside a book, or its cover. {bytes, mime} or null. */
    public Object[] bookFile(String rest) throws Exception {
        int slash=rest.indexOf('/');
        if(slash<0)return null;
        return books.resource(Long.parseLong(rest.substring(0,slash)),rest.substring(slash+1));
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

    /**
     * Once after an update: brings older dictionaries' search keys up to date in the background (e.g. 大辞林's
     * 落ち合う, filed only as おちあ・う). Runs on the import thread, so it never overlaps an import.
     */
    public void upgradeIndexesLater(){
        importer.execute(()->{
            try{
                JSONArray todo=library.keysToUpgrade();
                if(todo.length()==0)return;
                importing=true;
                for(int i=0;i<todo.length();i++){
                    JSONObject d=todo.getJSONObject(i);final int index=i;
                    library.upgradeKeys(d.getLong("id"),new Library.Progress(){
                        long last=0;
                        @Override public void update(String stage,long a,long b){
                            long t=System.currentTimeMillis();if(t-last<400)return;last=t;
                            try{host.event("import",new JSONObject().put("title",d.getString("name")).put("index",index).put("count",todo.length()).put("stage","Improving search").put("done",a).put("total",b));}catch(Exception ignored){}
                        }
                        @Override public boolean cancelled(){return cancelImport.get();}
                    });
                }
                host.event("import-done",new JSONObject().put("done",0).put("failed",0).put("cancelled",false).put("quiet",true));
            }catch(Exception e){android.util.Log.w("Kotoba","index upgrade: "+e.getMessage());}
            finally{importing=false;}
        });
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
