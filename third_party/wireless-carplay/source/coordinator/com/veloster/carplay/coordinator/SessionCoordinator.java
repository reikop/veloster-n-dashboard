/* SPDX-License-Identifier: GPL-3.0-only. Isolated wireless integration module. */
package com.veloster.carplay.coordinator;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Single coordinator holds the private journal lock through run AND cleanup.
 * Recover never starts Bluetooth/AP/auth services; it stops the recorded owner
 * and retries resources retained from a failed cleanup in this process.
 * Blocking methods belong to a worker; cancel may be called by the UI thread.
 */
public final class SessionCoordinator implements Closeable {
    public enum State { IDLE, RUNNING, RECOVERING, CLEANUP_REQUIRED, CLOSED }
    public interface Generation { boolean current(String apSession)throws IOException; }
    public interface Factory {
        /** Bind OEM auth, create the RFCOMM adapter, then return the session and
         * its binding/resource lease. Use record.owner and record.phone exactly.
         * On a thrown exception the factory must close its partially built resources
         * or throw FactoryCleanupException with the pending cleanup lease.
         */
        Handle open(SessionJournal.Record record,ReceiverControl fenced)throws IOException,InterruptedException;
    }
    /** A factory failed before returning a Handle, and still owns resources.
     * The coordinator retains this cleanup object and blocks later starts until
     * close succeeds. The object must support a retry after a failed close. */
    public static final class FactoryCleanupException extends IOException {
        private static final long serialVersionUID=1L;
        public final Closeable pendingResources;
        public FactoryCleanupException(String message,Closeable pendingResources,Throwable cause){
            super(message,cause);if(pendingResources==null)throw new NullPointerException();this.pendingResources=pendingResources;
        }
    }
    public static final class Handle implements Closeable {
        public final WirelessSession session;
        private final Closeable resources;
        public Handle(WirelessSession session,Closeable resources){if(session==null || resources==null)throw new NullPointerException();this.session=session;this.resources=resources;}
        public void close()throws IOException{resources.close();}
    }
    public static final class Result {
        public final WirelessSession.Result session;
        public final boolean cleanupRequired;
        public final boolean wirelessCarPlayVerified=false;
        Result(WirelessSession.Result session,boolean cleanupRequired){this.session=session;this.cleanupRequired=cleanupRequired;}
    }
    private final SessionJournal journal;
    private final ReceiverControl receiver;
    private final Generation generation;
    private final ProcessOwner process;
    private volatile State state;
    private boolean busy,cancelled;
    private String currentOwner;
    private WirelessSession active;
    private Handle pendingHandle;private Closeable pendingFactoryResources;private boolean pendingSessionRetry;
    public SessionCoordinator(SessionJournal journal,ReceiverControl receiver,Generation generation)throws IOException {
        this(journal,receiver,generation,null);
    }
    public SessionCoordinator(SessionJournal journal,ReceiverControl receiver,Generation generation,ProcessOwner process)throws IOException {
        if(journal==null || receiver==null || generation==null)throw new NullPointerException();
        this.journal=journal;this.receiver=receiver;this.generation=generation;this.process=process;
        state=journal.pending()==null?State.IDLE:State.CLEANUP_REQUIRED;
    }
    public State state(){return state;}
    private synchronized void enter(boolean recovery,String owner)throws IOException {
        if(state==State.CLOSED)throw new IOException("Coordinator closed");
        if(busy)throw new IOException("Wireless operation already running");
        if(!recovery && (state==State.CLEANUP_REQUIRED || pendingHandle!=null || pendingFactoryResources!=null || journal.pending()!=null))throw new IOException("Previous receiver cleanup required");
        busy=true;cancelled=false;currentOwner=owner;state=recovery?State.RECOVERING:State.RUNNING;
    }
    private synchronized void leave(boolean clean){active=null;currentOwner=null;busy=false;state=clean?State.IDLE:State.CLEANUP_REQUIRED;}
    public boolean recover()throws IOException {
        enter(true,null);boolean clean=false;
        try{clean=cleanupPending();return clean;}
        finally{leave(clean);}
    }
    private enum RecordedCleanup { ABSENT, RELEASED, PENDING }
    private RecordedCleanup cleanupRecorded() {
        try {
            SessionJournal.Record previous=journal.pending();if(previous==null)return RecordedCleanup.ABSENT;
            ReceiverControl.Reply receipt=receiver.request(ReceiverControl.STOP,previous.owner);
            if(!receipt.released())return RecordedCleanup.PENDING;
            journal.clearAfterStop(previous,receipt);return RecordedCleanup.RELEASED;
        }catch(Exception unavailable){return RecordedCleanup.PENDING;}catch(LinkageError unavailable){return RecordedCleanup.PENDING;}
    }
    /** Called only by the single reserved worker. Do not drop authentication or
     * registration resources while receiver/session cleanup remains unproven. */
    private boolean cleanupPending(){
        Handle handle;Closeable factoryResources;boolean retry;
        synchronized(this){handle=pendingHandle;factoryResources=pendingFactoryResources;retry=pendingSessionRetry;}
        boolean sessionClean=true,retryClean=true;
        if(handle!=null){
            try{handle.session.close();}catch(Exception failure){sessionClean=false;}catch(LinkageError failure){sessionClean=false;}
            if(retry){
                try{if(handle.session.retryCleanup()){synchronized(this){pendingSessionRetry=false;}}else retryClean=false;}
                catch(Exception failure){retryClean=false;}catch(LinkageError failure){retryClean=false;}
            }
        }
        RecordedCleanup receiverClean=cleanupRecorded();
        if(!retryClean&&receiverClean==RecordedCleanup.RELEASED){
            // Drain completed after the session's bounded STOP wait. Reconcile
            // only after this exact journal owner received a real release and
            // its durable record cleared. Mere journal absence is not proof.
            try{retryClean=handle.session.retryCleanup();if(retryClean)synchronized(this){pendingSessionRetry=false;}}
            catch(Exception failure){retryClean=false;}catch(LinkageError failure){retryClean=false;}
        }
        if(receiverClean==RecordedCleanup.PENDING||!sessionClean||!retryClean)return false;
        if(handle!=null){
            try{handle.close();synchronized(this){if(pendingHandle==handle)pendingHandle=null;}}
            catch(Exception failure){return false;}catch(LinkageError failure){return false;}
        }
        if(factoryResources!=null){
            try{factoryResources.close();synchronized(this){if(pendingFactoryResources==factoryResources)pendingFactoryResources=null;}}
            catch(Exception failure){return false;}catch(LinkageError failure){return false;}
        }
        synchronized(this){return pendingHandle==null&&pendingFactoryResources==null;}
    }
    public Result run(String apSession,String selectedPhone,Factory factory)throws IOException {
        if(factory==null)throw new NullPointerException();
        SessionJournal.Record record=new SessionJournal.Record(WirelessSession.newOwner(),apSession,selectedPhone,process);
        enter(false,record.owner);return execute(record,factory);
    }
    /** Reserves ownership BEFORE scheduling, so an immediate UI stop cannot race
     * ahead of worker entry and be lost. Task cancellation is attempt-scoped.
     */
    public Task start(String apSession,String selectedPhone,Factory factory)throws IOException {
        if(factory==null)throw new NullPointerException();
        SessionJournal.Record record=new SessionJournal.Record(WirelessSession.newOwner(),apSession,selectedPhone,process);
        enter(false,record.owner);final Task task=new Task(record,factory);
        try{new Thread(new Runnable(){public void run(){task.work();}},"vn-wireless-session").start();return task;}
        catch(RuntimeException failure){leave(true);throw new IOException("Wireless worker unavailable");}
        catch(Error failure){leave(true);throw failure;}
    }
    public final class Task {
        private final SessionJournal.Record record;
        private final Factory factory;
        private final CountDownLatch done=new CountDownLatch(1);
        private volatile Result result;private volatile Throwable failure;
        private Task(SessionJournal.Record record,Factory factory){this.record=record;this.factory=factory;}
        private void work(){try{result=execute(record,factory);}catch(Throwable error){failure=error;}finally{done.countDown();}}
        public void cancel()throws IOException{cancelOwner(record.owner);}
        public boolean await(long timeoutMillis)throws InterruptedException {
            if(timeoutMillis<0)throw new IllegalArgumentException();return done.await(timeoutMillis,TimeUnit.MILLISECONDS);
        }
        public Result result()throws IOException {
            if(done.getCount()!=0)throw new IllegalStateException("Wireless worker still running");
            if(failure!=null)throw new IOException("Wireless worker failed");return result;
        }
    }
    private Result execute(SessionJournal.Record record,Factory factory) {
        Handle handle=null;Closeable factoryResources=null;WirelessSession.Result result=null;boolean clean=false;
        try {
            synchronized(this){if(cancelled)throw new IOException("Wireless start cancelled");}
            if(!generation.current(record.apSession))throw new IOException("AP generation unavailable");
            handle=factory.open(record,new FencedReceiver(record));
            if(handle==null || !record.owner.equals(handle.session.receiverOwner()) || !record.phone.equals(handle.session.selectedAddress()))
                throw new IOException("Session factory scope mismatch");
            boolean stop;
            synchronized(this){active=handle.session;stop=cancelled;}
            if(stop)handle.session.close();
            result=handle.session.run();
        }catch(FactoryCleanupException partial){factoryResources=partial.pendingResources;}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        catch(IOException unavailable){ }
        catch(RuntimeException unavailable){ }
        finally {
            boolean interrupted=Thread.interrupted();
            try {
                synchronized(this){pendingHandle=handle;pendingFactoryResources=factoryResources;pendingSessionRetry=result!=null&&result.cleanupRequired;}
                clean=cleanupPending();
            }finally{leave(clean);if(interrupted)Thread.currentThread().interrupt();}
        }
        return new Result(result,!clean);
    }
    public void cancel()throws IOException {
        WirelessSession session;
        synchronized(this){cancelled=true;session=active;}
        if(session!=null)session.close();
    }
    private void cancelOwner(String owner)throws IOException {
        WirelessSession session;
        synchronized(this){if(!owner.equals(currentOwner))return;cancelled=true;session=active;}
        if(session!=null)session.close();
    }
    /** Do not release the cross-process lock while a worker can still send BEGIN. */
    public synchronized void close()throws IOException {
        if(busy)throw new IOException("Stop the session worker before closing coordinator");
        if(pendingHandle!=null||pendingFactoryResources!=null)throw new IOException("Recover pending resource cleanup before closing coordinator");
        if(state!=State.CLOSED){
            try{journal.close();state=State.CLOSED;}
            catch(IOException pending){state=State.CLEANUP_REQUIRED;throw pending;}
        }
    }
    private final class FencedReceiver implements ReceiverControl {
        private final SessionJournal.Record record;
        FencedReceiver(SessionJournal.Record record){this.record=record;}
        private void activeAttempt()throws IOException {
            synchronized(SessionCoordinator.this) {
                if(!busy || cancelled || !record.owner.equals(currentOwner))throw new IOException("Receiver attempt is no longer active");
            }
        }
        public synchronized Reply request(int operation,String owner)throws IOException {
            if(!record.owner.equals(owner))throw new IOException("Receiver owner mismatch");
            SessionJournal.Record stored=journal.pending();
            if(stored!=null && !stored.same(record))throw new IOException("Recovery journal generation changed");
            if(operation==BEGIN) {
                activeAttempt();
                if(!generation.current(record.apSession))throw new IOException("AP generation changed before receiver start");
                if(stored==null)journal.prepare(record);
                activeAttempt();
            } else if(stored==null) {
                // No BEGIN was sent by this attempt. An early failure may still
                // ask for STOP; do not send unrelated commands without a journal.
                if(operation==STOP)return new Reply(0,0);
                throw new IOException("Receiver was not started by this attempt");
            }
            Reply reply=receiver.request(operation,owner);
            // Clear only after a real STOP receipt. Unknown outcome keeps the file.
            if(operation==STOP && reply.released())journal.clearAfterStop(record,reply);
            return reply;
        }
    }
}
