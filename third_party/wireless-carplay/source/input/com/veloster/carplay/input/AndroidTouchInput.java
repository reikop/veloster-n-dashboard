/* SPDX-License-Identifier: GPL-3.0-only. API17 MotionEvent adapter; UI callbacks enqueue only. */
package com.veloster.carplay.input;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import java.io.Closeable;

/** Bind to the actual CarPlay content view on the main thread. The host supplies
 * its displayed content rectangle/rotation, and enables only when CarPlay owns
 * this visible display and phone identity has been admitted. Call setEnabled(false)
 * on mode/visibility/focus loss, and close on view replacement/session teardown. */
public final class AndroidTouchInput implements View.OnTouchListener,View.OnAttachStateChangeListener,Closeable {
    private final View view;private final TouchInput input;private boolean closed;
    public AndroidTouchInput(View view,TouchInput input){
        main();if(view==null||input==null)throw new NullPointerException();this.view=view;this.input=input;
        view.setOnTouchListener(this);view.addOnAttachStateChangeListener(this);
    }
    private static void main(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Touch binding requires main thread");}
    public void viewport(float left,float top,float right,float bottom,int rotation){main();if(!closed)input.configure(left,top,right,bottom,rotation);}
    public void setEnabled(boolean enabled){main();if(!closed)input.setEnabled(enabled);}
    public boolean onTouch(View source,MotionEvent event){
        main();if(closed||source!=view||event==null)return false;
        if(!view.isShown()||!view.hasWindowFocus()){input.setEnabled(false);return false;}
        return dispatch(input,event);
    }
    static boolean dispatch(TouchInput input,MotionEvent event){
        int action=event.getActionMasked(),count=event.getPointerCount(),at=event.getActionIndex();
        int[] ids=new int[count];float[] xs=new float[count],ys=new float[count];
        for(int i=0;i<count;i++){ids[i]=event.getPointerId(i);xs[i]=event.getX(i);ys[i]=event.getY(i);}
        return input.event(action,at<count?ids[at]:-1,ids,xs,ys);
    }
    public void onViewAttachedToWindow(View attached){}
    public void onViewDetachedFromWindow(View detached){if(!closed&&detached==view)input.setEnabled(false);}
    /** Renderer replacement revokes local input without retiring the authenticated
     * sender. Its receiver Binding remains responsible for close and drain. */
    public void detach(){main();if(closed)return;closed=true;input.setEnabled(false);view.setOnTouchListener(null);view.removeOnAttachStateChangeListener(this);}
    public void close(){main();detach();input.close();}
}
