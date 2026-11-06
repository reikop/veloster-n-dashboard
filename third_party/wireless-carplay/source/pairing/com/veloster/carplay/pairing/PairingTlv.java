/* SPDX-License-Identifier: GPL-3.0-only. Strict pairing TLV8, adapted from DiPlay. */
package com.veloster.carplay.pairing;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PairingTlv {
    private PairingTlv() {}
    public static Map<Integer,byte[]> decode(byte[] data)throws IOException{
        if(data==null||data.length>8192)throw new IOException("Pairing TLV limit");
        Map<Integer,byte[]> out=new LinkedHashMap<Integer,byte[]>();int at=0,last=-1,lastSize=0;
        while(at<data.length){
            if(data.length-at<2)throw new IOException("Truncated TLV header");
            int type=data[at++]&255,size=data[at++]&255;
            if(size>data.length-at)throw new IOException("Truncated TLV value");
            byte[] value=Arrays.copyOfRange(data,at,at+size);at+=size;
            if(out.containsKey(type)){
                if(type!=last||lastSize!=255)throw new IOException("Duplicate pairing TLV");
                byte[] previous=out.get(type);byte[] joined=Arrays.copyOf(previous,previous.length+size);
                System.arraycopy(value,0,joined,previous.length,size);wipe(previous);wipe(value);value=joined;
            }
            out.put(type,value);last=type;lastSize=size;
        }
        return out;
    }
    public static byte[] encode(int[] types,byte[][] values)throws IOException{
        if(types.length!=values.length)throw new IllegalArgumentException();
        ByteArrayOutputStream out=new ByteArrayOutputStream();boolean[] seen=new boolean[256];
        for(int j=0;j<types.length;j++){
            int type=types[j];byte[] b=values[j];
            if(type<0||type>254||seen[type]||b==null)throw new IllegalArgumentException("Duplicate/invalid TLV");
            seen[type]=true;int at=0;
            do{int n=Math.min(255,b.length-at);if(out.size()+n+2>8192)throw new IOException("Pairing TLV limit");
                out.write(type);out.write(n);out.write(b,at,n);at+=n;
            }while(at<b.length);
        }
        return out.toByteArray();
    }
    static byte[] required(Map<Integer,byte[]> fields,int type,int min,int max)throws IOException{
        byte[] b=fields.get(type);if(b==null||b.length<min||b.length>max)throw new IOException("Missing/invalid pairing field");return b;
    }
    static String identifier(byte[] bytes)throws IOException{
        if(bytes==null||bytes.length<1||bytes.length>256)throw new IOException("Pairing identifier length");
        String s=Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        for(int i=0;i<s.length();i++)if(Character.isISOControl(s.charAt(i)))throw new IOException("Pairing identifier control character");
        return s;
    }
    static byte[] utf8(String s)throws IOException{
        if(s==null)throw new IOException("Missing identifier");byte[] bytes=s.getBytes("UTF-8");
        if(!s.equals(identifier(bytes)))throw new IOException("Invalid identifier");return bytes;
    }
    static byte[] join(byte[]... parts){
        int size=0;for(byte[] b:parts)size+=b.length;byte[] all=new byte[size];int at=0;
        for(byte[] b:parts){System.arraycopy(b,0,all,at,b.length);at+=b.length;}return all;
    }
    static void wipe(byte[] b){if(b!=null)Arrays.fill(b,(byte)0);}
    static void wipe(Map<Integer,byte[]> fields){if(fields!=null){for(byte[] b:fields.values())wipe(b);fields.clear();}}
}
