package com.veloster.vehicle;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.util.Locale;

/** Flat race-dash page in the F1 palette, laid out like an aftermarket racing display: body status strip and shift
 * bar on top; RPM, boost (with session peak) and a 2x2 temperature/pedal grid on the left; gear and G in the middle;
 * speed, a TPMS corner grid and a steering cell on the right (a 6MT adds a clutch cell left of the brake bar, a
 * diesel shows DPF in the SPARK slot); a trip line at the bottom (an acceleration-run banner while one runs).
 * drawRecords() is the second page of the same tab: acceleration runs and session peaks (DriveStats). Brake pressure has its own bar cell under RPM and the
 * accelerator pedal its own bar cell under speed; fuel flow is text only (it follows pedal, load and rpm, so a second bar adds little). Only received samples; missing ones show a dash. */
public final class RaceRenderer {
    /** Every colour goes through the selected theme (identity for N). */
    private static int T(int c){return Theme.current().map(c);}
    private static int D(){return Theme.current().danger;}
    private final Paint p=new Paint(3);
    private final RectF r=new RectF();
    private final Path path=new Path(),cellPath=new Path();
    private final RectF cellRect=new RectF();
    private static final int PANEL=0xff101315,LINE=0xff303638,TRACK=0xff1d2326,BLUE=0xffa4d9ef,WHITE=0xffe1edf2,MUTED=0xff91a5ae,
        RED=0xffff4059,AMBER=0xffffb84d,GREEN=0xff3ddc84,BG=0xff040506;
    private static final float TIRE_LOW=30,TIRE_CRIT=26;
    private static final long BLINK_MS=100,POP_MS=150,TURN_MS=350;
    private static final float STEER_TOP=320,L=12,R=788,LC=300,CL=308,CR=492,RC=500,TOP=104,BOT=448;
    private Telemetry.Sample[] s;
    private DriveStats st;
    private ShiftGuide.Cue cue;
    private VehicleProfile prof=VehicleProfile.PETROL_DCT;
    private long now,popAt=Long.MIN_VALUE/2,turnLeftAt=Long.MIN_VALUE/2,turnRightAt=Long.MIN_VALUE/2;
    private int lastGear;
    private boolean mediaOn;
    private MediaState.Snapshot music;
    private Bitmap artwork;

    private float v(int i){return i>=0&&i<s.length&&s[i].valid&&!Float.isNaN(s[i].value)&&!Float.isInfinite(s[i].value)?Telemetry.displayValue(i,s[i].value):Float.NaN;}
    private static String fmt(String f,float x){return Float.isNaN(x)?"—":Telemetry.format(f,x);}

