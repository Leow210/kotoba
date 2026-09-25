package app.kotoba.reader;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Imported dictionaries. Entry and resource bytes stay in the user's MDX/MDD files and are read on demand;
 * the database only holds the key index, anchor index and full-text index.
 */
public class Library {
    public interface Opener { FileChannel open(String uri) throws IOException; }
    public interface Progress { void update(String stage,long done,long total); boolean cancelled(); }

    final SQLiteDatabase db;
    final Opener opener;
    final Map<String,MdictFile> open=new HashMap<>();
    final MdictFile.BlockCache cache=new MdictFile.BlockCache(24);
    static final Pattern ID=Pattern.compile("\\sid=[\"']([^\"']+)[\"']");
    static final Pattern ENTRY_LINK=Pattern.compile("href=[\"']entry://([^\"'#]+)");
    static final Pattern THE2_TITLE=Pattern.compile("<div class=\"the2-title\"[^>]*>\\s*<nid>(.*?)</nid>(.*?)</div>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
    static final Pattern THE2_WORD=Pattern.compile("<a[^>]*class=\"the2-word-link\"[^>]*>(.*?)</a>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
    static final Pattern THE2_ANCESTOR=Pattern.compile("<a[^>]*class=\"the2-ancestor\"[^>]*>(.*?)</a>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);

    final android.content.res.AssetManager assets;
    Map<Integer,Integer> t2s;

    /** Traditional → simplified characters (OpenCC TSCharacters, one pair per two characters in assets/t2s.txt). */
    synchronized String simplified(String text){
        if(t2s==null){
            t2s=new HashMap<>();
            try(java.io.InputStream in=assets.open("t2s.txt")){
                java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buf=new byte[65536];int n;
                while((n=in.read(buf))>0)bytes.write(buf,0,n);
                int[] cp=new String(bytes.toByteArray(),StandardCharsets.UTF_8).codePoints().toArray();
                for(int i=0;i+1<cp.length;i+=2)t2s.put(cp[i],cp[i+1]);
            }catch(Exception ignored){}
        }
        StringBuilder b=new StringBuilder(text.length());
        text.codePoints().forEach(c->b.appendCodePoint(t2s.getOrDefault(c,c)));
        return b.toString();
    }

    Map<Integer,Integer> toJapanese;
    /**
     * Japanese text that came through a simplified-Chinese conversion (NetEase lyrics: 気→气, 変→变, 楽→乐) with its
     * kanji put back: a character that isn't a Japanese kanji (漢検's list) goes to its traditional form (OpenCC) and
     * from there to the Japanese kanji that has it as an old form (氣 → 気), 常用 first. Kanji dictionaries needed.
     */
    public synchronized String japaneseKanji(String text) throws Exception {
        if(toJapanese==null){
            toJapanese=new HashMap<>();
            simplified("");// loads t2s
            java.util.Set<Integer> jp=new java.util.HashSet<>();
            Map<Integer,Integer> fromOld=new HashMap<>();
            // Japanese kanji: 漢検's list. Old forms: 漢辞海's variant characters (変: 變); 漢検 only labels them 旧字.
            JSONArray rows=Store.rows(db,"SELECT char FROM kanji WHERE level!=''");
            for(int i=0;i<rows.length();i++)jp.add(rows.getJSONObject(i).getString("char").codePointAt(0));
            rows=Store.rows(db,"SELECT char,variants FROM kanji WHERE level='' AND variants!='' ORDER BY flags NOT LIKE '%常%'");
            for(int i=0;i<rows.length();i++){
                JSONObject r=rows.getJSONObject(i);
                int c=r.getString("char").codePointAt(0);
                if(!jp.contains(c))continue;
                r.getString("variants").codePoints().filter(Library::han).forEach(v->fromOld.putIfAbsent(v,c));
            }
            if(jp.isEmpty())return text;
            Map<Integer,java.util.List<Integer>> fromSimp=new HashMap<>();
            for(Map.Entry<Integer,Integer> e:t2s.entrySet())fromSimp.computeIfAbsent(e.getValue(),k->new ArrayList<>()).add(e.getKey());
            for(Map.Entry<Integer,java.util.List<Integer>> e:fromSimp.entrySet()){
                if(jp.contains(e.getKey()))continue;// also a Japanese kanji: leave it
                Integer best=null;
                for(int t:e.getValue()){Integer j=jp.contains(t)?Integer.valueOf(t):fromOld.get(t);if(j!=null){best=j;break;}}
                if(best!=null)toJapanese.put(e.getKey(),best);
            }
        }
        StringBuilder b=new StringBuilder(text.length());
        text.codePoints().forEach(c->b.appendCodePoint(toJapanese.getOrDefault(c,c)));
        return b.toString();
    }

    public Library(Context context,Opener opener){
        this.opener=opener;
        this.assets=context.getAssets();
        db=SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("library.sqlite3").getPath(),null);
        db.enableWriteAheadLogging();
        db.execSQL("CREATE TABLE IF NOT EXISTS dicts(id INTEGER PRIMARY KEY,name TEXT NOT NULL,title TEXT NOT NULL DEFAULT '',description TEXT NOT NULL DEFAULT '',mdx TEXT NOT NULL,mdd TEXT NOT NULL DEFAULT '[]',label TEXT NOT NULL DEFAULT '',kind TEXT NOT NULL DEFAULT 'term',position INTEGER NOT NULL DEFAULT 0,enabled INTEGER NOT NULL DEFAULT 1,entries INTEGER NOT NULL DEFAULT 0,keys INTEGER NOT NULL DEFAULT 0,resources INTEGER NOT NULL DEFAULT 0,fulltext INTEGER NOT NULL DEFAULT 0,status TEXT NOT NULL DEFAULT 'importing',imported INTEGER NOT NULL DEFAULT 0,key_min INTEGER NOT NULL DEFAULT 0,key_max INTEGER NOT NULL DEFAULT 0,rec_min INTEGER NOT NULL DEFAULT 0,rec_max INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS records(id INTEGER PRIMARY KEY,dict INTEGER NOT NULL,off INTEGER NOT NULL,len INTEGER NOT NULL,key TEXT NOT NULL,norm TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS keys(id INTEGER PRIMARY KEY,norm TEXT NOT NULL,dict INTEGER NOT NULL,rec INTEGER NOT NULL,key TEXT NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS keys_norm ON keys(norm,dict)");
        db.execSQL("CREATE INDEX IF NOT EXISTS records_norm ON records(dict,norm)");
        db.execSQL("CREATE TABLE IF NOT EXISTS anchors(dict INTEGER NOT NULL,anchor TEXT NOT NULL,rec INTEGER NOT NULL,PRIMARY KEY(dict,anchor)) WITHOUT ROWID");
        db.execSQL("CREATE TABLE IF NOT EXISTS kanji(dict INTEGER NOT NULL,rec INTEGER NOT NULL,char TEXT NOT NULL,strokes INTEGER NOT NULL DEFAULT 0,radical TEXT NOT NULL DEFAULT '',rstrokes INTEGER NOT NULL DEFAULT -1,level TEXT NOT NULL DEFAULT '',flags TEXT NOT NULL DEFAULT '',variants TEXT NOT NULL DEFAULT '',sortkey TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX IF NOT EXISTS kanji_dict ON kanji(dict,sortkey)");
        db.execSQL("CREATE INDEX IF NOT EXISTS kanji_char ON kanji(char)");
        db.execSQL("CREATE TABLE IF NOT EXISTS resources(dict INTEGER NOT NULL,name TEXT NOT NULL,file INTEGER NOT NULL,off INTEGER NOT NULL,len INTEGER NOT NULL,PRIMARY KEY(dict,name)) WITHOUT ROWID");
        try{db.execSQL("ALTER TABLE dicts ADD COLUMN grp TEXT NOT NULL DEFAULT ''");}catch(Exception ignored){}
        // Yomitan dictionaries: entries are stored here (deflated HTML) rather than read from the ZIP, which is JSON.
        try{db.execSQL("ALTER TABLE dicts ADD COLUMN format TEXT NOT NULL DEFAULT 'mdx'");}catch(Exception ignored){}
        db.execSQL("CREATE TABLE IF NOT EXISTS ytext(rec INTEGER PRIMARY KEY,reading TEXT NOT NULL DEFAULT '',tags TEXT NOT NULL DEFAULT '',body BLOB NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS ydict(dict INTEGER PRIMARY KEY,zdict BLOB,styles INTEGER NOT NULL DEFAULT 0)");
        // Frequency ranks, pitch accents and IPA from Yomitan term_meta banks.
        db.execSQL("CREATE TABLE IF NOT EXISTS meta(dict INTEGER NOT NULL,norm TEXT NOT NULL,reading TEXT NOT NULL DEFAULT '',mode TEXT NOT NULL,value REAL,display TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX IF NOT EXISTS meta_norm ON meta(norm,dict)");
        // The word as the dictionary spells it (norm folds katakana), and rank order for browsing a frequency list.
        try{db.execSQL("ALTER TABLE meta ADD COLUMN term TEXT NOT NULL DEFAULT ''");}catch(Exception ignored){}
        db.execSQL("CREATE INDEX IF NOT EXISTS meta_rank ON meta(dict,mode,value)");
        // Index version: 1 = heading spellings (【落(ち)合う】) and separator-free headings (おちあ・う → おちあう) are keys too.
        try{db.execSQL("ALTER TABLE dicts ADD COLUMN keys_v INTEGER NOT NULL DEFAULT 0");}catch(Exception ignored){}
        // Byte sizes of the dictionary's files ([mdx, mdd…]): a moved file is only relinked to an identical one.
        try{db.execSQL("ALTER TABLE dicts ADD COLUMN sizes TEXT NOT NULL DEFAULT ''");}catch(Exception ignored){}
        // Saved cards refer to dictionaries by id; a dictionary re-imported under the same title keeps its id.
        db.execSQL("CREATE TABLE IF NOT EXISTS dict_ids(title TEXT PRIMARY KEY,id INTEGER NOT NULL)");
        db.execSQL("INSERT OR IGNORE INTO dict_ids(title,id) SELECT title,id FROM dicts WHERE status='ready'");
        try(Cursor c=db.rawQuery("SELECT id,title,kind FROM dicts WHERE grp=''",null)){
            while(c.moveToNext())db.execSQL("UPDATE dicts SET grp=? WHERE id=?",new Object[]{groupFor(c.getString(1),c.getString(2)),c.getLong(0)});
        }
        try(Cursor c=db.rawQuery("SELECT id FROM dicts WHERE sizes='' AND status='ready'",null)){
            ArrayList<Long> ids=new ArrayList<>();while(c.moveToNext())ids.add(c.getLong(0));
            for(long id:ids)try{fileSizes(id);}catch(Exception ignored){}
        }
        db.execSQL("UPDATE dicts SET grp=? WHERE grp='Pronunciation'",new Object[]{PRONUNCIATION});
        // Interrupted imports are removed at startup so a half-built index is never searched.
        try(Cursor c=db.rawQuery("SELECT id FROM dicts WHERE status!='ready'",null)){
            ArrayList<Long> stale=new ArrayList<>();while(c.moveToNext())stale.add(c.getLong(0));
            for(long id:stale)delete(id);
        }
    }

    // ---------- files ----------

    synchronized MdictFile file(long dict,int index) throws Exception {
        String k=dict+":"+index;
        MdictFile f=open.get(k);
        if(f!=null)return f;
        JSONObject d=dictRow(dict);
        String uri=index==0?d.getString("mdx"):new JSONArray(d.getString("mdd")).getString(index-1);
        try{f=new MdictFile(opener.open(uri),index>0);}
        catch(IOException|SecurityException e){throw new Exception("Cannot open the dictionary file for "+d.getString("name")+". If you moved or deleted it, import it again from Library.",e);}
        open.put(k,f);
        return f;
    }

    synchronized void closeFiles(long dict){
        ZipSource z=zips.remove(dict);if(z!=null)try{z.close();}catch(IOException ignored){}
        for(java.util.Iterator<Map.Entry<String,MdictFile>> it=open.entrySet().iterator();it.hasNext();){
            Map.Entry<String,MdictFile> e=it.next();
            if(e.getKey().startsWith(dict+":")){try{e.getValue().close();}catch(IOException ignored){}it.remove();}
        }
    }

    JSONObject dictRow(long id) throws Exception {
        JSONArray rows=Store.rows(db,"SELECT * FROM dicts WHERE id=?",Long.toString(id));
        if(rows.length()==0)throw new Exception("Dictionary not found");
        return rows.getJSONObject(0);
    }

    // ---------- import ----------

    /** Default search group from the dictionary's title; users can change it in Library. */
    static String groupFor(String title,String kind){
        if("kanji".equals(kind))return "Kanji";
        if(title.matches(".*(シソーラス|類語).*"))return "Japanese/類語";
        if(title.matches(".*(アクセント|発音|Accent|accent|NHK|Pronunc).*"))return PRONUNCIATION;
        if(title.matches(".*(朝鮮|韓|Korean|한국).*"))return "Korean";
        if(title.matches(".*(中日|日中|中国|Chinese|汉).*"))return "Chinese";
        if(title.matches(".*(タイ|Thai|ไทย).*"))return "Thai";
        if(title.matches(".*(ロシア|露|Russian|Русск).*"))return "Russian";
        if(title.matches(".*(英和|和英|English).*"))return "English";
        return "Japanese";
    }

    static String kindFor(String title){
        return title.matches(".*(漢字|漢和|漢辞|字典|字源|Kanji|kanji|KANJI).*")?"kanji":"term";
    }

    /** Imports one dictionary in a single transaction; on failure nothing is left behind. */
    public long importDictionary(String name,String mdxUri,List<String> mddUris,boolean fulltext,Progress progress) throws Exception {
        MdictFile mdx=new MdictFile(opener.open(mdxUri),false);
        String title=mdx.title().isEmpty()?name:mdx.title();
        long id;
        ContentValues v=new ContentValues();
        v.put("name",title);v.put("title",title);v.put("description",mdx.description());v.put("mdx",mdxUri);
        v.put("mdd",new JSONArray(mddUris).toString());v.put("label",name);v.put("kind",kindFor(title));v.put("grp",groupFor(title,kindFor(title)));
        v.put("position",Store.rows(db,"SELECT coalesce(max(position),0)+1 p FROM dicts").getJSONObject(0).getLong("p"));
        v.put("fulltext",fulltext?1:0);v.put("status","importing");v.put("imported",System.currentTimeMillis()/1000);
        JSONArray previous=Store.rows(db,"SELECT id FROM dict_ids WHERE title=? AND id NOT IN (SELECT id FROM dicts)",title);
        if(previous.length()>0)v.put("id",previous.getJSONObject(0).getLong("id"));
        else v.put("id",Store.rows(db,"SELECT max(coalesce((SELECT max(id) FROM dicts),0),coalesce((SELECT max(id) FROM dict_ids),0))+1 n").getJSONObject(0).getLong("n"));
        id=db.insertOrThrow("dicts",null,v);
        db.execSQL("INSERT OR IGNORE INTO dict_ids(title,id) VALUES(?,?)",new Object[]{title,id});
        String fts="body_"+id;
        boolean ok=false;
        db.beginTransaction();
        try{
            if(fulltext)db.execSQL("CREATE VIRTUAL TABLE "+fts+" USING fts4(content=\"\",defs,exs,tokenize=simple)");
            SQLiteStatement insertRecord=db.compileStatement("INSERT INTO records(dict,off,len,key,norm) VALUES(?,?,?,?,?)");
            SQLiteStatement insertKey=db.compileStatement("INSERT INTO keys(norm,dict,rec,key) VALUES(?,?,?,?)");
            SQLiteStatement insertAnchor=db.compileStatement("INSERT OR IGNORE INTO anchors(dict,anchor,rec) VALUES(?,?,?)");
            final boolean kanjiDict="kanji".equals(kindFor(title));
            SQLiteStatement insertKanji=db.compileStatement("INSERT INTO kanji(dict,rec,char,strokes,radical,rstrokes,level,flags,variants,sortkey) VALUES(?,?,?,?,?,?,?,?,?,?)");
            SQLiteStatement insertBody=fulltext?db.compileStatement("INSERT INTO "+fts+"(docid,defs,exs) VALUES(?,?,?)"):null;
            db.execSQL("CREATE TEMP TABLE IF NOT EXISTS links(norm TEXT,key TEXT,target TEXT)");
            db.execSQL("DELETE FROM temp.links");
            SQLiteStatement insertLink=db.compileStatement("INSERT INTO temp.links(norm,key,target) VALUES(?,?,?)");
            final long total=mdx.entryCount;
            final long[] seen={0,Long.MAX_VALUE,0,0};// processed, first key row id, records, keys
            final ArrayList<String> group=new ArrayList<>();
            final long[] groupOffset={-1};
            // Record lengths come from the next larger offset, so files whose keys are not in record order still work.
            final long[][] offsetsHolder={new long[(int)Math.min(Math.max(total,16),50_000_000L)]};
            final int[] offsetCount={0};
            mdx.keys((key,offset)->{
                if(offsetCount[0]==offsetsHolder[0].length)offsetsHolder[0]=java.util.Arrays.copyOf(offsetsHolder[0],offsetCount[0]*2);
                offsetsHolder[0][offsetCount[0]++]=offset;
            });
            final long[] offsets=java.util.Arrays.copyOf(offsetsHolder[0],offsetCount[0]);
            offsetsHolder[0]=null;
            java.util.Arrays.sort(offsets);
            final long dictId=id;
            MdictFile.BlockCache importCache=new MdictFile.BlockCache(3);
            class Flush {
                void run() throws Exception {
                    if(group.isEmpty())return;
                    long off=groupOffset[0];
                    int at=java.util.Arrays.binarySearch(offsets,off);
                    while(at>=0&&at+1<offsets.length&&offsets[at+1]==off)at++;
                    long end=at>=0&&at+1<offsets.length?offsets[at+1]:mdx.recordTotal;
                    int len=(int)Math.max(0,Math.min(end-off,64L*1024*1024));
                    String html=mdx.text(mdx.record(off,len,importCache));
                    String trimmed=html.trim();
                    if(trimmed.length()<6000&&trimmed.startsWith("<div class='mdict-disambiguation'>")){
                        // Converter-made "which one?" pages: point the key straight at every target entry instead.
                        Matcher t=ENTRY_LINK.matcher(trimmed);
                        while(t.find()){
                            String target=HtmlText.normalize(HtmlText.entities(t.group(1)));
                            for(String key:group){insertLink.bindString(1,HtmlText.normalize(key));insertLink.bindString(2,key);insertLink.bindString(3,target);insertLink.executeInsert();}
                        }
                        group.clear();
                        return;
                    }
                    if(trimmed.startsWith("@@@LINK=")){
                        html=trimmed;
                        String target=html.substring(8).trim();
                        int nl=target.indexOf('\n');if(nl>=0)target=target.substring(0,nl).trim();
                        String targetNorm=HtmlText.normalize(target);
                        for(String key:group){
                            insertLink.bindString(1,HtmlText.normalize(key));insertLink.bindString(2,key);insertLink.bindString(3,targetNorm);insertLink.executeInsert();
                        }
                    }else{
                        insertRecord.bindLong(1,dictId);insertRecord.bindLong(2,off);insertRecord.bindLong(3,len);
                        insertRecord.bindString(4,group.get(0));insertRecord.bindString(5,HtmlText.normalize(group.get(0)));
                        long rec=insertRecord.executeInsert();
                        seen[2]++;
                        LinkedHashSet<String> unique=new LinkedHashSet<>(group);
                        unique.addAll(extraKeys(html,group));
                        for(String key:unique){
                            String norm=HtmlText.normalize(key);if(norm.isEmpty())continue;
                            insertKey.bindString(1,norm);insertKey.bindLong(2,dictId);insertKey.bindLong(3,rec);insertKey.bindString(4,key.trim());
                            long kid=insertKey.executeInsert();if(kid<seen[1])seen[1]=kid;seen[3]++;
                        }
                        Matcher m=ID.matcher(html);
                        LinkedHashSet<String> anchors=new LinkedHashSet<>();
                        while(m.find()&&anchors.size()<200){
                            String a=m.group(1);if(a.equals("index"))continue;
                            int dash=a.indexOf('-');anchors.add(dash>0?a.substring(0,dash):a);
                        }
                        for(String a:anchors){insertAnchor.bindLong(1,dictId);insertAnchor.bindString(2,a);insertAnchor.bindLong(3,rec);insertAnchor.executeInsert();}
                        if(kanjiDict){
                            for(String[] k:kanjiMeta(html)){
                                insertKanji.bindLong(1,dictId);insertKanji.bindLong(2,rec);insertKanji.bindString(3,k[0]);insertKanji.bindLong(4,parseIntOr(k[1],0));
                                insertKanji.bindString(5,k[2]);insertKanji.bindLong(6,parseIntOr(k[3],-1));insertKanji.bindString(7,k[4]);insertKanji.bindString(8,k[5]);insertKanji.bindString(9,k[6]);
                                insertKanji.bindString(10,String.format(Locale.ROOT,"%03d%05d",parseIntOr(k[1],999),k[0].codePointAt(0)%100000));
                                insertKanji.executeInsert();
                                // Variant characters (舊→旧, 國→国) become searchable keys of the same page.
                                for(int cp:k[6].codePoints().toArray()){
                                    String v=new String(Character.toChars(cp));
                                    insertKey.bindString(1,HtmlText.normalize(v));insertKey.bindLong(2,dictId);insertKey.bindLong(3,rec);insertKey.bindString(4,v);insertKey.executeInsert();
                                }
                            }
                        }
                        if(insertBody!=null){
                            HtmlText text=HtmlText.parse(html);
                            insertBody.bindLong(1,rec);
                            insertBody.bindString(2,HtmlText.tokens(HtmlText.normalize(text.definitions.toString()),200000));
                            insertBody.bindString(3,HtmlText.tokens(HtmlText.normalize(text.examples.toString()),200000));
                            insertBody.executeInsert();
                        }
                    }
                    group.clear();
                }
            }
            Flush flush=new Flush();
            mdx.keys((key,offset)->{
                if(offset!=groupOffset[0]){
                    flush.run();
                    groupOffset[0]=offset;
                }
                group.add(key);
                seen[0]++;
                if(seen[0]%2000==0){
                    if(progress.cancelled())throw new InterruptedException("Import cancelled");
                    progress.update("Indexing entries",seen[0],total);
                }
            });
            flush.run();
            progress.update("Linking alternate headwords",total,total);
            // Resolve @@@LINK aliases, including links to links.
            for(int round=0;round<3;round++){
                db.execSQL("INSERT INTO keys(norm,dict,rec,key) SELECT DISTINCT l.norm,?,k.rec,l.key FROM temp.links l JOIN keys k ON k.norm=l.target AND k.dict=?",new Object[]{id,id});
                db.execSQL("DELETE FROM temp.links WHERE EXISTS(SELECT 1 FROM keys k WHERE k.norm=temp.links.norm AND k.dict=? AND k.key=temp.links.key)",new Object[]{id});
            }
            db.execSQL("DELETE FROM temp.links");
            // Pages whose own key is an internal ID (SMK8's @smk8-…) take their first real headword as a label.
            db.execSQL("DROP TABLE IF EXISTS temp.labels");
            db.execSQL("CREATE TEMP TABLE labels(rec INTEGER PRIMARY KEY,key TEXT,norm TEXT)");
            db.execSQL("INSERT OR IGNORE INTO temp.labels SELECT rec,key,norm FROM keys WHERE id>=? AND dict=? AND key NOT LIKE '@%' ORDER BY length(key),id",new Object[]{seen[1]==Long.MAX_VALUE?0:seen[1],id});
            db.execSQL("UPDATE records SET key=(SELECT l.key FROM temp.labels l WHERE l.rec=records.id),norm=(SELECT l.norm FROM temp.labels l WHERE l.rec=records.id) WHERE dict=? AND key LIKE '@%' AND id IN (SELECT rec FROM temp.labels)",new Object[]{id});
            db.execSQL("DROP TABLE temp.labels");
            db.execSQL("DELETE FROM keys WHERE dict=? AND key LIKE '@%' AND id>=?",new Object[]{id,seen[1]==Long.MAX_VALUE?0:seen[1]});
            long resources=0;
            for(int i=0;i<mddUris.size();i++){
                progress.update("Indexing images and audio",0,1);
                MdictFile mdd=new MdictFile(opener.open(mddUris.get(i)),true);
                SQLiteStatement insertResource=db.compileStatement("INSERT OR REPLACE INTO resources(dict,name,file,off,len) VALUES(?,?,?,?,?)");
                final ArrayList<Object[]> pending=new ArrayList<>();
                final int fileIndex=i+1;
                mdd.keys((key,offset)->pending.add(new Object[]{key,offset}));
                pending.sort((a,b)->Long.compare((Long)a[1],(Long)b[1]));
                for(int k=0;k<pending.size();k++){
                    long off=(Long)pending.get(k)[1];
                    long end=mdd.recordTotal;
                    for(int j=k+1;j<pending.size();j++){long o=(Long)pending.get(j)[1];if(o>off){end=o;break;}}
                    insertResource.bindLong(1,id);insertResource.bindString(2,resourceName((String)pending.get(k)[0]));
                    insertResource.bindLong(3,fileIndex);insertResource.bindLong(4,off);insertResource.bindLong(5,end-off);
                    insertResource.executeInsert();resources++;
                    if(k%5000==0){progress.update("Indexing images and audio",k,pending.size());if(progress.cancelled())throw new InterruptedException("Import cancelled");}
                }
                mdd.close();
            }
            if(fulltext){progress.update("Optimizing search index",1,1);db.execSQL("INSERT INTO "+fts+"("+fts+") VALUES('optimize')");}
            JSONObject range=Store.rows(db,"SELECT min(id) a,max(id) b,count(*) n FROM keys WHERE id>=? AND dict=?",Long.toString(seen[1]==Long.MAX_VALUE?0:seen[1]),Long.toString(id)).getJSONObject(0);
            JSONObject recs=Store.rows(db,"SELECT min(id) a,max(id) b,count(*) n FROM records WHERE dict=?",Long.toString(id)).getJSONObject(0);
            ContentValues done=new ContentValues();
            dictionariesChanged();
            done.put("status","ready");done.put("entries",recs.getLong("n"));done.put("keys",range.getLong("n"));done.put("resources",resources);done.put("keys_v",KEYS_VERSION);
            done.put("key_min",range.optLong("a",0));done.put("key_max",range.optLong("b",0));done.put("rec_min",recs.optLong("a",0));done.put("rec_max",recs.optLong("b",0));
            db.update("dicts",done,"id=?",new String[]{Long.toString(id)});
            db.setTransactionSuccessful();
            ok=true;
        }finally{
            db.endTransaction();
            mdx.close();
            if(!ok){try{delete(id);}catch(Exception ignored){}}
        }
        return id;
    }

    // ---------- Yomitan ----------

    final Map<Long,String> formats=new java.util.concurrent.ConcurrentHashMap<>();
    final Map<Long,ZipSource> zips=new HashMap<>();
    final Map<Long,byte[]> zdicts=new java.util.concurrent.ConcurrentHashMap<>();

    public boolean isYomitan(long dict){
        String f=formats.get(dict);
        if(f==null){
            try(Cursor c=db.rawQuery("SELECT format FROM dicts WHERE id=?",new String[]{Long.toString(dict)})){f=c.moveToFirst()?c.getString(0):"mdx";}
            formats.put(dict,f);
        }
        return "yomitan".equals(f);
    }

    synchronized ZipSource zip(long dict) throws Exception {
        ZipSource z=zips.get(dict);
        if(z!=null)return z;
        JSONObject d=dictRow(dict);
        try{z=new ZipSource(opener.open(d.getString("mdx")));}
        catch(IOException|SecurityException e){throw new Exception("Cannot open the dictionary file for "+d.getString("name")+". If you moved or deleted it, import it again from Library.",e);}
        zips.put(dict,z);
        return z;
    }

    /** Images and styles.css are read from the dictionary's ZIP in place. */
    byte[] yomitanResource(long dict,String path) throws Exception {
        String p=path.replace('\\','/');while(p.startsWith("/"))p=p.substring(1);
        ZipSource z=zip(dict);
        ZipSource.Entry e=z.find(p);
        if(e==null||p.endsWith(".json"))return null;
        synchronized(this){return z.bytes(e);}
    }

    byte[] zdict(long dict){
        byte[] z=zdicts.get(dict);
        if(z==null){
            try(Cursor c=db.rawQuery("SELECT zdict FROM ydict WHERE dict=?",new String[]{Long.toString(dict)})){z=c.moveToFirst()&&!c.isNull(0)?c.getBlob(0):new byte[0];}
            zdicts.put(dict,z);
        }
        return z;
    }

    String yomitanHtml(long rec,long dict,String key) throws Exception {
        String reading="",tags="",body="";
        try(Cursor c=db.rawQuery("SELECT reading,tags,body FROM ytext WHERE rec=?",new String[]{Long.toString(rec)})){
            if(c.moveToFirst()){reading=c.getString(0);tags=c.getString(1);body=inflate(c.getBlob(2),zdict(dict));}
        }
        boolean styles=false;
        try(Cursor c=db.rawQuery("SELECT styles FROM ydict WHERE dict=?",new String[]{Long.toString(dict)})){styles=c.moveToFirst()&&c.getInt(0)==1;}
        return "<link rel=\"stylesheet\" href=\"/yomitan.css\">"+(styles?"<link rel=\"stylesheet\" href=\"styles.css\">":"")+Yomitan.entryHtml(key,reading,tags,body);
    }

    static byte[] deflate(String text,byte[] dictionary,java.util.zip.Deflater d){
        d.reset();
        if(dictionary.length>0)d.setDictionary(dictionary);
        d.setInput(text.getBytes(StandardCharsets.UTF_8));d.finish();
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream(Math.max(64,text.length()/3));
        byte[] buf=new byte[8192];
        while(!d.finished()){int n=d.deflate(buf);out.write(buf,0,n);}
        return out.toByteArray();
    }
    static String inflate(byte[] data,byte[] dictionary) throws Exception {
        java.util.zip.Inflater i=new java.util.zip.Inflater();
        try{
            i.setInput(data);
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream(data.length*4);
            byte[] buf=new byte[16384];
            while(!i.finished()){
                int n=i.inflate(buf);
                if(n==0){
                    if(i.needsDictionary())i.setDictionary(dictionary);
                    else if(i.needsInput())break;
                }
                out.write(buf,0,n);
            }
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }finally{i.end();}
    }

    static final Pattern BANK=Pattern.compile("(?i)(?:.*/)?(term|kanji|term_meta|kanji_meta|tag)_bank_(\\d+)\\.json");

    /** Title of a Yomitan ZIP (from index.json), or null when the ZIP isn't a Yomitan dictionary. */
    public static String yomitanTitle(ZipSource z){
        boolean banks=false;
        for(String n:z.entries.keySet())if(BANK.matcher(n).matches()){banks=true;break;}
        if(!banks||z.find("index.json")==null)return null;
        try{return Yomitan.index(utf8(z.bytes("index.json"))).title;}catch(Exception e){return null;}
    }

    /** Default group for a Yomitan dictionary, from the collection's [JA-JA Kogo]-style file name tag and the title. */
    static String yomitanGroup(String file,String title,String kind){
        String f=file+" "+title;
        String lang=f.matches("(?s).*(\\[KO|KO-|KRDICT|STDICT|OPENDICT|[Hh]anja|Korean|[\\uac00-\\ud7a3]).*")?"Korean"
            :f.matches("(?s).*(\\[ZH|ZH-|CEDICT|Mandarin|Cantonese|CantoDict|粵|汉|漢語|國語辭典|现代汉语).*")?"Chinese":"Japanese";
        String sub="";
        if(kind.equals("freq")||f.matches("(?is).*(\\bFreq|Frequency|CC100).*"))sub="Frequency";
        else if(f.matches("(?is).*\\bPitch.*"))return PRONUNCIATION;
        else if(lang.equals("Korean")&&f.matches("(?is).*hanja.*"))sub="Hanja 漢字";
        else if(kind.equals("kanji")||f.matches("(?s).*\\[Kanji\\].*"))return lang.equals("Japanese")?"Kanji":lang+"/Hanzi";
        else if(f.matches("(?is).*(Kogo|古語).*"))sub="古語";
        else if(f.matches("(?is).*(Yoji|四字熟語).*"))sub="四字熟語";
        else if(f.matches("(?is).*(Expressions|ことわざ|慣用句|故事).*"))sub="慣用句・ことわざ";
        else if(f.matches("(?is).*(Thesaurus|類語|同訓異義|Antonyms|対義語).*"))sub="類語";
        else if(f.matches("(?is).*(Grammar|文法).*"))sub="文法";
        else if(f.matches("(?is).*(\\bNames|JMnedict|人名|市区町村|地名).*"))sub="人名・地名";
        else if(f.matches("(?is).*(Dialect|方言).*"))sub="方言";
        else if(f.matches("(?is).*(Origins|語源).*"))sub="語源";
        else if(f.matches("(?is).*(Counters|数え方|助数詞).*"))sub="助数詞";
        else if(f.matches("(?is).*(Onomatopoeia|擬音|擬態).*"))sub="擬音語";
        else if(f.matches("(?is).*(Encyclopedia|Pictures|百科|図鑑).*"))sub="百科";
        return sub.isEmpty()?lang:lang+"/"+sub;
    }

    /** Counts characters read, for import progress through a bank. */
    static final class CountingReader extends java.io.FilterReader {
        long count;
        CountingReader(java.io.Reader in){super(in);}
        @Override public int read(char[] b,int off,int len) throws IOException {int n=super.read(b,off,len);if(n>0)count+=n;return n;}
    }

    /**
     * Imports a Yomitan ZIP. Term entries are rendered to HTML once and stored deflated (with a preset dictionary
     * sampled from the dictionary itself, since entries are small and alike); rows sharing a headword and reading
     * become one page. Images and styles.css stay in the ZIP. Frequency/pitch rows go to the meta table.
     */
    public long importYomitan(String name,String uri,boolean fulltext,Progress progress) throws Exception {
        ZipSource zip=new ZipSource(opener.open(uri));
        long id;boolean ok=false;
        try{
            Yomitan.Index index=Yomitan.index(utf8(zip.bytes("index.json")));
            String title=index.title;
            ArrayList<ZipSource.Entry> terms=new ArrayList<>(),kanjis=new ArrayList<>(),metas=new ArrayList<>(),tagBanks=new ArrayList<>();
            java.util.Map<ZipSource.Entry,Integer> number=new HashMap<>();
            long total=0;int media=0;
            for(ZipSource.Entry e:zip.entries.values()){
                Matcher m=BANK.matcher(e.name);
                if(!m.matches()){if(!e.name.endsWith("/")&&!e.name.endsWith(".json"))media++;continue;}
                number.put(e,Integer.parseInt(m.group(2)));
                String type=m.group(1).toLowerCase(Locale.ROOT);
                (type.equals("term")?terms:type.equals("kanji")?kanjis:type.equals("tag")?tagBanks:metas).add(e);
                if(!type.equals("tag"))total+=e.size;
            }
            java.util.Comparator<ZipSource.Entry> byNumber=(a,b)->number.get(a)-number.get(b);
            terms.sort(byNumber);kanjis.sort(byNumber);metas.sort(byNumber);tagBanks.sort(byNumber);
            if(terms.isEmpty()&&kanjis.isEmpty()&&metas.isEmpty())throw new Exception("This ZIP has no Yomitan term, kanji or frequency banks.");
            String kind=!terms.isEmpty()?"term":!kanjis.isEmpty()?"kanji":"freq";
            ZipSource.Entry styles=zip.find("styles.css");

            ContentValues v=new ContentValues();
            // Collection titles carry a date or version ("絵でわかる慣用句 [2024-06-30]"); the shown name drops it.
            String shown=title.replaceAll("\\s*[\\[(（][\\d\\-. v]+[\\])）]\\s*$","").trim();
            v.put("name",shown.isEmpty()?title:shown);v.put("title",title);v.put("description",index.description);v.put("mdx",uri);v.put("mdd","[]");v.put("label",name);
            v.put("kind",kind);v.put("grp",yomitanGroup(name,title,kind));v.put("format","yomitan");
            v.put("position",Store.rows(db,"SELECT coalesce(max(position),0)+1 p FROM dicts").getJSONObject(0).getLong("p"));
            v.put("fulltext",fulltext&&!kind.equals("freq")?1:0);v.put("status","importing");v.put("imported",System.currentTimeMillis()/1000);v.put("resources",media);
            JSONArray previous=Store.rows(db,"SELECT id FROM dict_ids WHERE title=? AND id NOT IN (SELECT id FROM dicts)",title);
            if(previous.length()>0)v.put("id",previous.getJSONObject(0).getLong("id"));
            else v.put("id",Store.rows(db,"SELECT max(coalesce((SELECT max(id) FROM dicts),0),coalesce((SELECT max(id) FROM dict_ids),0))+1 n").getJSONObject(0).getLong("n"));
            id=db.insertOrThrow("dicts",null,v);
            formats.put(id,"yomitan");
            db.execSQL("INSERT OR IGNORE INTO dict_ids(title,id) VALUES(?,?)",new Object[]{title,id});
            final long dictId=id;
            db.beginTransaction();
            try{
                Map<String,Yomitan.Tag> tags=new HashMap<>();
                for(ZipSource.Entry e:tagBanks){
                    try(java.io.Reader r=new java.io.InputStreamReader(zip.stream(e),StandardCharsets.UTF_8)){
                        new Yomitan.Json(r).eachInArray(row->{Yomitan.Tag t=Yomitan.tag(row);tags.put(t.name,t);});
                    }
                }
                SQLiteStatement insertRecord=db.compileStatement("INSERT INTO records(dict,off,len,key,norm) VALUES(?,0,?,?,?)");
                SQLiteStatement insertKey=db.compileStatement("INSERT INTO keys(norm,dict,rec,key) VALUES(?,?,?,?)");
                SQLiteStatement insertText=db.compileStatement("INSERT INTO ytext(rec,reading,tags,body) VALUES(?,?,?,?)");
                SQLiteStatement insertMeta=db.compileStatement("INSERT INTO meta(dict,norm,reading,mode,value,display,term) VALUES(?,?,?,?,?,?,?)");
                SQLiteStatement insertKanji=db.compileStatement("INSERT INTO kanji(dict,rec,char,strokes,radical,rstrokes,level,flags,variants,sortkey) VALUES(?,?,?,?,'',-1,'','','',?)");
                final long[] firstKey={Long.MAX_VALUE};
                final java.util.zip.Deflater deflater=new java.util.zip.Deflater(6);
                // Entries wait here until enough of them are seen to build the preset dictionary.
                final byte[][] zdict={null};
                final ArrayList<String[]> waiting=new ArrayList<>();// {term, reading, termTags, body}
                final int[] waitingSize={0};
                final long[] done={0,0};// chars read in finished banks, entries
                class Writer {
                    String term,reading,termTags;StringBuilder body;
                    void add(String t,String r,String tt,String sense) throws Exception {
                        if(body!=null&&t.equals(term)&&r.equals(reading)){body.append(sense);if(!tt.isEmpty()&&!termTags.contains(tt))termTags+=tt;return;}
                        flush();
                        term=t;reading=r;termTags=tt;body=new StringBuilder(sense);
                    }
                    void flush() throws Exception {
                        if(body==null)return;
                        String[] e={term,reading,termTags,body.toString()};body=null;
                        if(zdict[0]==null){
                            waiting.add(e);waitingSize[0]+=e[3].length();
                            if(waitingSize[0]<48000&&waiting.size()<400)return;
                            zdict[0]=presetDictionary(waiting);
                            db.execSQL("INSERT OR REPLACE INTO ydict(dict,zdict,styles) VALUES(?,?,?)",new Object[]{dictId,zdict[0],styles!=null?1:0});
                            for(String[] w:waiting)write(w);
                            waiting.clear();
                            return;
                        }
                        write(e);
                    }
                    void finish() throws Exception {
                        flush();
                        if(zdict[0]==null){
                            zdict[0]=waiting.isEmpty()?new byte[0]:presetDictionary(waiting);
                            db.execSQL("INSERT OR REPLACE INTO ydict(dict,zdict,styles) VALUES(?,?,?)",new Object[]{dictId,zdict[0],styles!=null?1:0});
                            for(String[] w:waiting)write(w);
                            waiting.clear();
                        }
                    }
                    long write(String[] e) throws Exception {
                        String norm=HtmlText.normalize(e[0]);
                        insertRecord.bindLong(1,dictId);insertRecord.bindLong(2,e[3].length());insertRecord.bindString(3,e[0]);insertRecord.bindString(4,norm);
                        long rec=insertRecord.executeInsert();
                        insertText.bindLong(1,rec);insertText.bindString(2,e[1]);insertText.bindString(3,e[2]);insertText.bindBlob(4,deflate(e[3],zdict[0],deflater));
                        insertText.executeInsert();
                        LinkedHashSet<String> keys=new LinkedHashSet<>();keys.add(e[0]);if(!e[1].isEmpty())keys.add(e[1]);
                        String h=honorific(e[0],e[1]);if(h!=null)keys.add(h);
                        LinkedHashSet<String> norms=new LinkedHashSet<>();
                        for(String k:keys){
                            String n=HtmlText.normalize(k);if(n.isEmpty()||!norms.add(n))continue;
                            insertKey.bindString(1,n);insertKey.bindLong(2,dictId);insertKey.bindLong(3,rec);insertKey.bindString(4,k);
                            long kid=insertKey.executeInsert();if(kid<firstKey[0])firstKey[0]=kid;
                        }
                        return rec;
                    }
                }
                Writer writer=new Writer();
                final long totalChars=Math.max(1,total);
                for(ZipSource.Entry e:terms){
                    try(CountingReader r=new CountingReader(new java.io.InputStreamReader(zip.stream(e),StandardCharsets.UTF_8))){
                        new Yomitan.Json(r).eachInArray(row->{
                            try{
                                Yomitan.Term t=Yomitan.term(row);
                                if(t.term.isEmpty())return;
                                writer.add(t.term,t.reading,Yomitan.termTagsHtml(t.termTags,tags),Yomitan.senseHtml(t,tags));
                                if(++done[1]%2000==0){
                                    if(progress.cancelled())throw new RuntimeException(new InterruptedException("Import cancelled"));
                                    progress.update("Indexing entries",done[0]+r.count,totalChars);
                                }
                            }catch(RuntimeException x){throw x;}catch(Exception x){throw new RuntimeException(x);}
                        });
                        done[0]+=r.count;
                    }catch(RuntimeException x){throw x.getCause() instanceof Exception?(Exception)x.getCause():x;}
                }
                writer.finish();
                for(ZipSource.Entry e:kanjis){
                    try(CountingReader r=new CountingReader(new java.io.InputStreamReader(zip.stream(e),StandardCharsets.UTF_8))){
                        new Yomitan.Json(r).eachInArray(row->{
                            try{
                                Yomitan.Kanji k=Yomitan.kanji(row);
                                if(k.character.isEmpty())return;
                                long rec=writer.write(new String[]{k.character,"","",Yomitan.kanjiHtml(k,tags)});
                                if(kind.equals("kanji")){
                                    int st=Yomitan.strokes(k);
                                    insertKanji.bindLong(1,dictId);insertKanji.bindLong(2,rec);insertKanji.bindString(3,k.character);insertKanji.bindLong(4,st);
                                    insertKanji.bindString(5,String.format(Locale.ROOT,"%03d%05d",st==0?999:st,k.character.codePointAt(0)%100000));
                                    insertKanji.executeInsert();
                                }
                                if(++done[1]%2000==0)progress.update("Indexing kanji",done[0]+r.count,totalChars);
                            }catch(Exception x){throw new RuntimeException(x);}
                        });
                        done[0]+=r.count;
                    }catch(RuntimeException x){throw x.getCause() instanceof Exception?(Exception)x.getCause():x;}
                }
                for(ZipSource.Entry e:metas){
                    try(CountingReader r=new CountingReader(new java.io.InputStreamReader(zip.stream(e),StandardCharsets.UTF_8))){
                        new Yomitan.Json(r).eachInArray(row->{
                            Yomitan.Meta m=Yomitan.meta(row);
                            if(m.term.isEmpty()||m.display.isEmpty())return;
                            insertMeta.bindLong(1,dictId);insertMeta.bindString(2,HtmlText.normalize(m.term));insertMeta.bindString(3,HtmlText.normalize(m.reading));insertMeta.bindString(4,m.mode);
                            if(Double.isNaN(m.value))insertMeta.bindNull(5);else insertMeta.bindDouble(5,m.value);
                            insertMeta.bindString(6,m.display);insertMeta.bindString(7,m.term);insertMeta.executeInsert();
                            if(++done[1]%5000==0){
                                if(progress.cancelled())throw new RuntimeException(new InterruptedException("Import cancelled"));
                                progress.update("Indexing frequencies",done[0]+r.count,totalChars);
                            }
                        });
                        done[0]+=r.count;
                    }catch(RuntimeException x){throw x.getCause() instanceof Exception?(Exception)x.getCause():x;}
                }
                deflater.end();
                if(zdict[0]==null)db.execSQL("INSERT OR REPLACE INTO ydict(dict,zdict,styles) VALUES(?,NULL,?)",new Object[]{id,styles!=null?1:0});
                // Rows for the same word and reading that weren't next to each other in the banks: one page each.
                progress.update("Merging entries",1,1);
                JSONArray dup=Store.rows(db,"SELECT group_concat(r.id) ids FROM records r JOIN ytext y ON y.rec=r.id WHERE r.dict=? GROUP BY r.key,y.reading HAVING count(*)>1",Long.toString(id));
                byte[] zd=zdict[0]==null?new byte[0]:zdict[0];
                java.util.zip.Deflater d2=new java.util.zip.Deflater(6);
                for(int i=0;i<dup.length();i++){
                    if(i%200==0){
                        if(progress.cancelled())throw new InterruptedException("Import cancelled");
                        progress.update("Merging entries",i,dup.length());
                    }
                    String[] ids=dup.getJSONObject(i).getString("ids").split(",");
                    java.util.Arrays.sort(ids,(a,b)->Long.compare(Long.parseLong(a),Long.parseLong(b)));
                    StringBuilder body=new StringBuilder();String tagsHtml="";
                    for(String rid:ids){
                        try(Cursor c=db.rawQuery("SELECT tags,body FROM ytext WHERE rec=?",new String[]{rid})){
                            if(c.moveToFirst()){body.append(inflate(c.getBlob(1),zd));if(!c.getString(0).isEmpty()&&!tagsHtml.contains(c.getString(0)))tagsHtml+=c.getString(0);}
                        }
                    }
                    db.execSQL("UPDATE ytext SET body=?,tags=? WHERE rec=?",new Object[]{deflate(body.toString(),zd,d2),tagsHtml,ids[0]});
                    db.execSQL("UPDATE records SET len=? WHERE id=?",new Object[]{body.length(),ids[0]});
                    for(int k=1;k<ids.length;k++){
                        // keys is indexed by (norm,dict), not rec: delete through the entry's own headword and reading.
                        try(Cursor c=db.rawQuery("SELECT r.key,y.reading FROM records r JOIN ytext y ON y.rec=r.id WHERE r.id=?",new String[]{ids[k]})){
                            if(c.moveToFirst())for(String w:new String[]{c.getString(0),c.getString(1)}){
                                String n=HtmlText.normalize(w);
                                if(!n.isEmpty())db.execSQL("DELETE FROM keys WHERE norm=? AND dict=? AND rec=?",new Object[]{n,id,ids[k]});
                            }
                        }
                        db.execSQL("DELETE FROM ytext WHERE rec=?",new Object[]{ids[k]});
                        db.execSQL("DELETE FROM records WHERE id=?",new Object[]{ids[k]});
                    }
                }
                d2.end();
                mergeSameEntries(id);
                if(fulltext&&!kind.equals("freq")){
                    String fts="body_"+id;
                    db.execSQL("CREATE VIRTUAL TABLE "+fts+" USING fts4(content=\"\",defs,exs,tokenize=simple)");
                    SQLiteStatement insertBody=db.compileStatement("INSERT INTO "+fts+"(docid,defs,exs) VALUES(?,?,?)");
                    long n=Store.rows(db,"SELECT count(*) n FROM records WHERE dict=?",Long.toString(id)).getJSONObject(0).getLong("n"),k=0;
                    try(Cursor c=db.rawQuery("SELECT y.rec,y.body FROM ytext y JOIN records r ON r.id=y.rec WHERE r.dict=?",new String[]{Long.toString(id)})){
                        while(c.moveToNext()){
                            HtmlText text=HtmlText.parse(inflate(c.getBlob(1),zd));
                            insertBody.bindLong(1,c.getLong(0));
                            insertBody.bindString(2,HtmlText.tokens(HtmlText.normalize(text.definitions.toString()),200000));
                            insertBody.bindString(3,HtmlText.tokens(HtmlText.normalize(text.examples.toString()),200000));
                            insertBody.executeInsert();
                            if(++k%2000==0){
                                if(progress.cancelled())throw new InterruptedException("Import cancelled");
                                progress.update("Building definition search",k,n);
                            }
                        }
                    }
                    progress.update("Optimizing search index",1,1);
                    db.execSQL("INSERT INTO "+fts+"("+fts+") VALUES('optimize')");
                }
                long from=firstKey[0]==Long.MAX_VALUE?0:firstKey[0];
                JSONObject range=Store.rows(db,"SELECT min(id) a,max(id) b,count(*) n FROM keys WHERE id>=? AND dict=?",Long.toString(from),Long.toString(id)).getJSONObject(0);
                JSONObject recs=Store.rows(db,"SELECT min(id) a,max(id) b,count(*) n FROM records WHERE dict=?",Long.toString(id)).getJSONObject(0);
                long metaRows=Store.rows(db,"SELECT count(*) n FROM meta WHERE dict=?",Long.toString(id)).getJSONObject(0).getLong("n");
                ContentValues fin=new ContentValues();
                dictionariesChanged();
                fin.put("status","ready");fin.put("keys_v",KEYS_VERSION);fin.put("entries",kind.equals("freq")?metaRows:recs.getLong("n"));fin.put("keys",kind.equals("freq")?metaRows:range.getLong("n"));
                fin.put("key_min",range.optLong("a",0));fin.put("key_max",range.optLong("b",0));fin.put("rec_min",recs.optLong("a",0));fin.put("rec_max",recs.optLong("b",0));
                db.update("dicts",fin,"id=?",new String[]{Long.toString(id)});
                db.setTransactionSuccessful();
                ok=true;
            }finally{
                db.endTransaction();
                if(!ok){try{delete(id);}catch(Exception ignored){}}
            }
        }finally{zip.close();}
        zdicts.remove(id);hasMeta=null;
        return id;
    }

    /** Up to 32 KB of the dictionary's own entry HTML, most typical parts last (deflate matches recent bytes most cheaply). */
    static byte[] presetDictionary(List<String[]> sample){
        StringBuilder b=new StringBuilder();
        for(String[] e:sample){b.append(e[3]);if(b.length()>40000)break;}
        byte[] all=b.toString().getBytes(StandardCharsets.UTF_8);
        return java.util.Arrays.copyOfRange(all,Math.max(0,all.length-32768),all.length);
    }

    /**
     * Frequency ranks (and pitch/IPA notes) for a word from enabled Yomitan meta dictionaries, best rank per dictionary.
     * With a reading, rows for other readings are left out (JPDB lists 人 ひと and 人 にん separately).
     */
    public JSONArray frequencies(String key,String reading) throws Exception {
        String n=HtmlText.normalize(key),rn=HtmlText.normalize(reading==null?"":reading);
        if(n.isEmpty()||!hasMeta())return new JSONArray();
        JSONArray rows=Store.rows(db,"SELECT m.dict,d.name dictionary,m.mode,m.reading,min(m.value) value,m.display FROM meta m JOIN dicts d ON d.id=m.dict WHERE m.norm=? AND d.enabled=1 AND d.status='ready' AND (?='' OR m.reading='' OR m.reading=? OR m.reading=m.norm) GROUP BY m.dict,m.mode ORDER BY d.position",n,rn,rn);
        return rows;
    }
    /**
     * A frequency dictionary as a ranked word list, most common first. from>0 starts at that rank.
     * Each word says whether any enabled dictionary has an entry for it.
     */
    public JSONObject freqList(long dict,long from,int offset,int limit) throws Exception {
        String d=Long.toString(dict),n=Integer.toString(Math.max(1,Math.min(300,limit)));
        JSONArray rows=Store.rows(db,"SELECT CASE WHEN m.term='' THEN m.norm ELSE m.term END word,m.norm,m.reading,m.value,m.display,"
            +"EXISTS(SELECT 1 FROM keys k JOIN dicts x ON x.id=k.dict WHERE k.norm=m.norm AND x.enabled=1 AND x.kind!='freq') found "
            +"FROM meta m WHERE m.dict=? AND m.mode='freq' AND m.value>=? ORDER BY m.value,m.rowid LIMIT ? OFFSET ?",d,Long.toString(from),n,Integer.toString(offset));
        long total=Store.rows(db,"SELECT count(*) n FROM meta WHERE dict=? AND mode='freq'",d).getJSONObject(0).getLong("n");
        return new JSONObject().put("items",rows).put("total",total);
    }

    volatile Boolean hasMeta;
    boolean hasMeta(){
        Boolean h=hasMeta;
        if(h==null){try(Cursor c=db.rawQuery("SELECT 1 FROM meta LIMIT 1",null)){h=c.moveToFirst();}hasMeta=h;}
        return h;
    }

    // ---------- extra keys from headings ----------

    // 2: headings like お鉢《×御鉢》 give お鉢 and 御鉢, not one combined spelling.
    // 3: an honorific 御 is also indexed as the kana it's read as (大辞林 おはち【御鉢】 → お鉢; ごはん【御飯】 → ご飯).
    // 4: the same for every key and Yomitan entry, not only heading spellings (明鏡 御鉢 おはち, 新明解's 御鉢 key).
    // 5: ruby annotations aren't part of a spelling (新明解 御︽鉢 is 御鉢).
    // 6: the heading's kana (見出仮名) also tells how 御 is read, for pages filed under their spelling (新明解 御鉢).
    // 7: a kana-only Yomitan entry (blank reading) merges with the same text filed under its spelling (明鏡 あからさま, 明白).
    static final int KEYS_VERSION=7;
    static final Pattern HEAD_KANA=Pattern.compile("data-name=\"(?:見出仮名|見出し仮名)\"");
    static final Pattern SPELLING=Pattern.compile("data-name=\"(?:標準表記|表記)\"");
    static final Pattern OPTIONAL_KANA=Pattern.compile("<span data-name=\"送り仮名省略\">");
    static final Pattern KEY_SEPARATORS=Pattern.compile("[・･‧·‐‑‒–—=＝]");

    /**
     * Written forms printed in a Monokakido-style heading, for pages keyed only by kana (大辞林 おちあ・う):
     * 【落(ち)合う】 gives 落ち合う and 落合う.
     */
    static List<String> headingSpellings(String rawHtml){
        ArrayList<String> out=new ArrayList<>();
        if(rawHtml.indexOf("表記")<0)return out;
        String html=MarkupFix.html(rawHtml);
        Matcher m=SPELLING.matcher(html);
        while(m.find()&&out.size()<20){
            int start=html.indexOf('>',m.end());if(start<0)break;
            int end=closing(html,start+1);if(end<0)continue;
            String inner=html.substring(start+1,end);
            for(String variant:new String[]{withOptional(inner,true),withOptional(inner,false)}){
                String text=HtmlText.entities(variant.replaceAll("(?s)<(rt|rp)\\b[^>]*>.*?</\\1>","").replaceAll("<[^>]*>","")).replaceAll("[()（）\\s]","");
                // Alternative forms in 《》〈〉 (NHK お鉢《×御鉢》) and several spellings joined by ・ are separate words.
                for(String part:text.split("[《》〈〉・,，、]")){
                    String t=stripMarks(part).replace("×","").replace("▲","").trim();
                    if(t.isEmpty()||t.length()>30||!t.codePoints().anyMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN))continue;
                    if(!out.contains(t))out.add(t);
                }
            }
        }
        return out;
    }
    /** Index of the </span> closing the span whose content starts at `from`, or -1. */
    static int closing(String html,int from){
        int depth=0;
        for(int i=from;i<html.length();){
            int open=html.indexOf("<span",i),close=html.indexOf("</span>",i);
            if(close<0)return -1;
            if(open>=0&&open<close){depth++;i=open+5;}
            else{if(depth==0)return close;depth--;i=close+7;}
        }
        return -1;
    }
    /** The okurigana that may be left out (送り仮名省略): kept, or dropped. */
    static String withOptional(String inner,boolean keep){
        if(keep)return inner;
        StringBuilder b=new StringBuilder();int i=0;
        Matcher m=OPTIONAL_KANA.matcher(inner);
        while(m.find(i)){
            int end=closing(inner,m.end());if(end<0)break;
            b.append(inner,i,m.start());i=end+7;
        }
        return b.append(inner.substring(i)).toString();
    }
    /** Extra keys for a page: its heading spellings, and its keys without separators. */
    static LinkedHashSet<String> extraKeys(String rawHtml,java.util.Collection<String> keys){
        LinkedHashSet<String> out=new LinkedHashSet<>(headingSpellings(rawHtml));
        for(String k:keys){String s2=KEY_SEPARATORS.matcher(k).replaceAll("");if(!s2.equals(k)&&!s2.isEmpty())out.add(s2);}
        // 御 read お/ご (the honorific prefix): also the way it's usually typed, お鉢 / ご飯.
        ArrayList<String> words=new ArrayList<>(out);words.addAll(keys);
        keys=new ArrayList<>(keys);keys.addAll(headingKana(rawHtml));
        for(String w:words)for(String k:keys){String h=honorific(w,k);if(h!=null)out.add(h);}
        return out;
    }
    /** The kana readings printed in a heading (見出仮名), without spaces and separators. */
    static List<String> headingKana(String rawHtml){
        ArrayList<String> out=new ArrayList<>();
        if(rawHtml.indexOf("仮名")<0)return out;
        String html=MarkupFix.html(rawHtml);
        Matcher m=HEAD_KANA.matcher(html);
        while(m.find()&&out.size()<6){
            int start=html.indexOf('>',m.end());if(start<0)break;
            int end=closing(html,start+1);if(end<0)continue;
            String t=HtmlText.entities(html.substring(start+1,end).replaceAll("(?s)<(rt|rp)\\b[^>]*>.*?</\\1>","").replaceAll("<[^>]*>","")).replaceAll("[\\s・･‐\\-=＝]","");
            if(!t.isEmpty()&&t.length()<30)out.add(t);
        }
        return out;
    }
    /** 御鉢 read おはち → お鉢; 御飯 read ごはん → ご飯; otherwise null. */
    static String honorific(String written,String reading){
        if(written==null||reading==null||!written.startsWith("御")||written.length()<2)return null;
        String kana=HtmlText.normalize(KEY_SEPARATORS.matcher(reading).replaceAll(""));
        return kana.startsWith("お")?"お"+written.substring(1):kana.startsWith("ご")?"ご"+written.substring(1):null;
    }

    /**
     * Adds the version-1 extra keys to an MDX dictionary imported before them (no re-import needed).
     * Reads every page once, in file order.
     */
    public void upgradeKeys(long dict,Progress progress) throws Exception {
        if(isYomitan(dict)){
            progress.update("Improving search",0,1);
            db.beginTransaction();
            try{
                mergeSameEntries(dict);
                SQLiteStatement add=db.compileStatement("INSERT INTO keys(norm,dict,rec,key) VALUES(?,?,?,?)");
                try(Cursor c=db.rawQuery("SELECT r.id,r.key,y.reading FROM records r JOIN ytext y ON y.rec=r.id WHERE r.dict=? AND r.key LIKE '御%'",new String[]{Long.toString(dict)})){
                    while(c.moveToNext()){
                        String h=honorific(c.getString(1),c.getString(2));if(h==null)continue;
                        String n=HtmlText.normalize(h);
                        if(Store.rows(db,"SELECT 1 FROM keys WHERE norm=? AND dict=? AND rec=?",n,Long.toString(dict),Long.toString(c.getLong(0))).length()>0)continue;
                        add.bindString(1,n);add.bindLong(2,dict);add.bindLong(3,c.getLong(0));add.bindString(4,h);add.executeInsert();
                    }
                }
                JSONObject n=Store.rows(db,"SELECT count(*) n FROM records WHERE dict=?",Long.toString(dict)).getJSONObject(0);
                db.execSQL("UPDATE dicts SET keys_v=?,entries=? WHERE id=?",new Object[]{KEYS_VERSION,n.getLong("n"),dict});
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            return;
        }
        if(Store.rows(db,"SELECT 1 FROM dicts WHERE id=? AND keys_v>=6",Long.toString(dict)).length()>0){
            // Nothing past version 6 changes MDX keys.
            db.execSQL("UPDATE dicts SET keys_v=? WHERE id=?",new Object[]{KEYS_VERSION,dict});
            return;
        }
        JSONObject d=dictRow(dict);
        MdictFile mdx=file(dict,0);
        MdictFile.BlockCache c=new MdictFile.BlockCache(3);
        long total=d.getLong("entries"),done=0,added=0;
        SQLiteStatement exists=db.compileStatement("SELECT count(*) FROM keys WHERE norm=? AND dict=? AND rec=?");
        SQLiteStatement insert=db.compileStatement("INSERT INTO keys(norm,dict,rec,key) VALUES(?,?,?,?)");
        db.beginTransaction();
        try{
            try(Cursor cur=db.rawQuery("SELECT id,off,len,key FROM records WHERE dict=? ORDER BY off",new String[]{Long.toString(dict)})){
                while(cur.moveToNext()){
                    long rec=cur.getLong(0);
                    String html;
                    try{html=mdx.text(mdx.record(cur.getLong(1),cur.getInt(2),c));}catch(Exception e){continue;}
                    java.util.List<String> keys=new ArrayList<>();
                    try(Cursor k=db.rawQuery("SELECT key FROM keys WHERE dict=? AND rec=? AND norm=?",new String[]{Long.toString(dict),Long.toString(rec),HtmlText.normalize(cur.getString(3))})){while(k.moveToNext())keys.add(k.getString(0));}
                    keys.add(cur.getString(3));
                    for(String extra:extraKeys(html,keys)){
                        String n=HtmlText.normalize(extra);if(n.isEmpty())continue;
                        exists.bindString(1,n);exists.bindLong(2,dict);exists.bindLong(3,rec);
                        if(exists.simpleQueryForLong()>0)continue;
                        insert.bindString(1,n);insert.bindLong(2,dict);insert.bindLong(3,rec);insert.bindString(4,extra);insert.executeInsert();added++;
                    }
                    if(++done%2000==0){
                        if(progress.cancelled())throw new InterruptedException("cancelled");
                        progress.update("Improving search for "+d.getString("name"),done,total);
                    }
                }
            }
            JSONObject range=Store.rows(db,"SELECT max(id) b,count(*) n FROM keys WHERE dict=?",Long.toString(dict)).getJSONObject(0);
            db.execSQL("UPDATE dicts SET keys_v=?,keys=?,key_max=max(key_max,?) WHERE id=?",new Object[]{KEYS_VERSION,range.getLong("n"),range.optLong("b",0),dict});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    /** Dictionaries whose search keys are older than this version. */
    public JSONArray keysToUpgrade() throws Exception {
        return Store.rows(db,"SELECT id,name FROM dicts WHERE status='ready' AND kind!='freq' AND keys_v<? ORDER BY entries",Integer.toString(KEYS_VERSION));
    }

    /**
     * Yomitan dictionaries often list one entry once per spelling (明鏡: 落ち合う, 落合う…) with identical text.
     * Those become one page: the first keeps its record, the others' keys point to it. Call inside a transaction.
     */
    int mergeSameEntries(long dict) throws Exception {
        JSONArray dup=Store.rows(db,"SELECT group_concat(r.id) ids FROM records r JOIN ytext y ON y.rec=r.id WHERE r.dict=? GROUP BY coalesce(nullif(y.reading,''),r.key),y.body HAVING count(*)>1",Long.toString(dict));
        int merged=0;
        for(int i=0;i<dup.length();i++){
            String[] ids=dup.getJSONObject(i).getString("ids").split(",");
            java.util.Arrays.sort(ids,(a,b)->Long.compare(Long.parseLong(a),Long.parseLong(b)));
            java.util.HashSet<String> kept=new java.util.HashSet<>();
            try(Cursor c=db.rawQuery("SELECT norm FROM keys WHERE dict=? AND rec=? AND norm IN (SELECT r.norm FROM records r WHERE r.id=?)",new String[]{Long.toString(dict),ids[0],ids[0]})){while(c.moveToNext())kept.add(c.getString(0));}
            JSONArray keepRow=Store.rows(db,"SELECT y.reading FROM ytext y WHERE y.rec=?",ids[0]);
            if(keepRow.length()>0)kept.add(HtmlText.normalize(keepRow.getJSONObject(0).getString("reading")));
            for(int k=1;k<ids.length;k++){
                JSONArray other=Store.rows(db,"SELECT r.key,y.reading FROM records r JOIN ytext y ON y.rec=r.id WHERE r.id=?",ids[k]);
                if(other.length()==0)continue;
                for(String w:new String[]{other.getJSONObject(0).getString("key"),other.getJSONObject(0).getString("reading")}){
                    String n=HtmlText.normalize(w);if(n.isEmpty())continue;
                    if(kept.add(n))db.execSQL("UPDATE keys SET rec=? WHERE norm=? AND dict=? AND rec=?",new Object[]{ids[0],n,dict,ids[k]});
                    else db.execSQL("DELETE FROM keys WHERE norm=? AND dict=? AND rec=?",new Object[]{n,dict,ids[k]});
                }
                db.execSQL("DELETE FROM ytext WHERE rec=?",new Object[]{ids[k]});
                db.execSQL("DELETE FROM records WHERE id=?",new Object[]{ids[k]});
                merged++;
            }
        }
        return merged;
    }

    static int parseIntOr(String s,int fallback){try{return Integer.parseInt(s.trim());}catch(Exception e){return fallback;}}

    static final Pattern K_CHAR=Pattern.compile("data-name=\"(?:OyajiCharacter|親字-[^\"]*)\"[^>]*>(?:\\s*<[^>]+>)*\\s*([\\x{3400}-\\x{9FFF}\\x{F900}-\\x{FAFF}\\x{20000}-\\x{2FFFF}])");
    static final Pattern K_STROKES=Pattern.compile("(?:data-name=\"Kakusuu\"|class=\"総画数TD\")[^>]*>(?:\\s*<[^>]+>)*\\s*[（(]?\\s*(\\d+)");
    static final Pattern K_RADICAL=Pattern.compile("(?:data-name=\"Busyu\"|class=\"部首TD\")[^>]*>(?:\\s*<[^>]+>)*\\s*([^<\\s])");
    static final Pattern K_RSTROKES=Pattern.compile("(?:data-name=\"BusyuKakusu\"|class=\"部首内画数TD\")[^>]*>(?:\\s*<[^>]+>)*\\s*(\\d+)");
    static final Pattern K_LEVEL=Pattern.compile("data-name=\"Kyuusuu\">([^<]+)<");
    static final Pattern K_VARIANTS=Pattern.compile("(?:data-name=\"(?:SubCharG|旧字M|印刷標準字体M|簡易慣用字体M)\"|class=\"(?:旧字TD|異体字TD)\")[^>]*>(.{0,300}?)</(?:span|td)>",Pattern.DOTALL);
    static final Pattern K_UNIT=Pattern.compile("data-name=\"(?:OyajiG|親字G)\"");

    /**
     * Kanji facts from a kanji-dictionary page (漢検 / 漢辞海 style markup): one row per head character with
     * {char, strokes, radical, radical strokes, 漢検 level, flags (常/教/人), variant characters}.
     */
    static List<String[]> kanjiMeta(String html){
        ArrayList<String[]> out=new ArrayList<>();
        Matcher u=K_UNIT.matcher(html);
        ArrayList<Integer> starts=new ArrayList<>();
        while(u.find())starts.add(u.start());
        if(starts.isEmpty())return out;
        for(int i=0;i<starts.size();i++){
            int a=starts.get(i),b=i+1<starts.size()?starts.get(i+1):html.length();
            // The character's own block is at most a few KB; compounds follow.
            String part=html.substring(a,Math.min(b,a+12000));
            Matcher m=K_CHAR.matcher(part);if(!m.find())continue;
            String ch=m.group(1);
            Matcher st=K_STROKES.matcher(part);String strokes=st.find()?st.group(1):"";
            Matcher ra=K_RADICAL.matcher(part);String radical=ra.find()?Normalizer.normalize(ra.group(1),Normalizer.Form.NFKC):"";
            Matcher rs=K_RSTROKES.matcher(part);String rstrokes=rs.find()?rs.group(1):"";
            Matcher lv=K_LEVEL.matcher(part);String level=lv.find()?lv.group(1).trim():"";
            StringBuilder flags=new StringBuilder();
            String head=part.substring(0,Math.min(part.length(),2500));
            if(head.contains("data-name=\"Logo\">常<")||head.contains("data-name=\"親字-常用\""))flags.append('常');
            if(head.contains("data-name=\"Logo\">教<"))flags.append('教');
            if(head.contains("data-name=\"Logo\">人<")||head.contains("data-name=\"親字-人名"))flags.append('人');
            StringBuilder variants=new StringBuilder();
            Matcher v=K_VARIANTS.matcher(head.length()<part.length()?part.substring(0,Math.min(part.length(),6000)):part);
            while(v.find()){
                String txt=v.group(1).replaceAll("<[^>]+>","");
                txt.codePoints().filter(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN&&!ch.equals(new String(Character.toChars(c)))).forEach(c->{String x=new String(Character.toChars(c));if(variants.indexOf(x)<0)variants.append(x);});
            }
            out.add(new String[]{ch,strokes,radical,rstrokes,level,flags.toString(),variants.toString()});
        }
        return out;
    }

    static String resourceName(String key){
        String s=key.replace('\\','/');
        while(s.startsWith("/"))s=s.substring(1);
        return s.toLowerCase(Locale.ROOT);
    }

    public synchronized void delete(long id){
        dictionariesChanged();
        closeFiles(id);formats.remove(id);zdicts.remove(id);hasMeta=null;
        db.beginTransaction();
        try{
            db.execSQL("DELETE FROM kanji WHERE dict=?",new Object[]{id});
            db.execSQL("DROP TABLE IF EXISTS body_"+id);
            db.execSQL("DELETE FROM keys WHERE dict=? AND id BETWEEN (SELECT key_min FROM dicts WHERE id=?) AND (SELECT key_max FROM dicts WHERE id=?)",new Object[]{id,id,id});
            // Fallback for interrupted imports whose ranges were never recorded.
            try(Cursor c=db.rawQuery("SELECT 1 FROM keys WHERE dict=? LIMIT 1",new String[]{Long.toString(id)})){if(c.moveToFirst())db.execSQL("DELETE FROM keys WHERE dict=?",new Object[]{id});}
            db.execSQL("DELETE FROM ytext WHERE rec IN (SELECT id FROM records WHERE dict=?)",new Object[]{id});
            db.execSQL("DELETE FROM ydict WHERE dict=?",new Object[]{id});
            db.execSQL("DELETE FROM meta WHERE dict=?",new Object[]{id});
            db.execSQL("DELETE FROM records WHERE dict=?",new Object[]{id});
            db.execSQL("DELETE FROM anchors WHERE dict=?",new Object[]{id});
            db.execSQL("DELETE FROM resources WHERE dict=?",new Object[]{id});
            db.execSQL("DELETE FROM dicts WHERE id=?",new Object[]{id});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }

    // ---------- queries ----------

    public JSONArray dictionaries() throws Exception {
        return Store.rows(db,"SELECT id,name,title,label,kind,grp,position,enabled,entries,keys,resources,fulltext,status,imported,mdx,mdd,format,description FROM dicts WHERE status='ready' ORDER BY position,id");
    }

    public void updateDictionary(JSONObject data) throws Exception {
        dictionariesChanged();
        long id=data.getLong("id");
        ContentValues v=new ContentValues();
        if(data.has("name")){String n=data.getString("name").trim();if(n.isEmpty()||n.length()>120)throw new Exception("Names need 1–120 characters.");v.put("name",n);}
        if(data.has("enabled"))v.put("enabled",data.getBoolean("enabled")?1:0);
        if(data.has("kind"))v.put("kind",data.getString("kind").equals("kanji")?"kanji":"term");
        if(data.has("grp")){String g=data.getString("grp").trim();if(g.isEmpty()||g.length()>60||g.chars().filter(c->c=='/').count()>1||g.startsWith("/")||g.endsWith("/"))throw new Exception("Group names need 1–60 characters, with at most one “/” (Japanese/古語).");v.put("grp",g);}
        if(v.size()>0)db.update("dicts",v,"id=?",new String[]{Long.toString(id)});
    }

    /** Dictionary ids by title and back, for sync (ids differ between devices, titles don't). */
    public Sync.Dicts syncDicts(){
        return new Sync.Dicts(){
            @Override public long idFor(String title){
                try{
                    JSONArray r=Store.rows(db,"SELECT id FROM dicts WHERE title=? UNION ALL SELECT id FROM dict_ids WHERE title=? LIMIT 1",title,title);
                    return r.length()==0?0:r.getJSONObject(0).getLong("id");
                }catch(Exception e){return 0;}
            }
            @Override public String titleFor(long id){
                try{JSONArray r=Store.rows(db,"SELECT title FROM dicts WHERE id=?",Long.toString(id));return r.length()==0?null:r.getJSONObject(0).getString("title");}catch(Exception e){return null;}
            }
        };
    }

    /** Sizes of a dictionary's files, recorded the first time they can be opened. Unknown sizes are -1. */
    public JSONArray fileSizes(long id) throws Exception {
        JSONObject d=dictRow(id);
        JSONArray uris=new JSONArray().put(d.getString("mdx"));
        JSONArray mdd=new JSONArray(d.getString("mdd"));for(int k=0;k<mdd.length();k++)uris.put(mdd.getString(k));
        JSONArray sizes=d.optString("sizes").isEmpty()?new JSONArray():new JSONArray(d.getString("sizes"));
        // Each file's size is kept on its own: one missing file mustn't lose what's known about the others.
        boolean changed=false;
        for(int k=0;k<uris.length();k++){
            if(sizes.optLong(k,-1)>=0)continue;
            long size=-1;
            try(FileChannel c=opener.open(uris.getString(k))){size=c.size();}catch(Exception ignored){}
            sizes.put(k,size);changed|=size>=0;
        }
        if(changed)db.execSQL("UPDATE dicts SET sizes=? WHERE id=?",new Object[]{sizes.toString(),id});
        return sizes;
    }

    /** New locations for a dictionary's files (the same files, moved); the index is kept, and so are known sizes. */
    public void setFiles(long id,String mdx,JSONArray mdd){
        closeFiles(id);
        ContentValues v=new ContentValues();v.put("mdx",mdx);v.put("mdd",mdd.toString());
        db.update("dicts",v,"id=?",new String[]{Long.toString(id)});
    }

    /**
     * Whether a file is really this dictionary's file number `index` (0 = MDX, 1… = MDD), by reading a few indexed
     * entries or resources from it. Used to relink a moved file whose size wasn't recorded.
     */
    public boolean verifyFile(long dict,int index,String uri){
        try(MdictFile f=new MdictFile(opener.open(uri),index>0)){
            MdictFile.BlockCache c=new MdictFile.BlockCache(2);
            JSONArray rows=index==0
                ?Store.rows(db,"SELECT off,len,key FROM records WHERE dict=? ORDER BY id LIMIT 3",Long.toString(dict))
                :Store.rows(db,"SELECT off,len,name key FROM resources WHERE dict=? AND file=? ORDER BY off LIMIT 3",Long.toString(dict),Integer.toString(index));
            if(rows.length()==0)return false;
            for(int i=0;i<rows.length();i++){
                JSONObject r=rows.getJSONObject(i);
                byte[] b=f.record(r.getLong("off"),r.getInt("len"),c);
                if(b==null||b.length==0)return false;
                if(index==0&&!HtmlText.normalize(f.text(b)).contains(HtmlText.normalize(r.getString("key")).replace("@","")))return false;
            }
            return true;
        }catch(Exception e){return false;}
    }

    public void reorder(JSONArray ids){
        db.beginTransaction();
        try{
            for(int i=0;i<ids.length();i++)db.execSQL("UPDATE dicts SET position=? WHERE id=?",new Object[]{i+1,ids.optLong(i)});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }

    String enabledClause(String column,String dict,ArrayList<String> args){
        if(dict!=null&&dict.startsWith("g:")){
            // "g:Japanese/*" is the group with all its types (Japanese, Japanese/古語…).
            if(dict.endsWith("/*")){String p=dict.substring(2,dict.length()-2);args.add(p);args.add(p+"/%");return column+" IN (SELECT id FROM dicts WHERE enabled=1 AND status='ready' AND (grp=? OR grp LIKE ?))";}
            args.add(dict.substring(2));return column+" IN (SELECT id FROM dicts WHERE enabled=1 AND status='ready' AND grp=?)";
        }
        if(dict!=null&&!dict.isEmpty()){args.add(dict);return column+"=?";}
        return column+" IN (SELECT id FROM dicts WHERE enabled=1 AND status='ready')";
    }

    static String the2Plain(String html){
        return HtmlText.entities(html.replaceAll("(?is)<rt\\b[^>]*>.*?</rt>","").replaceAll("(?s)<[^>]+>","")
            .replace('\u00a0',' ').replaceAll("\\s+"," ").trim());
    }

    /** The paper thesaurus's alphabetical index: a word leads to numbered meaning groups. */
    public JSONObject the2Index(String query) throws Exception {
        String term=HtmlText.normalize(query);
        JSONArray out=new JSONArray();
        if(term.isEmpty())return new JSONObject().put("items",out);
        JSONArray hits=Store.rows(db,"SELECT DISTINCT k.rec,k.dict,d.name dictionary FROM keys k JOIN dicts d ON d.id=k.dict WHERE k.norm=? AND d.enabled=1 AND d.status='ready' AND d.title LIKE '%日本語シソーラス%' LIMIT 120",term);
        ArrayList<JSONObject> groups=new ArrayList<>();
        for(int i=0;i<hits.length();i++){
            JSONObject hit=hits.getJSONObject(i);
            String html=recordHtml(hit.getLong("rec"));
            Matcher title=THE2_TITLE.matcher(html);
            if(!title.find())continue;
            String number=the2Plain(title.group(1)),name=the2Plain(title.group(2));
            if(number.isEmpty()||name.isEmpty())continue;
            JSONArray path=new JSONArray();
            Matcher ancestor=THE2_ANCESTOR.matcher(html.substring(0,title.start()));
            while(ancestor.find())path.put(the2Plain(ancestor.group(1)));
            JSONArray sample=new JSONArray();
            LinkedHashSet<String> words=new LinkedHashSet<>();
            Matcher word=THE2_WORD.matcher(html);
            while(word.find()&&words.size()<10){
                String w=the2Plain(word.group(1));
                if(!w.isEmpty()&&w.length()<=24)words.add(w);
            }
            for(String w:words)sample.put(w);
            groups.add(new JSONObject().put("dict",hit.getLong("dict")).put("rec",hit.getLong("rec"))
                .put("dictionary",hit.getString("dictionary")).put("number",number).put("title",name)
                .put("path",path).put("sample",sample));
        }
        groups.sort((a,b)->a.optString("number").compareTo(b.optString("number")));
        for(JSONObject group:groups)out.put(group);
        return new JSONObject().put("items",out);
    }

    /**
     * The words in a シソーラス group that are the searched word: its own spelling, or (for a reading like きれい) the
     * written words filed only under pages that the reading also leads to (綺麗・奇麗, not 美しい or a word unique to
     * this page). Lets the group open on the word you came from.
     */
    public JSONArray the2Matches(long rec,String query) throws Exception {
        String term=HtmlText.normalize(query);
        JSONArray out=new JSONArray();
        if(term.isEmpty())return out;
        java.util.HashSet<Long> termRecs=new java.util.HashSet<>();
        long dict;
        try(Cursor c=db.rawQuery("SELECT dict FROM records WHERE id=?",new String[]{Long.toString(rec)})){if(!c.moveToFirst())return out;dict=c.getLong(0);}
        try(Cursor c=db.rawQuery("SELECT rec FROM keys WHERE dict=? AND norm=?",new String[]{Long.toString(dict),term})){while(c.moveToNext())termRecs.add(c.getLong(0));}
        LinkedHashSet<String> words=new LinkedHashSet<>();
        Matcher word=THE2_WORD.matcher(recordHtml(rec));
        while(word.find()){String w=the2Plain(word.group(1));if(!w.isEmpty()&&w.length()<=24)words.add(w);}
        for(String w:words){
            String n=HtmlText.normalize(w);
            if(n.equals(term)){out.put(w);continue;}
            java.util.HashSet<Long> recs=new java.util.HashSet<>();
            try(Cursor c=db.rawQuery("SELECT rec FROM keys WHERE dict=? AND norm=? LIMIT 200",new String[]{Long.toString(dict),n})){while(c.moveToNext())recs.add(c.getLong(0));}
            // Most of the reading's groups, not just two it happens to share (婉美, 嬋娟 are only in 美しい and 美貌).
            if(recs.size()>=Math.max(2,(termRecs.size()+1)/2)&&termRecs.containsAll(recs))out.put(w);
        }
        return out;
    }

    static final String THESAURUS_FILTER=" AND coalesce(d.grp,'') NOT LIKE 'Japanese/類語%' AND coalesce(d.title,'') NOT LIKE '%日本語シソーラス%'";
    static final String THESAURUS_MATCH=" AND (d.grp LIKE 'Japanese/類語%' OR d.title LIKE '%日本語シソーラス%')";

    public JSONObject search(String query,String mode,String dict,int offset,boolean hideThesaurus) throws Exception {
        String term=HtmlText.normalize(query);
        JSONArray items;
        int limit=50;
        if(mode.equals("headword")||term.isEmpty()){
            ArrayList<String> args=new ArrayList<>();
            if(term.isEmpty()){
                if(dict==null||dict.isEmpty()||dict.startsWith("g:"))return new JSONObject().put("items",new JSONArray()).put("more",false);
                // Browse one dictionary's pages in index order.
                items=Store.rows(db,"SELECT r.key,r.id rec,r.dict,r.key page,d.name dictionary,d.kind,0 exact FROM records r JOIN dicts d ON d.id=r.dict WHERE r.dict=? ORDER BY r.norm,r.id LIMIT ? OFFSET ?",dict,Integer.toString(limit+1),Integer.toString(offset));
                boolean more=items.length()>limit;if(more)items.remove(limit);
                return new JSONObject().put("items",items).put("more",more);
            }
            // The SELECT list's "k.norm=?" placeholder comes first in the statement.
            args.add(term);
            String where="k.norm>=? AND k.norm<?";args.add(term);args.add(term+"\uffff");
            where+=" AND "+enabledClause("k.dict",dict,args);
            if(hideThesaurus)where+=THESAURUS_FILTER;
            args.add(Integer.toString(limit+1));args.add(Integer.toString(offset));
            items=Store.rows(db,"SELECT group_concat(k.key,char(1)) keys,k.rec,k.dict,coalesce(nullif((SELECT y.reading FROM ytext y WHERE y.rec=k.rec),''),r.key) page,d.name dictionary,d.kind,k.norm=? exact FROM keys k JOIN records r ON r.id=k.rec JOIN dicts d ON d.id=k.dict WHERE "+where+" GROUP BY k.norm,k.dict,k.rec ORDER BY k.norm,d.position,r.len DESC LIMIT ? OFFSET ?",args.toArray(new String[0]));
            if(offset==0&&(dict==null||dict.isEmpty()))items=withMixedExact(items,term);
            if(hideThesaurus){
                JSONArray ordinary=new JSONArray();
                for(int i=0;i<items.length();i++){
                    JSONObject item=items.getJSONObject(i);
                    if(!isThesaurusDict(item.getLong("dict")))ordinary.put(item);
                }
                items=ordinary;
            }
            displayKeys(items,query);
            addSpellings(items,term);
        }else if(mode.equals("contains")){
            ArrayList<String> args=new ArrayList<>();
            args.add(term);
            String where="instr(k.norm,?)>0";args.add(term);
            where+=" AND "+enabledClause("k.dict",dict,args);
            if(hideThesaurus)where+=THESAURUS_FILTER;
            args.add(Integer.toString(limit+1));args.add(Integer.toString(offset));
            items=Store.rows(db,"SELECT group_concat(k.key,char(1)) keys,k.rec,k.dict,coalesce(nullif((SELECT y.reading FROM ytext y WHERE y.rec=k.rec),''),r.key) page,d.name dictionary,d.kind,k.norm=? exact FROM keys k JOIN records r ON r.id=k.rec JOIN dicts d ON d.id=k.dict WHERE "+where+" GROUP BY k.norm,k.dict,k.rec ORDER BY exact DESC,length(k.norm),k.norm,d.position,r.len DESC LIMIT ? OFFSET ?",args.toArray(new String[0]));
            displayKeys(items,query);
        }else{
            String column=mode.equals("examples")?"exs":"defs";
            String phrase=HtmlText.tokens(term,64).replace("\"","");
            if(phrase.isEmpty())return new JSONObject().put("items",new JSONArray()).put("more",false);
            ArrayList<String> unions=new ArrayList<>();ArrayList<String> args=new ArrayList<>();
            String fulltextFilter=hideThesaurus?THESAURUS_FILTER:"";
            JSONArray dicts=dict==null||dict.isEmpty()?Store.rows(db,"SELECT d.id FROM dicts d WHERE d.enabled=1 AND d.status='ready' AND d.fulltext=1"+fulltextFilter+" ORDER BY d.position")
                :dict.endsWith("/*")?Store.rows(db,"SELECT d.id FROM dicts d WHERE d.enabled=1 AND d.status='ready' AND d.fulltext=1 AND (d.grp=? OR d.grp LIKE ?)"+fulltextFilter+" ORDER BY d.position",dict.substring(2,dict.length()-2),dict.substring(2,dict.length()-2)+"/%")
                :dict.startsWith("g:")?Store.rows(db,"SELECT d.id FROM dicts d WHERE d.enabled=1 AND d.status='ready' AND d.fulltext=1 AND d.grp=?"+fulltextFilter+" ORDER BY d.position",dict.substring(2))
                :Store.rows(db,"SELECT d.id FROM dicts d WHERE d.id=? AND d.fulltext=1"+fulltextFilter,dict);
            for(int i=0;i<dicts.length();i++){
                long d=dicts.getJSONObject(i).getLong("id");
                unions.add("SELECT docid rec FROM body_"+d+" WHERE "+column+" MATCH ?");
                args.add("\""+phrase+"\"");
            }
            if(unions.isEmpty())return new JSONObject().put("items",new JSONArray()).put("more",false).put("note","No dictionary with a full-text index is enabled.");
            args.add(Integer.toString(limit+1));args.add(Integer.toString(offset));
            items=Store.rows(db,"SELECT r.key,r.id rec,r.dict,r.key page,d.name dictionary,d.kind,0 exact FROM ("+String.join(" UNION ALL ",unions)+") m JOIN records r ON r.id=m.rec JOIN dicts d ON d.id=r.dict ORDER BY d.position,length(r.norm),r.norm LIMIT ? OFFSET ?",args.toArray(new String[0]));
            for(int i=0;i<Math.min(items.length(),limit);i++){
                JSONObject row=items.getJSONObject(i);
                try{row.put("snippet",snippet(row.getLong("rec"),term,mode.equals("examples")));}catch(Exception e){row.put("snippet","");}
            }
        }
        boolean more=items.length()>limit;
        if(more)items.remove(limit);
        // One result per page: a page with several matching keys (おちあう, おちあう【落ち合う】) keeps its first, closest one.
        java.util.HashSet<String> pages=new java.util.HashSet<>();
        JSONArray unique=new JSONArray();
        for(int i=0;i<items.length();i++){JSONObject r=items.getJSONObject(i);if(pages.add(r.optLong("dict")+":"+r.optLong("rec")))unique.put(r);}
        items=unique;
        addRanks(items);
        return new JSONObject().put("items",items).put("more",more);
    }

    boolean isThesaurusDict(long id) throws Exception {
        return Store.rows(db,"SELECT 1 FROM dicts d WHERE d.id=?"+THESAURUS_MATCH+" LIMIT 1",Long.toString(id)).length()>0;
    }

    /** Each result's rank in the first enabled frequency list of the result's language (JPDB for Japanese, CC100 for Korean…). */
    void addRanks(JSONArray items) throws Exception {
        if(!hasMeta())return;
        HashMap<String,Double> seen=new HashMap<>();
        HashMap<Long,String> langs=new HashMap<>();
        for(int i=0;i<items.length();i++){
            JSONObject row=items.getJSONObject(i);
            long dict=row.optLong("dict");
            String lang=langs.computeIfAbsent(dict,x->{String g=dictGroup(x);int s2=g.indexOf('/');return s2<0?g:g.substring(0,s2);});
            if(lang.isEmpty()||lang.equals("Kanji"))lang="Japanese";
            String n=HtmlText.normalize(row.optString("key"));
            // A kana search splits rows by written word (けんのう → 権能, 献納): each word has its own rank, read
            // the same way; the kana's own rank (how often けんのう is written in kana) is only for kana-only words.
            JSONArray words=row.optJSONArray("words");
            if(words!=null&&words.length()>0){
                JSONObject ranks=new JSONObject();
                for(int w=0;w<words.length();w++){
                    String word=words.getString(w),wn=HtmlText.normalize(word);
                    String wk=lang+"|"+wn+"|"+n;
                    if(!seen.containsKey(wk)){
                        Double v=null;
                        try(Cursor c=db.rawQuery("SELECT m.value,m.reading FROM meta m JOIN dicts d ON d.id=m.dict WHERE m.norm=? AND m.mode='freq' AND m.value>0 AND d.enabled=1 AND (d.grp=? OR d.grp LIKE ?) ORDER BY d.position,m.value",new String[]{wn,lang,lang+"/%"})){
                            while(c.moveToNext()){
                                String r=HtmlText.normalize(c.getString(1));
                                if(r.isEmpty()||r.equals(n)){v=c.getDouble(0);break;}
                            }
                        }
                        seen.put(wk,v);
                    }
                    Double v=seen.get(wk);
                    if(v!=null)ranks.put(word,v.longValue());
                }
                if(ranks.length()>0)row.put("ranks",ranks);
                continue;
            }
            String k=lang+"|"+n;
            if(!seen.containsKey(k)){
                Double v=null;
                try(Cursor c=db.rawQuery("SELECT min(m.value) FROM meta m JOIN dicts d ON d.id=m.dict WHERE m.norm=? AND m.mode='freq' AND m.value>0 AND d.enabled=1 AND (d.grp=? OR d.grp LIKE ?) GROUP BY m.dict ORDER BY d.position LIMIT 1",new String[]{n,lang,lang+"/%"})){
                    if(c.moveToFirst()&&!c.isNull(0))v=c.getDouble(0);
                }
                seen.put(k,v);
            }
            Double v=seen.get(k);
            if(v!=null)row.put("rank",v.longValue());
        }
    }

    static final String MARKS="▽▼△▲";
    static String stripMarks(String s){
        StringBuilder b=new StringBuilder(s.length());
        for(int i=0;i<s.length();i++){char c=s.charAt(i);if(MARKS.indexOf(c)<0)b.append(c);}
        return b.toString();
    }

    /** Picks the spelling to show for rows that group several variant keys (おと こ / おとこ / オトコ). */
    static void displayKeys(JSONArray rows,String query) throws Exception {
        String typed=query==null?"":query.trim();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);
            String[] keys=row.optString("keys","").split("\u0001");
            String best=null;int bestScore=Integer.MAX_VALUE;
            for(String k:keys){
                if(k.isEmpty())continue;
                int score=0;
                if(k.matches(".*\\s.*"))score+=100;
                if(!k.equals(stripMarks(k)))score+=10;
                // Prefer the script the user typed (kana query → kana key), else the page's own spelling.
                if(!typed.isEmpty()&&!k.startsWith(typed))score+=5;
                if(k.equals(row.optString("page")))score-=2;
                if(score<bestScore){bestScore=score;best=k;}
            }
            // Keys that carry their spelling (おちあう【落ち合う】, 日韓辞典's おちあう【落ち合う) show as the word itself.
            String shown=stripMarks(best==null?row.optString("page"):best).trim();
            java.util.regex.Matcher bracket=Pattern.compile("[【《〈]").matcher(shown);
            if(bracket.find()&&bracket.start()>0)shown=shown.substring(0,bracket.start()).trim();
            row.put("key",shown);
            row.put("page",stripMarks(row.optString("page")));
            row.remove("keys");
        }
    }

    /** Short passage around the first match, taken from the record itself. */
    String snippet(long rec,String term,boolean examples) throws Exception {
        String html=recordHtml(rec);
        HtmlText text=HtmlText.parse(html);
        String source=(examples?text.examples:text.definitions).toString().replaceAll("\\s+"," ").trim();
        // Map positions in the normalized string back to the source text.
        StringBuilder norm=new StringBuilder();ArrayList<Integer> map=new ArrayList<>();
        for(int i=0;i<source.length();){
            int c=source.codePointAt(i);int n=Character.charCount(c);
            String part=HtmlText.normalize(new String(Character.toChars(c)));
            for(int k=0;k<part.length();k++){norm.append(part.charAt(k));map.add(i);}
            i+=n;
        }
        int at=norm.indexOf(term);
        if(at<0)return source.substring(0,Math.min(120,source.length()));
        int start=map.get(at);
        int endIndex=Math.min(map.size()-1,at+term.length()-1);
        int end=Math.min(source.length(),map.get(endIndex)+Character.charCount(source.codePointAt(map.get(endIndex))));
        int from=Math.max(0,start-40),to=Math.min(source.length(),end+80);
        return (from>0?"…":"")+source.substring(from,start)+"\u0001"+source.substring(start,end)+"\u0002"+source.substring(end,to)+(to<source.length()?"…":"");
    }

    public JSONArray exact(String key,String excludeDict) throws Exception {return exact(key,excludeDict,true);}
    /** mixed: also pages spelled with more kanji (相まみえる → 相見える); off for the many internal checks of candidate forms. */
    JSONArray exact(String key,String excludeDict,boolean mixed) throws Exception {
        String norm=HtmlText.normalize(key);
        JSONArray rows=Store.rows(db,"SELECT group_concat(k.key,char(1)) keys,k.rec,k.dict,coalesce(nullif((SELECT y.reading FROM ytext y WHERE y.rec=k.rec),''),r.key) page,d.name dictionary,d.kind,r.len size,(SELECT 1 FROM kanji j WHERE j.rec=k.rec AND j.char=?) head FROM keys k JOIN records r ON r.id=k.rec JOIN dicts d ON d.id=k.dict WHERE k.norm=? AND d.enabled=1 AND d.status='ready' GROUP BY k.dict,k.rec ORDER BY d.position,head IS NULL,r.len DESC LIMIT 60",key.trim(),norm);
        if(mixed)rows=withMixedSpellings(rows,norm);
        // 類語 off: thesaurus pages don't match at all, so a phrase only they list (軌跡を辿る) falls back to 軌跡.
        if(Boolean.TRUE.equals(skipThesaurus.get())){
            java.util.Set<Long> the=thesaurusIds();
            JSONArray kept=new JSONArray();
            for(int i=0;i<rows.length();i++)if(!the.contains(rows.getJSONObject(i).optLong("dict")))kept.put(rows.get(i));
            rows=kept;
        }
        displayKeys(rows,key);
        addSpellings(rows,norm);
        return rows;
    }
    /** Set around a lookup while 類語 is switched off (per request thread). */
    public final ThreadLocal<Boolean> skipThesaurus=new ThreadLocal<>();
    java.util.Set<Long> thesaurusIds() throws Exception {
        java.util.Set<Long> the=new java.util.HashSet<>();
        JSONArray t=Store.rows(db,"SELECT id FROM dicts d WHERE NOT (1=1"+THESAURUS_FILTER+")");
        for(int i=0;i<t.length();i++)the.add(t.getJSONObject(i).getLong("id"));
        return the;
    }

    static boolean kana(int c){return c>=0x3041&&c<=0x3096||c==0x30fc;}
    static boolean han(int c){return c==0x3005||Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN;}

    /**
     * Pages whose spelling has more kanji than the query, where the query writes some of them in kana:
     * 相まみえる finds 大辞林's 相▽見える (あいまみえる). Only for a query that starts with a kanji and mixes in kana;
     * the query must also fit the page's own reading. Returns [dict,rec,key norm] per page.
     */
    ArrayList<String[]> mixedSpellings(String norm) throws Exception {
        ArrayList<String[]> found=new ArrayList<>();
        int[] q=norm.codePoints().toArray();
        if(q.length<2||q.length>10||!han(q[0]))return found;
        boolean anyKana=false;
        for(int c:q){if(kana(c))anyKana=true;else if(!han(c))return found;}
        if(!anyKana)return found;
        StringBuilder read=new StringBuilder();
        for(int c:q)read.append(kana(c)?Pattern.quote(new String(Character.toChars(c))):"[\u3041-\u3096\u30fc]{1,4}");
        Pattern reading=Pattern.compile(read.toString());
        // The spelling starts with the query's leading kanji and has another kanji right after it (相 + 見える for
        // 相まみえる): a kana there would already be an exact match. That range is small, unlike every key starting 相.
        int run=0;while(run<q.length&&han(q[run]))run++;
        String lead=new String(q,0,run);
        for(Object[] cand:mixedCandidates(lead)){
            String k=(String)cand[0];
            if(k.codePointCount(0,k.length())>q.length||k.equals(norm)||!((Pattern)cand[3]).matcher(norm).matches())continue;
            // A spelling ending in a kanji where the query ends in kana is a different word whose okurigana the
            // dictionary leaves out (日国's 見侮 for 見侮る, 見方): 見る and 見た mustn't find it.
            if(han(k.codePointBefore(k.length()))&&kana(q[q.length-1]))continue;
            // The kana the query adds must be how the page reads: 相まみえる fits あいまみえる.
            boolean fits=false;
            for(String n:(String[])cand[4])if(reading.matcher(n).matches()){fits=true;break;}
            if(fits)found.add(new String[]{(String)cand[1],(String)cand[2],k});
        }
        return found;
    }

    /**
     * Keys that start with these kanji followed by another kanji, each with its pattern (a kanji may be written in
     * kana), kept for the leads looked up recently: a lookup tries several lengths of the same text, and reading and
     * compiling them each time cost ~0.5 s on the phone. {norm, dict, rec, pattern, kana readings of the page (its
     * heading, 大辞林 あいまみ・える, or Yomitan reading)}. Cleared when dictionaries change.
     */
    final java.util.Map<String,List<Object[]>> mixedCache=java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String,List<Object[]>>(64,0.75f,true){
        protected boolean removeEldestEntry(java.util.Map.Entry<String,List<Object[]>> e){return size()>64;}
    });
    List<Object[]> mixedCandidates(String lead){
        List<Object[]> hit=mixedCache.get(lead);
        if(hit!=null)return hit;
        ArrayList<Object[]> list=new ArrayList<>();
        try(Cursor c=db.rawQuery("SELECT DISTINCT k.norm,k.dict,k.rec,r.norm,(SELECT y.reading FROM ytext y WHERE y.rec=k.rec) FROM keys k JOIN dicts d ON d.id=k.dict JOIN records r ON r.id=k.rec WHERE k.norm>=? AND k.norm<? AND length(k.norm)<=10 AND d.enabled=1 AND d.status='ready' AND d.kind!='freq' LIMIT 5000",
                new String[]{lead+"\u3400",lead+"\ua000"})){
            while(c.moveToNext()){
                String k=c.getString(0);
                StringBuilder b=new StringBuilder();boolean ok=true;
                for(int cp:k.codePoints().toArray()){
                    String ch=new String(Character.toChars(cp));
                    if(han(cp))b.append("(?:").append(Pattern.quote(ch)).append("|[\\u3041-\\u3096\\u30fc]{1,4})");
                    else if(kana(cp))b.append(Pattern.quote(ch));
                    else{ok=false;break;}
                }
                if(!ok)continue;
                ArrayList<String> readings=new ArrayList<>();
                for(int col=3;col<=4;col++){
                    if(c.isNull(col))continue;
                    String n=KEY_SEPARATORS.matcher(HtmlText.normalize(c.getString(col))).replaceAll("");
                    if(!n.isEmpty()&&n.codePoints().allMatch(Library::kana))readings.add(n);
                }
                if(!readings.isEmpty())list.add(new Object[]{k,Long.toString(c.getLong(1)),Long.toString(c.getLong(2)),Pattern.compile(b.toString()),readings.toArray(new String[0])});
            }
        }catch(Exception e){return list;}
        mixedCache.put(lead,list);
        return list;
    }

    /** Adds mixedSpellings pages from dictionaries that have no page under the query itself, in dictionary order. */
    JSONArray withMixedSpellings(JSONArray rows,String norm) throws Exception {
        ArrayList<String[]> more=mixedSpellings(norm);
        if(more.isEmpty())return rows;
        java.util.HashSet<Long> have=new java.util.HashSet<>(),recs=new java.util.HashSet<>();
        for(int i=0;i<rows.length();i++)have.add(rows.getJSONObject(i).getLong("dict"));
        ArrayList<JSONObject> all=new ArrayList<>();
        for(int i=0;i<rows.length();i++)all.add(rows.getJSONObject(i));
        for(String[] m:more){
            if(have.contains(Long.parseLong(m[0]))||!recs.add(Long.parseLong(m[1])))continue;
            JSONArray r=Store.rows(db,"SELECT group_concat(k.key,char(1)) keys,k.rec,k.dict,coalesce(nullif((SELECT y.reading FROM ytext y WHERE y.rec=k.rec),''),r.key) page,d.name dictionary,d.kind,r.len size,NULL head FROM keys k JOIN records r ON r.id=k.rec JOIN dicts d ON d.id=k.dict WHERE k.norm=? AND k.rec=? GROUP BY k.dict,k.rec",m[2],m[1]);
            for(int i=0;i<r.length();i++)all.add(r.getJSONObject(i));
        }
        if(all.size()==rows.length())return rows;
        java.util.HashMap<Long,Integer> pos=new java.util.HashMap<>();
        try(Cursor c=db.rawQuery("SELECT id,position FROM dicts",null)){while(c.moveToNext())pos.put(c.getLong(0),c.getInt(1));}
        // A stable sort keeps each dictionary's own order.
        all.sort((a,b)->Integer.compare(pos.getOrDefault(a.optLong("dict"),0),pos.getOrDefault(b.optLong("dict"),0)));
        return new JSONArray(all);
    }

    static final Pattern BRACKET_SPELLING=Pattern.compile("【([^】]{1,40})】");
    final java.util.Map<Long,JSONArray> spellingCache=java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<Long,JSONArray>(256,0.75f,true){
        protected boolean removeEldestEntry(java.util.Map.Entry<Long,JSONArray> e){return size()>4000;}
    });

    /**
     * For a query in kana, each row gets "words": the page's written spellings, so homophones
     * (けんのう: 権能, 献納) can be told apart. A page filed under a spelling has that one; a page filed under
     * its reading (大辞林, NHK) lists every 【…】 heading on it, since it may hold several words.
     */
    void addSpellings(JSONArray rows,String norm) throws Exception {
        if(norm.isEmpty()||!norm.codePoints().allMatch(Library::kana))return;
        for(int i=0;i<rows.length();i++){
            JSONObject o=rows.getJSONObject(i);
            long rec=o.getLong("rec");
            JSONArray words=spellingCache.get(rec);
            if(words==null){words=spellingsOf(rec);spellingCache.put(rec,words);}
            if(words.length()>0)o.put("words",words);
        }
    }

    JSONArray spellingsOf(long rec) throws Exception {
        LinkedHashSet<String> out=new LinkedHashSet<>();
        JSONArray r=Store.rows(db,"SELECT dict,key FROM records WHERE id=?",Long.toString(rec));
        if(r.length()==0)return new JSONArray();
        String key=stripMarks(r.getJSONObject(0).getString("key")).replace("×","").trim();
        if(key.codePoints().anyMatch(Library::han))out.add(key);
        else if(!isYomitan(r.getJSONObject(0).getLong("dict"))){
            String raw=recordHtml(rec);
            out.addAll(headingSpellings(raw));
            Matcher m=BRACKET_SPELLING.matcher(HtmlText.entities(raw.replaceAll("(?s)<(rt|rp)\\b[^>]*>.*?</\\1>","").replaceAll("<[^>]*>","")));
            while(m.find()&&out.size()<12){
                for(String part:m.group(1).split("[《》〈〉・,，、]")){
                    String t=stripMarks(part).replace("×","").replace("▲","").replaceAll("[()（）\\s]","").trim();
                    if(!t.isEmpty()&&t.codePoints().anyMatch(Library::han))out.add(t);
                }
            }
        }
        return new JSONArray(out);
    }

    /** Search results: mixedSpellings pages count as exact matches, placed after the query's own. */
    JSONArray withMixedExact(JSONArray items,String norm) throws Exception {
        JSONArray exact=new JSONArray(),rest=new JSONArray();
        for(int i=0;i<items.length();i++){JSONObject o=items.getJSONObject(i);(o.optInt("exact")==1?exact:rest).put(o);}
        int before=exact.length();
        exact=withMixedSpellings(exact,norm);
        if(exact.length()==before)return items;
        for(int i=0;i<exact.length();i++){JSONObject o=exact.getJSONObject(i);o.remove("size");o.remove("head");o.put("exact",1);}
        for(int i=0;i<rest.length();i++)exact.put(rest.get(i));
        return exact;
    }

    /** Entries in kanji dictionaries for each distinct CJK character in the text. */
    public JSONArray kanji(String text) throws Exception {
        JSONArray result=new JSONArray();
        LinkedHashSet<Integer> chars=new LinkedHashSet<>();
        text.codePoints().filter(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN).limit(12).forEach(chars::add);
        for(int c:chars){
            String s=new String(Character.toChars(c));
            JSONArray rows=Store.rows(db,"SELECT DISTINCT k.key,k.rec,k.dict,d.name dictionary,(SELECT 1 FROM kanji j WHERE j.rec=k.rec AND j.char=?) head FROM keys k JOIN dicts d ON d.id=k.dict WHERE k.norm=? AND d.kind='kanji' AND d.enabled=1 AND d.status='ready' ORDER BY d.position,head IS NULL LIMIT 8",s,HtmlText.normalize(s));
            if(rows.length()>0)result.put(new JSONObject().put("char",s).put("entries",rows));
        }
        return result;
    }

    /**
     * A window of a dictionary's pages in index order, for scrolling through it like a paper dictionary.
     * dir "after"/"before" continue from (norm,id); "from" starts at the first page >= prefix.
     */
    public JSONObject browse(long dict,String dir,String norm,long id,String prefix,int limit,boolean kanji) throws Exception {
        String d=Long.toString(dict),n=Integer.toString(Math.max(1,Math.min(400,limit)));
        JSONArray rows;
        if(kanji&&Store.rows(db,"SELECT 1 FROM kanji WHERE dict=? LIMIT 1",d).length()>0){
            // Kanji dictionaries browse by head character, ordered by stroke count.
            if(dir.equals("before")){
                rows=Store.rows(db,"SELECT rec,char key,sortkey norm,strokes,level FROM kanji WHERE dict=? AND (sortkey<? OR (sortkey=? AND rec<?)) ORDER BY sortkey DESC,rec DESC LIMIT ?",d,norm,norm,Long.toString(id),n);
                ArrayList<Object> list=new ArrayList<>();for(int i=rows.length()-1;i>=0;i--)list.add(rows.get(i));rows=new JSONArray(list);
            }else if(dir.equals("after")){
                rows=Store.rows(db,"SELECT rec,char key,sortkey norm,strokes,level FROM kanji WHERE dict=? AND (sortkey>? OR (sortkey=? AND rec>?)) ORDER BY sortkey,rec LIMIT ?",d,norm,norm,Long.toString(id),n);
            }else{
                String p=prefix==null?"":prefix.trim();String from="";
                if(p.matches("\\d+"))from=String.format(Locale.ROOT,"%03d",Integer.parseInt(p));
                else if(!p.isEmpty()){JSONArray k=Store.rows(db,"SELECT sortkey FROM kanji WHERE dict=? AND char=? LIMIT 1",d,p.substring(0,Character.charCount(p.codePointAt(0))));if(k.length()>0)from=k.getJSONObject(0).getString("sortkey");}
                rows=Store.rows(db,"SELECT rec,char key,sortkey norm,strokes,level FROM kanji WHERE dict=? AND sortkey>=? ORDER BY sortkey,rec LIMIT ?",d,from,n);
            }
            return new JSONObject().put("items",rows).put("kanji",true);
        }
        if(dir.equals("before")){
            rows=Store.rows(db,"SELECT id rec,key,norm FROM records WHERE dict=? AND (norm<? OR (norm=? AND id<?)) ORDER BY norm DESC,id DESC LIMIT ?",d,norm,norm,Long.toString(id),n);
            ArrayList<Object> list=new ArrayList<>();for(int i=rows.length()-1;i>=0;i--)list.add(rows.get(i));
            rows=new JSONArray(list);
        }else if(dir.equals("after")){
            rows=Store.rows(db,"SELECT id rec,key,norm FROM records WHERE dict=? AND (norm>? OR (norm=? AND id>?)) ORDER BY norm,id LIMIT ?",d,norm,norm,Long.toString(id),n);
        }else{
            rows=Store.rows(db,"SELECT id rec,key,norm FROM records WHERE dict=? AND norm>=? ORDER BY norm,id LIMIT ?",d,HtmlText.normalize(prefix==null?"":prefix),n);
            if(rows.length()==0)rows=Store.rows(db,"SELECT id rec,key,norm FROM records WHERE dict=? ORDER BY norm DESC,id DESC LIMIT 1",d);
        }
        for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);r.put("key",stripMarks(r.getString("key")));}
        return new JSONObject().put("items",rows);
    }

