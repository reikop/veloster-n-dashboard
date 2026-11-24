/* SPDX-License-Identifier: GPL-3.0-only. Owns runtime control after authenticated type130 SETUP. */
package com.veloster.carplay.control;
import com.veloster.carplay.*;
import com.veloster.carplay.location.LocationSource;
import com.veloster.carplay.pairing.PairVerify;
import com.veloster.carplay.tunnel.TunnelIap2Session;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** RuntimeSink for IapSetupHandler. Factory runs on the owned runtime worker, never control/Android UI.
 * No permissive owner, test auth, metadata, or automatic approval implementation is supplied. */
public final class RuntimeControlHost implements IapSetupHandler.RetirableRuntimeSink,Closeable {
    public interface Factory {
        /** Bind actual OEM auth and session-specific app/AP observers. All owned resources must be in the result. */
        Resources open(PairVerify.VerifiedSession session)throws IOException,InterruptedException;
    }
    public interface RetirableFactory extends Factory {
        /** Withdraw old callbacks and preserve already established media admission. No cleanup I/O. */
        void retire(PairVerify.VerifiedSession session)throws IOException;
    }
    public abstract static class Resources implements Closeable {
        public final Iap2WirelessController.Authentication authentication;
        public final Iap2WirelessController.Identity identity;
        public final Iap2RuntimeController.Environment environment;
        public final Iap2RuntimeController.Listener listener;
        public final LocationSource location;
        protected Resources(Iap2WirelessController.Authentication authentication,Iap2WirelessController.Identity identity,
                Iap2RuntimeController.Environment environment,Iap2RuntimeController.Listener listener){
            this(authentication,identity,environment,listener,null);
        }
        protected Resources(Iap2WirelessController.Authentication authentication,Iap2WirelessController.Identity identity,
                Iap2RuntimeController.Environment environment,Iap2RuntimeController.Listener listener,LocationSource location){
            if(authentication==null||identity==null||environment==null||listener==null)throw new NullPointerException();
            this.authentication=authentication;this.identity=identity;this.environment=environment;this.listener=listener;this.location=location;
        }
    }
    private final Factory factory;
    private Task task;
    private boolean closing;
    public RuntimeControlHost(Factory factory){if(factory==null)throw new NullPointerException();this.factory=factory;}
    public synchronized void opened(PairVerify.VerifiedSession owner,TunnelIap2Session runtime,TimingClock timing)throws IOException{
        owner.assertCurrent();if(closing)throw new IOException("Runtime host closing");if(task!=null&&(task.finished.getCount()!=0||!task.released))throw new IOException("Previous runtime worker still owns resources");
        final Task next=new Task(owner,runtime);Thread thread=new Thread(next,"vn-carplay-runtime-control");thread.setDaemon(true);task=next;
        try{thread.start();}catch(RuntimeException e){next.released=true;next.finished.countDown();owner.close();throw new IOException("Runtime worker unavailable",e);}
    }
    public synchronized void closed(PairVerify.VerifiedSession owner){
        if(task==null||task.owner!=owner)return;task.stop.set(true);Iap2RuntimeController controller=task.controller;if(controller!=null)controller.requestStop();
    }
    public boolean retire(PairVerify.VerifiedSession owner,TunnelIap2Session runtime)throws IOException{
        Task observed;synchronized(this){observed=task;}
        if(observed==null||observed.owner!=owner||observed.runtime!=runtime)return false;
        synchronized(observed.lifecycle){
            if(observed.completing||observed.protocolRetired)return false;
            owner.assertCurrent();
            if(!runtime.retire())return false;
            observed.protocolRetired=true;
            observed.stop.set(true);
            if(factory instanceof RetirableFactory)((RetirableFactory)factory).retire(owner);
            if(observed.controller!=null)observed.controller.requestStop();return true;
        }
    }
    public boolean retired(PairVerify.VerifiedSession owner,TunnelIap2Session runtime)throws InterruptedException{
        Task observed;synchronized(this){observed=task;}
        return observed!=null&&observed.owner==owner&&observed.runtime==runtime&&observed.protocolRetired&&observed.finished.getCount()==0&&observed.released&&runtime.awaitStopped(0);
    }
    /** Worker-only. millis bounds the worker join; a positive join also retries retained
     * resource close (whose platform operation has its own bound). Zero only observes. */
    public boolean awaitStopped(long millis)throws InterruptedException{
        Task observed;synchronized(this){observed=task;}if(observed==null)return true;if(!observed.finished.await(millis,TimeUnit.MILLISECONDS))return false;
        if(millis>0)observed.release();return observed.released;
    }
    /** Permanent receiver shutdown. Never take a session lock under the host monitor. */
    public void close(){
        Task observed;synchronized(this){closing=true;observed=task;}
        if(observed!=null){observed.stop.set(true);Iap2RuntimeController controller=observed.controller;
            if(controller!=null)controller.requestStop();observed.owner.close();}
    }
    public synchronized Iap2RuntimeController.State state(){return task==null?Iap2RuntimeController.State.NEW:task.state;}
    private final class Task implements Runnable {
        final PairVerify.VerifiedSession owner;final TunnelIap2Session runtime;
        final AtomicBoolean stop=new AtomicBoolean();final CountDownLatch finished=new CountDownLatch(1);
        final Object lifecycle=new Object();
        volatile Resources acquired;volatile boolean released;
        volatile boolean protocolRetired;boolean completing;
        synchronized void release(){
            if(released)return;try{if(acquired!=null)acquired.close();released=true;}
            catch(IOException e){state=Iap2RuntimeController.State.FAILED;}
            catch(RuntimeException e){state=Iap2RuntimeController.State.FAILED;}
            catch(LinkageError e){state=Iap2RuntimeController.State.FAILED;}
        }
        volatile Iap2RuntimeController controller;volatile Iap2RuntimeController.State state=Iap2RuntimeController.State.NEW;
        Task(PairVerify.VerifiedSession owner,TunnelIap2Session runtime){this.owner=owner;this.runtime=runtime;}
        void check()throws IOException{if(stop.get())throw new IOException("Runtime cancelled");owner.assertCurrent();}
        public void run(){
            try{
                check();final PairVerify.Context context=owner.context();acquired=factory.open(owner);check();
                if(acquired==null)throw new IOException("Runtime resources unavailable");final Resources resources=acquired;
                Iap2RuntimeController.Environment environment=new Iap2RuntimeController.LocationEnvironment(){
                    public boolean locationAllowed()throws IOException{checkCurrent();return owner.mediaAllowed(owner.binding());}
                    public void checkCurrent()throws IOException{check();resources.environment.checkCurrent();check();}
                    public Iap2WirelessController.ApSnapshot accessPoint()throws IOException{
                        checkCurrent();Iap2WirelessController.ApSnapshot ap=resources.environment.accessPoint();
                        if(ap==null)throw new IOException("Runtime AP unavailable");
                        try{checkCurrent();if(!context.apSession.equals(ap.session))throw new IOException("Runtime AP binding mismatch");return ap;}
                        catch(IOException e){ap.close();throw e;}
                    }
                    public Iap2WirelessController.ReceiverIdentity receiver()throws IOException{
                        checkCurrent();Iap2WirelessController.ReceiverIdentity receiver=resources.environment.receiver();checkCurrent();
                        if(receiver==null||!context.apSession.equals(receiver.apSession))throw new IOException("Runtime receiver binding mismatch");return receiver;
                    }
                    public void transportIdentifiers(String selected,String usb)throws IOException{
                        checkCurrent();if(selected!=null&&!context.selectedBluetooth.equals(selected))throw new IOException("Runtime phone binding mismatch");
                        resources.environment.transportIdentifiers(selected,usb);checkCurrent();owner.transportIdentifiers(selected,usb);checkCurrent();
                    }
                };
                Iap2WirelessController.Authentication authentication=new Iap2WirelessController.Authentication(){
                    public byte[] certificate()throws IOException{check();byte[] result=resources.authentication.certificate();return validate(result);}
                    public byte[] sign(byte[] challenge)throws IOException{check();byte[] result=resources.authentication.sign(challenge);return validate(result);}
                    private byte[] validate(byte[] result)throws IOException{
                        try{check();return result;}catch(IOException e){if(result!=null)java.util.Arrays.fill(result,(byte)0);throw e;}
                    }
                };
                Iap2RuntimeController.Listener listener=new Iap2RuntimeController.Listener(){
                    public void state(Iap2RuntimeController.State value)throws IOException{check();state=value;resources.listener.state(value);check();}
                    public void update(Iap2Csm.Frame frame)throws IOException{check();resources.listener.update(frame);check();}
                    public void availability(boolean available)throws IOException{check();resources.listener.availability(available);check();}
                };
                controller=new Iap2RuntimeController(runtime,authentication,resources.identity,context.selectedBluetooth,environment,listener,resources.location);
                if(stop.get())controller.requestStop();state=controller.run();
            }catch(InterruptedException e){Thread.currentThread().interrupt();state=Iap2RuntimeController.State.CANCELLED;}
             catch(IOException e){state=stop.get()?Iap2RuntimeController.State.CANCELLED:Iap2RuntimeController.State.FAILED;}
             catch(RuntimeException e){state=Iap2RuntimeController.State.FAILED;}
             catch(LinkageError e){state=Iap2RuntimeController.State.FAILED;}
             finally{
                synchronized(lifecycle){completing=true;if(!protocolRetired)owner.close();else state=Iap2RuntimeController.State.CANCELLED;}
                try{runtime.close();}catch(IOException e){if(!protocolRetired){state=Iap2RuntimeController.State.FAILED;owner.close();}}
                try{release();}finally{finished.countDown();}
             }
        }
    }
}
