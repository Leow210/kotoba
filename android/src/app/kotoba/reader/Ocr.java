package app.kotoba.reader;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * On-device text recognition for comic pages (PaddleOCR PP-OCRv5 mobile models through ONNX Runtime).
 * Detection: DB text detector → boxes. Recognition: CRNN + CTC greedy decode per box.
 * Lines are grouped into blocks (speech bubbles) and cached per page.
 */
public final class Ocr {
    /** Bumped when recognition changes, so cached pages are read again. */
    static final int VERSION=10;
    static final int DET_LONG=1280,REC_H=48,REC_MAX_W=3200,REC_MIN_W=320;
    static final float DET_THRESH=0.3f,BOX_THRESH=0.6f,UNCLIP=1.5f,MIN_CONF=0.5f;
    static final float[] MEAN={0.485f,0.456f,0.406f},STD={0.229f,0.224f,0.225f};// applied to B,G,R (the models take BGR)

    final Context context;final SQLiteDatabase db;
    OrtEnvironment env;OrtSession det;OrtSession.SessionOptions opts;
    final java.util.Map<String,OrtSession> recs=new java.util.HashMap<>();final java.util.Map<String,String[]> dicts=new java.util.HashMap<>();
    OrtSession rec;String[] chars;boolean columns;// columns: vertical text is read as columns (Japanese, Chinese)

    public Ocr(Context context,SQLiteDatabase db){
        this.context=context;this.db=db;
        db.execSQL("CREATE TABLE IF NOT EXISTS ocr_cache(chapter INTEGER NOT NULL,page INTEGER NOT NULL,lang TEXT NOT NULL,data TEXT NOT NULL,PRIMARY KEY(chapter,page,lang))");
    }

    public static final class Line { public float x1,y1,x2,y2,conf;public String text=""; }

    /** Recognized text on one comic page, cached. */
    public JSONObject page(Comics comics,long chapter,int index,String lang,boolean refresh) throws Exception {
        if(!refresh){
            JSONArray r=Store.rows(db,"SELECT data FROM ocr_cache WHERE chapter=? AND page=? AND lang=?",Long.toString(chapter),Integer.toString(index),lang);
            if(r.length()>0){JSONObject c=new JSONObject(r.getJSONObject(0).getString("data"));if(c.optInt("v")==VERSION)return c.put("cached",true);}
        }
        Object[] res=comics.page(chapter,index);
        if(res==null)throw new IllegalArgumentException("No such page");
        long t=System.currentTimeMillis();
        JSONObject out=recognize((byte[])res[0],lang).put("v",VERSION);
        out.put("ms",System.currentTimeMillis()-t);
        db.execSQL("INSERT OR REPLACE INTO ocr_cache(chapter,page,lang,data) VALUES(?,?,?,?)",new Object[]{chapter,index,lang,out.toString()});
        return out;
    }
    public void clear(long chapter){db.execSQL("DELETE FROM ocr_cache WHERE chapter=?",new Object[]{chapter});}

    public JSONObject recognize(byte[] image,String lang) throws Exception {
        BitmapFactory.Options o=new BitmapFactory.Options();o.inPreferredConfig=Bitmap.Config.ARGB_8888;
        Bitmap bmp=BitmapFactory.decodeByteArray(image,0,image.length,o);
        if(bmp==null)throw new IllegalArgumentException("Unreadable image");
        return recognize(bmp,lang);
    }

