package com.veloster.vehicle;

import com.veloster.i18n.UiText;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.util.Locale;

/** Actual receipt history; missing and invalid intervals are never filled with held values. */
public final class GraphRenderer {
    private final ThemedPaint p=new ThemedPaint(3);
    private float[] seg=new float[1024],dot=new float[512];   // drawLines pairs and drawPoints, grown as needed
    private final RectF card=new RectF();
    private static final int BLUE=0xffa4d9ef,WHITE=0xffe1edf2,MUTED=0xff8299a4,LINE=0xff293943;
    private void text(Canvas c,String s,float x,float y,float size,int color){p.setStyle(Paint.Style.FILL);p.setTypeface(Typeface.DEFAULT);p.setTextAlign(Paint.Align.LEFT);p.setTextSize(size);p.setColor(color);c.drawText(s,x,y,p);}
    private void line(Canvas c,float x,float y,float xx,float yy,int color){p.setStyle(Paint.Style.STROKE);p.setColor(color);p.setStrokeWidth(1);c.drawLine(x,y,xx,yy,p);p.setStyle(Paint.Style.FILL);}
    private static String number(int i,float value){return Fmt.f(i==0||i==7||i>=3&&i<=6||i==8?"%.0f":i==11||i==12||i==17?"%+.2f":i==13?"%+.1f":"%.1f",value);}
    private static float span(int signal){return signal==0?100:signal==11||signal==12?0.1f:signal==17?0.1f:signal==13?10:2;}
    public void draw(Canvas c,Telemetry model,long now,GraphLayout layout){
        text(c,UiText.t("그래프를 길게 눌러 항목 변경"),22,72,13,MUTED);
        for(int i=0;i<2;i++){
            float x=628+80*i;boolean selected=layout.window()==(i==0?60000:300000);
            p.setStyle(Paint.Style.FILL);p.setColor(selected?0xff294653:0xff142129);c.drawRoundRect(new RectF(x,54,x+74,79),5,5,p);
            text(c,i==0?UiText.t("1분"):UiText.t("5분"),x+24,72,14,selected?BLUE:MUTED);
        }
        for(int slot=0;slot<GraphLayout.SLOTS;slot++)drawSeries(c,model,now,layout,slot);
    }
    private void drawSeries(Canvas c,Telemetry model,long now,GraphLayout layout,int slot){
        int signal=layout.signal(slot);float top=GraphLayout.top(slot);
        p.setStyle(Paint.Style.FILL);p.setColor(0xff101920);card.set(18,top,782,top+124);c.drawRoundRect(card,7,7,p);
        text(c,UiText.t(Telemetry.LABELS[signal]),30,top+20,15,BLUE);
        Telemetry.Sample current=model.sample(signal,now);
        String value=current.valid?number(signal,Telemetry.displayValue(signal,current.value)):"—";
        text(c,value+" "+Telemetry.UNITS[signal],622,top+20,16,WHITE);
        Telemetry.Series series=model.history(signal,now,layout.window());
        float lo=Float.POSITIVE_INFINITY,hi=Float.NEGATIVE_INFINITY;
        for(int i=0;i<series.size();i++)if(series.valid[i]){float v=Telemetry.displayValue(signal,series.values[i]);lo=Math.min(lo,v);hi=Math.max(hi,v);}
        if(lo==Float.POSITIVE_INFINITY){lo=GaugeRenderer.min(signal,Telemetry.UNITS[signal]);hi=GaugeRenderer.max(signal,Telemetry.UNITS[signal]);}
        else{if(signal==Telemetry.STEERING||signal==11||signal==12){lo=Math.min(lo,0);hi=Math.max(hi,0);}float pad=Math.max(span(signal)/2,(hi-lo)*0.12f);lo-=pad;hi+=pad;}
        final float left=82,right=766,plotTop=top+30,bottom=top+103;
        for(int row=0;row<=2;row++){float y=plotTop+(bottom-plotTop)*row/2;line(c,left,y,right,y,LINE);text(c,number(signal,hi-(hi-lo)*row/2),27,y+4,10,MUTED);}
        text(c,layout.window()==60000?UiText.t("−1분"):UiText.t("−5분"),left,top+117,10,MUTED);text(c,layout.window()==60000?UiText.t("−30초"):UiText.t("−2분30초"),392,top+117,10,MUTED);text(c,UiText.t("지금"),740,top+117,10,MUTED);
        if(lo<0&&hi>0)line(c,left,bottom-(0-lo)/(hi-lo)*(bottom-plotTop),right,bottom-(0-lo)/(hi-lo)*(bottom-plotTop),0xff49616c);
        // segments with drawLines, not a Path: the line moves every frame, and 4.2 rasterised the path into a new texture each time
        int segs=0,dots=0;boolean connected=false;long previous=0;float px=0,py=0;
        int saved=c.save();c.clipRect(left,plotTop,right,bottom);
        for(int i=0;i<series.size();i++){
            if(!series.valid[i]){connected=false;continue;}
            float v=Telemetry.displayValue(signal,series.values[i]);
            float x=right-(now-series.times[i])*(right-left)/layout.window(),y=bottom-(v-lo)/(hi-lo)*(bottom-plotTop);
            if(connected&&!series.breakBefore[i]&&series.times[i]-previous<=2000){
                if(segs*4+4>seg.length)seg=java.util.Arrays.copyOf(seg,seg.length*2);
                seg[segs*4]=px;seg[segs*4+1]=py;seg[segs*4+2]=x;seg[segs*4+3]=y;segs++;}
            px=x;py=y;connected=true;previous=series.times[i];
            if(dots*2+2>dot.length)dot=java.util.Arrays.copyOf(dot,dot.length*2);dot[dots*2]=x;dot[dots*2+1]=y;dots++;
        }
        p.setStyle(Paint.Style.STROKE);p.setColor(BLUE);p.setStrokeWidth(2);p.setStrokeCap(Paint.Cap.ROUND);   // round ends join the segments
        if(segs>0)c.drawLines(seg,0,segs*4,p);
        p.setStrokeWidth(2.8f);if(dots>0)c.drawPoints(dot,0,dots*2,p);   // the samples as round points, one call (was a drawCircle each)
        p.setStrokeCap(Paint.Cap.BUTT);p.setStyle(Paint.Style.FILL);c.restoreToCount(saved);
    }
}
