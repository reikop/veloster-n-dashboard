/* SPDX-License-Identifier: GPL-3.0-only. See NOTICE.md. */
package com.veloster.carplay.tunnel;
import java.io.IOException;

/** Supported wire descriptions, not a declaration that an Android codec is available.
 * Exactly one negotiated format is required. Unsupported formats never fall back to PCM.
 */
public final class AudioWireFormat {
    public final long bits;
    public final int sampleRate,channels;
    public final boolean aac;
    /** The access-unit duration is codec-specific; PCM derives it from the payload. */
    public final int aacFrameSamples,aacProfile;
    private AudioWireFormat(long bits,int rate,int channels,boolean aac,int aacFrameSamples,int aacProfile){
        this.bits=bits;sampleRate=rate;this.channels=channels;this.aac=aac;
        this.aacFrameSamples=aacFrameSamples;this.aacProfile=aacProfile;
    }
    /** Explicit raw AAC-ELD 48 kHz stereo path. Kept out of format negotiation until
     * the target's format mask and runtime 480-frame codec support are verified. */
    public static AudioWireFormat aacEld480(){
        return new AudioWireFormat(0,48000,2,true,480,39);
    }
    public static AudioWireFormat parse(long bits)throws IOException{
        if(bits==0x400000L||bits==0x800000L)return new AudioWireFormat(bits,bits==0x400000L?44100:48000,2,true,1024,2);
        // Pinned third-party SDK mirror maps bit25 to AAC-ELD 48 kHz stereo.
        // A separate adapter capture also shows bit25; neither is official
        // Apple documentation or this receiver's capability declaration.
        if(bits==0x02000000L)return new AudioWireFormat(bits,48000,2,true,480,39);
        // Pinned SDK v320.17: bit26 is AAC-ELD 16 kHz mono, 480 samples/AU.
        // A parsed format is not a capability declaration or advertisement.
        if(bits==0x04000000L)return new AudioWireFormat(bits,16000,1,true,480,39);
        int[] rates={8000,16000,24000,32000,44100,48000};int[] shifts={2,4,6,8,10,14};
        for(int i=0;i<rates.length;i++)for(int ch=1;ch<=2;ch++)
            if(bits==(1L<<(shifts[i]+ch-1)))return new AudioWireFormat(bits,rates[i],ch,false,0,0);
        throw new IOException("Unsupported or ambiguous audio format");
    }
    /** AAC-LC AudioSpecificConfig for raw access units (one decrypted datagram per AU).
     * Caller must configure the decoder for raw AAC, not ADTS, when using these bytes. */
    public byte[] codecSpecificData()throws IOException{
        if(!aac)throw new IOException("PCM has no AAC config");
        if(aacProfile==39){
            if(aacFrameSamples!=480)throw new IOException("Unsupported AAC-ELD config");
            if(sampleRate==48000&&channels==2)return new byte[]{(byte)0xf8,(byte)0xe6,0x50,0x00};
            if(sampleRate==16000&&channels==1)return new byte[]{(byte)0xf8,(byte)0xf0,0x30,0x00};
            throw new IOException("Unsupported AAC-ELD config");
        }
        int value=(2<<11)|((sampleRate==44100?4:3)<<7)|(channels<<3);
        return new byte[]{(byte)(value>>>8),(byte)value};
    }
    /** AirPlay LPCM is signed16 big endian. Android AudioTrack consumes little endian.
     * A new buffer is returned; malformed/incomplete sample frames cannot be played. */
    public byte[] pcm16LittleEndian(byte[] payload)throws IOException{
        if(aac||payload==null||payload.length==0||payload.length%(channels*2)!=0||payload.length>AudioPacketDecoder.MAX_PACKET-36)
            throw new IOException("Invalid PCM sample frames");
        byte[] out=new byte[payload.length];for(int i=0;i<out.length;i+=2){out[i]=payload[i+1];out[i+1]=payload[i];}return out;
    }
}
