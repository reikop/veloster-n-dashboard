/* SPDX-License-Identifier: GPL-3.0-only. Screen wire profile based on DiPlay ScreenStream; see NOTICE.md. */
package com.veloster.carplay.tunnel;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;

/** Incremental receiver for one encrypted screen stream (type110/111).
 * Owns a single receive key/counter; never reuse on a replacement connection.
 * This is a packet decoder, not an H264 decoder or proof of rendered video.
 * Codec configuration is clear on the wire; video frames always require an authentication tag.
 * The session owner must check its lease before feeding input and in every sink callback. */
public final class ScreenDecoder implements Closeable {
    public interface Sink {
        /** Opaque VideoConfig bytes, including any avcC/hvcC wrapper. Validate in the codec layer.
         * Data is borrowed only during the callback; clone to retain. Must return promptly. */
        void configuration(byte[] data)throws IOException;
        /** Authenticated compressed bytes and their authenticated 128-byte header.
         * Preserve timestamps/header for the media scheduler. Buffers are borrowed. */
        void frame(byte[] header,byte[] data)throws IOException;
    }
    /** Configuration header is clear on the wire, unlike the authenticated frame
     * header. Preserve it for format flags without granting it frame authenticity. */
    public interface HeaderSink extends Sink {
        void configuration(byte[] header,byte[] data)throws IOException;
    }
    private static final int HEADER=128,MAX_BODY=8*1024*1024,MAX_CONFIG=1024*1024;
    private final byte[] key,header=new byte[HEADER];
    private final Sink sink;
    private byte[] body;
    private int headerUsed,bodyUsed,opcode;
    private long counter;
    private long packets;
    private boolean exhausted,closed,configured,feeding;

    /** Key must be derived from this verified session and this screen streamConnectionID,
     * with DataStream-Salt<decimal ID>/DataStream-Output-Encryption-Key (not the iAP seed). */
    public ScreenDecoder(byte[] receiveKey,Sink sink){
        if(receiveKey==null||receiveKey.length!=32||sink==null)throw new IllegalArgumentException("Screen key/sink");
        key=receiveKey.clone();this.sink=sink;
    }
    public synchronized void feed(byte[] data,int offset,int length)throws IOException{
        if(data==null||offset<0||length<0||offset>data.length-length)throw new IllegalArgumentException("Screen input range");
        check();if(feeding)throw new IOException("Reentrant screen input");feeding=true;
        try{
            while(length>0){
                if(headerUsed<HEADER){
                    int n=Math.min(length,HEADER-headerUsed);System.arraycopy(data,offset,header,headerUsed,n);
                    offset+=n;length-=n;headerUsed+=n;
                    if(headerUsed<HEADER)continue;
                    long size=(header[0]&255L)|((header[1]&255L)<<8)|((header[2]&255L)<<16)|((header[3]&255L)<<24);
                    opcode=header[4]&255;
                    if(opcode!=0&&opcode!=1)throw new IOException("Unsupported screen packet");
                    if(size<1||size>MAX_BODY||opcode==1&&size>MAX_CONFIG||opcode==0&&size<17)
                        throw new IOException("Screen body length");
                    if(opcode==0&&(!configured||exhausted))throw new IOException("Screen frame state");
                    body=new byte[(int)size];
                }
                int n=Math.min(length,body.length-bodyUsed);System.arraycopy(data,offset,body,bodyUsed,n);
                offset+=n;length-=n;bodyUsed+=n;
                if(bodyUsed!=body.length)continue;
                if(opcode==1){
                    if(sink instanceof HeaderSink)((HeaderSink)sink).configuration(header,body);else sink.configuration(body);
                    check();configured=true;
                }
                else{
                    byte[] plain=TunnelCrypto.decryptScreen(key,counter,header,body);
                    try{
                        if(counter==Long.MAX_VALUE)exhausted=true;else counter++;
                        sink.frame(header,plain);check();
                    }finally{Arrays.fill(plain,(byte)0);}
                }
                Arrays.fill(header,(byte)0);Arrays.fill(body,(byte)0);body=null;headerUsed=bodyUsed=0;
                packets++;
            }
        }catch(IOException e){close();throw e;}
         catch(RuntimeException e){close();throw e;}
         catch(LinkageError e){close();throw e;}
         finally{feeding=false;}
    }
    /** Clean EOF also retires the key. Partial header/body is an error, never a short frame. */
    public synchronized void endOfInput()throws IOException{
        check();boolean partial=headerUsed!=0||bodyUsed!=0;close();
        if(partial)throw new IOException("Truncated screen packet");
    }
    private void check()throws IOException{if(closed)throw new IOException("Screen decoder closed");}
    synchronized boolean hasPartialInput(){return headerUsed!=0;}
    synchronized long completedPackets(){return packets;}
    public synchronized void close(){
        if(closed)return;closed=true;Arrays.fill(key,(byte)0);Arrays.fill(header,(byte)0);
        if(body!=null)Arrays.fill(body,(byte)0);body=null;headerUsed=bodyUsed=0;
    }
}
