package com.veloster.vehicle;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import java.util.Locale;

/**
 * Cassette deck: the playing track on the tape label, tape winding onto the right reel with the track's progress,
 * reels spinning with rpm, two VU needles (accelerator, boost), a mechanical counter (odometer tenths) and a VFD speed.
 */
final class CassetteCluster extends ClusterKit {
    @Override boolean showsMusic(){return true;}   // the label is the track
    private static final int VF=0xff5effc0,VF_OFF=0xff0b2a20,INK=0xff1f3a8a,FACE=0xffefe3c2;
    private float rot,needleL,needleR;
    private long last;
    /** 카세트 찰칵 소리 switch (설정 → 사운드). The click fires only when a key is pressed on screen — never on play/track
     *  changes coming from the phone (those arrive as 재생 중인 곡 없음 then the new track, which clicked twice and grated). */
    static volatile boolean clickSound=true;
    static volatile int cue;      // ShiftBeep.LATCH/UP/TAP, set by a key press; VehicleDash plays and clears it
    static volatile int keyCmd;   // the media command a pressed key asks VehicleDash to send: -1 prev, 1 next, 2 play, 3 pause
    static int consumeKeyCmd(){int k=keyCmd;keyCmd=0;return k;}
    private long tapUntil;private int tapKey=-1;
    private int tapeColor=0xffd9542b;private android.graphics.Bitmap tapeArt;   // the label strip takes the album art's main colour

