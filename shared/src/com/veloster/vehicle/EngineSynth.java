package com.veloster.vehicle;

/**
 * Virtual engine sound from received rpm, pedal and boost only (no recordings, no Android). The same algorithm and
 * tables run in the listening page, sample for sample. A voice is an engine (cylinders, their unevenness, turbo)
 * played through an exhaust (resonances, brightness, overrun pops).
 *
 * Every firing plays the exhaust's answer to one blow-down pulse, a 48 ms wavelet built once per voice; the firings
 * overlap as the rpm rises, and the orders, the half orders and the way they fade into noise come out of the pulses
 * as in an engine (the way engine simulators run exhaust flow through the pipe's impulse response). The first version
 * added sixteen sine orders instead: next to a recording of this engine it had one order and little above 800 Hz
 * (an organ), which the alarm channel of the car, with no bass, thinned further.
 * - Wavelet: d/dt of a Friedlander blow-down flow pulse (what a tailpipe radiates), through the exhaust's formants
 *   and two fixed block / manifold bands, an all-pass chain (the pipe's dispersion) and reflections every cylinder
 *   shares. 4x oversampled: a firing lands within 1/8 sample.
 * - Above OWN_HP the pulse is spread over the firing interval (spread()): a petrol engine's mids and highs are near
 *   steady noise, and the click each firing used to keep there, heard one by one below 3000 rpm, is how a diesel sounds.
 * - Load blends a soft pulse (closed throttle: long, round, quiet) into a hard one (short, steep, loud); boost adds
 *   to the load the pedal gives.
 * - Every firing differs a little in strength and timing, and the crank speed wanders (Jagla et al.).
 * - Flow noise breathes with the firings; an intake pulse sits between them and follows the pedal.
 * - Overrun burble: on a lift, a random, firing-locked subset of exhaust strokes fires a thud: a Friedlander blast
 *   wave rung through the exhaust gas column's low resonance and a broad band at 3x, with a low-passed crackle tail.
 * - Turbo whistle with boost (a tone whose level wanders with low-passed noise); blow-off as loud as the boost it dumps.
 * - Latency compensation: rpm predicted ahead by linear extrapolation of the last two CAN samples (US 8270628).
 * Mono, -1..1 floats.
 */
public final class EngineSynth {
    public static final int RATE=11025;   // 11.025 kHz: engine content is under ~5 kHz, and the head unit cannot afford 22 kHz of synthesis
    static final double PUNCH=3;   // weight of the 3x band next to the thump (burble about 80 % below 150 Hz)
    // The wavelet design (the listening page's V2 table; keep the two in step).
    static final int OS=4,RING=1024;      // table oversampling; the mix ring: one wavelet (529 samples) plus the latest start (9 deg at 300 rpm: 55)
    static final double WAVE=.048,        // seconds of exhaust ringing kept per firing
        FB=1.6,                            // blow-down decay shape (Friedlander b)
        HARD_T=.0022,HARD_RISE=.00018,     // full load: short, steep pulse (bright)
        SOFT_T=.0042,SOFT_RISE=.0008,      // closed throttle: longer, rounder pulse (dull)
        SOFT_LEVEL=.7,HARD_EXP=.7,         // soft pulse energy next to the hard one; hardness = load ^ HARD_EXP
        LOW=.3,                            // firing strength at zero load (of full load): the listener's 저부하 음량
        GAMMA=.15,                         // loudness trim against rpm (0 = +6 dB per rpm doubling, the raw pulse rate)
        JIT_DEG=1.5,FLUTTER=.1,            // firing timing scatter in crank degrees; slow crank speed wander (about 0.25 % rms near 6 Hz)
        LOAD0=.6,                          // share of the load the pedal gives before boost arrives
        TILT=1500,ALLPASS_Q=1.2,SHARED_LP=900,OWN_HP=350,
        INTAKE_T=.0045,INTAKE_DRY=.2,INTAKE_GAIN=.5,
        NOISE_BASE=.25,NOISE_DEPTH=1.6,NOISE_TAU=.003,NOISE_GAIN=.1,OUT=4.6;
    static final double[][] RIDGES={{820,3,.28},{1900,2.2,.3}},   // block and manifold bands that stay put while the orders sweep through
        INTAKE_FORMANTS={{340,3,1},{700,3,.6},{1500,2.5,.35}},NOISE_BANDS={{850,.8,1},{2000,1.1,.5}};
    static final double[] ALLPASS={180,420,760,1200,1900,2800},
        SHARED={6,.0025,.026,.5,.014},INTAKE_TAPS={4,.0005,.006,.5,.004};   // reflections: count, first and last delay (s), gain, decay (s)
    // The spread of a firing above OWN_HP. Measured in 0.3 s windows of 500-1000 / 1000-2000 / 2000-4000 Hz, kurtosis
    // (steady noise = 3): recordings of this engine family 3.4 / 3.9 / 5.2; seven sparse reflections and the whole click
    // gave 8 / 7 / 9 at 1500 rpm and 16 / 13 / 13 at idle (a diesel's clatter); this gives 3 / 5 / 3 and 4 / 9 / 4.
    static final int OWN_N=64;             // reflections, evenly over OWN_D0..OWN_D1 s
    static final double OWN_D0=.0003,OWN_D1=.038,OWN_GAIN=.5,OWN_TAU=.015,OWN_RISE=.001,   // they swell in OWN_RISE and die away over OWN_TAU
        OWN_DIRECT=.2,                     // what is left of the click itself at 울림 100 % (울림 0 = all of it, the dry sound)
        OWN_VARY=.85,                      // how far a cylinder's reflections differ from the shared ones: about a fifth of the mids on half orders, as before
        OWN_SOFT=.7;                       // the soft pulse is spread less: with this the balance of lows to highs, on and off the pedal, stays where it was (within 1.5 dB)

