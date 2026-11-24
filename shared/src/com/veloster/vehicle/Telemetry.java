package com.veloster.vehicle;

/** Packet observations only. No CAN writes, preferences, simulation or Android dependency. */
public final class Telemetry {
    public static final int STEERING=13,AMBIENT=14,POWER=15,TORQUE=16,BOOST=17;
    public static final int TPMS_FL=18,TPMS_FR=19,TPMS_RL=20,TPMS_RR=21;
    public static final int TCS=22,ESC=23,ABS=24,BAT_SOC=25,BAT_VOLTAGE=26,STEERING_SPEED=27,ELSD=28;
    public static final int INTAKE=29;
    /** Signals of the dashboard ported from the i30 PD build (sung9155/i30-pd-dashboard, MIT). Indexes match that build so
     *  its floating window and tiles compile unchanged; this Veloster N build fills only the first CORE signals, the rest
     *  never get a value here, are not offered in any list and read as a dash. */
    public static final int GEAR_SELECTOR=30,ODOMETER=31,ENGINE_LOAD=32,YAW=33,DRIVE_MODE=34,PARK_BRAKE=35;
    public static final int THROTTLE_POS=36,FUEL_RATE=37,RANGE=38,AVG_FUEL=39,TORQUE_CORR=40,TORQUE_FRICTION=41;
    public static final int SPARK=42,BRAKE_SWITCH=43,STEER_TORQUE=44,DOOR_DRIVER=45,WIPER=46,DOOR_PASSENGER=47,TRUNK=48,HOOD=49,TURN_LEFT=50,TURN_RIGHT=51,SHIFT_HINT=52,HEAD_LAMP=53,TAIL_LAMP=54,HIGH_BEAM=55,CLUTCH=56,FUEL_LEVEL=57,CAT_TEMP=58,ABS_LOAD=59;
    public static final int DPF_DP=60,DPF_TEMP=61,DPF_REGEN=62,DPF_TRIGGER=63,DPF_SINCE_KM=64,DPF_AVG_KM=65,CLUTCH_ODD=66,CLUTCH_EVEN=67,DPF_SOOT=68,EGT_CAT=69;
    public static final int TIRE_TEMP_FL=70,TIRE_TEMP_FR=71,TIRE_TEMP_RL=72,TIRE_TEMP_RR=73;
    public static final int DCT_LIFE_A_ODD=74,DCT_LIFE_A_EVEN=75,DCT_LIFE_B_ODD=76,DCT_LIFE_B_EVEN=77;
    public static final int MIL=78,ENGINE_RUNTIME=79,ENGINE_IDLE=80,ENGINE_DRIVE=81;
    /** Signals this build receives (0..CORE-1); every list a driver picks from offers only these. */
    public static final int CORE=30;
    /** No reception path in this build. */
    public static boolean noSource(int s){return s!=DRIVE_MODE&&(s<0||s>=CORE);}   // the drive mode comes from the N-mode service (DriveModes)
    public static final float CLUTCH_WARM=150,CLUTCH_HOT=180;
    public static int clutchLevel(float t){return Float.isNaN(t)?0:t>=CLUTCH_HOT?2:t>=CLUTCH_WARM?1:0;}
    /** GPS speed calibration factor of the i30 PD build; always 1 here. */
    public static volatile float speedScale=1;
    public static final float BRAKING_BAR=3;
    public static boolean braking(float sw,float bar){return !Float.isNaN(sw)?sw>0:bar>BRAKING_BAR;}
    public static final String[] LABELS = {"엔진 회전수", "오일 온도", "냉각수 온도", "앞 왼쪽 휠", "앞 오른쪽 휠", "뒤 왼쪽 휠", "뒤 오른쪽 휠", "차량 속도", "EMS 기어", "스로틀", "브레이크 압력", "횡가속", "종가속", "조향각", "외기 온도", "출력 (계산)", "토크 (계산)", "부스트 (계산)", "앞 왼쪽 공기압", "앞 오른쪽 공기압", "뒤 왼쪽 공기압", "뒤 오른쪽 공기압", "TCS", "ESC", "ABS", "배터리 SOC", "배터리 전압", "조향 속도", "e-LSD 수치", "흡기 온도", "변속 레버", "주행거리", "엔진 부하", "요레이트", "주행 모드", "주차 브레이크", "스로틀 개도", "순간 연료소모", "주행가능거리", "평균 연비", "보정 토크", "마찰 토크", "점화 시기", "브레이크 스위치", "조향 토크", "운전석 문", "와이퍼", "조수석 문", "트렁크", "후드", "좌 방향지시등", "우 방향지시등", "변속 권장", "전조등", "미등", "상향등", "클러치 (수동)", "연료 잔량", "촉매 온도", "절대 부하", "DPF 차압", "DPF 온도", "DPF 재생", "DPF 재생 트리거", "DPF 재생 후 거리", "DPF 평균 재생 간격", "DCT 홀수단 클러치 온도", "DCT 짝수단 클러치 온도", "DPF 포집량", "산화촉매 앞 배기 온도", "앞 왼쪽 타이어 온도", "앞 오른쪽 타이어 온도", "뒤 왼쪽 타이어 온도", "뒤 오른쪽 타이어 온도", "DCT 잔량 후보 A 홀수단", "DCT 잔량 후보 A 짝수단", "DCT 잔량 후보 B 홀수단", "DCT 잔량 후보 B 짝수단", "엔진 경고등", "엔진 가동 시간", "공회전 시간", "주행 시간"};
    public static final String[] UNITS = {"rpm", "°C", "°C", "km/h", "km/h", "km/h", "km/h", "km/h", "", "%", "bar", "G", "G", "°", "°C", "PS", "N·m", "bar", "psi", "psi", "psi", "psi", "", "", "", "%", "V", "°/s", "", "°C", "", "km", "%", "°/s", "", "", "%", "L/h", "km", "km/L", "%", "%", "°", "", "Nm", "", "", "", "", "", "", "", "", "", "", "", "", "%", "°C", "%", "kPa", "°C", "", "%", "km", "km", "°C", "°C", "g", "°C", "°C", "°C", "°C", "°C", "%", "%", "%", "%", "", "", "", ""};
    /** The labels a driver picks from (gauges, graphs): only the signals this build receives. */
    public static final String[] CORE_LABELS=java.util.Arrays.copyOf(LABELS,CORE);
    public static final long STALE_MS = 60000; // UI freshness threshold, not an OEM timeout.
    public static final long HISTORY_MS=300000,HISTORY_STEP_MS=250;
    private final History[] history=new History[LABELS.length];
    /** Display orientation follows the driver's reported left/right convention. Raw SAS stays unchanged. */
    public static float displayValue(int signal,float raw){return signal==STEERING?(raw==0?0:-raw):raw;}
    public static final class Series {
        public final long[] times;
        public final float[] values;
        public final boolean[] valid,breakBefore;
        Series(int n){times=new long[n];values=new float[n];valid=new boolean[n];breakBefore=new boolean[n];}
        public int size(){return times.length;}
    }
    private static final class History {
        private static final int CAPACITY=(int)(HISTORY_MS/HISTORY_STEP_MS)+1;
        final long[] times=new long[CAPACITY];
        final float[] values=new float[CAPACITY];
        final boolean[] valid=new boolean[CAPACITY],breakBefore=new boolean[CAPACITY];
        int start,count;
        void add(float value,boolean ok,long now){
            ok=ok&&!Float.isNaN(value)&&!Float.isInfinite(value);
            int last=(start+count-1+CAPACITY)%CAPACITY;
            if(count>0&&now<times[last])return;
            if(count>0&&now/HISTORY_STEP_MS==times[last]/HISTORY_STEP_MS){
                breakBefore[last]|=!ok||!valid[last];times[last]=now;values[last]=value;valid[last]=ok;return;
            }
            boolean gap=count>0&&!valid[last];
            int at=(start+count)%CAPACITY;
            if(count==CAPACITY){start=(start+1)%CAPACITY;}else count++;
            times[at]=now;values[at]=value;valid[at]=ok;breakBefore[at]=gap||!ok;
            while(count>0&&times[start]<now-HISTORY_MS){start=(start+1)%CAPACITY;count--;}
        }
        Series snapshot(long now,long window){
            int n=0;long left=now-window;
            for(int i=0;i<count;i++){int at=(start+i)%CAPACITY;if(times[at]>=left&&times[at]<=now)n++;}
            Series result=new Series(n);n=0;
            for(int i=0;i<count;i++){int at=(start+i)%CAPACITY;if(times[at]<left||times[at]>now)continue;
                result.times[n]=times[at];result.values[n]=values[at];result.valid[n]=valid[at];result.breakBefore[n]=breakBefore[at];n++;
            }
            return result;
        }
    }
    public synchronized Series history(int signal,long now,long window){
        if(signal<0||signal>=LABELS.length||window<=0||window>HISTORY_MS)throw new IllegalArgumentException("history range");
        return history[signal]==null?new Series(0):history[signal].snapshot(now,window);
    }
    private int speedUnit=-1, rearView, rawSpeed;
    private final long[] counts=new long[LABELS.length];
    private final float[] values = new float[LABELS.length];
    private final long[] times = new long[LABELS.length];
    private final boolean[] valid = new boolean[LABELS.length], changed = new boolean[LABELS.length], seen = new boolean[LABELS.length];
    private long epoch,ptVersion,gfVersion,ptTime,gfTime,stdTime,lastComputedPt=-1,lastComputedGf=-1;
    private boolean ptValid,gfValid,stdSeen;
    private float inputRpm,inputTqi,inputAcor,inputStd,inputBoost,inputAmbient,friction;
    private float observedPreviousRpm=-1;
    private boolean observedTorqueValid;
    private final ThreadLocal<Calculation> calculation=new ThreadLocal<Calculation>();
    private static final class Calculation {
        long epoch,pt,gf,time;
        float power,torque,boost;
        boolean valid;
    }
    public static final class Sample {
        public final float value;
        public final long ageMs;
        public final boolean valid, changed, seen, stale;
        public Sample(float v, boolean ok, boolean diff, boolean received, boolean old,long age) {
            ageMs=age;value=v; valid=ok; changed=diff; seen=received; stale=old;
        }
    }
    private void put(int i, float v, boolean ok, long now) {
        changed[i] = ok && valid[i] && seen[i] && now-times[i] <= STALE_MS && values[i] != v;
        counts[i]++; values[i]=v; valid[i]=ok; seen[i]=true; times[i]=now;
        if(history[i]==null)history[i]=new History();
        history[i].add(v,ok,now);
    }
    public synchronized void clear() {
        speedUnit=-1; rearView=0; rawSpeed=0;
        epoch++;ptValid=false;gfValid=false;stdSeen=false;lastComputedPt=-1;lastComputedGf=-1;
        calculation.remove();
        observedPreviousRpm=-1;observedTorqueValid=false;
        for(int i=0;i<LABELS.length;i++) { counts[i]=0; valid[i]=false; changed[i]=false; seen[i]=false; history[i]=null; }
    }
    public synchronized Sample sample(int i, long now) {
        boolean stale=seen[i] && now-times[i]>=STALE_MS;
        return new Sample(values[i], valid[i] && !stale, changed[i] && !stale, seen[i], stale,seen[i]?Math.max(0,now-times[i]):Long.MAX_VALUE);
    }
    public synchronized Sample[] snapshot(long now) {
        Sample[] result=new Sample[LABELS.length];
        for(int i=0;i<result.length;i++)result[i]=sample(i,now);
        return result;
    }
    public static String gearLabel(int raw) { return raw>=1&&raw<=6?String.valueOf(raw):"—"; }
    /** GEAR_SELECTOR value: 0=P 1=R 2=N 3=D 4=M. Never received on the Veloster N JSN path. */
    public static String selectorLabel(int code) { return code==0?"P":code==1?"R":code==2?"N":code==3?"D":code==4?"M":"—"; }
    /** Gear box text: the EMS gear number (6MT), else a dash. */
    public static String gearText(float gear,float sel,VehicleProfile prof) {
        boolean known=!Float.isNaN(gear)&&gear>=1&&gear<=prof.topGear;
        int code=Float.isNaN(sel)?-1:(int)sel;
        return code==0?"P":code==1?"R":code==2?"N":known?gearLabel((int)gear):"—";
    }
    public static String shiftHint(float hint) { return Float.isNaN(hint)||hint==0?"":(hint>0?"▲":"▼")+(int)Math.abs(hint); }
    public static String driveModeLabel(int code) { return DriveModes.label(code); }
    /** The stock N-mode service's drive mode (DriveMode.mCurrentDrviceMode). */
    public synchronized void driveMode(int oem,long now){int c=DriveModes.fromOem(oem);put(DRIVE_MODE,c,c>=0,now);}
    /** Text for enumerated signals; null means a plain number. */
    public static String label(int signal,float value) {
        if(signal==8)return gearLabel((int)value);
        if(signal==TCS||signal==ESC)return value>0?"OFF":"ON";
        if(signal==ABS)return value>0?"WARN":"OK";
        if(signal==GEAR_SELECTOR)return selectorLabel((int)value);
        if(signal==SHIFT_HINT)return value==0?"—":shiftHint(value);
        if(signal==DRIVE_MODE)return driveModeLabel((int)value);
        if(signal==PARK_BRAKE||signal==BRAKE_SWITCH||signal==TURN_LEFT||signal==TURN_RIGHT||signal==HEAD_LAMP||signal==TAIL_LAMP||signal==HIGH_BEAM)return value>0?"ON":"OFF";
        if(signal==DOOR_DRIVER||signal==DOOR_PASSENGER||signal==TRUNK||signal==HOOD)return value>0?"OPEN":"CLOSED";
        if(signal==CLUTCH)return value>0?"IN":"OUT";
        if(signal==MIL)return value!=0?"WARN":"OK";
        if(signal>=ENGINE_RUNTIME&&signal<=ENGINE_DRIVE)return timeLabel(value);
        return null;
    }
    public static String timeLabel(float sec){
        if(Float.isNaN(sec)||sec<0)return "—";
        int t=(int)sec,h=t/3600,m=(t%3600)/60,s=t%60;
        return h>0?String.format(java.util.Locale.US,"%d:%02d:%02d",h,m,s):String.format(java.util.Locale.US,"%d:%02d",m,s);
    }
    /** String.format(Locale.US,f,x) without a lone minus on a value that rounds to zero; cached (it runs every frame). */
    private static final class Fmt{final String f,s;final int bits;Fmt(String f,int bits,String s){this.f=f;this.bits=bits;this.s=s;}}
    private static final Fmt[] FMT=new Fmt[1024];
    public static String format(String f,float x){
        int bits=Float.floatToIntBits(x),at=(f.hashCode()*31+bits*0x9E3779B1>>>12)&1023;Fmt e=FMT[at];
        if(e!=null&&e.bits==bits&&(e.f==f||e.f.equals(f)))return e.s;
        String s=format0(f,x);FMT[at]=new Fmt(f,bits,s);return s;
    }
    private static String format0(String f,float x){
        String s=String.format(java.util.Locale.US,f,x);
        if(s.length()<2||s.charAt(0)!='-'||s.charAt(1)!='0')return s;
        int i=2;if(i<s.length()&&s.charAt(i)=='.')for(i++;i<s.length()&&s.charAt(i)=='0';i++);
        if(i<s.length()&&s.charAt(i)>='0'&&s.charAt(i)<='9')return s;
        return (f.contains("%+")?"+":"")+s.substring(1);
    }
    /** 6MT downshift targets from a ratio table: none is trusted for the Veloster N gearbox yet, so NaN (RevMatch uses
     *  the ratios learned while driving instead). */
    public static float[] mtDownshift(float kmh,float gear){return new float[]{Float.NaN,Float.NaN,Float.NaN};}
    public static float mtRpmIn(float kmh,int gear){return Float.NaN;}
    public static int mtGear(float rpm,float kmh){return 0;}
    /** Selector code to trust, or -1 (a 6MT has no lever). */
    public static int selector(float sel,float gear,VehicleProfile prof){return prof.trans==VehicleProfile.Trans.MT||Float.isNaN(sel)?-1:(int)sel;}
    /** Factor that pulls a G point back onto a circle of radius edge g. */
    public static float pinG(float lat,float lon,float edge){float g=(float)Math.hypot(lat,lon);return g>edge?edge/g:1;}
    /** The Veloster N JSN this build targets: petrol, six-speed manual (the owner's car). */
    public VehicleProfile profile(){return VehicleProfile.VELOSTER_N;}
    public synchronized void speed(int raw,long now) {
        rawSpeed=raw;
        put(7,speedUnit==1?raw*1.609344f:raw,speedUnit>=0 && raw>=0 && raw<511,now);
    }
    public synchronized void units(byte[] b,long now) {
        speedUnit=b!=null && b.length>=3?(b[0]&1):-1;
        int unit=b!=null && b.length>=3?(b[0]>>1)&3:0;
        int raw=b!=null && b.length>=3?b[unit==2?2:1]&255:255;
        // JSN OEM ranges and offsets; the dashboard consistently shows Celsius.
        boolean ok=(unit==1&&raw<=100)||(unit==2&&raw<=180);
        put(AMBIENT,unit==2?(raw-40-32)*5f/9:raw-40,ok,now);
        if(seen[7]) {
            // Unit events must not refresh the timestamp or reception count of old speed.
            values[7]=speedUnit==1?rawSpeed*1.609344f:rawSpeed;
            valid[7]=speedUnit>=0 && rawSpeed>=0 && rawSpeed<511;
            changed[7]=false;
        }
    }
    public synchronized String diagnostics(long now) {
        StringBuilder s=new StringBuilder("unit=").append(speedUnit);
        for(int i=0;i<CORE;i++)s.append(" ").append(i).append(":").append(counts[i])
            .append("/").append(seen[i]?now-times[i]:-1).append("ms/").append(sample(i,now).valid?"valid":"unknown");
        return s.toString();
    }
    /** The rear camera is showing (PGS view 1 or 3). */
    public synchronized boolean rearCamera(){return rearView==1||rearView==3;}
    public synchronized void rearView(int view) {
        rearView=view;
        if(view==1 || view==3) { valid[8]=false;changed[8]=false; }
        // A clear event alone cannot resurrect a previously received gear.
    }
    public synchronized void additional(byte[] b,long now) {
        // Only the verified 19 / 40-byte layouts carry gear, pedal, brake and steering. Any other reply (the i30 PD build
        // saw a 41-byte one whenever an N-mode app asked, which blanked its gear every 2 s; sung9155/i30-pd-dashboard
        // v1.17.3) is ignored rather than taken as "no data": the last values stay and go stale on their own. A null
        // reply still clears them.
        if(b!=null && b.length!=19 && b.length!=40)return;
        pressures(b,now);
        if(b==null){   // an explicit empty reply still means "no data"
            for(int i=8;i<=10;i++)put(i,0,false,now);
            put(STEERING,0,false,now);
            return;
        }
        int gear=b[4];
        int throttle=((b[6]&255)<<8)|(b[7]&255);
        int brake=b.length==19?b[0]:((b[0]&255)<<8)|(b[1]&255);
        put(8,gear,gear>=1 && gear<=6 && b[5]!=1 && rearView!=1 && rearView!=3,now); // Verified manual forward gears only.
        put(9,Math.min(100,throttle*0.3906f),throttle!=65535,now);
        put(10,b.length==19?brake:brake*0.1f,brake>=0 && brake!=65535,now);
        if(b.length==40){
            int angle=((b[20]&255)<<8)|(b[21]&255);
            put(STEERING,(short)angle*0.1f,true,now);
        }
    }
    /** Only new IL frames from the verified JSN HSCAN bridge. No cached getter polling. */
    public synchronized void hscanJsn(int cid,int funcLow,byte[] b,long now){
        if(cid!=0xc7||(funcLow&0x80)!=0||b==null)return;
        if(funcLow==0x07&&b.length==8){
            int soc=b[2]&255,voltage=((b[4]&63)<<8)|(b[3]&255);
            boolean sensorOk=(b[6]&0x80)==0&&(b[7]&0x80)==0;
            put(BAT_SOC,soc,sensorOk&&soc<=100,now);
            put(BAT_VOLTAGE,voltage*0.001f+6,sensorOk&&voltage<=12000,now);
        }else if(funcLow==0x53&&b.length==5){
            int raw=b[2]&255;put(STEERING_SPEED,raw*4,raw<255,now);
        }else if(funcLow==0x23&&b.length==8){
            // JSN/Manual DBC call CAN 0x547 EMS15; AiM names its byte2 ECU_IAT.
            // 255 is conservatively withheld until validity is checked on this car.
            int raw=b[2]&255;put(INTAKE,raw*0.75f-48,raw<255,now);
        }else if(funcLow==0x6e&&b.length==8){
            // WHL_SPD11: LE14 per wheel; upper bits carry counters/checksum, not speed.
            for(int i=0;i<4;i++){
                int raw=(b[2*i]&255)|((b[2*i+1]&63)<<8);
                put(3+i,raw*0.03125f,raw<16352,now);
            }
        }else if(funcLow==0x5a&&b.length==8){
            boolean allFF=true;for(byte v:b)if(v!=(byte)255)allFF=false;
            put(TCS,(b[1]>>1)&1,!allFF,now);put(ESC,(b[1]>>6)&1,!allFF,now);put(ABS,(b[1]>>2)&1,!allFF,now);
        }
    }
    /** Explicit CAN-ID input only. No MICOM function has been mapped to e-LSD yet.
     * Retains the candidate scaled number without inventing a percent/current/torque unit. */
    public synchronized void canFrame(int canId,byte[] b,long now){
        if(canId!=0x3f0||b==null||b.length!=8)return;
        int raw=(b[1]&255)|((b[2]&255)<<8);
        put(ELSD,raw*0.1f,raw!=65535,now);
    }
    private void pressures(byte[] b,long now){
        // Captured NAdd40: status22, unit23, FL/FR/RL/RR BE16 at28..35.
        // Only status1/unit0 has been corroborated against this vehicle's cluster (psi).
        boolean known=b!=null&&b.length==40&&b[22]==1&&b[23]==0;
        for(int i=0;i<4;i++){
            int raw=known?((b[28+2*i]&255)<<8)|(b[29+2*i]&255):511;
            // Conservative display bound, not an asserted OEM validity enum.
            put(TPMS_FL+i,raw,known&&raw<=100,now);
        }
    }
    public synchronized void rpm(byte[] b, long now) {
        ptVersion++;ptTime=now;
        if(b==null || b.length<8) { ptValid=false;put(0,0,false,now);invalidateComputed(now);return; }
        int raw=((b[1]&255)<<8)|(b[2]&255);
        // OEM onPowerTorqueTurboBoostBitMasking: unsigned big-endian * 0.25.
        // 0xffff conservatively suppressed; protocol validity is not fully documented.
        put(0,raw*0.25f,raw!=65535,now);
        ptValid=raw!=65535;
        inputRpm=raw*0.25f;inputAcor=(b[0]&255)*0.390625f;inputTqi=(b[3]&255)*0.390625f;
        inputAmbient=(float)(((b[4]>>2)&31)*10.731613+458.98);
        inputBoost=(float)((((b[5]&255)<<4)|((b[6]>>4)&15))*1.322);
        if((b[4]&3)==3){
            inputStd=(((b[6]&15)<<2)|((b[7]>>6)&3))*10f;stdTime=now;stdSeen=inputStd>0;
        }
        if(!ptValid)invalidateComputed(now);
    }
    public synchronized void temperatures(byte[] b, long now) {
        for(int i=0;i<2;i++) {
            int raw=b!=null && b.length>=2 ? b[i]&255 : 255;
            // OEM onOilCoolantTemperatureBitMasking explicitly rejects 0xff.
            put(i+1,raw*0.75f-(i==0?40:48),raw<255,now);
        }
    }
    public synchronized void gforce(byte[] b,long now) {
        gfVersion++;gfTime=now;gfValid=b!=null&&b.length>=8;
        if(gfValid)friction=(b[3]&255)*0.390625f;else invalidateComputed(now);
        for(int axis=0;axis<2;axis++) {
            if(b==null || b.length<8) { put(11+axis,0,false,now);continue; }
            int offset=axis==0?0:4;
            int raw=((b[offset]&255)<<8)|(b[offset+1]&255);
            int status=(b[2]>>(axis*4))&15;
            float value=(float)(raw*0.000127465-4.17677312);
            if(axis==1)value=-value; // OEM longitudinal sign inversion.
            // Status 0 is observed in retained normal data; other states remain unknown.
            put(11+axis,value,status==0 && raw!=65535,now);
        }
    }
    private void invalidateComputed(long now){for(int i=POWER;i<=BOOST;i++)put(i,0,false,now);}
    private static boolean close(float a,float b){return !Float.isNaN(a)&&!Float.isInfinite(a)&&Math.abs(a-b)<=Math.max(0.0001f,Math.abs(b)*0.00001f);}
    /** Called at the start of OEM calculation, capturing the actual input generation. */
    public synchronized void beginComputed(float rpm,float tqi,float acor,float standard,float boost,float ambient){
        Calculation c=new Calculation();c.epoch=epoch;c.pt=ptVersion;c.gf=gfVersion;
        c.time=Math.min(ptTime,Math.min(gfTime,stdTime));
        c.valid=ptValid&&gfValid&&stdSeen&&close(rpm,inputRpm)&&close(tqi,inputTqi)&&close(acor,inputAcor)
            &&close(standard,inputStd)&&close(boost,inputBoost)&&close(ambient,inputAmbient);
        float rawTorque=(acor-friction)*standard/100;
        c.power=Math.max(0,(float)(rawTorque*((double)rpm/716.2/9.81)));
        c.torque=rawTorque<5.5f?0:rawTorque;
        float pressure=(float)(((double)boost-1.333*ambient)/10);
        c.boost=pressure<5.5f?0:pressure;
        calculation.set(c);
    }
    /** Receives instantaneous validated OEM outputs, never the peak/total log fields. */
    public synchronized void computed(float power,float torque,float boostKpa){
        Calculation c=calculation.get();calculation.remove();
        if(c==null||!c.valid||c.epoch!=epoch||c.pt!=ptVersion||c.gf!=gfVersion
                ||(c.pt==lastComputedPt&&c.gf==lastComputedGf))return;
        // Matching power also checks the friction sample used by the native calculation.
        // OEM startup/low-RPM policy may independently zero torque and boost.
        if(!close(power,c.power)||!(torque==0||close(torque,c.torque))||!(boostKpa==0||close(boostKpa,c.boost)))return;
        lastComputedPt=c.pt;lastComputedGf=c.gf;
        put(POWER,power,true,c.time);put(TORQUE,torque,true,c.time);put(BOOST,boostKpa/100,true,c.time);
    }
    /** Standalone calculation from received source frames, using the captured OEM formula.
     * No cached OEM app fields, peak values or synthetic receipts are involved. */
    public synchronized void calculateObserved(long now){
        if(!ptValid||!gfValid||!stdSeen||now-ptTime>=STALE_MS||now-gfTime>=STALE_MS||now-stdTime>=STALE_MS)return;
        if(ptVersion==lastComputedPt&&gfVersion==lastComputedGf)return;
        beginComputed(inputRpm,inputTqi,inputAcor,inputStd,inputBoost,inputAmbient);
        Calculation c=calculation.get();if(c==null||!c.valid)return;
        float torque=c.torque,boost=c.boost;
        if(inputRpm!=observedPreviousRpm){
            observedTorqueValid=!(inputRpm<=300||(observedPreviousRpm<inputRpm&&inputRpm<400));
        }
        if(!observedTorqueValid){torque=0;boost=0;}
        observedPreviousRpm=inputRpm;
        computed(c.power,torque,boost);
    }
    public synchronized void wheels(byte[] b, long now) {
        for(int i=0;i<4;i++) {
            int raw=b!=null && b.length>=8 ? ((b[2*i]&255)<<8)|(b[2*i+1]&255) : 65535;
            float speed=raw*0.03125f;
            // OEM uses this factor, then rejects aggregate speeds >=511. Apply
            // that range conservatively per wheel; this is not the cluster speed.
            put(i+3,speed,speed<511,now);
        }
    }
}
