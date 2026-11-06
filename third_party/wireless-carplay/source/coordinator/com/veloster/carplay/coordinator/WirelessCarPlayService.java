/* SPDX-License-Identifier: GPL-3.0-only. API17 service shell for the real session controller. */
package com.veloster.carplay.coordinator;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.io.IOException;
import java.lang.ref.WeakReference;

/** Declare exported=false, without a separate android:process. The Application
 * supplies installed platform dependencies and localized notifications; this
 * component supplies no default identity, consent, selected phone or auth.
 * All backend/journal/socket work belongs to SessionServiceController workers.
 * A destroyed Service does not imply its resources have finished draining. */
public final class WirelessCarPlayService extends Service {
    public static final String ACTION_START="com.veloster.carplay.START";
    public static final String ACTION_STOP="com.veloster.carplay.STOP";
    public static final String ACTION_RECOVER="com.veloster.carplay.RECOVER";
    public static final String ACTION_CLOSE="com.veloster.carplay.CLOSE";
    public static final String ACTION_AUTOMATIC_WAKE="com.veloster.carplay.AUTOMATIC_WAKE";
    public static final String ACTION_AUTOMATIC_RETAIN="com.veloster.carplay.AUTOMATIC_RETAIN";
    public static final String AP_SESSION="ap_session",SELECTED_PHONE="selected_phone";
    /** Private correlation for cleanup before a fresh product start. Never a
     * selected-phone permission or a request to start after Service recreation. */
    public static final String RECOVERY_TOKEN="recovery_token";
    private static final int NOTIFICATION_ID=0x564e4350;
    public interface Host {
        /** Main-thread factory lookup only. The returned factory opens resources
         * on its worker and must capture Application context, never an Activity
         * or Service. Creating this factory must not bind/open/query devices. */
        SessionServiceController.BackendFactory wirelessBackend(Context applicationContext)throws IOException;
        /** Main thread. A fresh notification with an app icon and localized text.
         * RUNNING is a running task, not evidence of CarPlay media success. */
        Notification wirelessNotification(Context applicationContext,SessionServiceController.Snapshot status);
    }
    /** Optional main-thread state publication, including CLOSED when no
     * foreground notification is built. Observers cannot prevent cleanup. */
    public interface StatusHost {void wirelessStatus(SessionServiceController.Snapshot status);}
    public interface RecoveryHost {
        /** Main thread, once for the exact RECOVER command after it drains.
         * The product must revalidate user intent and selection before START. */
        void wirelessRecovery(String token,boolean recovered);
    }
    /** Product-controlled restart eligibility. New registration consent is separate. */
    public interface AutomaticHost {
        void wirelessAutomaticWake();
        boolean wirelessKeepAlive();
    }
    private static final Object PROCESS=new Object();
    private static Slot active;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final LocalBinder binder=new LocalBinder();
    private Host host;private Slot slot;private boolean destroyed,finishWhenClosed,notificationFailed,lastCommandRejected,retainAfterClose;
    private int lastStartId;
    private final RecoveryFence recovery=new RecoveryFence();
    /** Command identity only; no resource or permission ownership. Kept separate
     * so the actual Service correlation can also be tested without Android. */
    static final class RecoveryFence {
        private String token;private long request;
        void clear(){token=null;}
        void begin(String value,long id){token=value;request=id;}
        /** A CLOSED controller cannot execute its abandoned mailbox. Its token
         * must fail once, never match a replacement controller's reused ID. */
        String retire(){String result=token;token=null;return result;}
        String completed(SessionServiceController.Snapshot status){
            if(token==null||status.requestId!=request||status.commandPending
                ||status.state!=SessionServiceController.State.IDLE&&status.state!=SessionServiceController.State.CLEANUP_REQUIRED&&status.state!=SessionServiceController.State.CLOSED)return null;
            String result=token;token=null;return result;
        }
    }

