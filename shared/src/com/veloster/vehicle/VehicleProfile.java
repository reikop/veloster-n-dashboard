package com.veloster.vehicle;

/**
 * Display constants of the car this build targets (redline, rpm scale, top gear, shift-light range). Shape follows the
 * i30 PD build's VehicleProfile (sung9155/i30-pd-dashboard, MIT) so the ported floating window and records compile
 * unchanged; the Veloster N JSN build has one profile: the 2.0 T-GDi with the six-speed manual.
 */
public final class VehicleProfile {
    public enum Trans { DCT, MT }
    public enum Fuel { PETROL, DIESEL }
    public final Fuel fuel;
    public final Trans trans;
    public final float redline,maxRpm;
    public final int topGear,shiftMin,shiftMax,shiftDefault;
    public final String label;
    private VehicleProfile(Fuel fuel,Trans trans,float redline,float maxRpm,int topGear,String label){
        this.fuel=fuel;this.trans=trans;this.redline=redline;this.maxRpm=maxRpm;this.topGear=topGear;this.label=label;
        shiftMin=ShiftGuide.MIN;shiftMax=ShiftGuide.MAX;shiftDefault=ShiftGuide.DEFAULT;
    }
    public static final VehicleProfile VELOSTER_N=new VehicleProfile(Fuel.PETROL,Trans.MT,6750,8000,6,"Veloster N 6MT");
    /** The i30 PD build's default profile name, kept for the ported drawing kit. */
    public static final VehicleProfile PETROL_DCT=VELOSTER_N;
    public int shiftTop(){return Math.min(shiftMax,(int)redline);}
}
