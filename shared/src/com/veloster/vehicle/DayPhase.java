package com.veloster.vehicle;

/**
 * Day, twilight or night by the clock, for the 8-bit views' sky and lamps. Seoul's mid-month sunrise and sunset
 * (KST, minutes after midnight); twilight is 30 minutes either side. No headlamp or light-sensor signal reaches
 * the head unit, so the clock stands in. No Android: host-tested.
 * ponytail: one table for Seoul; elsewhere in Korea differs by under half an hour.
 */
public final class DayPhase {
    public static final int DAY=0,TWILIGHT=1,NIGHT=2;
    private static final int[] RISE={465,445,410,365,330,312,325,350,375,400,430,455};
    private static final int[] SET={1055,1085,1115,1140,1165,1195,1195,1165,1125,1080,1045,1035};
    private DayPhase(){}
    /** month 1..12 (clamped), minute of the day 0..1439. */
    public static int of(int month,int minute){
        int m=Math.max(1,Math.min(12,month))-1,r=RISE[m],s=SET[m];
        if(Math.abs(minute-r)<=30||Math.abs(minute-s)<=30)return TWILIGHT;
        return minute>r&&minute<s?DAY:NIGHT;
    }
}