    /** Retained only while the controller owns resources. A Service recreation
     * in this process attaches to cleanup, rather than opening a second backend.
     * This is not proof of cleanup after process death; the journal handles that
     * separately and must not invent unknown-owner recovery receipts. */
    private static final class Slot implements SessionServiceController.Observer {
        final Handler main=new Handler(Looper.getMainLooper());
        final SessionServiceController controller;
        private WeakReference<WirelessCarPlayService> service;
        Slot(WirelessCarPlayService owner,SessionServiceController.BackendFactory factory)throws IOException{
            service=new WeakReference<WirelessCarPlayService>(owner);
            controller=new SessionServiceController(factory,this);
        }
        synchronized void attach(WirelessCarPlayService owner){service=new WeakReference<WirelessCarPlayService>(owner);}
        synchronized void detach(WirelessCarPlayService owner){if(service.get()==owner)service.clear();}
        public void changed(SessionServiceController.Snapshot status){
            if(status.state==SessionServiceController.State.CLOSED)synchronized(PROCESS){if(active==this)active=null;}
            main.post(new Runnable(){public void run(){
                WirelessCarPlayService owner;synchronized(Slot.this){owner=service.get();}
                if(owner!=null&&!owner.destroyed&&owner.slot==Slot.this)owner.publish();
            }});
        }
    }
    private static void requireMain(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Service UI methods require main thread");}
    private boolean automaticKeepAlive(){
        try{return host instanceof AutomaticHost&&((AutomaticHost)host).wirelessKeepAlive();}
        catch(RuntimeException unavailable){return false;}catch(LinkageError unavailable){return false;}
    }
    private void cancelRetainedClose(){if(retainAfterClose)finishWhenClosed=true;retainAfterClose=false;}
    public void onCreate(){
        super.onCreate();requireMain();
        if(!(getApplication() instanceof Host)){lastCommandRejected=true;stopSelf();return;}
        host=(Host)getApplication();
        try{ensureController();publish();}
        catch(IOException failure){lastCommandRejected=true;stopSelf();}
        catch(RuntimeException failure){lastCommandRejected=true;if(slot!=null)slot.controller.close();stopSelf();}
        catch(LinkageError failure){lastCommandRejected=true;if(slot!=null)slot.controller.close();stopSelf();}
    }
    private SessionServiceController ensureController()throws IOException{
        requireMain();if(destroyed||host==null)throw new IOException("Wireless service unavailable");
        synchronized(PROCESS){
            if(slot!=null&&slot.controller.snapshot().state!=SessionServiceController.State.CLOSED)return slot.controller;
            if(active!=null&&active.controller.snapshot().state!=SessionServiceController.State.CLOSED){slot=active;slot.attach(this);}
            else{
                Context application=getApplicationContext();
                SessionServiceController.BackendFactory factory=host.wirelessBackend(application);
                if(factory==null)throw new IOException("Wireless platform configuration unavailable");
                slot=new Slot(this,factory);active=slot;
            }
        }
        notificationFailed=false;finishWhenClosed=false;return slot.controller;
    }
    /** Snapshot publication always samples current state on main, so a queued
     * callback from an old command cannot overwrite a newer command's status. */
    private void publish(){
        requireMain();if(destroyed||slot==null)return;
        SessionServiceController.Snapshot status=slot.controller.snapshot();
        if(status.state==SessionServiceController.State.CLOSED&&retainAfterClose){
            // Fail any exact old recovery before replacing its controller.
            // The product sees the replacement IDLE, not a transient CLOSED
            // that would revoke its newly renewed Service-retention intent.
            String retired=recovery.retire();
            if(retired!=null&&host instanceof RecoveryHost)try{((RecoveryHost)host).wirelessRecovery(retired,false);}
            catch(RuntimeException ignored){}catch(LinkageError ignored){}
            if(destroyed||slot==null)return;status=slot.controller.snapshot();
            if(status.state==SessionServiceController.State.CLOSED&&retainAfterClose){
                if(!notificationFailed&&automaticKeepAlive()){
                    try{ensureController();retainAfterClose=false;status=slot.controller.snapshot();}
                    catch(IOException unavailable){cancelRetainedClose();lastCommandRejected=true;}
                    catch(RuntimeException unavailable){cancelRetainedClose();lastCommandRejected=true;}
                    catch(LinkageError unavailable){cancelRetainedClose();lastCommandRejected=true;}
                }else cancelRetainedClose();
            }
        }
        if(host instanceof StatusHost)try{((StatusHost)host).wirelessStatus(status);}
        catch(RuntimeException ignored){}catch(LinkageError ignored){}
        // A product observer may synchronously Stop/Recover/close. Never use
        // the pre-callback snapshot to complete a different command's fence.
        if(destroyed||slot==null)return;status=slot.controller.snapshot();
        String token=recovery.completed(status);
        if(token!=null){
            if(host instanceof RecoveryHost)try{((RecoveryHost)host).wirelessRecovery(token,status.state==SessionServiceController.State.IDLE&&!status.failed);}
            catch(RuntimeException ignored){}catch(LinkageError ignored){}
        }
        if(destroyed||slot==null)return;status=slot.controller.snapshot();
        if(status.state==SessionServiceController.State.CLOSED){
            stopForeground(true);
            if(finishWhenClosed){if(lastStartId!=0)stopSelfResult(lastStartId);else stopSelf();}
            return;
        }
        if(notificationFailed)return;
        try{
            Notification notice=host.wirelessNotification(getApplicationContext(),status);
            if(notice==null||notice.icon==0)throw new IllegalStateException("Foreground notification unavailable");
            notice.flags|=Notification.FLAG_ONGOING_EVENT;startForeground(NOTIFICATION_ID,notice);
        }catch(RuntimeException failure){notificationFailed=true;retainAfterClose=false;lastCommandRejected=true;finishWhenClosed=true;slot.controller.close();}
         catch(LinkageError failure){notificationFailed=true;retainAfterClose=false;lastCommandRejected=true;finishWhenClosed=true;slot.controller.close();}
    }
    public int onStartCommand(Intent intent,int flags,int startId){
        requireMain();lastStartId=startId;lastCommandRejected=false;
        try{
            String action=intent==null?null:intent.getAction();
            if(intent==null){if(host instanceof AutomaticHost)((AutomaticHost)host).wirelessAutomaticWake();}
            else if(ACTION_START.equals(action))binder.start(intent.getStringExtra(AP_SESSION),intent.getStringExtra(SELECTED_PHONE));
            else if(ACTION_STOP.equals(action))binder.stop();
            else if(ACTION_RECOVER.equals(action)){
                String token=intent.getStringExtra(RECOVERY_TOKEN);
                if(token!=null&&!token.matches("[0-9a-f]{32}"))throw new IllegalArgumentException("Recovery token");
                long request=binder.recover();recovery.begin(token,request);
            }
            else if(ACTION_CLOSE.equals(action))binder.closeService();
            else if(ACTION_AUTOMATIC_WAKE.equals(action)&&host instanceof AutomaticHost)((AutomaticHost)host).wirelessAutomaticWake();
            else if(ACTION_AUTOMATIC_RETAIN.equals(action)&&host instanceof AutomaticHost){
                if(!notificationFailed&&automaticKeepAlive()){
                    SessionServiceController.State state=slot==null?null:slot.controller.snapshot().state;
                    if(finishWhenClosed||state==SessionServiceController.State.CLOSING||state==SessionServiceController.State.CLOSED){retainAfterClose=true;finishWhenClosed=false;}
                }else cancelRetainedClose();
            }
            else lastCommandRejected=true;
        }catch(IOException failure){lastCommandRejected=true;}
         catch(IllegalArgumentException failure){lastCommandRejected=true;}
         catch(RuntimeException failure){retainAfterClose=false;lastCommandRejected=true;finishWhenClosed=true;if(slot!=null)slot.controller.close();}
         catch(LinkageError failure){retainAfterClose=false;lastCommandRejected=true;finishWhenClosed=true;if(slot!=null)slot.controller.close();}
        publish();
        // Null restart intents revalidate consent asynchronously. Only a loaded
        // product policy may retain this service; legacy hosts stay non-sticky.
        return !finishWhenClosed&&automaticKeepAlive()?START_STICKY:START_NOT_STICKY;
    }
    public IBinder onBind(Intent intent){requireMain();return host==null||destroyed?null:binder;}
    public void onDestroy(){
        requireMain();destroyed=true;recovery.clear();
        Slot owned=slot;if(owned!=null){owned.detach(this);owned.controller.close();}
        main.removeCallbacksAndMessages(null);stopForeground(true);super.onDestroy();
        // Do not join here or clear the process slot before actual backend close.
    }
    /** Same-process client API; no Binder transaction protocol or exported
     * endpoint is provided. Activity callers use this on their main thread. */
    public final class LocalBinder extends Binder {
        public long start(String apSession,String selectedPhone)throws IOException{
            requireMain();recovery.clear();SessionServiceController control=ensureController();
            long id=control.start(apSession,selectedPhone);finishWhenClosed=false;lastCommandRejected=false;publish();return id;
        }
        public long stop()throws IOException{requireMain();cancelRetainedClose();recovery.clear();long id=ensureController().stop();lastCommandRejected=false;publish();return id;}
        public long recover()throws IOException{requireMain();recovery.clear();long id=ensureController().recover();lastCommandRejected=false;publish();return id;}
        public void closeService(){requireMain();retainAfterClose=false;recovery.clear();finishWhenClosed=true;if(slot!=null)slot.controller.close();else stopSelf();publish();}
        public SessionServiceController.Snapshot snapshot(){requireMain();if(slot==null)throw new IllegalStateException("Wireless service unavailable");return slot.controller.snapshot();}
        public boolean lastCommandRejected(){requireMain();return lastCommandRejected;}
    }
}
