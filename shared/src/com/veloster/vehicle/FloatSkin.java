package com.veloster.vehicle;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

/**
 * 플로팅 창 스킨: the floating window's cells drawn the way a dashboard layout draws its readings (레이스, F1, 레트로,
 * 80년대, HUD, 8비트, 카세트 VU, 야간). Same cell grid as the plain window (124 x 64 at 보통, times k), so every shape,
 * count and size works; the round gauge keeps its own look. Reuses ClusterKit's seven-segment digits and PixelCluster's
 * 5 x 7 font. Each skin has its own bar, so a cell's graph choice applies to the plain window only (숫자만 still hides it).
 */
final class FloatSkin extends ClusterKit {
    static final int PLAIN=0,RACE=1,F1=2,RETRO=3,VFD=4,HUD=5,PIXEL=6,VU=7,NIGHT=8,CARD=9,WHITE=10,OEM=11;
    static final String[] NAMES={"기본","레이스","F1","레트로","80년대","HUD","8비트","카세트","야간","카드","화이트","순정"};
    private static final FloatSkin K=new FloatSkin();
    /** The skin that goes with a dashboard screen (DashboardPage id), PLAIN for screens without one of their own. */
    static int forScreen(int s){return s==DashboardPage.F1?F1:PLAIN;}
    protected void draw(Canvas c){}

    /** One cell's content, filled by FloatPanel before each cell (main thread only). */
    /** sn: the name a skin shows: the app's language (Korean in Korean); en: always English, for the 8-bit font (no Hangul). */
    static final class Cell{String name,en,sn,val,unit,pk;float f,zero,pf;int lv;boolean first,bar;String[] quad;final String[] q4=new String[4];final int[] l4=new int[4];final String[] t4=new String[4];}
    static final Cell CELL=new Cell();

    private static Typeface face(){return Fonts.MEDIUM!=null?Fonts.MEDIUM:Typeface.DEFAULT;}
    private static Typeface faceBold(){return Fonts.BOLD!=null?Fonts.BOLD:Typeface.DEFAULT_BOLD;}
    /** The theme's accent (the N palette's 5bc8f5) and light accent (a4d9ef): the race and night skins follow the theme like their screens. */
    private static int themeAcc(){return Theme.current().map(0xff5bc8f5);}
    private static int themeBox(){return Theme.current().map(0xffa4d9ef);}
    private static int a(int argb,float af){int al=Math.max(0,Math.min(255,Math.round((argb>>>24)*af)));return al<<24|argb&0xffffff;}

    /** The window body behind the cells, w x h; af scales its opacity (투명도: 보통 = 1). */
    static void body(Canvas c,int skin,float w,float h,float k,float af){K.bodyImpl(c,skin,w,h,k,af);}
    static void cell(Canvas c,int skin,float x,float y,float cw,float ch,float k){K.cellImpl(c,skin,x,y,cw,ch,k,CELL);}

