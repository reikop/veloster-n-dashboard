/* SPDX-License-Identifier: GPL-3.0-only. See media/NOTICE.md. */
package com.veloster.carplay.media;
import android.media.*;
import com.veloster.carplay.tunnel.AudioWireFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.ArrayDeque;

/** API17 synchronous raw AAC decoder / PCM16 AudioTrack. One worker owns all calls.
 * Track buffers while stopped until explicit start; per-track gain never changes global volume.
 * Playback-head progress proves Android consumption, not audible OEM speaker output.
 */
public final class Api17AudioOutput implements AudioSink.ReplayableOutput,AudioSink.PhaseOutput {
    public static final class CleanupFailure extends IOException {
        public CleanupFailure(String message,Throwable cause){super(message,cause);}
    }
    public interface Observer {
        /** Borrowed decoded/converted PCM before gain, never retained by production output. */
        void pcm(byte[] data,long ptsUs)throws IOException;
    }
    public static final class Factory implements AudioSink.Factory {
        private final Observer observer;
        public Factory(){this(null);}
        public Factory(Observer observer){this.observer=observer;}
        public AudioSink.Output open(AudioWireFormat f)throws IOException{return new Api17AudioOutput(f,observer,AudioManager.STREAM_MUSIC);}
    }
    private final AudioWireFormat format;private final Observer observer;
    private final AudioClockDiscipline discipline;
    private MediaCodec codec;private AudioTrack track;private ByteBuffer[] inputs,outputs;
    private final MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
    private final int frameBytes,capacityFrames;
    private byte[] pending;private int offset;private long written,played,lastHead,nextSample,silence,padding;
    private long pendingSample,paddingSample;private boolean paddingMapped;
    private long playbackStartedAt,lastPcmWriteAt,lastHeadAdvanceAt,realWritten,filledThroughReal;
    private AudioHead observedHead;
    private Integer peerCommand;private int peerChanges;
    private long headContinuity;private boolean headContinuous;
    private long discardSample;private boolean inputBegun,discardConfigured;
    private final AudioPlaybackTimeline timeline=new AudioPlaybackTimeline();
    private final ArrayDeque<Stamp> stamps=new ArrayDeque<Stamp>();
    // AAC-LC stereo zero frame, independently decoded at 44.1/48kHz. Common ICS
    // window_shape=0 (sine), matching a fresh decoder's previous-window state.
    // Old SoftAAC2 decodes its first AU twice during output-port configuration
    // (AOSP f3bd1972e039c6ded5154db715e5a32f1813a239). Prime with silence so
    // reconfiguration cannot corrupt the first real AU's overlap history.
    private static final byte[] AAC_SILENCE={(byte)0x21,0x00,0x04,0x60,(byte)0x8c,0x1c};
    private static final int PREROLL_FRAMES=2;
    private int prerollQueued;
    private byte[] decoded;private int decodedOffset;private long endPts;
    private boolean eosQueued,eosOutput,closed,configured,playing,codecStarted;private float gain=-1;
    private CleanupFailure cleanupFailure;
    private final byte[] zeros=new byte[4096];
    /** OEM stream 11 is available only on the pinned vehicle framework. The caller
     * must own a corresponding MICOM alternate-route lease before selecting it. */
    public static Api17AudioOutput openOnStream(AudioWireFormat format,int streamType)throws IOException{
        if(streamType!=AudioManager.STREAM_MUSIC&&streamType!=11)throw new IOException("Unsupported Android output stream");
        return new Api17AudioOutput(format,null,streamType);
    }
    private Api17AudioOutput(AudioWireFormat format,Observer observer,int streamType)throws IOException{
        this.format=format;this.observer=observer;frameBytes=format.channels*2;
        discipline=new AudioClockDiscipline(format.sampleRate,new AudioClockDiscipline.Device(){
            public boolean rate(int rate)throws IOException{
                int before=track.getPlaybackRate();int result=track.setPlaybackRate(rate);int after=track.getPlaybackRate();
                if(result==AudioTrack.SUCCESS&&after==rate)return true;
                if(after!=before)throw new IOException("AudioTrack rate changed despite rejection");
                return false;
            }
        });
        int mask=format.channels==1?AudioFormat.CHANNEL_OUT_MONO:AudioFormat.CHANNEL_OUT_STEREO;
        int min=AudioTrack.getMinBufferSize(format.sampleRate,mask,AudioFormat.ENCODING_PCM_16BIT);
        if(min<=0||min>1024*1024)throw new IOException("AudioTrack buffer unavailable");
        int size=Math.max(min,format.sampleRate*frameBytes/10);size=(size+frameBytes-1)/frameBytes*frameBytes;capacityFrames=size/frameBytes;
        try{
            track=new AudioTrack(streamType,format.sampleRate,mask,AudioFormat.ENCODING_PCM_16BIT,size,AudioTrack.MODE_STREAM);
            if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IOException("AudioTrack not initialized");
            setGain(0);
            if(format.aac){
                MediaFormat config=MediaFormat.createAudioFormat("audio/mp4a-latm",format.sampleRate,format.channels);
                config.setInteger("aac-profile",format.aacProfile);config.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,65536);
                config.setByteBuffer("csd-0",ByteBuffer.wrap(format.codecSpecificData()));
                codec=MediaCodec.createDecoderByType("audio/mp4a-latm");codec.configure(config,null,null,0);codec.start();codecStarted=true;
                inputs=codec.getInputBuffers();outputs=codec.getOutputBuffers();
            }else configured=true;
        }catch(IOException e){
            try{closeChecked();}catch(CleanupFailure cleanup){throw new CleanupFailure("Audio startup cleanup failed after: "+e.getMessage(),cleanup);}
            throw e;
        }catch(RuntimeException e){
            try{closeChecked();}catch(CleanupFailure cleanup){throw new CleanupFailure("Audio startup cleanup failed after: "+e.getClass().getName(),cleanup);}
            throw new IOException("Audio output startup failed",e);
        }
    }
    public boolean submit(byte[] payload,long ptsUs)throws IOException{
        if(closed||eosQueued)throw new IOException("Audio output closed/ended");
        inputBegun=true;
        if(!format.aac){if(pending!=null||padding!=0)return false;accept(format.pcm16LittleEndian(payload),ptsUs);return true;}
        if(stamps.size()>=64)return false;
        int index=codec.dequeueInputBuffer(0);if(index<0)return false;
        boolean preroll=format.aacProfile==2&&prerollQueued<PREROLL_FRAMES;
        if(preroll){payload=AAC_SILENCE;ptsUs=0;}
        ByteBuffer b=inputs[index];b.clear();if(payload.length>b.remaining())throw new IOException("AAC input exceeds decoder buffer");
        b.put(payload);codec.queueInputBuffer(index,0,payload.length,ptsUs,0);
        stamps.add(new Stamp((ptsUs*format.sampleRate+500000L)/1000000L,preroll));
        if(preroll){prerollQueued++;return false;}
        endPts=ptsUs+(long)format.aacFrameSamples*1000000L/format.sampleRate;return true;
    }
    private void accept(byte[] pcm,long ptsUs)throws IOException{
        if(pcm.length==0)return;
        if(pcm.length%frameBytes!=0||pcm.length>256*1024){Arrays.fill(pcm,(byte)0);throw new IOException("PCM output bounds");}
        long sample=(ptsUs*format.sampleRate+500000L)/1000000L;
        if(sample<discardSample){
            int skip=(int)Math.min(discardSample-sample,pcm.length/frameBytes);
            byte[] suffix=Arrays.copyOfRange(pcm,skip*frameBytes,pcm.length);Arrays.fill(pcm,(byte)0);pcm=suffix;
            if(pcm.length==0)return;
            sample+=skip;ptsUs=sample*1000000L/format.sampleRate;
        }
        try{if(observer!=null)observer.pcm(pcm,ptsUs);}catch(IOException e){Arrays.fill(pcm,(byte)0);throw e;}
        catch(RuntimeException e){Arrays.fill(pcm,(byte)0);throw e;}
        // Conceal short missing spans with silence. Long pauses re-anchor locally; network sync is separate.
        long gap=sample-nextSample;padding=gap>0&&gap<=format.sampleRate/4?gap:0;
        paddingMapped=padding>0;paddingSample=nextSample;pendingSample=sample;
        nextSample=sample+pcm.length/frameBytes;pending=pcm;offset=0;
    }
    private void setGain(float value)throws IOException{
        if(value==gain)return;
        if(track.setStereoVolume(value,value)!=AudioTrack.SUCCESS)throw new IOException("AudioTrack gain failed");gain=value;
    }
    private void head()throws IOException{
        long before=System.nanoTime();long value=track.getPlaybackHeadPosition()&0xffffffffL;long after=System.nanoTime();
        long delta=(value-lastHead)&0xffffffffL;if(delta>0x7fffffffL)throw new IOException("AudioTrack head reset");
        played+=delta;lastHead=value;if(played>written)throw new IOException("AudioTrack head exceeds written frames");timeline.advance(played);
        if(delta>0)lastHeadAdvanceAt=after;
        boolean continuous=playing&&played<written;
        if(headContinuous&&!continuous)headContinuity++;
        headContinuous=continuous;
        if(delta>0)observedHead=new AudioHead(played,before+(after-before)/2,after-before,headContinuity,continuous);
        else if(observedHead!=null&&(observedHead.continuity!=headContinuity||!continuous&&observedHead.rateContinuous))
            observedHead=new AudioHead(observedHead.frames,observedHead.monotonicNanos,observedHead.readDurationNanos,headContinuity,false);
    }
    /** Diagnostics are read on the owning audio worker only. */
    public int commandedPlaybackRate(){return peerCommand==null?discipline.commandedRate():peerCommand;}
    public int playbackRateChanges(){return discipline.changes()+peerChanges;}
    public boolean playbackRateControlDisabled(){return discipline.disabled();}
    private void disciplineHead()throws IOException{
        if(peerCommand!=null)return;
        if(observedHead!=null&&discipline.observe(observedHead,System.nanoTime())){
            // A rate fit may not span a command change. The next *actual* head
            // advance starts a fresh generation; preserve physical sample counts.
            headContinuity++;observedHead=null;headContinuous=false;
        }
    }
    public boolean peerRate(Integer requested)throws IOException{
        if(closed)throw new IOException("Audio phase output closed");
        if(requested!=null&&(requested<(int)Math.ceil(format.sampleRate*.995)||requested>(int)Math.floor(format.sampleRate*1.005)))throw new IOException("Audio phase rate bounds");
        if(requested==null&&peerCommand==null)return true;
        if(peerCommand==null&&discipline.reset()){headContinuity++;headContinuous=false;observedHead=null;}
        int next=requested==null?format.sampleRate:requested,before=track.getPlaybackRate();
        if(before!=next){
            int result=track.setPlaybackRate(next),after=track.getPlaybackRate();
            if(result!=AudioTrack.SUCCESS||after!=next){if(after!=before)throw new IOException("Audio phase rate changed despite rejection");if(requested==null)throw new IOException("Audio phase nominal restore rejected");return false;}
            peerChanges++;headContinuity++;headContinuous=false;observedHead=null;
        }
        peerCommand=requested;if(requested==null)discipline.reset();return true;
    }
    public void pump(float level)throws IOException{
        if(closed)return;setGain(level);head();disciplineHead();
        for(int work=0;work<16;work++){
            if(pending!=null||padding>0){
                long room=capacityFrames-(written-played);if(room<=0)return;
                boolean silenceNow=padding>0;byte[] data=silenceNow?zeros:pending;
                int start=silenceNow?0:offset;
                int available=silenceNow?(int)Math.min(padding*frameBytes,zeros.length):data.length-start;
                int count=(int)Math.min(Math.min(room*frameBytes,available),4096);count=count/frameBytes*frameBytes;
                if(count==0)return;
                int n=track.write(data,start,count);if(n<0||n>count||n%frameBytes!=0)throw new IOException("AudioTrack write failed: "+n);
                if(n==0)return;
                if(silenceNow){if(paddingMapped)timeline.append(n/frameBytes,paddingSample);else timeline.appendPadding(n/frameBytes);paddingSample+=n/frameBytes;}
                else timeline.append(n/frameBytes,pendingSample+offset/frameBytes);
                written+=n/frameBytes;
                if(silenceNow){padding-=n/frameBytes;silence+=n/frameBytes;}
                else {lastPcmWriteAt=System.nanoTime();realWritten+=n/frameBytes;offset+=n;if(offset==pending.length){Arrays.fill(pending,(byte)0);pending=null;offset=0;}}
                head();continue;
            }
            if(decoded!=null){
                Stamp stamp=stamps.peek();if(stamp==null)throw new IOException("AAC decoded more samples than submitted");
                int frames=Math.min(format.aacFrameSamples-stamp.used,(decoded.length-decodedOffset)/frameBytes);
                byte[] part=stamp.preroll?null:Arrays.copyOfRange(decoded,decodedOffset,decodedOffset+frames*frameBytes);
                long pts=(stamp.sample+stamp.used)*1000000L/format.sampleRate;
                decodedOffset+=frames*frameBytes;stamp.used+=frames;if(stamp.used==format.aacFrameSamples)stamps.remove();
                if(decodedOffset==decoded.length){Arrays.fill(decoded,(byte)0);decoded=null;decodedOffset=0;}
                // Only synthetic preroll is removed. Real AU PCM, including its first
                // sample, is retained with its original authenticated sample timestamp.
                if(part!=null)accept(part,pts);continue;
            }
            if(codec==null||eosOutput){
                fillQuietBuffer();
                // A delayed owner can already be at the sink's stall bound.
                // Try newly reserved fill in this bounded pump, before the sink
                // evaluates unchanged output stats. Zero writes still return.
                if(padding>0)continue;
                return;
            }
            int index=codec.dequeueOutputBuffer(info,0);
            if(index>=0){
                try{
                    if((info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0&&info.size>0){
                        if(!configured)throw new IOException("AAC output before negotiated format");
                        ByteBuffer b=outputs[index].duplicate();
                        if(info.offset<0||info.size<0||info.size>256*1024||info.offset>b.capacity()-info.size)throw new IOException("AAC output bounds");
                        if(info.size%frameBytes!=0)throw new IOException("AAC partial PCM sample");
                        // API17 SoftAAC2 shifts/regresses output PTS, including its final EOS buffer.
                        // Map PCM spans to authenticated input timestamps and the negotiated AU size,
                        // supporting split or coalesced output buffers rather than trusting codec PTS.
                        b.clear();b.position(info.offset);b.limit(info.offset+info.size);decoded=new byte[info.size];decodedOffset=0;b.get(decoded);
                    }
                    if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)eosOutput=true;
                }finally{codec.releaseOutputBuffer(index,false);}
            }else if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                MediaFormat f=codec.getOutputFormat();
                if(f.getInteger(MediaFormat.KEY_SAMPLE_RATE)!=format.sampleRate||f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)!=format.channels||
                    f.containsKey("pcm-encoding")&&f.getInteger("pcm-encoding")!=AudioFormat.ENCODING_PCM_16BIT)
                    throw new IOException("AAC output format differs from negotiation");
                configured=true;
            }else if(index==MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED)outputs=codec.getOutputBuffers();
            else if(index==MediaCodec.INFO_TRY_AGAIN_LATER){fillQuietBuffer();if(padding>0)continue;return;}
            else throw new IOException("AAC output status");
        }
    }
    /** API17 needs a full streaming buffer initially and after an idle underrun.
     * Short live speech has no EOS, so finish() cannot supply that fill. Wait
     * one buffer duration without PCM writes or head progress after permission,
     * then add only the missing frames. These are physical, unmapped silence,
     * not phone samples or an RTP clock advance. At most one fill is allowed
     * per real PCM write generation: silence cannot keep a broken track alive.
     * Continuous input fills normally. Empty idle tracks receive no padding.
     */
    private void fillQuietBuffer(){
        long queued=written-played;
        if(!playing||eosQueued||queued<=0||queued>=capacityFrames||realWritten==filledThroughReal||pending!=null||decoded!=null||padding!=0)return;
        long quietSince=Math.max(playbackStartedAt,Math.max(lastPcmWriteAt,lastHeadAdvanceAt));
        long waitNanos=(capacityFrames*1000000000L+format.sampleRate-1)/format.sampleRate;
        if(System.nanoTime()-quietSince<waitNanos)return;
        padding=capacityFrames-queued;paddingMapped=false;paddingSample=0;filledThroughReal=realWritten;
    }
    public boolean finish()throws IOException{
        if(codec!=null&&!eosQueued){
            int index=codec.dequeueInputBuffer(0);if(index<0)return false;
            codec.queueInputBuffer(index,0,0,endPts,MediaCodec.BUFFER_FLAG_END_OF_STREAM);eosQueued=true;
        }else if(codec==null){eosQueued=true;eosOutput=true;}
        if(eosOutput&&pending==null&&decoded==null&&!stamps.isEmpty())throw new IOException("AAC ended before all submitted samples");
        // API17 AudioFlinger keeps a new streaming track in FS_FILLING until its
        // buffer is full. A finite sound shorter than that never starts otherwise.
        // Pad only after all real PCM is written and EOS is known. Count these
        // physical zero frames as silence and wait for actual playback progress.
        if(eosOutput&&pending==null&&decoded==null&&padding==0&&written>0&&written<capacityFrames){
            padding=capacityFrames-written;paddingMapped=false;paddingSample=0;
        }
        head();return eosOutput&&pending==null&&decoded==null&&padding==0&&played>=written;
    }
    public long[] stats(){return new long[]{written,played,silence};}
    public AudioPlaybackTimeline.Position position(){return timeline.position();}
    public AudioHead headObservation(){return observedHead;}
    public AudioPlaybackTimeline.Position checkpoint()throws IOException{
        if(closed)throw new IOException("Audio output closed");
        track.pause();playing=false;head();discipline.reset();
        // API17 AudioFlinger acknowledges PAUSING before its mixer changes the
        // track to PAUSED; one final mix can still advance the shared server head.
        // Public API17 has no server-side pause acknowledgement. Require observed
        // quiescence for two buffer durations (at least 100ms), bounded to 2s.
        // This is an observed checkpoint, not a hardware presentation timestamp.
        long begin=System.nanoTime(),stableSince=begin,last=played;
        long quiet=Math.max(100000000L,2L*capacityFrames*1000000000L/format.sampleRate);
        while(true){
            long now=System.nanoTime();if(now-begin>=2000000000L)throw new IOException("Audio pause did not settle");
            head();if(played!=last){last=played;stableSince=now;}
            if(now-stableSince>=quiet)return timeline.position();
            try{Thread.sleep(5);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Audio checkpoint cancelled",e);}
        }
    }
    public void discardBefore(long sample)throws IOException{
        if(closed||inputBegun||discardConfigured||sample<0||sample>Long.MAX_VALUE/1000000L)
            throw new IOException("Audio replay cutoff requires a fresh output");
        discardConfigured=true;discardSample=sample;nextSample=sample;
    }
    public void start()throws IOException{
        if(closed||written==0)throw new IOException("Audio output not prepared");
        if(!playing){track.play();playing=true;playbackStartedAt=System.nanoTime();}
    }
    public void close(){
        try{closeChecked();}catch(CleanupFailure ignored){}
    }
    /** Probe/owner-worker cleanup gate. Ordinary close retains its historical
     * best-effort signature; this method reports any native codec/track failure. */
    public void closeChecked()throws CleanupFailure{
        if(closed){if(cleanupFailure!=null)throw cleanupFailure;return;}
        closed=true;
        if(pending!=null){Arrays.fill(pending,(byte)0);pending=null;}
        if(decoded!=null){Arrays.fill(decoded,(byte)0);decoded=null;}stamps.clear();
        RuntimeException first=null;StringBuilder failed=new StringBuilder();
        if(codec!=null){
            if(codecStarted)try{codec.stop();}catch(RuntimeException e){if(first==null)first=e;failed.append(" codec.stop");}
            try{codec.release();}catch(RuntimeException e){if(first==null)first=e;failed.append(" codec.release");}
            codec=null;codecStarted=false;
        }
        if(track!=null){
            if(playing)try{track.pause();}catch(RuntimeException e){if(first==null)first=e;failed.append(" track.pause");}
            try{if(track.getState()==AudioTrack.STATE_INITIALIZED)track.flush();}
            catch(RuntimeException e){if(first==null)first=e;failed.append(" track.flush");}
            try{track.release();}catch(RuntimeException e){if(first==null)first=e;failed.append(" track.release");}
            track=null;playing=false;
        }
        if(first!=null)throw cleanupFailure=new CleanupFailure("Audio output cleanup failed:"+failed,first);
    }
    private static final class Stamp {final long sample;final boolean preroll;int used;Stamp(long sample,boolean preroll){this.sample=sample;this.preroll=preroll;}}
}
