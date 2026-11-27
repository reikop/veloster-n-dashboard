package com.veloster.vehicle;

/**
 * The engine sound owners liked better: on 2026-11-24 the pulse-wavelet synth (beta 65d1480..70ec726, v1.17.0..v1.17.5)
 * was taken out again and this, the sound of v1.16 (beta 3910ce7), is the only one.
 *
 * Virtual engine sound from received rpm, pedal and boost only (no recordings, no Android). The same algorithm and
 * tables run in the listening page (engine-sound.html), sample for sample. A voice is an engine (firing: cylinders,
 * order maps, combustion pulse, turbo) played through an exhaust (resonances, low/high order balance, overrun pops).
 *
 * Built the way the active-sound-design literature does it:
 * - Engine-order synthesis: half orders 0.5..8 of the crank, phase-locked to rpm, with one amplitude map at no load and
 *   one at full load blended by the pedal (Siemens Simcenter ASD order maps per load; Cao et al., SAE 2020).
 * - Firing pulses, each cylinder a little different and every firing jittered (Jagla et al.).
 * - Harmonic plus noise: intake/combustion noise grows with load (Doerfler and Wyse, EUSIPCO 2026).
 * - Formant filters shape the sum like an exhaust and cabin (formant-filter driving sound, KSNVE 2015).
 * - Overrun burble: on a lift, a random, firing-locked subset of exhaust strokes fires a thud: a Friedlander blast
 *   wave (gunshot/thunder N-wave models) rung through the exhaust gas column's low resonance and a broad band at 3x,
 *   with a low-passed crackle tail (Chadwick and James).
 * - Turbo whistle with boost; blow-off as loud as the boost it dumps.
 * - Latency compensation: rpm predicted ahead by linear extrapolation of the last two CAN samples (US 8270628).
 * Mono, -1..1 floats.
 */
public final class EngineSynth {
    public static final int RATE=11025;   // 11.025 kHz: engine content is under ~5 kHz, and the head unit cannot afford 22 kHz of synthesis
    static final double PUNCH=3;   // weight of the 3x band next to the thump (burble about 80 % below 150 Hz)

    /**
     * Fast tanh-like soft clip, |out| < 1. The audio thread runs this twice per sample; on this head unit Math.tanh
     * was a top cost. Padé approximation, clamped to +/-1 at |x| >= 3 (where the approximation already reaches 1).
     */
    static double softclip(double x){
        if(x<=-3)return -1;if(x>=3)return 1;double x2=x*x;return x*(27+x2)/(27+9*x2);
    }