    private void bodyImpl(Canvas c,int skin,float w,float h,float k,float af){
        switch(skin){
            case RACE:fill(c,0,0,w,h,a(0xeb0e1316,af));break;
            case F1:round(c,0,0,w,h,6*k,a(0xed080a0c,af));break;
            case RETRO:fill(c,0,0,w,h,a(0xf0060402,af));break;
            case VFD:round(c,0,0,w,h,4*k,a(0xf0020a09,af));break;
            case PIXEL:{fill(c,0,0,w,h,a(0xf0000000,af));int o=Math.max(1,Math.round(k));   // the 8-bit dialog box: a thick and a thin white border
                frame(c,2*o,2*o,w-2*o,h-2*o,2*o,0xffffffff);frame(c,6*o,6*o,w-6*o,h-6*o,o,0xffffffff);break;}
            case VU:round(c,0,0,w,h,5*k,a(0xf2141210,af));break;
            case NIGHT:round(c,0,0,w,h,10*k,a(0xcc000000,af));break;
            case CARD:round(c,0,0,w,h,14*k,a(0xf01c1c1e,af));break;
            case WHITE:round(c,0,0,w,h,14*k,a(0xf4f5f5f7,af));break;
            case OEM:fill(c,0,0,w,h,a(0xf0000000,af));box(c,.5f,.5f,w-.5f,h-.5f,1,0xff3c4044);break;
            default:break;   // HUD: no body, the lines carry a shadow instead
        }
    }
    private void frame(Canvas c,float l,float t,float r2,float b,float th,int col){fill(c,l,t,r2,t+th,col);fill(c,l,b-th,r2,b,col);fill(c,l,t,l+th,b,col);fill(c,r2-th,t,r2,b,col);}
    private void box(Canvas c,float l,float t,float r2,float b,float sw,int col){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(sw);p.setColor(col(col));c.drawRect(l,t,r2,b,p);p.setStyle(Paint.Style.FILL);}
    /** Text at up to size px, shrunk to fit max px wide. */
    private void fit(Canvas c,String s,float x,float y,float size,float max,int col,Paint.Align al,Typeface tf){
        float w=width(s,size,tf);if(w>max&&w>0)size*=max/w;text(c,s,x,y,size,col,al,tf);}
    private static int warn(int lv,int normal){return lv==2?0xffff4b3c:lv==1?0xffffaa3c:normal;}
    /** The white body is the one light look: its warnings are darker so they read on it. */
    static boolean light(int skin){return skin==WHITE;}
    static int warn(int lv,int normal,boolean light){return light?(lv==2?0xffd92d20:lv==1?0xffc76a00:normal):warn(lv,normal);}
    private static float clamp01(float f){return Math.max(0,Math.min(1,f));}
    /** Segment i of n is lit: it lies between the bar's start z (0, or .5 for a reading centred on 0) and the reading f. */
    static boolean lit(int i,int n,float f,float z,boolean has){if(!has)return false;int a=Math.round(Math.min(z,f)*n),b=Math.round(Math.max(z,f)*n);return i>=a&&i<b;}
    /** Partial redraw (FloatPanel.PASS): st = kept as a bitmap (frames, names, scales, fixed units), dy = drawn each frame. */
    private static boolean st(){return FloatPanel.PASS!=FloatPanel.DYN;}
    private static boolean dy(){return FloatPanel.PASS!=FloatPanel.STAT;}

