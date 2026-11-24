/* SPDX-License-Identifier: GPL-3.0-only. AudioStream wire profile; see NOTICE.md. */
package com.veloster.carplay.tunnel;
import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;

/** Authenticated fixed-header RTP datagrams. This is not a jitter buffer or a codec.
 * Timestamp and SSRC are authenticated; sequence/marker/payload-type are NOT in the AAD.
 * Never use sequence alone for replay protection or to advance a trusted media clock.
 * Bad UDP packets leave valid receive state intact. A 64-nonce window permits reordering.
 */
public final class AudioPacketDecoder implements Closeable {
    public static final int MAX_PACKET=65507;
    public static final class Packet implements Closeable {
        public final long nonce,timestamp,ssrc;
        public final int sequence,payloadType;
        public final boolean marker;
        /** Owned by the caller until close(); may be handed to one bounded audio queue. */
        public final byte[] payload;
        private Packet(byte[] wire,byte[] payload,long nonce){
            this.nonce=nonce;this.payload=payload;timestamp=u32(wire,4);ssrc=u32(wire,8);
            sequence=((wire[2]&255)<<8)|(wire[3]&255);payloadType=wire[1]&127;marker=(wire[1]&128)!=0;
        }
        public void close(){Arrays.fill(payload,(byte)0);}
    }
    private final byte[] key;
    private boolean closed,seen;
    private long highest,window,ssrc;
    public AudioPacketDecoder(byte[] key){
        if(key==null||key.length!=32)throw new IllegalArgumentException("Audio key length");
        this.key=key.clone();
    }
    public synchronized Packet decode(byte[] wire,int length)throws IOException{
        if(closed)throw new IOException("Audio decoder closed");
        if(wire==null||length<37||length>MAX_PACKET||length>wire.length||wire[0]!=(byte)0x80)
            throw new IOException("Unsupported audio RTP packet");
        long counter=0;for(int i=0;i<8;i++)counter|=(long)(wire[length-8+i]&255)<<(8*i);
        boolean newer=!seen||unsignedGreater(counter,highest);
        long distance=newer?counter-highest:highest-counter;
        // Unsigned subtraction can wrap signed long. Distances outside this small window
        // are always stale (or reset the window when advancing), never shift by a wrapped value.
        if(seen&&!newer&&(distance<0||distance>=64||(window&(1L<<(int)distance))!=0))
            throw new IOException("Audio replay or stale nonce");
        byte[] nonce=new byte[12],aad=Arrays.copyOfRange(wire,4,12),sealed=Arrays.copyOfRange(wire,12,length-8),plain=null;
        System.arraycopy(wire,length-8,nonce,4,8);
        try{
            plain=TunnelCrypto.openAudio(key,nonce,sealed,aad);
            if(seen&&ssrc!=u32(wire,8))throw new IOException("Audio SSRC changed");
            Packet packet=new Packet(wire,plain,counter);
            if(!seen){highest=counter;window=1;ssrc=packet.ssrc;seen=true;}
            else if(newer){window=distance<0||distance>=64?1:(window<<(int)distance)|1;highest=counter;}
            else window|=1L<<(int)distance;
            plain=null;return packet;
        }finally{
            Arrays.fill(nonce,(byte)0);Arrays.fill(aad,(byte)0);Arrays.fill(sealed,(byte)0);
            if(plain!=null)Arrays.fill(plain,(byte)0);
        }
    }
    static boolean unsignedGreater(long a,long b){return (a^Long.MIN_VALUE)>(b^Long.MIN_VALUE);}
    private static long u32(byte[] b,int o){return ((long)(b[o]&255)<<24)|((long)(b[o+1]&255)<<16)|((long)(b[o+2]&255)<<8)|(b[o+3]&255);}
    public synchronized void close(){closed=true;Arrays.fill(key,(byte)0);window=0;}
}