    /** Kanji grid: head characters of a kanji dictionary filtered by 漢検 level, strokes, radical and flags. */
    public JSONObject kanjiGrid(long dict,String level,int strokes,String radical,String flag) throws Exception {
        ArrayList<String> args=new ArrayList<>();args.add(Long.toString(dict));
        StringBuilder where=new StringBuilder("dict=?");
        if(level!=null&&!level.isEmpty()){where.append(" AND level=?");args.add(level);}
        if(strokes>0){where.append(strokes>=30?" AND strokes>=?":" AND strokes=?");args.add(Integer.toString(strokes));}
        if(radical!=null&&!radical.isEmpty()){where.append(" AND radical=?");args.add(radical);}
        if(flag!=null&&!flag.isEmpty()){where.append(" AND instr(flags,?)>0");args.add(flag);}
        JSONArray cells=Store.rows(db,"SELECT rec,char,strokes,level,flags FROM kanji WHERE "+where+" ORDER BY sortkey,rec LIMIT 12000",args.toArray(new String[0]));
        String d=Long.toString(dict);
        JSONObject facets=new JSONObject()
            .put("levels",Store.rows(db,"SELECT level v,count(*) n FROM kanji WHERE dict=? AND level!='' GROUP BY level",d))
            .put("strokes",Store.rows(db,"SELECT strokes v,count(*) n FROM kanji WHERE dict=? AND strokes>0 GROUP BY strokes ORDER BY strokes",d))
            .put("radicals",Store.rows(db,"SELECT radical v,count(*) n,min(rstrokes) rs FROM kanji WHERE dict=? AND radical!='' GROUP BY radical ORDER BY min(strokes-max(rstrokes,0)),radical",d))
            .put("flags",Store.rows(db,"SELECT '常' v,count(*) n FROM kanji WHERE dict=? AND instr(flags,'常')>0 UNION ALL SELECT '教',count(*) FROM kanji WHERE dict=? AND instr(flags,'教')>0 UNION ALL SELECT '人',count(*) FROM kanji WHERE dict=? AND instr(flags,'人')>0",d,d,d));
        long total=Store.rows(db,"SELECT count(*) n FROM kanji WHERE "+where,args.toArray(new String[0])).getJSONObject(0).getLong("n");
        return new JSONObject().put("cells",cells).put("facets",facets).put("total",total);
    }

