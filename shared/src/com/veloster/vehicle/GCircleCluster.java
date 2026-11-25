package com.veloster.vehicle;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

/**
 * Friction circle: a large G circle with the last 4 s as a fading tail (telemetry history, so it survives a hidden
 * tab), session peaks per direction, and pedal, brake, steering and yaw bars. Follows the theme.
 */
final class GCircleCluster extends ClusterKit {
    private static final int TX=0xffe6f1f6,MU=0xff91a5ae,LN=0xff1d2326,RING=0xff303638,ACC=0xffa4d9ef,PEAK=0xffffd21a,PANEL=0xff101315;
    private static final float CX=236,CY=268,R=184;
    private float pkAcc,pkBrk,pkL,pkR;
    { themed=true; }

    @Override protected void draw(Canvas c){
        float lat=v(11),lon=v(12),pin=Float.isNaN(lat)||Float.isNaN(lon)?1:Telemetry.pinG(lat,lon,1),v=v(7),pedal=v(9),brake=v(10),steer=v(Telemetry.STEERING),yaw=v(Telemetry.YAW);
        fill(c,0,52,800,480,0xff040506);
        for(float g:new float[]{0.25f,0.5f,0.75f,1f}){ring(c,CX,CY,R*g,g==1?2:1,g==1?RING:LN);
            text(c,String.format(java.util.Locale.US,"%.2f",g),CX+R*g*0.707f+4,CY-R*g*0.707f-4,12,MU,Paint.Align.LEFT,Typeface.DEFAULT);}
        line(c,CX-R,CY,CX+R,CY,1,LN);line(c,CX,CY-R,CX,CY+R,1,LN);
        bold(c,t("제동"),CX,72,13,MU,Paint.Align.CENTER);bold(c,t("가속"),CX,476,13,MU,Paint.Align.CENTER);   // braking (-lon) up
        bold(c,t("오른쪽"),CX-R-8,CY+4,13,MU,Paint.Align.RIGHT);bold(c,t("왼쪽"),CX+R+8,CY+4,13,MU,Paint.Align.LEFT);   // the dot goes where the body is thrown: a left turn (+lat) right
        // tail: last 4 s of history, older segments fainter, colour by strength
        Telemetry.Series hl=model==null?null:model.history(11,now,4000),ho=model==null?null:model.history(12,now,4000);
        if(hl!=null&&ho!=null){
            int n=Math.min(hl.size(),ho.size());float px=Float.NaN,py=0;
            for(int i=0;i<n;i++){
                if(!hl.valid[i]||!ho.valid[i]){px=Float.NaN;continue;}
                float a=hl.values[i],o=ho.values[i];peaks(a,o);
                float k=Telemetry.pinG(a,o,1),x=CX+a*k*R,y=CY+o*k*R,age=(now-hl.times[i])/4000f;
                if(!Float.isNaN(px))line(c,px,py,x,y,3+(1-age)*3,fade(heat((float)Math.hypot(a,o)),1-age));
                px=x;py=y;
            }
            if(!Float.isNaN(px)&&!Float.isNaN(lat)&&!Float.isNaN(lon))line(c,px,py,CX+lat*pin*R,CY+lon*pin*R,6,heat((float)Math.hypot(lat,lon)));
        }
        if(!Float.isNaN(lat)&&!Float.isNaN(lon))peaks(lat,lon);
        mark(c,0,pkAcc,"+"+f2(pkAcc),8,16,Paint.Align.LEFT);mark(c,0,pkBrk,f2(pkBrk),8,-6,Paint.Align.LEFT);
        mark(c,pkL,0,f2(pkL),0,-10,Paint.Align.CENTER);mark(c,pkR,0,f2(Math.abs(pkR)),0,-10,Paint.Align.CENTER);
        if(!Float.isNaN(lat)&&!Float.isNaN(lon)){float x=CX+lat*pin*R,y=CY+lon*pin*R;dot(c,x,y,9,0xffffffff);ring(c,x,y,15,2,ACC);}
        // right panel
        float x0=490,g=Float.isNaN(lat)||Float.isNaN(lon)?Float.NaN:(float)Math.hypot(lat,lon);
        bold(c,t("합성 G"),x0,88,13,MU,Paint.Align.LEFT);
        String gs=fmt("%.2f",g);bold(c,gs,x0,150,72,!Float.isNaN(g)&&g>0.7f?0xffff8a5a:TX,Paint.Align.LEFT);
        bold(c,"g",x0+8+width(gs,72,Typeface.DEFAULT_BOLD),150,26,MU,Paint.Align.LEFT);
        bold(c,fmt("%.0f",v),790,122,44,TX,Paint.Align.RIGHT);bold(c,"km/h",790,142,13,MU,Paint.Align.RIGHT);
        String gr=gear();boolean m=manualGate(gr);   // the M sits small above the digit, clear of the big G's "g"
        bold(c,m?gr.substring(1):gr,700,122,44,ACC,Paint.Align.RIGHT);if(m)bold(c,"M",700,82,16,ACC,Paint.Align.RIGHT);
        bar(c,182,t("가속 페달"),Float.isNaN(pedal)?Float.NaN:pedal/100,fmt("%.0f",pedal)+" %",0xff3ddc84,false);
        bar(c,220,t("브레이크 압력"),Float.isNaN(brake)?Float.NaN:brake/60,fmt("%.0f",brake)+" bar",danger(),false);
        bar(c,258,t("조향각"),Float.isNaN(steer)?Float.NaN:steer/360,Float.isNaN(steer)?"—":(steer>0?t("오른쪽 "):steer<0?t("왼쪽 "):"")+Math.abs(Math.round(steer))+"°",ACC,true);
        bar(c,296,t("요레이트"),Float.isNaN(yaw)?Float.NaN:yaw/40,fmt("%.1f",Float.isNaN(yaw)?Float.NaN:Math.abs(yaw))+" °/s",PEAK,true);
        bold(c,t("세션 최대"),x0,342,13,MU,Paint.Align.LEFT);
        String[] l={t("가속"),t("제동"),t("왼쪽"),t("오른쪽")};float[] pv={pkAcc,Math.abs(pkBrk),pkL,Math.abs(pkR)};
        for(int i=0;i<4;i++){float x=x0+(i%2)*152,y=354+(i/2)*56;round(c,x,y,x+146,y+50,6,PANEL);outline(c,x,y,x+146,y+50,6,1,RING);
            bold(c,l[i],x+10,y+20,12,MU,Paint.Align.LEFT);bold(c,f2(pv[i])+" g",x+136,y+40,26,TX,Paint.Align.RIGHT);}
    }
    private void peaks(float a,float o){pkAcc=Math.max(pkAcc,o);pkBrk=Math.min(pkBrk,o);pkL=Math.max(pkL,a);pkR=Math.min(pkR,a);}
    private void mark(Canvas c,float a,float o,String s,float dx,float dy,Paint.Align al){
        if(Math.abs(a)<0.1f&&Math.abs(o)<0.1f)return;   // tiny peaks would pile up on the centre
        float k=Telemetry.pinG(a,o,1),x=CX+a*k*R,y=CY+o*k*R;dot(c,x,y,4,PEAK);bold(c,s,x+dx,y+dy,14,PEAK,al);   // a peak past 1 g sits on the rim, its label still reads the value
    }
    private void bar(Canvas c,float y,String label,float f,String txt,int color,boolean centre){
        float x0=490;bold(c,label,x0,y,13,MU,Paint.Align.LEFT);bold(c,txt,790,y,15,TX,Paint.Align.RIGHT);
        fill(c,x0,y+8,x0+300,y+18,0xff141c21);
        if(Float.isNaN(f))return;
        if(centre){float w=clamp(f,-1,1)*150;fill(c,x0+150+Math.min(0,w),y+8,x0+150+Math.max(0,w),y+18,color);fill(c,x0+149,y+5,x0+151,y+21,0xff3a4a54);}
        else fill(c,x0,y+8,x0+300*clamp(f,0,1),y+18,color);
    }
    private static String f2(float x){return String.format(java.util.Locale.US,"%.2f",x);}
    /** Green to red by strength (0..1 g). */
    private static int heat(float g){float t=clamp(g,0,1);return mix(0xffff4a3a,0xff3ddc84,t);}
    private static int fade(int c,float a){return ((int)(clamp(a,0,1)*0.9f*255)<<24)|(c&0xffffff);}
}
