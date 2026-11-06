/* SPDX-License-Identifier: GPL-3.0-only. Tracked optional physical input source. */
package com.veloster.carplay.input;
import com.veloster.carplay.control.*;
import com.veloster.carplay.pairing.PairVerify;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Wraps the existing control worker. Physical callbacks belong to one exact
 * verified session. Provider I/O runs off UI/event threads; its retirement is
 * drained with that session. An absent optional source leaves UI Siri usable. */
public final class ReceiverHardwareSiriSource implements IapSetupHandler.SessionControlFactory {
    public interface Listener {boolean down();void up();void retired();}
    public interface Lease extends Closeable {void renew(boolean downEnabled)throws IOException;}
    public interface Provider {Lease open(String nonce,Listener listener)throws IOException;}
    private final IapSetupHandler.SessionControlFactory delegate;
    private final ReceiverSiriInput input;private final Provider provider;
    private final Object ownerLock=new Object();private Binding current;
    private volatile IOException lastFailure;
    public ReceiverHardwareSiriSource(IapSetupHandler.SessionControlFactory delegate,ReceiverSiriInput input,Provider provider){
        if(delegate==null||input==null||provider==null)throw new NullPointerException();
        this.delegate=delegate;this.input=input;this.provider=provider;
    }
    public IOException failure(){return lastFailure;}
    public ReceiverWorkers.Worker open(PairVerify.VerifiedSession session,ModeControl modes,EncryptedEvents events,ReceiverMetadata metadata)throws IOException{
        ReceiverWorkers.Worker child=delegate.open(session,modes,events,metadata);
        Binding binding=new Binding(session,child);
        synchronized(ownerLock){if(input.hardwareSessionCurrent(session)){current=binding;lastFailure=null;}}
        binding.start();return binding;
    }
    private final class Binding implements ReceiverWorkers.Worker,Runnable,Listener {
        private final PairVerify.VerifiedSession session;private final ReceiverWorkers.Worker child;
        private final Object lock=new Object();private final CountDownLatch stopped=new CountDownLatch(1);
        private boolean closing,armed,retired;private ReceiverSiriInput.HardwarePress press;
        Binding(PairVerify.VerifiedSession session,ReceiverWorkers.Worker child){this.session=session;this.child=child;}
        private void report(IOException error){synchronized(ownerLock){if(current==this)lastFailure=error;}}
        void start(){try{Thread thread=new Thread(this,"vn-hardware-siri");thread.setDaemon(true);thread.start();}
            catch(RuntimeException e){report(new IOException("Physical Siri worker unavailable",e));retired();stopped.countDown();}
            catch(LinkageError e){report(new IOException("Physical Siri worker API unavailable",e));retired();stopped.countDown();}}
        private boolean live(){synchronized(lock){return !closing&&!retired;}}
        public boolean down(){synchronized(lock){
            if(closing||retired||!armed||press!=null)return false;
            press=input.pressHardware(session);return press!=null;
        }}
        public void up(){synchronized(lock){if(press!=null){press.release();press=null;}}}
        public void retired(){synchronized(lock){retired=true;armed=false;if(press!=null){press.close();press=null;}lock.notifyAll();}}
        private void waitTick()throws InterruptedException{synchronized(lock){if(!closing&&!retired)lock.wait(250);}}
        public void run(){Lease lease=null;try{
            while(live()&&input.hardwareSessionCurrent(session)&&!input.hardwareAvailable(session)){session.assertCurrent();waitTick();}
            if(!live()||!input.hardwareSessionCurrent(session))return;session.assertCurrent();
            lease=provider.open(UUID.randomUUID().toString(),this);
            if(lease==null)throw new IOException("Physical Siri source absent");
            synchronized(lock){if(!closing&&!retired)armed=true;}
            while(live()&&input.hardwareSessionCurrent(session)){
                session.assertCurrent();lease.renew(input.hardwareAvailable(session));waitTick();
            }
        }catch(IOException e){if(live()&&!session.controlRetired())report(e);}
         catch(InterruptedException e){Thread.currentThread().interrupt();if(live())report(new IOException("Physical Siri source interrupted",e));}
         catch(RuntimeException e){if(live())report(new IOException("Physical Siri source failed",e));}
         catch(LinkageError e){if(live())report(new IOException("Physical Siri source API unavailable",e));}
         finally{retired();try{if(lease!=null)lease.close();}catch(IOException e){report(e);}finally{stopped.countDown();}}
        }
        public void close(){synchronized(lock){closing=true;lock.notifyAll();}retired();child.close();}
        public boolean awaitStopped(long millis)throws InterruptedException{
            if(millis<0)throw new IllegalArgumentException();long began=System.nanoTime();
            if(!stopped.await(millis,TimeUnit.MILLISECONDS))return false;
            return child.awaitStopped(Math.max(0,millis-TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)));
        }
    }
}