    /**
     * Fast tanh-like soft clip, |out| < 1. The audio thread runs this twice per sample; on this head unit Math.tanh
     * was a top cost. Padé approximation, clamped to +/-1 at |x| >= 3 (where the approximation already reaches 1).
     */
    static double softclip(double x){
        if(x<=-3)return -1;if(x>=3)return 1;double x2=x*x;return x*(27+x2)/(27+9*x2);
    }

    static final class Engine {
        final double[] cyl;final double jitter,fScale,popMul,popRpm,flow,whistle,blowoff;
        Engine(double[] cyl,double jitter,double fScale,double popMul,double popRpm,double flow,double whistle,double blowoff){
            this.cyl=cyl;this.jitter=jitter;this.fScale=fScale;this.popMul=popMul;this.popRpm=popRpm;this.flow=flow;this.whistle=whistle;this.blowoff=blowoff;}
    }
    static final class Exhaust {
        final double[][] formants;final double dry,low,high,pulseMul,flowMul,drive,popQ,popWindow,popBodyHz,popT0,popBig,popGain;
        Exhaust(double[][] formants,double dry,double low,double high,double pulseMul,double flowMul,double drive,
                double popQ,double popWindow,double popBodyHz,double popT0,double popBig,double popGain){
            this.formants=formants;this.dry=dry;this.low=low;this.high=high;this.pulseMul=pulseMul;this.flowMul=flowMul;
            this.drive=drive;this.popQ=popQ;this.popWindow=popWindow;this.popBodyHz=popBodyHz;this.popT0=popT0;this.popBig=popBig;this.popGain=popGain;}
    }
    /** An engine through an exhaust: what render() plays. */
    public static final class Voice {
        final double[] cyl;final double[][] formants;final int nc,seed;
        final double jitter,fScale,dry,high,pulseMul,flow,drive,whistle,blowoff,popGain,popRpm,popChance,popWindow,popBodyHz,popT0,popBig;
        Voice(Engine e,Exhaust x,String id){
            nc=e.cyl.length;cyl=new double[nc];for(int c=0;c<nc;c++)cyl[c]=1+(e.cyl[c]-1)*x.low;   // a deeper exhaust makes the cylinders more uneven (the rumble)
            jitter=e.jitter;fScale=e.fScale;
            formants=new double[x.formants.length][];
            for(int f=0;f<formants.length;f++)formants[f]=new double[]{x.formants[f][0]*e.fScale,x.formants[f][1],x.formants[f][2]};
            dry=x.dry;high=x.high;pulseMul=x.pulseMul;flow=e.flow*x.flowMul;drive=x.drive;whistle=e.whistle;blowoff=e.blowoff;
            int h=0x2545F491;for(int i=0;i<id.length();i++){h^=id.charAt(i);h*=16777619;}seed=h|1;   // each voice its own reflections
            popGain=x.popGain;popRpm=e.popRpm;popChance=1-2*x.popQ*e.popMul;popWindow=x.popWindow;
            popBodyHz=x.popBodyHz*Math.sqrt(e.fScale);popT0=x.popT0/Math.sqrt(e.fScale);popBig=x.popBig;
        }
        /** False for engines without a turbo (no whistle, no blow-off). */
        public boolean turbo(){return whistle>0;}
    }
    /** engine 0..engines()-1 (0 = this car's 1.6 turbo four), exhaust 0..exhausts()-1 (0 = stock); out of range = 0. */
    public static Voice voice(int engine,int exhaust){
        int e=engine>=0&&engine<ENGINES.length?engine:0,x=exhaust>=0&&exhaust<EXHAUSTS.length?exhaust:0;
        return new Voice(ENGINES[e],EXHAUSTS[x],ENGINE_IDS[e]+"/"+EXHAUST_IDS[x]);
    }
    public static int engines(){return ENGINES.length;}
    public static int exhausts(){return EXHAUSTS.length;}

