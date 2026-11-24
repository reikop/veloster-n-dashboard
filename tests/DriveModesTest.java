import com.veloster.vehicle.DashboardPage;
import com.veloster.vehicle.DriveModes;
import com.veloster.vehicle.Telemetry;

/** Veloster N drive modes from the stock N-mode service, and the mode-screen jumps. */
public class DriveModesTest {
    static int n;static void check(boolean v,String m){n++;if(!v)throw new AssertionError(m);}
    public static void main(String[] a){
        check(DriveModes.fromOem(0)==DriveModes.NORMAL&&DriveModes.fromOem(1)==DriveModes.SPORT&&DriveModes.fromOem(2)==DriveModes.ECO,"stock 0/1/2 = NORMAL/SPORT/ECO");
        check(DriveModes.fromOem(6)==DriveModes.N&&DriveModes.fromOem(4)==DriveModes.N_CUSTOM&&DriveModes.fromOem(13)==DriveModes.N_CUSTOM,"6 N, 4 and 13 N CUSTOM");
        check(DriveModes.fromOem(15)<0&&DriveModes.fromOem(3)<0,"15 invalid, unknown codes ignored");
        check(Telemetry.driveModeLabel(DriveModes.ECO).equals("ECO")&&Telemetry.driveModeLabel(DriveModes.N).equals("N"),"labels");
        Telemetry t=new Telemetry();t.driveMode(6,1000);check(t.sample(Telemetry.DRIVE_MODE,1000).valid&&t.sample(Telemetry.DRIVE_MODE,1000).value==DriveModes.N,"stored as N");
        t.driveMode(15,1100);check(!t.sample(Telemetry.DRIVE_MODE,1100).valid,"invalid clears it");
        DashboardPage.configure(null,0);
        int[] tg={DashboardPage.LAMBDA,-1,-1,DashboardPage.TACHO,-1};   // SPORT -> 람다, N -> 타코
        int[] r=DriveModes.onMode(DriveModes.NORMAL,DriveModes.SPORT,DashboardPage.OVERVIEW,-1,tg);
        check(r[0]==DashboardPage.LAMBDA&&r[1]==DashboardPage.OVERVIEW,"SPORT opens its screen and remembers the one before");
        r=DriveModes.onMode(DriveModes.SPORT,DriveModes.N,r[0],r[1],tg);
        check(r[0]==DashboardPage.TACHO&&r[1]==DashboardPage.OVERVIEW,"N opens its own, the first screen is still remembered");
        r=DriveModes.onMode(DriveModes.N,DriveModes.NORMAL,r[0],r[1],tg);
        check(r[0]==DashboardPage.OVERVIEW&&r[1]==-1,"a mode set to the previous screen goes back");
        r=DriveModes.onMode(DriveModes.NORMAL,DriveModes.SPORT,DashboardPage.F1,-1,tg);
        r=DriveModes.onMode(DriveModes.SPORT,DriveModes.NORMAL,DashboardPage.GAUGES,r[1],tg);
        check(r[0]==DashboardPage.GAUGES,"a driver who moved off the jumped-to screen keeps it");
        check(DriveModes.onMode(-1,DriveModes.SPORT,DashboardPage.F1,-1,tg)[0]==DashboardPage.F1,"the first mode seen moves nothing");
        DashboardPage.configure(null,1<<DashboardPage.LAMBDA);
        check(DriveModes.onMode(DriveModes.NORMAL,DriveModes.SPORT,DashboardPage.F1,-1,tg)[0]==DashboardPage.F1,"a hidden target is not opened");
        DashboardPage.configure(null,0);
        System.out.println("PASS "+n+" drive mode checks");
    }
}
