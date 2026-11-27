package com.veloster.vehicle;

import com.veloster.i18n.UiText;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.util.Locale;

/**
 * 설정 (header gear): categories on the left, the chosen pane on the right, over the dashboard body. Draws and
 * hit-tests only; VehicleDash applies the returned action codes, saves them and opens the dialogs. Panes:
 * 화면 (show, hide and order the screens), 테마, 플로팅 (the floating window), 기록 (기록실), 백업 (settings backup), 글꼴
 * (the navigation's system font: FontManager) and 정보 (version, credits and the USB debugging switch from My Apps). The features follow the i30 PD build's settings (sung9155/i30-pd-dashboard, MIT); the layout is this build's.
 */
public final class SettingsPanel {
    public static final int SCREENS=0,THEME=1,FLOAT=2,DRIVE=3,SOUND=4,RECORDS=5,BACKUP=6,FONT=7,INFO=8,CATS=9;
    public static final String[] CAT_NAMES={"화면","테마","플로팅","주행 모드","사운드","기록","백업","글꼴","정보"};
    public static final String[] THEME_NAMES={"N","레드","와인","코퍼","앰버","샴페인","네이비","람보","그린","세이지","에메랄드","틸","아이스","그라파이트","BMW","나이트","퍼플","마젠타","로즈","모노"};
    public static final String[] PRESET_NAMES={"기본","레이스","미니","음악"};
    public static final String[] DN_NAMES={"자동","낮","밤"};
    // Action codes returned by hit().
    public static final int NONE=-1,DONE=1,CAT=100,SCREEN_TOGGLE=200,SCREEN_UP=300,SCREEN_DOWN=400,THEME_PICK=600,
        F_ON=700,F_OFF=701,F_SHAPE=702,F_PRESET=710,F_COUNT=720,F_SIZE=730,F_ALPHA=740,F_FOLLOW=750,F_OWN=751,F_DN=760,
        F_SKIN_DAY=770,F_SKIN_NIGHT=771,F_CELLS=772,F_MUSIC=780,F_HIDE=781,R_LIST=800,R_CLEAR=801,B_SAVE=900,B_RESTORE=901,
        D_COLOR_ON=1000,D_COLOR_OFF=1001,D_SCREEN_ON=1002,D_SCREEN_OFF=1003,D_TARGET=1010,
        S_ENGINE_ON=1100,S_ENGINE_OFF=1101,S_ENGINE=1102,S_EXHAUST=1103,S_VOL=1110,S_BEEP_ON=1120,S_BEEP_OFF=1121,S_BEEP_SOUND=1122,S_BEEP_VOL=1130,
        S_CHIME_KMH=1140,S_CHIME_SOUND=1141,S_FX_VOL=1150,S_FLOAT_ENGINE=1160,S_FLOAT_BEEP=1161,S_TEST_BEEP=1170,S_TEST_CHIME=1171,S_YIELD=1172,S_BOOST=1173,
        U_ON=1200,U_OFF=1201,FONT_UP=1300,FONT_DOWN=1301,FONT_SCAN=1302,FONT_APPLY=1303,FONT_REBOOT=1304,FONT_PICK=1310;
    /** Engine sound voices (EngineSynth.ENGINES / EXHAUSTS order) and shift beeps (ShiftBeep.SOUNDS order), from the i30 PD build. */
    public static final String[] SOUND_ENGINES={"직렬 4기통 터보","직렬 6기통 터보","V6 트윈터보","V8 크로스플레인","V8 플랫플레인",
        "4기통 · 2.0 터보 핫해치","4기통 · 고회전 캠 전환","4기통 · 수평대향 터보","4기통 · 랠리 안티랙","4기통 · 경량 자연흡기","4기통 · 디젤 터보"};
    /** 안내·통화 양보 (SoundYield.OFF, DUCK, STOP) and 속도 따라 키우기 (SoundYield.speedBoost levels 0..3), from i30 PD v1.18.0. */
    public static final String[] SOUND_YIELDS={"끔","안내 때 줄이기","안내 때 멈추기"},SOUND_BOOSTS={"끔","약 (+3 dB)","중 (+6 dB)","강 (+9 dB)"};
    public static final String[] SOUND_EXHAUSTS={"순정","스포츠 머플러","중통 제거","풀 배기 · 팝앤뱅"};
    public static final String[] BEEP_SOUNDS={"삐빅","삑","상승 3음","레이스 버저","틱틱",
        "삐빅 · 3번","삐빅 · 4번 빠르게","연타 · 5번","삐빅 · 낮게","삐빅 · 높게","두 번 · 길고 짧게","상승 2음","하강 2음","하강 3음","빠른 상승 4음","5도 위로","옥타브 위로","맑은 차임","두 음 차임","낮은 차임","유리 톡","디지털 3틱","짧은 펄스 · 낮게","긴 비프","낮은 톤"};

