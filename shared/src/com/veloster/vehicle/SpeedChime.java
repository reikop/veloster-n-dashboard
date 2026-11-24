package com.veloster.vehicle;

/**
 * 과속 차임: like the speed chime of Japanese-market cars of the 1980s (the AE86's "킨콘"), a sound that keeps ringing while
 * the car is over a set speed. Off by default; the speed and the sound are picked in 설정 → 사운드. It starts at the speed
 * and stops 3 km/h under it, so it does not stutter on the edge, and rings once every period of the chosen sound (the
 * AE86's every 1.15 s, as measured). No Android: host-tested.
 */
public final class SpeedChime {
    /** The speed choices, km/h; 0 = off. 105 is the old Japanese limit the original chime was set to. */
    public static final int[] KMH={0,100,105,110,120};
    /** The sounds (ShiftBeep.CHIMES order) and how often each rings, ms. */
    public static final String[] NAMES={"AE86 킨콘","GR86 삐삐삐","기계식 종","딩","유리 톡톡","전자 부저"};
    public static final long[] PERIOD_MS={1150,2100,2000,2000,2000,2000};
    static final float HYSTERESIS=3;
    public static volatile int kmh=0,sound=0;
    private boolean over;private long last=Long.MIN_VALUE/2;

    /** True when a ring is due now: speed (km/h, NaN = unknown) at or over the set speed, a period since the last ring. */
    public boolean due(float speed,long now){
        int at=kmh;
        if(at<=0||Float.isNaN(speed)){over=false;return false;}
        over=over?speed>=at-HYSTERESIS:speed>=at;
        return over&&now-last>=PERIOD_MS[Math.max(0,Math.min(PERIOD_MS.length-1,sound))];
    }
    /** The ring asked for by due() actually played (a sound still playing makes the next frame try again). */
    public void rang(long now){last=now;}
    /** The next speed choice after the current one (a tap on the settings row). */
    public static int next(int current){for(int i=0;i<KMH.length;i++)if(KMH[i]==current)return KMH[(i+1)%KMH.length];return KMH[0];}
}
