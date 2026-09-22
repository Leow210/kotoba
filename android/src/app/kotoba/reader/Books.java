package app.kotoba.reader;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Books (EPUB, TXT): import into app storage, metadata, reading position, highlights and bookmarks.
 * Tables live in the personal database so they are part of backups.
 */
public class Books {
    final Context context;
    final SQLiteDatabase db;
    final File dir;
    final Map<Long,ZipSource> zips=new HashMap<>();
    final Map<Long,BookParser.Book> texts=new HashMap<>();

    public Books(Context context,SQLiteDatabase db){
        this.context=context;this.db=db;
        dir=new File(context.getFilesDir(),"books");dir.mkdirs();
        db.execSQL("CREATE TABLE IF NOT EXISTS books(id INTEGER PRIMARY KEY,title TEXT NOT NULL,author TEXT NOT NULL DEFAULT '',lang TEXT NOT NULL DEFAULT '',format TEXT NOT NULL,file TEXT NOT NULL,cover TEXT NOT NULL DEFAULT '',meta TEXT NOT NULL,added INTEGER NOT NULL,opened INTEGER NOT NULL DEFAULT 0,progress REAL NOT NULL DEFAULT 0,position TEXT NOT NULL DEFAULT '',settings TEXT NOT NULL DEFAULT '{}')");
        db.execSQL("CREATE TABLE IF NOT EXISTS highlights(id INTEGER PRIMARY KEY,book_id INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,chapter INTEGER NOT NULL,start TEXT NOT NULL,end TEXT NOT NULL,text TEXT NOT NULL,color TEXT NOT NULL DEFAULT 'yellow',note TEXT NOT NULL DEFAULT '',created INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks(id INTEGER PRIMARY KEY,book_id INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,chapter INTEGER NOT NULL,position TEXT NOT NULL,label TEXT NOT NULL DEFAULT '',progress REAL NOT NULL DEFAULT 0,created INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS highlights_book ON highlights(book_id,chapter)");
    }

    static long now(){return System.currentTimeMillis()/1000;}

    /** Imports an EPUB or TXT given its bytes and file name. */
    public JSONObject importBook(byte[] bytes,String name) throws Exception {
        boolean zip=bytes.length>4&&bytes[0]=='P'&&bytes[1]=='K';
        String lower=name.toLowerCase(Locale.ROOT);
        if(!zip&&!(lower.endsWith(".txt")||lower.endsWith(".text")||!lower.contains(".")))throw new Exception(name+": only EPUB and TXT books are supported.");
        // Reserve an id first so the file can be named after it.
        ContentValues v=new ContentValues();
        v.put("title",name);v.put("format",zip?"epub":"txt");v.put("file","");v.put("meta","{}");v.put("added",now());
        long id=db.insertOrThrow("books",null,v);
        try{
            BookParser.Book book;
            File file;
            if(zip){
                file=new File(dir,id+".epub");
                write(file,bytes);
                try(ZipSource z=new ZipSource(new java.io.FileInputStream(file).getChannel())){book=BookParser.epub(z);}
            }else{
                book=BookParser.txt(bytes,name);
                file=new File(dir,id+".txt");
                write(file,book.text.getBytes(StandardCharsets.UTF_8));
            }
            ContentValues u=new ContentValues();
            u.put("title",book.title.isEmpty()?name.replaceFirst("\\.[^.]+$",""):book.title);
            u.put("author",book.author);u.put("lang",book.language);u.put("file",file.getName());u.put("cover",book.cover);
            u.put("meta",meta(book).toString());
            db.update("books",u,"id=?",new String[]{Long.toString(id)});
            return new JSONObject().put("id",id).put("title",u.getAsString("title"));
        }catch(Exception e){
            db.delete("books","id=?",new String[]{Long.toString(id)});
            throw new Exception(name+": "+e.getMessage(),e);
        }
    }

    static void write(File f,byte[] b) throws Exception {try(FileOutputStream out=new FileOutputStream(f)){out.write(b);}}

    static JSONObject meta(BookParser.Book b) throws Exception {
        JSONArray spine=new JSONArray(),toc=new JSONArray(),ranges=new JSONArray();
        for(BookParser.Spine s:b.spine)spine.put(new JSONObject().put("href",s.href).put("size",s.size).put("linear",s.linear));
        for(BookParser.Toc t:b.toc)toc.put(new JSONObject().put("title",t.title).put("href",t.href).put("level",t.level));
        for(int[] r:b.ranges)ranges.put(new JSONArray().put(r[0]).put(r[1]));
        return new JSONObject().put("spine",spine).put("toc",toc).put("direction",b.direction).put("writing",b.writing).put("language",b.language).put("encoding",b.encoding).put("ranges",ranges);
    }

