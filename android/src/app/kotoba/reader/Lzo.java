package app.kotoba.reader;

import java.io.IOException;

/** LZO1X decompressor for older MDict files. */
final class Lzo {
    static byte[] decompress(byte[] in,int start,int length,int expected) throws IOException {
        byte[] out=new byte[Math.max(expected,1)];
        int ip=start,end=start+length,op=0;
        try{
            int t=in[ip]&0xff;
            boolean firstLiteral=false,initialShort=false;
            if(t>17){
                ip++;t-=17;
                for(int i=0;i<t;i++)out[op++]=in[ip++];
                firstLiteral=true;initialShort=t<4;
            }
            outer:
            while(true){
                if(!firstLiteral){
                    t=in[ip++]&0xff;
                    if(t<16){
                        if(t==0){while(in[ip]==0){t+=255;ip++;}t+=15+(in[ip++]&0xff);}
                        t+=3;
                        for(int i=0;i<t;i++)out[op++]=in[ip++];
                    }else{
                        ip--;
                    }
                }
                // After a literal run of 4+ bytes, a short instruction means a 3-byte M2 match.
                boolean afterLiteral=!(firstLiteral&&initialShort);
                firstLiteral=false;
                while(true){
                    t=in[ip++]&0xff;
                    int m;
                    if(t<16&&afterLiteral){
                        m=op-(1+0x0800)-(t>>2)-((in[ip++]&0xff)<<2);
                        for(int i=0;i<3;i++)out[op++]=out[m++];
                    }else if(t<16){
                        m=op-1-(t>>2)-((in[ip++]&0xff)<<2);
                        out[op++]=out[m++];out[op++]=out[m];
                    }else if(t>=64){
                        m=op-1-((t>>2)&7)-((in[ip++]&0xff)<<3);
                        t=(t>>5)-1;
                        for(int i=0;i<t+2;i++)out[op++]=out[m++];
                    }else if(t>=32){
                        t&=31;
                        if(t==0){while(in[ip]==0){t+=255;ip++;}t+=31+(in[ip++]&0xff);}
                        m=op-1-(((in[ip]&0xff)|((in[ip+1]&0xff)<<8))>>2);ip+=2;
                        for(int i=0;i<t+2;i++)out[op++]=out[m++];
                    }else{
                        m=op-((t&8)<<11);
                        t&=7;
                        if(t==0){while(in[ip]==0){t+=255;ip++;}t+=7+(in[ip++]&0xff);}
                        m-=((in[ip]&0xff)|((in[ip+1]&0xff)<<8))>>2;ip+=2;
                        if(m==op)break outer;
                        m-=0x4000;
                        for(int i=0;i<t+2;i++)out[op++]=out[m++];
                    }
                    int trailing=in[ip-2]&3;
                    if(trailing==0){afterLiteral=false;continue outer;}
                    for(int i=0;i<trailing;i++)out[op++]=in[ip++];
                    afterLiteral=false;
                }
            }
        }catch(ArrayIndexOutOfBoundsException e){throw new IOException("Corrupt LZO block",e);}
        if(ip>end)throw new IOException("Corrupt LZO block");
        return op==out.length?out:java.util.Arrays.copyOf(out,op);
    }
}
