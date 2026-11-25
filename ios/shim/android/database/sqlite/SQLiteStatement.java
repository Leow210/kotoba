package android.database.sqlite;

import app.kotoba.ios.Sqlite3;

/** A compiled statement; bindings persist between executions, as on Android. */
public class SQLiteStatement implements AutoCloseable {
    final long db,st;
    SQLiteStatement(long db,long st){this.db=db;this.st=st;}

    private void check(int rc){if(rc!=Sqlite3.ROW&&rc!=Sqlite3.DONE)throw SQLiteException.of(Sqlite3.error(db),"statement");}
    public void bindString(int i,String v){if(v==null)Sqlite3.bindNull(st,i);else Sqlite3.bindText(st,i,v);}
    public void bindLong(int i,long v){Sqlite3.bindLong(st,i,v);}
    public void bindDouble(int i,double v){Sqlite3.bindDouble(st,i,v);}
    public void bindBlob(int i,byte[] v){if(v==null)Sqlite3.bindNull(st,i);else Sqlite3.bindBlob(st,i,v);}
    public void bindNull(int i){Sqlite3.bindNull(st,i);}
    public void clearBindings(){Sqlite3.clearBindings(st);}
    public long executeInsert(){run();return Sqlite3.lastInsertRowId(db);}
    public int executeUpdateDelete(){run();return Sqlite3.changes(db);}
    public void execute(){run();}
    private void run(){try{check(Sqlite3.step(st));}finally{Sqlite3.reset(st);}}
    public long simpleQueryForLong(){
        try{int rc=Sqlite3.step(st);check(rc);return rc==Sqlite3.ROW?Sqlite3.columnLong(st,0):0;}finally{Sqlite3.reset(st);}
    }
    @Override public void close(){Sqlite3.finalizeStatement(st);}
}
