package android.database.sqlite;

import android.content.ContentValues;
import android.database.Cursor;
import app.kotoba.ios.Sqlite3;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * iOS version of Android's SQLiteDatabase, over the system SQLite (see {@link Sqlite3}): just what Kotoba's shared
 * classes call. Like Android's connection pool, each thread has its own connection, so a long import transaction on one
 * thread doesn't block searches on another (WAL mode). Transactions nest the way Android's do.
 */
public class SQLiteDatabase {
    public interface CursorFactory {}
    public static final int CONFLICT_NONE=0,CONFLICT_ROLLBACK=1,CONFLICT_ABORT=2,CONFLICT_FAIL=3,CONFLICT_IGNORE=4,CONFLICT_REPLACE=5;

    final String path;
    final ThreadLocal<Conn> local=new ThreadLocal<>();
    final List<Conn> all=new ArrayList<>();

    static final class Conn {
        final long db;final java.util.ArrayDeque<Boolean> levels=new java.util.ArrayDeque<>();boolean ok=true;
        Conn(long db){this.db=db;}
    }

    SQLiteDatabase(String path){this.path=path;}

    public static SQLiteDatabase openOrCreateDatabase(String path,CursorFactory factory){
        SQLiteDatabase db=new SQLiteDatabase(path);
        db.execSQL("PRAGMA journal_mode=WAL");
        return db;
    }
    public static SQLiteDatabase openOrCreateDatabase(java.io.File file,CursorFactory factory){return openOrCreateDatabase(file.getPath(),factory);}

    Conn conn(){
        Conn c=local.get();
        if(c==null){
            long h=Sqlite3.open(path);
            if(h==0)throw new SQLiteException("Can't open the database "+path);
            Sqlite3.exec(h,"PRAGMA busy_timeout=60000");
            Sqlite3.exec(h,"PRAGMA foreign_keys=ON");
            c=new Conn(h);
            local.set(c);
            synchronized(all){all.add(c);}
        }
        return c;
    }
    long handle(){return conn().db;}

    public void enableWriteAheadLogging(){execSQL("PRAGMA journal_mode=WAL");}

    long prepare(String sql){
        long db=handle();
        long st=Sqlite3.prepare(db,sql);
        if(st==0)throw SQLiteException.of(Sqlite3.error(db),sql);
        return st;
    }

    private static void bind(long st,Object[] args){
        if(args==null)return;
        for(int i=0;i<args.length;i++){
            Object a=args[i];int n=i+1;
            if(a==null)Sqlite3.bindNull(st,n);
            else if(a instanceof byte[])Sqlite3.bindBlob(st,n,(byte[])a);
            else if(a instanceof Long||a instanceof Integer||a instanceof Short)Sqlite3.bindLong(st,n,((Number)a).longValue());
            else if(a instanceof Double||a instanceof Float)Sqlite3.bindDouble(st,n,((Number)a).doubleValue());
            else if(a instanceof Boolean)Sqlite3.bindLong(st,n,((Boolean)a)?1:0);
            else Sqlite3.bindText(st,n,a.toString());
        }
    }

    /** Runs a prepared statement to completion; its row count. */
    private int run(String sql,Object[] args){
        long db=handle();long st=prepare(sql);
        try{
            bind(st,args);
            int rc=Sqlite3.step(st);
            if(rc!=Sqlite3.ROW&&rc!=Sqlite3.DONE)throw SQLiteException.of(Sqlite3.error(db),sql);
            return Sqlite3.changes(db);
        }finally{Sqlite3.finalizeStatement(st);}
    }

    public void execSQL(String sql){
        long db=handle();
        String err=Sqlite3.exec(db,sql);
        if(err!=null)throw new SQLiteException(err,sql);
    }
    public void execSQL(String sql,Object[] args){run(sql,args);}

    public Cursor rawQuery(String sql,String[] args){
        long st=prepare(sql);
        bind(st,args);
        return new Cursor(handle(),st);
    }

    public SQLiteStatement compileStatement(String sql){return new SQLiteStatement(handle(),prepare(sql));}

    public long insertOrThrow(String table,String nullColumnHack,ContentValues values){return insertAs("INSERT",table,values);}
    long insertAs(String verb,String table,ContentValues values){
        StringBuilder cols=new StringBuilder(),marks=new StringBuilder();
        List<Object> args=new ArrayList<>();
        for(Map.Entry<String,Object> e:values.valueSet()){
            if(cols.length()>0){cols.append(',');marks.append(',');}
            cols.append(e.getKey());marks.append('?');args.add(e.getValue());
        }
        String sql=verb+" INTO "+table+"("+cols+") VALUES("+marks+")";
        run(sql,args.toArray());
        return Sqlite3.lastInsertRowId(handle());
    }
    public long insertWithOnConflict(String table,String nullColumnHack,ContentValues values,int conflict){
        String[] verbs={"INSERT","INSERT OR ROLLBACK","INSERT OR ABORT","INSERT OR FAIL","INSERT OR IGNORE","INSERT OR REPLACE"};
        return insertAs(verbs[conflict],table,values);
    }
    public long insert(String table,String nullColumnHack,ContentValues values){
        try{return insertOrThrow(table,nullColumnHack,values);}catch(SQLiteException e){return -1;}
    }

    public int update(String table,ContentValues values,String where,String[] whereArgs){
        StringBuilder set=new StringBuilder();List<Object> args=new ArrayList<>();
        for(Map.Entry<String,Object> e:values.valueSet()){if(set.length()>0)set.append(',');set.append(e.getKey()).append("=?");args.add(e.getValue());}
        if(whereArgs!=null)for(String a:whereArgs)args.add(a);
        return run("UPDATE "+table+" SET "+set+(where==null?"":" WHERE "+where),args.toArray());
    }

    public int delete(String table,String where,String[] whereArgs){
        return run("DELETE FROM "+table+(where==null?"":" WHERE "+where),whereArgs);
    }

    public void beginTransaction(){
        Conn c=conn();
        if(c.levels.isEmpty()){execSQL("BEGIN IMMEDIATE");c.ok=true;}
        c.levels.push(false);
    }
    /** Marks the innermost transaction level successful; a level ended without this rolls the whole transaction back. */
    public void setTransactionSuccessful(){Conn c=conn();if(!c.levels.isEmpty()){c.levels.pop();c.levels.push(true);}}
    public void endTransaction(){
        Conn c=conn();
        if(c.levels.isEmpty())return;
        if(!c.levels.pop())c.ok=false;
        if(c.levels.isEmpty())execSQL(c.ok?"COMMIT":"ROLLBACK");
    }
}
