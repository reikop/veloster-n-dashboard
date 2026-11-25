package com.veloster.vehicle;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import java.util.Locale;

/**
 * Circuit: at a known circuit (Circuits, from OpenStreetMap) the track is a minimap, north up, with the car on it and
 * the start / finish line; laps count by themselves from the first crossing (CircuitLap). On the right: the lap clock,
 * the live gap to the best lap at the same point of the track (with a bar), best / last / predicted lap, speed, gear
 * and an rpm bar, top speed this lap and last, and coolant / DCT clutch temperatures. Every fix is taken in tick, so
 * timing goes on while another page is up. Laps live until 초기화 (two taps) or the app closes (not saved).
 * Which circuit: 자동 takes the one the car is at (Circuits.pick), else the nearest, so a driver on the way sees where
 * they are headed; a tap on the name opens the list (자동, then the circuits nearest first) to fix one. The choice is
 * not saved: the app starts on 자동.
 */
final class CircuitCluster extends ClusterKit {
    private static final int TX=0xffe6f1f6,MU=0xff91a5ae,LN=0xff1d2326,TRACK=0xff3a4246,ACC=0xffa4d9ef,PANEL=0xff101315,EDGE=0xff303638,
        GOOD=0xff2fe06a,BAD=0xffff4059,AMBER=0xffffb84d;
    private static final float ML=14,MT=60,MR=470,MB=470,L=486,R=786;
    final CircuitLap lap=new CircuitLap();
    private int seen=-1;
    /** The circuit the car is at (-1: none) and its track in the lap's metres. */
    private int circuit=-1;
    private float[] track;
    private TrackPath trackPath;
    /** The last fix's time on the fix clock and when it was taken, to run the lap clock between fixes. */
    private long fixT,fixNano;
    /** Top speed this lap and the last one (km/h), and the laps seen, to roll them over. */
    private float vmax=Float.NaN,lastVmax=Float.NaN;
    private int lapsSeen;
    /** 초기화 asks for a second tap within 3 s. */
    private long resetAsk;
    /** The circuit the driver fixed (-1: 자동), metres from the car to each track at the last fix, the list open. */
    private int chosen=-1;
    private final double[] dist=new double[Circuits.count()];
    private boolean listing;
    private static final float ROW=44,LIST_T=MT+58;
    { themed=true; }

    @Override protected void tick(){
        int q=GpsFix.seq;
        if(q!=seen&&GpsFix.any()){seen=q;feed(GpsFix.lat,GpsFix.lon,GpsFix.at);}
        float v=v(7);
        if(lap.state()==CircuitLap.TIMING&&!Float.isNaN(v))vmax=Float.isNaN(vmax)?v:Math.max(vmax,v);
    }
    void feed(double lat,double lon,long t){
        for(int i=0;i<dist.length;i++)dist[i]=Circuits.distance(i,lat,lon);
        int c=chosen;
        if(c<0){c=Circuits.pick(lat,lon,circuit);if(c<0)c=nearest();}
        if(c!=circuit){circuit=c;atCircuit();}
        lap.fix(lat,lon,t);fixT=t;fixNano=System.nanoTime()/1000000;
        if(lap.laps!=lapsSeen){
            if(lap.laps>lapsSeen&&lap.last>0&&circuit>=0)Records.add(Records.lapKey(Circuits.name(circuit)),lap.last/1000f);   // 기록실
            lapsSeen=lap.laps;lastVmax=vmax;vmax=Float.NaN;}
    }
    /** A new circuit (or none): the laps start over, on that circuit's line. */
    private void atCircuit(){
        lap.reset();track=null;trackPath=null;bmpFor=-1;vmax=lastVmax=Float.NaN;lapsSeen=0;
        if(circuit<0)return;
        double[] s=Circuits.start(circuit);
        track=Circuits.outline(circuit,s[0],s[1]);trackPath=new TrackPath(track);
        lap.track(s[0],s[1],trackPath,Circuits.directionKnown(circuit));
    }
    /**
     * Where to draw the car now: moved on from the last fix at the speed between the last two, for up to LEAD_MS, along
     * the track (it keeps to the corners); off a circuit in a straight line. A slower car shows a small step back when
     * the next fix comes.
     */
    private double[] dot(){
        double x=lap.x(),y=lap.y(),qx=lap.prevX(),qy=lap.prevY();long gap=lap.gapMs();
        long e=Math.min(LEAD_MS,System.nanoTime()/1000000-fixNano);
        if(Double.isNaN(qx)||gap<=0||gap>3000||e<=0)return new double[]{x,y};
        if(trackPath!=null){
            double s1=trackPath.along(x,y),v=trackPath.delta(trackPath.along(qx,qy),s1)/gap;
            if(Math.abs(v)*1000>100)return new double[]{x,y};   // a jump, not driving
            double[] on=trackPath.at(s1),a=trackPath.at(s1+v*e);   // keep the fix's offset from the track line
            return new double[]{a[0]+x-on[0],a[1]+y-on[1]};
        }
        return new double[]{x+(x-qx)*e/gap,y+(y-qy)*e/gap};
    }
    private static final long LEAD_MS=1200;
    private int nearest(){int b=0;for(int i=1;i<dist.length;i++)if(dist[i]<dist[b])b=i;return b;}
    /** The circuits nearest first (list order before a fix). */
    private int[] order(){
        Integer[] o=new Integer[dist.length];for(int i=0;i<o.length;i++)o[i]=i;
        if(GpsFix.any())java.util.Arrays.sort(o,new java.util.Comparator<Integer>(){public int compare(Integer a,Integer b){return Double.compare(dist[a],dist[b]);}});
        int[] r=new int[o.length];for(int i=0;i<r.length;i++)r[i]=o[i];return r;
    }
    private String km(double m){return m<1000?String.format(Locale.US,"%.0f m",m):String.format(Locale.US,"%.0f km",m/1000);}
    private long current(){return lap.state()==CircuitLap.TIMING?fixT+(System.nanoTime()/1000000-fixNano)-lap.startAt:-1;}

