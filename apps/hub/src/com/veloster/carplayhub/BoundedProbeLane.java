package com.veloster.carplayhub;

/** App-owned lane for one optional, read-only probe at a time. Pure Java, no Android.
 *
 * At most one worker exists for the lane. Nothing is queued and nothing is cached: a request
 * made while a worker is still running is BUSY, and a result that arrives after its caller
 * stopped waiting is dropped. A blocked worker cannot be force-stopped from Java, so its slot
 * stays occupied until it actually returns; {@link #workerActive()} reports that truthfully.
 * The caller waits at most the configured bound, rechecks {@link Current} while waiting and once
 * more before returning a value, and is woken promptly by {@link #close()}. */
public final class BoundedProbeLane {
    public interface Work<T> {T run()throws Exception;}
    public interface Current {boolean current();}
    public enum Outcome { COMPLETED, FAILED, BUSY, TIMEOUT, WITHDRAWN, INTERRUPTED, CLOSED, UNAVAILABLE }
    public static final class Result<T> {
        public final Outcome outcome;private final T value;
        Result(Outcome outcome,T value){this.outcome=outcome;this.value=value;}
        /** Non-null only for COMPLETED. */
        public T value(){return outcome==Outcome.COMPLETED?value:null;}
    }
    private static final long RECHECK_MILLIS=25;
    private final Object lock=new Object();
    private final String name;private final long boundMillis;
    /** Guarded by lock. */
    private Slot active;private boolean closed;
    private static final class Slot {boolean done,failed;Object value;}
    public BoundedProbeLane(String name,long boundMillis){
        if(name==null||boundMillis<1||boundMillis>5000)throw new IllegalArgumentException("Probe lane bound");
        this.name=name;this.boundMillis=boundMillis;
    }
    /** True while a started worker has not yet returned, including after close or abandonment. */
    public boolean workerActive(){synchronized(lock){return active!=null;}}
    public boolean closed(){synchronized(lock){return closed;}}
    /** Stops admitting work and wakes waiting callers. Does not claim a blocked worker drained. */
    public void close(){synchronized(lock){closed=true;lock.notifyAll();}}
    @SuppressWarnings("unchecked")
    public <T> Result<T> run(final Work<T> work,Current current){
        if(work==null||current==null)throw new NullPointerException();
        final long deadline=System.nanoTime()+boundMillis*1000000L;
        if(!safeCurrent(current))return new Result<T>(Outcome.WITHDRAWN,null);
        final Slot slot=new Slot();
        synchronized(lock){
            if(closed)return new Result<T>(Outcome.CLOSED,null);
            if(active!=null)return new Result<T>(Outcome.BUSY,null);
            active=slot;
        }
        Thread worker=new Thread(new Runnable(){public void run(){
            Object value=null;boolean failed=true;
            try{value=work.run();failed=false;}
            catch(Exception error){}catch(LinkageError error){}
            finally{synchronized(lock){slot.value=failed?null:value;slot.failed=failed;slot.done=true;if(active==slot)active=null;lock.notifyAll();}}
        }},name);
        worker.setDaemon(true);
        try{worker.start();}
        catch(RuntimeException error){release(slot);return new Result<T>(Outcome.UNAVAILABLE,null);}
        catch(LinkageError error){release(slot);return new Result<T>(Outcome.UNAVAILABLE,null);}
        while(true){
            boolean done,failed,stop;Object value;
            synchronized(lock){
                if(!slot.done&&!closed){
                    long left=(deadline-System.nanoTime())/1000000L;
                    if(left>0){
                        try{lock.wait(Math.min(left,RECHECK_MILLIS));}
                        catch(InterruptedException interrupted){Thread.currentThread().interrupt();return new Result<T>(Outcome.INTERRUPTED,null);}
                    }
                }
                done=slot.done;failed=slot.failed;value=slot.value;stop=closed;
            }
            if(stop)return new Result<T>(Outcome.CLOSED,null);
            if(Thread.currentThread().isInterrupted())return new Result<T>(Outcome.INTERRUPTED,null);
            if(!safeCurrent(current))return new Result<T>(Outcome.WITHDRAWN,null);
            if(System.nanoTime()-deadline>=0)return new Result<T>(Outcome.TIMEOUT,null);
            if(done){
                if(failed||value==null)return new Result<T>(Outcome.FAILED,null);
                synchronized(lock){if(closed)return new Result<T>(Outcome.CLOSED,null);}
                if(!safeCurrent(current))return new Result<T>(Outcome.WITHDRAWN,null);
                if(System.nanoTime()-deadline>=0)return new Result<T>(Outcome.TIMEOUT,null);
                return new Result<T>(Outcome.COMPLETED,(T)value);
            }
            if(System.nanoTime()-deadline>=0)return new Result<T>(Outcome.TIMEOUT,null);
        }
    }
    private void release(Slot slot){synchronized(lock){if(active==slot)active=null;lock.notifyAll();}}
    private static boolean safeCurrent(Current current){
        try{return current.current();}catch(RuntimeException error){return false;}catch(LinkageError error){return false;}
    }
}
