package app.kotoba.reader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Reads Mihon/Tachiyomi backups (.tachibk / .proto.gz: gzip + protobuf) without a protobuf library.
 * Only the library metadata is read: titles, categories, chapters with read state. Backups contain no images.
 */
public final class MihonBackup {
    public static final class Chapter { public String url="",name="",scanlator="";public boolean read,bookmark;public long lastPage;public float number=-1; }
    public static final class Manga {
        public long source;public String url="",title="",author="",thumbnail="";public boolean favorite=true;
        public final List<Chapter> chapters=new ArrayList<>();public final List<Long> categories=new ArrayList<>();
    }
    public static final class Category { public String name="";public long order; }
    public final List<Manga> manga=new ArrayList<>();
    public final List<Category> categories=new ArrayList<>();
    public final java.util.Map<Long,String> sources=new java.util.HashMap<>();

    public static MihonBackup parse(byte[] bytes) throws IOException {
        byte[] raw=bytes;
        if(bytes.length>2&&(bytes[0]&0xff)==0x1f&&(bytes[1]&0xff)==0x8b){
            try(GZIPInputStream in=new GZIPInputStream(new ByteArrayInputStream(bytes));ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buf=new byte[65536];int n;while((n=in.read(buf))>0)out.write(buf,0,n);raw=out.toByteArray();
            }
        }
        MihonBackup b=new MihonBackup();
        Reader r=new Reader(raw,0,raw.length);
        while(r.more()){
            int tag=r.tag();int field=tag>>>3,wire=tag&7;
            if(field==1&&wire==2)b.manga.add(manga(r.sub()));
            else if(field==2&&wire==2)b.categories.add(category(r.sub()));
            else if(field==101&&wire==2){Reader s=r.sub();String name="";long id=0;while(s.more()){int t=s.tag();if(t>>>3==1&&(t&7)==2)name=s.string();else if(t>>>3==2&&(t&7)==0)id=s.varint();else s.skip(t&7);}b.sources.put(id,name);}
            else r.skip(wire);
        }
        if(b.manga.isEmpty()&&b.categories.isEmpty())throw new IOException("This doesn’t look like a Mihon backup.");
        return b;
    }

    static Manga manga(Reader r) throws IOException {
        Manga m=new Manga();
        while(r.more()){
            int tag=r.tag();int f=tag>>>3,w=tag&7;
            switch(f){
                case 1:if(w==0){m.source=r.varint();continue;}break;
                case 2:if(w==2){m.url=r.string();continue;}break;
                case 3:if(w==2){m.title=r.string();continue;}break;
                case 5:if(w==2){m.author=r.string();continue;}break;
                case 9:if(w==2){m.thumbnail=r.string();continue;}break;
                case 16:if(w==2){m.chapters.add(chapter(r.sub()));continue;}break;
                case 17:
                    if(w==0){m.categories.add(r.varint());continue;}
                    if(w==2){Reader p=r.sub();while(p.more())m.categories.add(p.varint());continue;}
                    break;
                case 100:if(w==0){m.favorite=r.varint()!=0;continue;}break;
            }
            r.skip(w);
        }
        return m;
    }
    static Chapter chapter(Reader r) throws IOException {
        Chapter c=new Chapter();
        while(r.more()){
            int tag=r.tag();int f=tag>>>3,w=tag&7;
            if(f==1&&w==2)c.url=r.string();
            else if(f==2&&w==2)c.name=r.string();
            else if(f==3&&w==2)c.scanlator=r.string();
            else if(f==4&&w==0)c.read=r.varint()!=0;
            else if(f==5&&w==0)c.bookmark=r.varint()!=0;
            else if(f==6&&w==0)c.lastPage=r.varint();
            else if(f==9&&w==5)c.number=Float.intBitsToFloat(r.fixed32());
            else r.skip(w);
        }
        return c;
    }
    static Category category(Reader r) throws IOException {
        Category c=new Category();
        while(r.more()){int tag=r.tag();if(tag>>>3==1&&(tag&7)==2)c.name=r.string();else if(tag>>>3==2&&(tag&7)==0)c.order=r.varint();else r.skip(tag&7);}
        return c;
    }

    /** Minimal protobuf wire-format reader. */
    static final class Reader {
        final byte[] b;int p;final int end;
        Reader(byte[] b,int start,int end){this.b=b;this.p=start;this.end=end;}
        boolean more(){return p<end;}
        long varint() throws IOException {long v=0;int shift=0;while(true){if(p>=end)throw new IOException("Truncated backup");int x=b[p++]&0xff;v|=(long)(x&0x7f)<<shift;if((x&0x80)==0)return v;shift+=7;if(shift>63)throw new IOException("Bad varint");}}
        int tag() throws IOException {return (int)varint();}
        int fixed32() throws IOException {if(p+4>end)throw new IOException("Truncated backup");int v=(b[p]&0xff)|((b[p+1]&0xff)<<8)|((b[p+2]&0xff)<<16)|((b[p+3]&0xff)<<24);p+=4;return v;}
        Reader sub() throws IOException {int len=(int)varint();if(len<0||p+len>end)throw new IOException("Truncated backup");Reader r=new Reader(b,p,p+len);p+=len;return r;}
        String string() throws IOException {Reader s=sub();return new String(b,s.p,s.end-s.p,StandardCharsets.UTF_8);}
        void skip(int wire) throws IOException {
            switch(wire){case 0:varint();break;case 1:p+=8;break;case 2:sub();break;case 5:p+=4;break;default:throw new IOException("Unsupported protobuf wire type "+wire);}
            if(p>end)throw new IOException("Truncated backup");
        }
    }
}
