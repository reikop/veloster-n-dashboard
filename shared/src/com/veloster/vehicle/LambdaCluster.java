package com.veloster.vehicle;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import java.util.Locale;

/**
 * 수동 screen, after the layout of the Reventón's digital mode (not its artwork): thin glowing lines on black, two
 * mirrored Λ gauges. Left Λ = rpm, right Λ = speed; each fills its legs upward together, and its ring (the current gear,
 * the speed) is joined to that level on both legs. Under the left apex hangs the gear column (6MT: 6-1 N R; 7DCT: 7-1
 * N R P); the predicted gears are drawn on it the same way as the current one, joined to their rpm on the legs instead
 * of a number: each gear down that the engine can take in its colour (6MT one to three down, DCT the next one), the gear
 * up ringed cyan at where it would land (RevMatch: the 6MT's ratio table, else ratios learned while driving). On a 6MT
 * the gear the blip is matched to turns green. Under the right apex: the speed ring and lamps
 * (6MT: clutch, brake, throttle, heel-and-toe; DCT: manual gate, brake, throttle, clutch heat). Centre: a curved-grid
 * G meter, four small arcs, and the last shift's numbers (DCT: with the clutch temperatures above them).
 */
final class LambdaCluster extends ClusterKit {
    private static final int CYAN=0xff5fe3e0,WH=0xfff2f6f8,DIM=0xff17302f,OR=0xffff8a3a,LINE_=0xff36575a,GREY=0xff7f9a9c,
        GREEN=0xff3ddc84,BLUE=0xff4aa8ff,RED=0xffff4059,AMBER=0xffffb84d,INK=0xff000000;
    /** One colour per gear (index = gear), from the owner's sketch: 5 teal, 4 yellow, 3 red, 2 purple. */
    private static final int[] GEAR_INK={0,0xffff5fa8,0xff9b5cff,0xffff6a4d,0xffffe04a,0xff2fe0c4,0xff5ab4ff,0xffc0c8ff};
    private static final float TOP=112,BOT=446,HALF=118,APEX=30,BAND=11,SPEED_MAX=260;   // a steep Λ
    private static final float LCX=172,RCX=628,COL_Y0=TOP+50,COL_Y1=412;   // the two Λ centres, mirrored about x = 400
    int shiftTarget=6000;   // set by PitRenderer from 설정 → RPM
    /** Chakra Petch (SIL OFL, apps/dashboard/res/raw), loaded by VehicleDash; null falls back to the system face. This screen only. */
    private static Typeface face(){return Fonts.MEDIUM!=null?Fonts.MEDIUM:Typeface.MONOSPACE;}
    private static Typeface faceBold(){return Fonts.BOLD!=null?Fonts.BOLD:Typeface.DEFAULT_BOLD;}
    @Override protected void bold(Canvas c,String str,float x,float y,float size,int color,Paint.Align align){text(c,str,x,y,size,color,align,faceBold());}
    @Override protected void mono(Canvas c,String str,float x,float y,float size,int color,Paint.Align align){text(c,str,x,y,size,color,align,face());}
    private final int[] tg=new int[3];private final float[] tr=new float[3];
    /** The accent: cyan, or the drive mode's colour while 설정 → 주행 모드 → 모드 색 is on (Theme.mode). */
    private int acc=CYAN;

    private float legX(float cx,boolean left,float f){float b=left?cx-HALF:cx+HALF,t=left?cx-APEX:cx+APEX;return b+(t-b)*f;}
    private float legY(float f){return BOT+(TOP-BOT)*f;}
    private GearRatios learned(){return st==null?null:st.ratios;}

