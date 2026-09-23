package app.kotoba.reader;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.webkit.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Offline host: the UI is local HTML; dictionaries, vocabulary and review all live on the phone. */
public class MainActivity extends Activity {
    static final String HOST="appassets.androidplatform.net";
    static final String ORIGIN="https://"+HOST;
    static final int PICK_FOLDER=51,EXPORT=52,RESTORE=53,PICK_BOOKS=54,PICK_COMIC_TREE=55,PICK_COMIC_FILES=56,PICK_WORDLIST=57,PICK_MIHON=58,PICK_COVER=59,SCAN_PICK=61,CAMERA_PERMISSION=62,SYNC_FOLDER=63;
    long coverSeries;

    KotobaWebView web;
    Library library;
    Store store;
    Books books;
    Comics comics; Ocr ocr;
    WordLists wordlists;
    Extras extras;
    Scans scans;
    PermissionRequest pendingCamera;
    final ExecutorService pool=Executors.newFixedThreadPool(3);
    Routes routes;
    Sync sync;
    final android.os.Handler syncTimer=new android.os.Handler(android.os.Looper.getMainLooper());
    byte[] pendingExport;

    /** WebView whose text-selection menu gains Look up and Save card. */
    public class KotobaWebView extends WebView {
        public KotobaWebView(Context c){super(c);}
        @Override public ActionMode startActionMode(ActionMode.Callback callback,int type){
            return super.startActionMode(wrap(callback),type);
        }
        @Override public ActionMode startActionMode(ActionMode.Callback callback){
            return super.startActionMode(wrap(callback));
        }
        ActionMode.Callback wrap(ActionMode.Callback original){
            return new ActionMode.Callback2(){
                @Override public boolean onCreateActionMode(ActionMode mode,Menu menu){
                    boolean result=original.onCreateActionMode(mode,menu);
                    menu.add(Menu.NONE,0x4b01,0,"Look up");
                    menu.add(Menu.NONE,0x4b02,1,"Save card");
                    return result;
                }
                @Override public boolean onPrepareActionMode(ActionMode mode,Menu menu){return original.onPrepareActionMode(mode,menu);}
                @Override public boolean onActionItemClicked(ActionMode mode,MenuItem item){
                    if(item.getItemId()==0x4b01||item.getItemId()==0x4b02){
                        String action=item.getItemId()==0x4b01?"lookup":"card";
                        evaluateJavascript("window.selectionAction&&window.selectionAction('"+action+"')",null);
                        mode.finish();
                        return true;
                    }
                    return original.onActionItemClicked(mode,item);
                }
                @Override public void onDestroyActionMode(ActionMode mode){original.onDestroyActionMode(mode);}
                @Override public void onGetContentRect(ActionMode mode,View view,android.graphics.Rect outRect){
                    if(original instanceof ActionMode.Callback2)((ActionMode.Callback2)original).onGetContentRect(mode,view,outRect);
                    else super.onGetContentRect(mode,view,outRect);
                }
            };
        }
    }

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(247,244,238));
        getWindow().setNavigationBarColor(Color.rgb(247,244,238));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getExternalFilesDir(null);// app-owned folder; also accepts dictionaries copied in over USB
        store=new Store(this);
        library=new Library(this,this::openChannel);
        books=new Books(this,store.db);
        comics=new Comics(this,store.db,this::openChannel);
        ocr=new Ocr(this,store.db);
        wordlists=new WordLists(store.db);
        extras=new Extras(library,new File(getExternalFilesDir(null),"extras"));
        sync=new Sync(store);
        routes=new Routes(library,store,wordlists,extras,new Routes.Host(){
            @Override public void event(String type,Object data){MainActivity.this.event(type,data);}
            @Override public void keepAwake(boolean on){runOnUiThread(()->{if(on)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);});}
        });
        routes.books=books;routes.comics=comics;routes.ocr=ocr;
        web=new KotobaWebView(this);
        setContentView(web);
        web.setBackgroundColor(Color.rgb(247,244,238));
        WebSettings s=web.getSettings();
        s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        s.setDefaultTextEncodingName("UTF-8");
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        WebView.setWebContentsDebuggingEnabled(true);
        web.addJavascriptInterface(new Bridge(),"Kotoba");
        web.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onConsoleMessage(ConsoleMessage m){android.util.Log.d("Kotoba",m.message()+" @"+m.sourceId()+":"+m.lineNumber());return true;}
            // The scanner's own camera view (getUserMedia): only our page, only video.
            @Override public void onPermissionRequest(PermissionRequest request){
                runOnUiThread(()->{
                    boolean video=java.util.Arrays.asList(request.getResources()).contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE);
                    if(!video||!HOST.equals(request.getOrigin().getHost())){request.deny();return;}
                    if(checkSelfPermission(android.Manifest.permission.CAMERA)==android.content.pm.PackageManager.PERMISSION_GRANTED){request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});return;}
                    if(pendingCamera!=null)pendingCamera.deny();
                    pendingCamera=request;
                    requestPermissions(new String[]{android.Manifest.permission.CAMERA},CAMERA_PERMISSION);
                });
            }
        });
        web.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return serve(request);}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                // Links inside entries are handled by the page; never navigate the app away.
                return true;
            }
            @Override public void onPageFinished(WebView view,String url){deliverIntent(getIntent());}
        });
        web.loadUrl(ORIGIN+"/");
    }

    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);deliverIntent(intent);}

    void deliverIntent(Intent intent){
        if(intent==null)return;
        // A shared screenshot or photo opens in the scanner.
        String type=intent.getType();
        if(Intent.ACTION_SEND.equals(intent.getAction())&&type!=null&&type.startsWith("image/")){
            Uri u=intent.getParcelableExtra(Intent.EXTRA_STREAM);
            intent.removeExtra(Intent.EXTRA_STREAM);intent.setAction(Intent.ACTION_MAIN);
            if(u!=null)pool.execute(()->{
                try{
                    String name=scans().importStream(getContentResolver().openInputStream(u),type);
                    web.post(()->web.evaluateJavascript("window.openScan&&window.openScan("+JSONObject.quote(name)+")",null));
                }catch(Exception e){event("toast","Couldn’t open that image: "+e.getMessage());}
            });
            return;
        }
        CharSequence text=intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        if(text==null&&Intent.ACTION_SEND.equals(intent.getAction()))text=intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if(text!=null){
            web.evaluateJavascript("window.externalLookup&&window.externalLookup("+JSONObject.quote(text.toString())+")",null);
            intent.removeExtra(Intent.EXTRA_PROCESS_TEXT);intent.removeExtra(Intent.EXTRA_TEXT);
        }
    }

    FileChannel openChannel(String uri) throws IOException {
        if(uri.startsWith("content://")){
            ParcelFileDescriptor pfd=getContentResolver().openFileDescriptor(Uri.parse(uri),"r");
            if(pfd==null)throw new FileNotFoundException(uri);
            // The stream owns the descriptor; the channel keeps the stream alive.
            return new ParcelFileDescriptor.AutoCloseInputStream(pfd).getChannel();
        }
        return new FileInputStream(uri.startsWith("file://")?Uri.parse(uri).getPath():uri).getChannel();
    }

    // ---------- JavaScript bridge ----------

    public class Bridge {
        @JavascriptInterface public void call(int id,String route,String body){
            pool.execute(()->{
                String reply;
                try{
                    Object result=route(route,body==null||body.isEmpty()?new JSONObject():new JSONObject(body));
                    reply=new JSONObject().put("data",result==null?JSONObject.NULL:result).toString();
                }catch(Throwable e){
                    android.util.Log.w("Kotoba","route "+route+" failed",e);
                    reply=error(e);
                }
                String script="window.__reply("+id+","+JSONObject.quote(reply)+")";
                web.post(()->web.evaluateJavascript(script,null));
            });
        }
        @JavascriptInterface public void copy(String text){runOnUiThread(()->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Kotoba",text));});}
        @JavascriptInterface public void share(String text){runOnUiThread(()->startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),"Share")));}
        /** The folder a sync tool (Syncthing…) shares with the Mac. */
        @JavascriptInterface public void pickSyncFolder(){runOnUiThread(()->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            // Opens where Syncthing keeps Kotoba's folder, so it's one tap.
            try{i.putExtra(DocumentsContract.EXTRA_INITIAL_URI,DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download/KotobaSync"));}catch(Exception ignored){}
            startActivityForResult(i,SYNC_FOLDER);
        });}
        @JavascriptInterface public void pickFolder(){pickFolderAt("Download/Monokakido_Ciyue");}
        /** Folder picker opening at a folder under shared storage (e.g. Download/Yomitan). */
        @JavascriptInterface public void pickFolderAt(String start){runOnUiThread(()->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            try{
                Uri initial=DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:"+start);
                i.putExtra(DocumentsContract.EXTRA_INITIAL_URI,initial);
            }catch(Exception ignored){}
            startActivityForResult(i,PICK_FOLDER);
        });}
        @JavascriptInterface public void exportFile(String name,String route,String body){pool.execute(()->{
            try{
                JSONObject data=body==null||body.isEmpty()?new JSONObject():new JSONObject(body);
                String text;String mime;
                String[] r=routes.export(route,data);
                if(r==null)throw new Exception("Unknown export");
                text=r[0];mime=r[1];
                byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
                runOnUiThread(()->{
                    pendingExport=bytes;
                    Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE,name);
                    startActivityForResult(i,EXPORT);
                });
            }catch(Exception e){event("toast",e.getMessage());}
        });}
        @JavascriptInterface public void pickBooks(){runOnUiThread(()->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/epub+zip","text/plain","application/zip","application/octet-stream"});
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
            startActivityForResult(i,PICK_BOOKS);
        });}
        @JavascriptInterface public void pickComicFolder(){runOnUiThread(()->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i,PICK_COMIC_TREE);
        });}
        @JavascriptInterface public void pickComicFiles(){runOnUiThread(()->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/zip","application/vnd.comicbook+zip","application/x-cbz","application/octet-stream"});
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i,PICK_COMIC_FILES);
        });}
        @JavascriptInterface public void pickWordList(){runOnUiThread(()->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"text/plain","text/csv","text/tab-separated-values","text/comma-separated-values","application/octet-stream"});
            startActivityForResult(i,PICK_WORDLIST);
        });}
        @JavascriptInterface public void pickComicCover(long series){runOnUiThread(()->{
            coverSeries=series;
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),PICK_COVER);
        });}
        @JavascriptInterface public void scanPick(){runOnUiThread(()->startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),SCAN_PICK));}
        @JavascriptInterface public void pickMihonBackup(){runOnUiThread(()->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            startActivityForResult(i,PICK_MIHON);
        });}
        /** Appendix PDFs: saved where a PDF viewer can open them (the WebView can't show PDFs inline). */
        @JavascriptInterface public void openResource(long dict,String name){pool.execute(()->{
            try{
                byte[] b=name.startsWith("files/")?extras.file(dict,name):library.resource(dict,name);
                if(b==null)throw new Exception("File not found in the dictionary");
                String file=name.substring(name.lastIndexOf('/')+1);
                runOnUiThread(()->{pendingExport=b;startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(Library.mime(file)).putExtra(Intent.EXTRA_TITLE,file),EXPORT);});
            }catch(Exception e){event("toast",e.getMessage());}
        });}
        @JavascriptInterface public void restoreBackup(){runOnUiThread(()->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            startActivityForResult(i,RESTORE);
        });}
        @JavascriptInterface public void keepAwake(boolean on){runOnUiThread(()->{if(on)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);});}
        /** Status and navigation bars follow the app/reader theme so dark pages have no white bars. */
        @JavascriptInterface public void setBars(String color,boolean lightBackground){runOnUiThread(()->{
            try{
                int c=Color.parseColor(color);
                getWindow().setStatusBarColor(c);getWindow().setNavigationBarColor(c);
                web.setBackgroundColor(c);
                int flags=getWindow().getDecorView().getSystemUiVisibility();
                int light=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                getWindow().getDecorView().setSystemUiVisibility(lightBackground?(flags|light):(flags&~light));
            }catch(Exception ignored){}
        });}
        /** Translation happens in Google Translate (app popup if installed, else the website); Kotoba itself stays offline. */
        @JavascriptInterface public void translate(String text){runOnUiThread(()->{
            String t=text.length()>4000?text.substring(0,4000):text;
            Intent i=new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").putExtra(Intent.EXTRA_PROCESS_TEXT,t).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY,true).setPackage("com.google.android.apps.translate");
            try{startActivity(i);return;}catch(Exception ignored){}
            try{startActivity(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,t).setPackage("com.google.android.apps.translate"));return;}catch(Exception ignored){}
            try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://translate.google.com/?sl=auto&tl=en&op=translate&text="+Uri.encode(t))));}
            catch(Exception e){event("toast","Install Google Translate to translate text");}
        });}
        @JavascriptInterface public void exitApp(){runOnUiThread(()->moveTaskToBack(true));}
    }

    String error(Throwable e){
        String message=e.getMessage()==null?e.toString():e.getMessage();
        try{return new JSONObject().put("error",message).toString();}catch(Exception ignored){return "{\"error\":\"Unexpected error\"}";}
    }

    void event(String type,Object data){
        try{
            String payload=new JSONObject().put("type",type).put("data",data==null?JSONObject.NULL:data).toString();
            web.post(()->web.evaluateJavascript("window.__event&&window.__event("+JSONObject.quote(payload)+")",null));
        }catch(Exception ignored){}
    }

    Object route(String route,JSONObject d) throws Exception {
        switch(route){
            case "sync.status":return syncStatus();
            case "sync.now":return syncNow();
            case "library.scan":return scan(Uri.parse(d.getString("tree")));
            case "library.relink":return relink(d.getString("tree"));
            case "library.scanLocal":{
                JSONArray found=new JSONArray();
                File root=getExternalFilesDir(null);
                if(root!=null)routes.scanFiles(root,"",0,found,library.dictionaries(),this::openChannel);
                return new JSONObject().put("items",found).put("path",root==null?"":root.getPath());
            }
            case "library.folders":{
                JSONArray out=new JSONArray();
                for(android.content.UriPermission p:getContentResolver().getPersistedUriPermissions())out.put(p.getUri().toString());
                return out;
            }
            case "comic.scanLocal":{
                java.io.File root=new java.io.File(getExternalFilesDir(null),"comics");root.mkdirs();
                return comics.scanFiles(root).put("path",root.getPath());
            }
            case "comic.importBackupFile":{
                // Backups copied into the app's own folder (the picker is the usual route).
                java.io.File f=new java.io.File(getExternalFilesDir(null),d.getString("name"));
                return comics.importBackup(MihonBackup.parse(read(new java.io.FileInputStream(f),200_000_000)));
            }
            case "wordlist.importFile":{
                java.io.File f=new java.io.File(getExternalFilesDir(null),d.getString("name"));
                return wordlists.importText(f.getName(),BookParser.decode(read(new java.io.FileInputStream(f),50_000_000))[0]);
            }
            case "scan.read":return scans().read(d.getString("name"),d.optString("lang","ja"),d.optJSONArray("crop"));
            case "scan.list":return scans().list();
            case "scan.delete":scans().delete(d.getString("name"));return null;
            case "scan.save":{
                // A photo from the scanner's own camera view, as base64 JPEG.
                byte[] b=android.util.Base64.decode(d.getString("data"),android.util.Base64.DEFAULT);
                return new JSONObject().put("name",scans().importStream(new ByteArrayInputStream(b),d.optString("mime","image/jpeg")));
            }
            default:return routes.route(route,d);
        }
    }

    // ---------- sync ----------

    /** The shared sync folder, reached through the document tree the user picked. */
    Sync.Folder syncFolder(Uri tree){
        String root=DocumentsContract.getTreeDocumentId(tree);
        return new Sync.Folder(){
            Map<String,String[]> list() throws Exception {// name → {document id, last modified}
                Map<String,String[]> out=new HashMap<>();
                Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,root);
                try(Cursor c=getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_LAST_MODIFIED},null,null,null)){
                    while(c!=null&&c.moveToNext())out.put(c.getString(1),new String[]{c.getString(0),Long.toString(c.isNull(2)?0:c.getLong(2))});
                }
                return out;
            }
            @Override public List<String> names() throws Exception {return new ArrayList<>(list().keySet());}
            @Override public long modified(String name) throws Exception {String[] d=list().get(name);return d==null?0:Long.parseLong(d[1]);}
            @Override public byte[] read(String name) throws Exception {
                String[] d=list().get(name);if(d==null)throw new FileNotFoundException(name);
                return MainActivity.read(getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree,d[0])),200_000_000);
            }
            @Override public void write(String name,byte[] data) throws Exception {
                String[] d=list().get(name);
                Uri doc=d!=null?DocumentsContract.buildDocumentUriUsingTree(tree,d[0])
                    :DocumentsContract.createDocument(getContentResolver(),DocumentsContract.buildDocumentUriUsingTree(tree,root),"application/json",name);
                if(doc==null)throw new IOException("Couldn’t create "+name+" in the sync folder");
                try(OutputStream o=getContentResolver().openOutputStream(doc,"wt")){o.write(data);}
            }
        };
    }

    synchronized JSONObject syncNow() throws Exception {
        String folder=store.setting("sync_folder","");
        if(folder.isEmpty())return new JSONObject().put("skipped",true);
        JSONObject r=sync.run(syncFolder(Uri.parse(folder)),android.os.Build.MODEL,library.syncDicts());
        if(r.optBoolean("changed"))event("synced",r);
        return r.put("status",syncStatus());
    }

    JSONObject syncStatus() throws Exception {
        String folder=store.setting("sync_folder","");
        JSONArray devices=new JSONArray();
        if(!folder.isEmpty())try{for(String n:syncFolder(Uri.parse(folder)).names())if(n.matches("kotoba-[0-9a-f]+\\.json")&&!n.equals(sync.fileName()))devices.put(new JSONObject().put("file",n));}catch(Exception ignored){}
        String shown=folder.isEmpty()?"":Uri.decode(folder).replaceFirst("^.*tree/primary:","").replaceFirst("^.*tree/","");
        return new JSONObject().put("folder",shown).put("device",sync.deviceId()).put("name",android.os.Build.MODEL)
            .put("last",Long.parseLong(store.setting("sync_last","0"))).put("devices",devices);
    }

    /** While Kotoba is open, sync every minute (it only reads or writes when something changed). */
    final Runnable syncTick=new Runnable(){@Override public void run(){
        pool.execute(()->{try{syncNow();}catch(Exception e){android.util.Log.w("Kotoba","sync",e);}});
        syncTimer.postDelayed(this,60_000);
    }};
    @Override protected void onResume(){super.onResume();syncTimer.removeCallbacks(syncTick);syncTimer.postDelayed(syncTick,3000);}
    @Override protected void onPause(){
        syncTimer.removeCallbacks(syncTick);
        // One last write so the Mac sees what was just done here.
        pool.execute(()->{try{syncNow();}catch(Exception ignored){}});
        super.onPause();
    }

    // ---------- importing ----------

    /**
     * Dictionaries whose files were moved or deleted are pointed at files with the same name and size in the chosen
     * folder ("local" = the app's own folder). Indexes stay as they are, so nothing is re-imported.
     */
    JSONObject relink(String tree) throws Exception {
        Map<String,List<String>> found=new HashMap<>();// name/size → uris
        if(tree.equals("local")){
            File root=getExternalFilesDir(null);
            if(root!=null)collectLocal(root,0,found);
        }else{
            Uri t=Uri.parse(tree);
            collectTree(t,DocumentsContract.getTreeDocumentId(t),0,found);
        }
        int fixed=0,missing=0;
        JSONArray all=library.dictionaries();
        for(int i=0;i<all.length();i++){
            JSONObject d=all.getJSONObject(i);
            JSONArray files=new JSONArray().put(d.getString("mdx"));
            JSONArray mdd=new JSONArray(d.getString("mdd"));for(int k=0;k<mdd.length();k++)files.put(mdd.getString(k));
            JSONArray sizes=library.fileSizes(d.getLong("id"));
            boolean changed=false;
            for(int k=0;k<files.length();k++){
                String u=relinked(files.getString(k),sizes.optLong(k,-1),found);
                if(u==null)missing++;else if(!u.equals(files.getString(k))){files.put(k,u);changed=true;}
            }
            if(changed){
                JSONArray m=new JSONArray();for(int k=1;k<files.length();k++)m.put(files.getString(k));
                library.setFiles(d.getLong("id"),files.getString(0),m);fixed++;
            }
        }
        return new JSONObject().put("fixed",fixed).put("missing",missing);
    }

    /** The file's current uri when it still opens, else a file with the same name and size (the index points into it), else null. */
    String relinked(String uri,long size,Map<String,List<String>> found){
        try(FileChannel c=openChannel(uri)){return uri;}catch(Exception e){/* moved or deleted */}
        if(size<0)return null;
        String name=Uri.decode(uri);
        name=name.substring(name.lastIndexOf('/')+1);
        List<String> same=found.get(name+"\u0000"+size);
        return same==null?null:same.get(0);
    }

    void collectLocal(File dir,int depth,Map<String,List<String>> found){
        File[] files=dir.listFiles();if(files==null)return;
        for(File f:files){
            if(f.isDirectory()){if(depth<3)collectLocal(f,depth+1,found);continue;}
            if(f.getName().matches("(?i).+\\.(mdx|mdd|zip)"))found.computeIfAbsent(f.getName()+"\u0000"+f.length(),k->new ArrayList<>()).add(f.getPath());
        }
    }

    void collectTree(Uri tree,String docId,int depth,Map<String,List<String>> found){
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,docId);
        try(Cursor c=getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE},null,null,null)){
            while(c!=null&&c.moveToNext()){
                if(DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))){if(depth<3)collectTree(tree,c.getString(0),depth+1,found);continue;}
                String n=c.getString(1);
                if(n!=null&&n.matches("(?i).+\\.(mdx|mdd|zip)"))found.computeIfAbsent(n+"\u0000"+(c.isNull(3)?0:c.getLong(3)),k->new ArrayList<>()).add(DocumentsContract.buildDocumentUriUsingTree(tree,c.getString(0)).toString());
            }
        }catch(Exception e){android.util.Log.w("Kotoba","relink scan",e);}
    }

    /** Lists MDX dictionaries (with matching MDD resource files) in the chosen folder and its subfolders. */
    JSONArray scan(Uri tree) throws Exception {
        JSONArray found=new JSONArray();
        String rootId=DocumentsContract.getTreeDocumentId(tree);
        JSONArray imported=library.dictionaries();
        scanDirectory(tree,rootId,"",0,found,imported);
        return found;
    }

    void scanDirectory(Uri tree,String docId,String path,int depth,JSONArray found,JSONArray imported) throws Exception {
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,docId);
        List<String[]> files=new ArrayList<>();List<String[]> dirs=new ArrayList<>();
        try(Cursor c=getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE},null,null,null)){
            while(c!=null&&c.moveToNext()){
                String[] row={c.getString(0),c.getString(1),c.getString(2),Long.toString(c.isNull(3)?0:c.getLong(3))};
                if(DocumentsContract.Document.MIME_TYPE_DIR.equals(row[2]))dirs.add(row);else files.add(row);
            }
        }
        for(String[] f:files){
            String name=f[1];
            if(name.toLowerCase(Locale.ROOT).endsWith(".zip")){
                String uri=DocumentsContract.buildDocumentUriUsingTree(tree,f[0]).toString();
                routes.addYomitan(uri,name,path,Long.parseLong(f[3]),found,imported,this::openChannel);
                continue;
            }
            if(!name.toLowerCase(Locale.ROOT).endsWith(".mdx"))continue;
            String base=name.substring(0,name.length()-4);
            JSONArray mdd=new JSONArray();long size=Long.parseLong(f[3]);
            ArrayList<String[]> resources=new ArrayList<>();
            for(String[] g:files){
                String n=g[1].toLowerCase(Locale.ROOT);String b=base.toLowerCase(Locale.ROOT);
                if(n.equals(b+".mdd")||(n.startsWith(b+".")&&n.endsWith(".mdd")&&n.substring(b.length()+1,n.length()-4).matches("\\d+")))resources.add(g);
            }
            resources.sort((x,y)->x[1].length()!=y[1].length()?x[1].length()-y[1].length():x[1].compareTo(y[1]));
            for(String[] g:resources){mdd.put(DocumentsContract.buildDocumentUriUsingTree(tree,g[0]).toString());size+=Long.parseLong(g[3]);}
            String uri=DocumentsContract.buildDocumentUriUsingTree(tree,f[0]).toString();
            String title=base;
            try(MdictFile m=new MdictFile(openChannel(uri),false)){if(!m.title().isEmpty())title=m.title();}
            catch(Exception e){title=base+" — "+e.getMessage();}
            boolean already=false;
            for(int i=0;i<imported.length();i++){
                JSONObject d=imported.getJSONObject(i);
                if(d.getString("mdx").equals(uri)||d.getString("title").equals(title))already=true;
            }
            found.put(new JSONObject().put("name",base).put("title",title).put("folder",path).put("mdx",uri).put("mdd",mdd).put("size",size).put("imported",already));
        }
        if(depth<3)for(String[] dir:dirs)scanDirectory(tree,dir[0],path.isEmpty()?dir[1]:path+"/"+dir[1],depth+1,found,imported);
    }

    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        if(request!=CAMERA_PERMISSION||pendingCamera==null)return;
        PermissionRequest r=pendingCamera;pendingCamera=null;
        if(results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)r.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
        else r.deny();
    }

    @Override protected void onActivityResult(int request,int result,Intent intent){
        super.onActivityResult(request,result,intent);
        if(request==SCAN_PICK&&result==RESULT_OK&&intent!=null&&intent.getData()!=null){
            Uri u=intent.getData();
            pool.execute(()->{
                try{event("scan-ready",new JSONObject().put("name",scans().importStream(getContentResolver().openInputStream(u),getContentResolver().getType(u))));}
                catch(Exception e){event("toast","Couldn’t open that image: "+e.getMessage());}
            });
            return;
        }
        if(request==PICK_COMIC_TREE&&result==RESULT_OK&&intent!=null&&intent.getData()!=null){
            Uri tree=intent.getData();
            try{getContentResolver().takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
            event("comics-scanning",null);
            pool.execute(()->{
                try{event("comics-added",comics.scan(tree));}
                catch(Exception e){event("toast","Couldn’t read that folder: "+e.getMessage());}
            });
            return;
        }
        if(request==PICK_COMIC_FILES&&result==RESULT_OK&&intent!=null){
            ArrayList<Uri> uris=new ArrayList<>();
            if(intent.getClipData()!=null)for(int i=0;i<intent.getClipData().getItemCount();i++)uris.add(intent.getClipData().getItemAt(i).getUri());
            else if(intent.getData()!=null)uris.add(intent.getData());
            pool.execute(()->{
                try{
                    ArrayList<String> names=new ArrayList<>();
                    for(Uri u:uris){try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}names.add(displayName(u));}
                    comics.addArchives(uris,names);
                    event("comics-added",new JSONObject().put("series",1).put("chapters",uris.size()));
                }catch(Exception e){event("toast","Couldn’t add those files: "+e.getMessage());}
            });
            return;
        }
        if(request==PICK_WORDLIST&&result==RESULT_OK&&intent!=null&&intent.getData()!=null){
            Uri u=intent.getData();
            pool.execute(()->{
                try{
                    byte[] b=read(getContentResolver().openInputStream(u),50_000_000);
                    String text=BookParser.decode(b)[0];
                    JSONObject r=wordlists.importText(displayName(u),text);
                    event("wordlist-imported",r);
                }catch(Exception e){event("toast","Couldn’t import that list: "+e.getMessage());}
            });
            return;
        }
        if(request==PICK_COVER&&result==RESULT_OK&&intent!=null&&intent.getData()!=null){
            Uri u=intent.getData();long series=coverSeries;
            pool.execute(()->{
                try{comics.setCover(series,read(getContentResolver().openInputStream(u),32*1024*1024));event("comic-cover",new JSONObject().put("id",series));}
                catch(Exception e){event("toast","Couldn’t use that image: "+e.getMessage());}
            });
            return;
        }
        if(request==PICK_MIHON&&result==RESULT_OK&&intent!=null&&intent.getData()!=null){
            Uri u=intent.getData();
            pool.execute(()->{
                try{event("mihon-imported",comics.importBackup(MihonBackup.parse(read(getContentResolver().openInputStream(u),200_000_000))));}
                catch(Exception e){event("toast","Couldn’t read that backup: "+e.getMessage());}
            });
            return;
        }
        if(request==PICK_BOOKS&&result==RESULT_OK&&intent!=null){
            ArrayList<Uri> uris=new ArrayList<>();
            if(intent.getClipData()!=null)for(int i=0;i<intent.getClipData().getItemCount();i++)uris.add(intent.getClipData().getItemAt(i).getUri());
            else if(intent.getData()!=null)uris.add(intent.getData());
            pool.execute(()->{
                JSONArray added=new JSONArray(),errors=new JSONArray();
                for(Uri u:uris){
                    String name=displayName(u);
                    try{added.put(books.importBook(read(getContentResolver().openInputStream(u),300_000_000),name));}
                    catch(Exception e){errors.put(e.getMessage()==null?name:e.getMessage());}
                }
                try{event("books-imported",new JSONObject().put("added",added).put("errors",errors));}catch(Exception ignored){}
            });
            return;
        }
        if(result!=RESULT_OK||intent==null||intent.getData()==null){pendingExport=null;event("picker-cancelled",request);return;}
        Uri uri=intent.getData();
        if(request==SYNC_FOLDER){
            try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}
            catch(SecurityException e){event("toast","Could not keep access to that folder: "+e.getMessage());return;}
            store.setSetting("sync_folder",uri.toString());
            pool.execute(()->{try{syncNow();event("sync-status",syncStatus());event("toast","Sync folder set");}catch(Exception e){event("toast","Sync: "+e.getMessage());}});
            return;
        }
        if(request==PICK_FOLDER){
            try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException e){event("toast","Could not keep access to that folder: "+e.getMessage());}
            event("folder",uri.toString());
            return;
        }
        pool.execute(()->{
            try{
                if(request==EXPORT){
                    try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){out.write(pendingExport);}
                    pendingExport=null;event("toast","Saved");
                }else if(request==RESTORE){
                    byte[] bytes=read(getContentResolver().openInputStream(uri),100_000_000);
                    JSONObject r=store.restore(new JSONObject(new String(bytes,StandardCharsets.UTF_8)));
                    event("restored",r);
                }
            }catch(Exception e){event("toast","Couldn’t complete that: "+e.getMessage());}
        });
    }

    synchronized Scans scans(){if(scans==null)scans=new Scans(new File(getExternalFilesDir(null),"scans"),ocr);return scans;}

    String displayName(Uri uri){
        try(Cursor c=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null&&c.moveToFirst()&&c.getString(0)!=null)return c.getString(0);
        }catch(Exception ignored){}
        String last=uri.getLastPathSegment();
        return last==null?"book":last.substring(last.lastIndexOf('/')+1);
    }

    // ---------- local web server ----------

    static final String ENTRY_CSP="default-src 'self' data:; script-src 'none'; style-src 'self' 'unsafe-inline' data:; img-src 'self' data:; media-src 'self' data:; font-src 'self' data:; object-src 'none'; base-uri 'none'";
    static final String APP_CSP="default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; media-src 'self' data:; font-src 'self' data:; frame-src 'self'; object-src 'none'; base-uri 'none'";

    WebResourceResponse response(String mime,byte[] bytes,int status,String csp){
        Map<String,String> headers=new HashMap<>();
        headers.put("Cache-Control",status==200&&!mime.startsWith("text/html")&&!mime.equals("application/json")?"max-age=3600":"no-store");
        headers.put("X-Content-Type-Options","nosniff");
        headers.put("Access-Control-Allow-Origin",ORIGIN);
        if(csp!=null)headers.put("Content-Security-Policy",csp);
        String encoding=mime.startsWith("text/")||mime.contains("json")||mime.contains("javascript")?"UTF-8":null;
        return new WebResourceResponse(mime,encoding,status,status==200?"OK":"Not Found",headers,new ByteArrayInputStream(bytes));
    }

    WebResourceResponse serve(WebResourceRequest request){
        Uri uri=request.getUrl();
        if(!HOST.equals(uri.getHost())||!"https".equals(uri.getScheme()))return response("text/plain",new byte[0],404,null);
        String path=uri.getPath()==null?"/":uri.getPath();
        try{
            if(path.startsWith("/scan/")){
                File f=scans().file(path.substring(6));
                if(!f.isFile())return response("text/plain",new byte[0],404,null);
                return response(Library.mime(f.getName()),Files.readAllBytes(f.toPath()),200,null);
            }
            if(path.startsWith("/comic/")){
                String[] p=path.substring(7).split("/");
                if(p.length==2&&p[0].equals("cover")){
                    byte[] b=comics.cover(Long.parseLong(p[1]));
                    return b==null?response("text/plain",new byte[0],404,null):response("image/jpeg",b,200,null);
                }
                if(p.length==2){
                    Object[] res=comics.page(Long.parseLong(p[0]),Integer.parseInt(p[1]));
                    if(res==null)return response("text/plain",new byte[0],404,null);
                    return response((String)res[1],(byte[])res[0],200,null);
                }
                return response("text/plain",new byte[0],404,null);
            }
            if(path.startsWith("/book/")){
                String rest=path.substring(6);int slash=rest.indexOf('/');
                if(slash<0)return response("text/plain",new byte[0],404,null);
                Object[] res=books.resource(Long.parseLong(rest.substring(0,slash)),rest.substring(slash+1));
                if(res==null)return response("text/plain",new byte[0],404,null);
                String mime=(String)res[1];
                return response(mime,(byte[])res[0],200,mime.contains("html")?ENTRY_CSP:null);
            }
            if(path.startsWith("/d/")){
                String rest=path.substring(3);
                int slash=rest.indexOf('/');
                if(slash<0)return response("text/plain",new byte[0],404,null);
                Object[] f=routes.dictFile(Long.parseLong(rest.substring(0,slash)),rest.substring(slash+1));
                if(f==null)return response("text/plain",("Missing: "+rest).getBytes(StandardCharsets.UTF_8),404,null);
                return response((String)f[0],(byte[])f[1],200,f[2]!=null?ENTRY_CSP:null);
            }
            String asset=path.equals("/")?"index.html":path.substring(1);
            if(!asset.matches("[a-zA-Z0-9_.-]+"))return response("text/plain",new byte[0],404,null);
            String mime=asset.endsWith(".js")?"text/javascript":asset.endsWith(".css")?"text/css":asset.endsWith(".svg")?"image/svg+xml":asset.endsWith(".woff2")?"font/woff2":"text/html";
            return response(mime,read(getAssets().open(asset),8*1024*1024),200,mime.equals("text/html")?APP_CSP:null);
        }catch(Exception e){
            android.util.Log.w("Kotoba","serve "+path,e);
            return response("text/plain",String.valueOf(e.getMessage()).getBytes(StandardCharsets.UTF_8),404,null);
        }
    }

    static byte[] read(InputStream in,int limit) throws IOException {
        try(InputStream stream=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[65536];int n,total=0;
            while((n=stream.read(buffer))!=-1){total+=n;if(total>limit)throw new IOException("File is too large");out.write(buffer,0,n);}
            return out.toByteArray();
        }
    }

    @Override public void onBackPressed(){
        web.evaluateJavascript("window.appBack?window.appBack():false",value->{if(!"true".equals(value))moveTaskToBack(true);});
    }

    @Override protected void onDestroy(){
        if(routes!=null&&routes.importing)routes.cancelImport.set(true);
        super.onDestroy();
    }
}
