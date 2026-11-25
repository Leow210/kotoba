package app.kotoba.ios;

/**
 * SQLite on iOS: the system's libsqlite3, called through J2ObjC native methods. Handles are the C pointers as longs;
 * the android.database.sqlite classes in this folder are written on top of it.
 */
public final class Sqlite3 {
    private Sqlite3() {}

    public static final int ROW = 100, DONE = 101;

    /*-[
    #include <sqlite3.h>
    ]-*/

    /** Opens (creating if needed) a database file; 0 when it can't be opened. */
    public static native long open(String path) /*-[
      sqlite3 *db = NULL;
      if (sqlite3_open_v2([path UTF8String], &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, NULL) != SQLITE_OK) {
        if (db) sqlite3_close(db);
        return 0;
      }
      return (jlong)db;
    ]-*/;

    public static native void close(long db) /*-[ sqlite3_close_v2((sqlite3 *)db); ]-*/;

    /** The last error message of a connection. */
    public static native String error(long db) /*-[ return [NSString stringWithUTF8String:sqlite3_errmsg((sqlite3 *)db)]; ]-*/;

    /** Runs SQL that returns nothing (several statements allowed); null when it worked, else the message. */
    public static native String exec(long db, String sql) /*-[
      char *err = NULL;
      if (sqlite3_exec((sqlite3 *)db, [sql UTF8String], NULL, NULL, &err) != SQLITE_OK) {
        NSString *m = err ? [NSString stringWithUTF8String:err] : @"SQLite error";
        sqlite3_free(err);
        return m;
      }
      return nil;
    ]-*/;

    /** Prepares one statement; 0 when the SQL is wrong (see {@link #error}). */
    public static native long prepare(long db, String sql) /*-[
      sqlite3_stmt *st = NULL;
      if (sqlite3_prepare_v2((sqlite3 *)db, [sql UTF8String], -1, &st, NULL) != SQLITE_OK) return 0;
      return (jlong)st;
    ]-*/;

    public static native void finalizeStatement(long st) /*-[ sqlite3_finalize((sqlite3_stmt *)st); ]-*/;
    public static native void reset(long st) /*-[ sqlite3_reset((sqlite3_stmt *)st); ]-*/;
    public static native void clearBindings(long st) /*-[ sqlite3_clear_bindings((sqlite3_stmt *)st); ]-*/;
    /** {@link #ROW}, {@link #DONE}, or an error code. */
    public static native int step(long st) /*-[ return sqlite3_step((sqlite3_stmt *)st); ]-*/;
    public static native long lastInsertRowId(long db) /*-[ return sqlite3_last_insert_rowid((sqlite3 *)db); ]-*/;
    public static native int changes(long db) /*-[ return sqlite3_changes((sqlite3 *)db); ]-*/;

    public static native void bindNull(long st, int i) /*-[ sqlite3_bind_null((sqlite3_stmt *)st, i); ]-*/;
    public static native void bindLong(long st, int i, long v) /*-[ sqlite3_bind_int64((sqlite3_stmt *)st, i, v); ]-*/;
    public static native void bindDouble(long st, int i, double v) /*-[ sqlite3_bind_double((sqlite3_stmt *)st, i, v); ]-*/;
    public static native void bindText(long st, int i, String v) /*-[ sqlite3_bind_text((sqlite3_stmt *)st, i, [v UTF8String], -1, SQLITE_TRANSIENT); ]-*/;
    public static native void bindBlob(long st, int i, byte[] v) /*-[
      sqlite3_bind_blob((sqlite3_stmt *)st, i, v->buffer_, (int)v->size_, SQLITE_TRANSIENT);
    ]-*/;

    public static native int columnCount(long st) /*-[ return sqlite3_column_count((sqlite3_stmt *)st); ]-*/;
    public static native String columnName(long st, int i) /*-[ return [NSString stringWithUTF8String:sqlite3_column_name((sqlite3_stmt *)st, i)]; ]-*/;
    /** 1 integer, 2 float, 3 text, 4 blob, 5 null (SQLite's codes). */
    public static native int columnType(long st, int i) /*-[ return sqlite3_column_type((sqlite3_stmt *)st, i); ]-*/;
    public static native long columnLong(long st, int i) /*-[ return sqlite3_column_int64((sqlite3_stmt *)st, i); ]-*/;
    public static native double columnDouble(long st, int i) /*-[ return sqlite3_column_double((sqlite3_stmt *)st, i); ]-*/;
    public static native String columnText(long st, int i) /*-[
      const unsigned char *t = sqlite3_column_text((sqlite3_stmt *)st, i);
      if (!t) return nil;
      int n = sqlite3_column_bytes((sqlite3_stmt *)st, i);
      return [[NSString alloc] initWithBytes:t length:n encoding:NSUTF8StringEncoding];
    ]-*/;
    public static native byte[] columnBlob(long st, int i) /*-[
      const void *b = sqlite3_column_blob((sqlite3_stmt *)st, i);
      int n = sqlite3_column_bytes((sqlite3_stmt *)st, i);
      if (!b && n == 0) return nil;
      return [IOSByteArray arrayWithBytes:(const jbyte *)b count:n];
    ]-*/;
}
