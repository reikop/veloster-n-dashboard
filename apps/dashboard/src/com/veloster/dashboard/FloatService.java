package com.veloster.dashboard;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import com.veloster.vehicle.FloatPanel;
import com.veloster.vehicle.VehicleDash;

/**
 * 플로팅 창: while the dashboard is not on screen (any other app: navi, home, media), keeps reading the car (the
 * VehicleDataClient it shares with the activity) and shows FloatPanel over everything. Started by DashboardActivity.onPause and
 * at boot when 설정 → 플로팅 → 플로팅 창 is on; stopped when the dashboard comes back. Looks 20 times a second, draws when something changed. A tap opens
 * the dashboard, a long press 내 앱, a drag moves it (remembered per shape, cell count and size). Steps aside while the rear camera shows. With
 * 플로팅 버튼 숨기기 on it asks 내 앱 to hide its edge button, again every 30 s while up (내 앱 brings the button back on
 * its own when that stops); on the way out it asks for the button back.
 * From the i30 PD build (sung9155/i30-pd-dashboard, MIT).
 */
public final class FloatService extends Service implements Runnable {
    static final String HUB="com.veloster.carplayhub",HIDE_ACTION="com.veloster.carplayhub.QUICK_ENTRY_HIDE";
    private static final long FRAME_MS=50,HIDE_REPEAT_MS=30000;   // 20 frames a second, like the race screen: the car sends every 40-80 ms
    /** A tick runs on the first vsync at least this long after the last one: every third frame at 60 Hz (50 ms), in step with the display
     *  instead of a 50 ms timer that drifts by the work it does. */
    private static final long TICK_MS=FRAME_MS-8;
    /** While a bar glides, a frame at most this often (every second vsync, ~30 a second): every vsync took ~50 draws a second, 4-6 ms each
     *  on the UI thread the navi shares the CPU with, and stretched the tick gaps to 130 ms. */
    private static final long GLIDE_FRAME_MS=30;private long mGlideAt;
    /** GPU drawing for the window, on by default since 2026-11-17 (our car: draw 5-10 ms -> 2.5-4 ms, worst 51 -> 12 ms, the same picture).
     *  Off while /sdcard/vn_float_sw exists (adb shell touch / rm), looked at every 2 s, so the two can still be compared without a new build. */
    private static final String SW_FLAG="vn_float_sw";
    private boolean hw=true,alive;private long hwCheckedAt;
    // ---- frame-rate measurement: one "FLOAT ..." line a second (tag VehicleDash) -------------------
    private int mTicks,mInval,mDraws;private long mDrawNs,mDrawMax,mTickNs,mTickMax,mGapMax,mLastTick,mLogAt;private boolean mHwCanvas;
    private final Choreographer.FrameCallback frame=new Choreographer.FrameCallback(){public void doFrame(long ns){
        if(!alive)return;
        long now=android.os.SystemClock.elapsedRealtime();
        if(now-mLastTick>=TICK_MS)run();
        else if(panel!=null&&now-mGlideAt>=GLIDE_FRAME_MS&&FloatPanel.gliding(now)){mGlideAt=now;panel.invalidate();}   // a bar on its way: about 30 frames a second until it lands
        Choreographer.getInstance().postFrameCallback(this);}};
    private String mDnSource;
    private final Handler handler=new Handler();
    private VehicleDataClient client;
    private WindowManager windows;
    private View panel;
    private WindowManager.LayoutParams layout;
    private int shown=-1;   // the key (shape, cells, size) of the window up, -1 none
    private long hideSentAt;private boolean hideSent;
    /** The media receiver is held for the window (VehicleDash.floatMusic). */
    private boolean musicOn;

    /** Starts the window if it is switched on (the dashboard just left the screen, or the head unit booted). */
    static void startIfOn(Context c){
        VehicleDash.loadFloat(c.getSharedPreferences("pit_vehicle_ui",0));
        if(DashboardActivity.resumed)return;   // boot's broadcast can come after the dashboard is already up
        if(FloatPanel.shape!=FloatPanel.OFF)try{wanted=c.startService(new Intent(c,FloatService.class))!=null;}catch(RuntimeException e){android.util.Log.w("VehicleDash","Floating window not started",e);}
    }
    /** The service is starting or up: it holds the shared car data client, so the dashboard leaves it running on its way out. */
    static volatile boolean wanted;
    static void stop(Context c){wanted=false;try{c.stopService(new Intent(c,FloatService.class));}catch(RuntimeException ignored){/* not running */}}

    public IBinder onBind(Intent intent){return null;}
    public void onCreate(){
        super.onCreate();FloatPanel.dayNightReset();
        windows=(WindowManager)getSystemService(Context.WINDOW_SERVICE);
        com.veloster.i18n.LanguageSettings.init(this);com.veloster.vehicle.Fonts.load(this);wanted=true;client=VehicleDataClient.shared(this);client.start();   // already running when the dashboard handed it over: no gap, values kept
        alive=true;Choreographer.getInstance().postFrameCallback(frame);
    }
    public int onStartCommand(Intent intent,int flags,int startId){
        VehicleDash.loadFloat(prefs());
        if(FloatPanel.shape==FloatPanel.OFF||DashboardActivity.resumed){stopSelf();return START_NOT_STICKY;}   // also when it is restarted with the dashboard up
        return START_STICKY;
    }
    private SharedPreferences prefs(){return getSharedPreferences("pit_vehicle_ui",0);}

