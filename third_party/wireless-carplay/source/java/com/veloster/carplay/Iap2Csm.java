/* SPDX-License-Identifier: GPL-3.0-only. See TRANSPORT-NOTICE.md. */
package com.veloster.carplay;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** iAP2 control messages: 4040/u16 total length/u16 message, then ordered TLVs.
 * Byte fields are never logged or converted by toString (they can contain credentials).
 */
public final class Iap2Csm {
    public static final int MAX_FRAME=65535;
    private static final Charset UTF8=Charset.forName("UTF-8");
    public static final class Field {
        public final int id;
        private final byte[] bytes;
        public Field(int id,byte[] value) {
            if(id<0 || id>65535 || value==null || value.length>65531)throw new IllegalArgumentException("Invalid TLV");
            this.id=id;bytes=value.clone();
        }
        public byte[] bytes(){return bytes.clone();}
    }
    public static final class Frame {
        public final int message;
        private final byte[] bytes;
        public Frame(int message,byte[] payload) {
            if(message<0 || message>65535 || payload==null || payload.length>MAX_FRAME-6)
                throw new IllegalArgumentException("Invalid control frame");
            this.message=message;bytes=payload.clone();
        }
        public byte[] payload(){return bytes.clone();}
        public List<Field> fields() throws IOException{return parseFields(bytes);}
        public byte[] encode(){
            byte[] b=new byte[bytes.length+6];b[0]=64;b[1]=64;put16(b,2,b.length);put16(b,4,message);
            System.arraycopy(bytes,0,b,6,bytes.length);return b;
        }
    }
    private final byte[] buffer=new byte[MAX_FRAME];
    private int used;
    private final ArrayDeque<Frame> pending=new ArrayDeque<Frame>();
    public Frame poll(){return pending.poll();}
    public void clear(){Arrays.fill(buffer,(byte)0);used=0;pending.clear();}
    public void feed(byte[] bytes) throws IOException {
        int offset=0;
        while(offset<bytes.length) {
            int n=Math.min(buffer.length-used,bytes.length-offset);
            if(n==0)throw new IOException("Control buffer limit");
            System.arraycopy(bytes,offset,buffer,used,n);offset+=n;used+=n;
            while(used>=6) {
                if(buffer[0]!=64 || buffer[1]!=64)throw new IOException("Invalid control message marker");
                int size=u16(buffer,2);
                if(size<6)throw new IOException("Invalid control message length");
                if(used<size)break;
                if(pending.size()>=64)throw new IOException("Undrained control queue");
                pending.add(new Frame(u16(buffer,4),Arrays.copyOfRange(buffer,6,size)));
                System.arraycopy(buffer,size,buffer,0,used-size);Arrays.fill(buffer,used-size,used,(byte)0);used-=size;
            }
        }
    }
    public static List<Field> parseFields(byte[] bytes) throws IOException {
        ArrayList<Field> out=new ArrayList<Field>();int at=0;
        while(at<bytes.length) {
            if(bytes.length-at<4)throw new IOException("Truncated TLV header");
            int size=u16(bytes,at);
            if(size<4 || size>bytes.length-at)throw new IOException("Invalid TLV length");
            out.add(new Field(u16(bytes,at+2),Arrays.copyOfRange(bytes,at+4,at+size)));at+=size;
        }
        return Collections.unmodifiableList(out);
    }
    public static byte[] fields(Field... values) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(Field value:values) {
            int size=value.bytes.length+4;
            if(size>MAX_FRAME-6-out.size())throw new IllegalArgumentException("Control body too large");
            out.write(size>>>8);out.write(size);out.write(value.id>>>8);out.write(value.id);
            out.write(value.bytes,0,value.bytes.length);
        }
        return out.toByteArray();
    }
    public static Field string(int id,String value) {
        if(value==null || value.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid protocol string");
        byte[] raw=value.getBytes(UTF8);return new Field(id,Arrays.copyOf(raw,raw.length+1));
    }
    public static Field u8(int id,int value) {
        if(value<0 || value>255)throw new IllegalArgumentException("Not u8");return new Field(id,new byte[]{(byte)value});
    }
    public static Field u16(int id,int value) {
        if(value<0 || value>65535)throw new IllegalArgumentException("Not u16");return new Field(id,new byte[]{(byte)(value>>>8),(byte)value});
    }
    private static int u16(byte[] b,int at){return ((b[at]&255)<<8)|(b[at+1]&255);}
    private static void put16(byte[] b,int at,int n){b[at]=(byte)(n>>>8);b[at+1]=(byte)n;}
}
