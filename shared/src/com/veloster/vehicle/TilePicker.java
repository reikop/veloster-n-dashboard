package com.veloster.vehicle;

import com.veloster.i18n.UiText;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;

/** The two-step signal list (group, then signal) every editor uses: the hold-to-edit tiles, gauges and graphs.
 *  Only what this car has; the current choice is marked. One dialog at a time. */
final class TilePicker {
    interface Pick { void pick(int signal); }   // -1 = back to the tile's default
    private final Activity activity;
    private AlertDialog dialog;
    TilePicker(Activity a){activity=a;}
    void dismiss(){AlertDialog old=dialog;dialog=null;if(old!=null)old.dismiss();}
    private void show(AlertDialog.Builder b){
        dismiss();if(activity.isFinishing())return;
        final AlertDialog next=b.create();dialog=next;
        next.setOnDismissListener(new DialogInterface.OnDismissListener(){public void onDismiss(DialogInterface d){if(dialog==next)dialog=null;}});
        next.show();
    }
    /** tiles: also the several-value entries (four tyres, both clutches, the DPF cell) and session statistics;
     *  reset: a first line that puts the default back. */
    void open(final String title,final VehicleProfile p,final boolean tiles,final boolean reset,final int current,final Pick pick){
        final int[] gs=Tiles.groups(p,tiles);final int top=reset?1:0;int mine=Tiles.groupOf(current);
        String[] names=new String[gs.length+top];
        if(reset)names[0]=UiText.t("기본값으로 되돌리기");
        for(int i=0;i<gs.length;i++)names[i+top]=UiText.t(Tiles.GROUPS[gs[i]])+(gs[i]==mine?"  ●":"");
        show(new AlertDialog.Builder(activity).setTitle(title)
            .setItems(names,new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){
                if(w<top){pick.pick(-1);return;}
                group(title,p,tiles,reset,current,gs[w-top],pick);
            }}).setNegativeButton(UiText.t("닫기"),null));
    }
    private void group(final String title,final VehicleProfile p,final boolean tiles,final boolean reset,final int current,int g,final Pick pick){
        final int[] ss=Tiles.group(g,p,tiles);int at=-1;String[] names=new String[ss.length];
        for(int i=0;i<ss.length;i++){names[i]=UiText.t(Tiles.label(ss[i]));if(ss[i]==current)at=i;}
        show(new AlertDialog.Builder(activity).setTitle(UiText.t(Tiles.GROUPS[g]))
            .setSingleChoiceItems(names,at,new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){pick.pick(ss[w]);dismiss();}})
            .setNegativeButton(UiText.t("뒤로"),new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){open(title,p,tiles,reset,current,pick);}}));
    }
}
