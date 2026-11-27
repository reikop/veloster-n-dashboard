import com.veloster.vehicle.EngineSynth;

/** Virtual engine sound: silent when off, follows the firing order, louder with load, every engine and exhaust
 *  bounded, overrun burble that the pop rate scales, and latency compensation that cuts the lag without overshooting
 *  a gear change. */
public final class EngineSynthTest {
    private static int checks;
    private static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
    /** Renders seconds of steady input in 512-sample blocks like the audio thread; returns the last second. */
    private static float[] run(EngineSynth s,double rpm,double pedal,double boost,double seconds){
        int n=(int)(seconds*EngineSynth.RATE),tail=EngineSynth.RATE;float[] all=new float[n];
        for(int i=0;i<n;i+=512){s.set(rpm,pedal,boost);s.render(all,i,Math.min(512,n-i),1);}
        float[] last=new float[tail];System.arraycopy(all,n-tail,last,0,tail);return last;
    }
    private static double rms(float[] x){double a=0;for(float v:x)a+=v*v;return Math.sqrt(a/x.length);}
    private static double peak(float[] x){double a=0;for(float v:x)a=Math.max(a,Math.abs(v));return a;}
    /** Goertzel power at hz. */
    private static double power(float[] x,double hz){
        double w=2*Math.PI*hz/EngineSynth.RATE,c=2*Math.cos(w),s1=0,s2=0;
        for(float v:x){double s0=v+c*s1-s2;s2=s1;s1=s0;}
        return s1*s1+s2*s2-c*s1*s2;
    }
    /** Energy added by overrun pops over a 2.5 s lift from 4000 rpm: the voice minus the same voice with pops off. */
    static int lastFlames;
    private static double burble(double popRate){
        EngineSynth a=new EngineSynth(),b=new EngineSynth();a.popRate=popRate;b.popRate=0;
        float[] x=new float[512],y=new float[512];double e=0;
        for(int i=0;i<60;i++){a.set(4500,1,1);b.set(4500,1,1);a.render(x,0,512,1);b.render(y,0,512,1);}
        for(int i=0;i<108;i++){a.set(4000-i*15,0,-.5);b.set(4000-i*15,0,-.5);a.render(x,0,512,1);b.render(y,0,512,1);for(int k=0;k<512;k++){double d=x[k]-y[k];e+=d*d;}}
        lastFlames=a.flames;return e;
    }
    /** Mean rpm lag (rpm) while accelerating 2000 rpm/s with CAN frames every 50 ms arriving delayMs late, and the
     *  furthest the sound drops below the engine after an upshift. */
    private static double[] lag(double lead,int delayMs){
        EngineSynth s=new EngineSynth();s.lead=lead;float[] x=new float[512];double sum=0,low=0;int n=0;
        for(int i=0;i<(int)(3.5*EngineSynth.RATE/512);i++){
            double t=i*512.0/EngineSynth.RATE,ct=Math.floor((t-delayMs/1000.0)/.05)*.05;
            s.set(ct<0?1500:truth(ct),.8,.5);s.render(x,0,512,1);
            if(t>1&&t<1.9){sum+=truth(t)-s.rpmForTest();n++;}
            if(t>2.3&&t<3)low=Math.min(low,s.rpmForTest()-truth(t));
        }
        return new double[]{sum/n,low};
    }
    /** Energy in the 0.25 s after a pull to 6500 rpm falls to 4500 at the given pedal, either in one CAN step (an upshift) or
     *  over a second. */
    private static double shift(int engine,double pedal,boolean step,double pops){
        EngineSynth s=new EngineSynth(EngineSynth.RATE,EngineSynth.voice(engine,0));s.popRate=pops;float[] x=new float[512];double e=0;
        for(int i=0;i<40;i++){s.set(5700+i*20,pedal,.8);s.render(x,0,512,1);}   // pulling to 6500 rpm, a new CAN value every block
        for(int i=0;i<6;i++){s.set(step?4500+i*10:6500-i*40,pedal,.8);s.render(x,0,512,1);for(float v:x)e+=v*v;}
        return e;
    }
    /** Energy over a 1.5 s lift from 4500 rpm with the given pop rate. */
    private static double overrun(int engine,double pops){
        EngineSynth s=new EngineSynth(EngineSynth.RATE,EngineSynth.voice(engine,0));s.popRate=pops;s.bovVol=0;float[] x=new float[512];double e=0;
        for(int i=0;i<40;i++){s.set(4500,1,0);s.render(x,0,512,1);}
        for(int i=0;i<32;i++){s.set(4500-i*40,0,-.5);s.render(x,0,512,1);for(float v:x)e+=v*v;}
        return e;
    }
    /** Energy over 0.5 s after lifting off 1.2 bar of boost at 4500 rpm, with the blow-off trim bov. */
    private static double lift(int engine,double bov){
        EngineSynth s=new EngineSynth(EngineSynth.RATE,EngineSynth.voice(engine,0));s.bovVol=bov;s.popRate=0;float[] x=new float[512];double e=0;
        for(int i=0;i<40;i++){s.set(4500,1,1.2);s.render(x,0,512,1);}
        for(int i=0;i<11;i++){s.set(4400,0,.2);s.render(x,0,512,1);for(float v:x)e+=v*v;}
        return e;
    }
    private static double truth(double t){return t<.5?1500:t<2?1500+(t-.5)*2000:3000+Math.max(0,t-2.15)*1500;}

