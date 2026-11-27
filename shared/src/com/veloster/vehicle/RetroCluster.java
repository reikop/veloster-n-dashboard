package com.veloster.vehicle;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import java.util.Calendar;

/**
 * 레트로: eight 1980s-style digital dashboards in one tab, a tap on the body moving to the next one. Original layouts in
 * the manner of the period's vacuum-fluorescent, LED and LCD clusters (no maker's design, name or badge is copied):
 * twin pods, green arcs, a curved bar tach, an LED dot matrix with keys, a rainbow concept pod, a perspective road, three
 * amber panels and twin circles. Each style's unlit glass (ghost segments, scales, labels) is drawn once into a bitmap
 * kept until the style, the redline or the screen changes; a frame draws only that bitmap and the lit elements.
 */
final class RetroCluster extends ClusterKit {
    /** Style names (Korean source strings, translations/en.json). */
    static final String[] NAMES={"트윈 포드","그린 아크","곡선 타코","LED 매트릭스","레인보우","원근 도로","앰버 트리플","트윈 서클"};
    static final int COUNT=NAMES.length;
    /** The style shown; VehicleDash restores and saves it (pit_vehicle_ui "retro_style"). */
    static int style;
    static int validStyle(int s){return s>=0&&s<COUNT?s:0;}
    private long bannerUntil;

    @Override boolean tap(float x,float y){style=(style+1)%COUNT;bannerUntil=now+1800;return true;}

    // ---- the cached unlit layer ---------------------------------------------------------------------------------
    private Bitmap glass;
    private int glassKey=-1;
    @Override void trim(){glass=recycle(glass);glassKey=-1;}

    @Override protected void draw(Canvas c){
        style=validStyle(style);
        int k=style*100000+Math.round(prof.redline/10)*10+Math.round(prof.maxRpm/1000);
        if(glass==null||glass.isRecycled()||glassKey!=k){
            if(glass==null||glass.isRecycled())glass=bitmap(800,428);
            Canvas g=new Canvas(glass);g.translate(0,-52);
            fill(g,0,52,800,480,0xff000000);
            switch(style){case 0:pods0(g);break;case 1:arcs0(g);break;case 2:curve0(g);break;case 3:led0(g);break;
                case 4:rainbow0(g);break;case 5:road0(g);break;case 6:amber0(g);break;default:circles0(g);}
            for(int i=0;i<COUNT;i++)dot(g,400+(i-3.5f)*12,474,2.4f,i==style?0xff8a949a:0xff23282b);
            glassKey=k;
        }
        blit(c,glass,0,0,800,428,0,52,800,480);
        glowing=true;segGlow=6;
        switch(style){case 0:pods(c);break;case 1:arcs(c);break;case 2:curve(c);break;case 3:led(c);break;
            case 4:rainbow(c);break;case 5:road(c);break;case 6:amber(c);break;default:circles(c);}
        glowing=false;segGlow=0;
        if(now<bannerUntil){
            String name=t(NAMES[style])+"  "+(style+1)+"/"+COUNT;float w=width(name,18,Typeface.DEFAULT_BOLD)+40;
            round(c,400-w/2,60,400+w/2,96,8,0xee101315);outline(c,400-w/2,60,400+w/2,96,8,1.5f,0xff4b5a62);
            bold(c,name,400,85,18,0xffe1edf2,Paint.Align.CENTER);
        }
    }

    // ---- glow -------------------------------------------------------------------------------------------------------
    // Lit elements get a halo: the same shape a little larger in two faint layers of its own colour, drawn under it, so
    // neighbouring cells bloom into each other like phosphor behind glass. No blur filter (unsupported or slow on the
    // head unit's renderer); about three draws per lit element. Only while the lit layer draws (glowing).
    private boolean glowing;
    private static final int HALO_OUT=0x1a000000,HALO_IN=0x34000000;
    @Override protected void fill(Canvas c,float l,float t,float rr,float b,int color){
        if(glowing){
            float g=Math.min(7,2+Math.min(rr-l,b-t)*0.5f);int rgb=col(color)&0xffffff;p.setStyle(Paint.Style.FILL);
            r.set(l-g,t-g,rr+g,b+g);p.setColor(rgb|HALO_OUT);c.drawRoundRect(r,g,g,p);
            float h=g*0.45f;r.set(l-h,t-h,rr+h,b+h);p.setColor(rgb|HALO_IN);c.drawRoundRect(r,h,h,p);
        }
        super.fill(c,l,t,rr,b,color);
    }
    @Override protected void round(Canvas c,float l,float t,float rr,float b,float rad,int color){
        if(glowing){
            int rgb=col(color)&0xffffff;p.setStyle(Paint.Style.FILL);
            r.set(l-8,t-8,rr+8,b+8);p.setColor(rgb|HALO_OUT);c.drawRoundRect(r,rad+8,rad+8,p);
            r.set(l-3.5f,t-3.5f,rr+3.5f,b+3.5f);p.setColor(rgb|HALO_IN);c.drawRoundRect(r,rad+3.5f,rad+3.5f,p);
        }
        super.round(c,l,t,rr,b,rad,color);
    }
    @Override protected void line(Canvas c,float x1,float y1,float x2,float y2,float width,int color){
        if(glowing){
            float g=Math.min(7,2+width*0.7f);int rgb=col(color)&0xffffff;
            p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(width+2*g);p.setColor(rgb|HALO_OUT);c.drawLine(x1,y1,x2,y2,p);
            p.setStrokeWidth(width+g);p.setColor(rgb|HALO_IN);c.drawLine(x1,y1,x2,y2,p);
            p.setStrokeCap(Paint.Cap.BUTT);p.setStyle(Paint.Style.FILL);
        }
        super.line(c,x1,y1,x2,y2,width,color);
    }
    @Override protected void text(Canvas c,String str,float x,float y,float size,int color,Paint.Align align,Typeface face){
        if(glowing){
            int rgb=col(color)&0xffffff;p.setTypeface(face);p.setTextSize(size);p.setTextAlign(align);
            p.setStyle(Paint.Style.STROKE);p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(size*0.32f);p.setColor(rgb|HALO_OUT);c.drawText(str,x,y,p);
            p.setStrokeWidth(size*0.14f);p.setColor(rgb|HALO_IN);c.drawText(str,x,y,p);
            p.setStrokeJoin(Paint.Join.MITER);p.setStyle(Paint.Style.FILL);
        }
        super.text(c,str,x,y,size,color,align,face);
    }
    @Override protected void poly(Canvas c,int color,long k){
        if(glowing){
            android.graphics.Path m=memo(k);int rgb=col(color)&0xffffff;
            p.setStyle(Paint.Style.STROKE);p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(12);p.setColor(rgb|HALO_OUT);c.drawPath(m,p);
            p.setStrokeWidth(5);p.setColor(rgb|HALO_IN);c.drawPath(m,p);
            p.setStrokeJoin(Paint.Join.MITER);
        }
        super.poly(c,color,k);
    }