    @Override protected void draw(Canvas c){
        float rpm=v(0),pedal=v(9),boost=v(Telemetry.BOOST),spd=v(7),top=boostTop();
        float dt=last==0||now-last>2000?0:(now-last)/1000f;last=now;
        rot+=dt*(0.4f+(Float.isNaN(rpm)?0:rpm)/1500);
        float tl=Float.isNaN(pedal)?0:pedal/100,tr=Float.isNaN(boost)?0:clamp((boost+0.7f)/(top+0.7f),0,1);
        needleL+=(tl-needleL)*Math.min(1,dt*(tl>needleL?10:4));needleR+=(tr-needleR)*Math.min(1,dt*(tr>needleR?10:4));
        boolean has=music!=null&&music.title!=null&&!music.title.isEmpty();
        boolean known=has&&music.hasProgress();   // position and duration both reported
        long pos=!known?0:Math.min(music.durationMs,music.positionMs+(music.playState==1?music.timeAgeMs:0));
        float prog=known?clamp(pos/(float)music.durationMs,0,1):0.35f;
        fill(c,0,52,800,480,0xff17181a);
        // door, shell, screws
        round(c,20,60,528,356,10,0xff060607);outline(c,20,60,528,356,10,2,0xff3a3b3e);
        round(c,40,76,508,340,12,0xff2a2c30);outline(c,40,76,508,340,12,1.5f,0xff4a4c52);
        for(float[] s:new float[][]{{52,88},{496,88},{52,328},{496,328},{274,328}}){dot(c,s[0],s[1],5,0xff55585e);line(c,s[0]-3,s[1],s[0]+3,s[1],1.5f,0xff2a2c30);}
        // label: the header strip takes the album art's main colour; the art, when there is one, sits as a cover
        android.graphics.Bitmap art=has?artwork:null;
        if(art!=null){if(art!=tapeArt){tapeArt=art;tapeColor=dominant(art);}}else{tapeColor=0xffd9542b;tapeArt=null;}
        round(c,58,94,490,250,6,0xffefe6cf);fill(c,58,94,490,116,tapeColor);fill(c,58,116,490,122,mix(tapeColor,0xffffffff,0.4f));
        bold(c,"A",74,111,18,0xffffffff,Paint.Align.LEFT);bold(c,"C-60 · TYPE II",480,111,12,0xffffffff,Paint.Align.RIGHT);
        boolean cover=art!=null;float tx=cover?148:76,avail=cover?334:404;
        if(cover){round(c,62,122,134,194,5,0xff20160e);blit(c,art,0,0,art.getWidth(),art.getHeight(),64,124,132,192);outline(c,62,122,134,194,5,1,mix(tapeColor,0xff000000,0.3f));}
        String title=has?music.title:t("재생 중인 곡 없음"),artist=has&&music.artist!=null?music.artist:"";
        float tw=width(title,30,Typeface.DEFAULT_BOLD);   // the title slides when it is wider than the label
        if(tw<=avail)text(c,title,tx,154,30,INK,Paint.Align.LEFT,Typeface.DEFAULT_BOLD);
        else{float gap=48,period=tw+gap,off=(now/1000f*45f)%period;
            c.save();c.clipRect(tx,128,482,166);
            text(c,title,tx-off,154,30,INK,Paint.Align.LEFT,Typeface.DEFAULT_BOLD);
            text(c,title,tx-off+period,154,30,INK,Paint.Align.LEFT,Typeface.DEFAULT_BOLD);
            c.restore();}
        // artist on the left, the time right-aligned so it never runs into the artist
        float artistRight=456;   // the time takes the right; the artist slides in the lane left of it, never clipped to …
        if(known){String tm=mmss(pos)+" / "+mmss(music.durationMs);text(c,tm,462,180,16,0xff3a4f9a,Paint.Align.RIGHT,Typeface.DEFAULT);artistRight=462-width(tm,16,Typeface.DEFAULT)-10;}
        if(has&&!artist.isEmpty()){float aw=width(artist,17,Typeface.DEFAULT);
            if(tx+aw<=artistRight)text(c,artist,tx,180,17,0xff3a4f9a,Paint.Align.LEFT,Typeface.DEFAULT);
            else{float gap=40,period=aw+gap,off=(now/1000f*38f)%period;
                c.save();c.clipRect(tx,168,artistRight,186);
                text(c,artist,tx-off,180,17,0xff3a4f9a,Paint.Align.LEFT,Typeface.DEFAULT);
                text(c,artist,tx-off+period,180,17,0xff3a4f9a,Paint.Align.LEFT,Typeface.DEFAULT);
                c.restore();}}
        fill(c,76,188,480,189,0x401f3a8a);
        // reel window
        round(c,146,194,402,246,26,0xff0b0b0c);
        c.save();c.clipRect(146,194,402,246);
        reel(c,206,13+26*(1-prog),-rot*30/(13+26*(1-prog)));reel(c,342,13+26*prog,-rot*30/(13+26*prog));
        c.restore();
        outline(c,146,194,402,246,26,1.5f,0xff5a5c62);
        path.reset();path.moveTo(110,270);path.lineTo(438,270);path.lineTo(460,336);path.lineTo(88,336);path.close();poly(c,0xff232528);
        fill(c,100,324,448,327,0xff3a2516);
        for(float x:new float[]{140,226,300,386})round(c,x,312,x+22,330,3,0xff0b0b0c);
        // piano keys: ▶ down while playing, ❚❚ while paused; a pressed key also dips for 180 ms (its own feedback)
        String[] keys={"◀◀","▶","▶▶","■","❚❚"};   // no record: a media player cannot record
        boolean playing=has&&music.playState==1;
        int pressed=!has||music.playState<0?-1:playing?1:4;
        for(int i=0;i<5;i++){boolean down=i==pressed||i==tapKey&&now<tapUntil;float x=44+i*93,y=372+(down?5:0);
            round(c,x,y,x+78,y+72,6,down?0xff2c2e32:0xff3a3c41);outline(c,x,y,x+78,y+72,6,1,0xff55585e);fill(c,x+4,y+4,x+74,y+10,down?0xff202225:0xff4a4d52);
            bold(c,keys[i],x+39,y+46,20,i==1?0xff3ddc84:0xffdddddd,Paint.Align.CENTER);
            dot(c,x+39,364,3.5f,down?0xff3ddc84:0xff1d2a22);}
        // VU meters
        vu(c,540,62,needleL,t("L · 가속 페달"),fmt("%.0f",pedal)+" %");
        vu(c,540,188,needleR,t("R · 부스트"),fmt("%+.2f",boost)+String.format(Locale.US," / %.1f bar",top));
        // counter drums
        round(c,540,314,784,354,6,0xff0d0d0f);
        float odo=v(Telemetry.ODOMETER);String cnt=Float.isNaN(odo)?"----":String.format(Locale.US,"%04d",((int)(odo*10))%10000);
        for(int i=0;i<4;i++){float x=552+i*26;round(c,x,320,x+22,348,2,0xffe8e8e8);bold(c,cnt.substring(i,i+1),x+11,342,20,0xff111111,Paint.Align.CENTER);}
        bold(c,"COUNTER",664,332,11,0xff8a8c90,Paint.Align.LEFT);text(c,t("주행 0.1 km"),664,346,11,0xff8a8c90,Paint.Align.LEFT,Typeface.DEFAULT);
        // VFD
        round(c,540,364,784,472,6,0xff06100c);outline(c,540,364,784,472,6,1,0xff2b3a33);
        String sp=Float.isNaN(spd)?"---":String.valueOf(Math.round(spd));while(sp.length()<3)sp=" "+sp;
        seg7(c,sp,662,378,52,VF,VF_OFF,false);
        bold(c,"km/h",672,428,13,VF,Paint.Align.LEFT);
        bold(c,gear(),774,402,22,VF,Paint.Align.RIGHT);bold(c,fmt("%,.0f",rpm)+" rpm",774,428,14,VF,Paint.Align.RIGHT);
        bold(c,playing?"AUTO REVERSE · PLAY":"AUTO REVERSE · STOP",552,462,11,0xff3a9a78,Paint.Align.LEFT);
    }
    /** A key press drives the media (VehicleDash reads keyCmd) and makes the only click. */
    @Override boolean tap(float x,float y){
        if(y<372||y>444)return false;
        for(int i=0;i<5;i++){float kx=44+i*93;if(x>=kx&&x<=kx+78){
            tapKey=i;tapUntil=now+180;
            cue=(i==0||i==2)?ShiftBeep.TAP:ShiftBeep.LATCH;   // ◀◀/▶▶ spring back, ▶/■/❚❚ latch
            keyCmd=i==0?-1:i==2?1:i==1?2:5;   // ◀◀ prev / ▶▶ next / ▶ play; ■ and ❚❚ mute (the head unit has no BT pause, only mute)
            return true;}}
        return false;
    }
    /** The album art's most vivid colour (saturation x brightness), for the tape label. */
    private int dominant(android.graphics.Bitmap b){
        try{
            android.graphics.Bitmap s=android.graphics.Bitmap.createScaledBitmap(b,8,8,true);
            int[] px=new int[64];s.getPixels(px,0,8,0,0,8,8);if(s!=b)s.recycle();
            double best=-1;int col=0xffd9542b;
            for(int p:px){int r=(p>>16)&255,g=(p>>8)&255,bl=p&255,mx=Math.max(r,Math.max(g,bl)),mn=Math.min(r,Math.min(g,bl));
                double sat=mx==0?0:(mx-mn)/(double)mx,score=sat*mx;
                if(mx>40&&mx<252&&score>best){best=score;col=0xff000000|(r<<16)|(g<<8)|bl;}}
            return col;
        }catch(RuntimeException e){return 0xffd9542b;}
    }
    private void reel(Canvas c,float x,float rad,float a){
        dot(c,x,220,rad,0xff3a2516);ring(c,x,220,rad-1.5f,1,0xff4a3220);
        dot(c,x,220,12,0xffe8e8ea);dot(c,x,220,7,0xff0b0b0c);
        for(int k=0;k<6;k++){double b=a+k*Math.PI/3;line(c,x+(float)Math.sin(b)*5,220-(float)Math.cos(b)*5,x+(float)Math.sin(b)*9,220-(float)Math.cos(b)*9,3,0xffe8e8ea);}
    }
    private void vu(Canvas c,float x,float y,float n,String label,String sub){
        round(c,x,y,x+244,y+118,8,0xff0d0d0f);outline(c,x,y,x+244,y+118,8,1.5f,0xff3a3b3e);round(c,x+8,y+8,x+236,y+110,5,FACE);
        float cx=x+122,cy=y+128,rad=96;
        r.set(cx-rad,cy-rad,cx+rad,cy+rad);p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f);p.setColor(0xff222222);c.drawArc(r,-140,72*1.0f,false,p);
        p.setStrokeWidth(4);p.setColor(0xffc0261f);c.drawArc(r,-68,28,false,p);p.setStyle(Paint.Style.FILL);
        String[] l={"-20","-10","-7","-5","-3","0","+3"};float[] f={0,0.25f,0.38f,0.5f,0.62f,0.72f,1};
        for(int i=0;i<7;i++){float d=-50+f[i]*100;int col=f[i]>=0.72f?0xffc0261f:0xff222222;
            line(c,px(cx,rad,d),py(cy,rad,d),px(cx,rad+8,d),py(cy,rad+8,d),1.5f,col);
            float ly=py(cy,rad+18,d);if(ly>y+14)bold(c,l[i],px(cx,rad+18,d),ly+4,11,col,Paint.Align.CENTER);}
        text(c,"VU",cx,y+80,18,0xff3a3122,Paint.Align.CENTER,Typeface.DEFAULT_BOLD);
        bold(c,label,x+16,y+102,12,0xff3a3122,Paint.Align.LEFT);bold(c,sub,x+228,y+102,12,0xff3a3122,Paint.Align.RIGHT);
        c.save();c.clipRect(x+8,y+8,x+236,y+110);
        float d=-50+clamp(n,0,1.02f)*100;line(c,cx,cy,px(cx,rad+4,d),py(cy,rad+4,d),1.6f,0xff111111);
        c.restore();
        dot(c,x+226,y+18,4,n>0.72f?0xffff3a2a:0xff4a1a14);
    }
    private String ellipsize(String s,int max,float widthPx){
        String o=s.length()>max?s.substring(0,max)+"…":s;
        while(o.length()>2&&width(o,30,Typeface.DEFAULT_BOLD)>widthPx)o=o.substring(0,o.length()-2)+"…";
        return o;
    }
    private static String mmss(long ms){long s=ms/1000;return String.format(Locale.US,"%d:%02d",s/60,s%60);}
}