    /** Tap the rpm Λ: downshift targets always at full colour instead of dim until the clutch / brake (saved by VehicleDash). */
    public static boolean downAlways;
    @Override boolean tap(float x,float y){
        if(x<LCX-HALF-40||x>LCX+HALF+40||y<TOP-20||y>BOT+30)return false;
        downAlways=!downAlways;return true;
    }
    @Override protected void draw(Canvas c){
        fill(c,0,52,800,480,0xff000000);
        int ma=Theme.modeAccent();acc=ma!=0?0xff000000|ma:CYAN;
        float rpm=v(0),kmh=v(7),gear=v(8);boolean mt=prof.trans==VehicleProfile.Trans.MT;int matched=RevMatch.matched(s,prof);
        boolean shift=!Float.isNaN(rpm)&&rpm>=shiftTarget;
        String shown=Telemetry.gearText(gear,v(Telemetry.GEAR_SELECTOR),prof);
        int rc=matched>0?GREEN:shift?(blink(now,120)?BLUE:DIM):acc;
        lambda(c,LCX,Float.isNaN(rpm)?0:rpm/prof.maxRpm,prof.redline/prof.maxRpm,rc,matched>0||shift);
        for(int k=1;k*1000<=prof.maxRpm;k++)scale(c,LCX,k*1000/prof.maxRpm,String.valueOf(k),k*1000>=prof.redline?RED:WH);
        gears(c,rpm,s[7].valid?s[7].value:Float.NaN,gear,shown,mt,matched);   // the car's speed: ratios are learned against it
        bold(c,fmt("%.0f",rpm),LCX,BOT+4,20,matched>0?GREEN:WH,Paint.Align.CENTER);mono(c,"x1000 rpm",LCX,BOT+22,11,GREY,Paint.Align.CENTER);
        lambda(c,RCX,Float.isNaN(kmh)?0:kmh/SPEED_MAX,2,GREEN,false);
        for(int k=20;k<=SPEED_MAX;k+=40)scale(c,RCX,k/SPEED_MAX,String.valueOf(k),WH);
        lamps(c,kmh,shown,mt);
        bold(c,"MAX "+fmt("%.0f",st==null?Float.NaN:st.maxSpeed),RCX,BOT+4,18,WH,Paint.Align.CENTER);mono(c,"km/h",RCX,BOT+22,11,GREY,Paint.Align.CENTER);
        centre(c,mt);
    }
    /** Both legs of a Λ and their inner bands filled to fraction f; red past fraction red (unless over). */
    private void lambda(Canvas c,float cx,float f,float red,int col,boolean over){
        line(c,legX(cx,true,0),BOT,legX(cx,true,1),TOP,2,LINE_);line(c,legX(cx,false,0),BOT,legX(cx,false,1),TOP,2,LINE_);
        f=clamp(f,0,1);int seg=40;
        for(int i=0;i<seg;i++){float a=i/(float)seg,b=(i+1)/(float)seg,e=b;
            int k=a<f?(!over&&a>=red?RED:col):DIM;if(a<f)e=Math.min(b,f);
            band(c,cx,true,a,e,k);band(c,cx,false,a,e,k);if(e<b){band(c,cx,true,e,b,DIM);band(c,cx,false,e,b,DIM);}}
    }
    private void band(Canvas c,float cx,boolean left,float a,float b,int col){
        float sg=left?1:-1,xa=legX(cx,left,a)+3*sg,xb=legX(cx,left,b)+3*sg;
        path.reset();path.moveTo(xa,legY(a));path.lineTo(xb,legY(b));path.lineTo(xb+BAND*sg,legY(b));path.lineTo(xa+BAND*sg,legY(a));path.close();poly(c,col);
    }
    /** Lines from both legs' inner edge at fraction f to the two sides of a ring at (cx, y), solid or dashed. */
    private void join(Canvas c,float cx,float f,float y,float half,float w,int col){join(c,cx,f,y,half,w,col,false);}
    private void join(Canvas c,float cx,float f,float y,float half,float w,int col,boolean dashed){
        f=clamp(f,0,1);float ly=legY(f);
        seg(c,legX(cx,true,f)+3+BAND,ly,cx-half,y,w,col,dashed);seg(c,legX(cx,false,f)-3-BAND,ly,cx+half,y,w,col,dashed);
    }
    private void seg(Canvas c,float x1,float y1,float x2,float y2,float w,int col,boolean dashed){
        if(!dashed){rline(c,x1,y1,x2,y2,w,col);return;}
        float len=(float)Math.hypot(x2-x1,y2-y1),on=10,off=6;
        for(float d=0;d<len;d+=on+off){float a=d/len,b=Math.min(len,d+on)/len;rline(c,x1+(x2-x1)*a,y1+(y2-y1)*a,x1+(x2-x1)*b,y1+(y2-y1)*b,w,col);}
    }
    /** A tick and numeral outside both legs at fraction f. */
    private void scale(Canvas c,float cx,float f,String label,int col){
        float y=legY(f),xl=legX(cx,true,f),xr=legX(cx,false,f);
        line(c,xl-12,y,xl-2,y,2,col);line(c,xr+2,y,xr+12,y,2,col);
        mono(c,label,xl-16,y+7,19,col,Paint.Align.RIGHT);mono(c,label,xr+16,y+7,19,col,Paint.Align.LEFT);
    }
    /** The gear column under the rpm apex, with the downshift targets drawn onto it. */
    private void gears(Canvas c,float rpm,float kmh,float gear,String shown,boolean mt,int matched){
        // the gears, then one circle for N / R / P that shows whichever the car is in (N, dimmed, while driving)
        String cur=shown.startsWith("M")?shown.substring(1):shown;   // the DCT manual gate: M4 rings 4
        boolean still=cur.equals("N")||cur.equals("R")||cur.equals("P");
        int top=mt?6:7;String[] gs=new String[top+1];for(int k=0;k<top;k++)gs[k]=String.valueOf(top-k);gs[top]=still?cur:"N";
        float dy=(COL_Y1-COL_Y0)/(gs.length-1),rad=Math.min(18,dy/2-2);float fs=rad>=17?22:19;
        int n=RevMatch.targets(s,prof,learned(),tg,tr),alpha=downAlways||RevMatch.active(s)?0xff:0x80;
        int g=Float.isNaN(gear)?0:(int)gear;   // the gear up and where it would land
        float up=g>=1&&g<prof.topGear&&!Float.isNaN(kmh)&&kmh>=10?RevMatch.rpmIn(prof,learned(),kmh,g+1):Float.NaN;
        // a bar across each leg at each predicted rpm, in the gear's colour: shows the level against the scale and the redline
        for(int j=0;j<n;j++){boolean ok=tg[j]==matched;int col=((ok?GREEN:GEAR_INK[tg[j]])&0xffffff)|((g-tg[j]>=2?0x99:0xcc)*alpha/0xff<<24);
            level(c,tr[j]/prof.maxRpm,col,!ok&&g-tg[j]>=2);}
        if(!Float.isNaN(up))level(c,up/prof.maxRpm,(acc&0xffffff)|0x80000000,false);
        for(int i=0;i<gs.length;i++){float y=COL_Y0+i*dy;boolean on=i==top?still:gs[i].equals(cur);
            int t=-1;for(int j=0;j<n;j++)if(String.valueOf(tg[j]).equals(gs[i]))t=j;
            boolean isUp=!Float.isNaN(up)&&gs[i].equals(String.valueOf(g+1))&&!gs[i].equals(cur);
            if(t>=0){boolean ok=tg[t]==matched;int col=((ok?GREEN:GEAR_INK[tg[t]])&0xffffff)|(alpha<<24);
                // a gear down: joined to its rpm like the current gear, a little lighter (dashed two or more gears away); green
                // on a match. 3 px nearly opaque: the 1.6 px 40 % line read dark and stepped on the 8-inch screen (6MT owner).
                int a=(g-tg[t]>=2?0xb3:0xe6)*alpha/0xff;
                join(c,LCX,tr[t]/prof.maxRpm,y,rad+1,ok?3.5f:3f,ok?col:(col&0xffffff)|(a<<24),!ok&&g-tg[t]>=2);
                dot(c,LCX,y,rad,col);mono(c,gs[i],LCX,y+fs*0.36f,fs,INK,Paint.Align.CENTER);}
            else if(isUp){join(c,LCX,up/prof.maxRpm,y,rad+1,2.5f,(acc&0xffffff)|0x99000000);   // the gear up, lighter: where it would land
                dot(c,LCX,y,rad,INK);ring(c,LCX,y,rad,2.5f,acc);mono(c,gs[i],LCX,y+fs*0.36f,fs,acc,Paint.Align.CENTER);}
            else{ring(c,LCX,y,rad,on?3:1.2f,on?(gs[i].equals("R")?RED:acc):LINE_);mono(c,gs[i],LCX,y+fs*0.36f,fs,on?WH:GREY,Paint.Align.CENTER);}
            if(on&&!Float.isNaN(rpm))join(c,LCX,rpm/prof.maxRpm,y,rad,2,WH);}   // the current gear points at the rpm level
    }
    /** A short bar across each leg of the rpm Λ at fraction f (the band and a little beyond on both sides), not across the middle:
     *  marks the predicted rpm on the gauge itself, where the gear's join lines land (owner's sketch, 2026-11-10). */
    private void level(Canvas c,float f,int col,boolean dashed){
        f=clamp(f,0,1);float y=legY(f),xl=legX(LCX,true,f),xr=legX(LCX,false,f);
        // the inner end stops exactly at the band's inner edge, where the gear line starts: the round cap (half the width) is
        // taken off so nothing pokes into the middle; a black edge keeps the bar apart from a lit band of the same colour
        float il=xl+3+BAND,ir=xr-3-BAND;
        rline(c,xl-9,y,il-2.5f,y,5,INK);rline(c,ir+2.5f,y,xr+9,y,5,INK);
        rline(c,xl-9,y,il-1.25f,y,2.5f,col);rline(c,ir+1.25f,y,xr+9,y,2.5f,col);
    }
    /** A line with round ends: the gear lines and the rpm bars (owner, 2026-11-10). */
    private void rline(Canvas c,float x1,float y1,float x2,float y2,float w,int col){
        p.setStrokeCap(Paint.Cap.ROUND);line(c,x1,y1,x2,y2,w,col);p.setStrokeCap(Paint.Cap.BUTT);
    }
    /** Mirroring the gear column: the speed ring and the lamps. */
    private void lamps(Canvas c,float kmh,String shown,boolean mt){
        float dy=(COL_Y1-COL_Y0)/7,ry=COL_Y0+1.5f*dy;
        if(!Float.isNaN(kmh))join(c,RCX,kmh/SPEED_MAX,ry,38,2,WH);   // the speed ring points at the speed level
        dot(c,RCX,ry,38,INK);ring(c,RCX,ry,38,3,WH);bold(c,fmt("%.0f",kmh),RCX,ry+11,30,WH,Paint.Align.CENTER);
        float y=COL_Y0+2.3f*dy;
        float brake=v(10),pedal=v(9);
        if(mt){Telemetry.Sample k0=s[Telemetry.CLUTCH];boolean clutch=k0.valid&&k0.value>0;
            boolean heel=clutch&&brake>ShiftCoach.HEEL_BRAKE_BAR&&pedal>ShiftCoach.HEEL_PEDAL;
            lamp(c,y+1.05f*dy,"CLU",clutch,BLUE);lamp(c,y+3.9f*dy,"H/T",heel,OR);}
        else{int lv=Math.max(Telemetry.clutchLevel(v(Telemetry.CLUTCH_ODD)),Telemetry.clutchLevel(v(Telemetry.CLUTCH_EVEN)));
            lamp(c,y+1.05f*dy,"MAN",shown.startsWith("M"),acc);lamp(c,y+3.9f*dy,"CLT",lv>0,lv==2?RED:AMBER);}
        boolean braking=Telemetry.braking(v(Telemetry.BRAKE_SWITCH),brake);   // the pills fill with the pressure (of BRAKE_FULL bar) / the pedal %
        fillLamp(c,y+2.0f*dy,"BRK",!braking?0:Float.isNaN(brake)?1:Math.max(.1f,brake/BRAKE_FULL),!braking||Float.isNaN(brake)?null:fmt("%.0f",brake)+" bar",RED);
        fillLamp(c,y+2.95f*dy,"THR",Float.isNaN(pedal)||pedal<1?0:pedal/100,Float.isNaN(pedal)||pedal<1?null:fmt("%.0f",pedal)+"%",GREEN);
    }
    /** A lamp as a pill with its name in English (the whole screen is English; three letters: CLU BRK THR H/T, DCT MAN BRK THR CLT), every pill the
     *  same width whatever its word: filled in its colour while on. */
    private static final float LAMP_W=72;
    static final float BRAKE_FULL=100;   // the most the brake pressure reads (owner, 2026-11-18); 1-2 bar at rest
    /** A pill filled from the left to f (0..1); the text (or the name) dark over the fill, grey beside it. */
    private void fillLamp(Canvas c,float y,String name,float f,String text,int col){
        String t=text==null?name:text;float w=LAMP_W,h=28,l=RCX-w/2,size=16;f=clamp(f,0,1);while(size>10&&width(t,size,faceBold())>w-12)size--;
        outline(c,l,y-h/2,l+w,y+h/2,h/2,1.2f,LINE_);bold(c,t,RCX,y+size*0.36f,size,GREY,Paint.Align.CENTER);
        if(f>0){c.save();c.clipRect(l,y-h/2,l+w*f,y+h/2);round(c,l,y-h/2,l+w,y+h/2,h/2,col);bold(c,t,RCX,y+size*0.36f,size,INK,Paint.Align.CENTER);c.restore();}
    }
    private void lamp(Canvas c,float y,String name,boolean on,int col){
        float w=LAMP_W,h=28,size=16;while(size>10&&width(name,size,faceBold())>w-16)size--;
        if(on)round(c,RCX-w/2,y-h/2,RCX+w/2,y+h/2,h/2,col);else outline(c,RCX-w/2,y-h/2,RCX+w/2,y+h/2,h/2,1.2f,LINE_);
        bold(c,name,RCX,y+size*0.36f,size,on?INK:GREY,Paint.Align.CENTER);
    }
    /** Centre column: G grid, four small arcs, the last shift's numbers (DCT: SYNC for the clutch time, clutch temperatures above). */
    private void centre(Canvas c,boolean mt){
        float cx=400,top=64,bot=124,wt=130,wb=96;
        for(int i=0;i<=6;i++){float f=i/6f;line(c,cx-wt/2+wt*f,top,cx-wb/2+wb*f,bot,1,i==3?acc:LINE_);}
        for(int j=0;j<=4;j++){float f=j/4f,y=top+(bot-top)*f,w=wt+(wb-wt)*f,bow=5*(1-Math.abs(f-.5f)*2);
            line(c,cx-w/2,y,cx,y+bow,1,j==2?acc:LINE_);line(c,cx,y+bow,cx+w/2,y,1,j==2?acc:LINE_);}
        float lat=v(11),lon=v(12);
        if(!Float.isNaN(lat)&&!Float.isNaN(lon)){float k=Telemetry.pinG(lat,lon,1.2f)/1.2f,fy=clamp(.5f+lon*k*.5f,0,1),w=wt+(wb-wt)*fy;
            dot(c,cx+lat*k*w/2,top+(bot-top)*fy,6,GREEN);}
        mono(c,String.format(Locale.US,"%.2f G",Float.isNaN(lat)||Float.isNaN(lon)?0:Math.hypot(lat,lon)),cx+wt/2,bot+18,12,WH,Paint.Align.RIGHT);
        float m=v(Telemetry.DRIVE_MODE);int mode=Float.isNaN(m)?-1:(int)m;   // the drive mode, in its own colour (SPORT red, ECO green)
        mono(c,mode<0?"MODE —":Telemetry.driveModeLabel(mode),cx-wt/2,bot+18,12,mode==1?RED:mode==3?GREEN:GREY,Paint.Align.LEFT);
        mini(c,364,196,"COOLANT",v(2),40,120,"%.0f");mini(c,436,196,"BOOST",v(Telemetry.BOOST),-1,1.5f,"%+.1f");
        float y2=mt?290:254;   // DCT: the second row up, for the clutch temperatures under it
        mini(c,364,y2,"INTAKE",v(Telemetry.INTAKE),0,80,"%.0f");mini(c,436,y2,"BATT",v(Telemetry.BAT_VOLTAGE),10,15,"%.1f");
        if(!mt){mono(c,"CLUTCH",cx,284,10,GREY,Paint.Align.CENTER);
            clutchTemp(c,364,"ODD",v(Telemetry.CLUTCH_ODD));clutchTemp(c,436,"EVEN",v(Telemetry.CLUTCH_EVEN));}
        ShiftCoach co=st==null?null:st.coach;ShiftCoach.Shift sh=co==null?null:co.last;
        mono(c,"LAST SHIFT",cx,344,11,GREY,Paint.Align.CENTER);
        if(sh!=null){bold(c,sh.from+" > "+sh.to,cx,376,26,WH,Paint.Align.CENTER);
            if(sh.heelToe)mono(c,"H/T",cx,394,12,OR,Paint.Align.CENTER);
            stat(c,414,"REV",Float.isNaN(sh.revErr)?"—":String.format(Locale.US,"%+,d rpm",Math.round(sh.revErr)));
            stat(c,432,mt?"CLUTCH":"SYNC",Float.isNaN(sh.timeS)?"—":String.format(Locale.US,"%.2f s",sh.timeS));
            stat(c,450,"JOLT",Float.isNaN(sh.jolt)?"—":String.format(Locale.US,"%.2f G",sh.jolt));}
    }
    private void stat(Canvas c,float y,String k,String val){mono(c,k,346,y,11,GREY,Paint.Align.LEFT);mono(c,val,454,y,12,WH,Paint.Align.RIGHT);}
    private void clutchTemp(Canvas c,float x,String name,float t){
        int lv=Telemetry.clutchLevel(t);
        bold(c,fmt("%.0f",t),x,311,24,lv==2?RED:lv==1?AMBER:WH,Paint.Align.CENTER);mono(c,name+" °C",x,325,10,GREY,Paint.Align.CENTER);
    }
    private void mini(Canvas c,float cx,float cy,String name,float val,float lo,float hi,String f){
        float r=24,a0=-55,a1=55,on=Float.isNaN(val)?-1:clamp((val-lo)/(hi-lo),0,1);
        for(int i=0;i<=14;i++){float a=a0+(a1-a0)*i/14f;line(c,px(cx,r,a),py(cy,r,a),px(cx,r+6,a),py(cy,r+6,a),2,i/14f<=on?acc:DIM);}
        mono(c,name,cx,cy-r-10,10,GREY,Paint.Align.CENTER);mono(c,fmt(f,val),cx,cy+8,14,WH,Paint.Align.CENTER);
    }
}
