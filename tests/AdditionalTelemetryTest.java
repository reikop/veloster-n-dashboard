import com.veloster.vehicle.Telemetry;

public class AdditionalTelemetryTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static boolean near(float a,float b){return Math.abs(a-b)<0.001f;}
    static final byte[] PTT={(byte)128,0x3e,(byte)0x80,(byte)128,83,89,(byte)204,(byte)128};
    static final byte[] GF={(byte)128,0,0,64,(byte)128,0,0,0};
    static final float AMP=(float)(20*10.731613+458.98),PRE=(float)(1436*1.322);
    static final float POWER=(float)(125*(4000.0/716.2/9.81)),BOOST=(float)(((double)PRE-1.333*AMP)/10);
    static void begin(Telemetry t){t.beginComputed(4000,50,50,500,PRE,AMP);}
    static void result(Telemetry t){t.computed(POWER,125,BOOST);}
    public static void main(String[] args){
        Telemetry t=new Telemetry();byte[] additional=new byte[40];additional[20]=(byte)255;additional[21]=121;
        t.additional(additional,1000);check(near(t.sample(Telemetry.STEERING,1000).value,-13.5f),"retained packet signed steering angle");
        additional[21]=120;t.additional(additional,1100);check(near(t.sample(Telemetry.STEERING,1100).value,-13.6f),"negative steering change");
        t.additional(new byte[19],1200);check(t.sample(Telemetry.STEERING,1200).ageMs==100,"legacy packet does not refresh absent angle");
        check(!t.sample(Telemetry.STEERING,61100).valid,"steering expires after one minute");
        t.units(new byte[]{2,60,0},2000);check(t.sample(Telemetry.AMBIENT,2000).valid&&t.sample(Telemetry.AMBIENT,2000).value==20,"JSN Celsius ambient");
        t.units(new byte[]{4,(byte)255,108},2100);check(t.sample(Telemetry.AMBIENT,2100).valid&&near(t.sample(Telemetry.AMBIENT,2100).value,20),"Fahrenheit packet converted to Celsius");
        t.units(new byte[]{0,60,108},2200);check(!t.sample(Telemetry.AMBIENT,2200).valid,"unknown temperature unit cannot invent a reading");
        t.units(new byte[]{2,101,108},2300);check(!t.sample(Telemetry.AMBIENT,2300).valid,"OEM Celsius validity bound");
        t.units(new byte[]{4,60,(byte)181},2400);check(!t.sample(Telemetry.AMBIENT,2400).valid,"OEM Fahrenheit validity bound");
        t.clear();begin(t);result(t);check(!t.sample(Telemetry.POWER,3000).seen,"OEM default getters alone are not fresh data");
        t.rpm(PTT,1000);begin(t);result(t);check(!t.sample(Telemetry.POWER,1200).seen,"friction packet is required");
        t.gforce(GF,1200);begin(t);result(t);
        check(near(t.sample(Telemetry.POWER,1300).value,POWER)&&near(t.sample(Telemetry.TORQUE,1300).value,125),"instantaneous OEM outputs accepted against raw input fingerprint");
        check(near(t.sample(Telemetry.BOOST,1300).value,BOOST/100)&&t.sample(Telemetry.POWER,1300).ageMs==300,"kPa to bar and oldest source timestamp");
        begin(t);result(t);check(t.diagnostics(70000).contains("15:1/69000ms/unknown"),"timer calculations do not count as reception or refresh expiry");
        t.rpm(PTT,2000);begin(t);result(t);check(t.sample(Telemetry.POWER,2100).ageMs==900,"new PTT cannot refresh old friction timestamp");
        t.gforce(GF,3000);begin(t);result(t);check(t.sample(Telemetry.POWER,3100).ageMs==1100,"both sources use oldest latest reception");
        begin(t);t.rpm(PTT,4000);result(t);check(t.sample(Telemetry.POWER,4100).ageMs==2100,"racing raw update rejects old calculation context");
        t.beginComputed(3999,50,50,500,PRE,AMP);result(t);check(t.sample(Telemetry.POWER,4200).ageMs==2200,"stale OEM input arguments rejected");
        begin(t);t.computed(POWER+10,125,BOOST);check(t.sample(Telemetry.POWER,4300).ageMs==2300,"outputs inconsistent with friction sample rejected");
        begin(t);t.computed(POWER,0,0);check(t.sample(Telemetry.TORQUE,4400).valid&&t.sample(Telemetry.TORQUE,4400).value==0,"OEM low RPM zero policy preserved");
        begin(t);t.clear();result(t);check(!t.sample(Telemetry.POWER,4500).seen,"ignition clears in-flight calculation epoch");
        t.rpm(PTT,5000);t.gforce(GF,5100);begin(t);result(t);t.gforce(null,5200);
        check(!t.sample(Telemetry.POWER,5200).valid,"malformed dependency clears calculated readings immediately");
        System.out.println("Additional CAN telemetry tests passed");
    }
}
