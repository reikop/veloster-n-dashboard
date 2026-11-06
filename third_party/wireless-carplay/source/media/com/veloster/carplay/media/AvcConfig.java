/* SPDX-License-Identifier: GPL-3.0-only. See media/NOTICE.md. */
package com.veloster.carplay.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/** Bounded AVC configuration and access-unit parser. No Android dependency. */
public final class AvcConfig {
    public static final int MAX_FRAME=8*1024*1024;
    public final int width,height,nalLengthBytes,profile,level,constraints;
    private final byte[] sps,pps;
    private AvcConfig(int w,int h,int n,byte[] s,byte[] p,int profile,int level,int constraints){
        width=w;height=h;nalLengthBytes=n;sps=s;pps=p;this.profile=profile;this.level=level;this.constraints=constraints;
    }
    public byte[] sps(){return sps.clone();}
    public byte[] pps(){return pps.clone();}

    /** Raw avcC record or a single size-prefixed avcC box. Unknown wrappers/codecs fail explicitly. */
    public static AvcConfig parse(byte[] source)throws IOException{
        if(source==null||source.length<7||source.length>1024*1024)throw bad("configuration length");
        int start=0;
        if(source.length>=8&&source[4]=='a'&&source[5]=='v'&&source[6]=='c'&&source[7]=='C'){
            long size=0;for(int i=0;i<4;i++)size=(size<<8)|(source[i]&255);
            if(size!=source.length)throw bad("avcC box length");start=8;
        }
        Cursor c=new Cursor(source,start);if(c.u8()!=1)throw bad("configuration version/codec");
        int profile=c.u8(),constraints=c.u8(),level=c.u8();int n=c.u8();
        if((n&252)!=252||(n&3)==2)throw bad("NAL length field");n=(n&3)+1;
        int count=c.u8();if((count&224)!=224||(count&31)==0)throw bad("SPS count");
        ByteArrayOutputStream s=new ByteArrayOutputStream(),p=new ByteArrayOutputStream();int[] dim=null;
        for(int i=0;i<(count&31);i++){
            byte[] nal=c.nal(7);int[] d=dimensions(nal);
            if((nal[1]&255)!=profile||(nal[2]&255)!=constraints||(nal[3]&255)!=level)throw bad("SPS profile mismatch");
            if(dim!=null&&!Arrays.equals(dim,d))throw bad("mixed SPS dimensions");dim=d;append(s,nal);
        }
        count=c.u8();if(count==0)throw bad("PPS count");
        for(int i=0;i<count;i++)append(p,c.nal(8));
        if(c.left()>0){
            if(profile!=100&&profile!=110&&profile!=122&&profile!=144)throw bad("configuration trailing bytes");
            if(c.u8()!=253||c.u8()!=248||c.u8()!=248)throw bad("unsupported chroma/bit depth");
            count=c.u8();for(int i=0;i<count;i++)append(s,c.nal(13));
        }
        if(c.left()!=0)throw bad("configuration trailing bytes");
        return new AvcConfig(dim[0],dim[1],n,s.toByteArray(),p.toByteArray(),profile,level,constraints);
    }

