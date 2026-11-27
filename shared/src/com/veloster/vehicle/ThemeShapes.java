package com.veloster.vehicle;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Cell outlines for the themes that change shape: four cut corners (HEX) or rounded (ROUND). */
final class ThemeShapes {
    private ThemeShapes() {}
    static final float CUT=8,RADIUS=8;
    /** Hex cell outlines by box: Android 4.2 rasterises a rebuilt Path every frame and reuses only an unchanged Path
     *  object, so every cell of a hex theme cost a fresh rasterisation (see ClusterKit's memo). UI thread only. */
    private static final java.util.HashMap<Long,Path> HEX=new java.util.HashMap<Long,Path>();
    /** Draws fill + 1px edge in the current theme's shape and returns true, or returns false for SHAPE_CUT so the
     * caller keeps its original geometry (that is what keeps the N theme pixel-identical). Colours arrive themed. */
    static boolean cell(Canvas c,Paint p,Path path,RectF r,float l,float t,float rr,float b,int fill,int edge){
        int shape=Theme.current().shape;
        if(shape==Theme.SHAPE_CUT)return false;
        if(shape==Theme.SHAPE_ROUND){r.set(l,t,rr,b);}
        else{long key=ClusterKit.key(ClusterKit.key(ClusterKit.key(ClusterKit.key(11,l),t),rr),b);
            Path m=HEX.get(key);
            if(m==null){float k=Math.min(CUT,Math.min(rr-l,b-t)/3);m=new Path();
                m.moveTo(l+k,t);m.lineTo(rr-k,t);m.lineTo(rr,t+k);m.lineTo(rr,b-k);m.lineTo(rr-k,b);
                m.lineTo(l+k,b);m.lineTo(l,b-k);m.lineTo(l,t+k);m.close();
                if(HEX.size()>=ClusterKit.MEMO_MAX)HEX.clear();HEX.put(key,m);}
            path=m;}
        for(int pass=0;pass<2;pass++){
            p.setStyle(pass==0?Paint.Style.FILL:Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(pass==0?fill:edge);
            if(shape==Theme.SHAPE_ROUND)c.drawRoundRect(r,RADIUS,RADIUS,p);else c.drawPath(path,p);
        }
        p.setStyle(Paint.Style.FILL);
        return true;
    }
    /**
     * 2px ring that marks a warning when the danger colour is close to the accent. Amber frames it in white;
     * red's danger lamp is yellow on a red field, where a white ring clashes, so red frames it in the accent red.
     */
    static void ring(Canvas c,Paint p,float l,float t,float rr,float b){
        Theme th=Theme.current();
        if(!th.dangerRing)return;
        int col="red".equals(th.id)?th.accent:0xffffffff;
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setColor(col);c.drawRect(l+2,t+2,rr-2,b-2,p);p.setStyle(Paint.Style.FILL);
    }
}
