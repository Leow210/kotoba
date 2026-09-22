package app.kotoba.reader;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import org.json.JSONArray;
import org.json.JSONObject;

/** Imported word lists (frequency lists, JLPT/TOPIK lists…): browse them, look words up, turn them into decks. */
public class WordLists {
    final SQLiteDatabase db;
    public WordLists(SQLiteDatabase db){
        this.db=db;
        db.execSQL("CREATE TABLE IF NOT EXISTS wordlists(id INTEGER PRIMARY KEY,name TEXT NOT NULL,count INTEGER NOT NULL DEFAULT 0,ranked INTEGER NOT NULL DEFAULT 0,added INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS wordlist_items(list_id INTEGER NOT NULL REFERENCES wordlists(id) ON DELETE CASCADE,pos INTEGER NOT NULL,word TEXT NOT NULL,norm TEXT NOT NULL,reading TEXT NOT NULL DEFAULT '',note TEXT NOT NULL DEFAULT '',PRIMARY KEY(list_id,pos)) WITHOUT ROWID");
        db.execSQL("CREATE INDEX IF NOT EXISTS wordlist_norm ON wordlist_items(norm)");
        // あいう order: the reading, or for words imported without one the dictionary page's reading (日本 → にほん).
        try{db.execSQL("ALTER TABLE wordlist_items ADD COLUMN sortkey TEXT");}catch(Exception ignored){}
    }

    /** Fills missing sort keys; readingOf maps a word to its reading from the dictionaries (or null). */
    public void fillSortKeys(long list,java.util.function.Function<String,String> readingOf) throws Exception {
        JSONArray rows=Store.rows(db,"SELECT pos,word,reading,norm FROM wordlist_items WHERE list_id=? AND sortkey IS NULL",Long.toString(list));
        if(rows.length()==0)return;
        SQLiteStatement set=db.compileStatement("UPDATE wordlist_items SET sortkey=? WHERE list_id=? AND pos=?");
        db.beginTransaction();
        try{
            for(int i=0;i<rows.length();i++){
                JSONObject r=rows.getJSONObject(i);
                String key=!r.getString("reading").isEmpty()?HtmlText.normalize(r.getString("reading")):readingOf.apply(r.getString("word"));
                set.bindString(1,key==null||key.isEmpty()?r.getString("norm"):key);set.bindLong(2,list);set.bindLong(3,r.getLong("pos"));set.executeUpdateDelete();
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }

    /**
     * One entry per line: "word", "word<TAB>reading<TAB>meaning", CSV, or with a leading rank ("123 word" / "123,word").
     * Lines starting with # are comments.
     */
    public JSONObject importText(String name,String text) throws Exception {
        String[] lines=text.replace("\r","").split("\n");
        db.beginTransaction();
        try{
            ContentValues v=new ContentValues();v.put("name",name.replaceFirst("\\.[^.]+$",""));v.put("added",System.currentTimeMillis()/1000);
            long id=db.insertOrThrow("wordlists",null,v);
            SQLiteStatement ins=db.compileStatement("INSERT OR REPLACE INTO wordlist_items(list_id,pos,word,norm,reading,note) VALUES(?,?,?,?,?,?)");
            int pos=0;boolean ranked=false;
            java.util.HashSet<String> seen=new java.util.HashSet<>();
            for(String raw:lines){
                String line=raw.trim();
                if(line.isEmpty()||line.startsWith("#"))continue;
                String[] cols=line.contains("\t")?line.split("\t"):line.contains(",")&&!line.matches(".*[\\u3000-\\u9fff\\uac00-\\ud7a3].*,.*[\\u3000-\\u9fff\\uac00-\\ud7a3].*,.*")?line.split(","):line.split(",",-1);
                for(int i=0;i<cols.length;i++)cols[i]=cols[i].trim().replaceAll("^\"|\"$","");
                int c=0;
                // A leading rank column (frequency lists).
                if(cols.length>1&&cols[0].matches("\\d+")){ranked=true;c=1;}
                else if(cols.length==1&&cols[0].matches("\\d+[.)\\s]+\\S.*")){ranked=true;cols=new String[]{cols[0].replaceFirst("^\\d+[.)\\s]+","")};}
                if(c>=cols.length||cols[c].isEmpty())continue;
                String word=cols[c].split("\\s+")[0];
                if(pos==0&&word.equalsIgnoreCase("word")||word.equals("単語")||word.equals("단어"))continue;// header row
                String norm=HtmlText.normalize(word);
                if(norm.isEmpty()||!seen.add(norm))continue;
                ins.bindLong(1,id);ins.bindLong(2,++pos);ins.bindString(3,word);ins.bindString(4,norm);
                ins.bindString(5,c+1<cols.length?cols[c+1]:"");ins.bindString(6,c+2<cols.length?String.join(" · ",java.util.Arrays.copyOfRange(cols,c+2,cols.length)):"");
                ins.executeInsert();
            }
            if(pos==0)throw new Exception("No words found in "+name);
            db.execSQL("UPDATE wordlists SET count=?,ranked=? WHERE id=?",new Object[]{pos,ranked?1:0,id});
            db.setTransactionSuccessful();
            return new JSONObject().put("id",id).put("count",pos);
        }finally{db.endTransaction();}
    }

    public JSONArray lists() throws Exception {return Store.rows(db,"SELECT * FROM wordlists ORDER BY added DESC");}
    /** sort "abc": by sort key (see fillSortKeys); offset then counts rows instead of positions. */
    public JSONArray items(long list,int offset,int limit,String q,String sort) throws Exception {
        if("abc".equals(sort)&&(q==null||q.trim().isEmpty()))
            return Store.rows(db,"SELECT pos,word,reading,note FROM wordlist_items WHERE list_id=? ORDER BY sortkey,pos LIMIT ? OFFSET ?",Long.toString(list),Integer.toString(limit),Integer.toString(offset));
        return items(list,offset,limit,q);
    }
    public JSONArray items(long list,int offset,int limit,String q) throws Exception {
        if(q!=null&&!q.trim().isEmpty())return Store.rows(db,"SELECT pos,word,reading,note FROM wordlist_items WHERE list_id=? AND (instr(norm,?)>0 OR instr(reading,?)>0) ORDER BY pos LIMIT ?",Long.toString(list),HtmlText.normalize(q),q.trim(),Integer.toString(limit));
        return Store.rows(db,"SELECT pos,word,reading,note FROM wordlist_items WHERE list_id=? AND pos>? ORDER BY pos LIMIT ?",Long.toString(list),Integer.toString(offset),Integer.toString(limit));
    }
    public void delete(long id){db.execSQL("DELETE FROM wordlist_items WHERE list_id=?",new Object[]{id});db.execSQL("DELETE FROM wordlists WHERE id=?",new Object[]{id});}
    public void rename(long id,String name){db.execSQL("UPDATE wordlists SET name=? WHERE id=?",new Object[]{name.trim(),id});}

    /** Frequency rank of a word in the first ranked list that contains it (for badges in search results). */
    public JSONObject rank(String word) throws Exception {
        JSONArray r=Store.rows(db,"SELECT i.pos,l.name FROM wordlist_items i JOIN wordlists l ON l.id=i.list_id WHERE i.norm=? AND l.ranked=1 ORDER BY l.added LIMIT 1",HtmlText.normalize(word));
        return r.length()==0?null:r.getJSONObject(0);
    }
}
