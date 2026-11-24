package com.veloster.vehicle;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import com.veloster.i18n.UiText;
import java.util.Locale;

/** Reference-inspired layout. Draws only existing received Telemetry samples; no new decoding. */
public final class PerformanceRenderer {
    private static final int BG=0xff0c1820,PANEL=0xff10212b,LINE=0xff29424e,WHITE=0xffe4f1f6,BLUE=0xffa4d9ec,MUTED=0xff91aebb;
    private final ThemedPaint p=new ThemedPaint(Paint.ANTI_ALIAS_FLAG);
    private Telemetry.Sample[] samples;
    private boolean valid(int i){return samples[i].valid&&!Float.isNaN(samples[i].value)&&!Float.isInfinite(samples[i].value);}
    private float number(int i){return Telemetry.displayValue(i,samples[i].value);}
    private String value(int i,String format){return valid(i)?String.format(Locale.US,format,number(i)):"—";}
    private float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
    private void text(Canvas c,String s,float x,float y,float size,int color,Paint.Align align,boolean bold){
        p.setStyle(Paint.Style.FILL);p.setColor(color);p.setTextAlign(align);p.setTextSize(size);p.setTypeface(bold?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);c.drawText(s,x,y,p);
    }
    private void fit(Canvas c,String s,float x,float y,float size,float width,int color,Paint.Align align){
        p.setTypeface(Typeface.DEFAULT);p.setTextSize(size);float measured=p.measureText(s);
        text(c,s,x,y,measured>width?size*width/measured:size,color,align,false);
    }
    private void line(Canvas c,float x,float y,float x2,float y2,int color,float width){p.setColor(color);p.setStrokeWidth(width);c.drawLine(x,y,x2,y2,p);}
    private void box(Canvas c,float l,float t,float r,float b){
        p.setStyle(Paint.Style.FILL);p.setColor(PANEL);c.drawRoundRect(new RectF(l,t,r,b),7,7,p);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(LINE);c.drawRoundRect(new RectF(l,t,r,b),7,7,p);p.setStyle(Paint.Style.FILL);
    }
    private void arc(Canvas c,RectF bounds,float start,float sweep,int color,float width){
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(width);p.setColor(color);c.drawArc(bounds,start,sweep,false,p);p.setStyle(Paint.Style.FILL);
    }
    /** Called below the common header, on the already scaled canvas. */
    public void draw(Canvas c,Telemetry model,long now){
        samples=model.snapshot(now);
        p.setStyle(Paint.Style.FILL);p.setColor(BG);c.drawRect(0,52,800,480,p);
        text(c,"N",16,82,25,BLUE,Paint.Align.LEFT,true);
        text(c,"PERFORMANCE",42,80,19,WHITE,Paint.Align.LEFT,true);
        text(c,"VELOSTER N",233,79,13,MUTED,Paint.Align.LEFT,false);
        box(c,426,60,580,87);
        text(c,UiText.t("배터리"),438,78,13,MUTED,Paint.Align.LEFT,false);
        text(c,value(Telemetry.BAT_VOLTAGE,"%.2f")+" V",569,80,17,WHITE,Paint.Align.RIGHT,false);
        engine(c);headline(c);temperatures(c);pedals(c);forces(c);tires(c);
    }
    private void engine(Canvas c){
        text(c,"ENGINE",20,114,14,MUTED,Paint.Align.LEFT,false);
        text(c,"rpm",275,114,12,MUTED,Paint.Align.RIGHT,false);
        RectF gauge=new RectF(38,128,274,364);arc(c,gauge,180,180,LINE,6);
        if(valid(0))arc(c,gauge,180,180*clamp(number(0)/8000f,0,1),BLUE,6);
        fit(c,value(0,"%,.0f"),156,231,51,202,WHITE,Paint.Align.CENTER);
        text(c,"0",38,269,12,MUTED,Paint.Align.LEFT,false);
        text(c,"8,000",274,269,12,MUTED,Paint.Align.RIGHT,false);
        for(int i=0;i<20;i++){
            p.setColor(valid(0)&&number(0)/8000f*20>i?BLUE:LINE);
            c.drawRoundRect(new RectF(50+i*10.7f,280,56+i*10.7f,283),1,1,p);
        }
    }
    private void headline(Canvas c){
        line(c,293,106,293,281,LINE,1);line(c,389,106,389,281,LINE,1);
        text(c,"GEAR",341,115,14,MUTED,Paint.Align.CENTER,false);
        text(c,valid(8)?Telemetry.gearLabel((int)number(8)):"—",341,227,67,BLUE,Paint.Align.CENTER,false);
        text(c,UiText.t("기어"),341,259,14,MUTED,Paint.Align.CENTER,false);
        text(c,"SPEED",406,115,14,MUTED,Paint.Align.LEFT,false);
        fit(c,value(7,"%.0f"),488,227,79,171,WHITE,Paint.Align.CENTER);
        text(c,"km/h",488,259,15,MUTED,Paint.Align.CENTER,false);
    }
    private void temperatures(Canvas c){
        int[] ids={Telemetry.AMBIENT,Telemetry.INTAKE,2,1};
        String[] labels={UiText.t("외기 온도"),UiText.t("흡기 온도"),UiText.t("냉각수 온도"),UiText.t("오일 온도")};
        for(int n=0;n<4;n++){
            float x=14+n*144;box(c,x,296,x+134,355);
            fit(c,labels[n],x+10,314,13,114,MUTED,Paint.Align.LEFT);
            text(c,value(ids[n],"%.1f"),x+92,343,26,WHITE,Paint.Align.RIGHT,false);
            text(c,"°C",x+123,343,13,MUTED,Paint.Align.RIGHT,false);
        }
    }
    private void pedals(Canvas c){
        int[] ids={9,10};String[] labels={UiText.t("스로틀"),UiText.t("브레이크")};
        for(int n=0;n<2;n++){
            float x=14+n*144;box(c,x,365,x+134,466);
            text(c,labels[n],x+10,386,14,MUTED,Paint.Align.LEFT,false);
            text(c,value(ids[n],"%.1f"),x+92,423,29,WHITE,Paint.Align.RIGHT,false);
            text(c,n==0?"%":"bar",x+124,423,13,MUTED,Paint.Align.RIGHT,false);
            p.setColor(LINE);c.drawRoundRect(new RectF(x+10,446,x+124,452),3,3,p);
            if(valid(ids[n])){p.setColor(BLUE);float f=clamp(number(ids[n])/100f,0,1);if(f>0)c.drawRoundRect(new RectF(x+10,446,x+10+114*f,452),3,3,p);}
        }
    }
    private void forces(Canvas c){
        box(c,302,365,580,466);text(c,"G FORCE",314,385,14,MUTED,Paint.Align.LEFT,false);
        float cx=345,cy=424;
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(0xff557e91);
        c.drawCircle(cx,cy,31,p);c.drawCircle(cx,cy,19,p);
        line(c,cx-31,cy,cx+31,cy,LINE,1);line(c,cx,cy-31,cx,cy+31,LINE,1);
        if(valid(11)&&valid(12)){
            p.setStyle(Paint.Style.FILL);p.setColor(BLUE);
            c.drawCircle(cx+clamp(number(11),-1,1)*27,cy-clamp(number(12),-1,1)*27,4,p);
        }
        text(c,UiText.t("종가속"),423,402,13,MUTED,Paint.Align.CENTER,false);
        text(c,UiText.t("횡가속"),526,402,13,MUTED,Paint.Align.CENTER,false);
        fit(c,value(12,"%+.2f"),423,432,25,84,WHITE,Paint.Align.CENTER);
        fit(c,value(11,"%+.2f"),526,432,25,84,WHITE,Paint.Align.CENTER);
        text(c,"G",423,451,12,MUTED,Paint.Align.CENTER,false);text(c,"G",526,451,12,MUTED,Paint.Align.CENTER,false);
    }
    private void tires(Canvas c){
        box(c,592,94,786,466);
        fit(c,UiText.t("타이어 공기압")+" · psi",689,118,14,170,MUTED,Paint.Align.CENTER);
        text(c,UiText.t("조향각"),689,140,12,MUTED,Paint.Align.CENTER,false);
        fit(c,value(Telemetry.STEERING,"%+.1f")+" °",689,165,23,170,WHITE,Paint.Align.CENTER);
        float cx=689,cy=191;
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setColor(valid(Telemetry.STEERING)?BLUE:LINE);c.drawCircle(cx,cy,12,p);
        if(valid(Telemetry.STEERING)){
            double angle=Math.toRadians(number(Telemetry.STEERING));float dx=(float)Math.cos(angle)*10,dy=(float)Math.sin(angle)*10;
            line(c,cx-dx,cy-dy,cx+dx,cy+dy,BLUE,2);line(c,cx,cy,cx-dy,cy+dx,BLUE,2);
        }
        line(c,606,210,772,210,LINE,1);
        // Simple top-view silhouette, not a path prediction or an image asset.
        p.setStyle(Paint.Style.FILL);p.setColor(0xff688592);c.drawRoundRect(new RectF(666,235,712,368),16,16,p);
        p.setColor(0xffc6dce5);c.drawRoundRect(new RectF(671,239,707,362),13,13,p);
        p.setColor(0xff24414e);c.drawRoundRect(new RectF(675,264,703,331),8,8,p);
        p.setColor(0xff98b6c4);c.drawRect(677,281,701,316,p);
        p.setColor(BG);c.drawRoundRect(new RectF(660,253,666,275),2,2,p);c.drawRoundRect(new RectF(712,253,718,275),2,2,p);
        c.drawRoundRect(new RectF(660,334,666,356),2,2,p);c.drawRoundRect(new RectF(712,334,718,356),2,2,p);
        String[] labels={UiText.t("앞 왼쪽"),UiText.t("앞 오른쪽"),UiText.t("뒤 왼쪽"),UiText.t("뒤 오른쪽")};
        for(int n=0;n<4;n++){
            float x=n%2==0?624:754,y=n<2?246:342;
            fit(c,labels[n],x,y,12,60,MUTED,Paint.Align.CENTER);
            fit(c,value(Telemetry.TPMS_FL+n,"%.0f"),x,y+28,29,60,WHITE,Paint.Align.CENTER);
        }
        text(c,"VELOSTER N",689,427,14,MUTED,Paint.Align.CENTER,false);
        text(c,"TPMS",689,449,12,BLUE,Paint.Align.CENTER,false);
    }
}
