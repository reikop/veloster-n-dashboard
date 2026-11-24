import com.veloster.vehicle.DayNight;
import com.veloster.vehicle.DayPhase;

/** Day/night decision: stock screen mode, illumination, headlamps, clock, the 3 s hold and the first-read migration. */
public final class DayNightTest {
    private static int checks;
    private static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
    public static void main(String[] args){
        int U=DayNight.UNKNOWN;
        check(!DayNight.rawNight(1,1,1,DayPhase.NIGHT)&&DayNight.rawNight(2,0,0,DayPhase.DAY),"stock 주간/야간 fixes it");
        DayNight.ILLUM_TRUE_IS_NIGHT=false;
        check(!DayNight.rawNight(0,1,U,U)&&DayNight.rawNight(0,0,U,U),"auto follows the illumination (true = day until confirmed)");
        check(!DayNight.rawNight(3,1,U,U),"수동 behaves like auto");
        DayNight.ILLUM_TRUE_IS_NIGHT=true;check(DayNight.rawNight(0,1,U,U),"the polarity constant flips it");DayNight.ILLUM_TRUE_IS_NIGHT=false;
        check(DayNight.rawNight(U,U,1,DayPhase.DAY)&&!DayNight.rawNight(U,U,0,DayPhase.NIGHT),"no stock values: the headlamps");
        check(DayNight.rawNight(U,U,U,DayPhase.TWILIGHT)&&!DayNight.rawNight(U,U,U,DayPhase.DAY),"no lamp signal: the clock, twilight is night");
        check(DayNight.rawNight(U,U,U,U),"nothing known: night");
        check(DayNight.source(1,U,U,U).equals("oem")&&DayNight.source(0,0,U,U).equals("oem")&&DayNight.source(U,U,0,U).equals("lamp")&&DayNight.source(U,U,U,0).equals("clock")&&DayNight.source(U,U,U,U).equals("none"),"source names");
        DayNight.Hold h=new DayNight.Hold();
        check(h.night&&!h.update(false,0)&&!h.update(false,2999)&&h.night,"day under 3 s keeps night");
        check(h.update(false,3000)&&!h.night,"3 s of day switches");
        check(!h.update(true,4000)&&!h.update(false,5000)&&!h.update(true,6000)&&!h.update(true,8999)&&!h.night,"a flicker restarts the wait");
        check(h.update(true,9000)&&h.night,"3 s of night after the flicker switches");
        check(DayNight.pick(DayNight.DAY,true)&&!DayNight.pick(DayNight.NIGHT,false)&&DayNight.pick(DayNight.AUTO,false)&&!DayNight.pick(DayNight.AUTO,true),"자동·낮·밤 pick the day or the night look");
        check(java.util.Arrays.equals(DayNight.firstRead(-1),new int[]{DayNight.NIGHT,10,0,0}),"계기판 따라: night slot 기본");
        check(java.util.Arrays.equals(DayNight.firstRead(5),new int[]{DayNight.NIGHT,10,5,0}),"a picked look moves to the night slot");
        check(java.util.Arrays.equals(DayNight.firstRead(0),new int[]{DayNight.NIGHT,10,0,0}),"기본 stays 기본");
        check(java.util.Arrays.equals(DayNight.firstRead(9),new int[]{DayNight.NIGHT,10,0,1}),"the old round-gauge 9 becomes 기본 + the round shape");
        System.out.println("PASS "+checks+" day/night checks");
    }
}
