package app.kotoba.reader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Listening sets (Glossika-style: hear a line, repeat, shadow): each is a folder under listening/ with a set.json
 * (groups such as characters, each with lines: title, text, translation, audio path, duration) and its audio files.
 * tools/build_genshin_voice.py makes them; copy a set's folder to the phone as is.
 */
public final class Listening {
    final File root;
    public Listening(File root){this.root=root;}

    static boolean validId(String id){return id!=null&&id.matches("[A-Za-z0-9._-]{1,80}");}

    /** The sets there are, with their size. */
    public JSONArray sets() throws Exception {
        JSONArray out=new JSONArray();
        File[] dirs=root.listFiles(File::isDirectory);
        if(dirs==null)return out;
        java.util.Arrays.sort(dirs);
        for(File dir:dirs){
            File f=new File(dir,"set.json");
            if(!f.isFile()||!validId(dir.getName()))continue;
            try{
                JSONObject s=new JSONObject(Files.readString(f.toPath(),StandardCharsets.UTF_8));
                JSONArray groups=s.optJSONArray("groups");int lines=0;String cover="";
                if(groups!=null)for(int i=0;i<groups.length();i++){
                    JSONObject g=groups.getJSONObject(i);
                    JSONArray items=g.optJSONArray("items");lines+=items==null?0:items.length();
                    if(cover.isEmpty())cover=g.optString("icon","");
                }
                out.put(new JSONObject().put("id",dir.getName()).put("title",s.optString("title",dir.getName())).put("subtitle",s.optString("subtitle",""))
                    .put("lang",s.optString("lang","")).put("groups",groups==null?0:groups.length()).put("lines",lines).put("cover",cover));
            }catch(Exception ignored){}
        }
        return out;
    }

    /** One set, whole (its lines, grouped). */
    public JSONObject set(String id) throws Exception {
        if(!validId(id))throw new Exception("No such set.");
        File f=new File(new File(root,id),"set.json");
        if(!f.isFile())throw new Exception("This set isn’t on this device.");
        return new JSONObject(Files.readString(f.toPath(),StandardCharsets.UTF_8)).put("id",id);
    }

    /** A file inside a set (audio, icons), or null. */
    public File file(String path) throws Exception {
        String p=java.net.URLDecoder.decode(path,"UTF-8");
        File f=new File(root,p).getCanonicalFile();
        if(!f.getPath().startsWith(root.getCanonicalPath()+File.separator)||!f.isFile())return null;
        return f;
    }
}
