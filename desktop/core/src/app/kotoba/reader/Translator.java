package app.kotoba.reader;

import org.json.JSONObject;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Translation on the Mac without leaving Kotoba: a local model (Gemma 4 26B-A4B, a GGUF run by llama.cpp's
 * llama-server; it starts on the first request and stops after ten idle minutes), or Google's free web endpoint.
 * TranslateGemma 12B and Hy-MT2 7B were tried first; both translated idioms word for word (손이 맵네 → "spicier").
 */
final class Translator {
    static final String DEFAULT_LLM="/Volumes/T7/LLM Models/Gemma4-26B-A4B-QAT-Uncensored-HauhauCS-Balanced-MTP/Gemma4-26B-A4B-QAT-Uncensored-HauhauCS-Balanced-Q4_K_M.gguf";
    static final int PORT=18790;
    static final String[][] LANGS={{"auto","Detect"},{"en","English"},{"ko","Korean"},{"ja","Japanese"},{"zh-Hans","Chinese (Simplified)"},{"zh-Hant","Chinese (Traditional)"},
        {"yue","Cantonese"},{"th","Thai"},{"ru","Russian"},{"fr","French"},{"de","German"},{"es","Spanish"},{"pt","Portuguese"},{"it","Italian"},{"vi","Vietnamese"},{"id","Indonesian"}};
    final Store store;final File data;
    Process server;String serverModel;long lastUse;

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

