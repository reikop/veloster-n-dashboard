/* SPDX-License-Identifier: GPL-3.0-only. See media/NOTICE.md. */
package com.veloster.carplay.media;
import com.veloster.carplay.tunnel.AudioDataStream;
import com.veloster.carplay.tunnel.AudioPacketDecoder;
import com.veloster.carplay.tunnel.AudioWireFormat;
import com.veloster.carplay.tunnel.AudioSyncPacket;
import com.veloster.carplay.control.AudioSyncAnchor;
import com.veloster.carplay.control.AudioDescription;
import com.veloster.carplay.control.ClockReference;
import com.veloster.carplay.control.ModeControl;
import com.veloster.carplay.pairing.PairVerify;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded, timestamp-ordered audio worker. All output/codec calls belong to this worker.
 * The RTP timestamp supplied here must already have passed stream authentication.
 * Arrival/reorder timing is the fallback. Optional peer-bound RTP/NTP anchors gate
 * initial playback and discipline actual mapped-head phase on supported outputs.
 */
public final class AudioSink implements AudioDataStream.SynchronizedSink,Closeable {
    public interface Output {
        boolean submit(byte[] payload,long ptsUs)throws IOException;
        /** Prepare/write without consuming PCM until start(). Apply gain before starting. */
        void pump(float gain)throws IOException;
        /** Start consuming prepared PCM once; called only on the owning worker. */
        void start()throws IOException;
        /** Queue EOS and return true only after codec output AND the track have drained. */
        boolean finish()throws IOException;
        /** written PCM frames, played PCM frames, inserted silence frames. */
        long[] stats();
        void close();
    }
    public interface PositionedOutput extends Output {AudioPlaybackTimeline.Position position();}
    public interface TimedOutput extends PositionedOutput {AudioHead headObservation();}
    public interface PhaseOutput extends TimedOutput {
        /** Worker only. null restores local rate discipline; false leaves rate unchanged. */
        boolean peerRate(Integer samplesPerSecond)throws IOException;
    }
    public interface ReplayableOutput extends PositionedOutput {
        /** Pause consumption, then observe the head; never reset the old timeline first. */
        AudioPlaybackTimeline.Position checkpoint()throws IOException;
        /** Fresh outputs only: decode complete input, suppress PCM before this sample. */
        void discardBefore(long sample)throws IOException;
    }
    public interface Factory {Output open(AudioWireFormat format)throws IOException;}
    /** Optional exact owner and role aware output selection. */
    public interface SessionFactory extends Factory {
        Output open(PairVerify.VerifiedSession session,ModeControl modes,
                    AudioDescription description,AudioWireFormat format)throws IOException;
    }
    public interface Listener {
        /** First actual PCM buffered, before playback permission. Nonblocking. */
        void ready();
        void stopped(IOException failure);
    }
    private static final int MAX_PACKETS=64,MAX_BYTES=512*1024;
    private static final long REORDER_NS=40000000L;
    private final TreeMap<Long,Item> queue=new TreeMap<Long,Item>();
    private final AudioWireFormat format;private final Factory factory;private final Listener listener;
    private final AudioInputJournal journal;private boolean retainsInput,restartAvailable;
    private final Thread worker;private final CountDownLatch stopped=new CountDownLatch(1);
    private int bytes;private long highest,origin,expected,firstQueued,accepted,submitted,dropped;
    private boolean seen,started,closed,ending,startAllowed,recorded;private IOException failure;private float gain;
    private long[] outputStats={0,0,0};
    private long packetAcceptedAt;
    private AudioPlaybackTimeline.Position mediaPosition;
    private AudioHead deviceHead;private long deviceFramesBase;
    private final AudioRateEstimator rateEstimator;
    private final AudioSyncAnchor syncAnchor;private final AudioPhaseDiscipline phase;
    private long syncEpoch;private boolean phaseActive,phaseUnsupported,peerSeen,awaitingSync;private long phaseLastSeen,syncWaitStarted;
    private PhaseObservation phaseObservation;
    public static final class PhaseObservation {
        public final long rtpBoundary,headNanos,targetNanos,errorNanos;public final int commandedRate;
        private PhaseObservation(long rtp,long head,long target,int rate){rtpBoundary=rtp;headNanos=head;targetNanos=target;errorNanos=head-target;commandedRate=rate;}
    }
    public synchronized PhaseObservation phaseObservation(){
        if(closed||restart!=null||flush!=null||phaseObservation==null)return null;
        long age=System.nanoTime()-phaseObservation.headNanos;return age>=0&&age<=AudioHead.MAX_AGE_NANOS?phaseObservation:null;
    }
    public boolean synchronize(AudioSyncPacket packet)throws IOException{
        long timestamp,epoch;Long floor;
        synchronized(this){if(closed||syncAnchor==null||!seen||restart!=null||flush!=null)return false;timestamp=highest&0xffffffffL;epoch=syncEpoch;floor=discardFloor;}
        if(floor!=null&&(int)(packet.timestamp-(floor&0xffffffffL))<0)return false;
        boolean accepted=syncAnchor.update(packet,timestamp);boolean stale;
        synchronized(this){stale=closed||epoch!=syncEpoch;if(accepted&&!stale){peerSeen=true;awaitingSync=false;syncWaitStarted=0;notifyAll();}}
        if(stale)syncAnchor.clear();return accepted&&!stale;
    }
    private Restart restart;private AudioDataStream.Flush flush;private Long discardFloor;private long outputEpoch;
    private final class FlushControl implements AudioDataStream.Flush {
        final Restart request;
        FlushControl(Restart request){this.request=request;}
        public Long await(long millis)throws IOException,InterruptedException{
            RestartResult result=request.await(millis);return result.lastPlayedTimestamp==null?null:Long.valueOf(result.lastPlayedTimestamp&0xffffffffL);
        }
        public void responseSent()throws IOException{
            synchronized(AudioSink.this){
                if(closed||flush!=this||!request.isDone()||request.failure!=null)throw new IOException("Stale/incomplete audio FLUSH response");
                flush=null;AudioSink.this.notifyAll();
            }
        }
    }
    private final class EmptyFlush implements AudioDataStream.Flush {
        public Long await(long millis)throws IOException{if(millis<0)throw new IOException("Audio FLUSH wait bounds");return null;}
        public void responseSent()throws IOException{
            synchronized(AudioSink.this){
                if(closed||flush!=this)throw new IOException("Stale empty audio FLUSH response");
                flush=null;AudioSink.this.notifyAll();
            }
        }
    }
    public synchronized boolean canFlush(){return recorded&&!closed&&!ending&&restart==null&&flush==null&&
        (!seen&&!started||started&&restartAvailable);}
    public synchronized AudioDataStream.Flush beginFlush(long timestamp)throws IOException{
        if(!canFlush())throw new IOException("Audio FLUSH not prepared");
        if(timestamp<0||timestamp>0xffffffffL)throw new IOException("Audio FLUSH timestamp bounds");
        if(!seen){
            // No output or RTP origin exists yet. Establish a half-window unwrap
            // reference so even a packet just before a wrapped cutoff stays >=0.
            long floor=timestamp+0x100000000L;
            if(discardFloor!=null){
                int delta=(int)(timestamp-(discardFloor&0xffffffffL));
                if(delta==Integer.MIN_VALUE)throw new IOException("Ambiguous empty audio FLUSH timestamp");
                floor=discardFloor+Math.max(0,delta);
            }
            journal.discardBefore(floor);discardFloor=floor;packetAcceptedAt=0;
            syncEpoch++;phaseObservation=null;awaitingSync=peerSeen;syncWaitStarted=0;
            flush=new EmptyFlush();notifyAll();return flush;
        }
        Restart request=restartAt(timestamp);flush=new FlushControl(request);return flush;
    }
    public static final class RestartResult {
        public final long epoch,cutoff;
        public final Long lastPlayedTimestamp;
        public final AudioPlaybackTimeline.Position previousPosition;
        private RestartResult(long epoch,AudioReplayOutput output){this.epoch=epoch;cutoff=output.cutoff;lastPlayedTimestamp=output.lastPlayedTimestamp;previousPosition=output.previousPosition;}
    }
    /** One serialized request. A wait timeout is only an observation timeout; the
     * request remains owned until completed or the sink is closed. */
    public static final class Restart {
        private final long sample;private final CountDownLatch done=new CountDownLatch(1);
        private volatile RestartResult result;private volatile IOException failure;
        private Restart(long sample){this.sample=sample;}
        private synchronized void complete(RestartResult result,IOException error){if(done.getCount()==0)return;this.result=result;failure=error;done.countDown();}
        public boolean isDone(){return done.getCount()==0;}
        public RestartResult await(long millis)throws IOException,InterruptedException{
            if(millis<0)throw new IllegalArgumentException("Restart wait");
            if(!done.await(millis,TimeUnit.MILLISECONDS))throw new java.net.SocketTimeoutException("Audio restart still pending");
            if(failure!=null)throw failure;return result;
        }
    }
    /** Worker control primitive; not an RTSP acknowledgement. The timestamp must
     * belong to this authenticated stream. Initial stream origin is already known. */
    public synchronized Restart restartAt(long timestamp)throws IOException{
        if(timestamp<0||timestamp>0xffffffffL||closed||ending||!started||!restartAvailable||restart!=null||flush!=null)
            throw new IOException("Audio restart unavailable");
        int delta=(int)(timestamp-(highest&0xffffffffL));if(delta==Integer.MIN_VALUE)throw new IOException("Ambiguous audio restart timestamp");
        restart=new Restart(highest+delta);packetAcceptedAt=0;syncEpoch++;phaseObservation=null;awaitingSync=peerSeen;syncWaitStarted=0;notifyAll();return restart;
    }
    /** Output counters/position refer to this epoch; input counters remain cumulative. */
    public synchronized long outputEpoch(){return outputEpoch;}
    public AudioSink(AudioWireFormat format,Factory factory,Listener listener){
        this(format,factory,listener,false);
    }
    /** Declared network receivers require RECORD in addition to presentation permission.
     * Direct/legacy worker users retain their explicit local playback contract. */
    public AudioSink(AudioWireFormat format,Factory factory,Listener listener,boolean requireRecord){
        this(format,factory,listener,requireRecord,null);
    }
    public AudioSink(AudioWireFormat format,Factory factory,Listener listener,boolean requireRecord,ClockReference.Source clock){
        if(format==null||factory==null||listener==null)throw new NullPointerException();
        this.format=format;this.factory=factory;this.listener=listener;recorded=!requireRecord;journal=new AudioInputJournal(format.aac?2:0);
        rateEstimator=new AudioRateEstimator(format.sampleRate);
        syncAnchor=clock==null?null:new AudioSyncAnchor(clock,format.sampleRate);phase=new AudioPhaseDiscipline(format.sampleRate);
        worker=new Thread(new Runnable(){public void run(){work();}},"vn-carplay-audio");worker.setDaemon(true);worker.start();
    }
    public synchronized void record()throws IOException{
        if(closed)throw new IOException("Audio sink closed");
        recorded=true;notifyAll();
    }
    public synchronized boolean recorded(){return recorded;}
    /** Local monotonic observation of accepted input, not RTP or acoustic time.
     * Duplicates/rejected input never refresh it. FLUSH/restart invalidates the
     * old epoch; pending operations and close expose no activity timestamp. */
    public synchronized long lastPacketNanos(){return closed||restart!=null||flush!=null?0:packetAcceptedAt;}
    public synchronized boolean isClosing(){return closed;}
    public void retire(){close();}
    public void packet(AudioPacketDecoder.Packet packet)throws IOException{
        try{enqueue(packet.timestamp,packet.payload);}finally{packet.close();}
    }
    /** Transport adapter/test entry. This does not authenticate arbitrary caller data. */
    public synchronized void enqueue(long timestamp,byte[] payload)throws IOException{
        if(closed||ending)throw new IOException("Audio sink not accepting packets");
        if(timestamp<0||timestamp>0xffffffffL||payload==null||payload.length==0||payload.length>65471||
            (!format.aac&&payload.length%(format.channels*2)!=0))throw new IOException("Audio payload bounds");
        long sample=seen?highest+(int)(timestamp-(highest&0xffffffffL)):
            discardFloor==null?timestamp:discardFloor+(int)(timestamp-(discardFloor&0xffffffffL));
        int frames=format.aac?format.aacFrameSamples:payload.length/(format.channels*2);
        // A small authenticated AAC prefix can warm the new decoder without
        // presenting pre-cutoff PCM. Normal ordering/duplicates still apply.
        if(discardFloor!=null&&sample+frames<=discardFloor-(format.aac?2L*format.aacFrameSamples:0)){dropped++;return;}
        if(!seen||sample>highest)highest=sample;seen=true;
        if(started&&sample<expected||queue.containsKey(sample)){dropped++;return;}
        if(queue.size()>=MAX_PACKETS||bytes>MAX_BYTES-payload.length)throw new IOException("Audio queue overflow");
        if(queue.isEmpty())firstQueued=System.nanoTime();Item queued=new Item(payload.clone(),System.nanoTime());queue.put(sample,queued);packetAcceptedAt=queued.arrived;
        bytes+=payload.length;accepted++;notifyAll();
    }
    /** A positive presentation gain authorizes initial playback. Later zero gain mutes
     * an already started stream without rewinding it. Only the current owner may call. */
    public void setGain(float value){setPlayback(value,value>0);}
    /** Explicit permission for an authorized silent stream. Zero gain alone is not permission.
     * Permission and gain are sampled together on the output worker; no UI codec calls. */
    public synchronized void setPlayback(float value,boolean allowStart){
        if(Float.isNaN(value)||value<0||value>1)throw new IllegalArgumentException("Audio gain");
        if(!closed){gain=value;startAllowed=allowStart;notifyAll();}
    }
    /** Finite stream drain; an idle live stream should simply remain open. */
    public synchronized void endOfInput(){if(!closed){ending=true;notifyAll();}}
    public void closed(IOException reason){stop(reason);}
    public void close(){stop(null);}
    private synchronized void stop(IOException reason){if(closed)return;closed=true;syncEpoch++;phaseObservation=null;failure=reason;clear();
        if(restart!=null)restart.complete(null,reason==null?new IOException("Audio restart cancelled"):reason);flush=null;
        notifyAll();worker.interrupt();}
    private void clear(){for(Item p:queue.values()){p.erase();dropped++;}queue.clear();bytes=0;packetAcceptedAt=0;}
    public boolean awaitStopped(long millis)throws InterruptedException{return Thread.currentThread()!=worker&&stopped.await(millis,TimeUnit.MILLISECONDS);}
    public synchronized long[] stats(){return new long[]{accepted,submitted,dropped,outputStats[0],outputStats[1],outputStats[2]};}
    public static final class PlaybackPosition {
        public final AudioPlaybackTimeline.Position output;
        /** Exclusive uint32 RTP boundary; null before any mapped frame is consumed. */
        public final Long rtpBoundary;
        private PlaybackPosition(AudioPlaybackTimeline.Position output,long origin){
            this.output=output;rtpBoundary=output.sampleKnown?Long.valueOf((origin+output.nextSample)&0xffffffffL):null;
        }
    }
    /** One atomic snapshot of the last device-head observation and its RTP origin.
     * null means the backend supplies no mapping. RECORD metadata is not the origin. */
    public synchronized PlaybackPosition playbackPosition(){return mediaPosition==null?null:new PlaybackPosition(mediaPosition,origin);}
    /** Same stream's physical output clock; FLUSH discard never invents consumed frames. */
    public synchronized AudioHead headObservation(){return closed||restart!=null||flush!=null?null:deviceHead;}
    public static final class ClockObservation {
        public final AudioHead head;public final AudioRateEstimator.Estimate rate;
        private ClockObservation(AudioHead head,AudioRateEstimator.Estimate rate){this.head=head;this.rate=rate;}
    }
    /** Head and rate from one output generation, under the same sink monitor. */
    public synchronized ClockObservation clockObservation(){
        AudioHead head=headObservation();return new ClockObservation(head,rateEstimator.estimate(head,System.nanoTime()));
    }
    /** null until a position-reporting output is selected. Payloads never leave the worker. */
    public synchronized AudioInputJournal.State retainedInput(){return retainsInput?journal.state():null;}
    private synchronized void observe(Output output)throws IOException{
        outputStats=output.stats().clone();mediaPosition=output instanceof PositionedOutput?((PositionedOutput)output).position():null;
        AudioHead head=output instanceof TimedOutput?((TimedOutput)output).headObservation():null;
        if(head!=null){
            if(head.frames>Long.MAX_VALUE-deviceFramesBase)throw new IOException("Audio physical clock overflow");
            deviceHead=new AudioHead(deviceFramesBase+head.frames,head.monotonicNanos,head.readDurationNanos,head.continuity,head.rateContinuous);
        }else deviceHead=null;
        rateEstimator.observe(deviceHead,System.nanoTime());
        if(retainsInput&&mediaPosition!=null&&mediaPosition.sampleKnown){
            if(origin>Long.MAX_VALUE-mediaPosition.nextSample)throw new IOException("Audio position overflow");
            journal.consumedThrough(origin+mediaPosition.nextSample);
        }
    }
    private synchronized Item take(long now){
        if(queue.isEmpty())return null;
        long sample=queue.firstKey();Item item=queue.get(sample);
        if(!ending&&(!started&&now-firstQueued<REORDER_NS||started&&sample>expected&&now-item.arrived<REORDER_NS))return null;
        queue.remove(sample);bytes-=item.data.length;
        if(!started){origin=sample;expected=sample;started=true;}
        if(sample<expected){item.erase();dropped++;return null;}
        item.sample=sample;item.pts=(sample-origin)*1000000L/format.sampleRate;
        expected=sample+(format.aac?format.aacFrameSamples:item.data.length/(format.channels*2));return item;
    }
    private boolean phaseStartAllowed(long now)throws IOException{
        if(syncAnchor==null)return true;long sample;
        synchronized(this){sample=discardFloor==null?origin:discardFloor;}
        Long target=syncAnchor.deadline(sample&0xffffffffL);
        if(target==null)synchronized(this){if(awaitingSync){if(syncWaitStarted==0)syncWaitStarted=now;else if(now-syncWaitStarted>AudioSyncAnchor.MAX_AGE_NS)throw new IOException("Audio sync anchor required after FLUSH");return false;}}
        return target==null||now-target>=0;
    }
    /** No clock/session calls while holding the sink monitor. Output is worker-owned. */
    private void phaseOutput(Output output)throws IOException{
        if(syncAnchor==null||!(output instanceof PhaseOutput)||phaseUnsupported)return;
        long now=System.nanoTime(),boundary;AudioHead head;AudioPlaybackTimeline.Position position;
        synchronized(this){head=deviceHead;position=mediaPosition;boundary=position==null?0:(origin+position.nextSample)&0xffffffffL;}
        Long target=position!=null&&position.headSampleKnown?syncAnchor.deadline(boundary):null;
        if(target!=null&&head!=null&&head.fresh(now)&&head.rateContinuous&&position.playedFrames+deviceFramesBase==head.frames){
            Integer rate=phase.observe(head,head.monotonicNanos-target,now);
            if(rate!=null){
                if(!((PhaseOutput)output).peerRate(rate)){phaseUnsupported=true;((PhaseOutput)output).peerRate(null);phase.reset();phaseActive=false;synchronized(this){phaseObservation=null;}}
                else{phaseActive=true;phaseLastSeen=now;synchronized(this){phaseObservation=new PhaseObservation(boundary,head.monotonicNanos,target,rate);}}return;
            }
        }
        if(phaseActive&&(target==null||now-phaseLastSeen>AudioHead.MAX_AGE_NANOS)){
            ((PhaseOutput)output).peerRate(null);phase.reset();phaseActive=false;synchronized(this){phaseObservation=null;}
        }
    }
    private void work(){
        Output output=null;Item item=null;boolean ready=false,announced=false,playbackStarted=false;long progressAt=System.nanoTime(),lastWritten=0,lastPlayed=0;
        try{
            while(true){
                float level;boolean end,allowStart;Restart requested;
                synchronized(this){if(closed)break;level=gain;end=ending;allowStart=startAllowed&&recorded&&flush==null;requested=restart;}
                if(requested!=null){
                    if(syncAnchor!=null)syncAnchor.clear();phase.reset();phaseActive=false;phaseUnsupported=false;
                    if(!(output instanceof ReplayableOutput))throw new IOException("Audio output cannot restart");
                    // restart owns and closes the old output even if checkpoint/open
                    // fails. Do not observe or close that retired output in finally.
                    ReplayableOutput previous=(ReplayableOutput)output;output=null;
                    AudioReplayOutput replacement=AudioReplayOutput.restart(previous,factory,format,journal,origin,requested.sample);
                    output=replacement;
                    synchronized(this){
                        journal.discardBefore(replacement.cutoff);discardFloor=replacement.cutoff;
                        if(replacement.previousPosition.playedFrames>Long.MAX_VALUE-deviceFramesBase)throw new IOException("Audio restart clock overflow");
                        deviceFramesBase+=replacement.previousPosition.playedFrames;deviceHead=null;rateEstimator.reset();
                        outputEpoch++;outputStats=new long[]{0,0,0};mediaPosition=replacement.position();
                        // Already accepted AAC before the cutoff is still decoded as
                        // warm-up. It never reaches the track, and journal pruning is
                        // independent of device consumption. PCM needs no such history.
                        if(!format.aac){
                            if(item!=null&&item.sample+item.data.length/(format.channels*2)<=discardFloor){item.erase();item=null;dropped++;}
                            Iterator<Map.Entry<Long,Item>> iterator=queue.entrySet().iterator();
                            while(iterator.hasNext()){
                                Map.Entry<Long,Item> e=iterator.next();Item p=e.getValue();
                                if(e.getKey()+p.data.length/(format.channels*2)>discardFloor)break;
                                bytes-=p.data.length;p.erase();dropped++;iterator.remove();
                            }
                        }
                        if(!closed)requested.complete(new RestartResult(outputEpoch,replacement),null);
                        restart=null;
                    }
                    ready=false;playbackStarted=false;lastWritten=lastPlayed=0;progressAt=System.nanoTime();
                    continue; // Resample current owner gain/permission after the slow checkpoint/open.
                }
                if(output!=null){
                    int replayBefore=output instanceof AudioReplayOutput?((AudioReplayOutput)output).remainingReplayPackets():0;
                    output.pump(level);long[] stats=output.stats();
                    if(replayBefore>0&&((AudioReplayOutput)output).remainingReplayPackets()<replayBefore)progressAt=System.nanoTime();
                    observe(output);
                    phaseOutput(output);
                    if(stats[0]!=lastWritten||stats[1]!=lastPlayed){progressAt=System.nanoTime();lastWritten=stats[0];lastPlayed=stats[1];}
                    if(!ready&&stats[0]>0){ready=true;if(!announced){announced=true;listener.ready();}}
                    if(ready&&!playbackStarted){
                        // Prepared PCM is held while the UI/owner decides. This is not
                        // a stalled codec. Queues remain bounded and close still cancels.
                        progressAt=System.nanoTime();
                        if(allowStart&&phaseStartAllowed(System.nanoTime())){output.start();playbackStarted=true;}
                    }
                }
                if(item==null)synchronized(this){item=take(System.nanoTime());}
                if(item!=null){
                    if(output==null){
                        output=factory.open(format);
                        Long floor;synchronized(this){floor=discardFloor;}
                        if(floor!=null&&floor>origin){
                            if(!(output instanceof ReplayableOutput))throw new IOException("Initial audio FLUSH needs clipping output");
                            ((ReplayableOutput)output).discardBefore(floor-origin);
                        }
                        synchronized(this){retainsInput=output instanceof PositionedOutput;restartAvailable=output instanceof ReplayableOutput;}
                        progressAt=System.nanoTime();
                    }
                    boolean capacity;synchronized(this){capacity=!retainsInput||journal.canRetain(item.data.length);}
                    if(capacity&&output.submit(item.data,item.pts)){
                        synchronized(this){if(retainsInput&&!closed)journal.retain(item.sample,format.aac?format.aacFrameSamples:item.data.length/(format.channels*2),item.data);submitted++;}
                        item.erase();item=null;progressAt=System.nanoTime();
                    }
                }else if(end){if(output==null||output.finish())break;}
                if(output!=null&&(item!=null||lastWritten>lastPlayed||end||output instanceof AudioReplayOutput&&((AudioReplayOutput)output).replayPending())&&System.nanoTime()-progressAt>3000000000L)
                    throw new IOException("Audio codec/output stalled");
                synchronized(this){if(!closed)wait(2);}
            }
        }catch(InterruptedException e){synchronized(this){if(!closed)failure=new IOException("Audio worker interrupted",e);}}
         catch(IOException e){synchronized(this){if(!closed)failure=e;}}
         catch(RuntimeException e){synchronized(this){if(!closed)failure=new IOException("Audio output failure",e);}}
         catch(LinkageError e){synchronized(this){if(!closed)failure=new IOException("Audio API unavailable",e);}}
        finally{
            if(item!=null)item.erase();
            if(output!=null){
                try{observe(output);}catch(IOException e){synchronized(this){if(failure==null)failure=e;}}
                catch(RuntimeException e){synchronized(this){if(failure==null)failure=new IOException("Audio final observation failed",e);}}
                catch(LinkageError e){synchronized(this){if(failure==null)failure=new IOException("Audio final API unavailable",e);}}
                synchronized(this){journal.close();}
                try{output.close();}catch(RuntimeException e){synchronized(this){if(failure==null)failure=new IOException("Audio release failed",e);}}
            }
            IOException reason;synchronized(this){journal.close();closed=true;clear();gain=0;reason=failure;
                if(restart!=null)restart.complete(null,reason==null?new IOException("Audio worker stopped before restart"):reason);restart=null;flush=null;}
            try{listener.stopped(reason);}finally{stopped.countDown();}
        }
    }
    private static final class Item {
        final byte[] data;final long arrived;long pts,sample;
        Item(byte[] data,long arrived){this.data=data;this.arrived=arrived;}
        void erase(){Arrays.fill(data,(byte)0);}
    }
}
