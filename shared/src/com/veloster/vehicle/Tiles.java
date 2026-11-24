package com.veloster.vehicle;

/**
 * The small value tiles a driver can re-point by holding them: four on classic's left and four on its right, the race
 * page's 2 x 2, F1's two lines under speed and rpm, retro's bottom row. Also the signal list every editor offers
 * (these tiles, gauges, graphs), in groups of related signals, without what this car does not have. Pure Java: the
 * renderers ask it what each tile shows and how, TilePicker shows the lists, VehicleDash keeps the choices.
 */
public final class Tiles {
    private Tiles(){}
    /** Not Telemetry signals: a tile that shows several values (the four tyres, both DCT clutches, the DPF cell) or a
     *  session statistic. Above every Telemetry index, stable because they are saved. */
    public static final int TIRES=1000,CLUTCH=1001,DPF=1002,CORNER_MIN_SPEED=1003,CORNER_MAX_LAT=1004,EXHAUST=1005;
    public static final int CLASSIC=0,RACE=1,F1=2,RETRO=3;
    public static final String[] KEYS={"classic","race","f1","retro"};
    /** Tile rectangles {l,t,r,b} on the 800 x 480 page, drawn and hit-tested alike. */
    public static final float[][][] RECTS={
        {{12,138,133,258},{141,138,262,258},{12,266,133,386},{141,266,262,386},{538,138,659,258},{667,138,788,258},{538,266,659,386},{667,266,788,386}},
        {{12,320,154,382},{158,320,300,382},{12,386,154,448},{158,386,300,448}},
        {{18,280,276,318},{524,280,782,318}},
        {{12,382,204,472},{208.5f,382,400.5f,472},{405,382,597,472},{601.5f,382,793.5f,472}}};
    /** What each tile shows until the driver picks something else. */
    public static int[] defaults(int layout,VehicleProfile p){
        boolean mt=p.trans==VehicleProfile.Trans.MT,diesel=p.fuel==VehicleProfile.Fuel.DIESEL;
        switch(layout){
            // Veloster N build: oil (a real sensor here), coolant, intake and the battery, all received on the JSN.
            case CLASSIC:return new int[]{2,Telemetry.INTAKE,Telemetry.BOOST,9,TIRES,Telemetry.AMBIENT,1,Telemetry.BAT_VOLTAGE};
            case RACE:return new int[]{2,Telemetry.INTAKE,1,Telemetry.BAT_VOLTAGE};
            case F1:return new int[]{1,2};
            default:return new int[]{CORNER_MIN_SPEED,CORNER_MAX_LAT,11,Telemetry.BAT_VOLTAGE};
        }
    }
    /** The driver's choices, -1 = the default; VehicleDash fills it from preferences.
     *  ponytail: one static table for the one dashboard; pass it through the renderers if there are ever two. */
    public static final int[][] picked={{-1,-1,-1,-1,-1,-1,-1,-1},{-1,-1,-1,-1},{-1,-1},{-1,-1,-1,-1}};
    /** What tile i of a layout shows on this car: the pick if this car has it, else the default. */
    public static int signal(int layout,int i,VehicleProfile p){
        int s=picked[layout][i];return s>=0&&available(s,p)?s:defaults(layout,p)[i];
    }
    /** The tile under (x, y), or -1. */
    public static int hit(int layout,float x,float y){
        float[][] r=RECTS[layout];
        for(int i=0;i<r.length;i++)if(x>=r[i][0]&&x<=r[i][2]&&y>=r[i][1]&&y<=r[i][3])return i;
        return -1;
    }

