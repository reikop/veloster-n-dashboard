package com.veloster.vehicle;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * 플로팅 창: a small see-through window of readings that the dashboard keeps over every other screen (navi, home, media)
 * while it is not on screen itself. Chosen in 설정 → 플로팅: the shape (카드, 아래 띠, 옆 세로), how many cells (1, 2 or 4)
 * and the size; each cell a short name, the value and a thin bar that follows it (from zero for a value that goes
 * negative, like boost). Off by default. The dashboard app's FloatService owns the window; this draws it and holds the
 * choices. Host-testable.
 */
public final class FloatPanel {
    /** ROUND: 원형 게이지, a disc per reading laid out like the card, in the theme's colours (no 색감). */
    public static final int OFF=0,CARD=1,STRIP=2,SIDE=3,ROUND=4;
    /** The rectangular layouts with their own inside (FloatLayouts). */
    public static final int PILL=FloatLayouts.PILL,HERO=FloatLayouts.HERO,GEARBOX=FloatLayouts.GEAR,DIAG=FloatLayouts.DIAG,DIAL=FloatLayouts.DIAL,SHIFT=FloatLayouts.SHIFT,TICK=FloatLayouts.TICK,FILLS=FloatLayouts.FILLS,BARS=FloatLayouts.BARS,TWIN=FloatLayouts.TWIN,WEDGE=FloatLayouts.WEDGE,TOWER=FloatLayouts.TOWER;
    /** 음악: album art, the track and its progress, with one or two readings and the gear (FloatMusic). */
    public static final int MUSIC=FloatLayouts.LAST+1;
    /** 세로 띠 + RPM: six readings stacked, a full-height rpm bar on the right (FloatLayouts.VRPM; numbered after 음악 so saved choices keep their meaning). */
    public static final int VRPM=FloatLayouts.VRPM;
    /** 세로 캡슐: a screen-high pill, a boost dial in its top end, readings with the rpm lane beside them, the gear at the bottom (FloatLayouts.VCAP). */
    public static final int VCAP=FloatLayouts.VCAP;
    /** Readings a window can hold (세로 띠 + RPM shows six, and a seventh while no track plays). */
    public static final int MAX_CELLS=7;
    public static final String[] SHAPE_NAMES={"끔","카드","가로 띠","세로 띠","원형 게이지","캡슐","큰 값 + 목록","단수 중심","사선","계기 + 목록","변속등","얇은 띠","가로 막대","세로 막대","계기 묶음","RPM 막대","세로 단수","음악","세로 띠 + RPM","세로 캡슐"};
    /** The 모양 sheet's order; SettingsLayout.FORM_SLOTS puts each on its 5 x 4 grid. */
    public static final int[] FORMS={CARD,STRIP,SIDE,TICK,FILLS, ROUND,DIAL,TWIN,BARS, PILL,GEARBOX,WEDGE,SHIFT,VCAP, VRPM,TOWER,HERO,DIAG,MUSIC};
    public static final int LAST_SHAPE=VCAP;
    /** 음악 띠: the playing track in a strip under the window, any shape but 음악. */
    public static volatile boolean musicStrip;
    /** The window shows the track (FloatService then keeps the media receiver up while the dashboard is away). */
    public static boolean musicShown(){int s=shape;return s==MUSIC||musicStrip&&s!=OFF;}
    /** The track and its album art (null for none) for the next tick and draw. */
    public static void music(MediaState.Snapshot track,android.graphics.Bitmap art){FloatMusic.track=track;FloatMusic.art=art;}
    private static boolean strip(int s){return musicStrip&&s>OFF&&s!=MUSIC&&s!=VRPM&&s!=VCAP;}   // 세로 띠 + RPM keeps the track in its own box; 세로 캡슐 is screen-high, no room under it
    /** 세로 띠 + RPM's box shows the track: 음악 띠 on and a track playing (else its seventh reading); 세로 캡슐 gives its last two rows to it. */
    static boolean vrMusic(int s){return (s==VRPM||s==VCAP)&&musicStrip&&FloatMusic.playing(FloatMusic.track);}
    /** The size's scale (작게 .85 ... 아주 크게 2). */
    static float sizeK(){return SIZES[clampSize(size)];}
    public static boolean layout(int s){return s>=PILL&&s<=FloatLayouts.LAST||s==VRPM||s==VCAP;}
    /** The cells' graph choice is used: the plain grid window only (skins and layouts have their own bars). */
    public static boolean graphsUsed(){int s=shape==OFF?lastShape:shape;return skinNow()==0&&!layout(s)&&s!=ROUND&&s!=MUSIC;}
    /** The cell counts shape s fills without a hole; the settings offer these only. */
    public static int[] counts(int s){return layout(s)?FloatLayouts.counts(s):s==MUSIC?MUSIC_COUNTS:COUNTS;}
    private static final int[] MUSIC_COUNTS={1,2};
    public static boolean fits(int s,int n){for(int v:counts(s))if(v==n)return true;return false;}
    /** n, or the next count up that shape s takes (its largest when there is none). */
    public static int fitCount(int s,int n){int[] cs=counts(s);for(int v:cs)if(v>=n)return v;return cs[cs.length-1];}
    /** Cells the shape shows of n (the short pill has room for two). */
    public static int used(int s,int n){return layout(s)?FloatLayouts.used(s,n):n;}
    public static final int[] COUNTS={1,2,4};
    public static final String[] SIZE_NAMES={"작게","보통","크게","아주 크게"};
    public static final float[] SIZES={.85f,1,1.5f,2};
    /** A cell at size 보통, px. */
    static final int CELL_W=124,CELL_H=64;
    /** A round gauge (원형 게이지) at size 보통: a disc this wide and high, laid out like the card (2 across). */
    static final int GAUGE=108;
    /** What a cell can show, in the order a tap on its settings row steps through, with the short name it shows. */
    public static final int[] CHOICES={Telemetry.BOOST,2,Telemetry.INTAKE,Telemetry.BAT_VOLTAGE,9,7,0,Telemetry.AMBIENT,Telemetry.FUEL_LEVEL,
        Telemetry.ENGINE_LOAD,10,Telemetry.CLUTCH_ODD,Telemetry.CLUTCH_EVEN,Telemetry.DPF_SOOT,Telemetry.DPF_TEMP};
    public static final String[] CHOICE_NAMES={"부스트","냉각수","흡기","배터리","페달","속도","RPM","외기온","연료","엔진 부하","브레이크","클러치 홀수","클러치 짝수","DPF 포집","DPF 온도"};
    /** -1 = automatic: boost, coolant, the four tyres (pressure, temperature beside it where there is room), both DCT clutches, then intake (diesel: DPF soot); a reading the car lacks gives its place to the next (resolve). */
    public static final int[] AUTO_PETROL={Telemetry.BOOST,2,FloatPanel.QUAD_P,FloatPanel.DCT2,Telemetry.INTAKE,Telemetry.ENGINE_LOAD,Telemetry.AVG_FUEL,Telemetry.RANGE},
        AUTO_DIESEL={Telemetry.BOOST,2,FloatPanel.QUAD_P,FloatPanel.DCT2,Telemetry.DPF_SOOT,Telemetry.ENGINE_LOAD,Telemetry.AVG_FUEL,Telemetry.RANGE};
    public static volatile int shape=OFF,count=4,size=1;
    /** The shape the 켬 switch brings back (the window was switched off from it). */
    public static volatile int lastShape=CARD;
    public static volatile boolean hideButton=true;
    public static final int[] cells={-1,-1,-1,-1,-1,-1,-1};
    /** Each cell's graph: a bar under the value, the last 30 s as a line, a row of lit segments (LED), or the number alone. */
    public static final int BAR=0,LINE=1,LED=2,NUM=3;
    public static final String[] GRAPH_NAMES={"막대","선 그래프","칸 막대","숫자만"};
    /** The window's corners: the theme's (N: two cut corners, hex themes: four, BMW / Rose: round), or a fixed one. */
    public static final int CORNER_THEME=0,CORNER_SQUARE=1,CORNER_ROUND=2,CORNER_CUT=3;
    public static final String[] CORNER_NAMES={"테마","직각","둥글게","사선 컷"};
    /** Colours: the theme's (accent, text, lines), the N blue whatever the theme, or white. Warnings stay amber / red. */
    public static final int COLOR_THEME=0,COLOR_BLUE=1,COLOR_WHITE=2;
    public static final String[] COLOR_NAMES={"테마","파랑","흰색"};
    public static volatile int corner=CORNER_THEME,color=COLOR_THEME;
    /** 투명도: how solid the window's body is over the screen behind (text, bars and lines stay solid). */
    public static final String[] ALPHA_NAMES={"진하게","보통","연하게"};
    static final int[] ALPHAS={0xf4,0xd8,0x80};
    /** The 투명도 setting as a factor on the skins' own body alpha (0xd8 = as drawn). */
    static float alphaF(){return ALPHAS[Math.max(0,Math.min(ALPHAS.length-1,alpha))]/(float)0xd8;}
    public static volatile int alpha=1;
    /** 스킨: the cells drawn like a dashboard layout (FloatSkin.NAMES); 0 = the plain window with its corners and colours;
     *  AUTO (계기판 따라, the default) = the skin of the dashboard screen last shown (screen, a ClusterPicker index); 0 = picked here:
     *  the day look or the night look (skinDay / skinNight) as dn (DayNight.AUTO / DAY / NIGHT) says. */
    public static final int AUTO=-1;
    public static volatile int screen=0;
    public static volatile int dn=DayNight.NIGHT,skinDay=FloatSkin.WHITE,skinNight=0;
    /** The day look is in use now (DayNight.HOLD: the car's day / night, held). */
    public static boolean dayNow(){return DayNight.pick(dn,DayNight.HOLD.night);}
    /** The skin drawn now. */
    public static int skinNow(){if(skin<0)return FloatSkin.forScreen(screen);int s=dayNow()?skinDay:skinNight;return s<0||s>=FloatSkin.NAMES.length?0:s;}
    /** Picks look s for the side in use now (day or night) and stops following the screen. */
    public static void setActiveSkin(int s){if(dayNow())skinDay=s;else skinNight=s;skin=0;}
    public static final String[] SKIN_NAMES=FloatSkin.NAMES;
    public static volatile int skin=AUTO;
    public static int skinCount(){return FloatSkin.NAMES.length;}
    /** 추천 presets (SettingsLayout.F_PRESETS order): shape, count, look (FloatSkin index), size, transparency.
     *  기본 = 카드 4 · 레이스 = 변속등 2 레이스 크게 진하게 · 미니 = 캡슐 1 작게 연하게 · 음악 = 음악 2. */
    private static final int[][] PRESETS={{CARD,4,0,1,1},{SHIFT,2,1,2,0},{PILL,1,0,0,2},{MUSIC,2,0,1,1}};
    /** Sets preset i and turns the window on; each cell keeps the reading picked for it. */
    public static void applyPreset(int i){if(i<0||i>=PRESETS.length)return;int[] p=PRESETS[i];
        shape=p[0];count=fitCount(p[0],p[1]);setActiveSkin(p[2]);size=p[3];alpha=p[4];}
    /** Whether the window is on and set exactly as preset i (the look: the one seen now). */
    public static boolean presetMatch(int i){if(i<0||i>=PRESETS.length||shape==OFF)return false;int[] p=PRESETS[i];
        return shape==p[0]&&count==fitCount(p[0],p[1])&&skin>=0&&skinNow()==p[2]&&size==p[3]&&alpha==p[4];}
    /** The round gauge shape is on: square cells, a disc each, no window body. */
    public static boolean round(){return round(shape==OFF?lastShape:shape);}
    public static boolean round(int s){return s==ROUND;}
    public static final int[] graphs={BAR,BAR,BAR,BAR,BAR,BAR,BAR};
    /** The line graph's memory: per cell the last HIST samples, one every HIST_MS (30 s); cleared when the cell's signal changes. */
    static final int HIST=60;static final long HIST_MS=500,HIST_GAP_MS=5000;
    private static final float[][] hist=new float[MAX_CELLS][HIST];private static final int[] histHead=new int[MAX_CELLS],histN=new int[MAX_CELLS],histSig={-1,-1,-1,-1,-1,-1,-1};
    private static final long[] histAt=new long[MAX_CELLS];
    /** The highest reading per cell (최고값), for the readings where a peak means something (boost, rpm, speed, pedal,
     *  temperatures, G); G and yaw keep the largest size either way. It starts over when the cell's reading changes, or
     *  when the reading has been missing for PEAK_IDLE_MS (the car was off). */
    static final long PEAK_IDLE_MS=600000;
    private static final float[] peak={Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN};private static final int[] peakSig={-1,-1,-1,-1,-1,-1,-1};private static final long[] peakAt=new long[MAX_CELLS];
    public static boolean peaks(int s){
        if(s==DCT2)return true;
        switch(s){case 0:case 1:case 2:case 7:case 9:case 10:case 11:case 12:case Telemetry.BOOST:case Telemetry.INTAKE:case Telemetry.ENGINE_LOAD:
            case Telemetry.THROTTLE_POS:case Telemetry.FUEL_RATE:case Telemetry.YAW:case Telemetry.STEERING_SPEED:case Telemetry.CLUTCH_ODD:case Telemetry.CLUTCH_EVEN:
            case Telemetry.CAT_TEMP:case Telemetry.DPF_TEMP:case Telemetry.EGT_CAT:case Telemetry.TIRE_TEMP_FL:case Telemetry.TIRE_TEMP_FR:case Telemetry.TIRE_TEMP_RL:case Telemetry.TIRE_TEMP_RR:return true;}
        return false;
    }
    private static boolean both(int s){return centred(s);}
    /** Readings that swing both ways around 0 (left / right: lateral G, steering angle, yaw, steering torque; accelerate / brake:
     *  longitudinal G) have 0 in the middle of their scale and fill from it. Everything else starts at the scale's left end
     *  (boost: 0 bar there). Their peak is the largest swing either way. */
    static boolean centred(int sig){int s=fmt(sig);return s==11||s==12||s==Telemetry.STEERING||s==Telemetry.YAW||s==Telemetry.STEER_TORQUE;}
    /** Half the scale of a centred reading: ±1.5 G, ±540° steering, ±60 °/s yaw, ±8 N·m steering torque. */
    private static float halfSpan(int s){return s==Telemetry.STEERING?540:s==Telemetry.YAW?60:s==Telemetry.STEER_TORQUE?8:1.5f;}
    /** The reading's place on its scale (0..1, NaN for none): centred ones around .5, the rest as the tiles show them. */
    /** Boost like a Defi gauge: vacuum (-1..0 bar) squeezed into the first eighteenth of the sweep, so on a 270 degree dial (which
     *  starts at half past seven) 0 bar sits at eight o'clock and the needle rests there at idle; boost (0..2.0 bar) has the rest, so
     *  1.0 bar is near twelve o'clock as on the Defi. */
    static final float VAC=1/18f;
    static float frac(int sig,float v){int s=fmt(sig);if(s==Telemetry.BOOST)return Float.isNaN(v)?Float.NaN:v<0?VAC*(Math.max(v,-1)+1):VAC+(1-VAC)*v/2f;
        if(centred(sig))return Float.isNaN(v)?Float.NaN:.5f+v/(2*halfSpan(s));return Tiles.fraction(s,v);}
    /** Where a reading's bar starts: the middle for a centred one, the left end otherwise. */
    static float zero(int sig){return centred(sig)?.5f:fmt(sig)==Telemetry.BOOST?VAC:0;}
    /** Cell k's highest reading so far, or NaN. */
    static float peak(int k,int sig){return !still&&k>=0&&k<MAX_CELLS&&peakSig[k]==sig?peak[k]:Float.NaN;}
    /** Drawing the settings' example window: no peaks (they belong to the real readings). */
    private static boolean still;
    /** A still picture is being drawn (the settings' previews): the 음악 line does not scroll there. */
    static boolean stillNow(){return still;}
    /** Partial redraw: ALL draws everything, STAT only what does not move (kept as the bitmap below), DYN the rest each frame.
     *  FloatSkin and the round gauge read PASS; FloatLayouts gets it as an argument. */
    static final int ALL=FloatLayouts.ALL,STAT=FloatLayouts.STAT,DYN=FloatLayouts.DYN;static int PASS=ALL;
    private static boolean st(){return PASS!=DYN;}
    private static boolean dy(){return PASS!=STAT;}
    /** The kept bitmap of the window's unmoving part (PASS STAT) and what it was drawn for. */
    private static android.graphics.Bitmap layer;private static int layerKey;
    /** Times the kept bitmap was drawn again (for the FLOAT log); off draws every part each frame, for a side-by-side look. */
    public static int layerBuilds;public static boolean layerOn=true;
    // ---- glide: bars, arcs and the rpm bar move from the last reading to the new one over GLIDE_MS (ease-out), the numbers jump as
    // before. The car sends every 40-80 ms, so a bar stepped about 12 times a second; gliding fills the frames in between. -----------
    public static final long GLIDE_MS=80;
    /** Off: every bar jumps (FloatService turns it off while /sdcard/i30pd_float_noglide exists, for a side-by-side look). */
    public static boolean glideOn=true;
    private static final int GLIDES=9;   // cells 0..7 and the rpm bar
    private static final float[] gFrom=new float[GLIDES],gTo=new float[GLIDES];private static final long[] gAt=new long[GLIDES];private static long drawNow;
    static{java.util.Arrays.fill(gTo,Float.NaN);}
    /** The bar position (0..1) to draw for slot i now: the target itself in a still picture, when off, at the first reading or with
     *  no reading; otherwise on its way from where it was shown when the target moved. */
    static float glide(int i,float f){
        if(still||!glideOn||i<0||i>=GLIDES){return f;}
        if(Float.isNaN(f)){gTo[i]=Float.NaN;return f;}
        if(Float.isNaN(gTo[i])){gTo[i]=f;gFrom[i]=f;gAt[i]=0;return f;}
        if(Math.abs(f-gTo[i])>1e-4f){gFrom[i]=glided(i,drawNow);gTo[i]=f;gAt[i]=drawNow;}
        return glided(i,drawNow);
    }
    private static float glided(int i,long now){
        if(gAt[i]==0)return gTo[i];float t=Math.max(0,Math.min(1,(now-gAt[i])/(float)GLIDE_MS));t=1-(1-t)*(1-t);
        return gFrom[i]+(gTo[i]-gFrom[i])*t;}
    /** A bar is still on its way: the window keeps drawing every frame until it lands. */
    public static boolean gliding(long now){
        if(!glideOn)return false;for(int i=0;i<GLIDES;i++)if(gAt[i]!=0&&!Float.isNaN(gTo[i])&&now-gAt[i]<GLIDE_MS)return true;return false;}
    /** Adds the cell's value to its line graph once every HIST_MS, and to its peak every time (called on every draw). */
    static void record(int k,int sig,float v,long now){
        if(peakSig[k]!=sig||now<peakAt[k]||now-peakAt[k]>PEAK_IDLE_MS){peakSig[k]=sig;peak[k]=Float.NaN;peakAt[k]=now;}
        if(!Float.isNaN(v)&&peaks(sig)){float a=both(sig)?Math.abs(v):v;if(Float.isNaN(peak[k])||a>peak[k])peak[k]=a;peakAt[k]=now;}
        if(histSig[k]!=sig||now<histAt[k]||histN[k]>0&&now-histAt[k]>HIST_GAP_MS){histSig[k]=sig;histN[k]=0;histHead[k]=0;histAt[k]=0;}   // a new signal, or the clock went back: start over
        if(histN[k]>0&&now-histAt[k]<HIST_MS)return;
        histAt[k]=now;hist[k][histHead[k]]=v;histHead[k]=(histHead[k]+1)%HIST;if(histN[k]<HIST)histN[k]++;
    }

