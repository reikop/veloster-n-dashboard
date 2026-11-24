package com.veloster.vehicle;

import com.veloster.i18n.UiText;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Locale;

/** 기록실: the kept records (Records) as a list — the three acceleration runs (the i30 PD build's circuit laps need GPS lap timing, not ported). */
public final class RecordsDialog {
    private RecordsDialog() {}

    public static void show(final Activity a){
        StringBuilder b=new StringBuilder();
        for(int i=0;i<DriveStats.RUNS.length;i++)
            section(b,String.format(Locale.US,"%.0f-%.0f km/h",DriveStats.RUNS[i][0],DriveStats.RUNS[i][1]),Records.entries(Records.runKey(i)),false);
        TextView text=new TextView(a);text.setText(b.toString().trim());text.setTextSize(17);text.setTextColor(0xffffffff);
        text.setTypeface(Typeface.MONOSPACE);text.setLineSpacing(6,1);
        int pad=(int)(18*a.getResources().getDisplayMetrics().density);text.setPadding(pad,pad/2,pad,pad/2);
        ScrollView scroll=new ScrollView(a);scroll.addView(text);
        new AlertDialog.Builder(a).setTitle(UiText.t("기록실")).setView(scroll)
            .setPositiveButton(UiText.t("닫기"),null)
            .setNeutralButton(UiText.t("기록 지우기"),new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){
                new AlertDialog.Builder(a).setMessage(UiText.t("저장된 기록을 모두 지울까요?"))
                    .setPositiveButton(UiText.t("지우기"),new DialogInterface.OnClickListener(){public void onClick(DialogInterface d2,int w2){Records.clear();}})
                    .setNegativeButton(UiText.t("취소"),null).show();
            }}).show();
    }
    private static void section(StringBuilder b,String title,String[] entries,boolean lap){
        b.append(title).append('\n');
        if(entries.length==0)b.append("   —\n");
        for(int i=0;i<entries.length;i++){
            float v=Records.value(entries[i]);
            b.append(String.format(Locale.US," %d. %8s   %s\n",i+1,String.format(Locale.US,"%.2f s",v),Records.date(entries[i])));
        }
        b.append('\n');
    }
}