    public JSONArray list() throws Exception {
        return Store.rows(db,"SELECT id,title,author,lang,format,cover!='' has_cover,added,opened,progress FROM books WHERE file!='' ORDER BY opened=0,opened DESC,added DESC");
    }

    public JSONObject open(long id) throws Exception {
        JSONArray r=Store.rows(db,"SELECT * FROM books WHERE id=?",Long.toString(id));
        if(r.length()==0)throw new Exception("This book is no longer in your library.");
        JSONObject b=r.getJSONObject(0);
        b.put("meta",new JSONObject(b.getString("meta")));
        b.put("settings",new JSONObject(b.optString("settings","{}")));
        db.execSQL("UPDATE books SET opened=? WHERE id=?",new Object[]{now(),id});
        return b;
    }

    public void savePosition(long id,String position,double progress){
        db.execSQL("UPDATE books SET position=?,progress=?,opened=? WHERE id=?",new Object[]{position,progress,now(),id});
    }
    public void saveSettings(long id,JSONObject settings){
        db.execSQL("UPDATE books SET settings=? WHERE id=?",new Object[]{settings.toString(),id});
    }
    public void rename(long id,String title) throws Exception {
        if(title.trim().isEmpty())throw new Exception("Add a title.");
        db.execSQL("UPDATE books SET title=? WHERE id=?",new Object[]{title.trim(),id});
    }

    public synchronized void delete(long id) throws Exception {
        JSONArray r=Store.rows(db,"SELECT file FROM books WHERE id=?",Long.toString(id));
        ZipSource z=zips.remove(id);if(z!=null)try{z.close();}catch(Exception ignored){}
        texts.remove(id);
        if(r.length()>0&&!r.getJSONObject(0).getString("file").isEmpty())new File(dir,r.getJSONObject(0).getString("file")).delete();
        db.execSQL("DELETE FROM highlights WHERE book_id=?",new Object[]{id});
        db.execSQL("DELETE FROM bookmarks WHERE book_id=?",new Object[]{id});
        db.execSQL("DELETE FROM books WHERE id=?",new Object[]{id});
    }

    synchronized ZipSource zip(long id) throws Exception {
        ZipSource z=zips.get(id);
        if(z!=null)return z;
        JSONArray r=Store.rows(db,"SELECT file FROM books WHERE id=?",Long.toString(id));
        if(r.length()==0)throw new Exception("Book not found");
        FileChannel ch=new java.io.FileInputStream(new File(dir,r.getJSONObject(0).getString("file"))).getChannel();
        z=new ZipSource(ch);zips.put(id,z);
        return z;
    }

    synchronized BookParser.Book text(long id) throws Exception {
        BookParser.Book b=texts.get(id);
        if(b!=null)return b;
        JSONObject row=Store.rows(db,"SELECT * FROM books WHERE id=?",Long.toString(id)).getJSONObject(0);
        JSONObject meta=new JSONObject(row.getString("meta"));
        b=new BookParser.Book();b.format="txt";b.title=row.getString("title");
        byte[] bytes=java.nio.file.Files.readAllBytes(new File(dir,row.getString("file")).toPath());
        b.text=new String(bytes,StandardCharsets.UTF_8);
        JSONArray ranges=meta.getJSONArray("ranges"),toc=meta.getJSONArray("toc");
        for(int i=0;i<ranges.length();i++){
            b.ranges.add(new int[]{ranges.getJSONArray(i).getInt(0),ranges.getJSONArray(i).getInt(1)});
            b.toc.add(new BookParser.Toc(toc.getJSONObject(i).getString("title"),toc.getJSONObject(i).getString("href"),0));
        }
        texts.put(id,b);
        return b;
    }

