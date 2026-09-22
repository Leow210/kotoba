package app.kotoba.reader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

/**
 * Reader for MDict .mdx (entries) and .mdd (resources) files, versions 1.x and 2.x.
 * Supports zlib, LZO and uncompressed blocks, and key-index encryption (Encrypted="2").
 * Record encryption (Encrypted="1", registration-code dictionaries) is not supported.
 * Pure Java so the importer can be tested on a desktop JVM.
 */
public class MdictFile implements AutoCloseable {
    public final FileChannel channel;
    public final boolean mdd;
    public final Map<String,String> header=new HashMap<>();
    public final Charset charset;
    final boolean v2;
    final int encrypted;
    long keyBlocksStart, keyBlockCount, entryCount, keyBlocksSize, keyInfoSize, keyInfoDecompressed;
    long recordBlocksStart;
    public long[] recordCompressed, recordDecompressed, recordFileOffset, recordStart;
    public long recordTotal;
    long[] keyCompressed, keyDecompressed;

    public interface KeyVisitor { void key(String key,long recordOffset) throws Exception; }

    public MdictFile(FileChannel channel,boolean mdd) throws IOException {
        this.channel=channel;this.mdd=mdd;
        int headerLength=readBuffer(0,4).getInt();
        byte[] headerBytes=read(4,headerLength);
        String headerText=new String(headerBytes,StandardCharsets.UTF_16LE).replace("\u0000","").trim();
        Matcher m=Pattern.compile("(\\w+)=\"([^\"]*)\"").matcher(headerText);
        while(m.find())header.put(m.group(1),unescape(m.group(2)));
        double version;
        try{version=Double.parseDouble(header.getOrDefault("GeneratedByEngineVersion","2.0"));}catch(NumberFormatException e){version=2.0;}
        v2=version>=2.0;
        if(version>=3.0)throw new IOException("MDict 3.0 files are not supported yet");
        String enc=header.getOrDefault("Encrypted","0");
        int e=0;
        if(enc.equalsIgnoreCase("yes"))e=1;else if(!enc.equalsIgnoreCase("no")&&!enc.isEmpty()){try{e=Integer.parseInt(enc);}catch(NumberFormatException ignored){}}
        encrypted=e;
        if((encrypted&1)!=0)throw new IOException("This dictionary uses registration-code encryption and cannot be opened");
        String encoding=header.getOrDefault("Encoding","");
        if(mdd)charset=StandardCharsets.UTF_16LE;
        else if(encoding.isEmpty()||encoding.equalsIgnoreCase("UTF-8"))charset=StandardCharsets.UTF_8;
        else if(encoding.toUpperCase().startsWith("UTF-16"))charset=StandardCharsets.UTF_16LE;
        else if(encoding.equalsIgnoreCase("GBK")||encoding.equalsIgnoreCase("GB2312"))charset=Charset.forName("GB18030");
        else charset=Charset.forName(encoding);
        long p=4+headerLength+4;
        int numberSize=v2?8:4;
        ByteBuffer b=readBuffer(p,v2?40:16);
        keyBlockCount=number(b,v2);entryCount=number(b,v2);
        if(v2)keyInfoDecompressed=number(b,true);
        keyInfoSize=number(b,v2);keyBlocksSize=number(b,v2);
        p+=v2?40+4:16;
        byte[] info=read(p,(int)keyInfoSize);
        p+=keyInfoSize;
        if(v2){
            if((encrypted&2)!=0)info=decryptKeyInfo(info);
            info=decompress(info,(int)keyInfoDecompressed);
        }
        parseKeyInfo(info);
        keyBlocksStart=p;
        p+=keyBlocksSize;
        ByteBuffer r=readBuffer(p,numberSize*4);
        long recordBlockCount=number(r,v2);number(r,v2);long recordInfoSize=number(r,v2);number(r,v2);
        p+=numberSize*4;
        ByteBuffer ri=readBuffer(p,(int)recordInfoSize);
        p+=recordInfoSize;
        int n=(int)recordBlockCount;
        recordCompressed=new long[n];recordDecompressed=new long[n];recordFileOffset=new long[n];recordStart=new long[n];
        long file=p,start=0;
        for(int i=0;i<n;i++){
            recordCompressed[i]=number(ri,v2);recordDecompressed[i]=number(ri,v2);
            recordFileOffset[i]=file;recordStart[i]=start;
            file+=recordCompressed[i];start+=recordDecompressed[i];
        }
        recordTotal=start;
    }