    // ---- the grouped list --------------------------------------------------------------------------------------
    public static final String[] GROUPS={"엔진","온도","터보·연료","변속·구동","주행·자세","브레이크·안전","타이어","전기·차체","디젤 DPF"};
    private static final int[][] MEMBERS={
        {0,32,59,36,9,42,15,16,40,41,Telemetry.MIL,Telemetry.ENGINE_RUNTIME,Telemetry.ENGINE_IDLE,Telemetry.ENGINE_DRIVE},
        {1,2,29,14,58,CLUTCH,66,67},
        {17,37,39,38,57},
        {8,30,52,56,34,28,74,75,76,77},
        {7,3,4,5,6,11,12,33,13,27,44,CORNER_MIN_SPEED,CORNER_MAX_LAT},
        {10,43,35,22,23,24},
        {TIRES,18,19,20,21,70,71,72,73},
        {26,25,31,45,47,48,49,53,54,55,46,50,51},
        {DPF,EXHAUST,68,60,61,69,62,63,64,65}};
    /** Whether this car has the signal: DPF only on a diesel, the clutch pedal only on a 6MT, the DCT clutch
     *  temperatures only on a DCT, no ignition timing on a diesel. */
    public static boolean available(int s,VehicleProfile p){
        boolean diesel=p.fuel==VehicleProfile.Fuel.DIESEL,mt=p.trans==VehicleProfile.Trans.MT;
        if(s<TIRES&&Telemetry.noSource(s))return false;   // this build receives only Telemetry.CORE signals
        if(s==CLUTCH||s==DPF||s==EXHAUST)return false;
        if(s==DPF||s==EXHAUST||s>=Telemetry.DPF_DP&&s<=Telemetry.DPF_AVG_KM||s==Telemetry.DPF_SOOT||s==Telemetry.EGT_CAT)return diesel;
        if(s==Telemetry.CLUTCH)return mt;
        if(s==CLUTCH||s==Telemetry.CLUTCH_ODD||s==Telemetry.CLUTCH_EVEN||s>=Telemetry.DCT_LIFE_A_ODD&&s<=Telemetry.DCT_LIFE_B_EVEN)return !mt;
        if(s==Telemetry.SPARK)return !diesel;
        return s>=0&&s<Telemetry.LABELS.length||s>=TIRES&&s<=CORNER_MAX_LAT;
    }
    /** Group g's signals on this car; tiles also get the several-value entries and session statistics. */
    public static int[] group(int g,VehicleProfile p,boolean tiles){
        int n=0;int[] out=new int[MEMBERS[g].length];
        for(int s:MEMBERS[g])if(available(s,p)&&(tiles||s<TIRES))out[n++]=s;
        int[] r=new int[n];System.arraycopy(out,0,r,0,n);return r;
    }
    /** The groups that have anything on this car (a petrol has no DPF group). */
    public static int[] groups(VehicleProfile p,boolean tiles){
        int n=0;int[] out=new int[GROUPS.length];
        for(int g=0;g<GROUPS.length;g++)if(group(g,p,tiles).length>0)out[n++]=g;
        int[] r=new int[n];System.arraycopy(out,0,r,0,n);return r;
    }
    public static int groupOf(int s){for(int g=0;g<MEMBERS.length;g++)for(int m:MEMBERS[g])if(m==s)return g;return -1;}
    /** List name (Korean, through UiText at the call). */
    public static String label(int s){
        switch(s){case TIRES:return "타이어 공기압 (4바퀴 묶음)";case CLUTCH:return "DCT 클러치 온도 (홀·짝 묶음)";case DPF:return "DPF (차압·온도·재생 묶음)";case EXHAUST:return "배기 온도 (DPF 앞·산화촉매 앞 묶음)";
            case CORNER_MIN_SPEED:return "직전 코너 최저 속도";case CORNER_MAX_LAT:return "직전 코너 최대 횡가속";}
        return Telemetry.LABELS[s];
    }