    /** Short plain definition for a word (first matching word-dictionary page), for word-list rows and quick cards. */
    public JSONObject gloss(String word) throws Exception {
        JSONArray rows=wordEntries(exact(word,null,false));
        if(rows.length()==0){JSONArray f=forms(word);if(f.length()>0)rows=f.getJSONObject(0).getJSONArray("items");}
        if(rows.length()==0)return new JSONObject().put("word",word).put("text","");
        // Homographs: the first-numbered entry (먹다¹ "eat", not 먹다² "go deaf") is the usual meaning.
        JSONObject r=rows.getJSONObject(0);
        for(int i=1;i<rows.length();i++){JSONObject x=rows.getJSONObject(i);if(x.getLong("dict")==r.getLong("dict")&&x.getLong("rec")<r.getLong("rec"))r=x;}
        String text="";
        try{
            HtmlText t=HtmlText.parse(MarkupFix.html(recordHtml(r.getLong("rec"))));
            text=t.definitions.toString().replaceAll("\\s+"," ").trim();
            if(text.length()>160)text=text.substring(0,160)+"…";
        }catch(Exception ignored){}
        return new JSONObject().put("word",word).put("text",text).put("rec",r.getLong("rec")).put("dict",r.getLong("dict")).put("dictionary",r.getString("dictionary")).put("page",r.optString("page"));
    }

