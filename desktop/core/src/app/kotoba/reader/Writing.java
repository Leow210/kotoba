package app.kotoba.reader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Mac writing editor: documents are ordinary HTML files in ~/Documents/Kotoba (furigana as <ruby>, so they open
 * in any browser), and the grammar check asks the local Gemma model used for translation.
 */
final class Writing {
    final File dir=new File(System.getProperty("user.home"),"Documents/Kotoba");
    final Translator translator;

    Writing(Translator translator){this.translator=translator;}

    JSONArray list() throws Exception {
        dir.mkdirs();
        File[] files=dir.listFiles((d,n)->n.endsWith(".html"));
        JSONArray out=new JSONArray();
        if(files==null)return out;
        java.util.Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
        for(File f:files){
            JSONObject doc=read(f);
            String text=doc.getString("html").replaceAll("<rt>.*?</rt>","").replaceAll("<[^>]+>"," ").replace("&nbsp;"," ").replaceAll("\\s+"," ").trim();
            out.put(new JSONObject().put("id",f.getName()).put("title",doc.getString("title")).put("updated",f.lastModified())
                .put("preview",unescape(text.length()>140?text.substring(0,140):text)).put("vertical",doc.getBoolean("vertical")));
        }
        return out;
    }

    JSONObject get(String id) throws Exception {return read(file(id)).put("id",id);}

    /** Saves a document; a new title renames its file (when that name is free). */
    JSONObject save(JSONObject d) throws Exception {
        dir.mkdirs();
        String title=d.optString("title","").trim();if(title.isEmpty())title="Untitled";
        String id=d.optString("id","");
        File f=id.isEmpty()?null:file(id);
        File wanted=new File(dir,safe(title)+".html");
        if(f==null||!f.exists()){
            f=wanted;for(int i=2;f.exists();i++)f=new File(dir,safe(title)+" "+i+".html");
        }else if(!f.getName().equals(wanted.getName())&&!wanted.exists()){
            if(f.renameTo(wanted))f=wanted;
        }
        boolean vertical=d.optBoolean("vertical",false);
        String html="<!doctype html>\n<html lang=\""+esc(d.optString("lang","ja"))+"\"><head><meta charset=\"utf-8\"><title>"+esc(title)+"</title>"
            +"<meta name=\"kotoba-vertical\" content=\""+(vertical?"1":"0")+"\">"
            +"<style>body{font:18px/1.9 'Hiragino Mincho ProN',serif;max-width:40em;margin:3em auto;padding:0 1em"+(vertical?";writing-mode:vertical-rl;max-height:36em":"")+"}rt{font-size:.5em}</style></head>\n<body>\n"
            +d.optString("html","")+"\n</body></html>\n";
        Files.writeString(f.toPath(),html);
        return new JSONObject().put("id",f.getName()).put("title",title).put("updated",f.lastModified());
    }

    /** Deleted documents go to Kotoba's own trash folder beside them, not away for good. */
    void delete(String id) throws Exception {
        File f=file(id),trash=new File(dir,".trash");trash.mkdirs();
        File to=new File(trash,f.getName());
        for(int i=2;to.exists();i++)to=new File(trash,i+" "+f.getName());
        if(!f.renameTo(to))throw new Exception("Couldn't delete "+f.getName());
    }

    File file(String id) throws Exception {
        if(id.contains("/")||id.contains("\\")||id.startsWith("."))throw new Exception("Bad document name");
        return new File(dir,id);
    }

    static JSONObject read(File f) throws Exception {
        String s=Files.readString(f.toPath(),StandardCharsets.UTF_8);
        Matcher t=Pattern.compile("(?is)<title>(.*?)</title>").matcher(s);
        Matcher b=Pattern.compile("(?is)<body[^>]*>\\n?(.*?)\\n?</body>").matcher(s);
        Matcher l=Pattern.compile("(?is)<html[^>]*lang=\"([^\"]*)\"").matcher(s);
        String name=f.getName().replaceAll("\\.html$","");
        return new JSONObject().put("title",t.find()?unescape(t.group(1)):name).put("html",b.find()?b.group(1):s)
            .put("vertical",s.contains("name=\"kotoba-vertical\" content=\"1\"")).put("lang",l.find()?l.group(1):"ja");
    }

