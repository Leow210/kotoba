package app.kotoba.reader;

import org.json.JSONObject;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Translation on the Mac with TranslateGemma (a GGUF run by llama.cpp's llama-server), so a sentence can be read in
 * another language without leaving Kotoba. The server starts on the first request and stops after ten idle minutes.
 */
final class Translator {
    static final String DEFAULT_MODEL="/Volumes/T7/LLM Models/TranslateGemma-12B/translategemma-12b-it.Q4_K_M.gguf";
    static final int PORT=18790;
    static final String[][] LANGS={{"auto","Detect"},{"en","English"},{"ko","Korean"},{"ja","Japanese"},{"zh-Hans","Chinese (Simplified)"},{"zh-Hant","Chinese (Traditional)"},
        {"yue","Cantonese"},{"th","Thai"},{"ru","Russian"},{"fr","French"},{"de","German"},{"es","Spanish"},{"pt","Portuguese"},{"it","Italian"},{"vi","Vietnamese"},{"id","Indonesian"}};
    final Store store;final File data;
    Process server;long lastUse;

    Translator(Store store,File data){
        this.store=store;this.data=data;
        Thread idle=new Thread(()->{
            while(true){
                try{Thread.sleep(60_000);}catch(InterruptedException e){return;}
                synchronized(this){if(server!=null&&System.currentTimeMillis()-lastUse>600_000){server.destroy();server=null;}}
            }
        },"translator-idle");
        idle.setDaemon(true);idle.start();
        Runtime.getRuntime().addShutdownHook(new Thread(()->{Process p=server;if(p!=null)p.destroy();}));
    }

    String model(){return store.setting("translate_model",DEFAULT_MODEL);}
    static File llamaServer(){
        for(String p:new String[]{"/opt/homebrew/bin/llama-server","/usr/local/bin/llama-server"}){File f=new File(p);if(f.canExecute())return f;}
        return null;
    }
    JSONObject config(){
        File m=new File(model());
        String engine=store.setting("translate_engine",m.isFile()?"gemma":"google");
        JSONObject langs=new JSONObject();for(String[] l:LANGS)langs.put(l[0],l[1]);
        return new JSONObject().put("engine",engine).put("from",store.setting("translate_from","auto")).put("to",store.setting("translate_to","en"))
            .put("model",m.getPath()).put("modelFound",m.isFile()).put("serverFound",llamaServer()!=null).put("running",server!=null&&server.isAlive())
            .put("languages",langs);
    }
    void set(JSONObject d){
        for(String k:new String[]{"engine","from","to","model"})if(d.has(k))store.setSetting("translate_"+k,d.getString(k));
    }

    static String name(String code){for(String[] l:LANGS)if(l[0].equals(code))return l[1];return code;}
    /** The source language from the script, when it's set to Detect. */
    static String detect(String t){
        int ko=0,kana=0,han=0,th=0,cy=0,lat=0;
        for(int c:t.codePoints().toArray()){
            if(c>=0xAC00&&c<=0xD7A3||c>=0x1100&&c<=0x11FF)ko++;else if(c>=0x3040&&c<=0x30FF)kana++;else if(Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN)han++;
            else if(c>=0x0E00&&c<=0x0E7F)th++;else if(c>=0x0400&&c<=0x04FF)cy++;else if(Character.isLetter(c))lat++;
        }
        if(ko>0&&ko>=kana)return "ko";if(kana>0)return "ja";if(han>0)return "zh-Hans";if(th>0)return "th";if(cy>0)return "ru";
        return "en";
    }

    synchronized JSONObject translate(String text,String from,String to) throws Exception {
        if(text.trim().isEmpty())throw new Exception("Nothing to translate.");
        if(from==null||from.isEmpty())from=store.setting("translate_from","auto");
        if(to==null||to.isEmpty())to=store.setting("translate_to","en");
        if(from.equals("auto"))from=detect(text);
        if(from.equals(to))to=from.equals("en")?"ko":"en";
        long t=System.currentTimeMillis();
        ensureServer();
        lastUse=System.currentTimeMillis();
        // TranslateGemma's recommended prompt, in Gemma's turn format (the GGUF's chat template expects structured content).
        String src=name(from),tgt=name(to);
        String prompt="You are a professional "+src+" ("+from+") to "+tgt+" ("+to+") translator. Your goal is to accurately convey the meaning and nuances of the original "
            +src+" text while adhering to "+tgt+" grammar, vocabulary, and cultural sensitivities.\nProduce only the "+tgt+" translation, without any additional explanations or commentary. Please translate the following "
            +src+" text into "+tgt+":\n\n\n"+text.trim();
        JSONObject body=new JSONObject().put("prompt","<start_of_turn>user\n"+prompt+"<end_of_turn>\n<start_of_turn>model\n")
            .put("n_predict",Math.min(2048,text.length()*4+64)).put("temperature",0).put("cache_prompt",true).put("stop",new org.json.JSONArray().put("<end_of_turn>"));
        JSONObject r=new JSONObject(http("POST","/completion",body.toString(),300_000));
        lastUse=System.currentTimeMillis();
        return new JSONObject().put("text",r.optString("content","").trim()).put("from",from).put("to",to).put("ms",System.currentTimeMillis()-t);
    }

    void ensureServer() throws Exception {
        if(server!=null&&server.isAlive())return;
        File m=new File(model());
        if(!m.isFile())throw new Exception("TranslateGemma isn't at "+m.getPath()+". Plug in the drive, or choose the model in Settings › Translation.");
        File exe=llamaServer();
        if(exe==null)throw new Exception("llama-server isn't installed (brew install llama.cpp).");
        try{http("GET","/health",null,1000);return;}catch(Exception ignored){}// one already running on the port
        server=new ProcessBuilder(exe.getPath(),"-m",m.getPath(),"--host","127.0.0.1","--port",Integer.toString(PORT),"-c","4096","-ngl","99",
                // The GGUF's chat template only takes TranslateGemma's structured messages; Kotoba sends the formatted prompt itself.
                "--no-jinja","--chat-template","gemma")
            .redirectErrorStream(true).redirectOutput(new File(data,"translator.log")).start();
        long until=System.currentTimeMillis()+180_000;
        while(System.currentTimeMillis()<until){
            if(!server.isAlive()){server=null;throw new Exception("TranslateGemma didn't start (see translator.log in Kotoba's data folder).");}
            try{JSONObject h=new JSONObject(http("GET","/health",null,2000));if("ok".equals(h.optString("status")))return;}catch(Exception ignored){}
            Thread.sleep(500);
        }
        throw new Exception("TranslateGemma is taking too long to load.");
    }

    static String http(String method,String path,String body,int timeout) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+PORT+path).openConnection();
        c.setRequestMethod(method);c.setConnectTimeout(2000);c.setReadTimeout(timeout);
        if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));}
        int code=c.getResponseCode();
        byte[] out=(code<400?c.getInputStream():c.getErrorStream()).readAllBytes();
        if(code>=400)throw new Exception("Translation failed ("+code+")");
        return new String(out,StandardCharsets.UTF_8);
    }
}