    /** A record's definition text, shortened (for hover popups and quick cards). */
    public String glossText(long rec,int max) throws Exception {
        HtmlText t=HtmlText.parse(MarkupFix.html(recordHtml(rec)));
        String text=t.definitions.toString().replaceAll("\\s+"," ").trim();
        return text.length()>max?text.substring(0,max)+"…":text;
    }

    /** The reading a word is filed under: the page key of its first Japanese word-dictionary entry (日本 → にほん), else null. */
    public String readingOf(String word){
        try{
            JSONArray rows=Store.rows(db,"SELECT r.norm FROM keys k JOIN records r ON r.id=k.rec JOIN dicts d ON d.id=k.dict WHERE k.norm=? AND d.enabled=1 AND d.status='ready' AND d.kind='term' ORDER BY d.position LIMIT 1",HtmlText.normalize(word));
            return rows.length()==0?null:rows.getJSONObject(0).getString("norm");
        }catch(Exception e){return null;}
    }

    /** Furoku: appendix pages shipped inside a dictionary's .mdd (HTML, PDF). */
    public JSONArray appendix(long dict) throws Exception {
        return Store.rows(db,"SELECT name FROM resources WHERE dict=? AND (name LIKE '%.html' OR name LIKE '%.htm' OR name LIKE '%.pdf') ORDER BY name",Long.toString(dict));
    }
    public JSONArray appendixCounts() throws Exception {
        return Store.rows(db,"SELECT dict,count(*) n FROM resources WHERE name LIKE '%.html' OR name LIKE '%.htm' OR name LIKE '%.pdf' GROUP BY dict");
    }