    public static final class Frame {
        public final byte[] annexB;
        public final boolean idr;
        private Frame(byte[] b,boolean key){annexB=b;idr=key;}
    }
    /** Try negotiated lengths first, then Annex B; length 1 must not be mistaken for a start code. */
    public Frame frame(byte[] source)throws IOException{
        if(source==null||source.length==0||source.length>MAX_FRAME)throw bad("frame length");
        try{return lengthFrame(source);}
        catch(IOException error){
            if(prefix(source,0)==0)throw error;
            ByteArrayOutputStream out=new ByteArrayOutputStream();int pos=0,count=0;
            while(pos<source.length){
                int n=prefix(source,pos);if(n==0)throw error;pos+=n;
                int end=pos;while(end<source.length&&prefix(source,end)==0)end++;
                int limit=end;while(limit>pos&&source[limit-1]==0)limit--;
                int length=limit-pos;if(length<1||++count>4096)throw error;
                // Normalize to four-byte lengths for validation with the same policy below.
                if(out.size()+4+length>MAX_FRAME)throw bad("expanded frame length");
                out.write(length>>>24);out.write(length>>>16);out.write(length>>>8);out.write(length);out.write(source,pos,length);pos=end;
            }
            return new AvcConfig(width,height,4,sps,pps,profile,level,constraints).lengthFrame(out.toByteArray());
        }
    }
    private static int prefix(byte[] b,int p){
        if(p+3>b.length||b[p]!=0||b[p+1]!=0)return 0;
        if(b[p+2]==1)return 3;
        return p+4<=b.length&&b[p+2]==0&&b[p+3]==1?4:0;
    }
    private Frame lengthFrame(byte[] source)throws IOException{
        int pos=0,total=0,count=0;boolean idr=false;
        while(pos<source.length){
            if(source.length-pos<nalLengthBytes)throw bad("truncated NAL length");
            long len=0;for(int i=0;i<nalLengthBytes;i++)len=(len<<8)|(source[pos++]&255);
            if(len<1||len>source.length-pos||++count>4096)throw bad("NAL length/count");
            int type=source[pos]&31;
            if((source[pos]&128)!=0||type==0||type>=24)throw bad("NAL header");
            // Parameter changes must arrive through configuration() so the codec is recreated safely.
            if((type==7||type==13)&&!contains(sps,source,pos,(int)len)||type==8&&!contains(pps,source,pos,(int)len))
                throw bad("changed in-band parameter set");
            idr|=type==5;total+=4+(int)len;if(total>MAX_FRAME)throw bad("expanded frame length");pos+=(int)len;
        }
        byte[] result=new byte[total];pos=0;int out=0;
        while(pos<source.length){
            int len=0;for(int i=0;i<nalLengthBytes;i++)len=(len<<8)|(source[pos++]&255);
            result[out+3]=1;out+=4;System.arraycopy(source,pos,result,out,len);out+=len;pos+=len;
        }
        return new Frame(result,idr);
    }
    private static boolean contains(byte[] sets,byte[] value,int start,int length){
        int pos=0;
        while(pos<sets.length){
            pos+=4;int end=pos;while(end<sets.length&&prefix(sets,end)==0)end++;
            if(end-pos==length){boolean equal=true;for(int i=0;i<length;i++)equal&=sets[pos+i]==value[start+i];if(equal)return true;}
            pos=end;
        }
        return false;
    }
    private static void append(ByteArrayOutputStream out,byte[] nal){out.write(0);out.write(0);out.write(0);out.write(1);out.write(nal,0,nal.length);}
    private static IOException bad(String message){return new IOException("AVC "+message);}
    private static final class Cursor {
        final byte[] b;int pos;
        Cursor(byte[] data,int offset){b=data;pos=offset;}
        int left(){return b.length-pos;}
        int u8()throws IOException{if(left()<1)throw bad("truncated configuration");return b[pos++]&255;}
        byte[] nal(int type)throws IOException{
            int length=(u8()<<8)|u8();if(length==0||length>left())throw bad("parameter set length");
            byte[] result=Arrays.copyOfRange(b,pos,pos+length);pos+=length;
            if((result[0]&128)!=0||(result[0]&31)!=type)throw bad("parameter set type");return result;
        }
    }
    private static int[] dimensions(byte[] nal)throws IOException{
        Bits b=new Bits(nal);int profile=b.read(8);b.read(8);b.read(8);if(b.ue()>31)throw bad("SPS id");
        if(profile==100||profile==110||profile==122||profile==244||profile==44||profile==83||profile==86||profile==118||profile==128||profile==138||profile==139||profile==134||profile==135){
            if(b.ue()!=1||b.ue()!=0||b.ue()!=0)throw bad("requires 8-bit 4:2:0");
            b.read(1);if(b.read(1)!=0)for(int i=0;i<8;i++)if(b.read(1)!=0){
                int last=8,next=8;for(int j=0;j<(i<6?16:64);j++){
                    if(next!=0)next=(last+b.se()+256)&255;if(next!=0)last=next;
                }
            }
        }else if(profile!=66&&profile!=77&&profile!=88)throw bad("unsupported profile");
        if(b.ue()>12)throw bad("frame number size");int poc=b.ue();
        if(poc==0){if(b.ue()>12)throw bad("POC size");}
        else if(poc==1){b.read(1);b.se();b.se();int n=b.ue();if(n>255)throw bad("POC cycle");for(int i=0;i<n;i++)b.se();}
        else if(poc!=2)throw bad("POC type");
        if(b.ue()>16)throw bad("reference count");b.read(1);
        int w=b.ue(),h=b.ue();if(w>255||h>255)throw bad("coded dimensions");
        int progressive=b.read(1);if(progressive==0)b.read(1);b.read(1);
        if((w+1)*16>1920||(h+1)*16*(2-progressive)>1088)throw bad("coded allocation dimensions");
        long left=0,right=0,top=0,bottom=0;
        if(b.read(1)!=0){left=b.ue();right=b.ue();top=b.ue();bottom=b.ue();}
        long width=(w+1)*16L-2*(left+right),height=(h+1)*16L*(2-progressive)-2*(2-progressive)*(top+bottom);
        if(width<16||height<16||width>1920||height>1088)throw bad("display dimensions");
        return new int[]{(int)width,(int)height};
    }
    private static final class Bits {
        final byte[] data;int bit;
        Bits(byte[] nal)throws IOException{
            ByteArrayOutputStream out=new ByteArrayOutputStream();int zeros=0;
            for(int i=1;i<nal.length;i++){
                int v=nal[i]&255;
                if(zeros==2&&v==3){if(i+1>=nal.length||(nal[i+1]&255)>3)throw bad("emulation prevention");zeros=0;continue;}
                out.write(v);zeros=v==0?zeros+1:0;
            }
            data=out.toByteArray();
        }
        int read(int n)throws IOException{
            if(n<0||n>30||bit+n>data.length*8)throw bad("truncated SPS");
            int result=0;for(int i=0;i<n;i++){result=(result<<1)|((data[bit/8]>>(7-bit%8))&1);bit++;}return result;
        }
        int ue()throws IOException{int zeros=0;while(read(1)==0)if(++zeros>24)throw bad("Exp-Golomb overflow");return ((1<<zeros)-1)+read(zeros);}
        int se()throws IOException{int n=ue();return (n&1)==0?-(n/2):(n+1)/2;}
    }
}
