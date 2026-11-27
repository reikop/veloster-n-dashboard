package com.veloster.vehicle;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;

/** Passive subscription to the same OEM broadcasts consumed by the home widget. Shared with Neon Drive. */
public final class MediaReceiver extends BroadcastReceiver {
    private static final String MODE_CHANGED="com.hkmc.intent.action.MODE_CHANGED";
    private final MediaState state;
    private Context registered;
    public MediaReceiver(MediaState state){this.state=state;}
    public void start(Context context){
        if(registered!=null)return;
        IntentFilter filter=new IntentFilter();for(String action:MediaState.ACTIONS)filter.addAction(action);
        filter.addAction(MODE_CHANGED);
        try{
            Intent sticky=context.registerReceiver(this,filter);registered=context;
            // If a producer sent its widget update sticky, the last one comes back here: show the current track at once.
            // (Whether the OEM producers use sticky broadcasts is unverified; for normal broadcasts this is null.)
            if(sticky!=null)onReceive(context,sticky);
            for(String action:MediaState.ACTIONS){Intent last=context.registerReceiver(null,new IntentFilter(action));if(last!=null&&last!=sticky)onReceive(context,last);}
            try{
                Class<?> type=Class.forName("com.hkmc.mode.ModeManager");
                Object manager=type.getConstructor(Context.class).newInstance(context);
                state.mode(((Integer)type.getMethod("getCurrentMode").invoke(manager)).intValue());
            }catch(Exception e){android.util.Log.i("VehicleDash","Initial media mode unavailable; waiting for mode broadcast");}
        }
        catch(RuntimeException e){android.util.Log.e("VehicleDash","Media receiver unavailable",e);}
    }
    public void stop(){
        if(registered==null)return;
        try{registered.unregisterReceiver(this);}
        catch(RuntimeException e){android.util.Log.e("VehicleDash","Media receiver detach failed",e);}
        finally{registered=null;}
    }
    public void onReceive(Context context,Intent intent){
        if(intent==null)return;
        if(MODE_CHANGED.equals(intent.getAction())){
            if(intent.hasExtra("com.hkmc.mode.EXTRA_MODE_TYPE"))state.mode(intent.getIntExtra("com.hkmc.mode.EXTRA_MODE_TYPE",-1));
            return;
        }
        int source=MediaState.source(intent.getAction());if(source<0)return;
        try{
            // Android Auto uses start_time/end_time; the other widget producers use audio_*.
            String position=source==4?"start_time":"audio_current_time";
            String duration=source==4?"end_time":"audio_total_time";
            state.update(source,intent.hasExtra("filename"),intent.getStringExtra("filename"),
                intent.hasExtra("artist"),intent.getStringExtra("artist"),
                intent.hasExtra(position),intent.getLongExtra(position,-1),
                intent.hasExtra(duration),intent.getLongExtra(duration,-1),
                intent.getBooleanExtra("invisible",false),SystemClock.elapsedRealtime());
            state.artwork(source,intent.hasExtra("artwork_uri"),intent.getStringExtra("artwork_uri"),
                intent.getByteArrayExtra("artwork_raw_image"),intent.getBooleanExtra("default",false));
            if(source==1&&intent.hasExtra("playing_state"))state.playback(source,intent.getBooleanExtra("playing_state",false)?1:0);
            else if(source==4&&intent.hasExtra("state")){
                int status=intent.getIntExtra("state",0);state.playback(source,status==2?1:status==1?0:-1);
            }else if(source!=1&&source!=4&&intent.hasExtra("playstate")){
                // OEM partial widget broadcasts use -1 for an omitted field.
                int status=intent.getIntExtra("playstate",-1);if(status==0||status==1)state.playback(source,status);
            }
        }catch(RuntimeException e){android.util.Log.e("VehicleDash","Malformed media update",e);}
    }
}
