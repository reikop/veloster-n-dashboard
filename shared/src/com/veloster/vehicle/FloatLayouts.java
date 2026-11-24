package com.veloster.vehicle;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

/**
 * 플로팅 창 레이아웃: rectangular windows with their own inside (짧은 캡슐, 주인공 + 목록, 단수 중심, 사선 분할, 반원 계기,
 * 변속등 머리) beside the grid shapes (카드, 아래 띠, 옆 세로). Geometry is in base px (size 보통) and scales with k. Colours
 * and type come from the skin: the plain window's theme colours, or a palette per skin (FloatSkin), so 계기판 따라 dresses
 * them too. The round gauge skin has no palette of its own and draws these plain.
 */
final class FloatLayouts extends ClusterKit {
    static final int PILL=5,HERO=6,GEAR=7,DIAG=8,DIAL=9,SHIFT=10,TICK=11,FILLS=12,BARS=13,TWIN=14,WEDGE=15,TOWER=16,LAST=TOWER;
    /** 세로 띠 + RPM: after 음악 (FloatPanel.MUSIC = LAST + 1), so a saved shape number keeps its meaning. */
    static final int VRPM=LAST+2;
    /** 세로 캡슐: after 세로 띠 + RPM, so saved shape numbers keep their meaning. */
    static final int VCAP=LAST+3;
    /** VRPM geometry, base px: an 80 wide column of 60 high rows, the rpm bar in a 16 wide lane on the right (narrow, so CarPlay shows more). */
    private static final int VR_COL=80,VR_ROW=60,VR_LANE=16;
    /** TOWER: a 62 wide column (gear box, readings), the rpm in a 14 wide lane on the right. */
    private static final int TW_COL=62,TW_LANE=14;
    /** VCAP, base px: 88 wide; the top end (VC_TOP high) holds the first reading as a dial, rows in a 70 wide column with the rpm
     *  lane beside them, the bottom end (VC_END high) the gear. Screen-high like VRPM. */
    private static final int VC_W=88,VC_COL=70,VC_TOP=100,VC_END=48;
    static float vcRow(int n){return (vrH()-VC_TOP-VC_END)/Math.max(1,n-1);}
    /** Where the track goes while one plays (base px): 세로 띠 + RPM's box under its rows, 세로 캡슐's last two rows. */
    static float[] musicBox(int shape,int n){
        if(shape!=VCAP)return rect(shape,n,6);
        float R=vcRow(n);return new float[]{0,VC_TOP+(n-3)*R,VC_COL,VC_TOP+(n-1)*R};}
    /** VRPM fills the screen's height (472 of its 480 px, at any size): six rows up to VR_ROW high, then a box at least VR_BOX high
     *  for the rest: the track while one plays (with 음악 띠 on; FloatMusic.box, drawn by FloatPanel each frame), else the seventh reading. */
    private static final int VR_SCREEN=472,VR_BOX=90;
    /** The box shows the track (FloatPanel sets it before each draw). */
    static volatile boolean vrMusic;
    static float vrH(){return VR_SCREEN/FloatPanel.sizeK();}
    static float vrRow(){return Math.min(VR_ROW,(vrH()-8-VR_BOX)/6);}
    static float vrBox(){return 4+6*vrRow();}
    private static final FloatLayouts K=new FloatLayouts();
    protected void draw(Canvas c){}

    /** Cells shown: the pill has room for two. */
    static int used(int shape,int n){return shape==PILL?Math.min(n,2):n;}
    /** The cell counts a layout fills without a hole (the settings offer these only). */
    static int[] counts(int shape){return shape==VRPM?new int[]{7}:shape==VCAP?new int[]{6}:shape==PILL?new int[]{1,2}:shape==HERO||shape==DIAL?new int[]{1,4}:shape==TICK||shape==FILLS||shape==TWIN||shape==TOWER?new int[]{1,2,4}:new int[]{2,4};}
    private static int rows(int n){return Math.min(3,n-1);}
    static float w(int shape,int n){
        if(shape==VRPM)return VR_COL+VR_LANE;
        if(shape==VCAP)return VC_W;
        switch(shape){case PILL:return 44+used(shape,n)*81;case HERO:return rows(n)>0?248:140;case DIAL:return rows(n)>0?248:132;case SHIFT:return n*70;case TICK:return n*96;case FILLS:return 180;case BARS:return n*56+8;case TWIN:return Math.min(2,n)*116;case TOWER:return TW_COL+TW_LANE;default:return 248;}
    }
    static float h(int shape,int n){
        if(shape==VRPM||shape==VCAP)return vrH();
        switch(shape){case PILL:return 40;case HERO:return 112;case GEAR:return 112;case DIAG:return n>=4?144:72;case DIAL:return 112;case TICK:return 26;case FILLS:return n*30+8;case BARS:return 112;case TWIN:return (n+1)/2*92;case WEDGE:return n<=2?82:126;case TOWER:return 76+n*40;default:return 64;}
    }
    /** Cell i's rectangle in base px, or null when the layout does not show it. */
    static float[] rect(int shape,int n,int i){
        if(i>=used(shape,n))return null;float W=w(shape,n),H=h(shape,n);
        switch(shape){
            case VRPM:{float R=vrRow();return i<6?new float[]{0,4+i*R,VR_COL,4+(i+1)*R}:new float[]{0,vrBox(),VR_COL,H-4};}
            case VCAP:{if(i==0)return new float[]{0,0,W,VC_TOP};float R=vcRow(n),y=VC_TOP+(i-1)*R;return new float[]{0,y,VC_COL,y+R};}
            case PILL:return new float[]{38+i*81,0,38+(i+1)*81,H};
            case HERO:return i==0?new float[]{0,0,140,H}:new float[]{140,6+(i-1)*32,W,6+i*32};
            case DIAL:return i==0?new float[]{0,0,132,H}:new float[]{132,8+(i-1)*30,W,8+i*30};
            case GEAR:return n<=2?new float[]{i%2==0?0:154,28,i%2==0?94:248,84}:new float[]{i%2==0?0:154,i<2?0:56,i%2==0?94:248,i<2?56:112};
            case DIAG:return new float[]{i%2==0?0:128,(i/2)*72,i%2==0?128:248,(i/2+1)*72};
            case TICK:return new float[]{i*96,0,(i+1)*96,H};
            case FILLS:return new float[]{0,4+i*30,W,34+i*30};
            case BARS:return new float[]{4+i*56,0,60+i*56,H};
            case TWIN:return new float[]{(i%2)*116,(i/2)*92,(i%2+1)*116,(i/2+1)*92};
            case WEDGE:return new float[]{(i%2)*124,34+(i/2)*44,(i%2+1)*124,78+(i/2)*44};
            case TOWER:return new float[]{0,72+i*40,TW_COL,112+i*40};
            default:return new float[]{i*70,0,(i+1)*70,H};
        }
    }

