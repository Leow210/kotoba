package android.database;

import android.database.sqlite.SQLiteException;
import app.kotoba.ios.Sqlite3;

/** A forward-only walk over a statement's rows (moveToFirst only before moving), as the core uses it. */
public class Cursor implements AutoCloseable {
    public static final int FIELD_TYPE_NULL=0,FIELD_TYPE_INTEGER=1,FIELD_TYPE_FLOAT=2,FIELD_TYPE_STRING=3,FIELD_TYPE_BLOB=4;
    final long db,st;
    int position=-1;boolean ended=false,closed=false;

    public Cursor(long db,long st){this.db=db;this.st=st;}

    public boolean moveToNext(){
        if(ended||closed)return false;
        int rc=Sqlite3.step(st);
        if(rc==Sqlite3.ROW){position++;return true;}
        ended=true;
        if(rc!=Sqlite3.DONE)throw new SQLiteException(Sqlite3.error(db));
        return false;
    }
    public boolean moveToFirst(){
        if(position==0)return true;
        if(position>0)throw new IllegalStateException("moveToFirst after moving");
        return moveToNext();
    }
    public int getColumnCount(){return Sqlite3.columnCount(st);}
    public String getColumnName(int i){return Sqlite3.columnName(st,i);}
    public int getType(int i){
        switch(Sqlite3.columnType(st,i)){
            case 1:return FIELD_TYPE_INTEGER;case 2:return FIELD_TYPE_FLOAT;case 3:return FIELD_TYPE_STRING;case 4:return FIELD_TYPE_BLOB;default:return FIELD_TYPE_NULL;
        }
    }
    public boolean isNull(int i){return Sqlite3.columnType(st,i)==5;}
    public String getString(int i){return Sqlite3.columnText(st,i);}
    public long getLong(int i){return Sqlite3.columnLong(st,i);}
    public int getInt(int i){return (int)Sqlite3.columnLong(st,i);}
    public double getDouble(int i){return Sqlite3.columnDouble(st,i);}
    public byte[] getBlob(int i){return Sqlite3.columnBlob(st,i);}
    @Override public void close(){if(!closed){closed=true;Sqlite3.finalizeStatement(st);}}
}
