import com.veloster.vehicle.CircuitLap;
import com.veloster.vehicle.TrackPath;

/** GPS laps on a synthetic 2 km circle at 1 Hz: lap time, direction, the minimum lap, stray crossings, the live gap. */
public final class CircuitLapTest {
    private static int checks;
    private static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
    static final double LAT=38.08,LON=128.36,R=318.31,KX=111320*Math.cos(Math.toRadians(LAT));   // circumference about 2,000 m
    /** The circle as a track, its first point at the origin heading north (anticlockwise). */
    private static TrackPath circle(){
        float[] p=new float[400];
        for(int i=0;i<200;i++){double a=2*Math.PI*i/200;p[i*2]=(float)(R*(1-Math.cos(a)));p[i*2+1]=(float)(R*Math.sin(a));}
        return new TrackPath(p);
    }
    /** The circle with its direction unknown (the first crossing decides), or known to be anticlockwise. */
    private static CircuitLap onCircle(){CircuitLap c=new CircuitLap();c.track(LAT,LON,circle(),false);return c;}
    private static CircuitLap anticlockwise(){CircuitLap c=new CircuitLap();c.track(LAT,LON,circle(),true);return c;}
    /** A fix s metres round the circle (negative: before the line), anticlockwise or not. */
    private static void at(CircuitLap c,double s,long t,boolean ccw){
        double a=(ccw?1:-1)*s/R;
        c.fix(LAT+R*Math.sin(a)/110540,LON+R*(1-Math.cos(a))/KX,t);
    }
    public static void main(String[] args){
        double v=2000/80.0;   // 80 s laps (the polygon is a touch shorter than the circle)
        CircuitLap c=onCircle();
        check(c.state()==CircuitLap.WAIT,"a known track waits for the first crossing");
        long t=5000;double s=-10*v;
        for(int i=0;i<=420;i++){at(c,s,t,true);t+=1000;s+=v;}
        check(c.state()==CircuitLap.TIMING,"timing after the first crossing");
        check(c.laps==5,"five laps in 410 s at 80 s a lap: "+c.laps);
        check(Math.abs(c.last-80000)<=60&&Math.abs(c.best-80000)<=60,"lap time interpolated between 1 Hz fixes: "+c.last);
        check(Math.abs(c.gap)<=300,"the same pace as the best lap: no gap: "+c.gap);

        CircuitLap cw=onCircle();t=5000;s=-10*v;   // clockwise: the line was guessed the other way
        for(int i=0;i<=200;i++){at(cw,s,t,false);t+=1000;s+=v;}
        check(cw.laps==2&&Math.abs(cw.last-80000)<=60,"laps either way round, the direction from the first crossing: "+cw.laps+" "+cw.last);

        CircuitLap faster=onCircle();t=5000;s=-10*v;
        for(int i=0;i<90;i++){at(faster,s,t,true);t+=1000;s+=v;}   // lap 1 at 80 s, just over the line
        double v2=v*1.05;
        for(int i=0;i<40;i++){at(faster,s,t,true);t+=1000;s+=v2;}   // 5 % quicker: about 1.9 s up halfway round
        check(faster.laps==1&&faster.gap<-1500&&faster.gap>-2300,"the live gap shows time gained on the best lap: "+faster.gap);

        CircuitLap back=onCircle();t=5000;s=-10*v;
        for(int i=0;i<20;i++){at(back,s,t,true);t+=1000;s+=v;}
        for(int i=0;i<40;i++){at(back,s,t,true);t+=1000;s-=v;}   // turn round and drive back over the line
        check(back.laps==0,"crossing the wrong way is no lap");

        CircuitLap jitter=onCircle();t=5000;s=-2*v;
        for(int i=0;i<5;i++){at(jitter,s,t,true);t+=1000;s+=v;}
        for(int i=0;i<5;i++){at(jitter,s,t,true);t+=1000;s-=v;}
        for(int i=0;i<5;i++){at(jitter,s,t,true);t+=1000;s+=v;}   // back over the line and forward again within 20 s
        check(jitter.laps==0,"no lap shorter than the minimum");

        CircuitLap off=onCircle();t=0;at(off,-v,t,true);at(off,v,t+=1000,true);   // lap 1 starts
        off.fix(LAT-0.0005,LON-220/KX,t+=30000);off.fix(LAT+0.0005,LON-220/KX,t+=5000);   // cross the line's extension 220 m away
        check(off.laps==0,"crossing far from the line (another straight, the paddock) is no lap");

        CircuitLap none=new CircuitLap();none.fix(LAT,LON,0);none.fix(LAT+0.001,LON,1000);
        check(none.state()==CircuitLap.NONE&&Math.abs(none.y()-110.54)<0.1,"without a track it only keeps the position");
        CircuitLap wrong=anticlockwise();t=5000;s=-10*v;   // known anticlockwise, driven clockwise (backwards on a service road)
        for(int i=0;i<=200;i++){at(wrong,s,t,false);t+=1000;s+=v;}
        check(wrong.state()==CircuitLap.WAIT&&wrong.laps==0,"a known direction ignores crossings the other way");
        CircuitLap right=anticlockwise();t=5000;s=-5*v;
        for(int i=0;i<3;i++){at(right,s,t,true);t+=1000;s-=v;}   // first backwards over the line (out of the pits the wrong way)
        for(int i=0;i<=200;i++){at(right,s,t,true);t+=1000;s+=v;}   // then the right way round
        check(right.laps==2&&Math.abs(right.last-80000)<=60,"and still times laps the right way after a wrong-way pass: "+right.laps+" "+right.last);
        CircuitLap parked=onCircle();t=5000;   // parked on the line, the fix wandering 2 m either side
        for(int i=0;i<30;i++){at(parked,i%2==0?-2:2,t,true);t+=1000;}
        check(parked.state()==CircuitLap.WAIT,"GPS wander at the line (under walking pace) starts nothing");
        System.out.println("PASS "+checks+" circuit lap checks");
    }
}