    static final class Engine {
        final double[] cyl,coast,full,pulse;final double jitter,fScale,popMul,popRpm,flow,whistle,blowoff;
        /** What sets one four apart from another (all off by default):
         *  firePos - where each cylinder's pulse reaches the collector in the 720 deg cycle (0..1, null = evenly);
         *            a boxer's unequal-length headers bunch them in pairs: the off-beat rumble.
         *  camRpm, camLift - a high-lift cam above camRpm: orders 3 and up and the intake roar rise by camLift.
         *  brap - an upshift at full throttle (rpm drops faster than a rev can) cuts ignition for a moment and the
         *         unburnt charge cracks in the exhaust: a quick volley of pops (dual-clutch hot hatches).
         *  flutter - lifting off boost with no blow-off valve: the compressor surges, a chopped "chu-tu-tu".
         *  clatter - a diesel's combustion knock: a short bright click on every firing. */
        double[] firePos;double camRpm,camLift,brap,flutter,clatter;
        Engine(double[] cyl,double jitter,double fScale,double popMul,double popRpm,double[] coast,double[] full,double[] pulse,double flow,double whistle,double blowoff){
            this.cyl=cyl;this.jitter=jitter;this.fScale=fScale;this.popMul=popMul;this.popRpm=popRpm;this.coast=coast;this.full=full;
            this.pulse=pulse;this.flow=flow;this.whistle=whistle;this.blowoff=blowoff;}
        Engine fire(double... pos){firePos=pos;return this;}
        Engine cam(double rpm,double lift){camRpm=rpm;camLift=lift;return this;}
        Engine brap(double v){brap=v;return this;}
        Engine flutter(double v){flutter=v;return this;}
        Engine clatter(double v){clatter=v;return this;}
    }
    static final class Exhaust {
        final double[][] formants;final double dry,orderGain,low,high,pulseMul,flowMul,drive,popQ,popWindow,popBodyHz,popT0,popBig,popGain;
        Exhaust(double[][] formants,double dry,double orderGain,double low,double high,double pulseMul,double flowMul,double drive,
                double popQ,double popWindow,double popBodyHz,double popT0,double popBig,double popGain){
            this.formants=formants;this.dry=dry;this.orderGain=orderGain;this.low=low;this.high=high;this.pulseMul=pulseMul;this.flowMul=flowMul;
            this.drive=drive;this.popQ=popQ;this.popWindow=popWindow;this.popBodyHz=popBodyHz;this.popT0=popT0;this.popBig=popBig;this.popGain=popGain;}
    }
    /** An engine through an exhaust: what render() plays. */
    public static final class Voice {
        final double[] cyl,coast=new double[16],full=new double[16],pulse,firePos;final double[][] formants;
        final double jitter,dry,orderGain,flow,drive,whistle,blowoff,popGain,popRpm,popChance,popWindow,popBodyHz,popT0,popBig,camRpm,camLift,brap,flutter,clatter;
        Voice(Engine e,Exhaust x){
            cyl=e.cyl;jitter=e.jitter;firePos=e.firePos;camRpm=e.camRpm;camLift=e.camLift;brap=e.brap;flutter=e.flutter;clatter=e.clatter;
            for(int m=0;m<16;m++){double s=m<=2?x.low:m>=8?x.high:1;coast[m]=e.coast[m]*s;full[m]=e.full[m]*s;}
            formants=new double[x.formants.length][];
            for(int f=0;f<formants.length;f++)formants[f]=new double[]{x.formants[f][0]*e.fScale,x.formants[f][1],x.formants[f][2]};
            dry=x.dry;orderGain=x.orderGain;
            pulse=new double[]{e.pulse[0]*x.pulseMul,e.pulse[1]*x.pulseMul,e.pulse[2],e.pulse[3]};flow=e.flow*x.flowMul;drive=x.drive;
            whistle=e.whistle;blowoff=e.blowoff;
            popGain=x.popGain;popRpm=e.popRpm;popChance=1-2*x.popQ*e.popMul;popWindow=x.popWindow;
            popBodyHz=x.popBodyHz*Math.sqrt(e.fScale);popT0=x.popT0/Math.sqrt(e.fScale);popBig=x.popBig;
        }
        /** False for engines without a turbo (no whistle, no blow-off or flutter). */
        public boolean turbo(){return whistle>0;}
    }
    /** engine 0..engines()-1 (0 = this car's 1.6 turbo four), exhaust 0..exhausts()-1 (0 = stock); out of range = 0. */
    public static Voice voice(int engine,int exhaust){
        return new Voice(ENGINES[engine>=0&&engine<ENGINES.length?engine:0],EXHAUSTS[exhaust>=0&&exhaust<EXHAUSTS.length?exhaust:0]);
    }
    public static int engines(){return ENGINES.length;}
    public static int exhausts(){return EXHAUSTS.length;}

