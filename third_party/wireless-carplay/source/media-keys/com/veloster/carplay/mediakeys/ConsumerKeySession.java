/* SPDX-License-Identifier: GPL-3.0-only. Session-owned Consumer HID sender. */
package com.veloster.carplay.mediakeys;

import com.veloster.carplay.input.ConsumerHid;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** One authenticated session and one physical source. Callback methods never wait for I/O. */
public final class ConsumerKeySession implements Closeable, Runnable {
    public enum Route { CLAIMED, QUARANTINED, STALE }
    /** Nonblocking, nonthrowing check of exact VerifiedSession, declaration, and source generation. */
    public interface Current {boolean current();}
    /** Return true only after peer ACK; false only when no report was written.
     * The implementation must recheck Current immediately before its encrypted write. */
    public interface ConsumerChannel extends Closeable {
        boolean send(byte[] report,long timeoutMillis,Current current)throws IOException;
        void close();
    }
    public interface Failure {void failed(IOException error);}

    private final Object lock=new Object();
    private final Object source;
    private final Current current;
    private final ConsumerChannel channel;
    private final Failure failureCallback;
    private final long timeoutMillis;
    private final Thread worker;
    private int activeUsage;
    private boolean pressAcked,releaseRequested,closing,stopped,retirementRequired;
    private IOException failure;

    public ConsumerKeySession(Object source,Current current,ConsumerChannel channel,Failure failure,long timeoutMillis)throws IOException{
        if(source==null||current==null||channel==null||failure==null)throw new NullPointerException();
        if(timeoutMillis<1||timeoutMillis>30000)throw new IllegalArgumentException("Consumer ACK timeout");
        this.source=source;this.current=current;this.channel=channel;this.failureCallback=failure;this.timeoutMillis=timeoutMillis;
        worker=new Thread(this,"vn-consumer-hid-owner");worker.setDaemon(true);
        try{worker.start();}catch(RuntimeException error){channel.close();throw new IOException("Consumer worker unavailable",error);}
    }
    /** The caller must latch CLAIMED for its matching physical UP. */
    public Route down(Object supplied,int usage){
        if(usage!=ConsumerHid.NEXT&&usage!=ConsumerHid.PREVIOUS)throw new IllegalArgumentException("Consumer usage");
        synchronized(lock){
            if(supplied!=source)return Route.STALE;
            if(closing||failure!=null||activeUsage!=0)return Route.QUARANTINED;
            if(!current.current())return Route.STALE;
            activeUsage=usage;pressAcked=false;releaseRequested=false;lock.notifyAll();return Route.CLAIMED;
        }
    }
    public Route up(Object supplied,int usage){
        if(usage!=ConsumerHid.NEXT&&usage!=ConsumerHid.PREVIOUS)throw new IllegalArgumentException("Consumer usage");
        synchronized(lock){
            if(supplied!=source)return Route.STALE;
            if(activeUsage!=usage||failure!=null)return Route.QUARANTINED;
            releaseRequested=true;lock.notifyAll();return Route.CLAIMED;
        }
    }
    /** Retirement queues the release of an ACKed press; a blocked ACK keeps this worker owned. */
    public void close(){synchronized(lock){closing=true;if(activeUsage!=0)releaseRequested=true;lock.notifyAll();}}
    public boolean awaitStopped(long millis)throws InterruptedException{
        if(millis<0)throw new IllegalArgumentException("Wait bound");
        long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        synchronized(lock){while(!stopped){long remaining=end-System.nanoTime();if(remaining<=0)return false;
            TimeUnit.NANOSECONDS.timedWait(lock,remaining);}return true;}
    }
    public boolean awaitIdle(long millis)throws InterruptedException{
        if(millis<0)throw new IllegalArgumentException("Wait bound");
        long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        synchronized(lock){while(activeUsage!=0&&!stopped){long remaining=end-System.nanoTime();if(remaining<=0)return false;
            TimeUnit.NANOSECONDS.timedWait(lock,remaining);}return activeUsage==0&&failure==null;}
    }
    /** False after an unknown send outcome until the caller has retired the exact session. */
    public boolean safeToReplace(){synchronized(lock){return stopped&&!retirementRequired;}}
    /** Immediate callback-side check; an unresolved press must never fall through to another route. */
    public boolean hasOutstanding(){synchronized(lock){return activeUsage!=0||retirementRequired||failure!=null;}}
    public boolean requiresSessionRetirement(){synchronized(lock){return retirementRequired;}}
    public IOException failure(){synchronized(lock){return failure;}}

    public void run(){
        IOException ending=null;
        try{for(;;){int usage;boolean release;
            synchronized(lock){
                while(activeUsage==0&&!closing)lock.wait();
                if(activeUsage==0)break;
                if(closing&&!pressAcked){activeUsage=0;lock.notifyAll();continue;}
                if(pressAcked&&!releaseRequested){lock.wait();continue;}
                usage=activeUsage;release=pressAcked;
            }
            if(!current.current())throw new SendFailure("Consumer owner retired",release);
            byte[] report;
            try{report=release?ConsumerHid.release():ConsumerHid.press(usage);}
            catch(IOException error){throw new SendFailure("Consumer declaration mismatch",release,error);}
            boolean ack;
            try{ack=channel.send(report,timeoutMillis,current);}
            catch(IOException error){throw new SendFailure("Consumer report outcome unknown",true,error);}
            if(!ack)throw new SendFailure("Consumer report refused before write",release);
            synchronized(lock){
                if(release){activeUsage=0;pressAcked=false;releaseRequested=false;lock.notifyAll();}
                else{pressAcked=true;lock.notifyAll();}
            }
        }}catch(InterruptedException error){Thread.currentThread().interrupt();ending=new IOException("Consumer worker interrupted",error);
            synchronized(lock){retirementRequired=activeUsage!=0;}}
        catch(SendFailure error){ending=error;synchronized(lock){retirementRequired=error.retirementRequired;}}
        catch(RuntimeException error){ending=new IOException("Consumer worker failed",error);
            synchronized(lock){retirementRequired=activeUsage!=0;}}
        catch(LinkageError error){ending=new IOException("Consumer channel unavailable",error);
            synchronized(lock){retirementRequired=activeUsage!=0;}}
        finally{
            synchronized(lock){closing=true;if(ending!=null)failure=ending;}
            try{channel.close();}catch(RuntimeException error){
                synchronized(lock){retirementRequired=true;if(failure==null)failure=new IOException("Consumer channel close failed",error);}
            }catch(LinkageError error){
                synchronized(lock){retirementRequired=true;if(failure==null)failure=new IOException("Consumer channel close unavailable",error);}
            }
            IOException reported;
            synchronized(lock){reported=failure;}
            try{if(reported!=null)failureCallback.failed(reported);}
            catch(RuntimeException ignored){}catch(LinkageError ignored){}
            finally{synchronized(lock){stopped=true;lock.notifyAll();}}
        }
    }
    private static final class SendFailure extends IOException {
        final boolean retirementRequired;
        SendFailure(String message,boolean required){super(message);retirementRequired=required;}
        SendFailure(String message,boolean required,IOException cause){super(message,cause);retirementRequired=required;}
    }
}
