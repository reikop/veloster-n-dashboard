/* SPDX-License-Identifier: GPL-3.0-only. Registration and resource release with confirmed receiver drain. */
package com.veloster.carplay.coordinator;
import java.io.Closeable;
import java.io.IOException;

/** Worker-only, retryable cleanup. Owns registration, receiver and additional
 * resources after successful construction. Cancellation before BEGIN still
 * closes the local adapter; it does not depend on a journaled STOP being sent.
 * The registry stays occupied until all extra resources close successfully. */
public final class RegisteredReceiverLease implements Closeable {
    private final RegisteredReceiverControl registry;private final IndependentReceiverControl receiver;
    private final long drainWaitMillis;private final Closeable[] resources;
    private boolean cancelRequested,drained;private volatile boolean closed;private int released;
    RegisteredReceiverLease(RegisteredReceiverControl registry,IndependentReceiverControl receiver,long drainWaitMillis,Closeable[] resources)throws IOException{
        if(registry==null||receiver==null||resources==null)throw new NullPointerException();
        if(drainWaitMillis<0||drainWaitMillis>10000)throw new IllegalArgumentException("Receiver drain wait");
        this.resources=resources.clone();for(Closeable resource:this.resources)if(resource==null)throw new NullPointerException();
        this.registry=registry;this.receiver=receiver;this.drainWaitMillis=drainWaitMillis;
        registry.register(receiver);
    }
    public boolean isClosed(){return closed;}
    public synchronized void close()throws IOException{
        if(closed)return;
        try{
            if(!cancelRequested){receiver.close();cancelRequested=true;}
            if(!drained){if(!receiver.awaitStopped(drainWaitMillis))throw new IOException("Independent receiver still owns workers");drained=true;}
            while(released<resources.length){resources[released].close();released++;}
            registry.unregister(receiver.scope().owner);closed=true;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Receiver lease cleanup interrupted",interrupted);}
        catch(RuntimeException failure){throw new IOException("Receiver lease resource cleanup failed",failure);}
        catch(LinkageError failure){throw new IOException("Receiver lease resource cleanup API unavailable",failure);}
    }
}