    /** Takes ownership of bmp (it is recycled). */
    public synchronized JSONObject recognize(Bitmap bmp,String lang) throws Exception {
        load(lang);
        int W=bmp.getWidth(),H=bmp.getHeight();
        List<Line> lines=new ArrayList<>();
        // Tall webtoon strips: detect in overlapping tiles so text isn't shrunk to nothing.
        int tile=H>W*3?Math.max(W*5/2,64):H,overlap=H>W*3?W/4:0;
        for(int y0=0;y0<H;y0+=tile-overlap){
            int th=Math.min(tile,H-y0);
            Bitmap part=th==H&&y0==0?bmp:Bitmap.createBitmap(bmp,0,y0,W,th);
            for(float[] b:detect(part)){
                float cy=(b[1]+b[3])/2;
                // Keep a box only in the tile that holds its centre away from the seam.
                if(y0>0&&cy<overlap/2f)continue;
                if(y0+th<H&&cy>th-overlap/2f)continue;
                Line l=new Line();l.x1=b[0];l.y1=b[1]+y0;l.x2=b[2];l.y2=b[3]+y0;
                lines.add(l);
            }
            if(part!=bmp)part.recycle();
            if(y0+th>=H)break;
        }
        for(Line l:lines){l.x1=Math.max(0,l.x1);l.y1=Math.max(0,l.y1);l.x2=Math.min(W,l.x2);l.y2=Math.min(H,l.y2);}
        // Furigana: vertical columns much narrower than the page's typical column. Looking up the base text is enough.
        List<Float> widths=new ArrayList<>();
        for(Line l:lines)if(vertical(l))widths.add(l.x2-l.x1);
        Collections.sort(widths);
        float median=widths.isEmpty()?0:widths.get(widths.size()/2);
        List<Line> kept=new ArrayList<>();
        for(Line l:lines){
            if(l.x2-l.x1<4||l.y2-l.y1<4)continue;
            if(columns&&vertical(l)&&(widths.size()>=3&&l.x2-l.x1<median*0.6f||l.x2-l.x1<median*0.8f&&besideWider(l,lines)))continue;
            recognizeLine(bmp,l);
            if("ko".equals(lang)){
                l.text=fixKorean(l.text);
                // Short lines with no Korean or CJK on a Korean page are artwork or site watermarks (000000, YoN).
                String tt=l.text.trim();
                if(tt.codePointCount(0,tt.length())<=6&&tt.codePoints().noneMatch(c->c>=0xAC00&&c<=0xD7A3||isCjk(c)))continue;
            }
            if(l.text.trim().isEmpty()||l.conf<MIN_CONF)continue;
            // Decorative shapes decode to symbols only (……, ※, ：).
            if(l.text.codePoints().noneMatch(Character::isLetterOrDigit))continue;
            // A lone Latin letter or digit on a Korean/Japanese page is almost always a shape in the artwork.
            String tt=l.text.trim();
            if(tt.codePointCount(0,tt.length())==1&&tt.codePointAt(0)<0x250)continue;
            kept.add(l);
        }
        bmp.recycle();
        JSONObject out=new JSONObject().put("w",W).put("h",H);
        JSONArray blocks=new JSONArray();
        for(List<Line> g:group(kept)){
            float x1=Float.MAX_VALUE,y1=Float.MAX_VALUE,x2=0,y2=0,conf=0;StringBuilder text=new StringBuilder();JSONArray ls=new JSONArray();
            for(Line l:g){
                x1=Math.min(x1,l.x1);y1=Math.min(y1,l.y1);x2=Math.max(x2,l.x2);y2=Math.max(y2,l.y2);conf+=l.conf;
                // Korean and Latin lines join with a space; Japanese lines just continue.
                if(text.length()>0&&!(isCjk(text.codePointBefore(text.length()))&&isCjk(l.text.trim().codePointAt(0))))text.append(' ');text.append(l.text.trim());
                ls.put(new JSONObject().put("x",Math.round(l.x1)).put("y",Math.round(l.y1)).put("w",Math.round(l.x2-l.x1)).put("h",Math.round(l.y2-l.y1)).put("text",l.text.trim()).put("conf",Math.round(l.conf*100)/100.0));
            }
            blocks.put(new JSONObject().put("x",Math.round(x1)).put("y",Math.round(y1)).put("w",Math.round(x2-x1)).put("h",Math.round(y2-y1))
                .put("text",text.toString()).put("conf",Math.round(conf/g.size()*100)/100.0).put("lines",ls));
        }
        return out.put("blocks",blocks);
    }

