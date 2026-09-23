package app.kotoba.reader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Random-access ZIP reader over a FileChannel (EPUB, CBZ). Works with Android document URIs,
 * so large comic archives are read in place instead of being copied. Supports stored/deflated entries and ZIP64.
 */
public class ZipSource implements AutoCloseable {
    public static final class Entry {
        public final String name;
        public final int method;
        public final long compressed,size,localOffset;
        Entry(String name,int method,long compressed,long size,long localOffset){this.name=name;this.method=method;this.compressed=compressed;this.size=size;this.localOffset=localOffset;}
    }
    final FileChannel channel;
    public final Map<String,Entry> entries=new LinkedHashMap<>();

    public ZipSource(FileChannel channel) throws IOException {
        this.channel=channel;
        long size=channel.size();
        int tail=(int)Math.min(size,65557);
        ByteBuffer end=read(size-tail,tail);
        int eocd=-1;
        for(int i=tail-22;i>=0;i--)if(end.getInt(i)==0x06054b50){eocd=i;break;}
        if(eocd<0)throw new IOException("Not a ZIP file");
        long count=end.getShort(eocd+10)&0xffff;
        long cdSize=end.getInt(eocd+12)&0xffffffffL;
        long cdOffset=end.getInt(eocd+16)&0xffffffffL;
        if(cdOffset==0xffffffffL||count==0xffff){
            // ZIP64 end of central directory locator sits just before the EOCD record.
            int loc=eocd-20;
            if(loc>=0&&end.getInt(loc)==0x07064b50){
                long z64=end.getLong(loc+8);
                ByteBuffer z=read(z64,56);
                if(z.getInt(0)==0x06064b50){count=z.getLong(32);cdSize=z.getLong(40);cdOffset=z.getLong(48);}
            }
        }
        ByteBuffer cd=read(cdOffset,(int)cdSize);
        int p=0;
        for(long i=0;i<count&&p+46<=cd.limit();i++){
            if(cd.getInt(p)!=0x02014b50)break;
            int flags=cd.getShort(p+8)&0xffff,method=cd.getShort(p+10)&0xffff;
            long comp=cd.getInt(p+20)&0xffffffffL,unc=cd.getInt(p+24)&0xffffffffL;
            int nameLen=cd.getShort(p+28)&0xffff,extraLen=cd.getShort(p+30)&0xffff,commentLen=cd.getShort(p+32)&0xffff;
            long local=cd.getInt(p+42)&0xffffffffL;
            byte[] nameBytes=new byte[nameLen];
            for(int k=0;k<nameLen;k++)nameBytes[k]=cd.get(p+46+k);
            // ZIP64 extra field carries the real sizes/offset when the 32-bit fields are saturated.
            int e=p+46+nameLen,eEnd=e+extraLen;
            while(e+4<=eEnd){
                int id=cd.getShort(e)&0xffff,len=cd.getShort(e+2)&0xffff;int q=e+4;
                if(id==1){
                    if(unc==0xffffffffL&&q+8<=e+4+len){unc=cd.getLong(q);q+=8;}
                    if(comp==0xffffffffL&&q+8<=e+4+len){comp=cd.getLong(q);q+=8;}
                    if(local==0xffffffffL&&q+8<=e+4+len){local=cd.getLong(q);}
                }
                e+=4+len;
            }
            String name=decodeName(nameBytes,(flags&0x800)!=0);
            entries.put(name,new Entry(name,method,comp,unc,local));
            p+=46+nameLen+extraLen+commentLen;
        }
    }

    /** UTF-8 when flagged or valid; otherwise the legacy encodings common for Japanese/Korean archives. */
    static String decodeName(byte[] b,boolean utf8){
        if(utf8)return new String(b,StandardCharsets.UTF_8);
        for(String cs:new String[]{"UTF-8","Shift_JIS","EUC-KR","GB18030"}){
            try{return Charset.forName(cs).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();}
            catch(CharacterCodingException|RuntimeException ignored){}
        }
        return new String(b,StandardCharsets.ISO_8859_1);
    }

    ByteBuffer read(long position,int length) throws IOException {
        ByteBuffer b=ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        while(b.hasRemaining()){int n=channel.read(b,position+b.position());if(n<0)throw new IOException("Unexpected end of ZIP");}
        b.flip();
        return b;
    }

    public Entry find(String name){
        Entry e=entries.get(name);
        if(e!=null)return e;
        for(Entry x:entries.values())if(x.name.equalsIgnoreCase(name))return x;
        return null;
    }

    public byte[] bytes(String name) throws IOException {
        Entry e=find(name);
        if(e==null)throw new IOException("Missing in archive: "+name);
        return bytes(e);
    }

    public byte[] bytes(Entry e) throws IOException {
        if(e.size>256L*1024*1024)throw new IOException("Entry too large: "+e.name);
        ByteBuffer local=read(e.localOffset,30);
        if(local.getInt(0)!=0x04034b50)throw new IOException("Bad local header: "+e.name);
        long data=e.localOffset+30+(local.getShort(26)&0xffff)+(local.getShort(28)&0xffff);
        byte[] raw=new byte[(int)e.compressed];
        ByteBuffer rb=ByteBuffer.wrap(raw);
        while(rb.hasRemaining()){int n=channel.read(rb,data+rb.position());if(n<0)throw new IOException("Truncated entry");}
        if(e.method==0)return raw;
        if(e.method!=8)throw new IOException("Unsupported compression "+e.method+" in "+e.name);
        Inflater inflater=new Inflater(true);
        try{
            inflater.setInput(raw);
            byte[] out=new byte[(int)e.size];int total=0;
            while(total<out.length&&!inflater.finished()){
                int n=inflater.inflate(out,total,out.length-total);
                if(n==0&&(inflater.needsInput()||inflater.needsDictionary()))break;
                total+=n;
            }
            return out;
        }catch(DataFormatException ex){throw new IOException("Corrupt entry "+e.name,ex);}
        finally{inflater.end();}
    }

    /** Streams an entry without holding it in memory (Yomitan term banks are 75 MB of JSON each). */
    public java.io.InputStream stream(Entry e) throws IOException {
        ByteBuffer local=read(e.localOffset,30);
        if(local.getInt(0)!=0x04034b50)throw new IOException("Bad local header: "+e.name);
        final long start=e.localOffset+30+(local.getShort(26)&0xffff)+(local.getShort(28)&0xffff);
        final long end=start+e.compressed;
        java.io.InputStream raw=new java.io.InputStream(){
            long pos=start;boolean pad=false;
            @Override public int read() throws IOException {byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&0xff;}
            @Override public int read(byte[] b,int off,int len) throws IOException {
                if(pos>=end){
                    // A raw inflater may ask for one byte past the data; give it a dummy byte once.
                    if(e.method==8&&!pad){pad=true;b[off]=0;return 1;}
                    return -1;
                }
                ByteBuffer buf=ByteBuffer.wrap(b,off,(int)Math.min(len,end-pos));
                int n=channel.read(buf,pos);
                if(n<0)throw new IOException("Truncated entry "+e.name);
                pos+=n;return n;
            }
        };
        if(e.method==0)return new java.io.BufferedInputStream(raw,1<<16);
        if(e.method!=8)throw new IOException("Unsupported compression "+e.method+" in "+e.name);
        return new java.util.zip.InflaterInputStream(new java.io.BufferedInputStream(raw,1<<16),new Inflater(true),1<<16){
            @Override public void close() throws IOException {super.close();inf.end();}
        };
    }

    public List<String> names(){return new ArrayList<>(entries.keySet());}

    @Override public void close() throws IOException {channel.close();}
}