    static String safe(String title){
        String s=title.replaceAll("[/\\\\:*?\"<>|\\p{Cntrl}]","_").replaceAll("^\\.+","").trim();
        if(s.length()>80)s=s.substring(0,80).trim();
        return s.isEmpty()?"Untitled":s;
    }
    static String esc(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    static String unescape(String s){return s.replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&#39;","'").replace("&amp;","&");}

    // ---------- grammar ----------

    /**
     * Grammar, particles, spelling and unnatural phrasing, one paragraph block at a time (so long documents fit in
     * the context). Each issue quotes the exact text, so the editor can find and replace it.
     */
    JSONObject grammar(String text,String lang) throws Exception {
        if(text.trim().isEmpty())throw new Exception("Nothing to check.");
        if(lang==null||lang.isEmpty()||lang.equals("auto"))lang=Translator.detect(text);
        String language=Translator.name(lang).replaceAll(" \\(.*\\)","");
        JSONArray issues=new JSONArray();
        long t=System.currentTimeMillis();
        for(String chunk:chunks(text,1200)){
            String prompt="You are a careful "+language+" editor helping a learner. Check this "+language+" text for grammar mistakes, wrong particles, "
                +"wrong conjugations, misspellings or wrong kanji, wrong word choice, and phrasing a native speaker would find unnatural. "
                +"Don't change the style or rewrite correct sentences, and don't report punctuation preferences.\n"
                +"Answer with JSON only: {\"issues\":[{\"original\":\"the exact wrong part, copied character for character from the text (as short as possible, but unique)\","
                +"\"suggestion\":\"the corrected replacement for that part\",\"type\":\"grammar|particle|spelling|word choice|naturalness\","
                +"\"explanation\":\"one or two short sentences in English\"}]}. If the text is fine, answer {\"issues\":[]}.\n\nText:\n"+chunk;
            JSONObject body=new JSONObject().put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content",prompt)))
                .put("max_tokens",2048).put("temperature",0.1).put("response_format",new JSONObject().put("type","json_object"))
                .put("chat_template_kwargs",new JSONObject().put("enable_thinking",false));
            String content=translator.chat(body);
            JSONObject r;
            try{r=new JSONObject(content.substring(content.indexOf('{'),content.lastIndexOf('}')+1));}catch(Exception e){continue;}
            JSONArray found=r.optJSONArray("issues");if(found==null)continue;
            for(int i=0;i<found.length();i++){
                JSONObject x=found.optJSONObject(i);if(x==null)continue;
                String original=x.optString("original",""),suggestion=x.optString("suggestion","");
                // Only issues the editor can point at, and that change something.
                if(original.isEmpty()||!chunk.contains(original)||original.equals(suggestion))continue;
                issues.put(x.put("context",around(chunk,original)));
            }
        }
        return new JSONObject().put("issues",issues).put("lang",lang).put("ms",System.currentTimeMillis()-t);
    }

    static java.util.List<String> chunks(String text,int max){
        java.util.List<String> out=new java.util.ArrayList<>();
        StringBuilder b=new StringBuilder();
        for(String p:text.split("(?<=\\n)")){
            if(b.length()>0&&b.length()+p.length()>max){out.add(b.toString());b.setLength(0);}
            while(p.length()>max){
                // A very long paragraph: cut after a sentence end near the limit.
                int cut=Math.max(p.lastIndexOf('。',max),Math.max(p.lastIndexOf(". ",max),p.lastIndexOf('.',max)));
                if(cut<max/2)cut=max;else cut++;
                out.add(p.substring(0,cut));p=p.substring(cut);
            }
            b.append(p);
        }
        if(b.toString().trim().length()>0)out.add(b.toString());
        return out;
    }
    static String around(String text,String part){
        int i=text.indexOf(part);
        int a=Math.max(0,i-20),b=Math.min(text.length(),i+part.length()+20);
        return text.substring(a,b).replace('\n',' ');
    }
}
