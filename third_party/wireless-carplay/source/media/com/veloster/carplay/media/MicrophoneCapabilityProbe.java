/* SPDX-License-Identifier: GPL-3.0-only. Owned AudioRecord initialization/release capability discovery; never recording. */
package com.veloster.carplay.media;
import android.content.Context;
import com.veloster.carplay.control.ReceiverWorkers;
import com.veloster.carplay.tunnel.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Construct without side effects, register this worker, then start off main.
 * No input capability is published until actual prepare AND release complete.
 * Scope retirement cancels discovery but cannot steal its pending cleanup. */
public final class MicrophoneCapabilityProbe implements ReceiverWorkers.Worker {
    public interface Factory {AudioInputStream.PreparedSource create(Api17MicrophoneInput.Purpose purpose,int rate)throws IOException;}
    public static final class Result {
        private final Context context;private final MicrophoneCaptureLease.ScopeLease owner;private final long speech,call;
        private final Map<String,String> observations;
        private Result(Context context,MicrophoneCaptureLease.ScopeLease owner,long speech,long call,Map<String,String> observations){this.context=context;this.owner=owner;this.speech=speech;this.call=call;this.observations=Collections.unmodifiableMap(new LinkedHashMap<String,String>(observations));}
        public void checkCurrent()throws IOException{owner.checkCurrent();}
        public long formats(Api17MicrophoneInput.Purpose purpose)throws IOException{if(purpose==null)throw new NullPointerException();checkCurrent();return purpose==Api17MicrophoneInput.Purpose.TELEPHONY?call:speech;}
        public boolean matches(Context supplied){if(supplied==null)return false;Context app=supplied.getApplicationContext();return context==(app==null?supplied:app);}
        public Map<String,String> observations(){return observations;}
    }
    private final Context context;private final MicrophoneCaptureLease.ScopeLease owner;private final MicrophoneCaptureLease captures;private final Factory factory;
    private final Object lock=new Object();private final CountDownLatch stopped=new CountDownLatch(1);
    private boolean started,finished;private volatile boolean closed;private volatile Thread worker;private volatile IOException failure,cleanupFailure;private volatile Result result;
    public MicrophoneCapabilityProbe(Context supplied,MicrophoneCaptureLease.ScopeLease owner){
        if(supplied==null||owner==null)throw new NullPointerException();Context app=supplied.getApplicationContext();context=app==null?supplied:app;this.owner=owner;captures=MicrophoneCaptureLease.process();
        factory=new Factory(){public AudioInputStream.PreparedSource create(Api17MicrophoneInput.Purpose purpose,int rate)throws IOException{return new Api17MicrophoneInput(context,rate,rate/50,purpose);}};
    }
    /** Deterministic factory seam; does not authorize a production Android factory Result. */
    MicrophoneCapabilityProbe(MicrophoneCaptureLease.ScopeLease owner,MicrophoneCaptureLease captures,Factory factory){if(owner==null||captures==null||factory==null)throw new NullPointerException();context=null;this.owner=owner;this.captures=captures;this.factory=factory;}
    private void check()throws IOException{if(closed)throw new IOException("Microphone capability discovery cancelled");owner.checkCurrent();}
    public void start(){
        synchronized(lock){if(started)throw new IllegalStateException("Probe already started");started=true;if(closed){finished=true;stopped.countDown();return;}
            try{worker=new Thread(new Runnable(){public void run(){discover();}},"vn-microphone-capabilities");worker.setDaemon(true);worker.start();}
            catch(RuntimeException e){worker=null;failure=new IOException("Microphone capability worker unavailable",e);finished=true;stopped.countDown();}}
    }
    private void discover(){
        MicrophoneCaptureLease.Reservation reservation=null;boolean interrupted=false;Map<String,String> observations=new LinkedHashMap<String,String>();long speech=0,call=0;
        try{
            while(reservation==null){check();reservation=captures.acquire(owner,this);if(reservation==null)synchronized(lock){if(!closed)lock.wait(25);}}
            for(Api17MicrophoneInput.Purpose purpose:Api17MicrophoneInput.Purpose.values())for(int rate:new int[]{8000,16000}){
                check();AudioInputStream.PreparedSource source=null;boolean initialized=false;String observation="unavailable";
                try{source=factory.create(purpose,rate);if(source==null)throw new IOException("No capture source");check();source.prepare();check();initialized=true;observation="initialized-source-verified";}
                catch(IOException e){observation="unavailable:"+e.toString();}
                catch(RuntimeException e){observation="unavailable:"+e.toString();}
                catch(LinkageError e){observation="unavailable:"+e.toString();}
                finally{if(source!=null)for(;;){
                    try{source.close();break;}
                    catch(IOException e){if(cleanupFailure==null)cleanupFailure=e;initialized=false;}
                    catch(RuntimeException e){if(cleanupFailure==null)cleanupFailure=new IOException("Probe source release failed",e);initialized=false;}
                    catch(LinkageError e){if(cleanupFailure==null)cleanupFailure=new IOException("Probe release API failed",e);initialized=false;}
                    synchronized(lock){try{lock.wait(250);}catch(InterruptedException e){interrupted=true;}}
                }}
                check();if(cleanupFailure!=null)throw cleanupFailure;
                if(initialized){if(purpose==Api17MicrophoneInput.Purpose.TELEPHONY)call|=rate==8000?4:16;else speech|=rate==8000?4:16;observation+=";released";}
                observations.put(purpose+":"+rate,observation);
            }
            check();result=new Result(context,owner,speech,call,observations);
        }catch(IOException e){failure=e;}
         catch(InterruptedException e){interrupted=true;failure=new IOException("Microphone capability discovery interrupted",e);}
         catch(RuntimeException e){failure=new IOException("Microphone capability discovery failed",e);}
         catch(LinkageError e){failure=new IOException("Microphone capability API unavailable",e);}
        finally{
            if(reservation!=null)try{reservation.released(owner,this);}catch(IOException e){failure=e;result=null;}
            if(failure!=null)result=null;synchronized(lock){finished=true;lock.notifyAll();}stopped.countDown();if(interrupted)Thread.currentThread().interrupt();
        }
    }
    public Result result()throws IOException{synchronized(lock){if(!finished)throw new IOException("Microphone capability discovery pending");if(failure!=null)throw failure;if(result==null)throw new IOException("Microphone capability discovery cancelled");}result.checkCurrent();return result;}
    public IOException failure(){return failure;}
    public IOException cleanupFailure(){return cleanupFailure;}
    public void close(){Thread value;synchronized(lock){closed=true;value=worker;if(!started){finished=true;stopped.countDown();}lock.notifyAll();}if(value!=null)value.interrupt();}
    public boolean awaitStopped(long millis)throws InterruptedException{if(millis<0||millis>86400000L)throw new IllegalArgumentException("Wait bound");return stopped.await(millis,TimeUnit.MILLISECONDS);}
}
