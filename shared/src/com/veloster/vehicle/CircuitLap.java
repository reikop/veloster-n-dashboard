package com.veloster.vehicle;

/**
 * GPS lap timing on a known circuit (track). The line sits at the track's first point, across its first stretch. Where
 * the track's points run in the driving direction only a crossing that way starts lap 1, so driving in from the pits or
 * a service road, or GPS wander at the line, cannot set it backwards; elsewhere the first crossing at walking pace or
 * more (MOVING_MPS), either way round, starts lap 1 and fixes the direction. The out lap before that is not timed. After that a lap ends whenever the segment
 * between two fixes crosses the line (HALF_M either side) going the same way, at least MIN_LAP_MS after the last one;
 * the crossing time is interpolated along the segment, so a 1 Hz receiver still gives about a tenth of a second.
 * Each fix also records how far round the lap the car is and when, so the live gap to the best lap (gap) compares
 * the same distance on both. Positions are metres east / north of the line. No Android: host-tested.
 */
public final class CircuitLap {
    static final double HALF_M=30;
    static final long MIN_LAP_MS=20000;
    static final double MOVING_MPS=5;
    static final int MAX_POINTS=3000;   // ponytail: about 50 min at 1 Hz in one lap; longer laps drop the map line, not the timing
    /** No track, timing, or waiting for the first crossing. */
    public static final int NONE=0,TIMING=2,WAIT=3;
    private int state=NONE;
    /** Local frame: origin and metres per degree of longitude there. */
    private double lat0=Double.NaN,lon0,kx;
    /** The line: a point and the unit direction the car crosses it. */
    private double ax,ay,hx,hy;
    private TrackPath path;
    /** +1 when the car drives the track in the order of its points, -1 the other way. */
    private int sense=1;
    private boolean known;
    /** The last fix in metres and its time, and the one before (for the car's speed between fixes). */
    private double px=Double.NaN,py,qx=Double.NaN,qy;
    private long pt,qt;
    public int laps;
    /** Last and best lap (ms), -1 before one; the current lap's start (fix clock, ms). */
    public long last=-1,best=-1,startAt;
    /** The best lap before the last one finished, for the delta (-1: none). */
    public long bestBefore=-1;
    /** At the last fix: ms behind (+) or ahead (-) of the best lap at the same distance; Long.MIN_VALUE before one. */
    public long gap=Long.MIN_VALUE;
    private float[] cur=new float[256];
    private int curN;
    /** This lap's and the best lap's distance (m from the line) and time (ms from its start) at each fix. */
    private double[] curS=new double[128],bestS;
    private long[] curT=new long[128],bestT;
    private int curSN;

    public int state(){return state;}
    public void reset(){state=NONE;lat0=Double.NaN;px=Double.NaN;qx=Double.NaN;path=null;laps=0;last=-1;best=-1;bestBefore=-1;gap=Long.MIN_VALUE;curN=0;curSN=0;bestS=null;}
    /** Time laps on track p, its points in metres east / north of (lat, lon); directionKnown: they run the driving way. */
    public void track(double lat,double lon,TrackPath p,boolean directionKnown){
        reset();lat0=lat;lon0=lon;kx=111320*Math.cos(Math.toRadians(lat));path=p;known=directionKnown;
        double[] a=p.at(0),b=p.at(1);double n=Math.hypot(b[0]-a[0],b[1]-a[1]);
        ax=a[0];ay=a[1];hx=(b[0]-a[0])/n;hy=(b[1]-a[1])/n;state=WAIT;
    }
    /** A GPS fix: degrees and the fix time in ms (any steady clock). Without a track it only keeps the position. */
    public void fix(double lat,double lon,long tMs){
        if(Double.isNaN(lat0)){lat0=lat;lon0=lon;kx=111320*Math.cos(Math.toRadians(lat));}
        double x=(lon-lon0)*kx,y=(lat-lat0)*110540;
        if(!Double.isNaN(px)&&state!=NONE){
            double sp=(px-ax)*hx+(py-ay)*hy,sc=(x-ax)*hx+(y-ay)*hy;
            boolean across=state==WAIT&&!known?(sp<0)!=(sc<0)&&Math.hypot(x-px,y-py)*1000>=MOVING_MPS*(tMs-pt):sp<0&&sc>=0;
            if(across){
                double f=-sp/(sc-sp),ix=px+f*(x-px),iy=py+f*(y-py);
                long t=pt+Math.round(f*(tMs-pt));
                if(Math.abs((ix-ax)*-hy+(iy-ay)*hx)<=HALF_M){
                    if(state==WAIT){
                        if(sc<sp){hx=-hx;hy=-hy;}   // moving against the guessed direction (sp may be exactly 0)
                        sense=hx*(path.at(1)[0]-ax)+hy*(path.at(1)[1]-ay)>0?1:-1;
                        state=TIMING;startAt=t;
                        curN=0;add(ix,iy);curSN=0;sample(0,0);
                    }else if(t-startAt>=MIN_LAP_MS){
                        long lap=t-startAt;last=lap;bestBefore=best;best=best<0?lap:Math.min(best,lap);laps++;startAt=t;
                        if(lap==best){sample(path.length,lap);bestS=java.util.Arrays.copyOf(curS,curSN);bestT=java.util.Arrays.copyOf(curT,curSN);}
                        curN=0;add(ix,iy);curSN=0;sample(0,0);
                    }
                }
            }
        }
        if(state==TIMING){
            add(x,y);
            double s=path.along(x,y);if(sense<0)s=(path.length-s)%path.length;
            long e=tMs-startAt;
            if(s>path.length*0.75&&e<15000)s=0;   // just past the line, but placed a little behind it
            sample(s,e);
            gap=bestS==null?Long.MIN_VALUE:e-bestAt(s);
        }
        qx=px;qy=py;qt=pt;px=x;py=y;pt=tMs;
    }
    private void add(double x,double y){
        if(curN+2>MAX_POINTS*2)return;
        if(curN+2>cur.length)cur=java.util.Arrays.copyOf(cur,cur.length*2);
        cur[curN++]=(float)x;cur[curN++]=(float)y;
    }
    /** Distance and time on the lap in progress; a fix placed behind the last one is left out. */
    private void sample(double s,long t){
        if(curSN>0&&s<curS[curSN-1])return;
        if(curSN==curS.length){curS=java.util.Arrays.copyOf(curS,curSN*2);curT=java.util.Arrays.copyOf(curT,curSN*2);}
        curS[curSN]=s;curT[curSN]=t;curSN++;
    }
    /** The best lap's time at distance s, between its samples. */
    private long bestAt(double s){
        int i=0;while(i<bestS.length-2&&bestS[i+1]<s)i++;
        double seg=bestS[i+1]-bestS[i],f=seg<=0?0:Math.max(0,Math.min(1,(s-bestS[i])/seg));
        return bestT[i]+Math.round(f*(bestT[i+1]-bestT[i]));
    }
    /** The current lap's path so far (x, y pairs; count = curPoints()). */
    public float[] curPath(){return cur;}
    public int curPoints(){return curN/2;}
    /** Where the car is, in metres (NaN before a fix); the fix before (NaN before two) and the time between them. */
    public double x(){return px;}
    public double y(){return py;}
    public double prevX(){return qx;}
    public double prevY(){return qy;}
    public long gapMs(){return pt-qt;}
    /** The line: its point and crossing direction (unit). */
    public double lineX(){return ax;}
    public double lineY(){return ay;}
    public double dirX(){return hx;}
    public double dirY(){return hy;}
}