    /** A random page of one dictionary (or of all enabled word dictionaries). */
    public JSONObject random(long dict) throws Exception {
        JSONArray range=dict>0?Store.rows(db,"SELECT id,rec_min a,rec_max b FROM dicts WHERE id=?",Long.toString(dict))
            :Store.rows(db,"SELECT id,rec_min a,rec_max b FROM dicts WHERE enabled=1 AND status='ready' AND kind='term' ORDER BY random() LIMIT 1");
        if(range.length()==0)throw new Exception("No dictionary to pick from.");
        JSONObject r=range.getJSONObject(0);
        java.util.Random random=new java.util.Random();
        for(int attempt=0;attempt<5;attempt++){
            long pick=r.getLong("a")+(long)(random.nextDouble()*(r.getLong("b")-r.getLong("a")+1));
            JSONArray rows=Store.rows(db,"SELECT r.id rec,r.dict,r.key,d.name dictionary FROM records r JOIN dicts d ON d.id=r.dict WHERE r.dict=? AND r.id>=? ORDER BY r.id LIMIT 1",Long.toString(r.getLong("id")),Long.toString(pick));
            if(rows.length()>0){JSONObject o=rows.getJSONObject(0);o.put("key",stripMarks(o.getString("key")));if(!o.getString("key").startsWith("@"))return o;}
        }
        throw new Exception("Couldn’t pick a word.");
    }

