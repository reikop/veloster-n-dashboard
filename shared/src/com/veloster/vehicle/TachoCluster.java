package com.veloster.vehicle;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * 타코 screen: one big 270° tachometer for the manual driver. Speed below, the gear in the hub, the shift lights as twelve
 * dots round the dial (green, yellow, red over the last 1,000 rpm before the shift target, blue at it), and the downshift
 * targets on the dial: the rpm one to three gears down would turn at this speed (DCT: one), a tick and a numbered dot in
 * the gear's colour, green when the blip with the clutch in lands within 100 rpm. In N (mid-shift or coasting) the gear
 * last engaged is the base, and re-engaging it shows too. Round the dial four sector cells (boost, coolant, intake, engine
 * load) with their own arc graph; outside, a grid: trapezoids for ambient, fuel, battery, range, and between them the
 * downshift list (left) and the pedals as they sit in the car (right: clutch, brake, accelerator; DCT brake, accelerator).
 *
 * Built for the head unit: the dial, ticks, numbers and cell shapes are drawn once into a bitmap (again when the rpm range,
 * redline, shift target, accent or language changes); each frame adds only the needle, the marks and the numbers. No glow.
 */
final class TachoCluster extends ClusterKit {
    int shiftTarget=6000;   // set by PitRenderer from 설정 → RPM
    private static final float W=800,H=428,CX=400,CY=206,R=166,A0=135,SW=270,AR0=R+40,AR1=R+110;
    private static final int BG=0xff06080a,CELL=0xff0c1216,EDGE=0xff1f2a31,TRACK=0xff17212a,FG=0xffe9eef1,MUT=0xff8b979d,
        RED=0xffff3b30,BLUE=0xff4aa8ff,GRN=0xff3ddc84,YEL=0xffffd23f,CYAN=0xff5fe3e0,TICK=0xffc9d3d8,DIM=0xff141c21;
    /** One colour per gear, the 람다 screen's. */
    private static final int[] INK={0,0xffff5fa8,0xff9b5cff,0xffff6a4d,0xffffe04a,0xff2fe0c4,0xff5ab4ff,0xffc0c8ff};
    private static final float[][] TL={{14,14},{170,14},{112,118},{14,118}},MID={{14,126},{112,126},{112,286},{14,286}},BL={{14,294},{112,294},{182,414},{14,414}};   // (182,414) lies on the same circle round the dial as (112,294), radius 301: with 170 the arc missed both corners (a notch at the top, a slanted bottom edge)
    private Bitmap face;private String faceKey="";private final RectF arc=new RectF();
    private int lastGear;private float peak;private long peakAt;
    private final int[] tg=new int[4];private final float[] tr=new float[4];private final boolean[] tok=new boolean[4];

    private static Typeface num(){Typeface f=Fonts.BOLD;return f!=null?f:Typeface.DEFAULT_BOLD;}
    private float ang(float rpm){return A0+SW*clamp(rpm/prof.maxRpm,0,1);}
    private static float px(float deg,float r){return CX+(float)Math.cos(Math.toRadians(deg))*r;}
    private static float py(float deg,float r){return CY+(float)Math.sin(Math.toRadians(deg))*r;}
    /** A sector label turned to run along the dial (tangent: 90° off the radius), flipped to stay upright. */
    private static float tilt(float deg){float r=(deg+90)%360;if(r>180)r-=360;if(r<-180)r+=360;if(r>90)r-=180;else if(r<-90)r+=180;return r;}
    private int accent(){int m=Theme.modeAccent();return m!=0?0xff000000|m:Theme.current().accent;}
    private GearRatios learned(){return st==null?null:st.ratios;}

