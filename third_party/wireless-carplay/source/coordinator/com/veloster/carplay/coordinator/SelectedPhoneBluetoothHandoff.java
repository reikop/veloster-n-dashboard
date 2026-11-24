/* SPDX-License-Identifier: GPL-3.0-only. Selected-device Bluetooth teardown. */
package com.veloster.carplay.coordinator;

import java.io.Closeable;
import java.io.IOException;

/** A single AP/selection lease. Commands run on a worker. State queries, never
 * command return values or broadcasts, acknowledge each ordered teardown step.
 * A timed out/cancelled lease cannot be revived by a late callback. */
public final class SelectedPhoneBluetoothHandoff implements Closeable {
    public interface Current {void checkCurrent(String ap,String phone,long generation)throws IOException;}
    public interface Step {
        boolean connected(String phone)throws IOException;
        boolean disconnect(String phone)throws IOException;
    }
    private final String ap,phone;private final long generation,timeoutMillis;
    private final Current current;private final Step[] steps;
    private final Object operation=new Object(),wake=new Object();
    private volatile boolean closed,failed;
    public SelectedPhoneBluetoothHandoff(String ap,String phone,long generation,Current current,Step[] ordered,long timeoutMillis)throws IOException {
        if(ap==null||!ap.matches("[0-9a-f]{32}")||phone==null||!phone.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}")||generation<1||current==null||ordered==null||ordered.length<1||timeoutMillis<1||timeoutMillis>30000)
            throw new IOException("Invalid selected Bluetooth lease");
        this.ap=ap;this.phone=phone;this.generation=generation;this.current=current;this.timeoutMillis=timeoutMillis;
        steps=ordered.clone();for(Step step:steps)if(step==null)throw new IOException("Missing Bluetooth teardown step");check();
    }
    private void check()throws IOException {
        if(closed||failed)throw new IOException("Selected Bluetooth handoff retired");
        if(Thread.currentThread().isInterrupted())throw new IOException("Bluetooth handoff interrupted");
        current.checkCurrent(ap,phone,generation);
    }
    private boolean connected(Step step)throws IOException{check();boolean result=step.connected(phone);check();return result;}
    private static void deadline(long value)throws IOException{if(System.nanoTime()-value>=0)throw new IOException("Selected Bluetooth teardown timed out");}
    public boolean profilesDisconnected()throws IOException {
        synchronized(operation){check();for(Step step:steps)if(connected(step))return false;check();return true;}
    }
    public boolean disconnectProfiles()throws IOException {
        synchronized(operation){
            try {
                check();long deadline=System.nanoTime()+timeoutMillis*1000000L;
                for(Step step:steps){
                    deadline(deadline);
                    if(!connected(step))continue;
                    check();deadline(deadline);boolean accepted=step.disconnect(phone);check();deadline(deadline);
                    // A refusal may race the genuine disconnect. Re-query before
                    // rejecting, but never equate accepted=true with completion.
                    if(!accepted&&connected(step))throw new IOException("Selected Bluetooth teardown refused");
                    while(connected(step)){
                        long left=deadline-System.nanoTime();if(left<=0)throw new IOException("Selected Bluetooth teardown timed out");
                        synchronized(wake){check();try{wake.wait(Math.min(50,Math.max(1,left/1000000L)));}
                            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Bluetooth handoff interrupted",e);}}
                    }
                }
                // Reconnect during a later step invalidates the whole handoff.
                if(!profilesDisconnected())throw new IOException("Selected Bluetooth reconnected during handoff");deadline(deadline);return true;
            }catch(IOException e){failed=true;throw e;}catch(RuntimeException e){failed=true;throw new IOException("Selected Bluetooth handoff unavailable",e);}
        }
    }
    /** Notification is merely a wakeup hint. Foreign or stale events do nothing. */
    public void stateChanged(String ap,String phone,long generation){
        if(!this.ap.equals(ap)||!this.phone.equals(phone)||this.generation!=generation||closed||failed)return;
        synchronized(wake){wake.notifyAll();}
    }
    public void close(){closed=true;synchronized(wake){wake.notifyAll();}}
}
