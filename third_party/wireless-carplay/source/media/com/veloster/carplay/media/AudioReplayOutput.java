/* SPDX-License-Identifier: GPL-3.0-only. Worker-owned decoder replacement and replay. */
package com.veloster.carplay.media;
import com.veloster.carplay.tunnel.AudioWireFormat;
import java.io.*;
import java.util.Arrays;

/** Rebuild one output from retained input. Does not select a network stream, prune
 * the owner's pending queue, alter crypto state or acknowledge an RTSP request.
 * All calls, including restart, must run on the existing audio output worker.
 */
public final class AudioReplayOutput implements AudioSink.ReplayableOutput,AudioSink.PhaseOutput {
    public final AudioPlaybackTimeline.Position previousPosition;
    /** Unwrapped original media coordinates; not a physical frame count. */
    public final long cutoff;
    public final Long lastPlayedTimestamp;
    private final AudioSink.ReplayableOutput output;
    private final AudioInputJournal.Replay replay;
    private final long origin;private final int rate;
    private int cursor;private boolean closed;
    private AudioReplayOutput(AudioSink.ReplayableOutput output,AudioInputJournal.Replay replay,
                              AudioPlaybackTimeline.Position previous,long origin,int rate){
        this.output=output;this.replay=replay;previousPosition=previous;cutoff=replay.cutoff;
        lastPlayedTimestamp=replay.lastPlayedTimestamp;this.origin=origin;this.rate=rate;
    }
    /** On failure the caller must retire its worker/journal. Once checkpointed, the
     * old output is always closed; a failed replacement must never resume stale PCM.
     * The journal still owns live input. Future queue policy belongs to its owner. */
    public static AudioReplayOutput restart(AudioSink.ReplayableOutput previous,AudioSink.Factory factory,
        AudioWireFormat format,AudioInputJournal journal,long origin,long requestedCutoff)throws IOException{
        if(previous==null||factory==null||format==null||journal==null)throw new NullPointerException();
        AudioInputJournal.Replay replay=null;AudioSink.Output fresh=null;boolean retired=false;
        try{
            AudioPlaybackTimeline.Position p=previous.checkpoint();
            if(p.sampleKnown){
                if(origin>Long.MAX_VALUE-p.nextSample)throw new IOException("Audio checkpoint overflow");
                journal.consumedThrough(origin+p.nextSample);
            }
            replay=journal.replayFrom(Math.max(origin,requestedCutoff));
            // Retained source can begin before cutoff for AAC warm-up, but never
            // before the stream origin. Keep integer-microsecond conversions bounded.
            long relative=replay.cutoff-origin;
            if(relative<0||relative>Long.MAX_VALUE/1000000L)throw new IOException("Audio replay cutoff bounds");
            for(AudioInputJournal.Packet packet:replay.packets)
                if(packet.sample<origin||packet.sample-origin>Long.MAX_VALUE/1000000L)throw new IOException("Audio replay input bounds");
            try{previous.close();}finally{retired=true;}fresh=factory.open(format);
            if(!(fresh instanceof AudioSink.ReplayableOutput))throw new IOException("Audio factory cannot replay");
            ((AudioSink.ReplayableOutput)fresh).discardBefore(relative);
            AudioReplayOutput result=new AudioReplayOutput((AudioSink.ReplayableOutput)fresh,replay,p,origin,format.sampleRate);
            fresh=null;replay=null;return result;
        }finally{
            try{if(!retired)previous.close();}finally{try{if(replay!=null)replay.close();}finally{if(fresh!=null)fresh.close();}}
        }
    }
    public boolean replayPending(){return cursor<replay.packets.size();}
    public int remainingReplayPackets(){return closed?0:replay.packets.size()-cursor;}
    public boolean submit(byte[] payload,long ptsUs)throws IOException{
        if(closed)throw new IOException("Audio replay closed");
        return !replayPending()&&output.submit(payload,ptsUs);
    }
    public void pump(float gain)throws IOException{
        if(closed)return;output.pump(gain);
        if(replayPending()){
            AudioInputJournal.Packet p=replay.packets.get(cursor);
            if(output.submit(p.bytes,(p.sample-origin)*1000000L/rate)){
                Arrays.fill(p.bytes,(byte)0);cursor++;
            }
        }
    }
    public void start()throws IOException{output.start();}
    public boolean finish()throws IOException{return !replayPending()&&output.finish();}
    public long[] stats(){return output.stats();}
    public AudioPlaybackTimeline.Position position(){return output.position();}
    public AudioHead headObservation(){return output instanceof AudioSink.TimedOutput?((AudioSink.TimedOutput)output).headObservation():null;}
    public boolean peerRate(Integer rate)throws IOException{return output instanceof AudioSink.PhaseOutput?((AudioSink.PhaseOutput)output).peerRate(rate):rate==null;}
    public AudioPlaybackTimeline.Position checkpoint()throws IOException{return output.checkpoint();}
    public void discardBefore(long sample)throws IOException{throw new IOException("Replay output already configured");}
    public void close(){if(closed)return;closed=true;replay.close();output.close();}
}
