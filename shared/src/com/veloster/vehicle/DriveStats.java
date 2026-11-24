package com.veloster.vehicle;

/** Session records computed from received samples only (no Android): acceleration runs, peaks, warm-up / turbo
 * cool-down advice and a short G trail. Fed once per drawn frame; every value is timed by its own sample time, so the
 * result does not depend on the frame rate. Reset when the app closes. Ported from the i30 PD build
 * (sung9155/i30-pd-dashboard, MIT). */
public final class DriveStats {
    /** Acceleration runs in km/h: 0-100 from standstill, then two rolling in-gear runs. */
    public static final float[][] RUNS={{0,100},{60,100},{80,120}};
    public static final int TRAIL=24;
    static final float STOP_KMH=0.5f,WARM_C=70,HARD_BOOST=0.7f,HARD_RPM=4500;
    /** Turbo cool-down only after sustained hard use: HARD_SUM_MS of boost >= HARD_BOOST or rpm >= HARD_RPM within the
     *  HARD_WINDOW_MS before stopping. A city drive's short boost bursts (10/29 log: 7 s over 0.5 bar in 10 min, which
     *  showed COOL-DOWN at one of two stops) no longer count. */
    static final long RUN_LIMIT_MS=30000,COOLDOWN_MS=45000,HARD_WINDOW_MS=300000,HARD_SUM_MS=30000;

    // Runs: armed below the start speed, running after the upward crossing, finished at the end speed.
    public final float[] last=nan(3),best=nan(3);
    public final boolean[] running=new boolean[3];
    public final long[] runStart=new long[3],finishedAt=new long[3];
    private final boolean[] armed=new boolean[3];
    // Peaks.
    public float maxG=Float.NaN,maxLat=Float.NaN,maxDecel=Float.NaN,maxBoost=Float.NaN,maxRpm=Float.NaN,maxSpeed=Float.NaN;
    /** 7DCT clutch temperatures, hottest this session (the TCU is asked every 2 s, so a sample up to 5 s old counts). */
    public float maxClutchOdd=Float.NaN,maxClutchEven=Float.NaN;
    // Warm-up / cool-down.
    public boolean warmUp;private long lastHardAt=Long.MIN_VALUE/2,stoppedAt=-1,hardFrameAt=Long.MIN_VALUE;
    /** Hard-use time per frame (start ms, length ms), oldest first, kept for HARD_WINDOW_MS; and whether the current stop
     *  followed enough of it. */
    private final java.util.ArrayDeque<long[]> hard=new java.util.ArrayDeque<long[]>();private boolean coolNeeded;
    // G trail, oldest first when read through trailX/trailY(i).
    private final float[] tx=new float[TRAIL],ty=new float[TRAIL];private int tHead,tCount;
    private float prevSpeed=Float.NaN;private long prevSpeedAt=Long.MIN_VALUE,prevGAt=Long.MIN_VALUE;
    public long now;
    /** Last finished corner (|lateral G| >= CORNER_G for CORNER_MIN_MS, ended by CORNER_END_MS below it): its lowest
     * speed (km/h) and peak |lateral G|. Last finished pull (pedal >= PULL_THROTTLE): its peak boost (bar). NaN = none yet. */
    public float cornerMinSpeed=Float.NaN,cornerMaxLat=Float.NaN,pullMaxBoost=Float.NaN;
    static final float CORNER_G=0.25f,PULL_THROTTLE=70f;
    static final long CORNER_MIN_MS=1000,CORNER_END_MS=500,PULL_MIN_MS=500,PULL_END_MS=300;
    /** A hole longer than this (screen hidden, data dropout) drops the run in progress instead of counting as its time. */
    static final long GAP_MS=1000;
    private boolean inCorner,inPull;private long cornerAbove,cornerBelow,pullAbove,pullBelow,latAt=Long.MIN_VALUE,pedalAt=Long.MIN_VALUE;
    private float runMin,runLat,runBoost;

    private static float[] nan(int n){float[] a=new float[n];for(int i=0;i<n;i++)a[i]=Float.NaN;return a;}
    private static float max(float a,float b){return Float.isNaN(a)?b:Float.isNaN(b)?a:Math.max(a,b);}