    // The listening page's tables; keep the two in step. Engine: cylinder strengths, strength jitter, resonance scale,
    // pop rate and floor rpm, flow noise, turbo whistle, blow-off.
    static final String[] ENGINE_IDS={"i4t","i6","v6","v8","v8flat"};
    static final Engine[] ENGINES={
        new Engine(new double[]{1,0.86,0.95,0.8},0.12,1,1,2200,0.04,0.014,0.05),
        new Engine(new double[]{1,0.97,0.99,0.96,0.98,0.95},0.06,1.3,0.7,2400,0.04,0.012,0.05),
        new Engine(new double[]{1,0.93,0.97,0.9,0.99,0.92},0.09,1.05,0.8,2300,0.045,0.016,0.08),
        new Engine(new double[]{1,0.7,0.95,0.74,0.9,0.66,1,0.8},0.15,0.7,1,2200,0.04,0,0),
        new Engine(new double[]{1,0.95,0.98,0.93,1,0.96,0.97,0.94},0.07,1.5,0.6,2600,0.04,0,0)};
    // Exhaust: formants {Hz, Q, mix}, dry, cylinder unevenness (low), brightness (high), hard pulse and flow noise
    // multipliers, drive into the soft clip, then the pops.
    static final String[] EXHAUST_IDS={"stock","sport","resonator","straight"};
    static final Exhaust[] EXHAUSTS={
        new Exhaust(new double[][]{{105,1.1,1},{420,2.2,0.55},{1150,3,0.18}},0.35,1,1,1,1,0.8,0.15,2.5,60,0.01,0.08,1.3),
        new Exhaust(new double[][]{{78,1,1.15},{300,1.8,0.5},{900,2.5,0.1}},0.42,1.7,0.75,1.1,0.9,0.78,0.19,2.5,52,0.012,0.1,1.5),
        new Exhaust(new double[][]{{95,1.2,0.9},{430,1.6,0.7},{1500,2,0.35},{82,7,0.7}},0.38,1.15,1.35,1.2,1.75,0.82,0.19,3,58,0.011,0.12,1.56),
        new Exhaust(new double[][]{{88,1,1.1},{380,1.5,0.7},{1300,2.2,0.3}},0.4,1.5,1.25,1.3,1.75,0.88,0.275,3.5,50,0.012,0.25,1.75)};

    private final double rate;
    private int seed=0x2545F491;
    private double rpm,pedal,boost,targetRpm,targetPedal,targetBoost,master;
    private double phase,whistlePhase,wobblePhase;   // phase = half-order phase: 2*pi per 720 degrees of crank
    private int firing=-1,firingI=-1;
    private double puff,blowoff,liftTimer,lastPedal,peakBoost;
    /** The pedal gates the rpm lead: letting it go ends the trend the lead extrapolates (a lift, then often an upshift), and
     *  the sound used to keep climbing past the engine. pedTop follows the pedal up at once and sinks LEAD_SINK a second; a
     *  pedal LEAD_DROP under it holds the lead for LEAD_HOLD s. Scored on five recorded drives (two cars), 80 ms lead: rpm
     *  error in the 0.5 s after a lift 65 -> 55 rpm rms (our car 92 -> 65), elsewhere unchanged. Raising the rpm early on
     *  a pedal press was tried too: it helped the diesel and made our car worse, so a press only changes the load. */
    static final double LEAD_DROP=.15,LEAD_SINK=.4,LEAD_HOLD=.4;
    private double pedTop,leadHold;
    private double hp,hpIn,blowLp,blowBp,whLp,flut;
    private int popT=-1;
    private double popA,popT0,popB,popTau,crLp;
    private final double[] popSt=new double[4],punchSt=new double[4];
    private final double[][] nst=new double[2][4];
    private double[][] nbq;
    private double[] popBq,punchBq;
    private boolean running;
    private Voice p;
    // The wavelets of the voice (per cylinder: soft and hard pulse; one intake pulse) and the firings ringing now: each
    // is added whole into the ring when it fires (its strength and mix are fixed then), so a sample is one read instead
    // of a walk over every firing still ringing (38 of them for a V8 at 6000 rpm, slow on the head unit's Dalvik).
    private int len;private double even;
    private float[][] tabS,tabH;private float[] tabI;
    private final double[] mix=new double[RING];private int tick;
    /** Listener tuning (tune()): brightness, flow noise, pipe reflections, intake, firing scatter (1 = as designed), off-load strength. */
    private double tone=1,rough=1,body=1,intakeVol=1,uneven=1,low=LOW;
    /** Listener trims on the voice's own values (1 = as designed). */
    public volatile double popRate=1,turboVol=1,bovVol=1;
    /** Big pops that earn a flame on screen (PixelCluster): only early in a lift and at most one per FLAME_GAP s. */
    public volatile int flames;
    /** elapsedRealtime when the latest of those pops leaves the speaker (set by EngineSound); PixelCluster shows it.
     *  Here, not in EngineSound, so the screens build without android.media (preview.sh). */
    static volatile long flameAt=Long.MIN_VALUE/2;
    /** True while EngineSound plays this synth audibly: its pops drive the flames, so PixelCluster skips its own. */
    static volatile boolean voiced;
    static final double FLAME_GAP=.5;
    private double flameHold;
    /** Latency compensation in seconds (0 = off). */
    public volatile double lead;
    private double lastRaw=Double.NaN,slope,interval;
    private double sinceNew;

