/* SPDX-License-Identifier: GPL-3.0-only. Tracked sender for explicit resource choices. */
package com.veloster.carplay.input;
import com.veloster.carplay.control.*;
import com.veloster.carplay.media.LastUserModes;
import com.veloster.carplay.pairing.PairVerify;
import java.io.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** No network operation on the UI/event-reader thread. One coalesced intent;
 * each worker belongs to the verified session and is drained by its handler. */
public final class ReceiverModeInput implements IapSetupHandler.SessionControlFactory,Closeable {
    private final ReceiverScope scope;private final LastUserModes choices;private final ReceiverSiriInput siri;
    private final Object lock=new Object();private Binding current;private boolean closed;private volatile IOException lastError;
    public IOException failure(){return lastError;}
    public ReceiverModeInput(ReceiverScope scope,LastUserModes choices,ReceiverSiriInput siri){if(scope==null||choices==null||siri==null)throw new NullPointerException();this.scope=scope;this.choices=choices;this.siri=siri;}
    public ReceiverWorkers.Worker open(PairVerify.VerifiedSession session,ModeControl modes,EncryptedEvents events,ReceiverMetadata metadata)throws IOException{
        if(session==null||modes==null||!scope.contains(session.context()))throw new IOException("Mode input scope mismatch");
        Binding next=new Binding(session,modes,siri.open(session,modes,events,metadata));
        synchronized(lock){if(current!=null)current.close();current=next;if(closed)next.closing=true;}
        lastError=null;next.start();return next;
    }
    public void close(){synchronized(lock){closed=true;if(current!=null)current.close();lock.notifyAll();}siri.close();}
    private final class Binding implements ReceiverWorkers.Worker,Runnable {
        final PairVerify.VerifiedSession session;final ModeControl modes;final ReceiverWorkers.Worker voice;final CountDownLatch stopped=new CountDownLatch(1);
        boolean closing;long sent=-1;volatile IOException error;
        Binding(PairVerify.VerifiedSession session,ModeControl modes,ReceiverWorkers.Worker voice){this.session=session;this.modes=modes;this.voice=voice;}
        void start(){try{Thread thread=new Thread(this,"vn-receiver-modes");thread.setDaemon(true);thread.start();}catch(RuntimeException e){startFailed(new IOException("Mode sender unavailable",e));}catch(LinkageError e){startFailed(new IOException("Mode sender API unavailable",e));}}
        void startFailed(IOException e){error=e;synchronized(lock){if(current==this)lastError=e;}close();stopped.countDown();}
        public void run(){try{for(;;){synchronized(lock){if(closed||closing||current!=this)break;}
            session.assertCurrent();boolean admitted=session.mediaAllowed(session.binding());LastUserModes.Snapshot snapshot=choices.snapshot();
            if(admitted&&modes.isReady()&&snapshot.revision!=sent){ModeChanges request=snapshot.modes();
                if(request!=null){ModeControl.Result result=modes.request(request,2000);if(!result.accepted())error=new IOException("Mode request rejected: "+result.httpStatus+"/"+result.protocolStatus);else error=null;}
                synchronized(lock){if(current==this)lastError=error;}
                sent=snapshot.revision;
            }
            synchronized(lock){if(!closing&&!closed&&current==this)lock.wait(100);}
        }}catch(IOException e){error=e;}catch(InterruptedException e){Thread.currentThread().interrupt();error=new IOException("Mode sender interrupted",e);}catch(RuntimeException e){error=new IOException("Mode sender failed",e);}catch(LinkageError e){error=new IOException("Mode sender API unavailable",e);}
        finally{synchronized(lock){closing=true;if(current==this){lastError=error;current=null;}lock.notifyAll();}stopped.countDown();}}
        public void close(){synchronized(lock){closing=true;lock.notifyAll();}voice.close();}
        public boolean awaitStopped(long millis)throws InterruptedException{if(millis<0)throw new IllegalArgumentException();long start=System.nanoTime();if(!stopped.await(millis,TimeUnit.MILLISECONDS))return false;long remaining=Math.max(0,millis-TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));return voice.awaitStopped(remaining);}
    }
}
