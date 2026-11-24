package com.veloster.vehicle;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.util.Locale;

/**
 * Base for the extra Cluster layouts (ClusterPicker index 3 and up): the frame's samples and the drawing helpers
 * they share. A layout keeps its own fixed palette unless {@link #themed} is set, and warnings always use the
 * theme's danger colour. No glow or shadow layers: the head unit draws in software.
 */
abstract class ClusterKit {
    protected final Paint p=new Paint(3);
    protected final Path path=new Path();
    protected final RectF r=new RectF();
    /** Cached pixel art is drawn without smoothing. */
    private final Paint bmpPaint=new Paint(0);
    private final android.graphics.Rect srcRect=new android.graphics.Rect();
    protected Telemetry.Sample[] s;
    protected Telemetry model;
    protected DriveStats st;
    protected VehicleProfile prof=VehicleProfile.PETROL_DCT;
    protected long now;
    /** The track the head unit is playing, or null. */
    protected MediaState.Snapshot music;
    /** The current track's album art (PitRenderer.setArtwork), or null. Shared: one cluster draws at a time. */
    protected static android.graphics.Bitmap artwork;
    /** Remap colours through the selected theme (else the layout's own palette). */
    protected boolean themed;

    final void frame(Canvas c,Telemetry.Sample[] samples,Telemetry m,DriveStats stats,VehicleProfile profile,long frameTime,MediaState.Snapshot track){
        music=track;s=samples;model=m;st=stats;if(profile!=null)prof=profile;now=frameTime;
        c.save();c.clipRect(0,52,800,480);draw(c);c.restore();   // never paint over the header
    }
    /** Every dashboard frame on every page (ClusterRenderer.tick), so running totals keep counting off-screen. */
    final void background(Telemetry.Sample[] samples,long frameTime){s=samples;now=frameTime;tick();}
    /** Accumulate trip totals here, not in draw (draw only runs while this layout shows). */
    protected void tick(){}
    protected abstract void draw(Canvas c);
    /** A tap on the body while this layout shows; true when it used it. */
    boolean tap(float x,float y){return false;}
    /** True while the layout shows the now-playing track itself, so the track-change bar stays off. */
    boolean showsMusic(){return false;}
    /** True when the layout has its own track controls, so the corner album-art button is not drawn or tappable. */
    boolean ownsMediaToggle(){return false;}

    /** Display text in the selected language (Korean source strings, translations/en.json). */
    protected static String t(String ko){return com.veloster.i18n.UiText.t(ko);}
    protected int col(int c){return themed?Theme.current().map(c):c;}
    protected static int danger(){return Theme.current().danger;}
    protected float v(int i){return i>=0&&i<s.length&&s[i].valid&&!Float.isNaN(s[i].value)&&!Float.isInfinite(s[i].value)?Telemetry.displayValue(i,s[i].value):Float.NaN;}
    protected static String fmt(String f,float x){return Float.isNaN(x)?"—":Telemetry.format(f,x);}
    protected static float clamp(float x,float a,float b){return Math.max(a,Math.min(b,x));}
    protected float boostTop(){return st==null?DriveStats.boostTop(Float.NaN):st.boostTop();}
    protected String gear(){return Telemetry.gearText(v(8),v(Telemetry.GEAR_SELECTOR),prof);}
    /** DCT manual gate ("M3"): layouts with a one-digit gear slot draw the digit there and a small M beside it. */
    protected static boolean manualGate(String g){return g.length()>1&&g.charAt(0)=='M';}
    protected static boolean blink(long now,long period){return now%period<period/2;}

