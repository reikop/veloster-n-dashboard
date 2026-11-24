/* SPDX-License-Identifier: GPL-3.0-only. Session-owned Siri button integration; see control/NOTICE.md. */
package com.veloster.carplay.input;
import com.veloster.carplay.control.*;
import com.veloster.carplay.pairing.PairVerify;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Nonblocking UI/physical-button producers and one tracked sender per verified session.
 * A successful action queues a button request, not Siri activation or microphone capture.
 * Physical mapping belongs to a verified source adapter; this class owns only
 * the exact press lifetime. No Siri-cancel opcode or microphone is owned here. */
public final class ReceiverSiriInput implements IapSetupHandler.SessionControlFactory,Closeable {
    private static final int UI=1,HARDWARE=2;
    public interface Failure {void failed(IOException error);}
    private final ReceiverScope scope;private final Failure failure;private final Object lock=new Object();
    private boolean closed,resumed,focused=true;private long eligibility;private Binding current;
    public ReceiverSiriInput(ReceiverScope scope,Failure failure){if(scope==null||failure==null)throw new NullPointerException();this.scope=scope;this.failure=failure;}
    private void notifyFailure(IOException error){try{failure.failed(error);}catch(RuntimeException ignored){}catch(LinkageError ignored){}}
    public ReceiverWorkers.Worker open(PairVerify.VerifiedSession session,ModeControl modes,EncryptedEvents events,ReceiverMetadata metadata)throws IOException{
        if(session==null||events==null||!scope.contains(session.context()))throw new IOException("Receiver Siri scope mismatch");
        synchronized(lock){if(closed)throw new IOException("Receiver Siri disposed");}
        EncryptedEvents.SiriChannel channel=events.siriChannel(session);Binding next;
        try{next=new Binding(session,channel);}catch(RuntimeException error){channel.close();throw new IOException("Receiver Siri allocation failed",error);}
        synchronized(lock){Binding old=current;if(!closed)current=next;else next.closing=true;eligibility++;if(old!=null)old.close();lock.notifyAll();}
        // Even if close raced allocation, return the worker so the handler can drain it.
        next.start();return next;
    }
    public void foreground(boolean value){synchronized(lock){resumed=value;if(!value)withdraw();lock.notifyAll();}}
    public void windowFocus(boolean value){synchronized(lock){focused=value;if(!value)withdraw();lock.notifyAll();}}
    private void withdraw(){eligibility++;if(current!=null&&current.producer!=HARDWARE){current.intent=0;current.ready=false;}}
    private boolean eligible(Binding value,int producer){return !closed&&current==value&&!value.closing&&(producer==HARDWARE||resumed&&focused);}
    /** Snapshot only. Actual session/media admission is checked again by the sender. */
    public boolean available(){synchronized(lock){return available(UI);}}
    private boolean available(int producer){return current!=null&&eligible(current,producer)&&current.ready&&current.intent==0&&!current.down&&!current.sending&&current.hardware==null;}
    private boolean request(int intent){synchronized(lock){if(!available(UI))return false;Binding value=current;
        value.producer=UI;value.intent=intent;lock.notifyAll();return true;}}
    public boolean pulse(){return request(2);}
    public boolean press(){return request(1);}
    public boolean release(){synchronized(lock){Binding value=current;if(value==null||value.producer!=UI||value.closing||value.intent==0&&!value.down&&!value.sending)return false;
        value.intent=0;lock.notifyAll();return true;}}
    public boolean hardwareAvailable(){synchronized(lock){return available(HARDWARE);}}
    /** A delayed callback from a previous physical-source lease cannot select
     * the successor's otherwise-ready channel. Identity, not equal fields. */
    public boolean hardwareAvailable(PairVerify.VerifiedSession expected){synchronized(lock){return current!=null&&current.session==expected&&available(HARDWARE);}}
    public boolean hardwareSessionCurrent(PairVerify.VerifiedSession expected){synchronized(lock){return current!=null&&current.session==expected&&eligible(current,HARDWARE);}}
    /** One physical DOWN, independent of Activity visibility. The adapter must
     * retain this handle: an UP or source loss cannot release a later session. */
    public HardwarePress pressHardware(){return pressHardware(null);}
    public HardwarePress pressHardware(PairVerify.VerifiedSession expected){synchronized(lock){
        if(expected!=null&&(current==null||current.session!=expected))return null;
        if(!available(HARDWARE))return null;Binding value=current;
        HardwarePress press=new HardwarePress(value);value.hardware=press;
        value.producer=HARDWARE;value.intent=1;lock.notifyAll();return press;
    }}
    public final class HardwarePress implements Closeable {
        private final Binding binding;private boolean finished;
        private HardwarePress(Binding binding){this.binding=binding;}
        /** Physical UP preserves an accepted quick press as DOWN then UP even
         * if the encrypted sender has not run yet. It never targets a new peer. */
        public boolean release(){return finish(true);}
        /** Source disconnect/revocation cancels an unsent DOWN, or releases
         * this channel's already-sent DOWN. It cannot manufacture a new press. */
        public void close(){finish(false);}
        private boolean finish(boolean physicalUp){synchronized(lock){
            if(finished)return false;finished=true;
            if(binding.hardware!=this)return false;
            binding.intent=physicalUp&&!binding.closing&&eligible(binding,HARDWARE)&&
                binding.intent!=0&&!binding.down?2:0;
            if(binding.intent==0&&!binding.down&&!binding.sending)binding.hardware=null;
            lock.notifyAll();return true;
        }}
    }
    public void close(){synchronized(lock){if(closed)return;closed=true;eligibility++;Binding value=current;current=null;if(value!=null)value.close();lock.notifyAll();}}
    private final class Binding implements ReceiverWorkers.Worker,Runnable {
        final PairVerify.VerifiedSession session;final EncryptedEvents.SiriChannel channel;final CountDownLatch stopped=new CountDownLatch(1);
        boolean closing,ready,down,sending;int intent,producer;HardwarePress hardware;
        Binding(PairVerify.VerifiedSession session,EncryptedEvents.SiriChannel channel){this.session=session;this.channel=channel;}
        void start(){Thread worker=new Thread(this,"vn-receiver-siri");worker.setDaemon(true);try{worker.start();}
            catch(RuntimeException error){close();channel.close();synchronized(lock){if(current==this)current=null;}stopped.countDown();notifyFailure(new IOException("Siri worker unavailable",error));}}
        private void report(IOException error){boolean report;synchronized(lock){report=!closing&&current==this;}if(report)notifyFailure(error);}
        public void run(){
            try{for(;;){
                session.assertCurrent();channel.checkCurrent();boolean admitted=session.mediaAllowed(session.binding()),transportReady=channel.ready();int action;final long generation;final int source;
                synchronized(lock){
                    // Event state may call SendGuard under its own lock; never
                    // acquire that lock while holding this producer monitor.
                    ready=admitted&&transportReady;if(!admitted)intent=0;
                    if(down&&(closing||!eligible(this,producer)||intent==0)){if(!admitted)break;action=3;}
                    else if(closing)break;
                    else if(!down&&intent!=0&&eligible(this,producer)&&ready)action=2;
                    else{lock.wait(100);continue;}
                    generation=eligibility;source=producer;sending=true;
                }
                final int selected=action;
                boolean acknowledged=channel.send(action,2000,new EncryptedEvents.SendGuard(){public boolean current(){synchronized(lock){
                    // An UP completes only this channel's sent DOWN, even after UI withdrawal.
                    return selected==3?down:eligible(Binding.this,source)&&producer==source&&intent!=0&&(source==HARDWARE||eligibility==generation);
                }}});
                synchronized(lock){sending=false;if(acknowledged){down=action==2;if(action==2&&intent==2)intent=0;}
                    if(!down&&intent==0)hardware=null;lock.notifyAll();}
            }}catch(IOException error){
                // Normal control termination can precede handler worker close.
                // A dead session alone is insufficient evidence: event failures
                // also revoke it, and must still reach the UI failure callback.
                if(!session.controlRetired())report(error);
            }
              catch(InterruptedException error){Thread.currentThread().interrupt();report(new IOException("Siri worker interrupted",error));}
              catch(RuntimeException error){report(new IOException("Siri worker failed",error));}
              catch(LinkageError error){report(new IOException("Siri API unavailable",error));}
            finally{channel.close();synchronized(lock){ready=false;sending=false;down=false;intent=0;hardware=null;closing=true;if(current==this)current=null;lock.notifyAll();}stopped.countDown();}
        }
        public void close(){synchronized(lock){closing=true;ready=false;intent=0;lock.notifyAll();}}
        public boolean awaitStopped(long millis)throws InterruptedException{if(millis<0)throw new IllegalArgumentException("Wait bound");return stopped.await(millis,TimeUnit.MILLISECONDS);}
    }
}
