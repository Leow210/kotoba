package app.kotoba.reader;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.Calendar;

/**
 * Personal data: folders, saved words/subwords/meanings (items), flashcard scheduling and review history.
 * Kept in its own database so dictionaries can be removed or re-imported without touching it.
 */
public class Store {
    final SQLiteDatabase db;
    static final String[] ITEM_TEXT={"kind","dict_name","page","anchor","headword","reading","back","back_html","note","context","audio"};

    public Store(Context context){
        db=SQLiteDatabase.openOrCreateDatabase(new File(context.getFilesDir(),"personal-v2.sqlite3"),null);
        db.enableWriteAheadLogging();
        db.execSQL("PRAGMA foreign_keys=ON");
        db.execSQL("CREATE TABLE IF NOT EXISTS folders(id INTEGER PRIMARY KEY,name TEXT NOT NULL UNIQUE,position INTEGER NOT NULL DEFAULT 0,created INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("INSERT OR IGNORE INTO folders(id,name,position,created) VALUES(1,'Inbox',0,strftime('%s','now'))");
        db.execSQL("CREATE TABLE IF NOT EXISTS items(id INTEGER PRIMARY KEY,folder_id INTEGER NOT NULL REFERENCES folders(id),kind TEXT NOT NULL DEFAULT 'entry',dict INTEGER NOT NULL DEFAULT 0,dict_name TEXT NOT NULL DEFAULT '',page TEXT NOT NULL DEFAULT '',anchor TEXT NOT NULL DEFAULT '',headword TEXT NOT NULL,reading TEXT NOT NULL DEFAULT '',back TEXT NOT NULL DEFAULT '',back_html TEXT NOT NULL DEFAULT '',note TEXT NOT NULL DEFAULT '',context TEXT NOT NULL DEFAULT '',created INTEGER NOT NULL,updated INTEGER NOT NULL,review INTEGER NOT NULL DEFAULT 0,state INTEGER NOT NULL DEFAULT 0,step INTEGER NOT NULL DEFAULT 0,stability REAL NOT NULL DEFAULT 0,difficulty REAL NOT NULL DEFAULT 0,due INTEGER NOT NULL DEFAULT 0,last_review INTEGER NOT NULL DEFAULT 0,reps INTEGER NOT NULL DEFAULT 0,lapses INTEGER NOT NULL DEFAULT 0,introduced INTEGER NOT NULL DEFAULT 0)");
        try{db.execSQL("ALTER TABLE items ADD COLUMN audio TEXT NOT NULL DEFAULT ''");}catch(Exception ignored){}
        // Folders are decks: everything saved is studied unless its folder is switched off.
        try{db.execSQL("ALTER TABLE folders ADD COLUMN study INTEGER NOT NULL DEFAULT 1");}catch(Exception ignored){}
        if(setting("unified_decks","").isEmpty()){db.execSQL("UPDATE items SET review=1");setSetting("unified_decks","1");}
        db.execSQL("CREATE INDEX IF NOT EXISTS items_folder ON items(folder_id,updated)");
        db.execSQL("CREATE INDEX IF NOT EXISTS items_due ON items(review,state,due)");
        db.execSQL("CREATE TABLE IF NOT EXISTS reviews(id INTEGER PRIMARY KEY,item_id INTEGER NOT NULL REFERENCES items(id) ON DELETE CASCADE,rating INTEGER NOT NULL,reviewed INTEGER NOT NULL,before TEXT NOT NULL,after_due INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS history(norm TEXT PRIMARY KEY,query TEXT NOT NULL,used INTEGER NOT NULL)");
    }

    static long now(){return System.currentTimeMillis()/1000;}

    static long dayStart(){
        Calendar c=Calendar.getInstance();
        // Study days roll over at 4am like Anki, so late-night reviews count toward the same day.
        c.add(Calendar.HOUR_OF_DAY,-4);
        c.set(Calendar.HOUR_OF_DAY,4);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);
        return c.getTimeInMillis()/1000;
    }

