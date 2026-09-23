package app.kotoba.reader;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Comics and manhwa: series → chapters → pages, read in place from image folders or CBZ/ZIP files
 * (including Mihon's downloads folder). Nothing is copied into app storage.
 */
public class Comics {
    public interface Opener { java.nio.channels.FileChannel open(String uri) throws Exception; }
    final Context context;
    final SQLiteDatabase db;
    final Opener opener;
    final File thumbs;
    final Map<Long,List<String>> pageCache=new HashMap<>();
    final Map<Long,ZipSource> zips=new HashMap<>();

    public Comics(Context context,SQLiteDatabase db,Opener opener){
        this.context=context;this.db=db;this.opener=opener;
        thumbs=new File(context.getFilesDir(),"comic-thumbs");thumbs.mkdirs();
        db.execSQL("CREATE TABLE IF NOT EXISTS series(id INTEGER PRIMARY KEY,title TEXT NOT NULL,source TEXT NOT NULL DEFAULT '',tree TEXT NOT NULL DEFAULT '',doc TEXT NOT NULL DEFAULT '',lang TEXT NOT NULL DEFAULT '',added INTEGER NOT NULL,opened INTEGER NOT NULL DEFAULT 0,settings TEXT NOT NULL DEFAULT '{}',UNIQUE(tree,doc))");
        db.execSQL("CREATE TABLE IF NOT EXISTS chapters(id INTEGER PRIMARY KEY,series_id INTEGER NOT NULL REFERENCES series(id) ON DELETE CASCADE,name TEXT NOT NULL,sort TEXT NOT NULL,kind TEXT NOT NULL,uri TEXT NOT NULL,pages INTEGER NOT NULL DEFAULT 0,page INTEGER NOT NULL DEFAULT 0,read INTEGER NOT NULL DEFAULT 0,opened INTEGER NOT NULL DEFAULT 0,UNIQUE(series_id,uri))");
        try{db.execSQL("ALTER TABLE series ADD COLUMN category TEXT NOT NULL DEFAULT ''");}catch(Exception ignored){}
        try{db.execSQL("ALTER TABLE series ADD COLUMN thumb TEXT NOT NULL DEFAULT ''");}catch(Exception ignored){}
        cleanChapterNames();
        db.execSQL("CREATE TABLE IF NOT EXISTS comic_marks(id INTEGER PRIMARY KEY,chapter_id INTEGER NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,page INTEGER NOT NULL,label TEXT NOT NULL DEFAULT '',created INTEGER NOT NULL)");
    }

    /** Mihon appends "_" + 6 hex digits of the chapter URL's hash to file names (1화_529068.cbz); it isn't part of the title. */
    static final Pattern MIHON_HASH=Pattern.compile("_[0-9a-f]{6}$");
    static String chapterName(String fileName){
        return MIHON_HASH.matcher(fileName).replaceFirst("").replaceAll("[_]+"," ").trim();
    }
    /** Chapters imported before the hash was stripped: rename those whose file really ends in _<hash>. */
    void cleanChapterNames(){
        try(android.database.Cursor c=db.rawQuery("SELECT id,name,uri FROM chapters WHERE name GLOB '* [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]'",null)){
            while(c.moveToNext()){
                String name=c.getString(1),hash=name.substring(name.length()-6),uri=Uri.decode(c.getString(2));
                if(uri.matches("(?s).*_"+hash+"(\\.(?i:cbz|zip))?/?$"))
                    db.execSQL("UPDATE chapters SET name=? WHERE id=?",new Object[]{name.substring(0,name.length()-7).trim(),c.getLong(0)});
            }
        }catch(Exception ignored){}
    }

    static long now(){return System.currentTimeMillis()/1000;}
    static byte[] readAll(java.io.InputStream in,int limit) throws java.io.IOException {
        try(java.io.InputStream stream=in){
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[65536];int n,total=0;
            while((n=stream.read(b))!=-1){total+=n;if(total>limit)throw new java.io.IOException("File is too large");out.write(b,0,n);}
            return out.toByteArray();
        }
    }
    static final Pattern IMAGE=Pattern.compile("(?i).*\\.(jpe?g|png|webp|gif|avif|bmp)$");
    static final Pattern ARCHIVE=Pattern.compile("(?i).*\\.(cbz|zip)$");

    /** Natural sort key: "Chapter 2" before "Chapter 10", "12.5" between 12 and 13. */
    static String sortKey(String name){
        Matcher m=Pattern.compile("(\\d+)(?:[.,](\\d+))?").matcher(name.toLowerCase(Locale.ROOT));
        StringBuffer out=new StringBuffer();
        while(m.find()){
            String whole=String.format(Locale.ROOT,"%010d",Long.parseLong(m.group(1).length()>10?m.group(1).substring(0,10):m.group(1)));
            String frac=m.group(2)==null?"":"."+m.group(2);
            m.appendReplacement(out,whole+frac);
        }
        m.appendTail(out);
        return out.toString();
    }

    // ---------- scanning ----------

    static final class Doc { String id,name,mime;long size; boolean dir(){return DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);} }

    List<Doc> children(Uri tree,String docId){
        ArrayList<Doc> out=new ArrayList<>();
        Uri uri=DocumentsContract.buildChildDocumentsUriUsingTree(tree,docId);
        try(Cursor c=context.getContentResolver().query(uri,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE},null,null,null)){
            while(c!=null&&c.moveToNext()){Doc d=new Doc();d.id=c.getString(0);d.name=c.getString(1);d.mime=c.getString(2);d.size=c.isNull(3)?0:c.getLong(3);out.add(d);}
        }
        return out;
    }

    /** Scans a picked folder: it can be one chapter, one series, or a whole library (e.g. Mihon/downloads). */
    public JSONObject scan(Uri tree) throws Exception {
        String root=DocumentsContract.getTreeDocumentId(tree);
        int[] counts={0,0};
        String rootName=root.contains("/")?root.substring(root.lastIndexOf('/')+1):root.replaceFirst("^[^:]*:","");
        scanDir(tree,root,rootName,"",0,counts);
        return new JSONObject().put("series",counts[0]).put("chapters",counts[1]);
    }

    void scanDir(Uri tree,String docId,String name,String source,int depth,int[] counts) throws Exception {
        List<Doc> kids=children(tree,docId);
        ArrayList<Doc> images=new ArrayList<>(),archives=new ArrayList<>(),dirs=new ArrayList<>();
        for(Doc d:kids){
            if(d.dir())dirs.add(d);
            else if(ARCHIVE.matcher(d.name).matches())archives.add(d);
            else if(IMAGE.matcher(d.name).matches())images.add(d);
        }
        if(!images.isEmpty()&&archives.isEmpty()){
            // A folder of images is a single chapter; its series is the folder itself.
            long s=series(tree,docId,name,source);
            chapter(s,name,"folder",docId,images.size());counts[0]++;counts[1]++;
            return;
        }
        ArrayList<Doc> chapterDirs=new ArrayList<>();
        for(Doc d:dirs){
            // A subfolder that directly holds images is a chapter of this series.
            List<Doc> sub=children(tree,d.id);
            int imgs=0;boolean nested=false;
            for(Doc x:sub){if(IMAGE.matcher(x.name).matches())imgs++;if(x.dir()||ARCHIVE.matcher(x.name).matches())nested=true;}
            if(imgs>0&&!nested){d.size=imgs;chapterDirs.add(d);}
        }
        if(!archives.isEmpty()||!chapterDirs.isEmpty()){
            long s=series(tree,docId,name,source);counts[0]++;
            for(Doc a:archives){chapter(s,a.name.replaceFirst("(?i)\\.(cbz|zip)$",""),"zip",DocumentsContract.buildDocumentUriUsingTree(tree,a.id).toString(),0);counts[1]++;}
            for(Doc d:chapterDirs){chapter(s,d.name,"folder",d.id,(int)d.size);counts[1]++;}
        }
        if(depth<4)for(Doc d:dirs){
            if(chapterDirs.contains(d))continue;
            // Mihon: downloads/<source>/<series>/<chapter>; the source folder's name labels its series.
            scanDir(tree,d.id,d.name,childSource(name,depth),depth+1,counts);
        }
    }

    static String childSource(String name,int depth){
        String n=name.toLowerCase(Locale.ROOT);
        return depth==0||n.equals("downloads")||n.equals("local")||n.equals("mihon")||n.equals("tachiyomi")?"":name;
    }

    /** Same scan over plain files (dictionary-style USB copies into the app's own folder). */
    public JSONObject scanFiles(File dir) throws Exception {
        int[] counts={0,0};
        scanFileDir(dir,"",0,counts);
        return new JSONObject().put("series",counts[0]).put("chapters",counts[1]);
    }
    void scanFileDir(File dir,String source,int depth,int[] counts) throws Exception {
        File[] kids=dir.listFiles();if(kids==null)return;
        ArrayList<File> images=new ArrayList<>(),archives=new ArrayList<>(),dirs=new ArrayList<>(),chapterDirs=new ArrayList<>();
        for(File f:kids){if(f.isDirectory())dirs.add(f);else if(ARCHIVE.matcher(f.getName()).matches())archives.add(f);else if(IMAGE.matcher(f.getName()).matches())images.add(f);}
        Uri tree=Uri.parse("file://"+dir.getParentFile().getPath());
        if(!images.isEmpty()&&archives.isEmpty()){long s=series(Uri.parse("file://"+dir.getPath()),"",dir.getName(),source);chapter(s,dir.getName(),"dir",dir.getPath(),images.size());counts[0]++;counts[1]++;return;}
        for(File d:dirs){File[] sub=d.listFiles();int imgs=0;boolean nested=false;if(sub!=null)for(File x:sub){if(IMAGE.matcher(x.getName()).matches())imgs++;if(x.isDirectory()||ARCHIVE.matcher(x.getName()).matches())nested=true;}if(imgs>0&&!nested)chapterDirs.add(d);}
        if(!archives.isEmpty()||!chapterDirs.isEmpty()){
            long s=series(Uri.parse("file://"+dir.getPath()),"",dir.getName(),source);counts[0]++;
            for(File a:archives){chapter(s,a.getName().replaceFirst("(?i)\\.(cbz|zip)$",""),"zip",a.getPath(),0);counts[1]++;}
            for(File d:chapterDirs){chapter(s,d.getName(),"dir",d.getPath(),0);counts[1]++;}
        }
        if(depth<4)for(File d:dirs)if(!chapterDirs.contains(d))scanFileDir(d,childSource(dir.getName(),depth),depth+1,counts);
    }

    long series(Uri tree,String doc,String title,String source) throws Exception {
        JSONArray r=Store.rows(db,"SELECT id FROM series WHERE tree=? AND doc=?",tree.toString(),doc);
        if(r.length()>0)return r.getJSONObject(0).getLong("id");
        ContentValues v=new ContentValues();
        v.put("title",title.replaceAll("[_]+"," ").trim());v.put("source",source.equalsIgnoreCase("downloads")?"":source);v.put("tree",tree.toString());v.put("doc",doc);v.put("added",now());
        v.put("lang",title.codePoints().anyMatch(c->c>=0xac00&&c<=0xd7a3)?"ko":title.codePoints().anyMatch(c->c>=0x3040&&c<=0x30ff)?"ja":"");
        return db.insertOrThrow("series",null,v);
    }

    void chapter(long series,String name,String kind,String uri,int pages){
        ContentValues v=new ContentValues();
        v.put("series_id",series);name=chapterName(name);v.put("name",name);v.put("sort",sortKey(name));v.put("kind",kind);v.put("uri",uri);v.put("pages",pages);
        db.insertWithOnConflict("chapters",null,v,SQLiteDatabase.CONFLICT_IGNORE);
    }

    /** CBZ/ZIP files picked individually become one series (named after the first file's folder or name). */
    public JSONObject addArchives(List<Uri> uris,List<String> names) throws Exception {
        String title=names.isEmpty()?"Comic":commonTitle(names);
        ContentValues v=new ContentValues();
        v.put("title",title);v.put("tree","files:"+now()+":"+uris.get(0).toString().hashCode());v.put("doc","");v.put("added",now());
        long s=db.insertOrThrow("series",null,v);
        for(int i=0;i<uris.size();i++)chapter(s,names.get(i).replaceFirst("(?i)\\.(cbz|zip)$",""),"zip",uris.get(i).toString(),0);
        return new JSONObject().put("id",s);
    }
    static String commonTitle(List<String> names){
        String first=names.get(0).replaceFirst("(?i)\\.(cbz|zip)$","");
        if(names.size()==1)return first;
        String p=first;
        for(String n:names)while(!n.startsWith(p)&&!p.isEmpty())p=p.substring(0,p.length()-1);
        p=p.replaceAll("[\\s_\\-–—.,(\\[]*(ch|chapter|vol|v|第|제)?\\.?\\s*\\d*$","").trim();
        return p.length()>=2?p:first;
    }

    // ---------- listing ----------

    public JSONArray list() throws Exception {
        return Store.rows(db,"SELECT s.id,s.title,s.source,s.lang,s.opened,s.added,s.category,count(c.id) chapters,coalesce(sum(c.read),0) read,(SELECT c2.name FROM chapters c2 WHERE c2.series_id=s.id AND c2.opened>0 ORDER BY c2.opened DESC LIMIT 1) last FROM series s LEFT JOIN chapters c ON c.series_id=s.id GROUP BY s.id ORDER BY s.opened=0,s.opened DESC,s.title COLLATE NOCASE");
    }

    public JSONObject series(long id) throws Exception {
        JSONArray r=Store.rows(db,"SELECT * FROM series WHERE id=?",Long.toString(id));
        if(r.length()==0)throw new Exception("This series is no longer in your library.");
        JSONObject s=r.getJSONObject(0);
        s.put("settings",new JSONObject(s.optString("settings","{}")));
        s.put("chapters",Store.rows(db,"SELECT id,name,kind,pages,page,read,opened FROM chapters WHERE series_id=? ORDER BY sort,name",Long.toString(id)));
        return s;
    }

    public void saveSettings(long id,JSONObject settings){db.execSQL("UPDATE series SET settings=? WHERE id=?",new Object[]{settings.toString(),id});}
    public void rename(long id,String title){db.execSQL("UPDATE series SET title=? WHERE id=?",new Object[]{title.trim(),id});}
    public void delete(long id){
        for(JSONObject c:iter("SELECT id FROM chapters WHERE series_id="+id)){long cid=c.optLong("id");pageCache.remove(cid);ZipSource z=zips.remove(cid);if(z!=null)try{z.close();}catch(Exception ignored){}}
        db.execSQL("DELETE FROM comic_marks WHERE chapter_id IN (SELECT id FROM chapters WHERE series_id=?)",new Object[]{id});
        db.execSQL("DELETE FROM chapters WHERE series_id=?",new Object[]{id});
        db.execSQL("DELETE FROM series WHERE id=?",new Object[]{id});
        new File(thumbs,id+".jpg").delete();new File(thumbs,id+"-custom").delete();
    }
    List<JSONObject> iter(String sql){
        ArrayList<JSONObject> out=new ArrayList<>();
        try{JSONArray a=Store.rows(db,sql);for(int i=0;i<a.length();i++)out.add(a.getJSONObject(i));}catch(Exception ignored){}
        return out;
    }

    public void progress(long chapter,int page,boolean read){
        db.execSQL("UPDATE chapters SET page=?,read=max(read,?),opened=? WHERE id=?",new Object[]{page,read?1:0,now(),chapter});
        db.execSQL("UPDATE series SET opened=? WHERE id=(SELECT series_id FROM chapters WHERE id=?)",new Object[]{now(),chapter});
    }
    public void markRead(JSONArray ids,boolean read){
        for(int i=0;i<ids.length();i++)db.execSQL("UPDATE chapters SET read=?,page=CASE WHEN ? THEN page ELSE 0 END WHERE id=?",new Object[]{read?1:0,read?1:0,ids.optLong(i)});
    }

    // ---------- pages ----------

    JSONObject chapterRow(long id) throws Exception {
        JSONArray r=Store.rows(db,"SELECT c.*,s.tree FROM chapters c JOIN series s ON s.id=c.series_id WHERE c.id=?",Long.toString(id));
        if(r.length()==0)throw new Exception("Chapter not found");
        return r.getJSONObject(0);
    }

    /** Page references in reading order: ZIP entry names, or document ids inside a folder. */
    public synchronized List<String> pages(long chapter) throws Exception {
        List<String> cached=pageCache.get(chapter);
        if(cached!=null)return cached;
        JSONObject c=chapterRow(chapter);
        ArrayList<String> pages=new ArrayList<>();
        if(c.getString("kind").equals("zip")){
            for(String n:zip(chapter,c).names())if(IMAGE.matcher(n).matches()&&!n.startsWith("__MACOSX"))pages.add(n);
            Collections.sort(pages,(a,b)->sortKey(a).compareTo(sortKey(b)));
        }else if(c.getString("kind").equals("dir")){
            File[] files=new File(c.getString("uri")).listFiles();
            if(files!=null)for(File f:files)if(IMAGE.matcher(f.getName()).matches())pages.add(f.getPath());
            Collections.sort(pages,(a,b)->sortKey(new File(a).getName()).compareTo(sortKey(new File(b).getName())));
        }else{
            Uri tree=Uri.parse(c.getString("tree"));
            ArrayList<Doc> docs=new ArrayList<>();
            for(Doc d:children(tree,c.getString("uri")))if(IMAGE.matcher(d.name).matches())docs.add(d);
            Collections.sort(docs,(a,b)->sortKey(a.name).compareTo(sortKey(b.name)));
            for(Doc d:docs)pages.add(d.id);
        }
        pageCache.put(chapter,pages);
        db.execSQL("UPDATE chapters SET pages=? WHERE id=?",new Object[]{pages.size(),chapter});
        return pages;
    }

    synchronized ZipSource zip(long chapter,JSONObject row) throws Exception {
        ZipSource z=zips.get(chapter);
        if(z==null){
            // Keep a handful of archives open; webtoon reading moves through chapters in order.
            if(zips.size()>6){Long first=zips.keySet().iterator().next();try{zips.remove(first).close();}catch(Exception ignored){}}
            z=new ZipSource(opener.open(row.getString("uri")));zips.put(chapter,z);
        }
        return z;
    }

    public Object[] page(long chapter,int index) throws Exception {
        List<String> pages=pages(chapter);
        if(index<0||index>=pages.size())return null;
        JSONObject c=chapterRow(chapter);
        String ref=pages.get(index);
        byte[] bytes;
        if(c.getString("kind").equals("zip"))bytes=zip(chapter,c).bytes(ref);
        else if(c.getString("kind").equals("dir"))bytes=java.nio.file.Files.readAllBytes(new File(ref).toPath());
        else{
            Uri doc=DocumentsContract.buildDocumentUriUsingTree(Uri.parse(c.getString("tree")),ref);
            bytes=readAll(context.getContentResolver().openInputStream(doc),128*1024*1024);
        }
        return new Object[]{bytes,Library.mime(ref)};
    }

    /** Small cover image from the first page of the first chapter, cached on disk. */
    public byte[] cover(long series) throws Exception {
        File f=new File(thumbs,series+".jpg");
        if(f.isFile())return java.nio.file.Files.readAllBytes(f.toPath());
        byte[] b=coverSource(series);
        if(b==null)return null;
        BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(b,0,b.length,o);
        int sample=1;while(o.outWidth/sample>600)sample*=2;
        o=new BitmapFactory.Options();o.inSampleSize=sample;
        Bitmap bm=BitmapFactory.decodeByteArray(b,0,b.length,o);
        if(bm==null)return b;
        // Webtoon first pages are very tall; keep a cover-shaped top slice.
        int h=Math.min(bm.getHeight(),(int)(bm.getWidth()*1.5));
        Bitmap crop=Bitmap.createBitmap(bm,0,0,bm.getWidth(),h);
        Bitmap scaled=Bitmap.createScaledBitmap(crop,300,Math.max(1,300*h/Math.max(1,bm.getWidth())),true);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG,82,out);
        java.nio.file.Files.write(f.toPath(),out.toByteArray());
        return out.toByteArray();
    }

    /**
     * Where a series cover comes from, in order: one the user chose; Mihon's cached cover (matched through the
     * backup's thumbnail URL hash); cover.jpg/png/webp in the series folder; the first page of the first chapter.
     */
    byte[] coverSource(long series) throws Exception {
        File custom=new File(thumbs,series+"-custom");
        if(custom.isFile())return java.nio.file.Files.readAllBytes(custom.toPath());
        JSONArray r=Store.rows(db,"SELECT tree,doc,thumb FROM series WHERE id=?",Long.toString(series));
        if(r.length()==0)return null;
        JSONObject s=r.getJSONObject(0);
        String thumb=s.optString("thumb");
        if(!thumb.isEmpty()){
            // Mihon's own cache isn't readable by other apps on Android 11+, but a copy can be placed in Kotoba's folder.
            for(File dir:new File[]{new File(context.getExternalFilesDir(null),"mihon-covers"),new File("/sdcard/Android/data/app.mihon/files/covers")}){
                File c=new File(dir,thumb);
                try{if(c.isFile()&&c.canRead())return java.nio.file.Files.readAllBytes(c.toPath());}catch(Exception ignored){}
            }
        }
        try{
            String tree=s.optString("tree"),doc=s.optString("doc");
            if(tree.startsWith("file://")){
                File dir=new File(Uri.parse(tree).getPath());
                for(String n:new String[]{"cover.jpg","cover.jpeg","cover.png","cover.webp"}){File c=new File(dir,n);if(c.isFile())return java.nio.file.Files.readAllBytes(c.toPath());}
            }else if(!doc.isEmpty()){
                for(Doc d:children(Uri.parse(tree),doc))
                    if(d.name.matches("(?i)cover\\.(jpe?g|png|webp)"))
                        return readAll(context.getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree),d.id)),32*1024*1024);
            }
        }catch(Exception ignored){}
        JSONArray first=Store.rows(db,"SELECT id FROM chapters WHERE series_id=? ORDER BY sort,name LIMIT 1",Long.toString(series));
        if(first.length()==0)return null;
        Object[] p=page(first.getJSONObject(0).getLong("id"),0);
        return p==null?null:(byte[])p[0];
    }
    /** A cover the user chose (image file or a page); null bytes go back to the automatic cover. */
    public void setCover(long series,byte[] image) throws Exception {
        File custom=new File(thumbs,series+"-custom");
        if(image==null)custom.delete();else java.nio.file.Files.write(custom.toPath(),image);
        new File(thumbs,series+".jpg").delete();
    }
    public void coverFromPage(long chapter,int index) throws Exception {
        Object[] p=page(chapter,index);if(p==null)throw new IllegalArgumentException("No such page");
        JSONObject c=chapterRow(chapter);setCover(c.getLong("series_id"),(byte[])p[0]);
    }
    static String md5(String s){
        try{byte[] h=java.security.MessageDigest.getInstance("MD5").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte x:h)b.append(String.format("%02x",x));return b.toString();}
        catch(Exception e){return "";}
    }

    static String titleKey(String t){return HtmlText.normalize(t).replaceAll("[\\p{Punct}\\s・：:！!？?～~「」『』()（）\\[\\]]","");}
    static String chapterKey(String name){
        // Chapter number if there is one ("Ch. 12.5", "제12화", "第12話"), otherwise the normalized name.
        java.util.regex.Matcher m=Pattern.compile("(?:ch(?:apter)?\\.?|第|제|episode|ep\\.?)\\s*(\\d+(?:[.,]\\d+)?)",Pattern.CASE_INSENSITIVE).matcher(name);
        if(m.find())return "#"+Double.parseDouble(m.group(1).replace(',','.'));
        java.util.regex.Matcher n=Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(name);
        String last=null;while(n.find())last=n.group(1);
        return last!=null?"#"+Double.parseDouble(last):HtmlText.normalize(name);
    }

    /**
     * Applies a Mihon backup to the scanned library: read state and last page per chapter, and categories.
     * Series are matched by title; titles without downloaded files are only counted.
     */
    public JSONObject importBackup(MihonBackup b) throws Exception {
        java.util.HashMap<Long,String> cats=new java.util.HashMap<>();
        for(MihonBackup.Category c:b.categories)cats.put(c.order,c.name);
        java.util.HashMap<String,Long> byTitle=new java.util.HashMap<>();
        for(JSONObject s:iter("SELECT id,title FROM series"))byTitle.put(titleKey(s.optString("title")),s.optLong("id"));
        int matched=0,chaptersUpdated=0;JSONArray unmatched=new JSONArray();
        db.beginTransaction();
        try{
            for(MihonBackup.Manga m:b.manga){
                Long sid=byTitle.get(titleKey(m.title));
                if(sid==null){if(unmatched.length()<200)unmatched.put(m.title);continue;}
                matched++;
                StringBuilder cat=new StringBuilder();
                for(long c:m.categories){String n=cats.get(c);if(n!=null){if(cat.length()>0)cat.append(", ");cat.append(n);}}
                db.execSQL("UPDATE series SET category=?,source=CASE WHEN source='' THEN ? ELSE source END,thumb=? WHERE id=?",new Object[]{cat.toString(),b.sources.getOrDefault(m.source,""),m.thumbnail.isEmpty()?"":md5(m.thumbnail),sid});
                new File(thumbs,sid+".jpg").delete();
                java.util.HashMap<String,JSONObject> chapters=new java.util.HashMap<>();
                for(JSONObject c:iter("SELECT id,name FROM chapters WHERE series_id="+sid))chapters.put(chapterKey(c.optString("name")),c);
                for(MihonBackup.Chapter c:m.chapters){
                    String key=c.number>=0?"#"+(double)c.number:chapterKey(c.name);
                    JSONObject local=chapters.get(key);
                    if(local==null)local=chapters.get(chapterKey(c.name));
                    if(local==null)continue;
                    if(c.read||c.lastPage>0){
                        db.execSQL("UPDATE chapters SET read=max(read,?),page=CASE WHEN ?>page THEN ? ELSE page END,opened=CASE WHEN opened=0 THEN ? ELSE opened END WHERE id=?",
                            new Object[]{c.read?1:0,c.lastPage,c.lastPage,now(),local.optLong("id")});
                        chaptersUpdated++;
                    }
                }
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return new JSONObject().put("manga",b.manga.size()).put("matched",matched).put("chapters",chaptersUpdated).put("unmatched",unmatched).put("categories",b.categories.size());
    }

    public JSONArray marks(long series) throws Exception {return Store.rows(db,"SELECT m.*,c.name chapter FROM comic_marks m JOIN chapters c ON c.id=m.chapter_id WHERE c.series_id=? ORDER BY c.sort,m.page",Long.toString(series));}
    public JSONObject addMark(long chapter,int page,String label) throws Exception {
        ContentValues v=new ContentValues();v.put("chapter_id",chapter);v.put("page",page);v.put("label",label);v.put("created",now());
        return new JSONObject().put("id",db.insertOrThrow("comic_marks",null,v));
    }
    public void deleteMark(long id){db.delete("comic_marks","id=?",new String[]{Long.toString(id)});}
}
