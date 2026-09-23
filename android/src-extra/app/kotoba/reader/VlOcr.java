package app.kotoba.reader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;
import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Content;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.SamplerConfig;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Collections;

/**
 * PaddleOCR-VL 1.6 (0.9B vision-language OCR, LiteRT-LM on the phone's GPU) reading whole speech bubbles: it gets
 * vertical manga columns, furigana and small kana right, and reads stylized Korean lettering far better than the
 * mobile recognizers. The model (~1.4 GB) goes in the app's files/models/ folder; without it PaddleOCR reads as before.
 * Only compiled when tools/fetch_android_libs.py has fetched LiteRT-LM.
 */
public final class VlOcr implements Ocr.BlockReader {
    static final String MODEL="PaddleOCR-VL-1.6.litertlm";
    final File model,cache;final Store store;
    Engine engine;boolean broken;

    VlOcr(Context context,Store store){
        this.model=new File(context.getExternalFilesDir(null),"models/"+MODEL);
        this.cache=context.getCacheDir();this.store=store;
    }

    /** Called by MainActivity through reflection, so the app still builds without LiteRT-LM. */
    public static void install(Context context,Store store){Ocr.blockReader=new VlOcr(context,store);}

    @Override public boolean handles(String lang){
        if(broken||"paddle".equals(store.setting("ocr_engine","")))return false;
        return ("ja".equals(lang)||"ko".equals(lang)||"zh".equals(lang))&&model.isFile();
    }

    synchronized Engine engine() throws Exception {
        if(engine==null){
            long t=System.currentTimeMillis();
            // GPU for both towers: the model card reports degenerate output on CPU.
            Engine e=new Engine(new EngineConfig(model.getPath(),new Backend.GPU(),new Backend.GPU(),null,null,1,cache.getPath()));
            try{e.initialize();}
            catch(Throwable err){broken=true;Log.w("Kotoba","PaddleOCR-VL didn't load; using PaddleOCR",err);throw err;}
            engine=e;
            Log.i("Kotoba","PaddleOCR-VL loaded in "+(System.currentTimeMillis()-t)+" ms");
        }
        return engine;
    }

    /** Timings of the last reads, for tuning (ms): image, first answer, total. */
    public static volatile String lastTimings="";

    /**
     * The runtime squeezes every image to 560×560, which distorts a tall or wide bubble (Korean suffered most); the
     * crop is centred on a white square first, at most 560 px, so letters keep their shape.
     */
    static Bitmap square(Bitmap crop){
        int w=crop.getWidth(),h=crop.getHeight(),side=Math.max(w,h);
        float scale=side>560?560f/side:1f;
        int S=Math.round(side*scale);
        Bitmap out=Bitmap.createBitmap(S,S,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(out);c.drawColor(Color.WHITE);
        int dw=Math.round(w*scale),dh=Math.round(h*scale);
        c.drawBitmap(crop,null,new android.graphics.Rect((S-dw)/2,(S-dh)/2,(S-dw)/2+dw,(S-dh)/2+dh),new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    @Override public synchronized String read(Bitmap crop,String lang) throws Exception {
        long t0=System.currentTimeMillis();
        Bitmap sq=square(crop);
        ByteArrayOutputStream png=new ByteArrayOutputStream();
        sq.compress(Bitmap.CompressFormat.PNG,100,png);sq.recycle();
        Engine e=engine();
        long t1=System.currentTimeMillis();
        // One image per conversation (a second image in the same chat degrades), greedy, and a cap against runaways.
        ConversationConfig config=new ConversationConfig(null,Collections.emptyList(),Collections.emptyList(),new SamplerConfig(1,1.0,0.0,0),
            false,Collections.emptyList(),Collections.emptyMap(),null,false,160);
        try(Conversation c=e.createConversation(config)){
            long tc=System.currentTimeMillis();
            Message m=Message.Companion.user(Contents.Companion.of(new Content.ImageBytes(png.toByteArray()),new Content.Text("OCR:")));
            Message r=c.sendMessage(m,Collections.emptyMap(),null,null,null,null,null,null);
            StringBuilder out=new StringBuilder();
            for(Content x:r.getContents().getContents())if(x instanceof Content.Text)out.append(((Content.Text)x).getText());
            long t2=System.currentTimeMillis();
            lastTimings="image "+(t1-t0)+", conversation "+(tc-t1)+", answer "+(t2-tc)+" ms, "+out.length()+" chars";
            Log.w("Kotoba","PaddleOCR-VL "+lastTimings);
            return out.toString();
        }
    }
}