    public EngineSynth(){this(RATE,voice(0,0));}
    public EngineSynth(double rate,Voice v){this.rate=rate;interval=rate*.05;use(v);}

    private static double[] bandPass(double hz,double q,double r){   // RBJ band-pass, 0 dB peak
        double w=2*Math.PI*hz/r,alpha=Math.sin(w)/(2*q),a0=1+alpha;
        return new double[]{alpha/a0,0,-alpha/a0,-2*Math.cos(w)/a0,(1-alpha)/a0};
    }
    private int tabSeed;
    private double rnd(){int s=tabSeed;s^=s<<13;s^=s>>>17;s^=s<<5;tabSeed=s;return (s&0xffffff)/16777216.0;}
    /** d/dt of a blow-down flow pulse (what the tailpipe radiates): a rounded click with a suction tail. */
    private double[] pulse(double T,double rise,double r4){
        double[] a=new double[len];double prev=0;
        for(int i=0;i<len;i++){double t=(i+1)/r4,f=t<5*T?(1-t/T)*Math.exp(-FB*t/T)*(1-Math.exp(-t/rise)):0;a[i]=f-prev;prev=f;}
        return a;
    }
    /** dry + band-pass formants (the exhaust / intake tract); formant Hz times scale. */
    private double[] through(double[] x,double[][] formants,double[][] more,double scale,double moreScale,double dry,double r4){
        double[] y=new double[len];for(int i=0;i<len;i++)y[i]=x[i]*dry;
        for(int f=0;f<formants.length+(more==null?0:more.length);f++){
            double[] fm=f<formants.length?formants[f]:more[f-formants.length];double hz=fm[0]*(f<formants.length?scale:moreScale);
            if(hz>=r4*.45)continue;
            double[] c=bandPass(hz,fm[1],r4);double x1=0,x2=0,y1=0,y2=0,mix=fm[2]*3;
            for(int i=0;i<len;i++){double o=c[0]*x[i]+c[1]*x1+c[2]*x2-c[3]*y1-c[4]*y2;x2=x1;x1=x[i];y2=y1;y1=o;y[i]+=o*mix;}
        }
        return y;
    }
    private double[] onePole(double[] x,double hz,double r4){
        double k=1-Math.exp(-2*Math.PI*hz/r4),s=0;double[] y=new double[len];
        for(int i=0;i<len;i++){s+=(x[i]-s)*k;y[i]=s;}
        return y;
    }
    /** Reflections {delay in table samples, gain}: delays spread log-uniformly, later ones weaker. */
    private double[][] taps(double[] o,double r4){
        double[][] a=new double[(int)o[0]][];
        for(int j=0;j<a.length;j++){
            double d=o[1]*Math.pow(o[2]/o[1],rnd())/Math.sqrt(p.fScale);
            double sign=rnd()<.5?-1:1;
            a[j]=new double[]{Math.round(d*r4),sign*o[3]*Math.exp(-d/o[4])*(.5+.5*rnd())*body};
        }
        return a;
    }
    /** The reflections that spread a firing's mids and highs (OWN_*), before a cylinder varies them. */
    private double[][] spread(double r4){
        double[][] a=new double[OWN_N][];
        for(int j=0;j<OWN_N;j++){
            double d=(OWN_D0+(OWN_D1-OWN_D0)*rnd())/Math.sqrt(p.fScale),sign=rnd()<.5?-1:1;
            a[j]=new double[]{Math.round(d*r4),sign*OWN_GAIN*Math.exp(-d/OWN_TAU)*(1-Math.exp(-d/OWN_RISE))*body};
        }
        return a;
    }
    private void reflect(double[] y,double[] src,double[][] tp){for(double[] t:tp){int d=(int)t[0];double g=t[1];for(int i=d;i<len;i++)y[i]+=src[i-d]*g;}}
    /** Brightness (one-pole low-pass) and the band limit of the output rate: the tables are read every OS-th sample. */
    private double[] shape(double[] x,double[] aa,double r4){
        x=onePole(x,TILT*tone*Math.sqrt(p.fScale)*p.high,r4);double[] y=new double[len];int AA=aa.length/2;
        for(int i=0;i<len;i++){double a=0;for(int j=-AA;j<=AA;j++){int k=i-j;if(k>=0&&k<len)a+=x[k]*aa[j+AA];}y[i]=a;}
        return y;
    }
    /** All-pass chain: smears the click into the short chirp a pipe makes of it, same spectrum. */
    private double[] disperse(double[] x,double r4){
        for(double hz:ALLPASS){
            double w=2*Math.PI*hz*Math.sqrt(p.fScale)/r4,al=Math.sin(w)/(2*ALLPASS_Q),a0=1+al,b0=(1-al)/a0,b1=-2*Math.cos(w)/a0,x1=0,x2=0,y1=0,y2=0;
            for(int i=0;i<len;i++){double o=b0*x[i]+b1*x1+x2-b1*y1-b0*y2;x2=x1;x1=x[i];y2=y1;y1=o;x[i]=o;}
        }
        return x;
    }
    /** Fades the tail and sets the energy per firing. */
    private float[] finish(double[] y,double energy){
        int f0=(int)Math.floor(len*.8);double e=0;
        for(int i=f0;i<len;i++)y[i]*=.5+.5*Math.cos(Math.PI*(i-f0)/(len-f0));
        for(int i=0;i<len;i++)e+=y[i]*y[i];
        double g=Math.sqrt(energy*OS/e);float[] o=new float[len];for(int i=0;i<len;i++)o[i]=(float)(y[i]*g);
        return o;
    }
    private float[] cylinder(double[] base,double energy,double[][] shared,double[][] own,double r4,double spread){
        double[] y=new double[len],lp=onePole(base,SHARED_LP,r4),lo=onePole(base,OWN_HP,r4),hi=new double[len];double direct=1-(1-OWN_DIRECT)*Math.min(1,body);
        for(int i=0;i<len;i++){double h=base[i]-lo[i];y[i]=lo[i]+direct*h;hi[i]=h*spread;}
        reflect(y,lp,shared);reflect(y,hi,own);return finish(y,energy);
    }

