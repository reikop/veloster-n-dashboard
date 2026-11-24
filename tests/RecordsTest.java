import com.veloster.vehicle.DriveStats;
import com.veloster.vehicle.Records;
import java.util.HashMap;

public final class RecordsTest {
    static int n;
    static void check(boolean ok,String what){n++;if(!ok)throw new AssertionError(what);}
    public static void main(String[] a){
        // the list: best first, a tie keeps the older one, the best KEEP kept, a bad value changes nothing
        String l=Records.insert("",7.5f,"d1");l=Records.insert(l,6.83f,"d2");l=Records.insert(l,7.5f,"d3");
        check(l.equals("6.830@d2,7.500@d1,7.500@d3"),"order: "+l);
        for(int i=0;i<10;i++)l=Records.insert(l,9+i,"x");
        check(l.split(",").length==Records.KEEP&&l.startsWith("6.830@d2"),"cap: "+l);
        check(Records.insert(l,20,"late").equals(l)&&Records.insert(l,Float.NaN,"x").equals(l)&&Records.insert(l,0,"x").equals(l)&&Records.insert(null,Float.NaN,"x").equals(""),"no change");
        check(Records.value("6.830@d2")==6.83f&&Records.date("6.830@d2").equals("d2")&&Float.isNaN(Records.value("junk")),"entry parts");

        // no store (the host, or before the dashboard set one): nothing kept, nothing thrown
        Records.store(null);Records.add("run_0",6f);check(Float.isNaN(Records.best("run_0"))&&Records.entries("run_0").length==0,"no store");

        final HashMap<String,String> kept=new HashMap<String,String>();final int[] puts={0};
        Records.store(new Records.Store(){
            public String get(String k){return kept.get(k);}
            public void put(String k,String v){kept.put(k,v);puts[0]++;}
            public void clear(){kept.clear();}});
        Records.add("run_0",7.2f);check(Records.best("run_0")==7.2f,"first");
        Records.add("run_0",6.9f);check(Records.best("run_0")==6.9f&&Records.entries("run_0").length==2,"better goes first (cache refreshed)");
        for(int i=0;i<Records.KEEP;i++)Records.add("run_0",5f+i*0.1f);
        int before=puts[0];Records.add("run_0",30f);check(puts[0]==before,"a run outside the list is not written");
        check(Records.lapKey("인제 스피디움").equals("lap_인제 스피디움")&&Records.runKey(2).equals("run_2"),"keys");

        // a finished 0-100 lands in the list by itself (DriveStats, the same clock the screen uses)
        Records.clear();check(Records.entries("run_0").length==0,"clear");
        DriveStats s=new DriveStats();long t=1000;
        s.update(t,0,t,800,0,0,0,t,90);
        for(float v=0;v<=104;v+=2){t+=100;s.update(t,v,t,3000,1,0,0.3f,t,90);}
        check(!Float.isNaN(s.last[0])&&Math.abs(Records.best("run_0")-s.last[0])<0.001f,"0-100 recorded: "+s.last[0]+" vs "+Records.best("run_0"));
        Records.store(null);
        System.out.println("PASS "+n+" record list checks");
    }
}
