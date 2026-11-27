package com.veloster.vehicle;

import android.graphics.Canvas;
import android.graphics.Paint;
import java.util.Calendar;
import java.util.Locale;

/**
 * Fighter-jet HUD in green: a ladder that banks with lateral G and rises with longitudinal G, a flight-path
 * marker on the G vector, speed and rpm tapes, a yaw-rate scale, SHIFT caution past the redline.
 */
final class HudCluster extends ClusterKit {
    private static final int G=0xff7dff8e,GD=0xff3f8048,GF=0xff12301a;
    private static final float CX=400,CY=270;

    @Override protected void draw(Canvas c){
        float v=v(7),rpm=v(0),lat=v(11),lon=v(12),yaw=v(Telemetry.YAW),boost=v(Telemetry.BOOST),pedal=v(9),brake=v(10),steer=v(Telemetry.STEERING),cool=v(2);
        float la=Float.isNaN(lat)?0:lat,lo=Float.isNaN(lon)?0:lon;
        fill(c,0,52,800,480,0xff020604);
        double roll=-la*0.55;float pitch=lo*170;
        // ladder (rotated by hand: no canvas rotation in this code base)
        c.save();c.clipRect(262,98,538,428);
        segR(c,-200,pitch,-40,pitch,roll,2);segR(c,40,pitch,200,pitch,roll,2);
        for(int k=-4;k<=4;k++){if(k==0)continue;float y=pitch-k*52,tick=k<0?-8:8;
            if(k>0){segR(c,-88,y,-40,y,roll,2);segR(c,40,y,88,y,roll,2);}
            else for(float x=-88;x<-40;x+=12){segR(c,x,y,Math.min(x+7,-40),y,roll,2);segR(c,Math.max(40,-x-7),y,-x,y,roll,2);}   // dashed below the horizon
            segR(c,-88,y+tick,-88,y,roll,2);segR(c,88,y,88,y+tick,roll,2);
            String lbl=(k>0?"+":"-")+String.format(Locale.US,"%.1f",Math.abs(k)*0.2f);
            mono(c,lbl,rx(-96,y,roll),ry(-96,y,roll)+5,14,G,Paint.Align.RIGHT);mono(c,lbl,rx(96,y,roll),ry(96,y,roll)+5,14,G,Paint.Align.LEFT);
        }
        c.restore();
        // bank arc and pointer
        r.set(CX-170,CY-170,CX+170,CY+170);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setColor(G);c.drawArc(r,-135,90,false,p);p.setStyle(Paint.Style.FILL);
        for(int d:new int[]{-45,-30,-20,-10,0,10,20,30,45}){float l=d%30==0||Math.abs(d)==45?14:8;line(c,px(CX,170,d),py(CY,170,d),px(CX,170+l,d),py(CY,170+l,d),2,G);}
        float a=(float)Math.toDegrees(-roll);
        path.reset();path.moveTo(px(CX,164,a),py(CY,164,a));path.lineTo(px(CX,150,a-3),py(CY,150,a-3));path.lineTo(px(CX,150,a+3),py(CY,150,a+3));path.close();poly(c,G);
        // waterline and flight-path marker on the G vector
        line(c,CX-34,CY,CX-16,CY,2,G);line(c,CX-16,CY,CX-8,CY+10,2,G);line(c,CX-8,CY+10,CX,CY,2,G);line(c,CX,CY,CX+8,CY+10,2,G);line(c,CX+8,CY+10,CX+16,CY,2,G);line(c,CX+16,CY,CX+34,CY,2,G);
        float fx=CX+la*130,fy=CY+lo*130;   // where the body is thrown: braking up, a left turn right
        ring(c,fx,fy,9,2,G);line(c,fx-24,fy,fx-9,fy,2,G);line(c,fx+9,fy,fx+24,fy,2,G);line(c,fx,fy-9,fx,fy-18,2,G);
        // tapes
        tape(c,60,v,5,20,3.2f,-1,"KM/H",Float.NaN,1);
        tape(c,644,rpm,250,1000,0.06f,1,"RPM x100",prof.redline,100);
        // yaw rate scale
        float y=448;line(c,260,y,540,y,2,G);
        for(int d=-40;d<=40;d+=5){float x=CX+d*3.5f;line(c,x,y,x,y-(d%20==0?12:6),2,G);if(d%20==0)mono(c,String.valueOf(Math.abs(d)),x,y+18,13,GD,Paint.Align.CENTER);}
        mono(c,"YAW deg/s",CX,y-34,13,GD,Paint.Align.CENTER);
        if(!Float.isNaN(yaw)){float yx=CX+clamp(-yaw,-40,40)*3.5f;path.reset();path.moveTo(yx,y-14);path.lineTo(yx-7,y-26);path.lineTo(yx+7,y-26);path.close();poly(c,G);}
        // text blocks
        float mode=v(Telemetry.DRIVE_MODE);
        String[][] tl={{"MODE",Float.isNaN(mode)?"—":Telemetry.driveModeLabel((int)mode)},{"GEAR",gear()},{"THR",fmt("%.0f",pedal)+"%"},{"BST",fmt("%+.2f",boost)}};
        for(int i=0;i<4;i++){mono(c,tl[i][0],168,126+i*22,14,GD,Paint.Align.LEFT);mono(c,tl[i][1],208,126+i*22,16,G,Paint.Align.LEFT);}
        float gsum=Float.isNaN(lat)||Float.isNaN(lon)?Float.NaN:(float)Math.hypot(lat,lon);
        String[][] tr={{"G",fmt("%.2f",gsum)},{"BRK",fmt("%.0f",brake)+" bar"},{"STR",Float.isNaN(steer)?"—":(steer>0?"R":"L")+Math.abs(Math.round(steer))},{"TMP",fmt("%.0f",cool)+"C"}};
        for(int i=0;i<4;i++){mono(c,tr[i][0],576,126+i*22,14,GD,Paint.Align.RIGHT);mono(c,tr[i][1],636,126+i*22,16,G,Paint.Align.RIGHT);}
        float co=v(Telemetry.CLUTCH_ODD),ce=v(Telemetry.CLUTCH_EVEN);   // 7DCT clutch odd / even, once the TCU answers; blinks from amber up
        if(!(Float.isNaN(co)&&Float.isNaN(ce))&&(Math.max(Telemetry.clutchLevel(co),Telemetry.clutchLevel(ce))==0||blink(now,400))){
            mono(c,"CLU",576,214,14,GD,Paint.Align.RIGHT);mono(c,fmt("%.0f",co)+"/"+fmt("%.0f",ce)+"C",636,214,16,G,Paint.Align.RIGHT);}
        Calendar k=Calendar.getInstance();
        mono(c,String.format(Locale.US,"%02d:%02d:%02d",k.get(Calendar.HOUR_OF_DAY),k.get(Calendar.MINUTE),k.get(Calendar.SECOND)),740,470,15,GD,Paint.Align.RIGHT);
        mono(c,"ODO "+fmt("%.0f",v(Telemetry.ODOMETER)),60,470,15,GD,Paint.Align.LEFT);
        if(!Float.isNaN(rpm)&&rpm>prof.redline-500&&blink(now,400)){outline(c,CX-52,184,CX+52,214,0,2.5f,G);mono(c,"SHIFT",CX,206,22,G,Paint.Align.CENTER);}
        if(!Float.isNaN(brake)&&brake>35)mono(c,"BRAKE "+fmt("%.2f",lon)+" G",CX,334,18,G,Paint.Align.CENTER);
    }
    private static float rx(float x,float y,double a){return CX+(float)(x*Math.cos(a)-y*Math.sin(a));}
    private static float ry(float x,float y,double a){return CY+(float)(x*Math.sin(a)+y*Math.cos(a));}
    private void segR(Canvas c,float x1,float y1,float x2,float y2,double a,float w){line(c,rx(x1,y1,a),ry(x1,y1,a),rx(x2,y2,a),ry(x2,y2,a),w,G);}
    /** Vertical tape: side -1 has ticks on its right edge (speed), +1 on its left (rpm); labels every major/div. */
    private void tape(Canvas c,float x,float val,float step,float major,float scale,int side,String label,float red,float div){
        float top=112,bot=422,mid=(top+bot)/2,w=96,edge=side<0?x+w:x;
        c.save();c.clipRect(x-4,top,x+w+4,bot);
        if(!Float.isNaN(val)){
            float v0=(float)Math.floor((val-(mid-top)/scale)/step)*step;
            for(float t=v0;t<=val+(mid-top)/scale+step;t+=step){
                if(t<0)continue;float y=mid-(t-val)*scale;boolean maj=Math.abs(t%major)<0.01f;
                if(!Float.isNaN(red)&&t>=red)fill(c,x,y-step*scale,x+w,y,GF);
                line(c,edge,y,edge+side*(maj?16:8),y,2,G);
                if(maj)mono(c,String.valueOf(Math.round(t/div)),side<0?x+w-22:x+22,y+6,15,G,side<0?Paint.Align.RIGHT:Paint.Align.LEFT);
            }
        }
        c.restore();
        line(c,edge,top,edge,bot,2,G);
        float bx=side<0?x-4:x+10,bw=w-6;
        path.reset();
        if(side<0){path.moveTo(bx,mid-18);path.lineTo(bx+bw,mid-18);path.lineTo(bx+bw+12,mid);path.lineTo(bx+bw,mid+18);path.lineTo(bx,mid+18);}
        else{path.moveTo(bx+bw,mid-18);path.lineTo(bx,mid-18);path.lineTo(bx-12,mid);path.lineTo(bx,mid+18);path.lineTo(bx+bw,mid+18);}
        path.close();poly(c,0xff000000);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setColor(G);c.drawPath(path.kept(),p);p.setStyle(Paint.Style.FILL);
        mono(c,Float.isNaN(val)?"—":String.valueOf(Math.round(val)),bx+bw/2,mid+8,21,G,Paint.Align.CENTER);
        mono(c,label,x+w/2,top-8,15,G,Paint.Align.CENTER);
    }
}