    /** Records the cells without drawing (the dashboard's own frame, while the window is down), so a peak reached there
     *  counts and the line graph has no hole. */
    public static void track(int n,Telemetry.Sample[] q,VehicleProfile prof,long now){
        for(int i=0;i<n;i++){int sig=signal(i,prof);record(i,sig,value(q,sig),now);}
    }
    private static int lastLook;
    /** Every frame tick: records the line graphs and says whether the window would look different from the last tick
     *  (text, colour, bar end to the pixel, a new line-graph sample), so an unchanged window is not drawn again. */
    /** The shown text of a value as an int for the change hash (Tiles.textKey), no string built. */
    private static int key(int sig,float v){long k=Tiles.textKey(fmt(sig),v);return (int)(k^(k>>>32));}
    public static boolean tick(int n,int size,Telemetry.Sample[] q,VehicleProfile prof,long now){
        int h=17;h=h*31+skinNow();h=h*31+alpha;h=h*31+color;h=h*31+corner;h=h*31+Theme.current().id.hashCode();
        for(int i=0;i<n;i++){int sig=signal(i,prof);float v=value(q,sig),f=frac(sig,v);record(i,sig,v,now);
            h=h*31+sig;h=h*31+key(sig,v);if(sig==DCT2)h=h*31+valText(q,sig,v).hashCode();h=h*31+Tiles.level(fmt(sig),v);h=h*31+graphs[i];
            h=h*31+(Float.isNaN(f)?-1:Math.round(Math.max(0,Math.min(1,f))*cellW(CARD,size)));
            if(graphs[i]==LINE)h=h*31+histHead[i]*64+histN[i];
            float pk=peak(i,sig);if(!Float.isNaN(pk))h=h*31+key(sig,pk);
            if(quad(sig)){quadTexts(q,sig);for(int w=0;w<4;w++)h=h*31+QV[w].hashCode()+QL[w]+QT[w].hashCode()*7;}}
        int s=shape==OFF?lastShape:shape;
        h=h*31+shiftStep(q,prof,now);   // the shift light on the window's edge
        if(s==PILL||s==GEARBOX||s==SHIFT||s==WEDGE||s==TOWER||s==VRPM||s==VCAP){Telemetry.Sample rm=q[0],gm=q[8];float rf=rm!=null&&rm.valid?Tiles.fraction(0,rm.value):Float.NaN;
            h=h*31+(Float.isNaN(rf)?-1:Math.round(Math.max(0,Math.min(1,rf))*120));h=h*31+(gm!=null&&gm.valid?key(8,gm.value):0);
            Telemetry.Sample sm=q.length>Telemetry.GEAR_SELECTOR?q[Telemetry.GEAR_SELECTOR]:null;h=h*31+(sm!=null&&sm.valid?(int)sm.value+1:0);}   // P / R / N come from the selector
        if(s==MUSIC||strip(s))h=h*31+FloatMusic.key(strip(s)?now:-1);   // a scrolling 음악 띠 line moves every tick
        if((s==VRPM||s==VCAP)&&musicStrip)h=h*31+(vrMusic(s)?FloatMusic.key(now):7);
        h=h*31+s;boolean changed=h!=lastLook;lastLook=h;return changed;
    }

