/* SPDX-License-Identifier: GPL-3.0-only. See NOTICE.md. */
package com.veloster.carplay.tunnel;
import java.io.*;
import java.util.Arrays;

/** Input-key PCM microphone RTP profile, independently bounded. No plaintext
 * transport fallback; counters belong to this one never-reopened input lease. */
public final class AudioPacketEncoder implements Closeable {
    private final byte[] key;private final AudioWireFormat format;private final int payloadType;
    private int sequence;private long timestamp,counter;private boolean closed,exhausted;
    public AudioPacketEncoder(byte[] key,AudioWireFormat format,int payloadType){
        if(key==null||key.length!=32||format==null||format.aac||format.channels!=1||(format.sampleRate!=16000&&format.sampleRate!=8000)||payloadType!=100)throw new IllegalArgumentException("Microphone wire profile");
        this.key=key.clone();this.format=format;this.payloadType=payloadType;
    }
    public synchronized byte[] encode(byte[] littleEndian,int samples)throws IOException {
        if(closed||exhausted)throw new IOException("Microphone encoder retired");
        if(samples<format.sampleRate/200||samples>format.sampleRate*60/1000||littleEndian==null||littleEndian.length!=samples*format.channels*2)throw new IOException("Microphone frame bounds");
        byte[] header=new byte[12],nonce=new byte[12],aad=null,plain=null,sealed=null;
        header[0]=(byte)0x80;header[1]=(byte)payloadType;header[2]=(byte)(sequence>>>8);header[3]=(byte)sequence;
        for(int i=0;i<4;i++)header[4+i]=(byte)(timestamp>>>(24-8*i));
        for(int i=0;i<8;i++)nonce[4+i]=(byte)(counter>>>(8*i));
        try {
            aad=Arrays.copyOfRange(header,4,12);plain=new byte[littleEndian.length];
            for(int i=0;i<plain.length;i+=2){plain[i]=littleEndian[i+1];plain[i+1]=littleEndian[i];}
            sealed=TunnelCrypto.sealAudio(key,nonce,plain,aad);
            if(sealed==null||sealed.length!=plain.length+16)throw new IOException("Microphone sealed frame bounds");
            byte[] wire=new byte[12+sealed.length+8];System.arraycopy(header,0,wire,0,12);System.arraycopy(sealed,0,wire,12,sealed.length);System.arraycopy(nonce,4,wire,12+sealed.length,8);
            sequence=(sequence+1)&65535;timestamp=(timestamp+samples)&0xffffffffL;
            if(counter==-1L)exhausted=true;else counter++;
            return wire;
        }finally{Arrays.fill(header,(byte)0);Arrays.fill(nonce,(byte)0);if(aad!=null)Arrays.fill(aad,(byte)0);if(plain!=null)Arrays.fill(plain,(byte)0);if(sealed!=null)Arrays.fill(sealed,(byte)0);}
    }
    public synchronized void close(){closed=true;Arrays.fill(key,(byte)0);}
}
