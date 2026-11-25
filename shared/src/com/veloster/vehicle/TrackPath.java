package com.veloster.vehicle;

/**
 * A closed track as a path: distance along it (metres from its first point) for a position, and the position at a
 * distance. CircuitCluster moves the car's dot along the track between 1 Hz fixes with it. No Android: host-tested.
 */
public final class TrackPath {
    private final float[] p;
    private final double[] at;
    public final double length;

    /** Closed track, x then y in metres (the last point joins the first). */
    public TrackPath(float[] xy){
        p=xy;int n=xy.length/2;at=new double[n+1];
        for(int i=0;i<n;i++){int j=(i+1)%n;at[i+1]=at[i]+Math.hypot(xy[j*2]-xy[i*2],xy[j*2+1]-xy[i*2+1]);}
        length=at[n];
    }
    /** Distance along the track of the point on it nearest (x, y). */
    public double along(double x,double y){
        int n=p.length/2;double best=Double.MAX_VALUE,s=0;
        for(int i=0;i<n;i++){int j=(i+1)%n;
            double ax=p[i*2],ay=p[i*2+1],dx=p[j*2]-ax,dy=p[j*2+1]-ay,l=dx*dx+dy*dy;
            double t=l==0?0:Math.max(0,Math.min(1,((x-ax)*dx+(y-ay)*dy)/l));
            double d=Math.hypot(ax+t*dx-x,ay+t*dy-y);
            if(d<best){best=d;s=at[i]+t*(at[i+1]-at[i]);}
        }
        return s;
    }
    /** Signed distance from s0 to s1 the short way round (the car never covers half a lap between two fixes). */
    public double delta(double s0,double s1){double d=(s1-s0)%length;if(d>length/2)d-=length;else if(d<-length/2)d+=length;return d;}
    /** The point at distance s (any value: it wraps), x then y. */
    public double[] at(double s){
        int n=p.length/2;s%=length;if(s<0)s+=length;
        int i=0;while(i<n-1&&at[i+1]<s)i++;
        int j=(i+1)%n;double seg=at[i+1]-at[i],f=seg==0?0:(s-at[i])/seg;
        return new double[]{p[i*2]+f*(p[j*2]-p[i*2]),p[i*2+1]+f*(p[j*2+1]-p[i*2+1])};
    }
}
