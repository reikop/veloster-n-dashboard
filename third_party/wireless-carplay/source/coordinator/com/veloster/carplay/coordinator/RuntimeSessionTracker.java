/* SPDX-License-Identifier: GPL-3.0-only. Verified runtime session evidence, independent of publication. */
package com.veloster.carplay.coordinator;
import com.veloster.carplay.Iap2Csm;
import com.veloster.carplay.Iap2RuntimeController;
import com.veloster.carplay.control.ReceiverScope;
import com.veloster.carplay.control.RuntimeControlHost;
import com.veloster.carplay.pairing.BootstrapIdentityStore;
import com.veloster.carplay.pairing.PairVerify;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Decorates the actual OEM/runtime resource factory. READY is accepted only from
 * the runtime controller's callback for the same live verified session. Session
 * start additionally requires the received phone identity/media grant. This is
 * not proof of a decoded frame, audio playback, or a working physical handset. */
public final class RuntimeSessionTracker implements RuntimeControlHost.RetirableFactory,Closeable {
    private final ReceiverScope scope;private final RuntimeControlHost.Factory delegate;
    private volatile boolean closed;private volatile Entry entry;
    private volatile PairVerify.VerifiedSession established;
    private static final class Entry {
        final PairVerify.VerifiedSession session;volatile boolean released;final AtomicBoolean closing=new AtomicBoolean();
        volatile Iap2RuntimeController.State state=Iap2RuntimeController.State.NEW;
        RuntimeControlHost.Resources resources;Closeable pendingResources;volatile boolean allocationFinished;
        Entry(PairVerify.VerifiedSession session){this.session=session;}
    }
    public RuntimeSessionTracker(ReceiverScope scope,RuntimeControlHost.Factory delegate){
        if(scope==null||delegate==null)throw new NullPointerException();this.scope=scope;this.delegate=delegate;
    }
    private void exact(PairVerify.VerifiedSession session)throws IOException{
        PairVerify.Context value=session.context();if(!scope.contains(value))throw new IOException("Runtime session belongs to another receiver scope");
    }
    public RuntimeControlHost.Resources open(final PairVerify.VerifiedSession session)throws IOException,InterruptedException{
        exact(session);final Entry next=new Entry(session);
        synchronized(this){if(closed||entry!=null&&!entry.released)throw new IOException("Runtime tracker already owns resources");if(established!=session)established=null;entry=next;}
        RuntimeControlHost.Resources acquired=null;boolean handed=false;
        try{
            acquired=delegate.open(session);next.resources=acquired;if(acquired==null)throw new IOException("Runtime resources unavailable");final RuntimeControlHost.Resources resources=acquired;
            exact(session);if(closed||entry!=next)throw new IOException("Runtime tracker retired during allocation");
            RuntimeControlHost.Resources tracked=new RuntimeControlHost.Resources(resources.authentication,resources.identity,resources.environment,new Iap2RuntimeController.Listener(){
                public void state(Iap2RuntimeController.State value)throws IOException{
                    if(value==null)throw new IOException("Runtime state unavailable");check();resources.listener.state(value);check();next.state=value;
                }
                public void update(Iap2Csm.Frame frame)throws IOException{check();resources.listener.update(frame);check();}
                public void availability(boolean available)throws IOException{check();resources.listener.availability(available);check();}
                private void check()throws IOException{if(closed||next.closing.get()||next.released||entry!=next)throw new IOException("Runtime observation retired");exact(session);}
            },resources.location){
                public void close()throws IOException{release(next);}
            };
            handed=true;return tracked;
        }catch(SessionCoordinator.FactoryCleanupException partial){next.pendingResources=partial.pendingResources;throw partial;
        }finally{next.allocationFinished=true;if(!handed)release(next);}
    }
    /** No tracker monitor is held while checking a session or owner provider. */
    public boolean sessionStarted(ReceiverScope supplied)throws IOException{
        BootstrapIdentityStore.same(scope,supplied);Entry value=entry;
        PairVerify.VerifiedSession retained=established;
        if(!closed&&retained!=null){
            try{exact(retained);if(retained.mediaAllowed(retained.binding()))return !closed&&established==retained;}
            catch(IOException gone){return false;}
        }
        if(closed||value==null||value.closing.get()||value.released||value.state!=Iap2RuntimeController.State.READY)return false;
        try{exact(value.session);if(!value.session.mediaAllowed(value.session.binding()))return false;}
        catch(IOException gone){return false;}
        return !closed&&entry==value&&!value.closing.get()&&!value.released&&value.state==Iap2RuntimeController.State.READY;
    }
    /** Only protocol retirement preserves a prior READY/media grant. Generic close
     * and failed allocation still withdraw readiness. Old callbacks are fenced now,
     * before potentially delayed platform resource release. */
    public void retire(PairVerify.VerifiedSession session)throws IOException{
        exact(session);Entry value=entry;
        if(value==null)return; // Runtime allocation may not have started yet.
        if(value.session!=session)throw new IOException("Foreign runtime retirement");
        if(value.state==Iap2RuntimeController.State.READY&&session.mediaAllowed(session.binding()))established=session;
        value.closing.set(true);
    }
    /** Memory-only withdrawal; ReceiverRuntime owns cancellation and worker drain. */
    public void close(){closed=true;}
    /** A failed/unfinished resource close is not proof of release. */
    public boolean resourcesReleased(){Entry value=entry;return value==null||value.released;}
    private static void release(Entry value)throws IOException{
        synchronized(value){value.closing.set(true);if(value.released)return;
            if(value.resources!=null)value.resources.close();
            if(value.pendingResources!=null)value.pendingResources.close();value.released=true;}
    }
    /** Worker only, after ReceiverRuntime.awaitStopped proves no allocation or
     * runtime worker remains. Repeats only an unfinished resource close. */
    public boolean retryCleanup()throws IOException{
        if(!closed)throw new IOException("Stop runtime tracker before cleanup retry");Entry value=entry;
        if(value==null)return true;if(!value.allocationFinished)return false;release(value);return value.released;
    }
}
