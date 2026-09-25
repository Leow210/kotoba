package app.kotoba.reader;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

/**
 * Vocabulary sync through a shared folder (Syncthing, a synced drive…): each device writes its own
 * kotoba-<device>.json there and merges the others'. Nothing is sent anywhere by Kotoba itself.
 *
 * Folders and cards carry a uid and a "changed" time (ms) kept up to date by triggers, so every edit counts
 * whichever code made it; deletions leave tombstones. Merging keeps the newer version of each card and
 * folder, adds reviews not seen yet, and applies deletions newer than the local copy. Dictionaries are
 * matched by title, since their ids differ between devices.
 */
public class Sync {
    public interface Dicts { long idFor(String title); String titleFor(long id); }
    /** The shared folder, however the platform reaches it (a path on the Mac, a document tree on Android). */
    public interface Folder {
        java.util.List<String> names() throws Exception;
        long modified(String name) throws Exception;
        byte[] read(String name) throws Exception;
        void write(String name,byte[] data) throws Exception;
    }

    final SQLiteDatabase db;
    final Store store;

    public Sync(Store store){
        this.store=store;this.db=store.db;
        String now="CAST((julianday('now')-2440587.5)*86400000 AS INTEGER)";
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_deleted(uid TEXT PRIMARY KEY,kind TEXT NOT NULL,at INTEGER NOT NULL)");
        for(String t:new String[]{"items","folders"}){
            try{db.execSQL("ALTER TABLE "+t+" ADD COLUMN uid TEXT NOT NULL DEFAULT ''");}catch(Exception ignored){}
            try{db.execSQL("ALTER TABLE "+t+" ADD COLUMN changed INTEGER NOT NULL DEFAULT 0");}catch(Exception ignored){}
            db.execSQL("UPDATE "+t+" SET uid=lower(hex(randomblob(16))) WHERE uid=''");
            db.execSQL("UPDATE "+t+" SET changed="+now+" WHERE changed=0");
            // Local edits bump "changed"; merged rows set it themselves, so the trigger leaves them alone.
            db.execSQL("CREATE TRIGGER IF NOT EXISTS "+t+"_sync_insert AFTER INSERT ON "+t+" WHEN NEW.uid='' OR NEW.changed=0 BEGIN "
                +"UPDATE "+t+" SET uid=CASE WHEN NEW.uid='' THEN lower(hex(randomblob(16))) ELSE NEW.uid END,changed=CASE WHEN NEW.changed=0 THEN "+now+" ELSE NEW.changed END WHERE rowid=NEW.rowid; END");
            db.execSQL("CREATE TRIGGER IF NOT EXISTS "+t+"_sync_update AFTER UPDATE ON "+t+" WHEN NEW.changed=OLD.changed BEGIN "
                +"UPDATE "+t+" SET changed="+now+" WHERE rowid=NEW.rowid; END");
            db.execSQL("CREATE TRIGGER IF NOT EXISTS "+t+"_sync_delete AFTER DELETE ON "+t+" BEGIN "
                +"INSERT OR REPLACE INTO sync_deleted(uid,kind,at) VALUES(OLD.uid,'"+t+"',"+now+"); END");
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS items_uid ON items(uid)");
        if(store.setting("device_id","").isEmpty())store.setSetting("device_id",java.util.UUID.randomUUID().toString().substring(0,8));
    }

    public String deviceId(){return store.setting("device_id","");}
    public String fileName(){return "kotoba-"+deviceId()+".json";}

    /** Latest local change, to skip writing an unchanged file. */
    public long lastChange() throws Exception {
        return Store.rows(db,"SELECT max(coalesce((SELECT max(changed) FROM items),0),coalesce((SELECT max(changed) FROM folders),0),coalesce((SELECT max(at) FROM sync_deleted),0),coalesce((SELECT max(reviewed)*1000 FROM reviews),0),coalesce((SELECT max(changed) FROM known),0)) m").getJSONObject(0).getLong("m");
    }

    /** This device's sync file. */
    public JSONObject export(String deviceName,Dicts dicts) throws Exception {
        JSONObject out=new JSONObject().put("format","kotoba-sync-1").put("device",deviceId()).put("name",deviceName).put("exported",System.currentTimeMillis());
        out.put("folders",Store.rows(db,"SELECT uid,name,position,study,new_per_day,created,changed FROM folders"));
        JSONArray items=Store.rows(db,"SELECT i.*,f.uid folder_uid FROM items i JOIN folders f ON f.id=i.folder_id");
        HashMap<Long,String> titles=new HashMap<>();
        for(int i=0;i<items.length();i++){
            JSONObject it=items.getJSONObject(i);
            long d=it.optLong("dict",0);
            if(d>0){String t=titles.computeIfAbsent(d,dicts::titleFor);if(t!=null)it.put("dict_title",t);}
            it.remove("id");it.remove("folder_id");
        }
        out.put("items",items);
        out.put("reviews",Store.rows(db,"SELECT i.uid item,r.rating,r.reviewed,r.before,r.after_due FROM reviews r JOIN items i ON i.id=r.item_id"));
        out.put("deleted",Store.rows(db,"SELECT uid,kind,at FROM sync_deleted"));
        out.put("known",Store.rows(db,"SELECT lang,norm,word,known,changed FROM known"));
        JSONObject settings=new JSONObject();
        for(String k:new String[]{"new_per_day","retention"}){String v=store.setting(k,"");if(!v.isEmpty())settings.put(k,v);}
        out.put("settings",settings);
        return out;
    }

    /** Merges another device's file. Returns what changed here. */
    public JSONObject merge(JSONObject in,Dicts dicts) throws Exception {
        if(!"kotoba-sync-1".equals(in.optString("format")))throw new Exception("Not a Kotoba sync file");
        if(deviceId().equals(in.optString("device")))return new JSONObject().put("added",0).put("updated",0).put("deleted",0);
        int added=0,updated=0,deleted=0,reviews=0;
        db.beginTransaction();
        try{
            // Deleted cards newer than the local copy go first. Deleted folders wait until the cards are merged: a card
            // moved out of a folder just before the folder was deleted (Jp → JP, then delete Jp) must arrive in its new
            // folder, not be swept into the Inbox with the folder.
            JSONArray dels=in.optJSONArray("deleted");
            if(dels!=null)for(int i=0;i<dels.length();i++){
                JSONObject d=dels.getJSONObject(i);
                if(d.getString("kind").equals("items"))deleted+=applyDeletion(d);
            }
            java.util.Set<String> gone=new java.util.HashSet<>();
            try(Cursor c=db.rawQuery("SELECT uid FROM sync_deleted",null)){while(c.moveToNext())gone.add(c.getString(0));}

            // Folders: by uid, else by name (made on both devices separately), then the newer name/order.
            Map<String,Long> folderIds=new HashMap<>();
            JSONArray folders=in.optJSONArray("folders");
            if(folders!=null)for(int i=0;i<folders.length();i++){
                JSONObject f=folders.getJSONObject(i);
                String uid=f.getString("uid");
                if(gone.contains(uid))continue;
                JSONArray local=Store.rows(db,"SELECT id,uid,changed FROM folders WHERE uid=?",uid);
                if(local.length()==0)local=Store.rows(db,"SELECT id,uid,changed FROM folders WHERE name=?",f.getString("name"));
                if(local.length()==0){
                    ContentValues v=new ContentValues();
                    v.put("uid",uid);v.put("name",uniqueFolderName(f.getString("name")));v.put("position",f.optLong("position"));v.put("study",f.optLong("study",1));v.put("new_per_day",f.optLong("new_per_day",-1));
                    v.put("created",f.optLong("created"));v.put("changed",f.getLong("changed"));
                    folderIds.put(uid,db.insertOrThrow("folders",null,v));
                    continue;
                }
                JSONObject l=local.getJSONObject(0);long id=l.getLong("id");
                folderIds.put(uid,id);
                // Same folder made on two devices: both converge on the smaller uid.
                if(!l.getString("uid").equals(uid)&&uid.compareTo(l.getString("uid"))<0)
                    db.execSQL("UPDATE folders SET uid=?,changed=changed+1 WHERE id=?",new Object[]{uid,id});
                // The Inbox keeps its name, but its deck settings follow the newer copy.
                if(f.getLong("changed")>l.getLong("changed")&&id==1)
                    db.execSQL("UPDATE folders SET study=?,new_per_day=?,changed=? WHERE id=1",new Object[]{f.optLong("study",1),f.optLong("new_per_day",-1),f.getLong("changed")});
                if(f.getLong("changed")>l.getLong("changed")&&id!=1){
                    String name=f.getString("name");
                    if(Store.rows(db,"SELECT 1 FROM folders WHERE name=? AND id!=?",name,Long.toString(id)).length()>0)name=uniqueFolderName(name);
                    db.execSQL("UPDATE folders SET name=?,position=?,study=?,new_per_day=?,changed=? WHERE id=?",new Object[]{name,f.optLong("position"),f.optLong("study",1),f.optLong("new_per_day",-1),f.getLong("changed"),id});
                }
            }

            // Cards: new ones are added, newer versions replace older ones (text, folder and review state together).
            String[] longs={"created","updated","review","state","step","due","last_review","reps","lapses","introduced"};
            JSONArray items=in.optJSONArray("items");
            Map<String,Long> itemIds=new HashMap<>();
            if(items!=null)for(int i=0;i<items.length();i++){
                JSONObject it=items.getJSONObject(i);
                String uid=it.getString("uid");
                if(gone.contains(uid))continue;
                Long folder=folderIds.get(it.optString("folder_uid"));
                if(folder==null){JSONArray f=Store.rows(db,"SELECT id FROM folders WHERE uid=?",it.optString("folder_uid"));folder=f.length()>0?f.getJSONObject(0).getLong("id"):1L;}
                ContentValues v=new ContentValues();
                v.put("folder_id",folder);
                for(String k:Store.ITEM_TEXT)v.put(k,it.optString(k,""));
                for(String k:longs)v.put(k,it.optLong(k,0));
                v.put("stability",it.optDouble("stability",0));v.put("difficulty",it.optDouble("difficulty",0));
                long dict=0;String title=it.optString("dict_title","");
                if(!title.isEmpty())dict=dicts.idFor(title);
                v.put("dict",dict);
                v.put("changed",it.getLong("changed"));
                JSONArray local=Store.rows(db,"SELECT id,changed FROM items WHERE uid=?",uid);
                if(local.length()==0){v.put("uid",uid);itemIds.put(uid,db.insertOrThrow("items",null,v));added++;}
                else{
                    long id=local.getJSONObject(0).getLong("id");itemIds.put(uid,id);
                    if(it.getLong("changed")>local.getJSONObject(0).getLong("changed")){db.update("items",v,"id=?",new String[]{Long.toString(id)});updated++;}
                }
            }

            if(dels!=null)for(int i=0;i<dels.length();i++){
                JSONObject d=dels.getJSONObject(i);
                if(d.getString("kind").equals("folders"))deleted+=applyDeletion(d);
            }

            // Known words: the newer marking wins.
            JSONArray known=in.optJSONArray("known");
            if(known!=null)for(int i=0;i<known.length();i++){
                JSONObject k=known.getJSONObject(i);
                JSONArray local=Store.rows(db,"SELECT changed FROM known WHERE lang=? AND norm=?",k.getString("lang"),k.getString("norm"));
                if(local.length()>0&&local.getJSONObject(0).getLong("changed")>=k.getLong("changed"))continue;
                db.execSQL("INSERT OR REPLACE INTO known(lang,norm,word,known,changed) VALUES(?,?,?,?,?)",new Object[]{k.getString("lang"),k.getString("norm"),k.optString("word",k.getString("norm")),k.optInt("known",1),k.getLong("changed")});
                updated++;
            }

            // Review history: every answer from every device, once.
            JSONArray revs=in.optJSONArray("reviews");
            if(revs!=null)for(int i=0;i<revs.length();i++){
                JSONObject r=revs.getJSONObject(i);
                Long item=itemIds.get(r.getString("item"));
                if(item==null){JSONArray l=Store.rows(db,"SELECT id FROM items WHERE uid=?",r.getString("item"));if(l.length()==0)continue;item=l.getJSONObject(0).getLong("id");}
                if(Store.rows(db,"SELECT 1 FROM reviews WHERE item_id=? AND reviewed=?",Long.toString(item),Long.toString(r.getLong("reviewed"))).length()>0)continue;
                db.execSQL("INSERT INTO reviews(item_id,rating,reviewed,before,after_due) VALUES(?,?,?,?,?)",new Object[]{item,r.getInt("rating"),r.getLong("reviewed"),r.optString("before","{}"),r.optLong("after_due")});
                reviews++;
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return new JSONObject().put("added",added).put("updated",updated).put("deleted",deleted).put("reviews",reviews);
    }

    /** Applies one of another device's deletions if it's newer than the local copy; returns 1 when something went. */
    int applyDeletion(JSONObject d) throws Exception {
        String uid=d.getString("uid"),kind=d.getString("kind");long at=d.getLong("at");
        if(!kind.equals("items")&&!kind.equals("folders"))return 0;
        int n=0;
        JSONArray local=Store.rows(db,"SELECT id,changed FROM "+kind+" WHERE uid=?",uid);
        if(local.length()>0&&local.getJSONObject(0).getLong("changed")<=at){
            long id=local.getJSONObject(0).getLong("id");
            if(kind.equals("folders")){
                if(id==1)return 0;// the Inbox stays
                // Cards still in it go to the Inbox. That isn't an edit of the card: its time moves on by 1 ms only, so a
                // real change made elsewhere (moving it to another folder) still wins.
                db.execSQL("UPDATE items SET folder_id=1,changed=changed+1 WHERE folder_id=?",new Object[]{id});
            }else db.execSQL("DELETE FROM reviews WHERE item_id=?",new Object[]{id});
            db.execSQL("DELETE FROM "+kind+" WHERE id=?",new Object[]{id});n=1;
        }
        db.execSQL("INSERT OR IGNORE INTO sync_deleted(uid,kind,at) VALUES(?,?,?)",new Object[]{uid,kind,at});
        return n;
    }

    /**
     * One pass: merge other devices' files that changed since last time, then write this device's file if anything
     * changed here (or it's missing). Returns counts, and whether anything here changed (so the page can refresh).
     */
    public synchronized JSONObject run(Folder folder,String deviceName,Dicts dicts) throws Exception {
        int added=0,updated=0,deleted=0,reviews=0,devices=0;
        java.util.List<String> names=folder.names();
        for(String name:names){
            if(!name.matches("kotoba-[0-9a-f]+\\.json")||name.equals(fileName()))continue;
            devices++;
            long m=folder.modified(name);
            if(m<=Long.parseLong(store.setting("sync_seen:"+name,"0")))continue;
            JSONObject r=merge(new JSONObject(new String(folder.read(name),java.nio.charset.StandardCharsets.UTF_8)),dicts);
            added+=r.optInt("added");updated+=r.optInt("updated");deleted+=r.optInt("deleted");reviews+=r.optInt("reviews");
            store.setSetting("sync_seen:"+name,Long.toString(m));
        }
        long change=lastChange();
        if(!names.contains(fileName())||change>Long.parseLong(store.setting("sync_written","0"))){
            folder.write(fileName(),export(deviceName,dicts).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            store.setSetting("sync_written",Long.toString(lastChange()));
        }
        store.setSetting("sync_last",Long.toString(System.currentTimeMillis()));
        return new JSONObject().put("added",added).put("updated",updated).put("deleted",deleted).put("reviews",reviews).put("devices",devices)
            .put("changed",added+updated+deleted+reviews>0);
    }

    String uniqueFolderName(String name) throws Exception {
        String n=name;
        for(int i=2;Store.rows(db,"SELECT 1 FROM folders WHERE name=?",n).length()>0;i++)n=name+" ("+i+")";
        return n;
    }
}