    /** Gear ratios learned while driving (the Veloster N build has no ratio table: RevMatch reads these), and the last
     *  6MT shift measured (the 람다 screen; it needs a clutch signal, which this build does not receive yet). */
    public final GearRatios ratios=new GearRatios();public final ShiftCoach coach=new ShiftCoach();
    /** Feeds the learned ratios from the EMS gear (verified 1-6 on this 6MT). */
    public void gear(long now,float gear,float rpm,float kmh){ratios.update(now,gear,rpm,kmh,false);}
    public void update(Telemetry.Sample[] s,long now){
        update(now,f(s,7)*Telemetry.speedScale,at(s,7,now),f(s,0),f(s,Telemetry.BOOST),f(s,11),f(s,12),at(s,12,now),f(s,2),f(s,9));
        for(int k=0;k<2;k++){Telemetry.Sample x=s[Telemetry.CLUTCH_ODD+k];if(!x.valid||x.ageMs>5000)continue;
            if(k==0)maxClutchOdd=max(maxClutchOdd,x.value);else maxClutchEven=max(maxClutchEven,x.value);}
    }
    private static float f(Telemetry.Sample[] s,int i){return s[i].valid&&s[i].ageMs<=1000?s[i].value:Float.NaN;}
    private static long at(Telemetry.Sample[] s,int i,long now){return now-s[i].ageMs;}