    /** Where a wavelet's tail has faded for good: everything after stays under -80 dB of its peak (under one 16-bit step
     *  in the output). The intake pulse is done in about half the 48 ms, so half its additions are saved. */
    private static int end(float[] t){
        float pk=0;for(float v:t)pk=Math.max(pk,Math.abs(v));
        int e=t.length;while(e>1&&Math.abs(t[e-1])<=pk*1e-4f)e--;
        return e;
    }
    /** Builds the wavelets of the voice (a few ms of work, once per voice or brightness / reflection change) and switches
     *  in place: inputs, phase and smoothing carry on, and the firings still ringing keep their old tables. */
    public void use(Voice v){
        p=v;even=Math.sqrt(4.0/v.nc);   // more cylinders, more firings: same loudness
        double r4=rate*OS,root=Math.sqrt(v.fScale);len=(int)Math.round(WAVE*rate)*OS;tabSeed=v.seed;
        int AA=24;double[] aa=new double[2*AA+1];double sum=0;   // windowed sinc at 0.42 of the output rate
        for(int i=-AA;i<=AA;i++){double x=i*2*.42/OS;sum+=aa[i+AA]=(i!=0?Math.sin(Math.PI*x)/(Math.PI*x):1)*(.5+.5*Math.cos(Math.PI*i/(AA+1)));}
        for(int i=0;i<=2*AA;i++)aa[i]/=sum;
        double[][] shared=taps(SHARED,r4),dense=spread(r4);
        double[] baseH=disperse(shape(through(pulse(HARD_T/root,HARD_RISE/v.high,r4),v.formants,RIDGES,1,root,v.dry*v.high,r4),aa,r4),r4),
            baseS=disperse(shape(through(pulse(SOFT_T/root,SOFT_RISE,r4),v.formants,RIDGES,1,root,v.dry,r4),aa,r4),r4);
        float[][] h=new float[v.nc][],s=new float[v.nc][];
        for(int c=0;c<v.nc;c++){double[][] own=new double[OWN_N][];for(int j=0;j<OWN_N;j++)own[j]=new double[]{dense[j][0],dense[j][1]*(1-OWN_VARY+2*OWN_VARY*rnd())};
            h[c]=cylinder(baseH,1,shared,own,r4,1);s[c]=cylinder(baseS,SOFT_LEVEL*SOFT_LEVEL,shared,own,r4,OWN_SOFT);
            int e=Math.max(end(h[c]),end(s[c]));h[c]=java.util.Arrays.copyOf(h[c],e);s[c]=java.util.Arrays.copyOf(s[c],e);}   // spawn() adds the pair over one length
        // intake: one suction pulse per induction stroke through the intake tract
        double[] a=new double[len];int n=Math.min(len,(int)Math.round(INTAKE_T/root*r4));
        for(int i=0;i<n;i++){double q=Math.sin(Math.PI*i/n);a[i]=-q*q;}
        double[] base=shape(through(a,INTAKE_FORMANTS,null,v.fScale,1,INTAKE_DRY,r4),aa,r4),y=base.clone();
        reflect(y,base,taps(INTAKE_TAPS,r4));
        tabH=h;tabS=s;tabI=finish(y,1);tabI=java.util.Arrays.copyOf(tabI,end(tabI));
        nbq=new double[2][];for(int b=0;b<2;b++)nbq[b]=bandPass(Math.min(NOISE_BANDS[b][0]*root,rate*.42),NOISE_BANDS[b][1],rate);
        double w=2*Math.PI*v.popBodyHz/rate,alpha=Math.sin(w)/(2*1.1),cw=Math.cos(w),a0=1+alpha;   // pop body: resonant low-pass
        popBq=new double[]{(1-cw)/2/a0,(1-cw)/a0,(1-cw)/2/a0,-2*cw/a0,(1-alpha)/a0};
        double w2=2*Math.PI*v.popBodyHz*3/rate,al2=Math.sin(w2)/(2*.8),b0=1+al2;   // punch: broad band-pass at 3x
        punchBq=new double[]{al2/b0,0,-al2/b0,-2*Math.cos(w2)/b0,(1-al2)/b0};
    }
    /** Listener tuning: brightness, flow noise, pipe reflections, intake, firing-to-firing scatter (all 1 = as designed)
     *  and the off-load firing strength (0..1 of full load). Brightness and reflections rebuild the wavelets. */
    public void tune(double tone,double rough,double body,double intake,double uneven,double low){
        boolean re=tone!=this.tone||body!=this.body;
        this.tone=tone;this.rough=rough;this.body=body;intakeVol=intake;this.uneven=uneven;this.low=low;
        if(re)use(p);
    }