    // ---- values -------------------------------------------------------------------------------------------------
    // Only what this car sends to the dashboard: rpm, oil / water temperature, speed, gear, throttle, brake pressure, G,
    // steering, ambient and intake temperature, boost / power / torque (computed), TPMS, TCS / ESC / ABS and the battery.
    // Fuel level, range, odometer, doors, lamps, turn signals and the engine warning lamp never arrive in Telemetry (Telemetry has
    // slots for them from the i30 PD port, nothing fills them here), so no style shows them. Turn signals come separately
    // (turnL / turnR, from Neon Drive's hscand feed).
    private boolean on(int i){float x=v(i);return !Float.isNaN(x)&&x>0;}
    private boolean shift(){float x=v(0);return !Float.isNaN(x)&&x>prof.redline-500;}
    private boolean tpmsLow(){for(int i=Telemetry.TPMS_FL;i<=Telemetry.TPMS_RR;i++){float x=v(i);if(!Float.isNaN(x)&&x<30)return true;}return false;}
    private boolean waterHot(){float x=v(2);return !Float.isNaN(x)&&x>=110;}
    private boolean oilHot(){float x=v(1);return !Float.isNaN(x)&&x>=130;}
    private boolean battLow(){float x=v(Telemetry.BAT_VOLTAGE);return !Float.isNaN(x)&&x<11.8f;}
    private boolean braking(){float x=v(10);return !Float.isNaN(x)&&x>3;}
    /** The warning words most styles carry: SHIFT, ABS, TCS OFF, ESC OFF, TPMS (ABS red, the rest amber). */
    private static final String[] WARN={"SHIFT","ABS","TCS OFF","ESC OFF","TPMS"};
    private final boolean[] warnOn=new boolean[WARN.length];
    private boolean[] warn(){warnOn[0]=shift();warnOn[1]=on(Telemetry.ABS);warnOn[2]=on(Telemetry.TCS);warnOn[3]=on(Telemetry.ESC);warnOn[4]=tpmsLow();return warnOn;}
    private String power(){float x=v(Telemetry.POWER);return num(x,3);}
    /** Turn lamps lit this frame (VehicleDash: Neon Drive's hscand feed, the real lamp phase or a 0.75 s timer). */
    boolean turnL,turnR;
    /** Turn arrows: tips at lx (pointing left) and rx (pointing right); the unlit pair in the glass layer (ghost). */
    private void turns(Canvas c,float lx,float rx,float y,float s,int col,boolean ghost){
        if(ghost||turnL)arrow(c,lx,y,s,1,col);
        if(ghost||turnR)arrow(c,rx,y,s,-1,col);
    }
    private void arrow(Canvas c,float x,float y,float s,int dir,int col){
        path.reset();path.moveTo(x,y);path.lineTo(x+dir*s,y-s*0.75f);path.lineTo(x+dir*s,y-s*0.3f);path.lineTo(x+dir*s*1.8f,y-s*0.3f);
        path.lineTo(x+dir*s*1.8f,y+s*0.3f);path.lineTo(x+dir*s,y+s*0.3f);path.lineTo(x+dir*s,y+s*0.75f);path.close();
        poly(c,col,key(key(key(key(0x41,x),y),s),dir));
    }
    private static float frac(float x,float lo,float hi){return Float.isNaN(x)?0:clamp((x-lo)/(hi-lo),0,1);}
    private static int lit(float x,float lo,float hi,int n){return Math.round(frac(x,lo,hi)*n);}
    private float rpmMax(){return prof.maxRpm>0?prof.maxRpm:8000;}
    /** n characters, right-justified, clamped to n digits; dashes when unknown. */
    private static String num(float x,int n){
        if(Float.isNaN(x)){char[] d=new char[n];java.util.Arrays.fill(d,'-');return new String(d);}
        int k=Math.max(0,Math.round(x));int max=1;for(int i=0;i<n;i++)max*=10;if(k>=max)k=max-1;
        String s=Integer.toString(k);while(s.length()<n)s=" "+s;return s;
    }
    private static String oneDec(float x){
        if(Float.isNaN(x))return "-.-";
        int k=Math.round(x*10),a=Math.abs(k);return (k<0?"-":"")+(a/10)+"."+(a%10);
    }
    private static String ghost(int n){char[] d=new char[n];java.util.Arrays.fill(d,'8');return new String(d);}
    /** The gear in a one-digit seven-segment slot (right edge x), or as text when it does not fit one. */
    private void gearSeg(Canvas c,float x,float y,float h,int col){
        String g=gear();boolean m=manualGate(g);if(m)g=g.substring(1);
        boolean one=g.length()==1&&"0123456789PRND".indexOf(g.charAt(0))>=0;
        if(g.length()==0||g.charAt(0)=='—'||g.charAt(0)=='-'){g="-";one=true;}   // unknown: a dash in the slot
        if(one)seg7(c,g,x,y,h,col,0,false);else bold(c,g,x-h*0.25f,y+h*0.8f,h*0.6f,col,Paint.Align.CENTER);
        if(m)mono(c,"M",x+4,y+h*0.3f,h*0.26f,col,Paint.Align.LEFT);
    }
    private String clock(){Calendar k=Calendar.getInstance();int h=k.get(Calendar.HOUR_OF_DAY),m=k.get(Calendar.MINUTE);return (h<10?"0":"")+h+":"+(m<10?"0":"")+m;}

    // ---- shared shapes ------------------------------------------------------------------------------------------
    /** Ring segment: a straight bar w wide on the radius at deg (clockwise from 12 o'clock), masked to the ring between r1
     *  and r2. Its sides stay parallel like a rectangle's; its inner and outer ends are cut on the two circles, so a row of
     *  them sits in a clean round band. Built as that shape (rectangle ∩ annulus) rather than clipped: Android 4.2's GPU
     *  renderer has no clipPath. */
    private void radial(Canvas c,float cx,float cy,float r1,float r2,float deg,float w,int col){
        float hw=Math.min(w/2,r1*0.99f),sn=(float)Math.sin(Math.toRadians(deg)),cs=(float)Math.cos(Math.toRadians(deg));
        float a2=(float)Math.asin(hw/r2),a1=(float)Math.asin(hw/r1);
        path.reset();
        for(int i=0;i<=RING_STEPS;i++){double f=-a2+2*a2*i/RING_STEPS;ringPt(i==0,cx,cy,sn,cs,(float)(r2*Math.cos(f)),(float)(r2*Math.sin(f)));}
        for(int i=RING_STEPS;i>=0;i--){double f=-a1+2*a1*i/RING_STEPS;ringPt(false,cx,cy,sn,cs,(float)(r1*Math.cos(f)),(float)(r1*Math.sin(f)));}
        path.close();
        poly(c,col,key(key(key(key(key(key(0x52,cx),cy),r1),r2),deg),w));
    }
    private static final int RING_STEPS=4;
    /** Point t along the radius, s across it (clockwise), around (cx,cy) for a bar whose radius points at deg. */
    private void ringPt(boolean first,float cx,float cy,float sn,float cs,float t,float s){
        float x=cx+t*sn+s*cs,y=cy-t*cs+s*sn;if(first)path.moveTo(x,y);else path.lineTo(x,y);
    }
    // Ring glow: one soft band along each run of lit segments of a colour (under them), not a halo per segment, so the
    // glow follows the circle instead of stepping around each segment's corners. glowStart, glowSeg per lit segment in
    // order, glowEnd; then draw the segments with ringLit (no halo of their own).
    private float gx,gy,g1,g2,ga,gb;private int gcol;private boolean gon;
    private void glowStart(float cx,float cy,float r1,float r2){gx=cx;gy=cy;g1=r1;g2=r2;gon=false;}
    private void glowSeg(Canvas c,float deg,float w,int col){
        float half=(float)Math.toDegrees(w/2/((g1+g2)/2)),a=deg-half,b=deg+half;
        if(gon&&col==gcol&&a-gb<6){gb=b;return;}
        glowEnd(c);gon=true;gcol=col;ga=a;gb=b;
    }
    private void glowEnd(Canvas c){
        if(!gon)return;gon=false;
        float mid=(g1+g2)/2;
        for(int layer=0;layer<2;layer++){
            float g=layer==0?7:3,ext=(float)Math.toDegrees(g/mid),a=ga-ext,b=gb+ext,ri=g1-g,ro=g2+g;
            int steps=Math.max(2,(int)Math.ceil((b-a)/3));
            path.reset();
            for(int i=0;i<=steps;i++){float d=a+(b-a)*i/steps;if(i==0)path.moveTo(px(gx,ro,d),py(gy,ro,d));else path.lineTo(px(gx,ro,d),py(gy,ro,d));}
            for(int i=steps;i>=0;i--){float d=a+(b-a)*i/steps;path.lineTo(px(gx,ri,d),py(gy,ri,d));}
            path.close();
            p.setStyle(Paint.Style.FILL);p.setColor((col(gcol)&0xffffff)|(layer==0?HALO_OUT:HALO_IN));
            c.drawPath(memo(key(key(key(key(key(key(0x47,gx),gy),ri),ro),a),b)),p);
        }
    }
    /** A lit ring segment without its own halo (the run's band from glowEnd is under it). */
    private void ringLit(Canvas c,float cx,float cy,float r1,float r2,float deg,float w,int col){boolean g=glowing;glowing=false;radial(c,cx,cy,r1,r2,deg,w,col);glowing=g;}
    /** A legend lamp behind smoked glass, unlit (glass layer): the legend barely showing through a hairline frame. */
    private void lampGlass(Canvas c,float l,float t,float rr,float b,String label,int col){
        round(c,l,t,rr,b,5,mix(col,0xff000000,0.05f));outline(c,l,t,rr,b,5,1,mix(col,0xff000000,0.17f));
        bold(c,label,(l+rr)/2,(t+b)/2+5,13,mix(col,0xff000000,0.26f),Paint.Align.CENTER);
    }
    /** Lit: the glass tinted with the lamp colour, a bright hairline frame, a soft halo and the legend glowing. */
    private void lamp(Canvas c,float l,float t,float rr,float b,String label,boolean on,int col){
        if(!on)return;
        boolean g=glowing;glowing=false;
        p.setStyle(Paint.Style.FILL);p.setColor((col&0xffffff)|0x24000000);r.set(l-5,t-5,rr+5,b+5);c.drawRoundRect(r,9,9,p);
        round(c,l,t,rr,b,5,mix(col,0xff000000,0.24f));fill(c,l+7,t+2,rr-7,t+3,mix(col,0xff000000,0.5f));
        outline(c,l,t,rr,b,5,1.2f,col);
        glowing=g;bold(c,label,(l+rr)/2,(t+b)/2+5,13,col,Paint.Align.CENTER);glowing=false;
        bold(c,label,(l+rr)/2,(t+b)/2+5,13,mix(0xffffffff,col,0.55f),Paint.Align.CENTER);glowing=g;
    }
    /** A slim key (glass layer): a dark recess, the face and a hairline highlight along its top. */
    private void keyGlass(Canvas c,float l,float t,float rr,float b){
        round(c,l,t,rr,b,7,0xff050505);round(c,l+1.5f,t+1.5f,rr-1.5f,b-1.5f,6,0xff161719);fill(c,l+10,t+2.5f,rr-10,t+3.5f,0xff2c2e31);
    }
    /** 5 x 7 dot-matrix text: every cell (all) or the lit dots of str, left at x. */
    private void dm(Canvas c,String str,float x,float y,float pitch,float size,int col,boolean all){
        for(int i=0;i<str.length();i++){
            int g=PixelGlyphs.glyph(str.charAt(i));float cx=x+i*6*pitch;
            for(int row=0;row<7;row++){
                int bits=all?31:PixelGlyphs.FONT_BITS[g*7+row];if(bits==0)continue;
                for(int col2=0;col2<5;col2++)if((bits&(16>>col2))!=0)fill(c,cx+col2*pitch,y+row*pitch,cx+col2*pitch+size,y+row*pitch+size,col);
            }
        }
    }
    private static String right(String s,int n){while(s.length()<n)s=" "+s;return s.length()>n?s.substring(s.length()-n):s;}