    @Override protected void draw(Canvas c){
        c.save();c.translate(0,52);
        int acc=accent();
        String key=prof.maxRpm+"/"+prof.redline+"/"+shiftTarget+"/"+acc+"/"+com.veloster.i18n.UiText.language()+"/"+(prof.trans==VehicleProfile.Trans.MT);
        if(face==null||!key.equals(faceKey)){face=buildFace(acc);faceKey=key;}
        c.drawBitmap(face,0,0,null);
        float rpm=v(0),kmh=v(7),raw=s[8].valid?s[8].value:Float.NaN;
        boolean mt=prof.trans==VehicleProfile.Trans.MT,moving=!Float.isNaN(kmh)&&kmh>=10;
        int g=Float.isNaN(raw)?-1:(int)raw;
        if(g>=1&&g<=prof.topGear&&!Float.isNaN(kmh)&&kmh>3)lastGear=g;
        boolean neutral=mt&&g==0&&moving&&lastGear>=1;
        int base=neutral?lastGear:g;
        // downshift targets (RevMatch's ratios: the 6MT table, else what was learned driving)
        boolean clutch=mt&&s[Telemetry.CLUTCH].valid&&s[Telemetry.CLUTCH].value>0;
        int n=0;
        if(moving&&base>=1&&base<=prof.topGear)
            for(int k=neutral?0:1;k<=(mt?3:1)&&n<4;k++){int h=base-k;if(h<1)break;float t=RevMatch.rpmIn(prof,learned(),kmh,h);
                if(Float.isNaN(t)||t<RevMatch.LOW_RPM||t>=prof.redline)continue;
                tg[n]=h;tr[n]=t;tok[n]=RevMatch.applies(prof)&&clutch&&!Float.isNaN(rpm)&&Math.abs(rpm-t)<=RevMatch.MATCH_RPM;n++;}
        boolean match=false;for(int i=0;i<n;i++)match|=tok[i];
        // shift lights
        float fillTo=Float.isNaN(rpm)?0:clamp((rpm-(shiftTarget-1000))/1000f,0,1);
        boolean flash=!Float.isNaN(rpm)&&rpm>=shiftTarget&&g>=1&&g<prof.topGear,on=blink(now,180);
        for(int i=0;i<12;i++){float d=A0+SW*(i+.5f)/12;boolean lit=fillTo*12>i;
            int col=flash?(on?BLUE:0xff11202c):lit?(i<5?GRN:i<9?YEL:RED):DIM;dot(c,px(d,R+30),py(d,R+30),6.5f,col);}
        // downshift marks, the gear up as a cyan ring
        for(int i=0;i<n;i++){float d=ang(tr[i]);int col=tok[i]?GRN:INK[tg[i]];
            p.setStrokeCap(Paint.Cap.ROUND);line(c,px(d,R-34),py(d,R-34),px(d,R-3),py(d,R-3),tok[i]?7:5,col);p.setStrokeCap(Paint.Cap.BUTT);
            if(tok[i])dot(c,px(d,R+13),py(d,R+13),16,0x553ddc84);
            dot(c,px(d,R+13),py(d,R+13),11,col);bold(c,String.valueOf(tg[i]),px(d,R+13),py(d,R+13)+6,17,BG,Paint.Align.CENTER);}
        if(!neutral&&moving&&g>=1&&g<prof.topGear){float u=RevMatch.rpmIn(prof,learned(),kmh,g+1);if(!Float.isNaN(u)&&u>=900){float d=ang(u);ring(c,px(d,R-18),py(d,R-18),7,2.5f,CYAN);}}
        // 3 s peak, needle
        if(!Float.isNaN(rpm)&&(rpm>peak||now-peakAt>3000)){peak=rpm;peakAt=now;}
        if(peak>0){float d=ang(peak);line(c,px(d,R-30),py(d,R-30),px(d,R-4),py(d,R-4),3,acc);}
        if(!Float.isNaN(rpm)){float d=ang(rpm);int nc=rpm>=prof.redline?RED:acc;
            c.save();c.rotate(d,CX,CY);path.reset();path.moveTo(CX+84,CY-5);path.lineTo(CX+R-6,CY-1.6f);path.lineTo(CX+R-6,CY+1.6f);path.lineTo(CX+84,CY+5);path.close();poly(c,nc);c.restore();}
        // hub
        dot(c,CX,CY,82,0xff070b0e);ring(c,CX,CY,82,match?4:2,match?GRN:mix(acc,BG,.55f));
        if(mt)bold(c,"C",CX,CY-50,16,clutch?YEL:0xff26313a,Paint.Align.CENTER);
        String gs=neutral||g==0&&mt?"N":gear();if(manualGate(gs))gs=gs.substring(1);
        text(c,gs,CX,CY+22,84,match?GRN:flash&&on?BLUE:FG,Paint.Align.CENTER,num());
        text(c,Float.isNaN(rpm)?"—":String.format(java.util.Locale.US,"%,d",Math.round(rpm/10)*10),CX,CY+58,26,!Float.isNaN(rpm)&&rpm>=prof.redline?RED:FG,Paint.Align.CENTER,num());
        text(c,fmt("%.0f",kmh),CX,CY+166,54,FG,Paint.Align.CENTER,num());
        // sector cells: the arc graph on the inner edge, the number on the outer
        float boost=v(Telemetry.BOOST),cool=v(2),iat=v(Telemetry.INTAKE),load=v(Telemetry.ENGINE_LOAD);
        sector(c,184,220,fmt("%+.2f",boost),"bar",(boost+.6f)/2.1f,false,acc);     // boost + intake flank 9 o'clock,
        sector(c,320,356,fmt("%.0f",cool),"°C",(cool-40)/80,true,acc);              // coolant + load mirror them at 3 o'clock
        sector(c,140,176,fmt("%.0f",iat),"°C",(iat+20)/80,true,acc);
        sector(c,4,40,fmt("%.0f",load),"%",load/100,false,acc);
        // grid cells
        value(c,false,true,fmt("%.0f",v(Telemetry.AMBIENT)),"°C");
        value(c,true,true,fmt("%.0f",v(Telemetry.FUEL_LEVEL)),"%");
        value(c,false,false,fmt("%.1f",v(Telemetry.BAT_VOLTAGE)),"V");
        value(c,true,false,fmt("%.0f",v(Telemetry.RANGE)),"km");
        // left middle: the downshift list on a 6MT; a DCT (paddles step one gear) shows its two clutch temperatures instead
        if(mt){
            if(n==0)text(c,"—",63,214,26,MUT,Paint.Align.CENTER,num());
            for(int i=0;i<n;i++){float y=178+i*30;int col=tok[i]?GRN:INK[tg[i]];dot(c,32,y-7,9,col);bold(c,String.valueOf(tg[i]),32,y-1,14,BG,Paint.Align.CENTER);
                text(c,String.format(java.util.Locale.US,"%,d",Math.round(tr[i]/50)*50),106,y,19,tok[i]?GRN:FG,Paint.Align.RIGHT,num());}
        }else for(int i=0;i<2;i++){float tc=v(i==0?Telemetry.CLUTCH_ODD:Telemetry.CLUTCH_EVEN),y=206+i*58;
            int col=Float.isNaN(tc)?MUT:tc>=Telemetry.CLUTCH_HOT?RED:tc>=Telemetry.CLUTCH_WARM?YEL:FG;
            text(c,t(i==0?"홀수단":"짝수단"),26,y-30,12,MUT,Paint.Align.LEFT,Typeface.DEFAULT);
            text(c,fmt("%.0f",tc),88,y,28,col,Paint.Align.RIGHT,num());text(c,"°C",91,y,11,MUT,Paint.Align.LEFT,Typeface.DEFAULT);}
        // pedals (right middle), left to right as in the car
        float br=v(10),sw=v(Telemetry.BRAKE_SWITCH),acl=v(9);
        float brake=!Float.isNaN(br)?clamp(br/80f,0,1):!Float.isNaN(sw)&&sw>0?1:0;
        float[] lv=mt?new float[]{clutch?1:0,brake,Float.isNaN(acl)?0:acl/100}:new float[]{brake,Float.isNaN(acl)?0:acl/100};
        int[] lc=mt?new int[]{YEL,RED,acc}:new int[]{RED,acc};
        float bw=mt?20:30,x0=W-112+8,gap=(82-lv.length*bw)/(lv.length-1);
        for(int i=0;i<lv.length;i++){float x=x0+i*(bw+gap),h=92*clamp(lv[i],0,1);if(h>.5f)fill(c,x,250-h,x+bw,250,lc[i]);}
        c.restore();
    }
    private void sector(Canvas c,float d0,float d1,String val,String unit,float frac,boolean fromEnd,int acc){
        float r=AR1-8,p0=d0+3.5f,p1=d1-3.5f,f=Float.isNaN(frac)?0:clamp(frac,0,1);
        if(f>.01f){float s0=fromEnd?p1-(p1-p0)*f:p0,s1=fromEnd?p1:p0+(p1-p0)*f;
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(8);p.setStrokeCap(Paint.Cap.ROUND);p.setColor(acc);this.r.set(CX-r,CY-r,CX+r,CY+r);
            c.drawArc(this.r,s0,s1-s0,false,p);p.setStrokeCap(Paint.Cap.BUTT);p.setStyle(Paint.Style.FILL);
            float tip=fromEnd?s0:s1;dot(c,px(tip,r),py(tip,r),4.5f,FG);}
        float am=(d0+d1)/2,tx=px(am,AR0+28),ty=py(am,AR0+28);
        c.save();c.rotate(tilt(am),tx,ty);   // the reading leans with the round dial
        float vw=width(val,20,num());text(c,val,tx-6,ty+13,20,FG,Paint.Align.CENTER,num());text(c,unit,tx-6+vw/2+3,ty+13,11,MUT,Paint.Align.LEFT,Typeface.DEFAULT);
        c.restore();
    }
    private void value(Canvas c,boolean right,boolean top,String val,String unit){
        float x=right?W-28:28,vy=top?88:370;
        if(right){text(c,unit,x,vy,14,MUT,Paint.Align.RIGHT,Typeface.DEFAULT);text(c,val,x-width(unit,14,Typeface.DEFAULT)-5,vy,32,FG,Paint.Align.RIGHT,num());}
        else{text(c,val,x,vy,32,FG,Paint.Align.LEFT,num());text(c,unit,x+width(val,32,num())+5,vy,14,MUT,Paint.Align.LEFT,Typeface.DEFAULT);}
    }