    /** xorshift32, the same in the page: -1..1. */
    private double noise(){int s=seed;s^=s<<13;s^=s>>>17;s^=s<<5;seed=s;return (seed&0xffffff)/8388608.0-1;}

    /** NaN rpm (engine off, stale data, reverse) fades to silence; pedal 0..1; boost in bar (negative = vacuum). */
    public void set(double rpm,double pedal,double boost){
        running=!Double.isNaN(rpm)&&rpm>300;
        if(running){
            targetRpm=rpm;
            if(rpm!=lastRaw){   // a new CAN sample (held values repeat between frames)
                if(!Double.isNaN(lastRaw)&&sinceNew>0){
                    // A step faster than 5000 rpm/s is a gear change or a clutch event, not a trend: hold it.
                    double sl=(rpm-lastRaw)/sinceNew;
                    slope=Math.abs(sl)*rate>5000?0:sl;interval=sinceNew;
                }
                lastRaw=rpm;sinceNew=0;
            }
        }else{lastRaw=Double.NaN;slope=0;}
        double pd=Double.isNaN(pedal)?0:Math.max(0,Math.min(1,pedal)),b=Double.isNaN(boost)?0:boost;
        if(lastPedal-pd>.25&&Math.max(b,peakBoost)>.3)blowoff=Math.min(1,Math.max(b,peakBoost));
        if(lastPedal>.2&&pd<.05)liftTimer=p.popWindow;
        peakBoost=Math.max(b,peakBoost*.9);
        lastPedal=pd;targetPedal=pd;targetBoost=b;
    }
    /** Adds one wavelet into the ring: a*tA + b*tB, table index pos at the sample being made (negative = it starts later). */
    private void spawn(float[] tA,float[] tB,double a,double b,int pos){
        int m=pos<0?(OS-1-pos)/OS:0,k=pos+m*OS,x=(tick+m)&(RING-1);final double[] r=mix;final int L=tA.length;
        if(b==0)for(;k<L;k+=OS,x=(x+1)&(RING-1))r[x]+=a*tA[k];
        else if(a==0)for(;k<L;k+=OS,x=(x+1)&(RING-1))r[x]+=b*tB[k];
        else for(;k<L;k+=OS,x=(x+1)&(RING-1))r[x]+=a*tA[k]+b*tB[k];
    }

