package com.veloster.vehicle;

import android.graphics.Canvas;
import android.graphics.Paint;
import java.util.Calendar;
import java.util.Locale;

/** 80s digital dash: teal VFD glass with ghost segments, stepped speed bar, rising tach, TURBO lamp, gear in amber. */
final class VfdCluster extends ClusterKit {
    private static final int ON=0xff4ff3d3,GHOST=0xff0b211d,AMB=0xffffb23e,AMB_GHOST=0xff261b09,RED=0xffff4b3a,DIM=0xff2a8a78,BOX=0xff163a33;

    @Override protected void draw(Canvas c){
        float v=v(7),rpm=v(0),boost=v(Telemetry.BOOST),top=boostTop();
        fill(c,0,52,800,480,0xff030405);
        round(c,10,58,790,474,12,0xff010302);outline(c,10,58,790,474,12,1.5f,0xff18302b);
        // stepped speed bar 0..240 km/h
        int n=60,lit=Float.isNaN(v)?0:Math.round(v/240*n);
        for(int i=0;i<n;i++){
            float x=44+i*12,h=16+i*0.62f,y=134-h;int col=i<lit?(i>=50?AMB:ON):(i>=50?AMB_GHOST:GHOST);
            path.reset();path.moveTo(x+3,y);path.lineTo(x+12,y);path.lineTo(x+9,134);path.lineTo(x,134);path.close();poly(c,col);
        }
        for(int k=0;k<=240;k+=20)mono(c,String.valueOf(k),44+k/240f*n*12,152,13,DIM,Paint.Align.CENTER);
        // big speed
        String sp=Float.isNaN(v)?"---":String.valueOf(Math.round(v));while(sp.length()<3)sp=" "+sp;
        seg7(c,sp,300,184,118,ON,GHOST,false);
        mono(c,"KM/H",300,322,16,DIM,Paint.Align.RIGHT);
        // warning words, lit only while on
        String[] w={"DOOR","PARK","BRAKE","SHIFT","ENGINE"};   // ENGINE = the MIL (engine warning lamp), lit amber like the others
        boolean[] on={v(Telemetry.DOOR_DRIVER)>0||v(Telemetry.DOOR_PASSENGER)>0,v(Telemetry.PARK_BRAKE)>0,v(10)>35,!Float.isNaN(rpm)&&rpm>prof.redline-500,v(Telemetry.MIL)>0};
        for(int i=0;i<w.length;i++)mono(c,w[i],70+i*62,346,15,on[i]?AMB:AMB_GHOST,Paint.Align.CENTER);
        // rising tach 0..8000
        for(int i=0;i<40;i++){
            float r=(i+1)*200,x=336+i*11,h=14+i*3.4f;boolean isOn=!Float.isNaN(rpm)&&rpm>=r-100;
            int col=r>prof.redline?RED:r>prof.redline-1000?AMB:ON;
            fill(c,x,318-h,x+8,318,isOn?col:(r>prof.redline?0xff2a0d0a:GHOST));
        }
        for(int k=1;k<=8;k++)mono(c,String.valueOf(k),336+(k*5-1)*11+4,336,14,k*1000>prof.redline?0xffb03a2c:DIM,Paint.Align.CENTER);
        mono(c,"RPM",340,190,14,DIM,Paint.Align.LEFT);   // above the low end of the tach, clear of the tall bars
        seg7(c,Float.isNaN(rpm)?"----":String.format(Locale.US,"%4d",Math.round(rpm/10f)*10),520,198,30,ON,GHOST,false);
        mono(c,"x1000",776,334+18,12,DIM,Paint.Align.RIGHT);
        // bottom boxes
        box(c,24,140,"FUEL");box(c,172,140,"TEMP");box(c,320,96,"GEAR");box(c,424,170,"BOOST");box(c,602,174,"ODO . TRIP");
        float range=v(Telemetry.RANGE),cool=v(2);
        hbar(c,34,Float.isNaN(range)?0:range/600,3,-1);mono(c,"E",34,422,12,DIM,Paint.Align.LEFT);mono(c,"F",150,422,12,DIM,Paint.Align.RIGHT);
        seg7(c,fmt("%.0f",range).replace("—","---"),154,426,24,ON,GHOST,false);mono(c,"km",38,448,12,DIM,Paint.Align.LEFT);
        hbar(c,182,Float.isNaN(cool)?0:(cool-40)/80,-1,13);mono(c,"C",182,422,12,DIM,Paint.Align.LEFT);mono(c,"H",298,422,12,DIM,Paint.Align.RIGHT);
        seg7(c,fmt("%.0f",cool).replace("—","--"),302,426,24,ON,GHOST,false);mono(c,"°C",186,448,12,DIM,Paint.Align.LEFT);
        String g=gear();boolean m=manualGate(g);if(m)g=g.substring(1);   // manual gate: the digit in segments, a small M beside it
        boolean one=g.length()==1&&"0123456789PRND".indexOf(g.charAt(0))>=0;   // P R N D in segments too, not a letter over the glass
        seg7(c,one?g:" ",392,386,64,AMB,AMB_GHOST,false);
        if(m)mono(c,"M",398,386,18,AMB,Paint.Align.LEFT);
        else if(!one)bold(c,g,368,446,40,AMB,Paint.Align.CENTER);
        // boost bar: -0.7 .. top, zero tick, TURBO lamp past 0.4 bar
        float span=top+0.7f,bf=Float.isNaN(boost)?0:(boost+0.7f)/span,z=434+0.7f/span*150;
        for(int i=0;i<20;i++){float val=-0.7f+(i+.5f)*span/20;boolean isOn=i<Math.round(bf*20);fill(c,434+i*7.5f,388,439.5f+i*7.5f,406,isOn?(val>top*0.8f?AMB:ON):GHOST);}
        fill(c,z-1,384,z,410,DIM);mono(c,"0",z,422,12,DIM,Paint.Align.CENTER);mono(c,String.format(Locale.US,"%.1f",top),584,422,12,DIM,Paint.Align.RIGHT);
        boolean turbo=!Float.isNaN(boost)&&boost>0.4f;
        if(turbo)round(c,434,430,504,452,4,AMB);else outline(c,434,430,504,452,4,1,AMB_GHOST);
        text(c,"TURBO",469,447,15,turbo?0xff1a0e00:AMB_GHOST,Paint.Align.CENTER,android.graphics.Typeface.DEFAULT_BOLD);
        seg7(c,Float.isNaN(boost)?"---":String.format(Locale.US,"%s%.1f",boost<0?"-":" ",Math.abs(boost)),584,428,24,ON,GHOST,false);
        float odo=v(Telemetry.ODOMETER);
        seg7(c,fmt("%.0f",odo).replace("—","------"),764,384,22,ON,GHOST,false);mono(c,"ODO",612,400,12,DIM,Paint.Align.LEFT);
        seg7(c,st==null?"---.-":String.format(Locale.US,"%.1f",trip()),764,412,22,ON,GHOST,false);mono(c,"TRIP",612,428,12,DIM,Paint.Align.LEFT);
        Calendar k=Calendar.getInstance();
        seg7(c,String.format(Locale.US,"%02d:%02d",k.get(Calendar.HOUR_OF_DAY),k.get(Calendar.MINUTE)),764,438,18,AMB,AMB_GHOST,false);
    }
    private float tripKm;
    private long tripAt;
    /** Distance since the app started, from the speed seen between frames (every frame, whatever page shows). */
    @Override protected void tick(){
        float v=v(7);
        if(tripAt!=0&&!Float.isNaN(v)&&now-tripAt<2000)tripKm+=v*(now-tripAt)/3600000f;
        tripAt=now;
    }
    private float trip(){return tripKm;}
    private void box(Canvas c,float x,float w,String label){outline(c,x,358,x+w,462,6,1,BOX);mono(c,label,x+10,376,13,DIM,Paint.Align.LEFT);}
    /** 16-cell bar; cells below lowWarn or from highWarn up turn amber / red. */
    private void hbar(Canvas c,float x,float frac,int lowWarn,int highWarn){
        int litN=Math.round(clamp(frac,0,1)*16);
        for(int i=0;i<16;i++){int col=i<lowWarn?AMB:highWarn>=0&&i>=highWarn?RED:ON;fill(c,x+i*7.5f,388,x+5.5f+i*7.5f,406,i<litN?col:GHOST);}
    }
}
