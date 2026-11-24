import com.veloster.vehicle.Telemetry;
import com.veloster.vehicle.Tiles;
import com.veloster.vehicle.VehicleProfile;

/** The floating window's signal list on the Veloster N build: only what this build receives. */
public class TilesTest {
    static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
    public static void main(String[] a){
        VehicleProfile p=VehicleProfile.VELOSTER_N;
        int n=0;
        for(int g:Tiles.groups(p,false))for(int s:Tiles.group(g,p,false)){n++;
            check(s>=0&&s<Telemetry.CORE||s==Telemetry.DRIVE_MODE,"only received signals are offered: "+s);}
        check(n>=20,"most received signals are offered: "+n);
        check(Tiles.available(Telemetry.BOOST,p)&&Tiles.available(Telemetry.TPMS_FL,p)&&Tiles.available(Telemetry.INTAKE,p),"boost, tyres and intake are there");
        check(!Tiles.available(Telemetry.CLUTCH,p),"no clutch pedal signal reaches this build");
        check(!Tiles.available(Telemetry.FUEL_LEVEL,p)&&!Tiles.available(Telemetry.CLUTCH_ODD,p)&&!Tiles.available(Tiles.CLUTCH,p),"no OBD values, no DCT clutches");
        check(!Tiles.available(Telemetry.DPF_DP,p)&&!Tiles.available(Tiles.DPF,p),"no diesel DPF");
        check(Tiles.available(Tiles.TIRES,p),"the four tyres as one entry");
        System.out.println("PASS: Veloster N tile list offers only received signals");
    }
}
