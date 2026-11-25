package android.database.sqlite;

public class SQLiteConstraintException extends SQLiteException {
    public SQLiteConstraintException(String message,String sql){super(message,sql);}
}
