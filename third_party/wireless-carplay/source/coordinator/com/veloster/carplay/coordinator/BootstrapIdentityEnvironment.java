/* SPDX-License-Identifier: GPL-3.0-only. Authenticated bootstrap callback -> exact-scope evidence. */
package com.veloster.carplay.coordinator;
import com.veloster.carplay.Iap2WirelessController;
import com.veloster.carplay.control.ProvisionalPeerAdmission;
import com.veloster.carplay.control.ReceiverScope;
import com.veloster.carplay.pairing.BootstrapIdentityStore;
import java.io.Closeable;
import java.io.IOException;

/** Wrap the actual platform Environment before passing it to WirelessSession.
 * The same instance can be an ApPeerAdmission bootstrap provider before IDs arrive.
 * The caller must use scope.owner as WirelessSession.receiverOwner, supply a real
 * secure selected-bond Transport, and keep Current memory-only for media callbacks.
 * Owns its attempt Binding; the service owns the shared store and delegate. */
public final class BootstrapIdentityEnvironment implements Iap2WirelessController.TransportObservationEnvironment,
        WirelessSession.AttemptEnvironment,ProvisionalPeerAdmission.BootstrapIdentity,Closeable {
    private final ReceiverScope scope;private final Iap2WirelessController.Environment delegate;
    private final BootstrapIdentityStore store;private final BootstrapIdentityStore.Current generation;
    private volatile BootstrapIdentityStore.Binding binding;private boolean started;private volatile boolean retired;
    private IOException cleanupFailure;
    public BootstrapIdentityEnvironment(ReceiverScope scope,Iap2WirelessController.Environment delegate,
            BootstrapIdentityStore store,BootstrapIdentityStore.Current generation){
        if(scope==null||delegate==null||store==null||generation==null)throw new NullPointerException();
        this.scope=scope;this.delegate=delegate;this.store=store;this.generation=generation;
    }
    private void check()throws IOException{
        if(retired)throw new IOException("Bootstrap environment retired");
        try{generation.checkCurrent(scope);}catch(IOException ended){retired=true;throw ended;}
    }
    private void check(String ap,String selected)throws IOException{
        check();if(!scope.apSession.equals(ap)||!scope.selectedBluetooth.equals(selected))throw new IOException("Bootstrap callback scope mismatch");
    }
    public synchronized void attemptStarted(String owner,String ap,String selected)throws IOException{
        check(ap,selected);if(started||!scope.owner.equals(owner))throw new IOException("Bootstrap receiver attempt mismatch");
        binding=store.begin(scope,generation);started=true;
    }
    public synchronized void attemptStopped(String owner)throws IOException{
        if(!scope.owner.equals(owner))throw new IOException("Foreign bootstrap attempt stop");close();
    }
    public String usbIdentifier(ReceiverScope supplied)throws IOException{
        check();BootstrapIdentityStore.same(scope,supplied);BootstrapIdentityStore.Binding value=binding;
        String result=value==null?null:value.usbIdentifier(supplied);check();return result;
    }
    public BootstrapIdentityStore.Record snapshot(ReceiverScope supplied)throws IOException{
        check();BootstrapIdentityStore.same(scope,supplied);BootstrapIdentityStore.Binding value=binding;
        BootstrapIdentityStore.Record result=value==null?null:value.snapshot(supplied);check();return result;
    }
    public Iap2WirelessController.ApSnapshot accessPoint()throws IOException{
        synchronized(this){check();}Iap2WirelessController.ApSnapshot value=delegate.accessPoint();boolean ok=false;
        try{synchronized(this){check();if(value==null||!scope.apSession.equals(value.session))throw new IOException("Bootstrap AP changed");ok=true;return value;}}
        finally{if(!ok&&value!=null)value.close();}
    }
    public boolean current(String ap)throws IOException{
        synchronized(this){check(ap,scope.selectedBluetooth);}boolean result=delegate.current(ap);synchronized(this){check();}return result;
    }
    public Iap2WirelessController.ReceiverIdentity receiver(String ap)throws IOException{
        synchronized(this){check(ap,scope.selectedBluetooth);}return delegate.receiver(ap);
    }
    public Iap2WirelessController.HandoffObservation handoff(String ap,String selected)throws IOException{
        synchronized(this){check(ap,selected);}return delegate.handoff(ap,selected);
    }
    public boolean disconnectBluetoothProfiles(String ap,String selected)throws IOException{
        synchronized(this){check(ap,selected);}return delegate.disconnectBluetoothProfiles(ap,selected);
    }
    public void transportIdentifiers(String ap,String selected,String usb)throws IOException{
        throw new IOException("Bootstrap evidence requires actual received fields");
    }
    public void receivedTransportIdentifiers(String ap,String selected,String observed,String usb)throws IOException{
        synchronized(this){check(ap,selected);if(!started||binding==null)throw new IOException("Bootstrap attempt not started");}
        if(delegate instanceof Iap2WirelessController.TransportObservationEnvironment)
            ((Iap2WirelessController.TransportObservationEnvironment)delegate).receivedTransportIdentifiers(ap,selected,observed,usb);
        else delegate.transportIdentifiers(ap,selected,usb);
        synchronized(this){check(ap,selected);binding.received(observed,usb);check();}
    }
    public synchronized void close()throws IOException{
        retired=true;if(cleanupFailure!=null)throw cleanupFailure;
        if(binding!=null){BootstrapIdentityStore.Binding value=binding;binding=null;
            try{value.close();}catch(IOException failure){cleanupFailure=failure;throw failure;}}
    }
}
