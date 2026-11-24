import com.veloster.vehicle.DriveStats;

/** Synthetic sample streams for the session records: runs, peaks, cool-down, warm-up and the G trail. */
public final class DriveStatsTest {
    private static int checks;
    private static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
    /** One 80 ms hscan step: speed, rpm, boost, longitudinal G all timed at t (lateral 0, coolant 85 C). */
    private static void step(DriveStats d,long t,float speed,float rpm,float boost,float lon){
        d.update(t,speed,t,rpm,boost,0f,lon,t,85f);
    }
    /** One 80 ms step with lateral G and accelerator pedal % (rpm 3000, longitudinal 0, coolant 85 C). */
    private static void turn(DriveStats d,long t,float speed,float lat,float throttle,float boost){
        d.update(t,speed,t,3000,boost,lat,0f,t,85f,throttle);
    }
    public static void main(String[] args){
        // 0-100 in 7.96 s: standstill, then +1 km/h every 80 ms from t=1000 (crosses 0.5 at t=1040, 100 at t=9000).
        DriveStats d=new DriveStats();long t=0;
        for(;t<=1000;t+=80)step(d,t,0,800,0,0);
        float v=0;
        while(v<130){t+=80;v+=1;step(d,t,v,3000,0.3f,0.35f);}
        check(Math.abs(d.last[0]-7.96f)<0.05f,"0-100 time "+d.last[0]);
        check(Math.abs(d.last[1]-3.2f)<0.05f,"60-100 time "+d.last[1]);
        check(Math.abs(d.last[2]-3.2f)<0.05f,"80-120 time "+d.last[2]);
        check(d.best[0]==d.last[0],"first run is the best");
        check(Math.abs(d.maxSpeed-130)<0.01f&&Math.abs(d.maxRpm-3000)<0.01f&&Math.abs(d.maxBoost-0.3f)<0.01f,"peaks");
        // A rolling run aborted by backing off below the start speed records nothing.
        DriveStats r=new DriveStats();t=0;
        for(v=50;v<70;v+=1){t+=80;step(r,t,v,3000,0,0.2f);}
        for(;v>55;v-=1){t+=80;step(r,t,v,2000,0,-0.2f);}
        check(Float.isNaN(r.last[1])&&!r.running[1],"aborted 60-100 leaves no time");
        check(Math.abs(r.maxDecel-0.2f)<0.01f,"peak decel");
        // Cool-down: 40 s of hard use (boost 0.8) then a stop counts 45 s down; no hard use -> not needed.
        DriveStats s=new DriveStats();t=0;
        for(int k=0;k<500;k++){t+=80;step(s,t,80,5200,0.8f,0.3f);}
        check(s.coolDownLeft()==-1,"moving: no cool-down");
        for(v=80;v>0;v-=4){t+=80;step(s,t,Math.max(0,v-4),900,0,-0.3f);}
        long stop=t;for(int k=0;k<10;k++){t+=80;step(s,t,0,800,0,0);}
        check(Math.abs(s.coolDownLeft()-(45000-(t-stop)))<=80,"cool-down counts from the stop: "+s.coolDownLeft());
        t+=46000;step(s,t,0,800,0,0);
        check(s.coolDownLeft()==0,"cool-down done after 45 s");
        DriveStats calm=new DriveStats();t=0;
        for(int k=0;k<20;k++){t+=80;step(calm,t,30,1500,0,0);}
        for(int k=0;k<10;k++){t+=80;step(calm,t,0,800,0,0);}
        check(calm.coolDownLeft()==-1,"gentle drive needs no cool-down");
        // City bursts: four 2 s pulls at 0.9 bar in five minutes (8 s, under the 30 s needed), then a stop: not needed.
        DriveStats city=new DriveStats();t=0;
        for(int p=0;p<4;p++){for(int k=0;k<25;k++){t+=80;step(city,t,50,3000,0.9f,0.2f);}for(int k=0;k<800;k++){t+=80;step(city,t,40,1800,0,0);}}
        for(int k=0;k<10;k++){t+=80;step(city,t,0,800,0,0);}
        check(city.coolDownLeft()==-1,"short city boost bursts need no cool-down");
        // 0.6 bar is ordinary driving now, however long: not needed.
        DriveStats mild=new DriveStats();t=0;
        for(int k=0;k<1000;k++){t+=80;step(mild,t,90,3000,0.6f,0);}
        for(int k=0;k<10;k++){t+=80;step(mild,t,0,800,0,0);}
        check(mild.coolDownLeft()==-1,"0.6 bar cruising needs no cool-down");
        // Hard use older than five minutes before the stop does not count.
        DriveStats old=new DriveStats();t=0;
        for(int k=0;k<500;k++){t+=80;step(old,t,100,5000,1f,0);}
        for(int k=0;k<4000;k++){t+=80;step(old,t,60,2000,0,0);}
        for(int k=0;k<10;k++){t+=80;step(old,t,0,800,0,0);}
        check(old.coolDownLeft()==-1,"hard use 5+ minutes before the stop is forgotten");
        // Warm-up below 70 C coolant; G trail keeps the last 24 points, newest last.
        DriveStats w=new DriveStats();w.update(0,0,0,800,0,0.1f,0.2f,0,55f);
        check(w.warmUp,"55 C is warm-up");
        check(d.trailCount()==DriveStats.TRAIL&&Math.abs(d.trailY(DriveStats.TRAIL-1)-0.35f)<0.01f,"trail holds the newest point last");
        check(Math.abs(d.maxG-0.35f)<0.01f,"session peak G");
        // Corner: 0.4 g for 2 s while speed falls 60 -> 45 then rises to 55, then 1 s straight.
        DriveStats c=new DriveStats();t=0;
        for(int k=0;k<25;k++){t+=80;turn(c,t,k<12?60-k*15f/12:45+(k-12)*10f/12,0.4f,20,0);}
        check(Float.isNaN(c.cornerMinSpeed),"corner not sealed while still turning");
        for(int k=0;k<13;k++){t+=80;turn(c,t,55,0f,20,0);}
        check(Math.abs(c.cornerMinSpeed-45)<0.6f&&Math.abs(c.cornerMaxLat-0.4f)<0.01f,"corner sealed: min speed "+c.cornerMinSpeed+" peak lat "+c.cornerMaxLat);
        // A 0.6 s jolt of 0.5 g is not a corner.
        DriveStats j=new DriveStats();t=0;
        for(int k=0;k<8;k++){t+=80;turn(j,t,50,0.5f,20,0);}
        for(int k=0;k<13;k++){t+=80;turn(j,t,50,0f,20,0);}
        check(Float.isNaN(j.cornerMinSpeed),"short jolt is not a corner");
        // A 2 s lateral-G dropout mid-corner neither ends nor seals it: the corner continues at 0.6 g afterwards.
        DriveStats g=new DriveStats();t=0;
        for(int k=0;k<15;k++){t+=80;turn(g,t,50,0.4f,20,0);}
        for(int k=0;k<25;k++){t+=80;turn(g,t,50,Float.NaN,20,0);}
        for(int k=0;k<6;k++){t+=80;turn(g,t,48,0.6f,20,0);}
        for(int k=0;k<13;k++){t+=80;turn(g,t,50,0f,20,0);}
        check(Math.abs(g.cornerMaxLat-0.6f)<0.01f&&Math.abs(g.cornerMinSpeed-48)<0.01f,"NaN gap neither ends nor seals the corner early: "+g.cornerMaxLat);
        // Pull: pedal 90 % for 1.5 s with boost 0.2 -> 0.9 -> 0.7, then pedal 10 % for 0.5 s.
        DriveStats p=new DriveStats();t=0;
        for(int k=0;k<19;k++){t+=80;turn(p,t,80,0f,90,k<9?0.2f+k*0.7f/9:k==9?0.9f:0.7f);}
        check(Float.isNaN(p.pullMaxBoost),"pull not sealed while the pedal is down");
        for(int k=0;k<7;k++){t+=80;turn(p,t,80,0f,10,0.1f);}
        check(Math.abs(p.pullMaxBoost-0.9f)<0.01f,"pull peak boost sealed when the pedal lifts: "+p.pullMaxBoost);
        check(Float.isNaN(new DriveStats().pullMaxBoost)&&Float.isNaN(new DriveStats().cornerMaxLat),"nothing recorded yet");
        // A one-frame pedal blip after a real pull must not overwrite the pull's peak.
        DriveStats q=new DriveStats();t=0;
        for(int k=0;k<10;k++){t+=80;turn(q,t,80,0f,90,0.9f);}
        for(int k=0;k<7;k++){t+=80;turn(q,t,80,0f,10,0.1f);}
        t+=80;turn(q,t,80,0f,75,-0.3f);
        for(int k=0;k<7;k++){t+=80;turn(q,t,80,0f,10,0.1f);}
        check(Math.abs(q.pullMaxBoost-0.9f)<0.01f,"pedal blip keeps the last real pull: "+q.pullMaxBoost);
        // A long hole in the samples (screen hidden) is not corner time: 0.3 s + 120 s gap + 0.24 s is no corner.
        DriveStats h=new DriveStats();t=0;
        for(int k=0;k<4;k++){t+=80;turn(h,t,70,0.4f,20,0);}
        t+=120000;
        for(int k=0;k<3;k++){t+=80;turn(h,t,70,0.4f,20,0);}
        for(int k=0;k<13;k++){t+=80;turn(h,t,70,0f,20,0);}
        check(Float.isNaN(h.cornerMinSpeed),"a sample gap is not counted as corner time");
        // Boost bars run to 1.5 bar and grow in 0.5 bar steps once the car has gone past (tuned cars).
        check(DriveStats.boostTop(Float.NaN)==1.5f&&DriveStats.boostTop(0.9f)==1.5f&&DriveStats.boostTop(1.44f)==1.5f,"stock car keeps 1.5 bar");
        check(DriveStats.boostTop(1.46f)==2.0f&&DriveStats.boostTop(1.78f)==2.0f&&DriveStats.boostTop(2.1f)==2.5f,"tuned car widens in 0.5 bar steps");
        check(new DriveStats().boostTop()==1.5f,"nothing seen yet reads 1.5 bar");

        // A 4 s hole in the speed data mid-run: no time is drawn across it (no start or finish inside the hole).
        DriveStats gh=new DriveStats();long tg=0;
        for(;tg<=1000;tg+=80)step(gh,tg,0,800,0,0);
        for(float vg=1;vg<=30;vg++){tg+=80;step(gh,tg,vg,3000,0.3f,0.35f);}
        tg+=4000;step(gh,tg,110,3000,0.3f,0.35f);
        for(int k=0;k<20;k++){tg+=80;step(gh,tg,110+k,3000,0.3f,0.35f);}
        check(Float.isNaN(gh.last[0])&&!gh.running[0],"0-100 across a 4 s hole is dropped");
        check(Float.isNaN(gh.last[1])&&!gh.running[1],"60-100 does not start inside the hole");
        // Speed that stops arriving ends the run in progress instead of measuring forever.
        DriveStats gq=new DriveStats();long tq=0;
        for(;tq<=1000;tq+=80)step(gq,tq,0,800,0,0);
        tq+=80;step(gq,tq,20,3000,0.3f,0.35f);
        check(gq.running[0],"0-100 started");
        gq.update(tq+3000,Float.NaN,tq,3000,0.3f,0f,0f,tq,85f);
        check(!gq.running[0],"no speed for 3 s stops the run");
        System.out.println("PASS "+checks+" drive stats checks");
    }
}
