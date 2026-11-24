package com.veloster.vehicle;

import com.veloster.i18n.UiText;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * 기록 page: the three acceleration runs (this drive's last and best, and the all-time best kept by Records) on top,
 * this drive's peaks below. Layout follows the i30 PD build's records page (sung9155/i30-pd-dashboard, MIT), drawn
 * with the N / PIT panels. A tap on a run box opens the kept records (RecordsDialog, VehicleDash).
 */
final class RecordsRenderer {
    static final float RUN_TOP=60,RUN_BOTTOM=250;
    private static final int PANEL=0xff101315,LINE=0xff303638,BLUE=0xffa4d9ef,WHITE=0xffe1edf2,MUTED=0xff91a5ae,GREEN=0xff3ddc84,AMBER=0xffffb347;
    private final ThemedPaint p=new ThemedPaint(3);
    private final Path path=new Path();
    private final RectF rect=new RectF();
    private static String fmt(String f,float x){return Float.isNaN(x)?"—":Telemetry.format(f,x);}
    private static String run(int i){return String.format(java.util.Locale.US,"%.0f→%.0f",DriveStats.RUNS[i][0],DriveStats.RUNS[i][1]);}
    void draw(Canvas c,DriveStats st,long now){
        float l0=18,r0=782,w=(r0-l0-16)/3;
        for(int i=0;i<3;i++){
            float l=l0+i*(w+8),r=l+w;boolean live=st!=null&&st.running[i];
            panel(c,l,RUN_TOP,r-l,RUN_BOTTOM-RUN_TOP,live?0xff12212a:PANEL,live?BLUE:LINE);
            p.setColor(BLUE);c.drawRect(l+10,RUN_TOP,l+53,RUN_TOP+2,p);
            text(c,run(i)+" km/h",l+12,RUN_TOP+26,16,MUTED,false,Paint.Align.LEFT);
            float best=st==null?Float.NaN:st.best[i];
            text(c,"BEST "+fmt("%.2f",best),r-12,RUN_TOP+26,15,Float.isNaN(best)?MUTED:GREEN,true,Paint.Align.RIGHT);
            text(c,UiText.t("역대")+" "+fmt("%.2f",Records.best(Records.runKey(i))),r-12,RUN_TOP+48,13,MUTED,false,Paint.Align.RIGHT);
            float shown=st==null?Float.NaN:live?(now-st.runStart[i])/1000f:st.last[i];
            text(c,"s",r-12,RUN_TOP+146,16,MUTED,false,Paint.Align.RIGHT);
            text(c,fmt(live?"%.1f":"%.2f",shown),r-30,RUN_TOP+146,66,live?AMBER:WHITE,true,Paint.Align.RIGHT);
            text(c,live?UiText.t("측정 중"):i==0?UiText.t("정차 후 출발하면 측정"):UiText.t("가속해 들어가면 측정"),l+12,RUN_BOTTOM-14,13,live?AMBER:MUTED,false,Paint.Align.LEFT);
        }
        String[] name={UiText.t("최대 횡G"),UiText.t("최대 감속G"),UiText.t("최대 부스트"),UiText.t("최고 RPM"),UiText.t("최고 속도")};
        String[] val=st==null?new String[]{"—","—","—","—","—"}:new String[]{fmt("%.2f",st.maxLat),fmt("%.2f",st.maxDecel),fmt("%+.2f",st.maxBoost),fmt("%.0f",st.maxRpm),fmt("%.0f",st.maxSpeed)};
        String[] unit={"G","G","bar","rpm","km/h"};
        float w5=(r0-l0-8*4)/5;
        for(int i=0;i<5;i++){
            float l=l0+i*(w5+8);
            panel(c,l,258,w5,190,PANEL,LINE);
            text(c,name[i],l+12,282,13,MUTED,false,Paint.Align.LEFT);
            text(c,val[i],l+w5-12,400,42,WHITE,true,Paint.Align.RIGHT);
            text(c,unit[i],l+w5-12,428,13,MUTED,false,Paint.Align.RIGHT);
        }
        text(c,UiText.t("BEST·최대값은 이번 주행 기준 · 측정 칸을 누르면 기록실"),r0,470,12,MUTED,false,Paint.Align.RIGHT);
    }
    private void panel(Canvas c,float x,float y,float w,float h,int fill,int edge){
        if(ThemeShapes.cell(c,p,path,rect,x,y,x+w,y+h,fill,edge))return;
        path.reset();path.moveTo(x,y);path.lineTo(x+w-12,y);path.lineTo(x+w,y+12);
        path.lineTo(x+w,y+h);path.lineTo(x+12,y+h);path.lineTo(x,y+h-12);path.close();
        p.setStyle(Paint.Style.FILL);p.setColor(fill);c.drawPath(path,p);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(edge);c.drawPath(path,p);p.setStyle(Paint.Style.FILL);
    }
    private void text(Canvas c,String t,float x,float y,float size,int color,boolean bold,Paint.Align align){
        p.setStyle(Paint.Style.FILL);p.setColor(color);p.setTypeface(bold?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);p.setTextSize(size);
        p.setTextAlign(align);c.drawText(t,x,y,p);p.setTextAlign(Paint.Align.LEFT);
    }
}