    private void cellImpl(Canvas c,int skin,float x,float y,float cw,float ch,float k,Cell d){
        if(d.quad!=null){String v=d.val,u=d.unit;d.val="";d.unit="";d.bar=false;cellBody(c,skin,x,y,cw,ch,k,d);d.val=v;d.unit=u;if(dy())quadIn(c,skin,x,y,cw,ch,k,d);return;}
        cellBody(c,skin,x,y,cw,ch,k,d);
    }
    /** A 4-wheel cell: the skin draws its frame and name, then the wheels go in its value ink and type. */
    private void quadIn(Canvas c,int skin,float x,float y,float cw,float ch,float k,Cell d){
        int ink;Typeface tf=faceBold();
        switch(skin){case RACE:ink=d.first?0xff061015:0xfff2f6f8;break;case RETRO:ink=0xffffb347;tf=Typeface.MONOSPACE;break;case VFD:ink=0xff5ef2d2;break;
            case HUD:ink=0xff62ff8a;tf=Typeface.MONOSPACE;break;case PIXEL:ink=0xffffffff;break;case VU:ink=0xff3a3226;break;case NIGHT:ink=0xffc5ccd0;tf=Typeface.DEFAULT;break;case CARD:case OEM:ink=0xffffffff;tf=Typeface.DEFAULT;break;case WHITE:ink=0xff1c1c1e;tf=Typeface.DEFAULT;break;default:ink=0xffffffff;}
        if(skin!=PIXEL){float t=y+(skin==VU?10:22)*k,b=y+ch-(skin==VU?12:3)*k;   // a car seen from above, the skin's ink (the 8-bit keeps its pixel numbers)
            FloatPanel.tyres(c,x+4*k,t,cw-8*k,b-t,k,d.quad,d.l4,d.t4,ink,(ink&0xffffff)|0x66000000,tf);return;}
        float cx=x+cw/2,t=y+(skin==VU?10:24)*k,b=y+ch-(skin==VU?14:6)*k,s=Math.min(15*k,(b-t)/2.1f);
        line(c,cx,t,cx,b,Math.max(1,k),(ink&0xffffff)|0x55000000);
        for(int w=0;w<4;w++){int col=d.l4[w]==2?0xffff4b3c:d.l4[w]==1?0xffffaa3c:ink;float yy=w<2?t+s*.95f:b-2*k;String q=d.quad[w];
            if(skin==PIXEL){int sc=Math.max(1,Math.round(s/7f));float wd=(q.length()*6-1)*sc;pixel(c,q,w%2==0?cx-8*k-wd:cx+8*k,yy-7*sc,sc,col);}
            else text(c,q,w%2==0?cx-8*k:cx+8*k,yy,s,col,w%2==0?Paint.Align.RIGHT:Paint.Align.LEFT,tf);}
    }
    private void cellBody(Canvas c,int skin,float x,float y,float cw,float ch,float k,Cell d){
        boolean has=!Float.isNaN(d.f)&&d.bar;float f=has?clamp01(d.f):0;boolean pk=d.pk!=null&&!Float.isNaN(d.pf);float pf=pk?clamp01(d.pf):0;
        switch(skin){
        case RACE:{   // dark boxes, small caps, big white number; the first cell in the race screen's light-blue gear box
            boolean hot=d.first;
            if(st()){if(hot)fill(c,x+3*k,y+3*k,x+cw-3*k,y+ch-3*k,themeBox());else box(c,x+3.5f*k,y+3.5f*k,x+cw-3.5f*k,y+ch-3.5f*k,1,0xff2a353c);}
            int ink=hot?0xff061015:warn(d.lv,0xfff2f6f8),mut=hot?0xb3061015:0xff8fa0a8;
            if(st())fit(c,d.sn,x+10*k,y+15*k,8.5f*k,cw*(pk?.5f:.8f),mut,Paint.Align.LEFT,faceBold());
            if(dy()&&pk)text(c,"MAX "+d.pk,x+cw-10*k,y+15*k,8*k,mut,Paint.Align.RIGHT,face());
            float uw=width(d.unit,9*k,face());if(st())text(c,d.unit,x+cw-10*k,y+46*k,9*k,mut,Paint.Align.RIGHT,face());
            if(dy())fit(c,d.val,x+cw-13*k-uw,y+46*k,25*k,cw-26*k-uw,ink,Paint.Align.RIGHT,faceBold());
            if(has){float bl=x+10*k,br=x+cw-10*k,bt=y+53*k;if(st())fill(c,bl,bt,br,bt+3*k,hot?0x2e061015:0xff26323a);if(!dy())break;
                float z=d.zero,lo=bl+(br-bl)*Math.min(z,f),hi=bl+(br-bl)*Math.max(z,f);fill(c,lo,bt,Math.max(hi,lo+2),bt+3*k,hot?0xff061015:warn(d.lv,themeAcc()));
                if(pk){float px=bl+(br-bl)*pf;fill(c,px-k,bt-2*k,px+k,bt+5*k,hot?0xff061015:0xffffffff);}}
            break;}
        case F1:{   // the F1 screen's shift lights as the cell's bar (green, red, blue), then the number large
            if(st()&&x>.5f)fill(c,x,y+8*k,x+1,y+ch-6*k,0xff2a343b);
            if(dy()&&d.bar){int n=8;for(int i=0;i<n;i++){boolean on=lit(i,n,f,d.zero,has);int col=i<3?0xff2fe06a:i<6?0xffff3b3b:0xff4aa8ff;
                float cx=x+12*k+i*(cw-24*k)/(n-1);if(on)dot(c,cx,y+10*k,5.5f*k,col&0x44ffffff);dot(c,cx,y+10*k,3.2f*k,on?col:0xff1d2428);}}
            if(st())fit(c,d.sn,x+10*k,y+27*k,8*k,cw*(pk?.5f:.8f),0xff8fa0a8,Paint.Align.LEFT,faceBold());
            if(dy()&&pk)text(c,"▲"+d.pk,x+cw-10*k,y+27*k,8*k,0xffff6b5b,Paint.Align.RIGHT,face());
            float uw=width(d.unit,9*k,face());if(st())text(c,d.unit,x+cw-10*k,y+ch-9*k,9*k,0xff8fa0a8,Paint.Align.RIGHT,face());
            if(dy())fit(c,d.val,x+cw-13*k-uw,y+ch-9*k,24*k,cw-26*k-uw,warn(d.lv,0xffffffff),Paint.Align.RIGHT,faceBold());
            break;}
        case RETRO:{   // orange-ruled boxes with a header band, amber fixed-width digits, a vertical tick bar
            if(st()){box(c,x+3.5f*k,y+3.5f*k,x+cw-3.5f*k,y+ch-3.5f*k,1,0xffb4500f);fill(c,x+3*k,y+3*k,x+cw-3*k,y+14*k,0xffb4500f);
                fit(c,d.sn,x+8*k,y+12*k,8*k,cw*(pk?.5f:.8f),0xff140800,Paint.Align.LEFT,Typeface.MONOSPACE);}
            if(dy()&&pk)text(c,"MAX "+d.pk,x+cw-8*k,y+12*k,8*k,0xff140800,Paint.Align.RIGHT,Typeface.MONOSPACE);
            if(st())text(c,d.unit,x+cw-28*k,y+48*k,9*k,0xffc46a1c,Paint.Align.LEFT,Typeface.MONOSPACE);
            if(!dy())break;
            fit(c,d.val,x+cw-30*k,y+48*k,26*k,cw-(d.bar?60:40)*k,warn(d.lv==1?0:d.lv,0xffffb347),Paint.Align.RIGHT,Typeface.MONOSPACE);
            if(d.bar){int segs=8;for(int s=0;s<segs;s++)fill(c,x+9*k,y+(56-s*5)*k,x+19*k,y+(59.4f-s*5)*k,lit(s,segs,f,d.zero,has)?(s>=6?0xffff5a1f:0xffff9a2e):0xff2a1606);}
            break;}
        case VFD:{   // a fluorescent display: seven-segment digits over their unlit ghost, a stepped bar
            int on=warn(d.lv==1?0:d.lv,0xff5ef2d2),ghost=0x145ef2d2,dim=0xb35ef2d2;
            if(st()){box(c,x+3.5f*k,y+3.5f*k,x+cw-3.5f*k,y+ch-3.5f*k,1,0x385ef2d2);
                fit(c,d.sn,x+9*k,y+14*k,8*k,cw*.6f,dim,Paint.Align.LEFT,faceBold());text(c,d.unit,x+cw-9*k,y+14*k,8*k,dim,Paint.Align.RIGHT,faceBold());}
            if(!dy())break;
            String s=d.val.startsWith("+")?d.val.substring(1):d.val;if(s.equals("—"))s="--";
            seg7(c,s.replaceAll("[0-9]","8"),x+cw-9*k,y+19*k,24*k,ghost,0,false);float tw=seg7(c,s,x+cw-9*k,y+19*k,24*k,on,0,false);
            if(d.val.startsWith("+")){float px=x+cw-9*k-tw-11*k;fill(c,px,y+30*k,px+7*k,y+32.4f*k,on);fill(c,px+2.3f*k,y+27.7f*k,px+4.7f*k,y+34.7f*k,on);}
            if(d.bar){int n=12;for(int i=0;i<n;i++){float hh=(2+i*.7f)*k;fill(c,x+(9+i*8.6f)*k,y+58*k-hh,x+(15.4f+i*8.6f)*k,y+58*k,lit(i,n,f,d.zero,has)?(i>=10?0xffff4d6a:on):0x1a5ef2d2);}}
            break;}
        case HUD:{   // green lines only: corner brackets, the value in the HUD's arrow box, a tick scale with a caret
            for(int pass=0;pass<2;pass++){int g=pass==0?0xb4000000:warn(d.lv,0xff62ff8a);c.save();if(pass==0)c.translate(k,k);   // a dark copy one pixel down-right, then the green: readable on a bright map
            float L=8*k,x0=x+4*k,y0=y+4*k,x1=x+cw-4*k,y1=y+ch-4*k,sw=1.2f*k;
            if(dy()){line(c,x0,y0,x0+L,y0,sw,g);   /* corners each frame: the caret's dark copy reaches the bottom-left one */line(c,x0,y0,x0,y0+L,sw,g);line(c,x1-L,y0,x1,y0,sw,g);line(c,x1,y0,x1,y0+L,sw,g);
            line(c,x0,y1,x0+L,y1,sw,g);line(c,x0,y1-L,x0,y1,sw,g);line(c,x1-L,y1,x1,y1,sw,g);line(c,x1,y1-L,x1,y1,sw,g);
            }
            if(st())fit(c,d.sn,x+12*k,y+17*k,8*k,cw*.7f,g,Paint.Align.LEFT,Typeface.MONOSPACE);
            if(d.val.isEmpty()){c.restore();continue;}   // a 4-wheel cell: the wheels go where the box was
            float bx=x+40*k,by=y+22*k,bw=70*k,bh=24*k;if(dy()){path.reset();path.moveTo(bx,by);path.lineTo(bx+bw,by);path.lineTo(bx+bw,by+bh);path.lineTo(bx,by+bh);path.lineTo(bx-8*k,by+bh/2);path.close();
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(sw);p.setColor(g);c.drawPath(path,p);p.setStyle(Paint.Style.FILL);
            fit(c,d.val,bx+bw-6*k,by+19*k,19*k,bw-12*k,g,Paint.Align.RIGHT,Typeface.MONOSPACE);fit(c,d.unit,x+12*k,y+40*k,8*k,26*k,g,Paint.Align.LEFT,Typeface.MONOSPACE);}
            if(d.bar){float tl=x+12*k,tr=x+cw-12*k,ty=y+54*k;if(dy())for(int i=0;i<=10;i++){   /* the scale each frame too: the caret's dark copy can fall on its first tick */float tx=tl+(tr-tl)*i/10;line(c,tx,ty,tx,ty-(i%5==0?4:2)*k,sw,g);}
                if(dy()&&has){float px=tl+(tr-tl)*f;path.reset();path.moveTo(px,ty+k);path.lineTo(px-3*k,ty+6*k);path.lineTo(px+3*k,ty+6*k);path.close();p.setColor(g);c.drawPath(path,p);}
                if(dy()&&pk){float qx=tl+(tr-tl)*pf;path.reset();path.moveTo(qx,ty-6*k);path.lineTo(qx-2.5f*k,ty-10*k);path.lineTo(qx+2.5f*k,ty-10*k);path.close();p.setColor(g);c.drawPath(path,p);}}
            c.restore();}
            break;}
        case PIXEL:{   // the 8-bit screen's dialog: pixel text, an HP-block bar, the peak as a HI score
            int ls=Math.max(1,Math.round(k*1.2f)),vs=Math.max(2,Math.round(2*k));
            String hi=pk?"HI"+d.pk:"";float hiW=hi.isEmpty()?0:(hi.length()*6-1)*ls+6*k;
            if(st())pixel(c,clip(d.en,(cw-24*k-hiW)/(6*ls)),x+12*k,y+19*k-7*ls,ls,0xffffd84a);
            if(!dy())break;
            if(pk)pixel(c,hi,x+cw-12*k-(hi.length()*6-1)*ls,y+19*k-7*ls,ls,0xffff6aa0);
            pixel(c,clip(d.val.equals("—")?"--":d.val,(cw-20*k)/(6*vs)),x+12*k,y+26*k,vs,warn(d.lv,0xffffffff));
            if(d.bar){int n=9;for(int i=0;i<n;i++)fill(c,Math.round(x+(12+i*11)*k),Math.round(y+47*k),Math.round(x+(21+i*11)*k),Math.round(y+53*k),lit(i,n,f,d.zero,has)?(i>=7?0xffff3d3d:0xff3ddc5a):0xff2a2a2a);}
            break;}
        case VU:{   // the cassette screen's ivory VU meter: an arc, a red zone, a needle, the value at the bottom
            float fx=x+4*k,fy=y+4*k,fw=cw-8*k,fh=ch-8*k,cx=fx+fw/2,cy=fy+fh+16*k,R=fh+4*k;
            if(st()){round(c,fx,fy,fx+fw,fy+fh,3*k,0xffece0bc);fill(c,fx,fy+fh*.62f,fx+fw,fy+fh-3*k,0xffe4d3a6);
            r.set(cx-(R-8*k),cy-(R-8*k),cx+(R-8*k),cy+(R-8*k));
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(k);p.setColor(0xff3a3226);c.drawArc(r,225,90,false,p);
            r.set(cx-(R-9*k),cy-(R-9*k),cx+(R-9*k),cy+(R-9*k));p.setStrokeWidth(2.4f*k);p.setColor(0xffc8302a);c.drawArc(r,291.6f,23.4f,false,p);p.setStyle(Paint.Style.FILL);
            for(int i=0;i<=8;i++){double an=Math.toRadians(225+90.0*i/8);float r1=R-8*k,r2=R-(i%2==1?11:13)*k;
                line(c,cx+(float)Math.cos(an)*r1,cy+(float)Math.sin(an)*r1,cx+(float)Math.cos(an)*r2,cy+(float)Math.sin(an)*r2,k,i>=6?0xffc8302a:0xff3a3226);}
            text(c,"VU",cx,fy+fh-14*k,8*k,0xff3a3226,Paint.Align.CENTER,faceBold());}
            if(!dy())break;
            String v=d.val+(d.unit.isEmpty()?"":" "+d.unit);float vw=Math.min(width(v,8.5f*k,faceBold()),fw*.55f);
            fit(c,d.name,fx+5*k,fy+fh-4*k,8.5f*k,fw-vw-14*k,0xff3a3226,Paint.Align.LEFT,Typeface.DEFAULT_BOLD);
            fit(c,v,fx+fw-5*k,fy+fh-4*k,8.5f*k,fw*.55f,warn(d.lv,0xff3a3226),Paint.Align.RIGHT,faceBold());
            if(pk){double an=Math.toRadians(225+90*pf);dot(c,cx+(float)Math.cos(an)*(R-5*k),cy+(float)Math.sin(an)*(R-5*k),1.8f*k,0xffc8302a);}
            if(has){double an=Math.toRadians(225+90*f);c.save();c.clipRect(fx,fy,fx+fw,fy+fh);line(c,cx,cy,cx+(float)Math.cos(an)*(R-6*k),cy+(float)Math.sin(an)*(R-6*k),1.3f*k,0xff14110c);c.restore();}
            break;}
        case NIGHT:{   // the night screen: thin grey figures and one hairline
            if(st())fit(c,d.name,x+12*k,y+17*k,9*k,cw*(pk?.45f:.8f),0xff8a959a,Paint.Align.LEFT,Typeface.DEFAULT);
            if(dy()&&pk)text(c,com.veloster.i18n.UiText.t("최고")+" "+d.pk,x+cw-10*k,y+17*k,8.5f*k,0xff737d82,Paint.Align.RIGHT,Typeface.DEFAULT);
            if(st())text(c,d.unit,x+cw-27*k,y+48*k,9*k,0xff8a959a,Paint.Align.LEFT,Typeface.DEFAULT);
            if(dy())fit(c,d.val,x+cw-30*k,y+48*k,28*k,cw-44*k,warn(d.lv,0xffc5ccd0),Paint.Align.RIGHT,Typeface.DEFAULT);
            if(d.bar){if(st())fill(c,x+12*k,y+55*k,x+cw-12*k,y+55*k+1,0xff2b3134);if(dy()&&has)fill(c,x+12*k+(cw-24*k)*Math.min(d.zero,f),y+55*k,x+12*k+(cw-24*k)*Math.max(d.zero,f),y+55*k+1,themeAcc());}
            break;}
        case CARD:case WHITE:{   // a widget card: a small grey name, a large light figure with its unit after it, one rounded bar in the accent
            boolean wh=skin==WHITE;int ink=wh?0xff1c1c1e:0xffffffff,mut=wh?0xff6e6e73:0xff98989f,trk=wh?0xffdcdce0:0xff3a3a3c,acc=wh?0xff1677e5:themeAcc();
            int vcol=warn(d.lv,ink,wh);
            if(st()){fit(c,d.sn,x+12*k,y+19*k,9.5f*k,cw*(pk?.5f:.8f),mut,Paint.Align.LEFT,Typeface.DEFAULT);
                if(x>.5f)fill(c,x,y+12*k,x+1,y+ch-12*k,wh?0x16000000:0x1cffffff);if(y>.5f)fill(c,x+12*k,y,x+cw-12*k,y+1,wh?0x16000000:0x1cffffff);}
            if(dy()&&pk)text(c,"▲ "+d.pk,x+cw-12*k,y+19*k,8.5f*k,mut,Paint.Align.RIGHT,Typeface.DEFAULT);
            if(dy()){float vs=27*k,vw=width(d.val,vs,Typeface.DEFAULT),uw=width(d.unit,10*k,Typeface.DEFAULT),mx=cw-24*k-uw-3*k;if(vw>mx&&vw>0){vs*=mx/vw;vw=mx;}
                text(c,d.val,x+12*k,y+47*k,vs,vcol,Paint.Align.LEFT,Typeface.DEFAULT);text(c,d.unit,x+12*k+vw+3*k,y+47*k,10*k,mut,Paint.Align.LEFT,Typeface.DEFAULT);}
            if(d.bar){float bl=x+12*k,br=x+cw-12*k,bt=y+54*k;if(st())round(c,bl,bt,br,bt+3*k,1.5f*k,trk);
                if(dy()&&has){float lo=bl+(br-bl)*Math.min(d.zero,f),hi=bl+(br-bl)*Math.max(d.zero,f);round(c,lo,bt,Math.max(hi,lo+3*k),bt+3*k,1.5f*k,d.lv>0?vcol:acc);
                    if(pk){float px=bl+(br-bl)*pf;fill(c,px-.5f*k,bt-2*k,px+.5f*k,bt+5*k,mut);}}}
            break;}
        case OEM:{   // the head unit's own screens: black, hairline grey rules, white text, its glossy blue as the bar
            if(st()){if(x>.5f)fill(c,x,y+1,x+1,y+ch-1,0xff2c3034);if(y>.5f)fill(c,x+1,y,x+cw-1,y+1,0xff2c3034);
                fit(c,d.sn,x+10*k,y+18*k,10*k,cw*(pk?.5f:.8f),0xffe6e9eb,Paint.Align.LEFT,Typeface.DEFAULT);
                text(c,d.unit,x+cw-10*k,y+47*k,10*k,0xff8b9197,Paint.Align.RIGHT,Typeface.DEFAULT);}
            if(dy()&&pk)text(c,"▲ "+d.pk,x+cw-10*k,y+18*k,8.5f*k,0xff8b9197,Paint.Align.RIGHT,Typeface.DEFAULT);
            float uw=width(d.unit,10*k,Typeface.DEFAULT);
            if(dy())fit(c,d.val,x+cw-13*k-uw,y+47*k,26*k,cw-26*k-uw,warn(d.lv,0xffffffff),Paint.Align.RIGHT,Typeface.DEFAULT);
            if(d.bar){float bl=x+10*k,br=x+cw-10*k,bt=y+54*k;if(st())fill(c,bl,bt,br,bt+3*k,0xff23272b);
                if(dy()&&has){float lo=bl+(br-bl)*Math.min(d.zero,f),hi=Math.max(bl+(br-bl)*Math.max(d.zero,f),lo+2*k);fill(c,lo,bt,hi,bt+3*k,warn(d.lv,0xff2f7fe0));fill(c,lo,bt,hi,bt+k,d.lv>0?0x55ffffff:0xff9fd2ff);}}
            break;}
        default:break;
        }
    }
    private static String clip(String s,float chars){int n=Math.max(1,(int)chars);return s.length()<=n?s:s.substring(0,n);}
    /** PixelCluster's 5 x 7 font at a whole-pixel scale, top-left at x,y. */
    private void pixel(Canvas c,String s,float x,float y,int sc,int col){
        float x0=Math.round(x),y0=Math.round(y);p.setStyle(Paint.Style.FILL);p.setColor(col(col));
        for(int i=0;i<s.length();i++){int g=PixelGlyphs.glyph(s.charAt(i));if(g==0)continue;
            for(int rw=0;rw<7;rw++){int bits=PixelGlyphs.FONT_BITS[g*7+rw];
                for(int b=0;b<5;b++)if((bits&(16>>b))!=0){float px=x0+(i*6+b)*sc,py=y0+rw*sc;c.drawRect(px,py,px+sc,py+sc,p);}}}
    }
}
