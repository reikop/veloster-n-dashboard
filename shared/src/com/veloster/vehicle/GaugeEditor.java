package com.veloster.vehicle;

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
        show(new AlertDialog.Builder(activity).setTitle("게이지 "+(slot+1)+" · "+Telemetry.LABELS[layout.signal(slot)])
            .setItems(new String[]{"표시 항목", "게이지 모양", "다른 칸과 위치 바꾸기", "전체 기본 배치로 복원"},new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){
                if(which==0)source(slot);else if(which==1)style(slot);else if(which==2)swap(slot);else reset();
            }}).setNegativeButton("닫기",null));
    }
    private void source(final int slot){
        show(new AlertDialog.Builder(activity).setTitle("표시 항목").setSingleChoiceItems(Telemetry.LABELS,layout.signal(slot),new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int n){layout.set(slot,n,layout.style(slot));save();d.dismiss();}}).setNegativeButton("취소",null));
    }
    private void style(final int slot){
        show(new AlertDialog.Builder(activity).setTitle("게이지 모양").setSingleChoiceItems(new String[]{"원형", "막대", "숫자"},layout.style(slot),new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int n){layout.set(slot,layout.signal(slot),n);save();d.dismiss();}}).setNegativeButton("취소",null));
    }
    private void swap(final int slot){
        String[] names=new String[layout.count()];for(int i=0;i<names.length;i++)names[i]=(i+1)+"번 · "+Telemetry.LABELS[layout.signal(i)];
        show(new AlertDialog.Builder(activity).setTitle("위치를 바꿀 칸").setItems(names,new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int n){layout.swap(slot,n);save();}}).setNegativeButton("취소",null));
    }
    private void reset(){show(new AlertDialog.Builder(activity).setTitle("기본 배치로 복원").setMessage("모든 게이지의 항목과 모양을 초기화합니다.").setPositiveButton("복원",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int n){layout.reset();save();}}).setNegativeButton("취소",null));}
}