    /** Bytes and MIME type for /book/<id>/<path>. Chapter documents get the reader stylesheet and lose scripts. */
    public Object[] resource(long id,String path) throws Exception {
        JSONArray r=Store.rows(db,"SELECT format,cover FROM books WHERE id=?",Long.toString(id));
        if(r.length()==0)return null;
        String format=r.getJSONObject(0).getString("format");
        if(path.equals("cover")){
            String cover=r.getJSONObject(0).getString("cover");
            if(cover.isEmpty()||!format.equals("epub"))return null;
            return new Object[]{zip(id).bytes(cover),Library.mime(cover)};
        }
        if(format.equals("txt")){
            java.util.regex.Matcher m=java.util.regex.Pattern.compile("txt/(\\d+)\\.xhtml").matcher(path);
            if(!m.matches())return null;
            return new Object[]{inject(BookParser.txtChapter(text(id),Integer.parseInt(m.group(1)))).getBytes(StandardCharsets.UTF_8),"application/xhtml+xml"};
        }
        ZipSource z=zip(id);
        ZipSource.Entry e=z.find(path);
        if(e==null)return null;
        byte[] bytes=z.bytes(e);
        String lower=path.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".xhtml")||lower.endsWith(".html")||lower.endsWith(".htm")||lower.endsWith(".xml")){
            String html=inject(new String(bytes,StandardCharsets.UTF_8));
            // Serve as XHTML when it parses as XML (keeps EPUB semantics), otherwise as forgiving HTML.
            String mime="text/html";
            if(!lower.endsWith(".htm")){try{BookParser.xml(html.getBytes(StandardCharsets.UTF_8));mime="application/xhtml+xml";}catch(Exception ignored){}}
            return new Object[]{html.getBytes(StandardCharsets.UTF_8),mime};
        }
        String mime=lower.endsWith(".css")?"text/css":lower.endsWith(".svg")?"image/svg+xml":Library.mime(path);
        if(mime.equals("application/octet-stream")&&(lower.endsWith(".ttf")||lower.endsWith(".otf")))mime="font/ttf";
        return new Object[]{bytes,mime};
    }

    static String inject(String html){
        String h=html.replaceAll("(?is)<script\\b.*?</script>","").replaceAll("(?is)<script\\b[^>]*/>","");
        String link="<link rel=\"stylesheet\" type=\"text/css\" href=\"/reader-base.css\"/>";
        int head=h.toLowerCase(Locale.ROOT).indexOf("</head>");
        if(head>=0)return h.substring(0,head)+link+h.substring(head);
        int body=h.toLowerCase(Locale.ROOT).indexOf("<body");
        return body>=0?h.substring(0,body)+"<head>"+link+"</head>"+h.substring(body):link+h;
    }

    // ---------- highlights & bookmarks ----------

    public JSONArray highlights(long book) throws Exception {return Store.rows(db,"SELECT * FROM highlights WHERE book_id=? ORDER BY chapter,id",Long.toString(book));}
    public JSONObject saveHighlight(JSONObject d) throws Exception {
        ContentValues v=new ContentValues();
        for(String k:new String[]{"start","end","text","color","note"})if(d.has(k))v.put(k,d.getString(k));
        if(d.optLong("id",0)>0){db.update("highlights",v,"id=?",new String[]{Long.toString(d.getLong("id"))});return new JSONObject().put("id",d.getLong("id"));}
        v.put("book_id",d.getLong("book"));v.put("chapter",d.getInt("chapter"));v.put("created",now());
        if(!v.containsKey("color"))v.put("color","yellow");
        return new JSONObject().put("id",db.insertOrThrow("highlights",null,v));
    }
    public void deleteHighlight(long id){db.delete("highlights","id=?",new String[]{Long.toString(id)});}

    public JSONArray bookmarks(long book) throws Exception {return Store.rows(db,"SELECT * FROM bookmarks WHERE book_id=? ORDER BY chapter,progress,id",Long.toString(book));}
    public JSONObject saveBookmark(JSONObject d) throws Exception {
        ContentValues v=new ContentValues();
        v.put("book_id",d.getLong("book"));v.put("chapter",d.getInt("chapter"));v.put("position",d.getString("position"));
        v.put("label",d.optString("label",""));v.put("progress",d.optDouble("progress",0));v.put("created",now());
        return new JSONObject().put("id",db.insertOrThrow("bookmarks",null,v));
    }
    public void deleteBookmark(long id){db.delete("bookmarks","id=?",new String[]{Long.toString(id)});}

    public String exportHighlights(long book) throws Exception {
        JSONObject b=Store.rows(db,"SELECT title,author,meta FROM books WHERE id=?",Long.toString(book)).getJSONObject(0);
        JSONArray toc=new JSONObject(b.getString("meta")).optJSONArray("toc");
        StringBuilder out=new StringBuilder("# ").append(b.getString("title")).append("\n");
        if(!b.getString("author").isEmpty())out.append("_").append(b.getString("author")).append("_\n");
        JSONArray hs=highlights(book);
        for(int i=0;i<hs.length();i++){
            JSONObject h=hs.getJSONObject(i);
            out.append("\n> ").append(h.getString("text").replace("\n","\n> ")).append("\n");
            if(!h.getString("note").isEmpty())out.append("\n").append(h.getString("note")).append("\n");
        }
        return out.toString();
    }
}
