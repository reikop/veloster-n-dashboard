/* SPDX-License-Identifier: GPL-3.0-only. See audio-oem/NOTICE.md. */
package com.veloster.carplay.audiooem;

import com.veloster.carplay.control.AudioDescription;
import com.veloster.carplay.control.ModeControl;
import com.veloster.carplay.media.Api17AudioOutput;
import com.veloster.carplay.media.AudioHead;
import com.veloster.carplay.media.AudioPlaybackTimeline;
import com.veloster.carplay.media.AudioSink;
import com.veloster.carplay.pairing.PairVerify;
import com.veloster.carplay.tunnel.AudioWireFormat;
import java.io.IOException;

/** Optional exact-session output selector. The OEM gateway must authenticate the
 * caller and return a one-owner MICOM stream-19 lease or refuse the request.
 */
public final class OemAlternateAudioFactory implements AudioSink.SessionFactory {
    public interface RouteLease {
        void assertCurrent() throws IOException;
        void close();
    }
    public interface Gateway {
        RouteLease acquire(PairVerify.VerifiedSession owner,ModeControl modes,
                           AudioDescription description) throws IOException;
    }
    public interface TrackFactory {AudioSink.Output open(AudioWireFormat format,int androidStream)throws IOException;}
    public static final int ANDROID_STREAM_DIPO_ALT=11;
    private final Gateway gateway;
    private final TrackFactory tracks;
    public OemAlternateAudioFactory(Gateway gateway){this(gateway,new TrackFactory(){
        public AudioSink.Output open(AudioWireFormat format,int stream)throws IOException{
            return Api17AudioOutput.openOnStream(format,stream);
        }
    });}
    public OemAlternateAudioFactory(Gateway gateway,TrackFactory tracks){
        if(gateway==null||tracks==null)throw new NullPointerException();
        this.gateway=gateway;this.tracks=tracks;
    }
    /** Legacy callers have no authenticated role; only ordinary stream 3 is safe. */
    public AudioSink.Output open(AudioWireFormat format)throws IOException{return tracks.open(format,3);}
    public AudioSink.Output open(PairVerify.VerifiedSession owner,ModeControl modes,
                                 AudioDescription description,AudioWireFormat format)throws IOException{
        if(owner==null||modes==null||description==null||format==null)throw new NullPointerException();
        owner.assertCurrent();
        if(description.type!=101)return tracks.open(format,3);
        RouteLease lease=gateway.acquire(owner,modes,description);
        if(lease==null)throw new IOException("OEM alternate route unavailable");
        AudioSink.Output output=null;
        try{
            owner.assertCurrent();lease.assertCurrent();
            output=tracks.open(format,ANDROID_STREAM_DIPO_ALT);
            if(!(output instanceof AudioSink.ReplayableOutput)||!(output instanceof AudioSink.PhaseOutput))
                throw new IOException("OEM output lacks required replay/phase support");
            owner.assertCurrent();lease.assertCurrent();
            return new RoutedOutput(output,lease);
        }catch(IOException e){if(output!=null)output.close();lease.close();throw e;}
         catch(RuntimeException e){if(output!=null)output.close();lease.close();throw e;}
    }
    private static final class RoutedOutput implements AudioSink.ReplayableOutput,AudioSink.PhaseOutput {
        private final AudioSink.Output output;private final RouteLease lease;private boolean closed;
        RoutedOutput(AudioSink.Output output,RouteLease lease){this.output=output;this.lease=lease;}
        private void current()throws IOException{if(closed)throw new IOException("OEM audio output closed");lease.assertCurrent();}
        public boolean submit(byte[] data,long pts)throws IOException{current();return output.submit(data,pts);}
        public void pump(float gain)throws IOException{current();output.pump(gain);}
        public void start()throws IOException{current();output.start();}
        public boolean finish()throws IOException{current();return output.finish();}
        public long[] stats(){return output.stats();}
        public AudioPlaybackTimeline.Position position(){return ((AudioSink.PositionedOutput)output).position();}
        public AudioHead headObservation(){return ((AudioSink.TimedOutput)output).headObservation();}
        public boolean peerRate(Integer rate)throws IOException{current();return ((AudioSink.PhaseOutput)output).peerRate(rate);}
        public AudioPlaybackTimeline.Position checkpoint()throws IOException{
            current();
            return ((AudioSink.ReplayableOutput)output).checkpoint();
        }
        public void discardBefore(long sample)throws IOException{
            current();
            ((AudioSink.ReplayableOutput)output).discardBefore(sample);
        }
        public void close(){if(closed)return;closed=true;try{output.close();}finally{lease.close();}}
    }
}
