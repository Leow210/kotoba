package android.content.res;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Desktop stand-in: assets are read from the Android app's assets folder. */
public class AssetManager {
    final File root;
    public AssetManager(File root){this.root=root;}
    public AssetFileDescriptor openFd(String name) throws IOException {
        File f=new File(root,name);
        if(!f.isFile())throw new java.io.FileNotFoundException(name);
        return new AssetFileDescriptor(f.length());
    }
    public InputStream open(String name) throws IOException {
        File f=new File(root,name).getCanonicalFile();
        if(!f.getPath().startsWith(root.getCanonicalPath()))throw new IOException("Outside assets: "+name);
        return new FileInputStream(f);
    }
}