    private FloatPanel(){}
    /** Columns of a shape with n cells: a card is 2 wide from 2 cells, the strip one row, the side one column. */
    public static int cols(int shape,int n){return shape==STRIP?n:shape==SIDE?1:Math.min(2,n);}
    public static int rows(int shape,int n){return (n+cols(shape,n)-1)/cols(shape,n);}
    public static int cellW(int shape,int size){return Math.round((round(shape)?GAUGE:CELL_W)*SIZES[clampSize(size)]);}
    public static int cellH(int shape,int size){return Math.round((round(shape)?GAUGE:CELL_H)*SIZES[clampSize(size)]);}
    public static int width(int shape,int n,int size){return shape<=OFF?0:shape==MUSIC?Math.round(FloatMusic.W*SIZES[clampSize(size)]):layout(shape)?Math.round(FloatLayouts.w(shape,n)*SIZES[clampSize(size)]):cols(shape,n)*cellW(shape,size);}
    /** The window's height: the body, and the 음악 띠 under it when that is on. */
    public static int height(int shape,int n,int size){return bodyH(shape,n,size)+(strip(shape)?Math.round((FloatMusic.STRIP_H+FloatMusic.GAP)*SIZES[clampSize(size)]):0);}
    private static int bodyH(int shape,int n,int size){return shape<=OFF?0:shape==MUSIC?Math.round(FloatMusic.H*SIZES[clampSize(size)]):layout(shape)?Math.round(FloatLayouts.h(shape,n)*SIZES[clampSize(size)]):rows(shape,n)*cellH(shape,size);}
    /** Each shown cell's rectangle in the window (l, t, r, b at the size): taps and the settings' outline. */
    public static float[][] cellRects(int shape,int n,int size){int u=used(shape,n);float[][] o=new float[u][];float k=SIZES[clampSize(size)];
        for(int i=0;i<u;i++){if(layout(shape)||shape==MUSIC){float[] b=shape==MUSIC?FloatMusic.rect(n,i):FloatLayouts.rect(shape,n,i);o[i]=new float[]{b[0]*k,b[1]*k,b[2]*k,b[3]*k};}
            else{int cols=cols(shape,n),cw=cellW(shape,size),ch=cellH(shape,size);o[i]=new float[]{(i%cols)*cw,(i/cols)*ch,(i%cols+1)*cw,(i/cols+1)*ch};}}
        return o;}
    private static int clampSize(int s){return Math.max(0,Math.min(SIZES.length-1,s));}
    /** Where the window starts (screen 800 × 480, below the 52 px status bar): the card and the side column top right, the strip bottom middle. */
    private static boolean bottom(int s){return s==STRIP||s==PILL||s==SHIFT||s==TICK;}
    public static int defaultX(int shape,int n,int size){return bottom(shape)?(800-width(shape,n,size))/2:800-width(shape,n,size)-8;}
    public static int defaultY(int shape,int n,int size){return shape==VRPM||shape==VCAP?(480-height(shape,n,size))/2:bottom(shape)?480-height(shape,n,size)-10:62;}
    /** The next cell count after the current one (1, 2, 4, 1 ...). */
    public static int nextCount(int n){return n>=4?1:n>=2?4:2;}
    /** The signal cell k shows: its choice, or the automatic one when it has none or this car lacks it (a DPF value on a
     *  petrol, the DCT clutches on a 6MT). */
    public static int signal(int k,VehicleProfile p){return resolve(cells[k],k,p);}
    // ---- 타이어 4개: one cell with all four wheels (pressure or temperature) -------------------------------------------
    public static final int QUAD_P=2000,QUAD_T=2001;
    public static boolean quad(int s){return s==QUAD_P||s==QUAD_T;}
    /** DCT 클러치: both 7DCT clutches in one cell (odd · even °C); its bar, warning and peak follow the hotter one. */
    public static final int DCT2=2002;
    /** A cell choice that is not one signal (4 wheels, both clutches), saved like a real one. */
    public static boolean pseudo(int s){return quad(s)||s==DCT2;}
    private static String unit0(int s){String u=Telemetry.UNITS[fmt(s)];return u==null?"":u;}
    /** Wheel w (0 FL, 1 FR, 2 RL, 3 RR) of a 4-wheel cell. */
    public static int quadSig(int s,int w){return (s==QUAD_P?Telemetry.TPMS_FL:Telemetry.TIRE_TEMP_FL)+w;}
    /** The signal a cell formats and scales with: a wheel for the 4-wheel cells. */
    static int fmt(int s){return quad(s)?quadSig(s,0):s==DCT2?Telemetry.CLUTCH_ODD:s;}
    public static boolean available(int s,VehicleProfile p){return Tiles.available(fmt(s),p);}
    public static int groupOf(int s){return Tiles.groupOf(fmt(s));}
    /** A cell's value: the reading, or for 4 wheels the lowest pressure / the highest temperature. */
    static float value(Telemetry.Sample[] q,int sig){
        if(sig==DCT2){float a=value(q,Telemetry.CLUTCH_ODD),b=value(q,Telemetry.CLUTCH_EVEN);return Float.isNaN(a)?b:Float.isNaN(b)?a:Math.max(a,b);}
        if(!quad(sig)){if(sig<0||sig>=q.length)return Float.NaN;Telemetry.Sample m=q[sig];return m!=null&&m.valid?Telemetry.displayValue(sig,m.value):Float.NaN;}   // as the dashboard shows it: corrected speed, steering sign
        float r=Float.NaN;for(int w=0;w<4;w++){Telemetry.Sample m=q[quadSig(sig,w)];if(m==null||!m.valid)continue;float v=m.value;r=Float.isNaN(r)?v:sig==QUAD_P?Math.min(r,v):Math.max(r,v);}
        return r;}
    /** A cell's value as shown: both clutches as "odd·even", else the reading's text. */
    static String valText(Telemetry.Sample[] q,int sig,float v){
        if(sig!=DCT2||q==null)return Tiles.text(fmt(sig),v);
        return Tiles.text(Telemetry.CLUTCH_ODD,value(q,Telemetry.CLUTCH_ODD))+"·"+Tiles.text(Telemetry.CLUTCH_EVEN,value(q,Telemetry.CLUTCH_EVEN));}
    static final String[] QV=new String[4];static final int[] QL=new int[4];
    /** With the pressures (QUAD_P): each wheel's temperature ("21°"), or "" until the BCM has answered. */
    static final String[] QT=new String[4];
    /** The four wheels' texts and levels into QV / QL. */
    static void quadTexts(Telemetry.Sample[] q,int sig){for(int w=0;w<4;w++){int s=quadSig(sig,w);Telemetry.Sample m=q[s];float v=m!=null&&m.valid?m.value:Float.NaN;QV[w]=Tiles.text(s,v);QL[w]=Tiles.level(s,v);
        Telemetry.Sample t=sig==QUAD_P?q[Telemetry.TIRE_TEMP_FL+w]:null;QT[w]=t!=null&&t.valid&&!Float.isNaN(t.value)?Tiles.tyreTemp(t.value)+"°":"";}}
    /** The four wheels on one line (FL FR RL RR), for small places. */
    public static String inline(Telemetry.Sample[] q,int sig){quadTexts(q,sig);return QV[0]+" "+QV[1]+" · "+QV[2]+" "+QV[3];}
    /** 세로 띠 + RPM's own 자동 (it has the rpm as its bar, so no rpm row): boost, coolant, the four tyres, intake (diesel: soot),
     *  battery, outside air, and in the box under them (shown while no track plays) the range left, short enough for the large box. */
    public static final int[] AUTO_VR_PETROL={Telemetry.BOOST,2,QUAD_P,QUAD_T,DCT2,Telemetry.INTAKE,Telemetry.RANGE,Telemetry.ENGINE_LOAD,Telemetry.AVG_FUEL},
        AUTO_VR_DIESEL={Telemetry.BOOST,2,QUAD_P,QUAD_T,DCT2,Telemetry.DPF_SOOT,Telemetry.RANGE,Telemetry.ENGINE_LOAD,Telemetry.AVG_FUEL};
    public static int resolve(int c,int k,VehicleProfile p){boolean diesel=p!=null&&p.fuel==VehicleProfile.Fuel.DIESEL;
        if(c>=0&&(p==null||available(c,p)))return c;
        int s=shape==OFF?lastShape:shape;int[] a=s==VRPM?(diesel?AUTO_VR_DIESEL:AUTO_VR_PETROL):s==VCAP?(diesel?AUTO_VC_DIESEL:AUTO_VC_PETROL):diesel?AUTO_DIESEL:AUTO_PETROL;
        int j=0;for(int x:a)if(p==null||available(x,p)){if(j==k)return x;j++;}   // a reading this car lacks (no DCT clutches on a 6MT) gives its place to the next
        return a[Math.min(k,a.length-1)];}
    /** 세로 캡슐's own 자동: boost in the dial, then coolant, tyre pressures, both DCT clutches, tyre temperatures, intake (diesel: soot); a playing track takes the last two rows. */
    public static final int[] AUTO_VC_PETROL={Telemetry.BOOST,2,QUAD_P,DCT2,QUAD_T,Telemetry.INTAKE,Telemetry.ENGINE_LOAD,Telemetry.AVG_FUEL,Telemetry.RANGE},
        AUTO_VC_DIESEL={Telemetry.BOOST,2,QUAD_P,DCT2,QUAD_T,Telemetry.DPF_SOOT,Telemetry.ENGINE_LOAD,Telemetry.AVG_FUEL,Telemetry.RANGE};
    // ---- 변속 알림: like the tacho's shift dots, every shape's edge (a border and a coloured glow inside it) warms from yellow to red
    // over the last SHIFT_SPAN rpm before the shift point (설정 → RPM), then flashes the dots' blue at it -----------------------------
    /** 설정 → RPM's shift point as saved (0 = the powertrain's default); read with the window's other choices. */
    public static volatile int shiftTarget;
    static final int SHIFT_SPAN=1000,SHIFT_YEL=0xffffd23f,SHIFT_RED=0xffff3b30,SHIFT_FLASH=0xff4aa8ff;
    /** How near the shift point: 0..1 over the last SHIFT_SPAN rpm, 1 or more at it; NaN below that, or with no gear above. */
    static float shiftLevel(Telemetry.Sample[] q,VehicleProfile prof){
        if(prof==null||q==null||q.length<9)return Float.NaN;Telemetry.Sample rm=q[0],gm=q[8];
        if(rm==null||!rm.valid||gm==null||!gm.valid||Float.isNaN(rm.value)||Float.isNaN(gm.value))return Float.NaN;
        int g=(int)gm.value;if(g<1||g>=prof.topGear)return Float.NaN;
        float f=(rm.value-(ShiftGuide.target(shiftTarget,prof)-SHIFT_SPAN))/SHIFT_SPAN;return f<0?Float.NaN:f;}
    static boolean flashOn(long now){return now%180<90;}
    /** What the edge shows, for the change test: 0 nothing, 1..13 the warming steps, 20 / 21 the flash off / on. */
    static int shiftStep(Telemetry.Sample[] q,VehicleProfile prof,long now){
        float f=shiftLevel(q,prof);return Float.isNaN(f)?0:f>=1?(flashOn(now)?21:20):1+Math.round(f*12);}
    /** The edge round the window's body (w x h) at shift level f: the pill's round ends, the corner shape otherwise. A 3 px border,
     *  then GLOW rings inside it fading out, so the window looks lit from its rim (rings, not a gradient: the same on the car and host). */
    private static void shiftEdge(Canvas c,int shape,float w,float h,float k,float f,long now){
        int col;float a;
        if(f>=1){if(!flashOn(now))return;col=SHIFT_FLASH;a=1;}
        else{float t=Math.max(0,Math.min(1,f));col=mix(SHIFT_YEL,SHIFT_RED,t);a=.3f+.7f*t;}
        float sw=3*k,rad=shape==PILL?h/2:shape==VCAP?w/2:cornerShape()==1?10*k:4*k;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);p.setColor(withAlpha(col,a));r.set(sw/2,sw/2,w-sw/2,h-sw/2);c.drawRoundRect(r,rad,rad,p);
        final int GLOW=6;float rw=2*k;p.setStrokeWidth(rw);
        for(int i=0;i<GLOW;i++){float in=sw+rw*(i+.5f),rr=Math.max(0,rad-in+sw/2);p.setColor(withAlpha(col,a*.42f*(1-i/(float)GLOW)));
            r.set(in,in,w-in,h-in);c.drawRoundRect(r,rr,rr,p);}
        p.setStyle(Paint.Style.FILL);}
    private static int mix(int x,int y,float t){int o=0xff000000;for(int s=0;s<24;s+=8){int a=(x>>s)&255,b=(y>>s)&255;o|=Math.round(a+(b-a)*t)<<s;}return o;}
    private static int withAlpha(int c,float a){return Math.round(255*Math.max(0,Math.min(1,a)))<<24|c&0xffffff;}
    /** The reading groups a cell can choose from on this car (Tiles.GROUPS indices, single readings only). */
    public static int[] groups(VehicleProfile p){return Tiles.groups(p,false);}
    public static int[] group(int g,VehicleProfile p){int[] base=Tiles.group(g,p,false);
        int[] lead=g==Tiles.groupOf(Telemetry.TPMS_FL)?new int[]{QUAD_P,QUAD_T}:g==Tiles.groupOf(Telemetry.CLUTCH_ODD)?new int[]{DCT2}:null;   // the tyre group starts with the 4-wheel cells, the clutch group with both clutches in one
        if(lead==null)return base;int n=0;int[] o=new int[base.length+lead.length];
        for(int s:lead)if(p==null||available(s,p))o[n++]=s;for(int s:base)o[n++]=s;
        int[] r=new int[n];System.arraycopy(o,0,r,0,n);return r;}
    /** The next choice for a cell: automatic, then CHOICES in order, then automatic again. */
    public static int next(int current){if(current<0)return CHOICES[0];for(int i=0;i<CHOICES.length;i++)if(CHOICES[i]==current)return i+1<CHOICES.length?CHOICES[i+1]:-1;return -1;}
    /** The name as the window shows it (translated). */
    static String label(int sig){return com.veloster.i18n.UiText.t(name(sig));}
    public static String name(int sig){if(sig==DCT2)return "DCT 클러치";if(sig==QUAD_P)return "타이어 공기압";if(sig==QUAD_T)return "타이어 온도";if(sig<0||sig>=Telemetry.LABELS.length)return "—";for(int i=0;i<CHOICES.length;i++)if(CHOICES[i]==sig)return CHOICE_NAMES[i];return Telemetry.LABELS[sig];}

    private static final Paint p=new Paint(3);private static final RectF r=new RectF();
    private static final int WARM=0xffffaa3c,HOT=0xffff4b3c;
    /** This frame's colours: N palette values, which Theme.map turns into the theme's. */
    static int BG,EDGE,DIV,TXT,MUT,TRACK,ACC;
    /** A colour of the N palette in this frame's colour choice (the theme's, or as is). */
    static int themed(int c){return color==COLOR_THEME?Theme.current().map(c):c;}
    private static void colours(){
        Theme th=color==COLOR_THEME?Theme.current():null;
        BG=m(th,0xd805070a)&0xffffff|ALPHAS[Math.max(0,Math.min(ALPHAS.length-1,alpha))]<<24;EDGE=m(th,0xff3a4650);DIV=m(th,0x993a4650);TXT=m(th,0xfff2f6f8);MUT=m(th,0xff8fa0a8);TRACK=m(th,0xff2a343b);
        ACC=color==COLOR_WHITE?0xffe8eef2:m(th,0xff5bc8f5);
    }
    private static int m(Theme th,int c){return th==null?c:th.map(c);}
    /** The corner drawn: 0 square, 1 round, 2 four cut corners, 3 the N cut (top right and bottom left). */
    public static int cornerShape(){
        if(corner==CORNER_SQUARE)return 0;if(corner==CORNER_ROUND)return 1;if(corner==CORNER_CUT)return 2;
        int s=Theme.current().shape;return s==Theme.SHAPE_ROUND?1:s==Theme.SHAPE_HEX?2:3;
    }
    private static final android.graphics.Path edge=new android.graphics.Path();
    /** The window's body and its 1 px edge in the chosen corner shape. */
    static void outline(Canvas c,float w,float h,float k,int fill,int line){
        int cs=cornerShape();float a=cs==1?10*k:cs==0?2*k:11*k;
        p.setStyle(Paint.Style.FILL);p.setColor(fill);
        if(cs<=1){r.set(0,0,w,h);c.drawRoundRect(r,a,a,p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(line);r.set(.5f,.5f,w-.5f,h-.5f);c.drawRoundRect(r,a,a,p);}
        else for(int pass=0;pass<2;pass++){float o=pass==0?0:.5f,l=o,t=o,rr=w-o,b=h-o;edge.reset();
            if(cs==2){edge.moveTo(l+a,t);edge.lineTo(rr-a,t);edge.lineTo(rr,t+a);edge.lineTo(rr,b-a);edge.lineTo(rr-a,b);edge.lineTo(l+a,b);edge.lineTo(l,b-a);edge.lineTo(l,t+a);}
            else{edge.moveTo(l,t);edge.lineTo(rr-a,t);edge.lineTo(rr,t+a);edge.lineTo(rr,b);edge.lineTo(l+a,b);edge.lineTo(l,b-a);}
            edge.close();if(pass==1){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(line);}c.drawPath(edge,p);}
        p.setStyle(Paint.Style.FILL);
    }
    private static final int[] SIG=new int[MAX_CELLS],G=new int[MAX_CELLS];
    private static final FloatSkin.Cell[] LCELLS={new FloatSkin.Cell(),new FloatSkin.Cell(),new FloatSkin.Cell(),new FloatSkin.Cell(),new FloatSkin.Cell(),new FloatSkin.Cell(),new FloatSkin.Cell()};
    /** A cell's texts and scale positions for a skin or a layout. */
    private static void fillCell(FloatSkin.Cell d,int i,int sig,float v,int g,boolean first){float pk=peak(i,sig);
        d.name=label(sig);d.en=com.veloster.i18n.UiText.en(name(sig)).toUpperCase(java.util.Locale.US);d.sn="ko".equals(com.veloster.i18n.UiText.language())?d.name:d.en;   // skins in Korean too; only the 8-bit font keeps English
        d.val=valText(LAST_Q,sig,v);String u=Telemetry.UNITS[fmt(sig)];d.unit=u==null?"":u;d.f=glide(i,frac(sig,v));d.lv=Tiles.level(fmt(sig),v);
        d.zero=zero(sig);d.pk=Float.isNaN(pk)?null:Tiles.text(fmt(sig),pk);d.pf=Float.isNaN(pk)?Float.NaN:frac(sig,pk);
        d.first=first;d.bar=g!=NUM;d.quad=null;
        if(quad(sig)){quadTexts(LAST_Q,sig);System.arraycopy(QV,0,d.q4,0,4);System.arraycopy(QL,0,d.l4,0,4);System.arraycopy(QT,0,d.t4,0,4);d.quad=d.q4;d.val=QV[0]+" "+QV[1]+" · "+QV[2]+" "+QV[3];d.f=Float.NaN;d.lv=0;}}
    /** Draws the window (shape, n cells, size) at 0,0 from the samples (index = signal). */
    private static VehicleProfile gearProf;
    /** The gear as the dashboard pages show it (P / R / N from the selector, M4 in the manual gate), "-" for none. */
    static String gear(Telemetry.Sample[] q){
        Telemetry.Sample g=q[8],s=q.length>Telemetry.GEAR_SELECTOR?q[Telemetry.GEAR_SELECTOR]:null;float gv=g!=null&&g.valid?g.value:Float.NaN;
        if(gearProf==null)return Float.isNaN(gv)?"-":Tiles.text(8,gv);
        String t=Telemetry.gearText(gv,s!=null&&s.valid?s.value:Float.NaN,gearProf);return "—".equals(t)?"-":t;}
    /** Day/night: the last source ("oem", "lamp", "clock", "none"; null before the first look), when it was last judged, whether the first look is done. Main thread only: FloatService.run() (before tick), draw, and the dashboard settings pane (drawFloatPane, 1 Hz redraw) call dayNight; whichever comes first in a second does the work. */
    public static String dnSource;
    /** A new service adopts the current state at once. */
    public static void dayNightReset(){dnPrimed=false;}private static long dnAt;private static boolean dnPrimed;
    /** Judged once a second from the stock screen mode / illumination, the headlamps, then the clock; the first look is adopted at once (no night colours for 3 s after start). */
    public static void dayNight(Telemetry.Sample[] q,long now){
        if(dnPrimed&&now-dnAt<1000&&now>=dnAt)return;dnAt=now;
        Telemetry.Sample l=q!=null&&q.length>Telemetry.HEAD_LAMP?q[Telemetry.HEAD_LAMP]:null;int lamp=l!=null&&l.valid?(l.value>0?1:0):DayNight.UNKNOWN;
        java.util.Calendar cal=java.util.Calendar.getInstance();int phase=DayPhase.of(cal.get(java.util.Calendar.MONTH)+1,cal.get(java.util.Calendar.HOUR_OF_DAY)*60+cal.get(java.util.Calendar.MINUTE));
        int dim=DayNight.oemDim,il=DayNight.oemIllum;boolean raw=DayNight.rawNight(dim,il,lamp,phase);
        if(!dnPrimed){dnPrimed=true;DayNight.HOLD.night=raw;}else DayNight.HOLD.update(raw,now);
        dnSource=DayNight.source(dim,il,lamp,phase);}
    public static void draw(Canvas c,int shape,int n,int size,Telemetry.Sample[] q,VehicleProfile prof,long now){
        dayNight(q,now);
        for(int i=0;i<n;i++){SIG[i]=signal(i,prof);G[i]=graphs[i];}
        gearProf=prof;
        draw(c,shape,n,size,q,SIG,G,0,true,now);
        float sl=shape>OFF?shiftLevel(q,prof):Float.NaN;
        if(!Float.isNaN(sl))shiftEdge(c,shape,width(shape,n,size),bodyH(shape,n,size),SIZES[clampSize(size)],sl,now);
    }
    /** The window as draw does, but without recording (the settings' preview and sheets: example values must not reach
     *  the peaks or the line graphs). */
    public static void drawStill(Canvas c,int shape,int n,int size,Telemetry.Sample[] q,VehicleProfile prof,long now){
        for(int i=0;i<n;i++){SIG[i]=signal(i,prof);G[i]=graphs[i];}
        still=true;try{draw(c,shape,n,size,q,SIG,G,0,false,now);}finally{still=false;}
    }
    private static Telemetry.Sample[] demo;
    /** Fixed example readings for the settings (a car at 3,500 rpm in 3rd, boost +0.42 bar, coolant 88 °C ...): a live
     *  preview redrew nine windows every frame and lagged. Signals without one sit at the middle of their bar. */
    public static Telemetry.Sample[] example(){
        if(demo!=null)return demo;Telemetry.Sample[] d=new Telemetry.Sample[Telemetry.LABELS.length];
        for(int i=0;i<d.length;i++){float f0=Tiles.fraction(i,0),f1=Tiles.fraction(i,1),v=Float.isNaN(f0)||Float.isNaN(f1)||f1==f0?0:(.55f-f0)/(f1-f0);
            if(i==0)v=3500;else if(i==1||i==2)v=88;else if(i==7)v=87;else if(i==8)v=3;else if(i==9)v=35;else if(i==Telemetry.BOOST)v=.42f;
            else if(i==Telemetry.INTAKE)v=34;else if(i==Telemetry.BAT_VOLTAGE)v=13.8f;else if(i==Telemetry.AMBIENT)v=14;
            d[i]=new Telemetry.Sample(v,true,false,true,false,0);}
        return demo=d;
    }
    /** One cell showing sig with graph g at 0,0 (the settings' value chooser); its line graph is cell k's memory. */
    public static void drawCell(Canvas c,int shape,int size,Telemetry.Sample[] q,int sig,int g,int k,long now){
        still=true;try{body(c,CARD,1,size,q,new int[]{sig},new int[]{g},k,false,now);}finally{still=false;}
    }
    /** first: the cell index of the first cell drawn (its line-graph memory); rec: the real window, which feeds the line graphs. */
    private static Telemetry.Sample[] LAST_Q;
    private static void draw(Canvas c,int shape,int n,int size,Telemetry.Sample[] q,int[] sigs,int[] gs,int first,boolean rec,long now){
        if(shape==MUSIC){colours();LAST_Q=q;drawNow=now;PASS=ALL;FloatMusic.shape(c,SIZES[clampSize(size)],n,q,sigs,first,rec,now);return;}   // drawn whole: its text changes every second
        body(c,shape,n,size,q,sigs,gs,first,rec,now);
        if(strip(shape))FloatMusic.strip(c,width(shape,n,size),bodyH(shape,n,size)+FloatMusic.GAP*SIZES[clampSize(size)],SIZES[clampSize(size)],now);
        if(vrMusic(shape)){float[] b=FloatLayouts.musicBox(shape,n);float k=SIZES[clampSize(size)];if(b!=null)FloatMusic.box(c,b[0]*k,b[1]*k,b[2]*k,b[3]*k,k,now);}   // drawn each frame over the body
    }
    /** The window without the 음악 띠. */
    private static void body(Canvas c,int shape,int n,int size,Telemetry.Sample[] q,int[] sigs,int[] gs,int first,boolean rec,long now){
        colours();LAST_Q=q;drawNow=now;PASS=ALL;FloatLayouts.vrMusic=vrMusic(shape);
        if(still||!layerOn){parts(c,shape,n,size,q,sigs,gs,first,rec,now);return;}
        // partial redraw: what does not move is drawn once into a bitmap, again when anything it shows changes: the shape, count, size,
        // look, alpha, theme, language, or a cell's reading, graph, mode (a value or none yet, a peak or none, a line with history) or warning
        FloatLayouts.vrMusic=vrMusic(shape);
        int key=17;key=key*31+shape;key=key*31+(FloatLayouts.vrMusic?1:0);key=key*31+n;key=key*31+size;key=key*31+skinNow();key=key*31+alpha;key=key*31+first;key=key*31+Theme.current().id.hashCode();
        for(int j=0;j<n;j++){int i=first+j,sig=sigs[j];float v=value(q,sig);key=key*31+sig;key=key*31+gs[j];key=key*31+label(sig).hashCode();
            key=key*31+(Float.isNaN(frac(sig,v))?1:0)+(Float.isNaN(peak(i,sig))?2:0)+(gs[j]==LINE&&drawn(i,sig)?4:0)+8*Tiles.level(fmt(sig),v);}
        int bw=Math.max(1,width(shape,n,size)),bh=Math.max(1,bodyH(shape,n,size));
        if(layer==null||layerKey!=key||layer.getWidth()!=bw||layer.getHeight()!=bh){
            if(layer==null||layer.getWidth()!=bw||layer.getHeight()!=bh)layer=android.graphics.Bitmap.createBitmap(bw,bh,android.graphics.Bitmap.Config.ARGB_8888);
            else layer.eraseColor(0);
            PASS=STAT;try{parts(new Canvas(layer),shape,n,size,q,sigs,gs,first,false,now);}finally{PASS=ALL;}layerKey=key;layerBuilds++;}
        c.drawBitmap(layer,0,0,null);
        PASS=DYN;try{parts(c,shape,n,size,q,sigs,gs,first,rec,now);}finally{PASS=ALL;}
    }
    private static void parts(Canvas c,int shape,int n,int size,Telemetry.Sample[] q,int[] sigs,int[] gs,int first,boolean rec,long now){
        int cols=cols(shape,n),rows=rows(shape,n),cw=cellW(shape,size),ch=cellH(shape,size),w=cols*cw,h=rows*ch;float k=SIZES[clampSize(size)];
        if(round(shape)){   // no window body: each reading is its own disc
            for(int j=0;j<n;j++){int i=first+j,sig=sigs[j];float v=value(q,sig);if(rec)record(i,sig,v,now);
                gauge(c,(j%cols)*cw,(j/cols)*ch,cw,k,sig,v,peak(i,sig),i);}
            return;}
        int sk=skinNow();
        if(layout(shape)){   // a layout: its own inside, the skin's colours
            int u=FloatLayouts.used(shape,n);
            for(int j=0;j<u;j++){int i=first+j,sig=sigs[j];float v=value(q,sig);if(rec)record(i,sig,v,now);fillCell(LCELLS[j],i,sig,v,gs[j],j==0&&n>1);}
            Telemetry.Sample rm=q[0],gm=q[8];float rpm=glide(GLIDES-1,rm!=null&&rm.valid?Tiles.fraction(0,rm.value):Float.NaN);String gear=gear(q);
            float af=ALPHAS[Math.max(0,Math.min(ALPHAS.length-1,alpha))]/(float)0xd8;
            FloatLayouts.draw(c,shape,n,k,sk,af,LCELLS,rpm,gear,PASS);
            return;}
        if(sk!=FloatSkin.PLAIN){   // a skin: its own body and cells
            if(st())FloatSkin.body(c,sk,w,h,k,ALPHAS[Math.max(0,Math.min(ALPHAS.length-1,alpha))]/(float)0xd8);
            for(int j=0;j<n;j++){int i=first+j,sig=sigs[j];float v=value(q,sig);if(rec)record(i,sig,v,now);
                fillCell(FloatSkin.CELL,i,sig,v,gs[j],j==0&&n>1);
                FloatSkin.cell(c,sk,(j%cols)*cw,(j/cols)*ch,cw,ch,k);}
            return;}
        if(st()){outline(c,w,h,k,BG,EDGE);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);
            p.setColor(DIV);for(int i=1;i<cols;i++)c.drawLine(i*cw,8*k,i*cw,h-8*k,p);for(int i=1;i<rows;i++)c.drawLine(8*k,i*ch,w-8*k,i*ch,p);}
        p.setStyle(Paint.Style.FILL);
        for(int j=0;j<n;j++){int i=first+j,sig=sigs[j];float x=(j%cols)*cw,y=(j/cols)*ch;float v=value(q,sig);
            int lv=Tiles.level(fmt(sig),v),col=lv==2?HOT:lv==1?WARM:TXT,g=gs[j];if(rec)record(i,sig,v,now);
            if(st())text(c,label(sig),x+8*k,y+17*k,12*k,MUT,Paint.Align.LEFT,Typeface.DEFAULT_BOLD);
            boolean barred=!(Float.isNaN(frac(sig,v))||g==NUM||g==LINE||g==LED||quad(sig));   // only the plain bar has a kept part (its track)
            if(!dy()){if(barred){float bl=x+8*k,br=x+cw-8*k,bt=y+ch-9*k,bb=bt+4*k;p.setColor(TRACK);r.set(bl,bt,br,bb);c.drawRoundRect(r,2*k,2*k,p);}continue;}
            String unit=Telemetry.UNITS[fmt(sig)],val=valText(q,sig,v);float f=g==LINE||g==NUM?frac(sig,v):glide(i,frac(sig,v));
            float pk=peak(i,sig),pf=Float.isNaN(pk)?Float.NaN:frac(sig,pk);
            if(quad(sig)){quadTexts(q,sig);text(c,unit0(sig),x+cw-8*k,y+16*k,10*k,MUT,Paint.Align.RIGHT,Typeface.DEFAULT);quad(c,x,y,cw,ch,k,TXT,DIV,Typeface.DEFAULT_BOLD);continue;}   // the unit where the peak would be
            if(!Float.isNaN(pk)&&g!=LINE){String ps="▲ "+Tiles.text(fmt(sig),pk);   // the peak, small, top right (left out when the name leaves no room)
                if(width(label(sig),12*k,Typeface.DEFAULT_BOLD)+width(ps,10*k,Typeface.DEFAULT)+20*k<=cw)text(c,ps,x+cw-8*k,y+16*k,10*k,MUT,Paint.Align.RIGHT,Typeface.DEFAULT);}
            if(Float.isNaN(f)||g==NUM||g==LINE&&!drawn(i,sig)){   // nothing to graph (no value yet, or the line has no history): the value alone, large, in the middle
                float vw=width(val,30*k,Typeface.DEFAULT_BOLD),uw=width(unit,12*k,Typeface.DEFAULT),x0=x+(cw-(vw+3*k+uw))/2;
                text(c,val,x0,y+ch-13*k,30*k,Float.isNaN(v)?MUT:col,Paint.Align.LEFT,Typeface.DEFAULT_BOLD);text(c,unit,x0+vw+3*k,y+ch-13*k,12*k,MUT,Paint.Align.LEFT,Typeface.DEFAULT);continue;}
            if(g==LINE){   // the value on the name's line (the unit gives way to a long name), the last 30 s below it
                float nw=width(label(sig),12*k,Typeface.DEFAULT_BOLD),vw=width(val,21*k,Typeface.DEFAULT_BOLD),uw=width(unit,10*k,Typeface.DEFAULT);
                boolean fits=nw+vw+uw+26*k<=cw;float right=x+cw-8*k;
                if(fits){text(c,unit,right,y+20*k,10*k,MUT,Paint.Align.RIGHT,Typeface.DEFAULT);right-=uw+3*k;}
                text(c,val,right,y+21*k,21*k,col,Paint.Align.RIGHT,Typeface.DEFAULT_BOLD);
                line(c,i,sig,x+8*k,y+29*k,x+cw-8*k,y+ch-7*k,k,lv);
                if(!Float.isNaN(pf)){float t2=y+29*k,b2=y+ch-7*k,py=b2-Math.max(0,Math.min(1,pf))*(b2-t2);p.setColor(0x99ffffff);   // the peak as a dotted line across the graph
                    for(float xx=x+8*k;xx<x+cw-8*k;xx+=5*k)c.drawRect(xx,py-.5f,Math.min(xx+2.5f*k,x+cw-8*k),py+.5f,p);}
                continue;}
            float uw=width(unit,11*k,Typeface.DEFAULT);
            text(c,unit,x+cw-10*k,y+ch-15*k,11*k,MUT,Paint.Align.RIGHT,Typeface.DEFAULT);
            text(c,val,x+cw-10*k-uw-3*k,y+ch-15*k,26*k,col,Paint.Align.RIGHT,Typeface.DEFAULT_BOLD);
            float bl=x+8*k,br=x+cw-8*k;
            if(g==LED){   // lit segments, the top fifth amber
                int segs=Math.max(8,Math.round((br-bl)/(8*k)));float sw=(br-bl)/segs,bt=y+ch-11*k,bb=bt+6*k;float z0=zero(sig),fc=Float.isNaN(f)?z0:Math.max(0,Math.min(1,f));int litA=Math.round(Math.min(z0,fc)*segs),lit=Math.round(Math.max(z0,fc)*segs);
                int pseg=Float.isNaN(pf)?-1:Math.min(segs-1,Math.max(0,Math.round(Math.max(0,Math.min(1,pf))*segs)-1));
                for(int s2=0;s2<segs;s2++){p.setColor(s2>=litA&&s2<lit?(s2>=segs*.8f?WARM:lv==2?HOT:ACC):s2==pseg?0x88ffffff:TRACK);c.drawRect(bl+s2*sw+k,bt,bl+(s2+1)*sw-k,bb,p);}
                continue;}
            float bt=y+ch-9*k,bb=bt+4*k;if(PASS==ALL){p.setColor(TRACK);r.set(bl,bt,br,bb);c.drawRoundRect(r,2*k,2*k,p);}
            f=Math.max(0,Math.min(1,f));
            float zero=zero(sig),a=bl+(br-bl)*Math.min(zero,f),b=bl+(br-bl)*Math.max(zero,f);
            p.setColor(lv==2?HOT:lv==1?WARM:ACC);r.set(a,bt,Math.max(b,a+2),bb);c.drawRoundRect(r,2*k,2*k,p);
            if(zero>0){p.setColor(0x99ffffff);float z=bl+(br-bl)*zero;c.drawRect(z-.5f,bt-2*k,z+.5f,bb+2*k,p);}
            if(!Float.isNaN(pf)){p.setColor(0xffffffff);float z=bl+(br-bl)*Math.max(0,Math.min(1,pf));c.drawRect(z-k,bt-2*k,z+k,bb+2*k,p);}   // the peak, a white tick
        }
    }
    /** A round gauge in the d x d square at x,y: a dark disc, a 270 degree ring open at the bottom (filled from zero, so
     *  boost below zero fills the other way), the value in the middle, its unit under it and its name in the gap. */
    private static void gauge(Canvas c,float x,float y,float d,float k,int sig,float v,float pk,int slot){
        float cx=x+d/2,cy=y+d/2,rad=d/2-2*k,sw=7*k,ar=rad-sw/2-4*k;
        if(quad(sig)){if(st()){p.setStyle(Paint.Style.FILL);p.setColor(BG);c.drawCircle(cx,cy,rad,p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(EDGE);c.drawCircle(cx,cy,rad-.5f,p);p.setStyle(Paint.Style.FILL);
                p.setColor(DIV);c.drawRect(cx-.5f,cy-d*.2f,cx+.5f,cy+d*.2f,p);text(c,label(sig),cx,cy+ar*.84f,10*k,MUT,Paint.Align.CENTER,Typeface.DEFAULT_BOLD);}
            if(!dy())return;quadTexts(LAST_Q,sig);float s2=Math.min(15*k,d*.14f);
            for(int w=0;w<4;w++)text(c,QV[w],cx+(w%2==0?-1:1)*d*.2f,cy+(w<2?-d*.06f:d*.16f),s2,QL[w]==2?HOT:QL[w]==1?WARM:TXT,Paint.Align.CENTER,Typeface.DEFAULT_BOLD);
            return;}
        int lv=Tiles.level(fmt(sig),v),col=lv==2?HOT:lv==1?WARM:TXT;float f=glide(slot,frac(sig,v));
        r.set(cx-ar,cy-ar,cx+ar,cy+ar);
        if(st()){p.setStyle(Paint.Style.FILL);p.setColor(BG);c.drawCircle(cx,cy,rad,p);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(EDGE);c.drawCircle(cx,cy,rad-.5f,p);
            p.setStrokeWidth(sw);p.setStrokeCap(Paint.Cap.ROUND);p.setColor(TRACK);c.drawArc(r,135,270,false,p);p.setStrokeCap(Paint.Cap.BUTT);p.setStyle(Paint.Style.FILL);
            text(c,label(sig),cx,cy+ar*.84f,11*k,MUT,Paint.Align.CENTER,Typeface.DEFAULT_BOLD);}   // in the ring's open gap, clear of the disc edge
        if(!dy())return;
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(sw);p.setStrokeCap(Paint.Cap.ROUND);
        if(!Float.isNaN(f)){f=Math.max(0,Math.min(1,f));float zero=zero(sig),a=Math.min(zero,f),b=Math.max(zero,f);
            p.setColor(lv==2?HOT:lv==1?WARM:ACC);c.drawArc(r,KeyPath.arc(135+270*a),KeyPath.arc(Math.max(1,270*(b-a))),false,p);}
        p.setStrokeCap(Paint.Cap.BUTT);p.setStyle(Paint.Style.FILL);
        float pf=Float.isNaN(pk)?Float.NaN:frac(sig,pk);
        if(!Float.isNaN(pf)){double a=Math.toRadians(135+270*Math.max(0,Math.min(1,pf)));float o=ar+sw/2+1*k,in=ar-sw/2-1*k;   // the peak: a white tick across the ring
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2*k);p.setColor(0xffffffff);
            c.drawLine(cx+(float)Math.cos(a)*in,cy+(float)Math.sin(a)*in,cx+(float)Math.cos(a)*o,cy+(float)Math.sin(a)*o,p);p.setStyle(Paint.Style.FILL);
            text(c,"▲ "+Tiles.text(fmt(sig),pk),cx,cy-ar*.42f,9*k,MUT,Paint.Align.CENTER,Typeface.DEFAULT);}
        String val=valText(LAST_Q,sig,v),unit=Telemetry.UNITS[fmt(sig)];float size=24*k,room=ar*1.5f;
        p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(size);float vw=p.measureText(val);if(vw>room)size*=room/vw;
        text(c,val,cx,cy+size*.36f,size,Float.isNaN(v)?MUT:col,Paint.Align.CENTER,Typeface.DEFAULT_BOLD);
        if(unit!=null&&!unit.isEmpty())text(c,unit,cx,cy+size*.36f+13*k,10*k,MUT,Paint.Align.CENTER,Typeface.DEFAULT);
    }
    /** Cell k's line graph has at least two samples with a place on the scale. */
    private static boolean drawn(int k,int sig){int n=histN[k],ok=0;if(histSig[k]!=sig)return false;
        for(int j=0;j<n&&ok<2;j++)if(!Float.isNaN(frac(sig,hist[k][(histHead[k]-n+j+HIST*2)%HIST])))ok++;return ok>=2;}
    private static final android.graphics.Path path=new android.graphics.Path();
    /** Cell k's last 30 s in the box l,t-r,b: a filled area under a line, scaled to the signal's bar range, the latest value a dot at the right. */
    private static void line(Canvas c,int k,int sig,float l,float t,float r2,float b,float s,int lv){
        int n=histN[k];if(n<2)return;
        float px=(r2-l)/(HIST-1);boolean open=false;float lastX=0,lastY=0,firstX=0;path.reset();
        for(int j=0;j<n;j++){float v=hist[k][(histHead[k]-n+j+HIST*2)%HIST],f=frac(sig,v);
            if(Float.isNaN(f))continue;f=Math.max(0,Math.min(1,f));
            float xx=r2-(n-1-j)*px,yy=b-f*(b-t);if(!open){path.moveTo(xx,b);path.lineTo(xx,yy);firstX=xx;open=true;}else path.lineTo(xx,yy);lastX=xx;lastY=yy;}
        if(!open)return;
        path.lineTo(lastX,b);path.close();p.setStyle(Paint.Style.FILL);p.setColor(lv==2?0x55ff4b3c:lv==1?0x55ffaa3c:ACC&0xffffff|0x44000000);c.drawPath(path,p);
        path.reset();open=false;
        for(int j=0;j<n;j++){float v=hist[k][(histHead[k]-n+j+HIST*2)%HIST],f=frac(sig,v);if(Float.isNaN(f))continue;f=Math.max(0,Math.min(1,f));
            float xx=r2-(n-1-j)*px,yy=b-f*(b-t);if(!open){path.moveTo(xx,yy);open=true;}else path.lineTo(xx,yy);}
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.6f*s);p.setColor(lv==2?HOT:lv==1?WARM:ACC);c.drawPath(path,p);p.setStyle(Paint.Style.FILL);
        p.setColor(0xffffffff);c.drawCircle(lastX,lastY,2.2f*s,p);
    }
    /** The four wheels (QV / QL, QT) in the cell under its name, as a car seen from above (FloatPanel.tyres). */
    static void quad(Canvas c,float x,float y,float cw,float ch,float k,int ink,int line,Typeface face){
        tyres(c,x+4*k,y+20*k,cw-8*k,ch-22*k,k,QV,QL,QT,ink,line,face);
    }
    /** A car seen from above, an H on its side: the front axle on top, the rear below, a spine between, a tyre at each end; each
     *  wheel's pressure beside its tyre (amber / red when low) and, when known, its temperature under it, small. Fits the box x,y,w,h. */
    static void tyres(Canvas c,float x,float y,float w,float h,float k,String[] v,int[] lv,String[] t,int ink,int line,Typeface face){
        boolean lt=FloatSkin.light(skinNow());boolean temps=t!=null&&(t[0].length()+t[1].length()+t[2].length()+t[3].length())>0;
        float s=Math.min(18*k,h/2.35f),aw=Math.min(w*.09f,8*k);
        // fit the width: the temperatures go first (a narrow column), then the numbers shrink
        float need=2*aw+2*(Math.max(width(v[0],s,face),width(v[1],s,face))+Math.max(3*k,s*.3f)+4*k);
        float tws=0;if(temps)for(int i=0;i<4;i++)tws=Math.max(tws,width(t[i],s*.62f,Typeface.DEFAULT));
        if(temps&&need+2*(tws+2*k)>w)temps=false;else if(temps)need+=2*(tws+2*k);
        if(need>w)s*=w/need;
        float ts=s*.62f,cx=x+w/2,tw=Math.max(3*k,s*.3f),th=s*1.05f;
        float ty=y+h*.30f,by=y+h*.78f;   // the axles' heights; each wheel's numbers sit on its axle's line, the temperature on the outside
        float lw=Math.max(1,1.4f*k);fill(c,cx-aw,ty-lw/2,cx+aw,ty+lw/2,line);fill(c,cx-aw,by-lw/2,cx+aw,by+lw/2,line);fill(c,cx-lw/2,ty,cx+lw/2,by,line);   // the H on its side
        for(int i=0;i<4;i++){boolean right=i%2==1;float ay=i<2?ty:by,tx=right?cx+aw:cx-aw;int col=lt?FloatSkin.warn(lv[i],ink,true):lv[i]==2?HOT:lv[i]==1?WARM:ink;
            r.set(tx-tw/2,ay-th/2,tx+tw/2,ay+th/2);p.setStyle(Paint.Style.FILL);p.setColor(lv[i]>0?col:line);c.drawRoundRect(r,tw/2,tw/2,p);
            float lx=right?tx+tw/2+3*k:tx-tw/2-3*k,base=ay+s*.36f,pw=width(v[i],s,face);Paint.Align al=right?Paint.Align.LEFT:Paint.Align.RIGHT;
            text(c,v[i],lx,base,s,col,al,face);
            if(temps&&!t[i].isEmpty())text(c,t[i],right?lx+pw+2*k:lx-pw-2*k,base,ts,lt?0xff6e6e73:MUT,al,Typeface.DEFAULT);}
    }
    private static void fill(Canvas c,float l,float tp,float rr,float bt,int color){p.setStyle(Paint.Style.FILL);p.setColor(color);c.drawRect(l,tp,rr,bt,p);}
    private static void text(Canvas c,String s,float x,float y,float size,int color,Paint.Align a,Typeface face){
        p.setTypeface(face);p.setTextSize(size);p.setTextAlign(a);p.setColor(color);c.drawText(s,x,y,p);}
    private static float width(String s,float size,Typeface face){p.setTypeface(face);p.setTextSize(size);return p.measureText(s);}
}