    // ---- this frame's palette -------------------------------------------------------------------------------------
    private int bg,edge,div,txt,mut,track,acc,box,boxInk;private Typeface face,bold;private boolean plain,greenRed,pix;
    private static Typeface chakra(){return Fonts.MEDIUM!=null?Fonts.MEDIUM:Typeface.DEFAULT;}
    private static Typeface chakraBold(){return Fonts.BOLD!=null?Fonts.BOLD:Typeface.DEFAULT_BOLD;}
    private void palette(int skin,float af){
        plain=false;greenRed=true;pix=skin==FloatSkin.PIXEL;face=chakra();bold=chakraBold();box=0xffa8dcf2;boxInk=0xff061015;
        switch(skin){
            case FloatSkin.RACE:set(0xeb0e1316,0xff2a353c,0xfff2f6f8,0xff8fa0a8,0xff26323a,Theme.current().map(0xff5bc8f5));box=Theme.current().map(0xffa4d9ef);break;
            case FloatSkin.F1:set(0xed080a0c,0xff2a343b,0xffffffff,0xff8fa0a8,0xff1d2428,0xff2fe06a);box=0xffffffff;break;
            case FloatSkin.RETRO:set(0xf0060402,0xffb4500f,0xffffb347,0xffc46a1c,0xff2a1606,0xffff9a2e);face=Typeface.MONOSPACE;bold=Typeface.MONOSPACE;box=0xffb4500f;boxInk=0xff140800;greenRed=false;break;
            case FloatSkin.VFD:set(0xf0020a09,0x385ef2d2,0xff5ef2d2,0xb35ef2d2,0x1a5ef2d2,0xff5ef2d2);box=0xff5ef2d2;boxInk=0xff021210;greenRed=false;break;
            case FloatSkin.HUD:set(0x40000000,0xff62ff8a,0xff62ff8a,0xb362ff8a,0x3362ff8a,0xff62ff8a);face=Typeface.MONOSPACE;bold=Typeface.MONOSPACE;box=0xff62ff8a;boxInk=0xff031a0a;greenRed=false;break;
            case FloatSkin.PIXEL:set(0xf0000000,0xffffffff,0xffffffff,0xffffd84a,0xff2a2a2a,0xff3ddc5a);face=Typeface.MONOSPACE;bold=Typeface.MONOSPACE;box=0xffffffff;boxInk=0xff000000;break;
            case FloatSkin.VU:set(0xf2ece0bc,0xff3a3226,0xff14110c,0xff5a4e3a,0xffd8c89c,0xffc8302a);box=0xff3a3226;boxInk=0xffece0bc;greenRed=false;break;
            case FloatSkin.NIGHT:set(0xcc000000,0xff2b3134,0xffc5ccd0,0xff8a959a,0xff2b3134,Theme.current().map(0xff5bc8f5));face=Typeface.DEFAULT;bold=Typeface.DEFAULT;box=0xffc5ccd0;boxInk=0xff000000;greenRed=false;break;
            case FloatSkin.CARD:set(0xf01c1c1e,0xff3a3a3c,0xffffffff,0xff98989f,0xff3a3a3c,Theme.current().map(0xff5bc8f5));face=Typeface.DEFAULT;bold=Typeface.DEFAULT;box=0xffffffff;boxInk=0xff000000;greenRed=false;break;
            case FloatSkin.WHITE:set(0xf4f5f5f7,0xffc7c7cc,0xff1c1c1e,0xff6e6e73,0xffdcdce0,0xff1677e5);face=Typeface.DEFAULT;bold=Typeface.DEFAULT;box=0xff1c1c1e;boxInk=0xffffffff;greenRed=false;break;
            case FloatSkin.OEM:set(0xf0000000,0xff3c4044,0xffffffff,0xff8b9197,0xff23272b,0xff2f7fe0);face=Typeface.DEFAULT;bold=Typeface.DEFAULT;box=0xff2f7fe0;boxInk=0xffffffff;greenRed=false;break;
            default:plain=true;bg=FloatPanel.BG;edge=FloatPanel.EDGE;div=FloatPanel.DIV;txt=FloatPanel.TXT;mut=FloatPanel.MUT;track=FloatPanel.TRACK;acc=FloatPanel.ACC;box=FloatPanel.themed(0xffa4d9ef);boxInk=FloatPanel.themed(0xff061015);break;
        }
        if(!plain){div=(edge&0xffffff)|0x99000000;int al=Math.max(0,Math.min(255,Math.round((bg>>>24)*af)));bg=al<<24|bg&0xffffff;}
    }
    private void frame(Canvas c,float l,float t,float r2,float b,float th){fill(c,l,t,r2,t+th,edge);fill(c,l,b-th,r2,b,edge);fill(c,l,t,l+th,b,edge);fill(c,r2-th,t,r2,b,edge);}
    private void set(int b,int e,int t,int m,int tr,int a){bg=b;edge=e;txt=t;mut=m;track=tr;acc=a;}
    private int warn(int lv,int normal){return FloatSkin.warn(lv,normal,FloatSkin.light(FloatPanel.skinNow()));}