    // =============================================================================================================
    // 0 트윈 포드: two green glass pods, speed digits over a bar on the left, a widening bar tach on the right.
    private static final int P_G=0xff45ff8f,P_GG=0xff0b2615,P_A=0xffffab2e,P_AG=0xff2b1b07,P_R=0xffff3d2e,P_RG=0xff2c0a07,P_L=0xff21844c,P_B=0xff4aa3ff;
    private float podBarX(int i){return 440+i*6.8f;}
    private float podBarH(int i){return 18+i*2.0f;}
    private int zone(float r,int on,int amber,int red){return r>prof.redline?red:r>prof.redline-1000?amber:on;}
    private void pods0(Canvas c){
        fill(c,0,52,800,480,0xff020403);
        round(c,22,104,382,420,20,0xff050b07);outline(c,22,104,382,420,20,2,0xff17352a);
        round(c,418,104,778,420,20,0xff050b07);outline(c,418,104,778,420,20,2,0xff17352a);
        turns(c,24,776,82,14,P_GG,true);
        for(int i=0;i<WARN.length;i++)lampGlass(c,60+i*138,68,178+i*138,96,WARN[i],i==1?P_R:P_A);
        seg7(c,ghost(3),358,128,128,P_GG,0,false);mono(c,"km/h",358,280,16,P_L,Paint.Align.RIGHT);
        for(int i=0;i<40;i++)fill(c,44+i*8,298,50+i*8,328,i>=30?P_AG:P_GG);
        for(int k=0;k<=240;k+=60)mono(c,String.valueOf(k),44+k/240f*320,348,13,P_L,Paint.Align.CENTER);
        mono(c,"OIL",44,378,13,P_L,Paint.Align.LEFT);for(int i=0;i<12;i++)fill(c,92+i*8,366,98+i*8,380,i>=10?P_RG:P_GG);
        mono(c,"TEMP",206,378,13,P_L,Paint.Align.LEFT);for(int i=0;i<12;i++)fill(c,254+i*8,366,260+i*8,380,i>=9?P_RG:P_GG);
        mono(c,"BATT",44,406,13,P_L,Paint.Align.LEFT);seg7(c,"88.8",142,392,16,P_GG,0,false);mono(c,"V",148,406,13,P_L,Paint.Align.LEFT);
        mono(c,"POWER",206,406,13,P_L,Paint.Align.LEFT);seg7(c,ghost(3),330,392,16,P_GG,0,false);mono(c,"PS",336,406,13,P_L,Paint.Align.LEFT);
        mono(c,"RPM x1000",440,126,13,P_L,Paint.Align.LEFT);
        float max=rpmMax();
        for(int i=0;i<48;i++){float x=podBarX(i),h=podBarH(i);fill(c,x,246-h,x+4.6f,246,zone((i+1)*max/48,P_GG,P_AG,P_RG));}
        for(int k=0;k*1000<=max;k++)mono(c,String.valueOf(k),podBarX(0)+k*1000/max*48*6.8f,264,13,k*1000>prof.redline?0xffa0352a:P_L,Paint.Align.CENTER);
        mono(c,"GEAR",444,290,13,P_L,Paint.Align.LEFT);seg7(c,"8",486,298,84,P_AG,0,false);
        mono(c,"RPM",760,356,13,P_L,Paint.Align.RIGHT);seg7(c,ghost(4),760,298,46,P_GG,0,false);
        mono(c,"BOOST",540,384,13,P_L,Paint.Align.LEFT);for(int i=0;i<16;i++)fill(c,600+i*10,372,608+i*10,386,i>=13?P_AG:P_GG);
    }
    private void pods(Canvas c){
        float v=v(7),rpm=v(0),max=rpmMax();
        turns(c,24,776,82,14,P_G,false);
        boolean[] w=warn();for(int i=0;i<w.length;i++)lamp(c,60+i*138,68,178+i*138,96,WARN[i],w[i],i==1?P_R:P_A);
        seg7(c,num(v,3),358,128,128,P_G,0,false);
        int n=lit(v,0,240,40);for(int i=0;i<n;i++)fill(c,44+i*8,298,50+i*8,328,i>=30?P_A:P_G);
        n=lit(v(1),40,150,12);for(int i=0;i<n;i++)fill(c,92+i*8,366,98+i*8,380,i>=10?P_R:P_G);
        n=lit(v(2),40,130,12);for(int i=0;i<n;i++)fill(c,254+i*8,366,260+i*8,380,i>=9?P_R:P_G);
        float bv=v(Telemetry.BAT_VOLTAGE);seg7(c,Float.isNaN(bv)?"--.-":right(oneDec(Math.min(99.9f,bv)),4),142,392,16,battLow()?P_R:P_G,0,false);
        seg7(c,power(),330,392,16,P_G,0,false);
        for(int i=0;i<48;i++){float r=(i+1)*max/48;if(Float.isNaN(rpm)||rpm<r-max/96)break;float x=podBarX(i),h=podBarH(i);fill(c,x,246-h,x+4.6f,246,zone(r,P_G,P_A,P_R));}
        gearSeg(c,486,298,84,P_A);
        seg7(c,num(Float.isNaN(rpm)?rpm:Math.round(rpm/10f)*10,4),760,298,46,P_G,0,false);
        n=lit(v(Telemetry.BOOST),-0.7f,boostTop(),16);for(int i=0;i<n;i++)fill(c,600+i*10,372,608+i*10,386,i>=13?P_A:P_G);
    }

