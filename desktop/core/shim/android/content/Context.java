package android.content;

import android.content.res.AssetManager;
import java.io.File;

/** Desktop stand-in for Context: where databases, files and the interface's assets live. */
public class Context {
    final File data;final AssetManager assets;
    public Context(File data,File assets){this.data=data;this.assets=new AssetManager(assets);data.mkdirs();}
    public File getDatabasePath(String name){File d=new File(data,"databases");d.mkdirs();return new File(d,name);}
    public File getFilesDir(){File d=new File(data,"files");d.mkdirs();return d;}
    public AssetManager getAssets(){return assets;}
}
