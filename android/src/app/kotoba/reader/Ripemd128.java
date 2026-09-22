package app.kotoba.reader;

/** RIPEMD-128, used only to derive MDict key-index decryption keys. */
final class Ripemd128 {
    private static final int[] R1={0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,7,4,13,1,10,6,15,3,12,0,9,5,2,14,11,8,3,10,14,4,9,15,8,1,2,7,0,6,13,11,5,12,1,9,11,10,0,8,12,4,13,3,7,15,14,5,6,2};
    private static final int[] R2={5,14,7,0,9,2,11,4,13,6,15,8,1,10,3,12,6,11,3,7,0,13,5,10,14,15,8,12,4,9,1,2,15,5,1,3,7,14,6,9,11,8,12,2,10,0,4,13,8,6,4,1,3,11,15,0,5,12,2,13,9,7,10,14};
    private static final int[] S1={11,14,15,12,5,8,7,9,11,13,14,15,6,7,9,8,7,6,8,13,11,9,7,15,7,12,15,9,11,7,13,12,11,13,6,7,14,9,13,15,14,8,13,6,5,12,7,5,11,12,14,15,14,15,9,8,9,14,5,6,8,6,5,12};
    private static final int[] S2={8,9,9,11,13,15,15,5,7,7,8,11,14,14,12,6,9,13,15,7,12,8,9,11,7,7,12,7,6,15,13,11,9,7,15,11,8,6,6,14,12,13,5,14,13,13,7,5,15,5,8,11,14,14,6,14,6,9,12,9,12,5,15,8};
    private static final int[] K1={0,0x5a827999,0x6ed9eba1,0x8f1bbcdc};
    private static final int[] K2={0x50a28be6,0x5c4dd124,0x6d703ef3,0};

    private static int f(int j,int x,int y,int z){
        switch(j){case 0:return x^y^z;case 1:return (x&y)|(~x&z);case 2:return (x|~y)^z;default:return (x&z)|(y&~z);}
    }

    static byte[] digest(byte[] message){
        int[] h={0x67452301,0xefcdab89,0x98badcfe,0x10325476};
        long bitLength=(long)message.length*8;
        int padded=((message.length+8)/64+1)*64;
        byte[] data=new byte[padded];
        System.arraycopy(message,0,data,0,message.length);
        data[message.length]=(byte)0x80;
        for(int i=0;i<8;i++)data[padded-8+i]=(byte)(bitLength>>>(8*i));
        int[] x=new int[16];
        for(int block=0;block<padded;block+=64){
            for(int i=0;i<16;i++)x[i]=(data[block+i*4]&0xff)|((data[block+i*4+1]&0xff)<<8)|((data[block+i*4+2]&0xff)<<16)|((data[block+i*4+3]&0xff)<<24);
            int a1=h[0],b1=h[1],c1=h[2],d1=h[3],a2=h[0],b2=h[1],c2=h[2],d2=h[3];
            for(int j=0;j<64;j++){
                int round=j>>4;
                int t=Integer.rotateLeft(a1+f(round,b1,c1,d1)+x[R1[j]]+K1[round],S1[j]);
                a1=d1;d1=c1;c1=b1;b1=t;
                t=Integer.rotateLeft(a2+f(3-round,b2,c2,d2)+x[R2[j]]+K2[round],S2[j]);
                a2=d2;d2=c2;c2=b2;b2=t;
            }
            int t=h[1]+c1+d2;h[1]=h[2]+d1+a2;h[2]=h[3]+a1+b2;h[3]=h[0]+b1+c2;h[0]=t;
        }
        byte[] out=new byte[16];
        for(int i=0;i<4;i++)for(int k=0;k<4;k++)out[i*4+k]=(byte)(h[i]>>>(8*k));
        return out;
    }
}
