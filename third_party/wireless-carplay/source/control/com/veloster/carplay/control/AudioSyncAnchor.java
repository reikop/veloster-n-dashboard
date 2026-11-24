/* SPDX-License-Identifier: GPL-3.0-only. Session-owned RTP / peer-NTP mapping. */
package com.veloster.carplay.control;
import com.veloster.carplay.tunnel.AudioSyncPacket;
import java.io.IOException;

/** One stream's bounded anchor, never a global clock. The supplied source must
 * revalidate its verified session. A packet origin filter is not authentication.
 * Local policy: 3-second anchor age, 4-second sample horizon, 2-second deadline
 * horizon and 50ms consistency limit; these are not Apple interoperability limits. */
public final class AudioSyncAnchor {
    public static final long MAX_AGE_NS=3000000000L;
    private final ClockReference.Source source;private final int rate;
    private ClockReference clock;private long rtp,ntp,arrived;private boolean valid,ownerSeen;private Object sourceOwner;
    public AudioSyncAnchor(ClockReference.Source source,int rate){if(source==null||rate<8000||rate>192000)throw new IllegalArgumentException("Audio sync clock");this.source=source;this.rate=rate;}
    public synchronized void clear(){valid=false;clock=null;}
    private ClockReference current(ClockReference value,long now)throws IOException{
        if(value==null||!value.isFresh(now))return null;
        if(!ownerSeen){sourceOwner=value.owner;ownerSeen=true;}
        else if(sourceOwner!=value.owner){clear();return null;}
        if(valid&&(clock.owner!=value.owner||clock.generation!=value.generation)){clear();return null;}return value;
    }
    public synchronized boolean update(AudioSyncPacket packet,long authenticatedRtp)throws IOException{
        ClockReference value=source.reference();return update(packet,authenticatedRtp,value,System.nanoTime());
    }
    public synchronized boolean update(AudioSyncPacket packet,long authenticatedRtp,long now)throws IOException{
        return update(packet,authenticatedRtp,source.reference(),now);
    }
    private boolean update(AudioSyncPacket packet,long authenticatedRtp,ClockReference reference,long now)throws IOException{
        ClockReference value=current(reference,now);if(value==null)return false;
        if(authenticatedRtp<0||authenticatedRtp>0xffffffffL||packet.latencyFrames>2L*rate)return false;
        int proximity=(int)(packet.timestamp-authenticatedRtp);if(proximity==Integer.MIN_VALUE||Math.abs((long)proximity)>4L*rate)return false;
        long target;try{target=value.toMonotonicNanos(packet.ntpTime,now);}catch(IOException outside){return false;}
        if(Math.abs(target-now)>2000000000L)return false;
        if(valid){
            if(now-arrived<0||now-arrived>MAX_AGE_NS){clear();}
            else{
                int frames=(int)(packet.timestamp-rtp);long time=packet.ntpTime-ntp;
                if(frames<=0||frames>4L*rate||time<=0)return false;
                long expected=ntpDelta(frames,rate),error=time-expected;
                if(error < -(1L<<32)/20||error > (1L<<32)/20)return false;
            }
        }
        clock=value;rtp=packet.timestamp;ntp=packet.ntpTime;arrived=now;valid=true;return true;
    }
    public synchronized Long deadline(long timestamp)throws IOException{
        ClockReference value=source.reference();return deadline(timestamp,value,System.nanoTime());
    }
    public synchronized Long deadline(long timestamp,long now)throws IOException{
        return deadline(timestamp,source.reference(),now);
    }
    private Long deadline(long timestamp,ClockReference reference,long now)throws IOException{
        if(!valid||timestamp<0||timestamp>0xffffffffL)return null;
        ClockReference value=current(reference,now);if(value==null||!valid)return null;
        long age=now-arrived;if(age<0||age>MAX_AGE_NS){clear();return null;}
        int frames=(int)(timestamp-rtp);if(frames==Integer.MIN_VALUE||Math.abs((long)frames)>4L*rate)return null;
        long result;try{result=value.toMonotonicNanos(ntp+ntpDelta(frames,rate),now);}catch(IOException outside){return null;}
        return Math.abs(result-now)<=2000000000L?Long.valueOf(result):null;
    }
    private static long ntpDelta(long samples,int rate){return (samples/rate)*(1L<<32)+(samples%rate)*(1L<<32)/rate;}
}
