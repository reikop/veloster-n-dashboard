package com.veloster.vehicle;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * 글꼴: what the font manager needs from one TrueType/OpenType file, read from its tables only (no Android, host-tested):
 * the family and style names, the weight, italic, Hangul and Latin coverage from the Unicode cmap, a CFF outline and a
 * variable font. A collection (.ttc), a damaged table or anything that is not a font is rejected with a reason.
 */
public final class FontFile {
    public static final int HANGUL_FIRST=0xAC00,HANGUL_LAST=0xD7A3,HANGUL_ALL=HANGUL_LAST-HANGUL_FIRST+1;
    /** The largest file the manager reads or installs (the head unit's /system has a few tens of MB free). */
    public static final long MAX_BYTES=40L*1024*1024;
    public final String path,family,style,displayFamily;
    public final long bytes;
    public final int weight,hangul;
    public final boolean italic,latin,cff,variable;

    private FontFile(String path,long bytes,String family,String style,String displayFamily,int weight,boolean italic,int hangul,boolean latin,boolean cff,boolean variable){
        this.path=path;this.bytes=bytes;this.family=family;this.style=style;this.displayFamily=displayFamily;this.weight=weight;this.italic=italic;
        this.hangul=hangul;this.latin=latin;this.cff=cff;this.variable=variable;
    }
    /** All 11,172 modern Hangul syllables. */
    public boolean fullHangul(){return hangul>=HANGUL_ALL;}
    public String toString(){return family+" "+style+" w"+weight+(italic?" italic":"")+" hangul="+hangul+(cff?" cff":"")+(variable?" variable":"")+" "+path;}

    /** Thrown for a file that is not a usable single font. */
    public static final class Rejected extends IOException{public Rejected(String why){super(why);}}

    public static FontFile read(File f)throws IOException{
        long n=f.length();
        if(n<12)throw new Rejected("too small");
        if(n>MAX_BYTES)throw new Rejected("larger than 40 MB");
        RandomAccessFile in=new RandomAccessFile(f,"r");
        try{return read(new Source(in,n),f.getPath(),n);}finally{in.close();}
    }
    /** For tests: a font held in memory. */
    public static FontFile read(byte[] data,String path)throws IOException{return read(new Source(data),path,data.length);}

    private static FontFile read(Source s,String path,long n)throws IOException{
        int tag=s.u32(0);
        if(tag==0x74746366)throw new Rejected("font collection (.ttc)");
        boolean cffOutline=tag==0x4F54544F;   // 'OTTO'
        if(tag!=0x00010000&&tag!=0x74727565&&!cffOutline)throw new Rejected("not a TrueType/OpenType font");
        int tables=s.u16(4);
        if(tables<=0||tables>200||12+16L*tables>n)throw new Rejected("bad table directory");
        long nameOff=-1,nameLen=0,os2Off=-1,os2Len=0,cmapOff=-1,cmapLen=0,headOff=-1,headLen=0;boolean fvar=false,glyf=false,cffTable=false;
        for(int i=0;i<tables;i++){
            int at=12+16*i,t=s.u32(at);long off=s.u32(at+8)&0xffffffffL,len=s.u32(at+12)&0xffffffffL;
            if(off+len>n)throw new Rejected("table outside the file");
            switch(t){
                case 0x6E616D65:nameOff=off;nameLen=len;break;   // name
                case 0x4F532F32:os2Off=off;os2Len=len;break;     // OS/2
                case 0x636D6170:cmapOff=off;cmapLen=len;break;   // cmap
                case 0x68656164:headOff=off;headLen=len;break;   // head
                case 0x66766172:fvar=true;break;                 // fvar
                case 0x676C7966:glyf=true;break;                 // glyf
                case 0x43464620:case 0x43464632:cffTable=true;break;   // CFF / CFF2
                default:
            }
        }
        if(nameOff<0||cmapOff<0)throw new Rejected("no name or cmap table");
        if(!glyf&&!cffTable)throw new Rejected("no outlines");
        String[] names=names(s,nameOff,nameLen);
        String family=first(names[0],names[1]),style=first(names[2],names[3]),display=first(names[4],names[5],family);
        if(family==null||family.trim().length()==0)throw new Rejected("no family name");
        if(style==null)style="Regular";
        int weight=400;boolean italic=false;
        if(os2Off>=0&&os2Len>=64){int w=s.u16(os2Off+4);if(w>=1&&w<=1000)weight=w;italic=(s.u16(os2Off+62)&1)!=0;}
        else if(headOff>=0&&headLen>=46){int mac=s.u16(headOff+44);if((mac&1)!=0)weight=700;italic=(mac&2)!=0;}
        int[] cover=cover(s,cmapOff,cmapLen);
        return new FontFile(path,n,family.trim(),style.trim(),display.trim(),weight,italic,cover[0],cover[1]>=52,cffOutline||cffTable&&!glyf,fvar);
    }
    private static String first(String... v){for(String s:v)if(s!=null&&s.trim().length()>0)return s;return null;}

