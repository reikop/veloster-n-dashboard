/* SPDX-License-Identifier: GPL-3.0-only. Optional verified-session Consumer key source. */
package com.veloster.carplay.mediakeys;

import com.veloster.carplay.control.*;
import com.veloster.carplay.input.ConsumerHid;
import com.veloster.carplay.pairing.PairVerify;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Provider I/O and encrypted ACKs run on tracked workers, never on key callbacks. */
public final class ReceiverHardwareConsumerSource implements IapSetupHandler.SessionControlFactory,Closeable {
    public enum Route { ORIGINAL, CLAIMED, QUARANTINED }
    public interface Listener {Route down(int usage);Route up(int usage);void retired();}
    public interface Lease extends Closeable {void renew(boolean downEnabled)throws IOException;}
    public interface Provider {Lease open(String nonce,Listener listener)throws IOException;}
    public interface MediaGate {boolean enabled(ModeControl owner);}
    public interface Failure {void failed(PairVerify.VerifiedSession session,IOException error);}

    private final IapSetupHandler.SessionControlFactory delegate;
    private final ReceiverScope scope;
    private final ConsumerHid hid;
    private final Provider provider;
    private final MediaGate mediaGate;
    private final Failure failureCallback;
    private final Object ownerLock=new Object();
    private Binding current;
    private volatile IOException lastFailure;
    private boolean closed;
    public ReceiverHardwareConsumerSource(IapSetupHandler.SessionControlFactory delegate,ReceiverScope scope,
        ConsumerHid hid,Provider provider,MediaGate mediaGate,Failure failure){
        if(delegate==null||scope==null||hid==null||provider==null||mediaGate==null||failure==null)throw new NullPointerException();
        this.delegate=delegate;this.scope=scope;this.hid=hid;this.provider=provider;this.mediaGate=mediaGate;failureCallback=failure;
    }
    public ConsumerHid declaration(){return hid;}
    public IOException failure(){return lastFailure;}
    public ReceiverWorkers.Worker open(PairVerify.VerifiedSession session,ModeControl modes,
        EncryptedEvents events,ReceiverMetadata metadata)throws IOException{
        if(session==null||modes==null||events==null||metadata==null||metadata.consumerInput()!=hid||!scope.contains(session.context()))
            throw new IOException("Consumer source scope/declaration mismatch");
        synchronized(ownerLock){if(closed)throw new IOException("Consumer source disposed");}
        EncryptedEvents.ConsumerChannel channel=events.consumerChannel(session,metadata);
        ReceiverWorkers.Worker child;
        try{child=delegate.open(session,modes,events,metadata);}
        catch(IOException error){channel.close();throw error;}
        catch(RuntimeException error){channel.close();throw error;}
        catch(LinkageError error){channel.close();throw error;}
        if(child==null){channel.close();throw new IOException("Consumer delegate absent");}
        Binding next=new Binding(session,modes,events,channel,child);
        Binding old;boolean stop;
        synchronized(ownerLock){old=current;next.predecessor=old;current=next;lastFailure=null;stop=closed;}
        // Publish the tracked binding even when cancellation races allocation.
        next.start();
        if(stop)try{next.close();}catch(RuntimeException error){next.fatal(new IOException("Consumer cancellation failed",error));}
        catch(LinkageError error){next.fatal(new IOException("Consumer cancellation API unavailable",error));}
        if(old!=null)try{old.close();}catch(RuntimeException error){old.fatal(new IOException("Previous Consumer source cancellation failed",error));}
        catch(LinkageError error){old.fatal(new IOException("Previous Consumer cancellation API unavailable",error));}
        return next;
    }
    public void close(){Binding value;synchronized(ownerLock){closed=true;value=current;}if(value!=null)value.close();}
    /** Observed phone audio ownership plus explicit no-call/no-Siri state. Unknown denies. */
    public static boolean musicMode(ModeState state){return state!=null&&state.audio==ModeState.PHONE&&
        state.phoneCall==ModeState.NONE&&state.speechOwner==ModeState.NONE;}

