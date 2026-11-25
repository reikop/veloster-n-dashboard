package com.veloster.vehicle;

import java.util.Locale;

/**
 * Lap timer for the performance page. Tap: start, then each tap closes a lap and starts the next (taps within
 * MIN_LAP of a lap's start are ignored, so a double tap cannot record a 0.3 s lap); a stopped timer resumes the same
 * lap. Hold: stop while running, clear everything while stopped. No Android: host-tested.
 */
public final class LapTimer {
    public static final int IDLE=0,RUNNING=1,STOPPED=2;
    static final long MIN_LAP=3000;
    private int state;
    private long lapStart,stoppedAt;
    /** Laps completed; the last, the best, and the best before the last lap (for its delta), -1 when none. */
    public int laps;
    public long last=-1,best=-1,bestBefore=-1;

    public int state(){return state;}
    /** True when the tap did something. */
    public boolean tap(long now){
        if(state==IDLE){lapStart=now;state=RUNNING;return true;}
        if(state==STOPPED){lapStart+=now-stoppedAt;state=RUNNING;return true;}
        long t=now-lapStart;
        if(t<MIN_LAP)return false;
        bestBefore=best;last=t;if(best<0||t<best)best=t;laps++;lapStart=now;return true;
    }
    /** True when the hold did something. */
    public boolean hold(long now){
        if(state==RUNNING){stoppedAt=now;state=STOPPED;return true;}
        if(state==STOPPED){state=IDLE;laps=0;last=best=bestBefore=-1;return true;}
        return false;
    }
    /** The lap in progress (frozen while stopped, 0 when idle). */
    public long current(long now){return state==IDLE?0:(state==RUNNING?now:stoppedAt)-lapStart;}
    /** Last lap against the best before it (negative: a new best); Long.MIN_VALUE when there is nothing to compare. */
    public long delta(){return last<0||bestBefore<0?Long.MIN_VALUE:last-bestBefore;}
    /** m:ss.cc, minutes unpadded ("1:23.45"); "—" for -1. */
    public static String format(long ms){
        if(ms<0)return "—";long cs=ms/10;
        return String.format(Locale.US,"%d:%02d.%02d",cs/6000,cs/100%60,cs%100);
    }
    /** +0.42 / -1.05 seconds. */
    public static String formatDelta(long ms){return String.format(Locale.US,"%s%d.%02d",ms<0?"-":"+",Math.abs(ms)/1000,Math.abs(ms)/10%100);}
}