    /** Previous and next pages of the same dictionary in index order, for browsing. */
    public JSONObject neighbors(long rec) throws Exception {
        JSONArray rows=Store.rows(db,"SELECT dict,norm FROM records WHERE id=?",Long.toString(rec));
        if(rows.length()==0)return new JSONObject();
        String dict=Long.toString(rows.getJSONObject(0).getLong("dict")),norm=rows.getJSONObject(0).getString("norm");
        JSONArray prev=Store.rows(db,"SELECT id rec,key FROM records WHERE dict=? AND (norm<? OR (norm=? AND id<?)) ORDER BY norm DESC,id DESC LIMIT 1",dict,norm,norm,Long.toString(rec));
        JSONArray next=Store.rows(db,"SELECT id rec,key FROM records WHERE dict=? AND (norm>? OR (norm=? AND id>?)) ORDER BY norm,id LIMIT 1",dict,norm,norm,Long.toString(rec));
        return new JSONObject().put("prev",prev.length()>0?prev.get(0):JSONObject.NULL).put("next",next.length()>0?next.get(0):JSONObject.NULL);
    }

    public JSONObject record(long rec) throws Exception {
        JSONArray rows=Store.rows(db,"SELECT r.id rec,r.dict,r.key,d.name dictionary,d.kind FROM records r JOIN dicts d ON d.id=r.dict WHERE r.id=?",Long.toString(rec));
        if(rows.length()==0)throw new Exception("This entry is no longer in your library.");
        return rows.getJSONObject(0);
    }

    public String recordHtml(long rec) throws Exception {
        JSONArray rows=Store.rows(db,"SELECT dict,off,len,key FROM records WHERE id=?",Long.toString(rec));
        if(rows.length()==0)throw new Exception("This entry is no longer in your library.");
        JSONObject r=rows.getJSONObject(0);
        if(isYomitan(r.getLong("dict")))return yomitanHtml(rec,r.getLong("dict"),r.getString("key"));
        MdictFile f=file(r.getLong("dict"),0);
        return f.text(f.record(r.getLong("off"),r.getInt("len"),cache));
    }

    /** Resolves a stable bookmark (dictionary + page key) after a re-import. */
    public long findRecord(long dict,String pageKey) throws Exception {
        JSONArray rows=Store.rows(db,"SELECT id FROM records WHERE dict=? AND norm=? ORDER BY id LIMIT 1",Long.toString(dict),HtmlText.normalize(pageKey));
        return rows.length()==0?0:rows.getJSONObject(0).getLong("id");
    }

    public JSONObject reference(long dict,String ref) throws Exception {
        String r=ref;
        if(r.startsWith("entry://"))r=r.substring(8);
        else if(r.startsWith("bword://"))r=r.substring(8);
        try{r=java.net.URLDecoder.decode(r.replace("+","%2B"),"UTF-8");}catch(Exception ignored){}
        String fragment="";
        int hash=r.indexOf('#');
        if(hash>=0){fragment=r.substring(hash+1);r=r.substring(0,hash);}
        JSONObject out=new JSONObject();
        String anchorSource=r.isEmpty()?fragment:r;
        int dash=anchorSource.indexOf('-');
        String prefix=dash>0?anchorSource.substring(0,dash):anchorSource;
        JSONArray rows=Store.rows(db,"SELECT rec FROM anchors WHERE dict=? AND anchor=?",Long.toString(dict),prefix);
        if(rows.length()>0)return out.put("rec",rows.getJSONObject(0).getLong("rec")).put("anchor",anchorSource);
        if(!r.isEmpty()){
            JSONArray keys=Store.rows(db,"SELECT rec,key FROM keys WHERE norm=? AND dict=? LIMIT 1",HtmlText.normalize(r),Long.toString(dict));
            if(keys.length()==0)keys=Store.rows(db,"SELECT k.rec,k.key FROM keys k JOIN dicts d ON d.id=k.dict WHERE k.norm=? AND d.enabled=1 ORDER BY d.position LIMIT 1",HtmlText.normalize(r));
            if(keys.length()>0)return out.put("rec",keys.getJSONObject(0).getLong("rec")).put("key",keys.getJSONObject(0).getString("key")).put("anchor",fragment);
        }
        return out.put("rec",JSONObject.NULL);
    }

    public byte[] resource(long dict,String path) throws Exception {
        if(isYomitan(dict))return yomitanResource(dict,path);
        String name=resourceName(path);
        JSONArray rows=Store.rows(db,"SELECT file,off,len FROM resources WHERE dict=? AND name=?",Long.toString(dict),name);
        if(rows.length()==0){
            // Some exports reference files without their folder, or with a different extension case.
            int slash=name.lastIndexOf('/');
            if(slash>=0)rows=Store.rows(db,"SELECT file,off,len FROM resources WHERE dict=? AND name=?",Long.toString(dict),name.substring(slash+1));
        }
        if(rows.length()==0){
            // Some exports link 0021.aac while the resource is stored as 21.aac.
            java.util.regex.Matcher m=Pattern.compile("^(.*?)(0+)(\\d+\\.\\w+)$").matcher(name);
            if(m.find())rows=Store.rows(db,"SELECT file,off,len FROM resources WHERE dict=? AND name=?",Long.toString(dict),m.group(1)+m.group(3));
        }
        if(rows.length()==0)return null;
        JSONObject r=rows.getJSONObject(0);
        MdictFile f=file(dict,r.getInt("file"));
        return f.record(r.getLong("off"),r.getInt("len"),cache);
    }

    // ---------- lookup of selected or tapped text ----------

    boolean isHeadword(String word) throws Exception {
        String n=HtmlText.normalize(word);
        if(n.isEmpty())return false;
        try(Cursor c=db.rawQuery("SELECT 1 FROM keys k JOIN dicts d ON d.id=k.dict WHERE k.norm=? AND d.enabled=1 AND d.status='ready' AND d.kind='term' LIMIT 1",new String[]{n})){return c.moveToFirst();}
    }

    /** Helper verbs after a verb's -아/어 form, with what they add. */
    static final java.util.Map<String,String> KO_AUX=java.util.Map.ofEntries(
        java.util.Map.entry("내다","all the way, out"),java.util.Map.entry("주다","for someone"),java.util.Map.entry("드리다","for someone, humble"),
        java.util.Map.entry("보다","try doing"),java.util.Map.entry("버리다","completely, regrettably"),java.util.Map.entry("놓다","in advance, and leave it"),
        java.util.Map.entry("두다","in advance, and keep it"),java.util.Map.entry("가다","gradually, going on"),java.util.Map.entry("오다","gradually, up to now"),
        java.util.Map.entry("지다","becoming, passive"),java.util.Map.entry("대다","over and over"),java.util.Map.entry("있다","resulting state"),
        java.util.Map.entry("치우다","get it over with"),java.util.Map.entry("먹다","(regrettably) end up"),java.util.Map.entry("나다","through to the end"));
    static final java.util.Set<String> NOUN_PARTICLES=java.util.Set.of("의","을","를","에","에서","에게","한테","께","께서","와","과","처럼","만큼","조차","마저","밖에","로","으로","로부터","으로부터","에게서","한테서","에서부터");
    static final String[] KO_PARTICLES={"에서부터","에게서","한테서","으로부터","로부터","이라고","이라는","이라도","이었다","이었어요","입니다","이에요","이지만","에서도","에게도","한테도","까지도","부터도","으로도","에서는","에게는","으로는","이든지","이랑","이나","이야","이며","이고","인데","였다","였어요","예요","라고","라는","라도","로도","로는","에도","에는","과는","와는","하고","처럼","보다","만큼","까지","부터","조차","마저","밖에","든지","에서","에게","한테","께서","으로","이다","이든","로","와","과","도","만","들","의","은","는","이","가","을","를","에","께","랑","나","야","며","고","요","든"};

    boolean koKey(String word) throws Exception {return isHeadword(word);}
    /**
     * Moves an analysis ahead of the ones before it only when its word is at least four times more common, so close
     * calls keep their rule order (걸었다고: 걷다, or 걸다) while 가나다 + -면서 or the rare 아다 give way to 가다, 알다.
     */
    void preferCommon(ArrayList<JSONObject> out,int from,int to){
        if(to-from<2)return;
        HashMap<String,Double> ranks=new HashMap<>();
        for(int i=from;i<to;i++)ranks.put(out.get(i).optString("base"),koRank(out.get(i).optString("base")));
        for(int i=from+1;i<to;i++){
            for(int j=i;j>from;j--){
                double a=ranks.get(out.get(j).optString("base")),b=ranks.get(out.get(j-1).optString("base"));
                if(a<Double.MAX_VALUE&&(b==Double.MAX_VALUE||a*4<b)){JSONObject t=out.get(j);out.set(j,out.get(j-1));out.set(j-1,t);}
                else break;
            }
        }
    }
    /** A Korean word's best rank in the Korean frequency lists (CC100…); unknown words sort last. */
    double koRank(String word){
        try(Cursor c=db.rawQuery("SELECT min(m.value) FROM meta m JOIN dicts d ON d.id=m.dict WHERE m.norm=? AND m.mode='freq' AND m.value>0 AND d.enabled=1 AND d.grp LIKE 'Korean%'",new String[]{HtmlText.normalize(word)})){
            if(c.moveToFirst()&&!c.isNull(0))return c.getDouble(0);
        }catch(Exception e){}
        return Double.MAX_VALUE;
    }
    JSONArray koEntries(String word) throws Exception {return wordEntries(exact(word,null,false));}