    /** Fills out[off..off+n) with -1..1 samples at the given master volume (0 = silent, 1 = as designed, up to 2 louder:
     *  at or below 1 the finished sound is scaled, above 1 it is driven harder into the soft clip so it gets louder and
     *  more saturated without ever leaving -1..1 — no hard clipping). */
    public void render(float[] out,int off,int n,double volume){
        Voice P=p;int nc=P.nc;double popC=1-(1-P.popChance)*popRate,tv=turboVol,bv=bovVol;
        double k=1-Math.exp(-1/(.08*rate)),ka=1-Math.exp(-1/(.025*rate)),kr=lead>0?1-Math.exp(-1/(.03*rate)):k,leadN=lead*rate,ke=1-Math.exp(-1/(.15*rate)),
            puffDecay=Math.exp(-1/(NOISE_TAU*rate)),blowDecay=Math.exp(-1/(.15*rate)),dt=n/rate,
            step=2*Math.PI/nc,kw=1-Math.exp(-2*Math.PI*70/rate),kf=1-Math.exp(-2*Math.PI*6/rate);
        if(!running&&master<1e-5){master=0;java.util.Arrays.fill(mix,0);for(int i=0;i<n;i++)out[off+i]=0;return;}   // fully faded: silence
        liftTimer=Math.max(0,liftTimer-dt);flameHold=Math.max(0,flameHold-dt);
        pedTop=Math.max(targetPedal,pedTop-LEAD_SINK*dt);leadHold=Math.max(0,leadHold-dt);
        if(pedTop-targetPedal>LEAD_DROP){leadHold=LEAD_HOLD;pedTop=targetPedal;}
        for(int i=0;i<n;i++){
            if(leadN>0&&running&&!Double.isNaN(lastRaw)){   // predicted rpm, never more than 400 rpm from the last sample
                double ahead=Math.min(sinceNew+leadN,leadN+1.5*interval);
                // no lead while the pedal holds it, nor on a slope gone stale: an rpm that stops changing sends no new
                // sample, and the last slope used to stay added for as long as the value repeated (188 rpm sharp after a pull)
                targetRpm=leadHold>0||sinceNew>2.5*interval?lastRaw:lastRaw+Math.max(-400,Math.min(400,slope*ahead));
            }
            sinceNew++;
            rpm+=(targetRpm-rpm)*kr;pedal+=(targetPedal-pedal)*(targetPedal>pedal?ka:k);/* pressing follows in 25 ms, lifting eases off over 80 ms */boost+=(targetBoost-boost)*k;
            master+=((running?1:0)-master)*ke;
            double dph=2*Math.PI*rpm/120/rate*(1+FLUTTER*uneven*flut);   // half-order phase: one cycle per two revolutions
            phase+=dph;
            if(phase>=2*Math.PI)phase-=2*Math.PI;
            double load=pedal*(LOAD0+(1-LOAD0)*Math.max(0,Math.min(1,(boost+.3)/1.3)));
            int fire=(int)Math.floor(phase/step);
            if(fire!=firing){   // a cylinder fires: its exhaust valve sends one pulse down its own pipe
                firing=fire;
                int c=fire%nc;double frac=dph>0?Math.min(1,(phase-fire*step)/dph):0;
                double trim=Math.pow(3000/Math.max(600,rpm),GAMMA);
                double a=P.cyl[c]*(1+P.jitter*uneven*noise())*(low+(1-low)*Math.pow(load,.8))*trim*even;
                double h=Math.pow(load,HARD_EXP),late=JIT_DEG*uneven*(1+noise())*rate/(Math.max(300,rpm)*6);
                spawn(tabS[c],tabH[c],a*(1-h),a*h*P.pulseMul,(int)Math.round((frac-late)*OS));
                puff+=a*(.3+.7*h);
                // Overrun burble: a firing-locked, random subset of thuds gives the "du-du-du" that slows with the rpm.
                if(liftTimer>0&&pedal<.05&&rpm>P.popRpm&&noise()>popC){
                    boolean big=noise()>1-2*P.popBig;double fade=.45+.55*Math.min(1,liftTimer/P.popWindow);
                    popT0=P.popT0*(big?.7:1+.2*noise());
                    popA=(big?1.3:.75+.2*noise())*fade;
                    popB=1.8+.4*noise();popTau=big?.03:.012;popT=0;
                    if(big&&fade>.7&&flameHold<=0&&master>.3){flames++;flameHold=FLAME_GAP;}   // ponytail: a flame for the odd big pop, not every thud
                }
            }
            double q=phase/step+.5,fq=Math.floor(q);int fi=(int)fq%nc;
            if(fi!=firingI){   // induction stroke, half a firing interval away
                firingI=fi;
                if(pedal>.02){
                    double fracI=dph>0?Math.min(1,(q-fq)*step/dph):0;
                    double a=INTAKE_GAIN*intakeVol*Math.pow(pedal,1.2)*(.35+.65*Math.min(1,rpm/4000))*(1+.06*noise())*Math.pow(3000/Math.max(600,rpm),GAMMA)*even;
                    spawn(tabI,tabI,a,0,(int)Math.round(fracI*OS));
                }
            }
            int tx=tick&(RING-1);double v=mix[tx];mix[tx]=0;tick++;   // the firings still ringing
            // flow noise that breathes with the firings, through two fixed bands
            double nz=noise();flut+=(nz-flut)*kf;
            double nin=nz*(NOISE_BASE+NOISE_DEPTH*puff)*P.flow*25*(.12+load)*P.high*rough*tone;   // the hiss follows the brightness
            puff*=puffDecay;
            double ns=0;
            for(int b=0;b<2;b++){
                double[] c=nbq[b],s=nst[b];
                double o=c[0]*nin+c[1]*s[0]+c[2]*s[1]-c[3]*s[2]-c[4]*s[3];
                s[1]=s[0];s[0]=nin;s[3]=s[2];s[2]=o;ns+=o*NOISE_BANDS[b][2];
            }
            double y=(v+ns*NOISE_GAIN)*OUT;
            double dc=y-hpIn+.995*hp;hpIn=y;hp=dc;
            double s=dc*master*Math.min(1,rpm/800);
            double spool=Math.max(0,Math.min(1,boost/1.2));
            if(spool>0){   // turbo: a breathy whistle (a tone whose level wanders with low-passed noise), rising with boost
                wobblePhase+=2*Math.PI*3.1/rate;
                whistlePhase+=2*Math.PI*(1800+2800*spool+30*Math.sin(wobblePhase))/rate;
                if(whistlePhase>2*Math.PI)whistlePhase-=2*Math.PI;
                whLp+=(nz-whLp)*kw;
                s+=Math.sin(whistlePhase)*(1.4+36*whLp)*P.whistle*tv*spool*(.4+.6*pedal)*master;
            }
            // Blow-off: a short band-limited hiss (about 1.5-5 kHz), as loud as the boost it dumps.
            if(blowoff>.001){blowLp+=(nz-blowLp)*.25;blowBp+=((nz-blowLp)-blowBp)*.5;s+=blowBp*P.blowoff*bv*blowoff*master;blowoff*=blowDecay;}
            double x=0,cr=0;
            if(popT>=0){
                double t=popT/rate,T=popT0;
                if(t<4*T)x=popA*(1-t/T)*Math.exp(-popB*t/T)*Math.min(1,t/.0006);
                crLp+=(nz-crLp)*.3;
                cr=crLp*popA*.16*Math.exp(-t/popTau)*Math.min(1,t/.002);
                if(++popT>rate*Math.max(4*T,6*popTau))popT=-1;
            }
            double body=popBq[0]*x+popBq[1]*popSt[0]+popBq[2]*popSt[1]-popBq[3]*popSt[2]-popBq[4]*popSt[3];
            popSt[1]=popSt[0];popSt[0]=x;popSt[3]=popSt[2];popSt[2]=body;
            double punch=punchBq[0]*x+punchBq[1]*punchSt[0]+punchBq[2]*punchSt[1]-punchBq[3]*punchSt[2]-punchBq[4]*punchSt[3];
            punchSt[1]=punchSt[0];punchSt[0]=x;punchSt[3]=punchSt[2];punchSt[2]=punch;
            s+=(softclip((body*1.7+punch*PUNCH)*1.3)*.85+cr)*P.popGain*master;
            out[off+i]=(float)(softclip(s*P.drive*(volume>1?volume:1))*(volume<1?volume:1));
        }
    }
    public boolean running(){return running;}
    /** The rpm the sound follows right now (tests and the lag readout). */
    public double rpmForTest(){return rpm;}
}
