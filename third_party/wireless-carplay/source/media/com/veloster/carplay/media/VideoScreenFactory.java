/* SPDX-License-Identifier: GPL-3.0-only. See media/NOTICE.md. */
package com.veloster.carplay.media;

import com.veloster.carplay.control.IapSetupHandler;
import com.veloster.carplay.control.EncryptedEvents;
import com.veloster.carplay.control.ControlMessage;
import com.veloster.carplay.control.ReceiverMetadata;
import com.veloster.carplay.control.ReceiverWorkers;
import com.veloster.carplay.pairing.PairVerify;
import com.veloster.carplay.tunnel.ScreenDataStream;
import com.veloster.carplay.control.TimingClock;
import java.io.IOException;
import java.util.Map;

/** Connects authenticated type110/111 SETUP to actual video workers, owned by that VerifiedSession. */
public final class VideoScreenFactory implements IapSetupHandler.ScreenSinkFactory {
    public interface Screens {
        boolean supports(int type);
        /** Bind this exact sink to a view's Surface lifecycle. Called on control worker, must not block. */
        void opened(int type,VideoSink sink)throws IOException;
        /** Exact sink identity prevents delayed requests from an old stream affecting a replacement. */
        void keyframeNeeded(int type,VideoSink sink);
        /** Remove only this sink, not a replacement stream, on decoder worker. */
        void closed(int type,VideoSink sink,IOException reason);
    }
    private final Screens screens;
    private final String alternateUuid;
    private final ReceiverWorkers workers;
    public static VideoScreenFactory forReceiver(Screens screens,ReceiverMetadata metadata)throws IOException{
        return forReceiver(screens,metadata,null);
    }
    public static VideoScreenFactory forReceiver(Screens screens,ReceiverMetadata metadata,ReceiverWorkers workers)throws IOException{
        if(screens==null||metadata==null)throw new NullPointerException();
        if(!screens.supports(110)||screens.supports(111)!=metadata.supports(111))throw new IOException("Screen metadata mismatch");
        return new VideoScreenFactory(screens,metadata.supports(111)?metadata.displayUuid(111):null,workers);
    }
    public VideoScreenFactory(Screens screens,String alternateUuid)throws IOException{
        this(screens,alternateUuid,null);
    }
    private VideoScreenFactory(Screens screens,String alternateUuid,ReceiverWorkers workers)throws IOException{
        if(screens==null)throw new NullPointerException();
        if(screens.supports(111)||alternateUuid!=null)ControlMessage.validateDisplayUuid(alternateUuid);
        this.screens=screens;this.alternateUuid=alternateUuid;this.workers=workers;
    }
    public boolean supports(int type){return (type==110||type==111)&&screens.supports(type);}
    public ScreenDataStream.Sink open(final PairVerify.VerifiedSession session,final int type,Map<String,Object> stream,TimingClock timing,EncryptedEvents events)throws IOException{
        session.assertCurrent();if(!supports(type))throw new IOException("Screen not supported");
        final EncryptedEvents.ScreenRecovery recovery=events.screenRecovery(session,type,type==111?alternateUuid:null);
        final VideoSink[] owned=new VideoSink[1];
        ReceiverWorkers.Slot slot=workers==null?null:workers.reserve();boolean attached=false;
        final VideoSink sink;
        try{sink=new VideoSink(new VideoSink.Listener(){
            public void keyframeNeeded(){if(recovery.request())screens.keyframeNeeded(type,owned[0]);}
            public void stopped(IOException reason){
                // Explicit application stop also retires the parent; an idle socket must not keep it alive.
                // Surface loss uses setOutput(null), not close(). Session close is nonblocking/idempotent.
                recovery.close();
                if(reason!=null||!owned[0].protocolRetired())session.close();
                screens.closed(type,owned[0],reason);
            }
        },timing);
        owned[0]=sink;
        if(slot!=null)slot.attach(new ReceiverWorkers.Worker(){public void close(){sink.close();}public boolean awaitStopped(long millis)throws InterruptedException{return sink.awaitStopped(millis);}});
        attached=true;
        }finally{if(!attached){recovery.close();if(slot!=null)slot.failed();}}
        try{screens.opened(type,sink);session.assertCurrent();return sink;}
        catch(IOException e){sink.closed(e);throw e;}
        catch(RuntimeException e){sink.closed(new IOException("Screen view binding failed",e));throw e;}
    }
}
