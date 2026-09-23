package android.database.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Desktop stand-in for a compiled statement; bindings persist between executions, as on Android. */
public class SQLiteStatement implements AutoCloseable {
    final Connection connection;final PreparedStatement p;
    SQLiteStatement(Connection connection,PreparedStatement p){this.connection=connection;this.p=p;}
    public void bindString(int i,String v){try{p.setString(i,v);}catch(SQLException e){throw new SQLiteException(e);}}
    public void bindLong(int i,long v){try{p.setLong(i,v);}catch(SQLException e){throw new SQLiteException(e);}}
    public void bindDouble(int i,double v){try{p.setDouble(i,v);}catch(SQLException e){throw new SQLiteException(e);}}
    public void bindBlob(int i,byte[] v){try{p.setBytes(i,v);}catch(SQLException e){throw new SQLiteException(e);}}
    public void bindNull(int i){try{p.setNull(i,java.sql.Types.NULL);}catch(SQLException e){throw new SQLiteException(e);}}
    public void clearBindings(){try{p.clearParameters();}catch(SQLException e){throw new SQLiteException(e);}}
    public long executeInsert(){
        try{
            p.executeUpdate();
            try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("SELECT last_insert_rowid()")){r.next();return r.getLong(1);}
        }catch(SQLException e){throw new SQLiteException(e);}
    }
    public int executeUpdateDelete(){try{return p.executeUpdate();}catch(SQLException e){throw new SQLiteException(e);}}
    public void execute(){try{p.execute();}catch(SQLException e){throw new SQLiteException(e);}}
    public long simpleQueryForLong(){try(ResultSet r=p.executeQuery()){r.next();return r.getLong(1);}catch(SQLException e){throw new SQLiteException(e);}}
    @Override public void close(){try{p.close();}catch(SQLException ignored){}}
}
