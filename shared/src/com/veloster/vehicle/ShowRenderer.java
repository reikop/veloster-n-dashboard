package com.veloster.vehicle;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.util.Locale;

/** "Show" page of the symmetric tab (reached by tapping 퍼포먼스 again): a visual, enthusiast-facing screen that
 * reuses the data the car already sends. Three zones below the shared navigation — a powerband curve with a moving
 * dot on the left, a large analogue turbo-boost gauge with a peak-hold needle in the middle, and a traction-circle
 * (lateral vs longitudinal g) with a fading trail on the right — over a speed / gear / drive-mode strip. The accent
 * colour follows the drive mode (SPORT red, ECO green, else blue). Only received samples are drawn; missing ones
 * leave the needle at rest. No CAN writes, no simulation. */
public final class ShowRenderer {
    /** Every colour goes through the selected theme (identity for N). */
    private static int T(int c){return Theme.current().map(c);}
    private static int D(){return Theme.current().danger;}
    private final Paint p=new Paint(3);
    private final Path path=new Path(),cellPath=new Path();
    private final RectF cellRect=new RectF();
    private final RectF oval=new RectF();
    private static final int BG=0xff05070a,PANEL=0xff0e1318,LINE=0xff263038,TRACK=0xff1b2228,
        WHITE=0xffe6f1f6,MUTED=0xff86969f,BLUE=0xff5bc8f5,RED=0xffff4a5e,GREEN=0xff3ddc84,AMBER=0xffffb84d;
    private static final float BMIN=-1.0f,A0=140,SW=260;
    /** Gauge top: 1.5 bar, wider once this session has seen more boost (tuned cars). */
    private float bmax=1.5f,maxSeen=Float.NaN;
    private Telemetry.Sample[] s;
    private VehicleProfile prof=VehicleProfile.PETROL_DCT;
    private long now,last;
    private float needleBoost,peakBoost=Float.NEGATIVE_INFINITY;
    private boolean boostInit;
    private long peakAt;
    private static final int TRAIL=48;
    private final float[] tx=new float[TRAIL],ty=new float[TRAIL];
    private final long[] tAt=new long[TRAIL];
    private int tHead,tCount;
    private float peakG;
    private long turnLAt=Long.MIN_VALUE/2,turnRAt=Long.MIN_VALUE/2;

    private float v(int i){return i>=0&&i<s.length&&s[i].valid&&!Float.isNaN(s[i].value)&&!Float.isInfinite(s[i].value)?Telemetry.displayValue(i,s[i].value):Float.NaN;}
    private static String fmt(String f,float x){return Float.isNaN(x)?"—":Telemetry.format(f,x);}
    private int accent(){float m=v(Telemetry.DRIVE_MODE);int mode=Float.isNaN(m)?-1:(int)m;return !Theme.current().id.equals("n")?BLUE:mode==1?RED:mode==3?GREEN:BLUE;}  // drive-mode colours only in N

    public void draw(Canvas c,Telemetry.Sample[] samples,long frameTime,VehicleProfile profile){
        s=samples;now=frameTime;prof=profile;
        long dt=last==0?16:Math.max(1,Math.min(200,now-last));last=now;
        int accent=accent();
        p.setStyle(Paint.Style.FILL);p.setColor(T(BG));c.drawRect(0,48,800,480,p);
        powerband(c,12,56,256,432,accent);
        boostGauge(c,268,56,532,432,accent,dt);
        traction(c,544,56,788,432,accent);
        strip(c,accent);
        edgeGlow(c);
    }

