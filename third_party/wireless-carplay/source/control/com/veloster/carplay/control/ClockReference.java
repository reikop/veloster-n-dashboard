/* SPDX-License-Identifier: GPL-3.0-only. Local projection of a session network clock. */
package com.veloster.carplay.control;
import java.io.IOException;

/** Coherent NTP64/monotonic observation. No wall-clock adjustments, RTP anchoring,
 * output scheduling or claim of peer authentication. Consumers obtain a fresh
 * reference for each scheduling decision and recheck the owning clock generation. */
public final class ClockReference {
    public interface Source {ClockReference reference()throws IOException;}
    public static final long MAX_AGE_NANOS=10000000000L;
    public static final long MAX_DISTANCE_NANOS=10000000000L;
    private static final long SCALE=4294967296L;
    public final long ntpTime,monotonicNanos,generation;
    final Object owner;
    private final long measuredNanos;
    ClockReference(Object owner,long ntp,long mono,long measured,long generation){
        this.owner=owner;ntpTime=ntp;monotonicNanos=mono;measuredNanos=measured;this.generation=generation;
    }
    /** Relative subtraction tolerates signed System.nanoTime wrap; negative age
     * denotes an invalid/future observation, not a fresh clock. */
    public boolean isFresh(long now){long age=now-measuredNanos;return age>=0&&age<=MAX_AGE_NANOS;}
    public long measurementAgeNanos(long now)throws IOException{
        if(!isFresh(now))throw new IOException("Network clock reference expired");return now-measuredNanos;
    }
    /** NTP subtraction is modulo 2^64 and signed only AFTER subtraction. The
     * ten-second horizon excludes ambiguous half-era values and huge deadlines.
     * Integer split conversion keeps the full wire bits and stays below overflow. */
    public long toMonotonicNanos(long ntp,long now)throws IOException{
        if(!isFresh(now))throw new IOException("Network clock reference expired");
        long delta=ntp-ntpTime,limit=10*SCALE;
        if(delta < -limit||delta > limit)throw new IOException("Network timestamp outside local horizon");
        long nanos=(delta/SCALE)*1000000000L+(delta%SCALE)*1000000000L/SCALE;
        long target=monotonicNanos+nanos,distance=target-now;
        if(distance < -MAX_DISTANCE_NANOS||distance > MAX_DISTANCE_NANOS)throw new IOException("Network deadline outside local horizon");
        return target;
    }
    /** Project an observed local device-head time, retaining modulo-2^64 NTP bits. */
    public long toNtpTime(long monotonic,long now)throws IOException{
        if(!isFresh(now))throw new IOException("Network clock reference expired");
        long delta=monotonic-monotonicNanos,distance=monotonic-now;
        if(delta < -MAX_DISTANCE_NANOS||delta > MAX_DISTANCE_NANOS||
            distance < -MAX_DISTANCE_NANOS||distance > MAX_DISTANCE_NANOS)
            throw new IOException("Local observation outside network horizon");
        return ntpTime+(delta/1000000000L)*SCALE+(delta%1000000000L)*SCALE/1000000000L;
    }
}
