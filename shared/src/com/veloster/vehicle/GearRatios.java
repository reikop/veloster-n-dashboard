package com.veloster.vehicle;

/**
 * rpm per km/h of each gear, learned while driving, for gearboxes without a ratio table here (the 7DCT, a diesel): a
 * gear counts once it has held HOLD_MS with the clutch closed, above MIN_KMH and MIN_RPM, and then follows the reading
 * slowly (a smoothing factor of 0.05 a sample). A gear never driven yet has no ratio. No Android: host-tested.
 */
public final class GearRatios {
    public static final long HOLD_MS=1500;public static final float MIN_KMH=20,MIN_RPM=1000;
    private final float[] perKmh=new float[9];
    private int gear=-1;private long since;

    /** Every frame; clutch: a 6MT clutch pedal in (a DCT passes false). */
    public void update(long now,float gearValue,float rpm,float kmh,boolean clutch){
        int g=Float.isNaN(gearValue)?-1:(int)gearValue;
        if(g!=gear){gear=g;since=now;return;}
        if(g<1||g>8||clutch||!(kmh>=MIN_KMH)||!(rpm>=MIN_RPM)||now-since<HOLD_MS)return;
        float x=rpm/kmh;perKmh[g]=perKmh[g]==0?x:perKmh[g]+(x-perKmh[g])*0.05f;
    }
    /** The rpm gear g would turn at this speed, to 50 rpm; NaN before g has been driven. */
    public float rpmIn(float kmh,int g){
        return g<1||g>8||perKmh[g]==0||Float.isNaN(kmh)?Float.NaN:Math.round(kmh*perKmh[g]/50f)*50f;
    }
}
