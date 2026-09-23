package android.database.sqlite;

import android.content.ContentValues;
import android.database.Cursor;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Desktop stand-in for Android's SQLiteDatabase, over sqlite-jdbc: just what Kotoba's shared classes call.
 * Like Android's connection pool, each thread has its own connection, so a long import transaction on one
 * thread doesn't block searches on another (WAL mode). Transactions nest the way Android's do.
 */
public class SQLiteDatabase {
    public interface CursorFactory {}
    public static final int CONFLICT_NONE=0,CONFLICT_ROLLBACK=1,CONFLICT_ABORT=2,CONFLICT_FAIL=3,CONFLICT_IGNORE=4,CONFLICT_REPLACE=5;

    final String url;
    final ThreadLocal<Conn> local=new ThreadLocal<>();
    final List<Conn> all=new ArrayList<>();

    static final class Conn {
        final Connection c;final java.util.ArrayDeque<Boolean> levels=new java.util.ArrayDeque<>();boolean ok=true;
        Conn(Connection c){this.c=c;}
    }

    SQLiteDatabase(String path){this.url="jdbc:sqlite:"+path;}

    public static SQLiteDatabase openOrCreateDatabase(String path,CursorFactory factory){
        SQLiteDatabase db=new SQLiteDatabase(path);
        db.execSQL("PRAGMA journal_mode=WAL");
        return db;
    }
    public static SQLiteDatabase openOrCreateDatabase(java.io.File file,CursorFactory factory){return openOrCreateDatabase(file.getPath(),factory);}

    Conn conn(){
        Conn c=local.get();
        if(c==null){
            try{
                Connection j=DriverManager.getConnection(url);
                try(Statement s=j.createStatement()){s.execute("PRAGMA busy_timeout=60000");s.execute("PRAGMA foreign_keys=ON");}
                c=new Conn(j);
            }catch(SQLException e){throw new SQLiteException(e);}
            local.set(c);
            synchronized(all){all.add(c);}
        }
        return c;
    }
    Connection jdbc(){return conn().c;}

    public void enableWriteAheadLogging(){execSQL("PRAGMA journal_mode=WAL");}

    public void execSQL(String sql){
        try(Statement s=jdbc().createStatement()){s.execute(sql);}catch(SQLException e){throw new SQLiteException(e,sql);}
    }
    public void execSQL(String sql,Object[] args){
        try(PreparedStatement p=jdbc().prepareStatement(sql)){bind(p,args);p.execute();}catch(SQLException e){throw new SQLiteException(e,sql);}
    }

    public Cursor rawQuery(String sql,String[] args){
        try{
            PreparedStatement p=jdbc().prepareStatement(sql);
            bind(p,args);
            return new Cursor(p,p.executeQuery());
        }catch(SQLException e){throw new SQLiteException(e,sql);}
    }

    public SQLiteStatement compileStatement(String sql){
        try{return new SQLiteStatement(jdbc(),jdbc().prepareStatement(sql));}catch(SQLException e){throw new SQLiteException(e,sql);}
    }

    public long insertOrThrow(String table,String nullColumnHack,ContentValues values){return insertAs("INSERT",table,values);}
    long insertAs(String verb,String table,ContentValues values){
        StringBuilder cols=new StringBuilder(),marks=new StringBuilder();
        List<Object> args=new ArrayList<>();
        for(Map.Entry<String,Object> e:values.valueSet()){
            if(cols.length()>0){cols.append(',');marks.append(',');}
            cols.append(e.getKey());marks.append('?');args.add(e.getValue());
        }
        String sql=verb+" INTO "+table+"("+cols+") VALUES("+marks+")";
        try(PreparedStatement p=jdbc().prepareStatement(sql)){
            bind(p,args.toArray());p.executeUpdate();
            try(Statement s=jdbc().createStatement();java.sql.ResultSet r=s.executeQuery("SELECT last_insert_rowid()")){r.next();return r.getLong(1);}
        }catch(SQLException e){throw SQLiteException.of(e,sql);}
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
        String sql="UPDATE "+table+" SET "+set+(where==null?"":" WHERE "+where);
        try(PreparedStatement p=jdbc().prepareStatement(sql)){bind(p,args.toArray());return p.executeUpdate();}catch(SQLException e){throw SQLiteException.of(e,sql);}
    }

    public int delete(String table,String where,String[] whereArgs){
        String sql="DELETE FROM "+table+(where==null?"":" WHERE "+where);
        try(PreparedStatement p=jdbc().prepareStatement(sql)){bind(p,whereArgs);return p.executeUpdate();}catch(SQLException e){throw new SQLiteException(e,sql);}
    }

    public void beginTransaction(){
        Conn c=conn();
        try{
            if(c.levels.isEmpty()){try(Statement s=c.c.createStatement()){s.execute("BEGIN IMMEDIATE");}c.ok=true;}
            c.levels.push(false);
        }catch(SQLException e){throw new SQLiteException(e);}
    }
    /** Marks the innermost transaction level successful; a level ended without this rolls the whole transaction back. */
    public void setTransactionSuccessful(){Conn c=conn();if(!c.levels.isEmpty()){c.levels.pop();c.levels.push(true);}}
    public void endTransaction(){
        Conn c=conn();
        if(c.levels.isEmpty())return;
        if(!c.levels.pop())c.ok=false;
        if(c.levels.isEmpty()){
            try(Statement s=c.c.createStatement()){s.execute(c.ok?"COMMIT":"ROLLBACK");}catch(SQLException e){throw new SQLiteException(e);}
        }
    }

    static void bind(PreparedStatement p,Object[] args) throws SQLException {
        if(args==null)return;
        for(int i=0;i<args.length;i++){
            Object a=args[i];
            if(a==null)p.setNull(i+1,java.sql.Types.NULL);
            else if(a instanceof byte[])p.setBytes(i+1,(byte[])a);
            else if(a instanceof Long||a instanceof Integer||a instanceof Short)p.setLong(i+1,((Number)a).longValue());
            else if(a instanceof Double||a instanceof Float)p.setDouble(i+1,((Number)a).doubleValue());
            else if(a instanceof Boolean)p.setLong(i+1,((Boolean)a)?1:0);
            else p.setString(i+1,a.toString());
        }
    }
}
