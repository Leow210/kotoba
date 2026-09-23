package android.database.sqlite;

public class SQLiteException extends RuntimeException {
    public SQLiteException(Throwable cause){super(cause.getMessage(),cause);}
    public SQLiteException(Throwable cause,String sql){super(cause.getMessage()+" — in: "+(sql.length()>200?sql.substring(0,200)+"…":sql),cause);}
    /** Constraint violations (duplicate folder names…) become SQLiteConstraintException, as on Android. */
    static SQLiteException of(java.sql.SQLException e,String sql){
        return String.valueOf(e.getMessage()).contains("CONSTRAINT")?new SQLiteConstraintException(e,sql):new SQLiteException(e,sql);
    }
}
