package com.veloster.vehicle;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import java.util.Calendar;
import java.util.Locale;

/** Night minimal: one dim speed number, a thin rpm line, the gear; warnings light their slot only while on. */
final class NightCluster extends ClusterKit {
    private static final int SP=0xffa9b6be,DIM=0xff3a4248,ACC=0xffa4d9ef;   // accent is the N palette accent so the theme remaps it
    { themed=true; }   // the minimal night layout follows the theme's accent

    @Override protected void draw(Canvas c){
        float v=v(7),rpm=v(0),cool=v(2);
        fill(c,0,52,800,480,0xff000000);
        Calendar k=Calendar.getInstance();
        bold(c,String.format(Locale.US,"%02d:%02d",k.get(Calendar.HOUR_OF_DAY),k.get(Calendar.MINUTE)),24,88,20,DIM,Paint.Align.LEFT);
        bold(c,fmt("%.0f",cool)+"°C",776,88,20,0xff2c3338,Paint.Align.RIGHT);
        if(v(Telemetry.MIL)>0)MilLamp.draw(c,686,81,18,MilLamp.AMBER);   // engine warning lamp: a small amber glyph by the temperature, nothing when off
        text(c,fmt("%.0f",v),400,354,236,SP,Paint.Align.CENTER,Typeface.DEFAULT);
        bold(c,"km/h",400,386,18,0xff4b565e,Paint.Align.CENTER);
        bold(c,gear(),632,184,64,ACC,Paint.Align.CENTER);bold(c,"GEAR",632,206,13,0xff2f3a44,Paint.Align.CENTER);
        float x0=120,x1=680,y=424,f=Float.isNaN(rpm)?0:clamp(rpm/prof.maxRpm,0,1);
        fill(c,x0,y,x1,y+3,0xff0d1114);
        fill(c,x0,y,x0+(x1-x0)*f,y+3,!Float.isNaN(rpm)&&rpm>prof.redline-1000?0xffa8742a:ACC);
        float rx=x0+(x1-x0)*prof.redline/prof.maxRpm;fill(c,rx,y-6,rx+2,y+9,0xff4a1d1a);
        bold(c,fmt("%,.0f",rpm),x1,y-10,15,0xff3b464e,Paint.Align.RIGHT);
        arrow(c,48,1,v(Telemetry.TURN_LEFT)>0&&blink(now,800));arrow(c,752,-1,v(Telemetry.TURN_RIGHT)>0&&blink(now,800));
        // warning slot: dark unless something needs attention
        String warn=warning(cool);
        if(warn==null)fill(c,250,448,550,472,0xff07090a);
        else{fill(c,250,448,550,472,danger());bold(c,warn,400,466,15,0xff000000,Paint.Align.CENTER);}
    }
    private String warning(float cool){
        if(v(Telemetry.DOOR_DRIVER)>0||v(Telemetry.DOOR_PASSENGER)>0)return t("문 열림");
        if(v(Telemetry.TRUNK)>0)return t("트렁크 열림");
        if(v(Telemetry.HOOD)>0)return t("후드 열림");
        for(int i=Telemetry.TPMS_FL;i<=Telemetry.TPMS_RR;i++){float t=v(i);if(!Float.isNaN(t)&&t<30)return t("타이어 공기압 낮음");}
        if(!Float.isNaN(cool)&&cool>=110)return t("냉각수 과열");
        float co=v(Telemetry.CLUTCH_ODD),ce=v(Telemetry.CLUTCH_EVEN);boolean even=Float.isNaN(co)||!Float.isNaN(ce)&&ce>co;
        float hot=even?ce:co;int lv=Telemetry.clutchLevel(hot);   // 7DCT: the hotter clutch, only once it runs warm
        if(lv>0)return t(lv==2?"클러치 과열":"클러치 온도 높음")+" · "+t(even?"짝수단":"홀수단")+" "+fmt("%.0f",hot)+"°C";
        if(v(Telemetry.PARK_BRAKE)>0&&v(7)>3)return t("주차 브레이크");
        if(v(Telemetry.DPF_REGEN)>0)return t("DPF 재생 중");
        return null;
    }
    private void arrow(Canvas c,float x,int dir,boolean on){
        path.reset();path.moveTo(x,268);path.lineTo(x+dir*24,252);path.lineTo(x+dir*24,284);path.close();poly(c,on?0xff2f8f5a:0xff0b0f0d);
    }
}