    // ---- geometry (800 x 480) ------------------------------------------------------------------------------------
    static final float CAT_L=18,CAT_R=166,CAT_T=60,CAT_H=36,CAT_STEP=40,PANE_L=180,PANE_R=782,TOP=60,BOTTOM=472;
    static final float DONE_T=420,DONE_B=468;
    static final float ROW_T=60,ROW_H=46,ROW_STEP=51,ARROW_W=34,COL_W=(PANE_R-PANE_L-2*10)/3;
    /** The screen list in three columns of seven (20 screens). */
    static final int ROWS=7;
    static float rowL(int i){return PANE_L+(i/ROWS)*(COL_W+10);}
    static float upL(int i){return rowL(i)+COL_W-2*ARROW_W-6;}
    static float downL(int i){return rowL(i)+COL_W-ARROW_W;}
    static final int THEME_COLS=5;
    static final float TILE_W=(PANE_R-PANE_L-(THEME_COLS-1)*8)/THEME_COLS,TILE_H=94;
    static final float F_ROW_T=62,F_ROW_H=36,F_ROW_STEP=41,F_LABEL_W=88,F_CTRL_L=PANE_L+F_LABEL_W,F_CTRL_R=560,PREVIEW_L=572,PREVIEW_T=62,PREVIEW_B=378;
    static final float TOGGLE_T1=386,TOGGLE_T2=430,TOGGLE_H=38;
    /** 사운드: the 양보 and 속도 buttons at the top of the right column. */
    static final float YIELD_T=PREVIEW_T,BOOST_T=PREVIEW_T+F_ROW_STEP;
    /** 정보: the USB debugging ON / OFF buttons (the same OEM request as My Apps → USB 디버깅). */
    static final float USB_T=402,USB_H=44,USB_W=170,USB_OFF_L=PANE_R-USB_W,USB_ON_L=USB_OFF_L-8-USB_W;
    /** 글꼴: the list (six rows a page) on the left, the preview and the state on the right, apply / reboot below. */
    public static final int FONT_ROWS=6;
    static final float FL_R=470,FL_ROW_H=46,FL_STEP=50,FB_T=366,FB_H=38,FP_L=482,FP_B=300,FS_T=308,FS_B=404,FA_T=414,FA_H=44,FA_SPLIT=640;
    static float fontRow(int i){return TOP+2+i*FL_STEP;}

    static boolean in(float x,float y,float l,float t,float r,float b){return x>=l&&x<=r&&y>=t&&y<=b;}
    static float catTop(int i){return CAT_T+i*CAT_STEP;}
    static float rowTop(int i){return ROW_T+(i%ROWS)*ROW_STEP;}
    static float tileL(int i){return PANE_L+(i%THEME_COLS)*(TILE_W+8);}
    static float tileT(int i){return TOP+2+(i/THEME_COLS)*(TILE_H+8);}
    static float fRow(int i){return F_ROW_T+i*F_ROW_STEP;}
    /** n equal chips across the control column of a floating row. */
    static float chipL(int i,int n){float w=(F_CTRL_R-F_CTRL_L-(n-1)*6)/n;return F_CTRL_L+i*(w+6);}
    static float chipW(int n){return (F_CTRL_R-F_CTRL_L-(n-1)*6)/n;}

