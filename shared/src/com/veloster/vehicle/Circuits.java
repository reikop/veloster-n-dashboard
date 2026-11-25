package com.veloster.vehicle;

/**
 * The circuits in CircuitData: which one the car is at, and each track in metres for the map. Where two layouts
 * share the ground (KIC's full and permanent circuits) the one the car is on wins: it changes only when the car is
 * more than 30 m off the current track and within 15 m of the other. No Android: host-tested.
 */
public final class Circuits {
    private Circuits(){}
    static final double MARGIN_M=300,OFF_M=30,ON_M=15;
    /** Track points in 1e-5 degrees (latitude, longitude pairs), first point on the start / finish line. */
    private static int[][] pts;
    private static int[][] box;

    public static int count(){return CircuitData.TRACKS.length;}
    public static String id(int i){return CircuitData.TRACKS[i][0];}
    public static String name(int i){return CircuitData.TRACKS[i][1];}
    public static int lengthM(int i){return Integer.parseInt(CircuitData.TRACKS[i][3]);}
    /** The start / finish line is an estimate (no official map or OSM finish node behind it). */
    public static boolean lineEstimated(int i){return CircuitData.TRACKS[i][5].equals("est");}
    /** The track's points run in the driving direction (otherwise the first crossing decides). */
    public static boolean directionKnown(int i){return CircuitData.TRACKS[i][6].equals("1");}
    private static synchronized void load(){
        if(pts!=null)return;
        int n=count();int[][] p=new int[n][],b=new int[n][];
        for(int i=0;i<n;i++){
            p[i]=TaxiZones.polyline(CircuitData.TRACKS[i][4]);
            int[] bb={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};
            for(int k=0;k<p[i].length;k+=2){bb[0]=Math.min(bb[0],p[i][k]);bb[1]=Math.min(bb[1],p[i][k+1]);bb[2]=Math.max(bb[2],p[i][k]);bb[3]=Math.max(bb[3],p[i][k+1]);}
            b[i]=bb;
        }
        box=b;pts=p;
    }
    /** Track point k of circuit i (latitude, longitude), and how many there are: the preview and tests drive along them. */
    public static int points(int i){load();return pts[i].length/2;}
    public static double[] point(int i,int k){load();return new double[]{pts[i][k*2]/1e5,pts[i][k*2+1]/1e5};}
    /** Start / finish line: latitude, longitude. */
    public static double[] start(int i){load();return new double[]{pts[i][0]/1e5,pts[i][1]/1e5};}
    /** The track in metres east / north of (lat0, lon0), x then y, closed (the last point joins the first). */
    public static float[] outline(int i,double lat0,double lon0){
        load();int[] p=pts[i];float[] o=new float[p.length];double kx=111320*Math.cos(Math.toRadians(lat0));
        for(int k=0;k<p.length;k+=2){o[k]=(float)((p[k+1]/1e5-lon0)*kx);o[k+1]=(float)((p[k]/1e5-lat0)*110540);}
        return o;
    }
    private static boolean near(int i,double lat,double lon){
        double m=MARGIN_M/110540*1e5,mx=MARGIN_M/(111320*Math.cos(Math.toRadians(lat)))*1e5,y=lat*1e5,x=lon*1e5;int[] b=box[i];
        return y>=b[0]-m&&y<=b[2]+m&&x>=b[1]-mx&&x<=b[3]+mx;
    }
    /** Metres from the position to circuit i's track (no allocation: it runs for every circuit at every fix). */
    public static double distance(int i,double lat,double lon){
        load();int[] p=pts[i];double best=Double.MAX_VALUE,kx=111320*Math.cos(Math.toRadians(lat));int n=p.length;
        for(int k=0;k<n;k+=2){
            double ax=(p[k+1]/1e5-lon)*kx,ay=(p[k]/1e5-lat)*110540,bx=(p[(k+3)%n]/1e5-lon)*kx,by=(p[(k+2)%n]/1e5-lat)*110540,dx=bx-ax,dy=by-ay,l=dx*dx+dy*dy;
            double t=l==0?0:Math.max(0,Math.min(1,-(ax*dx+ay*dy)/l));
            best=Math.min(best,Math.hypot(ax+t*dx,ay+t*dy));
        }
        return best;
    }
    /** The circuit at this position, keeping cur while the car is on it (-1: none). */
    public static int pick(double lat,double lon,int cur){
        load();int best=-1;double bd=Double.MAX_VALUE;
        for(int i=0;i<count();i++)if(near(i,lat,lon)){double d=distance(i,lat,lon);if(d<bd){bd=d;best=i;}}
        if(best<0||best==cur)return best;
        if(cur>=0&&near(cur,lat,lon)&&!(distance(cur,lat,lon)>OFF_M&&bd<ON_M))return cur;
        return best;
    }
}
