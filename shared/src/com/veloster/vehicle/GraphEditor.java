package com.veloster.vehicle;

import com.veloster.i18n.UiText;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;

/** Local graph selection and time window only; no vehicle setting commands. */
public final class GraphEditor {
    private final Activity activity;
    private final GraphLayout layout;
    private final SharedPreferences prefs;
    private final Runnable changed;
    private AlertDialog dialog;
    public GraphEditor(Activity a,GraphLayout l,Runnable invalidate){
        activity=a;layout=l;changed=invalidate;prefs=a.getSharedPreferences("nmode_vehicle_graphs",0);
        try{layout.window(prefs.getInt("window",60000));}catch(IllegalArgumentException e){}catch(ClassCastException e){}
        for(int i=0;i<GraphLayout.SLOTS;i++)try{layout.signal(i,prefs.getInt("signal_"+i,layout.signal(i)));}catch(IllegalArgumentException e){}catch(ClassCastException e){}
    }
    public void dismiss(){AlertDialog old=dialog;dialog=null;if(old!=null)old.dismiss();}
    private void save(){SharedPreferences.Editor e=prefs.edit().putInt("window",(int)layout.window());for(int i=0;i<GraphLayout.SLOTS;i++)e.putInt("signal_"+i,layout.signal(i));e.apply();changed.run();}
    public void window(long value){dismiss();layout.window(value);save();}
    public void edit(final int slot){
        if(slot<0||slot>=GraphLayout.SLOTS||activity.isFinishing())return;
        dismiss();final AlertDialog next=new AlertDialog.Builder(activity).setTitle(UiText.t("그래프 ")+(slot+1)+UiText.t(" · 표시 항목"))
            .setSingleChoiceItems(UiText.list(Telemetry.LABELS),layout.signal(slot),new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int signal){layout.signal(slot,signal);save();d.dismiss();}})
            .setNegativeButton(UiText.t("취소"),null).create();
        dialog=next;next.setOnDismissListener(new DialogInterface.OnDismissListener(){public void onDismiss(DialogInterface d){if(dialog==next)dialog=null;}});next.show();
    }
}