    private void body(Canvas c,float w,float h,float k,float rad){
        if(pix){fill(c,0,0,w,h,bg);int o=Math.max(1,Math.round(k));frame(c,2*o,2*o,w-2*o,h-2*o,2*o);frame(c,6*o,6*o,w-6*o,h-6*o,o);return;}   // the 8-bit dialog box
        if(plain&&rad<0){FloatPanel.outline(c,w,h,k,bg,edge);return;}
        float rr=rad<0?6*k:rad;round(c,0,0,w,h,rr,bg);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(edge);r.set(.5f,.5f,w-.5f,h-.5f);c.drawRoundRect(r,rr,rr,p);p.setStyle(Paint.Style.FILL);
    }
    private String nm(FloatSkin.Cell d){return pix?d.en:d.name;}
    /** Text, or for the 8-bit skin PixelCluster's 5 x 7 font at the nearest whole scale (degree signs dropped, ▲ as HI). */
    private void t(Canvas c,String s,float x,float y,float size,int col,Paint.Align al,Typeface tf){if(pix)pix(c,s,x,y,size,Float.MAX_VALUE,col,al);else text(c,s,x,y,size,col,al,tf);}
    private float pix(Canvas c,String s,float x,float y,float size,float max,int col,Paint.Align al){
        s=s.replace("°","").replace("▲ ","HI").replace("▲","HI");int sc=Math.max(1,Math.round(size/7f));
        while(sc>1&&(s.length()*6-1)*sc>max)sc--;int fits=(int)((max+sc)/(6*sc));if(max<Float.MAX_VALUE&&fits<s.length())s=s.substring(0,Math.max(1,fits));
        float w=(s.length()*6-1)*sc,x0=al==Paint.Align.CENTER?x-w/2:al==Paint.Align.RIGHT?x-w:x;pixel(c,s,x0,y-7*sc,sc,col);return w;}
    private void pixel(Canvas c,String s,float x,float y,int sc,int col){
        float x0=Math.round(x),y0=Math.round(y);p.setStyle(Paint.Style.FILL);p.setColor(col);
        for(int i=0;i<s.length();i++){int g=PixelGlyphs.glyph(s.charAt(i));if(g==0)continue;
            for(int rw=0;rw<7;rw++){int bits=PixelGlyphs.FONT_BITS[g*7+rw];for(int bt=0;bt<5;bt++)if((bits&(16>>bt))!=0){float px=x0+(i*6+bt)*sc,py=y0+rw*sc;c.drawRect(px,py,px+sc,py+sc,p);}}}
    }
    private void fit(Canvas c,String s,float x,float y,float size,float max,int col,Paint.Align al,Typeface tf){
        if(pix){pix(c,s,x,y,size,max,col,al);return;}
        float w=width(s,size,tf);if(w>max&&w>0)size*=max/w;text(c,s,x,y,size,col,al,tf);}
    /** A value with its unit: LEFT starts at x, RIGHT ends (the unit) at x; the value shrinks to max px. */
    private void vu(Canvas c,FloatSkin.Cell d,float x,float y,float size,float max,float k,Paint.Align al){
        float us=Math.max(7*k,size*.36f),uw=d.unit.isEmpty()?0:width(d.unit,us,face)+3*k;
        float vs=size,vw=width(d.val,vs,bold);if(vw>max-uw&&vw>0){vs*=(max-uw)/vw;vw=max-uw;}
        int col=warn(d.lv,txt);
        if(pix){String un=d.unit.replace("°","");float uw2=un.isEmpty()?0:(un.length()*6-1)+3;
            if(al==Paint.Align.RIGHT){if(uw2>0)pix(c,un,x,y,7,Float.MAX_VALUE,mut,Paint.Align.RIGHT);pix(c,d.val,x-uw2,y,size,max-uw2,col,Paint.Align.RIGHT);}
            else{float w=pix(c,d.val,x,y,size,max-uw2,col,Paint.Align.LEFT);if(uw2>0)pix(c,un,x+w+3,y,7,Float.MAX_VALUE,mut,Paint.Align.LEFT);}return;}
        if(al==Paint.Align.RIGHT){text(c,d.unit,x,y,us,mut,Paint.Align.RIGHT,face);text(c,d.val,x-uw,y,vs,col,Paint.Align.RIGHT,bold);}
        else{text(c,d.val,x,y,vs,col,Paint.Align.LEFT,bold);text(c,d.unit,x+vw+3*k,y,us,mut,Paint.Align.LEFT,face);}
    }
    private void bar(Canvas c,FloatSkin.Cell d,float x,float y,float w,float h,float k,boolean peak){
        if(pix){int nb=Math.max(4,Math.round(w/(9*k)));float bw=w/nb,hh=Math.max(h,4*k);boolean has=!Float.isNaN(d.f)&&d.bar;float fc=has?Math.max(0,Math.min(1,d.f)):0;   // HP blocks, lit from the bar's start
            for(int i=0;i<nb;i++){boolean lt=FloatSkin.lit(i,nb,fc,d.zero,has);if(pass==ALL||pass==STAT||lt)fill(c,Math.round(x+i*bw),Math.round(y),Math.round(x+(i+1)*bw-2*k),Math.round(y+hh),lt&&pass!=STAT?(i>=nb*.8f?0xffff3d3d:acc):track);}return;}
        if(pass!=DYN)fill(c,x,y,x+w,y+h,track);if(pass==STAT||Float.isNaN(d.f)||!d.bar)return;float f=Math.max(0,Math.min(1,d.f)),z=d.zero,a=Math.min(z,f),b=Math.max(z,f);
        fill(c,x+w*a,y,Math.max(x+w*b,x+w*a+2),y+h,warn(d.lv,acc));
        if(peak&&d.pk!=null&&!Float.isNaN(d.pf)){float px=x+w*Math.max(0,Math.min(1,d.pf));fill(c,px-k,y-2*k,px+k,y+h+2*k,plain?0xffffffff:txt);}
    }
    /** The rpm as a lit column (l..rr, from b up to t): the skin's accent, amber near the top, red at the redline end; the track
     *  is kept, the lit part drawn each frame. */
    private void lane(Canvas c,float l,float rr,float t,float b,float k,boolean rOk,float rf,boolean st,boolean dy){
        int segs=Math.max(8,Math.round((b-t)/(9*k)));float sh=(b-t)/segs;
        if(st)fill(c,l-4*k,t,l-4*k+1,b,div);
        for(int i=0;i<segs;i++){boolean on=rOk&&(i+.5f)/segs<rf;if(on&&!dy||!on&&!st)continue;
            float f=(i+.5f)/segs,y1=b-(i+1)*sh+k,y2=b-i*sh-k;fill(c,l,y1,rr,y2,on?(f<.7f?acc:warn(f<.85f?1:2,0)):track);}
    }
    private int led(int i,int n){float t=(i+.5f)/n;if(greenRed)return t<.45f?0xff2fe06a:t<.8f?0xffff3b3b:0xff4aa8ff;return t<.6f?acc:warn(t<.85f?1:2,0);}

