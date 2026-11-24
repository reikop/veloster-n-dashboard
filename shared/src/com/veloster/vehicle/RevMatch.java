package com.veloster.vehicle;

/**
 * From the i30 PD build (sung9155/i30-pd-dashboard, MIT); on the Veloster N build the targets come from learned ratios.
 * Downshift targets for the 수동 screen and the rev-match cue: the rpm each gear one to three below the current one
 * would turn at this speed. The petrol 6MT uses the 1.6 T-GDi 6MT ratio table (Telemetry.mtRpmIn); any other gearbox
 * (the 7DCT, a diesel) uses the ratios learned while driving (GearRatios), so a gear shows once it has been driven.
 * Only gears the engine can take count: from LOW_RPM up to the redline; one that would over-rev is left out, not warned
 * about (the owner's call: people read a positive cue faster than a negative one). The rev match itself (clutch in, the
 * rpm within MATCH_RPM of a target) needs a clutch pedal, so it is the 6MT's only. No Android: host-tested.
 */
public final class RevMatch {
    public static final float MATCH_RPM=100,LOW_RPM=1500;
    private RevMatch(){}

    /** A petrol 6MT: the car the ratio table fits, and the one with a clutch to match the blip on. */
    public static boolean applies(VehicleProfile p){return p.trans==VehicleProfile.Trans.MT&&p.fuel==VehicleProfile.Fuel.PETROL;}
    /** The clutch in or the brake pressed (Telemetry.braking): the moment of a downshift, when the marks show at full strength. */
    public static boolean active(Telemetry.Sample[] s){
        Telemetry.Sample k=s[Telemetry.CLUTCH],b=s[10],sw=s[Telemetry.BRAKE_SWITCH];
        return k.valid&&k.value>0||Telemetry.braking(sw.valid?sw.value:Float.NaN,b.valid?b.value:Float.NaN);
    }
    /** The rpm gear g would turn at this speed: the table on a petrol 6MT, else the learned ratio (NaN if none yet). */
    public static float rpmIn(VehicleProfile p,GearRatios learned,float kmh,int g){
        // Veloster N build: no ratio table is trusted for this gearbox yet, so every car uses the ratios it learned
        // while driving (a gear shows once it has been driven); Telemetry.mtRpmIn answers NaN.
        return learned==null?Float.NaN:learned.rpmIn(kmh,g);
    }
    /** Fills gear[] / rpm[] (length 3, one gear down first) with the gears worth showing; returns how many. A 6MT gets
     *  up to three gears down (skip shifts); a DCT only the next one down (the paddle steps one gear at a time). */
    public static int targets(Telemetry.Sample[] s,VehicleProfile p,GearRatios learned,int[] gear,float[] rpm){
        Telemetry.Sample g=s[8],v=s[7];if(!g.valid||!v.valid||!(v.value>=10))return 0;
        int cur=(int)g.value,n=0;if(cur<2||cur>p.topGear)return 0;
        int most=p.trans==VehicleProfile.Trans.MT?3:1;
        for(int k=1;k<=most&&cur-k>=1;k++){float t=rpmIn(p,learned,v.value,cur-k);
            if(t>=LOW_RPM&&t<p.redline){gear[n]=cur-k;rpm[n]=t;n++;}}
        return n;
    }
    /** The petrol 6MT's targets from the table alone (host tests). */
    public static int targets(float kmh,float current,float redline,int[] gear,float[] rpm){
        float[] t=Telemetry.mtDownshift(kmh,current);int n=0;
        for(int k=0;k<t.length;k++)if(t[k]>=LOW_RPM&&t[k]<redline){gear[n]=(int)current-k-1;rpm[n]=t[k];n++;}
        return n;
    }
    /** The gear the rpm is matched to (6MT, clutch in, within MATCH_RPM of its target), or 0. */
    public static int matched(Telemetry.Sample[] s,VehicleProfile p){
        Telemetry.Sample k=s[Telemetry.CLUTCH],r=s[0],g=s[8],v=s[7];
        if(!applies(p)||!k.valid||k.value<=0||!r.valid||!g.valid||!v.valid)return 0;
        return matched(r.value,v.value,g.value,p.redline);
    }
    /** How many gears down the blip is matched to (1-3), or 0: the 람다 screen's downshift sounds. */
    public static int downs(Telemetry.Sample[] s,VehicleProfile p){
        int m=matched(s,p);return m>0?(int)s[8].value-m:0;
    }
    /** When a downshift sound plays: as the blip lands on a gear's target, once; not again for the same gear within
     *  MIN_GAP ms, so rpm hovering at the edge of the window does not chirp. Pure, host-tested. */
    public static final class Cue {
        public static final long MIN_GAP=800;
        private int was,lastDowns;private long lastAt=Long.MIN_VALUE/2;
        public int fire(int downs,long now){
            boolean edge=downs>0&&downs!=was;was=downs;
            if(!edge||downs==lastDowns&&now-lastAt<MIN_GAP)return 0;
            lastDowns=downs;lastAt=now;return downs;
        }
    }
    /** With the clutch in: the lower gear whose target the rpm is within MATCH_RPM of, or 0. */
    public static int matched(float rpm,float kmh,float current,float redline){
        int[] g=new int[3];float[] t=new float[3];int n=targets(kmh,current,redline,g,t);
        for(int i=0;i<n;i++)if(Math.abs(rpm-t[i])<=MATCH_RPM)return g[i];
        return 0;
    }
}