    // 0..4 came from the listening page (engine-sound.html); 5..10, the fours added 2026-11-27, live only here
    // (their samples are rendered from this class). Indexes are saved, so new engines only ever go at the end.
    static final String[] ENGINE_IDS={"i4t","i6","v6","v8","v8flat","i4hot","i4cam","boxer","i4rally","i4light","i4diesel"};
    static final Engine[] ENGINES={
        new Engine(new double[]{1,0.86,0.95,0.8},0.12,1,1,2200,
            new double[]{0.05,0.1,0.03,1,0.03,0.1,0.02,0.32,0.02,0.06,0.01,0.14,0.01,0.03,0.01,0.05},
            new double[]{0.2,0.24,0.16,1,0.14,0.3,0.11,0.55,0.09,0.22,0.07,0.36,0.06,0.15,0.05,0.2},
            new double[]{0.35,0.45,0.3,0.5},0.04,0.014,0.05),
        new Engine(new double[]{1,0.97,0.99,0.96,0.98,0.95},0.06,1.3,0.7,2400,
            new double[]{0.02,0.04,0.02,0.1,0.02,1,0.02,0.08,0.02,0.06,0.01,0.4,0.01,0.04,0.01,0.1},
            new double[]{0.06,0.08,0.05,0.18,0.05,1,0.05,0.16,0.05,0.14,0.04,0.55,0.04,0.1,0.03,0.18},
            new double[]{0.3,0.4,0.28,0.45},0.04,0.012,0.05),
        new Engine(new double[]{1,0.93,0.97,0.9,0.99,0.92},0.09,1.05,0.8,2300,
            new double[]{0.04,0.08,0.14,0.12,0.03,1,0.03,0.1,0.06,0.08,0.02,0.3,0.02,0.05,0.01,0.08},
            new double[]{0.1,0.14,0.3,0.22,0.08,1,0.08,0.2,0.14,0.16,0.05,0.45,0.05,0.12,0.04,0.16},
            new double[]{0.34,0.42,0.3,0.5},0.045,0.016,0.08),
        new Engine(new double[]{1,0.7,0.95,0.74,0.9,0.66,1,0.8},0.15,0.7,1,2200,
            new double[]{0.3,0.45,0.25,0.55,0.15,0.2,0.1,1,0.08,0.12,0.06,0.25,0.05,0.1,0.04,0.3},
            new double[]{0.45,0.6,0.4,0.7,0.25,0.3,0.18,1,0.15,0.2,0.1,0.4,0.08,0.15,0.06,0.45},
            new double[]{0.4,0.45,0.32,0.52},0.04,0,0),
        new Engine(new double[]{1,0.95,0.98,0.93,1,0.96,0.97,0.94},0.07,1.5,0.6,2600,
            new double[]{0.02,0.03,0.02,0.12,0.02,0.05,0.02,1,0.02,0.05,0.02,0.14,0.02,0.06,0.02,0.45},
            new double[]{0.04,0.06,0.04,0.2,0.04,0.1,0.04,1,0.04,0.12,0.04,0.28,0.04,0.14,0.04,0.6},
            new double[]{0.3,0.4,0.28,0.48},0.04,0,0),
        // 5 2.0 turbo hot hatch: rasp in the low half orders, a hard 4th and 6th under load, crackle on lifts and a brap
        //   on every flat-out upshift (dual-clutch hot hatches).
        new Engine(new double[]{1,0.82,0.97,0.78},0.14,0.95,1.7,2000,
            new double[]{0.08,0.16,0.06,1,0.05,0.14,0.03,0.4,0.03,0.09,0.02,0.18,0.01,0.05,0.01,0.06},
            new double[]{0.26,0.34,0.2,1,0.18,0.38,0.14,0.7,0.12,0.3,0.09,0.48,0.07,0.2,0.05,0.24},
            new double[]{0.4,0.5,0.32,0.55},0.05,0.013,0.07).brap(.9),
        // 6 high-revving NA with a cam changeover: smooth and thin low down, then at 5600 rpm the high-lift cam opens
        //   up a hard, bright scream with a lot of intake.
        new Engine(new double[]{1,0.95,0.98,0.94},0.07,1.15,0.5,3200,
            new double[]{0.03,0.06,0.02,1,0.02,0.12,0.02,0.3,0.02,0.1,0.01,0.16,0.01,0.06,0.01,0.08},
            new double[]{0.08,0.12,0.06,1,0.06,0.24,0.05,0.5,0.05,0.24,0.04,0.36,0.03,0.16,0.03,0.2},
            new double[]{0.3,0.4,0.28,0.45},0.06,0,0).cam(5600,1.3),
        // 7 flat four, unequal-length headers: the pulses reach the collector in pairs (0, 200, 360, 560 deg), so the
        //   half orders carry an off-beat rumble; a turbo that flutters instead of blowing off.
        new Engine(new double[]{1,0.9,0.96,0.88},0.11,0.92,1,2200,
            new double[]{0.3,0.5,0.32,1,0.12,0.18,0.08,0.3,0.05,0.08,0.03,0.12,0.02,0.04,0.01,0.04},
            new double[]{0.45,0.7,0.48,1,0.24,0.32,0.16,0.5,0.1,0.18,0.07,0.28,0.05,0.12,0.04,0.14},
            new double[]{0.4,0.5,0.32,0.55},0.045,0.015,0).fire(0,0.278,0.5,0.778).flutter(.8),
        // 8 rally turbo with anti-lag: rough, pops on nearly every overrun stroke, flutter and a brap on upshifts.
        new Engine(new double[]{1,0.78,0.94,0.74},0.2,0.95,2.6,1700,
            new double[]{0.12,0.2,0.1,1,0.08,0.16,0.05,0.38,0.04,0.1,0.03,0.18,0.02,0.06,0.01,0.07},
            new double[]{0.3,0.4,0.26,1,0.2,0.4,0.15,0.66,0.12,0.28,0.09,0.44,0.07,0.2,0.05,0.24},
            new double[]{0.45,0.55,0.34,0.58},0.055,0.02,0).flutter(.6).brap(.7),
        // 9 light 1.6 NA roadster: smooth and buzzy, little bass, a clean rise to the redline, few pops.
        new Engine(new double[]{1,0.97,0.99,0.96},0.05,1.12,0.45,3000,
            new double[]{0.02,0.04,0.02,1,0.02,0.16,0.02,0.36,0.01,0.12,0.01,0.2,0.01,0.08,0.01,0.1},
            new double[]{0.05,0.08,0.04,1,0.04,0.26,0.04,0.5,0.03,0.2,0.03,0.3,0.02,0.12,0.02,0.14},
            new double[]{0.28,0.38,0.26,0.42},0.035,0,0),
        // 10 diesel four (turbo): deep and lumpy, a combustion click on every firing, no overrun pops.
        new Engine(new double[]{1,0.88,0.95,0.86},0.1,0.88,0,99999,
            new double[]{0.1,0.18,0.08,1,0.06,0.2,0.04,0.28,0.03,0.08,0.02,0.1,0.01,0.03,0.01,0.03},
            new double[]{0.2,0.3,0.14,1,0.1,0.3,0.07,0.4,0.05,0.12,0.04,0.16,0.02,0.05,0.02,0.05},
            new double[]{0.6,0.8,0.4,0.5},0.04,0.012,0.04).clatter(.8)};
    static final String[] EXHAUST_IDS={"stock","sport","resonator","straight"};
    static final Exhaust[] EXHAUSTS={
        new Exhaust(new double[][]{{105,1.1,1},{420,2.2,0.55},{1150,3,0.18}},0.35,0.3,1,1,1,1,0.8,0.15,2.5,60,0.01,0.08,1.3),
        new Exhaust(new double[][]{{78,1,1.15},{300,1.8,0.5},{900,2.5,0.1}},0.42,0.34,1.7,0.75,1.1,0.9,0.78,0.19,2.5,52,0.012,0.1,1.5),
        new Exhaust(new double[][]{{95,1.2,0.9},{430,1.6,0.7},{1500,2,0.35},{82,7,0.7}},0.38,0.34,1.15,1.35,1.2,1.75,0.82,0.19,3,58,0.011,0.12,1.56),
        new Exhaust(new double[][]{{88,1,1.1},{380,1.5,0.7},{1300,2.2,0.3}},0.4,0.36,1.5,1.25,1.3,1.75,0.88,0.275,3.5,50,0.012,0.25,1.75)};

