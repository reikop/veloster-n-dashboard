/* SPDX-License-Identifier: GPL-3.0-only. Bounded peer-phase feedback policy. */
package com.veloster.carplay.media;

/** Worker-owned phase servo. Error is observed local head time minus the peer
 * deadline of that SAME consumed sample. Positive error commands faster output.
 * A 5ms observation deadband, 500ppm/250ms slew and +/-0.5% total rate are local
 * policy, not a hardware/DAC accuracy guarantee. No fabricated consumption. */
public final class AudioPhaseDiscipline {
    private final int nominal;private int rate;private long last,continuity;private boolean seen;
    private double integral;
    public AudioPhaseDiscipline(int nominal){if(nominal<=0)throw new IllegalArgumentException("Audio phase rate");this.nominal=nominal;rate=nominal;}
    public void reset(){rate=nominal;integral=0;seen=false;}
    public int rate(){return rate;}
    public Integer observe(AudioHead head,long error,long now){
        if(head==null||!head.fresh(now)||!head.rateContinuous||head.readDurationNanos>5000000L||error < -250000000L||error>250000000L){reset();return null;}
        // Our own rate actuator ends one head-rate continuity interval. Preserve
        // the bounded oscillator correction across that command; invalid/idle
        // observations and explicit reset() still retire the entire servo.
        if(seen&&(head.continuity!=continuity||head.monotonicNanos-last<0))seen=false;
        if(seen&&head.monotonicNanos-last<250000000L)return Integer.valueOf(rate);
        double elapsed=seen?Math.min(1,(head.monotonicNanos-last)/1e9):.25;
        seen=true;continuity=head.continuity;last=head.monotonicNanos;
        double seconds=Math.abs(error)<=5000000L?0:error/1e9;
        integral=Math.max(-.0025,Math.min(.0025,integral+seconds*elapsed*.002));
        double correction=Math.max(-.005,Math.min(.005,seconds*.025+integral));
        int desired=Math.max((int)Math.ceil(nominal*.995),Math.min((int)Math.floor(nominal*1.005),(int)Math.round(nominal*(1+correction)))),step=Math.max(1,(int)Math.round(nominal*.0005));
        rate=Math.max(rate-step,Math.min(rate+step,desired));return Integer.valueOf(rate);
    }
}