    // =============================================================================================================
    // 1 그린 아크: two segmented arcs (speed, tach) with digits inside, four small arcs in the corners.
    private static final int A_C=0xff3cf6c9,A_CG=0xff0a221c,A_L=0xff1f8a72,A_Y=0xffffd84a,A_R=0xffff4433,A_RG=0xff2c0b08,A_O=0xffff8c28,A_OG=0xff2c1a08;
    private static final float A_SX=252,A_TX=548,A_CY=278;
    private static float arcDeg(int i,int n){return -125+(i+0.5f)*250f/n;}
    /** Corner gauge g (0 volts, 1 temp, 2 oil, 3 boost): centre, side, scale. */
    private static final float[] CG_X={66,66,734,734},CG_Y={160,346,160,346};
    private static final String[] CG_NAME={"VOLTS","TEMP","OIL","BOOST"},CG_LO={"L","C","C","-"},CG_HI={"H","H","H","+"};
    private static float cgDeg(int g,int i){float a=(i+0.5f)*130f/14;return g<2?205+a:155-a;}
    /** Corner segment i of 14 is a warning one (low / high volts, hot water / oil, the top of the boost range). */
    private static boolean cgWarn(int g,int i){return g==0?i<2||i>=13:i>=11;}
    private float cgValue(int g){return g==0?v(Telemetry.BAT_VOLTAGE):g==1?v(2):g==2?v(1):v(Telemetry.BOOST);}
    private float cgLow(int g){return g==0?10:g==3?-0.7f:40;}
    private float cgHigh(int g){return g==0?16:g==1?130:g==2?150:boostTop();}
    private void arcs0(Canvas c){
        fill(c,0,52,800,480,0xff010605);
        float max=rpmMax();
        for(int i=0;i<48;i++)radial(c,A_SX,A_CY,84,106,arcDeg(i,48),5.5f,A_CG);
        for(int k=0;k<=240;k+=40){float d=-125+k/240f*250;mono(c,String.valueOf(k),px(A_SX,122,d),py(A_CY,122,d)+5,15,A_L,Paint.Align.CENTER);}
        seg7(c,ghost(3),A_SX+44,A_CY-40,54,A_CG,0,false);mono(c,"km/h",A_SX,A_CY+40,14,A_L,Paint.Align.CENTER);
        for(int i=0;i<40;i++)radial(c,A_TX,A_CY,84,106,arcDeg(i,40),6.5f,zone((i+1)*max/40,A_CG,A_OG,A_RG));
        for(int k=0;k*1000<=max;k++){float d=-125+k*1000/max*250;mono(c,String.valueOf(k),px(A_TX,122,d),py(A_CY,122,d)+5,15,k*1000>prof.redline?0xffa83a2c:A_L,Paint.Align.CENTER);}
        seg7(c,ghost(4),A_TX+44,A_CY-46,30,A_CG,0,false);mono(c,"RPM",A_TX,A_CY,13,A_L,Paint.Align.CENTER);
        seg7(c,ghost(3),A_TX+22,A_CY+14,18,A_CG,0,false);mono(c,"PS",A_TX,A_CY+50,12,A_L,Paint.Align.CENTER);
        seg7(c,"8",414,A_CY+40,56,A_OG,0,false);mono(c,"GEAR",400,A_CY+118,12,A_L,Paint.Align.CENTER);
        for(int g=0;g<4;g++){
            for(int i=0;i<14;i++)radial(c,CG_X[g],CG_Y[g],36,54,cgDeg(g,i),4.4f,cgWarn(g,i)?A_RG:A_CG);
            float tx=g<2?CG_X[g]+16:CG_X[g]-16;Paint.Align al=g<2?Paint.Align.LEFT:Paint.Align.RIGHT;
            mono(c,CG_NAME[g],tx,CG_Y[g]-8,12,A_L,al);
            mono(c,CG_LO[g],px(CG_X[g],66,cgDeg(g,0)),py(CG_Y[g],66,cgDeg(g,0))+5,13,A_L,Paint.Align.CENTER);
            mono(c,CG_HI[g],px(CG_X[g],66,cgDeg(g,13)),py(CG_Y[g],66,cgDeg(g,13))+5,13,A_L,Paint.Align.CENTER);
            seg7(c,g==0||g==3?"88.8":ghost(3),g<2?tx+50:tx,CG_Y[g]+2,18,A_CG,0,false);
        }
        turns(c,150,650,86,13,A_CG,true);
        for(int i=0;i<WARN.length;i++)bold(c,WARN[i],232+i*84,92,13,i==1?A_RG:A_OG,Paint.Align.CENTER);
    }
    private void arcs(Canvas c){
        float v=v(7),rpm=v(0),max=rpmMax();
        int n=lit(v,0,240,48);
        glowStart(A_SX,A_CY,84,106);for(int i=0;i<n;i++)glowSeg(c,arcDeg(i,48),5.5f,A_C);glowEnd(c);
        for(int i=0;i<n;i++)ringLit(c,A_SX,A_CY,84,106,arcDeg(i,48),5.5f,A_C);
        seg7(c,num(v,3),A_SX+44,A_CY-40,54,A_C,0,false);
        int nt=0;while(nt<40&&!Float.isNaN(rpm)&&rpm>=(nt+1)*max/40-max/80)nt++;
        glowStart(A_TX,A_CY,84,106);for(int i=0;i<nt;i++)glowSeg(c,arcDeg(i,40),6.5f,zone((i+1)*max/40,A_C,A_O,A_R));glowEnd(c);
        for(int i=0;i<nt;i++)ringLit(c,A_TX,A_CY,84,106,arcDeg(i,40),6.5f,zone((i+1)*max/40,A_C,A_O,A_R));
        seg7(c,num(Float.isNaN(rpm)?rpm:Math.round(rpm/10f)*10,4),A_TX+44,A_CY-46,30,A_C,0,false);
        seg7(c,power(),A_TX+22,A_CY+14,18,A_C,0,false);
        gearSeg(c,414,A_CY+40,56,A_O);
        for(int g=0;g<4;g++){
            float x=cgValue(g);int k=lit(x,cgLow(g),cgHigh(g),14);
            glowStart(CG_X[g],CG_Y[g],36,54);for(int i=0;i<k;i++)glowSeg(c,cgDeg(g,i),4.4f,cgWarn(g,i)?A_R:A_C);glowEnd(c);
            for(int i=0;i<k;i++)ringLit(c,CG_X[g],CG_Y[g],36,54,cgDeg(g,i),4.4f,cgWarn(g,i)?A_R:A_C);
            float tx=g<2?CG_X[g]+16:CG_X[g]-16;
            seg7(c,g==0||g==3?(Float.isNaN(x)?"--.-":right(oneDec(clamp(x,-9.9f,99.9f)),4)):num(x,3),g<2?tx+50:tx,CG_Y[g]+2,18,A_C,0,false);
        }
        turns(c,150,650,86,13,A_C,false);
        boolean[] w=warn();for(int i=0;i<w.length;i++)if(w[i])bold(c,WARN[i],232+i*84,92,13,i==1?A_R:A_O,Paint.Align.CENTER);
    }

