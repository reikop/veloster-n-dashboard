/* SPDX-License-Identifier: GPL-3.0-only. Service-owned receiver; optional Activity renderer. */
package com.veloster.carplay.discovery;
import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.os.Looper;
import android.os.Handler;
import android.view.*;
import com.veloster.carplay.control.*;
import com.veloster.carplay.input.*;
import com.veloster.carplay.media.*;
import com.veloster.carplay.pairing.PairVerify;
import java.io.Closeable;
import java.io.IOException;

/** Main-thread creation/disposal. Audio, verified input factories and screens
 * survive Activity replacement. Runtime workers must drain before final close.
 * No Activity or enrollment approval is invented for a headless receiver. */
public final class AndroidReceiverSession implements Closeable {
    public final ScreenOutputs screens;public final AudioPresentation audio;
    public final ControlConnection.ApprovalUi approval;
    public final ReceiverTouchInput input;public final ReceiverSiriInput sessionControls;
    private final ReceiverScope scope;private final PairVerify.Context pairingContext;
    private final AndroidReceiverUi.Events events;private final AndroidApprovalUi.Labels labels;
    private final ModePresentation presentation;private final ApprovalRouter approvals=new ApprovalRouter();
    private AndroidReceiverUi renderer,retiring;private boolean closing,closed,screenEnabled,phoneScreenVisible;
    private LastUserModes lastUserModes;
    private ReceiverModeInput modeInput;private volatile ModeControl modeOwner;
    private ReceiverHardwareSiriSource hardwareControls;
    private final Handler main=new Handler(Looper.getMainLooper());
    public interface PresentationEvents extends AndroidReceiverUi.Events {void screenAvailable();void nativeScreenRequested();}
    /** Explicit app choices only. Legacy callers retain the absent /info modes field. */
    public ModeChanges initialModes(){return lastUserModes==null?null:lastUserModes.snapshot().modes();}
    public IapSetupHandler.SessionControlFactory sessionControlFactory(){return hardwareControls!=null?hardwareControls:modeInput==null?sessionControls:modeInput;}
    /** Configure the optional, pinned OEM source before handing the session to
     * ReceiverRuntime. Opening/renewing it runs on a tracked verified worker. */
    public void setHardwareSiriSource(ReceiverHardwareSiriSource.Provider provider){requireMain();
        if(closing||closed||hardwareControls!=null)throw new IllegalStateException("Physical Siri source already configured or disposed");
        hardwareControls=new ReceiverHardwareSiriSource(modeInput==null?sessionControls:modeInput,sessionControls,provider);
    }
    public IOException hardwareSiriFailure(){return hardwareControls==null?null:hardwareControls.failure();}
    public IOException modeRequestFailure(){return modeInput==null?null:modeInput.failure();}
    public AndroidReceiverSession(Context context,ReceiverScope scope,TouchHid touch,AndroidReceiverUi.Events events,AndroidApprovalUi.Labels labels){this(context,scope,touch,events,labels,null,false,null);}
    /** Store must already be loaded on its worker and is owned by the app, not this session. */
    public AndroidReceiverSession(Context context,ReceiverScope scope,TouchHid touch,AndroidReceiverUi.Events events,AndroidApprovalUi.Labels labels,VolumeStore volumes){this(context,scope,touch,events,labels,null,false,volumes);}
    public AndroidReceiverSession(Context context,ReceiverScope scope,TouchHid touch,AndroidReceiverUi.Events events,AndroidApprovalUi.Labels labels,VolumeStore volumes,LastUserModes modes){
        this(context,scope,touch,events,labels,volumes,modes,new Api17AudioOutput.Factory());
    }
    /** Output construction and optional OEM checks run later on audio workers. */
    public AndroidReceiverSession(Context context,ReceiverScope scope,TouchHid touch,AndroidReceiverUi.Events events,AndroidApprovalUi.Labels labels,VolumeStore volumes,LastUserModes modes,AudioSink.Factory outputs){
        this(context,scope,touch,events,labels,null,false,volumes,outputs);if(modes==null)throw new NullPointerException();lastUserModes=modes;
        modes.claim(this);modeInput=new ReceiverModeInput(scope,modes,sessionControls);audio.setMediaEnabled(modes.snapshot().playMedia());
    }
    AndroidReceiverSession(Context context,ReceiverScope scope,TouchHid touch,final AndroidReceiverUi.Events events,AndroidApprovalUi.Labels labels,PairVerify.Context pairingContext,boolean legacy){
        this(context,scope,touch,events,labels,pairingContext,legacy,null);
    }
    private AndroidReceiverSession(Context context,ReceiverScope scope,TouchHid touch,final AndroidReceiverUi.Events events,AndroidApprovalUi.Labels labels,PairVerify.Context pairingContext,boolean legacy,VolumeStore volumes){
        this(context,scope,touch,events,labels,pairingContext,legacy,volumes,new Api17AudioOutput.Factory());
    }
    private AndroidReceiverSession(Context context,ReceiverScope scope,TouchHid touch,final AndroidReceiverUi.Events events,AndroidApprovalUi.Labels labels,PairVerify.Context pairingContext,boolean legacy,VolumeStore volumes,AudioSink.Factory outputs){
        requireMain();if(context==null||scope==null||events==null||labels==null||outputs==null)throw new NullPointerException();Context app=context.getApplicationContext();if(app==null)app=context;
        this.scope=scope;this.events=events;this.labels=labels;this.pairingContext=pairingContext;screenEnabled=legacy;approval=approvals;
        screens=new ScreenOutputs(false,new ScreenOutputs.Listener(){public void keyframeNeeded(int type,VideoSink sink){}public void stopped(int type,VideoSink sink,IOException error){if(error!=null)events.failed(error);}});
        audio=volumes==null?new AudioPresentation(outputs):new AudioPresentation(outputs,volumes);
        input=touch==null?null:new ReceiverTouchInput(scope,touch,new ReceiverTouchInput.Failure(){public void failed(IOException error){events.failed(error);}});
        sessionControls=new ReceiverSiriInput(scope,new ReceiverSiriInput.Failure(){public void failed(IOException error){events.failed(error);}});sessionControls.foreground(false);sessionControls.windowFocus(false);
        presentation=new ModePresentation(new ModePresentation.Screen(){public void showCarPlay(boolean visible){phoneScreenVisible=visible;render();}},(AudioManager)app.getSystemService(Context.AUDIO_SERVICE),audio,new ModePresentation.Failure(){public void failed(ModeControl owner,RuntimeException error){events.failed(new IOException("Receiver presentation failed",error));}});
        audio.attach(new AudioReadiness.Roles(){
            public void opened(ModeControl owner)throws IOException{modeOwner=owner;presentation.opened(owner);if(input!=null)input.opened(owner);}
            public void changed(ModeControl owner,ModeState state)throws IOException{presentation.changed(owner,state);if(input!=null)input.changed(owner,state);}
            public boolean hostUiRequested(final ModeControl owner)throws IOException{
                if(lastUserModes==null){boolean accepted=presentation.hostUiRequested(owner);if(accepted&&input!=null)input.hostUiRequested(owner);return accepted;}
                final long screenRevision=lastUserModes.snapshot().screenRevision;
                return main.post(new Runnable(){public void run(){if(closing||closed||modeOwner!=owner||lastUserModes.snapshot().screenRevision!=screenRevision)return;
                    try{if(!presentation.hostUiRequested(owner))return;if(input!=null)input.hostUiRequested(owner);
                        chooseScreen(false);if(events instanceof PresentationEvents)((PresentationEvents)events).nativeScreenRequested();
                    }catch(IOException error){events.failed(error);}}});}
            public void closed(ModeControl owner){if(modeOwner==owner)modeOwner=null;presentation.closed(owner);if(input!=null)input.closed(owner);}
            public void audioReady(ModeControl owner,boolean ready)throws IOException{presentation.audioReady(owner,ready);}
            public void audioReady(ModeControl owner,boolean mainReady,boolean alternateReady)throws IOException{presentation.audioReady(owner,mainReady,alternateReady);}
        });
    }
    private static void requireMain(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Receiver presentation requires main thread");}
    private final class ApprovalRouter implements ControlConnection.ApprovalUi {
        volatile AndroidApprovalUi current;
        public void request(ControlConnection.Approval request){AndroidApprovalUi target=current;if(target==null)request.reject();else target.request(request);}
        public void dismiss(ControlConnection.Approval request){AndroidApprovalUi target=current;if(target!=null)target.dismiss(request);}
    }
    AndroidApprovalUi approvalFor(Activity activity){return pairingContext==null?new AndroidApprovalUi(activity,scope,labels):new AndroidApprovalUi(activity,pairingContext,labels);}
    /** Attaching alone does not resume the renderer or enable screen/input. */
    public AndroidReceiverUi attach(Activity activity,SurfaceView surface,View host){
        requireMain();if(closing||closed)throw new IllegalStateException("Receiver session disposed");
        if(renderer!=null&&renderer.activity==activity&&renderer.surface==surface&&renderer.host==host)return renderer;
        AndroidReceiverUi next=new AndroidReceiverUi(this,activity,surface,host,false);install(next);return next;
    }
    void install(AndroidReceiverUi next){requireMain();if(closing||closed)throw new IllegalStateException("Receiver session disposed");if(retiring!=null)detach(retiring);if(renderer!=null)detach(renderer);renderer=next;approvals.current=next.approval;if(input!=null)input.attach(next.surface);next.bindCallbacks();render();}
    public void detach(AndroidReceiverUi expected){
        requireMain();if(renderer!=expected&&retiring!=expected)return;
        if(renderer==expected){approvals.current=null;renderer=null;retiring=expected;}
        // Revoke capability use before callbacks. Keep the retiring handle until
        // all Android listener/window cleanup returns so failure can be retried.
        sessionControls.foreground(false);sessionControls.windowFocus(false);screens.setOutput(110,null);
        if(input!=null){input.foreground(false);if(!closing)input.attach(null);}expected.approval.close();expected.retireRenderer();retiring=null;
    }
    void resumed(AndroidReceiverUi expected){requireMain();if(!current(expected))return;expected.resumed=true;expected.approval.foreground(true);if(input!=null){input.foreground(true);input.windowFocus(expected.surface.hasWindowFocus());}sessionControls.foreground(true);sessionControls.windowFocus(expected.surface.hasWindowFocus());bind(expected);render();}
    void paused(AndroidReceiverUi expected){requireMain();if(!current(expected))return;expected.resumed=false;expected.approval.foreground(false);if(input!=null)input.foreground(false);sessionControls.foreground(false);screens.setOutput(110,null);render();}
    void focused(AndroidReceiverUi expected,boolean focused){requireMain();if(!current(expected))return;if(input!=null)input.windowFocus(focused);sessionControls.windowFocus(focused);}
    void surfaceChanged(AndroidReceiverUi expected){requireMain();if(current(expected))bind(expected);}
    void surfaceDestroyed(AndroidReceiverUi expected){requireMain();if(!current(expected))return;if(input!=null)input.surfaceAvailable(false);screens.setOutput(110,null);}
    private boolean current(AndroidReceiverUi expected){return !closing&&!closed&&renderer==expected&&!expected.closed;}
    private void bind(AndroidReceiverUi expected){Surface value=expected.surface.getHolder().getSurface();boolean valid=current(expected)&&expected.resumed&&screenEnabled&&value!=null&&value.isValid();screens.setOutput(110,valid?new Api17SurfaceOutput(value):null);if(input!=null)input.surfaceAvailable(valid);}
    private void render(){if(closing||closed)return;AndroidReceiverUi target=renderer;if(target!=null){boolean show=screenEnabled&&target.resumed&&phoneScreenVisible;target.surface.setVisibility(show?View.VISIBLE:View.INVISIBLE);target.host.setVisibility(show?View.INVISIBLE:View.VISIBLE);}
        if(phoneScreenVisible&&events instanceof PresentationEvents)((PresentationEvents)events).screenAvailable();}
    public boolean chooseScreen(boolean carplay){requireMain();if(closing||closed||lastUserModes==null||!lastUserModes.choose(this,carplay?LastUserModes.CARPLAY:LastUserModes.NATIVE,null))return false;
        if(carplay){try{presentation.phoneUiRequested(modeOwner);if(input!=null)input.phoneUiRequested(modeOwner);}catch(IOException error){events.failed(error);return false;}}setScreenEnabled(carplay);return true;}
    public boolean chooseMedia(boolean carplay){requireMain();if(closing||closed||lastUserModes==null||!lastUserModes.choose(this,null,carplay?LastUserModes.CARPLAY:LastUserModes.NATIVE))return false;audio.setMediaEnabled(carplay);return true;}
    /** Apply already-recorded app choices or trusted producer history without
     * manufacturing a new user action, timestamp or persistent checkpoint. */
    public boolean applyUserModes(boolean screen,boolean media){
        requireMain();if(closing||closed||lastUserModes==null||!lastUserModes.owns(this))return false;
        LastUserModes.Snapshot choice=lastUserModes.snapshot();
        if(screen){if(choice.showScreen())try{presentation.phoneUiRequested(modeOwner);if(input!=null)input.phoneUiRequested(modeOwner);}
            catch(IOException error){events.failed(error);return false;}setScreenEnabled(choice.showScreen());}
        if(media)audio.setMediaEnabled(choice.playMedia());return true;
    }
    public void setScreenEnabled(boolean enabled){requireMain();if(closed)return;screenEnabled=enabled;if(renderer!=null)bind(renderer);render();}
    /** MEDIA source preference only: call/Siri/navigation/ringtone retain their
     * authenticated ownership and focus gates. Headless defaults to media on. */
    public void setPresentationEnabled(boolean screen,boolean media){requireMain();if(closed)return;audio.setMediaEnabled(media);setScreenEnabled(screen);}
    public PresentationPolicy.Status status(){return presentation.status();}
    boolean siriAvailable(AndroidReceiverUi expected){requireMain();return current(expected)&&expected.resumed&&sessionControls.available();}
    boolean siriPulse(AndroidReceiverUi expected){requireMain();return current(expected)&&expected.resumed&&sessionControls.pulse();}
    boolean siriPress(AndroidReceiverUi expected){requireMain();return current(expected)&&expected.resumed&&sessionControls.press();}
    boolean siriRelease(AndroidReceiverUi expected){requireMain();return current(expected)&&expected.resumed&&sessionControls.release();}
    /** Called by the verified vehicle-button adapter on main, without requiring
     * a renderer. The returned press owns its original encrypted session only. */
    public ReceiverSiriInput.HardwarePress hardwareSiriPress(){requireMain();return closing||closed?null:sessionControls.pressHardware();}
    public boolean hardwareSiriAvailable(){requireMain();return !closing&&!closed&&sessionControls.hardwareAvailable();}
    void requestStop(){events.stopRequested();}
    public void close(){requireMain();if(closed)return;closing=true;modeOwner=null;approvals.current=null;if(lastUserModes!=null)lastUserModes.release(this);if(modeInput!=null)modeInput.close();RuntimeException failure=null;
        try{if(renderer!=null)detach(renderer);if(retiring!=null)detach(retiring);}catch(RuntimeException e){failure=e;}
        try{if(input!=null)input.close();}catch(RuntimeException e){if(failure==null)failure=e;}
        try{sessionControls.close();}catch(RuntimeException e){if(failure==null)failure=e;}
        try{audio.close();}catch(RuntimeException e){if(failure==null)failure=e;}
        try{screens.close();}catch(RuntimeException e){if(failure==null)failure=e;}
        try{presentation.close();}catch(RuntimeException e){if(failure==null)failure=e;}
        if(failure!=null)throw failure;closed=true;
    }
}
