package android.content;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Desktop stand-in for ContentValues: column → value, in insertion order. */
public class ContentValues {
    final LinkedHashMap<String,Object> values=new LinkedHashMap<>();
    public void put(String k,String v){values.put(k,v);}
    public void put(String k,Long v){values.put(k,v);}
    public void put(String k,Integer v){values.put(k,v);}
    public void put(String k,Double v){values.put(k,v);}
    public void put(String k,Boolean v){values.put(k,v);}
    public void put(String k,byte[] v){values.put(k,v);}
    public void putNull(String k){values.put(k,null);}
    public int size(){return values.size();}
    public boolean containsKey(String k){return values.containsKey(k);}
    public Object get(String k){return values.get(k);}
    public String getAsString(String k){Object v=values.get(k);return v==null?null:v.toString();}
    public Long getAsLong(String k){Object v=values.get(k);return v instanceof Number?((Number)v).longValue():v==null?null:Long.valueOf(v.toString());}
    public Integer getAsInteger(String k){Object v=values.get(k);return v instanceof Number?((Number)v).intValue():v==null?null:Integer.valueOf(v.toString());}
    public Set<Map.Entry<String,Object>> valueSet(){return values.entrySet();}
    public Set<String> keySet(){return values.keySet();}
}