    // =============================================================================================================
    // 2 곡선 타코: a bar tach rising on a curve across the glass, speed / temp / gear boxes above, wedge columns at the sides.
    private static final int Z_G=0xff5effb4,Z_GG=0xff0b221a,Z_L=0xff238f63,Z_O=0xffff9b2e,Z_OG=0xff2b1c08,Z_R=0xffff3d30,Z_RG=0xff2c0b08,Z_B=0xff4aa3ff;
    private static final String[] Z_LAMPS={"SHIFT","ABS","TCS OFF","ESC OFF","TPMS","BATT"};
    private static float zx(int i){return 150+i*7.8f;}
    private static float zh(int i){return 8+170*(float)Math.pow(i/63f,1.7);}
    private void curve0(Canvas c){
        fill(c,0,52,800,480,0xff020504);
        round(c,14,60,786,428,14,0xff030806);outline(c,14,60,786,428,14,1.5f,0xff12302a);
        float max=rpmMax();
        for(int i=0;i<64;i++)fill(c,zx(i),388-zh(i),zx(i)+5.2f,388,zone((i+1)*max/64,Z_GG,Z_OG,Z_RG));
        for(int k=0;k*1000<=max;k++)mono(c,String.valueOf(k),zx(0)+2.6f+k*1000/max*64*7.8f,410,14,k*1000>prof.redline?0xffa83a2c:Z_L,Paint.Align.CENTER);
        mono(c,"RPM x1000",150,330,13,Z_L,Paint.Align.LEFT);
        outline(c,300,70,500,170,6,1.5f,0xff12302a);seg7(c,ghost(3),470,80,72,Z_GG,0,false);mono(c,"km/h",490,164,12,Z_L,Paint.Align.RIGHT);
        outline(c,198,70,292,170,6,1.5f,0xff12302a);mono(c,"TEMP",206,88,12,Z_L,Paint.Align.LEFT);seg7(c,ghost(3),282,104,40,Z_GG,0,false);mono(c,"°C",282,164,12,Z_L,Paint.Align.RIGHT);
        outline(c,508,70,602,170,6,1.5f,0xff12302a);mono(c,"GEAR",516,88,12,Z_L,Paint.Align.LEFT);seg7(c,"8",572,96,64,Z_OG,0,false);
        for(int i=0;i<6;i++){float l=i<3?30:614,t=70+(i%3)*34;lampGlass(c,l,t,l+156,t+28,Z_LAMPS[i],i==1||i==5?Z_R:Z_O);}
        turns(c,330,470,206,12,Z_GG,true);
        mono(c,"THROTTLE",34,200,13,Z_L,Paint.Align.LEFT);mono(c,"BOOST",766,200,13,Z_L,Paint.Align.RIGHT);
        for(int i=0;i<12;i++){float y=388-(i+1)*14.5f,w=28+i*6.5f;fill(c,34,y,34+w,y+10,i>=10?Z_OG:Z_GG);fill(c,766-w,y,766,y+10,i>=10?Z_OG:Z_GG);}
    }
    private void curve(Canvas c){
        float v=v(7),rpm=v(0),max=rpmMax();
        for(int i=0;i<64;i++){float r=(i+1)*max/64;if(Float.isNaN(rpm)||rpm<r-max/128)break;fill(c,zx(i),388-zh(i),zx(i)+5.2f,388,zone(r,Z_G,Z_O,Z_R));}
        seg7(c,num(v,3),470,80,72,Z_G,0,false);
        seg7(c,num(v(2),3),282,104,40,Z_G,0,false);
        gearSeg(c,572,96,64,Z_O);
        boolean[] w=warn();
        for(int i=0;i<6;i++){float l=i<3?30:614,t=70+(i%3)*34;lamp(c,l,t,l+156,t+28,Z_LAMPS[i],i<5?w[i]:battLow(),i==1||i==5?Z_R:Z_O);}
        turns(c,330,470,206,12,Z_G,false);
        int nf=lit(v(9),0,100,12),nb=lit(v(Telemetry.BOOST),-0.7f,boostTop(),12);
        for(int i=0;i<12;i++){float y=388-(i+1)*14.5f,w2=28+i*6.5f;if(i<nf)fill(c,34,y,34+w2,y+10,i>=10?Z_O:Z_G);if(i<nb)fill(c,766-w2,y,766,y+10,i>=10?Z_O:Z_G);}
    }

    // =============================================================================================================
    // 3 LED 매트릭스: orange dot-matrix digits in a black window, rows of keys either side whose lamps light.
    private static final int L_O=0xffff7b1c,L_OG=0xff241106,L_R=0xffff3a2a,L_RG=0xff2a0a07,L_KT=0xff77716a,L_OL=0xff8a4414;
    private static final String[] L_LEFT={"TCS","ESC","ABS","TPMS","WATER","OIL","BATT","SHIFT","BRAKE","LAUNCH"};
    private static final String[] L_RIGHT={"OIL","WATER","AIR","VOLT","SOC","BOOST","OUT","PS","THR","BRK"};
    private static float lkx(boolean right,int i){return (right?606:20)+(i%2)*90;}
    private static float lky(int i){return 66+(i/2)*56;}
    private void key0(Canvas c,float l,float t,String label){
        keyGlass(c,l,t,l+84,t+50);mono(c,label,l+42,t+20,11,L_KT,Paint.Align.CENTER);
    }
    private void led0(Canvas c){
        fill(c,0,52,800,480,0xff0c0b0a);
        round(c,206,62,594,340,10,0xff2a2622);round(c,212,68,588,334,8,0xff040303);
        dm(c,"   ",236,84,13,10,L_OG,true);dm(c,"KM/H",470,152,4,3,L_OL,false);
        dm(c,"    ",236,198,6,4.6f,L_OG,true);dm(c,"RPM",384,226,3.4f,2.6f,L_OL,false);
        dm(c," ",500,190,9,7,L_OG,true);dm(c,"GEAR",498,258,3.4f,2.6f,L_OL,false);
        float max=rpmMax();for(int i=0;i<44;i++)fill(c,236+i*8,284,242+i*8,300,(i+1)*max/44>prof.redline?L_RG:L_OG);
        dm(c,"     ",236,312,2.6f,2,L_OG,true);
        turns(c,478,578,104,12,L_OG,true);
        for(int i=0;i<10;i++){key0(c,lkx(false,i),lky(i),L_LEFT[i]);round(c,lkx(false,i)+30,lky(i)+31,lkx(false,i)+54,lky(i)+36,2.5f,L_RG);}
        for(int i=0;i<10;i++)key0(c,lkx(true,i),lky(i),L_RIGHT[i]);
        round(c,206,350,594,466,10,0xff2a2622);round(c,212,356,588,460,8,0xff040303);
        dm(c,"           ",230,370,5,4,L_OG,true);dm(c,"           ",230,416,5,4,L_OG,true);
        for(int i=0;i<2;i++){float l=i==0?20:606;keyGlass(c,l,350,l+174,466);mono(c,i==0?"LAT G":"LON G",l+87,380,11,L_KT,Paint.Align.CENTER);}
    }
    private void led(Canvas c){
        float v=v(7),rpm=v(0),max=rpmMax();
        dm(c,num(v,3),236,84,13,10,L_O,false);
        dm(c,num(Float.isNaN(rpm)?rpm:Math.round(rpm/10f)*10,4),236,198,6,4.6f,L_O,false);
        String g=gear();if(g.length()>1&&manualGate(g))g=g.substring(1);dm(c,g.length()==1?g:"-",500,190,9,7,L_O,false);
        for(int i=0;i<44;i++){float r=(i+1)*max/44;if(Float.isNaN(rpm)||rpm<r-max/88)break;fill(c,236+i*8,284,242+i*8,300,r>prof.redline?L_R:L_O);}
        dm(c,clock(),236,312,2.6f,2,L_O,false);
        turns(c,478,578,104,12,L_O,false);
        float vv=v(7),th=v(9);   // LAUNCH: standing (under 3 km/h) with the throttle past half
        boolean[] w={on(Telemetry.TCS),on(Telemetry.ESC),on(Telemetry.ABS),tpmsLow(),waterHot(),oilHot(),battLow(),shift(),braking(),!Float.isNaN(vv)&&vv<3&&!Float.isNaN(th)&&th>50};
        for(int i=0;i<10;i++)if(w[i]){float l=lkx(false,i),t=lky(i);round(c,l+30,t+31,l+54,t+36,2.5f,i==0||i==1||i==7||i==9?L_O:L_R);glowing=false;mono(c,L_LEFT[i],l+42,t+20,11,0xffe8ddd0,Paint.Align.CENTER);glowing=true;}
        float[] val={v(1),v(2),v(Telemetry.INTAKE),v(Telemetry.BAT_VOLTAGE),v(Telemetry.BAT_SOC),v(Telemetry.BOOST),v(Telemetry.AMBIENT),v(Telemetry.POWER),v(9),v(10)};
        for(int i=0;i<10;i++){
            float x=val[i];String s=i==3||i==5?oneDec(x):Float.isNaN(x)?"--":Integer.toString(Math.round(x));
            mono(c,s,lkx(true,i)+42,lky(i)+41,16,L_O,Paint.Align.CENTER);
        }
        float tq=v(Telemetry.TORQUE);
        dm(c,right("TQ "+(Float.isNaN(tq)?"---":Integer.toString(Math.round(tq)))+" NM",11),230,370,5,4,L_O,false);
        String alarm=on(Telemetry.ABS)?"ABS":tpmsLow()?"TPMS LOW":waterHot()?"WATER HOT":oilHot()?"OIL HOT":battLow()?"BATT LOW":null;
        dm(c,right(alarm!=null?alarm:"BOOST "+oneDec(v(Telemetry.BOOST)),11),230,416,5,4,alarm!=null&&blink(now,1000)?L_R:L_O,false);
        float lat=v(11),lon=v(12);
        mono(c,Float.isNaN(lat)?"-.--":Telemetry.format("%+.2f",lat),107,436,28,L_O,Paint.Align.CENTER);
        mono(c,Float.isNaN(lon)?"-.--":Telemetry.format("%+.2f",lon),693,436,28,L_O,Paint.Align.CENTER);
    }

