package com.veloster.vehicle;

import com.veloster.i18n.UiText;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;

/** Local display customization only. Uses the same card geometry as GaugeRenderer. */
public final class GaugeEditor {
    private final Activity activity;
    private final GaugeLayout layout;
    private final Runnable invalidate;
    private final SharedPreferences prefs;
    private AlertDialog dialog;
    public void dismiss(){AlertDialog previous=dialog;dialog=null;if(previous!=null)previous.dismiss();}
    private void show(AlertDialog.Builder builder){
        dismiss();
        if(activity.isFinishing())return;
        final AlertDialog next=builder.create();dialog=next;
        next.setOnDismissListener(new DialogInterface.OnDismissListener(){public void onDismiss(DialogInterface d){if(dialog==next)dialog=null;}});
        next.show();
    }
    public GaugeEditor(Activity a,GaugeLayout l,Runnable changed){
        activity=a;layout=l;invalidate=changed;prefs=a.getSharedPreferences("nmode_vehicle_gauges",0);
        try{layout.dense(prefs.getInt("dense",0)==1);}catch(ClassCastException e){/* Keep default grid. */}
        for(int i=0;i<GaugeLayout.SLOTS;i++){
            try{layout.set(i,prefs.getInt("signal_"+i,l.signal(i)),prefs.getInt("style_"+i,l.style(i)));}
            catch(IllegalArgumentException e){/* Corrupt selection: retain this slot's default. */}
            catch(ClassCastException e){/* Preferences from an incompatible version. */}
        }
    }
    public int hit(float x,float y){return layout.hit(x,y);}
    public void toggleGrid(){dismiss();layout.dense(!layout.dense());save();}
    private void save(){SharedPreferences.Editor e=prefs.edit();e.putInt("dense",layout.dense()?1:0);for(int i=0;i<GaugeLayout.SLOTS;i++){e.putInt("signal_"+i,layout.signal(i));e.putInt("style_"+i,layout.style(i));}e.apply();invalidate.run();}
    public void edit(final int slot){
        if(slot<0||slot>=layout.count()||activity.isFinishing())return;
        show(new AlertDialog.Builder(activity).setTitle(UiText.t("게이지 ")+(slot+1)+" · "+UiText.t(Telemetry.LABELS[layout.signal(slot)]))
            .setItems(new String[]{UiText.t("표시 항목"), UiText.t("게이지 모양"), UiText.t("다른 칸과 위치 바꾸기"), UiText.t("전체 기본 배치로 복원")},new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){
                if(which==0)source(slot);else if(which==1)style(slot);else if(which==2)swap(slot);else reset();
            }}).setNegativeButton(UiText.t("닫기"),null));
    }
    private void source(final int slot){
        show(new AlertDialog.Builder(activity).setTitle(UiText.t("표시 항목")).setSingleChoiceItems(UiText.list(Telemetry.LABELS),layout.signal(slot),new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int n){layout.set(slot,n,layout.style(slot));save();d.dismiss();}}).setNegativeButton(UiText.t("취소"),null));
    }
    private void style(final int slot){
        show(new AlertDialog.Builder(activity).setTitle(UiText.t("게이지 모양")).setSingleChoiceItems(new String[]{UiText.t("원형"), UiText.t("막대"), UiText.t("숫자")},layout.style(slot),new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int n){layout.set(slot,layout.signal(slot),n);save();d.dismiss();}}).setNegativeButton(UiText.t("취소"),null));
    }
    private void swap(final int slot){
        String[] names=new String[layout.count()];for(int i=0;i<names.length;i++)names[i]=(i+1)+UiText.t("번 · ")+UiText.t(Telemetry.LABELS[layout.signal(i)]);
        show(new AlertDialog.Builder(activity).setTitle(UiText.t("위치를 바꿀 칸")).setItems(names,new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int n){layout.swap(slot,n);save();}}).setNegativeButton(UiText.t("취소"),null));
    }
    private void reset(){show(new AlertDialog.Builder(activity).setTitle(UiText.t("기본 배치로 복원")).setMessage(UiText.t("모든 게이지의 항목과 모양을 초기화합니다.")).setPositiveButton(UiText.t("복원"),new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int n){layout.reset();save();}}).setNegativeButton(UiText.t("취소"),null));}
}
