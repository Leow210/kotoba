package android.content.res;

/** Desktop stand-in: only the length is used (to tell whether a copied model is current). */
public class AssetFileDescriptor implements AutoCloseable {
    final long length;
    AssetFileDescriptor(long length){this.length=length;}
    public long getLength(){return length;}
    @Override public void close(){}
}