    /**
     * Korean corrections for the recognizer's usual confusion: 어 next to "!" or a stroke reads as 에.
     * A syllable ending in ㅆ (past 았/었/였, 있) is never followed by 에, so there it must be 어 (기다렸에 → 기다렸어).
     */
    static String fixKorean(String t){
        StringBuilder b=new StringBuilder(t);
        for(int i=1;i<b.length();i++){
            char prev=b.charAt(i-1),c=b.charAt(i);
            boolean ssang=prev>=0xAC00&&prev<=0xD7A3&&(prev-0xAC00)%28==20;
            if(ssang&&c=='에')b.setCharAt(i,'어');
            else if(ssang&&c=='예')b.setCharAt(i,'여');
        }
        return b.toString();
    }
    static boolean isCjk(int c){
        Character.UnicodeScript s=Character.UnicodeScript.of(c);
        return s==Character.UnicodeScript.HAN||s==Character.UnicodeScript.HIRAGANA||s==Character.UnicodeScript.KATAKANA||c==0x30fc||(c>=0x3000&&c<=0x303f)||(c>=0xff01&&c<=0xff60);
    }
    /** Furigana: a thin column right next to (and alongside) a column at least 1.7× wider. */
    static boolean besideWider(Line l,List<Line> all){
        float w=l.x2-l.x1,h=l.y2-l.y1;
        for(Line o:all){
            if(o==l)continue;
            float ow=o.x2-o.x1;
            if(ow<w*1.7f)continue;
            float gap=Math.max(l.x1-o.x2,o.x1-l.x2);
            float overlap=Math.min(l.y2,o.y2)-Math.max(l.y1,o.y1);
            // Ruby never extends past the text it annotates.
            if(gap<w*1.5f&&overlap>h*0.5f&&l.y1>=o.y1-w&&l.y2<=o.y2+w&&h<(o.y2-o.y1)*0.9f)return true;
        }
        return false;
    }
    static boolean vertical(Line l){return l.y2-l.y1>=(l.x2-l.x1)*1.5f;}

    /** Lines stacked closely with overlapping columns belong to one bubble; vertical columns read right to left. */
    static List<List<Line>> group(List<Line> lines){
        List<Line> cols=new ArrayList<>(),rows=new ArrayList<>();
        for(Line l:lines)(vertical(l)?cols:rows).add(l);
        List<List<Line>> groups=groupRows(rows);
        Collections.sort(cols,(a,b)->Float.compare(b.x2,a.x2));
        List<List<Line>> colGroups=new ArrayList<>();
        for(Line l:cols){
            List<Line> best=null;
            for(List<Line> g:colGroups){
                Line last=g.get(g.size()-1);
                float w=Math.min(last.x2-last.x1,l.x2-l.x1);
                float gap=last.x1-l.x2;
                float overlap=Math.min(last.y2,l.y2)-Math.max(last.y1,l.y1);
                if(gap<w*1.1f&&gap>-w*0.6f&&overlap>0.3f*Math.min(last.y2-last.y1,l.y2-l.y1)&&l.y1-last.y1<w*3f&&last.y1-l.y1<w*3f){best=g;break;}
            }
            if(best==null){best=new ArrayList<>();colGroups.add(best);}
            best.add(l);
        }
        groups.addAll(colGroups);
        // Reading order: top to bottom; blocks side by side read right to left for vertical text.
        final boolean rtl=!colGroups.isEmpty()&&colGroups.size()>=groups.size()/2;
        Collections.sort(groups,(a,b)->{
            Line fa=a.get(0),fb=b.get(0);
            float ha=Math.max(fa.y2-fa.y1,fb.y2-fb.y1)*0.5f;
            if(Math.abs(fa.y1-fb.y1)>ha||!rtl)return Float.compare(fa.y1,fb.y1);
            return Float.compare(fb.x2,fa.x2);
        });
        return groups;
    }
    static List<List<Line>> groupRows(List<Line> lines){
        List<Line> sorted=new ArrayList<>(lines);
        Collections.sort(sorted,(a,b)->Float.compare(a.y1,b.y1));
        List<List<Line>> groups=new ArrayList<>();
        for(Line l:sorted){
            List<Line> best=null;
            for(List<Line> g:groups){
                Line last=g.get(g.size()-1);
                float h=Math.min(last.y2-last.y1,l.y2-l.y1);
                float gap=l.y1-last.y2;
                float overlap=Math.min(last.x2,l.x2)-Math.max(last.x1,l.x1);
                float cx=(l.x1+l.x2)/2,lcx=(last.x1+last.x2)/2;
                boolean aligned=overlap>0.3f*Math.min(last.x2-last.x1,l.x2-l.x1)||Math.abs(cx-lcx)<h;
                if(gap<h*0.9f&&gap>-h*0.5f&&aligned){best=g;break;}
            }
            if(best==null){best=new ArrayList<>();groups.add(best);}
            best.add(l);
        }
        return groups;
    }

