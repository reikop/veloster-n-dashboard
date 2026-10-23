package com.veloster.vehicle;

/** Adjustable display advisory, NOT a measured optimal-shift map or ECU control. */
public final class ShiftGuide {
    public static final int MIN=6000, MAX=6700, DEFAULT=6000;
    public static int clamp(int rpm) { return Math.max(MIN,Math.min(MAX,rpm/100*100)); }
    public static final class Cue {
        public final int leds;
        public final boolean shift;
        Cue(int n,boolean s) { leds=n;shift=s; }
    }
    public static Cue evaluate(Telemetry.Sample[] s,int target) {
        target=clamp(target);
        boolean fresh=s[0].valid && s[0].ageMs<=500;
        int leds=fresh?Math.max(0,Math.min(15,(int)((s[0].value-(target-1500))/100)+1)):0;
        boolean moving=s[7].valid && s[7].ageMs<=500 && s[7].value>0;
        boolean gear=s[8].valid && s[8].ageMs<=500 && s[8].value>=1 && s[8].value<6;
        return new Cue(leds,fresh && moving && gear && s[0].value>=target);
    }
}
