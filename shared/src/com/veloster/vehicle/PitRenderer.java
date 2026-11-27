package com.veloster.vehicle;

import com.veloster.i18n.UiText;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.Path;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Bitmap;
import android.graphics.RectF;
import java.util.Locale;

/** Original N/PIT drawing geometry, with only received vehicle samples as values. */
public final class PitRenderer {
    private final ThemedPaint paint=new ThemedPaint(3);
    private final KeyPath shape=new KeyPath();   // draw shape.kept(): see KeyPath
    private static int T(int c){return Theme.current().map(c);}
    /** Background and strip fades, rebuilt when the theme changes (shaders are not remapped by ThemedPaint). */
    private Theme shaderTheme;
    private Shader background,fadeLeft,fadeRight;
    private void shaders(){
        if(shaderTheme==Theme.current())return;shaderTheme=Theme.current();
        background=new LinearGradient(0,0,800,480,T(0xff111315),T(0xff030405),Shader.TileMode.CLAMP);
        int h=T(0xff090b0c)&0xffffff;
        fadeLeft=new LinearGradient(DashboardPage.STRIP_LEFT,0,DashboardPage.STRIP_LEFT+26,0,0xff000000|h,h,Shader.TileMode.CLAMP);
        fadeRight=new LinearGradient(DashboardPage.STRIP_RIGHT-26,0,DashboardPage.STRIP_RIGHT,0,h,0xff000000|h,Shader.TileMode.CLAMP);
    }
    /** Page whose tab is selected (DashboardPage id); records page data. */
    private int page=DashboardPage.OVERVIEW;
    public void setPage(int value){page=DashboardPage.normalize(value);}
    private DriveStats stats;
    /** Screens from the i30 PD build (sung9155/i30-pd-dashboard, MIT): 레이스, 람다 (Λ), 타코. */
    private final RaceRenderer raceRenderer=new RaceRenderer();
    private final LambdaCluster lambda=new LambdaCluster();
    private final TachoCluster tacho=new TachoCluster();
    // More screens from the i30 PD build (sung9155/i30-pd-dashboard v1.17.7, MIT): unchanged, on the shared ClusterKit.
    private final NightCluster night=new NightCluster();
    private final VfdCluster vfd=new VfdCluster();
    private final HudCluster hud=new HudCluster();
    private final GCircleCluster gcircle=new GCircleCluster();
    private final WarpCluster warp=new WarpCluster();
    private final DragCluster drag=new DragCluster();
    private final CassetteCluster cassette=new CassetteCluster();
    private final CircuitCluster circuit=new CircuitCluster();
    private final ShowRenderer showRenderer=new ShowRenderer();
    /** The ported ClusterKit screen on a page, or null. */
    private ClusterKit kit(int p){
        switch(p){
            case DashboardPage.NIGHT:return night;case DashboardPage.VFD:return vfd;case DashboardPage.HUD:return hud;
            case DashboardPage.GCIRCLE:return gcircle;case DashboardPage.WARP:return warp;case DashboardPage.DRAG:return drag;
            case DashboardPage.CASSETTE:return cassette;case DashboardPage.CIRCUIT:return circuit;default:return null;
        }
    }
    /** A tap on the body of a ported screen (드래그 준비 key, cassette, circuit list); true when it used the tap. */
    public boolean screenTap(int p,float x,float y){ClusterKit k=kit(p);return k!=null&&k.tap(x,y);}
    /** Ported screens that animate (needles, stars, the G tail): redraw about 20 times a second. */
    public static boolean animated(int p){return p==DashboardPage.HUD||p==DashboardPage.GCIRCLE||p==DashboardPage.WARP||p==DashboardPage.DRAG||p==DashboardPage.CASSETTE||p==DashboardPage.SHOW||p==DashboardPage.VFD||p==DashboardPage.CIRCUIT;}
    private final RecordsRenderer recordsRenderer=new RecordsRenderer();
    public void setRecords(DriveStats value){stats=value;}
    /** Settings open: the gear button is lit. */
    private boolean settingsOpen;
    public void setSettingsOpen(boolean value){settingsOpen=value;}
    private static final int BG=0xff040506,PANEL=0xff101315,LINE=0xff303638,BLUE=0xffa4d9ef,WHITE=0xffe1edf2,MUTED=0xff91a5ae,RED=0xffff4059;
    private Telemetry.Sample[] samples;
    private int shiftTarget;
    private long frameTime;
    private ShiftGuide.Cue cue;
    private boolean mediaPage;
    private MediaState.Snapshot music;
    private Bitmap artwork;
    private final GaugeRenderer gaugeRenderer=new GaugeRenderer();
    private final GraphRenderer graphRenderer=new GraphRenderer();
    private GraphLayout graphLayout;
    private GaugeLayout gaugeLayout;
    private OverviewLayout overviewLayout=new OverviewLayout();
    private boolean performancePage;
    private final PerformanceRenderer performanceRenderer=new PerformanceRenderer();
    private final java.text.SimpleDateFormat clockFormat=new java.text.SimpleDateFormat("HH:mm",Locale.US);
    private long clockMinute=Long.MIN_VALUE;
    private String clockText="";
    private String clock(){
        long now=System.currentTimeMillis(),minute=now/60000;
        if(minute!=clockMinute){clockMinute=minute;clockFormat.setTimeZone(java.util.TimeZone.getDefault());clockText=clockFormat.format(new java.util.Date(now));}
        return clockText;
    }
    public void setPerformancePage(boolean value){performancePage=value;}
    private TaxiMeter taxi;
    private float taxiSpeed=Float.NaN;
    private int taxiHour;
    private final TaxiRenderer taxiRenderer=new TaxiRenderer();
    /** Taxi page: meter to show (null hides the page), received speed km/h (NaN unknown), wall-clock hour. */
    private boolean taxiReceipt;
    public void setTaxi(TaxiMeter meter,float speed,int hour,boolean receipt,int pressed){taxi=meter;taxiSpeed=speed;taxiHour=hour;taxiReceipt=receipt;taxiRenderer.setPressed(pressed);}
    /** Horse animation strip for the taxi page (frames side by side). */
    public void setTaxiHorse(android.graphics.Bitmap strip,int frames){taxiRenderer.setHorse(strip,frames);}
    public void setArtwork(Bitmap value){artwork=value;}
    public void setGaugeLayout(GaugeLayout value){gaugeLayout=value;}
    public void setGraphLayout(GraphLayout value){graphLayout=value;}
    public void setOverviewLayout(OverviewLayout value){if(value!=null)overviewLayout=value;}
    private static final String[] PREFIX={"엔진 RPM","오일 온도","냉각수 온도","앞 왼쪽 휠","앞 오른쪽 휠","뒤 왼쪽 휠","뒤 오른쪽 휠","속도 km/h","현재 기어","스로틀 %","브레이크 압력 bar","횡가속","종가속","조향각","외기 온도","출력 (계산)","토크 (계산)","부스트 (계산)","앞 왼쪽 공기압","앞 오른쪽 공기압","뒤 왼쪽 공기압","뒤 오른쪽 공기압","TCS","ESC","ABS","배터리 SOC","배터리 전압","조향 속도","e-LSD 수치","흡기 온도"};
    private int index(String prefix){for(int i=0;i<PREFIX.length;i++)if(PREFIX[i].equals(prefix))return i;return -1;}
    private String value(String prefix){int i=index(prefix);if(i<0||i>=samples.length||!samples[i].valid)return "—";
        if(i==8)return Telemetry.gearLabel((int)samples[i].value);
        return String.format(Locale.US,i==0||i==7||(i>=Telemetry.TPMS_FL&&i<=Telemetry.BAT_SOC)||i==Telemetry.STEERING_SPEED?"%.0f":i==11||i==12?"%+.2f":i==13?"%+.1f":i==17||i==Telemetry.BAT_VOLTAGE?"%.2f":"%.1f",Telemetry.displayValue(i,samples[i].value));}
    private float numeric(String prefix){int i=index(prefix);return i>=0&&i<samples.length&&samples[i].valid?Telemetry.displayValue(i,samples[i].value):0;}
    private boolean changed(String prefix){int i=index(prefix);return i>=0&&i<samples.length&&samples[i].changed;}
    public void draw(Canvas c,int width,int height,boolean cluster,Telemetry model,long now){
        draw(c,width,height,cluster,model,now,ShiftGuide.DEFAULT);
    }
    public void draw(Canvas c,int width,int height,boolean cluster,Telemetry model,long now,int target){
        draw(c,width,height,cluster,model,now,target,false,null);
    }
    public void draw(Canvas c,int width,int height,boolean cluster,Telemetry model,long now,int target,boolean media,MediaState.Snapshot track){
        mediaPage=cluster && media;music=track;
        shiftTarget=ShiftGuide.clamp(target);frameTime=now;
        samples=model.snapshot(now);cue=ShiftGuide.evaluate(samples,shiftTarget);
        shaders();
        c.save();c.scale(width/800f,height/480f);c.drawColor(T(BG));
        paint.setShader(background);c.drawRect(0,0,800,480,paint);paint.setShader(null);
        for(int y=56;y<480;y+=8)stroke(c,0,y,800,y,0x063f4948,1);
        box(c,0,0,800,52,0xff090b0c);box(c,18,51,782,52,LINE);
        boolean taxiPage=taxi!=null;
        // N / PIT logo, then the scrollable tab strip.
        stroke(c,19,16,13,35,RED,4);stroke(c,27,16,21,35,BLUE,4);
        text(c,"N / PIT",36,33,23,WHITE,true);
        int selected=page;
        int saved=c.save();
        c.clipRect(DashboardPage.STRIP_LEFT,0,DashboardPage.STRIP_RIGHT,52);
        for(int tab:DashboardPage.shown()){
            float l=DashboardPage.left(tab,tabScroll),w=DashboardPage.width(tab);
            if(l>DashboardPage.STRIP_RIGHT||l+w<DashboardPage.STRIP_LEFT)continue;
            button(c,tabLabel(tab),l,8,w,38,tab==selected&&!settingsOpen,BLUE);
        }
        c.restoreToCount(saved);
        // Edge hints when more tabs are hidden on that side.
        if(tabScroll>0.5f)edgeHint(c,DashboardPage.STRIP_LEFT,true);
        if(tabScroll<DashboardPage.maxScroll()-0.5f)edgeHint(c,DashboardPage.STRIP_RIGHT,false);
        button(c,"",DashboardPage.GEAR_LEFT,8,DashboardPage.GEAR_WIDTH,38,settingsOpen,BLUE);
        gearIcon(c,DashboardPage.GEAR_LEFT+DashboardPage.GEAR_WIDTH/2,27,settingsOpen?BLUE:MUTED);
        button(c,UiText.language().toUpperCase(Locale.US),LANGUAGE_X,8,48,38,false,BLUE);
        centered(c,clock(),628,33,22,WHITE,true);
        button(c,UiText.t("돌아가기"),666,8,116,38,false,BLUE);
        boolean own=page==DashboardPage.RACE||page==DashboardPage.LAMBDA||page==DashboardPage.TACHO||page==DashboardPage.NEON||page==DashboardPage.CAR3D||kit(page)!=null||page==DashboardPage.SHOW;   // ported screens with their own media handling
        if(page==DashboardPage.NEON)centered(c,UiText.t("네온 드라이브"),400,270,22,MUTED,true);   // the Neon Drive view covers this while it runs
        else if(page==DashboardPage.CAR3D)centered(c,UiText.t("내 차"),400,270,22,MUTED,true);   // the 3D car view covers this while it runs
        else if(page==DashboardPage.RECORDS)recordsRenderer.draw(c,stats,now);
        else if(page==DashboardPage.RACE)raceRenderer.draw(c,samples,cue,now,stats,model.profile(),false,music,artwork);
        else if(page==DashboardPage.LAMBDA)lambda.frame(c,samples,model,stats,model.profile(),now,null);
        else if(page==DashboardPage.TACHO){tacho.shiftTarget=shiftTarget;tacho.frame(c,samples,model,stats,model.profile(),now,null);}
        else if(page==DashboardPage.SHOW)showRenderer.draw(c,samples,now,model.profile());
        else if(kit(page)!=null)kit(page).frame(c,samples,model,stats,model.profile(),now,page==DashboardPage.CASSETTE?music:null);
        else if(taxiPage)taxiRenderer.draw(c,taxi,taxiSpeed,taxiHour,now,taxiReceipt);
        else if(performancePage)performanceRenderer.draw(c,model,now);
        else if(graphLayout!=null)graphRenderer.draw(c,model,now,graphLayout);
        else if(gaugeLayout!=null)gaugeRenderer.draw(c,model,now,gaugeLayout);
        else if(mediaPage)drawCombined(c);else if(cluster)drawCluster(c);else drawDashboard(c);
        boolean records=page==DashboardPage.RECORDS||own;
        if(!records&&!taxiPage&&!performancePage&&graphLayout==null&&!mediaPage && music!=null && music.notice)drawMedia(c,true);
        if(!records&&!taxiPage&&cluster&&gaugeLayout==null&&graphLayout==null)drawArtworkToggle(c);
        c.restore();
    }
    /** Language button left edge (after the tab strip). */
    public static final int LANGUAGE_X=542;
    private float tabScroll;
    /** Horizontal offset of the header tab strip (see {@link DashboardPage}). */
    public void setTabScroll(float value){tabScroll=DashboardPage.clampScroll(value);}
    private static String tabLabel(int page){
        switch(page){
            case DashboardPage.F1:return "F1";
            case DashboardPage.GAUGES:return UiText.t("게이지");
            case DashboardPage.GRAPHS:return UiText.t("그래프");
            case DashboardPage.PERFORMANCE:return UiText.t("퍼포먼스");
            case DashboardPage.TAXI:return UiText.t("택시");
            case DashboardPage.RECORDS:return UiText.t("기록");
            case DashboardPage.RACE:return UiText.t("레이스");
            case DashboardPage.LAMBDA:return UiText.t("람다");
            case DashboardPage.TACHO:return UiText.t("타코");
            case DashboardPage.NEON:return UiText.t("네온 드라이브");
            case DashboardPage.NIGHT:case DashboardPage.VFD:case DashboardPage.HUD:case DashboardPage.GCIRCLE:case DashboardPage.WARP:
            case DashboardPage.DRAG:case DashboardPage.CASSETTE:case DashboardPage.SHOW:case DashboardPage.CIRCUIT:case DashboardPage.CAR3D:return UiText.t(DashboardPage.NAMES[page]);
            default:return UiText.t("전체 조망");
        }
    }
    /** Gear (settings) icon: eight teeth around a ring. */
    private void gearIcon(Canvas c,float cx,float cy,int color){
        paint.setColor(color);
        for(int i=0;i<8;i++){double a=Math.PI*i/4;float dx=(float)Math.cos(a),dy=(float)Math.sin(a);
            paint.setStrokeWidth(4);c.drawLine(cx+dx*7,cy+dy*7,cx+dx*11,cy+dy*11,paint);}
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3.5f);c.drawCircle(cx,cy,7,paint);paint.setStyle(Paint.Style.FILL);
        c.drawCircle(cx,cy,2.2f,paint);
    }
    /** Fade and a small chevron at a strip edge that has hidden tabs beyond it. */
    private void edgeHint(Canvas c,float x,boolean left){
        paint.setShader(left?fadeLeft:fadeRight);c.drawRect(left?x:x-26,6,left?x+26:x,48,paint);paint.setShader(null);
        float cx=left?x+8:x-8;
        stroke(c,cx+(left?4:-4),20,cx+(left?-2:2),27,BLUE,2.5f);stroke(c,cx+(left?-2:2),27,cx+(left?4:-4),34,BLUE,2.5f);
    }
    private void box(Canvas c,float l,float t,float r,float b,int color){paint.setColor(color);c.drawRect(l,t,r,b,paint);}
    private final RectF cellRect=new RectF();
    private void panel(Canvas c,float x,float y,float w,float h,int fill,int edge){
        if(ThemeShapes.cell(c,paint,shape,cellRect,x,y,x+w,y+h,fill,edge))return;   // LB / M / rose / copper / champagne / navy shapes; N keeps the cut corner
        shape.reset();shape.moveTo(x,y);shape.lineTo(x+w-12,y);shape.lineTo(x+w,y+12);
        shape.lineTo(x+w,y+h);shape.lineTo(x+12,y+h);shape.lineTo(x,y+h-12);shape.close();
        paint.setColor(fill);c.drawPath(shape.kept(),paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1);paint.setColor(edge);c.drawPath(shape.kept(),paint);paint.setStyle(Paint.Style.FILL);
    }
    private void stroke(Canvas c,float x,float y,float x2,float y2,int color,float width){
        paint.setColor(color);paint.setStrokeWidth(width);c.drawLine(x,y,x2,y2,paint);
    }
    private void text(Canvas c,String s,float x,float y,float size,int color,boolean bold){
        paint.setColor(color);paint.setTextSize(size);paint.setTypeface(bold?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);c.drawText(s,x,y,paint);
    }
    private void fitted(Canvas c,String s,float x,float y,float size,float width,int color){
        paint.setTypeface(Typeface.DEFAULT);paint.setTextSize(size);
        float measured=paint.measureText(s);
        text(c,s,x,y,measured>width?size*width/measured:size,color,false);
    }
    private void ellipsis(Canvas c,String s,float x,float y,float size,float width,int color){
        paint.setTypeface(Typeface.DEFAULT);paint.setTextSize(size);
        if(paint.measureText(s)>width){
            int end=s.length();while(end>0 && paint.measureText(s.substring(0,end)+"…")>width)end--;
            if(end>0 && Character.isHighSurrogate(s.charAt(end-1)))end--;
            s=s.substring(0,end)+"…";
        }
        text(c,s,x,y,size,color,false);
    }
    private void drawMedia(Canvas c,boolean notice){
        long age=music==null?0:music.noticeAgeMs;
        float phase=Math.min(1f,Math.min(age/250f,(5000-age)/250f));
        phase=Math.max(0,phase);phase=1-(1-phase)*(1-phase);
        float top=347+133*(1-phase);
        panel(c,18,top,764,101,0xff12212a,BLUE);
        cover(c,30,top+12,76);
        String source=music==null||music.source.length()==0?"MEDIA":music.source;
        text(c,source,120,top+18,11,BLUE,true);
        String title=music==null||music.title.length()==0?"—":music.title;
        ellipsis(c,title,120,top+43,21,644,WHITE);
        String artist=music==null||music.artist.length()==0?"—":music.artist;
        ellipsis(c,artist,120,top+64,13,480,MUTED);
        if(music!=null && music.hasProgress()){
            String times=MediaState.time(music.positionMs)+" / "+MediaState.time(music.durationMs);
            text(c,times,622,top+64,12,WHITE,false);
            box(c,120,top+79,764,top+83,LINE);
            box(c,120,top+79,120+644*music.progress(),top+83,BLUE);
        }
    }
    private void cover(Canvas c,float x,float y,float size){
        box(c,x,y,x+size,y+size,0xff1c303b);
        if(artwork!=null){
            float scale=Math.min(size/artwork.getWidth(),size/artwork.getHeight());
            float w=artwork.getWidth()*scale,h=artwork.getHeight()*scale;
            c.drawBitmap(artwork,null,new RectF(x+(size-w)/2,y+(size-h)/2,x+(size+w)/2,y+(size+h)/2),paint);
        }else{
            centered(c,"♪",x+size/2,y+size*.61f,size*.35f,BLUE,true);
            if(size>100)centered(c,"ALBUM ART",x+size/2,y+size*.84f,11,MUTED,false);
        }
    }
    private void drawArtworkToggle(Canvas c){
        panel(c,18,436,52,44,mediaPage?0xff233b46:PANEL,mediaPage?BLUE:LINE);
        cover(c,24,438,40);
        text(c,music==null||music.source.length()==0?UiText.t("음악"):music.source,80,451,11,BLUE,true);
        ellipsis(c,music==null||music.title.length()==0?"♪":music.title,80,472,13,120,MUTED);
    }
    private void drawCombined(Canvas c){
        panel(c,18,60,464,388,PANEL,LINE);
        text(c,cue.shift?"SHIFT ↑":"SHIFT / LED",30,83,11,cue.shift?RED:MUTED,true);
        for(int i=0;i<15;i++){
            boolean lit=cue.shift?(frameTime/250)%2==0:i<cue.leds;
            paint.setColor(lit?(i<5?BLUE:i<10?WHITE:RED):0xff252a2c);
            c.drawCircle(36+i*30.5f,105,7,paint);
        }
        float rpm=Math.max(0,Math.min(8000,numeric("엔진 RPM")));
        box(c,30,124,470,127,LINE);if(rpm>0)box(c,30,124,30+440*rpm/8000,127,BLUE);
        box(c,180,143,316,340,BLUE);
        centered(c,"EMS GEAR",248,164,11,BG,true);
        {String g=value("현재 기어");paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setTextSize(130);float gw=paint.measureText(g);
            float sz=gw>116?130*116/gw:130;   // "M7" in the manual gate ran past the 136-wide box: two characters shrink to fit, one stays 130 (i30 PD v1.17.12)
            centered(c,g,248,290-(130-sz)*0.36f,sz,BG,true);}
        centered(c,"VELOSTER N",248,324,10,BG,true);
        centered(c,"SPEED",98,177,12,MUTED,true);
        centered(c,value("속도 km/h"),98,253,57,WHITE,true);
        centered(c,"km/h",98,280,12,MUTED,false);
        centered(c,"ENGINE",398,177,12,MUTED,true);
        centered(c,value("엔진 RPM"),398,253,46,WHITE,true);
        centered(c,"rpm",398,280,12,MUTED,false);
        text(c,"OIL",32,372,11,MUTED,true);text(c,value("오일 온도")+" °C",116,375,22,WHITE,true);
        text(c,"COOLANT",258,372,11,MUTED,true);text(c,value("냉각수 온도")+" °C",368,375,22,WHITE,true);
        compactPedal(c,UiText.t("스로틀"),"스로틀 %","%",32,208,BLUE,100);
        compactPedal(c,UiText.t("브레이크"),"브레이크 압력 bar","bar",258,212,RED,100);
        panel(c,494,60,288,388,0xff12212a,LINE);
        cover(c,518,72,240);
        text(c,music==null||music.source.length()==0?"MEDIA":music.source,510,333,12,BLUE,true);
        ellipsis(c,music==null||music.title.length()==0?"—":music.title,510,362,21,256,WHITE);
        ellipsis(c,music==null||music.artist.length()==0?"—":music.artist,510,386,14,256,MUTED);
        if(music!=null && music.hasProgress()){
            text(c,MediaState.time(music.positionMs)+" / "+MediaState.time(music.durationMs),510,413,13,WHITE,false);
            box(c,510,428,766,432,LINE);box(c,510,428,510+256*music.progress(),432,BLUE);
        }
        button(c,"−",210,452,42,27,false,BLUE);text(c,UiText.t("목표 ")+shiftTarget+" rpm",267,471,12,BLUE,true);
        button(c,"+",440,452,42,27,false,BLUE);
        button(c,UiText.t("이전"),494,452,86,27,false,BLUE);
        button(c,music!=null&&music.playState==1?"Ⅱ":music!=null&&music.playState==0?"▶":"▶ / Ⅱ",588,452,98,27,false,BLUE);
        button(c,UiText.t("다음"),694,452,88,27,false,BLUE);
    }
    private void compactPedal(Canvas c,String label,String prefix,String unit,int x,int width,int color,float maximum){
        text(c,label,x,420,11,MUTED,true);
        fitted(c,value(prefix)+" "+unit,x+66,422,18,width-66,WHITE);
        box(c,x,432,x+width,438,LINE);
        float fraction=Math.max(0,Math.min(1,numeric(prefix)/maximum));
        if(fraction>0)box(c,x,432,x+width*fraction,438,color);
    }
    private void button(Canvas c,String s,float x,float y,float w,float h,boolean on,int accent){
        panel(c,x,y,w,h,on?0xff233b46:0xff111415,on?accent:LINE);
        if(on)box(c,x+9,y+h-3,x+w-9,y+h-1,accent);
        paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setTextSize(14);float measured=paint.measureText(s);
        text(c,s,x+12,y+h/2+5,measured>w-24?14*(w-24)/measured:14,on?accent:MUTED,true);
    }
    private void centered(Canvas c,String s,float x,float y,float size,int color,boolean bold){
        paint.setTextSize(size);paint.setTypeface(bold?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);
        text(c,s,x-paint.measureText(s)/2,y,size,color,bold);
    }
    private void pedal(Canvas c,String label,String prefix,String unit,int x,int color,float maximum){
        panel(c,x,377,376,57,0xff111516,LINE);
        text(c,label,x+14,396,10,MUTED,true);
        String v=value(prefix);
        fitted(c,v,x+14,423,25,85,WHITE);
        text(c,unit,x+105,422,11,MUTED,false);
        float fraction=Math.max(0,Math.min(1,numeric(prefix)/maximum));
        for(int i=0;i<20;i++)box(c,x+148+i*10.6f,403,x+155+i*10.6f,421,fraction>i/20f?color:LINE);
    }
    private void drawCluster(Canvas c){
        float rpm=Math.max(0,Math.min(8000,numeric("엔진 RPM")));
        text(c,cue.shift?"SHIFT ↑":"SHIFT / LED",20,83,11,cue.shift?RED:MUTED,true);
        text(c,shiftTarget+" rpm",690,83,11,MUTED,true);
        for(int i=0;i<15;i++){
            int color=i<5?BLUE:i<10?0xffd0ebf8:RED;
            float x=155+i*35;
            boolean lit=cue.shift?(frameTime/250)%2==0:i<cue.leds;
            paint.setColor(lit?color:0xff252a2c);c.drawCircle(x,79,10,paint);
            if(lit){paint.setColor(0x70ffffff);c.drawCircle(x-2,76,3,paint);}
        }
        box(c,18,105,782,108,LINE);
        if(rpm>0)box(c,18,105,18+764*rpm/8000,108,BLUE);
        // Central gear is the primary cue, like a single-seat race display.
        box(c,294,120,506,367,BLUE);
        centered(c,"E M S  G E A R",400,148,15,BG,true);
        String gear=value("현재 기어");
        {paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setTextSize(184);float gw=paint.measureText(gear),sz=gw>190?184*190/gw:184;   // "M7" ran past the 212-wide box
            centered(c,gear,400,321-(184-sz)*0.36f,sz,BG,true);}
        centered(c,"V E L O S T E R  N",400,350,11,BG,true);
        text(c,"SPEED",22,148,14,MUTED,true);
        text(c,"km/h",229,148,12,MUTED,false);
        centered(c,value("속도 km/h"),150,253,88,WHITE,true);
        box(c,18,276,276,278,LINE);
        f1Tile(c,0,22,175,250);
        text(c,"ENGINE",526,148,14,MUTED,true);
        text(c,"rpm",749,148,12,MUTED,false);
        centered(c,value("엔진 RPM"),653,247,69,WHITE,true);
        box(c,524,276,782,278,LINE);
        f1Tile(c,1,528,685,760);
        pedal(c,"THROTTLE", "스로틀 %", "%",18,BLUE,100);
        pedal(c,"BRAKE PRESSURE", "브레이크 압력 bar", "bar",406,RED,100);
        button(c,"−",548,452,42,27,false,BLUE);
        text(c,UiText.t("목표 ")+shiftTarget+" rpm",604,471,12,BLUE,true);
        button(c,"+",740,452,42,27,false,BLUE);
    }
    /** One of F1's two lines under speed and rpm: a tile (Tiles.F1) the driver re-points by holding it (oil, coolant by default). */
    private void f1Tile(Canvas c,int i,float captionX,float valueX,float unitX){
        int sig=Tiles.signal(Tiles.F1,i,VehicleProfile.VELOSTER_N);
        float v=Tiles.value(sig,samples,stats);int lv=Tiles.level(sig,v);
        text(c,Tiles.caption(sig),captionX,307,11,MUTED,true);
        text(c,Tiles.text(sig,v),valueX,310,22,lv==2?RED:WHITE,true);
        text(c,Tiles.unit(sig),unitX,310,11,MUTED,false);
    }
    /** Start-up welcome sweep (from the i30 PD build), drawn over the live dashboard for t in 0..1: a dark cover that
     *  fades out, an accent wipe left to right and the N / VELOSTER N badge easing in. It never blocks the data. */
    public void drawIntro(Canvas c,int width,int height,float t){
        c.save();c.scale(width/800f,height/480f);
        float cover=t<0.72f?1f:Math.max(0f,1f-(t-0.72f)/0.28f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor((((int)(cover*255))&0xff)<<24|0x060a0e);c.drawRect(0,0,800,480,paint);
        float sp=Math.min(1f,t/0.55f),se=1f-(1f-sp)*(1f-sp),x=800*se;
        if(sp<1f){
            paint.setColor((((int)(cover*36))&0xff)<<24|0x5bc8f5);c.drawRect(0,0,x,480,paint);
            paint.setColor((((int)(cover*210))&0xff)<<24|0x5bc8f5);c.drawRect(x-3,0,x+1,480,paint);
        }
        float lp=Math.max(0f,Math.min(1f,(t-0.2f)/0.5f)),le=1f-(1f-lp)*(1f-lp);
        int la=((int)(le*cover*255)&0xff)<<24;
        float scale=0.72f+0.28f*le,size=56*scale,cy=244+(1f-le)*12;
        paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setTextSize(size);
        float tw=paint.measureText("VELOSTER N"),sx=400-(tw+34*scale)/2;
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(6*scale);
        paint.setColor(la|0xff4059);c.drawLine(sx,cy+14*scale,sx+13*scale,cy-16*scale,paint);
        paint.setColor(la|0xa4d9ef);c.drawLine(sx+13*scale,cy+14*scale,sx+26*scale,cy-16*scale,paint);
        paint.setStyle(Paint.Style.FILL);
        text(c,"VELOSTER N",sx+34*scale,cy+size*0.34f,size,la|0xffffff,true);
        if(le>0.55f){paint.setTextSize(13);float sw=paint.measureText("N  /  P I T   D A S H B O A R D");
            int sa=((int)((le-0.55f)/0.45f*cover*150)&0xff)<<24;
            text(c,"N  /  P I T   D A S H B O A R D",400-sw/2,cy+size*0.34f+28,13,sa|0xbcd0d8,false);}
        c.restore();
    }
    private void drawDashboard(Canvas c){
        String[] names={"SPEED / km/h","ENGINE / rpm","EMS GEAR","THROTTLE / %","BRAKE / bar"};
        for(int slot=0;slot<overviewLayout.count();slot++){
            int i=overviewLayout.signalAt(slot);boolean head=overviewLayout.headline(i);
            if(overviewLayout.grouped(i))groupCard(c,i);else overviewCard(c,i,head?names[slot]:UiText.t(Telemetry.LABELS[i]));
        }
    }
    private void groupCard(Canvas c,int i){
        float x=overviewLayout.left(i),y=overviewLayout.top(i),w=overviewLayout.width(i);
        panel(c,x,y,w,overviewLayout.height(i),PANEL,LINE);box(c,x+10,y,x+53,y+2,BLUE);
        if(i==Telemetry.TPMS_FL){
            fitted(c,UiText.t("타이어 공기압"),x+10,y+20,13,w-20,MUTED);
            String[] corners={"FL","FR","RL","RR"};
            for(int n=0;n<4;n++){
                float l=x+10+(n%2)*69,t=y+44+(n/2)*25;
                text(c,corners[n],l,t,10,MUTED,false);
                text(c,value(PREFIX[i+n]),l+20,t,20,BLUE,true);
            }
            text(c,"psi",x+10,y+88,11,MUTED,false);
        }else{
            fitted(c,"TCS · ESC · ABS",x+10,y+20,13,w-20,MUTED);
            for(int n=0;n<3;n++){
                float t=y+43+n*22;Telemetry.Sample s=samples[i+n];
                text(c,UiText.t(Telemetry.LABELS[i+n]),x+10,t,12,MUTED,false);
                // TCS / ESC 1 = switched off (the lamp lights), ABS 1 = warning lamp (wording from the i30 PD build, sung9155, MIT)
                paint.setTextSize(16);paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setRawColor(s.valid&&s.value==1?Theme.current().danger:Theme.current().calm(BLUE));c.drawText(s.valid?(s.value==1?UiText.t(n==2?"경고":"꺼짐"):UiText.t("정상")):"—",x+66,t,paint);   // already themed: not mapped again
            }
        }
    }
    private void overviewCard(Canvas c,int i,String title){
        float x=overviewLayout.left(i),y=overviewLayout.top(i),w=overviewLayout.width(i),h=overviewLayout.height(i);
        boolean head=overviewLayout.headline(i),ok=samples[i].valid&&!Float.isNaN(samples[i].value)&&!Float.isInfinite(samples[i].value);
        int style=overviewLayout.style(i),color=samples[i].changed?0xffe9f7ff:BLUE;
        panel(c,x,y,w,h,PANEL,LINE);box(c,x+10,y,x+53,y+2,head&&i==0?RED:BLUE);
        fitted(c,title,x+10,y+(head?18:22),head?11:13,w-20,MUTED);
        String number=ok?value(PREFIX[i]):"—";
        float lo=GaugeRenderer.min(i,Telemetry.UNITS[i]),hi=GaugeRenderer.max(i,Telemetry.UNITS[i]);
        // Unsupported gears keep the existing dash and have no gauge position.
        boolean position=ok&&(i!=8||(samples[i].value>=1&&samples[i].value<=6));
        float f=position?Math.max(0,Math.min(1,(Telemetry.displayValue(i,samples[i].value)-lo)/(hi-lo))):0;
        if(style==2){
            fitted(c,number,x+10,y+(head?61:63),head?(i==0?35:38):32,w-20,color);
        }else if(style==1){
            fitted(c,number,x+10,y+(head?48:54),head?28:29,w-20,color);
            float t=y+(head?59:64),b=t+7;
            box(c,x+10,t,x+w-10,b,LINE);if(position)box(c,x+10,t,x+10+(w-20)*f,b,BLUE);
            if(lo<0){float zero=x+10+(w-20)*(-lo)/(hi-lo);stroke(c,zero,t-2,zero,b+2,MUTED,1);}
        }else{
            float cx=x+32,cy=y+(head?46:56),radius=head?17:23;
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(head?3:4);paint.setColor(LINE);
            RectF arc=new RectF(cx-radius,cy-radius,cx+radius,cy+radius);c.drawArc(arc,150,240,false,paint);
            if(position){paint.setColor(BLUE);c.drawArc(arc,150,KeyPath.arc(240*f),false,paint);}
            paint.setStyle(Paint.Style.FILL);
            if(position){double angle=Math.toRadians(150+240*f);stroke(c,cx,cy,cx+(float)Math.cos(angle)*(radius-6),cy+(float)Math.sin(angle)*(radius-6),WHITE,1.5f);paint.setColor(BLUE);c.drawCircle(cx,cy,2,paint);}
            fitted(c,number,x+58,y+(head?54:66),head?26:28,w-68,color);
        }
        if(!head)text(c,Telemetry.UNITS[i],x+10,y+85,11,MUTED,false);
    }
}