    static String unescape(String v){return v.replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&#x27;","'").replace("&#39;","'").replace("&amp;","&");}

    public String title(){return header.getOrDefault("Title","");}
    public String description(){return header.getOrDefault("Description","");}

    static long number(ByteBuffer b,boolean v2){return v2?b.getLong():b.getInt()&0xffffffffL;}

    ByteBuffer readBuffer(long position,int length) throws IOException {
        return ByteBuffer.wrap(read(position,length)).order(ByteOrder.BIG_ENDIAN);
    }

    public byte[] read(long position,int length) throws IOException {
        ByteBuffer buffer=ByteBuffer.allocate(length);
        while(buffer.hasRemaining()){
            int n=channel.read(buffer,position+buffer.position());
            if(n<0)throw new IOException("Unexpected end of dictionary file");
        }
        return buffer.array();
    }

    int textUnit(){return charset==StandardCharsets.UTF_16LE?2:1;}

    void parseKeyInfo(byte[] info){
        ByteBuffer b=ByteBuffer.wrap(info).order(ByteOrder.BIG_ENDIAN);
        int n=(int)keyBlockCount,unit=textUnit();
        keyCompressed=new long[n];keyDecompressed=new long[n];
        for(int i=0;i<n;i++){
            number(b,v2);
            for(int k=0;k<2;k++){
                int size=v2?b.getShort()&0xffff:b.get()&0xff;
                b.position(b.position()+size*unit+(v2?unit:0));
            }
            keyCompressed[i]=number(b,v2);keyDecompressed[i]=number(b,v2);
        }
    }

    /** Visits every key in file order with the offset of its record in the decompressed record stream. */
    public void keys(KeyVisitor visitor) throws Exception {
        long p=keyBlocksStart;int unit=textUnit();
        for(int i=0;i<keyCompressed.length;i++){
            byte[] block=decompress(read(p,(int)keyCompressed[i]),(int)keyDecompressed[i]);
            p+=keyCompressed[i];
            int pos=0;
            while(pos<block.length){
                long offset=0;
                if(v2){for(int k=0;k<8;k++)offset=(offset<<8)|(block[pos+k]&0xff);pos+=8;}
                else{for(int k=0;k<4;k++)offset=(offset<<8)|(block[pos+k]&0xff);pos+=4;}
                int end=pos;
                if(unit==1)while(end<block.length&&block[end]!=0)end++;
                else while(end+1<block.length&&(block[end]!=0||block[end+1]!=0))end+=2;
                visitor.key(new String(block,pos,end-pos,charset),offset);
                pos=end+unit;
            }
        }
    }

    public byte[] recordBlock(int index) throws IOException {
        return decompress(read(recordFileOffset[index],(int)recordCompressed[index]),(int)recordDecompressed[index]);
    }

    public int blockFor(long offset){
        int lo=0,hi=recordStart.length-1;
        while(lo<hi){int mid=(lo+hi+1)>>>1;if(recordStart[mid]<=offset)lo=mid;else hi=mid-1;}
        return lo;
    }

    /** Record bytes; handles records that span record blocks. */
    public byte[] record(long offset,int length,BlockCache cache) throws IOException {
        int block=blockFor(offset);
        ByteArrayOutputStream out=new ByteArrayOutputStream(length);
        long position=offset;int remaining=length;
        while(remaining>0&&block<recordStart.length){
            byte[] data=cache==null?recordBlock(block):cache.get(this,block);
            int from=(int)(position-recordStart[block]);
            int n=Math.min(remaining,data.length-from);
            out.write(data,from,n);remaining-=n;position+=n;block++;
        }
        return out.toByteArray();
    }

    public String text(byte[] bytes){
        int end=bytes.length,unit=textUnit();
        while(end>=unit&&bytes[end-1]==0&&(unit==1||bytes[end-2]==0))end-=unit;
        return new String(bytes,0,end,charset);
    }

    public static class BlockCache {
        final int capacity;
        final java.util.LinkedHashMap<String,byte[]> map;
        public BlockCache(int capacity){
            this.capacity=capacity;
            map=new java.util.LinkedHashMap<String,byte[]>(16,0.75f,true){
                @Override protected boolean removeEldestEntry(Map.Entry<String,byte[]> e){return size()>BlockCache.this.capacity;}
            };
        }
        public synchronized byte[] get(MdictFile file,int block) throws IOException {
            String key=System.identityHashCode(file)+":"+block;
            byte[] value=map.get(key);
            if(value==null){value=file.recordBlock(block);map.put(key,value);}
            return value;
        }
    }

    static byte[] decompress(byte[] block,int expected) throws IOException {
        if(block.length<8)throw new IOException("Truncated block");
        int type=block[0]&0xff;
        switch(type){
            case 0:{byte[] out=new byte[block.length-8];System.arraycopy(block,8,out,0,out.length);return out;}
            case 1:return Lzo.decompress(block,8,block.length-8,expected);
            case 2:{
                Inflater inflater=new Inflater();
                inflater.setInput(block,8,block.length-8);
                byte[] out=new byte[Math.max(expected,16)];int total=0;
                try{
                    while(!inflater.finished()){
                        if(total==out.length)out=java.util.Arrays.copyOf(out,out.length*2);
                        int n=inflater.inflate(out,total,out.length-total);
                        if(n==0&&(inflater.needsInput()||inflater.needsDictionary()))break;
                        total+=n;
                    }
                }catch(java.util.zip.DataFormatException e){throw new IOException("Corrupt compressed block",e);}
                finally{inflater.end();}
                return total==out.length?out:java.util.Arrays.copyOf(out,total);
            }
            default:throw new IOException("Unknown block compression "+type);
        }
    }

    static byte[] decryptKeyInfo(byte[] data){
        byte[] keySource=new byte[8];
        System.arraycopy(data,4,keySource,0,4);
        keySource[4]=(byte)0x95;keySource[5]=(byte)0x36;keySource[6]=0;keySource[7]=0;
        byte[] key=Ripemd128.digest(keySource);
        byte[] out=data.clone();
        int previous=0x36;
        for(int i=8;i<out.length;i++){
            int b=data[i]&0xff;
            int t=((b>>4)|(b<<4))&0xff;
            t=t^previous^((i-8)&0xff)^(key[(i-8)%key.length]&0xff);
            previous=b;out[i]=(byte)t;
        }
        return out;
    }

    @Override public void close() throws IOException {channel.close();}
}
