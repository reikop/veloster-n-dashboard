/* SPDX-License-Identifier: GPL-3.0-only. Observed device-head clock, not speaker time. */
package com.veloster.carplay.media;

/** Immutable local physical sample counter and the midpoint of its API observation.
 * Does not extrapolate while paused, idle or starved. It is not an RTP timestamp. */
public final class AudioHead {
    public static final long MAX_AGE_NANOS=250000000L;
    public final long frames,monotonicNanos,readDurationNanos;
    public final long continuity;
    public final boolean rateContinuous;
    public AudioHead(long frames,long monotonic,long duration){
        this(frames,monotonic,duration,0,true);
    }
    public AudioHead(long frames,long monotonic,long duration,long continuity,boolean continuous){
        if(frames<0||duration<0)throw new IllegalArgumentException("Audio head bounds");
        this.frames=frames;monotonicNanos=monotonic;readDurationNanos=duration;
        this.continuity=continuity;rateContinuous=continuous;
    }
    public boolean fresh(long now){long age=now-monotonicNanos;return age>=0&&age<=MAX_AGE_NANOS&&readDurationNanos<=MAX_AGE_NANOS;}
}