    /** The action under (x, y) for the open category, or NONE. */
    public static int hit(int cat,float x,float y){
        for(int i=0;i<CATS;i++)if(in(x,y,CAT_L,catTop(i),CAT_R,catTop(i)+CAT_H))return CAT+i;
        if(in(x,y,CAT_L,DONE_T,CAT_R,DONE_B))return DONE;
        if(x<PANE_L)return NONE;
        switch(cat){
            case SCREENS:{
                int[] o=DashboardPage.order();
                for(int i=0;i<o.length;i++){float t=rowTop(i),b=t+ROW_H;
                    if(in(x,y,upL(i),t,upL(i)+ARROW_W,b))return SCREEN_UP+i;
                    if(in(x,y,downL(i),t,downL(i)+ARROW_W,b))return SCREEN_DOWN+i;
                    if(in(x,y,rowL(i),t,upL(i)-6,b))return SCREEN_TOGGLE+i;}
                return NONE;}
            case THEME:
                for(int i=0;i<Theme.IDS.length;i++)if(in(x,y,tileL(i),tileT(i),tileL(i)+TILE_W,tileT(i)+TILE_H))return THEME_PICK+i;
                return NONE;
            case FLOAT:return floatHit(x,y);
            case SOUND:{int a;
                if((a=chips(x,y,0,2,S_ENGINE_ON))!=NONE)return a;
                if((a=chips(x,y,1,2,S_ENGINE))!=NONE)return a;
                if((a=chips(x,y,2,3,S_VOL))!=NONE)return a;
                if((a=chips(x,y,3,2,S_BEEP_ON))!=NONE)return a;
                if(in(x,y,F_CTRL_L,fRow(4),F_CTRL_R,fRow(4)+F_ROW_H))return S_BEEP_SOUND;
                if((a=chips(x,y,5,3,S_BEEP_VOL))!=NONE)return a;
                if((a=chips(x,y,6,2,S_CHIME_KMH))!=NONE)return a;
                if((a=chips(x,y,7,3,S_FX_VOL))!=NONE)return a;
                if((a=chips(x,y,8,2,S_FLOAT_ENGINE))!=NONE)return a;
                if(in(x,y,PREVIEW_L,YIELD_T,PANE_R,YIELD_T+F_ROW_H))return S_YIELD;
                if(in(x,y,PREVIEW_L,BOOST_T,PANE_R,BOOST_T+F_ROW_H))return S_BOOST;
                if(in(x,y,PREVIEW_L,TOGGLE_T1,PANE_R,TOGGLE_T1+TOGGLE_H))return S_TEST_BEEP;
                if(in(x,y,PREVIEW_L,TOGGLE_T2,PANE_R,TOGGLE_T2+TOGGLE_H))return S_TEST_CHIME;
                return NONE;}
            case DRIVE:{int a;
                if((a=chips(x,y,0,2,D_COLOR_ON))!=NONE)return a;
                if((a=chips(x,y,1,2,D_SCREEN_ON))!=NONE)return a;
                for(int k=0;k<DriveModes.NAMES.length;k++)if(in(x,y,F_CTRL_L,fRow(3+k),F_CTRL_R,fRow(3+k)+F_ROW_H))return D_TARGET+k;
                return NONE;}
            case RECORDS:
                if(in(x,y,PANE_L,402,PANE_L+290,446))return R_LIST;
                if(in(x,y,PANE_R-290,402,PANE_R,446))return R_CLEAR;
                return NONE;
            case BACKUP:
                if(in(x,y,PANE_L,250,PANE_L+290,294))return B_SAVE;
                if(in(x,y,PANE_R-290,250,PANE_R,294))return B_RESTORE;
                return NONE;
            case FONT:
                for(int i=0;i<FONT_ROWS;i++)if(in(x,y,PANE_L,fontRow(i),FL_R,fontRow(i)+FL_ROW_H))return FONT_PICK+i;
                if(in(x,y,PANE_L,FB_T,PANE_L+58,FB_T+FB_H))return FONT_UP;
                if(in(x,y,PANE_L+64,FB_T,PANE_L+122,FB_T+FB_H))return FONT_DOWN;
                if(in(x,y,PANE_L+128,FB_T,FL_R,FB_T+FB_H))return FONT_SCAN;
                if(in(x,y,FP_L,FA_T,FA_SPLIT-4,FA_T+FA_H))return FONT_APPLY;
                if(in(x,y,FA_SPLIT+4,FA_T,PANE_R,FA_T+FA_H))return FONT_REBOOT;
                return NONE;
            case INFO:
                if(in(x,y,USB_ON_L,USB_T,USB_ON_L+USB_W,USB_T+USB_H))return U_ON;
                if(in(x,y,USB_OFF_L,USB_T,USB_OFF_L+USB_W,USB_T+USB_H))return U_OFF;
                return NONE;
            default:return NONE;
        }
    }
    private static int chips(float x,float y,int row,int n,int base){
        float t=fRow(row);if(y<t||y>t+F_ROW_H)return NONE;
        for(int i=0;i<n;i++)if(x>=chipL(i,n)&&x<=chipL(i,n)+chipW(n))return base+i;
        return NONE;
    }
    private static int floatHit(float x,float y){
        int shape=FloatPanel.shape==FloatPanel.OFF?FloatPanel.lastShape:FloatPanel.shape;
        int a;
        if((a=chips(x,y,0,2,F_ON))!=NONE)return a;
        if((a=chips(x,y,1,PRESET_NAMES.length,F_PRESET))!=NONE)return a;
        if(in(x,y,F_CTRL_L,fRow(2),F_CTRL_R,fRow(2)+F_ROW_H))return F_SHAPE;
        if((a=chips(x,y,3,FloatPanel.counts(shape).length,F_COUNT))!=NONE)return a;
        if((a=chips(x,y,4,FloatPanel.SIZE_NAMES.length,F_SIZE))!=NONE)return a;
        if((a=chips(x,y,5,FloatPanel.ALPHA_NAMES.length,F_ALPHA))!=NONE)return a;
        if((a=chips(x,y,6,2,F_FOLLOW))!=NONE)return a;
        if((a=chips(x,y,7,DN_NAMES.length,F_DN))!=NONE)return a;
        if((a=chips(x,y,8,2,F_SKIN_DAY))!=NONE)return a;
        if(in(x,y,F_CTRL_L,fRow(9),F_CTRL_R,fRow(9)+F_ROW_H))return F_CELLS;
        if(in(x,y,PREVIEW_L,TOGGLE_T1,PANE_R,TOGGLE_T1+TOGGLE_H))return F_MUSIC;
        return NONE;
    }

    // ---- drawing -------------------------------------------------------------------------------------------------
    private static final int BG=0xff040506,PANEL=0xff101315,LINE=0xff303638,BLUE=0xffa4d9ef,WHITE=0xffe1edf2,MUTED=0xff91a5ae,DIM=0xff233b46,OFFCELL=0xff111415;
    private final ThemedPaint p=new ThemedPaint(3);
    private final Path path=new Path();
    private final RectF rect=new RectF();
    /** What the panes show that the dashboard keeps: the page on screen, the backup file, the app version. */
    public static final class State{public int cat,page;public String backupPath="",backupTime="",version="",usbStatus="";
        /** 주행 모드: accent follows the mode, the mode opens its screen, the screen per mode (-1 원래 화면), the mode now (-1 none). */
        public boolean modeColor,modeScreen;
        /** 사운드: switches, voices and volumes (percent). */
        public boolean engineOn,beepOn,floatEngine,floatBeep;public int engine,exhaust,yield,boost,engineVol=70,beep,beepVol=70,chimeKmh,chime,fxVol=70;public int[] modeTargets=new int[DriveModes.NAMES.length];public int modeNow=-1;
        /** 글꼴: the last scan (null before the first one ends), the row picked, the first row shown, and work in progress. */
        public FontManager.Snapshot fonts;public int fontPick=-1,fontTop;public boolean fontBusy;}

