/* SPDX-License-Identifier: GPL-3.0-only. Bounded bplist00 codec; see NOTICE.md. */
package com.veloster.carplay.control;
import java.io.*;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Protocol values only: null/bool, integers through uint64, finite reals, date, data, strings,
 * lists and string-key dictionaries. No keyed archives/UID/set coercion or lossy numeric casts. */
public final class BinaryPlist {
    public static final int MAX_BYTES=1048576,MAX_OBJECTS=4096,MAX_DEPTH=32;
    private static final BigInteger U64=BigInteger.ONE.shiftLeft(64),MIN=BigInteger.ONE.shiftLeft(63).negate();
    private static final byte[] MAGIC={98,112,108,105,115,116,48,48};
    private BinaryPlist(){}
    private static IOException bad(){return new IOException("Invalid/unsupported binary plist");}
    private static void textUnits(String value)throws IOException{
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(c>=0xd800&&c<=0xdbff){if(++i>=value.length()||value.charAt(i)<0xdc00||value.charAt(i)>0xdfff)throw bad();}
            else if(c>=0xdc00&&c<=0xdfff)throw bad();
        }
    }
    private static String string(byte[] bytes,int at,int size,boolean ascii)throws IOException{
        // API17's charset decoder can accept unpaired surrogates despite REPORT. Validate code units ourselves.
        char[] chars=new char[ascii?size:size/2];
        for(int i=0;i<chars.length;i++){
            if(ascii){int c=bytes[at+i]&255;if(c>127)throw bad();chars[i]=(char)c;}
            else chars[i]=(char)(((bytes[at+i*2]&255)<<8)|(bytes[at+i*2+1]&255));
        }
        String value=new String(chars);textUnits(value);return value;
    }
    private static long uint(byte[] bytes,int at,int size,int end)throws IOException{
        if(size<1||size>8||at<0||at>end-size)throw bad();long n=0;
        for(int i=0;i<size;i++){int b=bytes[at+i]&255;if(n>(Long.MAX_VALUE-b)/256)throw bad();n=n*256+b;}return n;
    }
    private static int width(long value){return value<=255?1:value<=65535?2:value<=0xffffffffL?4:8;}
    private static void number(ByteArrayOutputStream out,long n,int bytes){for(int i=bytes-1;i>=0;i--)out.write((int)(n>>>(i*8))&255);}
    private static int power(int bytes){return bytes==1?0:bytes==2?1:bytes==4?2:bytes==8?3:4;}
    private static byte[] marker(int type,int size){
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(type|(size<15?size:15));
        if(size>=15){int w=width(size);out.write(0x10|power(w));number(out,size,w);}return out.toByteArray();
    }
    public static Object decode(byte[] bytes)throws IOException{return new Decoder(bytes).read();}
    private static final class Decoder {
        final byte[] bytes;final int[] offsets,ends;final byte[] state;final Object[] cache;final int refs,top;int visits,allocation;
        Decoder(byte[] input)throws IOException{
            if(input==null||input.length<40||input.length>MAX_BYTES||!Arrays.equals(Arrays.copyOf(input,8),MAGIC))throw bad();bytes=input;
            int trailer=input.length-32,ow=input[trailer+6]&255;refs=input[trailer+7]&255;
            if(!(ow==1||ow==2||ow==4||ow==8)||!(refs==1||refs==2||refs==4||refs==8))throw bad();
            long count=uint(input,trailer+8,8,input.length),root=uint(input,trailer+16,8,input.length),table=uint(input,trailer+24,8,input.length);
            if(count<1||count>MAX_OBJECTS||root>=count||table<8||table>trailer||table+count*ow!=trailer)throw bad();top=(int)root;
            offsets=new int[(int)count];ends=new int[(int)count];state=new byte[(int)count];cache=new Object[(int)count];
            TreeSet<Integer> sorted=new TreeSet<Integer>();
            for(int i=0;i<count;i++){long offset=uint(bytes,(int)table+i*ow,ow,trailer);if(offset<8||offset>=table||!sorted.add((int)offset))throw bad();offsets[i]=(int)offset;}
            sorted.add((int)table);for(int i=0;i<count;i++)ends[i]=sorted.higher(offsets[i]);
        }
        Object read()throws IOException{return object(top,0);}
        void charge(int n)throws IOException{if(n<0||n>4*MAX_BYTES-allocation)throw bad();allocation+=n;}
        int reference(int at,int end)throws IOException{long n=uint(bytes,at,refs,end);if(n>=offsets.length)throw bad();return (int)n;}
        Object object(int index,int depth)throws IOException{
            if(depth>MAX_DEPTH||++visits>16384||state[index]==1)throw bad();if(state[index]==2)return cache[index];state[index]=1;
            int at=offsets[index],end=ends[index],tag=bytes[at++]&255,type=tag>>>4,low=tag&15;Object result;
            if(type==0){if(low==0)result=null;else if(low==8||low==9)result=low==9;else throw bad();}
            else if(type==1){
                if(low>4)throw bad();int n=1<<low;if(at>end-n)throw bad();
                byte[] raw=Arrays.copyOfRange(bytes,at,at+n);BigInteger value=n>=8?new BigInteger(raw):new BigInteger(1,raw);
                if(value.compareTo(MIN)<0||value.compareTo(U64)>=0)throw bad();result=value.bitLength()<64?Long.valueOf(value.longValue()):value;
            }else if(type==2||type==3){
                int n=1<<low;if(type==3&&low!=3||type==2&&n!=4&&n!=8||at>end-n)throw bad();
                long bits=0;for(int i=0;i<n;i++)bits=(bits<<8)|(bytes[at+i]&255);
                double value=n==4?Float.intBitsToFloat((int)bits):Double.longBitsToDouble(bits);
                if(Double.isNaN(value)||Double.isInfinite(value))throw bad();
                if(type==3){double millis=(value+978307200.0)*1000;if(millis<Long.MIN_VALUE||millis>Long.MAX_VALUE)throw bad();result=new Date(Math.round(millis));}
                else result=Double.valueOf(value);
            }else{
                int count=low;
                if(low==15){if(at>=end)throw bad();int lengthTag=bytes[at++]&255;if((lengthTag>>>4)!=1||(lengthTag&15)>3)throw bad();int n=1<<(lengthTag&15);
                    long value=uint(bytes,at,n,end);at+=n;if(value>MAX_BYTES)throw bad();count=(int)value;}
                int size=type==6?count*2:type==10?count*refs:type==13?count*2*refs:count;
                if(size<0||at>end-size)throw bad();charge(size);
                if(type==4)result=Arrays.copyOfRange(bytes,at,at+count);
                else if(type==5||type==6){
                    result=string(bytes,at,size,type==5);
                }else if(type==10){
                    if(count>MAX_OBJECTS)throw bad();List<Object> list=new ArrayList<Object>(count);
                    for(int i=0;i<count;i++)list.add(object(reference(at+i*refs,end),depth+1));result=Collections.unmodifiableList(list);
                }else if(type==13){
                    if(count>MAX_OBJECTS)throw bad();Map<String,Object> map=new LinkedHashMap<String,Object>();
                    for(int i=0;i<count;i++){Object key=object(reference(at+i*refs,end),depth+1);if(!(key instanceof String)||map.containsKey(key))throw bad();
                        map.put((String)key,object(reference(at+(count+i)*refs,end),depth+1));}result=Collections.unmodifiableMap(map);
                }else throw bad();
            }
            state[index]=2;cache[index]=result;return result;
        }
    }
    private static final class Node {byte[] body;int[] refs;Node(byte[] body){this.body=body;}}
    private static final class Encoder {
        final ArrayList<Node> nodes=new ArrayList<Node>();final IdentityHashMap<Object,Boolean> active=new IdentityHashMap<Object,Boolean>();int total;
        int add(Object value,int depth)throws IOException{
            if(depth>MAX_DEPTH||nodes.size()>=MAX_OBJECTS)throw bad();int index=nodes.size();Node node=new Node(null);nodes.add(node);
            if(value instanceof Map||value instanceof List){
                if(active.put(value,Boolean.TRUE)!=null)throw bad();
                try{
                    if(value instanceof List){List<?> list=(List<?>)value;if(list.size()>MAX_OBJECTS)throw bad();node.body=marker(0xa0,list.size());node.refs=new int[list.size()];
                        for(int i=0;i<list.size();i++)node.refs[i]=add(list.get(i),depth+1);
                    }else{Map<?,?> map=(Map<?,?>)value;if(map.size()>MAX_OBJECTS)throw bad();node.body=marker(0xd0,map.size());node.refs=new int[map.size()*2];int i=0;
                        for(Map.Entry<?,?> entry:map.entrySet()){if(!(entry.getKey() instanceof String))throw bad();node.refs[i]=add(entry.getKey(),depth+1);node.refs[i+map.size()]=add(entry.getValue(),depth+1);i++;}}
                }finally{active.remove(value);}
            }else if(value==null)node.body=new byte[]{0};
            else if(value instanceof Boolean)node.body=new byte[]{(byte)(((Boolean)value)?9:8)};
            else if(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long||value instanceof BigInteger){
                BigInteger number=value instanceof BigInteger?(BigInteger)value:BigInteger.valueOf(((Number)value).longValue());
                if(number.compareTo(MIN)<0||number.compareTo(U64)>=0)throw bad();
                int n=number.signum()<0?8:number.bitLength()>63?16:width(number.longValue());node.body=new byte[n+1];node.body[0]=(byte)(0x10|power(n));
                if(number.signum()<0)Arrays.fill(node.body,1,node.body.length,(byte)255);byte[] raw=number.toByteArray();int copied=Math.min(raw.length,n);System.arraycopy(raw,raw.length-copied,node.body,node.body.length-copied,copied);
            }else if(value instanceof Float||value instanceof Double||value instanceof Date){
                double d=value instanceof Date?(((Date)value).getTime()/1000.0)-978307200.0:((Number)value).doubleValue();if(Double.isNaN(d)||Double.isInfinite(d))throw bad();
                ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(value instanceof Date?0x33:0x23);number(out,Double.doubleToLongBits(d),8);node.body=out.toByteArray();
            }else if(value instanceof String||value instanceof byte[]){
                byte[] raw;int type,count;
                if(value instanceof String){String s=(String)value;if(s.length()>MAX_BYTES/2)throw bad();textUnits(s);boolean ascii=true;
                    for(int i=0;i<s.length();i++)if(s.charAt(i)>127)ascii=false;
                    Charset charset=Charset.forName(ascii?"US-ASCII":"UTF-16BE");
                    try{ByteBuffer encoded=charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(s));raw=new byte[encoded.remaining()];encoded.get(raw);}
                    catch(CharacterCodingException e){throw bad();}type=ascii?0x50:0x60;count=s.length();
                }else{raw=(byte[])value;if(raw.length>MAX_BYTES)throw bad();type=0x40;count=raw.length;}
                byte[] header=marker(type,count);node.body=Arrays.copyOf(header,header.length+raw.length);System.arraycopy(raw,0,node.body,header.length,raw.length);
            }else throw bad();
            if(node.body.length>MAX_BYTES-total)throw bad();total+=node.body.length;return index;
        }
        byte[] encode(Object root)throws IOException{
            int top=add(root,0),refSize=width(nodes.size()-1);ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(MAGIC,0,8);int[] offsets=new int[nodes.size()];
            for(int i=0;i<nodes.size();i++){offsets[i]=out.size();Node node=nodes.get(i);out.write(node.body,0,node.body.length);if(node.refs!=null)for(int ref:node.refs)number(out,ref,refSize);if(out.size()>MAX_BYTES)throw bad();}
            int table=out.size(),offsetSize=width(table);for(int offset:offsets)number(out,offset,offsetSize);number(out,0,6);out.write(offsetSize);out.write(refSize);
            number(out,nodes.size(),8);number(out,top,8);number(out,table,8);if(out.size()>MAX_BYTES)throw bad();return out.toByteArray();
        }
    }
    public static byte[] encode(Object root)throws IOException{return new Encoder().encode(root);}
    /** Only protocol fields explicitly declared uint64 may reinterpret an eight-byte negative Long. */
    public static BigInteger unsigned64(Object value)throws IOException{
        BigInteger n;if(value instanceof Long||value instanceof Integer){long raw=((Number)value).longValue();n=BigInteger.valueOf(raw);if(raw<0)n=n.add(U64);}
        else if(value instanceof BigInteger)n=(BigInteger)value;else throw bad();
        if(n.signum()<0||n.compareTo(U64)>=0)throw bad();return n;
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> dictionary(Object value)throws IOException{if(!(value instanceof Map))throw bad();return (Map<String,Object>)value;}
}