    protected void fill(Canvas c,float l,float t,float rr,float b,int color){p.setStyle(Paint.Style.FILL);p.setColor(col(color));c.drawRect(l,t,rr,b,p);}
    protected void round(Canvas c,float l,float t,float rr,float b,float rad,int color){p.setStyle(Paint.Style.FILL);p.setColor(col(color));r.set(l,t,rr,b);c.drawRoundRect(r,rad,rad,p);}
    protected void outline(Canvas c,float l,float t,float rr,float b,float rad,float width,int color){
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(width);p.setColor(col(color));r.set(l+width/2,t+width/2,rr-width/2,b-width/2);
        if(rad>0)c.drawRoundRect(r,rad,rad,p);else c.drawRect(r.left,r.top,r.right,r.bottom,p);p.setStyle(Paint.Style.FILL);
    }
    protected void line(Canvas c,float x1,float y1,float x2,float y2,float width,int color){
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(width);p.setColor(col(color));c.drawLine(x1,y1,x2,y2,p);p.setStyle(Paint.Style.FILL);
    }
    /** Lets go of the bitmaps this screen keeps between frames (the dashboard is out of sight: only the floating window, or
     *  nothing, is drawn); each is made again the next time the screen draws. UI thread. */
    void trim(){}
    /** Frees a let-go bitmap's pixels now rather than at the next collections (a bitmap waits for its finalizer: on the
     *  V141 emulator 2.2 MB of trimmed bitmaps were still held a while after trim). Null-safe; returns null for the field. */
    static Bitmap recycle(Bitmap b){if(b!=null&&!b.isRecycled())b.recycle();return null;}
    static void recycle(Bitmap[] bs){if(bs!=null)for(int i=0;i<bs.length;i++)bs[i]=recycle(bs[i]);}
    static void recycle(java.util.Map<?,Bitmap> m){for(Bitmap b:m.values())recycle(b);m.clear();}
    protected static Bitmap bitmap(int w,int h){return Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);}
    /** Draws part of a cached bitmap into a screen rectangle, nearest neighbour. */
    protected void blit(Canvas c,Bitmap b,int sx,int sy,int sw,int sh,float l,float t,float rr,float bt){
        srcRect.set(sx,sy,sx+sw,sy+sh);r.set(l,t,rr,bt);c.drawBitmap(b,srcRect,r,bmpPaint);
    }
    /** Fills the current path. */
    protected void poly(Canvas c,int color){p.setStyle(Paint.Style.FILL);p.setColor(col(color));c.drawPath(path,p);}
    protected void dot(Canvas c,float x,float y,float rad,int color){p.setStyle(Paint.Style.FILL);p.setColor(col(color));c.drawCircle(x,y,rad,p);}
    protected void ring(Canvas c,float x,float y,float rad,float width,int color){
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(width);p.setColor(col(color));c.drawCircle(x,y,rad,p);p.setStyle(Paint.Style.FILL);
    }
    protected void text(Canvas c,String str,float x,float y,float size,int color,Paint.Align align,Typeface face){
        p.setStyle(Paint.Style.FILL);p.setColor(col(color));p.setTypeface(face);p.setTextSize(size);p.setTextAlign(align);c.drawText(str,x,y,p);
    }
    protected void bold(Canvas c,String str,float x,float y,float size,int color,Paint.Align align){text(c,str,x,y,size,color,align,Typeface.DEFAULT_BOLD);}
    protected void mono(Canvas c,String str,float x,float y,float size,int color,Paint.Align align){text(c,str,x,y,size,color,align,Typeface.MONOSPACE);}
    protected float width(String str,float size,Typeface face){p.setTypeface(face);p.setTextSize(size);return p.measureText(str);}
    /** One line at x/baseline y inside width w; wider than that it flows (looping, with a gap) instead of ending in …. */
    protected void marquee(Canvas c,String str,float x,float y,float w,float size,int color,Typeface face,float pxPerSec){
        float tw=width(str,size,face);
        if(tw<=w){text(c,str,x,y,size,color,Paint.Align.LEFT,face);return;}
        float gap=48,period=tw+gap,off=(now/1000f*pxPerSec)%period;
        c.save();c.clipRect(x,y-size-2,x+w,y+size*0.35f);
        text(c,str,x-off,y,size,color,Paint.Align.LEFT,face);
        text(c,str,x-off+period,y,size,color,Paint.Align.LEFT,face);
        c.restore();
    }
    /** Key colours: face, top highlight, shadow lip, lamp, label. */
    static final int[] KEY_GREEN={0xff1f4a2c,0xff3a7a4c,0xff0f2416,0xff2fe06a,0xfff2f2f4},KEY_RED={0xff5a2420,0xff8a3a32,0xff2a100e,0xffff3b2f,0xfff2f2f4},
        KEY_AMBER={0xff4a3a12,0xff7a6224,0xff241c08,0xffffb000,0xfff2f2f4},KEY_GREY={0xff26262a,0xff3a3a40,0xff121214,0xff3a3a40,0xff6a6a70},
        KEY_SLATE={0xff2c2e33,0xff484b52,0xff141518,0xff3a3a40,0xfff2f2f4};   // GREY: a dead key; SLATE: a live neutral one
    /** A raised meter key in (l,t)-(r,b): dark recess, shadow lip, face, a highlight along the top, a lamp on the left and
     *  the label at up to size px (shrunk to fit). k: KEY_*; lamp overrides k's lamp colour (0: k's). */
    protected void key(Canvas c,float l,float t,float r,float b,String label,float size,int[] k,int lamp){
        round(c,l,t,r,b,9,0xff0a0a0b);round(c,l+4,t+7,r-4,b-4,7,k[2]);round(c,l+4,t+3,r-4,b-9,7,k[0]);fill(c,l+12,t+5,r-12,t+7,k[1]);
        float cy=(t+3+b-9)/2;ring(c,l+30,cy,9,2,0xff0a0a0b);dot(c,l+30,cy,7,lamp!=0?lamp:k[3]);
        float s=size;while(s>12&&width(label,s,Typeface.DEFAULT_BOLD)>r-l-58)s--;
        bold(c,label,(l+39+r-4)/2,cy+s*0.36f,s,k[4],Paint.Align.CENTER);
    }
    /** Pointer from (cx,cy) at deg clockwise from 12 o'clock. */
    protected static float px(float cx,float r,float deg){return cx+(float)Math.sin(Math.toRadians(deg))*r;}
    protected static float py(float cy,float r,float deg){return cy-(float)Math.cos(Math.toRadians(deg))*r;}

    // Seven-segment digits: lit segments in on, unlit ones as a faint ghost (off may be 0 to skip them).
    private static final String[] SEG={"abcdef","bc","abdeg","abcdg","bcfg","acdfg","acdefg","abc","abcdefg","abcdfg"};
    private static String segs(char ch){return ch>='0'&&ch<='9'?SEG[ch-'0']:ch=='-'?"g":ch=='n'||ch=='N'?"ceg":ch=='P'?"abefg":ch=='R'?"eg":ch=='D'?"bcdeg":"";}   // gears P r n d
    /** Draws str (digits, '-', ' ', '.', ':', the gear letters P R N D) right-aligned at x (or left when left), height h; returns its width. */
    protected float seg7(Canvas c,String str,float x,float y,float h,int on,int off,boolean left){
        float w=h*0.5f,gap=h*0.17f,t=Math.max(2,h*0.115f),tw=-gap;
        for(int i=0;i<str.length();i++){char ch=str.charAt(i);tw+=ch=='.'||ch==':'?t*1.6f:w+gap;}
        float cx=left?x:x-tw;
        for(int i=0;i<str.length();i++){
            char ch=str.charAt(i);
            if(ch=='.'||ch==':'){
                float dx=cx+t*0.3f-gap*0.35f;
                if(ch=='.')fill(c,dx,y+h-t,dx+t,y+h,on);else{fill(c,dx,y+h*0.3f-t/2,dx+t,y+h*0.3f+t/2,on);fill(c,dx,y+h*0.68f-t/2,dx+t,y+h*0.68f+t/2,on);}
                cx+=t*1.6f;continue;
            }
            String lit=segs(ch);
            for(char k:"abcdefg".toCharArray()){boolean isOn=lit.indexOf(k)>=0;if(!isOn&&off==0)continue;segment(c,k,cx,y,w,h,t,isOn?on:off);}
            cx+=w+gap;
        }
        return tw;
    }
    private void segment(Canvas c,char k,float x,float y,float w,float h,float t,int color){
        float g=t*0.2f,hh=h/2;
        base=y+h;path.reset();
        switch(k){
            case 'a':hseg(x+t/2+g,x+w-t/2-g,y+t/2,t);break;
            case 'g':hseg(x+t/2+g,x+w-t/2-g,y+hh,t);break;
            case 'd':hseg(x+t/2+g,x+w-t/2-g,y+h-t/2,t);break;
            case 'f':vseg(x+t/2,y+t/2+g,y+hh-g,t);break;
            case 'e':vseg(x+t/2,y+hh+g,y+h-t/2-g,t);break;
            case 'b':vseg(x+w-t/2,y+t/2+g,y+hh-g,t);break;
            default:vseg(x+w-t/2,y+hh+g,y+h-t/2-g,t);break;
        }
        p.setStyle(Paint.Style.FILL);p.setColor(col(color));c.drawPath(path,p);
    }
    private float base;
    /** Segment corner, slanted 0.1 to the right per unit of height above the digit's baseline. */
    private void pt(boolean first,float x,float y){float sx=x+(base-y)*0.1f;if(first)path.moveTo(sx,y);else path.lineTo(sx,y);}
    private void hseg(float x1,float x2,float yc,float t){pt(true,x1,yc);pt(false,x1+t/2,yc-t/2);pt(false,x2-t/2,yc-t/2);pt(false,x2,yc);pt(false,x2-t/2,yc+t/2);pt(false,x1+t/2,yc+t/2);path.close();}
    private void vseg(float xc,float y1,float y2,float t){pt(true,xc,y1);pt(false,xc+t/2,y1+t/2);pt(false,xc+t/2,y2-t/2);pt(false,xc,y2);pt(false,xc-t/2,y2-t/2);pt(false,xc-t/2,y1+t/2);path.close();}
    protected static int mix(int a,int b,float t){
        int o=0xff000000;for(int sh=0;sh<24;sh+=8){int x=(a>>sh)&255,y=(b>>sh)&255;o|=Math.round(y+(x-y)*t)<<sh;}return o;
    }
}