    /**
     * Korean analyses, best first. Each has base (the dictionary word), explain, chain, items (its entries)
     * and extra (entries for the ending/particle/second part), so the real word leads and grammar is one tab away.
     */
    public JSONArray koreanAnalyses(String word) throws Exception {
        ArrayList<JSONObject> out=new ArrayList<>();
        java.util.HashSet<String> seen=new java.util.HashSet<>();
        java.util.function.BiConsumer<JSONObject,String> add=(o,base)->{if(seen.add(base))out.add(o);};
        // 1. Whole word.
        JSONArray whole=koEntries(word);
        if(whole.length()>0)add.accept(new JSONObject().put("base",word).put("explain","").put("chain",word).put("items",whole),word);
        // 2. Rule-based conjugation (irregular verbs, auxiliaries).
        int rulesFrom=out.size();
        for(Deinflect.Candidate c:Deinflect.korean(word,w->{try{return koKey(w);}catch(Exception e){return false;}},this::koreanStems)){
            if(c.steps.size()==1&&c.steps.get(0).label.startsWith("noun + particle"))continue;// handled below with the dictionary
            JSONArray rows=koEntries(c.base);if(rows.length()==0)continue;
            add.accept(new JSONObject().put("base",c.base).put("explain",c.explain()).put("chain",c.chain()).put("items",rows),c.base);
        }
        // 아세요 is 알다 (ㄹ drops) far more often than the rare 아다.
        preferCommon(out,rulesFrom,out.size());
        // 3. Stem + an ending the dictionary lists (간다며 = 가다 + -ㄴ다며; 먹는다며 = 먹다 + -는다며).
        int endingsFrom=out.size();
        for(int k=word.length()-1;k>=1&&out.size()<6;k--){
            String left=word.substring(0,k),ending=word.substring(k);
            char last=left.charAt(left.length()-1);
            ArrayList<String[]> splits=new ArrayList<>();// {stem, ending}
            splits.add(new String[]{left,ending});
            if(last>=0xAC00&&last<=0xD7A3){
                int jong=(last-0xAC00)%28;
                String[] merged={null,null,null,null,"ㄴ",null,null,null,"ㄹ",null,null,null,null,null,null,null,"ㅁ","ㅂ",null,null,"ㅆ"};
                if(jong<merged.length&&merged[jong]!=null){
                    String open=left.substring(0,left.length()-1)+(char)(last-jong);
                    splits.add(new String[]{open,merged[jong]+ending});
                }
            }
            for(String[] sp:splits){
                if(sp[0].isEmpty())continue;
                // A particle that only follows nouns isn't a verb ending: 비운의 is 비운 + 의, not "비운다 + -의" (a noun +
                // particle is found below). Particles that are also endings (-고, -든지, -라는) still count.
                if(NOUN_PARTICLES.contains(sp[1]))continue;
                // Some endings are listed in their own dictionary form: -잖아(요) and -잖니 under -잖다 (먹었잖아).
                String endingKey="-"+sp[1];
                if(!koKey(endingKey)&&sp[1].matches("잖(아|아요|니|냐|어|습니까|아서)"))endingKey="-잖다";
                // Polite 요 on an ending the dictionary lists without it: -더라고요 → -더라고, -거든요, -면서요.
                if(!koKey(endingKey)&&sp[1].length()>1&&sp[1].endsWith("요")&&koKey("-"+sp[1].substring(0,sp[1].length()-1)))endingKey="-"+sp[1].substring(0,sp[1].length()-1);
                // Colloquial -나 for the question-quoting -냐 (가나면서 = 가냐면서, 먹나고).
                if(!koKey(endingKey)&&sp[1].length()>1&&sp[1].charAt(0)=='나'&&koKey("-냐"+sp[1].substring(1)))endingKey="-냐"+sp[1].substring(1);
                if(!koKey(endingKey))continue;
                String verb=sp[0]+"다";
                JSONArray rows=koEntries(verb);
                if(rows.length()>0){add.accept(new JSONObject().put("base",verb).put("explain","ending -"+sp[1]).put("chain",verb+" + -"+sp[1]).put("items",rows).put("extra",koEntries(endingKey)),verb);continue;}
                // A conjugated stem before the ending: 갔다며 = 갔(가다, past) + -다며. When two verbs fit (걸었다고: 걷다 "walk"
                // or 걸다 "bet"), both are kept, since only the context can tell; lookups offer the second as "or 걸다".
                int found=0;
                for(Deinflect.Candidate c:Deinflect.korean(verb,w->{try{return koKey(w);}catch(Exception e){return false;}},this::koreanStems)){
                    JSONArray r2=koEntries(c.base);if(r2.length()==0)continue;
                    String ex=c.explain().replaceAll("\\s*·?\\s*plain$","");
                    add.accept(new JSONObject().put("base",c.base).put("explain",(ex.isEmpty()?"":ex+" + ")+"ending -"+sp[1]).put("chain",c.base+" + "+c.chain().substring(c.base.length()).replaceFirst("\\s*\\+\\s*다$","")+" + -"+sp[1]).put("items",r2).put("extra",koEntries(endingKey)),c.base);
                    if(++found==2)break;
                }
            }
        }
        // Where different splits compete (가나면서: 가나다 + -면서 or 가다 + -나면서), the more common word leads.
        preferCommon(out,endingsFrom,out.size());
        // 4. Noun + one or two particles (학교에도, 친구들에게).
        for(String p1:KO_PARTICLES){
            if(word.length()<=p1.length()||!word.endsWith(p1))continue;
            String rest=word.substring(0,word.length()-p1.length());
            String particles=p1;
            for(int pass=0;pass<2;pass++){
                if(pass==1){
                    String p2=null;for(String p:KO_PARTICLES)if(rest.length()>p.length()&&rest.endsWith(p)){p2=p;break;}
                    if(p2==null)break;
                    rest=rest.substring(0,rest.length()-p2.length());particles=p2+" + "+p1;
                }
                JSONArray rows=koEntries(rest);
                JSONArray extra=koEntries(p1);
                if(rows.length()>0){add.accept(new JSONObject().put("base",rest).put("explain","noun + "+particles).put("chain",rest+" + "+particles).put("items",rows).put("extra",extra),rest);break;}
                JSONObject[] parts=koCompound(rest);
                if(parts!=null){
                    // Not one dictionary word: show each part as its own word, the particles going with the last.
                    add.accept(parts[0],parts[0].getString("base"));
                    String b=parts[1].getString("base");
                    parts[1].put("explain","noun + "+particles).put("chain",b+" + "+particles).put("extra",extra);
                    add.accept(parts[1],b);break;
                }
            }
        }
        // 4b. A verb in its -아/어 form + a helper verb, each conjugated as usual: 쏟아내 = 쏟다 + 내다 ("pour out"),
        // 먹어 버렸어 = 먹다 + 버리다, 알아봤어요 = 알다 + 보다. Dictionaries rarely list every pair as one word.
        if(out.isEmpty())for(int k=word.length()-1;k>=1&&out.isEmpty();k--){
            String left=word.substring(0,k),right=word.substring(k);
            char last=left.charAt(left.length()-1);
            if(last<0xAC00||last>0xD7A3||(last-0xAC00)%28!=0)continue;// the -아/어 form ends in an open syllable
            String aux=null,auxNote=null;JSONArray auxRows=null;
            for(Deinflect.Candidate c:Deinflect.korean(right,w->{try{return koKey(w);}catch(Exception e){return false;}},this::koreanStems)){
                String note=KO_AUX.get(c.base);if(note==null)continue;
                JSONArray rows=koEntries(c.base);if(rows.length()==0)continue;
                aux=c.base;auxNote=note+(c.explain().isEmpty()?"":" · "+c.explain());auxRows=rows;break;
            }
            if(aux==null&&KO_AUX.containsKey(right+"다")&&koKey(right+"다")){aux=right+"다";auxNote=KO_AUX.get(aux);auxRows=koEntries(aux);}
            if(aux==null)continue;
            for(Deinflect.Candidate c:Deinflect.korean(left,w->{try{return koKey(w);}catch(Exception e){return false;}},this::koreanStems)){
                if(!c.explain().contains("informal")&&!c.chain().contains("아/어"))continue;
                JSONArray rows=koEntries(c.base);if(rows.length()==0)continue;
                String auxEnding=aux.substring(0,aux.length()-1);
                add.accept(new JSONObject().put("base",c.base).put("explain","+ "+aux+" ("+auxNote+")").put("chain",c.base+" + -아/어 "+auxEnding+"-").put("items",rows).put("extra",auxRows),c.base);
                break;
            }
        }
        // 5. Compound noun (장례식장 = 장례 + 식장), or a prefix + a conjugated word (떠 + 올렸다 → 올리다).
        if(out.isEmpty()){JSONObject[] c=koCompound(word);if(c!=null){add.accept(c[0],c[0].getString("base"));add.accept(c[1],c[1].getString("base"));}}
        if(out.isEmpty()&&word.length()>=3){
            for(int k=1;k<=Math.min(2,word.length()-2)&&out.isEmpty();k++){
                String head=word.substring(0,k),rest=word.substring(k);
                if(!koKey(head))continue;
                for(Deinflect.Candidate c:Deinflect.korean(rest,w->{try{return koKey(w);}catch(Exception e){return false;}},this::koreanStems)){
                    if(c.steps.size()==1&&c.steps.get(0).label.startsWith("noun + particle"))continue;
                    JSONArray rows=koEntries(c.base);if(rows.length()==0)continue;
                    add.accept(new JSONObject().put("base",c.base).put("explain","compound: "+head+" + "+c.base+" · "+c.explain()+" (not listed as one word)").put("chain",head+" + "+c.chain()).put("items",rows).put("extra",koEntries(head)),word);
                    break;
                }
            }
        }
        JSONArray result=new JSONArray();
        for(int i=0;i<Math.min(3,out.size());i++)result.put(out.get(i));
        return result;
    }

    /** Splits a word that isn't in the dictionary into two dictionary words (balanced splits with longer parts win). */
    JSONObject[] koCompound(String word) throws Exception {
        JSONObject[] best=null;int bestScore=-1;
        for(int k=1;k<word.length();k++){
            String a=word.substring(0,k),b=word.substring(k);
            if(a.length()<2&&b.length()<2)continue;
            if(!koKey(a)||!koKey(b))continue;
            int score=Math.min(a.length(),b.length())*10+a.length();
            if(score>bestScore){
                bestScore=score;
                best=new JSONObject[]{
                    new JSONObject().put("base",a).put("explain","part 1 of "+word+" ("+a+" · "+b+")").put("chain",a).put("items",koEntries(a)).put("split",true),
                    new JSONObject().put("base",b).put("explain","part 2 of "+word+" ("+a+" · "+b+")").put("chain",b).put("items",koEntries(b))};
            }
        }
        return best;
    }

    /** Kanji dictionaries list okurigana readings (行る) as keys; they must not confirm a conjugation guess. */
    static JSONArray wordEntries(JSONArray rows) throws Exception {
        JSONArray out=new JSONArray();
        for(int i=0;i<rows.length();i++)if(!"kanji".equals(rows.getJSONObject(i).optString("kind")))out.put(rows.get(i));
        return out;
    }

    /** Korean verb/adjective headwords that could be the stem of the given surface form. */
    List<String> koreanStems(String target){
        ArrayList<String> out=new ArrayList<>();
        char first=target.charAt(0);
        if(first<0xAC00||first>0xD7A3)return out;
        int initial=(first-0xAC00)/588;
        for(String h:verbsByInitial(initial)){
                if(h.length()>target.length()+1)continue;
                String stem=h.substring(0,h.length()-1);
                if(stem.length()>target.length())continue;
                boolean ok=true;
                for(int i=0;i<stem.length()&&ok;i++){
                    char a=stem.charAt(i),b=target.charAt(i);
                    if(i<stem.length()-2){ok=a==b;}
                    else if(a!=b){ok=a>=0xAC00&&a<=0xD7A3&&b>=0xAC00&&b<=0xD7A3&&(a-0xAC00)/588==(b-0xAC00)/588;}
                }
                if(ok)out.add(h);
        }
        return out;
    }

    /**
     * Every enabled dictionary's -다 headwords starting with this initial consonant (ㄷ: 대다, 듣다, 되다…), loaded once
     * and kept: a Korean lookup asks for them several times, and reading them from SQLite each time took most of a
     * lookup (~0.6 s on the phone). Cleared when dictionaries change.
     */
    final java.util.Map<Integer,List<String>> verbCache=new java.util.concurrent.ConcurrentHashMap<>();
    List<String> verbsByInitial(int initial){
        return verbCache.computeIfAbsent(initial,i->{
            ArrayList<String> list=new ArrayList<>();
            String lo=String.valueOf((char)(0xAC00+i*588)),hi=String.valueOf((char)(0xAC00+(i+1)*588));
            try(Cursor c=db.rawQuery("SELECT DISTINCT k.norm FROM keys k JOIN dicts d ON d.id=k.dict WHERE k.norm>=? AND k.norm<? AND substr(k.norm,-1)='다' AND length(k.norm)<=12 AND d.enabled=1",new String[]{lo,hi})){
                while(c.moveToNext()){String h=c.getString(0);if(h.length()>=2)list.add(h);}
            }
            return list;
        });
    }
    void dictionariesChanged(){verbCache.clear();mixedCache.clear();}

    /**
     * Dictionary forms for a conjugated word (食べさせられた, 추웠어요, читала), each with its grammatical explanation.
     * Only bases that are real headwords are returned, at most four, strongest explanation first.
     */
    public JSONArray forms(String query) throws Exception {
        String q=java.text.Normalizer.normalize(query.trim(),java.text.Normalizer.Form.NFKC).replaceAll("\\s+"," ");
        JSONArray out=new JSONArray();
        if(q.codePointCount(0,q.length())<2||q.length()>40)return out;
        List<Deinflect.Candidate> candidates;
        char first=q.charAt(0);
        if(q.chars().anyMatch(c->c>=0xAC00&&c<=0xD7A3)){
            JSONArray all=koreanAnalyses(q.replace(" ","")),out2=new JSONArray();
            String qn0=HtmlText.normalize(q);
            for(int i=0;i<all.length();i++)if(!HtmlText.normalize(all.getJSONObject(i).getString("base")).equals(qn0))out2.put(all.get(i));
            return out2;
        }else if(q.chars().anyMatch(c->c>=0x0400&&c<=0x04FF)){
            candidates=Deinflect.russian(q.replace("\u0301","").replace("\u0300",""));
        }else if(q.chars().anyMatch(c->(c>=0x3040&&c<=0x30ff)||(c>=0x4e00&&c<=0x9fff))){
            candidates=Deinflect.japanese(q);
        }else return out;
        boolean exactExists=isHeadword(q);
        String qn=HtmlText.normalize(q);
        // Fewest grammatical steps first; among equals, the one explaining more of the word.
        candidates.sort((a,b)->a.steps.size()!=b.steps.size()?a.steps.size()-b.steps.size():b.strength-a.strength);
        java.util.HashSet<String> seen=new java.util.HashSet<>();
        java.util.HashMap<String,Boolean> known=new java.util.HashMap<>();
        ArrayList<Deinflect.Candidate> deferred=new ArrayList<>();
        ArrayList<Deinflect.Candidate> ordered=new ArrayList<>();
        for(Deinflect.Candidate c:candidates){
            boolean throughWord=false;
            for(String v:c.via){
                Boolean k=known.get(v);if(k==null){k=isHeadword(v);known.put(v,k);}
                if(k){throughWord=true;break;}
            }
            // Prefer stopping at the first real word: 食べました → 食べる before the classical 食ぶ.
            (throughWord?deferred:ordered).add(c);
        }
        ordered.addAll(deferred);
        int direct=ordered.size()-deferred.size(),index=0;
        ArrayList<JSONObject> found=new ArrayList<>();
        for(Deinflect.Candidate c:ordered){
            if(index++>=direct&&found.size()>=1)break;
            if(found.size()>=8)break;
            String n=HtmlText.normalize(c.base);
            if(n.equals(qn)||seen.contains(n))continue;
            // When the typed word is itself a headword, ignore weak one-character guesses (e.g. imperative 書け, adverb 〜く).
            // (Short words only: 張り詰めた being some dictionary's headword doesn't make 張り詰める a guess.)
            if(exactExists&&c.strength<2&&q.codePointCount(0,q.length())<=2)continue;
            String base=c.base;
            JSONArray rows=wordEntries(exact(base,null,false));
            if(rows.length()==0&&base.endsWith("する")&&base.length()>2){
                // Noun + する verbs are listed under the noun (勉強しています → 勉強).
                String noun=base.substring(0,base.length()-2);
                rows=wordEntries(exact(noun,null,false));
                if(rows.length()>0){c.suffixNote="する verb";base=noun;n=HtmlText.normalize(noun);if(seen.contains(n))continue;}
            }
            if(rows.length()==0)continue;
            seen.add(n);
            JSONArray steps=new JSONArray();
            for(Deinflect.Step s:c.steps)steps.put(new JSONObject().put("suffix",s.suffix).put("label",s.label));
            long size=0;for(int i=0;i<rows.length();i++)size=Math.max(size,rows.getJSONObject(i).optLong("size"));
            found.add(new JSONObject().put("base",base).put("explain",c.explain()).put("chain",c.chain().replaceFirst("^"+java.util.regex.Pattern.quote(c.base),base+(c.suffixNote.isEmpty()?"":" + する"))).put("steps",steps).put("items",rows).put("size",size).put("rank",c.steps.size()));
        }
        // Ambiguous endings (行って: 行く/行う) — the word with the fuller entry is usually the intended one.
        found.sort((a,b)->a.optInt("rank")!=b.optInt("rank")?a.optInt("rank")-b.optInt("rank"):Long.compare(b.optLong("size"),a.optLong("size")));
        long best=found.isEmpty()?0:found.get(0).optLong("size");
        for(JSONObject f:found){
            if(out.length()>=3)break;
            // Drop rare readings whose entries are tiny next to the best match.
            if(out.length()>0&&f.optInt("rank")==found.get(0).optInt("rank")&&f.optLong("size")*20<best)continue;
            f.remove("size");f.remove("rank");out.put(f);
        }
        return out;
    }

    /**
     * Finds the longest dictionary headword at the start of the text (after deinflection).
     * Used for selections and for looking up a word by tapping.
     */
    public JSONObject lookup(String text) throws Exception {return lookup(text,"");}

