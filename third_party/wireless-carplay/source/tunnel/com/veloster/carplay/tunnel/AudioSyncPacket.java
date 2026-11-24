/* SPDX-License-Identifier: GPL-3.0-only. Independently written bounded PT84 parser. */
package com.veloster.carplay.tunnel;
import java.io.IOException;

/** Restricted AirPlay sync profile: NTP64 refers to the delayed RTP sample at
 * offset 4; the source RTP at offset 16 supplies the requested sample latency.
 * This UDP message has NO cryptographic authenticator. Parsing is not trust;
 * the caller must bind peer/port, current media lease and authenticated RTP.
 * The legacy flags=7 extra-latency interpretation is intentionally unsupported. */
public final class AudioSyncPacket {
    public final long timestamp,ntpTime,sourceTimestamp,latencyFrames;
    private AudioSyncPacket(byte[] data){
        timestamp=u32(data,4);ntpTime=(u32(data,8)<<32)|u32(data,12);sourceTimestamp=u32(data,16);
        latencyFrames=(sourceTimestamp-timestamp)&0xffffffffL;
    }
    public static AudioSyncPacket parse(byte[] data,int length)throws IOException{
        if(data==null||length!=20||data.length<length||(data[0]!=(byte)0x80&&data[0]!=(byte)0x90)||
            data[1]!=(byte)0xd4||data[2]!=0||data[3]!=4)throw new IOException("Unsupported audio sync profile");
        AudioSyncPacket result=new AudioSyncPacket(data);
        if(result.latencyFrames>768000)throw new IOException("Audio sync latency bounds");return result;
    }
    private static long u32(byte[] data,int at){long n=0;for(int i=0;i<4;i++)n=(n<<8)|(data[at+i]&255);return n;}
}
