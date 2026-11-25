package android.provider;

import android.net.Uri;

/** Desktop stand-in: Android's document trees don't exist on the Mac; the file-path code is used instead. */
public final class DocumentsContract {
    private DocumentsContract(){}
    public static final class Document {
        public static final String MIME_TYPE_DIR="vnd.android.document/directory";
        public static final String COLUMN_DOCUMENT_ID="document_id",COLUMN_DISPLAY_NAME="_display_name",COLUMN_MIME_TYPE="mime_type",COLUMN_SIZE="_size",COLUMN_LAST_MODIFIED="last_modified";
    }
    static UnsupportedOperationException no(){return new UnsupportedOperationException("Android document trees aren't available on the Mac");}
    public static String getTreeDocumentId(Uri tree){throw no();}
    public static Uri buildChildDocumentsUriUsingTree(Uri tree,String doc){throw no();}
    public static Uri buildDocumentUriUsingTree(Uri tree,String doc){throw no();}
}
