package android.content;

import android.database.Cursor;
import android.net.Uri;
import java.io.FileInputStream;
import java.io.InputStream;

/** Desktop stand-in: content:// has no meaning on the Mac, so only file paths resolve. */
public class ContentResolver {
    public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){return null;}
    public InputStream openInputStream(Uri uri) throws java.io.IOException {return new FileInputStream(uri.getPath());}
}
