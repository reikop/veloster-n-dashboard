package com.veloster.vehicle;

import com.veloster.i18n.UiText;
import com.veloster.i18n.LanguageSettings;
import android.app.Activity;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;
import java.lang.ref.WeakReference;

/** Opt-in in-activity panel; keeps the original content and all OEM controls alive. */
public final class VehicleDash extends View implements Runnable {
    private static final Telemetry DATA=new Telemetry();
    private static WeakReference<VehicleDash> current=new WeakReference<VehicleDash>(null);
    private final Activity activity;
    private final PitRenderer renderer=new PitRenderer();
    /** One track for the process: the floating window reads it while the dashboard is away (floatMusic). */
    private static final MediaState mediaState=new MediaState();
    private static final MediaReceiver mediaReceiver=new MediaReceiver(mediaState);
    private final ArtworkLoader artwork;
    private final GaugeLayout gaugeLayout=new GaugeLayout();
    private final GaugeEditor gaugeEditor;
    private final GraphLayout graphLayout=new GraphLayout();
    private final GraphEditor graphEditor;
    private final OverviewLayout overviewLayout=new OverviewLayout();
    private int overviewDown=-1;
    private boolean cluster, media, gauges, graphs, performance, taxiPage, records, touching;
    /** Page on screen (DashboardPage id). */
    private int page=DashboardPage.OVERVIEW;
    /** 설정 (gear): open, and the pane shown. */
    private boolean settings;
    private final SettingsPanel settingsPanel=new SettingsPanel();
    private final SettingsPanel.State settingsState=new SettingsPanel.State();
    private TilePicker cellPicker;
    /** This drive's acceleration runs and peaks (기록 page); process-wide so a drive survives the view being recreated. */
    private static final DriveStats STATS=new DriveStats();
    private static long statsSpeedAt=Long.MIN_VALUE;
    /** Start-up welcome sweep (PitRenderer.drawIntro). */
    private long introStart;private static final long INTRO_MS=1500;
    /** Which hold-to-edit tile set (Tiles) the page has, or -1: F1's two lines (not with the music panel) and the race page's four. */
    private int tileLayout(){return page==DashboardPage.F1&&!media?Tiles.F1:page==DashboardPage.RACE?Tiles.RACE:-1;}
    /** Hold a tile: pick what it shows from the grouped list, or put its default back; kept per layout and tile. */
    private final Runnable tileHold=new Runnable(){public void run(){
        final int l=tileLayout(),i=l<0?-1:Tiles.hit(l,downX,downY);
        if(i<0||!touching||blocked||settings||getVisibility()!=VISIBLE)return;
        touching=false;int cur=Tiles.signal(l,i,DATA.profile());
        cellPicker.open(UiText.t("표시 항목")+" · "+UiText.t(Tiles.label(cur)),DATA.profile(),true,true,cur,new TilePicker.Pick(){public void pick(int sig){
            Tiles.picked[l][i]=sig;ui().edit().putInt(Tiles.key(l,i),sig).apply();invalidate();}});
    }};
    /** 주행 모드 options: accent follows the mode; a mode change opens that mode's screen (DriveModes.onMode). */
    private boolean modeColor,modeScreen;
    private final int[] modeTargets=DriveModes.DEFAULT.clone();
    private int lastMode=-1,modeBack=-1,colorMode=-2;
    private void modeTick(long now){
        Telemetry.Sample m=DATA.sample(Telemetry.DRIVE_MODE,now);int mode=m.valid?(int)m.value:-1;
        int want=modeColor?mode:-1;if(want!=colorMode){colorMode=want;Theme.mode(want);invalidate();}
        if(mode!=lastMode){
            if(modeScreen&&!settings){int[] r=DriveModes.onMode(lastMode,mode,page,modeBack,modeTargets);modeBack=r[1];if(r[0]!=page)setPage(r[0],true);}
            if(mode>=0)lastMode=mode;
        }
    }
    private static String modeKey(int k){return "mode_screen_"+DriveModes.NAMES[k].toLowerCase(java.util.Locale.US).replace(' ','_');}
    // ---- 사운드 (from the i30 PD build): engine sound, shift beep and speed chime on the car's alarm path (front speakers) ----
    private static EngineSound sound;private static ShiftBeep beep;
    private static boolean soundOn,beepOn,floatSound,floatBeep;private static int soundEngine,soundExhaust,soundVol=70,beepSound,beepVol=70,fxVol=70;
    private static final ShiftGuide.Beep beepEdge=new ShiftGuide.Beep();private static final SpeedChime chime=new SpeedChime();
    private static int savedShift=ShiftGuide.DEFAULT;
    /** The floating window is up (FloatService); with 플로팅 중 the sounds keep going while the dashboard is away. */
    public static volatile boolean floatUp;
    private static void loadSound(android.content.Context c,android.content.SharedPreferences p){
        if(sound==null){sound=new EngineSound(c.getApplicationContext(),DATA);beep=new ShiftBeep(c);}
        try{soundOn=p.getBoolean("engine_sound",false);beepOn=p.getBoolean("shift_beep",false);floatSound=p.getBoolean("float_sound",false);floatBeep=p.getBoolean("float_beep",false);
            soundEngine=clampI(p.getInt("engine_sound_engine",0),0,EngineSynth.engines()-1);soundExhaust=clampI(p.getInt("engine_sound_exhaust",0),0,EngineSynth.exhausts()-1);
            soundVol=clampI(p.getInt("engine_sound_volume",70),10,200);beepSound=clampI(p.getInt("shift_beep_sound",0),0,SettingsPanel.BEEP_SOUNDS.length-1);
            beepVol=clampI(p.getInt("shift_beep_vol",70),0,200);fxVol=clampI(p.getInt("fx_vol",70),0,200);
            SpeedChime.kmh=p.getInt("speed_chime_kmh",0);SpeedChime.sound=clampI(p.getInt("speed_chime_sound",0),0,SpeedChime.NAMES.length-1);
            savedShift=ShiftGuide.clamp(p.getInt("shift_target",ShiftGuide.DEFAULT));}
        catch(ClassCastException e){soundOn=beepOn=false;}
        sound.volume(soundVol);sound.voice(soundEngine,soundExhaust);
    }
    private static int clampI(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}
    private static void saveSound(android.content.SharedPreferences p){
        p.edit().putBoolean("engine_sound",soundOn).putBoolean("shift_beep",beepOn).putBoolean("float_sound",floatSound).putBoolean("float_beep",floatBeep)
         .putInt("engine_sound_engine",soundEngine).putInt("engine_sound_exhaust",soundExhaust).putInt("engine_sound_volume",soundVol)
         .putInt("shift_beep_sound",beepSound).putInt("shift_beep_vol",beepVol).putInt("fx_vol",fxVol).putInt("speed_chime_kmh",SpeedChime.kmh).putInt("speed_chime_sound",SpeedChime.sound).apply();
    }
    /** The engine sound stream when the beep should ride on it (the car's alarm path carries one AudioTrack), else null. */
    private static EngineSound mix(){return soundOn&&sound!=null?sound:null;}
    /** Every tick, dashboard or floating window: the engine sound on or off, the shift beep on the cue's edge, the speed chime. */
    private static void soundTick(boolean dashboardUp){
        if(sound==null)return;long now=SystemClock.elapsedRealtime();
        {int ck=CassetteCluster.cue;if(ck!=0){CassetteCluster.cue=0;   // a key of the 카세트 screen: latched, released or tapped
            if(dashboardUp&&CassetteCluster.clickSound)beep.playCassette(mix(),ck,fxVol/100f);}}
        sound.play(soundOn&&(dashboardUp||floatUp&&floatSound)?0:-1);
        if(!dashboardUp&&!(floatUp&&floatBeep))return;
        Telemetry.Sample[] s=DATA.snapshot(now);
        if(beepEdge.fire(ShiftGuide.evaluate(s,savedShift).shift,now)&&beepOn)beep.play(mix(),beepSound,beepVol/100f);
        Telemetry.Sample sp=DATA.sample(7,now);
        if(SpeedChime.kmh>0&&chime.due(sp.valid?sp.value:Float.NaN,now)&&beep.playChime(mix(),SpeedChime.sound,fxVol/100f))chime.rang(now);
    }
    /** Long press on the tab strip opens 설정 → 화면. */
    private final Runnable stripHold=new Runnable(){public void run(){
        if(touching&&!blocked&&getVisibility()==VISIBLE&&DashboardPage.inStrip(downX,downY)){touching=false;tabDrag=false;openSettings(SettingsPanel.SCREENS);}   // a finger that moved 16 px already cleared touching
    }};
    /** Header tab strip scroll (px) and its drag state. */
    private float tabScroll, tabDownScroll;
    private boolean tabDrag;
    /** Taxi meter: process-wide so a ride survives the view being recreated; advanced from received speed. */
    private static final TaxiMeter TAXI=new TaxiMeter();
    private long taxiTick=-1;
    /** Paper receipt shown after paying, until tapped. */
    private boolean taxiReceipt;
    /** Taxi button held down (TaxiRenderer.slot), for the pressed look. */
    private int taxiPressed=-1;
    private float downX,downY;
    private long lastDiagnostic;
    private long lastPolicyCheck;
    private boolean resumed;
    private long entryRequestUntil;
    private int shiftTarget;
    private final java.util.concurrent.atomic.AtomicBoolean mediaPending=new java.util.concurrent.atomic.AtomicBoolean();
    private Button entry;
    private boolean blocked;
    private boolean standalone;
    private final Runnable editHold=new Runnable(){public void run(){
        if((gauges||graphs)&&touching&&!blocked&&getVisibility()==VISIBLE){
            int slot=graphs?graphLayout.hit(downX,downY):gaugeEditor.hit(downX,downY);
            if(slot>=0){touching=false;if(graphs)graphEditor.edit(slot);else gaugeEditor.edit(slot);}
        }
    }};
    private VehicleDash(Activity a) { super(a); activity=a; artwork=new ArtworkLoader(a,this);
        cellPicker=new TilePicker(a);
        {final android.content.SharedPreferences rp=a.getApplicationContext().getSharedPreferences("pit_vehicle_records",0);   // 기록실
         Records.store(new Records.Store(){public String get(String k){return rp.getString(k,"");}
             public void put(String k,String l){rp.edit().putString(k,l).apply();}public void clear(){rp.edit().clear().apply();}});}
        try{settingsState.version=a.getPackageManager().getPackageInfo(a.getPackageName(),0).versionName;}catch(Exception e){/* no name shown */}
        settingsState.backupPath=backupFile().getPath();
        try{android.content.SharedPreferences p=a.getSharedPreferences("pit_vehicle_ui",0);
            Theme.select(p.getString("theme","n"));
            DashboardPage.configure(DashboardPage.parse(p.getString("dashboard_order",null)),p.getInt("dashboard_hidden",0));
            loadFloat(p);loadSound(a,p);
            modeColor=p.getBoolean("mode_color",false);modeScreen=p.getBoolean("mode_screen",false);
            for(int k=0;k<modeTargets.length;k++)modeTargets[k]=DriveModes.validTarget(p.getInt(modeKey(k),DriveModes.DEFAULT[k]));
            for(int l=0;l<Tiles.KEYS.length;l++)for(int i=0;i<Tiles.picked[l].length;i++)Tiles.picked[l][i]=p.getInt(Tiles.key(l,i),-1);}
        catch(ClassCastException e){Theme.select("n");DashboardPage.configure(null,0);}
        Fonts.load(a);
        gaugeEditor=new GaugeEditor(a,gaugeLayout,new Runnable(){public void run(){invalidate();}});
        graphEditor=new GraphEditor(a,graphLayout,new Runnable(){public void run(){invalidate();}});
        for(int i=0;i<Telemetry.LABELS.length;i++)try{
            overviewLayout.style(i,a.getSharedPreferences("pit_vehicle_ui",0).getInt("overview_style_"+i,2));
        }catch(IllegalArgumentException e){/* Retain default for an invalid saved style. */}
        catch(ClassCastException e){/* Retain default for incompatible preferences. */}
        try{setPage(a.getSharedPreferences("pit_vehicle_ui",0).getInt("dashboard_page",DashboardPage.OVERVIEW),false);}catch(ClassCastException ignored){setPage(DashboardPage.OVERVIEW,false);}
        loadTaxiHorse(a);
        try{TAXI.setArea(a.getSharedPreferences("pit_vehicle_ui",0).getString("taxi_area","서울"));}catch(ClassCastException ignored){}   // the last area until the GPS places the car
        try{if(TAXI.region()!=a.getSharedPreferences("pit_vehicle_ui",0).getInt("taxi_region",TaxiMeter.KOREA))TAXI.setRegion(a.getSharedPreferences("pit_vehicle_ui",0).getInt("taxi_region",TaxiMeter.KOREA));}catch(ClassCastException ignored){}
        shiftTarget=ShiftGuide.clamp(a.getSharedPreferences("pit_vehicle_ui",0).getInt("shift_target",ShiftGuide.DEFAULT)); setVisibility(GONE); }
    // ---- 네온 드라이브 (apps/neondrive) as a page: its own view over the dashboard, back button returns ----
    private com.veloster.neondrive.NeonDrivePage neon;
    private int neonBack=DashboardPage.OVERVIEW;
    private void showNeon(){
        if(!standalone)return;
        try{
            if(neon==null)neon=new com.veloster.neondrive.NeonDrivePage(activity,new Runnable(){public void run(){exitNeon();}});
            android.view.View v=neon.view();
            if(v.getParent()==null){FrameLayout parent=(FrameLayout)activity.findViewById(android.R.id.content);parent.addView(v,new FrameLayout.LayoutParams(-1,-1));}
            v.bringToFront();neon.start();
        }catch(RuntimeException e){com.veloster.diagnostics.ErrorLog.record("neon_page",e);android.util.Log.e("VehicleDash","Neon Drive page unavailable",e);}
    }
    private void hideNeon(){
        if(neon==null)return;
        neon.stop();android.view.View v=neon.view();
        if(v.getParent()!=null)((ViewGroup)v.getParent()).removeView(v);
    }
    private void exitNeon(){hideNeon();setPage(neonBack==DashboardPage.NEON?DashboardPage.OVERVIEW:neonBack,true);invalidate();}
    // ---- 내 차 (com.veloster.car3d): the 3D car with lamps and steering, its own GL view over the dashboard ----
    private com.veloster.car3d.CarPage car;
    private int carBack=DashboardPage.OVERVIEW;
    private void showCar(){
        if(!standalone)return;
        try{
            if(car==null)car=new com.veloster.car3d.CarPage(activity,new Runnable(){public void run(){exitCar();}});
            android.view.View v=car.view();
            if(v.getParent()==null){FrameLayout parent=(FrameLayout)activity.findViewById(android.R.id.content);parent.addView(v,new FrameLayout.LayoutParams(-1,-1));}
            v.bringToFront();car.start();
        }catch(RuntimeException e){com.veloster.diagnostics.ErrorLog.record("car3d_page",e);android.util.Log.e("VehicleDash","3D car page unavailable",e);}
    }
    private void hideCar(){
        if(car==null)return;
        car.stop();android.view.View v=car.view();
        if(v.getParent()!=null)((ViewGroup)v.getParent()).removeView(v);
    }
    private void exitCar(){hideCar();setPage(carBack==DashboardPage.CAR3D?DashboardPage.OVERVIEW:carBack,true);invalidate();}
    /** The back key: leaves Neon Drive or closes the settings; false lets the activity go back as usual. */
    public static boolean back(Activity a){
        VehicleDash d=current.get();if(d==null||d.activity!=a)return false;
        if(d.page==DashboardPage.NEON&&d.neon!=null&&d.neon.view().getParent()!=null){d.exitNeon();return true;}
        if(d.page==DashboardPage.CAR3D&&d.car!=null&&d.car.view().getParent()!=null){d.exitCar();return true;}
        if(d.settings){d.closeSettings();return true;}
        return false;
    }
    private void setPage(int value,boolean save){
        int before=page;
        page=DashboardPage.resolve(value);FloatPanel.screen=page;
        if(page==DashboardPage.NEON){if(before!=DashboardPage.NEON)neonBack=before;if(resumed||standalone&&getVisibility()==VISIBLE)showNeon();}
        else hideNeon();
        if(page==DashboardPage.CAR3D){if(before!=DashboardPage.CAR3D)carBack=before;if(resumed||standalone&&getVisibility()==VISIBLE)showCar();}
        else hideCar();
        cluster=page==DashboardPage.F1;gauges=page==DashboardPage.GAUGES;graphs=page==DashboardPage.GRAPHS;performance=page==DashboardPage.PERFORMANCE;taxiPage=page==DashboardPage.TAXI;records=page==DashboardPage.RECORDS;
        tabScroll=DashboardPage.reveal(page,tabScroll);   // keep the selected tab in view
        if(save)activity.getSharedPreferences("pit_vehicle_ui",0).edit().putInt("dashboard_page",page).apply();
    }
    public static void attach(Activity a) {
        try {
            FrameLayout parent=(FrameLayout)a.findViewById(android.R.id.content);
            VehicleDash old=current.get();
            if(old!=null && old.activity==a) return;
            final VehicleDash dash=new VehicleDash(a);
            parent.addView(dash,new FrameLayout.LayoutParams(-1,-1));
            Button button=new Button(a); dash.entry=button; button.setText(UiText.t("N 계기판"));
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-2,48,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
            parent.addView(button,lp);
            button.setOnClickListener(new OnClickListener() { public void onClick(View v) {
                if(!dash.blocked) dash.showDashboard();
            }});
            current=new WeakReference<VehicleDash>(dash);
            android.util.Log.i("VehicleDash","attached N/PIT vehicle panel");
        } catch(RuntimeException e) { android.util.Log.e("VehicleDash","attach failed",e); }
    }
    /** Independent APK entry: no OEM view or disappearing entry button is involved. */
    public static void attachStandalone(Activity a){
        FrameLayout parent=(FrameLayout)a.findViewById(android.R.id.content);
        VehicleDash dash=new VehicleDash(a);dash.standalone=true;dash.blocked=false;
        current=new WeakReference<VehicleDash>(dash);
        parent.addView(dash,new FrameLayout.LayoutParams(-1,-1));dash.setVisibility(VISIBLE);
        dash.introStart=SystemClock.elapsedRealtime();   // the welcome sweep on each start of the dashboard
        dash.backupOffer();
    }
    public static void resumeStandalone(Activity a){
        VehicleDash d=current.get();if(d==null||d.activity!=a)return;
        d.resumed=true;d.artwork.open();d.mediaReceiver.start(a.getApplicationContext());
        d.policy(false,"standalone-resume");d.setVisibility(VISIBLE);
        if(d.page==DashboardPage.NEON)d.showNeon();
        if(d.page==DashboardPage.CAR3D)d.showCar();
    }
    public static void pauseStandalone(Activity a){
        VehicleDash d=current.get();if(d==null||d.activity!=a)return;
        d.resumed=false;d.touching=false;d.removeCallbacks(d.editHold);d.removeCallbacks(d.stripHold);backupSave(a);d.hideNeon();d.hideCar();
        d.gaugeEditor.dismiss();d.graphEditor.dismiss();d.cellPicker.dismiss();if(!FloatPanel.musicShown()||FloatPanel.shape==FloatPanel.OFF)d.mediaReceiver.stop();d.artwork.close();
    }
    private void showDashboard(){setVisibility(VISIBLE);if(entry!=null)entry.setVisibility(GONE);bringToFront();}
    private void close() { entryRequestUntil=0;removeCallbacks(editHold);touching=false;gaugeEditor.dismiss();graphEditor.dismiss();setVisibility(GONE); if(entry!=null) entry.setVisibility(blocked?GONE:VISIBLE); }
    public static void consumeEntryIntent(Activity a){entryIntent(a,a.getIntent());}
    /** Explicit opt-in only; consume once and require a live unrestricted OEM response. */
    public static void entryIntent(Activity a,android.content.Intent intent){
        if(intent==null)return;
        final String key="com.veloster.vehicle.OPEN_DASHBOARD";
        try{
            if(!intent.getBooleanExtra(key,false))return;
            intent.removeExtra(key);
            VehicleDash d=current.get();
            if(d==null||d.activity!=a)return;
            d.entryRequestUntil=SystemClock.elapsedRealtime()+3000;
            d.tryRequestedEntry();
        }catch(RuntimeException e){android.util.Log.e("VehicleDash","invalid dashboard entry request",e);}
    }
    private void tryRequestedEntry(){
        if(entryRequestUntil==0||!resumed)return;
        int state=RegulationState.read(activity);
        if(state==1){entryRequestUntil=0;policy(true,"entry-service");Toast.makeText(activity,UiText.t("현재 차량 표시 제한으로 N 계기판을 열 수 없습니다."),Toast.LENGTH_SHORT).show();return;}
        if(SystemClock.elapsedRealtime()>=entryRequestUntil){entryRequestUntil=0;Toast.makeText(activity,UiText.t("차량 표시 상태를 확인하지 못했습니다. N 계기판 버튼으로 다시 열어주세요."),Toast.LENGTH_SHORT).show();return;}
        if(state==0){entryRequestUntil=0;policy(false,"entry-service");showDashboard();}
    }
    public static void hide(Activity a) {
        VehicleDash d=current.get(); if(d!=null && d.activity==a) d.close();
    }
    public static void resume(Activity a,boolean cachedRestriction){
        attach(a);VehicleDash d=current.get();
        if(d!=null&&d.activity==a){d.resumed=true;d.lastPolicyCheck=SystemClock.elapsedRealtime();regulation(a,cachedRestriction);d.tryRequestedEntry();android.util.Log.i("VehicleDash","entry resume cachedRestriction="+cachedRestriction);}
    }
    public static void pause(Activity a){VehicleDash d=current.get();if(d!=null&&d.activity==a){d.resumed=false;d.close();}}
    private void policy(boolean restricted,String source){
        // The independent instrument display is not gated by the OEM parking-brake UI policy.
        if(standalone){blocked=false;setVisibility(VISIBLE);invalidate();return;}
        if(blocked!=restricted)android.util.Log.i("VehicleDash","OEM regulation="+restricted+" source="+source);
        blocked=restricted;
        if(blocked)close();
        else if(entry!=null){entry.setVisibility(getVisibility()==VISIBLE?GONE:VISIBLE);if(getVisibility()!=VISIBLE)entry.bringToFront();}
    }
    public static void regulation(final Activity a,final boolean blocked) {
        a.runOnUiThread(new Runnable(){public void run(){VehicleDash d=current.get();if(d==null||d.activity!=a||d.standalone)return;
            int status=RegulationState.read(a);d.policy(status<0?blocked:status==1,status<0?"cached":"service");
        }});
    }
    public static void regulation(boolean blocked){
        VehicleDash d=current.get();if(d!=null)regulation(d.activity,blocked);
    }
    public static void ignition(boolean on) { DATA.clear(); }
    public static void rpm(byte[] data) { DATA.rpm(data,SystemClock.elapsedRealtime()); }
    public static void beginComputed(float rpm,float tqi,float acor,float standard,float boost,float ambient) { DATA.beginComputed(rpm,tqi,acor,standard,boost,ambient); }
    public static void computed(float power,float torque,float boostKpa) { DATA.computed(power,torque,boostKpa); }
    public static void calculateObserved(){DATA.calculateObserved(SystemClock.elapsedRealtime());}
    public static void temperatures(byte[] data) { DATA.temperatures(data,SystemClock.elapsedRealtime()); }
    public static void gforce(byte[] data) { DATA.gforce(data,SystemClock.elapsedRealtime()); }
    /** The G sensor for other screens (내 차): {longitudinal (+ accelerating), lateral} in g, NaN when not received. */
    public static float[] gSensor(){long now=SystemClock.elapsedRealtime();Telemetry.Sample x=DATA.sample(11,now),y=DATA.sample(12,now);
        return new float[]{y.valid?y.value:Float.NaN,x.valid?x.value:Float.NaN};}
    public static void wheels(byte[] data) { DATA.wheels(data,SystemClock.elapsedRealtime()); }
    public static void rearView(int data) { DATA.rearView(data); }
    public static void units(byte[] data) { DATA.units(data,SystemClock.elapsedRealtime()); }
    public static void speed(int data) { DATA.speed(data,SystemClock.elapsedRealtime()); }
    public static void additional(byte[] data) { DATA.additional(data,SystemClock.elapsedRealtime()); }
    /** The N-mode service's drive mode (DriveMode.mCurrentDrviceMode; 15 = invalid). The stock app does not gate on mIsValid,
     *  so neither does this; it is logged with each change for the in-car check. */
    public static void driveMode(byte isValid,byte mode){
        int v=mode&255;if(v!=lastOemMode){lastOemMode=v;android.util.Log.i("VehicleDash","drive mode oem="+v+" valid="+isValid);}
        DATA.driveMode(v,SystemClock.elapsedRealtime());
    }
    private static volatile int lastOemMode=-1;
    public static void hscanJsn(int cid,int funcLow,byte[] data){DATA.hscanJsn(cid,funcLow,data,SystemClock.elapsedRealtime());}
    public static void hscanJsn(int cid,int funcLow,byte[] data,long receivedAt){DATA.hscanJsn(cid,funcLow,data,receivedAt);}
    protected void onAttachedToWindow() { super.onAttachedToWindow(); Gps.start(activity.getApplicationContext()); artwork.open();mediaReceiver.start(activity.getApplicationContext()); post(this); }
    protected void onDetachedFromWindow() { Gps.stop();removeCallbacks(this);removeCallbacks(editHold);removeCallbacks(stripHold);touching=false;gaugeEditor.dismiss();graphEditor.dismiss();cellPicker.dismiss();if(floatArt==null)mediaReceiver.stop();artwork.close();super.onDetachedFromWindow(); }
    public void run() {
        long delay=100;
        long tick=SystemClock.elapsedRealtime();
        if(resumed&&!standalone&&tick-lastPolicyCheck>=1000){lastPolicyCheck=tick;int status=RegulationState.read(activity);if(status>=0)policy(status==1,"service");}
        tryRequestedEntry();
        // The taxi meter runs whenever a ride is on, whatever page is shown.
        int taxiHour=java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        if(tick-taxiPlaceAt>=1000){taxiPlaceAt=tick;taxiPlace();}
        if(TAXI.autoStart(taxiSpeed(tick),taxiHour)){taxiReceipt=false;taxiTick=tick;}   // the car moved off: a new trip
        if(taxiTick>=0)TAXI.update(taxiSpeed(tick),tick-taxiTick,taxiHour);
        taxiTick=tick;
        feedStats(tick);
        modeTick(tick);
        savedShift=shiftTarget;soundTick(resumed&&getVisibility()==VISIBLE);
        if(getVisibility()==VISIBLE) {
            invalidate();long now=SystemClock.elapsedRealtime();
            if(graphs)delay=250;
            else if((page==DashboardPage.OVERVIEW||page==DashboardPage.F1&&!media||page==DashboardPage.GAUGES)&&!settings&&mediaState.snapshot(now).notice)delay=16;
            else if(page==DashboardPage.RACE||page==DashboardPage.LAMBDA||page==DashboardPage.TACHO||PitRenderer.animated(page))delay=50;   // shift lights blink, needles glide
            if(introStart>0&&now-introStart<INTRO_MS)delay=16;
            if(now-lastDiagnostic>=5000) { lastDiagnostic=now;android.util.Log.i("VehicleDash",DATA.diagnostics(now)); }
        }
        postDelayed(this,delay);
    }
    protected void onDraw(Canvas c) {
        long now=SystemClock.elapsedRealtime();
        MediaState.Snapshot track=mediaState.snapshot(now);artwork.request(track);
        renderer.setPage(page);renderer.setRecords(STATS);renderer.setSettingsOpen(settings);
        renderer.setPerformancePage(performance);
        renderer.setTabScroll(tabScroll);
        renderer.setTaxi(taxiPage?TAXI:null,taxiSpeed(now),java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),taxiReceipt,taxiPressed);
        renderer.setArtwork(artwork.bitmap());
        renderer.setGaugeLayout(gauges?gaugeLayout:null);
        renderer.setGraphLayout(graphs?graphLayout:null);
        renderer.setOverviewLayout(overviewLayout);
        renderer.draw(c,getWidth(),getHeight(),cluster,DATA,now,shiftTarget,media,track);
        if(introStart>0&&now-introStart<INTRO_MS)renderer.drawIntro(c,getWidth(),getHeight(),(now-introStart)/(float)INTRO_MS);
        if(settings){
            settingsState.page=page;if(settingsState.cat==SettingsPanel.DRIVE)syncModeState();
            int saved=c.save();c.scale(getWidth()/800f,getHeight()/480f);
            settingsPanel.draw(c,settingsState,now);
            c.restoreToCount(saved);
        }
    }
    private long taxiPlaceAt;
    /** Once a second: the GPS business area for the meter's table and out-of-area surcharge; a new area table is saved. */
    private void taxiPlace(){
        int z=-1;
        if(GpsFix.fresh()&&TaxiZones.ready())z=TaxiZones.at(GpsFix.lat,GpsFix.lon);   // the areas decode off the UI thread first (Gps.start)
        if(TAXI.place(z,z>=0?TaxiZones.tariff(z):null))activity.getSharedPreferences("pit_vehicle_ui",0).edit().putString("taxi_area",TAXI.area()).apply();
    }
    /** Number of gallop frames in res/drawable-nodpi/taxi_horse.png. */
    private static final int TAXI_HORSE_FRAMES=8;
    /** Loads the taxi horse strip by name, so shared code needs no app R class; missing art leaves no horse. */
    private void loadTaxiHorse(Activity a){
        try{
            int id=a.getResources().getIdentifier("taxi_horse","drawable",a.getPackageName());
            if(id!=0){android.graphics.BitmapFactory.Options o=new android.graphics.BitmapFactory.Options();o.inScaled=false;
                renderer.setTaxiHorse(android.graphics.BitmapFactory.decodeResource(a.getResources(),id,o),TAXI_HORSE_FRAMES);}
        }catch(RuntimeException e){android.util.Log.w("VehicleDash","Taxi horse art unavailable",e);}
        catch(OutOfMemoryError e){android.util.Log.w("VehicleDash","Taxi horse art too large");}
    }
    /** Received vehicle speed in km/h, NaN when unknown or stale. */
    /**
     * Speed for the taxi meter, km/h: the mean of the two rear wheel speeds (WHL_SPD11, about every 40 ms and
     * 0.03 km/h steps; the rear wheels are not driven, so they do not spin). The cluster speed only arrives
     * on change and is invalid until the unit is known, so it is only the fallback; NaN pauses the meter.
     */
    private static float taxiSpeed(long now){
        Telemetry.Sample rl=DATA.sample(5,now),rr=DATA.sample(6,now);
        if(rl.valid&&rr.valid)return (rl.value+rr.value)/2f;
        Telemetry.Sample s=DATA.sample(7,now);return s.valid?s.value:Float.NaN;
    }
    private void requestMedia(final int command){
        if(blocked||!mediaPending.compareAndSet(false,true))return;
        final MediaState.Snapshot displayed=mediaState.snapshot(SystemClock.elapsedRealtime());
        final int displayedMode=MediaState.modeForSource(displayed.source);
        new Thread(new Runnable(){public void run(){
            String error=null;
            try{if(!(command==0?MediaControl.toggle(activity,displayedMode,displayed.playState):MediaControl.step(activity,command>0,displayedMode)))error=UiText.t("현재 재생 소스에서 음악을 제어할 수 없습니다");}
            catch(Exception e){android.util.Log.e("VehicleDash","Media control unavailable",e);error=UiText.t("음악 제어 요청을 보낼 수 없습니다");}
            final String message=error;
            activity.runOnUiThread(new Runnable(){public void run(){
                mediaPending.set(false);
                if(message!=null&&!activity.isFinishing())Toast.makeText(activity,message,Toast.LENGTH_SHORT).show();
            }});
        }},"NMode-track-control").start();
    }
    private boolean overviewCovered(float y){return y>=347&&mediaState.snapshot(SystemClock.elapsedRealtime()).notice;}
    public boolean onTouchEvent(MotionEvent event) {
        float x=event.getX()*800/getWidth(), y=event.getY()*480/getHeight();
        if(event.getAction()==MotionEvent.ACTION_DOWN) {
            removeCallbacks(editHold);removeCallbacks(stripHold);removeCallbacks(tileHold);touching=true;downX=x;downY=y;
            tabDrag=DashboardPage.inStrip(x,y)&&DashboardPage.maxScroll()>0;tabDownScroll=tabScroll;
            if(!blocked&&DashboardPage.inStrip(x,y))postDelayed(stripHold,700);
            if(settings&&y>52){taxiPressed=-1;overviewDown=-1;return true;}
            taxiPressed=taxiPage&&!blocked&&!taxiReceipt?TaxiRenderer.slot(x,y,TAXI.region()):-1;if(taxiPressed>=0)invalidate();
            overviewDown=page==DashboardPage.OVERVIEW&&!blocked&&!overviewCovered(y)?overviewLayout.hit(x,y):-1;
            if(!blocked&&((gauges&&gaugeEditor.hit(x,y)>=0)||(graphs&&graphLayout.hit(x,y)>=0)))postDelayed(editHold,600);
            if(!blocked&&!settings&&tileLayout()>=0&&Tiles.hit(tileLayout(),x,y)>=0)postDelayed(tileHold,600);
            return true;
        }
        if(event.getAction()==MotionEvent.ACTION_MOVE&&tabDrag){
            float next=DashboardPage.clampScroll(tabDownScroll-(x-downX));
            if(next!=tabScroll){tabScroll=next;invalidate();}
        }
        if(event.getAction()==MotionEvent.ACTION_MOVE){if(Math.abs(x-downX)>=16||Math.abs(y-downY)>=16){touching=false;removeCallbacks(editHold);removeCallbacks(stripHold);removeCallbacks(tileHold);if(taxiPressed>=0){taxiPressed=-1;invalidate();}}return true;}
        if(event.getAction()==MotionEvent.ACTION_CANCEL) { touching=false;tabDrag=false;removeCallbacks(editHold);removeCallbacks(stripHold);if(taxiPressed>=0){taxiPressed=-1;invalidate();}return true; }
        if(event.getAction()!=MotionEvent.ACTION_UP)return true;
        removeCallbacks(editHold);removeCallbacks(stripHold);removeCallbacks(tileHold);tabDrag=false;
        boolean tap=touching && Math.abs(x-downX)<16 && Math.abs(y-downY)<16;
        touching=false;
        if(settings&&tap&&!blocked&&y>52&&downY>52){
            int a=SettingsPanel.hit(settingsState.cat,x,y);
            if(a!=SettingsPanel.NONE&&a==SettingsPanel.hit(settingsState.cat,downX,downY)){playSoundEffect(android.view.SoundEffectConstants.CLICK);settingsAction(a);invalidate();}
            return true;
        }
        if(settings&&y>52)return true;
        if(tap&&!blocked&&DashboardPage.gear(x,y)&&DashboardPage.gear(downX,downY)){if(settings)closeSettings();else openSettings(settingsState.cat);return true;}
        if(tap&&!blocked&&y>=DashboardPage.BOTTOM+6&&renderer.screenTap(page,x,y)){   // 드래그 준비 key, cassette keys, circuit list
            int kc=CassetteCluster.consumeKeyCmd();if(kc==-1||kc==1)requestMedia(kc);else if(kc!=0)requestMedia(0);   // ▶ / ■ / ❚❚: play-pause (no stop or mute here)
            invalidate();return true;}
        if(tap&&!blocked&&records&&y>=RecordsRenderer.RUN_TOP&&y<=RecordsRenderer.RUN_BOTTOM&&downY>=RecordsRenderer.RUN_TOP&&downY<=RecordsRenderer.RUN_BOTTOM){RecordsDialog.show(activity);return true;}
        if(standalone&&tap&&x>=666&&x<=782&&downX>=666&&downX<=782&&y>=8&&y<=46&&downY>=8&&downY<=46){activity.finish();return true;}
        if(tap&&!blocked&&page==DashboardPage.OVERVIEW&&overviewDown>=0&&overviewLayout.hit(x,y)==overviewDown&&!overviewCovered(y)){
            overviewLayout.cycle(overviewDown);
            activity.getSharedPreferences("pit_vehicle_ui",0).edit().putInt("overview_style_"+overviewDown,overviewLayout.style(overviewDown)).apply();
            invalidate();return true;
        }
        if(taxiPressed>=0){taxiPressed=-1;invalidate();}
        if(tap&&!blocked&&taxiPage&&taxiReceipt&&y>52&&downY>52){taxiReceipt=false;playSoundEffect(android.view.SoundEffectConstants.CLICK);invalidate();return true;}   // any tap closes the receipt
        if(tap&&!blocked&&taxiPage){
            int region=TAXI.region(),button=TaxiRenderer.hit(x,y,region);
            if(button>=0&&button==TaxiRenderer.hit(downX,downY,region)&&TaxiRenderer.enabled(button,TAXI)){
                playSoundEffect(android.view.SoundEffectConstants.CLICK);
                int hour=java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
                if(button==TaxiRenderer.VACANT_BUTTON){TAXI.reset();taxiReceipt=false;}
                else if(button==TaxiRenderer.HIRE_BUTTON){if(TAXI.state()!=TaxiMeter.HIRED){TAXI.start(hour);taxiReceipt=false;}}
                else if(button==TaxiRenderer.SURCHARGE_BUTTON)TAXI.toggleOutOfCity();
                else if(button==TaxiRenderer.PAY_BUTTON){if(TAXI.state()==TaxiMeter.HIRED)TAXI.stop();if(TAXI.state()==TaxiMeter.PAID&&TaxiRenderer.payShowsReceipt(region))taxiReceipt=true;}   // pressing again re-opens the receipt
                else if(button==TaxiRenderer.RECEIPT_BUTTON){if(TAXI.state()==TaxiMeter.PAID)taxiReceipt=true;}
                else if(button==TaxiRenderer.REGION_BUTTON&&TAXI.setRegion(TAXI.region()==TaxiMeter.JAPAN?TaxiMeter.KOREA:TaxiMeter.JAPAN)){
                    taxiReceipt=false;activity.getSharedPreferences("pit_vehicle_ui",0).edit().putInt("taxi_region",TAXI.region()).apply();
                }
                invalidate();return true;
            }
        }
        if(tap&&!blocked&&gauges&&x>=650&&x<=788&&downX>=650&&downX<=788&&y>=54&&y<=78&&downY>=54&&downY<=78){gaugeEditor.toggleGrid();return true;}
        if(tap&&!blocked&&graphs&&y>=54&&y<=79&&downY>=54&&downY<=79){
            if(x>=628&&x<=702&&downX>=628&&downX<=702)graphEditor.window(60000);
            else if(x>=708&&x<=782&&downX>=708&&downX<=782)graphEditor.window(300000);
            return true;
        }
        if(tap&&!blocked&&cluster&&!gauges){
            boolean small=x>=18&&x<=70&&downX>=18&&downX<=70&&y>=436&&y<=480&&downY>=436&&downY<=480;
            boolean large=media&&x>=518&&x<=758&&downX>=518&&downX<=758&&y>=72&&y<=312&&downY>=72&&downY<=312;
            if(small||large){media=!media;mediaState.dismissNotice();invalidate();return true;}
        }
        if(tap && !blocked && cluster && y>=452 && y<=480 && downY>=452 && downY<=480) {
            if(media && x>=494 && x<=580 && downX>=494 && downX<=580){requestMedia(-1);return true;}
            if(media && x>=588 && x<=686 && downX>=588 && downX<=686){requestMedia(0);return true;}
            if(media && x>=694 && x<=782 && downX>=694 && downX<=782){requestMedia(1);return true;}
            int next=shiftTarget;
            float minus=media?210:548,plus=media?440:740;
            if(x>=minus && x<=minus+42 && downX>=minus && downX<=minus+42)next-=100;
            if(x>=plus && x<=plus+42 && downX>=plus && downX<=plus+42)next+=100;
            shiftTarget=ShiftGuide.clamp(next);
            activity.getSharedPreferences("pit_vehicle_ui",0).edit().putInt("shift_target",shiftTarget).apply();
            invalidate();return true;
        }
        if(!tap || blocked || y<8 || y>46 || downY<8 || downY>46)return true;
        gaugeEditor.dismiss();graphEditor.dismiss();
        int selected=DashboardPage.tap(downX,downY,x,y,tabScroll);
        if(selected>=0){if(settings)closeSettings();setPage(selected,true);invalidate();}
        else if(x>=PitRenderer.LANGUAGE_X && x<=PitRenderer.LANGUAGE_X+48 && downX>=PitRenderer.LANGUAGE_X && downX<=PitRenderer.LANGUAGE_X+48){LanguageSettings.show(activity,new Runnable(){public void run(){invalidate();}});}
        else if(x>=666 && x<=782 && downX>=666 && downX<=782){if(settings)closeSettings();close();}
        return true;
    }

    // ---- 설정 (gear) ----------------------------------------------------------------------------------------------
    private void syncModeState(){settingsState.modeColor=modeColor;settingsState.modeScreen=modeScreen;System.arraycopy(modeTargets,0,settingsState.modeTargets,0,modeTargets.length);
        Telemetry.Sample m=DATA.sample(Telemetry.DRIVE_MODE,SystemClock.elapsedRealtime());settingsState.modeNow=m.valid?(int)m.value:-1;}
    private void syncSoundState(){SettingsPanel.State st=settingsState;st.engineOn=soundOn;st.beepOn=beepOn;st.floatEngine=floatSound;st.floatBeep=floatBeep;
        st.engine=soundEngine;st.exhaust=soundExhaust;st.engineVol=soundVol;st.beep=beepSound;st.beepVol=beepVol;st.chimeKmh=SpeedChime.kmh;st.chime=SpeedChime.sound;st.fxVol=fxVol;}
    private void openSettings(int cat){syncModeState();syncSoundState();settings=true;settingsState.cat=cat<0||cat>=SettingsPanel.CATS?0:cat;gaugeEditor.dismiss();graphEditor.dismiss();refreshBackupTime();refreshUsb();fontFirstScan();invalidate();}
    private void closeSettings(){settings=false;cellPicker.dismiss();invalidate();}
    /** The back key: true when it closed the open settings. */
    public static boolean closeSettings(Activity a){VehicleDash d=current.get();if(d==null||d.activity!=a||!d.settings)return false;d.closeSettings();return true;}
    private android.content.SharedPreferences ui(){return activity.getSharedPreferences("pit_vehicle_ui",0);}
    private void saveScreens(){ui().edit().putString("dashboard_order",DashboardPage.csv(DashboardPage.order())).putInt("dashboard_hidden",DashboardPage.hidden()).apply();}
    private void settingsAction(int a){
        if(a==SettingsPanel.DONE){closeSettings();return;}
        if(a>=SettingsPanel.CAT&&a<SettingsPanel.CAT+SettingsPanel.CATS){settingsState.cat=a-SettingsPanel.CAT;cellPicker.dismiss();refreshBackupTime();refreshUsb();fontFirstScan();return;}
        if(a>=SettingsPanel.SCREEN_TOGGLE&&a<SettingsPanel.SCREEN_TOGGLE+DashboardPage.COUNT){
            int[] o=DashboardPage.order();int pg=o[a-SettingsPanel.SCREEN_TOGGLE];int h=DashboardPage.toggle(DashboardPage.hidden(),pg);
            if(h==DashboardPage.hidden()){Toast.makeText(activity,UiText.t("화면을 하나 이상 남겨야 합니다"),Toast.LENGTH_SHORT).show();return;}
            DashboardPage.configure(o,h);saveScreens();setPage(page,true);return;}
        if(a>=SettingsPanel.SCREEN_UP&&a<SettingsPanel.SCREEN_UP+DashboardPage.COUNT){DashboardPage.configure(DashboardPage.move(DashboardPage.order(),a-SettingsPanel.SCREEN_UP,-1),DashboardPage.hidden());saveScreens();tabScroll=DashboardPage.reveal(page,tabScroll);return;}
        if(a>=SettingsPanel.SCREEN_DOWN&&a<SettingsPanel.SCREEN_DOWN+DashboardPage.COUNT){DashboardPage.configure(DashboardPage.move(DashboardPage.order(),a-SettingsPanel.SCREEN_DOWN,1),DashboardPage.hidden());saveScreens();tabScroll=DashboardPage.reveal(page,tabScroll);return;}
        if(a>=SettingsPanel.THEME_PICK&&a<SettingsPanel.THEME_PICK+Theme.IDS.length){Theme.select(Theme.IDS[a-SettingsPanel.THEME_PICK]);ui().edit().putString("theme",Theme.current().id).apply();return;}
        if(a>=700&&a<800){floatAction(a);android.content.SharedPreferences.Editor e=ui().edit();saveFloat(e);e.apply();return;}
        if(a>=1100&&a<1200){soundAction(a);saveSound(ui());syncSoundState();return;}
        if(a==SettingsPanel.D_COLOR_ON||a==SettingsPanel.D_COLOR_OFF){modeColor=a==SettingsPanel.D_COLOR_ON;ui().edit().putBoolean("mode_color",modeColor).apply();syncModeState();return;}
        if(a==SettingsPanel.D_SCREEN_ON||a==SettingsPanel.D_SCREEN_OFF){modeScreen=a==SettingsPanel.D_SCREEN_ON;modeBack=-1;ui().edit().putBoolean("mode_screen",modeScreen).apply();syncModeState();return;}
        if(a>=SettingsPanel.D_TARGET&&a<SettingsPanel.D_TARGET+DriveModes.NAMES.length){
            final int k=a-SettingsPanel.D_TARGET;final int[] pages=DashboardPage.shown();String[] names=new String[pages.length+1];int at=0;
            names[0]=UiText.t("원래 화면");for(int i=0;i<pages.length;i++){names[i+1]=UiText.t(DashboardPage.NAMES[pages[i]]);if(pages[i]==modeTargets[k])at=i+1;}
            new android.app.AlertDialog.Builder(activity).setTitle(DriveModes.NAMES[k]).setSingleChoiceItems(names,at,new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface d,int w){
                d.dismiss();modeTargets[k]=w==0?-1:pages[w-1];ui().edit().putInt(modeKey(k),modeTargets[k]).apply();syncModeState();invalidate();}})
                .setNegativeButton(UiText.t("취소"),null).show();return;}
        if(a==SettingsPanel.R_LIST){RecordsDialog.show(activity);return;}
        if(a==SettingsPanel.R_CLEAR){
            new android.app.AlertDialog.Builder(activity).setMessage(UiText.t("저장된 기록을 모두 지울까요?"))
                .setPositiveButton(UiText.t("지우기"),new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface d,int w){Records.clear();invalidate();}})
                .setNegativeButton(UiText.t("취소"),null).show();return;}
        if(a==SettingsPanel.B_SAVE){activity.getSharedPreferences(SettingsBackup.PREFS,0).edit().putBoolean(SettingsBackup.ASKED,true).commit();backupWritten=null;
            backupSave(activity,new Runnable(){public void run(){activity.runOnUiThread(new Runnable(){public void run(){refreshBackupTime();invalidate();
                Toast.makeText(activity,UiText.t(backupFile().isFile()?"설정을 저장했습니다":"설정을 저장하지 못했습니다"),Toast.LENGTH_SHORT).show();}});}});return;}
        if(a==SettingsPanel.U_ON||a==SettingsPanel.U_OFF){usbRequest(a==SettingsPanel.U_ON);return;}
        if(a>=SettingsPanel.FONT_UP&&a<SettingsPanel.FONT_PICK+SettingsPanel.FONT_ROWS){fontAction(a);return;}
        if(a==SettingsPanel.B_RESTORE){
            if(!backupFile().isFile()){Toast.makeText(activity,UiText.t("저장해 둔 설정이 없습니다"),Toast.LENGTH_SHORT).show();return;}
            new android.app.AlertDialog.Builder(activity).setMessage(UiText.t("저장해 둔 설정으로 되돌리고 계기판을 다시 시작할까요?"))
                .setPositiveButton(UiText.t("설정 되돌리기"),new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface d,int w){restoreAndRestart();}})
                .setNegativeButton(UiText.t("취소"),null).show();}
    }
    /** 글꼴 (FontManager): the list is read once when the pane first opens and again on 새로고침; applying asks first,
     *  runs as root off the UI thread and offers the reboot the change needs. */
    private FontManager fontManager;
    private FontManager fonts(){if(fontManager==null)fontManager=new FontManager(activity);return fontManager;}
    private void fontFirstScan(){if(settingsState.cat==SettingsPanel.FONT&&settingsState.fonts==null)fontScan();}
    private void fontScan(){
        final SettingsPanel.State st=settingsState;if(st.fontBusy)return;
        st.fontBusy=true;invalidate();
        FontManager.Entry was=st.fonts!=null&&st.fontPick>=0&&st.fontPick<st.fonts.entries.size()?st.fonts.entries.get(st.fontPick):null;
        final String wasKey=was==null?null:was.key();
        new Thread(new Runnable(){public void run(){FontManager.Snapshot r=null;
            try{r=fonts().scan();}catch(RuntimeException e){android.util.Log.w("VehicleDash","font scan",e);}
            final FontManager.Snapshot result=r;
            activity.runOnUiThread(new Runnable(){public void run(){st.fontBusy=false;
                if(result!=null){
                    int pick=result.current>=0?result.current:0;
                    for(int i=0;i<result.entries.size();i++)if(result.entries.get(i).key().equals(wasKey))pick=i;
                    st.fonts=result;st.fontPick=result.entries.isEmpty()?-1:pick;fontReveal();fontLoadPicked();
                }else Toast.makeText(activity,UiText.t("글꼴 목록을 읽지 못했습니다"),Toast.LENGTH_SHORT).show();
                invalidate();}});
        }},"vn-font-scan").start();
    }
    /** A font inside the app loads (from the APK) when its row is picked; one at a time. */
    private boolean fontLoading;
    private void fontLoadPicked(){
        final SettingsPanel.State st=settingsState;final FontManager.Snapshot snap=st.fonts;
        if(fontLoading||snap==null||st.fontPick<0||st.fontPick>=snap.entries.size())return;
        final FontManager.Entry e=snap.entries.get(st.fontPick);if(!e.needsLoad())return;
        fontLoading=true;
        new Thread(new Runnable(){public void run(){
            try{fonts().load(snap,e);}catch(RuntimeException x){android.util.Log.w("VehicleDash","font load",x);}
            activity.runOnUiThread(new Runnable(){public void run(){fontLoading=false;invalidate();fontLoadPicked();}});
        }},"vn-font-load").start();
    }
    private void fontReveal(){SettingsPanel.State st=settingsState;int n=st.fonts==null?0:st.fonts.entries.size();
        if(st.fontPick>=0&&st.fontPick<st.fontTop)st.fontTop=st.fontPick;
        if(st.fontPick>=st.fontTop+SettingsPanel.FONT_ROWS)st.fontTop=st.fontPick-SettingsPanel.FONT_ROWS+1;
        st.fontTop=Math.max(0,Math.min(st.fontTop,Math.max(0,n-SettingsPanel.FONT_ROWS)));}
    private void fontAction(int a){
        final SettingsPanel.State st=settingsState;int n=st.fonts==null?0:st.fonts.entries.size();
        if(a==SettingsPanel.FONT_SCAN){fontScan();return;}
        if(a==SettingsPanel.FONT_UP){st.fontTop=Math.max(0,st.fontTop-SettingsPanel.FONT_ROWS);return;}
        if(a==SettingsPanel.FONT_DOWN){if(st.fontTop+SettingsPanel.FONT_ROWS<n)st.fontTop+=SettingsPanel.FONT_ROWS;return;}
        if(a>=SettingsPanel.FONT_PICK){int k=st.fontTop+a-SettingsPanel.FONT_PICK;if(k<n){st.fontPick=k;fontLoadPicked();}return;}
        if(st.fontBusy){Toast.makeText(activity,UiText.t("작업 중…"),Toast.LENGTH_SHORT).show();return;}
        if(a==SettingsPanel.FONT_REBOOT){fontRebootAsk(UiText.t("내비를 재부팅할까요? 화면·음악·PC 연결이 잠시 끊깁니다."));return;}
        if(a!=SettingsPanel.FONT_APPLY)return;
        if(st.fontPick<0||st.fontPick>=n){Toast.makeText(activity,UiText.t("왼쪽에서 글꼴을 고르세요"),Toast.LENGTH_SHORT).show();return;}
        final FontManager.Entry e=st.fonts.entries.get(st.fontPick);
        if(!e.usable){Toast.makeText(activity,UiText.t("이 글꼴은 적용할 수 없습니다"),Toast.LENGTH_SHORT).show();return;}
        if(st.fontPick==st.fonts.current){Toast.makeText(activity,UiText.t("이미 설정된 글꼴입니다"),Toast.LENGTH_SHORT).show();return;}
        StringBuilder m=new StringBuilder(e.name).append(UiText.t(" 글꼴을 내비 기본 글꼴로 바꿉니다."))
            .append("\n\n").append(UiText.t("루트 권한으로 시스템 글꼴 설정(/system/etc/system_fonts.xml)을 바꾸며, 재부팅해야 반영됩니다. 처음 바꾸기 전 설정은 /data/local/tmp/vn-fonts에 보관됩니다."));
        if(!e.stock()&&!e.hangul())m.append("\n\n").append(UiText.t("이 글꼴은 한글이 모자라 빠진 글자는 기존 대체 글꼴로 보입니다."));
        if(e.variable())m.append("\n\n").append(UiText.t("가변 글꼴은 기본 굵기 하나만 쓰입니다."));
        new android.app.AlertDialog.Builder(activity).setTitle(UiText.t("글꼴 적용")).setMessage(m.toString())
            .setPositiveButton(UiText.t("적용"),new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface d,int w){fontApply(e);}})
            .setNegativeButton(UiText.t("취소"),null).show();
    }
    private void fontApply(final FontManager.Entry e){
        settingsState.fontBusy=true;invalidate();
        new Thread(new Runnable(){public void run(){String r;boolean ok=false;
            try{r=fonts().apply(e);ok=true;}
            catch(Exception x){android.util.Log.w("VehicleDash","font apply",x);r=UiText.t("글꼴을 바꾸지 못했습니다: ")+(x.getMessage()!=null?x.getMessage():x.getClass().getSimpleName());}
            final String answer=r;final boolean done=ok;
            activity.runOnUiThread(new Runnable(){public void run(){settingsState.fontBusy=false;fontScan();
                if(activity.isFinishing())return;
                if(done)fontRebootAsk(answer);
                else new android.app.AlertDialog.Builder(activity).setMessage(answer).setPositiveButton(UiText.t("확인"),null).show();}});
        }},"vn-font-apply").start();
    }
    private void fontRebootAsk(String message){
        new android.app.AlertDialog.Builder(activity).setMessage(message)
            .setPositiveButton(UiText.t("지금 재부팅"),new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface d,int w){
                new Thread(new Runnable(){public void run(){
                    try{fonts().reboot();}
                    catch(final RuntimeException x){android.util.Log.w("VehicleDash","reboot",x);
                        activity.runOnUiThread(new Runnable(){public void run(){if(!activity.isFinishing())new android.app.AlertDialog.Builder(activity)
                            .setMessage(UiText.t("재부팅하지 못했습니다: ")+x.getClass().getSimpleName()+"\n"+UiText.t("'내 앱' → 강제 재시작으로 재부팅하세요."))
                            .setPositiveButton(UiText.t("확인"),null).show();}});}
                }},"vn-reboot").start();}})
            .setNegativeButton(UiText.t("나중에"),null).show();
    }
    /** 정보 → USB 디버깅: My Apps' OEM request (UsbDebugControl: MiscManager.setSystem 5 / 6), OFF asked first. */
    private boolean usbBusy;
    private void refreshUsb(){settingsState.usbStatus=UsbDebugControl.status();}
    private void usbRequest(final boolean on){
        if(usbBusy)return;
        if(!on){
            new android.app.AlertDialog.Builder(activity).setTitle(UiText.t("USB 디버깅"))
                .setMessage(UiText.t("OFF: 일반 USB 모드로 복귀하며 PC 연결이 끊길 수 있습니다."))
                .setPositiveButton("OFF",new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface d,int w){usbSend(false);}})
                .setNegativeButton(UiText.t("취소"),null).show();
            return;}
        usbSend(true);
    }
    private void usbSend(final boolean on){
        usbBusy=true;
        new Thread(new Runnable(){public void run(){String r;
            try{r=UsbDebugControl.request(activity,on)?UiText.t("USB 디버깅 ")+(on?"ON":"OFF")+UiText.t(" 전환 요청 완료. 실제 PC 연결은 별도로 확인하세요."):UiText.t("USB 모드 전환 요청이 거부되었습니다.");}
            catch(Exception e){r=UiText.t("USB 모드 전환 실패: ")+e.getClass().getSimpleName();}
            catch(LinkageError e){r=UiText.t("USB 모드 전환 실패: ")+e.getClass().getSimpleName();}
            final String answer=r;
            activity.runOnUiThread(new Runnable(){public void run(){usbBusy=false;refreshUsb();invalidate();
                if(!activity.isFinishing())new android.app.AlertDialog.Builder(activity).setMessage(answer).setPositiveButton(UiText.t("확인"),null).show();}});
        }},"vn-usb-mode").start();
    }
    private void soundAction(int a){
        if(a==SettingsPanel.S_ENGINE_ON||a==SettingsPanel.S_ENGINE_OFF)soundOn=a==SettingsPanel.S_ENGINE_ON;
        else if(a==SettingsPanel.S_BEEP_ON||a==SettingsPanel.S_BEEP_OFF)beepOn=a==SettingsPanel.S_BEEP_ON;
        else if(a==SettingsPanel.S_FLOAT_ENGINE)floatSound=!floatSound;
        else if(a==SettingsPanel.S_FLOAT_BEEP)floatBeep=!floatBeep;
        else if(a>=SettingsPanel.S_VOL&&a<SettingsPanel.S_VOL+3)soundVol=step(soundVol,a-SettingsPanel.S_VOL,10,200);
        else if(a>=SettingsPanel.S_BEEP_VOL&&a<SettingsPanel.S_BEEP_VOL+3)beepVol=step(beepVol,a-SettingsPanel.S_BEEP_VOL,0,200);
        else if(a>=SettingsPanel.S_FX_VOL&&a<SettingsPanel.S_FX_VOL+3)fxVol=step(fxVol,a-SettingsPanel.S_FX_VOL,0,200);
        else if(a==SettingsPanel.S_ENGINE)pickSound(UiText.t("엔진"),UiText.list(SettingsPanel.SOUND_ENGINES),soundEngine,0);
        else if(a==SettingsPanel.S_EXHAUST)pickSound(UiText.t("배기"),UiText.list(SettingsPanel.SOUND_EXHAUSTS),soundExhaust,1);
        else if(a==SettingsPanel.S_BEEP_SOUND)pickSound(UiText.t("알림 소리"),UiText.list(SettingsPanel.BEEP_SOUNDS),beepSound,2);
        else if(a==SettingsPanel.S_CHIME_KMH){String[] n=new String[SpeedChime.KMH.length];int at=0;for(int i=0;i<n.length;i++){n[i]=SpeedChime.KMH[i]<=0?UiText.t("끔"):SpeedChime.KMH[i]+" km/h";if(SpeedChime.KMH[i]==SpeedChime.kmh)at=i;}pickSound(UiText.t("과속 차임"),n,at,3);}
        else if(a==SettingsPanel.S_CHIME_SOUND)pickSound(UiText.t("차임 소리"),UiText.list(SpeedChime.NAMES),SpeedChime.sound,4);
        else if(a==SettingsPanel.S_TEST_BEEP&&beep!=null)beep.play(mix(),beepSound,beepVol/100f);
        else if(a==SettingsPanel.S_TEST_CHIME&&beep!=null)beep.playChime(mix(),SpeedChime.sound,fxVol/100f);
        if(sound!=null){sound.volume(soundVol);sound.voice(soundEngine,soundExhaust);}
    }
    /** −, value (default), + chips: 10 % steps. */
    private static int step(int v,int zone,int lo,int hi){return zone==0?Math.max(lo,v-10):zone==2?Math.min(hi,v+10):70;}
    private void pickSound(String title,String[] names,int at,final int which){
        if(activity.isFinishing())return;
        new android.app.AlertDialog.Builder(activity).setTitle(title).setSingleChoiceItems(names,at,new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface d,int w){
            d.dismiss();
            if(which==0)soundEngine=w;else if(which==1)soundExhaust=w;else if(which==2){beepSound=w;if(beep!=null)beep.play(mix(),w,beepVol/100f);}
            else if(which==3)SpeedChime.kmh=SpeedChime.KMH[w];else{SpeedChime.sound=w;if(beep!=null)beep.playChime(mix(),w,fxVol/100f);}
            if(sound!=null)sound.voice(soundEngine,soundExhaust);saveSound(ui());syncSoundState();invalidate();}})
            .setNegativeButton(UiText.t("취소"),null).show();
    }
    private void floatAction(int a){
        boolean off=FloatPanel.shape==FloatPanel.OFF;int shape=off?FloatPanel.lastShape:FloatPanel.shape;
        if(a==SettingsPanel.F_ON){if(off)FloatPanel.shape=FloatPanel.lastShape;}
        else if(a==SettingsPanel.F_OFF){if(!off){FloatPanel.lastShape=FloatPanel.shape;FloatPanel.shape=FloatPanel.OFF;}}
        else if(a>=SettingsPanel.F_PRESET&&a<SettingsPanel.F_PRESET+SettingsPanel.PRESET_NAMES.length)FloatPanel.applyPreset(a-SettingsPanel.F_PRESET);
        else if(a==SettingsPanel.F_SHAPE)pickShape();
        else if(a>=SettingsPanel.F_COUNT&&a<SettingsPanel.F_COUNT+10){int[] cs=FloatPanel.counts(shape);int i=a-SettingsPanel.F_COUNT;if(i<cs.length)FloatPanel.count=cs[i];}
        else if(a>=SettingsPanel.F_SIZE&&a<SettingsPanel.F_SIZE+FloatPanel.SIZE_NAMES.length)FloatPanel.size=a-SettingsPanel.F_SIZE;
        else if(a>=SettingsPanel.F_ALPHA&&a<SettingsPanel.F_ALPHA+FloatPanel.ALPHA_NAMES.length)FloatPanel.alpha=a-SettingsPanel.F_ALPHA;
        else if(a==SettingsPanel.F_FOLLOW){if(!FloatPanel.round(shape))FloatPanel.skin=FloatPanel.AUTO;}
        else if(a==SettingsPanel.F_OWN){if(!FloatPanel.round(shape)&&FloatPanel.skin<0)FloatPanel.setActiveSkin(FloatPanel.skinNow());}
        else if(a>=SettingsPanel.F_DN&&a<SettingsPanel.F_DN+3){if(FloatPanel.skin>=0&&!FloatPanel.round(shape))FloatPanel.dn=a-SettingsPanel.F_DN;}
        else if(a==SettingsPanel.F_SKIN_DAY||a==SettingsPanel.F_SKIN_NIGHT)pickSkin(a==SettingsPanel.F_SKIN_DAY);
        else if(a==SettingsPanel.F_CELLS)pickCells();
        else if(a==SettingsPanel.F_MUSIC){if(shape!=FloatPanel.MUSIC)FloatPanel.musicStrip=!FloatPanel.musicStrip;}
        else if(a==SettingsPanel.F_HIDE)FloatPanel.hideButton=!FloatPanel.hideButton;
        int s2=FloatPanel.shape==FloatPanel.OFF?FloatPanel.lastShape:FloatPanel.shape;FloatPanel.count=FloatPanel.fitCount(s2,FloatPanel.count);
    }
    private void choose(String title,String[] names,int selected,final IntPick pick){
        if(activity.isFinishing())return;
        new android.app.AlertDialog.Builder(activity).setTitle(title).setSingleChoiceItems(names,selected,new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface d,int w){
            d.dismiss();pick.pick(w);android.content.SharedPreferences.Editor e=ui().edit();saveFloat(e);e.apply();invalidate();}})
            .setNegativeButton(UiText.t("취소"),null).show();
    }
    private interface IntPick{void pick(int i);}
    private void pickShape(){
        final int[] forms=FloatPanel.FORMS;String[] names=new String[forms.length];int at=-1;int cur=FloatPanel.shape==FloatPanel.OFF?FloatPanel.lastShape:FloatPanel.shape;
        for(int i=0;i<forms.length;i++){names[i]=UiText.t(FloatPanel.SHAPE_NAMES[forms[i]]);if(forms[i]==cur)at=i;}
        choose(UiText.t("모양"),names,at,new IntPick(){public void pick(int i){int f=forms[i];if(FloatPanel.shape==FloatPanel.OFF)FloatPanel.lastShape=f;else FloatPanel.shape=f;FloatPanel.count=FloatPanel.fitCount(f,FloatPanel.count);}});
    }
    private void pickSkin(final boolean day){
        choose(UiText.t(day?"낮 색감":"밤 색감"),UiText.list(FloatPanel.SKIN_NAMES),day?FloatPanel.skinDay:FloatPanel.skinNight,new IntPick(){public void pick(int i){if(day)FloatPanel.skinDay=i;else FloatPanel.skinNight=i;if(FloatPanel.skin<0)FloatPanel.skin=0;}});
    }
    private void pickCells(){
        int shape=FloatPanel.shape==FloatPanel.OFF?FloatPanel.lastShape:FloatPanel.shape;final int u=FloatPanel.used(shape,FloatPanel.count);
        String[] names=new String[u];VehicleProfile prof=DATA.profile();
        for(int k=0;k<u;k++){int c0=FloatPanel.cells[k];names[k]=(k+1)+UiText.t("번 칸")+" · "+(c0<0?UiText.t("자동")+" ("+UiText.t(FloatPanel.name(FloatPanel.resolve(c0,k,prof)))+")":UiText.t(FloatPanel.name(c0)));}
        new android.app.AlertDialog.Builder(activity).setTitle(UiText.t("칸 내용")).setItems(names,new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface d,final int k){
            cellPicker.open(UiText.t("칸")+" "+(k+1),DATA.profile(),false,true,FloatPanel.cells[k],new TilePicker.Pick(){public void pick(int sig){
                FloatPanel.cells[k]=sig;android.content.SharedPreferences.Editor e=ui().edit();saveFloat(e);e.apply();invalidate();}});
        }}).setNegativeButton(UiText.t("닫기"),null).show();
    }

    // ---- 기록: this drive's runs and peaks, from the rear wheel speed (every ~40 ms; the cluster speed only comes on change) ----
    private static void feedStats(long now){
        Telemetry.Sample rl=DATA.sample(5,now),rr=DATA.sample(6,now),sp=DATA.sample(7,now);
        float speed;long at;
        if(rl.valid&&rr.valid&&rl.ageMs<=1000){speed=(rl.value+rr.value)/2f;at=now-Math.max(rl.ageMs,rr.ageMs);}
        else if(sp.valid&&sp.ageMs<=1000){speed=sp.value;at=now-sp.ageMs;}
        else{speed=Float.NaN;at=statsSpeedAt;}
        if(!Float.isNaN(speed))statsSpeedAt=at;
        Telemetry.Sample rpm=DATA.sample(0,now),boost=DATA.sample(Telemetry.BOOST,now),lat=DATA.sample(11,now),lon=DATA.sample(12,now),cool=DATA.sample(2,now),thr=DATA.sample(9,now);
        STATS.update(now,speed,at,f(rpm),f(boost),f(lat),f(lon),now-lon.ageMs,f(cool),f(thr));
        Telemetry.Sample g=DATA.sample(8,now);STATS.gear(now,g.valid?g.value:Float.NaN,f(rpm),speed);   // learned ratios: 람다 / 타코 downshift targets
    }
    private static float f(Telemetry.Sample s){return s.valid&&s.ageMs<=1000?s.value:Float.NaN;}

    // ---- 설정 백업 (SettingsBackup, from the i30 PD build): a file on the head unit's storage, which 삭제 does not remove ----
    private static volatile String backupWritten;
    private static java.io.File backupFile(){return new java.io.File(new java.io.File(android.os.Environment.getExternalStorageDirectory(),SettingsBackup.DIR),SettingsBackup.FILE);}
    private void refreshBackupTime(){
        try{java.io.File f=backupFile();settingsState.backupTime=f.isFile()?new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",java.util.Locale.US).format(new java.util.Date(f.lastModified())):"";}
        catch(RuntimeException e){settingsState.backupTime="";}
    }
    static void backupSave(android.content.Context context){backupSave(context,null);}
    /** Writes every preference file when something changed, off the main thread. Not before the restore question is
     *  settled (ASKED): a fresh install would overwrite the backup with its defaults. */
    static void backupSave(android.content.Context context,final Runnable done){
        final android.content.Context c=context.getApplicationContext();
        if(!c.getSharedPreferences(SettingsBackup.PREFS,0).getBoolean(SettingsBackup.ASKED,false)){if(done!=null)done.run();return;}
        java.util.Map<String,java.util.Map<String,?>> all=new java.util.TreeMap<String,java.util.Map<String,?>>();
        String[] files=new java.io.File(c.getApplicationInfo().dataDir,"shared_prefs").list();
        if(files!=null)for(String n:files)if(n.endsWith(".xml")){String name=n.substring(0,n.length()-4);all.put(name,c.getSharedPreferences(name,0).getAll());}
        final String text=SettingsBackup.text(all);if(text.equals(backupWritten)){if(done!=null)done.run();return;}
        new Thread(new Runnable(){public void run(){
            try{SettingsBackup.write(backupFile(),text);backupWritten=text;}
            catch(Exception e){com.veloster.diagnostics.ErrorLog.record("settings_backup",e);android.util.Log.w("VehicleDash","settings backup not written: "+e);}
            if(done!=null)done.run();
        }},"SettingsBackup").start();
    }
    private static boolean backupRestore(android.content.Context c){
        try{java.util.Map<String,java.util.Map<String,Object>> all=SettingsBackup.values(SettingsBackup.read(backupFile()));if(all.isEmpty())return false;
            for(java.util.Map.Entry<String,java.util.Map<String,Object>> f:all.entrySet()){
                android.content.SharedPreferences.Editor ed=c.getSharedPreferences(f.getKey(),0).edit().clear();
                for(java.util.Map.Entry<String,Object> v:f.getValue().entrySet()){Object o=v.getValue();
                    if(o instanceof Integer)ed.putInt(v.getKey(),(Integer)o);else if(o instanceof Boolean)ed.putBoolean(v.getKey(),(Boolean)o);
                    else if(o instanceof Float)ed.putFloat(v.getKey(),(Float)o);else if(o instanceof Long)ed.putLong(v.getKey(),(Long)o);else ed.putString(v.getKey(),(String)o);}
                ed.commit();}
            c.getSharedPreferences(SettingsBackup.PREFS,0).edit().putBoolean(SettingsBackup.ASKED,true).commit();return true;
        }catch(Exception e){com.veloster.diagnostics.ErrorLog.record("settings_restore",e);android.util.Log.w("VehicleDash","settings backup not restored: "+e);return false;}
    }
    /** Every component read its settings at start: a new process reads the restored ones. */
    private void restoreAndRestart(){
        if(!backupRestore(activity)){Toast.makeText(activity,UiText.t("저장해 둔 설정을 읽지 못했습니다."),Toast.LENGTH_LONG).show();return;}
        android.content.Intent again=activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
        ((android.app.AlarmManager)activity.getSystemService(android.content.Context.ALARM_SERVICE)).set(android.app.AlarmManager.ELAPSED_REALTIME,SystemClock.elapsedRealtime()+500,
            android.app.PendingIntent.getActivity(activity,0,again,android.app.PendingIntent.FLAG_CANCEL_CURRENT));
        activity.finish();System.exit(0);
    }
    /** A fresh install (ASKED not set) that finds a backup asks once whether to put it back; with none there is nothing to ask. */
    private void backupOffer(){
        final android.content.SharedPreferences p=activity.getSharedPreferences(SettingsBackup.PREFS,0);
        if(p.getBoolean(SettingsBackup.ASKED,false))return;
        long at=0;try{java.io.File f=backupFile();if(f.isFile()&&f.length()>0)at=f.lastModified();}catch(RuntimeException e){/* no storage */}
        if(at==0){p.edit().putBoolean(SettingsBackup.ASKED,true).apply();return;}
        String when=new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",java.util.Locale.US).format(new java.util.Date(at));
        new android.app.AlertDialog.Builder(activity).setTitle(UiText.t("저장해 둔 설정이 있습니다"))
            .setMessage(String.format(UiText.t("전에 쓰던 화면 배치 · 테마 · 플로팅 창 설정입니다. %s 저장"),when))
            .setPositiveButton(UiText.t("설정 되돌리기"),new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface d,int w){restoreAndRestart();}})
            .setNegativeButton(UiText.t("새로 시작"),new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface d,int w){p.edit().putBoolean(SettingsBackup.ASKED,true).apply();}}).show();
    }

    // ---- 플로팅 창 (FloatPanel, from the i30 PD build): choices kept in pit_vehicle_ui, read by FloatService too ----------
    public static void loadFloat(android.content.SharedPreferences p){
        try{int shape=p.getInt("float_shape",FloatPanel.OFF),size=p.getInt("float_size",1);
            FloatPanel.shape=shape>=FloatPanel.OFF&&shape<=FloatPanel.LAST_SHAPE?shape:FloatPanel.OFF;FloatPanel.size=Math.max(0,Math.min(FloatPanel.SIZES.length-1,size));
            int n=p.getInt("float_count",4);FloatPanel.count=n>=1&&n<=FloatPanel.MAX_CELLS?n:4;FloatPanel.hideButton=p.getBoolean("float_hide_button",false);FloatPanel.musicStrip=p.getBoolean("float_music_strip",false);
            int last=p.getInt("float_last_shape",FloatPanel.CARD);FloatPanel.lastShape=last>=FloatPanel.CARD&&last<=FloatPanel.LAST_SHAPE?last:FloatPanel.CARD;
            FloatPanel.count=FloatPanel.fitCount(FloatPanel.shape==FloatPanel.OFF?FloatPanel.lastShape:FloatPanel.shape,FloatPanel.count);
            int al=p.getInt("float_alpha",1),skn=p.getInt("float_skin",FloatPanel.AUTO),dn=p.getInt("float_dn",DayNight.AUTO),sd=p.getInt("float_skin_day",FloatSkin.WHITE),sn=p.getInt("float_skin_night",0);
            FloatPanel.alpha=al>=0&&al<FloatPanel.ALPHA_NAMES.length?al:1;
            FloatPanel.skin=skn<0||skn>=FloatPanel.skinCount()?FloatPanel.AUTO:0;
            FloatPanel.dn=dn>=DayNight.AUTO&&dn<=DayNight.NIGHT?dn:DayNight.AUTO;
            FloatPanel.skinDay=sd>=0&&sd<FloatPanel.skinCount()?sd:FloatSkin.WHITE;FloatPanel.skinNight=sn>=0&&sn<FloatPanel.skinCount()?sn:0;
            FloatPanel.corner=FloatPanel.CORNER_THEME;FloatPanel.color=FloatPanel.COLOR_THEME;
            FloatPanel.screen=DashboardPage.normalize(p.getInt("dashboard_page",DashboardPage.OVERVIEW));
            FloatPanel.shiftTarget=p.getInt("shift_target",0);
            if(current.get()==null)Theme.select(p.getString("theme","n"));   // the window started at boot, before the dashboard
            for(int k=0;k<FloatPanel.MAX_CELLS;k++){int v=p.getInt("float_cell_"+k,-1);FloatPanel.cells[k]=v>=0&&v<Telemetry.CORE?v:FloatPanel.pseudo(v)?v:-1;int g=p.getInt("float_graph_"+k,0);FloatPanel.graphs[k]=g>=0&&g<FloatPanel.GRAPH_NAMES.length?g:0;}}
        catch(ClassCastException e){FloatPanel.shape=FloatPanel.OFF;}
    }
    private static void saveFloat(android.content.SharedPreferences.Editor e){
        e.putInt("float_shape",FloatPanel.shape).putInt("float_count",FloatPanel.count).putInt("float_size",FloatPanel.size).putBoolean("float_hide_button",FloatPanel.hideButton).putBoolean("float_music_strip",FloatPanel.musicStrip)
         .putInt("float_last_shape",FloatPanel.lastShape).putInt("float_alpha",FloatPanel.alpha).putInt("float_skin",FloatPanel.skin)
         .putInt("float_dn",FloatPanel.dn).putInt("float_skin_day",FloatPanel.skinDay).putInt("float_skin_night",FloatPanel.skinNight);
        for(int k=0;k<FloatPanel.MAX_CELLS;k++)e.putInt("float_cell_"+k,FloatPanel.cells[k]).putInt("float_graph_"+k,FloatPanel.graphs[k]);
    }
    private static final Telemetry.Sample[] FLOAT_Q=new Telemetry.Sample[Telemetry.LABELS.length];
    private static final Telemetry.Sample NONE_SAMPLE=new Telemetry.Sample(Float.NaN,false,false,false,false,Long.MAX_VALUE);
    /** Only the signals the floating window's n cells show, in an array indexed by signal that is reused (main thread only). */
    public static Telemetry.Sample[] floatSamples(int n){long now=SystemClock.elapsedRealtime();VehicleProfile prof=DATA.profile();
        if(FLOAT_Q[FLOAT_Q.length-1]==null)java.util.Arrays.fill(FLOAT_Q,NONE_SAMPLE);
        for(int k=0;k<n;k++){int sig=FloatPanel.signal(k,prof);
            if(FloatPanel.quad(sig))for(int w=0;w<4;w++){int s=FloatPanel.quadSig(sig,w);FLOAT_Q[s]=DATA.sample(s,now);}
            else if(sig>=0&&sig<FLOAT_Q.length)FLOAT_Q[sig]=DATA.sample(sig,now);}
        FLOAT_Q[0]=DATA.sample(0,now);FLOAT_Q[7]=DATA.sample(7,now);FLOAT_Q[8]=DATA.sample(8,now);return FLOAT_Q;}
    public static VehicleProfile profileNow(){return DATA.profile();}
    private static ArtworkLoader floatArt;
    /** The floating window shows the track: keep the media receiver up on the application context while the dashboard is away. */
    public static void floatMusic(android.content.Context c,boolean on){
        if(on){mediaReceiver.start(c.getApplicationContext());if(floatArt==null)floatArt=new ArtworkLoader(c.getApplicationContext(),null);floatArt.open();return;}
        if(floatArt!=null){floatArt.close();floatArt=null;}
        VehicleDash d=current.get();if(d==null||!d.resumed)mediaReceiver.stop();
    }
    public static MediaState.Snapshot floatTrack(){MediaState.Snapshot t=mediaState.snapshot(SystemClock.elapsedRealtime());if(floatArt!=null)floatArt.request(t);return t;}
    public static android.graphics.Bitmap floatArtwork(){return floatArt==null?null:floatArt.bitmap();}
    /** The rear camera is showing: the floating window steps aside. */
    public static boolean rearCamera(){return DATA.rearCamera();}
    /** Keeps feeding this drive's runs while only the floating window is up. */
    public static void floatTick(){feedStats(SystemClock.elapsedRealtime());VehicleDash d=current.get();soundTick(d!=null&&d.resumed);}
    /** The floating window went away: the engine sound stops with it unless the dashboard is back. */
    public static void floatGone(){floatUp=false;VehicleDash d=current.get();soundTick(d!=null&&d.resumed);}
}
