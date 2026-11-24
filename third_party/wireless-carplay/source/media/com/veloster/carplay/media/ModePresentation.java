/* SPDX-License-Identifier: GPL-3.0-only. API17 mode-driven view and media audio focus adapter. */
package com.veloster.carplay.media;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import com.veloster.carplay.control.ModeControl;
import com.veloster.carplay.control.ModeState;
import java.io.IOException;
import java.util.IdentityHashMap;

/** Construct/close on the main thread. Network callbacks only replace a bounded mailbox.
 * SurfaceHolder callbacks bind/unbind the existing ScreenOutputs; visibility never closes the
 * media session. This adapter manages media focus, not OEM call/Siri routing or PCM decoding. */
public final class ModePresentation implements AudioReadiness.Roles {
    /** Optional renderer; audio focus belongs to the session independently. */
    public interface Screen {void showCarPlay(boolean visible);}
    public interface AudioGate {
        /** Apply to the owned player's output, not global stream volume. Called on main thread. */
        void gain(ModeControl owner,float gain);
    }
    public interface Failure {void failed(ModeControl control,RuntimeException failure);}
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Object lock=new Object();private final PresentationPolicy policy;private final Failure failure;
    private ModeControl accepted;private ModeState mode=ModeState.unknown();private boolean audioReady,hostRequested,queued,closed;
    private long audioEpoch;private volatile PresentationPolicy.Status status;
    private boolean mainReady,alternateReady;
    private final IdentityHashMap<PresentationPolicy.Focus.Callback,AudioManager.OnAudioFocusChangeListener> focusListeners=new IdentityHashMap<PresentationPolicy.Focus.Callback,AudioManager.OnAudioFocusChangeListener>();
    public ModePresentation(final View carplay,final View host,final AudioManager audio,final AudioGate gate,Failure failure){
        this(views(carplay,host),audio,gate,failure);
    }
    private static Screen views(final View carplay,final View host){
        if(carplay==null||host==null)throw new NullPointerException();
        return new Screen(){public void showCarPlay(boolean visible){carplay.setVisibility(visible?View.VISIBLE:View.INVISIBLE);host.setVisibility(visible?View.INVISIBLE:View.VISIBLE);}};
    }
    public ModePresentation(final Screen screen,final AudioManager audio,final AudioGate gate,Failure failure){
        requireMain();if(screen==null||audio==null||gate==null||failure==null)throw new NullPointerException();this.failure=failure;
        policy=new PresentationPolicy(new PresentationPolicy.Effects(){
            public void showCarPlay(boolean visible){screen.showCarPlay(visible);}
            public void audioGain(Object owner,float value){gate.gain((ModeControl)owner,value);}
        },new PresentationPolicy.AudioRoleFocus(){
            public boolean request(final Callback callback){
                return request(callback,PresentationPolicy.MEDIA);
            }
            public boolean request(final Callback callback,int role){
                AudioManager.OnAudioFocusChangeListener listener=new AudioManager.OnAudioFocusChangeListener(){public void onAudioFocusChange(final int value){
                    Runnable update=new Runnable(){public void run(){
                        try{callback.changed(value);status=policy.status();}catch(RuntimeException e){failed(e);}
                    }};
                    if(Looper.myLooper()==Looper.getMainLooper())update.run();else if(!main.post(update))failed(new IllegalStateException("Audio callback queue stopped"));
                }};
                focusListeners.put(callback,listener);
                int gain=role==PresentationPolicy.CALL?AudioManager.AUDIOFOCUS_GAIN_TRANSIENT:role==PresentationPolicy.ALTERNATE?AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK:AudioManager.AUDIOFOCUS_GAIN;
                return audio.requestAudioFocus(listener,AudioManager.STREAM_MUSIC,gain)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            }
            public void abandon(Callback callback){AudioManager.OnAudioFocusChangeListener listener=focusListeners.remove(callback);if(listener!=null)audio.abandonAudioFocus(listener);}
        });
        policy.reset();status=policy.status();
    }
    private static void requireMain(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Main thread required");}
    private void queue()throws IOException{
        if(queued)return;queued=true;if(!main.post(drain)){queued=false;throw new IOException("Presentation queue stopped");}
    }
    private final Runnable drain=new Runnable(){public void run(){
        ModeControl owner;ModeState state;boolean main,alternate,host;long epoch;
        synchronized(lock){queued=false;owner=accepted;state=mode;main=mainReady;alternate=alternateReady;host=hostRequested;epoch=audioEpoch;}
        try{policy.apply(owner,state.screen,state.audio,main,alternate,host,epoch,state.phoneCall==ModeState.PHONE&&state.audio==ModeState.PHONE);status=policy.status();}
        catch(RuntimeException e){failed(e);}
    }};
    public void opened(ModeControl control)throws IOException{
        if(control==null)throw new NullPointerException();
        synchronized(lock){if(closed)throw new IOException("Presentation disposed");accepted=control;mode=ModeState.unknown();audioReady=mainReady=alternateReady=false;hostRequested=false;audioEpoch++;queue();}
    }
    public void changed(ModeControl control,ModeState state)throws IOException{
        synchronized(lock){if(control==null||closed||accepted!=control)return;
            if(mode.audio!=state.audio)audioEpoch++;
            if(mode.screen!=state.screen&&state.screen==ModeState.PHONE)hostRequested=false;
            mode=state;queue();}
    }
    public boolean hostUiRequested(ModeControl control)throws IOException{
        synchronized(lock){if(control==null||closed||accepted!=control)return false;hostRequested=true;queue();return true;}
    }
    /** Explicit local return, including unchanged PHONE ownership/empty ACK. */
    public void phoneUiRequested(ModeControl control)throws IOException{
        synchronized(lock){if(control==null||closed||accepted!=control)return;hostRequested=false;queue();}
    }
    /** Call from the real output stream's start/stop lifecycle. Merely owning audio is insufficient. */
    public void audioReady(ModeControl control,boolean ready)throws IOException{
        audioReady(control,ready,false);
    }
    public void audioReady(ModeControl control,boolean main,boolean alternate)throws IOException{
        synchronized(lock){if(control==null||closed||accepted!=control)return;if(mainReady!=main||alternateReady!=alternate){mainReady=main;alternateReady=alternate;audioReady=main||alternate;audioEpoch++;queue();}}
    }
    /** Explicit user retry after denied/permanent loss; unrelated mode updates do not steal focus. */
    public void retryAudio(ModeControl control)throws IOException{
        synchronized(lock){if(control==null||closed||accepted!=control)return;audioEpoch++;queue();}
    }
    public void closed(ModeControl control){
        RuntimeException error=null;
        synchronized(lock){if(control==null||accepted!=control)return;accepted=null;audioReady=mainReady=alternateReady=false;hostRequested=false;audioEpoch++;
            try{queue();}catch(IOException e){error=new IllegalStateException(e);}}
        if(error!=null)failure.failed(control,error);
    }
    public PresentationPolicy.Status status(){return status;}
    private void failed(RuntimeException error){
        ModeControl owner;synchronized(lock){owner=accepted;accepted=null;closed=true;audioReady=mainReady=alternateReady=false;audioEpoch++;}
        if(Looper.myLooper()==Looper.getMainLooper()){
            try{policy.reset();status=policy.status();}catch(RuntimeException ignored){}
        }
        failure.failed(owner,error);
    }
    public void close(){requireMain();synchronized(lock){closed=true;accepted=null;audioReady=mainReady=alternateReady=false;audioEpoch++;}main.removeCallbacks(drain);policy.reset();status=policy.status();}
}
