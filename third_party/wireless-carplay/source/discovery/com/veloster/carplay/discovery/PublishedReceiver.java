/* SPDX-License-Identifier: GPL-3.0-only. Control endpoint and matching discovery ownership. */
package com.veloster.carplay.discovery;
import com.veloster.carplay.control.*;
import com.veloster.carplay.pairing.*;
import java.io.*;
import java.util.concurrent.TimeUnit;

/** Production entry point: open actual control socket, bind its metadata, then
 * announce that exact identity/port. Caller retains ownership of PairingStore. */
public final class PublishedReceiver implements Closeable {
    public final ControlListener listener;public final ReceiverDiscovery discovery;
    public PublishedReceiver(PairingStore store,PairVerify.Context context,PairVerify.OwnerLease owner,
        ControlConnection.ApprovalUi ui,ControlConnection.Handler handler,ReceiverMetadata metadata,int port,
        ReceiverDiscovery.MulticastLease lease)throws IOException{
        this(store,new ReceiverAdmission.Fixed(context,owner),ui,handler,metadata,port,lease);
    }
    public PublishedReceiver(PairingStore store,ReceiverAdmission admission,
        ControlConnection.ApprovalUi ui,ControlConnection.Handler handler,ReceiverMetadata metadata,int port,
        ReceiverDiscovery.MulticastLease lease)throws IOException{
        listener=new ControlListener(store,admission,ui,handler,port);ReceiverDiscovery opened=null;boolean success=false;
        try{metadata.bind(listener);opened=new ReceiverDiscovery(metadata,lease);success=true;}
        finally{if(!success){
            if(opened!=null)opened.close();listener.close();
            // No caller receives this partially built object. Retain its workers
            // here until they release, rather than orphaning them on failure.
            boolean interrupted=Thread.interrupted(),done=false;
            while(!done)try{done=(opened==null||opened.awaitStopped(100))&&listener.awaitStopped(100);}
            catch(InterruptedException e){interrupted=true;}
            if(interrupted)Thread.currentThread().interrupt();
        }}
        discovery=opened;
    }
    public void close(){discovery.close();listener.close();}
    public boolean awaitStopped(long millis)throws InterruptedException{
        if(millis<0)throw new IllegalArgumentException("Wait duration");long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        if(!discovery.awaitStopped(millis))return false;
        return listener.awaitStopped(Math.max(0,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())));
    }
}
