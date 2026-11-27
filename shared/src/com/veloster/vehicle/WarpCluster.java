package com.veloster.vehicle;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import java.util.Locale;
import java.util.Random;

/**
 * Starship warp: stars stream past at the car's speed (bluer under boost) behind a cockpit frame; panels for warp
 * stage (gear), energy (boost), an inertia radar (G) and status values; a red-alert border on hard braking.
 * ponytail: 160 stars, the cost of one line each; fewer if the head unit struggles.
 */
final class WarpCluster extends ClusterKit {
    private static final int AC=0xffa4d9ef,TX=0xffdfe8ff,MU=0xff6f7f9e,PANEL=0xff0a0d14,EDGE=0xff24304a;   // AC is the N palette accent so the theme remaps it
    private static final int N=160;
    private final float[] sx=new float[N],sy=new float[N],sz=new float[N];
    private final Random rnd=new Random(7);
    { themed=true; }   // the warp layout follows the theme's accent
    private float dist,sweep;
    private long last;
    {for(int i=0;i<N;i++)spawn(i,0.05f+rnd.nextFloat()*0.95f);}
    private void spawn(int i,float z){sx[i]=(rnd.nextFloat()*2-1)*1.6f;sy[i]=(rnd.nextFloat()*2-1)*1.0f;sz[i]=z;}

    private long tickAt;
    /** DISTANCE counts every frame, whatever page shows. */
    @Override protected void tick(){
        float v=v(7),dt=tickAt==0||now-tickAt>2000?0:(now-tickAt)/1000f;tickAt=now;
        if(!Float.isNaN(v))dist+=v*dt/3600;
    }
    @Override protected void draw(Canvas c){
        float v=v(7),boost=v(Telemetry.BOOST),lat=v(11),lon=v(12),brake=v(10),top=boostTop();
        float dt=last==0||now-last>2000?0:(now-last)/1000f;last=now;
        float sp=0.04f+(Float.isNaN(v)?0:v)/240*1.3f+(Float.isNaN(boost)?0:Math.max(0,boost))*0.3f;
        for(int i=0;i<N;i++){sz[i]-=sp*dt;if(sz[i]<=0.03f)spawn(i,1);}
        sweep+=dt*130;
        fill(c,0,52,800,480,0xff04060f);
        float cx=400,cy=222,f=280;boolean blue=!Float.isNaN(boost)&&boost>0.5f;
        for(int i=0;i<N;i++){
            float x=cx+sx[i]/sz[i]*f,y=cy+sy[i]/sz[i]*f,z2=Math.min(1,sz[i]+sp*0.22f),x2=cx+sx[i]/z2*f,y2=cy+sy[i]/z2*f;
            int a=(int)(clamp(1.15f-sz[i],0,1)*255);line(c,x2,y2,x,y,0.6f+2.2f*(1-sz[i]),(a<<24)|(blue?0xaacdff:0xffffff));
        }
        ring(c,cx,cy,18,1.5f,0x808fb8ff);line(c,cx-34,cy,cx-22,cy,1.5f,0x808fb8ff);line(c,cx+22,cy,cx+34,cy,1.5f,0x808fb8ff);line(c,cx,cy-34,cx,cy-22,1.5f,0x808fb8ff);
        // cockpit frame
        int frame=0xff0b0d12;
        path.reset();path.moveTo(0,52);path.lineTo(96,52);path.lineTo(44,330);path.lineTo(0,350);path.close();poly(c,frame);
        path.reset();path.moveTo(800,52);path.lineTo(704,52);path.lineTo(756,330);path.lineTo(800,350);path.close();poly(c,frame);
        fill(c,0,52,800,66,frame);for(int x=20;x<800;x+=40)dot(c,x,59,2,0xff2a3448);
        path.reset();path.moveTo(0,342);for(int x=0;x<=800;x+=40){float y=342-40*(1-((x-400)/400f)*((x-400)/400f));path.lineTo(x,y);}path.lineTo(800,480);path.lineTo(0,480);path.close();poly(c,0xff10131a);
        // warp stage + energy
        round(c,24,352,262,466,8,PANEL);outline(c,24,352,262,466,8,1,EDGE);
        String g=gear();boolean m=manualGate(g);   // manual gate: a small M after the digit, clear of the caption
        mono(c,"WARP",40,376,14,MU,Paint.Align.LEFT);bold(c,m?g.substring(1):g,40,442,66,AC,Paint.Align.LEFT);if(m)bold(c,"M",80,404,18,AC,Paint.Align.LEFT);
        text(c,t("단 = 워프 단계"),92,442,12,MU,Paint.Align.LEFT,Typeface.DEFAULT);
        mono(c,"ENERGY",150,376,12,MU,Paint.Align.LEFT);
        float ef=Float.isNaN(boost)?0:clamp((boost+0.7f)/(top+0.7f),0,1);
        for(int i=0;i<10;i++)fill(c,150+i*10,384,158+i*10,408,i<Math.round(ef*10)?(i>7?0xffffb35a:AC):0xff18203a);
        mono(c,fmt("%+.2f",boost)+String.format(Locale.US," / %.1f bar",top),258,428,12,TX,Paint.Align.RIGHT);   // right-aligned to the bar's end so it stays inside the panel
        // inertia radar
        round(c,282,352,518,466,8,PANEL);outline(c,282,352,518,466,8,1,EDGE);
        float rx=400,ry=410,rad=48;
        dot(c,rx,ry,rad,0xff06140f);ring(c,rx,ry,rad,1.5f,0xff1f5a3a);ring(c,rx,ry,rad/2,1,0xff1f5a3a);line(c,rx-rad,ry,rx+rad,ry,1,0xff1f5a3a);line(c,rx,ry-rad,rx,ry+rad,1,0xff1f5a3a);
        r.set(-rad,-rad,rad,rad);p.setStyle(Paint.Style.FILL);p.setColor(0x5550ff96);c.save();c.translate(rx,ry);c.rotate(sweep%360);c.drawArc(r,-30,30,true,p);c.restore();   // one wedge turned by the canvas: a new sweep angle every frame was a new arc texture on 4.2
        if(!Float.isNaN(lat)&&!Float.isNaN(lon))dot(c,rx+clamp(lat,-1,1)*rad,ry+clamp(lon,-1,1)*rad,4,0xff6dffa0);
        bold(c,t("관성"),296,374,12,MU,Paint.Align.LEFT);
        mono(c,Float.isNaN(lat)||Float.isNaN(lon)?"—":String.format(Locale.US,"%.2f g",Math.hypot(lat,lon)),504,374,13,TX,Paint.Align.RIGHT);
        // status
        round(c,538,352,776,466,8,PANEL);outline(c,538,352,776,466,8,1,EDGE);
        String[][] rows={{"VELOCITY",fmt("%.0f",v)+" km/h"},{"HULL TEMP",fmt("%.0f",v(2))+" °C"},{"FUEL",fmt("%.0f",v(Telemetry.RANGE))+" km"},{"DISTANCE",String.format(Locale.US,"%.1f km",dist)}};
        for(int i=0;i<4;i++){mono(c,rows[i][0],552,378+i*24,13,MU,Paint.Align.LEFT);mono(c,rows[i][1],762,378+i*24,16,i==0?AC:TX,Paint.Align.RIGHT);}
        if(!Float.isNaN(brake)&&brake>35){outline(c,4,56,796,476,0,8,blink(now,500)?0xffff2a3a:0x4dff2a3a);bold(c,t("RED ALERT · 급제동"),cx,98,22,0xffff4a5a,Paint.Align.CENTER);}
    }
}
