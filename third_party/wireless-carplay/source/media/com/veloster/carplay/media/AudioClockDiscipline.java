/* SPDX-License-Identifier: GPL-3.0-only. Independently written local clock discipline. */
package com.veloster.carplay.media;
import java.io.IOException;

/** Worker-owned local nominal-rate correction. This is not peer clock/phase sync.
 * Only observed consumption enters the estimator; commands never invent samples.
 * Integer-Hz playback-rate control also changes pitch. All limits are local policy.
 */
public final class AudioClockDiscipline {
    public interface Device {
        /** Return false if rejected without changing the current rate. */
        boolean rate(int samplesPerSecond)throws IOException;
    }
    private final int nominal;private final Device device;private final AudioRateEstimator estimator;
    private int commanded,changes;private boolean disabled,waiting;private long waitingSince;
    public AudioClockDiscipline(int nominal,Device device){
        if(nominal<=0||device==null)throw new IllegalArgumentException("Audio discipline");
        this.nominal=nominal;this.device=device;commanded=nominal;estimator=new AudioRateEstimator(nominal);
    }
    public int commandedRate(){return commanded;}
    public int changes(){return changes;}
    public boolean disabled(){return disabled;}
    /** Discontinuity/pause: restore nominal, clear history, never carry an old fit. */
    public boolean reset()throws IOException{
        estimator.reset();waiting=false;if(commanded==nominal)return false;
        if(!device.rate(nominal))throw new IOException("Audio nominal rate restore rejected");
        commanded=nominal;changes++;return true;
    }
    /** True means the actual device command changed and head continuity must end. */
    public boolean observe(AudioHead head,long now)throws IOException{
        if(disabled)return false;
        if(head==null||!head.fresh(now)||!head.rateContinuous)return reset();
        estimator.observe(head,now);AudioRateEstimator.Estimate fit=estimator.estimate(head,now);
        if(fit==null){
            if(!waiting){waiting=true;waitingSince=now;}
            // An oscillator jump can move a corrected output outside the fit's
            // admissible range. Do not keep an unobservable correction forever.
            if(now-waitingSince>40000000000L)return reset();
            return false;
        }
        waiting=false;
        double relative=nominal/fit.sampleRate-1;
        // Do not chase fit dispersion or integer-Hz quantization. This is not a
        // statistical guarantee; API17 heads are mixer observations, not DAC time.
        double deadband=Math.max(.000100,Math.max(3*fit.slopeStandardError/nominal,1.0/nominal));
        if(Math.abs(relative)<=deadband)return false;
        double step=Math.max(-.000500,Math.min(.000500,relative));
        int next=(int)Math.round(commanded*(1+step));
        next=Math.max((int)Math.ceil(nominal*.995),Math.min((int)Math.floor(nominal*1.005),next));
        if(next==commanded)return false;
        if(!device.rate(next)){
            // Unsupported rate control must not leave a partially corrected track.
            disabled=true;return reset();
        }
        commanded=next;changes++;estimator.reset();waiting=false;return true;
    }
}