    /**
     * Korean, Thai and Russian (East Slavic) have their own recognizers; Japanese and Chinese (simplified and
     * traditional) share the multilingual CJK + Latin one.
     */
    void load(String lang) throws Exception {
        String model="ko".equals(lang)||"th".equals(lang)||"ru".equals(lang)?lang:"ja";
        columns="ja".equals(model);
        if(det==null){
            env=OrtEnvironment.getEnvironment();
            opts=new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors())));
            // Without the arena, memory goes back after each page instead of staying at the biggest page's peak (~200 MB).
            opts.setCPUArenaAllocator(false);
            opts.setMemoryPatternOptimization(false);
            det=env.createSession(asset("det.onnx").getPath(),opts);
        }
        if(!recs.containsKey(model)){
            // One recognizer at a time: switching Japanese ↔ Korean frees the other (~30 MB each).
            for(OrtSession other:recs.values())other.close();
            recs.clear();dicts.clear();
            recs.put(model,env.createSession(asset("rec-"+model+".onnx").getPath(),opts));
            String[] dict;
            try(InputStream in=context.getAssets().open("ocr/dict-"+model+".txt")){dict=new String(Comics.readAll(in,8*1024*1024),StandardCharsets.UTF_8).split("\n",-1);}
            String[] c=new String[dict.length+2];c[0]="";System.arraycopy(dict,0,c,1,dict.length);c[c.length-1]=" ";
            dicts.put(model,c);
        }
        rec=recs.get(model);chars=dicts.get(model);
    }
    /** ONNX Runtime wants a file path; models are copied out of the APK once. */
    File asset(String name) throws Exception {
        File dir=new File(context.getFilesDir(),"ocr");dir.mkdirs();
        File f=new File(dir,name);
        long size;try(android.content.res.AssetFileDescriptor fd=context.getAssets().openFd("ocr/"+name)){size=fd.getLength();}catch(Exception e){size=-1;}
        if(f.exists()&&(size<0||f.length()==size))return f;
        File tmp=new File(dir,name+".tmp");
        try(InputStream in=context.getAssets().open("ocr/"+name);FileOutputStream out=new FileOutputStream(tmp)){byte[] b=new byte[1<<16];int n;while((n=in.read(b))>0)out.write(b,0,n);}
        if(!tmp.renameTo(f))throw new IllegalStateException("Couldn't install OCR model");
        return f;
    }

    /** DB detector: returns boxes [x1,y1,x2,y2,score] in the bitmap's pixel space. */
    List<float[]> detect(Bitmap bmp) throws Exception {
        int W=bmp.getWidth(),H=bmp.getHeight();
        float scale=Math.min(1f,(float)DET_LONG/Math.max(W,H));
        int w=Math.max(32,Math.round(W*scale/32f)*32),h=Math.max(32,Math.round(H*scale/32f)*32);
        Bitmap s=Bitmap.createScaledBitmap(bmp,w,h,true);
        int[] px=new int[w*h];s.getPixels(px,0,w,0,0,w,h);if(s!=bmp)s.recycle();
        float[] in=new float[3*w*h];int plane=w*h;
        for(int i=0;i<plane;i++){
            int c=px[i];float r=((c>>16)&255)/255f,g=((c>>8)&255)/255f,b=(c&255)/255f;
            in[i]=(b-MEAN[0])/STD[0];in[plane+i]=(g-MEAN[1])/STD[1];in[2*plane+i]=(r-MEAN[2])/STD[2];
        }
        float[] prob=new float[plane];
        try(OnnxTensor t=OnnxTensor.createTensor(env,FloatBuffer.wrap(in),new long[]{1,3,h,w});
            OrtSession.Result r=det.run(Collections.singletonMap(det.getInputNames().iterator().next(),t))){
            ((OnnxTensor)r.get(0)).getFloatBuffer().get(prob);
        }
        List<float[]> boxes=new ArrayList<>();
        int[] label=new int[plane];int[] stack=new int[plane];int n=0;
        for(int start=0;start<plane;start++){
            if(prob[start]<=DET_THRESH||label[start]!=0)continue;
            n++;int sp=0;stack[sp++]=start;label[start]=n;
            int x1=w,y1=h,x2=0,y2=0,count=0;double sum=0;
            while(sp>0){
                int p=stack[--sp];int x=p%w,y=p/w;count++;sum+=prob[p];
                if(x<x1)x1=x;if(x>x2)x2=x;if(y<y1)y1=y;if(y>y2)y2=y;
                if(x>0){int q=p-1;if(label[q]==0&&prob[q]>DET_THRESH){label[q]=n;stack[sp++]=q;}}
                if(x<w-1){int q=p+1;if(label[q]==0&&prob[q]>DET_THRESH){label[q]=n;stack[sp++]=q;}}
                if(y>0){int q=p-w;if(label[q]==0&&prob[q]>DET_THRESH){label[q]=n;stack[sp++]=q;}}
                if(y<h-1){int q=p+w;if(label[q]==0&&prob[q]>DET_THRESH){label[q]=n;stack[sp++]=q;}}
            }
            if(count<10)continue;
            float score=(float)(sum/count);if(score<BOX_THRESH)continue;
            x2++;y2++;
            float bw=x2-x1,bh=y2-y1,d=bw*bh*UNCLIP/(2*(bw+bh));
            // Vertical columns get little side padding: furigana sits right beside them and garbles the reading.
            float dx=bh>=bw*1.5f?d*0.3f:d;
            boxes.add(new float[]{(x1-dx)/w*W,(y1-d)/h*H,(x2+dx)/w*W,(y2+d)/h*H,score});
        }
        return boxes;
    }

    /** Recognizer: crop → height 48 → CTC greedy decode. */
    /**
     * One text line. Vertical columns are read two ways: turned sideways, and cut into characters laid out upright
     * in a row. The reading whose length best fits the column's shape (and is confident) wins.
     */
    void recognizeLine(Bitmap bmp,Line l) throws Exception {
        int x=(int)l.x1,y=(int)l.y1,cw=Math.max(1,(int)Math.ceil(l.x2)-x),ch=Math.max(1,(int)Math.ceil(l.y2)-y);
        cw=Math.min(cw,bmp.getWidth()-x);ch=Math.min(ch,bmp.getHeight()-y);
        if(cw<2||ch<2)return;
        Bitmap crop=Bitmap.createBitmap(bmp,x,y,cw,ch);
        if(ch<cw*1.5f||!columns){Object[] r=read(crop);crop.recycle();l.text=(String)r[0];l.conf=(Float)r[1];return;}
        Matrix m=new Matrix();m.postRotate(-90);
        Bitmap side=Bitmap.createBitmap(crop,0,0,cw,ch,m,true);
        Object[] a=read(side);side.recycle();
        Bitmap row=uprightRow(crop);
        Object[] b=row==null?new Object[]{"",0f}:read(row);
        if(row!=null)row.recycle();crop.recycle();
        float expected=Math.max(1f,ch/(float)cw*1.6f);
        Object[] best=fit(a,expected)>=fit(b,expected)?a:b;
        l.text=(String)best[0];l.conf=(Float)best[1];
    }
    static float fit(Object[] r,float expected){
        int n=((String)r[0]).replace("…","..").codePointCount(0,((String)r[0]).replace("…","..").length());
        if(n==0)return 0;
        return (Float)r[1]*Math.min(n,expected)/Math.max(n,expected);
    }
    /** Cuts a vertical column at the blank rows between characters and lays the characters out left to right. */
    static Bitmap uprightRow(Bitmap col){
        int w=col.getWidth(),h=col.getHeight();
        int[] px=new int[w*h];col.getPixels(px,0,w,0,0,w,h);
        int[] gray=new int[w*h];int[] hist=new int[256];
        for(int i=0;i<px.length;i++){int c=px[i];int g=(((c>>16)&255)*30+((c>>8)&255)*59+(c&255)*11)/100;gray[i]=g;hist[g]++;}
        int bg=0,acc=0;for(;bg<256;bg++){acc+=hist[bg];if(acc*2>=px.length)break;}
        List<int[]> segs=new ArrayList<>();int start=-1;
        for(int yy=0;yy<h;yy++){
            int ink=0;for(int xx=0;xx<w;xx++)if(Math.abs(gray[yy*w+xx]-bg)>60)ink++;
            boolean on=ink>Math.max(1,0.02f*w);
            if(on&&start<0)start=yy;
            if(!on&&start>=0){segs.add(new int[]{start,yy});start=-1;}
        }
        if(start>=0)segs.add(new int[]{start,h});
        if(segs.isEmpty())return null;
        // Merge pieces of one character (二, 三, separate radicals) up to about a square cell.
        List<int[]> chars=new ArrayList<>();chars.add(segs.get(0));
        for(int i=1;i<segs.size();i++){
            int[] prev=chars.get(chars.size()-1),cur=segs.get(i);
            if(cur[1]-prev[0]<=w*0.95f&&(prev[1]-prev[0]<w*0.55f||cur[1]-cur[0]<w*0.3f&&cur[0]-prev[1]<w*0.15f))prev[1]=cur[1];
            else chars.add(cur);
        }
        int cell=w,gap=Math.max(1,w/4);
        Bitmap out=Bitmap.createBitmap(cell*chars.size()+gap*(chars.size()+1),cell,Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv=new android.graphics.Canvas(out);cv.drawColor(0xff000000|bg<<16|bg<<8|bg);
        android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        int xx=gap;
        for(int[] c:chars){
            int ph=c[1]-c[0];float sc=ph>cell?cell/(float)ph:1f;
            int dw=Math.max(1,Math.round(w*sc)),dh=Math.max(1,Math.round(ph*sc));
            int left=xx+(cell-dw)/2,top=(cell-dh)/2;
            cv.drawBitmap(col,new android.graphics.Rect(0,c[0],w,c[1]),new android.graphics.Rect(left,top,left+dw,top+dh),paint);
            xx+=cell+gap;
        }
        return out;
    }

    /** Recognizer: height 48, padded to at least 320 wide like PaddleOCR's own pipeline, CTC greedy decode. */
    Object[] read(Bitmap crop) throws Exception {
        int cw=crop.getWidth(),ch=crop.getHeight();
        int tw=Math.min(REC_MAX_W,Math.max(16,(int)Math.ceil(REC_H*(double)cw/ch)));
        int pw=Math.max(REC_MIN_W,tw);
        Bitmap s=Bitmap.createScaledBitmap(crop,tw,REC_H,true);
        int[] px=new int[tw*REC_H];s.getPixels(px,0,tw,0,0,tw,REC_H);
        if(s!=crop)s.recycle();
        int plane=pw*REC_H;float[] in=new float[3*plane];// padding stays 0 (mid-grey after normalization)
        for(int yy=0;yy<REC_H;yy++)for(int xx=0;xx<tw;xx++){
            int c=px[yy*tw+xx],i=yy*pw+xx;
            in[i]=((c&255)/255f-0.5f)/0.5f;in[plane+i]=(((c>>8)&255)/255f-0.5f)/0.5f;in[2*plane+i]=(((c>>16)&255)/255f-0.5f)/0.5f;
        }
        try(OnnxTensor t=OnnxTensor.createTensor(env,FloatBuffer.wrap(in),new long[]{1,3,REC_H,pw});
            OrtSession.Result r=rec.run(Collections.singletonMap(rec.getInputNames().iterator().next(),t))){
            OnnxTensor o=(OnnxTensor)r.get(0);long[] shape=o.getInfo().getShape();
            int T=(int)shape[1],C=(int)shape[2];float[] out=new float[T*C];o.getFloatBuffer().get(out);
            StringBuilder text=new StringBuilder();double conf=0;int kept=0,last=-1;
            for(int i=0;i<T;i++){
                int best=0;float bp=out[i*C];
                for(int k=1;k<C;k++){float v=out[i*C+k];if(v>bp){bp=v;best=k;}}
                if(best!=last&&best!=0&&best<chars.length){text.append(chars[best]);conf+=bp;kept++;}
                last=best;
            }
            return new Object[]{text.toString(),kept>0?(float)(conf/kept):0f};
        }
    }
}