    private final class Binding implements ReceiverWorkers.Worker,Runnable,Listener {
        final PairVerify.VerifiedSession session;final ModeControl modes;final EncryptedEvents events;
        final EncryptedEvents.ConsumerChannel channel;final ReceiverWorkers.Worker child;
        final Object lock=new Object();final CountDownLatch providerStopped=new CountDownLatch(1);
        volatile ConsumerKeySession sender;
        Binding predecessor;
        boolean closing,retired,armed,cleanupUncertain,failed;
        Binding(PairVerify.VerifiedSession session,ModeControl modes,EncryptedEvents events,
            EncryptedEvents.ConsumerChannel channel,ReceiverWorkers.Worker child){
            this.session=session;this.modes=modes;this.events=events;this.channel=channel;this.child=child;
        }
        void start(){try{Thread thread=new Thread(this,"vn-hardware-consumer-source");thread.setDaemon(true);thread.start();}
            catch(RuntimeException error){startFailed(new IOException("Consumer provider worker unavailable",error));}
            catch(LinkageError error){startFailed(new IOException("Consumer provider API unavailable",error));}}
        private void startFailed(IOException error){try{fatal(error);}finally{providerStopped.countDown();}}
        private boolean ownerCurrent(){synchronized(ownerLock){return current==this;}}
        private boolean channelCurrent(){if(!ownerCurrent())return false;
            try{session.assertCurrent();return session.mediaAllowed(session.binding())&&channel.declaration()==hid;}
            catch(IOException error){return false;}}
        private boolean musicEligible(){return channelCurrent()&&modes.isReady()&&musicMode(modes.snapshot())&&mediaGate.enabled(modes);}
        private boolean downAllowed(){if(!musicEligible())return false;
            synchronized(lock){return !closing&&!retired&&armed;}}
        private boolean active(){synchronized(lock){return !closing&&!retired;}}
        private void fatal(IOException error){boolean first;synchronized(lock){first=!failed;failed=true;closing=true;armed=false;lock.notifyAll();}
            if(!first)return;
            synchronized(ownerLock){if(current==this)lastFailure=error;}
            try{failureCallback.failed(session,error);}catch(RuntimeException ignored){}catch(LinkageError ignored){}
            // The exact verified session and event counter set cannot be reused after an unknown ACK.
            ConsumerKeySession value=sender;if(value!=null)value.close();
            try{child.close();}catch(RuntimeException closeError){synchronized(lock){cleanupUncertain=true;}}
            catch(LinkageError closeError){synchronized(lock){cleanupUncertain=true;}}
            try{session.close();}finally{events.close();}
        }
        public Route down(int usage){
            if(usage!=ConsumerHid.NEXT&&usage!=ConsumerHid.PREVIOUS)return Route.ORIGINAL;
            ConsumerKeySession value=sender;if(value==null)return Route.ORIGINAL;
            if(value.hasOutstanding())return Route.QUARANTINED;
            if(!downAllowed())return Route.ORIGINAL;
            ConsumerKeySession.Route result=value.down(this,usage);
            return result==ConsumerKeySession.Route.CLAIMED?Route.CLAIMED:
                result==ConsumerKeySession.Route.QUARANTINED?Route.QUARANTINED:Route.ORIGINAL;
        }
        public Route up(int usage){
            ConsumerKeySession value=sender;if(value==null)return Route.ORIGINAL;
            ConsumerKeySession.Route result=value.up(this,usage);
            return result==ConsumerKeySession.Route.CLAIMED?Route.CLAIMED:
                result==ConsumerKeySession.Route.QUARANTINED&&value.hasOutstanding()?Route.QUARANTINED:Route.ORIGINAL;
        }
        public void retired(){ConsumerKeySession value=sender;synchronized(lock){retired=true;armed=false;lock.notifyAll();}
            if(value!=null)value.close();}
        public void run(){Lease lease=null;try{
            sender=new ConsumerKeySession(this,new ConsumerKeySession.Current(){public boolean current(){return channelCurrent();}},channel,
                new ConsumerKeySession.Failure(){public void failed(IOException error){fatal(error);}},2000);
            if(predecessor!=null){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(active()&&!predecessor.awaitStopped(100)){
                    if(System.nanoTime()-end>=0)throw new IOException("Previous Consumer owner still draining");
                }}
            while(active()&&!musicEligible()){session.assertCurrent();synchronized(lock){if(!closing&&!retired)lock.wait(100);}}
            if(!active())return;
            lease=provider.open(UUID.randomUUID().toString(),this);
            if(lease==null)throw new IOException("Consumer provider absent after declaration");
            synchronized(lock){if(!closing&&!retired)armed=true;}
            while(active()&&ownerCurrent()){
                session.assertCurrent();lease.renew(musicEligible());
                synchronized(lock){if(!closing&&!retired)lock.wait(100);}
            }
        }catch(IOException error){if(active())fatal(error);}
         catch(InterruptedException error){Thread.currentThread().interrupt();if(active())fatal(new IOException("Consumer provider interrupted",error));}
         catch(RuntimeException error){if(active())fatal(new IOException("Consumer provider failed",error));}
         catch(LinkageError error){if(active())fatal(new IOException("Consumer provider API unavailable",error));}
         finally{retired();try{if(lease!=null)lease.close();}
            catch(IOException error){synchronized(lock){cleanupUncertain=true;}fatal(new IOException("Consumer provider close uncertain",error));}
            catch(RuntimeException error){synchronized(lock){cleanupUncertain=true;}fatal(new IOException("Consumer provider close failed",error));}
            catch(LinkageError error){synchronized(lock){cleanupUncertain=true;}fatal(new IOException("Consumer provider close API unavailable",error));}
            boolean unexpected;synchronized(lock){unexpected=retired&&!closing&&!failed;}
            if(unexpected){ConsumerKeySession value=sender;
                try{if(value!=null&&!value.awaitStopped(3000))fatal(new IOException("Consumer retirement release did not drain"));
                    else fatal(new IOException("Consumer source retired while advertised"));}
                catch(InterruptedException error){Thread.currentThread().interrupt();fatal(new IOException("Consumer retirement interrupted",error));}}
            providerStopped.countDown();}
        }
        public void close(){ConsumerKeySession value=sender;synchronized(lock){closing=true;armed=false;lock.notifyAll();}
            if(value!=null)value.close();else channel.close();child.close();}
        public boolean awaitStopped(long millis)throws InterruptedException{
            if(millis<0)throw new IllegalArgumentException("Wait bound");long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
            if(!providerStopped.await(millis,TimeUnit.MILLISECONDS))return false;
            ConsumerKeySession value=sender;
            if(value!=null&&!value.awaitStopped(left(end)))return false;
            if(value!=null&&value.requiresSessionRetirement()&&!events.awaitStopped(left(end)))return false;
            if(!child.awaitStopped(left(end)))return false;
            synchronized(lock){return !cleanupUncertain;}
        }
        private long left(long end){return Math.max(0,TimeUnit.NANOSECONDS.toMillis(end-System.nanoTime()));}
    }
}
