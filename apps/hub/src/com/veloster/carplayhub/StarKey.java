package com.veloster.carplayhub;

import com.veloster.i18n.UiText;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * ☆ (user button) short press → dashboard. Adapted from the i30 PD build (sung9155/i30-pd-dashboard, MIT).
 * The OEM ModeManager (com.hkmc.mode.ModeManager, automotive.jar; getCustomKey/setCustomKey are present in the JSN
 * framework) keeps the button's action in persist.sys.key.custom and, for an action outside its own list, sends it as a
 * broadcast; this receiver catches ours. Opening the stock button settings (long press) resets an unlisted action, so
 * status reads the live value and nothing turns it back on behind the user's back. Only the switch in My Apps writes it.
 */
public final class StarKey extends BroadcastReceiver {
    public void onReceive(Context context,Intent intent){
        if(intent==null||!QuickEntryPolicy.STAR_ACTION.equals(intent.getAction()))return;
        Intent dash=new Intent(Intent.ACTION_MAIN).setClassName("com.veloster.dashboard","com.veloster.dashboard.DashboardActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try{context.startActivity(dash);}catch(RuntimeException e){Log.w("VNCarPlayHub","star key: dashboard not opened",e);}
    }
    static boolean on(Context context){try{return QuickEntryPolicy.STAR_ACTION.equals(get(context));}catch(Exception e){return false;}}
    static String status(Context context){
        try{return QuickEntryPolicy.STAR_ACTION.equals(get(context))?UiText.t("켜짐 (짧게 누르면 계기판)"):UiText.t("꺼짐");}
        catch(Exception e){return UiText.t("확인 불가");}
    }
    /** On: remember the current action, then point ☆ at us. Off: put the remembered action back, only if ☆ is still ours. */
    static boolean set(Context context,boolean on)throws Exception{
        SharedPreferences prefs=context.getSharedPreferences("star_key",Context.MODE_PRIVATE);
        String now=get(context);
        if(on){
            if(!QuickEntryPolicy.STAR_ACTION.equals(now))prefs.edit().putString("previous",now).commit();
            return put(context,QuickEntryPolicy.STAR_ACTION);
        }
        return !QuickEntryPolicy.STAR_ACTION.equals(now)||put(context,QuickEntryPolicy.starRestore(prefs.getString("previous",null)));
    }
    private static Object manager(Context context)throws Exception{   // the OEM keeps the first Context in a singleton
        return Class.forName("com.hkmc.mode.ModeManager").getConstructor(Context.class).newInstance(context.getApplicationContext());
    }
    private static String get(Context context)throws Exception{
        Object manager=manager(context);return (String)manager.getClass().getMethod("getCustomKey").invoke(manager);
    }
    private static boolean put(Context context,String action)throws Exception{
        Object manager=manager(context);
        return Boolean.TRUE.equals(manager.getClass().getMethod("setCustomKey",String.class).invoke(manager,action))&&action.equals(get(context));
    }
}