    public void draw(Canvas c,State s,long now){
        p.setColor(BG);c.drawRect(0,53,800,480,p);
        for(int i=0;i<CATS;i++)button(c,UiText.t(CAT_NAMES[i]),CAT_L,catTop(i),CAT_R-CAT_L,CAT_H,i==s.cat,16);
        button(c,UiText.t("완료"),CAT_L,DONE_T,CAT_R-CAT_L,DONE_B-DONE_T,false,16);
        p.setColor(LINE);c.drawRect(PANE_L-7,TOP,PANE_L-6,BOTTOM,p);
        switch(s.cat){
            case SCREENS:screens(c,s);break;
            case THEME:themes(c);break;
            case FLOAT:floating(c,now);break;
            case DRIVE:drive(c,s);break;
            case SOUND:sound(c,s);break;
            case RECORDS:records(c);break;
            case BACKUP:backup(c,s);break;
            case FONT:fonts(c,s);break;
            default:info(c,s);
        }
    }
    private void screens(Canvas c,State s){
        int[] o=DashboardPage.order();
        for(int i=0;i<o.length;i++){
            int page=o[i];boolean on=DashboardPage.isShown(page);float t=rowTop(i),l=rowL(i),r=upL(i)-6;
            panel(c,l,t,r-l,ROW_H,on?PANEL:OFFCELL,on?LINE:0xff252a2c);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setColor(on?BLUE:MUTED);c.drawRect(l+8,t+14,l+28,t+34,p);p.setStyle(Paint.Style.FILL);
            if(on){p.setStrokeWidth(3);c.drawLine(l+12,t+24,l+17,t+29,p);c.drawLine(l+17,t+29,l+25,t+18,p);}
            fitted(c,UiText.t(DashboardPage.NAMES[page]),l+36,t+21,14,r-l-42,on?WHITE:MUTED);
            fitted(c,(page==s.page?UiText.t("지금 화면")+" · ":"")+UiText.t(DashboardPage.DESC[page]),l+36,t+38,10,r-l-42,page==s.page?BLUE:MUTED);
            button(c,"▲",upL(i),t,ARROW_W,ROW_H,false,14);button(c,"▼",downL(i),t,ARROW_W,ROW_H,false,14);
        }
        text(c,UiText.t("줄을 누르면 보이기·숨기기, ▲▼로 순서 변경 · 탭 줄을 길게 눌러도 열립니다"),PANE_R,470,12,MUTED,false,Paint.Align.RIGHT);
    }
    private void volRow(Canvas c,int row,String name,int v){label(c,row,name);chipRow(c,row,new String[]{"−",v+"%","+"},-1);}
    private void sound(Canvas c,State s){
        String[] onOff={UiText.t("켜기"),UiText.t("끄기")};
        label(c,0,UiText.t("엔진 사운드"));chipRow(c,0,onOff,s.engineOn?0:1);
        label(c,1,UiText.t("엔진 · 배기"));chipRow(c,1,new String[]{UiText.t(SOUND_ENGINES[s.engine])+" ›",UiText.t(SOUND_EXHAUSTS[s.exhaust])+" ›"},-1);
        volRow(c,2,UiText.t("엔진 볼륨"),s.engineVol);
        label(c,3,UiText.t("변속 알림음"));chipRow(c,3,onOff,s.beepOn?0:1);
        label(c,4,UiText.t("알림 소리"));button(c,UiText.t(BEEP_SOUNDS[s.beep])+"  ›",F_CTRL_L,fRow(4),F_CTRL_R-F_CTRL_L,F_ROW_H,false,14);
        volRow(c,5,UiText.t("알림 볼륨"),s.beepVol);
        label(c,6,UiText.t("과속 차임"));chipRow(c,6,new String[]{(s.chimeKmh<=0?UiText.t("끔"):s.chimeKmh+" km/h")+" ›",UiText.t(SpeedChime.NAMES[s.chime])+" ›"},-1);
        volRow(c,7,UiText.t("효과음 볼륨"),s.fxVol);
        label(c,8,UiText.t("플로팅 중"));chipRow(c,8,new String[]{UiText.t("엔진 사운드")+(s.floatEngine?" ON":" OFF"),UiText.t("변속 알림")+(s.floatBeep?" ON":" OFF")},-1);
        button(c,UiText.t("양보")+" · "+UiText.t(SOUND_YIELDS[s.yield])+"  ›",PREVIEW_L,YIELD_T,PANE_R-PREVIEW_L,F_ROW_H,false,13);
        button(c,UiText.t("속도")+" · "+UiText.t(SOUND_BOOSTS[s.boost])+"  ›",PREVIEW_L,BOOST_T,PANE_R-PREVIEW_L,F_ROW_H,false,13);
        float pt=BOOST_T+F_ROW_STEP;
        panel(c,PREVIEW_L,pt,PANE_R-PREVIEW_L,PREVIEW_B-pt-8,0xff0b0f12,LINE);
        String[] notes={UiText.t("차의 알림 채널(앞 스피커)로 냅니다"),UiText.t("음악 위에 섞여 들리고, 소리를 낼 때"),UiText.t("음악이 잠깐 작아질 수 있습니다"),
            UiText.t("받은 RPM·페달·부스트로 만든 소리"),UiText.t("후진 중이거나 RPM이 없으면 조용합니다"),
            UiText.t("양보: 내비 안내 때 줄이거나 멈춤, 통화 때 멈춤"),UiText.t("속도: 40~110 km/h에서 바람 소리만큼 키움"),UiText.t("i30 PD 포팅에서 가져옴 · 실차 미확인")};
        float y=pt+22;for(String n:notes){fitted(c,n,PREVIEW_L+10,y,12,PANE_R-PREVIEW_L-20,MUTED);y+=24;}
        button(c,UiText.t("변속음 미리 듣기"),PREVIEW_L,TOGGLE_T1,PANE_R-PREVIEW_L,TOGGLE_H,false,14);
        button(c,UiText.t("차임 미리 듣기"),PREVIEW_L,TOGGLE_T2,PANE_R-PREVIEW_L,TOGGLE_H,false,14);
    }
    private void drive(Canvas c,State s){
        label(c,0,UiText.t("모드 색"));chipRow(c,0,new String[]{UiText.t("켜기"),UiText.t("끄기")},s.modeColor?0:1);
        label(c,1,UiText.t("모드별 화면"));chipRow(c,1,new String[]{UiText.t("켜기"),UiText.t("끄기")},s.modeScreen?0:1);
        fitted(c,UiText.t("모드를 바꾸면 그 모드에 정한 화면이 열립니다"),PANE_L+4,fRow(2)+F_ROW_H/2-2,12,F_CTRL_R-PANE_L-8,MUTED);
        fitted(c,UiText.t("‘원래 화면’이면 처음 보던 화면으로 돌아갑니다"),PANE_L+4,fRow(2)+F_ROW_H/2+14,12,F_CTRL_R-PANE_L-8,MUTED);
        for(int k=0;k<DriveModes.NAMES.length;k++){
            label(c,3+k,DriveModes.NAMES[k]);int t=s.modeTargets[k];
            button(c,(t<0?UiText.t("원래 화면"):UiText.t(DashboardPage.NAMES[DashboardPage.normalize(t)]))+"  ›",F_CTRL_L,fRow(3+k),F_CTRL_R-F_CTRL_L,F_ROW_H,false,14);
        }
        String now=s.modeNow<0?UiText.t("받지 못함"):DriveModes.label(s.modeNow);
        panel(c,PREVIEW_L,PREVIEW_T,PANE_R-PREVIEW_L,120,0xff0b0f12,LINE);
        text(c,UiText.t("지금 모드"),PREVIEW_L+12,PREVIEW_T+22,12,MUTED,true,Paint.Align.LEFT);
        text(c,now,PREVIEW_L+(PANE_R-PREVIEW_L)/2,PREVIEW_T+86,s.modeNow<0?18:38,s.modeNow<0?MUTED:WHITE,true,Paint.Align.CENTER);
        fitted(c,UiText.t("순정 N 모드 서비스의 주행 모드 알림을 읽기만 합니다"),PREVIEW_L+4,PREVIEW_T+150,11,PANE_R-PREVIEW_L-8,MUTED);
        fitted(c,UiText.t("모드 색: SPORT·N 빨강, N CUSTOM 보라, ECO 초록"),PREVIEW_L+4,PREVIEW_T+172,11,PANE_R-PREVIEW_L-8,MUTED);
    }
    private void themes(Canvas c){
        for(int i=0;i<Theme.IDS.length;i++){
            Theme th=Theme.of(Theme.IDS[i]);boolean sel=th==Theme.current();
            float l=tileL(i),t=tileT(i);
            // swatch drawn in that theme's own colours (raw: ThemedPaint would map them again)
            p.setRawColor(th.map(0xff101315));c.drawRect(l,t,l+TILE_W,t+TILE_H,p);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(sel?3:1);p.setRawColor(sel?th.accent:th.map(0xff303638));c.drawRect(l+1,t+1,l+TILE_W-1,t+TILE_H-1,p);p.setStyle(Paint.Style.FILL);
            p.setRawColor(th.accent);c.drawRect(l+12,t+14,l+44,t+30,p);
            p.setRawColor(th.danger);c.drawRect(l+50,t+14,l+60,t+30,p);
            p.setRawColor(th.map(0xffe1edf2));p.setTextSize(16);p.setTypeface(Typeface.DEFAULT_BOLD);c.drawText(UiText.t(THEME_NAMES[i]),l+12,t+58,p);
            p.setRawColor(th.map(0xff91a5ae));p.setTextSize(12);p.setTypeface(Typeface.DEFAULT);c.drawText(th.label+(sel?"  ●":""),l+12,t+80,p);
        }
    }
    private static String shapeName(int s){return UiText.t(s>=0&&s<FloatPanel.SHAPE_NAMES.length?FloatPanel.SHAPE_NAMES[s]:"—");}
    private void floating(Canvas c,long now){
        boolean off=FloatPanel.shape==FloatPanel.OFF;int shape=off?FloatPanel.lastShape:FloatPanel.shape;
        boolean pick=FloatPanel.skin>=0,round=FloatPanel.round(shape);
        label(c,0,UiText.t("플로팅 창"));chipRow(c,0,new String[]{UiText.t("켜기"),UiText.t("끄기")},off?1:0);
        label(c,1,UiText.t("추천"));int pre=-1;for(int i=0;i<PRESET_NAMES.length;i++)if(FloatPanel.presetMatch(i))pre=i;chipRow(c,1,UiText.list(PRESET_NAMES),pre);
        label(c,2,UiText.t("모양"));button(c,shapeName(shape)+"  ›",F_CTRL_L,fRow(2),F_CTRL_R-F_CTRL_L,F_ROW_H,false,14);
        int[] counts=FloatPanel.counts(shape);String[] cn=new String[counts.length];int ci=-1;
        for(int i=0;i<counts.length;i++){cn[i]=counts[i]+UiText.t("칸");if(counts[i]==FloatPanel.count)ci=i;}
        label(c,3,UiText.t("칸 수"));chipRow(c,3,cn,ci);
        label(c,4,UiText.t("크기"));chipRow(c,4,UiText.list(FloatPanel.SIZE_NAMES),FloatPanel.size);
        label(c,5,UiText.t("투명도"));chipRow(c,5,UiText.list(FloatPanel.ALPHA_NAMES),FloatPanel.alpha);
        label(c,6,UiText.t("색감"));chipRow(c,6,new String[]{UiText.t("계기판 따라"),UiText.t("직접 고르기")},round?-1:pick?1:0);
        label(c,7,UiText.t("낮/밤"));chipRow(c,7,UiText.list(DN_NAMES),pick&&!round?FloatPanel.dn:-1);
        label(c,8,UiText.t("낮·밤 색감"));
        chipRow(c,8,new String[]{UiText.t("낮")+" · "+UiText.t(FloatPanel.SKIN_NAMES[FloatPanel.skinDay])+" ›",UiText.t("밤")+" · "+UiText.t(FloatPanel.SKIN_NAMES[FloatPanel.skinNight])+" ›"},-1);
        label(c,9,UiText.t("칸 내용"));button(c,UiText.t("칸마다 보일 값 고르기")+"  ›",F_CTRL_L,fRow(9),F_CTRL_R-F_CTRL_L,F_ROW_H,false,14);
        // preview with example readings, scaled into its box
        panel(c,PREVIEW_L,PREVIEW_T,PANE_R-PREVIEW_L,PREVIEW_B-PREVIEW_T,0xff0b0f12,LINE);
        text(c,UiText.t("미리보기"),PREVIEW_L+10,PREVIEW_T+18,11,MUTED,true,Paint.Align.LEFT);
        int n=FloatPanel.count,size=FloatPanel.size;float w=FloatPanel.width(shape,n,size),h=FloatPanel.height(shape,n,size);
        if(w>0&&h>0){
            float bw=PANE_R-PREVIEW_L-20,bh=PREVIEW_B-PREVIEW_T-36,k=Math.min(1,Math.min(bw/w,bh/h));
            int saved=c.save();
            c.translate(PREVIEW_L+10+(bw-w*k)/2,PREVIEW_T+28+(bh-h*k)/2);c.scale(k,k);
            try{FloatPanel.drawStill(c,shape,n,size,FloatPanel.example(),VehicleProfile.VELOSTER_N,now);}
            catch(RuntimeException e){android.util.Log.w("VehicleDash","floating preview",e);}
            c.restoreToCount(saved);
        }
        if(off)text(c,UiText.t("꺼짐"),PREVIEW_L+(PANE_R-PREVIEW_L)/2,PREVIEW_B-10,12,MUTED,true,Paint.Align.CENTER);
        toggle(c,UiText.t("음악 띠"),PREVIEW_L,TOGGLE_T1,FloatPanel.musicStrip||shape==FloatPanel.MUSIC);
        fitted(c,UiText.t("창을 누르면 계기판, 길게 누르면 내 앱, 끌면 이동"),PREVIEW_L+4,TOGGLE_T2+24,12,PANE_R-PREVIEW_L-8,MUTED);
    }
    private void records(Canvas c){
        float w=(PANE_R-PANE_L-16)/3;
        for(int i=0;i<DriveStats.RUNS.length;i++){
            float l=PANE_L+i*(w+8);
            panel(c,l,TOP+2,w,330,PANEL,LINE);
            text(c,String.format(Locale.US,"%.0f→%.0f km/h",DriveStats.RUNS[i][0],DriveStats.RUNS[i][1]),l+12,TOP+28,16,BLUE,true,Paint.Align.LEFT);
            String[] e=Records.entries(Records.runKey(i));
            if(e.length==0)text(c,"—",l+12,TOP+64,18,MUTED,false,Paint.Align.LEFT);
            for(int k=0;k<e.length;k++){float y=TOP+66+k*54;
                text(c,(k+1)+".",l+12,y,15,MUTED,false,Paint.Align.LEFT);
                text(c,String.format(Locale.US,"%.2f s",Records.value(e[k])),l+w-12,y,24,k==0?0xff3ddc84:WHITE,true,Paint.Align.RIGHT);
                text(c,Records.date(e[k]),l+w-12,y+20,12,MUTED,false,Paint.Align.RIGHT);}
        }
        button(c,UiText.t("기록실 크게 보기"),PANE_L,402,290,44,false,15);
        button(c,UiText.t("기록 지우기"),PANE_R-290,402,290,44,false,15);
        text(c,UiText.t("가속 측정마다 상위 5개를 날짜와 함께 보관합니다 (앱을 지워도 백업 파일에 남음)"),PANE_R,470,12,MUTED,false,Paint.Align.RIGHT);
    }
    private void backup(Canvas c,State s){
        panel(c,PANE_L,TOP+2,PANE_R-PANE_L,176,PANEL,LINE);
        text(c,UiText.t("설정 백업"),PANE_L+16,TOP+32,18,WHITE,true,Paint.Align.LEFT);
        fitted(c,UiText.t("화면 배치 · 테마 · 플로팅 창 · 게이지 · 기록을 내비 저장소의 파일 하나로 보관합니다."),PANE_L+16,TOP+60,13,PANE_R-PANE_L-32,MUTED);
        fitted(c,UiText.t("계기판이 화면에서 사라질 때 자동 저장되고, 새로 설치하면 되돌릴지 한 번 묻습니다."),PANE_L+16,TOP+80,13,PANE_R-PANE_L-32,MUTED);
        text(c,UiText.t("파일")+"  "+s.backupPath,PANE_L+16,TOP+118,13,BLUE,false,Paint.Align.LEFT);
        text(c,UiText.t("마지막 저장")+"  "+(s.backupTime.length()==0?UiText.t("없음"):s.backupTime),PANE_L+16,TOP+144,13,BLUE,false,Paint.Align.LEFT);
        button(c,UiText.t("지금 저장"),PANE_L,250,290,44,false,15);
        button(c,UiText.t("백업에서 되돌리기"),PANE_R-290,250,290,44,false,15);
    }
    private static final String[] FONT_SAMPLE={"가나다라 벨로스터 N","목적지까지 12.4 km","서울특별시 강남구 테헤란로 152","ABCDEFG abcdefg 0123456789"};
    private void fonts(Canvas c,State s){
        FontManager.Snapshot f=s.fonts;
        int n=f==null?0:f.entries.size();
        for(int i=0;i<FONT_ROWS;i++){
            int k=s.fontTop+i;if(k>=n)break;
            FontManager.Entry e=f.entries.get(k);boolean sel=k==s.fontPick,cur=k==f.current;float t=fontRow(i);
            panel(c,PANE_L,t,FL_R-PANE_L,FL_ROW_H,sel?DIM:e.usable?PANEL:OFFCELL,sel?BLUE:LINE);
            String name=(cur?"● ":"")+e.name;
            p.setStyle(Paint.Style.FILL);p.setColor(e.usable?(sel?WHITE:0xffc9d6dc):MUTED);p.setTypeface(e.regular!=null?e.regular:Typeface.DEFAULT);p.setTextSize(17);
            float m=p.measureText(name),w=FL_R-PANE_L-24;if(m>w)p.setTextSize(17*w/m);
            c.drawText(name,PANE_L+12,t+22,p);
            fitted(c,e.detail,PANE_L+12,t+39,11,w,cur?BLUE:MUTED);
        }
        if(f==null)text(c,UiText.t(s.fontBusy?"글꼴을 찾는 중…":"새로고침을 누르면 글꼴을 찾습니다"),PANE_L+12,TOP+30,14,MUTED,false,Paint.Align.LEFT);
        boolean up=s.fontTop>0,down=s.fontTop+FONT_ROWS<n;
        button(c,"▲",PANE_L,FB_T,58,FB_H,false,15);button(c,"▼",PANE_L+64,FB_T,58,FB_H,false,15);
        if(!up||!down){p.setColor(0x99040506);if(!up)c.drawRect(PANE_L,FB_T,PANE_L+58,FB_T+FB_H,p);if(!down)c.drawRect(PANE_L+64,FB_T,PANE_L+122,FB_T+FB_H,p);}
        button(c,UiText.t("새로고침"),PANE_L+128,FB_T,FL_R-PANE_L-128,FB_H,false,14);
        fitted(c,UiText.t("순정 · 앱에 포함된 글꼴 · 내비에 설치된 글꼴"),PANE_L+2,FA_T+18,11,FL_R-PANE_L-4,MUTED);
        fitted(c,UiText.t("적용하면 재부팅 뒤에 화면에 반영됩니다"),PANE_L+2,FA_T+36,11,FL_R-PANE_L-4,MUTED);
        // preview of the picked row, in its own regular and bold
        panel(c,FP_L,TOP+2,PANE_R-FP_L,FP_B-TOP-2,0xff0b0f12,LINE);
        text(c,UiText.t("미리보기"),FP_L+10,TOP+20,11,MUTED,true,Paint.Align.LEFT);
        FontManager.Entry pick=f!=null&&s.fontPick>=0&&s.fontPick<n?f.entries.get(s.fontPick):null;
        if(pick==null)text(c,UiText.t("왼쪽에서 글꼴을 고르세요"),FP_L+(PANE_R-FP_L)/2,TOP+130,14,MUTED,false,Paint.Align.CENTER);
        else if(pick.needsLoad())text(c,UiText.t("불러오는 중…"),FP_L+(PANE_R-FP_L)/2,TOP+130,14,MUTED,false,Paint.Align.CENTER);
        else if(pick.regular==null)text(c,UiText.t("이 글꼴을 열지 못했습니다"),FP_L+(PANE_R-FP_L)/2,TOP+130,14,MUTED,false,Paint.Align.CENTER);
        else{
            float y=TOP+60,bw=PANE_R-FP_L-20;float[] sizes={26,21,16,16};
            for(int i=0;i<FONT_SAMPLE.length;i++){
                boolean bold=i==1;Typeface tf=bold?(pick.bold!=null?pick.bold:pick.regular):pick.regular;
                p.setStyle(Paint.Style.FILL);p.setColor(WHITE);p.setTypeface(tf);p.setFakeBoldText(bold&&pick.bold==null);p.setTextSize(sizes[i]);
                float m=p.measureText(FONT_SAMPLE[i]);if(m>bw)p.setTextSize(sizes[i]*bw/m);
                c.drawText(FONT_SAMPLE[i],FP_L+10,y,p);p.setFakeBoldText(false);y+=sizes[i]+22;
            }
            fitted(c,pick.detail,FP_L+10,FP_B-12,11,bw,MUTED);
        }
        // state: what the system XML says now, and whether a reboot is still owed
        panel(c,FP_L,FS_T,PANE_R-FP_L,FS_B-FS_T,PANEL,LINE);
        float sw=PANE_R-FP_L-20;
        if(f!=null){
            fitted(c,UiText.t("설정된 글꼴")+"  "+f.currentName,FP_L+10,FS_T+24,13,sw,WHITE);
            fitted(c,f.pending?UiText.t("재부팅하면 화면에 반영됩니다"):UiText.t("지금 화면의 글꼴과 같습니다"),FP_L+10,FS_T+48,12,sw,f.pending?0xffffc24a:MUTED);
            String space=f.free>=0?String.format(Locale.US,UiText.t("시스템 남은 공간")+" %.1f MB",f.free/1048576.0):"",tool=f.root?"":UiText.t("루트 도구 없음");
            fitted(c,space+(space.length()>0&&tool.length()>0?"  ·  ":"")+tool,FP_L+10,FS_T+72,11,sw,f.root?MUTED:0xffffc24a);
        }
        if(s.fontBusy)text(c,UiText.t("작업 중…"),PANE_R-10,FS_T+24,12,BLUE,true,Paint.Align.RIGHT);
        button(c,UiText.t("선택한 글꼴 적용"),FP_L,FA_T,FA_SPLIT-4-FP_L,FA_H,false,14);
        button(c,UiText.t("재부팅"),FA_SPLIT+4,FA_T,PANE_R-FA_SPLIT-4,FA_H,f!=null&&f.pending,14);
    }
    private void info(Canvas c,State s){
        panel(c,PANE_L,TOP+2,PANE_R-PANE_L,328,PANEL,LINE);
        float y=TOP+34;
        text(c,UiText.t("N 계기판")+"  "+s.version,PANE_L+16,y,18,WHITE,true,Paint.Align.LEFT);y+=34;
        String[] lines={
            UiText.t("차량이 이미 받는 데이터를 읽기만 합니다. 차량에 아무것도 보내지 않습니다."),
            "",
            UiText.t("원작: reikop/veloster-n-dashboard (MIT)"),
            UiText.t("플로팅 창 · 설정 백업 · 기록실 · 테마 · 화면 목록:"),
            "   "+UiText.t("sung9155/i30-pd-dashboard (i30 PD 포팅, MIT)에서 가져와 벨로스터 N에 맞춤"),
            UiText.t("플로팅 창 글꼴: Chakra Petch, SIL Open Font License 1.1"),
            "",
            UiText.t("오류가 나면 앱 내부 진단 기록에 남습니다 (차량 값·음원 정보 제외)."),
        };
        for(String l:lines){if(l.length()>0)fitted(c,l,PANE_L+16,y,13,PANE_R-PANE_L-32,MUTED);y+=24;}
        // USB 디버깅 (from My Apps): the requested OEM USB mode, and ON / OFF
        text(c,UiText.t("USB 디버깅"),PANE_L+4,USB_T+19,15,WHITE,true,Paint.Align.LEFT);
        fitted(c,s.usbStatus,PANE_L+4,USB_T+39,12,USB_ON_L-PANE_L-12,BLUE);
        button(c,"ON",USB_ON_L,USB_T,USB_W,USB_H,false,15);
        button(c,"OFF",USB_OFF_L,USB_T,USB_W,USB_H,false,15);
        text(c,UiText.t("OFF는 PC 연결을 끊을 수 있습니다 · 시동을 껐다 켜면 저절로 꺼집니다"),PANE_R,470,12,MUTED,false,Paint.Align.RIGHT);
    }