    // =============================================================================================================
    // 4 레인보우: a concept pod: rainbow rpm columns, two wire hills (speed, throttle), colour tiles and level columns.
    private static final int W_G=0xff48ff98,W_GG=0xff0d2a1d,W_Y=0xffffe047,W_R=0xffff4b3b,W_PK=0xffff5fb4,W_BL=0xff49a6ff,W_OR=0xffff9a2e,W_PANEL=0xff0a1514,W_L=0xff4f8a7c;
    private static final String[] W_TILES={"TCS OFF","ESC OFF","ABS","TPMS","SHIFT","BRAKE"};
    private static final int[] W_TILE_COL={W_BL,W_BL,W_R,W_PK,W_OR,W_Y};
    /** Level columns: boost, water, oil, volts. */
    private static final String[] W_COLS={"B","W","O","V"};
    private static final int[] W_COL_COL={W_G,W_R,W_Y,W_BL};
    private static int dim(int col){return mix(col,0xff000000,0.17f);}
    private int rainbow(float r){return r>prof.redline?W_R:r>prof.redline-1500?W_Y:W_G;}
    private void hill(Canvas c,float cx,int k,int col){
        float w=28+k*8.5f,h=20+k*12.5f,base=398;
        path.reset();path.moveTo(cx-w,base);path.quadTo(cx,base-2*h,cx+w,base);
        android.graphics.Path m=memo(key(key(0x48,cx),k));p.setStyle(Paint.Style.STROKE);
        if(glowing){int rgb=col(col)&0xffffff;p.setStrokeWidth(9);p.setColor(rgb|HALO_OUT);c.drawPath(m,p);p.setStrokeWidth(5);p.setColor(rgb|HALO_IN);c.drawPath(m,p);}
        p.setStrokeWidth(2.2f);p.setColor(col(col));c.drawPath(m,p);p.setStyle(Paint.Style.FILL);
    }
    private void rainbow0(Canvas c){
        fill(c,0,52,800,480,0xff040808);
        round(c,150,62,650,418,16,W_PANEL);outline(c,150,62,650,418,16,2,0xff1d2f2c);
        float max=rpmMax();for(int i=0;i<20;i++)fill(c,172+i*23,76,189+i*23,140,dim(rainbow((i+1)*max/20)));
        seg7(c,ghost(3),446,154,58,dim(W_Y),0,false);mono(c,"km/h",452,210,12,W_L,Paint.Align.LEFT);
        outline(c,556,152,630,216,6,2,dim(W_PK));seg7(c,"8",606,160,46,dim(W_PK),0,false);
        outline(c,170,152,256,216,6,2,dim(W_BL));seg7(c,ghost(3),246,166,28,dim(W_BL),0,false);mono(c,"°C",178,208,11,W_L,Paint.Align.LEFT);
        for(int k=0;k<12;k++){hill(c,275,k,W_GG);hill(c,525,k,W_GG);}
        mono(c,"SPEED",275,412,11,W_L,Paint.Align.CENTER);mono(c,"THROTTLE",525,412,11,W_L,Paint.Align.CENTER);
        for(int i=0;i<6;i++){float t=70+i*58;lampGlass(c,24,t,136,t+50,W_TILES[i],W_TILE_COL[i]);}
        for(int j=0;j<4;j++){mono(c,W_COLS[j],682+j*27,88,13,W_L,Paint.Align.CENTER);for(int i=0;i<18;i++)fill(c,672+j*27,380-i*16-12,692+j*27,380-i*16,dim(W_COL_COL[j]));}
        turns(c,170,630,446,12,W_GG,true);
        mono(c,"POWER",350,452,12,W_L,Paint.Align.RIGHT);seg7(c,ghost(3),420,438,18,W_GG,0,false);mono(c,"PS",426,452,12,W_L,Paint.Align.LEFT);
    }
    private void rainbow(Canvas c){
        float v=v(7),rpm=v(0),max=rpmMax();
        for(int i=0;i<20;i++){float r=(i+1)*max/20;if(Float.isNaN(rpm)||rpm<r-max/40)break;fill(c,172+i*23,76,189+i*23,140,rainbow(r));}
        seg7(c,num(v,3),446,154,58,W_Y,0,false);
        gearSeg(c,606,160,46,W_PK);
        seg7(c,num(v(2),3),246,166,28,W_BL,0,false);
        int ns=lit(v,0,240,12),nt=lit(v(9),0,100,12);
        for(int k=0;k<ns;k++)hill(c,275,k,k>=10?W_Y:W_G);
        for(int k=0;k<nt;k++)hill(c,525,k,k>=10?W_PK:W_G);
        boolean[] w={on(Telemetry.TCS),on(Telemetry.ESC),on(Telemetry.ABS),tpmsLow(),shift(),braking()};
        for(int i=0;i<6;i++){float t=70+i*58;lamp(c,24,t,136,t+50,W_TILES[i],w[i],W_TILE_COL[i]);}
        int[] n={lit(v(Telemetry.BOOST),-0.7f,boostTop(),18),lit(v(2),40,130,18),lit(v(1),40,150,18),lit(v(Telemetry.BAT_VOLTAGE),10,16,18)};
        for(int j=0;j<4;j++)for(int i=0;i<n[j];i++)fill(c,672+j*27,380-i*16-12,692+j*27,380-i*16,W_COL_COL[j]);
        seg7(c,power(),420,438,18,W_G,0,false);
        turns(c,170,630,446,12,W_G,false);
    }

