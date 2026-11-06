/* SPDX-License-Identifier: GPL-3.0-only. See media/NOTICE.md. */
package com.veloster.carplay.media;
import com.veloster.carplay.control.*;
import com.veloster.carplay.pairing.PairVerify;
import com.veloster.carplay.tunnel.*;
import java.io.IOException;
import java.util.*;

/** SETUP -> verified UDP -> bounded worker -> injected actual decoder/track output.
 * Product owner supplies exact-session presentation callbacks; gains default to zero.
 */
public final class AudioStreamFactory implements IapSetupHandler.DeclaredAudioSinkFactory,IapSetupHandler.FeedbackAudioSinkFactory,IapSetupHandler.ClockedAudioSinkFactory {
    public interface Outputs extends AudioSink.Factory {
        boolean supports(AudioDescription description);
        void opened(PairVerify.VerifiedSession owner,ModeControl modes,AudioDescription description,AudioSink sink)throws IOException;
        void ready(PairVerify.VerifiedSession owner,ModeControl modes,AudioDescription description,AudioSink sink);
        void stopped(PairVerify.VerifiedSession owner,ModeControl modes,AudioDescription description,AudioSink sink,IOException failure);
    }
    public interface SessionOutputs extends Outputs {
        AudioSink.Output open(PairVerify.VerifiedSession owner,ModeControl modes,
                              AudioDescription description,AudioWireFormat format)throws IOException;
    }
    private final Outputs outputs;private final AudioCapabilities capabilities;
    private final ReceiverWorkers workers;
    private final Map<PairVerify.VerifiedSession,Map<String,AudioSink>> sinks=new IdentityHashMap<PairVerify.VerifiedSession,Map<String,AudioSink>>();
    private synchronized AudioSink find(PairVerify.VerifiedSession owner,String key){Map<String,AudioSink> live=sinks.get(owner);return live==null?null:live.get(key);}
    private synchronized void add(PairVerify.VerifiedSession owner,String key,AudioSink sink)throws IOException{
        Map<String,AudioSink> live=sinks.get(owner);if(live==null){live=new LinkedHashMap<String,AudioSink>();sinks.put(owner,live);}
        AudioSink previous=live.get(key);if(previous!=null&&!previous.isClosing())throw new IOException("Audio feedback owner already exists");live.put(key,sink);
    }
    private synchronized void remove(PairVerify.VerifiedSession owner,String key,AudioSink sink){
        Map<String,AudioSink> live=sinks.get(owner);if(live!=null&&live.get(key)==sink){live.remove(key);if(live.isEmpty())sinks.remove(owner);}
    }
    public Map<String,Object> feedback(PairVerify.VerifiedSession session,AudioDescription description,TimingClock timing)throws IOException{
        session.assertCurrent();AudioSink sink=find(session,description.key);if(sink==null)return null;
        AudioSink.ClockObservation observed=sink.clockObservation();ClockReference reference=timing==null?null:timing.reference();
        Map<String,Object> result=AudioFeedback.project(description,observed.head,observed.rate,reference,System.nanoTime());
        session.assertCurrent();return find(session,description.key)==sink?result:null;
    }
    public AudioStreamFactory(Outputs outputs){this(outputs,null);}
    public AudioStreamFactory(Outputs outputs,AudioCapabilities capabilities){this(outputs,capabilities,null);}
    public AudioStreamFactory(Outputs outputs,AudioCapabilities capabilities,ReceiverWorkers workers){if(outputs==null)throw new NullPointerException();this.outputs=outputs;this.capabilities=capabilities;this.workers=workers;}
    public AudioCapabilities capabilities(){return capabilities;}
    public boolean supports(AudioDescription description){return (capabilities==null||capabilities.supports(description))&&outputs.supports(description);}
    public AudioDataStream.Sink open(final PairVerify.VerifiedSession session,final AudioDescription description,final ModeControl modes)throws IOException{
        return open(session,description,modes,null);
    }
    public AudioDataStream.Sink open(final PairVerify.VerifiedSession session,final AudioDescription description,final ModeControl modes,final TimingClock timing)throws IOException{
        session.assertCurrent();if(!supports(description))throw new IOException("Unsupported audio output");
        final AudioSink[] owned=new AudioSink[1];
        ReceiverWorkers.Slot slot=workers==null?null:workers.reserve();boolean attached=false;
        final AudioSink sink;
        AudioSink.Factory streamOutput=new AudioSink.Factory(){public AudioSink.Output open(AudioWireFormat format)throws IOException{
            session.assertCurrent();
            AudioSink.Output opened=outputs instanceof SessionOutputs?
                ((SessionOutputs)outputs).open(session,modes,description,format):outputs.open(format);
            try{session.assertCurrent();return opened;}
            catch(IOException e){opened.close();throw e;}
        }};
        try{sink=new AudioSink(description.format,streamOutput,new AudioSink.Listener(){
            public void ready(){outputs.ready(session,modes,description,owned[0]);}
            public void stopped(IOException failure){
                remove(session,description.key,owned[0]);
                boolean failed=failure!=null;
                try{outputs.stopped(session,modes,description,owned[0],failure);}
                catch(RuntimeException e){failed=true;}
                catch(LinkageError e){failed=true;}
                finally{if(failed)session.close();}
            }
        },capabilities!=null,timing==null?null:new ClockReference.Source(){public ClockReference reference()throws IOException{session.assertCurrent();return timing.reference();}});
        owned[0]=sink;
        if(slot!=null)slot.attach(new ReceiverWorkers.Worker(){public void close(){sink.close();}public boolean awaitStopped(long millis)throws InterruptedException{return sink.awaitStopped(millis);}});
        attached=true;
        }finally{if(!attached&&slot!=null)slot.failed();}
        try{add(session,description.key,sink);outputs.opened(session,modes,description,sink);session.assertCurrent();return sink;}
        catch(IOException e){remove(session,description.key,sink);sink.closed(e);throw e;}
        catch(RuntimeException e){remove(session,description.key,sink);sink.closed(new IOException("Audio output binding failed",e));throw e;}
    }
}