    /** Draws layout shape at 0,0 with cells d[0..], the rpm fraction and the gear's text. */
    static void draw(Canvas c,int shape,int n,float k,int skin,float af,FloatSkin.Cell[] d,float rpm,String gear){draw(c,shape,n,k,skin,af,d,rpm,gear,ALL);}
    /** Partial redraw: STAT draws only what does not move (body, scale, ticks, names) for FloatPanel to keep as a bitmap, DYN only what
     *  does (values, filled arcs, needle, peaks); ALL both. Shapes that do not split draw everything on ALL and STAT, nothing on DYN. */
    static final int ALL=0,STAT=1,DYN=2;
    /** Shapes whose drawing is split into STAT and DYN (the others are drawn whole every frame). */
    static boolean splits(int shape){return shape==VRPM||true;}
    static void draw(Canvas c,int shape,int n,float k,int skin,float af,FloatSkin.Cell[] d,float rpm,String gear,int pass){
        K.pass=splits(shape)?pass:pass==DYN?-1:ALL;if(K.pass<0)return;
        try{K.drawImpl(c,shape,n,k,skin,af,d,rpm,gear);}finally{K.pass=ALL;}}
    private int pass=ALL;
    private void drawImpl(Canvas c,int shape,int n,float k,int skin,float af,FloatSkin.Cell[] d,float rpm,String gear){
        palette(skin,af);boolean st=pass!=DYN,dy=pass!=STAT;   // partial redraw: st = what is kept as a bitmap, dy = what is drawn each frame
        float W=w(shape,n)*k,H=h(shape,n)*k;int u=used(shape,n);boolean rOk=!Float.isNaN(rpm);float rf=rOk?Math.max(0,Math.min(1,rpm)):0;
        switch(shape){
        case PILL:{   // a short pill: gear in a ring that fills with rpm, then up to two values
            float cx=20*k,cy=20*k;
            if(st){body(c,W,H,k,H/2);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3*k);p.setColor(track);c.drawCircle(cx,cy,14*k,p);p.setStyle(Paint.Style.FILL);}
            if(dy){if(rOk){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3*k);r.set(cx-14*k,cy-14*k,cx+14*k,cy+14*k);p.setColor(rf>.85f?0xffff4b3c:acc);p.setStrokeCap(Paint.Cap.ROUND);c.drawArc(r,-90,Math.max(1,360*rf),false,p);p.setStrokeCap(Paint.Cap.BUTT);p.setStyle(Paint.Style.FILL);}
                fit(c,gear,cx,cy+6*k,17*k,22*k,txt,Paint.Align.CENTER,bold);}
            for(int i=0;i<u;i++){float x=(44+i*81)*k;
                if(st){if(i>0)fill(c,x-7*k,9*k,x-6*k,31*k,div);fit(c,nm(d[i]),x,14*k,7.5f*k,72*k,mut,Paint.Align.LEFT,Typeface.DEFAULT);}
                if(dy)vu(c,d[i],x,33*k,16*k,72*k,k,Paint.Align.LEFT);}
            break;}
        case HERO:case DIAL:{   // one big reading on the left (a bar, or a round 270 degree dial with a needle), the next three as rows
            FloatSkin.Cell a=d[0];boolean dial=shape==DIAL;float lw=(dial?132:140)*k;
            if(st){body(c,W,H,k,-1);if(dial)fit(c,nm(a),66*k,(pix?101:104)*k,8*k,56*k,mut,Paint.Align.CENTER,Typeface.DEFAULT_BOLD);   // the dial's name at the bottom, under its opening
                else fit(c,nm(a),12*k,20*k,10*k,lw*.55f,mut,Paint.Align.LEFT,Typeface.DEFAULT_BOLD);}
            if(dy&&!dial&&a.pk!=null)t(c,"▲ "+a.pk,lw-12*k,20*k,8.5f*k,mut,Paint.Align.RIGHT,face);
            if(!dial){if(dy)vu(c,a,12*k,66*k,34*k,116*k,k,Paint.Align.LEFT);bar(c,a,12*k,84*k,116*k,6*k,k,true);}
            else{float cx=66*k,cy=54*k,rad=44*k;r.set(cx-rad,cy-rad,cx+rad,cy+rad);   // 270 degrees open at the bottom, from low left (135) round to low right
                if(st){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(6*k);p.setColor(track);c.drawArc(r,135,270,false,p);p.setStyle(Paint.Style.FILL);
                    for(int i=0;i<=10;i++){double an=Math.toRadians(135+27*i);float r1=rad-9*k,r2=rad-(i%5==0?15:12)*k;line(c,cx+(float)Math.cos(an)*r1,cy+(float)Math.sin(an)*r1,cx+(float)Math.cos(an)*r2,cy+(float)Math.sin(an)*r2,k,mut);}}
                if(dy){boolean has=!Float.isNaN(a.f);float f=has?Math.max(0,Math.min(1,a.f)):0;
                    if(has){float lo=Math.min(a.zero,f),hi=Math.max(a.zero,f);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(6*k);p.setColor(warn(a.lv,acc));c.drawArc(r,135+270*lo,Math.max(1,270*(hi-lo)),false,p);p.setStyle(Paint.Style.FILL);}
                    if(a.pk!=null&&!Float.isNaN(a.pf)){double an=Math.toRadians(135+270*Math.max(0,Math.min(1,a.pf)));line(c,cx+(float)Math.cos(an)*(rad-4*k),cy+(float)Math.sin(an)*(rad-4*k),cx+(float)Math.cos(an)*(rad+4*k),cy+(float)Math.sin(an)*(rad+4*k),2*k,plain?0xffffffff:txt);}
                    if(has){double an=Math.toRadians(135+270*f);p.setStrokeCap(Paint.Cap.ROUND);line(c,cx,cy,cx+(float)Math.cos(an)*(rad-6*k),cy+(float)Math.sin(an)*(rad-6*k),2*k,0xffff6b5b);p.setStrokeCap(Paint.Cap.BUTT);}
                    dot(c,cx,cy,4*k,0xffff6b5b);fit(c,a.val,cx,cy+33*k,13*k,40*k,warn(a.lv,txt),Paint.Align.CENTER,bold);   // in the opening under the hub, where the needle never goes
                    if(a.pk!=null)fit(c,"▲ "+a.pk,lw-8*k,(pix?101:104)*k,8*k,pix?36*k:28*k,mut,Paint.Align.RIGHT,face);}}   // the peak bottom right, clear of the ring
            int rows=rows(n);if(st&&rows>0)fill(c,lw,10*k,lw+1,H-10*k,div);
            for(int i=1;i<=rows;i++){FloatSkin.Cell b=d[i];float y=(dial?10+(i-1)*30:12+(i-1)*32)*k,x=lw+10*k,rr=W-(dial?10:12)*k;
                String nb=nm(b);if(st)fit(c,nb,x,y+(dial?(pix?9:12):10)*k,(dial?8:7.5f)*k,rr-x,mut,Paint.Align.LEFT,Typeface.DEFAULT);
                if(dy){
                float nw=pix?(nb.length()*6-1)*Math.max(1,Math.round(7.5f*k/7f)):width(nb,7.5f*k,Typeface.DEFAULT);   // a row's value keeps clear of its name
                String v=b.val+(b.unit.isEmpty()?"":" "+b.unit);fit(c,v,rr,y+(dial?(pix?26:24):12)*k,(dial?14:13)*k,dial?rr-x:Math.min((rr-x)*.6f,rr-x-nw-6*k),warn(b.lv,txt),Paint.Align.RIGHT,bold);}
                if(!dial){FloatSkin.Cell bb=b;bar(c,bb,x,y+18*k,rr-x,2*k,k,false);}}
            break;}
        case GEAR:{   // the race screen's gear box in the middle, an rpm line over it, a reading in each corner
            if(st){body(c,W,H,k,-1);fill(c,94*k,18*k,154*k,96*k,box);fill(c,94*k,10*k,154*k,13*k,track);}
            if(dy){fit(c,gear,124*k,78*k,58*k,54*k,boxInk,Paint.Align.CENTER,bold);if(rOk)fill(c,94*k,10*k,94*k+60*k*rf,13*k,rf>.85f?0xffff4b3c:acc);}
            for(int i=0;i<Math.min(4,n);i++){boolean left=i%2==0;float x=(left?10:238)*k,y=(n<=2?36:i<2?10:62)*k;Paint.Align al=left?Paint.Align.LEFT:Paint.Align.RIGHT;
                if(st)fit(c,nm(d[i]),x,y+11*k,8*k,76*k,mut,al,Typeface.DEFAULT);if(dy)vu(c,d[i],x,y+34*k,20*k,78*k,k,al);}
            break;}
        case DIAG:{   // the N logo's slant splits each band: a big reading left, the next one right
            if(st)body(c,W,H,k,-1);
            for(int b=0;b*2<n&&b<2;b++){float y=b*72*k;FloatSkin.Cell a=d[b*2];
                if(st){if(b>0)fill(c,8*k,y,W-8*k,y+1,div);fit(c,nm(a),12*k,y+18*k,9*k,60*k,mut,Paint.Align.LEFT,Typeface.DEFAULT_BOLD);}
                if(dy){if(a.pk!=null)t(c,"▲ "+a.pk,112*k,y+18*k,8*k,mut,Paint.Align.RIGHT,face);vu(c,a,12*k,y+52*k,28*k,100*k,k,Paint.Align.LEFT);}bar(c,a,12*k,y+60*k,96*k,3*k,k,true);
                if(b*2+1<n){FloatSkin.Cell e=d[b*2+1];
                    if(st){line(c,138*k,y+8*k,118*k,y+64*k,2*k,acc);fit(c,nm(e),236*k,y+18*k,9*k,80*k,mut,Paint.Align.RIGHT,Typeface.DEFAULT_BOLD);}
                    if(dy)vu(c,e,236*k,y+52*k,26*k,86*k,k,Paint.Align.RIGHT);bar(c,e,150*k,y+60*k,86*k,3*k,k,true);}}
            break;}
        case TICK:{   // one thin line: name, value, a hair of a bar
            if(st)body(c,W,H,k,-1);
            for(int i=0;i<n;i++){FloatSkin.Cell a=d[i];float x=i*96*k;if(st&&i>0)fill(c,x,6*k,x+1,H-6*k,div);
                if(pix){if(st)fit(c,nm(a),x+8*k,16*k,7*k,42*k,mut,Paint.Align.LEFT,Typeface.DEFAULT);if(dy)fit(c,a.val,x+86*k,16*k,7*k,34*k,warn(a.lv,txt),Paint.Align.RIGHT,bold);continue;}   // 8-bit: one pixel size, no unit, no bar (the frame is there)
                if(st)fit(c,nm(a),x+8*k,16*k,7.5f*k,34*k,mut,Paint.Align.LEFT,Typeface.DEFAULT);if(dy)vu(c,a,x+90*k,17*k,13*k,48*k,k,Paint.Align.RIGHT);bar(c,a,x+8*k,21*k,82*k,2*k,k,false);}
            break;}
        case FILLS:{   // rows whose ground fills with the reading (the name sits on the fill, so it is drawn each frame)
            if(st)body(c,W,H,k,-1);
            for(int i=0;i<n;i++){FloatSkin.Cell a=d[i];float y=(4+i*30)*k,l=8*k,rr=W-8*k;if(st)fill(c,l,y+3*k,rr,y+27*k,track);if(!dy)continue;
                if(!Float.isNaN(a.f)&&a.bar){float f=Math.max(0,Math.min(1,a.f)),lo=Math.min(a.zero,f),hi=Math.max(a.zero,f);int col=warn(a.lv,acc);
                    fill(c,l+(rr-l)*lo,y+3*k,Math.max(l+(rr-l)*hi,l+(rr-l)*lo+2),y+27*k,col&0xffffff|0x55000000);float e=l+(rr-l)*f;fill(c,e-k,y+3*k,e+k,y+27*k,col);}
                fit(c,nm(a),l+8*k,y+19*k,9*k,66*k,txt,Paint.Align.LEFT,Typeface.DEFAULT_BOLD);vu(c,a,rr-8*k,y+20*k,14*k,76*k,k,Paint.Align.RIGHT);}
            break;}
        case BARS:{   // a standing bar per reading, the value under it
            if(st)body(c,W,H,k,-1);
            for(int i=0;i<n;i++){FloatSkin.Cell a=d[i];float cx=(32+i*56)*k,t0=10*k,b0=66*k;
                if(st){fill(c,cx-8*k,t0,cx+8*k,b0,track);for(int j=0;j<=4;j++){float ty=b0-(b0-t0)*j/4;fill(c,cx+11*k,ty,cx+(j%2==0?17:14)*k,ty+1,mut);}
                    fit(c,nm(a)+(a.unit.isEmpty()?"":" "+a.unit.replace("°","")),cx,101*k,7.5f*k,52*k,mut,Paint.Align.CENTER,Typeface.DEFAULT);}
                if(dy){if(!Float.isNaN(a.f)&&a.bar){float f=Math.max(0,Math.min(1,a.f)),lo=Math.min(a.zero,f),hi=Math.max(a.zero,f),yb=b0-(b0-t0)*lo;fill(c,cx-8*k,Math.min(b0-(b0-t0)*hi,yb-2),cx+8*k,yb,warn(a.lv,acc));}
                    fit(c,a.val,cx,86*k,15*k,50*k,warn(a.lv,txt),Paint.Align.CENTER,bold);}}
            break;}
        case TWIN:{   // a 270 degree dial with a needle per reading (open at the bottom like the round gauges): the value in the opening, name and unit under it
            if(st)body(c,W,H,k,-1);
            for(int i=0;i<n;i++){FloatSkin.Cell a=d[i];float cx=((i%2)*116+58)*k,cy=((i/2)*92+40)*k,rad=34*k;r.set(cx-rad,cy-rad,cx+rad,cy+rad);
                if(st){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(5*k);p.setColor(track);c.drawArc(r,135,270,false,p);p.setStyle(Paint.Style.FILL);
                    fit(c,nm(a)+(a.unit.isEmpty()||nm(a).endsWith(" "+a.unit)?"":" "+a.unit),cx,cy+(pix?42:46)*k,8*k,104*k,mut,Paint.Align.CENTER,Typeface.DEFAULT);}   // 8-bit: clear of the dialog's inner line
                if(!dy)continue;
                boolean has=!Float.isNaN(a.f);float f=has?Math.max(0,Math.min(1,a.f)):0;
                if(has){float lo=Math.min(a.zero,f),hi=Math.max(a.zero,f);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(5*k);p.setColor(warn(a.lv,acc));c.drawArc(r,135+270*lo,Math.max(1,270*(hi-lo)),false,p);p.setStyle(Paint.Style.FILL);}
                if(has){double an=Math.toRadians(135+270*f);line(c,cx,cy,cx+(float)Math.cos(an)*(rad-7*k),cy+(float)Math.sin(an)*(rad-7*k),2*k,0xffff6b5b);}
                dot(c,cx,cy,3*k,0xffff6b5b);
                fit(c,a.val,cx,cy+31*k,14*k,44*k,warn(a.lv,txt),Paint.Align.CENTER,bold);}   // under the hub, between the ring's ends, where the needle never goes
            break;}
        case WEDGE:{   // rpm as a row of bars that grow to the right, the gear at its end, readings under it
            if(st)body(c,W,H,k,-1);int seg=24;
            for(int i=0;i<seg;i++){float x=(10+i*7.75f)*k,hh=(4+14f*i/(seg-1))*k;boolean on=rOk&&(i+.5f)/seg<rf;if(pass!=STAT)fill(c,x,26*k-hh,x+5.75f*k,26*k,on?led(i,seg):track);}   // drawn each frame: the bars sit between pixels, a lit one over a kept track would blend at its edges
            if(dy)fit(c,gear,238*k,27*k,(pix?14:26)*k,36*k,txt,Paint.Align.RIGHT,bold);
            for(int i=0;i<n;i++){FloatSkin.Cell a=d[i];float x=(i%2)*124*k,y=(34+(i/2)*44)*k;
                if(st){if(i%2==1)fill(c,x,y+6*k,x+1,y+38*k,div);if(i>=2&&i%2==0)fill(c,8*k,y,W-8*k,y+1,div);fit(c,nm(a),x+12*k,y+13*k,8*k,100*k,mut,Paint.Align.LEFT,Typeface.DEFAULT);}
                if(dy)vu(c,a,x+12*k,y+35*k,19*k,100*k,k,Paint.Align.LEFT);}
            break;}
        case VRPM:{   // 세로 띠 + RPM: six readings stacked, the rpm as a lit column the window's height on the right
            if(st)body(c,W,H,k,-1);
            lane(c,(VR_COL+3)*k,(VR_COL+VR_LANE-5)*k,8*k,H-8*k,k,rOk,rf,st,dy);
            float R=vrRow(),rk=k*R/VR_ROW;   // rows shorter than VR_ROW (the larger sizes) scale their insides
            for(int i=0;i<Math.min(n,6);i++){FloatSkin.Cell a=d[i];float y=(4+i*R)*k;
                if(st){if(i>0)fill(c,8*k,y,(VR_COL-6)*k,y+1,div);fit(c,nm(a),8*k,y+12*rk,8*rk,(VR_COL-16)*k,mut,Paint.Align.LEFT,Typeface.DEFAULT);}
                if(dy){if(a.quad!=null&&!pix)FloatPanel.tyres(c,2*k,y+14*rk,(VR_COL-4)*k,(VR_ROW-15)*rk,rk,a.quad,a.l4,a.t4,txt,div,bold);   // the wheels as a car seen from above
                    else vu(c,a,8*k,y+50*rk,28*rk,(VR_COL-14)*k,rk,Paint.Align.LEFT);}}   // the value as large as the row allows (the name sits small above it)
            {float by=vrBox()*k,bh=H-4*k-by;if(st)fill(c,8*k,by,(VR_COL-6)*k,by+1,div);
                if(n>6&&!vrMusic){FloatSkin.Cell a=d[6];   // no track: the seventh reading, larger than a row's
                    if(st)fit(c,nm(a),8*k,by+16*k,9*k,(VR_COL-16)*k,mut,Paint.Align.LEFT,Typeface.DEFAULT);
                    if(dy){if(a.quad!=null&&!pix)FloatPanel.tyres(c,2*k,by+20*k,(VR_COL-4)*k,bh-24*k,k,a.quad,a.l4,a.t4,txt,div,bold);
                        else vu(c,a,8*k,by+Math.min(bh-12*k,20*k+(bh-20*k)/2+13*k),Math.min(34*k,bh*.4f),(VR_COL-14)*k,k,Paint.Align.LEFT);}}}
            break;}
        case TOWER:{   // a narrow column for the screen's side: the gear box on top, readings stacked under it, the rpm as a lit lane on the right
            float C=TW_COL*k;
            if(st){body(c,W,H,k,-1);fill(c,8*k,8*k,C-4*k,64*k,box);}
            if(dy)fit(c,gear,(C+4*k)/2,54*k,(pix?35:44)*k,C-16*k,boxInk,Paint.Align.CENTER,bold);
            lane(c,C+2*k,W-5*k,8*k,H-8*k,k,rOk,rf,st,dy);
            for(int i=0;i<n;i++){FloatSkin.Cell a=d[i];float y=(72+i*40)*k;
                if(st){if(i>0)fill(c,6*k,y,C-4*k,y+1,div);fit(c,nm(a),8*k,y+13*k,8*k,C-14*k,mut,Paint.Align.LEFT,Typeface.DEFAULT);}
                if(dy)vu(c,a,8*k,y+33*k,17*k,C-12*k,k,Paint.Align.LEFT);}
            break;}
        case VCAP:{   // 세로 캡슐: a screen-high pill; the first reading (boost) as a dial in the top end, the rest stacked, the rpm lane beside them, the gear in the bottom end
            float C=VC_COL*k,cx=W/2,cy=46*k,rad=34*k,top=VC_TOP*k,end=H-VC_END*k;FloatSkin.Cell a=d[0];
            if(st){body(c,W,H,k,W/2);fill(c,8*k,top,W-8*k,top+1,div);fill(c,8*k,end,W-8*k,end+1,div);}
            r.set(cx-rad,cy-rad,cx+rad,cy+rad);
            if(st){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(5*k);p.setColor(track);c.drawArc(r,135,270,false,p);p.setStyle(Paint.Style.FILL);
                fit(c,nm(a)+(a.unit.isEmpty()?"":" "+a.unit),cx,cy+rad+12*k,8*k,W-16*k,mut,Paint.Align.CENTER,Typeface.DEFAULT);}
            if(dy){boolean has=!Float.isNaN(a.f);float f=has?Math.max(0,Math.min(1,a.f)):0;
                if(has){float lo=Math.min(a.zero,f),hi=Math.max(a.zero,f);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(5*k);p.setColor(warn(a.lv,acc));c.drawArc(r,135+270*lo,Math.max(1,270*(hi-lo)),false,p);p.setStyle(Paint.Style.FILL);
                    double an=Math.toRadians(135+270*f);line(c,cx,cy,cx+(float)Math.cos(an)*(rad-7*k),cy+(float)Math.sin(an)*(rad-7*k),2*k,0xffff6b5b);}
                dot(c,cx,cy,3*k,0xffff6b5b);fit(c,a.val,cx,cy+30*k,14*k,44*k,warn(a.lv,txt),Paint.Align.CENTER,bold);}   // under the hub, where the needle never goes
            lane(c,C+2*k,W-8*k,top+6*k,end-6*k,k,rOk,rf,st,dy);
            float R=vcRow(n),rk=Math.min(1,R/64f)*k;
            for(int i=1;i<n;i++){FloatSkin.Cell b=d[i];float y=top+(i-1)*R*k;
                if(st&&i>1&&!(vrMusic&&i==n-1))fill(c,8*k,y,C-4*k,y+1,div);   // the track box (FloatPanel draws it) takes the last two rows
                if(vrMusic&&i>=n-2)continue;
                if(st){fit(c,nm(b),10*k,y+14*rk,8*rk,C-16*k,mut,Paint.Align.LEFT,Typeface.DEFAULT);}
                if(dy){if(b.quad!=null&&!pix)FloatPanel.tyres(c,2*k,y+14*rk,C-6*k,R*k-16*rk,rk,b.quad,b.l4,b.t4,txt,div,bold);   // the wheels as a car seen from above
                    else vu(c,b,10*k,y+44*rk,24*rk,C-16*k,rk,Paint.Align.LEFT);}}
            if(dy)fit(c,gear,W/2,H-16*k,(pix?14:26)*k,W-24*k,txt,Paint.Align.CENTER,bold);
            break;}
        default:{   // SHIFT: a shift-light strip the window's width, the readings in a row under it
            if(st)body(c,W,H,k,-1);int leds=n*5;float lw=(W-16*k)/leds;
            for(int i=0;i<leds;i++){boolean on=rOk&&(i+.5f)/leds<rf;if(pass!=STAT)fill(c,8*k+i*lw+k,6*k,8*k+(i+1)*lw-k,10*k,on?led(i,leds):track);}   // each frame, as the rpm bars above
            for(int i=0;i<n;i++){float x=i*70*k;
                if(st){if(i>0)fill(c,x,18*k,x+1,56*k,div);fit(c,nm(d[i]),x+10*k,28*k,8*k,54*k,mut,Paint.Align.LEFT,Typeface.DEFAULT);}
                if(dy)vu(c,d[i],x+10*k,52*k,17*k,54*k,k,Paint.Align.LEFT);}
            break;}
        }
    }
}