    // =============================================================================================================
    // 5 원근 도로: rpm as rungs of a road running to the horizon, speed and gear below it, warning keys on the right.
    private static final int R_G=0xff52ffa3,R_GG=0xff0c2519,R_L=0xff238a5c,R_A=0xffffb02e,R_AG=0xff2b1d07,R_R=0xffff3b30,R_RG=0xff2c0b08;
    private static final String[] R_KEYS={"STOP","ABS","TPMS","WATER","OIL","BATT"};
    private static final int R_N=22;
    private static final float R_VX=301,R_VY=98,R_BASE=322,R_HALF=236;
    private static float rz(int k){return 1+k*0.17f;}
    private static float ry(int k){return R_VY+(R_BASE-R_VY)/rz(k);}
    private void rung(Canvas c,int k,int col){float z=rz(k),y=ry(k),hw=R_HALF/z*0.96f,th=Math.max(2,13/z);fill(c,R_VX-hw,y-th,R_VX+hw,y,col);}
    private void road0(Canvas c){
        fill(c,0,52,800,480,0xff010302);
        round(c,36,62,566,420,14,0xff030806);outline(c,36,62,566,420,14,2,0xff1a3a2a);
        line(c,R_VX-R_HALF,R_BASE+2,R_VX-R_HALF/rz(R_N),ry(R_N),2,R_L);line(c,R_VX+R_HALF,R_BASE+2,R_VX+R_HALF/rz(R_N),ry(R_N),2,R_L);
        float max=rpmMax();
        for(int k=0;k<R_N;k++)rung(c,k,zone((k+1)*max/R_N,R_GG,R_AG,R_RG));
        for(int t=1;t*1000<max;t++){int k=Math.round(t*1000/max*R_N)-1;if(k<0||k>=R_N)continue;float z=rz(k);mono(c,String.valueOf(t),R_VX-R_HALF/z-12,ry(k)-1,Math.max(9,15/z*1.4f),t*1000>prof.redline?0xffa83a2c:R_L,Paint.Align.RIGHT);}
        seg7(c,ghost(3),346,340,58,R_GG,0,false);mono(c,"km/h",354,396,13,R_L,Paint.Align.LEFT);
        mono(c,"RPM",60,354,12,R_L,Paint.Align.LEFT);seg7(c,ghost(4),60,362,24,R_GG,0,true);
        mono(c,"GEAR",470,354,12,R_L,Paint.Align.LEFT);seg7(c,"8",528,346,58,R_AG,0,false);
        turns(c,56,546,82,11,R_GG,true);
        for(int i=0;i<6;i++){float t=66+i*60;keyGlass(c,590,t,774,t+52);round(c,608,t+21,632,t+31,3,R_RG);bold(c,R_KEYS[i],694,t+32,15,0xff55585c,Paint.Align.CENTER);}
        mono(c,"OIL",40,450,12,R_L,Paint.Align.LEFT);mono(c,"TEMP",312,450,12,R_L,Paint.Align.LEFT);
        for(int i=0;i<20;i++){fill(c,84+i*10,436,92+i*10,452,i>=17?R_RG:R_GG);fill(c,358+i*10,436,366+i*10,452,i>=16?R_RG:R_GG);}
    }
    private void road(Canvas c){
        float v=v(7),rpm=v(0),max=rpmMax(),cool=v(2),oil=v(1);
        for(int k=0;k<R_N;k++){float r=(k+1)*max/R_N;if(Float.isNaN(rpm)||rpm<r-max/(2*R_N))break;rung(c,k,zone(r,R_G,R_A,R_R));}
        seg7(c,num(v,3),346,340,58,R_G,0,false);
        seg7(c,num(Float.isNaN(rpm)?rpm:Math.round(rpm/10f)*10,4),60,362,24,R_G,0,true);
        gearSeg(c,528,346,58,R_A);
        turns(c,56,546,82,11,R_G,false);
        // STOP: something to stop for: water past 115 °C, oil past 140 °C, ABS warning
        boolean stop=!Float.isNaN(cool)&&cool>=115||!Float.isNaN(oil)&&oil>=140||on(Telemetry.ABS);
        boolean[] w={stop,on(Telemetry.ABS),tpmsLow(),waterHot(),oilHot(),battLow()};
        for(int i=0;i<6;i++)if(w[i]){float t=66+i*60;boolean g=glowing;glowing=false;round(c,592,t+2,772,t+50,6,0xff241010);fill(c,600,t+2.5f,764,t+3.5f,0xff4a2522);glowing=g;
            round(c,608,t+21,632,t+31,3,R_R);glowing=false;bold(c,R_KEYS[i],694,t+32,15,0xffffe2de,Paint.Align.CENTER);glowing=g;}
        int nf=lit(oil,40,150,20),nt=lit(cool,40,130,20);
        for(int i=0;i<20;i++){if(i<nf)fill(c,84+i*10,436,92+i*10,452,i>=17?R_R:R_G);if(i<nt)fill(c,358+i*10,436,366+i*10,452,i>=16?R_R:R_G);}
    }

    // =============================================================================================================
    // 6 앰버 트리플: three amber panels: stepped level bars, big speed digits, an rpm curve climbing to the right.
    private static final int T_A=0xffffa31f,T_AG=0xff2b1a06,T_Y=0xffffe146,T_YG=0xff2a2509,T_R=0xffff4523,T_RG=0xff2d0c06,T_L=0xff8a5a1a,T_FRAME=0xff2c2721,T_PANEL=0xff080605;
    private static final String[] T_ROWS={"AIR","WATER","OIL","VOLT"};
    private static final float[] T_LO={-10,40,40,10},T_HI={80,130,150,16};
    private static boolean tWarn(int row,int i){return row==3?i==0||i==9:i>=8;}
    private static final int T_N=36;
    private static float tcx(int i){return 568+i*5.6f;}
    private static float tcy(int i){return 372-250*(float)Math.pow(i/(float)(T_N-1),1.6);}
    private void tSeg(Canvas c,int i,int col){if(i==0)fill(c,tcx(0)-3,tcy(0)-3,tcx(0)+3,tcy(0)+3,col);else{float x0=tcx(i-1),y0=tcy(i-1),x1=tcx(i),y1=tcy(i);line(c,x0+(x1-x0)*0.22f,y0+(y1-y0)*0.22f,x1,y1,7,col);}}
    private void amber0(Canvas c){
        fill(c,0,52,800,480,0xff050403);
        float[][] pn={{22,252},{264,536},{548,778}};
        for(float[] q:pn){round(c,q[0],88,q[1],394,10,T_PANEL);outline(c,q[0],88,q[1],394,10,2,T_FRAME);}
        for(int r=0;r<4;r++){
            float t=98+r*72;mono(c,T_ROWS[r],34,t+14,12,T_L,Paint.Align.LEFT);
            for(int i=0;i<10;i++){float x=34+i*15,h=8+i*3.6f;fill(c,x,t+62-h,x+11,t+62,tWarn(r,i)?T_RG:T_AG);}
            seg7(c,r==3?"88.8":ghost(3),242,t+30,22,T_AG,0,false);
        }
        seg7(c,ghost(3),503,112,112,T_AG,0,false);mono(c,"km/h",400,256,16,T_L,Paint.Align.CENTER);
        mono(c,"GEAR",300,290,12,T_L,Paint.Align.LEFT);seg7(c,"8",332,296,62,T_YG,0,false);
        mono(c,"POWER  PS",520,296,12,T_L,Paint.Align.RIGHT);seg7(c,ghost(3),520,302,20,T_AG,0,false);
        mono(c,"BOOST",520,344,12,T_L,Paint.Align.RIGHT);seg7(c,"88.8",520,350,20,T_AG,0,false);
        float max=rpmMax();
        for(int i=0;i<T_N;i++)tSeg(c,i,zone((i+1)*max/T_N,T_YG,T_AG,T_RG));
        for(int k=1;k*1000<=max;k++){int i=Math.round(k*1000/max*T_N)-1;if(i<0)continue;mono(c,String.valueOf(k),tcx(i)-12,tcy(i)-6,12,k*1000>prof.redline?0xffa83a2c:T_L,Paint.Align.RIGHT);}
        mono(c,"RPM",766,330,12,T_L,Paint.Align.RIGHT);seg7(c,ghost(4),766,340,28,T_AG,0,false);
        turns(c,30,770,72,11,T_AG,true);
        for(int i=0;i<WARN.length;i++)bold(c,WARN[i],220+i*90,78,13,i==1?T_RG:T_AG,Paint.Align.CENTER);
        mono(c,"TIME",264,420,12,T_L,Paint.Align.LEFT);seg7(c,"88:88",536,406,24,T_AG,0,false);
    }
    private void amber(Canvas c){
        float v=v(7),rpm=v(0),max=rpmMax();
        float[] val={v(Telemetry.INTAKE),v(2),v(1),v(Telemetry.BAT_VOLTAGE)};
        for(int r=0;r<4;r++){
            float t=98+r*72;int n=lit(val[r],T_LO[r],T_HI[r],10);
            for(int i=0;i<n;i++){float x=34+i*15,h=8+i*3.6f;fill(c,x,t+62-h,x+11,t+62,tWarn(r,i)?T_R:T_A);}
            seg7(c,r==3?(Float.isNaN(val[r])?"--.-":right(oneDec(Math.min(99.9f,val[r])),4)):num(val[r],3),242,t+30,22,T_A,0,false);
        }
        seg7(c,num(v,3),503,112,112,T_A,0,false);
        gearSeg(c,332,296,62,T_Y);
        seg7(c,power(),520,302,20,T_A,0,false);
        float b=v(Telemetry.BOOST);seg7(c,Float.isNaN(b)?"--.-":right(oneDec(clamp(b,-9.9f,9.9f)),4),520,350,20,T_A,0,false);
        for(int i=0;i<T_N;i++){float r=(i+1)*max/T_N;if(Float.isNaN(rpm)||rpm<r-max/(2*T_N))break;tSeg(c,i,zone(r,T_Y,T_A,T_R));}
        seg7(c,num(Float.isNaN(rpm)?rpm:Math.round(rpm/10f)*10,4),766,340,28,T_A,0,false);
        turns(c,30,770,72,11,T_A,false);
        boolean[] w=warn();for(int i=0;i<w.length;i++)if(w[i])bold(c,WARN[i],220+i*90,78,13,i==1?T_R:T_A,Paint.Align.CENTER);
        seg7(c,clock(),536,406,24,T_A,0,false);
    }

