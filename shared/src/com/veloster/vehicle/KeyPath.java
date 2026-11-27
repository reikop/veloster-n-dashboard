package com.veloster.vehicle;

import android.graphics.Path;
import android.graphics.RectF;

/**
 * A scratch Path that hashes what is built into it, so a shape rebuilt every frame is drawn from one kept copy.
 * Android 4.2's GPU renderer rasterises a drawPath on the UI thread and reuses the result only for the same, unchanged
 * Path object; one shared path reset for every shape missed every time (2026-11-26 car traces, then a host count of
 * every screen: 전체 조망 38 panels a frame, 80년대 60 bar segments, 워프 and 카세트 their fixed frames).
 * Draw kept() instead of the path itself. UI thread only.
 * ponytail: keys are 64-bit hashes (a collision would draw the other shape); the memo is cleared past MAX, so shapes
 * that move every frame (a caret, a value band) only cost a copy each.
 */
final class KeyPath extends Path {
    private static final java.util.HashMap<Long,Path> MEMO=new java.util.HashMap<Long,Path>();
    static final int MAX=2000;
    private long key=1;
    private static long k(long h,float v){return ClusterKit.key(h,v);}   // no varargs: no array per point
    @Override public void reset(){super.reset();key=1;}
    @Override public void moveTo(float x,float y){super.moveTo(x,y);key=k(k(k(key,1),x),y);}
    @Override public void lineTo(float x,float y){super.lineTo(x,y);key=k(k(k(key,2),x),y);}
    @Override public void quadTo(float x1,float y1,float x2,float y2){super.quadTo(x1,y1,x2,y2);key=k(k(k(k(k(key,3),x1),y1),x2),y2);}
    @Override public void arcTo(RectF o,float start,float sweep){super.arcTo(o,start,sweep);key=k(k(k(k(k(k(k(key,4),o.left),o.top),o.right),o.bottom),start),sweep);}
    @Override public void close(){super.close();key=k(key,5);}
    /** An arc angle snapped to half a degree. 4.2 rasterises each drawArc with a new start or sweep into its own texture;
     *  a reading that holds still (cruise, a warm engine) now reuses one, and half a degree is under a pixel on the
     *  gauges (1 px at the 128 px 쇼 dial, 2 px at the RPM screen's rim). */
    static float arc(float deg){return Math.round(deg*2)/2f;}
    /** The kept copy of the shape now in this path, made on first use. */
    Path kept(){
        Path m=MEMO.get(key);
        if(m==null){if(MEMO.size()>=MAX)MEMO.clear();m=new Path(this);MEMO.put(key,m);}
        return m;
    }
}
