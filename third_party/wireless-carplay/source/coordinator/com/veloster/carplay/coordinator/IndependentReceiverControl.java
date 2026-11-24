/* SPDX-License-Identifier: GPL-3.0-only. Attempt-scoped independent ReceiverRuntime adapter. */
package com.veloster.carplay.coordinator;
import com.veloster.carplay.Iap2WirelessController;
import com.veloster.carplay.control.ReceiverScope;
import com.veloster.carplay.control.RuntimeControlHost;
import com.veloster.carplay.discovery.ReceiverRuntime;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** One fresh attempt only; never construct this with a recovered old owner token
 * to invent a stop receipt. The app must route journal recovery to its original
 * backend and independently establish process/resource absence if it died.
 * BEGIN may block in the factory. close is a cancellation request; STOP returns
 * released only after allocation and all ReceiverRuntime workers have drained. */
public final class IndependentReceiverControl implements ReceiverControl,Closeable {
    public interface Generation {void checkCurrent(ReceiverScope scope)throws IOException;}
    public interface Factory {
        /** Create a fresh ReceiverRuntime using this exact scope and tracker as
         * its runtimeFactory. On exception, close/drain all partial resources. */
        ReceiverRuntime open(ReceiverScope scope,RuntimeSessionTracker tracker)throws IOException,InterruptedException;
    }
    public interface Engine {
        ReceiverRuntime.State state();
        Iap2WirelessController.ReceiverIdentity identity()throws IOException;
        boolean sessionStarted()throws IOException;
        void close();boolean awaitStopped(long millis)throws InterruptedException;
    }
    public interface Engines {Engine open()throws IOException,InterruptedException;}
    private final ReceiverScope scope;private final Generation generation;private final Engines factory;private final long stopWaitMillis;
    private final Object signal=new Object();private Engine engine;
    private boolean attempted,allocating,stopping,terminal;private IOException failure;
    public IndependentReceiverControl(final ReceiverScope scope,Generation generation,final Factory factory,
            final RuntimeControlHost.Factory runtimeFactory,long stopWaitMillis){
        this(scope,generation,engines(scope,factory,runtimeFactory),stopWaitMillis);
    }
    private static Engines engines(final ReceiverScope scope,final Factory factory,final RuntimeControlHost.Factory runtimeFactory){
        if(scope==null||factory==null||runtimeFactory==null)throw new NullPointerException();
        return new Engines(){public Engine open()throws IOException,InterruptedException{
            final RuntimeSessionTracker tracker=new RuntimeSessionTracker(scope,runtimeFactory);boolean success=false;
            try{
                final ReceiverRuntime runtime=factory.open(scope,tracker);if(runtime==null)throw new IOException("Independent receiver factory returned no runtime");
                Engine result=new Engine(){
                    public ReceiverRuntime.State state(){return runtime.state();}
                    public Iap2WirelessController.ReceiverIdentity identity()throws IOException{return runtime.receiverIdentity();}
                    public boolean sessionStarted()throws IOException{return tracker.sessionStarted(scope);}
                    public void close(){tracker.close();runtime.close();}
                    public boolean awaitStopped(long millis)throws InterruptedException{
                        if(!runtime.awaitStopped(millis))return false;
                        if(tracker.resourcesReleased())return true;
                        try{return tracker.retryCleanup();}catch(IOException pending){return false;}
                    }
                };success=true;return result;
            }finally{if(!success)tracker.close();}
        }};
    }
    public IndependentReceiverControl(ReceiverScope scope,Generation generation,Engines factory,long stopWaitMillis){
        if(scope==null||generation==null||factory==null)throw new NullPointerException();
        if(stopWaitMillis<0||stopWaitMillis>10000)throw new IllegalArgumentException("Receiver stop wait");
        this.scope=scope;this.generation=generation;this.factory=factory;this.stopWaitMillis=stopWaitMillis;
    }
    public ReceiverScope scope(){return scope;}
    /** Runtime session evidence, not publication or rendered-media proof. */
    public boolean sessionStarted()throws IOException{return request(STATUS,scope.owner).nativeStarted();}
    private void current()throws IOException{
        try{generation.checkCurrent(scope);}catch(IOException revoked){close();throw revoked;}
    }
    public Reply request(int operation,String token)throws IOException{
        if(operation<BEGIN||operation>STATUS)throw new IOException("Unknown receiver operation");
        if(!scope.owner.equals(token)){
            if(operation==STOP)throw new IOException("Unregistered receiver owner; no cleanup proof");
            return new Reply(3,0);
        }
        if(operation==STOP){close();try{return awaitStopped(stopWaitMillis)?new Reply(0,0):new Reply(0,1);}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Receiver cleanup interrupted",e);}}
        current();if(operation==BEGIN)begin();return status();
    }
    private void begin()throws IOException{
        synchronized(signal){
            if(stopping||terminal)throw new IOException("Independent receiver attempt ended");
            if(attempted)return;attempted=true;allocating=true;
        }
        Engine created=null;IOException error=null;
        try{created=factory.open();if(created==null)throw new IOException("Independent receiver unavailable");current();}
        catch(InterruptedException e){Thread.currentThread().interrupt();error=new IOException("Receiver allocation interrupted",e);}
        catch(IOException e){error=e;}
        catch(RuntimeException e){error=new IOException("Receiver allocation failed",e);}
        catch(LinkageError e){error=new IOException("Receiver allocation API unavailable",e);}
        boolean cancel;
        synchronized(signal){
            engine=created;allocating=false;if(error!=null){failure=error;stopping=true;}
            if(created==null){terminal=true;stopping=true;}cancel=stopping;signal.notifyAll();
        }
        if(cancel&&created!=null)cancel(created);
        if(error!=null)throw error;
    }
    private void cancel(Engine value){
        try{value.close();}catch(RuntimeException e){synchronized(signal){if(failure==null)failure=new IOException("Receiver cancellation failed",e);}}
        catch(LinkageError e){synchronized(signal){if(failure==null)failure=new IOException("Receiver cancellation API unavailable",e);}}
    }
    private Reply status()throws IOException{
        Engine value;boolean stop,finished,wasAttempted;IOException failed;
        synchronized(signal){value=engine;stop=stopping;finished=terminal;wasAttempted=attempted;failed=failure;}
        if(finished)return new Reply(failed==null?0:4,0);
        if(value==null)return new Reply(failed==null?0:4,wasAttempted?1:0);
        if(stop)return new Reply(failed==null?0:4,1);
        ReceiverRuntime.State state=value.state();int flags=1;
        if(state==ReceiverRuntime.State.READY){flags|=4;if(value.sessionStarted())flags|=8;}
        synchronized(signal){if(engine!=value||stopping)return new Reply(failure==null?0:4,terminal?0:1);}
        return new Reply(state==ReceiverRuntime.State.FAILED||state==ReceiverRuntime.State.STOPPING||state==ReceiverRuntime.State.STOPPED?4:0,flags);
    }
    /** Null only while this active attempt is allocating/publishing. READY returns
     * actual listener metadata; failed/closed attempts throw instead of retrying. */
    public Iap2WirelessController.ReceiverIdentity receiverIdentity()throws IOException{
        current();Engine value;synchronized(signal){if(stopping||terminal||!attempted)throw new IOException("Independent receiver not ready");value=engine;if(value==null&&allocating)return null;}
        if(value==null)throw new IOException("Independent receiver unavailable");
        if(value.state()==ReceiverRuntime.State.STARTING){current();synchronized(signal){if(engine!=value||stopping||terminal)throw new IOException("Independent receiver closed");}return null;}
        Iap2WirelessController.ReceiverIdentity identity=value.identity();
        if(identity==null||!scope.apSession.equals(identity.apSession))throw new IOException("Receiver metadata scope mismatch");
        current();synchronized(signal){if(engine!=value||stopping||terminal)throw new IOException("Independent receiver closed");}return identity;
    }
    public void close(){
        Engine value;synchronized(signal){stopping=true;value=engine;if(!allocating&&value==null)terminal=true;signal.notifyAll();}
        if(value!=null)cancel(value);
    }
    public boolean awaitStopped(long millis)throws InterruptedException{
        if(millis<0)throw new IllegalArgumentException("Receiver wait");long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);Engine value;
        synchronized(signal){
            if(!stopping)return false;
            while(allocating){long left=deadline-System.nanoTime();if(left<=0)return false;TimeUnit.NANOSECONDS.timedWait(signal,left);}
            if(terminal)return true;value=engine;
        }
        long left=Math.max(0,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()));
        if(value==null||!value.awaitStopped(left))return false;
        synchronized(signal){if(engine==value&&!allocating){terminal=true;engine=null;signal.notifyAll();return true;}return terminal;}
    }
}