    // ---- the still parts, drawn once ----
    @Override void trim(){face=recycle(face);faceKey="";}
    private Bitmap buildFace(int acc){
        Bitmap b=bitmap((int)W,(int)H);Canvas c=new Canvas(b);
        fill(c,0,0,W,H,BG);
        boolean mt=prof.trans==VehicleProfile.Trans.MT;
        cell(c,TL,false,true);cell(c,TL,true,true);cell(c,MID,false,false);cell(c,MID,true,false);cell(c,BL,false,true);cell(c,BL,true,true);
        label(c,false,40,t("외기온"),acc);label(c,true,40,t("연료"),acc);label(c,false,322,t("배터리"),acc);label(c,true,322,t("주행 가능"),acc);
        bold(c,t(mt?"다운":"클러치"),26,148,13,acc,Paint.Align.LEFT);bold(c,t("페달"),W-98,148,13,acc,Paint.Align.LEFT);
        int bars=mt?3:2;float bw=mt?20:30,x0=W-112+8,gap=(82-bars*bw)/(bars-1);String[] names=mt?new String[]{"C","B","A"}:new String[]{"B","A"};
        for(int i=0;i<bars;i++){float x=x0+i*(bw+gap);fill(c,x,158,x+bw,250,TRACK);text(c,names[i],x+bw/2,272,14,MUT,Paint.Align.CENTER,num());}
        sectorCell(c,184,220,t("부스트"),acc);sectorCell(c,320,356,t("냉각수"),acc);sectorCell(c,140,176,t("흡기"),acc);sectorCell(c,4,40,t("부하"),acc);
        dot(c,CX,CY,R+18,0xff0b1014);ring(c,CX,CY,R+18,1.5f,0xff1c252b);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(10);p.setColor(0xd9ff3b30);r.set(CX-(R-8),CY-(R-8),CX+R-8,CY+R-8);
        c.drawArc(r,ang(prof.redline),ang(prof.maxRpm)-ang(prof.redline),false,p);p.setStyle(Paint.Style.FILL);
        for(int k=0;k<=prof.maxRpm;k+=250){float d=ang(k);boolean maj=k%1000==0,mid=k%500==0;float r0=R-(maj?26:mid?18:13);
            line(c,px(d,r0),py(d,r0),px(d,R-4),py(d,R-4),maj?3:mid?2:1.2f,k>=prof.redline?RED:maj?TICK:0xff6b7880);
            if(maj)text(c,String.valueOf(k/1000),px(d,R-46),py(d,R-46)+10,28,k>=prof.redline?RED:FG,Paint.Align.CENTER,num());}
        float d=ang(shiftTarget);path.reset();path.moveTo(px(d,R+2),py(d,R+2));path.lineTo(px(d-2,R+14),py(d-2,R+14));path.lineTo(px(d+2,R+14),py(d+2,R+14));path.close();poly(c,acc);
        text(c,"x1000 rpm",CX,CY-94,12,MUT,Paint.Align.CENTER,Typeface.DEFAULT);text(c,"km/h",CX,CY+190,13,MUT,Paint.Align.CENTER,Typeface.DEFAULT);
        return b;
    }
    private void cell(Canvas c,float[][] pts,boolean mirror,boolean curve){
        path.reset();
        for(int i=0;i<pts.length;i++){float x=mirror?W-pts[i][0]:pts[i][0],y=pts[i][1];
            if(i==0)path.moveTo(x,y);
            else if(i==2&&curve){   // corner cells: the inner edge is a true arc centred on the dial through its two corners (the middle cells stay straight, their content sits at the edge)
                float x1=mirror?W-pts[1][0]:pts[1][0],y1=pts[1][1];
                float r1=(float)Math.hypot(x1-CX,y1-CY),r2=(float)Math.hypot(x-CX,y-CY),R=(r1+r2)/2;
                float a1=(float)Math.toDegrees(Math.atan2(y1-CY,x1-CX)),a2=(float)Math.toDegrees(Math.atan2(y-CY,x-CX)),sweep=a2-a1;
                while(sweep>180)sweep-=360;while(sweep<-180)sweep+=360;
                arc.set(CX-R,CY-R,CX+R,CY+R);path.arcTo(arc,a1,sweep);
            }
            else path.lineTo(x,y);
        }
        path.close();
        poly(c,CELL);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(EDGE);c.drawPath(path,p);p.setStyle(Paint.Style.FILL);
    }
    private void label(Canvas c,boolean right,float y,String s,int acc){bold(c,s,right?W-28:28,y,14,acc,right?Paint.Align.RIGHT:Paint.Align.LEFT);}
    private void sectorCell(Canvas c,float d0,float d1,String label,int acc){
        path.reset();RectF o=new RectF(CX-AR1,CY-AR1,CX+AR1,CY+AR1),in=new RectF(CX-AR0,CY-AR0,CX+AR0,CY+AR0);
        path.arcTo(o,d0,d1-d0);path.arcTo(in,d1,d0-d1);path.close();poly(c,CELL);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(EDGE);c.drawPath(path,p);
        p.setStrokeWidth(8);p.setStrokeCap(Paint.Cap.ROUND);p.setColor(TRACK);float r=AR1-8;r(r);c.drawArc(this.r,d0+3.5f,d1-d0-7,false,p);p.setStrokeCap(Paint.Cap.BUTT);p.setStyle(Paint.Style.FILL);
        float am=(d0+d1)/2,lx=px(am,AR0+28),ly=py(am,AR0+28);
        c.save();c.rotate(tilt(am),lx,ly);bold(c,label,lx,ly-8,12,acc,Paint.Align.CENTER);c.restore();
    }
    private void r(float rad){r.set(CX-rad,CY-rad,CX+rad,CY+rad);}
}