    // ---- how a tile shows one value ------------------------------------------------------------------------------
    private static final String[] CAPTION={"RPM","OIL","COOLANT","WHEEL FL","WHEEL FR","WHEEL RL","WHEEL RR","SPEED","GEAR","ACCEL",
        "BRAKE","LAT G","LON G","STEER","AMBIENT","POWER","TORQUE","BOOST","TIRE FL","TIRE FR","TIRE RL","TIRE RR","TCS","ESC","ABS",
        "SOC","BATT","STEER RATE","E-LSD","INTAKE","SELECTOR","ODO","LOAD","YAW","MODE","PARK BRK","THROTTLE","FUEL FLOW","RANGE",
        "AVG","TQ CORR","TQ FRIC","SPARK","BRK SW","STR TORQUE","DOOR L","WIPER","DOOR R","TRUNK","HOOD","TURN L","TURN R","SHIFT",
        "HEADLAMP","TAIL","HIGH BEAM","CLUTCH","FUEL","CAT","ABS LOAD","DPF ΔP","DPF TEMP","DPF REGEN","DPF TRIG","DPF LAST","DPF AVG",
        "CLUTCH ODD","CLUTCH EVEN","DPF SOOT","EGT CAT","TIRE T FL","TIRE T FR","TIRE T RL","TIRE T RR","LIFE A ODD","LIFE A EVEN","LIFE B ODD","LIFE B EVEN",
        "MIL","RUN TIME","IDLE TIME","DRIVE TIME"};
    public static String caption(int s){
        switch(s){case TIRES:return "TPMS";case CLUTCH:return "CLUTCH";case DPF:return "DPF";case EXHAUST:return "EXHAUST";case CORNER_MIN_SPEED:return "BTM SPEED";case CORNER_MAX_LAT:return "PREV LAT G";}
        return s>=0&&s<CAPTION.length?CAPTION[s]:"—";
    }
    public static String unit(int s){
        switch(s){case TIRES:return "psi";case CLUTCH:return "°C";case DPF:return "";case EXHAUST:return "°C";case CORNER_MIN_SPEED:return "km/h";case CORNER_MAX_LAT:return "G";}
        return Telemetry.UNITS[s];
    }
    /** The reading as text, "—" when there is none; on / off and gear style signals as their words. */
    /** The DPF bundle's main reading: soot g (22 ED1D) when the engine gives it, else the regeneration trigger %
     *  (01 8B), else the delta pressure kPa (ED03); the trigger ("—") when none has answered. */
    public static int dpfLead(float soot,float trigger,float dp){
        return !Float.isNaN(soot)?Telemetry.DPF_SOOT:!Float.isNaN(trigger)?Telemetry.DPF_TRIGGER:!Float.isNaN(dp)?Telemetry.DPF_DP:Telemetry.DPF_TRIGGER;
    }
    public static String text(int s,float v){
        if(Float.isNaN(v))return "—";
        if(s<TIRES){String w=Telemetry.label(s,v);if(w!=null)return w;}
        return Telemetry.format(fmt(s),v);
    }
    private static final float[] POW10={1,10,100,1000};
    /** A number that changes exactly when text(s, v) would (the digits shown), without building the string: the floating window's
     *  change test runs 20 times a second, and String.format on Dalvik allocates and is slow. */
    public static long textKey(int s,float v){
        if(Float.isNaN(v))return Long.MIN_VALUE;
        if(s<TIRES){String w=Telemetry.label(s,v);if(w!=null)return w.hashCode();}   // a word (gear, ON / OFF): constants, hash cached
        String f=fmt(s);int d=Math.max(0,Math.min(3,f.charAt(f.length()-2)-'0'));
        return Math.round((double)v*POW10[d]);   // in double, as Formatter rounds the float's exact value (101.405f is 101.40499..): -0.004 and 0.004 both read "0" (Telemetry.format drops the minus) and both round to 0 here
    }
    private static String fmt(int s){
        if(s==Telemetry.BOOST||s==11||s==12||s==CORNER_MAX_LAT)return "%+.2f";
        if(s==Telemetry.STEERING||s==Telemetry.STEER_TORQUE||s==Telemetry.YAW)return "%+.1f";
        String u=s>=TIRES?unit(s):Telemetry.UNITS[s];
        if(s==10||s==Telemetry.DPF_SOOT||"V".equals(u)||"L/h".equals(u)||"km/L".equals(u)||"kPa".equals(u))return "%.1f";
        return "%.0f";
    }
    /** The tyre temperature of wheel k (0 FL .. 3 RR) as a whole number, or "-" until the BCM has answered. */
    public static String tyreTemp(float t){return Float.isNaN(t)?"-":String.format(java.util.Locale.US,"%.0f",t);}
    /** 0 normal, 1 warm / low (amber), 2 critical (red). */
    public static int level(int s,float v){
        if(Float.isNaN(v))return 0;
        if(s==2||s==1)return v>=110?2:v>=100?1:0;
        if(s==Telemetry.INTAKE)return v>=80?2:v>=60?1:0;
        if(s==Telemetry.CLUTCH_ODD||s==Telemetry.CLUTCH_EVEN)return Telemetry.clutchLevel(v);
        if(s>=Telemetry.TPMS_FL&&s<=Telemetry.TPMS_RR)return v<26?2:v<30?1:0;
        if(s==Telemetry.DPF_REGEN)return v>0?1:0;
        if(s==Telemetry.MIL)return v!=0?2:0;
        return 0;
    }
    /** Where the reading sits on the tile's bar, 0..1, NaN for signals without a sensible scale. */
    public static float fraction(int s,float v){
        float lo,hi;
        if(s==0){lo=0;hi=8000;}else if(s==1||s==2){lo=40;hi=120;}else if(s==Telemetry.INTAKE){lo=0;hi=80;}
        else if(s==Telemetry.AMBIENT){lo=-20;hi=40;}else if(s==Telemetry.CAT_TEMP){lo=0;hi=900;}
        else if(s==Telemetry.CLUTCH_ODD||s==Telemetry.CLUTCH_EVEN){lo=0;hi=250;}else if(s==Telemetry.DPF_TEMP||s==Telemetry.EGT_CAT){lo=0;hi=700;}
        else if(s==Telemetry.DPF_SOOT){lo=0;hi=20;}else if(s==Telemetry.DPF_DP){lo=0;hi=50;}
        else if(s==Telemetry.BOOST){lo=0;hi=2f;}else if(s==10){lo=0;hi=100;}else if(s==Telemetry.BAT_VOLTAGE){lo=10;hi=15;}   // boost: 0 bar at the start, so the rise under boost uses the whole scale (vacuum stays at the start)
        else if(s>=Telemetry.TPMS_FL&&s<=Telemetry.TPMS_RR){lo=0;hi=45;}else if(s>=Telemetry.TIRE_TEMP_FL&&s<=Telemetry.TIRE_TEMP_RR){lo=0;hi=90;}else if(s==11||s==12||s==CORNER_MAX_LAT){return Float.isNaN(v)?Float.NaN:Math.abs(v)/1.5f;}
        else if(s==7||s>=3&&s<=6||s==CORNER_MIN_SPEED){lo=0;hi=240;}else if("%".equals(unit(s))){lo=0;hi=100;}
        else return Float.NaN;
        return Float.isNaN(v)?Float.NaN:(v-lo)/(hi-lo);
    }
    /** A single-value signal's reading (Telemetry display value, or the session statistic); NaN for none. */
    public static float value(int s,Telemetry.Sample[] samples,DriveStats st){
        if(s==CORNER_MIN_SPEED)return st==null?Float.NaN:st.cornerMinSpeed;
        if(s==CORNER_MAX_LAT)return st==null?Float.NaN:st.cornerMaxLat;
        if(s<0||s>=samples.length)return Float.NaN;
        Telemetry.Sample k=samples[s];
        return k.valid&&!Float.isNaN(k.value)&&!Float.isInfinite(k.value)?Telemetry.displayValue(s,k.value):Float.NaN;
    }
    /** Preference key of a tile. */
    public static String key(int layout,int i){return "tile_"+KEYS[layout]+"_"+i;}
}
