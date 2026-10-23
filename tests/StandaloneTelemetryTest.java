import com.veloster.vehicle.Telemetry;

/** Independent frame calculation must match the retained OEM formula and reject stale inputs. */
public class StandaloneTelemetryTest {
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
    static boolean near(float a,float b){return Math.abs(a-b)<0.001f;}
    static byte[] pt(int rpm){int raw=rpm*4;return new byte[]{(byte)128,(byte)(raw>>8),(byte)raw,(byte)128,83,89,(byte)204,(byte)128};}
    static final byte[] GF={(byte)128,0,0,64,(byte)128,0,0,0};
    public static void main(String[] args){
        Telemetry t=new Telemetry();t.rpm(pt(4000),1000);t.calculateObserved(1000);
        check(!t.sample(Telemetry.POWER,1000).valid,"missing friction cannot invent power");
        t.gforce(GF,1000);t.calculateObserved(1000);
        float expected=(float)(125*(4000.0/716.2/9.81));
        check(near(t.sample(Telemetry.POWER,1000).value,expected),"independent power equals captured OEM formula");
        check(t.sample(Telemetry.TORQUE,1000).value==125,"net torque subtracts friction");
        long age=t.sample(Telemetry.POWER,2000).ageMs;t.calculateObserved(2000);
        check(t.sample(Telemetry.POWER,2000).ageMs==age,"repeated calculation does not create a receipt");
        t.rpm(pt(0),2100);t.gforce(GF,2100);t.calculateObserved(2100);
        check(t.sample(Telemetry.TORQUE,2100).value==0&&t.sample(Telemetry.BOOST,2100).value==0,"stopped engine suppresses torque and boost");
        t.rpm(pt(350),2200);t.calculateObserved(2200);
        check(t.sample(Telemetry.TORQUE,2200).value==0&&t.sample(Telemetry.POWER,2200).value>0,"rising startup suppresses torque/boost, not raw-derived power");
        t.rpm(pt(350),2300);t.calculateObserved(2300);
        check(t.sample(Telemetry.TORQUE,2300).value==0,"unchanged startup RPM stays suppressed");
        t.rpm(pt(340),2400);t.calculateObserved(2400);
        check(t.sample(Telemetry.TORQUE,2400).value==125,"falling RPM above300 restores valid torque");
        t.rpm(pt(300),2500);t.calculateObserved(2500);
        check(t.sample(Telemetry.TORQUE,2500).value==0,"RPM300 boundary remains suppressed");
        t.rpm(pt(400),2600);t.calculateObserved(2600);
        check(t.sample(Telemetry.TORQUE,2600).value==125,"RPM400 boundary restores torque");
        t.rpm(pt(4000),65000);t.calculateObserved(65000);
        check(!t.sample(Telemetry.POWER,65000).valid,"stale friction cannot refresh computed values");
        t.gforce(GF,65000);t.calculateObserved(65000);
        check(t.sample(Telemetry.POWER,65000).valid,"fresh independent sources restore computation");
        t.gforce(null,65100);t.calculateObserved(65100);
        check(!t.sample(Telemetry.POWER,65100).valid,"invalid friction invalidates computed values");
        t.clear();t.calculateObserved(65200);
        check(!t.sample(Telemetry.POWER,65200).valid,"ignition clear cannot retain derived source state");
        System.out.println("PASS standalone formula, missing/stale/invalid sources, startup RPM boundaries, deduplication and ignition clear");
    }
}
