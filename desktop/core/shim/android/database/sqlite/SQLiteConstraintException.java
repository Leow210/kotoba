package android.database.sqlite;

public class SQLiteConstraintException extends SQLiteException {
    public SQLiteConstraintException(Throwable cause,String sql){super(cause,sql);}
}
