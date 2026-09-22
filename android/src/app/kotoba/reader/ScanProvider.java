package app.kotoba.reader;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/**
 * Lets the camera app write a photo straight into files/scans/ (content://app.kotoba.reader.scans/<name>).
 * Not exported: access comes only from the one-off grant on the capture intent.
 */
public class ScanProvider extends ContentProvider {
    static final String AUTHORITY="app.kotoba.reader.scans";

    static Uri uri(File f){return Uri.parse("content://"+AUTHORITY+"/"+f.getName());}

    File file(Uri uri) throws FileNotFoundException {
        String name=uri.getLastPathSegment();
        if(name==null||!name.matches("scan-\\d+\\.jpg"))throw new FileNotFoundException(String.valueOf(uri));
        return new File(getContext().getExternalFilesDir(null),"scans/"+name);
    }

    @Override public boolean onCreate(){return true;}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        File f=file(uri);f.getParentFile().mkdirs();
        return ParcelFileDescriptor.open(f,ParcelFileDescriptor.parseMode(mode));
    }
    @Override public String getType(Uri uri){return "image/jpeg";}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){
        try{
            File f=file(uri);
            MatrixCursor c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});
            c.addRow(new Object[]{f.getName(),f.length()});
            return c;
        }catch(FileNotFoundException e){return null;}
    }
    @Override public Uri insert(Uri uri,ContentValues values){return null;}
    @Override public int delete(Uri uri,String selection,String[] args){return 0;}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){return 0;}
}