    private final double rate;
    private int seed=0x2545F491;
    private double rpm,pedal,boost,targetRpm,targetPedal,targetBoost,master;
    private double phase,whistlePhase,wobblePhase;   // phase = half-order phase: 2*pi per 720 degrees of crank
    private int firing=-1;
    private double pulse,blowoff,liftTimer,lastPedal,peakBoost,brapT,flutterPhase,flutLp1,flutLp2,clatterEnv,clatLp;
    private double hp,hpIn,blowLp,blowBp;
    private int popT=-1;
    private double popA,popT0,popB,popTau,crLp;
    private final double[] popSt=new double[4],punchSt=new double[4],amp=new double[16];
    private double[][] bq,st=new double[0][];
    private double[] popBq,punchBq;
    private boolean running;
    private Voice p;
    /** Listener trims on the voice's own values (1 = as designed). */
    public volatile double popRate=1,turboVol=1,bovVol=1;
    /** The engine itself (orders, pulses, flow, overrun pops) against the turbo whistle and blow-off: 0 leaves only those two. */
    public volatile double engineVol=1;
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

    /** Switches the voice in place: inputs, phase and smoothing carry on, so there is no gap. */
    public void use(Voice v){
        p=v;double[][] old=st;bq=new double[v.formants.length][];st=new double[v.formants.length][];
        for(int f=0;f<bq.length;f++){   // RBJ band-pass, 0 dB peak
            double w=2*Math.PI*v.formants[f][0]/rate,alpha=Math.sin(w)/(2*v.formants[f][1]),a0=1+alpha;
            bq[f]=new double[]{alpha/a0,0,-alpha/a0,-2*Math.cos(w)/a0,(1-alpha)/a0};
            st[f]=f<old.length?old[f]:new double[4];
        }
        double w=2*Math.PI*v.popBodyHz/rate,alpha=Math.sin(w)/(2*1.1),cw=Math.cos(w),a0=1+alpha;   // pop body: resonant low-pass
        popBq=new double[]{(1-cw)/2/a0,(1-cw)/a0,(1-cw)/2/a0,-2*cw/a0,(1-alpha)/a0};
        double w2=2*Math.PI*v.popBodyHz*3/rate,al2=Math.sin(w2)/(2*.8),b0=1+al2;   // punch: broad band-pass at 3x
        punchBq=new double[]{al2/b0,0,-al2/b0,-2*Math.cos(w2)/b0,(1-al2)/b0};
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
                    if(-sl*rate>5000&&lastPedal>.5&&lastRaw>3000&&p.brap>0)brapT=.12;   // a flat-out upshift: ignition cut
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

    /** Fills out[off..off+n) with -1..1 samples at the given master volume (0 = silent, 1 = as designed, up to 2 louder:
     *  at or below 1 the finished sound is scaled, above 1 it is driven harder into the soft clip so it gets louder and
     *  more saturated without ever leaving -1..1 — no hard clipping). */
    public void render(float[] out,int off,int n,double volume){
        Voice P=p;int nc=P.cyl.length,nf=bq.length;double popC=1-(1-P.popChance)*popRate,tv=turboVol,bv=bovVol,ev=engineVol;double[] pl=P.pulse;
        double k=1-Math.exp(-1/(.08*rate)),ka=1-Math.exp(-1/(.025*rate)),kr=lead>0?1-Math.exp(-1/(.03*rate)):k,leadN=lead*rate,ke=1-Math.exp(-1/(.15*rate)),
            pulseDecay=Math.exp(-1/(.0025*rate)),blowDecay=Math.exp(-1/(.15*rate)),dt=n/rate;
        if(!running&&master<1e-5){master=0;for(int i=0;i<n;i++)out[off+i]=0;return;}   // fully faded: silence
        double load=pedal;
        double cam=P.camRpm>0?Math.max(0,Math.min(1,(rpm-P.camRpm)/250+.5)):0,lift=1+P.camLift*cam;   // 250 rpm crossfade at the changeover
        for(int m=0;m<16;m++)amp[m]=(P.coast[m]+(P.full[m]-P.coast[m])*load)*(m>=5?lift:1);
        double flowK=1+.6*P.camLift*cam,cut=brapT>0?.35:1;   // the ignition cut drops the engine itself under the brap
        liftTimer=Math.max(0,liftTimer-dt);flameHold=Math.max(0,flameHold-dt);brapT=Math.max(0,brapT-dt);
        for(int i=0;i<n;i++){
            if(leadN>0&&running&&!Double.isNaN(lastRaw)){   // predicted rpm, never more than 400 rpm from the last sample
                double ahead=Math.min(sinceNew+leadN,leadN+1.5*interval);
                targetRpm=lastRaw+Math.max(-400,Math.min(400,slope*ahead));
            }
            sinceNew++;
            rpm+=(targetRpm-rpm)*kr;pedal+=(targetPedal-pedal)*(targetPedal>pedal?ka:k);/* pressing follows in 25 ms, lifting eases off over 80 ms */boost+=(targetBoost-boost)*k;
            master+=((running?1:0)-master)*ke;
            double f=rpm/120;   // half-order frequency: one cycle per two revolutions
            phase+=2*Math.PI*f/rate;
            if(phase>=2*Math.PI)phase-=2*Math.PI;
            int fire;
            if(P.firePos==null)fire=(int)Math.floor(phase*nc/(2*Math.PI));
            else{double fr=phase/(2*Math.PI);fire=0;while(fire+1<nc&&fr>=P.firePos[fire+1])fire++;}
            if(fire!=firing){
                firing=fire;
                pulse=P.cyl[fire%nc]*(1+P.jitter*noise());
                if(P.clatter>0)clatterEnv=1;
                if(brapT>0&&fire%2==0&&master>.3&&popRate>0){   // brap: every other firing in the cut cracks in the exhaust (a short volley); the 팝 trim scales it
                    popT0=P.popT0*(.6+.2*noise());popA=(1.1+.2*noise())*P.brap*Math.min(1.5,popRate);popB=1.8+.4*noise();popTau=.02;popT=0;}
                // Overrun burble: a firing-locked, random subset of thuds gives the "du-du-du" that slows with the rpm.
                if(liftTimer>0&&pedal<.05&&rpm>P.popRpm&&noise()>popC){
                    boolean big=noise()>1-2*P.popBig;double fade=.45+.55*Math.min(1,liftTimer/P.popWindow);
                    popT0=P.popT0*(big?.7:1+.2*noise());
                    popA=(big?1.3:.75+.2*noise())*fade;
                    popB=1.8+.4*noise();popTau=big?.03:.012;popT=0;
                    if(big&&fade>.7&&flameHold<=0&&master>.3){flames++;flameHold=FLAME_GAP;}   // ponytail: a flame for the odd big pop, not every thud
                }
            }
            // Orders: sin(m*phase) for m = 1..16 by the Chebyshev recurrence, one sin and one cos per sample.
            double c2=2*Math.cos(phase),prev=0,cur=Math.sin(phase),v=0;
            for(int m=0;m<16;m++){v+=amp[m]*cur;double next=c2*cur-prev;prev=cur;cur=next;}
            v*=P.orderGain;
            double nz=noise();
            v+=pulse*(pl[0]+pl[1]*nz)*(pl[2]+pl[3]*pedal);   // combustion pulse with noise in it
            v+=nz*P.flow*flowK*(.2+pedal)*Math.min(1,rpm/4000);    // intake / flow noise with load (and the cam)
            pulse*=pulseDecay;
            double y=v*P.dry;
            for(int b=0;b<nf;b++){
                double[] c=bq[b],s=st[b];
                double o=c[0]*v+c[1]*s[0]+c[2]*s[1]-c[3]*s[2]-c[4]*s[3];
                s[1]=s[0];s[0]=v;s[3]=s[2];s[2]=o;
                y+=o*P.formants[b][2]*3;
            }
            double dc=y-hpIn+.995*hp;hpIn=y;hp=dc;
            double level=master*Math.min(1,rpm/800)*(.45+.55*pedal)*(.60+.40*Math.min(1,rpm/6000))*cut;
            if(clatterEnv>.001){clatLp+=(nz-clatLp)*.55;dc+=(nz-clatLp)*clatterEnv*P.clatter*.5*(.4+.6*pedal);clatterEnv*=.93;}   // diesel knock: a ~1 ms bright click
            double s=dc*level*ev;
            double spool=Math.max(0,Math.min(1,boost/1.2));
            if(spool>0){   // turbo whistle rises with boost, with a slow wobble
                wobblePhase+=2*Math.PI*3.1/rate;
                whistlePhase+=2*Math.PI*(1800+2800*spool+30*Math.sin(wobblePhase))/rate;
                if(whistlePhase>2*Math.PI)whistlePhase-=2*Math.PI;
                s+=Math.sin(whistlePhase)*P.whistle*tv*spool*(.4+.6*pedal)*master;
            }
            // Blow-off: a short band-limited hiss (about 1.5-5 kHz), as loud as the boost it dumps.
            if(blowoff>.001){blowLp+=(nz-blowLp)*.25;blowBp+=((nz-blowLp)-blowBp)*.5;s+=blowBp*P.blowoff*bv*blowoff*master;
                if(P.flutter>0){   // compressor surge: a dark rush (two-pole ~600 Hz) swelling ~9-15 times a second, never fully gated
                    // (2026-11-27: the first take - bright hiss gated on/off at 14-22 Hz - sounded harsh, "츄츄츄")
                    flutLp1+=(nz-flutLp1)*.085;flutLp2+=(flutLp1-flutLp2)*.085;
                    flutterPhase+=2*Math.PI*(9+6*blowoff)/rate;if(flutterPhase>2*Math.PI)flutterPhase-=2*Math.PI;
                    double sw=.5+.5*Math.sin(flutterPhase);s+=flutLp2*(.3+.7*sw*sw)*P.flutter*bv*blowoff*master*15;}
                blowoff*=blowDecay;}
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
            s+=(softclip((body*1.7+punch*PUNCH)*1.3)*.85+cr)*P.popGain*master*ev;
            out[off+i]=(float)(softclip(s*P.drive*(volume>1?volume:1))*(volume<1?volume:1));
        }
    }
    public boolean running(){return running;}
    /** The rpm the sound follows right now (tests and the lag readout). */
    public double rpmForTest(){return rpm;}
}
