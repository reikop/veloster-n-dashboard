/* SPDX-License-Identifier: GPL-3.0-only. One production control/discovery/media lifetime. */
package com.veloster.carplay.discovery;
import com.veloster.carplay.control.*;
import com.veloster.carplay.media.*;
import com.veloster.carplay.pairing.*;
import com.veloster.carplay.Iap2WirelessController;
import java.io.*;
import java.util.concurrent.*;

/** Owns the supplied fresh media adapters and all workers created for this AP/
 * owner generation. Caller owns PairingStore and the main-thread presentation.
 * No fixture identity, automatic approval or default OEM authentication is used.
 * close requests cancellation; awaitStopped includes pending allocations, UDP,
 * events, codecs, runtime authentication and discovery cleanup. */
public final class ReceiverRuntime implements Closeable {
    public enum State { STARTING, READY, STOPPING, STOPPED, FAILED }
    private final PairingStore store;private final ReceiverAdmission admission;
    private final ReceiverMetadata metadata;private final ScreenOutputs screens;private final AudioPresentation audio;
    private final ControlConnection.ApprovalUi approval;private final ReceiverDiscovery.MulticastLease multicast;
    private final int port;private final ReceiverWorkers media=new ReceiverWorkers(32);
    private final RuntimeControlHost runtime;private final IapSetupHandler handler;
    private final Object signal=new Object();private final CountDownLatch stopped=new CountDownLatch(1);
    private volatile PublishedReceiver published;private volatile boolean closing,userStop;private volatile IOException failure;
    private volatile State state=State.STARTING;
    public ReceiverRuntime(PairingStore store,PairVerify.Context context,PairVerify.OwnerLease owner,
        ControlConnection.ApprovalUi approval,ReceiverMetadata metadata,ScreenOutputs screens,AudioPresentation audio,
        RuntimeControlHost.Factory runtimeFactory,ReceiverDiscovery.MulticastLease multicast,int port)throws IOException{
        this(store,new ReceiverAdmission.Fixed(context,owner),approval,metadata,screens,audio,runtimeFactory,multicast,port);
    }
    public ReceiverRuntime(PairingStore store,PairVerify.Context context,PairVerify.OwnerLease owner,
        ControlConnection.ApprovalUi approval,ReceiverMetadata metadata,ScreenOutputs screens,AudioPresentation audio,
        RuntimeControlHost.Factory runtimeFactory,ReceiverDiscovery.MulticastLease multicast,int port,
        IapSetupHandler.InputSinkFactory inputFactory)throws IOException{
        this(store,new ReceiverAdmission.Fixed(context,owner),approval,metadata,screens,audio,runtimeFactory,multicast,port,inputFactory);
    }
    public ReceiverRuntime(PairingStore store,ReceiverAdmission admission,
        ControlConnection.ApprovalUi approval,ReceiverMetadata metadata,ScreenOutputs screens,AudioPresentation audio,
        RuntimeControlHost.Factory runtimeFactory,ReceiverDiscovery.MulticastLease multicast,int port)throws IOException{
        this(store,admission,approval,metadata,screens,audio,runtimeFactory,multicast,port,null);
    }
    public ReceiverRuntime(PairingStore store,ReceiverAdmission admission,
        ControlConnection.ApprovalUi approval,ReceiverMetadata metadata,ScreenOutputs screens,AudioPresentation audio,
        RuntimeControlHost.Factory runtimeFactory,ReceiverDiscovery.MulticastLease multicast,int port,
        IapSetupHandler.InputSinkFactory inputFactory)throws IOException{
        this(store,admission,approval,metadata,screens,audio,runtimeFactory,multicast,port,inputFactory,null);
    }
    public ReceiverRuntime(PairingStore store,ReceiverAdmission admission,
        ControlConnection.ApprovalUi approval,ReceiverMetadata metadata,ScreenOutputs screens,AudioPresentation audio,
        RuntimeControlHost.Factory runtimeFactory,ReceiverDiscovery.MulticastLease multicast,int port,
        IapSetupHandler.InputSinkFactory inputFactory,IapSetupHandler.MicrophoneFactory microphoneFactory)throws IOException{
        this(store,admission,approval,metadata,screens,audio,runtimeFactory,multicast,port,inputFactory,microphoneFactory,null);
    }
    public ReceiverRuntime(PairingStore store,ReceiverAdmission admission,
        ControlConnection.ApprovalUi approval,ReceiverMetadata metadata,ScreenOutputs screens,AudioPresentation audio,
        RuntimeControlHost.Factory runtimeFactory,ReceiverDiscovery.MulticastLease multicast,int port,
        IapSetupHandler.InputSinkFactory inputFactory,IapSetupHandler.MicrophoneFactory microphoneFactory,
        IapSetupHandler.SessionControlFactory sessionControlFactory)throws IOException{
        if(store==null||admission==null||approval==null||metadata==null||screens==null||audio==null||runtimeFactory==null)throw new NullPointerException();
        if(port<0||port>65535||metadata.audioCapabilities()==null)throw new IllegalArgumentException("Declared receiver outputs/port required");
        this.store=store;this.admission=admission;this.approval=approval;this.metadata=metadata;
        this.screens=screens;this.audio=audio;this.multicast=multicast;this.port=port;
        runtime=new RuntimeControlHost(runtimeFactory);
        VideoScreenFactory video=VideoScreenFactory.forReceiver(screens,metadata,media);
        AudioStreamFactory outputs=new AudioStreamFactory(audio,metadata.audioCapabilities(),media);
        handler=new IapSetupHandler(new ControlConnection.Handler(){
            public ControlMessage.Response info(ControlMessage.Request request)throws IOException{return ReceiverRuntime.this.metadata.info();}
            public ControlMessage.Response authenticated(ControlMessage.Request request,PairVerify.VerifiedSession session)throws IOException{return ControlMessage.Response.empty(501);}
            public void responseSent(ControlMessage.Request request,PairVerify.VerifiedSession session){}
        },runtime,video,metadata,audio,outputs,inputFactory,microphoneFactory,sessionControlFactory);
        Thread worker=new Thread(new Runnable(){public void run(){work();}},"vn-receiver-runtime");worker.setDaemon(true);
        try{worker.start();}catch(RuntimeException e){throw new IOException("Receiver runtime worker unavailable",e);}
    }
    private void failed(IOException error){synchronized(signal){if(failure==null)failure=error;closing=true;state=State.STOPPING;signal.notifyAll();}}
    private void cancel(Closeable value){
        if(value==null)return;
        try{value.close();}catch(IOException e){failed(e);}
        catch(RuntimeException e){failed(new IOException("Receiver cancellation failed",e));}
        catch(LinkageError e){failed(new IOException("Receiver cancellation API unavailable",e));}
    }
    private void work(){
        try{
            if(closing)return;
            ReceiverAdmission scoped=new ReceiverAdmission(){
                public ReceiverScope scope(){return admission.scope();}
                public void checkCurrent()throws IOException{if(closing)throw new IOException("Receiver generation closed");admission.checkCurrent();}
                public Peer admit(java.net.InetAddress remote)throws IOException{checkCurrent();return admission.admit(remote);}
            };
            published=new PublishedReceiver(store,scoped,approval,handler,metadata,port,multicast);
            while(!closing){
                PublishedReceiver current=published;
                if(current.discovery.awaitPublished(250)){
                    metadata.advertisement();
                    synchronized(signal){if(!closing){state=State.READY;signal.notifyAll();signal.wait(100);}}
                }else if(current.discovery.awaitStopped(0))throw new IOException("Receiver publication stopped");
            }
        }catch(IOException e){if(!closing)failed(e);}
        catch(InterruptedException e){if(!closing)failed(new IOException("Receiver runtime interrupted",e));}
        catch(RuntimeException e){failed(new IOException("Receiver runtime failed",e));}
        catch(LinkageError e){failed(new IOException("Receiver runtime API unavailable",e));}
        finally{
            synchronized(signal){closing=true;state=State.STOPPING;signal.notifyAll();}
            Thread.interrupted();cancel(published);handler.stopWorkers();cancel(runtime);cancel(media);cancel(audio);cancel(screens);
            boolean complete=false;
            while(!complete){
                try{complete=(published==null||published.awaitStopped(100))&&handler.awaitStopped(100)&&media.awaitStopped(100)&&runtime.awaitStopped(100);}
                catch(InterruptedException e){failed(new IOException("Receiver cleanup interrupted",e));Thread.interrupted();}
            }
            if(handler.cleanupFailure()!=null)failed(handler.cleanupFailure());if(media.failure()!=null)failed(media.failure());
            if(!userStop&&published!=null&&published.discovery.failure()!=null)failed(published.discovery.failure());
            synchronized(signal){state=failure==null?State.STOPPED:State.FAILED;stopped.countDown();signal.notifyAll();}
        }
    }
    public State state(){return state;}
    public IOException failure(){return failure;}
    public boolean awaitReady(long millis)throws IOException,InterruptedException{
        if(millis<0)throw new IllegalArgumentException("Wait duration");long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        synchronized(signal){while(state==State.STARTING&&!closing){long left=deadline-System.nanoTime();if(left<=0)return false;TimeUnit.NANOSECONDS.timedWait(signal,left);}
            if(failure!=null)throw failure;return state==State.READY&&!closing;}
    }
    public Iap2WirelessController.ReceiverIdentity receiverIdentity()throws IOException{
        if(state!=State.READY||closing)throw new IOException("Receiver not ready");return metadata.receiverIdentity();
    }
    public int port()throws IOException{if(state!=State.READY||closing)throw new IOException("Receiver not ready");return metadata.advertisement().port;}
    public void close(){synchronized(signal){userStop=true;closing=true;if(stopped.getCount()!=0)state=State.STOPPING;signal.notifyAll();}PublishedReceiver value=published;if(value!=null)value.close();}
    public boolean awaitStopped(long millis)throws InterruptedException{if(millis<0)throw new IllegalArgumentException("Wait duration");return stopped.await(millis,TimeUnit.MILLISECONDS);}
}
