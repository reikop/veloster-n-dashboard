/* SPDX-License-Identifier: GPL-3.0-only. Optional renderer for a service-owned receiver. */
package com.veloster.carplay.discovery;
import android.app.Activity;
import android.os.Looper;
import android.view.*;
import com.veloster.carplay.control.ReceiverScope;
import com.veloster.carplay.input.*;
import com.veloster.carplay.media.*;
import com.veloster.carplay.pairing.PairVerify;
import java.io.IOException;

/** Legacy constructors own the session and retain close->stopRequested. Handles
 * returned by session.attach own only a renderer; closing one never retires the
 * service-owned audio, receiver or authenticated input-channel lifetime. */
public final class AndroidReceiverUi {
    public interface Events {void failed(IOException failure);void stopRequested();}
    public final AndroidReceiverSession session;
    public final ScreenOutputs screens;public final AudioPresentation audio;public final AndroidApprovalUi approval;
    public final ReceiverTouchInput input;public final ReceiverSiriInput sessionControls;
    final Activity activity;final SurfaceView surface;final View host;private final boolean ownsSession;
    boolean resumed,closed;private boolean registered,finalClose;
    private final SurfaceHolder.Callback callback=new SurfaceHolder.Callback(){
        public void surfaceCreated(SurfaceHolder holder){session.surfaceChanged(AndroidReceiverUi.this);}
        public void surfaceChanged(SurfaceHolder holder,int format,int width,int height){session.surfaceChanged(AndroidReceiverUi.this);}
        public void surfaceDestroyed(SurfaceHolder holder){session.surfaceDestroyed(AndroidReceiverUi.this);}
    };
    public AndroidReceiverUi(Activity activity,PairVerify.Context context,SurfaceView surface,View host,Events events){this(activity,context,surface,host,events,AndroidApprovalUi.Labels.localized());}
    public AndroidReceiverUi(Activity activity,PairVerify.Context context,SurfaceView surface,View host,Events events,AndroidApprovalUi.Labels labels){this(activity,ReceiverScope.from(context),context,surface,host,events,labels,null);}
    public AndroidReceiverUi(Activity activity,ReceiverScope scope,SurfaceView surface,View host,Events events,AndroidApprovalUi.Labels labels){this(activity,scope,null,surface,host,events,labels,null);}
    public AndroidReceiverUi(Activity activity,ReceiverScope scope,SurfaceView surface,View host,Events events,AndroidApprovalUi.Labels labels,TouchHid touch){this(activity,scope,null,surface,host,events,labels,touch);}
    private AndroidReceiverUi(Activity activity,ReceiverScope scope,PairVerify.Context context,SurfaceView surface,View host,Events events,AndroidApprovalUi.Labels labels,TouchHid touch){
        this(new AndroidReceiverSession(activity,scope,touch,events,labels,context,true),activity,surface,host,true);session.install(this);
    }
    AndroidReceiverUi(AndroidReceiverSession session,Activity activity,SurfaceView surface,View host,boolean owns){
        if(session==null||activity==null||surface==null||host==null)throw new NullPointerException();
        this.session=session;this.activity=activity;this.surface=surface;this.host=host;ownsSession=owns;
        screens=session.screens;audio=session.audio;input=session.input;sessionControls=session.sessionControls;approval=session.approvalFor(activity);
    }
    void bindCallbacks(){surface.getHolder().addCallback(callback);registered=true;}
    void retireRenderer(){resumed=false;closed=true;if(registered){surface.getHolder().removeCallback(callback);registered=false;}surface.setVisibility(View.INVISIBLE);host.setVisibility(View.VISIBLE);}
    public void onResume(){session.resumed(this);}
    public void onPause(){session.paused(this);}
    public void onWindowFocusChanged(boolean focused){session.focused(this,focused);}
    public boolean siriAvailable(){return session.siriAvailable(this);}
    public boolean siriPulse(){return session.siriPulse(this);}
    public boolean siriPress(){return session.siriPress(this);}
    public boolean siriRelease(){return session.siriRelease(this);}
    public PresentationPolicy.Status status(){return session.status();}
    public void close(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Receiver presentation requires main thread");
        if(!ownsSession){session.detach(this);return;}
        if(finalClose){session.close();return;} // retry a failed final close
        if(closed)return; // a replaced legacy renderer has no final-close claim
        finalClose=true;try{session.requestStop();}finally{session.close();}
    }
}