    // ---- C: ambient edge glow reacting to brake / boost / turn signals -------------------------
    private void edgeGlow(Canvas c){
        float brake=v(10),boost=v(Telemetry.BOOST);
        if(v(Telemetry.TURN_LEFT)>0)turnLAt=now;
        if(v(Telemetry.TURN_RIGHT)>0)turnRAt=now;
        if(!Float.isNaN(brake)&&brake>8)border(c,RED,Math.min(0.85f,brake/40f));
        else if(!Float.isNaN(boost)&&boost>0.25f)border(c,BLUE,Math.min(0.75f,(boost-0.25f)/1.0f));
        boolean blinkOn=(now/350)%2==0;
        if(now-turnLAt<900&&blinkOn)sideGlow(c,true);
        if(now-turnRAt<900&&blinkOn)sideGlow(c,false);
    }
    private void border(Canvas c,int color,float intensity){
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3);
        for(int i=0;i<12;i++){float a=intensity*(1-i/12f);p.setColor(T(withAlpha(color,(int)(a*150))));float ins=1.5f+i*2.3f;c.drawRect(ins,48+ins,800-ins,480-ins,p);}
        p.setStyle(Paint.Style.FILL);
    }
    private void sideGlow(Canvas c,boolean left){
        for(int i=0;i<44;i++){float a=0.55f*(1-i/44f);p.setColor(T(withAlpha(AMBER,(int)(a*140))));float x=left?i:799-i;c.drawRect(x,48,x+1,480,p);}
    }

    // ---- F: powerband curve with a moving dot --------------------------------------------------
    private void powerband(Canvas c,float l,float t,float r,float b,int accent){
        panel(c,l,t,r,b);
        text(c,"POWERBAND",l+16,t+24,13,MUTED,true);
        float rpm=v(0);
        text(c,Float.isNaN(rpm)?"—":String.format(Locale.US,"%.0f",rpm),r-16,t+26,20,WHITE,Paint.Align.RIGHT);
        text(c,"rpm",r-16,t+42,11,MUTED,Paint.Align.RIGHT);
        float gl=l+20,gr=r-20,gt=t+70,gb=b-52;                  // curve plot box
        // stylised 1.6 T-GDI torque feel: rises, broad plateau, tapers past the power peak.
        int N=64;float[] cx=new float[N+1],cy=new float[N+1];
        for(int i=0;i<=N;i++){float f=i/(float)N;float shape=curve(f);cx[i]=gl+(gr-gl)*f;cy[i]=gb-(gb-gt)*shape;}
        // redline zone
        float rx=gl+(gr-gl)*(prof.redline/prof.maxRpm);
        p.setStyle(Paint.Style.FILL);p.setColor(T(0x22ff4a5e));c.drawRect(rx,gt,gr,gb,p);
        // area under the curve
        path.reset();path.moveTo(cx[0],gb);for(int i=0;i<=N;i++)path.lineTo(cx[i],cy[i]);path.lineTo(cx[N],gb);path.close();
        p.setColor(T(withAlpha(accent,0x22)));c.drawPath(path,p);
        // curve line
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3);p.setColor(T(withAlpha(accent,0xcc)));
        path.reset();path.moveTo(cx[0],cy[0]);for(int i=1;i<=N;i++)path.lineTo(cx[i],cy[i]);c.drawPath(path,p);
        p.setStyle(Paint.Style.FILL);
        // baseline + rpm ticks
        p.setColor(T(LINE));p.setStrokeWidth(1);c.drawLine(gl,gb,gr,gb,p);
        int km=(int)(prof.maxRpm/1000f),stepk=km>6?2:1;
        for(int k=0;k<=km;k+=stepk){float x=gl+(gr-gl)*Math.min(1f,k*1000f/prof.maxRpm);c.drawLine(x,gb,x,gb+4,p);text(c,String.valueOf(k),x,gb+18,10,MUTED,Paint.Align.CENTER);}
        // moving dot on the curve
        if(!Float.isNaN(rpm)){
            float f=Math.max(0,Math.min(1,rpm/prof.maxRpm));float dx=gl+(gr-gl)*f,dy=gb-(gb-gt)*curve(f);
            boolean red=rpm>=prof.redline;int col=red?RED:accent;
            p.setColor(T(withAlpha(col,0x55)));c.drawCircle(dx,dy,11,p);
            p.setColor(T(col));c.drawCircle(dx,dy,6,p);
            p.setColor(T(0xffffffff));c.drawCircle(dx,dy,2.5f,p);
            p.setColor(T(withAlpha(col,0x66)));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.5f);c.drawLine(dx,dy+6,dx,gb,p);p.setStyle(Paint.Style.FILL);
        }
    }
    private static float curve(float f){           // 0..1 rpm -> 0..1 height, broad mid plateau
        float rise=(float)(1-Math.exp(-f*6));
        float fall=1-Math.max(0,(f-0.75f)/0.25f)*0.45f;
        return Math.max(0,Math.min(1,rise*fall));
    }

    // ---- B: analogue turbo boost gauge with peak hold ------------------------------------------
    private void boostGauge(Canvas c,float l,float t,float r,float b,int accent,long dt){
        panel(c,l,t,r,b);
        float cx=(l+r)/2,cy=t+(b-t)*0.52f,rad=Math.min((r-l),(b-t))*0.40f;
        float boost=v(Telemetry.BOOST);
        if(!Float.isNaN(boost)){
            if(!boostInit){needleBoost=boost;boostInit=true;}
            else needleBoost+=(boost-needleBoost)*Math.min(1f,dt/120f);
            if(Float.isNaN(maxSeen)||boost>maxSeen){maxSeen=boost;bmax=DriveStats.boostTop(maxSeen);}
            if(peakBoost==Float.NEGATIVE_INFINITY||boost>peakBoost){peakBoost=boost;peakAt=now;}
            else if(now-peakAt>1800)peakBoost=Math.max(boost,peakBoost-dt*0.0006f);
        }
        text(c,"TURBO BOOST",cx,t+26,13,MUTED,Paint.Align.CENTER);
        // track arc
        oval.set(cx-rad,cy-rad,cx+rad,cy+rad);
        p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(14);p.setColor(T(TRACK));c.drawArc(oval,A0,SW,false,p);
        // positive-boost fill from the zero mark to the needle, glowing when high
        float zf=(0-BMIN)/(bmax-BMIN);
        float nb=Float.isNaN(boost)?Float.NaN:Math.max(BMIN,Math.min(bmax,needleBoost));
        if(!Float.isNaN(nb)&&nb>0){
            float a=SW*((nb-0)/(bmax-BMIN));
            float glow=Math.max(0,Math.min(1,nb/bmax));
            p.setStrokeWidth(14+10*glow);p.setColor(T(withAlpha(accent,0x33)));c.drawArc(oval,A0+SW*zf,a,false,p);
            p.setStrokeWidth(14);p.setColor(T(accent));c.drawArc(oval,A0+SW*zf,a,false,p);
        }else if(!Float.isNaN(nb)&&nb<0){
            float a=SW*((0-nb)/(bmax-BMIN));
            p.setStrokeWidth(14);p.setColor(T(0xff4a5a63));c.drawArc(oval,A0+SW*zf-a,a,false,p);
        }
        p.setStrokeCap(Paint.Cap.BUTT);
        // ticks + labels every 0.5 bar
        for(float bl=BMIN;bl<=bmax+0.01f;bl+=0.5f){
            float ang=(float)Math.toRadians(A0+SW*((bl-BMIN)/(bmax-BMIN)));
            float c1=(float)Math.cos(ang),s1=(float)Math.sin(ang);
            boolean major=Math.abs(bl)<0.01f||Math.abs(bl-bmax)<0.01f||Math.abs(bl-BMIN)<0.01f;
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(major?3:2);p.setColor(T(major?WHITE:MUTED));
            c.drawLine(cx+c1*(rad-12),cy+s1*(rad-12),cx+c1*(rad+2),cy+s1*(rad+2),p);
            p.setStyle(Paint.Style.FILL);
            text(c,(bl>0?"+":"")+String.format(Locale.US,"%.1f",bl),cx+c1*(rad-30),cy+s1*(rad-30)+4,11,major?WHITE:MUTED,Paint.Align.CENTER);
        }
        // peak-hold needle
        if(peakBoost!=Float.NEGATIVE_INFINITY){
            float pa=(float)Math.toRadians(A0+SW*((Math.max(BMIN,Math.min(bmax,peakBoost))-BMIN)/(bmax-BMIN)));
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setColor(T(AMBER));
            c.drawLine(cx+(float)Math.cos(pa)*(rad-18),cy+(float)Math.sin(pa)*(rad-18),cx+(float)Math.cos(pa)*(rad+2),cy+(float)Math.sin(pa)*(rad+2),p);
            p.setStyle(Paint.Style.FILL);
        }
        // live needle
        if(!Float.isNaN(nb)){
            float na=(float)Math.toRadians(A0+SW*((nb-BMIN)/(bmax-BMIN)));
            float nx=cx+(float)Math.cos(na)*(rad-6),ny=cy+(float)Math.sin(na)*(rad-6);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(4);p.setStrokeCap(Paint.Cap.ROUND);p.setColor(T(accent));
            c.drawLine(cx,cy,nx,ny,p);p.setStrokeCap(Paint.Cap.BUTT);p.setStyle(Paint.Style.FILL);
        }
        p.setColor(T(PANEL));c.drawCircle(cx,cy,20,p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setColor(T(accent));c.drawCircle(cx,cy,20,p);p.setStyle(Paint.Style.FILL);
        // numeric readout
        // numeric readout below the arc's end labels (-1.0 and the top), which it overlapped at cy + 0.62 rad
        text(c,fmt("%+.2f",boost),cx,cy+rad*0.83f,40,WHITE,Paint.Align.CENTER);
        text(c,"bar",cx,cy+rad*0.83f+20,13,MUTED,Paint.Align.CENTER);
        if(peakBoost!=Float.NEGATIVE_INFINITY)text(c,"PEAK "+fmt("%+.2f",peakBoost),cx,b-16,12,AMBER,Paint.Align.CENTER);
    }

    // ---- D: traction circle with a fading trail ------------------------------------------------
    private void traction(Canvas c,float l,float t,float r,float b,int accent){
        panel(c,l,t,r,b);
        text(c,"TRACTION",l+16,t+24,13,MUTED,true);
        float cx=(l+r)/2,cy=t+(b-t)*0.50f,rad=Math.min((r-l),(b-t))*0.40f;
        float lat=v(11),lon=v(12);                 // g, signed
        float scale=rad/1.2f;                       // 1.2 g to the edge
        // rings
        p.setStyle(Paint.Style.STROKE);p.setColor(T(LINE));p.setStrokeWidth(1.5f);
        c.drawCircle(cx,cy,rad,p);c.drawCircle(cx,cy,rad*0.5f,p);
        c.drawLine(cx-rad,cy,cx+rad,cy,p);c.drawLine(cx,cy-rad,cx,cy+rad,p);
        p.setStyle(Paint.Style.FILL);
        text(c,"1.2g",cx+rad-14,cy-6,9,MUTED,Paint.Align.RIGHT);
        // push current point into the trail
        if(!Float.isNaN(lat)&&!Float.isNaN(lon)){
            float k=Telemetry.pinG(lat,lon,1.2f),px=cx+lat*k*scale,py=cy+lon*k*scale;   // +lon (accel) down, braking up, +lat (left) ... keep lat right-positive visual
            tx[tHead]=px;ty[tHead]=py;tAt[tHead]=now;tHead=(tHead+1)%TRAIL;if(tCount<TRAIL)tCount++;
            float g=(float)Math.hypot(lat,lon);if(g>peakG)peakG=g;
        }
        // trail, older = fainter
        for(int i=0;i<tCount;i++){
            int idx=(tHead-1-i+TRAIL*2)%TRAIL;long age=now-tAt[idx];if(age>2500)continue;
            int a=(int)(0x88*(1-age/2500f));p.setColor(T(withAlpha(accent,Math.max(0,a))));
            c.drawCircle(tx[idx],ty[idx],2.5f,p);
        }
        // current point
        if(!Float.isNaN(lat)&&!Float.isNaN(lon)){
            float k=Telemetry.pinG(lat,lon,1.2f),px=cx+lat*k*scale,py=cy+lon*k*scale;
            p.setColor(T(withAlpha(accent,0x55)));c.drawCircle(px,py,10,p);
            p.setColor(T(accent));c.drawCircle(px,py,5,p);
            p.setColor(T(0xffffffff));c.drawCircle(px,py,2,p);
        }
        float g=(Float.isNaN(lat)||Float.isNaN(lon))?Float.NaN:(float)Math.hypot(lat,lon);
        text(c,Float.isNaN(g)?"—":String.format(Locale.US,"%.2f g",g),cx,b-30,22,WHITE,Paint.Align.CENTER);
        if(peakG>0)text(c,String.format(Locale.US,"PEAK %.2f g",peakG),cx,b-12,12,AMBER,Paint.Align.CENTER);
    }

    // ---- bottom strip: speed / gear / mode -----------------------------------------------------
    private void strip(Canvas c,int accent){
        float y=440,h=34;
        cellLabel(c,12,y,260,h,"SPEED",fmt("%.0f",v(7)),"km/h",WHITE);
        String gear=Telemetry.gearText(v(8),v(Telemetry.GEAR_SELECTOR),prof);   // P/R/N and "—" like the other pages, never an invented N
        cellLabel(c,272,y,256,h,"GEAR",gear,"",accent);
        float m=v(Telemetry.DRIVE_MODE);String mode=Float.isNaN(m)?"—":Telemetry.driveModeLabel((int)m);
        cellLabel(c,532,y,256,h,"DRIVE MODE",mode,"",accent);
    }
    private void cellLabel(Canvas c,float l,float y,float w,float h,String cap,String val,String unit,int color){
        panel(c,l,y,l+w,y+h);
        text(c,cap,l+12,y+h/2+5,12,MUTED,Paint.Align.LEFT);
        text(c,unit,l+w-10,y+h/2+5,11,MUTED,Paint.Align.RIGHT);
        p.setTextSize(11);float uw=unit.length()==0?0:p.measureText(unit)+8;
        text(c,val,l+w-10-uw,y+h/2+6,18,color,Paint.Align.RIGHT);
    }

    // ---- helpers -------------------------------------------------------------------------------
    private void panel(Canvas c,float l,float t,float r,float b){
        if(ThemeShapes.cell(c,p,cellPath,cellRect,l,t,r,b,T(PANEL),T(LINE)))return;
        p.setStyle(Paint.Style.FILL);p.setColor(T(PANEL));c.drawRect(l,t,r,b,p);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(T(LINE));c.drawRect(l+.5f,t+.5f,r-.5f,b-.5f,p);p.setStyle(Paint.Style.FILL);
    }
    private void text(Canvas c,String str,float x,float y,float size,int color,boolean bold){
        p.setStyle(Paint.Style.FILL);p.setColor(T(color));p.setTypeface(bold?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);p.setTextSize(size);p.setTextAlign(Paint.Align.LEFT);c.drawText(str,x,y,p);
    }
    private void text(Canvas c,String str,float x,float y,float size,int color,Paint.Align align){
        p.setStyle(Paint.Style.FILL);p.setColor(T(color));p.setTypeface(Typeface.DEFAULT);p.setTextSize(size);p.setTextAlign(align);c.drawText(str,x,y,p);p.setTextAlign(Paint.Align.LEFT);
    }
    private static int withAlpha(int color,int alpha){return (color&0x00ffffff)|((alpha&0xff)<<24);}
}
