package com.veloster.carplayhub;

import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.os.*;
import com.veloster.carplay.*;
import com.veloster.carplay.control.*;
import com.veloster.carplay.coordinator.*;
import com.veloster.carplay.discovery.*;
import com.veloster.carplay.input.TouchHid;
import com.veloster.carplay.media.VolumeStore;
import com.veloster.carplay.media.VolumePolicy;
import com.veloster.carplay.media.LastUserModes;
import com.veloster.carplay.media.AndroidLastUserModes;
import com.veloster.carplay.nativemodes.AndroidNativeModeObserver;
import com.veloster.carplay.nativemodes.NativeModeObserver;
import com.veloster.carplay.nativemodes.NativeModeHistory;
import com.veloster.carplay.nativemodes.AndroidNativeModeHistory;
import com.veloster.carplay.sirioem.AndroidOemSiriProvider;
import com.veloster.carplay.audiooem.OptionalOemAlternateAudioFactory;
import com.veloster.diagnostics.ErrorLog;
import com.veloster.i18n.UiText;
import java.io.*;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Application composition for the optional receiver. Device work stays on workers;
 * main owns the Activity, notification and explicit private Service commands. */
public final class HubApplication extends Application implements WirelessCarPlayService.Host,WirelessCarPlayService.StatusHost,WirelessCarPlayService.RecoveryHost,WirelessCarPlayService.AutomaticHost {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService work=Executors.newSingleThreadExecutor();
    private final AtomicLong radioEpoch=new AtomicLong();
    private volatile BluetoothRadioHandoff radioHandoff;
    private volatile WirelessCarPlaySettings settings;
    private volatile AutomaticConnectionState automaticState;
    private volatile boolean automaticReady;
    private boolean wakePending,wakeAgain,pendingPresence,selectedPresent;
    private String presenceSelection,deferredPresence;
    private volatile VolumeStore volumes;
    private volatile LastUserModes userModes;private long modeEditEpoch;private volatile long screenEditEpoch,mediaEditEpoch;
    private long appliedScreenEditEpoch,appliedMediaEditEpoch;
    // A persisted app preference cannot prove what happened in OEM apps while
    // this process was absent. Do not launch a screen on that evidence alone.
    private boolean screenChoiceObserved;
    private long screenChoiceAt=-1;
    private volatile AndroidNativeModeObserver nativeModeObserver;
    private volatile IOException nativeModeFailure;
    private volatile boolean nativeModesStopped;
    // Optional, locally reviewed OEM producer. Digest does not include or ship OEM code.
    private static final String OEM_SERVICES_JAR_SHA256="a28006e0449e991018a21c5ed46f98b3e4fdbdcc6febab1eb73928d8c769909f";
    interface HistoryReader {NativeModeHistory.Snapshot read()throws IOException;}
    private HistoryReader historyReader=new HistoryReader(){public NativeModeHistory.Snapshot read()throws IOException{return AndroidNativeModeHistory.read(OEM_SERVICES_JAR_SHA256);}};
    private final ExecutorService historyWork=Executors.newSingleThreadExecutor(new ThreadFactory(){public Thread newThread(Runnable task){Thread thread=new Thread(task,"vn-native-history");thread.setDaemon(true);return thread;}});
    private boolean historyReadPending,historyReadExpired;
    private final Runnable historyTimeout=new Runnable(){public void run(){
        if(!historyReadPending||nativeModesStopped)return;
        historyReadExpired=true;LastUserModes modes=userModes;
        if(modes!=null){LastUserModes.Snapshot before=modes.snapshot();applyNativeHistory(modes,null,before.screenRevision,before.mediaRevision);}
        // A timeout revokes restored trust, but does not release the blocked worker.
    }};
    private final Runnable historyPoll=new Runnable(){public void run(){
        if(nativeModesStopped||historyReadPending)return;
        historyReadPending=true;historyReadExpired=false;
        final long screenTicket=screenEditEpoch,mediaTicket=mediaEditEpoch;
        main.postDelayed(historyTimeout,4000);
        try{historyWork.execute(new Runnable(){public void run(){
            LastUserModes loaded=null;LastUserModes.Snapshot before=null;NativeModeHistory.Snapshot result=null;
            try{loaded=loadUserModes();before=loaded.snapshot();result=historyReader.read();}
            catch(IOException ignored){}catch(RuntimeException ignored){}catch(LinkageError ignored){}
            final LastUserModes modes=loaded;final LastUserModes.Snapshot prior=before;final NativeModeHistory.Snapshot history=result;
            main.post(new Runnable(){public void run(){
                main.removeCallbacks(historyTimeout);historyReadPending=false;
                if(nativeModesStopped)return;
                if(!historyReadExpired&&modes!=null&&prior!=null)applyNativeHistory(modes,history,
                    screenTicket==screenEditEpoch&&screenTicket==appliedScreenEditEpoch?prior.screenRevision:-1,
                    mediaTicket==mediaEditEpoch&&mediaTicket==appliedMediaEditEpoch?prior.mediaRevision:-1);
                main.postDelayed(historyPoll,1000);
            }});
        }});}catch(RuntimeException error){main.removeCallbacks(historyTimeout);historyReadPending=false;}
    }};
    private final Runnable nativeModeHealth=new Runnable(){public void run(){
        AndroidNativeModeObserver observer=nativeModeObserver;
        if(observer==null)return;
        if(nativeModesStopped||observer.failure()!=null){
            if(observer.failure()!=null)nativeModeFailure=observer.failure();
            observer.close();
            try{if(observer.awaitStopped(0))return;}catch(InterruptedException error){Thread.currentThread().interrupt();}
        }
        main.postDelayed(this,1000);
    }};
    private volatile Permit permit;
    private volatile long commandEpoch;
    private volatile String status="선택한 휴대폰 연결을 눌러 시작하세요.";
    private volatile String stage="";
    private WeakReference<CarPlayReceiverActivity> view=new WeakReference<CarPlayReceiverActivity>(null);
    private WeakReference<CarPlayReceiverActivity> autoAttempt=new WeakReference<CarPlayReceiverActivity>(null);
    private final AutomaticReconnectPolicy retry=new AutomaticReconnectPolicy(3,1000,8000);
    private SessionServiceController.Snapshot serviceState;
    private boolean retryClaimed,retrySuppressed;private long retryTicket=-1;
    interface ServiceCommands {void send(Intent intent);}
    private ServiceCommands commands=new ServiceCommands(){public void send(Intent intent){if(startService(intent)==null)throw new IllegalStateException("Connection service unavailable");}};
    private PendingStart pendingStart;
    private static final long RECOVERY_WAIT_MILLIS=180000;
    private final class PendingStart {
        final long ticket;final CarPlayReceiverActivity owner;final WirelessCarPlaySettings.Snapshot selected;final boolean automatic;
        final String token=WirelessSession.newOwner();boolean completed;
        final Runnable timeout=new Runnable(){public void run(){if(pendingStart==PendingStart.this){cancelRetry();commandEpoch++;message("이전 연결 정리가 아직 끝나지 않았습니다. 정리 다시 시도를 누르세요.");}}};
        PendingStart(long ticket,CarPlayReceiverActivity owner,WirelessCarPlaySettings.Snapshot selected,boolean automatic){this.ticket=ticket;this.owner=owner;this.selected=selected;this.automatic=automatic;}
    }
    private void clearPendingStart(){if(pendingStart!=null){main.removeCallbacks(pendingStart.timeout);pendingStart=null;}}
    private boolean current(PendingStart request){WirelessCarPlaySettings source=settings;WirelessCarPlaySettings.Snapshot latest=source==null?null:source.snapshot();
        return pendingStart==request&&request.ticket==commandEpoch
            &&(request.automatic?automaticConsent(latest):view.get()==request.owner&&request.owner!=null&&request.owner.foreground())
            &&latest!=null&&latest.usable&&selectionKey(request.selected).equals(selectionKey(latest));
    }
    private static String selectionKey(WirelessCarPlaySettings.Snapshot value){return value==null?null:value.storeId+":"+value.generation+":"+value.phone;}
    private boolean automaticConsent(WirelessCarPlaySettings.Snapshot value){AutomaticConnectionState persisted=automaticState;AutomaticConnectionState.Snapshot state=persisted==null?null:persisted.snapshot();
        return automaticReady&&!retrySuppressed&&value!=null&&value.usable&&value.autoConnect&&value.phone!=null&&persisted!=null
            &&state.usable&&!state.stopped&&selectionKey(value).equals(state.selection);}
    private boolean automaticAllowed(WirelessCarPlaySettings.Snapshot value){return automaticConsent(value)&&automaticState.snapshot().allows(selectionKey(value));}
    private final Runnable retryTick=new Runnable(){public void run(){
        requireMain();
        WirelessCarPlaySettings selected=settings;WirelessCarPlaySettings.Snapshot value=selected==null?null:selected.snapshot();
        long now=SystemClock.elapsedRealtime();retry.update(selectionKey(value),!retrySuppressed&&automaticAllowed(value),now);
        boolean idle=serviceState==null||!serviceState.commandPending&&(serviceState.state==SessionServiceController.State.IDLE||serviceState.state==SessionServiceController.State.CLOSED);
        boolean cleanup=serviceState!=null&&serviceState.state==SessionServiceController.State.CLEANUP_REQUIRED;
        if(pendingStart==null&&!retryClaimed&&retry.claim(now,idle,cleanup)){retryClaimed=true;startSelected(true);}
        if(view.get()!=null||automaticConsent(value)||pendingStart!=null)main.postDelayed(this,250);
    }};
    private void cancelRetry(){requireMain();clearPendingStart();retrySuppressed=true;retry.cancel();retry.finished(SystemClock.elapsedRealtime());retryClaimed=false;retryTicket=-1;}
    private void preflightEnded(final long ticket){main.post(new Runnable(){public void run(){
        if(ticket==commandEpoch&&pendingStart!=null&&pendingStart.ticket==ticket)clearPendingStart();
        if(ticket==commandEpoch&&retryClaimed&&retryTicket==ticket){retry.finished(SystemClock.elapsedRealtime());retryClaimed=false;retryTicket=-1;}
    }});}
    private UiRequest activeUi;private boolean serviceRequested;
    private static final class Permit {
        final String ap,phone,store;final long generation;
        Permit(String ap,WirelessCarPlaySettings.Snapshot selected){this.ap=ap;phone=selected.phone;store=selected.storeId;generation=selected.generation;}
    }
    private final WirelessCarPlaySettings.Listener changed=new WirelessCarPlaySettings.Listener(){
        public void changed(WirelessCarPlaySettings.Snapshot before,WirelessCarPlaySettings.Snapshot after){
            main.post(new Runnable(){public void run(){
                Permit live=permit;WirelessCarPlaySettings source=settings;WirelessCarPlaySettings.Snapshot latest=source==null?null:source.snapshot();
                if(pendingStart!=null&&!current(pendingStart))stopConnection(false);
                if(live!=null&&(latest==null||!latest.usable||!live.store.equals(latest.storeId)||live.generation!=latest.generation||!live.phone.equals(latest.phone)))stopConnection(false);
                if(latest!=null&&latest.usable)wakeAutomatic(false);
            }});
        }
    };
    private final BroadcastReceiver radio=new BroadcastReceiver(){public void onReceive(Context context,Intent intent){
        String action=intent.getAction();
        if(BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)||BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)){
            BluetoothDevice device=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);WirelessCarPlaySettings.Snapshot selected=settings==null?null:settings.snapshot();
            String key=selectionKey(selected);if(key==null||!key.equals(presenceSelection)){presenceSelection=key;selectedPresent=false;deferredPresence=null;}
            if(device==null||selected==null||selected.phone==null||!selected.phone.equals(device.getAddress()))return;
            boolean present=BluetoothDevice.ACTION_ACL_CONNECTED.equals(action);boolean edge=present&&!selectedPresent;selectedPresent=present;
            if(!present)deferredPresence=null;
            if(edge){if(permit==null)wakeAutomatic(true);else deferredPresence=key;}return;
        }
        Permit value=permit;BluetoothRadioHandoff owned=radioHandoff;
        if(BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())&&owned!=null){
            if(owned.stateChanged(intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE,-1),intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,-1)))return;
        }else if(owned!=null)owned.externalChange();
        if(value==null&&owned==null){if(BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)&&intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,-1)==BluetoothAdapter.STATE_ON)wakeAutomatic(false);return;}
        radioEpoch.incrementAndGet();if(value!=null)stopConnection(false);
    }};
    public void onCreate(){super.onCreate();try{ErrorLog.install(new File(getFilesDir(),"diagnostics"));}catch(RuntimeException ignored){}
        IntentFilter filter=new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        registerReceiver(radio,filter);
        startNativeModeObserver();
        main.post(historyPoll);
    }
    /** Read-only OEM observation is process-scoped; a blocked Binder registration
     * must not occupy the receiver/settings worker or the Android main thread. */
    private void startNativeModeObserver(){
        Thread worker=new Thread(new Runnable(){public void run(){
            final AndroidNativeModeObserver[] owner=new AndroidNativeModeObserver[1];
            try{
                owner[0]=AndroidNativeModeObserver.create(HubApplication.this,new NativeModeObserver.Listener(){
                    public void accepted(final NativeModeObserver.Selection selection){main.post(new Runnable(){public void run(){
                        acceptNativeMode(owner[0],selection);
                    }});}
                });
                nativeModeObserver=owner[0]; // Preserve before a possibly ambiguous registration.
                if(nativeModesStopped)owner[0].close();else owner[0].start();
            }catch(IOException error){nativeModeFailure=error;}
             catch(RuntimeException error){nativeModeFailure=new IOException("Native mode observer unavailable",error);}
             catch(LinkageError error){nativeModeFailure=new IOException("Native mode observer API unavailable",error);}
            finally{
                if(owner[0]!=null){if(nativeModesStopped||nativeModeFailure!=null)owner[0].close();main.post(nativeModeHealth);}
            }
        }},"vn-native-mode-open");
        worker.setDaemon(true);
        try{worker.start();}catch(RuntimeException error){nativeModeFailure=new IOException("Native observer worker unavailable",error);}
        catch(LinkageError error){nativeModeFailure=new IOException("Native observer worker API unavailable",error);}
    }
    private void acceptNativeMode(AndroidNativeModeObserver observer,NativeModeObserver.Selection selection){
        requireMain();
        if(nativeModesStopped||observer==null||observer!=nativeModeObserver||nativeModeFailure!=null
            ||!observer.isCurrent(selection)||!selection.screen||selection.requestedAt<=screenChoiceAt)return;
        // A confirmed native screen does not establish the user's music source.
        chooseModesAt(LastUserModes.NATIVE,null,selection.requestedAt);
    }
    private void applyNativeHistory(LastUserModes modes,NativeModeHistory.Snapshot history,long screenRevision,long mediaRevision){
        requireMain();if(nativeModesStopped||modes!=userModes)return;
        if(!modes.applyHistory(history,screenRevision,mediaRevision))return;
        LastUserModes.Snapshot after=modes.snapshot();screenChoiceObserved=after.showScreen();
        // An unobserved disk timestamp may belong to an earlier boot.
        if(after.screenObserved)screenChoiceAt=Math.max(screenChoiceAt,after.screenActionAt);
        UiRequest ui=activeUi;if(ui!=null&&!ui.retiring&&ui.session!=null)ui.session.applyUserModes(true,true);
    }
    public void onTerminate(){nativeModesStopped=true;AndroidNativeModeObserver observer=nativeModeObserver;
        main.removeCallbacks(historyPoll);main.removeCallbacks(historyTimeout);historyWork.shutdownNow();
        if(observer!=null){observer.close();main.removeCallbacks(nativeModeHealth);main.post(nativeModeHealth);}super.onTerminate();}
    private static void requireMain(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Main thread required");}
    private WirelessCarPlaySettings loadSettings()throws IOException{
        WirelessCarPlaySettings value=settings;
        if(value==null){value=WirelessCarPlaySettings.open(this);value.addListener(changed);settings=value;}return value;
    }
    private AutomaticConnectionState loadAutomatic()throws IOException{
        AutomaticConnectionState value=automaticState;if(value==null){value=AndroidAutomaticConnectionStore.open(this);automaticState=value;}return value;
    }
    private synchronized LastUserModes loadUserModes()throws IOException{LastUserModes value=userModes;if(value==null){value=AndroidLastUserModes.open(this);userModes=value;}return value;}
    /** Explicit actions only. Activity lifecycle and temporary audio ownership do not save modes. */
    private void chooseModes(final Integer screen,final Integer media){
        chooseModesAt(screen,media,SystemClock.uptimeMillis());
    }
    private void chooseModesAt(final Integer screen,final Integer media,final long actionAt){
        requireMain();final long ticket=++modeEditEpoch;
        if(screen!=null){screenEditEpoch=ticket;screenChoiceAt=actionAt;}if(media!=null)mediaEditEpoch=ticket;
        if(userModes!=null){applyModeChoice(ticket,screen,media,actionAt);return;}
        work.execute(new Runnable(){public void run(){try{loadUserModes();main.post(new Runnable(){public void run(){applyModeChoice(ticket,screen,media,actionAt);}});
        }catch(IOException error){message("화면·음악 선택을 저장하지 못했습니다.");}}});
    }
    private void applyModeChoice(long ticket,Integer screen,Integer media,long actionAt){requireMain();LastUserModes value=userModes;if(value==null)return;
        Integer s=ticket==screenEditEpoch?screen:null,m=ticket==mediaEditEpoch?media:null;
        if(!value.chooseAt(s,m,actionAt))return;
        if(s!=null)appliedScreenEditEpoch=ticket;if(m!=null)appliedMediaEditEpoch=ticket;UiRequest ui=activeUi;
        if(s!=null)screenChoiceObserved=s==LastUserModes.CARPLAY;
        if(ui==null||ui.retiring||ui.session==null)return;ui.session.applyUserModes(s!=null,m!=null);
    }
    public void chooseNativeScreen(CarPlayReceiverActivity owner){requireMain();if(view.get()==owner)chooseModes(LastUserModes.NATIVE,null);}
    public void chooseMedia(CarPlayReceiverActivity owner,boolean enabled){requireMain();if(view.get()==owner&&owner.foreground())chooseModes(null,enabled?LastUserModes.CARPLAY:LastUserModes.NATIVE);}
    public LastUserModes.Snapshot userModes(){LastUserModes value=userModes;return value==null?null:value.snapshot();}
    /** Wake from an actual startup/presence event, never an unconditional budget reset. */
    public void wakeAutomatic(final boolean presence){
        requireMain();if(wakePending){wakeAgain=true;pendingPresence|=presence;return;}wakePending=true;final long activation=commandEpoch;
        work.execute(new Runnable(){public void run(){boolean valid=false;try{
            WirelessCarPlaySettings selected=loadSettings();selected.refreshBond();WirelessCarPlaySettings.Snapshot latest=selected.snapshot();
            AutomaticConnectionState state=loadAutomatic();state.select(latest.phone==null?"-":selectionKey(latest));
            if(presence&&latest.usable&&latest.autoConnect&&latest.phone!=null&&selected.bluetoothEnabled()&&bonded(selected,latest.phone))state.presence(selectionKey(latest));
            valid=true;
        }catch(Exception error){}catch(LinkageError error){}
            final boolean loaded=valid;main.post(new Runnable(){public void run(){wakePending=false;
                boolean again=wakeAgain,observedPresence=pendingPresence;wakeAgain=pendingPresence=false;
                if(activation!=commandEpoch){if(again)wakeAutomatic(observedPresence);return;}automaticReady=loaded;
                WirelessCarPlaySettings.Snapshot latest=settings==null?null:settings.snapshot();
                if(loaded){AutomaticConnectionState.Snapshot state=automaticState.snapshot();retrySuppressed=state.stopped;
                    if(presence)retry.reset(selectionKey(latest),automaticAllowed(latest),SystemClock.elapsedRealtime());}
                if(automaticConsent(latest)){serviceRequested=true;try{commands.send(serviceIntent(WirelessCarPlayService.ACTION_AUTOMATIC_RETAIN));}catch(RuntimeException unavailable){automaticReady=false;}}
                if(automaticAllowed(latest)){main.removeCallbacks(retryTick);main.post(retryTick);}
                else if(!automaticConsent(latest)&&serviceRequested&&permit==null&&pendingStart==null&&(serviceState==null||!serviceState.commandPending&&serviceState.state==SessionServiceController.State.IDLE)){
                    try{commands.send(serviceIntent(WirelessCarPlayService.ACTION_CLOSE));}catch(RuntimeException ignored){}
                }
                if(again)wakeAutomatic(observedPresence);
            }});
        }});
    }
    public void wirelessAutomaticWake(){requireMain();serviceRequested=true;wakeAutomatic(false);}
    public boolean wirelessKeepAlive(){return automaticConsent(settings==null?null:settings.snapshot());}
    private void message(final String text){main.post(new Runnable(){public void run(){status=text;CarPlayReceiverActivity owner=view.get();if(owner!=null)owner.refreshStatus();}});}
    public String connectionStatus(){String text=UiText.t(status);UiRequest ui=activeUi;if(ui!=null&&ui.session!=null&&ui.session.modeRequestFailure()!=null)text+="\n"+UiText.t("화면·음악 선택을 휴대폰에 전달하지 못했습니다. 다시 선택하세요.");return text;}
    public void attach(CarPlayReceiverActivity owner){
        requireMain();view=new WeakReference<CarPlayReceiverActivity>(owner);if(activeUi!=null)activeUi.attachRenderer(owner);
        if(owner.explicitModeEntry())chooseModes(LastUserModes.CARPLAY,null);
        if(autoAttempt.get()!=owner){autoAttempt=new WeakReference<CarPlayReceiverActivity>(owner);
            final CarPlayReceiverActivity opened=owner;final long activation=commandEpoch;
            work.execute(new Runnable(){public void run(){try{loadSettings().refreshBond();}catch(IOException ignored){}catch(RuntimeException ignored){}
                main.post(new Runnable(){public void run(){if(view.get()!=opened||!opened.foreground()||activation!=commandEpoch)return;
                    WirelessCarPlaySettings.Snapshot selected=settings==null?null:settings.snapshot();
                    wakeAutomatic(false);main.removeCallbacks(retryTick);main.post(retryTick);
                }});
            }});
        }else{main.removeCallbacks(retryTick);main.post(retryTick);}
    }
    public void detach(CarPlayReceiverActivity owner){
        requireMain();paused(owner);if(view.get()==owner)view.clear();
        UiRequest ui=activeUi;if(ui!=null&&ui.owner==owner)ui.detachRenderer();
    }
    /** A pending start is tied to this foreground activation. Existing media
     * keeps its normal pause behavior; a later resume may claim a fresh retry. */
    void paused(CarPlayReceiverActivity owner){requireMain();if(view.get()==owner&&pendingStart!=null&&!pendingStart.automatic){
        clearPendingStart();commandEpoch++;retry.finished(SystemClock.elapsedRealtime());retryClaimed=false;retryTicket=-1;
    }}
    public AndroidReceiverUi uiFor(CarPlayReceiverActivity owner){requireMain();return activeUi!=null&&activeUi.owner==owner&&!activeUi.retiring?activeUi.value:null;}
    public VolumePolicy.Snapshot volumeFor(CarPlayReceiverActivity owner){requireMain();return activeUi!=null&&activeUi.owner==owner&&!activeUi.retiring&&activeUi.session!=null?activeUi.session.audio.volumeSnapshot():null;}
    public boolean setVolume(CarPlayReceiverActivity owner,VolumePolicy.Snapshot expected,int percent){requireMain();return owner.foreground()&&activeUi!=null&&activeUi.owner==owner&&!activeUi.retiring&&activeUi.session!=null&&activeUi.session.audio.setVolume(expected,percent);}
    public void startSelected(final boolean automatic){
        requireMain();final CarPlayReceiverActivity owner=view.get();if(!automatic&&(owner==null||!owner.foreground()))return;
        if(!automatic)chooseModes(LastUserModes.CARPLAY,LastUserModes.CARPLAY);
        if(!automatic){cancelRetry();retrySuppressed=false;WirelessCarPlaySettings.Snapshot current=settings==null?null:settings.snapshot();
            retry.reset(selectionKey(current),automaticAllowed(current),SystemClock.elapsedRealtime());
            retryClaimed=retry.claim(SystemClock.elapsedRealtime(),true,false);
        }
        final WirelessCarPlaySettings.Snapshot expected=settings==null?null:settings.snapshot();
        final long ticket=++commandEpoch;if(retryClaimed)retryTicket=ticket;main.removeCallbacks(retryTick);main.postDelayed(retryTick,250);
        if(expected==null){preflightEnded(ticket);message("휴대폰 선택과 Bluetooth 상태를 확인하세요.");return;}
        clearPendingStart();final PendingStart request=new PendingStart(ticket,owner,expected,automatic);pendingStart=request;
        work.execute(new Runnable(){public void run(){
            try{
                final WirelessCarPlaySettings selected=loadSettings();final WirelessCarPlaySettings.Snapshot snapshot=selected.snapshot();
                if(!selectionKey(expected).equals(selectionKey(snapshot))||!selected.current(expected.storeId,expected.generation,expected.phone))throw new IOException("Selected phone changed before start");
                if(automatic&&(!snapshot.usable||!snapshot.autoConnect||snapshot.phone==null)){preflightEnded(ticket);return;}
                if(!snapshot.usable||snapshot.phone==null)throw new IOException("Select a phone");
                if(automatic){AutomaticConnectionState state=loadAutomatic();state.select(selectionKey(snapshot));if(!state.reserve(selectionKey(snapshot))){preflightEnded(ticket);return;}}
                else{try{AutomaticConnectionState state=loadAutomatic();state.resume(selectionKey(snapshot));automaticReady=true;}catch(IOException unavailable){automaticReady=false;}}
                checkNative();
                main.post(new Runnable(){public void run(){
                    if(!current(request)){preflightEnded(ticket);return;}
                    permit=null;serviceRequested=true;
                    main.postDelayed(request.timeout,RECOVERY_WAIT_MILLIS);
                    try{commands.send(serviceIntent(WirelessCarPlayService.ACTION_RECOVER).putExtra(WirelessCarPlayService.RECOVERY_TOKEN,request.token));}
                    catch(RuntimeException failure){clearPendingStart();preflightEnded(ticket);message("연결 서비스를 시작하지 못했습니다.");}
                }});
            }catch(final NativeUnavailable missing){preflightEnded(ticket);if(ticket==commandEpoch)message("무선 수신기 모듈이 없는 빌드입니다.");}
             catch(Exception failure){preflightEnded(ticket);if(ticket==commandEpoch)message("휴대폰 선택과 Bluetooth 상태를 확인하세요.");}
             catch(LinkageError failure){preflightEnded(ticket);if(ticket==commandEpoch)message("무선 수신기 모듈을 불러오지 못했습니다.");}
        }});
    }
    public void wirelessRecovery(String token,boolean recovered){
        requireMain();final PendingStart request=pendingStart;
        if(request==null||!request.token.equals(token)||request.completed)return;
        request.completed=true;
        if(!recovered||!current(request)){clearPendingStart();preflightEnded(request.ticket);if(!recovered)message("이전 연결 정리가 완료되지 않았습니다. 정리 다시 시도를 누르세요.");return;}
        work.execute(new Runnable(){public void run(){try{
            final WirelessCarPlaySettings selected=loadSettings();selected.refreshBond();final WirelessCarPlaySettings.Snapshot snapshot=selected.snapshot();
            if(!selected.current(request.selected.storeId,request.selected.generation,request.selected.phone)||request.automatic&&!snapshot.autoConnect)throw new IOException("Selected phone permission withdrawn after cleanup");
            if(!selected.bluetoothEnabled()||!bonded(selected,snapshot.phone))throw new IOException("Selected bond unavailable after cleanup");
            checkNative();
            main.post(new Runnable(){public void run(){
                if(!current(request)){if(pendingStart==request)clearPendingStart();preflightEnded(request.ticket);return;}
                clearPendingStart();permit=new Permit(WirelessSession.newOwner(),snapshot);
                Intent intent=serviceIntent(WirelessCarPlayService.ACTION_START).putExtra(WirelessCarPlayService.AP_SESSION,permit.ap).putExtra(WirelessCarPlayService.SELECTED_PHONE,permit.phone);
                try{commands.send(intent);}catch(RuntimeException failure){permit=null;preflightEnded(request.ticket);message("연결 서비스를 시작하지 못했습니다.");}
            }});
        }catch(Exception failure){main.post(new Runnable(){public void run(){if(pendingStart==request){clearPendingStart();preflightEnded(request.ticket);message("휴대폰 선택과 Bluetooth 상태를 확인하세요.");}}});}
         catch(LinkageError failure){main.post(new Runnable(){public void run(){if(pendingStart==request){clearPendingStart();preflightEnded(request.ticket);message("무선 수신기 모듈을 불러오지 못했습니다.");}}});}
        }});
    }
    private static boolean bonded(WirelessCarPlaySettings selected,String phone)throws IOException{
        for(WirelessCarPlaySettings.Device d:selected.bondedDevices())if(phone.equals(d.address))return true;return false;
    }
    private Intent serviceIntent(String action){return new Intent(this,WirelessCarPlayService.class).setAction(action);}
    public void stopConnection(){stopConnection(true);}
    private void stopConnection(boolean manual){requireMain();if(manual)cancelRetry();else {retry.finished(SystemClock.elapsedRealtime());retryClaimed=false;retryTicket=-1;}commandEpoch++;permit=null;
        clearPendingStart();if(manual){persistStopped(WirelessCarPlayService.ACTION_CLOSE);return;}
        if(serviceRequested)try{commands.send(serviceIntent(WirelessCarPlayService.ACTION_STOP));}catch(RuntimeException failure){message("연결 중지를 요청하지 못했습니다.");}
    }
    private void persistStopped(final String action){
        final long ticket=commandEpoch;final WirelessCarPlaySettings.Snapshot expected=settings==null?null:settings.snapshot();
        work.execute(new Runnable(){public void run(){boolean saved=false;try{
            WirelessCarPlaySettings.Snapshot selected=loadSettings().snapshot();
            if(expected==null||selectionKey(expected).equals(selectionKey(selected)))loadAutomatic().stop(selected.phone==null?"-":selectionKey(selected));saved=true;
        }catch(Exception unavailable){}catch(LinkageError unavailable){}
            final boolean durable=saved;main.post(new Runnable(){public void run(){if(ticket!=commandEpoch)return;if(!durable){automaticReady=false;message("자동 연결 중지 상태를 저장하지 못했습니다.");}
                if(serviceRequested)try{commands.send(serviceIntent(action));}catch(RuntimeException failure){message("연결 중지를 요청하지 못했습니다.");}
            }});
        }});
    }
    public void recoverConnection(){requireMain();cancelRetry();commandEpoch++;permit=null;serviceRequested=true;
        try{commands.send(serviceIntent(WirelessCarPlayService.ACTION_RECOVER));}catch(RuntimeException failure){message("정리를 다시 요청하지 못했습니다.");}
    }
    private void closeConnection(){stopConnection(true);}
    public void setApChannel(final int channel){requireMain();if(channel!=6&&channel!=36)throw new IllegalArgumentException("AP channel");closeConnection();
        work.execute(new Runnable(){public void run(){boolean ok=getSharedPreferences("wireless_carplay_ap",MODE_PRIVATE).edit().putInt("channel",channel).commit();message(ok?"Wi-Fi 대역을 저장했습니다. 다시 연결하세요.":"Wi-Fi 대역을 저장하지 못했습니다.");}});
    }
    private static final class NativeUnavailable extends IOException {private static final long serialVersionUID=1L;}
    private void checkNative()throws IOException{
        InputStream input=getAssets().open("wireless/build.properties");ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try{byte[] buffer=new byte[1024];int n;while((n=input.read(buffer))!=-1){if(bytes.size()+n>4096)throw new IOException("Build metadata too large");bytes.write(buffer,0,n);}}finally{input.close();}
        try{
            Properties metadata=new Properties();metadata.load(new ByteArrayInputStream(bytes.toByteArray()));if(!"1".equals(metadata.getProperty("format")))throw new IOException("Receiver metadata format");if(!"1".equals(metadata.getProperty("native_available")))throw new NativeUnavailable();
            File library=new File(getApplicationInfo().nativeLibraryDir,"libvn-carplay-crypto.so");if(!library.isFile()||library.length()>16*1024*1024)throw new NativeUnavailable();
            MessageDigest hash=MessageDigest.getInstance("SHA-256");FileInputStream source=new FileInputStream(library);try{byte[] block=new byte[8192];int n;while((n=source.read(block))!=-1)hash.update(block,0,n);}finally{source.close();}
            StringBuilder hex=new StringBuilder();for(byte b:hash.digest())hex.append(String.format(Locale.US,"%02x",b&255));
            if(!hex.toString().equals(metadata.getProperty("native_sha256")))throw new IOException("Receiver native hash mismatch");
            System.loadLibrary("vn-carplay-crypto");
        }catch(NativeUnavailable missing){throw missing;}catch(Exception failure){throw new IOException("Receiver native unavailable",failure);}
    }
    private ApConfiguration apConfiguration()throws IOException{
        SharedPreferences prefs=getSharedPreferences("wireless_carplay_ap",MODE_PRIVATE);String name=prefs.getString("ssid",null),password=prefs.getString("password",null);
        if(name==null&&password==null){name="VN-CarPlay-"+WirelessSession.newOwner().substring(0,8);password=WirelessSession.newOwner();
            if(!prefs.edit().putString("ssid",name).putString("password",password).commit())throw new IOException("AP configuration persistence failed");}
        if(name==null||password==null)throw new IOException("AP configuration incomplete");char[] secret=password.toCharArray();
        try{return new ApConfiguration(name.getBytes("UTF-8"),secret,prefs.getInt("channel",6));}finally{Arrays.fill(secret,'\0');}
    }
    public SessionServiceController.BackendFactory wirelessBackend(Context applicationContext){
        return new SessionServiceController.BackendFactory(){public SessionServiceController.Backend open()throws IOException{
            checkNative();loadSettings();loadUserModes();if(volumes==null)volumes=VolumeStore.open(HubApplication.this);final File root=new File(getFilesDir(),"wireless_receiver");if(!root.isDirectory()&&!root.mkdir())throw new IOException("Receiver storage unavailable");new Api17DirectorySync().sync(getFilesDir());
            ReceiverProductConfiguration.Spec spec=new ReceiverProductConfiguration.Spec("Veloster N CarPlay","VN-API17","Veloster N Dashboard",
                "JSN-tcc893x-preview",Locale.getDefault().getLanguage(),ReceiverProductConfiguration.ProtocolProfile.experimentalDiPlay(),800,480,30,false,null);
            // Source-specific initialization probes run inside the backend attempt,
            // after its exact AP/phone scope exists. No buffer-size-only input claim.
            AudioCapabilities baseAudio=ReceiverAudioProfiles.create(0,0);
            ApConfiguration config=apConfiguration();boolean handed=false;
            try{IndependentSessionBackend backend=new IndependentSessionBackend(HubApplication.this,root,new ApManagerClient(),config,spec,
                new IndependentSessionBackend.RadioSelection(){
                    public IndependentSessionBackend.Phone open(String ap,String phone)throws IOException{return selectedPhone(ap,phone);}
                    public void checkRadioRecovery()throws IOException{Api17BluetoothRadio.recover(root);}
                },
                new IndependentSessionBackend.UiHost(){public IndependentSessionBackend.UiLease open(ReceiverScope scope,TouchHid touch)throws IOException,InterruptedException{return openUi(scope,touch);}},
                baseAudio,new IndependentSessionBackend.Progress(){public void stage(IndependentSessionBackend.Stage value){stage=value.name();}},
                new IndependentSessionBackend.CompressedAudioProfiles(){
                    public AudioCapabilities create(long speech,long call)throws IOException{return ReceiverAudioProfiles.create(speech,call);}
                    public AudioCapabilities create(long speech,long call,long output)throws IOException{return ReceiverAudioProfiles.create(speech,call,output);}
                });handed=true;return backend;
            }finally{if(!handed)config.close();}
        }};
    }
    private IndependentSessionBackend.Phone selectedPhone(final String ap,final String phone)throws IOException{
        final WirelessCarPlaySettings selected=loadSettings();final Permit approved=permit;final long epoch=radioEpoch.get();
        if(approved==null||!ap.equals(approved.ap)||!phone.equals(approved.phone)||!selected.current(approved.store,approved.generation,phone)||!selected.bluetoothEnabled()||!bonded(selected,phone))throw new IOException("Selected phone permission withdrawn");
        if(epoch!=radioEpoch.get())throw new IOException("Bluetooth changed during preparation");
        return new IndependentSessionBackend.RadioPhone(){private volatile boolean closed;private SelectedPhoneBluetoothHandoff handoff;private BluetoothRadioHandoff wholeRadio;
            private synchronized SelectedPhoneBluetoothHandoff handoff()throws IOException{
                checkCurrent();if(handoff==null)handoff=Api17SelectedPhoneBluetooth.open(ap,phone,approved.generation,new SelectedPhoneBluetoothHandoff.Current(){
                    public void checkCurrent(String expectedAp,String expectedPhone,long generation)throws IOException{
                        if(!ap.equals(expectedAp)||!phone.equals(expectedPhone)||approved.generation!=generation)throw new IOException("Foreign Bluetooth teardown");
                        currentSelection();
                    }
                },5000);return handoff;
            }
            private void currentSelection()throws IOException{if(closed||permit!=approved||radioEpoch.get()!=epoch||!selected.current(approved.store,approved.generation,phone))throw new IOException("Selected phone generation withdrawn");}
            public long generation(){return approved.generation;}
            public void checkCurrent()throws IOException{currentSelection();}
            public Iap2WirelessController.Permission permission()throws IOException{checkCurrent();return Iap2WirelessController.Permission.ALLOW;}
            public boolean disableBluetoothRequested()throws IOException{checkCurrent();return false;}
            public boolean radioDisabled()throws IOException{checkCurrent();BluetoothRadioHandoff value=wholeRadio;return value!=null&&value.radioDisabled();}
            public synchronized boolean disableRadio(ReceiverScope scope,int channel,BluetoothRadioHandoff.Readiness readiness)throws IOException{
                checkCurrent();readiness.checkReady(scope);
                if(wholeRadio==null){if(radioHandoff!=null)throw new IOException("Previous Bluetooth radio cleanup required");
                    wholeRadio=Api17BluetoothRadio.open(new File(getFilesDir(),"wireless_receiver"),ap,phone,approved.generation,new BluetoothRadioHandoff.Current(){
                        public void checkCurrent(String expectedAp,String expectedPhone,long generation)throws IOException{
                            if(!ap.equals(expectedAp)||!phone.equals(expectedPhone)||approved.generation!=generation)throw new IOException("Foreign radio handoff");currentSelection();
                        }
                    },5000);radioHandoff=wholeRadio;
                }
                return wholeRadio.disable(scope,channel,readiness);
            }
            public boolean profilesDisconnected()throws IOException{checkCurrent();return handoff().profilesDisconnected();}
            public boolean disconnectProfiles()throws IOException{checkCurrent();return handoff().disconnectProfiles();}
            public Iap2RuntimeController.Listener runtimeListener()throws IOException{checkCurrent();return new Iap2RuntimeController.Listener(){
                public void state(Iap2RuntimeController.State value)throws IOException{checkCurrent();}
                public void update(Iap2Csm.Frame frame)throws IOException{checkCurrent();}
                public void availability(boolean available)throws IOException{checkCurrent();}
            };}
            public synchronized void close()throws IOException{closed=true;if(handoff!=null)handoff.close();
                if(wholeRadio!=null){wholeRadio.close();if(radioHandoff==wholeRadio)radioHandoff=null;wholeRadio=null;}
            }
        };
    }
    public void wirelessStatus(SessionServiceController.Snapshot snapshot){
        requireMain();serviceState=snapshot;
        if(permit!=null&&permit.ap.equals(snapshot.requestApSession)&&!snapshot.commandPending&&snapshot.state==SessionServiceController.State.IDLE){
            if(retryClaimed&&retryTicket==commandEpoch)retry.finished(SystemClock.elapsedRealtime());retryClaimed=false;retryTicket=-1;permit=null;
            if(selectedPresent&&deferredPresence!=null&&deferredPresence.equals(selectionKey(settings==null?null:settings.snapshot()))){deferredPresence=null;wakeAutomatic(true);}
        }
        String text;
        if(snapshot.state==SessionServiceController.State.CLEANUP_REQUIRED)text="정리가 완료되지 않았습니다. Bluetooth가 꺼져 있으면 직접 켠 뒤 정리 다시 시도를 누르세요.";
        else if(snapshot.failed)text="연결 준비에 실패했습니다. 설정과 설치 상태를 확인하세요.";
        else switch(snapshot.state){
            case OPENING:case RECOVERING:case PREPARING:case STARTING:text="무선 연결을 준비하고 있습니다.";break;
            case RUNNING:text="휴대폰의 CarPlay 연결을 기다리고 있습니다.";break;
            case STOPPING:case CLOSING:text="연결 자원을 정리하고 있습니다.";break;
            case CLOSED:text="연결 서비스를 종료했습니다.";serviceRequested=false;break;
            default:text=automaticConsent(settings==null?null:settings.snapshot())?"휴대폰 자동 연결을 기다리고 있습니다.":"연결이 중지되었습니다.";
        }
        status=text;CarPlayReceiverActivity owner=view.get();if(owner!=null)owner.refreshStatus();
    }
    public Notification wirelessNotification(Context context,SessionServiceController.Snapshot snapshot){
        requireMain();PendingIntent open=PendingIntent.getActivity(this,45,new Intent(this,CarPlayReceiverActivity.class),PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle(UiText.t("무선 CarPlay")).setContentText(UiText.t(status)).setContentIntent(open).setOngoing(true).build();
    }
    private IndependentSessionBackend.UiLease openUi(ReceiverScope scope,TouchHid touch)throws IOException,InterruptedException{
        UiRequest request=new UiRequest(scope,touch);if(!main.post(request))throw new IOException("UI dispatcher unavailable");
        try{if(!request.ready.await(5000,TimeUnit.MILLISECONDS))throw new IOException("Receiver UI preparation timed out");
            if(request.failure!=null)throw request.failure;if(request.lease==null)throw new IOException("Receiver UI unavailable");return request.lease;
        }catch(IOException failure){request.cancelled=true;throw new SessionCoordinator.FactoryCleanupException("Receiver UI cleanup required",request,failure);}
         catch(InterruptedException failure){request.cancelled=true;throw new SessionCoordinator.FactoryCleanupException("Receiver UI interrupted",request,failure);}
    }
    private final class UiRequest implements Runnable,Closeable {
        final ReceiverScope scope;final TouchHid touch;final CountDownLatch ready=new CountDownLatch(1);
        volatile boolean cancelled,retiring;volatile IOException failure;volatile IndependentSessionBackend.UiLease lease;
        CarPlayReceiverActivity owner;AndroidReceiverUi value;AndroidReceiverSession session;boolean closed,screenLaunchPending;
        UiRequest(ReceiverScope scope,TouchHid touch){this.scope=scope;this.touch=touch;}
        public void run(){try{
            Permit permission=permit;
            if(cancelled||activeUi!=null||permission==null||!permission.ap.equals(scope.apSession)||!permission.phone.equals(scope.selectedBluetooth))throw new IOException("Receiver selection unavailable");
            session=new AndroidReceiverSession(HubApplication.this,scope,touch,new AndroidReceiverSession.PresentationEvents(){
                private void stop(final boolean manual){if(!retiring)main.post(new Runnable(){public void run(){if(activeUi==UiRequest.this&&!retiring)stopConnection(manual);}});}
                public void failed(IOException error){stop(false);}public void stopRequested(){stop(true);}
                public void screenAvailable(){
                    if(retiring||closed||activeUi!=UiRequest.this||screenLaunchPending||!screenChoiceObserved||view.get()!=null||userModes==null||userModes.snapshot().screen!=LastUserModes.CARPLAY)return;
                    screenLaunchPending=true;try{startActivity(new Intent(HubApplication.this,CarPlayReceiverActivity.class).putExtra("restore_user_screen",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
                    catch(RuntimeException error){screenLaunchPending=false;message("CarPlay 화면을 열지 못했습니다.");}
                }
                public void nativeScreenRequested(){if(activeUi==UiRequest.this){chooseModes(LastUserModes.NATIVE,null);if(owner!=null)owner.finish();}}
            },AndroidApprovalUi.Labels.localized(),volumes,userModes,new OptionalOemAlternateAudioFactory(HubApplication.this,OEM_SERVICES_JAR_SHA256));
            session.setHardwareSiriSource(new AndroidOemSiriProvider(HubApplication.this,OEM_SERVICES_JAR_SHA256));activeUi=this;
            CarPlayReceiverActivity shown=view.get();if(shown!=null&&shown.foreground())attachRenderer(shown);
            lease=new IndependentSessionBackend.UiLease(session){public void close()throws IOException{UiRequest.this.close();}};
        }catch(RuntimeException error){failure=new IOException("Receiver view initialization failed",error);}
         catch(LinkageError error){failure=new IOException("Receiver view API unavailable",error);}
         catch(IOException error){failure=error;}
         finally{ready.countDown();}}
        void attachRenderer(CarPlayReceiverActivity next){requireMain();if(closed||retiring||session==null||!next.foreground())return;if(owner==next&&value!=null)return;
            screenLaunchPending=false;detachRenderer();owner=next;session.setScreenEnabled(true);value=session.attach(next,next.surface,next.host);value.onResume();value.onWindowFocusChanged(next.hasWindowFocus());}
        void detachRenderer(){requireMain();if(value!=null)value.close();value=null;owner=null;if(session!=null)session.setScreenEnabled(false);}
        void closeOnMain(){requireMain();if(closed)return;retiring=true;if(session!=null)session.close();value=null;owner=null;closed=true;if(activeUi==this)activeUi=null;}
        public void close()throws IOException{
            cancelled=true;final CountDownLatch done=new CountDownLatch(1);final IOException[] error={null};
            if(!main.post(new Runnable(){public void run(){try{closeOnMain();}catch(RuntimeException e){error[0]=new IOException("UI cleanup failed",e);}catch(LinkageError e){error[0]=new IOException("UI cleanup API unavailable",e);}finally{done.countDown();}}}))throw new IOException("UI cleanup dispatch failed");
            try{if(!done.await(5000,TimeUnit.MILLISECONDS))throw new IOException("UI cleanup still pending");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("UI cleanup interrupted",e);}if(error[0]!=null)throw error[0];
        }
    }
}
