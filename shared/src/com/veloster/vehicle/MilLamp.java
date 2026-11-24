package com.veloster.vehicle;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * Engine warning lamp (MIL) glyph, drawn in one place so every screen shows the same symbol.
 * Shape: the Material Design Icons "engine" outline (24x24 units), scaled into a box `size` px
 * tall and centred at (cx,cy). The caller decides WHEN to draw it (only while the lamp is lit,
 * from Telemetry.MIL) and WHERE it fits that screen's design, and passes the colour (amber is the
 * standard MIL colour; a dim grey when the caller draws it idle in an always-visible strip).
 */
public final class MilLamp {
    private MilLamp(){}
    public static final int AMBER=0xffffb020;
    private static final Path P=build();
    private static final Paint PAINT=new Paint(1);
    private static Path build(){
        Path p=new Path();   // M7,4 V6 H10 V8 H7 L5,10 V13 H3 V10 H1 V18 H3 V15 H5 V18 H8 L10,20 H18 V16 H20 V19 H23 V9 H20 V12 H18 V8 H12 V6 H15 V4 Z
        p.moveTo(7,4);p.lineTo(7,6);p.lineTo(10,6);p.lineTo(10,8);p.lineTo(7,8);p.lineTo(5,10);p.lineTo(5,13);
        p.lineTo(3,13);p.lineTo(3,10);p.lineTo(1,10);p.lineTo(1,18);p.lineTo(3,18);p.lineTo(3,15);p.lineTo(5,15);
        p.lineTo(5,18);p.lineTo(8,18);p.lineTo(10,20);p.lineTo(18,20);p.lineTo(18,16);p.lineTo(20,16);p.lineTo(20,19);
        p.lineTo(23,19);p.lineTo(23,9);p.lineTo(20,9);p.lineTo(20,12);p.lineTo(18,12);p.lineTo(18,8);p.lineTo(12,8);
        p.lineTo(12,6);p.lineTo(15,6);p.lineTo(15,4);p.close();
        return p;
    }
    /** Draw the lamp centred at (cx,cy), `size` px tall, in `color`. */
    public static void draw(Canvas c,float cx,float cy,float size,int color){
        PAINT.setColor(color);PAINT.setStyle(Paint.Style.FILL);
        int s=c.save();
        c.translate(cx,cy);c.scale(size/24f,size/24f);c.translate(-12,-12);
        c.drawPath(P,PAINT);
        c.restoreToCount(s);
    }
}