    /** cue/target come from the F1 tab's shift target (ShiftGuide); now drives the blink and the upshift pop. */
    public void draw(Canvas c,Telemetry.Sample[] samples,ShiftGuide.Cue shiftCue,long frameTime,DriveStats stats,VehicleProfile profile,boolean media,MediaState.Snapshot track,Bitmap cover){
        s=samples;cue=shiftCue;now=frameTime;st=stats;prof=profile;mediaOn=media;music=track;artwork=cover;
        float gv=v(8);int gi=Float.isNaN(gv)?0:(int)gv;
        if(gi>=1&&gi<=7){if(lastGear>0&&gi!=lastGear)popAt=now;lastGear=gi;}
        status(c);shiftBar(c);
        float rpm=v(0),boost=v(Telemetry.BOOST);
        float brake=v(10),pedal=v(9);
        int matched=RevMatch.matched(s,prof);   // 6MT, clutch in: the blip sits on a lower gear's rpm
        cell(c,"RPM",fmt("%.0f",rpm),"rpm",L,TOP,LC,184,48,matched>0?GREEN:!Float.isNaN(rpm)&&rpm>=prof.redline?RED:WHITE);
        // 6MT: the clutch sits left of the brake, in pedal order (clutch | brake | gear | accel).
        boolean mt=prof.trans==VehicleProfile.Trans.MT;
        // 7DCT: both clutch temperatures (odd / even gears) in the same place, once the TCU answers (nothing moves before that).
        float odd=v(Telemetry.CLUTCH_ODD),even=v(Telemetry.CLUTCH_EVEN);boolean dct=!mt&&!(Float.isNaN(odd)&&Float.isNaN(even));
        if(mt)clutch(c);else if(dct)clutchTemps(c,odd,even);
        bar(c,"BRAKE",fmt("%.1f",brake)+" bar",mt||dct?L+76:L,LC,brake/100,RED,true);
        boost(c,boost);
        // Four tiles, hold one to pick what it shows (Tiles): coolant, intake, throttle and spark by default (a diesel: the DPF).
        for(int i=0;i<Tiles.RECTS[Tiles.RACE].length;i++)raceTile(c,Tiles.RECTS[Tiles.RACE][i],Tiles.signal(Tiles.RACE,i,prof));
        gear(c);gforce(c);
        // Shift point reached: the whole page edge blinks with the bar and the gear box.
        if(cue.shift&&blink()){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(4);p.setColor(T(RED));c.drawRect(3,55,797,478,p);p.setStyle(Paint.Style.FILL);}
        // SPEED and ACCEL stay full in both modes so they mirror RPM and BRAKE on the left (symmetric pairs).
        cell(c,"SPEED",fmt("%.0f",v(7)),"km/h",RC,TOP,R,184,48,WHITE);
        text(c,mediaOn?"✕":"♪",R-10,TOP+16,12,mediaOn?WHITE:MUTED,Paint.Align.RIGHT);   // tap the SPEED cell to toggle the music panel
        bar(c,"ACCEL "+fmt("%.0f",pedal)+" %","FUEL "+fmt("%.1f",v(Telemetry.FUEL_RATE))+" L/h",RC,R,pedal/100,BLUE,false);
        if(mediaOn)mediaPanel(c);         // music fills the TPMS/steering area; those two go compact inside it
        else{tires(c);steering(c);}
        trip(c);
    }
    /** Dedicated race + music layout for the right column: speed/accel/TPMS/steering kept compact on top, a
     * now-playing panel (cover, track, progress, prev/play/next) below. Left and centre stay as the normal race
     * page, so nothing is lost. Tap the SPEED row (✕) to close; buttons send prev/play/next. */
    private void mediaPanel(Canvas c){
        box(c,RC,232,R,BOT,0xff0c1a20);
        // TPMS and steering give up their cells to the music panel, so keep them as a compact header line.
        text(c,"TPMS "+fmt("%.0f",v(Telemetry.TPMS_FL))+" "+fmt("%.0f",v(Telemetry.TPMS_FR))+" "+fmt("%.0f",v(Telemetry.TPMS_RL))+" "+fmt("%.0f",v(Telemetry.TPMS_RR))+tyreTempsLine(),RC+12,252,11,MUTED,Paint.Align.LEFT);
        text(c,"STEER "+fmt("%+.1f",v(13))+"°",R-12,252,11,MUTED,Paint.Align.RIGHT);
        box(c,RC+12,262,R-12,263,LINE);
        text(c,"♪ "+(music==null||music.source.length()==0?"MEDIA":music.source),RC+12,284,11,BLUE,Paint.Align.LEFT);
        cover(c,RC+12,294,80);
        ellipsis(c,music==null||music.title.length()==0?"—":music.title,RC+102,314,16,R-(RC+102)-12,WHITE);
        ellipsis(c,music==null||music.artist.length()==0?"—":music.artist,RC+102,334,12,R-(RC+102)-12,MUTED);
        if(music!=null&&music.hasProgress()){
            text(c,MediaState.time(music.positionMs)+" / "+MediaState.time(music.durationMs),RC+102,358,11,WHITE,Paint.Align.LEFT);
            box(c,RC+102,366,R-12,369,LINE);box(c,RC+102,366,RC+102+(R-(RC+102)-12)*music.progress(),369,BLUE);
        }
        mbtn(c,508,410,86,"◁◁");
        mbtn(c,598,410,92,music!=null&&music.playState==1?"❚❚":"▶");
        mbtn(c,694,410,88,"▷▷");
    }
    private void mbtn(Canvas c,float x,float y,float w,String label){
        box(c,x,y,x+w,y+30,PANEL);
        text(c,label,x+w/2,y+20,15,BLUE,Paint.Align.CENTER);
    }
    private void cover(Canvas c,float x,float y,float size){
        p.setStyle(Paint.Style.FILL);p.setColor(T(0xff0b0f12));c.drawRect(x,y,x+size,y+size,p);
        if(artwork!=null){
            float scale=Math.min(size/artwork.getWidth(),size/artwork.getHeight());
            float cw=artwork.getWidth()*scale,ch=artwork.getHeight()*scale;
            r.set(x+(size-cw)/2,y+(size-ch)/2,x+(size+cw)/2,y+(size+ch)/2);c.drawBitmap(artwork,null,r,p);
        }else text(c,"♪",x+size/2,y+size/2+size*0.16f,size*0.42f,MUTED,Paint.Align.CENTER);
    }
    private void ellipsis(Canvas c,String str,float x,float y,float size,float width,int color){
        p.setTypeface(Typeface.DEFAULT);p.setTextSize(size);
        String t=str;
        if(p.measureText(t)>width){   // binary search: a 256-char title costs ~8 measurements, not ~250, every frame
            int lo=0,hi=t.length();while(lo<hi){int m=(lo+hi+1)/2;if(p.measureText(t.substring(0,m)+"…")<=width)lo=m;else hi=m-1;}
            if(lo>0&&Character.isHighSurrogate(t.charAt(lo-1)))lo--;   // never split an emoji
            t=t.substring(0,lo)+"…";}
        text(c,t,x,y,size,color,Paint.Align.LEFT);
    }
    private void box(Canvas c,float l,float t,float rr,float b,int fill){
        if(ThemeShapes.cell(c,p,cellPath,cellRect,l,t,rr,b,T(fill),T(LINE)))return;
        p.setStyle(Paint.Style.FILL);p.setColor(T(fill));c.drawRect(l,t,rr,b,p);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(T(LINE));c.drawRect(l+.5f,t+.5f,rr-.5f,b-.5f,p);p.setStyle(Paint.Style.FILL);
    }
    /** Caption top-left, value right-aligned at the bottom with a small unit. */
    /** DPF cell: the regeneration trigger (how close the next regeneration is) big, delta pressure and temperature
     *  small on the left; amber with DPF REGEN while one runs. Each reads "—" until the ECU answers (unconfirmed on a PD diesel). */
    private void dpf(Canvas c,float l,float t,float rr,float b){
        float tr=v(Telemetry.DPF_TRIGGER),dp=v(Telemetry.DPF_DP),tp=v(Telemetry.DPF_TEMP);boolean regen=v(Telemetry.DPF_REGEN)>0;int ink=regen?AMBER:WHITE;
        // the big number: soot g, else the trigger %, else the delta pressure (Tiles.dpfLead); ΔP small unless it is the big one
        int k=Tiles.dpfLead(v(Telemetry.DPF_SOOT),tr,dp);
        cell(c,regen?"DPF REGEN":k==Telemetry.DPF_DP?"DPF ΔP":k==Telemetry.DPF_SOOT&&Float.isNaN(v(Telemetry.DPF_SINCE_KM))&&Float.isNaN(v(Telemetry.DPF_AVG_KM))?"DPF SOOT":"DPF",Tiles.text(k,v(k)),Telemetry.UNITS[k],l,t,rr,b,24,ink);
        if(k!=Telemetry.DPF_DP)text(c,"ΔP "+fmt("%.1f",dp),l+8,t+35,13,Float.isNaN(dp)?MUTED:ink,Paint.Align.LEFT);   // delta pressure, kPa
        // Top right (not while regenerating, the caption then just "DPF" so they never meet): km since the last regeneration, else the ECU's average between them;
        // with both, they take turns every 3 s. Whole km cut down, as a DAG3 shows it (298.86 -> 298).
        float since=v(Telemetry.DPF_SINCE_KM),avg=v(Telemetry.DPF_AVG_KM);
        boolean avgTurn=Float.isNaN(since)||(!Float.isNaN(avg)&&now/3000%2==1);
        boolean split=false;for(int i=0;i<Tiles.RECTS[Tiles.RACE].length;i++)split|=Tiles.signal(Tiles.RACE,i,prof)==Tiles.EXHAUST;
        if(!regen&&!(Float.isNaN(since)&&Float.isNaN(avg))){String km=(avgTurn?"AVG ":"LAST ")+fmt("%.0f",avgTurn?avg:(float)Math.floor(since))+"km";
            text(c,km,rr-8,t+16,11,MUTED,Paint.Align.RIGHT);}   // top right; the caption shortens to "DPF" (the g says soot)
        if(!split)text(c,fmt("%.0f",tp)+" °C",l+8,t+52,13,Float.isNaN(tp)?MUTED:ink,Paint.Align.LEFT);   // the EXHAUST tile has it
    }
    private void cell(Canvas c,String caption,String value,String unit,float l,float t,float rr,float b,float size,int color){
        box(c,l,t,rr,b,PANEL);
        text(c,caption,l+8,t+16,12,MUTED,Paint.Align.LEFT);
        text(c,unit,rr-8,b-10,12,MUTED,Paint.Align.RIGHT);
        p.setTextSize(12);float uw=p.measureText(unit);
        float room=rr-14-uw-(l+8),w=rwidth(value,size);   // six record cells are ~123 px: 6242 or +1.52 at 38 px ran into the next cell
        rnumber(c,value,rr-14-uw,b-10,w>room?size*room/w:size,color);
        if(color==D())ThemeShapes.ring(c,p,l,t,rr,b);
    }
    /** Bar cell (188..228) under RPM/speed: caption left, reading right, a 30-block bar (F1 pedal style).
     * fromRight fills right to left (brake), mirroring the accelerator across the gear column. */
    private void bar(Canvas c,String caption,String reading,float l,float rr,float fraction,int color,boolean fromRight){
        box(c,l,188,rr,228,PANEL);
        text(c,caption,l+8,204,12,MUTED,Paint.Align.LEFT);
        text(c,reading,rr-8,204,12,WHITE,Paint.Align.RIGHT);
        int n=30,lit=Float.isNaN(fraction)?0:Math.round(Math.max(0,Math.min(1,fraction))*n);float w=(rr-l-16-2*(n-1))/n;
        for(int k=0;k<n;k++){p.setColor(T((fromRight?n-1-k:k)<lit?color:TRACK));c.drawRect(l+8+k*(w+2),210,l+8+k*(w+2)+w,222,p);}
    }
    /** Body/lamp strip between the turn arrows: grey caption when idle, a coloured block when the state is active. What changes
     *  while driving first (drive mode, the stability switches, brake, park brake, wiper), then what only changes parked (doors,
     *  trunk, hood). TCS / ESC light when switched OFF (amber / danger); an ABS warning lamp takes the ESC block. */
    private void status(Canvas c){
        float wiper=v(Telemetry.WIPER),mode=v(Telemetry.DRIVE_MODE),dl=v(Telemetry.DOOR_DRIVER),dr=v(Telemetry.DOOR_PASSENGER),abs=v(Telemetry.ABS);
        if(v(Telemetry.TURN_LEFT)>0)turnLeftAt=now;
        if(v(Telemetry.TURN_RIGHT)>0)turnRightAt=now;
        boolean openL=dl>0,openR=dr>0,absWarn=abs>0;
        float brake=v(Telemetry.BRAKE_SWITCH),bar=v(10),door=openL||openR?1:Float.isNaN(dl)&&Float.isNaN(dr)?Float.NaN:0;
        if(!Float.isNaN(brake)||!Float.isNaN(bar))brake=Telemetry.braking(brake,bar)?1:0;
        float tcs=v(Telemetry.TCS),esc=absWarn?1:v(Telemetry.ESC);
        String[] name={Float.isNaN(mode)?"MODE":Telemetry.driveModeLabel((int)mode),tcs>0?"TCS OFF":"TCS",absWarn?"ABS":esc>0?"ESC OFF":"ESC","BRAKE","PARK",
            "WIPER"+(Float.isNaN(wiper)?"":" "+Telemetry.label(Telemetry.WIPER,wiper)),openL&&openR?"DOORS":openR?"DOOR R":openL?"DOOR L":"DOOR","TRUNK","HOOD","ENGINE"};
        float[] val={mode,tcs,esc,brake,v(Telemetry.PARK_BRAKE),wiper,door,v(Telemetry.TRUNK),v(Telemetry.HOOD),v(Telemetry.MIL)};
        int[] on={0,AMBER,D(),AMBER,D(),BLUE,D(),D(),D(),AMBER};   // the park brake and anything open are warnings; the last cell is the engine lamp
        int mil=name.length-1;   // the engine lamp cell: the glyph, not a word
        int n=name.length;float aw=32,sl=L+aw+4,sr=R-aw-4,w=(sr-sl-4*(n-1))/n;
        for(int i=0;i<n;i++){
            float l=sl+i*(w+4),x=val[i];
            int fill=i==0?(x==1?RED:x==3?GREEN:0):!Float.isNaN(x)&&x>0?on[i]:0;
            box(c,l,56,l+w,80,fill!=0?fill:PANEL);if(fill==D())ThemeShapes.ring(c,p,l,56,l+w,80);
            if(i==mil){MilLamp.draw(c,l+w/2,68,18,fill!=0?BG:Float.isNaN(x)?0xff4a5860:MUTED);continue;}
            float size=12;p.setTypeface(Typeface.DEFAULT);p.setTextSize(size);while(size>9&&p.measureText(name[i])>w-6){size-=.5f;p.setTextSize(size);}   // WIPER HIGH in a tenth of the strip
            text(c,name[i],l+w/2,73,size,fill!=0?BG:Float.isNaN(x)?0xff4a5860:MUTED,Paint.Align.CENTER);
        }
        turnArrow(c,L,L+aw,true,now-turnLeftAt<900);
        turnArrow(c,R-aw,R,false,now-turnRightAt<900);
    }
    /** Turn-signal arrow at a strip end: amber and blinking while the signal is engaged, otherwise a dim idle arrow. */
    private void turnArrow(Canvas c,float l,float rr,boolean left,boolean active){
        box(c,l,56,rr,80,active?mix(AMBER,PANEL,0.25f):PANEL);
        boolean lit=active&&(now/TURN_MS)%2==0;
        p.setStyle(Paint.Style.FILL);p.setColor(T(lit?AMBER:active?0xff6a5326:0xff3a4650));
        float cy=68,tip=left?l+7:rr-7,base=left?rr-8:l+8;
        path.reset();path.moveTo(tip,cy);path.lineTo(base,cy-8);path.lineTo(base,cy+8);path.close();
        c.drawPath(path,p);
    }
    private boolean blink(){return (now/BLINK_MS)%2==0;}
    private static int mix(int a,int b,float t){
        int o=0xff000000;for(int sh=0;sh<24;sh+=8){int x=(a>>sh)&255,y=(b>>sh)&255;o|=Math.round(y+(x-y)*t)<<sh;}return o;
    }
    /** Steering-wheel style shift lights: both ends fill toward the middle over the last quarter below the shift
     * target (ShiftGuide's 15 LEDs: 1500 rpm at 6000), green -> amber -> red; at the target the whole bar blinks blue. */
    private void shiftBar(Canvas c){
        int n=40,half=n/2,lit=Math.round(cue.leds/15f*half);float w=(R-L-2*(n-1))/n;
        for(int i=0;i<n;i++){int d=i<half?i:n-1-i;
            p.setColor(T(cue.shift?(blink()?BLUE:TRACK):d<lit?(d<8?GREEN:d<15?AMBER:RED):TRACK));
            c.drawRect(L+i*(w+2),86,L+i*(w+2)+w,98,p);}
    }
    private void boost(Canvas c,float boost){
        cell(c,"BOOST",fmt("%+.2f",boost),"bar",L,232,LC,316,36,WHITE);
        text(c,"MAX "+fmt("%+.2f",st.maxBoost),LC-8,248,12,MUTED,Paint.Align.RIGHT);
        // -0.8 bar up to the boost top (1.5, wider on a tuned car), lit from 0 toward the value.
        float top=st==null?DriveStats.boostTop(Float.NaN):st.boostTop(),span=top+.8f;
        float x0=L+8,x1=LC-8,y=256,zero=x0+(x1-x0)*.8f/span;
        p.setColor(T(TRACK));c.drawRect(x0,y,x1,y+8,p);
        if(!Float.isNaN(boost)){float x=x0+(x1-x0)*Math.max(0,Math.min(1,(boost+.8f)/span));
            p.setColor(T(boost>=top*0.8f?RED:boost>=0?BLUE:MUTED));c.drawRect(Math.min(zero,x),y,Math.max(zero,x),y+8,p);}
        p.setColor(T(WHITE));c.drawRect(zero-1,y-3,zero+1,y+11,p);
    }
    private void gear(Canvas c){
        // Upshift pop: the box flashes white and the numeral shrinks back to size over POP_MS.
        float pop=Math.max(0,1-(now-popAt)/(float)POP_MS);
        int fill=cue.shift?(blink()?RED:BLUE):BLUE;
        box(c,CL,TOP,CR,332,pop>0?mix(WHITE,fill,pop):fill);
        // Last 300 rpm before the target: a red rim that thickens toward the shift point.
        if(!cue.shift&&cue.leds>=13){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2+(cue.leds-12)*2);p.setColor(T(RED));
            c.drawRect(CL+3,TOP+3,CR-3,329,p);p.setStyle(Paint.Style.FILL);}
        float gear=v(8),sel=v(Telemetry.GEAR_SELECTOR);
        String shown=Telemetry.gearText(gear,sel,prof);int code=Telemetry.selector(sel,gear,prof);
        text(c,"GEAR",(CL+CR)/2,TOP+20,13,BG,Paint.Align.CENTER);
        int save=c.save();c.clipRect(CL,TOP,CR,332);  // the pop never spills out of the gear box
        text(c,shown,(CL+CR)/2,300,shown.length()>1?120*(1+.1f*pop):170*(1+.25f*pop),BG,Paint.Align.CENTER);
        c.restoreToCount(save);
        long cool=st.coolDownLeft();
        String hint=Telemetry.shiftHint(v(Telemetry.SHIFT_HINT));  // 6MT: the cluster's shift recommendation
        String note=cue.shift?"SHIFT ▲":cool>0?"COOL-DOWN "+clock(cool):st.warmUp?"WARM-UP":!hint.isEmpty()?"SHIFT "+hint:code<0?"":Telemetry.selectorLabel(code);
        // The ECU's shift hint on a red band, so it stands out from the cyan box without the app's own blinking SHIFT cue.
        boolean ecuHint=!cue.shift&&cool<=0&&!st.warmUp&&!hint.isEmpty();
        if(ecuHint){p.setStyle(Paint.Style.FILL);p.setColor(T(RED));c.drawRect(CL+6,307,CR-6,330,p);}
        text(c,note,(CL+CR)/2,324,cue.shift?16:14,ecuHint?WHITE:cue.shift?BG:cool>0||st.warmUp?Theme.current().readableOn(T(BLUE),D()):BG,Paint.Align.CENTER);
    }
    private void gforce(Canvas c){
        box(c,CL,336,CR,BOT,PANEL);
        float gx=v(11),gy=v(12);boolean ok=!Float.isNaN(gx)&&!Float.isNaN(gy);
        float cx=CL+52,cy=(336+BOT)/2f,rad=42;
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(T(LINE));c.drawCircle(cx,cy,rad,p);c.drawCircle(cx,cy,rad/2,p);
        c.drawLine(cx-rad,cy,cx+rad,cy,p);c.drawLine(cx,cy-rad,cx,cy+rad,p);p.setStyle(Paint.Style.FILL);
        // Session peak G as a ring, then the last ~2 s of points fading in toward the live dot.
        if(!Float.isNaN(st.maxG)&&st.maxG>0.05f){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.5f);p.setColor(T(0x99ff4059));
            c.drawCircle(cx,cy,Math.min(1,st.maxG/1.2f)*rad,p);p.setStyle(Paint.Style.FILL);}
        for(int i=0,n=st.trailCount();i<n;i++){p.setColor(T(((40+180*i/Math.max(1,n))<<24)|(BLUE&0xffffff)));
            c.drawCircle(cx+clampG(st.trailX(i))*rad,cy+clampG(st.trailY(i))*rad,2.5f,p);}
        if(ok){p.setColor(T(RED));c.drawCircle(cx+clampG(gx)*rad,cy+clampG(gy)*rad,5,p);}   // braking (-) up
        text(c,"G  MAX "+fmt("%.2f",st.maxG),CR-8,352,12,MUTED,Paint.Align.RIGHT);
        text(c,ok?String.format(Locale.US,"%.2f",(float)Math.sqrt(gx*gx+gy*gy)):"—",CR-8,392,30,WHITE,Paint.Align.RIGHT);
        text(c,"LAT "+fmt("%+.2f",gx),CR-8,416,12,MUTED,Paint.Align.RIGHT);
        text(c,"LON "+fmt("%+.2f",gy),CR-8,434,12,MUTED,Paint.Align.RIGHT);
    }
    /** TPMS grid: four tyres around a body outline, pressure at each corner, amber/red when low. The cell ends at 316
     * like BOOST on the left, so the right column's seams line up with the left one's. */
    private void tires(Canvas c){
        box(c,RC,232,R,STEER_TOP-4,PANEL);
        text(c,"TPMS",RC+8,248,12,MUTED,Paint.Align.LEFT);text(c,"psi",R-8,248,12,MUTED,Paint.Align.RIGHT);
        float cx=(RC+R)/2,cy=(232+STEER_TOP-4)/2f+6,bw=34,bh=54,tw=16,th=22;
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setColor(T(MUTED));r.set(cx-bw/2,cy-bh/2,cx+bw/2,cy+bh/2);c.drawRoundRect(r,8,8,p);p.setStyle(Paint.Style.FILL);
        int[] sig={Telemetry.TPMS_FL,Telemetry.TPMS_FR,Telemetry.TPMS_RL,Telemetry.TPMS_RR};
        for(int i=0;i<4;i++){
            boolean right=i%2==1,rear=i>=2;float val=v(sig[i]);
            float tx=right?cx+bw/2+4:cx-bw/2-4-tw,ty=rear?cy+bh/2-th-2:cy-bh/2+2;
            p.setColor(T(Float.isNaN(val)?TRACK:val<TIRE_CRIT?D():val<TIRE_LOW?AMBER:Theme.current().dangerRing?WHITE:BLUE));r.set(tx,ty,tx+tw,ty+th);c.drawRoundRect(r,3,3,p);   // amber: a normal tyre must not look like a warning
            if(!Float.isNaN(val)&&val<TIRE_CRIT)ThemeShapes.ring(c,p,tx-3,ty-3,tx+tw+3,ty+th+3);
            text(c,fmt("%.0f",val),right?R-12:RC+12,ty+th-1,24,WHITE,right?Paint.Align.RIGHT:Paint.Align.LEFT);
            float tt=v(Telemetry.TIRE_TEMP_FL+i);
            if(!Float.isNaN(tt))text(c,fmt("%.0f",tt)+"°",right?R-12-38:RC+12+38,ty+th-1,12,MUTED,right?Paint.Align.RIGHT:Paint.Align.LEFT);   // °C of the same tyre, inside its pressure
        }
    }
    /** " | 20 20 16 16C" once the BCM has answered with tyre temperatures, else nothing. */
    private String tyreTempsLine(){
        boolean any=false;StringBuilder b=new StringBuilder(" | ");
        for(int k=0;k<4;k++){float t=v(Telemetry.TIRE_TEMP_FL+k);if(!Float.isNaN(t))any=true;b.append(k>0?" ":"").append(Tiles.tyreTemp(t));}
        return any?b.append("C").toString():"";
    }
    private void raceTile(Canvas c,float[] r,int sig){
        float l=r[0],t=r[1],rr=r[2],b=r[3];
        if(sig==Tiles.DPF){dpf(c,l,t,rr,b);return;}
        if(sig==Tiles.TIRES||sig==Tiles.CLUTCH||sig==Tiles.EXHAUST){   // several values: two lines under the caption
            boolean tyres=sig==Tiles.TIRES,egt=sig==Tiles.EXHAUST;float o=v(Telemetry.CLUTCH_ODD),e=v(Telemetry.CLUTCH_EVEN);
            boolean hot=!tyres&&!egt&&(Telemetry.clutchLevel(o)==2||Telemetry.clutchLevel(e)==2);
            boolean regen=egt&&v(Telemetry.DPF_REGEN)>0;
            box(c,l,t,rr,b,hot?D():PANEL);text(c,tyres?"TPMS psi":egt?"EXHAUST":"CLUTCH",l+8,t+16,12,hot?BG:MUTED,Paint.Align.LEFT);
            for(int k=0;k<(tyres?4:2);k++){
                int sigk=tyres?Telemetry.TPMS_FL+k:egt?(k==0?Telemetry.DPF_TEMP:Telemetry.EGT_CAT):k==0?Telemetry.CLUTCH_ODD:Telemetry.CLUTCH_EVEN;float val=v(sigk);int lv=regen?1:Tiles.level(sigk,val);
                float x=tyres?(k%2==0?l+(rr-l)*0.45f:rr-10):rr-28,y=t+(tyres?(k<2?36:56):(k==0?36:56));   // temperatures: °C after the number
                if(!tyres)text(c,egt?(k==0?"DPF":"CAT"):k==0?"ODD":"EVEN",l+8,y,11,hot?BG:MUTED,Paint.Align.LEFT);
                text(c,fmt("%.0f",val),x,y,17,hot?BG:lv==2?D():lv==1?AMBER:WHITE,Paint.Align.RIGHT);
                if(tyres){float tt=v(Telemetry.TIRE_TEMP_FL+k);if(!Float.isNaN(tt))text(c,fmt("%.0f",tt)+"°",x+4,y,10,MUTED,Paint.Align.LEFT);}   // °C right after the pressure
                if(!tyres)text(c,"°C",rr-8,y,11,hot?BG:MUTED,Paint.Align.RIGHT);
            }
            return;
        }
        float val=Tiles.value(sig,s,st);int lv=Tiles.level(sig,val);
        cell(c,Tiles.caption(sig),Tiles.text(sig,val),Tiles.unit(sig),l,t,rr,b,26,lv==2?D():lv==1?AMBER:WHITE);
    }
    /** 6MT clutch pedal (EMS12 CLU_ACK as received), left of the brake bar: pressed fills the cell in the accent. */
    private void clutch(Canvas c){
        float v=v(Telemetry.CLUTCH);boolean in=v>0;
        box(c,L,188,L+72,228,in?BLUE:PANEL);
        text(c,"CLUTCH",L+36,204,11,in?BG:MUTED,Paint.Align.CENTER);
        text(c,Float.isNaN(v)?"—":in?"IN":"OUT",L+36,222,15,in?BG:MUTED,Paint.Align.CENTER);
    }
    /** 7DCT clutch temperatures, odd-gear clutch over even-gear clutch, left of the brake bar like the 6MT clutch pedal.
     *  Levels: Telemetry.clutchLevel; at red the whole cell fills, like the 6MT clutch cell does when pressed. */
    private void clutchTemps(Canvas c,float odd,float even){
        boolean hot=Telemetry.clutchLevel(odd)==2||Telemetry.clutchLevel(even)==2;
        box(c,L,188,L+72,228,hot?D():PANEL);
        clutchRow(c,"ODD",odd,205,hot);clutchRow(c,"EVEN",even,223,hot);
    }
    private void clutchRow(Canvas c,String name,float t,float y,boolean filled){
        int lv=Telemetry.clutchLevel(t),ink=filled?BG:Float.isNaN(t)?MUTED:lv==1?AMBER:WHITE;
        text(c,name,L+5,y,10,filled||lv>0?ink:MUTED,Paint.Align.LEFT);
        text(c,fmt("%.0f",t)+"°",L+68,y,15,ink,Paint.Align.RIGHT);
    }
    /** Steering cell (320..448, as tall as the 2x2 grid on the left): a simple wheel icon turned by the displayed angle
     * (right = clockwise, as Telemetry.displayValue shows it), a centre-zero bar, the angle, and the SAS steering speed
     * and MDPS driver torque in their own boxes. */
    private void steering(Canvas c){
        box(c,RC,STEER_TOP,R,BOT,PANEL);
        float angle=v(Telemetry.STEERING);
        wheel(c,RC+58,(STEER_TOP+BOT)/2f+4,44,Float.isNaN(angle)?0:angle);
        text(c,"STEER",RC+8,STEER_TOP+16,12,MUTED,Paint.Align.LEFT);
        // Centre-zero bar to +-540 deg: past half a turn the wheel icon alone cannot show which way.
        float bl=RC+118,br=R-8,mid=(bl+br)/2,by=STEER_TOP+10;
        p.setColor(T(TRACK));c.drawRect(bl,by,br,by+6,p);
        if(!Float.isNaN(angle)){float x=mid+(br-mid)*Math.max(-1,Math.min(1,angle/540f));p.setColor(T(BLUE));c.drawRect(Math.min(mid,x),by,Math.max(mid,x),by+6,p);}
        p.setColor(T(WHITE));c.drawRect(mid-1,by-3,mid+1,by+9,p);
        text(c,"°",R-8,STEER_TOP+62,14,MUTED,Paint.Align.RIGHT);
        text(c,fmt("%+.1f",angle),R-20,STEER_TOP+62,40,WHITE,Paint.Align.RIGHT);
        box(c,bl,STEER_TOP+74,mid-2,BOT-8,TRACK);box(c,mid+2,STEER_TOP+74,br,BOT-8,TRACK);
        text(c,"RATE",bl+6,STEER_TOP+88,11,MUTED,Paint.Align.LEFT);text(c,"°/s",mid-8,BOT-14,11,MUTED,Paint.Align.RIGHT);
        String rate=fmt("%.0f",v(Telemetry.STEERING_SPEED)),torque=fmt("%+.1f",v(Telemetry.STEER_TORQUE));
        text(c,rate,mid-28,BOT-14,fit(rate,22,mid-28-(bl+5)),WHITE,Paint.Align.RIGHT);
        text(c,"TORQUE",mid+8,STEER_TOP+88,11,MUTED,Paint.Align.LEFT);text(c,"Nm",br-6,BOT-14,11,MUTED,Paint.Align.RIGHT);
        text(c,torque,br-28,BOT-14,fit(torque,22,br-28-(mid+7)),WHITE,Paint.Align.RIGHT);
    }
    /** Text size at most size that keeps s within width (a 4-digit -1016 °/s or +12.7 Nm in the steer cells). */
    private float fit(String s,float size,float width){
        p.setTypeface(Typeface.DEFAULT);p.setTextSize(size);float w=p.measureText(s);return w>width?size*width/w:size;
    }
    // Wheel icon geometry in wheel units (x right, y down, rim radius 1), rotated clockwise by wa about (wx,wy).
    private float wx,wy,wr,wa;
    private float wX(float x,float y){return wx+wr*(float)(x*Math.cos(wa)-y*Math.sin(wa));}
    private float wY(float x,float y){return wy+wr*(float)(x*Math.sin(wa)+y*Math.cos(wa));}
    private void poly(Canvas c,float[] xy,int color,boolean fill){
        path.reset();path.moveTo(wX(xy[0],xy[1]),wY(xy[0],xy[1]));
        for(int i=2;i<xy.length;i+=2)path.lineTo(wX(xy[i],xy[i+1]),wY(xy[i],xy[i+1]));
        if(fill)path.close();
        p.setStyle(fill?Paint.Style.FILL:Paint.Style.STROKE);p.setStrokeWidth(1.5f);p.setColor(T(color));c.drawPath(path,p);p.setStyle(Paint.Style.FILL);
    }
    /** Ring segment from deg0 to deg1 (0 = 12 o'clock, clockwise) between radii r0 and r1. */
    private void band(Canvas c,float deg0,float deg1,float r0,float r1,int color){
        int n=Math.max(2,(int)Math.abs(deg1-deg0)/4);float[] xy=new float[(n+1)*4];
        for(int i=0;i<=n;i++){double t=Math.toRadians(deg0+(deg1-deg0)*i/n);
            xy[i*2]=(float)(Math.sin(t)*r1);xy[i*2+1]=(float)(-Math.cos(t)*r1);
            xy[(2*n+1-i)*2]=(float)(Math.sin(t)*r0);xy[(2*n+1-i)*2+1]=(float)(-Math.cos(t)*r0);}
        poly(c,xy,color,true);
    }
    /** Simple one-colour wheel in the N layout (side spokes sitting a little low, one bottom spoke) with a large red
     * 12 o'clock stripe: the stripe and the single bottom spoke make the rotation readable at a glance. */
    private void wheel(Canvas c,float x,float y,float radius,float deg){
        wx=x;wy=y;wr=radius;wa=(float)Math.toRadians(deg);
        band(c,0,360,.80f,1f,WHITE);
        poly(c,new float[]{-.24f,0f,-.84f,.04f,-.84f,.20f,-.24f,.18f},WHITE,true);
        poly(c,new float[]{.24f,0f,.84f,.04f,.84f,.20f,.24f,.18f},WHITE,true);
        poly(c,new float[]{-.08f,.24f,.08f,.24f,.06f,.84f,-.06f,.84f},WHITE,true);
        band(c,0,360,.18f,.28f,WHITE);
        band(c,-9,9,.74f,1.06f,RED);
    }
    private static float clampG(float g){return Math.max(-1,Math.min(1,g/1.2f));}
    private static String clock(long ms){long sec=(ms+999)/1000;return String.format(Locale.US,"%d:%02d",sec/60,sec%60);}
    private static String run(int i){return String.format(Locale.US,"%.0f→%.0f",DriveStats.RUNS[i][0],DriveStats.RUNS[i][1]);}
    private void trip(Canvas c){
        box(c,L,452,R,476,PANEL);
        float w=(R-L)/4,left=(L+R)/2;
        for(int i=0;i<3;i++)if(st.running[i]){
            text(c,run(i)+" km/h   "+String.format(Locale.US,"%.1f s",(now-st.runStart[i])/1000f)+"   MEASURING",left,469,14,AMBER,Paint.Align.CENTER);return;}
        for(int i=0;i<3;i++)if(st.finishedAt[i]!=0&&now-st.finishedAt[i]<8000){boolean record=st.last[i]==st.best[i];
            text(c,run(i)+" km/h   "+String.format(Locale.US,"%.2f s",st.last[i])+"   BEST "+String.format(Locale.US,"%.2f",st.best[i])+(record?"   ★":""),
                left,469,14,record?GREEN:WHITE,Paint.Align.CENTER);return;}
        String[] item={"BATT "+fmt("%.1f",v(Telemetry.BAT_VOLTAGE))+"V","RANGE "+fmt("%.0f",v(Telemetry.RANGE))+"km",
            "AVG "+fmt("%.1f",v(Telemetry.AVG_FUEL))+"km/L","ODO "+fmt("%.0f",v(Telemetry.ODOMETER))+"km"};
        for(int i=0;i<item.length;i++)text(c,item[i],L+w*i+w/2,469,13,i==0&&v(Telemetry.BAT_VOLTAGE)<12?AMBER:WHITE,Paint.Align.CENTER);
    }
    /** Records page: the three acceleration runs on top, session peaks below. */
    public void drawRecords(Canvas c,DriveStats stats,long frameTime){
        st=stats;now=frameTime;
        float w=(R-L-16)/3;
        for(int i=0;i<3;i++){
            float l=L+i*(w+8),rr=l+w;boolean live=st.running[i];
            box(c,l,60,rr,250,PANEL);
            text(c,run(i)+" km/h",l+12,84,16,MUTED,Paint.Align.LEFT);
            text(c,"BEST "+fmt("%.2f",st.best[i]),rr-12,84,16,!Float.isNaN(st.best[i])?GREEN:MUTED,Paint.Align.RIGHT);
            text(c,"ALL-TIME "+fmt("%.2f",Records.best(Records.runKey(i))),rr-12,106,13,MUTED,Paint.Align.RIGHT);   // 기록실: kept across drives
            float shown=live?(now-st.runStart[i])/1000f:st.last[i];
            text(c,"s",rr-12,206,16,MUTED,Paint.Align.RIGHT);
            text(c,fmt(live?"%.1f":"%.2f",shown),rr-30,206,72,live?AMBER:WHITE,Paint.Align.RIGHT);
            text(c,live?"MEASURING":i==0?"Launch from a stop":"Accelerate to log a run",l+12,238,13,live?AMBER:MUTED,Paint.Align.LEFT);
        }
        String[] name={"MAX LAT G","MAX DECEL","MAX BOOST","MAX RPM","MAX SPEED"};
        String[] val={fmt("%.2f",st.maxLat),fmt("%.2f",st.maxDecel),fmt("%+.2f",st.maxBoost),fmt("%.0f",st.maxRpm),fmt("%.0f",st.maxSpeed)};
        String[] unit={"g","g","bar","rpm","km/h"};
        // 7DCT: a sixth cell with the session's hottest odd / even clutch, once the TCU has answered (5 cells otherwise)
        boolean ct=!Float.isNaN(st.maxClutchOdd)||!Float.isNaN(st.maxClutchEven);int n=ct?6:5;
        float w5=(R-L-8*(n-1))/n;
        for(int i=0;i<5;i++)cell(c,name[i],val[i],unit[i],L+i*(w5+8),258,L+i*(w5+8)+w5,468,n==6?38:44,WHITE);
        if(ct){float l=L+5*(w5+8),rr=l+w5;
            boolean hot=Telemetry.clutchLevel(st.maxClutchOdd)==2||Telemetry.clutchLevel(st.maxClutchEven)==2;
            box(c,l,258,rr,468,hot?D():PANEL);
            text(c,"MAX CLUTCH",l+12,282,13,hot?BG:MUTED,Paint.Align.LEFT);
            maxClutch(c,"ODD",st.maxClutchOdd,l,rr,390,hot);maxClutch(c,"EVEN",st.maxClutchEven,l,rr,446,hot);}
        text(c,"BEST and MAX are this drive's · tap a run for the kept records (기록실)",R,478,10,MUTED,Paint.Align.RIGHT);
    }
    private void maxClutch(Canvas c,String name,float t,float l,float rr,float y,boolean filled){
        int lv=Telemetry.clutchLevel(t),ink=filled?BG:Float.isNaN(t)?MUTED:lv==1?AMBER:WHITE;
        text(c,name,l+12,y-38,12,filled||lv>0?ink:MUTED,Paint.Align.LEFT);
        text(c,"°C",rr-10,y,13,filled?BG:MUTED,Paint.Align.RIGHT);
        text(c,fmt("%.0f",t),rr-32,y,36,ink,Paint.Align.RIGHT);
    }
    private void text(Canvas c,String t,float x,float y,float size,int color,Paint.Align align){
        p.setStyle(Paint.Style.FILL);p.setColor(T(color));p.setTypeface(Typeface.DEFAULT);p.setTextSize(size);
        p.setTextAlign(align);c.drawText(t,x,y,p);p.setTextAlign(Paint.Align.LEFT);
    }
    /** Right-aligned numeric readout with a fixed digit cell, so a narrow 1 does not make the reading jitter as it updates. */
    /** Width rnumber draws s at: every digit as wide as the widest one. */
    private float rwidth(String s,float size){
        p.setTypeface(Theme.current().digitWeight==2?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);p.setTextSize(size);
        float dw=0;for(char d='0';d<='9';d++)dw=Math.max(dw,p.measureText(String.valueOf(d)));
        float total=0;for(int k=0;k<s.length();k++){char ch=s.charAt(k);total+=ch>='0'&&ch<='9'?dw:p.measureText(String.valueOf(ch));}
        return total;
    }
    private void rnumber(Canvas c,String s,float rightX,float y,float size,int color){
        float total=rwidth(s,size);
        p.setStyle(Paint.Style.FILL);p.setColor(T(color));p.setTextAlign(Paint.Align.LEFT);
        float dw=0;for(char d='0';d<='9';d++)dw=Math.max(dw,p.measureText(String.valueOf(d)));
        float x=rightX-total;
        for(int k=0;k<s.length();k++){char ch=s.charAt(k);String g=String.valueOf(ch);float gw=p.measureText(g);
            if(ch>='0'&&ch<='9'){c.drawText(g,x+(dw-gw)/2,y,p);x+=dw;}else{c.drawText(g,x,y,p);x+=gw;}}
    }
}
