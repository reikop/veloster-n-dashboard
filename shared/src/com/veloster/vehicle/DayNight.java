package com.veloster.vehicle;

/**
 * Day or night for the floating window's look. The rule is the stock navigation's (HKMC_Navi_ExSL.odex
 * NaviVideoSetupListener): screen mode 1 = day, 2 = night, 0 and 3 follow the illumination. The illumination
 * polarity is unconfirmed (ILLUM_TRUE_IS_NIGHT, false until checked in the car). Then the headlamps, then the clock
 * (DayPhase); unknown = night. A switch only after the new state has held for HOLD_MS. No Android: host-tested.
 */
public final class DayNight {
    public static final int AUTO=0,DAY=1,NIGHT=2;
    public static final int UNKNOWN=-1;
    public static boolean ILLUM_TRUE_IS_NIGHT=false;
    public static final long HOLD_MS=3000;
    public static volatile int oemDim=UNKNOWN,oemIllum=UNKNOWN;
    public static final Hold HOLD=new Hold();
    private DayNight(){}

    /** oemDim, oemIllum, lamp: UNKNOWN or 0/1 (illum, lamp); phase: UNKNOWN or DayPhase.DAY/TWILIGHT/NIGHT. */
    public static boolean rawNight(int oemDim,int oemIllum,int lamp,int phase){
        if(oemDim==1)return false;
        if(oemDim==2)return true;
        if(oemIllum!=UNKNOWN)return (oemIllum==1)==ILLUM_TRUE_IS_NIGHT;
        if(lamp!=UNKNOWN)return lamp==1;
        if(phase!=UNKNOWN)return phase!=DayPhase.DAY;
        return true;
    }

    public static String source(int oemDim,int oemIllum,int lamp,int phase){
        if(oemDim==1||oemDim==2||oemIllum!=UNKNOWN)return "oem";
        if(lamp!=UNKNOWN)return "lamp";
        if(phase!=UNKNOWN)return "clock";
        return "none";
    }

    /** Night state that must hold for HOLD_MS before it is adopted. */
    public static final class Hold{
        public boolean night=true;
        private boolean waiting;
        private long since;
        /** True only on the call that changes the state. */
        public boolean update(boolean raw,long now){
            if(raw==night){waiting=false;return false;}
            if(!waiting){waiting=true;since=now;return false;}
            if(now-since<HOLD_MS)return false;
            night=raw;waiting=false;return true;
        }
    }

    /** Whether to use the day look: AUTO follows night, DAY always day, NIGHT never. */
    public static boolean pick(int dn,boolean night){
        if(dn==DAY)return true;
        if(dn==NIGHT)return false;
        return !night;
    }

    /** First run after the update (no float_dn key): {dn, skinDay, skinNight, toRound}. Old round gauge 9 becomes the 기본 look plus the round shape. */
    public static int[] firstRead(int oldSkin){
        return new int[]{NIGHT,10,oldSkin==9?0:Math.max(0,oldSkin),oldSkin==9?1:0};  // 10 = FloatSkin.WHITE (no Android here)
    }
}