    @Override protected void draw(Canvas c){
        fill(c,0,52,800,480,0xff040506);
        round(c,ML,MT,MR,MB,10,PANEL);outline(c,ML,MT,MR,MB,10,1.5f,EDGE);
        // the name is the button for the list
        String name=circuit>=0?t(Circuits.name(circuit)):t("서킷 선택");
        bold(c,name,ML+14,MT+24,15,TX,Paint.Align.LEFT);
        float tx=ML+14+width(name,15,Typeface.DEFAULT_BOLD)+10;   // a drawn ▾: the head unit's font has no such glyph
        path.reset();path.moveTo(tx,MT+14);path.lineTo(tx+10,MT+14);path.lineTo(tx+5,MT+20);path.close();poly(c,TX);
        if(circuit>=0)text(c,String.format(Locale.US,"%.2f km",Circuits.lengthM(circuit)/1000f),MR-14,MT+24,13,MU,Paint.Align.RIGHT,Typeface.DEFAULT);
        String sub=chosen<0?t("자동 · 가장 가까운 서킷"):t("직접 선택");
        if(circuit>=0&&Circuits.lineEstimated(circuit))sub+=" · "+t("출발선 위치는 추정");
        text(c,sub,ML+14,MT+44,12,MU,Paint.Align.LEFT,Typeface.DEFAULT);
        if(listing)list(c);
        else if(circuit>=0){
            map(c);
            if(GpsFix.any()&&dist[circuit]>1000)hint(c,String.format(Locale.US,t("서킷까지 %s"),km(dist[circuit])),MB-16);
            else if(lap.state()==CircuitLap.WAIT)hint(c,GpsFix.any()?t("출발선을 지나면 1랩이 시작됩니다"):t("GPS 신호를 기다리는 중"),MB-16);
        }else hint(c,t("GPS 신호를 기다리는 중"),(MT+MB)/2);
        side(c);
    }
    /** The right column: lap clock, live gap, laps, speed / gear / rpm, top speeds, temperatures, 초기화. */
    private void side(Canvas c){
        boolean timing=lap.state()==CircuitLap.TIMING;
        bold(c,timing?"LAP "+(lap.laps+1):"LAP —",L,84,16,ACC,Paint.Align.LEFT);
        String gps=!GpsFix.any()?t("GPS 없음"):!GpsFix.fresh()?t("GPS 끊김"):Float.isNaN(GpsFix.accuracy)?"GPS":String.format(Locale.US,"GPS ±%.0f m",GpsFix.accuracy);
        text(c,gps,R,84,12,GpsFix.fresh()?MU:BAD,Paint.Align.RIGHT,Typeface.DEFAULT);
        bold(c,LapTimer.format(current()),L,136,44,timing?TX:MU,Paint.Align.LEFT);
        // live gap to the best lap: the number, and a bar from the middle (a full side = 2 s), green ahead / red behind
        long g=timing?lap.gap:Long.MIN_VALUE;boolean has=g!=Long.MIN_VALUE;int gc=!has?MU:g<=0?GOOD:BAD;
        round(c,L,150,R,196,6,0xff0b0d0e);
        bold(c,has?LapTimer.formatDelta(g):"±—",L+12,184,26,gc,Paint.Align.LEFT);
        float bx0=L+132,bx1=R-12,mid=(bx0+bx1)/2,half=(bx1-bx0)/2;
        fill(c,bx0,171,bx1,175,LN);fill(c,mid-1,162,mid+1,184,MU);
        if(has){float f=clamp(g/2000f,-1,1)*half;fill(c,Math.min(mid,mid+f),165,Math.max(mid,mid+f),181,gc);}
        text(c,t("최고 랩 대비"),bx0,160,10,MU,Paint.Align.LEFT,Typeface.DEFAULT);
        String[] k={"BEST","LAST",t("예상")};
        long pred=has&&lap.best>0?lap.best+g:-1;
        String[] val={LapTimer.format(lap.best),LapTimer.format(lap.last),LapTimer.format(pred)};
        for(int i=0;i<3;i++){float y=224+i*28;
            text(c,k[i],L,y,12,MU,Paint.Align.LEFT,Typeface.DEFAULT_BOLD);
            bold(c,val[i],R,y,20,i==0?ACC:TX,Paint.Align.RIGHT);}
        line(c,L,290,R,290,1,LN);
        // speed and gear, then rpm against the redline
        float v=v(7),rpm=v(0);
        bold(c,fmt("%.0f",v),L,340,40,TX,Paint.Align.LEFT);
        text(c,"km/h",L+86,340,12,MU,Paint.Align.LEFT,Typeface.DEFAULT);
        bold(c,gear(),R,340,38,ACC,Paint.Align.RIGHT);
        float top=prof.maxRpm,red=prof.redline,w=R-L;
        fill(c,L,352,R,362,LN);fill(c,L+w*red/top,352,R,362,0xff3a1a1e);
        if(!Float.isNaN(rpm))fill(c,L,352,L+w*clamp(rpm/top,0,1),362,rpm>=red?BAD:ACC);
        // top speed, this lap and the last
        text(c,t("최고 속도"),L,392,12,MU,Paint.Align.LEFT,Typeface.DEFAULT);
        bold(c,fmt("%.0f",vmax)+" / "+fmt("%.0f",lastVmax)+" km/h",R,392,15,TX,Paint.Align.RIGHT);
        text(c,t("이번 랩 / 지난 랩"),L,408,10,MU,Paint.Align.LEFT,Typeface.DEFAULT);
        // temperatures: coolant, and the DCT clutches where there are any
        float cool=v(2),odd=v(Telemetry.CLUTCH_ODD),even=v(Telemetry.CLUTCH_EVEN);
        int cl=Tiles.level(2,cool);
        text(c,t("냉각수"),L,436,12,MU,Paint.Align.LEFT,Typeface.DEFAULT);
        bold(c,fmt("%.0f",cool)+"°",L+56,436,15,cl==2?BAD:cl==1?AMBER:TX,Paint.Align.LEFT);
        if(!Float.isNaN(odd)||!Float.isNaN(even)){int lv=Math.max(Telemetry.clutchLevel(odd),Telemetry.clutchLevel(even));
            text(c,t("클러치"),L+112,436,12,MU,Paint.Align.LEFT,Typeface.DEFAULT);
            bold(c,fmt("%.0f",odd)+" / "+fmt("%.0f",even)+"°",L+160,436,15,lv==2?BAD:lv==1?AMBER:TX,Paint.Align.LEFT);}
        boolean ask=now-resetAsk<3000&&resetAsk>0;
        round(c,R-104,448,R,472,6,ask?0xff3a1a1e:0xff1a1d20);
        bold(c,ask?t("한 번 더 누르면 초기화"):t("초기화"),R-52,465,ask?10:12,ask?BAD:MU,Paint.Align.CENTER);
    }
    /** 자동, then every circuit nearest first with its distance; the one in use is marked. */
    private void list(Canvas c){
        int[] o=order();
        for(int r=0;r<=o.length;r++){
            float y=LIST_T+r*ROW;boolean on=r==0?chosen<0:chosen==o[r-1];
            round(c,ML+10,y,MR-10,y+ROW-6,6,on?0xff1d2a30:0xff15181a);
            if(on)fill(c,ML+10,y,ML+14,y+ROW-6,ACC);
            String a=r==0?t("자동 · 가장 가까운 서킷"):t(Circuits.name(o[r-1]));
            bold(c,a,ML+24,y+27,15,on?TX:MU,Paint.Align.LEFT);
            if(r>0&&GpsFix.any())text(c,km(dist[o[r-1]]),MR-22,y+27,13,MU,Paint.Align.RIGHT,Typeface.DEFAULT);
        }
    }
    private void hint(Canvas c,String s,float y){
        float size=15;while(size>11&&width(s,size,Typeface.DEFAULT_BOLD)>MR-ML-24)size--;
        bold(c,s,(ML+MR)/2,y,size,MU,Paint.Align.CENTER);
    }
    /** The circuit's track and line (a stamped bitmap), the lap so far over it, and the car. */
    private void map(Canvas c){
        if(trackBmp==null||bmpFor!=circuit||bmpTheme!=Theme.current())drawTrack();
        blit(c,trackBmp,0,0,(int)(MR-ML),(int)(MB-MT),ML,MT,MR,MB);
        float[] b=lap.curPath();int bn=lap.curPoints();
        for(int i=1;i<bn;i++)line(c,sx(b[i*2-2]),sy(b[i*2-1]),sx(b[i*2]),sy(b[i*2+1]),3,ACC);
        double[] d=Double.isNaN(lap.x())||dist[circuit]>1000?null:dot();   // far off (on the way there): no dot
        if(d==null)return;
        if(bn>0)line(c,sx(b[bn*2-2]),sy(b[bn*2-1]),sx(d[0]),sy(d[1]),3,ACC);   // the lap's line runs on to the dot
        float px=clamp(sx(d[0]),ML+10,MR-10),py=clamp(sy(d[1]),MT+34,MB-30);   // off the map (paddock, the road in): pinned to its edge
        dot(c,px,py,10,0xff040506);dot(c,px,py,7,BAD);ring(c,px,py,10,2,TX);
    }
    /** The track and its start / finish line, drawn once per circuit and theme into trackBmp: the outline alone is a few
     *  hundred strokes (KIC about 550), too many to repeat every frame on the head unit. */
    private Bitmap trackBmp;
    private int bmpFor=-1;
    private Theme bmpTheme;
    /** The circuit's fixed view: its centre in metres, pixels per metre, and where the centre sits on screen. */
    private double vx,vy;
    private float vk,vox,voy;
    private float sx(double x){return vox+(float)(x-vx)*vk;}
    private float sy(double y){return voy-(float)(y-vy)*vk;}
    @Override void trim(){trackBmp=recycle(trackBmp);}
    private void drawTrack(){
        float[] a=track;int an=a.length/2;
        double x0=Double.MAX_VALUE,x1=-Double.MAX_VALUE,y0=Double.MAX_VALUE,y1=-Double.MAX_VALUE;
        for(int i=0;i<an;i++){x0=Math.min(x0,a[i*2]);x1=Math.max(x1,a[i*2]);y0=Math.min(y0,a[i*2+1]);y1=Math.max(y1,a[i*2+1]);}
        vx=(x0+x1)/2;vy=(y0+y1)/2;float w=MR-ML-48,h=MB-MT-96;
        vk=(float)Math.min(Math.min(w/Math.max(1,x1-x0),h/Math.max(1,y1-y0)),Math.min(w,h)/300);vox=(ML+MR)/2;voy=(MT+36+MB-36)/2;
        trackBmp=bitmap((int)(MR-ML),(int)(MB-MT));Canvas bc=new Canvas(trackBmp);bc.translate(-ML,-MT);   // screen coordinates
        for(int i=0;i<an;i++){   // the track: thick, joints rounded with a dot
            int j=(i+1)%an;line(bc,sx(a[i*2]),sy(a[i*2+1]),sx(a[j*2]),sy(a[j*2+1]),10,TRACK);dot(bc,sx(a[i*2]),sy(a[i*2+1]),5,TRACK);
        }
        // the line across the track, 30 m each side (at least 14 px), chequered; either crossing direction draws the same
        float lx=sx(lap.lineX()),ly=sy(lap.lineY()),half=Math.max(14,(float)CircuitLap.HALF_M*vk),nx=(float)-lap.dirY(),ny=(float)lap.dirX();
        line(bc,lx-nx*half,ly+ny*half,lx+nx*half,ly-ny*half,6,0xfff2f2f4);
        for(int s=-2;s<2;s+=2){float f0=s/2f,f1=(s+1)/2f;line(bc,lx+nx*half*f0,ly-ny*half*f0,lx+nx*half*f1,ly-ny*half*f1,6,0xff111111);}
        bmpFor=circuit;bmpTheme=Theme.current();
    }
    @Override boolean tap(float x,float y){
        if(x>=ML&&x<=MR&&y>=MT&&y<MT+48){listing=!listing;return true;}   // the name: open / close the list
        if(listing){
            listing=false;
            int r=(int)((y-LIST_T)/ROW);int[] o=order();
            if(x<ML||x>MR||y<LIST_T||r>o.length)return true;   // outside the rows: just close
            chosen=r==0?-1:o[r-1];
            int c=chosen>=0?chosen:circuit;
            if(chosen<0&&GpsFix.any()){c=Circuits.pick(GpsFix.lat,GpsFix.lon,-1);if(c<0)c=nearest();}
            if(c!=circuit||chosen>=0){circuit=c;atCircuit();seen=GpsFix.seq-1;}
            return true;
        }
        if(x<R-104||y<448)return false;
        if(now-resetAsk<3000&&resetAsk>0){resetAsk=0;atCircuit();seen=GpsFix.seq-1;}   // the next tick takes the last fix again
        else resetAsk=now;
        return true;
    }
}