    /** {typographic family, family, typographic style, style, Korean family, Korean typographic family}; Windows English
     *  names first, then any Windows or Mac Roman name. */
    private static String[] names(Source s,long off,long len)throws IOException{
        if(len<6)throw new Rejected("short name table");
        int count=s.u16(off+2),strings=s.u16(off+4);
        String[] best=new String[6];int[] rank=new int[6];
        for(int i=0;i<count;i++){
            long r=off+6+12L*i;if(r+12>off+len)break;
            int platform=s.u16(r),encoding=s.u16(r+2),lang=s.u16(r+4),id=s.u16(r+6),length=s.u16(r+8),at=s.u16(r+10);
            int slot=id==16?0:id==1?1:id==17?2:id==2?3:-1;
            if(slot<0)continue;
            long p=off+strings+at;if(length==0||p+length>off+len)continue;
            String text;int q;
            if(platform==3&&(encoding==1||encoding==10||encoding==0)){text=utf16(s,p,length);q=lang==0x409?4:3;}
            else if(platform==0){text=utf16(s,p,length);q=2;}
            else if(platform==1&&encoding==0){text=latin1(s,p,length);q=1;}
            else continue;
            if(q>rank[slot]){rank[slot]=q;best[slot]=text;}
            if(platform==3&&lang==0x412&&(id==1||id==16)){int k=id==1?4:5;if(best[k]==null||id==16)best[k]=text;}
        }
        return best;
    }
    private static String utf16(Source s,long p,int len)throws IOException{
        StringBuilder b=new StringBuilder();for(int i=0;i+1<len;i+=2)b.append((char)s.u16(p+i));return b.toString();
    }
    private static String latin1(Source s,long p,int len)throws IOException{
        StringBuilder b=new StringBuilder();for(int i=0;i<len;i++)b.append((char)s.u8(p+i));return b.toString();
    }

