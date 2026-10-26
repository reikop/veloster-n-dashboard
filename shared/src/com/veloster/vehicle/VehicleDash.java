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
    private final MediaState mediaState=new MediaState();
    private final MediaReceiver mediaReceiver=new MediaReceiver(mediaState);
    private final ArtworkLoader artwork;
    private final GaugeLayout gaugeLayout=new GaugeLayout();
    private final GaugeEditor gaugeEditor;
    private final GraphLayout graphLayout=new GraphLayout();
    private final GraphEditor graphEditor;
    private final OverviewLayout overviewLayout=new OverviewLayout();
    private int overviewDown=-1;
    private boolean cluster, media, gauges, graphs, touching;
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
        gaugeEditor=new GaugeEditor(a,gaugeLayout,new Runnable(){public void run(){invalidate();}});
        graphEditor=new GraphEditor(a,graphLayout,new Runnable(){public void run(){invalidate();}});
        for(int i=0;i<Telemetry.LABELS.length;i++)try{
            overviewLayout.style(i,a.getSharedPreferences("pit_vehicle_ui",0).getInt("overview_style_"+i,2));
        }catch(IllegalArgumentException e){/* Retain default for an invalid saved style. */}
        catch(ClassCastException e){/* Retain default for incompatible preferences. */}
        shiftTarget=ShiftGuide.clamp(a.getSharedPreferences("pit_vehicle_ui",0).getInt("shift_target",ShiftGuide.DEFAULT)); setVisibility(GONE); }
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
    }
    public static void resumeStandalone(Activity a){
        VehicleDash d=current.get();if(d==null||d.activity!=a)return;
        d.resumed=true;d.artwork.open();d.mediaReceiver.start(a);
        d.policy(false,"standalone-resume");d.setVisibility(VISIBLE);
    }
    public static void pauseStandalone(Activity a){
        VehicleDash d=current.get();if(d==null||d.activity!=a)return;
        d.resumed=false;d.touching=false;d.removeCallbacks(d.editHold);
        d.gaugeEditor.dismiss();d.graphEditor.dismiss();d.mediaReceiver.stop();d.artwork.close();
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
    public static void wheels(byte[] data) { DATA.wheels(data,SystemClock.elapsedRealtime()); }
    public static void rearView(int data) { DATA.rearView(data); }
    public static void units(byte[] data) { DATA.units(data,SystemClock.elapsedRealtime()); }
    public static void speed(int data) { DATA.speed(data,SystemClock.elapsedRealtime()); }
    public static void additional(byte[] data) { DATA.additional(data,SystemClock.elapsedRealtime()); }
    public static void hscanJsn(int cid,int funcLow,byte[] data){DATA.hscanJsn(cid,funcLow,data,SystemClock.elapsedRealtime());}
    public static void hscanJsn(int cid,int funcLow,byte[] data,long receivedAt){DATA.hscanJsn(cid,funcLow,data,receivedAt);}
    protected void onAttachedToWindow() { super.onAttachedToWindow(); artwork.open();mediaReceiver.start(activity); post(this); }
    protected void onDetachedFromWindow() { removeCallbacks(this);removeCallbacks(editHold);touching=false;gaugeEditor.dismiss();graphEditor.dismiss();mediaReceiver.stop();artwork.close();super.onDetachedFromWindow(); }
    public void run() {
        long delay=100;
        long tick=SystemClock.elapsedRealtime();
        if(resumed&&!standalone&&tick-lastPolicyCheck>=1000){lastPolicyCheck=tick;int status=RegulationState.read(activity);if(status>=0)policy(status==1,"service");}
        tryRequestedEntry();
        if(getVisibility()==VISIBLE) {
            invalidate();long now=SystemClock.elapsedRealtime();
            if(graphs)delay=250;
            else if(!(cluster&&media&&!gauges) && mediaState.snapshot(now).notice)delay=16;
            if(now-lastDiagnostic>=5000) { lastDiagnostic=now;android.util.Log.i("VehicleDash",DATA.diagnostics(now)); }
        }
        postDelayed(this,delay);
    }
    protected void onDraw(Canvas c) {
        long now=SystemClock.elapsedRealtime();
        MediaState.Snapshot track=mediaState.snapshot(now);artwork.request(track);
        renderer.setArtwork(artwork.bitmap());
        renderer.setGaugeLayout(gauges?gaugeLayout:null);
        renderer.setGraphLayout(graphs?graphLayout:null);
        renderer.setOverviewLayout(overviewLayout);
        renderer.draw(c,getWidth(),getHeight(),cluster,DATA,now,shiftTarget,media,track);
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
            removeCallbacks(editHold);touching=true;downX=x;downY=y;
            overviewDown=!cluster&&!gauges&&!graphs&&!blocked&&!overviewCovered(y)?overviewLayout.hit(x,y):-1;
            if(!blocked&&((gauges&&gaugeEditor.hit(x,y)>=0)||(graphs&&graphLayout.hit(x,y)>=0)))postDelayed(editHold,600);
            return true;
        }
        if(event.getAction()==MotionEvent.ACTION_MOVE){if(Math.abs(x-downX)>=16||Math.abs(y-downY)>=16){touching=false;removeCallbacks(editHold);}return true;}
        if(event.getAction()==MotionEvent.ACTION_CANCEL) { touching=false;removeCallbacks(editHold);return true; }
        if(event.getAction()!=MotionEvent.ACTION_UP)return true;
        removeCallbacks(editHold);
        boolean tap=touching && Math.abs(x-downX)<16 && Math.abs(y-downY)<16;
        touching=false;
        if(standalone&&tap&&x>=666&&x<=782&&downX>=666&&downX<=782&&y>=8&&y<=46&&downY>=8&&downY<=46){activity.finish();return true;}
        if(tap&&!blocked&&!cluster&&!gauges&&!graphs&&overviewDown>=0&&overviewLayout.hit(x,y)==overviewDown&&!overviewCovered(y)){
            overviewLayout.cycle(overviewDown);
            activity.getSharedPreferences("pit_vehicle_ui",0).edit().putInt("overview_style_"+overviewDown,overviewLayout.style(overviewDown)).apply();
            invalidate();return true;
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
        if(x>=144 && x<=244 && downX>=144 && downX<=244) { cluster=false;gauges=false;graphs=false;invalidate(); }
        else if(x>=250 && x<=322 && downX>=250 && downX<=322) { cluster=true;gauges=false;graphs=false;invalidate(); }
        else if(x>=328 && x<=412 && downX>=328 && downX<=412) { gauges=true;cluster=false;graphs=false;invalidate(); }
        else if(x>=418 && x<=528 && downX>=418 && downX<=528) { graphs=true;gauges=false;cluster=false;invalidate(); }
        else if(x>=534 && x<=582 && downX>=534 && downX<=582){LanguageSettings.show(activity,new Runnable(){public void run(){invalidate();}});}
        else if(x>=666 && x<=782 && downX>=666 && downX<=782)close();
        return true;
    }
}