    public void run(){
        long t0=System.nanoTime(),now0=android.os.SystemClock.elapsedRealtime();
        if(mLastTick>0)mGapMax=Math.max(mGapMax,now0-mLastTick);mLastTick=now0;
        if(now0-hwCheckedAt>=2000){hwCheckedAt=now0;java.io.File sd=android.os.Environment.getExternalStorageDirectory();boolean want=!new java.io.File(sd,SW_FLAG).exists();if(want!=hw){hw=want;remove();}
            FloatPanel.glideOn=!new java.io.File(sd,"vn_float_noglide").exists();FloatPanel.layerOn=!new java.io.File(sd,"vn_float_nolayer").exists();}   // a new window with the other renderer
        int want=VehicleDash.rearCamera()||FloatPanel.shape==FloatPanel.OFF?-1:FloatPanel.shape*100+FloatPanel.count*10+FloatPanel.size+(FloatPanel.round()?1000:0)+(FloatPanel.musicStrip?10000:0);   // the round gauge has square cells, the 음악 띠 a taller window: their own
        if(want!=shown){remove();if(want>=0)add(FloatPanel.shape,FloatPanel.count,FloatPanel.size);}
        boolean music=panel!=null&&FloatPanel.musicShown();if(music!=musicOn){musicOn=music;VehicleDash.floatMusic(this,music);}
        if(music)FloatPanel.music(VehicleDash.floatTrack(),VehicleDash.floatArtwork());
        int lookBefore=FloatPanel.skinNow();FloatPanel.dayNight(VehicleDash.floatSamples(FloatPanel.count),android.os.SystemClock.elapsedRealtime());boolean flipped=FloatPanel.skinNow()!=lookBefore;   // dusk, headlamps or the stock screen mode: redraw only when the look in use differs
        boolean changed=flipped|(FloatPanel.shape!=FloatPanel.OFF&&FloatPanel.tick(FloatPanel.count,FloatPanel.size,VehicleDash.floatSamples(FloatPanel.count),VehicleDash.profileNow(),android.os.SystemClock.elapsedRealtime()));
        String ds=FloatPanel.dnSource;if(ds!=null&&!ds.equals(mDnSource)){mDnSource=ds;android.util.Log.i("NDashboard","Float day/night source: "+ds+" night="+com.veloster.vehicle.DayNight.HOLD.night);}
        VehicleDash.floatUp=panel!=null;if(panel!=null)VehicleDash.floatTick();   // also the engine sound / shift beep with 플로팅 중 on
        if(panel!=null){if(changed||FloatPanel.gliding(now0)){panel.invalidate();if(changed)mInval++;}   // drawn only when it would look different: nothing to composite over the navi at a steady reading
            long now=android.os.SystemClock.elapsedRealtime();
            if(FloatPanel.hideButton&&(!hideSent||now-hideSentAt>=HIDE_REPEAT_MS)){hide(true);hideSentAt=now;}
            else if(!FloatPanel.hideButton&&hideSent)hide(false);}
        long dt=System.nanoTime()-t0;mTickNs+=dt;mTickMax=Math.max(mTickMax,dt);mTicks++;
        if(now0-mLogAt>=1000){
            if(mLogAt>0&&panel!=null)android.util.Log.i("VehicleDash",String.format(java.util.Locale.US,
                "FLOAT fps=%d ticks=%d redraw=%d%% draw_ms=%.1f/%.1f tick_ms=%.1f/%.1f gap_ms=%d hw=%d/%d shape=%d n=%d size=%d glide=%d layer=%d/%d",
                mDraws,mTicks,mTicks==0?0:mInval*100/mTicks,mDraws==0?0f:mDrawNs/1e6f/mDraws,mDrawMax/1e6f,mTicks==0?0f:mTickNs/1e6f/mTicks,mTickMax/1e6f,mGapMax,
                hw?1:0,mHwCanvas?1:0,FloatPanel.shape,FloatPanel.count,FloatPanel.size,FloatPanel.glideOn?1:0,FloatPanel.layerOn?1:0,FloatPanel.layerBuilds));
            mLogAt=now0;mTicks=0;mInval=0;mDraws=0;mDrawNs=0;mDrawMax=0;mTickNs=0;mTickMax=0;mGapMax=0;}
    }
    private void add(final int shape,final int n,final int size){
        final int w=FloatPanel.width(shape,n,size),h=FloatPanel.height(shape,n,size);final boolean rnd=FloatPanel.round();final String at=shape+"_"+n+"_"+size+(rnd?"_r":"");
        layout=new WindowManager.LayoutParams(w,h,WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|(hw?WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED:0),PixelFormat.TRANSLUCENT);   // x / y on the whole screen, not below the status bar (it sat 56 px low)
        layout.gravity=Gravity.TOP|Gravity.LEFT;
        layout.x=prefs().getInt("float_x_"+at,FloatPanel.defaultX(shape,n,size));layout.y=prefs().getInt("float_y_"+at,FloatPanel.defaultY(shape,n,size));layout.x=Math.max(0,Math.min(800-w,layout.x));layout.y=Math.max(0,Math.min(480-h,layout.y));   // a saved place from a smaller window stays on screen
        final View v=new View(this){protected void onDraw(Canvas c){long t=System.nanoTime();
            FloatPanel.draw(c,shape,n,size,VehicleDash.floatSamples(n),VehicleDash.profileNow(),android.os.SystemClock.elapsedRealtime());
            long d=System.nanoTime()-t;mDraws++;mDrawNs+=d;mDrawMax=Math.max(mDrawMax,d);mHwCanvas=c.isHardwareAccelerated();}};
        v.setOnTouchListener(new View.OnTouchListener(){
            float downX,downY;int startX,startY;boolean moved,held;
            final Runnable longPress=new Runnable(){public void run(){if(panel!=v)return;held=true;open(HUB,"com.veloster.carplayhub.HubActivity");}};
            public boolean onTouch(View view,MotionEvent e){
                switch(e.getAction()){
                    case MotionEvent.ACTION_DOWN:downX=e.getRawX();downY=e.getRawY();startX=layout.x;startY=layout.y;moved=false;held=false;handler.postDelayed(longPress,700);return true;
                    case MotionEvent.ACTION_MOVE:{float dx=e.getRawX()-downX,dy=e.getRawY()-downY;
                        if(!moved&&Math.abs(dx)+Math.abs(dy)<12)return true;
                        moved=true;handler.removeCallbacks(longPress);
                        layout.x=Math.max(0,Math.min(800-w,startX+(int)dx));layout.y=Math.max(0,Math.min(480-h,startY+(int)dy));
                        try{windows.updateViewLayout(view,layout);}catch(RuntimeException ignored){/* removed meanwhile */}return true;}
                    case MotionEvent.ACTION_UP:handler.removeCallbacks(longPress);
                        if(moved)prefs().edit().putInt("float_x_"+at,layout.x).putInt("float_y_"+at,layout.y).apply();
                        else if(!held)open(getPackageName(),"com.veloster.dashboard.DashboardActivity");
                        return true;
                    case MotionEvent.ACTION_CANCEL:handler.removeCallbacks(longPress);return true;
                }
                return false;
            }
        });
        try{windows.addView(v,layout);panel=v;shown=shape*100+n*10+size+(rnd?1000:0)+(FloatPanel.musicStrip?10000:0);/* the same key as run()'s want: without the 음악 띠 term the window went down and up again every tick */foreground(true);}
        catch(RuntimeException e){com.veloster.diagnostics.ErrorLog.record("float_window",e);android.util.Log.w("VehicleDash","Floating window refused",e);panel=null;shown=-1;stopSelf();}
    }
    private void remove(){
        if(panel!=null){try{windows.removeView(panel);}catch(RuntimeException ignored){/* already gone */}panel=null;foreground(false);}
        shown=-1;
        if(hideSent)hide(false);
        if(musicOn){musicOn=false;VehicleDash.floatMusic(this,false);}
    }
    private void open(String pkg,String cls){
        try{startActivity(new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(pkg,cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));}
        catch(RuntimeException e){android.util.Log.w("VehicleDash","Could not open "+cls,e);}
    }
    /** While the window is up the process runs as a foreground service: Android 4.2 otherwise keeps it in the background cgroup, which
     *  gets a few percent of the CPU when the navi is busy (driving: our draws took 6.5 ms and ticks slipped to 86 ms median, 326 ms worst).
     *  The notification it needs is the app's icon with "플로팅 창". */
    private boolean fg;
    private void foreground(boolean on){
        if(on==fg)return;fg=on;
        try{if(on){android.app.Notification n=new android.app.Notification.Builder(this).setSmallIcon(getApplicationInfo().icon!=0?getApplicationInfo().icon:android.R.drawable.ic_menu_info_details)
                .setContentTitle(getString(getApplicationInfo().labelRes)).setContentText(com.veloster.i18n.UiText.t("플로팅 창")).setOngoing(true).build();
                startForeground(7101,n);}
            else stopForeground(true);}
        catch(RuntimeException e){android.util.Log.w("VehicleDash","Foreground service refused",e);}
    }
    /** Asks 내 앱 to hide (or bring back) its edge button. Only 내 앱 receives it. */
    private void hide(boolean on){
        hideSent=on;
        try{sendBroadcast(new Intent(HIDE_ACTION).setPackage(HUB).putExtra("hide",on));}catch(RuntimeException ignored){/* 내 앱 not installed */}
    }
    public void onDestroy(){
        alive=false;Choreographer.getInstance().removeFrameCallback(frame);handler.removeCallbacks(this);remove();VehicleDash.floatGone();
        wanted=false;if(client!=null){if(!DashboardActivity.resumed)client.stop();client=null;}
        super.onDestroy();
    }
}
