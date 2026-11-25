package android.database.sqlite;

public class SQLiteException extends RuntimeException {
    public SQLiteException(String message){super(message);}
    public SQLiteException(String message,String sql){super(message+" — in: "+(sql.length()>200?sql.substring(0,200)+"…":sql));}
    /** Constraint violations (duplicate folder names…) become SQLiteConstraintException, as on Android. */
    static SQLiteException of(String message,String sql){
        return String.valueOf(message).contains("CONSTRAINT")?new SQLiteConstraintException(message,sql):new SQLiteException(message,sql);
    }
}