    public static void main(String[] args){
        EngineSynth s=new EngineSynth();
        float[] off=run(s,Double.NaN,0,0,1);
        check(peak(off)==0,"silent before the engine runs");
        float[] idle=run(s,750,0,0,2);
        check(rms(idle)>.01,"audible at idle: "+rms(idle));
        float[] coast=run(new EngineSynth(),3000,0,0,2),full=run(new EngineSynth(),3000,1,0,2);
        check(rms(full)>rms(coast)*1.3,"louder under load: "+rms(coast)+" -> "+rms(full));
        double firing=power(full,100);   // order 2 at 3000 rpm
        check(firing>power(full,87)*4&&firing>power(full,113)*4,"firing order stands out");
        check(power(full,75)>power(full,81)*2,"half order 1.5 present under load");
        float[] boosted=run(new EngineSynth(),4000,1,1.2,2);
        check(power(boosted,4600)>power(full,4600)*10,"turbo whistle with boost");
        EngineSynth quiet=new EngineSynth();quiet.turboVol=0;
        check(power(run(quiet,4000,1,1.2,2),4600)<power(boosted,4600)/10,"turbo whistle trim 0 silences it");
        // 엔진음 0: the turbo whistle alone, the engine silent (its firing order at 4000 rpm, order 2 = 133 Hz).
        EngineSynth turboOnly=new EngineSynth();turboOnly.engineVol=0;float[] to=run(turboOnly,4000,1,1.2,2);
        check(power(to,4600)>power(boosted,4600)/2&&power(to,133)<power(boosted,133)/100,"엔진음 0 keeps the whistle, drops the engine");
        check(rms(run(turboOnly,3000,1,0,2))<1e-3,"엔진음 0 and no boost: silent");
        for(float[] x:new float[][]{idle,coast,full,boosted})check(peak(x)<=1,"bounded");
        // Every engine through every exhaust plays, bounded.
        for(int e=0;e<EngineSynth.engines();e++){
            for(int x=0;x<EngineSynth.exhausts();x++){
                float[] v=run(new EngineSynth(EngineSynth.RATE,EngineSynth.voice(e,x)),5000,1,1,2);
                check(rms(v)>.1&&peak(v)<=1,"engine "+e+" exhaust "+x+": "+rms(v));
            }
        }
        // V8 fires four times a revolution: order 4 at 3000 rpm is 200 Hz.
        float[] v8=run(new EngineSynth(EngineSynth.RATE,EngineSynth.voice(3,0)),3000,1,0,2);
        check(power(v8,200)>power(v8,180)*4,"V8 firing order");
        // Overrun burble follows the pop rate.
        double b0=burble(0);int f0=lastFlames;double b1=burble(1);int f1=lastFlames;double b2=burble(2);int f2=lastFlames;
        check(f0==0&&f1>=1&&f1<=3&&f2<=3,"flames: a few big pops per lift, none with pops off: "+f0+" / "+f1+" / "+f2);
        check(b0==0&&b1>0&&b2>b1*1.3,"burble scales with the pop rate: "+b1+" -> "+b2);
        // Latency compensation: with 50 ms of CAN delay, 80 ms of lead takes most of the lag away and the upshift
        // is held rather than extrapolated.
        double[] plain=lag(0,50),comp=lag(.08,50);
        check(plain[0]>200&&Math.abs(comp[0])<80,"lag "+plain[0]+" rpm -> "+comp[0]+" rpm");
        check(comp[1]>-150,"no dive after the upshift: "+comp[1]);
        EngineSynth b=new EngineSynth();run(b,4000,1,1,1);
        float[] stop=run(b,Double.NaN,0,0,2);
        check(peak(stop)<.001,"fades out when the engine stops: "+peak(stop));
        // The fours added 2026-11-27: each one's character shows in the sound.
        // Flat four: the paired pulses put the off-beat order 1 (3000 rpm: 50 Hz) far up against an even four.
        float[] boxer=run(new EngineSynth(EngineSynth.RATE,EngineSynth.voice(7,0)),3000,1,0,2),even=run(new EngineSynth(EngineSynth.RATE,EngineSynth.voice(0,0)),3000,1,0,2);
        check(power(boxer,50)/power(boxer,100)>3*power(even,50)/power(even,100),"flat four rumbles on order 1");
        // Cam changeover: order 6 (36 x rpm/360) rises across 5600 rpm far more than the light NA four's does.
        double camUp=power(run(new EngineSynth(EngineSynth.RATE,EngineSynth.voice(6,0)),6200,1,0,2),620)/power(run(new EngineSynth(EngineSynth.RATE,EngineSynth.voice(6,0)),5000,1,0,2),500);
        double plainUp=power(run(new EngineSynth(EngineSynth.RATE,EngineSynth.voice(9,0)),6200,1,0,2),620)/power(run(new EngineSynth(EngineSynth.RATE,EngineSynth.voice(9,0)),5000,1,0,2),500);
        check(camUp>plainUp*2,"cam changeover brightens the top end: "+camUp+" vs "+plainUp);
        // Brap: a flat-out upshift (6500 -> 4500 rpm in one CAN step) cracks (the 팝 trim at 0 takes it away); a slow
        // fall does not, nor does the stock four.
        check(shift(5,1,true,1)>shift(5,1,true,0)*1.25,"hot hatch brap on a flat-out upshift: "+shift(5,1,true,1)+" vs "+shift(5,1,true,0));
        check(shift(5,1,false,1)==shift(5,1,false,0),"no brap on a slow fall");
        check(shift(0,1,true,1)==shift(0,1,true,0),"no brap from the stock four");
        // Flutter: lifting off boost with no valve chops the air; the 블로오프 trim silences it.
        // (softened 2026-11-27: a dark rush rather than gated hiss, so a little less energy than the first take's 1.38x)
        check(lift(7,1)>lift(7,0)*1.2,"flat four flutters on a lift: "+lift(7,1)+" vs "+lift(7,0));
        // Diesel: a click on every firing (energy above 2 kHz at idle) and no overrun pops.
        float[] dz=run(new EngineSynth(EngineSynth.RATE,EngineSynth.voice(10,0)),850,0,0,2),pz=run(new EngineSynth(EngineSynth.RATE,EngineSynth.voice(0,0)),850,0,0,2);
        check(power(dz,2600)/rms(dz)>3*power(pz,2600)/rms(pz),"diesel clatter");
        check(overrun(10,1)==overrun(10,0)&&overrun(5,1)>overrun(5,0),"diesel never pops (the hot hatch does)");
        System.out.println("PASS "+checks+" engine synth checks; lag "+Math.round(plain[0])+" -> "+Math.round(comp[0])+" rpm");
    }
}
