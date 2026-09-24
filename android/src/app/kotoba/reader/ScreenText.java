package app.kotoba.reader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Screen text over games on the phone: a floating 文 button; tapping it captures the screen (MediaProjection, kept in
 * memory only), reads it with Kotoba's OCR, and lays the shared overlay page (overlay.js) over the game — tap a word to
 * look it up and save it. Only the screen's pixels are read, never the game itself. Stopped from its notification or a
 * long press on the button.
 */
public class ScreenText extends Service {
    static final String CHANNEL="screen-text";
    static ScreenText running;

    MediaProjection projection;
    VirtualDisplay display;
    ImageReader reader;
    HandlerThread thread;
    Handler worker;
    final Handler main=new Handler(Looper.getMainLooper());
    WindowManager wm;
    TextView bubble;
    WindowManager.LayoutParams bubbleParams;
    WebView overlay;
    boolean overlayShown,pageReady,busy;
    final java.util.ArrayList<String> queued=new java.util.ArrayList<>();
    Bitmap shot;
    int width,height,dpi;

    String lang(){return getSharedPreferences("kotoba",MODE_PRIVATE).getString("screenTextLang","ja");}

    @Override public IBinder onBind(Intent i){return null;}

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&"stop".equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        if(projection!=null||intent==null)return START_NOT_STICKY;
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"Screen text",NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,ScreenText.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Kotoba screen text").setContentText("Tap 文 over a game to read its text. Nothing is saved.")
            .addAction(new Notification.Action.Builder(null,"Stop",stop).build()).setOngoing(true).build();
        startForeground(7,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        MediaProjectionManager mpm=getSystemService(MediaProjectionManager.class);
        projection=mpm.getMediaProjection(intent.getIntExtra("code",0),intent.getParcelableExtra("data"));
        if(projection==null){stopSelf();return START_NOT_STICKY;}
        thread=new HandlerThread("screen-text");thread.start();worker=new Handler(thread.getLooper());
        projection.registerCallback(new MediaProjection.Callback(){@Override public void onStop(){main.post(ScreenText.this::stopSelf);}},main);
        DisplayMetrics m=getResources().getDisplayMetrics();
        dpi=m.densityDpi;
        int[] size=screenSize();width=size[0];height=size[1];
        reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2);
        display=projection.createVirtualDisplay("kotoba-screen-text",width,height,dpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,worker);
        wm=getSystemService(WindowManager.class);
        showBubble();
        running=this;
        return START_NOT_STICKY;
    }

    /** The whole screen in pixels, in its current orientation. */
    int[] screenSize(){
        android.graphics.Rect b=getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
        return new int[]{b.width(),b.height()};
    }

    // ---------- the floating button ----------

    void showBubble(){
        bubble=new TextView(this);
        bubble.setText("文");bubble.setTextColor(Color.WHITE);bubble.setTextSize(20);bubble.setGravity(Gravity.CENTER);
        GradientDrawable bg=new GradientDrawable();bg.setShape(GradientDrawable.OVAL);bg.setColor(Color.argb(210,47,107,85));bg.setStroke(dp(2),Color.argb(160,255,255,255));
        bubble.setBackground(bg);
        int s=dp(46);
        bubbleParams=new WindowManager.LayoutParams(s,s,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT);
        bubbleParams.gravity=Gravity.TOP|Gravity.START;
        bubbleParams.x=getSharedPreferences("kotoba",MODE_PRIVATE).getInt("bubbleX",dp(16));
        bubbleParams.y=getSharedPreferences("kotoba",MODE_PRIVATE).getInt("bubbleY",dp(120));
        // Drag to move; tap to read the screen; hold to stop.
        bubble.setOnTouchListener(new View.OnTouchListener(){
            float dx,dy;int sx,sy;boolean moved;long down;
            @Override public boolean onTouch(View v,MotionEvent e){
                switch(e.getAction()){
                    case MotionEvent.ACTION_DOWN:dx=e.getRawX();dy=e.getRawY();sx=bubbleParams.x;sy=bubbleParams.y;moved=false;down=System.currentTimeMillis();return true;
                    case MotionEvent.ACTION_MOVE:
                        if(Math.abs(e.getRawX()-dx)+Math.abs(e.getRawY()-dy)>dp(8))moved=true;
                        if(moved){bubbleParams.x=sx+(int)(e.getRawX()-dx);bubbleParams.y=sy+(int)(e.getRawY()-dy);wm.updateViewLayout(bubble,bubbleParams);}
                        return true;
                    case MotionEvent.ACTION_UP:
                        if(moved){getSharedPreferences("kotoba",MODE_PRIVATE).edit().putInt("bubbleX",bubbleParams.x).putInt("bubbleY",bubbleParams.y).apply();return true;}
                        if(System.currentTimeMillis()-down>700){toast("Screen text stopped");stopSelf();return true;}
                        capture();return true;
                }
                return false;
            }
        });
        wm.addView(bubble,bubbleParams);
    }
    int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    void toast(String s){main.post(()->android.widget.Toast.makeText(this,s,android.widget.Toast.LENGTH_SHORT).show());}

    // ---------- capture and read ----------

    void capture(){
        if(busy)return;
        MainActivity host=MainActivity.current;
        if(host==null||host.ocr==null){toast("Open Kotoba once, then try again");return;}
        busy=true;
        bubble.setVisibility(View.INVISIBLE);
        // The game may have turned the screen since the capture started.
        int[] size=screenSize();
        if(size[0]!=width||size[1]!=height){
            width=size[0];height=size[1];
            ImageReader old=reader;
            reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2);
            display.resize(width,height,dpi);display.setSurface(reader.getSurface());
            old.close();
        }
        // A fresh frame without the button: drop what's queued, then take the next one.
        worker.post(()->{Image i;while((i=reader.acquireLatestImage())!=null)i.close();});
        worker.postDelayed(()->{
            Bitmap bmp=null;
            for(int tries=0;tries<20&&bmp==null;tries++){
                Image img=reader.acquireLatestImage();
                if(img==null){try{Thread.sleep(25);}catch(InterruptedException ignored){}continue;}
                bmp=toBitmap(img);img.close();
            }
            Bitmap got=bmp;
            main.post(()->{bubble.setVisibility(View.VISIBLE);if(got==null){busy=false;toast("Couldn’t capture the screen");}else showOverlay(got);});
        },140);
    }

    Bitmap toBitmap(Image img){
        Image.Plane p=img.getPlanes()[0];
        ByteBuffer buf=p.getBuffer();
        int pixelStride=p.getPixelStride(),rowStride=p.getRowStride(),w=img.getWidth(),h=img.getHeight();
        Bitmap wide=Bitmap.createBitmap(rowStride/pixelStride,h,Bitmap.Config.ARGB_8888);
        wide.copyPixelsFromBuffer(buf);
        if(wide.getWidth()==w)return wide;
        Bitmap b=Bitmap.createBitmap(wide,0,0,w,h);wide.recycle();return b;
    }

    void showOverlay(Bitmap bmp){
        if(shot!=null&&!shot.isRecycled())shot.recycle();
        shot=bmp;
        if(overlay==null)buildOverlay();
        if(!overlayShown){wm.addView(overlay,overlayParams());overlayShown=true;}
        overlay.requestFocus();
        MainActivity host=MainActivity.current;
        String app="Screen";
        try{
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG,80,out);
            JSONObject d=new JSONObject().put("image","data:image/jpeg;base64,"+Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP))
                .put("w",bmp.getWidth()).put("h",bmp.getHeight()).put("app",app).put("lang",lang());
            send("window.gameOverlay&&gameOverlay.show("+d+")");
        }catch(Exception e){busy=false;return;}
        recognize();
    }

    /** OCR on Kotoba's recognition thread; lines in pixels of the capture. */
    void recognize(){
        MainActivity host=MainActivity.current;
        Bitmap bmp=shot;String lang=lang();
        if(host==null||bmp==null){busy=false;return;}
        Bitmap copy=bmp.copy(Bitmap.Config.ARGB_8888,false);// recognize() recycles its bitmap; the shot stays for re-reads
        host.ocrPool.execute(()->{
            JSONArray lines=new JSONArray();
            try{
                JSONObject r=host.ocr.recognize(copy,lang);
                JSONArray blocks=r.getJSONArray("blocks");
                for(int i=0;i<blocks.length();i++){
                    JSONArray ls=blocks.getJSONObject(i).optJSONArray("lines");
                    if(ls!=null)for(int j=0;j<ls.length();j++)lines.put(ls.getJSONObject(j));
                }
            }catch(Exception e){toast("Couldn’t read the text: "+e.getMessage());}
            main.post(()->{busy=false;send("window.gameOverlay&&gameOverlay.lines("+lines+")");});
        });
    }

    WindowManager.LayoutParams overlayParams(){
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS|WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.START;
        // Over the notch too, so the boxes line up with the capture, which covers the whole screen.
        p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        return p;
    }

    void buildOverlay(){
        MainActivity host=MainActivity.current;
        overlay=new WebView(this){
            // Back closes the top sheet, then the overlay.
            @Override public boolean dispatchKeyEvent(KeyEvent e){
                if(e.getKeyCode()==KeyEvent.KEYCODE_BACK){if(e.getAction()==KeyEvent.ACTION_UP)evaluateJavascript("window.gameOverlayBack&&gameOverlayBack()",null);return true;}
                return super.dispatchKeyEvent(e);
            }
        };
        overlay.setBackgroundColor(Color.TRANSPARENT);
        android.webkit.WebSettings s=overlay.getSettings();
        s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);
        s.setTextZoom(100);s.setMediaPlaybackRequiresUserGesture(false);
        overlay.addJavascriptInterface(new Bridge(),"Kotoba");
        overlay.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){MainActivity h=MainActivity.current;return h==null?null:h.serve(r);}
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}
        });
        overlay.loadUrl(MainActivity.ORIGIN+"/?overlay=1");
    }

    void send(String js){
        if(pageReady)overlay.evaluateJavascript(js,null);else queued.add(js);
    }

    void hideOverlay(){
        if(overlay!=null)overlay.evaluateJavascript("window.gameOverlay&&gameOverlay.hide()",null);
        if(overlayShown){wm.removeView(overlay);overlayShown=false;}
        // The capture only lived in memory; drop it.
        if(shot!=null){shot.recycle();shot=null;}
    }

    /** The page's link to Kotoba: the same routes as the app, answered into this window. */
    public class Bridge {
        @JavascriptInterface public void call(int id,String route,String body){
            MainActivity h=MainActivity.current;
            if(h==null)return;
            (route.startsWith("ocr.")?h.ocrPool:h.pool).execute(()->{
                String reply;
                try{
                    Object result=h.route(route,body==null||body.isEmpty()?new JSONObject():new JSONObject(body));
                    reply=new JSONObject().put("data",result==null?JSONObject.NULL:result).toString();
                }catch(Throwable e){reply=h.error(e);}
                String r=reply;
                main.post(()->{if(overlay!=null)overlay.evaluateJavascript("window.__reply("+id+","+JSONObject.quote(r)+")",null);});
            });
        }
        @JavascriptInterface public void overlay(String json){
            main.post(()->{
                try{
                    JSONObject m=new JSONObject(json);
                    switch(m.optString("cmd")){
                        case "ready":pageReady=true;for(String q:queued)overlay.evaluateJavascript(q,null);queued.clear();break;
                        case "close":hideOverlay();break;
                        case "rescan":hideOverlay();main.postDelayed(ScreenText.this::capture,200);break;
                        case "lang":
                            getSharedPreferences("kotoba",MODE_PRIVATE).edit().putString("screenTextLang",m.optString("lang","ja")).apply();
                            busy=true;recognize();break;
                    }
                }catch(Exception ignored){}
            });
        }
        @JavascriptInterface public void interacting(){}
        @JavascriptInterface public void copy(String text){main.post(()->((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("Kotoba",text)));}
        @JavascriptInterface public void translate(String text){MainActivity h=MainActivity.current;if(h!=null)h.new Bridge().translate(text);}
        @JavascriptInterface public void setBars(String a,String b){}
    }

    @Override public void onDestroy(){
        running=null;
        try{hideOverlay();}catch(Exception ignored){}
        try{if(bubble!=null)wm.removeView(bubble);}catch(Exception ignored){}
        if(overlay!=null){overlay.destroy();overlay=null;}
        if(display!=null)display.release();
        if(reader!=null)reader.close();
        if(projection!=null)projection.stop();
        if(thread!=null)thread.quitSafely();
        super.onDestroy();
    }
}
