package com.veloster.vehicle;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * Music on the floating window (FloatPanel): the 음악 띠 under any shape (album art or play state, 가수 - 제목, a thin progress
 * line) and the 음악 shape (album art, title, artist, progress, then cell 0 large with the gear and cell 1 small). The track is
 * the OEM widget broadcasts' (MediaState) that VehicleDash and FloatService hand over before each tick and draw, in the
 * window's plain colours; the strip takes the 색감 (FloatSkin) body and ink. The strip reads 가수 - 제목 and scrolls when it
 * does not fit (the window then redraws every tick, about 20 a second, only while it scrolls); the shape cuts with an ellipsis.
 */
final class FloatMusic {
    /** The strip's height under the body and the gap above it, the music shape's size, at size 보통. */
    static final int STRIP_H=30,GAP=4,W=262,H=118,NARROW=150;   // NARROW: under this width the strip is words only
    static volatile MediaState.Snapshot track;
    static volatile Bitmap art;
    private static final Paint p=new Paint(3);private static final RectF r=new RectF();

    private FloatMusic(){}
    static boolean playing(MediaState.Snapshot t){return t!=null&&(t.title.length()>0||t.artist.length()>0);}
    /** Where the track is now: the last reported position, run on with the clock while it plays. */
    static long position(MediaState.Snapshot t){return Math.min(t.durationMs,t.positionMs+(t.playState==1?Math.max(0,t.timeAgeMs):0));}
    static float progress(MediaState.Snapshot t){return t!=null&&t.hasProgress()?Math.max(0,Math.min(1,position(t)/(float)t.durationMs)):0;}
    /** What the music part shows, for FloatPanel.tick's change hash: the texts, play state, the second and the art. */
    /** The track as the settings' sheets show it (title, artist, play state, art), without the position: the sheets are kept as a
     *  bitmap and should not be drawn again every second while a song plays. */
    static int sheetKey(){MediaState.Snapshot t=track;if(t==null)return 0;Bitmap a=art;
        return ((t.title.hashCode()*31+t.artist.hashCode())*31+t.playState)*31+(a==null?0:System.identityHashCode(a));}
    static int key(long now){MediaState.Snapshot t=track;if(t==null)return 0;
        int h=t.title.hashCode()*31+t.artist.hashCode();h=h*31+t.playState;h=h*31+(t.hasProgress()?(int)(position(t)/1000):-1);
        if(now>=0&&marquee)h=h*31+(int)(now/50);   // the strip's line is scrolling: a new frame each tick
        Bitmap a=art;return h*31+(a==null?0:System.identityHashCode(a));}
    /** The strip's line was wider than its room at the last draw, so it scrolls. */
    static volatile boolean marquee;
    /** The strip's line: 가수 - 제목, or whichever of the two there is. */
    static String line(MediaState.Snapshot t){return t.artist.isEmpty()?t.title:t.title.isEmpty()?t.artist:t.artist+" - "+t.title;}
    /** Scroll speed (px a second at size 1), the gap before the line comes round again, the pause at the start of each round. */
    static final float SPEED=30,ROUND_GAP=40;static final long PAUSE=1500;
    /** How far the line has scrolled at now: still for PAUSE, then SPEED*k a second until it has gone round (span = line + gap). */
    static float offset(long now,float span,float k){long cycle=PAUSE+(long)(span/(SPEED*k)*1000),t=(now%cycle+cycle)%cycle;return t<PAUSE?0:(t-PAUSE)*SPEED*k/1000f;}
    /** The cells of the music shape (size 보통): cell 0 the large value under the progress line, cell 1 the small one right of it. */
    static float[] rect(int n,int i){return n<2?new float[]{118,62,W,H}:i==0?new float[]{118,62,200,H}:new float[]{200,62,W,H};}

