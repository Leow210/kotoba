package app.kotoba.reader;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Korean word spacing for recognized text: webtoon letterers squeeze words together and the mobile recognizer drops
 * spaces inside a line (난언제쯤 평범하게 살수있지? → 난 언제쯤 평범하게 살 수 있지?). A Korean RoBERTa tagger
 * (fiveflow/roberta-base-spacing, int8 ONNX from noticemkjung/korean-spacing-ONNX, ~110 MB) reads the text one
 * character at a time without spaces; a space goes after each word end (E) or one-character word (S).
 * The model is optional: models/korean-spacing/{model_quantized.onnx,vocab.txt} in the app's files folder.
 */
public final class Spacing {
    final File dir;
    OrtEnvironment env;OrtSession session;Map<String,Integer> vocab;boolean broken;
    boolean mask;

    public Spacing(File filesDir){this.dir=new File(filesDir,"models/korean-spacing");}

    public boolean available(){return !broken&&new File(dir,"model_quantized.onnx").isFile()&&new File(dir,"vocab.txt").isFile();}

    synchronized boolean load(){
        if(session!=null)return true;
        if(!available())return false;
        try{
            vocab=new HashMap<>();
            try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(new File(dir,"vocab.txt")),StandardCharsets.UTF_8))){
                String line;int i=0;while((line=r.readLine())!=null)vocab.put(line,i++);
            }
            env=OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts=new OrtSession.SessionOptions();
            session=env.createSession(new File(dir,"model_quantized.onnx").getPath(),opts);
            mask=session.getInputNames().contains("attention_mask");
            return true;
        }catch(Throwable e){broken=true;return false;}
    }

    /** The text with its Korean spacing redone, or the text unchanged when there's no Hangul or no model. */
    public synchronized String fix(String text){
        if(text==null||text.codePoints().noneMatch(c->c>=0xAC00&&c<=0xD7A3)||!load())return text;
        // Line by line (a bubble's lines were joined with spaces); each goes through the tagger without its spaces.
        StringBuilder out=new StringBuilder();
        for(String part:text.split("\n",-1)){
            if(out.length()>0)out.append('\n');
            out.append(fixLine(part));
        }
        return out.toString();
    }

    String fixLine(String line){
        int[] cps=line.codePoints().filter(c->!Character.isWhitespace(c)).toArray();
        if(cps.length==0||cps.length>500)return line;
        int cls=vocab.getOrDefault("[CLS]",0),sep=vocab.getOrDefault("[SEP]",2),unk=vocab.getOrDefault("[UNK]",3);
        long[] ids=new long[cps.length+2];
        ids[0]=cls;ids[ids.length-1]=sep;
        for(int i=0;i<cps.length;i++){
            String ch=new String(Character.toChars(cps[i]));
            Integer id=vocab.get(ch);if(id==null)id=vocab.get("##"+ch);
            ids[i+1]=id==null?unk:id;
        }
        long[] shape={1,ids.length};
        try(OnnxTensor in=OnnxTensor.createTensor(env,LongBuffer.wrap(ids),shape)){
            Map<String,OnnxTensor> feed=new HashMap<>();feed.put("input_ids",in);
            OnnxTensor m=null;
            if(mask){long[] ones=new long[ids.length];java.util.Arrays.fill(ones,1);m=OnnxTensor.createTensor(env,LongBuffer.wrap(ones),shape);feed.put("attention_mask",m);}
            try(OrtSession.Result r=session.run(feed)){
                float[][] logits=((float[][][])r.get(0).getValue())[0];
                boolean[] spaceAfter=new boolean[cps.length];
                for(int i=0;i<cps.length;i++){
                    float[] l=logits[i+1];int best=0;for(int k=1;k<l.length;k++)if(l[k]>l[best])best=k;
                    // Labels: UNK, PAD, O, B, I, E (word end), S (one-character word).
                    spaceAfter[i]=best==5||best==6;
                }
                // Conservative: the spaces already there stay, and only chunks of 4+ Hangul syllables without a space
                // are split (난언제쯤 → 난 언제쯤). Short chunks are sound effects or words the recognizer spaced right
                // (크양, 토독, 본거지잖아 stay whole); the model still reads the whole line for context.
                StringBuilder b=new StringBuilder();
                int k=0;
                String[] chunks=line.trim().split("\\s+");
                for(int ci=0;ci<chunks.length;ci++){
                    int[] cc=chunks[ci].codePoints().toArray();
                    long hangul=java.util.Arrays.stream(cc).filter(c->c>=0xAC00&&c<=0xD7A3).count();
                    if(ci>0)b.append(' ');
                    for(int j=0;j<cc.length;j++,k++){
                        b.appendCodePoint(cc[j]);
                        if(hangul>=4&&j<cc.length-1&&k<spaceAfter.length&&spaceAfter[k])b.append(' ');
                    }
                }
                return b.toString();
            }finally{if(m!=null)m.close();}
        }catch(Throwable e){return line;}
    }
}