    /** {Hangul syllables mapped, basic Latin letters mapped} from the best Unicode subtable (format 12, then 4). */
    private static int[] cover(Source s,long off,long len)throws IOException{
        int count=s.u16(off+2);long f12=-1,f4=-1;
        for(int i=0;i<count;i++){
            long r=off+4+8L*i;if(r+8>off+len)break;
            int platform=s.u16(r),encoding=s.u16(r+2);long sub=off+(s.u32(r+4)&0xffffffffL);
            if(sub+4>off+len)continue;
            boolean unicode=platform==0||platform==3&&(encoding==1||encoding==10);
            if(!unicode)continue;
            int format=s.u16(sub);
            if(format==12&&f12<0)f12=sub;else if(format==4&&f4<0)f4=sub;
        }
        if(f12>=0)return format12(s,f12,off+len);
        if(f4>=0)return format4(s,f4,off+len);
        throw new Rejected("no Unicode cmap");
    }
    private static boolean latinLetter(long c){return c>='A'&&c<='Z'||c>='a'&&c<='z';}
    private static int[] format12(Source s,long sub,long end)throws IOException{
        long groups=s.u32(sub+12)&0xffffffffL;int hangul=0,latin=0;
        if(sub+16+12*groups>end)throw new Rejected("short cmap");
        for(long g=0;g<groups;g++){
            long r=sub+16+12*g,start=s.u32(r)&0xffffffffL,stop=s.u32(r+4)&0xffffffffL,glyph=s.u32(r+8)&0xffffffffL;
            if(stop<start)continue;
            long a=Math.max(start,HANGUL_FIRST),b=Math.min(stop,HANGUL_LAST);
            if(a<=b){hangul+=(int)(b-a+1);if(glyph==0&&start>=HANGUL_FIRST&&start<=HANGUL_LAST)hangul--;}
            for(long c='A';c<='z';c++)if(latinLetter(c)&&c>=start&&c<=stop&&glyph+(c-start)!=0)latin++;
        }
        return new int[]{Math.min(hangul,HANGUL_ALL),latin};
    }
    private static int[] format4(Source s,long sub,long end)throws IOException{
        int segs=s.u16(sub+6)/2;long ends=sub+14,starts=ends+2L*segs+2,deltas=starts+2L*segs,ranges=deltas+2L*segs;
        if(ranges+2L*segs>end)throw new Rejected("short cmap");
        int hangul=0,latin=0;
        for(int i=0;i<segs;i++){
            int stop=s.u16(ends+2L*i),start=s.u16(starts+2L*i),delta=s.u16(deltas+2L*i),range=s.u16(ranges+2L*i);
            if(stop<start)continue;
            for(int pass=0;pass<2;pass++){
                int a=pass==0?Math.max(start,HANGUL_FIRST):Math.max(start,'A'),b=pass==0?Math.min(stop,HANGUL_LAST):Math.min(stop,'z');
                for(int c=a;c<=b;c++){
                    if(pass==1&&!latinLetter(c))continue;
                    int glyph;
                    if(range==0)glyph=(c+delta)&0xffff;
                    else{long p=ranges+2L*i+range+2L*(c-start);if(p+2>end)continue;glyph=s.u16(p);if(glyph!=0)glyph=(glyph+delta)&0xffff;}
                    if(glyph!=0){if(pass==0)hangul++;else latin++;}
                }
            }
        }
        return new int[]{hangul,latin};
    }

    /** Big-endian reads from a file (through a small page cache, so a cmap walk is not one seek per glyph) or a byte
     *  array; out-of-range reads are a damaged font. */
    private static final class Source{
        private static final int PAGE=4096,PAGES=256;
        private final RandomAccessFile file;private final byte[] data;private final long length;
        private final java.util.LinkedHashMap<Long,byte[]> cache=new java.util.LinkedHashMap<Long,byte[]>(64,0.75f,true){
            protected boolean removeEldestEntry(java.util.Map.Entry<Long,byte[]> e){return size()>PAGES;}};
        Source(RandomAccessFile f,long n){file=f;data=null;length=n;}
        Source(byte[] d){file=null;data=d;length=d.length;}
        int u8(long p)throws IOException{
            if(p<0||p>=length)throw new Rejected("damaged font");
            if(data!=null)return data[(int)p]&0xff;
            Long key=Long.valueOf(p/PAGE);byte[] page=cache.get(key);
            if(page==null){long at=key.longValue()*PAGE;page=new byte[(int)Math.min(PAGE,length-at)];file.seek(at);file.readFully(page);cache.put(key,page);}
            return page[(int)(p%PAGE)]&0xff;
        }
        int u16(long p)throws IOException{
            if(p<0||p+2>length)throw new Rejected("damaged font");
            return u8(p)<<8|u8(p+1);
        }
        int u32(long p)throws IOException{return u16(p)<<16|u16(p+2);}
    }
}