    /** The music parts in the 색감's language (FloatSkin's cells): ink, muted, accent, progress track, typeface; sPad = the skin
     *  frames its cells, so the content keeps clear of the frame; sBar = the progress bar's form (0 round, 1 flat, 2 a hairline). */
    private static int sInk,sMut,sAcc,sTrack,sBar;private static Typeface sFace;private static boolean sShadow,sPad;
    private static void look(int skin){
        int acc=Theme.current().map(0xff5bc8f5);sFace=Typeface.DEFAULT_BOLD;sShadow=false;sPad=true;sBar=1;
        switch(skin){
            case FloatSkin.RACE:sInk=0xfff2f6f8;sMut=0xff8fa0a8;sAcc=acc;sTrack=0xff26323a;return;
            case FloatSkin.F1:sInk=0xffffffff;sMut=0xff8fa0a8;sAcc=0xff2fe06a;sTrack=0xff1d2428;sPad=false;sBar=0;return;
            case FloatSkin.RETRO:sInk=0xffffb347;sMut=0xffc46a1c;sAcc=0xffff9a2e;sTrack=0xff2a1606;sFace=Typeface.MONOSPACE;return;
            case FloatSkin.VFD:sInk=0xff5ef2d2;sMut=0xb35ef2d2;sAcc=0xff5ef2d2;sTrack=0x1a5ef2d2;return;
            case FloatSkin.HUD:sInk=0xff62ff8a;sMut=0xff62ff8a;sAcc=0xff62ff8a;sTrack=0x5562ff8a;sFace=Typeface.MONOSPACE;sShadow=true;sBar=2;return;   // no body: a dark copy under the green
            case FloatSkin.PIXEL:sInk=0xffffffff;sMut=0xffffd84a;sAcc=0xff3ddc5a;sTrack=0xff2a2a2a;return;
            case FloatSkin.VU:sInk=0xff3a3226;sMut=0xff6b5f4a;sAcc=0xffc8302a;sTrack=0x403a3226;sBar=0;return;   // dark ink on the ivory plate
            case FloatSkin.NIGHT:sInk=0xffc5ccd0;sMut=0xff8a959a;sAcc=acc;sTrack=0xff2b3134;sFace=Typeface.DEFAULT;sPad=false;sBar=2;return;
            case FloatSkin.CARD:sInk=0xffffffff;sMut=0xff98989f;sAcc=acc;sTrack=0xff3a3a3c;sFace=Typeface.DEFAULT;sPad=false;sBar=0;return;
            case FloatSkin.WHITE:sInk=0xff1c1c1e;sMut=0xff6e6e73;sAcc=0xff1677e5;sTrack=0xffdcdce0;sFace=Typeface.DEFAULT;sPad=false;sBar=0;return;
            case FloatSkin.OEM:sInk=0xffffffff;sMut=0xff8b9197;sAcc=0xff2f7fe0;sTrack=0xff23272b;sFace=Typeface.DEFAULT;sBar=1;return;
            default:sInk=FloatPanel.TXT;sMut=FloatPanel.MUT;sAcc=FloatPanel.ACC;sTrack=FloatPanel.TRACK;sPad=false;sBar=0;
        }
    }
    private static void box(Canvas c,float l,float t,float rt,float b,float sw,int col){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(sw);p.setColor(col);c.drawRect(l,t,rt,b,p);p.setStyle(Paint.Style.FILL);}
    private static void fill(Canvas c,float l,float t,float rt,float b,int col){p.setStyle(Paint.Style.FILL);p.setColor(col);c.drawRect(l,t,rt,b,p);}
    /** The background in the 색감, framed as that skin frames a cell: the race screen's thin box, the retro orange rule, the VFD's
     *  faint outline, the HUD's corner brackets, the 8-bit dialog's thick border, the cassette's ivory plate; plain, F1 and night
     *  have none. */
    private static void back(Canvas c,int sk,float w,float h,float k){
        if(sk==FloatSkin.PLAIN){FloatPanel.outline(c,w,h,k,FloatPanel.BG,FloatPanel.EDGE);return;}
        if(sk==FloatSkin.PIXEL){int o=Math.max(1,Math.round(k));fill(c,0,0,w,h,(int)(0xf0*Math.min(1,FloatPanel.alphaF()))<<24);
            fill(c,2*o,2*o,w-2*o,4*o,0xffffffff);fill(c,2*o,h-4*o,w-2*o,h-2*o,0xffffffff);fill(c,2*o,2*o,4*o,h-2*o,0xffffffff);fill(c,w-4*o,2*o,w-2*o,h-2*o,0xffffffff);return;}
        FloatSkin.body(c,sk,w,h,k,FloatPanel.alphaF());
        float i=3.5f*k;
        switch(sk){
            case FloatSkin.RACE:box(c,i,i,w-i,h-i,1,0xff2a353c);break;
            case FloatSkin.RETRO:box(c,i,i,w-i,h-i,1,0xffb4500f);break;
            case FloatSkin.VFD:box(c,i,i,w-i,h-i,1,0x385ef2d2);break;
            case FloatSkin.VU:p.setStyle(Paint.Style.FILL);p.setColor(0xffece0bc);r.set(4*k,4*k,w-4*k,h-4*k);c.drawRoundRect(r,3*k,3*k,p);break;
            case FloatSkin.HUD:for(int pass=0;pass<2;pass++){int g=pass==0?0xb4000000:0xff62ff8a;float d=pass==0?k:0,L=8*k,x0=4*k+d,y0=4*k+d,x1=w-4*k+d,y1=h-4*k+d,sw=1.2f*k;
                fill(c,x0,y0,x0+L,y0+sw,g);fill(c,x0,y0,x0+sw,y0+L,g);fill(c,x1-L,y0,x1,y0+sw,g);fill(c,x1-sw,y0,x1,y0+L,g);
                fill(c,x0,y1-sw,x0+L,y1,g);fill(c,x0,y1-L,x0+sw,y1,g);fill(c,x1-L,y1-sw,x1,y1,g);fill(c,x1-sw,y1-L,x1,y1,g);}break;
            default:break;
        }
    }
    private static void say(Canvas c,String s,float x,float y,float size,int color,Paint.Align a,Typeface face){
        if(sShadow)text(c,s,x+1,y+1,size,0xb4000000,a,face);text(c,s,x,y,size,color,a,face);}
    /** The strip at 0,y, w wide (the body's width), for size k, at now (the scroll): the album art (or the play state) on the left,
     *  가수 - 제목, the progress bar under it. No times: the bar says it. */
    static void strip(Canvas c,float w,float y,float k,long now){
        MediaState.Snapshot t=track;boolean on=playing(t);float h=STRIP_H*k;int sk=FloatPanel.skinNow();look(sk);
        c.save();c.translate(0,y);
        back(c,sk,w,h,k);
        float x0=(sPad?9:8)*k,base=h/2+(sPad?1.5f:3)*k,by=h-(sPad?9.5f:5.5f)*k,right=w-(sPad?10:9)*k,lx,size=12*k;Bitmap b=art;
        if(w<NARROW*k)lx=x0;   // a narrow window (세로 띠, 세로 단수): the words alone, the art and the play glyph would leave them no room
        else if(on&&b!=null&&!b.isRecycled()){float a=h-(sPad?14:10)*k,ay=(h-a)/2;r.set(x0,ay,x0+a,ay+a);p.setColor(0xff000000);c.drawBitmap(b,null,r,p);   // Paint(3): filtered; opaque
            if(t.playState!=1){fill(c,x0,ay,x0+a,ay+a,0x99000000);glyph(c,x0+a*.27f,ay+a/2,a*.3f,false,0xffffffff);}   // paused: the bars over the dimmed art
            lx=x0+a+6*k;}
        else{if(on)glyph(c,x0+2*k,base-4*k,5*k,t.playState==1,sAcc);else say(c,"♪",x0+4*k,base+k,12*k,sMut,Paint.Align.CENTER,Typeface.DEFAULT_BOLD);
            lx=x0+16*k;}
        float room=right-lx;
        if(!on){marquee=false;say(c,fit(com.veloster.i18n.UiText.t("재생 중인 곡 없음"),size,sFace,room),lx,base,size,sMut,Paint.Align.LEFT,sFace);}
        else{String line=line(t);float lw=width(line,size,sFace);boolean still=FloatPanel.stillNow();marquee=!still&&lw>room;
            if(still&&lw>room){line=fit(line,size,sFace,room);lw=width(line,size,sFace);}   // the settings' previews: cut, not scrolled (a still picture is not redrawn to scroll)
            c.save();c.clipRect(lx,0,lx+room,h);
            float span=lw+ROUND_GAP*k,off=marquee?offset(now,span,k):0;
            say(c,line,lx-off,base,size,sInk,Paint.Align.LEFT,sFace);if(marquee)say(c,line,lx-off+span,base,size,sInk,Paint.Align.LEFT,sFace);   // the next round follows the gap
            c.restore();}
        bar(c,lx,by,right,2.5f*k,progress(t));
        c.restore();
    }
    /** The music shape at 0,0 for size k: n cells (1 or 2) of signals sigs, slots from first; rec feeds the peaks and line graphs. */
    static void shape(Canvas c,float k,int n,Telemetry.Sample[] q,int[] sigs,int first,boolean rec,long now){
        MediaState.Snapshot t=track;boolean on=playing(t);float w=W*k,h=H*k;int sk=FloatPanel.skinNow();look(sk);
        back(c,sk,w,h,k);
        float a=h-20*k,x=a+20*k,right=w-10*k;Bitmap b=art;
        r.set(10*k,10*k,10*k+a,10*k+a);
        if(b!=null&&!b.isRecycled()){p.setColor(0xff000000);c.drawBitmap(b,null,r,p);}   // Paint(3): filtered; opaque
        else{p.setStyle(Paint.Style.FILL);p.setColor(sTrack);c.drawRoundRect(r,8*k,8*k,p);say(c,"♪",10*k+a/2,10*k+a*.68f,a*.5f,sAcc,Paint.Align.CENTER,sFace);}
        if(on){say(c,fit(t.title.isEmpty()?t.artist:t.title,15*k,sFace,right-x),x,27*k,15*k,sInk,Paint.Align.LEFT,sFace);
            if(!t.title.isEmpty())say(c,fit(t.artist,11*k,sFace,right-x),x,42*k,11*k,sMut,Paint.Align.LEFT,sFace);}
        else say(c,fit(com.veloster.i18n.UiText.t("재생 중인 곡 없음"),13*k,sFace,right-x),x,30*k,13*k,sMut,Paint.Align.LEFT,sFace);
        bar(c,x,50*k,right,3*k,progress(t));
        p.setColor(sTrack);c.drawRect(x,62*k,right,62*k+1,p);
        // the right column first (the gear, and the small value under it), so the large value knows the room it has left of it
        String gear=FloatPanel.gear(q);   // the gear, in the accent, above the small value
        say(c,gear,right,(n<2?h-12*k:h-30*k),18*k,sAcc,Paint.Align.RIGHT,sFace);
        float side=width(gear,18*k,sFace);
        for(int j=Math.min(n,2)-1;j>=0;j--){int sig=sigs[j];float v=FloatPanel.value(q,sig);if(rec)FloatPanel.record(first+j,sig,v,now);
            int f=FloatPanel.fmt(sig),lv=Tiles.level(f,v),col=FloatSkin.warn(lv,sInk,FloatSkin.light(FloatPanel.skinNow()));
            String val=FloatPanel.valText(q,sig,v),unit=Telemetry.UNITS[f]==null?"":Telemetry.UNITS[f];
            if(j==0){say(c,FloatPanel.label(sig),x,76*k,10*k,sMut,Paint.Align.LEFT,sFace);
                // 26 down to 14 until value and unit end 8 short of the right column: -0.65 bar beside 1561 rpm ran into it
                float uw=unit.isEmpty()?0:3*k+width(unit,10*k,sFace),room=right-side-8*k-x,size=26*k;
                while(size>14*k&&width(val,size,sFace)+uw>room)size-=k;
                float vw=width(val,size,sFace);say(c,val,x,h-12*k,size,Float.isNaN(v)?sMut:col,Paint.Align.LEFT,sFace);
                say(c,unit,x+vw+3*k,h-12*k,10*k,sMut,Paint.Align.LEFT,sFace);}
            else{String s=fit(val+(unit.isEmpty()?"":" "+unit),11*k,sFace,62*k);side=Math.max(side,width(s,11*k,sFace));
                say(c,s,right,h-12*k,11*k,Float.isNaN(v)?sMut:col,Paint.Align.RIGHT,sFace);}}
    }
    /** 세로 띠 + RPM's box under its rows while a track plays (l,t,rt,b in the window; FloatPanel draws it each frame over the body):
     *  the art as large as the box leaves room for, the play state and time beside it when there is room, then the title (scrolling
     *  when it does not fit), the artist and the progress line. */
    static void box(Canvas c,float l,float t,float rt,float b,float k,long now){
        MediaState.Snapshot tr=track;if(!playing(tr))return;look(FloatPanel.skinNow());
        float x=l+8*k,right=rt-8*k,w=right-x,ts=12*k,as=10*k,barY=b-8*k,y=t+8*k;
        float a=Math.min(w,barY-y-(ts+as+18*k));Bitmap bm=art;
        if(a>=24*k){r.set(x,y,x+a,y+a);
            if(bm!=null&&!bm.isRecycled()){p.setColor(0xff000000);c.drawBitmap(bm,null,r,p);}   // Paint(3): filtered; opaque
            else{p.setStyle(Paint.Style.FILL);p.setColor(sTrack);c.drawRoundRect(r,6*k,6*k,p);say(c,"♪",x+a/2,y+a*.68f,a*.5f,sAcc,Paint.Align.CENTER,sFace);}
            if(w-a>=26*k){float gx=x+a+7*k;glyph(c,gx+k,y+7*k,5*k,tr.playState==1,sAcc);   // ▶ or ❚❚, the time under it
                if(tr.hasProgress()){long sec=position(tr)/1000;say(c,(sec/60)+":"+(sec%60<10?"0":"")+(sec%60),gx,y+26*k,9*k,sMut,Paint.Align.LEFT,sFace);}}
            y+=a+4*k;}
        String title=tr.title.isEmpty()?tr.artist:tr.title;float base=y+ts,lw=width(title,ts,sFace);boolean mq=!FloatPanel.stillNow()&&lw>w;marquee=mq;
        if(!mq)say(c,fit(title,ts,sFace,w),x,base,ts,sInk,Paint.Align.LEFT,sFace);
        else{c.save();c.clipRect(x,t,right,b);float span=lw+ROUND_GAP*k,off=offset(now,span,k);
            say(c,title,x-off,base,ts,sInk,Paint.Align.LEFT,sFace);say(c,title,x-off+span,base,ts,sInk,Paint.Align.LEFT,sFace);c.restore();}
        if(!tr.title.isEmpty()&&!tr.artist.isEmpty())say(c,fit(tr.artist,as,sFace,w),x,base+as+4*k,as,sMut,Paint.Align.LEFT,sFace);
        bar(c,x,barY,right,2.5f*k,progress(tr));
    }
    /** The progress bar in the skin's form: round ends, flat, or a hairline. */
    private static void bar(Canvas c,float l,float t,float rt,float h,float f){p.setStyle(Paint.Style.FILL);
        if(sBar==2){float y=t+h/2;fill(c,l,y,rt,y+1,sTrack);if(f>0)fill(c,l,y-1,l+(rt-l)*f,y+2,sAcc);return;}
        float rad=sBar==0?h/2:0;p.setColor(sTrack);r.set(l,t,rt,t+h);c.drawRoundRect(r,rad,rad,p);
        if(f>0){p.setColor(sAcc);r.set(l,t,l+(rt-l)*f,t+h);c.drawRoundRect(r,rad,rad,p);}}
    /** ▶ while it plays, ❚❚ otherwise. */
    private static void glyph(Canvas c,float x,float y,float s,boolean play,int col){p.setStyle(Paint.Style.FILL);p.setColor(col);
        if(play){android.graphics.Path a=new android.graphics.Path();a.moveTo(x,y-s);a.lineTo(x+s*1.6f,y);a.lineTo(x,y+s);a.close();c.drawPath(a,p);}
        else{c.drawRect(x,y-s,x+s*.55f,y+s,p);c.drawRect(x+s*.95f,y-s,x+s*1.5f,y+s,p);}}
    /** s, or as much of it as fits in max followed by an ellipsis. */
    static String fit(String s,float size,Typeface f,float max){if(max<=0)return "";if(width(s,size,f)<=max)return s;
        int lo=0,hi=s.length();while(lo<hi){int m=(lo+hi+1)/2;if(width(s.substring(0,m)+"…",size,f)<=max)lo=m;else hi=m-1;}
        return lo==0?"…":s.substring(0,lo).trim()+"…";}
    private static void text(Canvas c,String s,float x,float y,float size,int color,Paint.Align a,Typeface face){
        p.setStyle(Paint.Style.FILL);p.setTypeface(face);p.setTextSize(size);p.setTextAlign(a);p.setColor(color);c.drawText(s,x,y,p);}
    private static float width(String s,float size,Typeface face){p.setTypeface(face);p.setTextSize(size);return p.measureText(s);}
}
