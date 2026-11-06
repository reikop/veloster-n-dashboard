/* SPDX-License-Identifier: GPL-3.0-only. Mode/session/Surface-owned Android touch integration. */
package com.veloster.carplay.input;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import com.veloster.carplay.control.*;
import com.veloster.carplay.pairing.PairVerify;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** One Android receiver generation. Main-thread view lifecycle; nonblocking mode
 * callbacks; session checks run only on the binding's worker. Input workers are
 * returned to IapSetupHandler, so receiver shutdown must drain them explicitly. */
public final class ReceiverTouchInput implements IapSetupHandler.InputSinkFactory,ModeControl.Listener,Closeable {
    public interface Failure {void failed(IOException failure);}
    private SurfaceView view;private final ReceiverScope scope;private final TouchHid hid;private final Failure failure;
    private final Handler main=new Handler(Looper.getMainLooper());private final Object lock=new Object();
    private Binding current,shown;private AndroidTouchInput adapter;private ModeControl accepted;
    private ModeState mode=ModeState.unknown();private boolean hostRequested,queued,closed,resumed,focused=true,surfaceReady;
    private boolean manualViewport;private float left,top,right,bottom;private int rotation;
    private final View.OnLayoutChangeListener layout=new View.OnLayoutChangeListener(){
        public void onLayoutChange(View v,int l,int t,int r,int b,int oldL,int oldT,int oldR,int oldB){updateUi();}
    };
    public ReceiverTouchInput(SurfaceView view,ReceiverScope scope,TouchHid hid,Failure failure){
        this(scope,hid,failure);if(view==null)throw new NullPointerException();attach(view);
    }
    /** Service-owned sender with no Activity or View until a renderer attaches. */
    public ReceiverTouchInput(ReceiverScope scope,TouchHid hid,Failure failure){
        requireMain();if(scope==null||hid==null||failure==null)throw new NullPointerException();
        this.scope=scope;this.hid=hid;this.failure=failure;
    }
    public void attach(SurfaceView next){
        requireMain();synchronized(lock){if(closed)throw new IllegalStateException("Receiver touch disposed");if(view==next)return;
            if(current!=null){current.input.setEnabled(false);current.configured=false;}
            if(adapter!=null)adapter.detach();adapter=null;shown=null;
            if(view!=null)view.removeOnLayoutChangeListener(layout);view=next;resumed=false;focused=false;surfaceReady=false;manualViewport=false;rotation=0;
            if(view!=null)view.addOnLayoutChangeListener(layout);
        }updateUi();
    }
    private static void requireMain(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Receiver touch UI requires main thread");}
    // A faulty application callback must not orphan the registered worker or skip drain.
    private void notifyFailure(IOException error){try{failure.failed(error);}catch(RuntimeException ignored){}catch(LinkageError ignored){}}
    public TouchHid declaration(){return hid;}
    private void queueLocked()throws IOException{if(queued||closed)return;queued=true;if(!main.post(drain)){queued=false;throw new IOException("Touch UI queue stopped");}}
    private final Runnable drain=new Runnable(){public void run(){synchronized(lock){queued=false;}updateUi();}};
    private final Runnable poll=new Runnable(){public void run(){updateUi();}};
    /** Called on the control worker after event creation, before the SETUP response. */
    public ReceiverWorkers.Worker open(PairVerify.VerifiedSession session,ModeControl modes,EncryptedEvents events,ReceiverMetadata metadata)throws IOException{
        if(session==null||modes==null||events==null||metadata==null||metadata.touchInput()!=hid||!scope.contains(session.context()))throw new IOException("Receiver touch scope/declaration mismatch");
        synchronized(lock){if(closed)throw new IOException("Receiver touch disposed");}
        EncryptedEvents.TouchChannel channel=events.touchChannel(session,metadata);Binding next;
        try{next=new Binding(session,modes,channel);}catch(RuntimeException error){channel.close();throw new IOException("Receiver touch allocation failed",error);}
        Binding old;IOException queueFailure=null;
        synchronized(lock){old=current;if(!closed){current=next;try{queueLocked();}catch(IOException error){queueFailure=error;next.close();}}else next.close();}
        if(old!=null)old.close();next.start();
        // Once a worker exists, return it even when cancellation raced allocation. The handler owns its drain.
        if(queueFailure!=null)notifyFailure(queueFailure);return next;
    }
    public void opened(ModeControl owner)throws IOException{
        if(owner==null)throw new NullPointerException();synchronized(lock){if(closed)throw new IOException("Receiver touch disposed");
            accepted=owner;mode=ModeState.unknown();hostRequested=false;if(current!=null)current.input.setEnabled(false);queueLocked();}
    }
    public void changed(ModeControl owner,ModeState state)throws IOException{
        if(state==null)throw new NullPointerException();synchronized(lock){if(closed||owner==null||owner!=accepted)return;
            if(mode.screen!=state.screen&&state.screen==ModeState.PHONE)hostRequested=false;mode=state;
            if(current!=null&&(state.screen!=ModeState.PHONE||hostRequested))current.input.setEnabled(false);queueLocked();}
    }
    public boolean hostUiRequested(ModeControl owner)throws IOException{
        synchronized(lock){if(closed||owner==null||owner!=accepted)return false;hostRequested=true;if(current!=null)current.input.setEnabled(false);queueLocked();return true;}
    }
    public void phoneUiRequested(ModeControl owner)throws IOException{
        synchronized(lock){if(closed||owner==null||owner!=accepted)return;hostRequested=false;queueLocked();}
    }
    public void closed(ModeControl owner){
        Binding retire=null;synchronized(lock){if(owner==null||owner!=accepted)return;accepted=null;mode=ModeState.unknown();hostRequested=false;
            if(current!=null&&current.modes==owner)retire=current;try{queueLocked();}catch(IOException ignored){}}
        if(retire!=null)retire.close();
    }
    public void foreground(boolean value){requireMain();synchronized(lock){resumed=value;if(!value&&current!=null)current.input.setEnabled(false);}updateUi();}
    public void windowFocus(boolean value){requireMain();synchronized(lock){focused=value;if(!value&&current!=null)current.input.setEnabled(false);}updateUi();}
    public void surfaceAvailable(boolean value){requireMain();synchronized(lock){surfaceReady=value;if(!value&&current!=null)current.input.setEnabled(false);}updateUi();}
    /** Explicit rectangle for a renderer that crops/letterboxes/rotates inside the SurfaceView. */
    public void viewport(float l,float t,float r,float b,int degrees){
        requireMain();if(Float.isNaN(l)||Float.isInfinite(l)||Float.isNaN(t)||Float.isInfinite(t)||Float.isNaN(r)||Float.isInfinite(r)||Float.isNaN(b)||Float.isInfinite(b)||r<=l||b<=t||degrees!=0&&degrees!=90&&degrees!=180&&degrees!=270)throw new IllegalArgumentException("Receiver touch viewport");
        synchronized(lock){manualViewport=true;left=l;top=t;right=r;bottom=b;rotation=degrees;}updateUi();
    }
    /** Default for the direct Surface renderer: its entire View maps to the declared display. */
    public void fullSurface(){requireMain();synchronized(lock){manualViewport=false;rotation=0;}updateUi();}
    public boolean enabled(){requireMain();synchronized(lock){return shown!=null&&shown.input.enabled();}}
    private void updateUi(){
        requireMain();main.removeCallbacks(poll);
        try{synchronized(lock){
            Binding target=!closed&&view!=null&&current!=null&&!current.closing&&current.modes==accepted?current:null;
            if(shown!=target){if(adapter!=null)adapter.detach();adapter=null;shown=target;
                if(target!=null)adapter=new AndroidTouchInput(view,target.input);}
            if(target!=null){float l=manualViewport?left:0,t=manualViewport?top:0,r=manualViewport?right:view.getWidth(),b=manualViewport?bottom:view.getHeight();
                boolean geometry=r>l&&b>t;
                if(geometry&&(!target.configured||target.left!=l||target.top!=t||target.right!=r||target.bottom!=b||target.rotation!=rotation)){
                    adapter.viewport(l,t,r,b,rotation);target.left=l;target.top=t;target.right=r;target.bottom=b;target.rotation=rotation;target.configured=true;
                }
                Surface surface=view.getHolder().getSurface();boolean enable=geometry&&resumed&&focused&&surfaceReady&&view.isShown()&&view.hasWindowFocus()&&surface!=null&&surface.isValid()&&
                    target.identified&&mode.screen==ModeState.PHONE&&!hostRequested;
                if(enable!=target.input.enabled())adapter.setEnabled(enable);
            }
            if(!closed&&view!=null&&current!=null)main.postDelayed(poll,100);
        }}catch(RuntimeException error){try{notifyFailure(new IOException("Receiver touch UI failed",error));}finally{close();}}
    }
    public void close(){
        requireMain();Binding retire;synchronized(lock){if(closed)return;closed=true;accepted=null;retire=current;current=null;}
        if(retire!=null)retire.close();main.removeCallbacks(drain);main.removeCallbacks(poll);if(view!=null)view.removeOnLayoutChangeListener(layout);
        if(adapter!=null)adapter.detach();adapter=null;shown=null;view=null;
    }
    private final class Binding implements ReceiverWorkers.Worker,Runnable {
        final PairVerify.VerifiedSession session;final ModeControl modes;final TouchInput input;
        final CountDownLatch stopped=new CountDownLatch(1);volatile boolean closing;boolean identified,configured,started;
        float left,top,right,bottom;int rotation;
        Binding(PairVerify.VerifiedSession session,ModeControl modes,EncryptedEvents.TouchChannel channel){this.session=session;this.modes=modes;input=new TouchInput(channel);}
        void start(){
            if(started)return;started=true;Thread worker=new Thread(this,"vn-receiver-touch-owner");worker.setDaemon(true);
            try{worker.start();}catch(RuntimeException e){close();notifyFailure(new IOException("Touch owner worker unavailable",e));stopped.countDown();}
        }
        public void run(){
            try{while(!closing){
                boolean allow=session.mediaAllowed(session.binding());IOException failed=input.failure();if(failed!=null)throw new IllegalStateException("Touch sender failed",failed);
                synchronized(lock){if(closing)break;if(identified!=allow){identified=allow;if(!allow)input.setEnabled(false);queueLocked();}lock.wait(100);}
            }}catch(IOException ended){/* Session/AP revocation retires only this binding. */}
            catch(InterruptedException e){Thread.currentThread().interrupt();if(!closing)notifyFailure(new IOException("Touch owner interrupted",e));}
            catch(RuntimeException error){if(!closing)notifyFailure(new IOException("Touch sender lifecycle failed",error));}
            finally{close();boolean done=false;while(!done){try{done=input.awaitStopped(100);}catch(InterruptedException e){Thread.interrupted();}}
                synchronized(lock){if(current==this)current=null;try{queueLocked();}catch(IOException ignored){}}stopped.countDown();}
        }
        public void close(){synchronized(lock){if(closing)return;closing=true;identified=false;input.setEnabled(false);input.close();lock.notifyAll();try{queueLocked();}catch(IOException ignored){}}}
        public boolean awaitStopped(long millis)throws InterruptedException{
            if(millis<0)throw new IllegalArgumentException("Wait bound");long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
            if(!stopped.await(millis,TimeUnit.MILLISECONDS))return false;long left=Math.max(0,TimeUnit.NANOSECONDS.toMillis(end-System.nanoTime()));return input.awaitStopped(left);
        }
    }
}
