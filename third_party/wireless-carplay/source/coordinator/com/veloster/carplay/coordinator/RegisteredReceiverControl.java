/* SPDX-License-Identifier: GPL-3.0-only. Explicit per-owner receiver routing for SessionCoordinator. */
package com.veloster.carplay.coordinator;
import java.io.Closeable;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Construct before SessionCoordinator; register a fresh exact-scope adapter in
 * Factory.open(record, fenced), then give WirelessSession the fenced receiver.
 * There is no unknown-owner success fallback, including after process restart.
 * Old Journal entries need independent backend/process cleanup evidence. */
public final class RegisteredReceiverControl implements ReceiverControl {
    private final Map<String,IndependentReceiverControl> active=new LinkedHashMap<String,IndependentReceiverControl>();
    private final Map<String,Boolean> released=new LinkedHashMap<String,Boolean>();
    /** Registers a fresh receiver and transfers ownership only on success.
     * Extra resources must support retry after a failed close; successful closes
     * are remembered and never repeated by the returned lease. */
    public RegisteredReceiverLease lease(IndependentReceiverControl receiver,long drainWaitMillis,Closeable... resources)throws IOException{
        return new RegisteredReceiverLease(this,receiver,drainWaitMillis,resources);
    }
    public synchronized void register(IndependentReceiverControl receiver)throws IOException{
        if(receiver==null)throw new NullPointerException();String owner=receiver.scope().owner;
        if(active.containsKey(owner)||released.containsKey(owner))throw new IOException("Receiver attempt token reused");
        if(!active.isEmpty())throw new IOException("Previous independent receiver still registered");
        active.put(owner,receiver);
    }
    /** May only remove a receiver after its actual allocation/worker drain. */
    public void unregister(String owner)throws IOException,InterruptedException{
        IndependentReceiverControl receiver;synchronized(this){receiver=active.get(owner);if(receiver==null){if(released.containsKey(owner))return;throw new IOException("Unregistered receiver owner");}}
        if(!receiver.awaitStopped(0))throw new IOException("Receiver still owns resources");
        synchronized(this){if(active.get(owner)!=receiver)throw new IOException("Receiver registration changed");active.remove(owner);released.put(owner,Boolean.TRUE);
            while(released.size()>32)released.remove(released.keySet().iterator().next());}
    }
    public Reply request(int operation,String owner)throws IOException{
        IndependentReceiverControl receiver;
        synchronized(this){
            receiver=active.get(owner);
            if(receiver==null){
                if(released.containsKey(owner)&&(operation==STOP||operation==STATUS))return new Reply(0,0);
                throw new IOException("No receiver cleanup evidence for this owner");
            }
        }
        return receiver.request(operation,owner);
    }
}