    // =============================================================================================================
    // 7 트윈 서클: two round gauges with yellow-green segment rings and digital centres, level columns between them.
    private static final int C_Y=0xffd9ff3e,C_YG=0xff1d230a,C_G=0xff47ff86,C_GG=0xff0c2617,C_L=0xff6f9a2a,C_R=0xffff4433,C_RG=0xff2c0b08,C_BEZEL=0xff3d1416,C_IN=0xff050605,C_B=0xff4aa3ff;
    private static final float C_LX=205,C_RX=595,C_CY=282;
    private static final int C_N=60;
    private static float cDeg(int i){return -135+(i+0.5f)*270f/C_N;}
    private void circles0(Canvas c){
        fill(c,0,52,800,480,0xff060303);
        for(float x:new float[]{C_LX,C_RX}){dot(c,x,C_CY,160,C_BEZEL);dot(c,x,C_CY,152,C_IN);ring(c,x,C_CY,152,1.5f,0xff5a2226);}
        float max=rpmMax();
        for(int i=0;i<C_N;i++){radial(c,C_LX,C_CY,120,142,cDeg(i),6,C_YG);radial(c,C_RX,C_CY,120,142,cDeg(i),6,zone((i+1)*max/C_N,C_YG,C_YG,C_RG));}
        for(int k=0;k<=240;k+=20){float d=-135+k/240f*270;mono(c,String.valueOf(k),px(C_LX,102,d),py(C_CY,102,d)+5,k%40==0?14:11,C_G,Paint.Align.CENTER);}
        for(int k=0;k*1000<=max;k++){float d=-135+k*1000/max*270;mono(c,String.valueOf(k),px(C_RX,102,d),py(C_CY,102,d)+5,14,k*1000>prof.redline?C_R:C_G,Paint.Align.CENTER);}
        seg7(c,ghost(3),C_LX+46,C_CY-22,50,C_GG,0,false);mono(c,"km/h",C_LX,C_CY+52,13,C_L,Paint.Align.CENTER);
        mono(c,"-",C_LX-56,C_CY+92,12,C_L,Paint.Align.RIGHT);mono(c,"+",C_LX+56,C_CY+92,12,C_L,Paint.Align.LEFT);
        for(int i=0;i<10;i++)fill(c,C_LX-50+i*10,C_CY+80,C_LX-42+i*10,C_CY+94,i>=8?C_RG:C_YG);
        mono(c,"BOOST",C_LX,C_CY+116,11,C_L,Paint.Align.CENTER);
        seg7(c,ghost(4),C_RX+52,C_CY-46,32,C_GG,0,false);mono(c,"RPM",C_RX,C_CY+2,12,C_L,Paint.Align.CENTER);
        seg7(c,"8",C_RX+12,C_CY+16,50,C_YG,0,false);mono(c,"GEAR",C_RX,C_CY+86,11,C_L,Paint.Align.CENTER);
        mono(c,"TEMP",383,130,10,C_L,Paint.Align.CENTER);mono(c,"OIL",417,130,10,C_L,Paint.Align.CENTER);
        for(int i=0;i<16;i++){fill(c,374,404-i*16-11,392,404-i*16,i>=13?C_RG:C_GG);fill(c,408,404-i*16-11,426,404-i*16,i>=13?C_RG:C_GG);}
        turns(c,24,776,78,12,C_GG,true);
        for(int i=0;i<WARN.length;i++)bold(c,WARN[i],220+i*90,82,13,C_RG,Paint.Align.CENTER);
        mono(c,"POWER",372,462,11,C_L,Paint.Align.RIGHT);seg7(c,ghost(3),420,446,16,C_GG,0,false);mono(c,"PS",426,462,11,C_L,Paint.Align.LEFT);
    }
    private void circles(Canvas c){
        float v=v(7),rpm=v(0),max=rpmMax();
        int n=lit(v,0,240,C_N);
        glowStart(C_LX,C_CY,120,142);for(int i=0;i<n;i++)glowSeg(c,cDeg(i),6,C_Y);glowEnd(c);
        for(int i=0;i<n;i++)ringLit(c,C_LX,C_CY,120,142,cDeg(i),6,C_Y);
        int nr=0;while(nr<C_N&&!Float.isNaN(rpm)&&rpm>=(nr+1)*max/C_N-max/(2*C_N))nr++;
        glowStart(C_RX,C_CY,120,142);for(int i=0;i<nr;i++)glowSeg(c,cDeg(i),6,(i+1)*max/C_N>prof.redline?C_R:C_Y);glowEnd(c);
        for(int i=0;i<nr;i++)ringLit(c,C_RX,C_CY,120,142,cDeg(i),6,(i+1)*max/C_N>prof.redline?C_R:C_Y);
        seg7(c,num(v,3),C_LX+46,C_CY-22,50,C_G,0,false);
        n=lit(v(Telemetry.BOOST),-0.7f,boostTop(),10);for(int i=0;i<n;i++)fill(c,C_LX-50+i*10,C_CY+80,C_LX-42+i*10,C_CY+94,i>=8?C_R:C_Y);
        seg7(c,num(Float.isNaN(rpm)?rpm:Math.round(rpm/10f)*10,4),C_RX+52,C_CY-46,32,C_G,0,false);
        gearSeg(c,C_RX+12,C_CY+16,50,C_Y);
        int nt=lit(v(2),40,130,16),no=lit(v(1),40,150,16);
        for(int i=0;i<16;i++){if(i<nt)fill(c,374,404-i*16-11,392,404-i*16,i>=13?C_R:C_G);if(i<no)fill(c,408,404-i*16-11,426,404-i*16,i>=13?C_R:C_G);}
        turns(c,24,776,78,12,C_G,false);
        boolean[] w=warn();for(int i=0;i<w.length;i++)if(w[i])bold(c,WARN[i],220+i*90,82,13,i==1?C_R:C_Y,Paint.Align.CENTER);
        seg7(c,power(),420,446,16,C_G,0,false);
    }
}
