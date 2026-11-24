/* SPDX-License-Identifier: GPL-3.0-only. Worker-owned Android service lifecycle adapter. */
package com.veloster.carplay.coordinator;

import com.veloster.carplay.RfcommConnector;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Nonblocking command mailbox. RUNNING means a Task exists, never connection success.
 * One worker owns backend creation, recovery, cancellation, join and resource close.
 * Backend callbacks must be bounded; a stuck callback retains ownership and cannot
 * allow a replacement generation. No interrupt is used to pretend it terminated.
 */
public final class SessionServiceController implements Closeable {
    public enum State { IDLE, OPENING, RECOVERING, PREPARING, STARTING, RUNNING, STOPPING, CLEANUP_REQUIRED, CLOSING, CLOSED }
    public enum Failure { NONE, OPEN_FAILED, START_FAILED, TASK_FAILED, CANCEL_FAILED, RECOVERY_REQUIRED, CLOSE_FAILED }
    public interface BackendFactory {
        /** Worker only. On failure, close all partially created resources before throwing. */
        Backend open()throws IOException,InterruptedException;
    }
    public interface Backend extends Closeable {
        SessionCoordinator coordinator()throws IOException;
        SessionCoordinator.Factory sessionFactory()throws IOException;
        /** Idempotently close additional resources, NOT the coordinator/journal.
         * The controller releases the coordinator lock only after this succeeds. */
        void close()throws IOException;
    }
    /** Optional actual AP/selected-phone preparation, before the coordinator
     * checks the AP generation. Runs on the same owning worker as cleanup.
     * The backend retains partial allocations even if prepare throws. No new
     * prepare may run before discardPrepared succeeds. A delayed preparation
     * is allowed to finish but cannot start a superseded receiver attempt. */
    public interface PreparedBackend extends Backend {
        SessionCoordinator.Factory prepare(String apSession,String selectedPhone)throws IOException,InterruptedException;
        /** Worker-only, retryable. Release this preparation after its session
         * has really drained; never merely because a STOP command arrived. */
        void discardPrepared()throws IOException;
    }
    /** Startup recovery for durable resources allocated before receiver BEGIN.
     * Runs after receiver cleanup and before preparation or replacement starts. */
    public interface RecoveryBackend extends PreparedBackend {void recoverOwnedResources()throws IOException;}
    public interface Observer {
        /** Worker callback; must not block. Android clients post to their main Handler. */
        void changed(Snapshot snapshot);
    }
    public static final class Snapshot {
        public final State state;public final long requestId,attemptId;
        /** Opaque AP generation of the latest START, retained after it ends;
         * STOP/RECOVER/CLOSE clear it. Contains no SSID or password. */
        public final String requestApSession;
        public final boolean failed,commandPending;public final Failure failure;
        private Snapshot(State state,long request,long attempt,Failure failure,boolean commandPending,String requestApSession){
            this.state=state;requestId=request;attemptId=attempt;this.failure=failure;failed=failure!=Failure.NONE;this.commandPending=commandPending;
            this.requestApSession=requestApSession;
        }
    }
    private static final int START=1,STOP=2,RECOVER=3,CLOSE=4;
    private static final class Command {
        final int operation;final long id;final String ap,phone;
        Command(int operation,long id,String ap,String phone){this.operation=operation;this.id=id;this.ap=ap;this.phone=phone;}
    }
    private final BackendFactory factory;private final Observer observer;private final Thread worker;
    private final CountDownLatch closed=new CountDownLatch(1);
    private long nextId,attemptId;private Command pending;private boolean closing;private String requestApSession;
    private Snapshot snapshot=new Snapshot(State.IDLE,0,0,Failure.NONE,false,null);
    // Only the worker accesses backend/coordinator/task and these flags.
    private Backend backend;private SessionCoordinator coordinator;private SessionCoordinator.Task task;
    private boolean needsRecovery,blocked,cancelSent,backendClosed,prepared;
    public SessionServiceController(BackendFactory factory,Observer observer)throws IOException{
        if(factory==null)throw new NullPointerException();this.factory=factory;this.observer=observer;
        worker=new Thread(new Runnable(){public void run(){work();}},"vn-session-service");worker.setDaemon(true);
        try{worker.start();}catch(RuntimeException|LinkageError unavailable){throw new IOException("Service worker unavailable",unavailable);}
    }
    public long start(String apSession,String selectedPhone)throws IOException{
        if(apSession==null||!apSession.matches("[0-9a-f]{32}"))throw new IllegalArgumentException("AP generation");
        return enqueue(START,apSession,RfcommConnector.normalizeAddress(selectedPhone));
    }
    public long stop()throws IOException{return enqueue(STOP,null,null);}
    public long recover()throws IOException{return enqueue(RECOVER,null,null);}
    /** Sticky shutdown intent; another close or recover explicitly retries failed cleanup. */
    public void close(){try{enqueue(CLOSE,null,null);}catch(IOException alreadyClosed){}}
    private synchronized long enqueue(int operation,String ap,String phone)throws IOException{
        if(snapshot.state==State.CLOSED)throw new IOException("Service controller closed");
        if(operation==START&&closing)throw new IOException("Service controller closing");
        if(nextId==Long.MAX_VALUE)throw new IOException("Service request generation exhausted");
        if(operation==CLOSE)closing=true;
        if(closing&&operation==STOP)operation=CLOSE;
        long id=++nextId;pending=new Command(operation,id,ap,phone);
        requestApSession=operation==START?ap:null;
        State state=closing?State.CLOSING:attemptId!=0?State.STOPPING:snapshot.state;
        snapshot=new Snapshot(state,id,attemptId,snapshot.failure,true,requestApSession);notifyAll();return id;
    }
    public synchronized Snapshot snapshot(){return snapshot;}
    public boolean awaitClosed(long millis)throws InterruptedException{
        if(millis<0)throw new IllegalArgumentException("Close wait");
        return Thread.currentThread()!=worker&&closed.await(millis,TimeUnit.MILLISECONDS);
    }
    private void publish(State state,Failure failure){
        Snapshot current;
        synchronized(this){snapshot=current=new Snapshot(state,nextId,attemptId,failure,pending!=null,requestApSession);}
        if(observer!=null)try{observer.changed(current);}catch(RuntimeException|LinkageError ignored){/* UI cannot skip cleanup. */}
    }
    private synchronized boolean current(Command command){return nextId==command.id&&!closing;}
    private synchronized boolean superseded(){return pending!=null||closing;}
    private synchronized boolean closing(){return closing;}
    private synchronized void attempt(long id){attemptId=id;}
    private boolean openBackend(){
        if(backend!=null)return coordinator!=null;
        publish(State.OPENING,Failure.NONE);
        try{
            backend=factory.open();if(backend==null)throw new IOException("Backend missing");
            coordinator=backend.coordinator();if(coordinator==null)throw new IOException("Coordinator missing");
            needsRecovery=true;return true;
        }catch(InterruptedException interrupted){Thread.interrupted();}
         catch(IOException unavailable){}
         catch(RuntimeException|LinkageError unavailable){}
        // A returned but malformed backend still owns resources and must be closed.
        if(backend!=null){blocked=true;publish(State.CLEANUP_REQUIRED,Failure.OPEN_FAILED);}
        else publish(State.IDLE,Failure.OPEN_FAILED);
        return false;
    }
    private boolean recoverBackend(){
        publish(State.RECOVERING,Failure.NONE);boolean clean=false;
        try{clean=coordinator!=null&&coordinator.recover();}catch(IOException unavailable){}catch(RuntimeException|LinkageError unavailable){}
        if(clean&&backend instanceof RecoveryBackend){
            try{((RecoveryBackend)backend).recoverOwnedResources();}
            catch(IOException failure){clean=false;}catch(RuntimeException|LinkageError failure){clean=false;}
        }
        if(clean)clean=discardPrepared();
        blocked=!clean;needsRecovery=!clean;
        if(!clean)publish(State.CLEANUP_REQUIRED,Failure.RECOVERY_REQUIRED);
        return clean;
    }
    private boolean discardPrepared(){
        if(!prepared)return true;
        try{((PreparedBackend)backend).discardPrepared();prepared=false;return true;}
        catch(IOException failure){}catch(RuntimeException|LinkageError failure){}
        blocked=true;needsRecovery=true;return false;
    }
    private void finishClose(){
        publish(State.CLOSING,Failure.NONE);
        try{
            if(!discardPrepared())throw new IOException("Prepared resources still owned");
            if(backend!=null&&!backendClosed){backend.close();backendClosed=true;}
            if(coordinator!=null)coordinator.close();
            backend=null;coordinator=null;blocked=false;attempt(0);
            publish(State.CLOSED,Failure.NONE);closed.countDown();
        }catch(IOException unavailable){blocked=true;publish(State.CLEANUP_REQUIRED,Failure.CLOSE_FAILED);}
         catch(RuntimeException|LinkageError unavailable){blocked=true;publish(State.CLEANUP_REQUIRED,Failure.CLOSE_FAILED);}
    }
    private void completedTask(){
        Failure failure=Failure.NONE;boolean dirty=true;
        try{SessionCoordinator.Result result=task.result();dirty=result==null||result.cleanupRequired;
            if(result==null||result.session==null&&!cancelSent||result.session!=null&&result.session.end==WirelessSession.End.FAILED)failure=Failure.TASK_FAILED;
        }catch(IOException unavailable){failure=Failure.TASK_FAILED;}
         catch(RuntimeException|LinkageError unavailable){failure=Failure.TASK_FAILED;}
        if(coordinator.state()==SessionCoordinator.State.CLEANUP_REQUIRED)dirty=true;
        task=null;cancelSent=false;attempt(0);
        if(!dirty&&!discardPrepared())dirty=true;
        blocked=dirty;
        publish(dirty?State.CLEANUP_REQUIRED:State.IDLE,dirty?Failure.RECOVERY_REQUIRED:failure);
    }
    private void work(){
        while(closed.getCount()!=0){
            if(task!=null){
                if(superseded()&&!cancelSent){
                    cancelSent=true;publish(State.STOPPING,Failure.NONE);
                    try{task.cancel();}catch(IOException unavailable){publish(State.STOPPING,Failure.CANCEL_FAILED);}
                     catch(RuntimeException|LinkageError unavailable){publish(State.STOPPING,Failure.CANCEL_FAILED);}
                }
                try{if(task.await(50))completedTask();}catch(InterruptedException interrupted){Thread.interrupted();}
                continue;
            }
            Command command;
            synchronized(this){
                while(pending==null&&closed.getCount()!=0)try{wait();}catch(InterruptedException interrupted){Thread.interrupted();}
                if(closed.getCount()==0)return;
                command=pending;pending=null;
            }
            if(command.operation==STOP){if(!blocked)discardPrepared();attempt(0);publish(blocked?State.CLEANUP_REQUIRED:State.IDLE,blocked?Failure.RECOVERY_REQUIRED:Failure.NONE);continue;}
            if(command.operation==CLOSE){
                if(backendClosed){finishClose();continue;}
                if(coordinator!=null&&(needsRecovery||coordinator.state()==SessionCoordinator.State.CLEANUP_REQUIRED)&&!recoverBackend())continue;
                finishClose();continue;
            }
            if(command.operation==RECOVER){
                if(closing()&&backendClosed){finishClose();continue;}
                if(!openBackend()){
                    // A malformed returned backend can still be retired on explicit recovery.
                    if(closing()&&backend!=null&&coordinator==null)finishClose();
                    continue;
                }
                if(!recoverBackend())continue;
                if(closing())finishClose();else publish(State.IDLE,Failure.NONE);
                continue;
            }
            // A new start is not permission to retry uncertain cleanup.
            if(blocked){publish(State.CLEANUP_REQUIRED,Failure.RECOVERY_REQUIRED);continue;}
            if(!current(command)||!openBackend()||!current(command))continue;
            if(needsRecovery&&!recoverBackend())continue;
            if(!current(command))continue;
            attempt(command.id);publish(State.STARTING,Failure.NONE);
            try{
                SessionCoordinator.Factory sessionFactory;
                if(backend instanceof PreparedBackend){
                    prepared=true;publish(State.PREPARING,Failure.NONE);
                    sessionFactory=((PreparedBackend)backend).prepare(command.ap,command.phone);
                }else sessionFactory=backend.sessionFactory();
                if(!current(command)){
                    attempt(0);if(!discardPrepared())publish(State.CLEANUP_REQUIRED,Failure.RECOVERY_REQUIRED);
                    continue;
                }
                publish(State.STARTING,Failure.NONE);
                task=coordinator.start(command.ap,command.phone,sessionFactory);cancelSent=false;
                publish(current(command)?State.RUNNING:State.STOPPING,Failure.NONE);
            }catch(InterruptedException unavailable){Thread.interrupted();startFailed();}
             catch(IOException unavailable){startFailed();}
             catch(RuntimeException|LinkageError unavailable){startFailed();}
        }
    }
    private void startFailed(){
        attempt(0);blocked=coordinator.state()==SessionCoordinator.State.CLEANUP_REQUIRED;
        if(!blocked)discardPrepared();
        publish(blocked?State.CLEANUP_REQUIRED:State.IDLE,Failure.START_FAILED);
    }
}
