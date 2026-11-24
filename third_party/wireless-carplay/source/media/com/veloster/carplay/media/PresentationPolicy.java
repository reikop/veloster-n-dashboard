/* SPDX-License-Identifier: GPL-3.0-only. Main-thread screen and media-focus policy. */
package com.veloster.carplay.media;

/** Platform-independent policy. All calls, including focus callbacks, belong to its creating
 * thread. Each focus attempt has its own identity, so late callbacks cannot unmute a successor. */
public final class PresentationPolicy {
    public interface Effects {void showCarPlay(boolean visible);void audioGain(Object owner,float gain);}
    public interface Focus {
        interface Callback {void changed(int change);}
        boolean request(Callback callback);
        void abandon(Callback callback);
    }
    public interface RoleFocus extends Focus {boolean request(Callback callback,boolean call);}
    public static final int MEDIA=0,CALL=1,ALTERNATE=2;
    public interface AudioRoleFocus extends Focus {boolean request(Callback callback,int role);}
    public static final int GAIN=1,LOSS=-1,TRANSIENT=-2,DUCK=-3;
    public static final class Status {
        public final boolean screenVisible,audioReady,focusRequested;
        public final float gain;public final String focus;public final int attempts;
        Status(boolean screen,boolean ready,boolean requested,float gain,String focus,int attempts){
            screenVisible=screen;audioReady=ready;focusRequested=requested;this.gain=gain;this.focus=focus;this.attempts=attempts;
        }
    }
    private final Thread thread=Thread.currentThread();private final Effects effects;private final Focus focus;
    private Object owner;private long audioEpoch;private Ticket ticket;private boolean attempted,ready,visible;
    private float gain=Float.NaN;private String state="idle";private int attempts;
    private int role;
    private final class Ticket implements Focus.Callback {
        boolean called;
        public void changed(int change){
            checkThread();if(ticket!=this)return;called=true;
            if(change==GAIN){state="granted";volume(1);}
            else if(change==DUCK){state="ducked";volume(.2f);}
            else if(change==TRANSIENT){state="transient-loss";volume(0);}
            else{release(false);state="lost";attempted=true;}
        }
    }
    public PresentationPolicy(Effects effects,Focus focus){if(effects==null||focus==null)throw new NullPointerException();this.effects=effects;this.focus=focus;}
    private void checkThread(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Presentation thread");}
    private void volume(float next){if(gain!=next){gain=next;effects.audioGain(owner,next);}}
    private void release(boolean retry){
        volume(0);Ticket old=ticket;ticket=null;if(old!=null)focus.abandon(old);
        if(retry){attempted=false;state="idle";}
    }
    /** audioEpoch changes at every ownership/readiness boundary, even if UI work was coalesced. */
    public void apply(Object owner,int screenOwner,int audioOwner,boolean audioReady,boolean hostRequested,long audioEpoch){
        apply(owner,screenOwner,audioOwner,audioReady,hostRequested,audioEpoch,false);
    }
    public void apply(Object owner,int screenOwner,int audioOwner,boolean audioReady,boolean hostRequested,long audioEpoch,boolean call){
        apply(owner,screenOwner,audioOwner,audioReady,false,hostRequested,audioEpoch,call);
    }
    public void apply(Object owner,int screenOwner,int audioOwner,boolean mainReady,boolean alternateReady,boolean hostRequested,long audioEpoch,boolean call){
        checkThread();
        int nextRole=audioOwner==1&&mainReady?(call?CALL:MEDIA):ALTERNATE;
        if(this.owner!=owner||this.audioEpoch!=audioEpoch||role!=nextRole){release(true);this.owner=owner;this.audioEpoch=audioEpoch;role=nextRole;}
        ready=owner!=null&&(mainReady||alternateReady);boolean show=owner!=null&&screenOwner==1&&!hostRequested;
        // Always establish the host view on the first update, including unknown ownership.
        effects.showCarPlay(show);visible=show;
        boolean want=owner!=null&&(audioOwner==1&&mainReady||alternateReady);
        if(!want){release(true);return;}
        if(attempted)return;attempted=true;attempts++;state="requesting";volume(0);
        Ticket request=new Ticket();ticket=request;
        boolean granted=focus instanceof AudioRoleFocus?((AudioRoleFocus)focus).request(request,role):focus instanceof RoleFocus?((RoleFocus)focus).request(request,role==CALL):focus.request(request);
        if(ticket!=request)return; // A synchronous permanent loss already retired this attempt.
        if(!granted){release(false);state="denied";}
        else if(!request.called){state="granted";volume(1);}
    }
    public void reset(){apply(null,0,0,false,false,0);}
    public Status status(){checkThread();return new Status(visible,ready,ticket!=null,gain,state,attempts);}
}