    // ---- primitives ----------------------------------------------------------------------------------------------
    private void label(Canvas c,int row,String s){fitted(c,s,PANE_L+4,fRow(row)+F_ROW_H/2+5,13,F_LABEL_W-10,MUTED);}
    private void chipRow(Canvas c,int row,String[] names,int selected){
        int n=names.length;float w=chipW(n);
        for(int i=0;i<n;i++)button(c,names[i],chipL(i,n),fRow(row),w,F_ROW_H,i==selected,13);
    }
    private void toggle(Canvas c,String s,float l,float t,boolean on){
        panel(c,l,t,PANE_R-l,TOGGLE_H,on?DIM:OFFCELL,on?BLUE:LINE);
        fitted(c,s,l+12,t+TOGGLE_H/2+5,13,PANE_R-l-80,on?WHITE:MUTED);
        text(c,on?"ON":"OFF",PANE_R-12,t+TOGGLE_H/2+5,13,on?BLUE:MUTED,true,Paint.Align.RIGHT);
    }
    private void button(Canvas c,String s,float x,float y,float w,float h,boolean on,float size){
        panel(c,x,y,w,h,on?DIM:OFFCELL,on?BLUE:LINE);
        if(on){p.setColor(BLUE);c.drawRect(x+9,y+h-3,x+w-9,y+h-1,p);}
        p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(size);float m=p.measureText(s);float k=m>w-16?(w-16)/m:1;
        text(c,s,x+w/2,y+h/2+size*k*0.36f,size*k,on?BLUE:MUTED,true,Paint.Align.CENTER);
    }
    private void panel(Canvas c,float x,float y,float w,float h,int fill,int edge){
        if(ThemeShapes.cell(c,p,path,rect,x,y,x+w,y+h,fill,edge))return;
        float k=Math.min(10,Math.min(w,h)/3);
        path.reset();path.moveTo(x,y);path.lineTo(x+w-k,y);path.lineTo(x+w,y+k);
        path.lineTo(x+w,y+h);path.lineTo(x+k,y+h);path.lineTo(x,y+h-k);path.close();
        p.setStyle(Paint.Style.FILL);p.setColor(fill);c.drawPath(path,p);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(edge);c.drawPath(path,p);p.setStyle(Paint.Style.FILL);
    }
    private void fitted(Canvas c,String s,float x,float y,float size,float width,int color){
        p.setTypeface(Typeface.DEFAULT);p.setTextSize(size);float m=p.measureText(s);
        text(c,s,x,y,m>width?size*width/m:size,color,false,Paint.Align.LEFT);
    }
    private void text(Canvas c,String t,float x,float y,float size,int color,boolean bold,Paint.Align align){
        p.setStyle(Paint.Style.FILL);p.setColor(color);p.setTypeface(bold?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);p.setTextSize(size);
        p.setTextAlign(align);c.drawText(t,x,y,p);p.setTextAlign(Paint.Align.LEFT);
    }
}
