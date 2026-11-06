/* SPDX-License-Identifier: GPL-3.0-only. Unsigned wheel measurements; no slip inference. */
package com.veloster.carplay.location.vehicle;

import java.util.Arrays;

/** One boot/connection generation. Unknown direction and slip remain explicit.
 * The 1s age and 250ms assembly bounds are application policies, not OEM timeouts.
 * A new received frame is not proof of the MICOM's original sensor measurement age. */
public final class WheelSpeedSource {
    public static final long MAX_AGE_MILLIS=1000,MAX_ASSEMBLY_MILLIS=250;
    public interface Listener {void sample(Snapshot sample);}
    public static final class Snapshot {
        public final String bootId;public final long sourceGeneration,receivedElapsedMillis,sequence;
        /** Invalidation epoch of the creating source at creation time. */
        public final long sourceEpoch;
        /** Private identity token of the creating source; compared only by reference. */
        private final Object owner;
        public final int flMillimetresPerSecond,frMillimetresPerSecond,rlMillimetresPerSecond,rrMillimetresPerSecond;
        public final int leftMillimetresPerSecond,rightMillimetresPerSecond,speedMillimetresPerSecond;
        public final char transmission='U';public final boolean slipKnown=false;public final int slipDetect=-1;
        private Snapshot(Object owner,String boot,long generation,long epoch,long time,long sequence,int[] raw){
            this.owner=owner;sourceEpoch=epoch;
            bootId=boot;sourceGeneration=generation;receivedElapsedMillis=time;this.sequence=sequence;
            flMillimetresPerSecond=convert(raw[0],1);frMillimetresPerSecond=convert(raw[1],1);
            rlMillimetresPerSecond=convert(raw[2],1);rrMillimetresPerSecond=convert(raw[3],1);
            leftMillimetresPerSecond=convert(raw[0]+raw[2],2);rightMillimetresPerSecond=convert(raw[1]+raw[3],2);
            speedMillimetresPerSecond=convert(raw[0]+raw[1]+raw[2]+raw[3],4);
        }
        private static int convert(int sum,int count){long denominator=3600L*count;return (int)((sum*31250L+denominator/2)/denominator);}
    }
    private final String bootId;private final long generation;private final Listener listener;private final HscanWheelDecoder decoder;
    private volatile boolean active=true;private volatile Snapshot latest;private byte[] previousWheel;
    private long lastReceived=-1,sequence;
    /** Unforgeable per-instance identity; never exposed. */
    private final Object identity=new Object();
    /** Written only under this monitor (accept/discontinuity); read lock-free. */
    private volatile long epoch;
    public WheelSpeedSource(String bootId,long generation,Listener listener){this(bootId,generation,listener,1);}
    /** Test boundary for epoch exhaustion. */
    WheelSpeedSource(String bootId,long generation,Listener listener,long initialEpoch){
        if(bootId==null||!bootId.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")||generation<1||listener==null||initialEpoch<1)throw new IllegalArgumentException("Wheel source scope");
        this.bootId=bootId;this.generation=generation;this.listener=listener;epoch=initialEpoch;
        decoder=new HscanWheelDecoder(new HscanWheelDecoder.Sink(){
            public void invalid(){revokeRetained();}
            public void wheel(byte[] data,long first,long completed){
                if(!active)return;
                if(completed<first||completed-first>MAX_ASSEMBLY_MILLIS){invalid();return;}
                int[] raw=new int[4];for(int i=0;i<4;i++){raw[i]=(data[2*i]&255)|((data[2*i+1]&63)<<8);
                    // Conservative OEM consumer range policy; 0x3fff is never a measured zero.
                    if(raw[i]>=16352){invalid();return;}}
                // An exact duplicate does not refresh reception age. Upper fields are opaque here;
                // no undocumented checksum/alive-counter rule manufactures sensor freshness.
                if(Arrays.equals(previousWheel,data))return;previousWheel=data.clone();
                if(sequence==Long.MAX_VALUE){close();return;}
                Snapshot next=new Snapshot(identity,WheelSpeedSource.this.bootId,WheelSpeedSource.this.generation,epoch,first,++sequence,raw);latest=next;
                if(active)WheelSpeedSource.this.listener.sample(next);
            }
        });
    }
    public synchronized void accept(byte[] bytes,int offset,int length,long receivedElapsedMillis){
        if(bytes==null||offset<0||length<0||length>4096||offset>bytes.length-length||receivedElapsedMillis<0)throw new IllegalArgumentException("Receive bounds");
        if(!active)return;
        if(receivedElapsedMillis<lastReceived){discontinuity();lastReceived=receivedElapsedMillis;return;}
        lastReceived=receivedElapsedMillis;decoder.accept(bytes,offset,length,receivedElapsedMillis);
    }
    public synchronized void discontinuity(){revokeRetained();decoder.reset();}
    /** Caller holds this monitor (accept/discontinuity paths). Revokes latest and every
     * retained sample of the current epoch. Duplicate-payload history is deliberately kept:
     * an identical payload after invalidation must not become a refreshed measurement. */
    private void revokeRetained(){
        latest=null;
        if(epoch==Long.MAX_VALUE){close();return;}
        epoch++;
    }
    public Snapshot snapshot(String currentBootId,long nowElapsedMillis){Snapshot value=latest;return isCurrent(value,currentBootId,nowElapsedMillis)?value:null;}
    /** Latest-only fence for queued callbacks: any newer sample supersedes value. */
    public boolean isCurrent(Snapshot value,String currentBootId,long nowElapsedMillis){
        return active&&value!=null&&value==latest&&value.owner==identity&&bootId.equals(currentBootId)&&value.sourceGeneration==generation
            &&nowElapsedMillis>=value.receivedElapsedMillis&&nowElapsedMillis-value.receivedElapsedMillis<=MAX_AGE_MILLIS;
    }
    /** Retained-sample validity for future multi-sample batching. A later good sample in the
     * same epoch does not supersede value; decoder invalid(), discontinuity, receive-clock
     * regression, epoch exhaustion and close revoke it. Not a substitute for isCurrent. */
    public boolean isRetainedValid(Snapshot value,String currentBootId,long nowElapsedMillis){
        return active&&value!=null&&value.owner==identity&&bootId.equals(currentBootId)&&value.sourceGeneration==generation
            &&value.sourceEpoch==epoch
            &&nowElapsedMillis>=value.receivedElapsedMillis&&nowElapsedMillis-value.receivedElapsedMillis<=MAX_AGE_MILLIS;
    }
    /** Nonblocking revocation. A caller must fence queued callback work with isCurrent. */
    public void close(){active=false;latest=null;}
}