    public static JSONArray rows(SQLiteDatabase db,String sql,String...args)throws Exception{
        JSONArray result=new JSONArray();
        try(Cursor cursor=db.rawQuery(sql,args)){
            while(cursor.moveToNext()){
                JSONObject row=new JSONObject();
                for(int i=0;i<cursor.getColumnCount();i++){
                    Object value;
                    switch(cursor.getType(i)){
                        case Cursor.FIELD_TYPE_INTEGER:value=cursor.getLong(i);break;
                        case Cursor.FIELD_TYPE_FLOAT:value=cursor.getDouble(i);break;
                        case Cursor.FIELD_TYPE_NULL:value=JSONObject.NULL;break;
                        default:value=cursor.getString(i);
                    }
                    row.put(cursor.getColumnName(i),value);
                }
                result.put(row);
            }
        }
        return result;
    }

    // ---------- settings ----------

    public String setting(String key,String fallback){
        try(Cursor c=db.rawQuery("SELECT value FROM settings WHERE key=?",new String[]{key})){return c.moveToFirst()?c.getString(0):fallback;}
    }
    public JSONObject settings()throws Exception{
        JSONObject out=new JSONObject();
        for(Object o:iter(rows(db,"SELECT key,value FROM settings")))out.put(((JSONObject)o).getString("key"),((JSONObject)o).getString("value"));
        return out;
    }
    public void setSetting(String key,String value){
        if(key.length()>64||value.length()>10000)throw new IllegalArgumentException("Setting too long");
        ContentValues v=new ContentValues();v.put("key",key);v.put("value",value);
        db.insertWithOnConflict("settings",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    static Iterable<Object> iter(JSONArray a){
        java.util.ArrayList<Object> list=new java.util.ArrayList<>();for(int i=0;i<a.length();i++)list.add(a.opt(i));return list;
    }

    // ---------- search history ----------

    public void remember(String query){
        String q=query.trim();if(q.isEmpty()||q.length()>200)return;
        ContentValues v=new ContentValues();v.put("norm",HtmlText.normalize(q));v.put("query",q);v.put("used",now());
        db.insertWithOnConflict("history",null,v,SQLiteDatabase.CONFLICT_REPLACE);
        db.execSQL("DELETE FROM history WHERE norm NOT IN (SELECT norm FROM history ORDER BY used DESC LIMIT 200)");
    }
    public JSONArray history()throws Exception{return rows(db,"SELECT query FROM history ORDER BY used DESC LIMIT 40");}
    public void clearHistory(){db.execSQL("DELETE FROM history");}

    // ---------- folders ----------

    public JSONArray folders()throws Exception{
        long t=now();
        return rows(db,"SELECT f.id,f.name,f.position,f.study,count(i.id) count,coalesce(sum(i.review),0) cards,coalesce(sum(CASE WHEN i.review=1 AND i.state>0 AND i.due<=? THEN 1 ELSE 0 END),0) due,coalesce(sum(CASE WHEN i.review=1 AND i.state=0 THEN 1 ELSE 0 END),0) fresh FROM folders f LEFT JOIN items i ON i.folder_id=f.id GROUP BY f.id ORDER BY f.id!=1,f.position,f.name",Long.toString(t));
    }

    public JSONObject saveFolder(JSONObject data)throws Exception{
        String name=data.optString("name","").trim();
        if(name.isEmpty()||name.length()>100)throw new Exception("Folder names need 1–100 characters.");
        ContentValues v=new ContentValues();v.put("name",name);
        try{
            if(data.optLong("id",0)>0){db.update("folders",v,"id=?",new String[]{Long.toString(data.getLong("id"))});return new JSONObject().put("id",data.getLong("id"));}
            v.put("created",now());
            v.put("position",rows(db,"SELECT coalesce(max(position),0)+1 p FROM folders").getJSONObject(0).getLong("p"));
            return new JSONObject().put("id",db.insertOrThrow("folders",null,v));
        }catch(android.database.sqlite.SQLiteConstraintException e){throw new Exception("A folder called “"+name+"” already exists.");}
    }

    public void deleteFolder(long id,boolean deleteItems)throws Exception{
        if(id==1)throw new Exception("Inbox can’t be deleted.");
        db.beginTransaction();
        try{
            if(deleteItems)db.execSQL("DELETE FROM items WHERE folder_id=?",new Object[]{id});
            else db.execSQL("UPDATE items SET folder_id=1 WHERE folder_id=?",new Object[]{id});
            db.execSQL("DELETE FROM folders WHERE id=?",new Object[]{id});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }

    public void setStudy(long id,boolean study){db.execSQL("UPDATE folders SET study=? WHERE id=?",new Object[]{study?1:0,id});}

    public void reorderFolders(JSONArray ids){
        db.beginTransaction();
        try{for(int i=0;i<ids.length();i++)db.execSQL("UPDATE folders SET position=? WHERE id=?",new Object[]{i+1,ids.optLong(i)});db.setTransactionSuccessful();}
        finally{db.endTransaction();}
    }

    // ---------- items ----------

    public JSONArray items(JSONObject q)throws Exception{
        StringBuilder where=new StringBuilder("1=1");
        java.util.ArrayList<String> args=new java.util.ArrayList<>();
        if(q.optLong("folder",0)>0){where.append(" AND folder_id=?");args.add(Long.toString(q.getLong("folder")));}
        String text=q.optString("q","").trim();
        if(!text.isEmpty()){where.append(" AND (headword LIKE ? OR reading LIKE ? OR back LIKE ? OR note LIKE ?)");String like="%"+text.replace("%","")+"%";for(int i=0;i<4;i++)args.add(like);}
        String filter=q.optString("filter","");
        if(filter.equals("new"))where.append(" AND review=1 AND state=0");
        if(filter.equals("suspended"))where.append(" AND review=0");
        if(filter.equals("due")){where.append(" AND review=1 AND state>0 AND due<=?");args.add(Long.toString(now()));}
        String order=q.optString("sort","updated");
        String orderBy=order.equals("headword")?"headword COLLATE NOCASE":order.equals("due")?"review DESC,state=0,due":"updated DESC";
        return rows(db,"SELECT i.*,f.name folder FROM items i JOIN folders f ON f.id=i.folder_id WHERE "+where+" ORDER BY "+orderBy+" LIMIT 2000",args.toArray(new String[0]));
    }

    public JSONObject item(long id)throws Exception{
        JSONArray r=rows(db,"SELECT i.*,f.name folder FROM items i JOIN folders f ON f.id=i.folder_id WHERE i.id=?",Long.toString(id));
        if(r.length()==0)throw new Exception("This saved word no longer exists.");
        return r.getJSONObject(0);
    }

    /** Items already saved for an entry, so the entry view can show its bookmark state. */
    public JSONArray savedFor(long dict,String page)throws Exception{
        return rows(db,"SELECT i.id,i.folder_id,i.kind,i.anchor,i.headword,i.review,f.name folder FROM items i JOIN folders f ON f.id=i.folder_id WHERE i.dict=? AND i.page=?",Long.toString(dict),page);
    }

    public JSONObject saveItem(JSONObject data)throws Exception{
        String headword=data.optString("headword","").trim();
        if(headword.isEmpty()||headword.length()>500)throw new Exception("Add the word (up to 500 characters).");
        if(data.optString("back","").length()>200000||data.optString("back_html","").length()>1000000)throw new Exception("This definition is too long to save.");
        ContentValues v=new ContentValues();
        long folder=data.optLong("folder_id",1);
        if(rows(db,"SELECT id FROM folders WHERE id=?",Long.toString(folder)).length()==0)folder=1;
        v.put("folder_id",folder);
        v.put("headword",headword);
        for(String k:ITEM_TEXT)if(data.has(k)&&!k.equals("headword"))v.put(k,data.optString(k,""));
        if(data.has("dict"))v.put("dict",data.optLong("dict",0));
        v.put("updated",now());
        boolean review=data.optBoolean("review",true);
        long id=data.optLong("id",0);
        // A word deleted elsewhere while its sheet was open is simply saved again.
        if(id>0&&rows(db,"SELECT 1 FROM items WHERE id=?",Long.toString(id)).length()==0)id=0;
        if(id>0){
            JSONObject old=item(id);
            v.put("review",review?1:0);
            if(review&&old.getInt("review")==0&&old.getLong("reps")==0){v.put("state",0);v.put("due",now());}
            db.update("items",v,"id=?",new String[]{Long.toString(id)});
        }else{
            v.put("created",now());v.put("review",review?1:0);v.put("state",0);v.put("due",now());
            id=db.insertOrThrow("items",null,v);
        }
        return new JSONObject().put("id",id);
    }

    public void deleteItems(JSONArray ids){
        db.beginTransaction();
        try{for(int i=0;i<ids.length();i++)db.delete("items","id=?",new String[]{Long.toString(ids.optLong(i))});db.setTransactionSuccessful();}
        finally{db.endTransaction();}
    }

    public void moveItems(JSONArray ids,long folder,boolean copy)throws Exception{
        if(rows(db,"SELECT id FROM folders WHERE id=?",Long.toString(folder)).length()==0)throw new Exception("Folder not found");
        db.beginTransaction();
        try{
            for(int i=0;i<ids.length();i++){
                String id=Long.toString(ids.optLong(i));
                if(copy)db.execSQL("INSERT INTO items(folder_id,kind,dict,dict_name,page,anchor,headword,reading,back,back_html,note,context,audio,created,updated,review,state,due) SELECT ?,kind,dict,dict_name,page,anchor,headword,reading,back,back_html,note,context,audio,?,?,review,0,? FROM items WHERE id=?",new Object[]{folder,now(),now(),now(),id});
                else db.execSQL("UPDATE items SET folder_id=?,updated=? WHERE id=?",new Object[]{folder,now(),id});
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }

    public void setReview(JSONArray ids,boolean review){
        db.beginTransaction();
        try{for(int i=0;i<ids.length();i++)db.execSQL("UPDATE items SET review=?,updated=? WHERE id=?",new Object[]{review?1:0,now(),ids.optLong(i)});db.setTransactionSuccessful();}
        finally{db.endTransaction();}
    }

    public void resetProgress(JSONArray ids){
        db.beginTransaction();
        try{for(int i=0;i<ids.length();i++)db.execSQL("UPDATE items SET state=0,step=0,stability=0,difficulty=0,due=?,last_review=0,reps=0,lapses=0,introduced=0 WHERE id=?",new Object[]{now(),ids.optLong(i)});db.setTransactionSuccessful();}
        finally{db.endTransaction();}
    }

    // ---------- review ----------

    Fsrs scheduler(){
        double retention=0.9;
        try{retention=Double.parseDouble(setting("retention","0.9"));}catch(NumberFormatException ignored){}
        return new Fsrs(retention,36500);
    }
    int newPerDay(){try{return Math.max(0,Math.min(9999,Integer.parseInt(setting("new_per_day","20"))));}catch(NumberFormatException e){return 20;}}

    static Fsrs.Card card(JSONObject row){
        Fsrs.Card c=new Fsrs.Card();
        c.state=row.optInt("state");c.step=row.optInt("step");c.reps=row.optInt("reps");c.lapses=row.optInt("lapses");
        c.stability=row.optDouble("stability",0);c.difficulty=row.optDouble("difficulty",0);c.due=row.optLong("due");c.lastReview=row.optLong("last_review");
        return c;
    }

    public JSONObject queue(long folder)throws Exception{
        long t=now(),day=dayStart();
        String scope=folder>0?" AND folder_id="+folder:" AND folder_id IN (SELECT id FROM folders WHERE study=1)";
        long introduced=rows(db,"SELECT count(*) n FROM items WHERE introduced>=?",Long.toString(day)).getJSONObject(0).getLong("n");
        long newLeft=Math.max(0,newPerDay()-introduced);
        JSONObject counts=rows(db,"SELECT coalesce(sum(CASE WHEN state IN(1,3) AND due<=? THEN 1 ELSE 0 END),0) learning,coalesce(sum(CASE WHEN state=2 AND due<=? THEN 1 ELSE 0 END),0) review,coalesce(sum(CASE WHEN state=0 THEN 1 ELSE 0 END),0) fresh FROM items WHERE review=1"+scope,Long.toString(t),Long.toString(t)).getJSONObject(0);
        long freshAvailable=Math.min(newLeft,counts.getLong("fresh"));
        counts.put("new",freshAvailable).put("new_total",counts.getLong("fresh")).put("new_limit",newPerDay());
        JSONArray next=rows(db,"SELECT i.*,f.name folder FROM items i JOIN folders f ON f.id=i.folder_id WHERE review=1 AND state>0 AND due<=?"+scope+" ORDER BY state=2,due LIMIT 1",Long.toString(t));
        if(next.length()==0&&freshAvailable>0)next=rows(db,"SELECT i.*,f.name folder FROM items i JOIN folders f ON f.id=i.folder_id WHERE review=1 AND state=0"+scope+" ORDER BY created,id LIMIT 1");
        // Learn ahead: a learning card due within 20 minutes is shown rather than leaving the session empty.
        if(next.length()==0)next=rows(db,"SELECT i.*,f.name folder FROM items i JOIN folders f ON f.id=i.folder_id WHERE review=1 AND state IN(1,3) AND due<=?"+scope+" ORDER BY due LIMIT 1",Long.toString(t+1200));
        JSONObject out=new JSONObject().put("counts",counts);
        JSONArray later=rows(db,"SELECT min(due) due FROM items WHERE review=1 AND state>0 AND due>?"+scope,Long.toString(t));
        out.put("next_due",later.getJSONObject(0).opt("due"));
        out.put("reviewed_today",rows(db,"SELECT count(*) n FROM reviews WHERE reviewed>=?",Long.toString(day)).getJSONObject(0).getLong("n"));
        if(next.length()>0){
            JSONObject item=next.getJSONObject(0);
            Fsrs.Card c=card(item);Fsrs s=scheduler();
            JSONArray previews=new JSONArray();
            for(int g=1;g<=4;g++)previews.put(s.answer(c,g,t).interval);
            out.put("item",item).put("intervals",previews);
        }
        return out;
    }

    public JSONObject answer(long id,int rating)throws Exception{
        db.beginTransaction();
        try{
            JSONObject row=item(id);
            if(row.getInt("review")==0)throw new Exception("This word is not in review.");
            long t=now();
            Fsrs.Card before=card(row);
            Fsrs.Card after=scheduler().answer(before,rating,t);
            JSONObject snapshot=new JSONObject().put("state",before.state).put("step",before.step).put("stability",before.stability).put("difficulty",before.difficulty).put("due",before.due).put("last_review",before.lastReview).put("reps",before.reps).put("lapses",before.lapses).put("introduced",row.getLong("introduced"));
            db.execSQL("INSERT INTO reviews(item_id,rating,reviewed,before,after_due) VALUES(?,?,?,?,?)",new Object[]{id,rating,t,snapshot.toString(),after.due});
            db.execSQL("UPDATE items SET state=?,step=?,stability=?,difficulty=?,due=?,last_review=?,reps=?,lapses=?,introduced=CASE WHEN introduced=0 THEN ? ELSE introduced END WHERE id=?",
                new Object[]{after.state,after.step,after.stability,after.difficulty,after.due,after.lastReview,after.reps,after.lapses,t,id});
            db.setTransactionSuccessful();
            return new JSONObject().put("due",after.due).put("interval",after.interval);
        }finally{db.endTransaction();}
    }

    public JSONObject undo()throws Exception{
        db.beginTransaction();
        try{
            JSONArray last=rows(db,"SELECT * FROM reviews ORDER BY id DESC LIMIT 1");
            if(last.length()==0)throw new Exception("Nothing to undo.");
            JSONObject r=last.getJSONObject(0);JSONObject b=new JSONObject(r.getString("before"));
            db.execSQL("UPDATE items SET state=?,step=?,stability=?,difficulty=?,due=?,last_review=?,reps=?,lapses=?,introduced=? WHERE id=?",
                new Object[]{b.getInt("state"),b.getInt("step"),b.getDouble("stability"),b.getDouble("difficulty"),b.getLong("due"),b.getLong("last_review"),b.getInt("reps"),b.getInt("lapses"),b.optLong("introduced",0),r.getLong("item_id")});
            db.delete("reviews","id=?",new String[]{Long.toString(r.getLong("id"))});
            db.setTransactionSuccessful();
            return new JSONObject().put("id",r.getLong("item_id"));
        }finally{db.endTransaction();}
    }

    public JSONObject stats()throws Exception{
        long t=now(),day=dayStart();
        JSONObject s=rows(db,"SELECT count(*) items,coalesce(sum(review),0) cards,coalesce(sum(CASE WHEN review=1 AND state=2 AND stability>=21 THEN 1 ELSE 0 END),0) mature FROM items").getJSONObject(0);
        s.put("reviewed_today",rows(db,"SELECT count(*) n FROM reviews WHERE reviewed>=?",Long.toString(day)).getJSONObject(0).getLong("n"));
        s.put("due",rows(db,"SELECT count(*) n FROM items WHERE review=1 AND state>0 AND due<=? AND folder_id IN (SELECT id FROM folders WHERE study=1)",Long.toString(t)).getJSONObject(0).getLong("n"));
        JSONArray forecast=new JSONArray();
        for(int d=0;d<7;d++)forecast.put(rows(db,"SELECT count(*) n FROM items WHERE review=1 AND state>0 AND due>=? AND due<?",Long.toString(d==0?0:day+d*86400L),Long.toString(day+(d+1)*86400L)).getJSONObject(0).getLong("n"));
        s.put("forecast",forecast);
        // Day index relative to today (0 = today, -1 = yesterday); offset keeps SQLite division flooring.
        JSONArray daysReviewed=rows(db,"SELECT DISTINCT (reviewed-?+86400000)/86400-1000 d FROM reviews WHERE reviewed>=? ORDER BY d DESC",Long.toString(day),Long.toString(day-400*86400L));
        java.util.HashSet<Long> set=new java.util.HashSet<>();
        for(int i=0;i<daysReviewed.length();i++)set.add(daysReviewed.getJSONObject(i).getLong("d"));
        long cursor=set.contains(0L)?0:-1;int days=0;
        while(set.contains(cursor)){days++;cursor--;}
        s.put("streak",days);
        return s;
    }

    // ---------- export / backup ----------

    static String tsvField(String s){return s.replace('\t',' ').replace("\r","").replace("\n","<br>");}

    public String exportTsv(long folder,boolean html)throws Exception{
        StringBuilder out=new StringBuilder("#separator:tab\n#html:true\n#columns:Front\tReading\tBack\tNote\tContext\tDictionary\tFolder\tTags\n#tags column:8\n");
        JSONArray items=rows(db,"SELECT i.*,f.name folder FROM items i JOIN folders f ON f.id=i.folder_id"+(folder>0?" WHERE folder_id="+folder:"")+" ORDER BY i.id");
        for(int i=0;i<items.length();i++){
            JSONObject it=items.getJSONObject(i);
            String back=html&&!it.getString("back_html").isEmpty()?it.getString("back_html"):escape(it.getString("back"));
            String tag="kotoba::"+it.getString("folder").replaceAll("\\s+","_");
            out.append(tsvField(escape(it.getString("headword")))).append('\t').append(tsvField(escape(it.getString("reading")))).append('\t')
               .append(tsvField(back)).append('\t').append(tsvField(escape(it.getString("note")))).append('\t').append(tsvField(escape(it.getString("context")))).append('\t')
               .append(tsvField(escape(it.getString("dict_name")))).append('\t').append(tsvField(escape(it.getString("folder")))).append('\t').append(tsvField(tag)).append('\n');
        }
        return out.toString();
    }

    static String csvField(String s){return "\""+s.replace("\"","\"\"")+"\"";}
    public String exportCsv(long folder)throws Exception{
        StringBuilder out=new StringBuilder("﻿headword,reading,definition,note,context,dictionary,folder,review,due\n");
        JSONArray items=rows(db,"SELECT i.*,f.name folder FROM items i JOIN folders f ON f.id=i.folder_id"+(folder>0?" WHERE folder_id="+folder:"")+" ORDER BY i.id");
        for(int i=0;i<items.length();i++){
            JSONObject it=items.getJSONObject(i);
            out.append(csvField(it.getString("headword"))).append(',').append(csvField(it.getString("reading"))).append(',').append(csvField(it.getString("back"))).append(',')
               .append(csvField(it.getString("note"))).append(',').append(csvField(it.getString("context"))).append(',').append(csvField(it.getString("dict_name"))).append(',')
               .append(csvField(it.getString("folder"))).append(',').append(it.getInt("review")).append(',').append(it.getLong("due")>0?new java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.ROOT).format(new java.util.Date(it.getLong("due")*1000)):"").append('\n');
        }
        return out.toString();
    }

    static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}

    public JSONObject backup()throws Exception{
        JSONObject data=new JSONObject().put("format","kotoba-personal-v2").put("exported",now());
        for(String table:new String[]{"folders","items","reviews","settings"})data.put(table,rows(db,"SELECT * FROM "+table));
        return data;
    }

    /** Merges a backup. Items that already exist (same folder, word, page and definition) are skipped. */
    public JSONObject restore(JSONObject data)throws Exception{
        if(!data.optString("format").equals("kotoba-personal-v2"))throw new Exception("This file isn’t a Kotoba backup.");
        int added=0,skipped=0;
        db.beginTransaction();
        try{
            java.util.HashMap<Long,Long> folderMap=new java.util.HashMap<>(),itemMap=new java.util.HashMap<>();
            JSONArray folders=data.getJSONArray("folders");
            for(int i=0;i<folders.length();i++){
                JSONObject f=folders.getJSONObject(i);
                db.execSQL("INSERT OR IGNORE INTO folders(name,position,created) VALUES(?,?,?)",new Object[]{f.getString("name"),f.optInt("position"),f.optLong("created")});
                folderMap.put(f.getLong("id"),rows(db,"SELECT id FROM folders WHERE name=?",f.getString("name")).getJSONObject(0).getLong("id"));
            }
            JSONArray items=data.getJSONArray("items");
            for(int i=0;i<items.length();i++){
                JSONObject it=items.getJSONObject(i);
                Long folder=folderMap.get(it.optLong("folder_id",1));if(folder==null)folder=1L;
                JSONArray existing=rows(db,"SELECT id FROM items WHERE folder_id=? AND headword=? AND page=? AND back=?",Long.toString(folder),it.optString("headword"),it.optString("page"),it.optString("back"));
                if(existing.length()>0){itemMap.put(it.getLong("id"),existing.getJSONObject(0).getLong("id"));skipped++;continue;}
                ContentValues v=new ContentValues();
                v.put("folder_id",folder);v.put("headword",it.getString("headword"));
                for(String k:ITEM_TEXT)v.put(k,it.optString(k,""));
                for(String k:new String[]{"dict","created","updated","review","state","step","due","last_review","reps","lapses","introduced"})v.put(k,it.optLong(k,0));
                v.put("stability",it.optDouble("stability",0));v.put("difficulty",it.optDouble("difficulty",0));
                itemMap.put(it.getLong("id"),db.insertOrThrow("items",null,v));added++;
            }
            JSONArray reviews=data.optJSONArray("reviews");
            if(reviews!=null)for(int i=0;i<reviews.length();i++){
                JSONObject r=reviews.getJSONObject(i);Long item=itemMap.get(r.getLong("item_id"));
                if(item==null)continue;
                if(rows(db,"SELECT 1 FROM reviews WHERE item_id=? AND reviewed=?",Long.toString(item),Long.toString(r.getLong("reviewed"))).length()>0)continue;
                db.execSQL("INSERT INTO reviews(item_id,rating,reviewed,before,after_due) VALUES(?,?,?,?,?)",new Object[]{item,r.getInt("rating"),r.getLong("reviewed"),r.optString("before","{}"),r.optLong("after_due")});
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return new JSONObject().put("added",added).put("skipped",skipped);
    }
}