    // ---------- words in a text (for the known-words estimate) ----------
    /** ja, ko, zh, th, ru from a dictionary's group (Japanese/古語 → ja), or "". */
    public String langOfDict(long dict){
        try{
            JSONArray r=Store.rows(db,"SELECT grp FROM dicts WHERE id=?",Long.toString(dict));
            String g=r.length()==0?"":r.getJSONObject(0).optString("grp","");
            return langOfGroup(g);
        }catch(Exception e){return "";}
    }
    static String langOfGroup(String g){
        g=g.split("/")[0];
        return g.equals("Japanese")||g.equals("Kanji")?"ja":g.equals("Korean")?"ko":g.equals("Chinese")?"zh":g.equals("Thai")?"th":g.equals("Russian")?"ru":"";
    }
    /** The language a word is written in when its script says so (Hangul, kana, Thai, Cyrillic), else the fallback. */
    public static String langOfWord(String w,String fallback){
        for(int c:w.codePoints().toArray()){
            if(c>=0xAC00&&c<=0xD7A3)return "ko";
            if(c>=0x3040&&c<=0x30FF)return "ja";
            if(c>=0x0E00&&c<=0x0E7F)return "th";
            if(c>=0x0400&&c<=0x04FF)return "ru";
        }
        return fallback==null||fallback.isEmpty()?"ja":fallback;
    }
    final java.util.Map<String,String> baseCache=java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String,String>(512,0.75f,true){
        protected boolean removeEldestEntry(java.util.Map.Entry<String,String> e){return size()>20000;}
    });
    /**
     * The dictionary words in a text with how often each occurs, as {base: count} in first-seen order (plus the first
     * written form of each). Korean goes word by word (먹었어요 → 먹다); Japanese and Chinese by the longest headword at
     * each position, with conjugations traced back (食べさせられた → 食べる). Punctuation, numbers and Latin are skipped.
     */
    public java.util.LinkedHashMap<String,Object[]> textWords(String text,String lang) throws Exception {
        java.util.LinkedHashMap<String,Object[]> out=new java.util.LinkedHashMap<>();
        if(text==null)return out;
        if("ko".equals(lang)){
            Matcher m=Pattern.compile("[\\uac00-\\ud7a3]+").matcher(text);
            while(m.find()){
                String w=m.group();
                String base=baseCache.get("ko:"+w);
                if(base==null){
                    JSONObject r=lookup(w,"");
                    base=r.getJSONArray("items").length()>0?r.optString("key",""):"";
                    baseCache.put("ko:"+w,base);
                }
                if(!base.isEmpty())count(out,base,w);
            }
            return out;
        }
        int[] cps=text.codePoints().toArray();
        for(int i=0;i<cps.length;){
            int c=cps[i];
            boolean cjk=han(c)||kana(c)||c>=0x30A0&&c<=0x30FF||c>=0x0E00&&c<=0x0E7F||c>=0x0400&&c<=0x04FF;
            if(!cjk){i++;continue;}
            int end=Math.min(cps.length,i+16);
            String window=new String(cps,i,end-i);
            JSONObject r=lookup(window,"zh".equals(lang)?"zh":"");
            String matched=r.optString("matched","");
            if(r.getJSONArray("items").length()==0||matched.isEmpty()){i++;continue;}
            count(out,r.optString("key",matched),matched);
            i+=Math.max(1,matched.codePointCount(0,matched.length()));
        }
        return out;
    }
    static void count(java.util.Map<String,Object[]> out,String base,String form){
        Object[] e=out.get(base);
        if(e==null)out.put(base,new Object[]{1,form});else e[0]=(Integer)e[0]+1;
    }

    /**
     * How many characters from the start of the text begin some headword (警戒してたのに… → 4, 警戒して). Longer prefixes
     * can't be headwords, so a lookup of a whole speech bubble doesn't try all 24 lengths in full (~0.5 s on the phone).
     */
    int keyReach(int[] cps) throws Exception {
        int n=0;
        for(int len=1;len<=cps.length;len++){
            String p=HtmlText.normalize(new String(cps,0,len));
            if(p.isEmpty()){n=len;continue;}
            if(Store.rows(db,"SELECT 1 FROM keys WHERE norm>=? AND norm<? LIMIT 1",p,p+"\uffff").length()==0)break;
            n=len;
        }
        return n;
    }

    /** lang (zh, th): text scanned from a Chinese or Thai screen looks in that language's dictionaries first. */
    public JSONObject lookup(String text,String lang) throws Exception {
        String clean=text.trim();
        String group="zh".equals(lang)?"Chinese":"th".equals(lang)?"Thai":null;
        if(group!=null){
            java.util.Set<Long> ids=new java.util.HashSet<>();
            JSONArray g=Store.rows(db,"SELECT id FROM dicts WHERE (grp=? OR grp LIKE ?) AND enabled=1 AND kind!='freq'",group,group+"/%");
            for(int i=0;i<g.length();i++)ids.add(g.getJSONObject(i).getLong("id"));
            int[] cps=clean.codePoints().limit(24).toArray();
            // Traditional text (Taiwan/Hong Kong games, Cantonese subtitles) is tried as written first, then as simplified: 穿過 → 穿过.
            int[] simp="zh".equals(lang)?simplified(new String(cps,0,cps.length)).codePoints().toArray():cps;
            int reach=Math.max(keyReach(cps),simp==cps?0:keyReach(simp));
            for(int len=Math.min(cps.length,reach);len>0&&!ids.isEmpty();len--)for(int[] form:simp.length==cps.length&&!java.util.Arrays.equals(simp,cps)?new int[][]{cps,simp}:new int[][]{cps}){
                String prefix=new String(form,0,len);
                JSONArray rows=exact(prefix,null),mine=new JSONArray();
                for(int i=0;i<rows.length();i++)if(ids.contains(rows.getJSONObject(i).getLong("dict")))mine.put(rows.get(i));
                if(mine.length()>0){
                    for(int i=0;i<rows.length();i++)if(!ids.contains(rows.getJSONObject(i).getLong("dict")))mine.put(rows.get(i));
                    return new JSONObject().put("matched",prefix).put("key",mine.getJSONObject(0).getString("key")).put("items",mine).put("forms",new JSONArray()).put("kanji",kanji(prefix));
                }
            }
        }
        // Korean: analyse the space-delimited word (whole word, conjugation, stem+ending, noun+particles, compound).
        java.util.regex.Matcher kw=Pattern.compile("^[\\uac00-\\ud7a3]+").matcher(clean);
        if(kw.find()&&kw.group().length()>=2){
            String word=kw.group();
            JSONArray analyses=koreanAnalyses(word);
            // 비운 is both a noun (否運) and 비우다's modifier form. Right before another word ("비운 자리") it's almost
            // always the modifier (the noun would take a particle: 비운의), so that reading goes first.
            if(analyses.length()>1&&analyses.getJSONObject(0).optString("explain").isEmpty()
                &&clean.substring(kw.end()).matches("(?s)\\s+[\\uac00-\\ud7a3].*")){
                for(int i=1;i<analyses.length();i++){
                    if(!analyses.getJSONObject(i).optString("explain").startsWith("adnominal"))continue;
                    JSONObject a=analyses.getJSONObject(i);
                    java.util.List<Object> list=new java.util.ArrayList<>();list.add(a);
                    for(int j=0;j<analyses.length();j++)if(j!=i)list.add(analyses.get(j));
                    analyses=new JSONArray(list);break;
                }
            }
            if(analyses.length()>0){
                JSONObject a0=analyses.getJSONObject(0);
                JSONArray items=new JSONArray();
                JSONArray main=a0.getJSONArray("items"),extra=a0.optJSONArray("extra");
                for(int i=0;i<main.length();i++)items.put(main.get(i));
                if(extra!=null)for(int i=0;i<extra.length();i++)items.put(extra.get(i));
                // A split compound: the second part (and its particle) follow as further tabs.
                if(a0.optBoolean("split")&&analyses.length()>1){
                    JSONObject a1=analyses.getJSONObject(1);
                    for(String f:new String[]{"items","extra"}){JSONArray x=a1.optJSONArray(f);if(x!=null)for(int i=0;i<x.length();i++)items.put(x.get(i));}
                }
                return new JSONObject().put("matched",word).put("key",a0.getString("base")).put("items",items).put("explain",a0.optString("explain","")).put("forms",analyses).put("kanji",new JSONArray());
            }
        }
        int[] cps=clean.codePoints().limit(24).toArray();
        int reach=keyReach(cps);
        for(int len=cps.length;len>0;len--){
            String prefix=new String(cps,0,len);
            // Past the longest start of the text that begins any headword, only a conjugated form (a few characters
            // longer than its stem) or a kana-for-kanji spelling (相まみえる) can still match.
            JSONArray rows=len<=reach?exact(prefix,null):len<=8?exact(prefix,null):new JSONArray();
            boolean hangulPart=prefix.codePoints().anyMatch(c->c>=0xAC00&&c<=0xD7A3)&&len<cps.length;
            JSONArray forms=len>=2&&len<=reach+10&&!hangulPart?forms(prefix):new JSONArray();
            if(rows.length()>0)return withPlainWord(prefix,cps,len,rows,forms);
            if(forms.length()>0){
                JSONObject f=forms.getJSONObject(0);
                return new JSONObject().put("matched",prefix).put("key",f.getString("base")).put("items",f.getJSONArray("items")).put("explain",f.getString("explain")).put("forms",forms).put("kanji",kanji(prefix));
            }
        }
        return new JSONObject().put("matched","").put("key",clean).put("items",new JSONArray()).put("forms",new JSONArray()).put("kanji",kanji(clean));
    }

    /** Leaves thesaurus pages (類語 dictionaries, 日本語シソーラス) out of a lookup's items and forms. */
    public void dropThesaurus(JSONObject r) throws Exception {
        java.util.Set<Long> the=new java.util.HashSet<>();
        JSONArray t=Store.rows(db,"SELECT id FROM dicts d WHERE NOT (1=1"+THESAURUS_FILTER+")");
        for(int i=0;i<t.length();i++)the.add(t.getJSONObject(i).getLong("id"));
        if(the.isEmpty())return;
        java.util.function.Function<JSONArray,JSONArray> keep=a->{JSONArray o=new JSONArray();for(int i=0;a!=null&&i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x!=null&&!the.contains(x.optLong("dict")))o.put(x);}return o;};
        JSONArray items=keep.apply(r.optJSONArray("items"));
        r.put("items",items);
        if(items.length()>0&&the.size()>0)r.put("key",items.getJSONObject(0).optString("key",r.optString("key")));
        JSONArray forms=r.optJSONArray("forms");
        for(int i=0;forms!=null&&i<forms.length();i++){JSONObject f=forms.optJSONObject(i);if(f!=null&&f.has("items"))f.put("items",keep.apply(f.optJSONArray("items")));}
    }

    /** A particle or the copula right after a word: 静かな, 日本の, 穏やかに, 元気だ. */
    // (not か, さ, も… which also end words: 静か, 高さ, 最も)
    static final java.util.Set<String> JA_TAILS=new java.util.HashSet<>(java.util.Arrays.asList("な","の","に","で","だ","って","だった","です","でした","じゃ","では","には"));

    /**
     * A match some dictionary lists as a phrase (気取った, 綺麗な) also brings the plain word: the dictionary form of a
     * conjugation (気取る), or the word without the particle or copula after it (綺麗). When the plain word is in more
     * dictionaries, it comes first, as the page most people want.
     */
    JSONObject withPlainWord(String prefix,int[] cps,int len,JSONArray rows,JSONArray forms) throws Exception {
        JSONArray plain=new JSONArray();String plainKey=null;
        java.util.Set<Long> seen=new java.util.HashSet<>();
        for(int i=0;i<rows.length();i++)seen.add(rows.getJSONObject(i).getLong("rec"));
        for(int f=0;f<forms.length();f++){
            JSONArray fi=forms.getJSONObject(f).optJSONArray("items");
            for(int i=0;fi!=null&&i<fi.length();i++)if(seen.add(fi.getJSONObject(i).getLong("rec"))){plain.put(fi.get(i));if(plainKey==null)plainKey=forms.getJSONObject(f).optString("base",null);}
        }
        for(int cut=1;cut<=3&&cut<len;cut++){
            String tail=new String(cps,len-cut,cut);
            if(!JA_TAILS.contains(tail))continue;
            String shorter=new String(cps,0,len-cut);
            if(!han(cps[len-cut-1]))continue;// after a kanji word only (綺麗な), not inside kana words (この, その)
            JSONArray sr=exact(shorter,null);
            if(sr.length()==0){JSONArray sf=forms(shorter);if(sf.length()>0)sr=sf.getJSONObject(0).getJSONArray("items");}
            for(int i=0;i<sr.length();i++)if(seen.add(sr.getJSONObject(i).getLong("rec"))){plain.put(sr.get(i));if(plainKey==null)plainKey=sr.getJSONObject(i).optString("key",shorter);}
            break;
        }
        // A phrase only one or two dictionaries list (張り詰めた空気): the word it starts with, from all of them.
        java.util.Set<Long> phraseDicts=new java.util.HashSet<>();
        for(int i=0;i<rows.length();i++)phraseDicts.add(rows.getJSONObject(i).getLong("dict"));
        String shorterMatch=null;
        if(plain.length()==0&&phraseDicts.size()<=2){
            // At least half the phrase (so an idiom like 目から鱗が落ちる doesn't shrink to 目).
            for(int l=len-1;l>=Math.max(2,(len+1)/2)&&plain.length()==0;l--){
                String sub=new String(cps,0,l);
                JSONArray sr=exact(sub,null);JSONArray sf=forms(sub);
                java.util.Set<Long> d=new java.util.HashSet<>();
                for(int i=0;i<sr.length();i++)d.add(sr.getJSONObject(i).getLong("dict"));
                for(int f=0;f<sf.length();f++){JSONArray fi=sf.getJSONObject(f).optJSONArray("items");for(int i=0;fi!=null&&i<fi.length();i++)d.add(fi.getJSONObject(i).getLong("dict"));}
                if(d.size()<=phraseDicts.size())continue;
                for(int f=0;f<sf.length();f++){JSONArray fi=sf.getJSONObject(f).optJSONArray("items");for(int i=0;fi!=null&&i<fi.length();i++)if(seen.add(fi.getJSONObject(i).getLong("rec"))){plain.put(fi.get(i));if(plainKey==null)plainKey=sf.getJSONObject(f).optString("base",sub);}}
                for(int i=0;i<sr.length();i++)if(seen.add(sr.getJSONObject(i).getLong("rec"))){plain.put(sr.get(i));if(plainKey==null)plainKey=sr.getJSONObject(i).optString("key",sub);}
                shorterMatch=sub;
            }
        }
        String key=rows.getJSONObject(0).getString("key");
        JSONArray items=rows;
        if(plain.length()>0){
            java.util.Set<Long> a=new java.util.HashSet<>(),b=new java.util.HashSet<>();
            for(int i=0;i<rows.length();i++)a.add(rows.getJSONObject(i).getLong("dict"));
            for(int i=0;i<plain.length();i++)b.add(plain.getJSONObject(i).getLong("dict"));
            items=new JSONArray();
            JSONArray first=b.size()>a.size()?plain:rows,second=first==plain?rows:plain;
            for(int i=0;i<first.length();i++)items.put(first.get(i));
            for(int i=0;i<second.length();i++)items.put(second.get(i));
            if(first==plain&&plainKey!=null)key=plainKey;
            // The shorter word wins: only it is the match, so what follows (空気) is its own word.
            if(first==plain&&shorterMatch!=null)prefix=shorterMatch;
        }
        return new JSONObject().put("matched",prefix).put("key",key).put("items",items).put("forms",forms).put("kanji",kanji(prefix));
    }

    static final Pattern AUDIO_LINK=Pattern.compile("href=[\"']((?:sound://)?[^\"']+?\\.(?:aac|mp3|m4a|ogg|oga|opus|wav|spx)(?:#[^\"']*)?)[\"']",Pattern.CASE_INSENSITIVE);

    /** Pronunciation clips for a word from every dictionary that has audio (NHK, Thai, Korean, Russian…). */
    public JSONArray audioFor(String key,String reading,long preferDict) throws Exception {
        JSONArray out=new JSONArray();
        if(reading!=null&&reading.matches("[\\u3040-\\u30ff・･‐\\-=＝]+"))reading=reading.replaceAll("[・･‐\\-=＝]","");
        JSONArray rows=exact(key,null);
        if(reading!=null&&!reading.isEmpty()&&!HtmlText.normalize(reading).equals(HtmlText.normalize(key))){
            // Pronunciation dictionaries often file words by reading only (NHK: ことば【言葉】); keep pages that mention the word.
            JSONArray byReading=exact(reading,null);
            java.util.HashSet<Long> have=new java.util.HashSet<>();
            for(int i=0;i<rows.length();i++)have.add(rows.getJSONObject(i).getLong("rec"));
            for(int i=0;i<byReading.length();i++){
                JSONObject r=byReading.getJSONObject(i);
                if(have.contains(r.getLong("rec")))continue;
                try{
                    String html=recordHtml(r.getLong("rec"));
                    // Pages that name the word, or kana-only pronunciation pages (NHK ことば) with no other kanji spelling.
                    boolean kanaOnly=isPronunciation(dictGroup(r.getLong("dict")))&&html.indexOf('【')<0;
                    if(bracketsMention(html,key)||kanaOnly)rows.put(r);
                }catch(Exception ignored){}
            }
        }
        java.util.HashSet<String> seen=new java.util.HashSet<>();
        for(int i=0;i<rows.length()&&out.length()<16;i++){
            JSONObject row=rows.getJSONObject(i);
            if("kanji".equals(row.optString("kind")))continue;
            if(Store.rows(db,"SELECT 1 FROM resources WHERE dict=? LIMIT 1",Long.toString(row.getLong("dict"))).length()==0)continue;
            String html;
            try{html=recordHtml(row.getLong("rec"));}catch(Exception e){continue;}
            // Pronunciation pages must be for this reading (not 〜橋 suffix pages read きょう).
            if(reading!=null&&!reading.isEmpty()&&isPronunciation(dictGroup(row.getLong("dict")))&&!html.contains(reading)&&!html.contains(katakana(reading)))continue;
            // Pronunciation pages hold several homographs (はし【端】【箸】【橋】); use only the section headed by this word.
            int from=0,to=html.length();boolean headed=false;int headScore=9;
            if(isPronunciation(dictGroup(row.getLong("dict")))){
                Matcher b=Pattern.compile("[【《]([^】》]*)[】》]").matcher(html);
                ArrayList<int[]> heads=new ArrayList<>();int match=-1;
                int bestScore=9;
                while(b.find()){
                    heads.add(new int[]{b.start(),b.end()});
                    // 0: the heading spells exactly this word; 1: a suffix/compound heading like 【〜雨】.
                    int score=9;
                    for(String part:b.group(1).replaceAll("<[^>]*>","").replace("×","").split("[・,，、/／]")){
                        String p=part.trim();
                        if(p.equals(key))score=Math.min(score,0);
                        else if(p.contains(key))score=Math.min(score,1);
                    }
                    if(score<bestScore){bestScore=score;match=heads.size()-1;}
                }
                headed=match>=0;
                headScore=bestScore;
                if(match>=0){from=heads.get(match)[1];to=match+1<heads.size()?heads.get(match+1)[0]:html.length();}
                else if(!heads.isEmpty()&&html.indexOf(key)<0&&!HtmlText.normalize(key).equals(HtmlText.normalize(reading==null?"":reading))){
                    // Only kana pages without any kanji spelling are safe to use whole.
                    continue;
                }
            }
            Matcher m=AUDIO_LINK.matcher(html);m.region(from,to);int n=0;
            while(m.find()&&n<4){
                String path=m.group(1).replaceFirst("^sound://","").replace('\\','/');
                while(path.startsWith("/"))path=path.substring(1);
                if(!seen.add(row.getLong("dict")+":"+path))continue;
                // Label with the text just before the link (NHK accent notation, example sentence…).
                String before=html.substring(Math.max(0,m.start()-400),m.start());
                int open=before.lastIndexOf('<');if(open>=0&&before.indexOf('>',open)<0)before=before.substring(0,open);
                before=before.replaceAll("^[^<]*>","").replaceAll("<[^>]*>","").replaceAll("[\\s\u3000]+"," ").trim();
                before=HtmlText.entities(before);
                // NHK: the accent notation this clip belongs to (タベ＼ル), shown next to the play button on cards.
                String accent="";
                int at=Math.max(html.lastIndexOf("<accent_text",m.start()),Math.max(html.lastIndexOf("<accent ",m.start()),html.lastIndexOf("<accent>",m.start())));
                if(at>=0&&m.start()-at<600){
                    // Only this clip's own notation: from its accent_text or the previous clip, whichever is nearer.
                    int prev=html.lastIndexOf("</a>",m.start());
                    int from2=Math.max(at,prev<0?at:prev+4);
                    String seg=html.substring(from2,m.start());
                    int dangling=seg.lastIndexOf('<');if(dangling>=0&&seg.indexOf('>',dangling)<0)seg=seg.substring(0,dangling);
                    accent=HtmlText.entities(seg.replaceAll("^[^<]*?>","").replaceAll("<[^>]*>","")).replaceAll("[\\s🔊]","");
                }
                out.put(new JSONObject().put("dict",row.getLong("dict")).put("dictionary",row.getString("dictionary")).put("path",path).put("label",before).put("group",dictGroup(row.getLong("dict"))).put("headed",headed).put("score",headScore).put("accent",accent)
                    .put("pageMatch",HtmlText.normalize(row.optString("page")).equals(HtmlText.normalize(reading==null||reading.isEmpty()?key:reading))||HtmlText.normalize(row.optString("page")).equals(HtmlText.normalize(key))));
                n++;
            }
        }
        // Pronunciation dictionaries first, then the dictionary the word was saved from.
        ArrayList<JSONObject> list=new ArrayList<>();for(int i=0;i<out.length();i++)list.add(out.getJSONObject(i));
        list.sort((a,b)->{
            int pa=isPronunciation(a.optString("group"))?Math.min(a.optInt("score",9),2):a.optLong("dict")==preferDict?3:4;
            int pb=isPronunciation(b.optString("group"))?Math.min(b.optInt("score",9),2):b.optLong("dict")==preferDict?3:4;
            if(pa!=pb)return pa-pb;
            return Boolean.compare(b.optBoolean("pageMatch"),a.optBoolean("pageMatch"));
        });
        return new JSONArray(list);
    }

    /** True when the page names the word: inside a 【…】 spelling if it has one, otherwise anywhere. */
    static boolean bracketsMention(String html,String key){
        Matcher b=Pattern.compile("【([^】]*)】").matcher(html);
        boolean any=false;
        while(b.find()){any=true;if(b.group(1).replaceAll("<[^>]*>","").contains(key))return true;}
        return !any&&html.contains(key);
    }
    static String katakana(String s){
        StringBuilder b=new StringBuilder();
        for(char c:s.toCharArray())b.append(c>='ぁ'&&c<='ゖ'?(char)(c+96):c);
        return b.toString();
    }

    /** Pronunciation dictionaries (NHK) are a type of Japanese dictionary: "Japanese/発音" (formerly the top-level "Pronunciation"). */
    static final String PRONUNCIATION="Japanese/発音";
    static boolean isPronunciation(String grp){return grp!=null&&(grp.equals("Pronunciation")||grp.endsWith("/発音")||grp.endsWith("/Pronunciation"));}

    String dictGroup(long dict){
        try(Cursor c=db.rawQuery("SELECT grp FROM dicts WHERE id=?",new String[]{Long.toString(dict)})){return c.moveToFirst()?c.getString(0):"";}
    }

    public JSONObject stats() throws Exception {
        return Store.rows(db,"SELECT count(*) dictionaries,coalesce(sum(entries),0) entries,coalesce(sum(keys),0) keys FROM dicts WHERE status='ready'").getJSONObject(0);
    }

    static Map<String,String> MIME=new LinkedHashMap<>();
    static{
        MIME.put("css","text/css");MIME.put("js","text/javascript");MIME.put("png","image/png");MIME.put("jpg","image/jpeg");MIME.put("jpeg","image/jpeg");
        MIME.put("gif","image/gif");MIME.put("svg","image/svg+xml");MIME.put("webp","image/webp");MIME.put("bmp","image/bmp");
        MIME.put("mp3","audio/mpeg");MIME.put("aac","audio/aac");MIME.put("m4a","audio/mp4");MIME.put("ogg","audio/ogg");MIME.put("oga","audio/ogg");MIME.put("opus","audio/ogg");MIME.put("wav","audio/wav");MIME.put("spx","audio/ogg");
        MIME.put("ttf","font/ttf");MIME.put("otf","font/otf");MIME.put("woff","font/woff");MIME.put("woff2","font/woff2");MIME.put("html","text/html");MIME.put("htm","text/html");MIME.put("pdf","application/pdf");
    }
    static String mime(String name){
        int dot=name.lastIndexOf('.');
        String type=dot<0?null:MIME.get(name.substring(dot+1).toLowerCase(Locale.ROOT));
        return type==null?"application/octet-stream":type;
    }
    static String utf8(byte[] b){return new String(b,StandardCharsets.UTF_8);}
}
