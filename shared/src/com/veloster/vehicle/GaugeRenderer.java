package com.veloster.vehicle;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.util.Locale;

/** Draws six or twelve cards below the shared navigation. */
public final class GaugeRenderer {
    private final Paint p=new Paint(3);
    private static final int BLUE=0xffa4d9ef,WHITE=0xffe1edf2,MUTED=0xff8299a4,LINE=0xff293943;
    private void text(Canvas c,String s,float x,float y,float size,int color){p.setStyle(Paint.Style.FILL);p.setColor(color);p.setTypeface(Typeface.DEFAULT);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(size);c.drawText(s,x,y,p);}
    private void line(Canvas c,float x,float y,float xx,float yy,int color,float width){p.setColor(color);p.setStrokeWidth(width);c.drawLine(x,y,xx,yy,p);}
    static float min(int i,String u){return "G".equals(u)?-2:"°C".equals(u)?-40:i==Telemetry.STEERING?-540:0;}
    static float max(int i,String u){
        if(i>=Telemetry.TCS&&i<=Telemetry.ABS)return 1;
        if(i==Telemetry.ELSD)return 6553.5f;
        if(i==Telemetry.BAT_VOLTAGE)return 18;if(i==Telemetry.STEERING_SPEED)return 1020;
        if("psi".equals(u))return 60;
        if("rpm".equals(u))return 8000;if("km/h".equals(u))return 300;if("%".equals(u))return 100;
        if("°C".equals(u))return 160;if("G".equals(u))return 2;if("PS".equals(u)||"hp".equals(u))return 350;
        if("Nm".equals(u)||"N·m".equals(u))return 500;if("kPa".equals(u))return 250;
        if("bar".equals(u))return i==10?100:2.5f;if(Telemetry.LABELS[i].contains("조향"))return 540;
        return i==8?6:100;
    }
    private static String axis(float value){return String.format(Locale.US,value==(int)value?"%.0f":"%.1f",value);}
    public void draw(Canvas c,Telemetry data,long now,GaugeLayout layout){
        int save=c.save();c.clipRect(0,52,800,480);p.setColor(0xff070b0e);c.drawRect(0,52,800,480,p);
        text(c,"게이지를 길게 눌러 항목 · 모양 · 위치 편집",300,71,13,MUTED);
        p.setColor(0xff233b46);c.drawRoundRect(new RectF(650,54,788,78),5,5,p);
        text(c,layout.dense()?"3 × 2 보기":"4 × 3 보기",719,71,14,BLUE);
        Telemetry.Sample[] samples=data.snapshot(now);
        for(int slot=0;slot<layout.count();slot++){
            int i=layout.signal(slot),style=layout.style(slot);Telemetry.Sample s=samples[i];
            String u=Telemetry.UNITS[i];float l=layout.left(slot),t=layout.top(slot),cx=l+layout.width()/2;
            // Scale the same card design uniformly; dense cards remain centered in their wider cell.
            int cardSave=c.save();float scale=layout.dense()?124f/190:1;
            c.translate(cx-126*scale,t);c.scale(scale,scale);l=0;t=0;cx=126;
            p.setStyle(Paint.Style.FILL);p.setColor(0xff101920);c.drawRoundRect(new RectF(l,t,l+252,t+190),10,10,p);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(LINE);c.drawRoundRect(new RectF(l,t,l+252,t+190),10,10,p);p.setStyle(Paint.Style.FILL);
            text(c,Telemetry.LABELS[i],cx,t+25,17,BLUE);
            boolean ok=s.valid&&!Float.isNaN(s.value)&&!Float.isInfinite(s.value);
            float displayed=Telemetry.displayValue(i,s.value);
            String value=ok?(i==8?Telemetry.gearLabel((int)s.value):String.format(Locale.US,"rpm".equals(u)||"km/h".equals(u)?"%.0f":"G".equals(u)?"%+.2f":i==Telemetry.STEERING?"%+.1f":"%.1f",displayed)):"—";
            float lo=min(i,u),hi=max(i,u),f=ok?Math.max(0,Math.min(1,(displayed-lo)/(hi-lo))):0;
            if(style==0){
                float cy=t+107,r=63;
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(5);p.setColor(LINE);RectF arc=new RectF(cx-r,cy-r,cx+r,cy+r);c.drawArc(arc,150,240,false,p);
                if(ok){p.setColor(BLUE);c.drawArc(arc,150,240*f,false,p);}
                for(int tick=0;tick<=8;tick++){double a=Math.toRadians(150+30*tick);line(c,cx+(float)Math.cos(a)*54,cy+(float)Math.sin(a)*54,cx+(float)Math.cos(a)*60,cy+(float)Math.sin(a)*60,MUTED,1);}
                if(ok){double a=Math.toRadians(150+240*f);line(c,cx,cy,cx+(float)Math.cos(a)*48,cy+(float)Math.sin(a)*48,WHITE,2);p.setStyle(Paint.Style.FILL);p.setColor(BLUE);c.drawCircle(cx,cy,4,p);}
                text(c,axis(lo),cx-69,t+155,10,MUTED);text(c,axis(hi),cx+69,t+155,10,MUTED);
                text(c,value,cx,t+163,29,WHITE);text(c,u,cx,t+181,12,MUTED);
            }else{
                text(c,value,cx,t+103,48,WHITE);text(c,u,cx,t+129,17,MUTED);
                if(style==1){p.setColor(LINE);c.drawRect(l+20,t+147,l+232,t+159,p);if(ok){p.setColor(BLUE);c.drawRect(l+20,t+147,l+20+212*f,t+159,p);}text(c,axis(lo),l+25,t+178,11,MUTED);text(c,axis(hi),l+226,t+178,11,MUTED);}
            }
            c.restoreToCount(cardSave);
        }
        c.restoreToCount(save);
    }
}
