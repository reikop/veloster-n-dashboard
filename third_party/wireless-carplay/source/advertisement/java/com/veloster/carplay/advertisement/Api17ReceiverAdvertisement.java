/* SPDX-License-Identifier: GPL-3.0-only. Worker-only Binder I/O and retained partial cleanup. */
package com.veloster.carplay.advertisement;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import com.veloster.carplay.control.ReceiverScope;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/** Construction performs no binding, package lookup, native load or radio operation.
 * start/awaitStopped are blocking worker APIs. close only requests retirement. */
public final class Api17ReceiverAdvertisement implements ReceiverAdvertisement {
    private static final class ProcessOwner {static final IBinder TOKEN=new Binder();static final AtomicLong SEQUENCE=new AtomicLong();}
    public interface Current {void checkCurrent()throws IOException;}
    private final Object lock=new Object();private final Context context;private final EirProtocol.Scope scope;private final Current current;
    private boolean started,closing,complete,drained,bound,grantAttempted,workerEnded;private Thread worker;private IBinder service,originalService,token;private IOException failure;private EirProtocol.Snapshot latest;private long observedAt,deadline,sequence,retirementRevision;
    private final IBinder.DeathRecipient death=new IBinder.DeathRecipient(){public void binderDied(){synchronized(lock){service=null;record(new IOException("EIR carrier process disconnected"));closing=true;lock.notifyAll();}}};
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){synchronized(lock){
            if(drained||complete)return;
            if(originalService!=null){if(originalService!=binder){record(new IOException("EIR carrier instance changed; cleanup unconfirmed"));closing=true;lock.notifyAll();}return;}
            if(!EirProtocol.CARRIER_PACKAGE.equals(name.getPackageName())||!EirProtocol.SERVICE.equals(name.getClassName())||binder==null){record(new IOException("Foreign EIR carrier component"));closing=true;}
            else{service=binder;originalService=binder;try{binder.linkToDeath(death,0);}catch(Exception e){service=null;record(new IOException("EIR carrier already stopped",e));closing=true;}}
            lock.notifyAll();
        }}
        public void onServiceDisconnected(ComponentName name){death.binderDied();}
    };
    public Api17ReceiverAdvertisement(Context context,ReceiverScope scope,Current current){
        if(context==null||scope==null||current==null)throw new NullPointerException();this.context=context;this.current=current;
        this.scope=new EirProtocol.Scope(scope.owner,scope.apSession,scope.generation,scope.selectedBluetooth);
    }
    /** Separate first-registration grant: no ReceiverScope, selected phone or old consent is accepted. */
    public Api17ReceiverAdvertisement(Context context,EirProtocol.EnrollmentScope scope,Current current){
        if(context==null||scope==null||current==null)throw new NullPointerException();this.context=context;this.current=current;this.scope=scope;
    }
    private static void workerRequired()throws IOException{if(Looper.myLooper()!=null&&Looper.myLooper()==Looper.getMainLooper())throw new IOException("EIR wait requires a worker");}
    private void record(IOException e){if(failure==null)failure=e;}
    public void start(long timeoutMillis)throws IOException,InterruptedException{
        workerRequired();if(timeoutMillis<1||timeoutMillis>30000)throw new IllegalArgumentException("EIR start timeout");current.checkCurrent();
        synchronized(lock){
            if(started||closing)throw new IOException("EIR client already used");started=true;deadline=SystemClock.elapsedRealtime()+timeoutMillis;
            try{worker=new Thread(new Runnable(){public void run(){work();}},"CarPlay-EIR-client");worker.setDaemon(true);worker.start();}
            catch(RuntimeException e){complete=true;workerEnded=true;closing=true;throw new IOException("EIR client worker unavailable",e);}catch(LinkageError e){complete=true;workerEnded=true;closing=true;throw new IOException("EIR client worker unavailable",e);}
            try{
                while(!closing&&failure==null&&(latest==null||!latest.ready())){long remaining=deadline-SystemClock.elapsedRealtime();if(remaining<=0){record(new IOException("EIR local registration deadline"));closing=true;lock.notifyAll();break;}lock.wait(remaining);}
            }catch(InterruptedException e){closing=true;lock.notifyAll();throw e;}
            if(failure!=null)throw failure;if(closing||latest==null||!latest.ready())throw new IOException("EIR local registration cancelled");
        }
        checkCurrent();
    }
    public void checkCurrent()throws IOException{
        current.checkCurrent();synchronized(lock){if(closing||failure!=null||service==null||latest==null||!latest.ready()||SystemClock.elapsedRealtime()-observedAt>1000)throw new IOException("EIR local lease is not current",failure);}
    }
    public void close(){synchronized(lock){closing=true;retirementRevision++;if(!started){drained=true;complete=true;}
        else if(workerEnded&&!complete&&(drained||service!=null))try{workerEnded=false;worker=new Thread(new Runnable(){public void run(){retirementRetry();}},"CarPlay-EIR-retire");worker.setDaemon(true);worker.start();}catch(RuntimeException e){workerEnded=true;record(new IOException("EIR retirement worker unavailable",e));}catch(LinkageError e){workerEnded=true;record(new IOException("EIR retirement worker unavailable",e));}
        lock.notifyAll();}}
    public boolean awaitStopped(long millis)throws InterruptedException{
        if(millis<0)throw new IllegalArgumentException("EIR stop timeout");long end=SystemClock.elapsedRealtime()+millis;
        synchronized(lock){while(!complete){long left=end-SystemClock.elapsedRealtime();if(left<=0)return false;lock.wait(left);}return true;}
    }
    private void work(){
        try{
            current.checkCurrent();EirIdentity.check(context.getPackageManager(),EirProtocol.CARRIER_PACKAGE,EirProtocol.CARRIER_UID,EirProtocol.MIN_CARRIER_VERSION,false);
            synchronized(lock){if(closing){complete=true;return;}token=ProcessOwner.TOKEN;sequence=ProcessOwner.SEQUENCE.incrementAndGet();if(sequence<1)throw new IOException("EIR process request sequence exhausted");}
            Intent intent=new Intent(EirProtocol.DESCRIPTOR).setComponent(new ComponentName(EirProtocol.CARRIER_PACKAGE,EirProtocol.SERVICE));
            synchronized(lock){bound=true;} // Android may retain its dispatcher even on refusal/throw.
            boolean accepted=context.bindService(intent,connection,Context.BIND_AUTO_CREATE);
            synchronized(lock){if(!accepted)throw new IOException("EIR carrier binding refused");
                while(service==null&&!closing&&failure==null){long left=deadline-SystemClock.elapsedRealtime();if(left<=0)throw new IOException("EIR carrier binding deadline");lock.wait(left);}
                if(closing||failure!=null)return;
            }
            current.checkCurrent();IBinder remote; synchronized(lock){if(closing)return;remote=service;grantAttempted=true;}
            try{observe(call(remote,EirProtocol.START));}catch(IOException uncertain){synchronized(lock){record(uncertain);closing=true;}}
            boolean stopSent=false;long stoppedRevision=-1;
            for(;;){
                synchronized(lock){remote=service;if(remote==null)throw new IOException("EIR carrier unavailable during cleanup");}
                boolean retire;synchronized(lock){retire=closing;}
                if(!retire)try{current.checkCurrent();}catch(IOException withdrawn){synchronized(lock){record(withdrawn);closing=true;}retire=true;}
                long revision;synchronized(lock){revision=retirementRevision;}
                if(retire&&(!stopSent||stoppedRevision!=revision)){observe(call(remote,EirProtocol.STOP));stopSent=true;stoppedRevision=revision;}
                EirProtocol.Snapshot snapshot=call(remote,EirProtocol.STATUS);observe(snapshot);
                if(retire&&snapshot.stopped()){synchronized(lock){drained=true;}return;}
                if(snapshot.state==EirProtocol.REJECTED||snapshot.state==EirProtocol.FAILED||snapshot.state==EirProtocol.CLEANUP_REQUIRED||!retire&&snapshot.stopped()){synchronized(lock){record(new IOException(snapshot.failure.length()==0?"EIR local publication unavailable":snapshot.failure));closing=true;}}
                synchronized(lock){if(!closing&&SystemClock.elapsedRealtime()>=deadline&&(latest==null||!latest.ready())){record(new IOException("EIR local registration deadline"));closing=true;}lock.wait(50);}
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();synchronized(lock){record(new IOException("EIR client worker interrupted",e));closing=true;}}
        catch(IOException e){synchronized(lock){record(e);closing=true;}}
        catch(RuntimeException e){synchronized(lock){record(new IOException("EIR client failed",e));closing=true;}}
        catch(LinkageError e){synchronized(lock){record(new IOException("EIR client unavailable",e));closing=true;}}
        finally{
            synchronized(lock){if(!grantAttempted)drained=true;}releaseIfStopped();synchronized(lock){workerEnded=true;lock.notifyAll();}
        }
    }
    private void retirementRetry(){
        try{IBinder remote;long stoppedRevision=-1;for(;;){long revision;synchronized(lock){if(drained)return;remote=service;revision=retirementRevision;}if(remote==null)return;
                if(revision!=stoppedRevision){observe(call(remote,EirProtocol.STOP));stoppedRevision=revision;}
                EirProtocol.Snapshot value=call(remote,EirProtocol.STATUS);observe(value);if(value.stopped()){synchronized(lock){drained=true;}return;}synchronized(lock){lock.wait(50);}}
        }catch(InterruptedException e){Thread.currentThread().interrupt();}catch(IOException e){synchronized(lock){record(e);}}catch(RuntimeException e){synchronized(lock){record(new IOException("EIR retirement retry failed",e));}}
        finally{releaseIfStopped();synchronized(lock){workerEnded=true;lock.notifyAll();}}
            }
    private void releaseIfStopped(){
        IBinder remote;boolean unbind;synchronized(lock){if(!drained)return;remote=service;service=null;unbind=bound;}
        if(remote!=null)try{remote.unlinkToDeath(death,0);}catch(RuntimeException ignored){}
        if(unbind)try{context.unbindService(connection);}catch(IllegalArgumentException alreadyGone){}
        catch(RuntimeException e){synchronized(lock){record(new IOException("EIR binding cleanup pending",e));}return;}
        catch(LinkageError e){synchronized(lock){record(new IOException("EIR binding cleanup unavailable",e));}return;}
        synchronized(lock){bound=false;complete=true;lock.notifyAll();}
    }
    private void observe(EirProtocol.Snapshot value)throws IOException{
        synchronized(lock){if(!scope.equals(value.scope)||value.requestSequence!=sequence)throw new IOException("Foreign EIR lease status");if(latest!=null&&latest.carrierGeneration>0&&value.carrierGeneration!=latest.carrierGeneration&&!(value.carrierGeneration==0&&value.stopped()))throw new IOException("EIR carrier generation changed");latest=value;observedAt=SystemClock.elapsedRealtime();lock.notifyAll();}
    }
    private EirProtocol.Snapshot call(IBinder remote,int command)throws IOException{return EirWire.call(remote,command,token,sequence,scope);}
}
