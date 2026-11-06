/* SPDX-License-Identifier: GPL-3.0-only. Independently written local output-rate fit. */
package com.veloster.carplay.media;

/** Bounded least-squares estimate of physical samples per monotonic second.
 * Guard thresholds are receiver policy, not an Apple limit or a hardware accuracy
 * guarantee. No extrapolated sample counts and no resampling occur here.
 * The AudioSink monitor owns this object; callers receive immutable estimates. */
public final class AudioRateEstimator {
    public static final long MIN_SPAN_NS=8000000000L,MAX_SPAN_NS=32000000000L;
    private static final long FIT_NS=250000000L,MAX_READ_NS=5000000L;
    private static final int CAPACITY=4096,MIN_POINTS=24;
    private final int nominal;
    private final long[] times=new long[CAPACITY],frames=new long[CAPACITY];
    private int size,start;private long lastFit;private boolean fitted;private AudioHead previous;private Estimate estimate;
    public static final class Estimate {
        public final int nominalRate,points;
        public final double sampleRate,residualRmsFrames,slopeStandardError;
        public final long continuity,firstNanos,lastNanos,lastFrames;
        private Estimate(int nominal,int points,double rate,double rms,double error,long continuity,long first,long last,long frames){
            nominalRate=nominal;this.points=points;sampleRate=rate;residualRmsFrames=rms;slopeStandardError=error;
            this.continuity=continuity;firstNanos=first;lastNanos=last;lastFrames=frames;
        }
        public boolean matches(AudioHead head,long now){
            return head!=null&&head.fresh(now)&&head.rateContinuous&&head.continuity==continuity&&
                head.frames>=lastFrames&&head.monotonicNanos-lastNanos>=0&&now-lastNanos>=0&&now-lastNanos<=1000000000L;
        }
    }
    public AudioRateEstimator(int nominal){if(nominal<=0)throw new IllegalArgumentException("Sample rate");this.nominal=nominal;}
    public void reset(){size=0;start=0;fitted=false;previous=null;estimate=null;}
    public Estimate estimate(AudioHead head,long now){return estimate!=null&&estimate.matches(head,now)?estimate:null;}
    public void observe(AudioHead head,long now){
        if(head==null||!head.fresh(now)||!head.rateContinuous||head.readDurationNanos>MAX_READ_NS){reset();return;}
        if(previous!=null){
            long elapsed=head.monotonicNanos-previous.monotonicNanos;
            if(head.continuity!=previous.continuity||elapsed<0||elapsed>AudioHead.MAX_AGE_NANOS||head.frames<previous.frames)reset();
            else if(elapsed==0){if(head.frames!=previous.frames)reset();else return;}
            else if(head.frames==previous.frames){reset();return;} // Re-stamped idle heads are not a sample clock.
        }
        previous=head;
        while(size>0&&head.monotonicNanos-time(0)>MAX_SPAN_NS)removeFirst();
        if(size==CAPACITY)removeFirst();
        int at=(start+size)%CAPACITY;times[at]=head.monotonicNanos;frames[at]=head.frames;size++;
        // Preserve every advancing observation: periodic subsampling can alias
        // the mixer's quantized counter. Only the fit computation is throttled.
        if(fitted&&head.monotonicNanos-lastFit<FIT_NS)return;
        fitted=true;lastFit=head.monotonicNanos;
        estimate=null;
        if(size<MIN_POINTS||time(size-1)-time(0)<MIN_SPAN_NS)return;
        // Subtract integral origins before conversion: counters may exceed 2^53,
        // and System.nanoTime may cross the signed-long boundary.
        double meanX=0,meanY=0;
        for(int i=0;i<size;i++){meanX+=(time(i)-time(0))/1e9;meanY+=frame(i)-frame(0);}
        meanX/=size;meanY/=size;double xx=0,xy=0;
        for(int i=0;i<size;i++){double x=(time(i)-time(0))/1e9-meanX,y=(frame(i)-frame(0))-meanY;xx+=x*x;xy+=x*y;}
        if(xx<=0)return;double rate=xy/xx,sum=0,max=0;
        for(int i=0;i<size;i++){double residual=(frame(i)-frame(0))-meanY-rate*((time(i)-time(0))/1e9-meanX);sum+=residual*residual;max=Math.max(max,Math.abs(residual));}
        double rms=Math.sqrt(sum/size),error=Math.sqrt(sum/(size-2)/xx);
        // Quantized mixer heads and scheduling jitter can make a fit misleading.
        // These dispersion checks are not a statistical confidence guarantee.
        if(Double.isNaN(rate)||Double.isInfinite(rate)||Math.abs(rate/nominal-1)>.005||
            rms>nominal*.010||max>nominal*.025||2*error/nominal>.000250)return;
        estimate=new Estimate(nominal,size,rate,rms,error,head.continuity,time(0),time(size-1),frame(size-1));
    }
    private long time(int i){return times[(start+i)%CAPACITY];}
    private long frame(int i){return frames[(start+i)%CAPACITY];}
    private void removeFirst(){size--;start=(start+1)%CAPACITY;}
}