    /** NaN means not received; speedAt/gAt are the sample times. */
    public void update(long now,float speed,long speedAt,float rpm,float boost,float lat,float lon,long gAt,float coolant){
        update(now,speed,speedAt,rpm,boost,lat,lon,gAt,coolant,Float.NaN);
    }
    /** throttle is the accelerator pedal %; NaN (not received) never advances the pull timer. */
    public void update(long now,float speed,long speedAt,float rpm,float boost,float lat,float lon,long gAt,float coolant,float throttle){
        corner(speed,lat,gAt);pull(boost,throttle,now);
        this.now=now;
        maxRpm=max(maxRpm,rpm);maxBoost=max(maxBoost,boost);maxSpeed=max(maxSpeed,speed);
        if(!Float.isNaN(lat))maxLat=max(maxLat,Math.abs(lat));
        if(!Float.isNaN(lon)&&lon<0)maxDecel=max(maxDecel,-lon);
        warmUp=!Float.isNaN(coolant)&&coolant<WARM_C;
        long frame=hardFrameAt==Long.MIN_VALUE?0:Math.max(0,Math.min(GAP_MS,now-hardFrameAt));hardFrameAt=now;   // a hole counts nothing
        if((!Float.isNaN(boost)&&boost>=HARD_BOOST)||(!Float.isNaN(rpm)&&rpm>=HARD_RPM)){lastHardAt=now;if(frame>0)hard.addLast(new long[]{now,frame});}
        while(!hard.isEmpty()&&now-hard.peekFirst()[0]>HARD_WINDOW_MS)hard.removeFirst();
        for(int i=0;i<3;i++)if(running[i]&&now-prevSpeedAt>GAP_MS)running[i]=false;   // speed stopped arriving
        if(!Float.isNaN(speed)&&speedAt!=prevSpeedAt){
            if(!Float.isNaN(prevSpeed)&&speedAt-prevSpeedAt>GAP_MS){for(int i=0;i<3;i++)running[i]=false;prevSpeed=Float.NaN;}   // no crossing inside a hole
            runs(speed,speedAt);
            if(speed<STOP_KMH&&stoppedAt<0){stoppedAt=speedAt;long sum=0;for(long[] h:hard)sum+=h[1];coolNeeded=sum>=HARD_SUM_MS;}   // decided once, at the stop
            else if(speed>=STOP_KMH)stoppedAt=-1;
            prevSpeed=speed;prevSpeedAt=speedAt;}
        if(!Float.isNaN(lat)&&!Float.isNaN(lon)&&gAt!=prevGAt){
            maxG=max(maxG,(float)Math.sqrt(lat*lat+lon*lon));
            tx[tHead]=lat;ty[tHead]=lon;tHead=(tHead+1)%TRAIL;if(tCount<TRAIL)tCount++;
            prevGAt=gAt;
        }
    }
    /** A NaN lateral sample leaves the corner state alone, and the gap it spans is not counted on either side. */
    private void corner(float speed,float lat,long t){
        if(Float.isNaN(lat)){latAt=Long.MIN_VALUE;return;}
        if(t==latAt)return;
        long dt=latAt==Long.MIN_VALUE?0:t-latAt;latAt=t;
        if(dt>GAP_MS){inCorner=false;dt=0;}
        float a=Math.abs(lat);
        if(a>=CORNER_G){
            if(!inCorner){inCorner=true;cornerAbove=0;runMin=Float.NaN;runLat=a;}else cornerAbove+=dt;
            cornerBelow=0;runLat=Math.max(runLat,a);if(!Float.isNaN(speed))runMin=Float.isNaN(runMin)?speed:Math.min(runMin,speed);
        }else if(inCorner&&(cornerBelow+=dt)>=CORNER_END_MS){
            if(cornerAbove>=CORNER_MIN_MS){cornerMinSpeed=runMin;cornerMaxLat=runLat;}
            inCorner=false;
        }
    }
    private void pull(float boost,float throttle,long t){
        if(Float.isNaN(throttle)){pedalAt=Long.MIN_VALUE;return;}
        long dt=pedalAt==Long.MIN_VALUE?0:t-pedalAt;pedalAt=t;
        if(dt>GAP_MS){inPull=false;dt=0;}
        if(throttle>=PULL_THROTTLE){
            if(!inPull){inPull=true;pullAbove=0;runBoost=Float.NaN;}else pullAbove+=dt;
            pullBelow=0;runBoost=max(runBoost,boost);
        }else if(inPull&&(pullBelow+=dt)>=PULL_END_MS){
            if(pullAbove>=PULL_MIN_MS&&!Float.isNaN(runBoost))pullMaxBoost=runBoost;   // a pedal blip is not a pull
            inPull=false;
        }
    }
    private void runs(float speed,long t){
        for(int i=0;i<3;i++){
            float from=RUNS[i][0]==0?STOP_KMH:RUNS[i][0],to=RUNS[i][1];
            if(running[i]){
                if(speed<from||t-runStart[i]>RUN_LIMIT_MS)running[i]=false;   // backed off or gave up
                else if(speed>=to){
                    long end=cross(prevSpeed,prevSpeedAt,speed,t,to);
                    last[i]=(end-runStart[i])/1000f;if(Float.isNaN(best[i])||last[i]<best[i])best[i]=last[i];
                    running[i]=false;finishedAt[i]=end;Records.add(Records.runKey(i),last[i]);   // 기록실
                }
            }else if(armed[i]&&speed>=from&&!Float.isNaN(prevSpeed)&&prevSpeed<from){
                running[i]=true;runStart[i]=cross(prevSpeed,prevSpeedAt,speed,t,from);
            }
            armed[i]=RUNS[i][0]==0?speed<STOP_KMH||(armed[i]&&speed<from):speed<from;
        }
    }
    /** Time where speed crossed v between two samples (linear). */
    private static long cross(float v0,long t0,float v1,long t1,float v){
        if(Float.isNaN(v0)||v1==v0)return t1;
        return t0+Math.round((t1-t0)*(double)Math.max(0,Math.min(1,(v-v0)/(v1-v0))));
    }
    /** Remaining turbo cool-down (ms) while parked after sustained hard use (HARD_SUM_MS in the HARD_WINDOW_MS before
     *  the stop); 0 = done, -1 = not needed. */
    public long coolDownLeft(){
        if(stoppedAt<0||!coolNeeded)return -1;
        return Math.max(0,COOLDOWN_MS-(now-Math.max(stoppedAt,lastHardAt)));
    }
    public int trailCount(){return tCount;}
    public float trailX(int i){return tx[(tHead-tCount+i+TRAIL)%TRAIL];}
    public float trailY(int i){return ty[(tHead-tCount+i+TRAIL)%TRAIL];}
    /** Top of a boost bar: 1.5 bar, widened in 0.5 bar steps once the highest boost seen goes past it (tuned cars). */
    public static float boostTop(float peak){return Float.isNaN(peak)?1.5f:Math.max(1.5f,(float)Math.ceil((peak+0.05f)*2)/2);}
    public float boostTop(){return boostTop(maxBoost);}
}
