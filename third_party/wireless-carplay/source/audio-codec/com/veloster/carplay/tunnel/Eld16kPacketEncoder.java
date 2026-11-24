/* SPDX-License-Identifier: GPL-3.0-only. Separate unadvertised uplink backend. */
package com.veloster.carplay.tunnel;
import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;

/** One raw AAC-ELD/16k/mono/480 AU per authenticated MainAudio RTP packet.
 * The packet shape and 480-sample clock follow the pinned 2017 SDK mirror;
 * format negotiation remains disabled pending JSN/iPhone validation.
 */
public final class Eld16kPacketEncoder implements Closeable {
    private static final int MAX_AU=1416;
    private final byte[] key;
    private int sequence;private long timestamp,counter;private boolean closed,exhausted;
    public Eld16kPacketEncoder(byte[] transmitKey){
        if(transmitKey==null||transmitKey.length!=32)throw new IllegalArgumentException("ELD transmit key");
        key=transmitKey.clone();
    }
    public synchronized byte[] encode(byte[] rawAccessUnit)throws IOException{
        if(closed||exhausted)throw new IOException("ELD RTP encoder retired");
        if(rawAccessUnit==null||rawAccessUnit.length<1||rawAccessUnit.length>MAX_AU)
            throw new IOException("ELD raw access unit bounds");
        byte[] header=new byte[12],nonce=new byte[12],aad=null,sealed=null;
        header[0]=(byte)0x80;header[1]=100;header[2]=(byte)(sequence>>>8);header[3]=(byte)sequence;
        for(int i=0;i<4;i++)header[4+i]=(byte)(timestamp>>>(24-8*i));
        for(int i=0;i<8;i++)nonce[4+i]=(byte)(counter>>>(8*i));
        try{
            aad=Arrays.copyOfRange(header,4,12);
            sealed=TunnelCrypto.sealAudio(key,nonce,rawAccessUnit,aad);
            if(sealed==null||sealed.length!=rawAccessUnit.length+16)
                throw new IOException("ELD sealed AU bounds");
            byte[] wire=new byte[12+sealed.length+8];
            System.arraycopy(header,0,wire,0,12);System.arraycopy(sealed,0,wire,12,sealed.length);
            System.arraycopy(nonce,4,wire,12+sealed.length,8);
            sequence=(sequence+1)&65535;timestamp=(timestamp+480)&0xffffffffL;
            if(counter==-1L)exhausted=true;else counter++;
            return wire;
        }finally{Arrays.fill(header,(byte)0);Arrays.fill(nonce,(byte)0);
            if(aad!=null)Arrays.fill(aad,(byte)0);if(sealed!=null)Arrays.fill(sealed,(byte)0);}
    }
    public synchronized void close(){closed=true;Arrays.fill(key,(byte)0);}
}
