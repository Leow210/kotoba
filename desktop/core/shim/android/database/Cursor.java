package android.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

/** Desktop stand-in for Android's Cursor: a forward-only walk over a JDBC result (moveToFirst only before moving). */
public class Cursor implements AutoCloseable {
    public static final int FIELD_TYPE_NULL=0,FIELD_TYPE_INTEGER=1,FIELD_TYPE_FLOAT=2,FIELD_TYPE_STRING=3,FIELD_TYPE_BLOB=4;
    final PreparedStatement statement;final ResultSet rs;final ResultSetMetaData meta;
    int position=-1;boolean ended=false;

    public Cursor(PreparedStatement statement,ResultSet rs) throws SQLException {this.statement=statement;this.rs=rs;this.meta=rs.getMetaData();}

    RuntimeException wrap(SQLException e){return new android.database.sqlite.SQLiteException(e);}
    public boolean moveToNext(){
        if(ended)return false;
        try{if(rs.next()){position++;return true;}ended=true;return false;}catch(SQLException e){throw wrap(e);}
    }
    public boolean moveToFirst(){
        if(position==0)return true;
        if(position>0)throw new IllegalStateException("moveToFirst after moving");
        return moveToNext();
    }
    public int getColumnCount(){try{return meta.getColumnCount();}catch(SQLException e){throw wrap(e);}}
    public String getColumnName(int i){try{return meta.getColumnLabel(i+1);}catch(SQLException e){throw wrap(e);}}
    public int getType(int i){
        try{
            Object o=rs.getObject(i+1);
            if(o==null)return FIELD_TYPE_NULL;
            if(o instanceof Integer||o instanceof Long||o instanceof Short)return FIELD_TYPE_INTEGER;
            if(o instanceof Double||o instanceof Float)return FIELD_TYPE_FLOAT;
            if(o instanceof byte[])return FIELD_TYPE_BLOB;
            return FIELD_TYPE_STRING;
        }catch(SQLException e){throw wrap(e);}
    }
    public boolean isNull(int i){try{rs.getObject(i+1);return rs.wasNull();}catch(SQLException e){throw wrap(e);}}
    public String getString(int i){try{Object o=rs.getObject(i+1);return o==null?null:o instanceof byte[]?new String((byte[])o,java.nio.charset.StandardCharsets.UTF_8):o.toString();}catch(SQLException e){throw wrap(e);}}
    public long getLong(int i){try{return rs.getLong(i+1);}catch(SQLException e){throw wrap(e);}}
    public int getInt(int i){try{return rs.getInt(i+1);}catch(SQLException e){throw wrap(e);}}
    public double getDouble(int i){try{return rs.getDouble(i+1);}catch(SQLException e){throw wrap(e);}}
    public byte[] getBlob(int i){try{return rs.getBytes(i+1);}catch(SQLException e){throw wrap(e);}}
    @Override public void close(){try{rs.close();statement.close();}catch(SQLException ignored){}}
}
