/* SPDX-License-Identifier: GPL-3.0-only. Owned worker drain, including in-flight allocation. */
package com.veloster.carplay.control;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Reserve before allocating a worker. Closing rejects new reservations, cancels
 * attached workers, and cancels late attachments. A pending allocation is never
 * reported as stopped. Worker methods must not join during close or callback
 * into this group from awaitStopped(0). */
public final class ReceiverWorkers implements Closeable {
    public interface Worker {void close();boolean awaitStopped(long millis)throws InterruptedException;}
    private final List<Slot> slots=new ArrayList<Slot>();private final int limit;
    private boolean closed;private volatile IOException failure;
    public ReceiverWorkers(int limit){if(limit<1||limit>256)throw new IllegalArgumentException("Worker bound");this.limit=limit;}
    public synchronized Slot reserve()throws IOException{
        if(closed)throw new IOException("Receiver workers closing");
        try{for(Iterator<Slot> it=slots.iterator();it.hasNext();){Slot slot=it.next();if(slot.worker!=null&&slot.worker.awaitStopped(0))it.remove();}}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Worker reservation interrupted",e);}
        if(slots.size()>=limit)throw new IOException("Receiver workers still draining");
        Slot slot=new Slot();slots.add(slot);return slot;
    }
    public final class Slot {
        private Worker worker;private boolean resolved;
        private Slot(){}
        public void attach(Worker value){
            if(value==null)throw new NullPointerException();boolean stop;
            synchronized(ReceiverWorkers.this){if(resolved)throw new IllegalStateException("Worker slot resolved");resolved=true;worker=value;stop=closed;ReceiverWorkers.this.notifyAll();}
            if(stop)cancel(value);
        }
        /** Only when construction failed without transferring a worker. */
        public void failed(){synchronized(ReceiverWorkers.this){if(resolved)throw new IllegalStateException("Worker slot resolved");resolved=true;slots.remove(this);ReceiverWorkers.this.notifyAll();}}
    }
    private void cancel(Worker worker){
        try{worker.close();}
        catch(RuntimeException e){failure=new IOException("Receiver worker cancellation failed",e);}
        catch(LinkageError e){failure=new IOException("Receiver worker cancellation API unavailable",e);}
    }
    public void close(){
        List<Worker> owned=new ArrayList<Worker>();
        synchronized(this){closed=true;for(Slot slot:slots)if(slot.worker!=null)owned.add(slot.worker);notifyAll();}
        for(Worker worker:owned)cancel(worker);
    }
    public IOException failure(){return failure;}
    public boolean awaitStopped(long millis)throws InterruptedException{
        if(millis<0)throw new IllegalArgumentException("Wait duration");long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        while(true){
            Worker worker;
            synchronized(this){
                if(!closed)return false;
                if(slots.isEmpty())return true;
                Slot slot=slots.get(0);worker=slot.worker;
                if(worker==null){long left=deadline-System.nanoTime();if(left<=0)return false;TimeUnit.NANOSECONDS.timedWait(this,left);continue;}
            }
            long left=Math.max(0,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()));
            if(!worker.awaitStopped(left))return false;
            synchronized(this){for(Iterator<Slot> it=slots.iterator();it.hasNext();)if(it.next().worker==worker){it.remove();break;}}
        }
    }
}