    String llm(){return store.setting("translate_llm",DEFAULT_LLM);}
    static File llamaServer(){
        for(String p:new String[]{"/opt/homebrew/bin/llama-server","/usr/local/bin/llama-server"}){File f=new File(p);if(f.canExecute())return f;}
        return null;
    }
    JSONObject config(){
        boolean found=new File(llm()).isFile();
        String engine=store.setting("translate_engine","");
        if(!engine.equals("google")&&!engine.equals("google-web"))engine=found||engine.equals("llm")?"llm":"google";
        JSONObject langs=new JSONObject();for(String[] l:LANGS)langs.put(l[0],l[1]);
        return new JSONObject().put("engine",engine).put("from",store.setting("translate_from","auto")).put("to",store.setting("translate_to","en"))
            .put("llm",llm()).put("llmFound",found)
            .put("serverFound",llamaServer()!=null).put("running",server!=null&&server.isAlive())
            .put("languages",langs);
    }
    void set(JSONObject d){
        for(String k:new String[]{"engine","from","to","llm"})if(d.has(k))store.setSetting("translate_"+k,d.getString(k));
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

    /** engine: llm (the local model, with the rest of a comic page as context) or google (Google's free web endpoint). */
    synchronized JSONObject translate(String text,String from,String to,String engine,String context) throws Exception {
        if(text.trim().isEmpty())throw new Exception("Nothing to translate.");
        if(engine==null||engine.isEmpty())engine=config().getString("engine");
        if(engine.equals("google"))return google(text,from,to);
        return llm(text,from,to,context);
    }

    /** llama-server with this model (one model at a time: switching engines restarts it). */
    void ensureServer(String path,boolean chat) throws Exception {
        if(server!=null&&server.isAlive()&&path.equals(serverModel))return;
        if(server!=null){server.destroy();server.waitFor();server=null;}
        File m=new File(path);
        if(!m.isFile())throw new Exception("The translation model isn't at "+m.getPath()+". Plug in the drive, or finish downloading it.");
        File exe=llamaServer();
        if(exe==null)throw new Exception("llama-server isn't installed (brew install llama.cpp).");
        java.util.List<String> cmd=new java.util.ArrayList<>(java.util.List.of(exe.getPath(),"-m",m.getPath(),"--host","127.0.0.1","--port",Integer.toString(PORT),"-c","4096","-ngl","99"));
        if(chat)cmd.add("--jinja");
        server=new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(new File(data,"translator.log")).start();
        serverModel=path;
        long until=System.currentTimeMillis()+180_000;
        while(System.currentTimeMillis()<until){
            if(!server.isAlive()){server=null;throw new Exception("The translation model didn't start (see translator.log in Kotoba's data folder).");}
            try{JSONObject h=new JSONObject(http("GET","/health",null,2000));if("ok".equals(h.optString("status")))return;}catch(Exception ignored){}
            Thread.sleep(500);
        }
        throw new Exception("The translation model is taking too long to load.");
    }

    /**
     * A general model (Gemma 4 26B-A4B): slower to load and bigger, but it knows idioms and slang (생각보다 손이 맵네 →
     * "You've got a heavier hand than I thought", where translation models say "spicier"). Thinking is off.
     */
    JSONObject llm(String text,String from,String to,String context) throws Exception {
        if(to==null||to.isEmpty())to=store.setting("translate_to","en");
        if(from==null||from.isEmpty())from=store.setting("translate_from","auto");
        if(from.equals("auto"))from=detect(text);
        if(from.equals(to))to=from.equals("en")?"ko":"en";
        long t=System.currentTimeMillis();
        ensureServer(llm(),true);
        lastUse=System.currentTimeMillis();
        String prompt="Translate this "+name(from)+" text into natural "+name(to)+". It is usually a line of dialogue from a comic, show, book or game. "
            +"Translate idioms and slang by their meaning, not word for word. Output only the translation.";
        if(context!=null&&!context.trim().isEmpty()&&!context.trim().equals(text.trim()))
            prompt+="\n\nFor context only (don't translate it), the rest of the page:\n"+context.trim();
        prompt+="\n\nText to translate:\n"+text.trim();
        JSONObject body=new JSONObject().put("messages",new org.json.JSONArray().put(new JSONObject().put("role","user").put("content",prompt)))
            .put("max_tokens",Math.min(2048,text.length()*4+128)).put("temperature",0.2)
            .put("chat_template_kwargs",new JSONObject().put("enable_thinking",false));
        JSONObject r=new JSONObject(http("POST","/v1/chat/completions",body.toString(),300_000));
        lastUse=System.currentTimeMillis();
        String out=r.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content","").trim();
        if(out.length()>1&&out.startsWith("\"")&&out.endsWith("\""))out=out.substring(1,out.length()-1);
        return new JSONObject().put("text",out).put("from",from).put("to",to).put("ms",System.currentTimeMillis()-t).put("engine","llm");
    }

    /** Google Translate's free web endpoint (the one its site widget uses): no key, unofficial, and the text goes to Google. */
    static JSONObject google(String text,String from,String to) throws Exception {
        String sl=from==null||from.isEmpty()||from.equals("auto")?"auto":googleCode(from),tl=googleCode(to==null||to.isEmpty()?"en":to);
        long t=System.currentTimeMillis();
        String url="https://translate.googleapis.com/translate_a/single?client=gtx&dt=t&sl="+sl+"&tl="+tl+"&q="+java.net.URLEncoder.encode(text,StandardCharsets.UTF_8);
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(8000);c.setReadTimeout(15000);c.setRequestProperty("User-Agent","Mozilla/5.0");
        if(c.getResponseCode()!=200)throw new Exception("Google Translate didn't answer ("+c.getResponseCode()+").");
        org.json.JSONArray a=new org.json.JSONArray(new String(c.getInputStream().readAllBytes(),StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder();
        org.json.JSONArray parts=a.getJSONArray(0);
        for(int i=0;i<parts.length();i++)if(!parts.isNull(i))out.append(parts.getJSONArray(i).optString(0,""));
        String detected=a.length()>2&&!a.isNull(2)?a.optString(2,sl):sl;
        return new JSONObject().put("text",out.toString().trim()).put("from",detected.equals("zh-CN")?"zh-Hans":detected.equals("zh-TW")?"zh-Hant":detected).put("to",to).put("ms",System.currentTimeMillis()-t).put("engine","google");
    }
    static String googleCode(String c){return c.equals("zh-Hans")?"zh-CN":c.equals("zh-Hant")?"zh-TW":c;}

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
