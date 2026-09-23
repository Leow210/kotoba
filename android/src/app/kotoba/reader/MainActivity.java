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
    static final int PICK_FOLDER=51,EXPORT=52,RESTORE=53,PICK_BOOKS=54,PICK_COMIC_TREE=55,PICK_COMIC_FILES=56,PICK_WORDLIST=57,PICK_MIHON=58,PICK_COVER=59,SCAN_PICK=61,CAMERA_PERMISSION=62;
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
    final ExecutorService importer=Executors.newSingleThreadExecutor();
    final AtomicBoolean cancelImport=new AtomicBoolean(false);
    volatile boolean importing=false;
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
                switch(route){
                    case "backup":text=store.backup().toString(1);mime="application/json";break;
                    case "tsv":text=store.exportTsv(data.optLong("folder",0),data.optBoolean("html",true));mime="text/tab-separated-values";break;
                    case "csv":text=store.exportCsv(data.optLong("folder",0));mime="text/csv";break;
                    case "pleco":{
                        java.util.Set<Long> zh=new java.util.HashSet<>();
                        JSONArray all=library.dictionaries();
                        for(int i=0;i<all.length();i++){JSONObject x=all.getJSONObject(i);if(x.getString("grp").equals("Chinese")||x.getString("grp").startsWith("Chinese/"))zh.add(x.getLong("id"));}
                        text=store.exportPleco(data.optLong("folder",0),zh);mime="text/plain";break;
                    }
                    case "text":text=data.optString("text","");mime="text/plain";break;
                    case "highlights":text=books.exportHighlights(data.getLong("book"));mime="text/markdown";break;
                    default:throw new Exception("Unknown export");
                }
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
            case "dicts":return library.dictionaries();
            case "search":{
                String q=d.optString("q","");
                JSONObject result=library.search(q,d.optString("mode","headword"),d.optString("dict",""),d.optInt("offset",0));
                if(d.optInt("offset",0)==0&&!q.trim().isEmpty()){
                    result.put("kanji",library.kanji(q));
                    if(d.optString("mode","headword").equals("headword"))result.put("forms",library.forms(q));
                }
                return result;
            }
            case "forms":return library.forms(d.getString("q"));
            case "audio":return library.audioFor(d.getString("key"),d.optString("reading",""),d.optLong("dict",0));
            case "exact":return library.exact(d.getString("key"),null);
            case "freq":return library.frequencies(d.getString("key"),d.optString("reading",""));
            case "lookup":return library.lookup(d.getString("text"),d.optString("lang",""));
            case "record":{
                JSONObject r=library.record(d.getLong("rec"));
                r.put("saved",store.savedFor(r.getLong("dict"),r.getString("key")));
                return r;
            }
            case "resolve":{
                long rec=library.findRecord(d.getLong("dict"),d.getString("page"));
                return new JSONObject().put("rec",rec==0?JSONObject.NULL:rec);
            }
            case "browse":return library.browse(d.getLong("dict"),d.optString("dir","from"),d.optString("norm",""),d.optLong("id",0),d.optString("prefix",""),d.optInt("limit",120),d.optBoolean("kanji",false));
            case "kanji.grid":return library.kanjiGrid(d.getLong("dict"),d.optString("level",""),d.optInt("strokes",0),d.optString("radical",""),d.optString("flag",""));
            case "random":return library.random(d.optLong("dict",0));
            case "neighbors":return library.neighbors(d.getLong("rec"));
            case "reference":return library.reference(d.getLong("dict"),d.getString("ref"));
            case "history":return store.history();
            case "history.add":store.remember(d.optString("q",""));return null;
            case "history.clear":store.clearHistory();return null;
            case "dict.update":library.updateDictionary(d);return null;
            case "dict.reorder":library.reorder(d.getJSONArray("ids"));return null;
            case "dict.delete":{
                if(importing)throw new Exception("Wait for the current import to finish.");
                library.delete(d.getLong("id"));return null;
            }
            case "library.scan":return scan(Uri.parse(d.getString("tree")));
            case "library.relink":return relink(d.getString("tree"));
            case "library.scanLocal":{
                JSONArray found=new JSONArray();
                File root=getExternalFilesDir(null);
                if(root!=null)scanFiles(root,"",0,found,library.dictionaries());
                return new JSONObject().put("items",found).put("path",root==null?"":root.getPath());
            }
            case "library.folders":{
                JSONArray out=new JSONArray();
                for(android.content.UriPermission p:getContentResolver().getPersistedUriPermissions())out.put(p.getUri().toString());
                return out;
            }
            case "library.import":startImport(d.getJSONArray("items"),d.optBoolean("fulltext",true));return null;
            case "library.cancel":cancelImport.set(true);return null;
            case "library.status":return new JSONObject().put("importing",importing);
            case "books":return books.list();
            case "book.open":return books.open(d.getLong("id"));
            case "book.position":books.savePosition(d.getLong("id"),d.getString("position"),d.optDouble("progress",0));return null;
            case "book.settings":books.saveSettings(d.getLong("id"),d.getJSONObject("settings"));return null;
            case "book.rename":books.rename(d.getLong("id"),d.getString("title"));return null;
            case "book.delete":books.delete(d.getLong("id"));return null;
            case "book.importPath":{
                java.io.File f=new java.io.File(d.getString("path"));
                return books.importBook(read(new java.io.FileInputStream(f),300_000_000),f.getName());
            }
            case "comics":return comics.list();
            case "comic.series":return comics.series(d.getLong("id"));
            case "comic.settings":comics.saveSettings(d.getLong("id"),d.getJSONObject("settings"));return null;
            case "comic.rename":comics.rename(d.getLong("id"),d.getString("title"));return null;
            case "comic.delete":comics.delete(d.getLong("id"));return null;
            case "ocr.page":return ocr.page(comics,d.getLong("chapter"),d.getInt("page"),d.optString("lang","ko"),d.optBoolean("refresh",false));
            case "ocr.clear":ocr.clear(d.getLong("chapter"));return null;
            case "comic.coverFromPage":comics.coverFromPage(d.getLong("chapter"),d.getInt("page"));return null;
            case "comic.resetCover":comics.setCover(d.getLong("id"),null);return null;
            case "comic.pages":return new JSONObject().put("count",comics.pages(d.getLong("chapter")).size());
            case "comic.progress":comics.progress(d.getLong("chapter"),d.getInt("page"),d.optBoolean("read",false));return null;
            case "comic.read":comics.markRead(d.getJSONArray("ids"),d.getBoolean("read"));return null;
            case "comic.marks":return comics.marks(d.getLong("series"));
            case "comic.mark":return comics.addMark(d.getLong("chapter"),d.getInt("page"),d.optString("label",""));
            case "comic.unmark":comics.deleteMark(d.getLong("id"));return null;
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
            case "wordlists":return wordlists.lists();
            case "wordlist.items":if("abc".equals(d.optString("sort")))wordlists.fillSortKeys(d.getLong("id"),library::readingOf);
                return wordlists.items(d.getLong("id"),d.optInt("offset",0),d.optInt("limit",200),d.optString("q",""),d.optString("sort",""));
            case "wordlist.delete":wordlists.delete(d.getLong("id"));return null;
            case "wordlist.rename":wordlists.rename(d.getLong("id"),d.getString("name"));return null;
            case "wordlist.toFolder":return wordListToFolder(d.getLong("id"),d.getLong("folder"));
            case "gloss":{
                JSONArray words=d.getJSONArray("words"),out=new JSONArray();
                for(int i=0;i<words.length()&&i<200;i++)out.put(library.gloss(words.getString(i)));
                return out;
            }
            case "rank":{JSONObject r=wordlists.rank(d.getString("word"));return r==null?JSONObject.NULL:r;}
            case "scan.read":return scans().read(d.getString("name"),d.optString("lang","ja"),d.optJSONArray("crop"));
            case "scan.list":return scans().list();
            case "scan.delete":scans().delete(d.getString("name"));return null;
            case "scan.save":{
                // A photo from the scanner's own camera view, as base64 JPEG.
                byte[] b=android.util.Base64.decode(d.getString("data"),android.util.Base64.DEFAULT);
                return new JSONObject().put("name",scans().importStream(new ByteArrayInputStream(b),d.optString("mime","image/jpeg")));
            }
            case "appendix":return extras.appendix(d.getLong("dict"));
            case "appendix.counts":return extras.counts();
            case "dictlists":return extras.lists();
            case "dictlist":return extras.list(d.getLong("dict"),d.getInt("index"));
            case "dictlist.toFolder":return extras.toFolder(store,d.getLong("dict"),d.getInt("index"),d.optString("section",""),d.getLong("folder"),n->event("toast","Added "+n+" cards…"));
            case "dictlist.resolve":return extras.resolve(d.getLong("dict"),d.optString("anchor"),d.optString("word"));
            case "highlights":return books.highlights(d.getLong("book"));
            case "highlight.save":return books.saveHighlight(d);
            case "highlight.delete":books.deleteHighlight(d.getLong("id"));return null;
            case "bookmarks":return books.bookmarks(d.getLong("book"));
            case "bookmark.save":return books.saveBookmark(d);
            case "bookmark.delete":books.deleteBookmark(d.getLong("id"));return null;
            case "folders":return store.folders();
            case "folder.save":return store.saveFolder(d);
            case "folder.study":store.setStudy(d.getLong("id"),d.getBoolean("study"));return null;
            case "folder.delete":store.deleteFolder(d.getLong("id"),d.optBoolean("items",false));return null;
            case "folder.reorder":store.reorderFolders(d.getJSONArray("ids"));return null;
            case "items":return store.items(d);
            case "item.similar":return store.similar(d.getString("headword"),d.optString("reading",""));
            case "item":return store.item(d.getLong("id"));
            case "item.save":return store.saveItem(d);
            case "item.delete":store.deleteItems(d.getJSONArray("ids"));return null;
            case "item.move":store.moveItems(d.getJSONArray("ids"),d.getLong("folder"),d.optBoolean("copy",false));return null;
            case "item.review":store.setReview(d.getJSONArray("ids"),d.getBoolean("review"));return null;
            case "item.reset":store.resetProgress(d.getJSONArray("ids"));return null;
            case "queue":return store.queue(d.optLong("folder",0));
            case "answer":return store.answer(d.getLong("id"),d.getInt("rating"));
            case "undo":return store.undo();
            case "stats":return store.stats().put("library",library.stats());
            case "settings":return store.settings();
            case "setting":store.setSetting(d.getString("key"),d.getString("value"));return null;
            default:throw new Exception("Unknown request: "+route);
        }
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
                addYomitan(uri,name,path,Long.parseLong(f[3]),found,imported);
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

    /** A Yomitan dictionary ZIP (index.json + banks); other ZIPs are ignored. */
    void addYomitan(String uri,String file,String folder,long size,JSONArray found,JSONArray imported) throws Exception {
        String title;
        try(ZipSource z=new ZipSource(openChannel(uri))){title=Library.yomitanTitle(z);}catch(Exception e){return;}
        if(title==null)return;
        boolean already=false;
        for(int i=0;i<imported.length();i++){JSONObject d=imported.getJSONObject(i);if(d.getString("mdx").equals(uri)||d.getString("title").equals(title))already=true;}
        String base=file.replaceFirst("(?i)\\.zip$","");
        found.put(new JSONObject().put("name",base).put("title",title).put("folder",folder).put("mdx",uri).put("mdd",new JSONArray()).put("size",size).put("imported",already).put("format","yomitan"));
    }

    /** Dictionaries copied into the app's own folder (Android/data/app.kotoba.reader/files) over USB. */
    void scanFiles(File dir,String path,int depth,JSONArray found,JSONArray imported) throws Exception {
        File[] files=dir.listFiles();if(files==null)return;
        java.util.Arrays.sort(files);
        for(File f:files){
            if(f.isDirectory()){if(depth<3)scanFiles(f,path.isEmpty()?f.getName():path+"/"+f.getName(),depth+1,found,imported);continue;}
            String name=f.getName();
            if(name.toLowerCase(Locale.ROOT).endsWith(".zip")){addYomitan(f.getPath(),name,path,f.length(),found,imported);continue;}
            if(!name.toLowerCase(Locale.ROOT).endsWith(".mdx"))continue;
            String base=name.substring(0,name.length()-4);
            JSONArray mdd=new JSONArray();long size=f.length();
            for(File g:files){
                String n=g.getName().toLowerCase(Locale.ROOT),b=base.toLowerCase(Locale.ROOT);
                if(n.equals(b+".mdd")||(n.startsWith(b+".")&&n.endsWith(".mdd")&&n.substring(b.length()+1,n.length()-4).matches("\\d+"))){mdd.put(g.getPath());size+=g.length();}
            }
            String title=base;
            try(MdictFile m=new MdictFile(openChannel(f.getPath()),false)){if(!m.title().isEmpty())title=m.title();}catch(Exception e){title=base+" — "+e.getMessage();}
            boolean already=false;
            for(int i=0;i<imported.length();i++){JSONObject d=imported.getJSONObject(i);if(d.getString("mdx").equals(f.getPath())||d.getString("title").equals(title))already=true;}
            found.put(new JSONObject().put("name",base).put("title",title).put("folder",path).put("mdx",f.getPath()).put("mdd",mdd).put("size",size).put("imported",already));
        }
    }

    void startImport(JSONArray items,boolean fulltext) throws Exception {
        if(importing)throw new Exception("An import is already running.");
        importing=true;cancelImport.set(false);
        runOnUiThread(()->getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        importer.execute(()->{
            int done=0,failed=0;
            try{
                for(int i=0;i<items.length();i++){
                    if(cancelImport.get())break;
                    JSONObject item=items.getJSONObject(i);
                    String title=item.optString("title",item.optString("name"));
                    final int index=i;
                    long started=System.currentTimeMillis();
                    try{
                        ArrayList<String> mdd=new ArrayList<>();
                        JSONArray m=item.optJSONArray("mdd");
                        if(m!=null)for(int k=0;k<m.length();k++)mdd.add(m.getString(k));
                        Library.Progress progress=new Library.Progress(){
                            long last=0;
                            @Override public void update(String stage,long a,long b){
                                long t=System.currentTimeMillis();
                                if(t-last<250&&a<b)return;
                                last=t;
                                try{event("import",new JSONObject().put("title",title).put("index",index).put("count",items.length()).put("stage",stage).put("done",a).put("total",b));}catch(Exception ignored){}
                            }
                            @Override public boolean cancelled(){return cancelImport.get();}
                        };
                        if("yomitan".equals(item.optString("format")))library.importYomitan(item.getString("name"),item.getString("mdx"),fulltext,progress);
                        else library.importDictionary(item.getString("name"),item.getString("mdx"),mdd,fulltext,progress);
                        done++;
                        android.util.Log.i("Kotoba","Imported "+title+" in "+(System.currentTimeMillis()-started)+"ms");
                    }catch(Throwable e){
                        failed++;
                        android.util.Log.w("Kotoba","Import failed "+title,e);
                        event("import-error",new JSONObject().put("title",title).put("error",e.getMessage()==null?e.toString():e.getMessage()));
                    }
                }
                event("import-done",new JSONObject().put("done",done).put("failed",failed).put("cancelled",cancelImport.get()));
            }catch(Exception e){event("import-error",e.getMessage());}
            finally{
                importing=false;
                runOnUiThread(()->getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            }
        });
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

    /** Every word of a list becomes a card in a folder, with a short definition from the dictionaries. */
    JSONObject wordListToFolder(long list,long folder) throws Exception {
        int added=0,missing=0,offset=0;
        while(true){
            JSONArray items=wordlists.items(list,offset,300,"");
            if(items.length()==0)break;
            for(int i=0;i<items.length();i++){
                JSONObject it=items.getJSONObject(i);offset=it.getInt("pos");
                JSONObject g=library.gloss(it.getString("word"));
                String back=!it.optString("note").isEmpty()?it.getString("note"):g.optString("text","");
                if(back.isEmpty()){missing++;continue;}
                JSONObject data=new JSONObject().put("folder_id",folder).put("headword",it.getString("word")).put("reading",it.optString("reading")).put("back",back)
                    .put("dict",g.optLong("dict",0)).put("dict_name",g.optString("dictionary")).put("page",g.optString("page")).put("kind","wordlist").put("review",true);
                store.saveItem(data);added++;
            }
            if(offset%50==0)event("toast","Added "+added+"…");
        }
        return new JSONObject().put("added",added).put("missing",missing);
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
                long dict=Long.parseLong(rest.substring(0,slash));
                String name=rest.substring(slash+1);
                if(name.startsWith("item-")&&name.endsWith(".card")){
                    JSONObject item=store.item(Long.parseLong(name.substring(5,name.length()-5)));
                    String body=item.getString("back_html").isEmpty()?"<div class=\"kotoba-plain\">"+Store.escape(item.getString("back")).replace("\n","<br>")+"</div>":item.getString("back_html");
                    String page="<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><link rel=\"stylesheet\" href=\"/entry-base.css\"></head><body class=\"kotoba-entry kotoba-card\">"+body+"</body></html>";
                    return response("text/html",page.getBytes(StandardCharsets.UTF_8),200,ENTRY_CSP);
                }
                if(name.endsWith(".entry")){
                    long rec=Long.parseLong(name.substring(0,name.length()-6));
                    return response("text/html",entryPage(rec).getBytes(StandardCharsets.UTF_8),200,ENTRY_CSP);
                }
                byte[] bytes=name.startsWith("files/")?extras.file(dict,name):library.resource(dict,name);
                if(bytes==null)bytes=extras.file(dict,"files/"+name.substring(name.lastIndexOf('/')+1));
                if(bytes==null)return response("text/plain",("Missing: "+name).getBytes(StandardCharsets.UTF_8),404,null);
                String mime=Library.mime(name);
                if(mime.equals("text/css")&&!library.isYomitan(dict))bytes=MarkupFix.css(new String(bytes,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                if(mime.equals("text/html")){
                    // Appendix pages: same treatment as entries (no scripts, renderable markup, base styles).
                    String h=MarkupFix.html(new String(bytes,StandardCharsets.UTF_8));
                    h=h.replaceAll("(?is)<script\\b.*?</script>","");
                    if(!h.contains("entry-base.css"))h="<link rel=\"stylesheet\" href=\"/entry-base.css\">"+h.replaceFirst("(?i)<body([^>]*)>","<body$1 class=\"kotoba-entry\">");
                    return response(mime,h.getBytes(StandardCharsets.UTF_8),200,ENTRY_CSP);
                }
                return response(mime,bytes,200,null);
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

    String entryPage(long rec) throws Exception {
        String html=MarkupFix.html(library.recordHtml(rec));
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            +"<link rel=\"stylesheet\" href=\"/entry-base.css\"></head><body class=\"kotoba-entry\">"+html+"</body></html>";
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
        if(importing)cancelImport.set(true);
        super.onDestroy();
    }
}
