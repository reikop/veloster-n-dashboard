import com.veloster.vehicle.Telemetry;

public class TelemetryHistoryTest {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static boolean near(float a,float b){return Math.abs(a-b)<0.001f;}
    public static void main(String[] args){
        Telemetry t=new Telemetry();
        check(t.history(1,1000,60000).size()==0,"polling cannot invent initial data");
        t.temperatures(new byte[]{(byte)180,(byte)188},1000);
        t.temperatures(new byte[]{(byte)181,(byte)188},1100);
        Telemetry.Series points=t.history(1,1100,60000);
        check(points.size()==1&&points.times[0]==1100&&near(points.values[0],95.75f),"one bucket retains latest actual receipt");
        t.temperatures(new byte[]{(byte)181,(byte)188},1250);
        points=t.history(1,1250,60000);check(points.size()==2&&points.times[1]==1250,"unchanged new receipt remains a real history point");
        t.snapshot(61250);points=t.history(1,61250,300000);
        check(!t.sample(1,61250).valid&&points.size()==2&&points.times[1]==1250,"held values expire without synthetic history points");
        t.temperatures(null,1300);t.temperatures(new byte[]{(byte)182,(byte)188},1350);
        points=t.history(1,1400,60000);check(points.size()==2&&points.valid[1]&&points.breakBefore[1],"invalid-to-valid transition inside bucket preserves a break");
        t.temperatures(null,1500);points=t.history(1,1600,60000);check(!points.valid[points.size()-1],"invalid packet does not become a zero-valued plotted point");
        check(t.history(1,1600,100).size()==1,"window query respects real timestamps");
        t.clear();check(t.history(1,1700,300000).size()==0,"ignition reset clears previous trip history");
        for(int i=0;i<=1600;i++)t.temperatures(new byte[]{(byte)(100+i%80),(byte)188},i*250L);
        points=t.history(1,400000,300000);
        check(points.size()==1201&&points.times[0]==100000&&points.times[1200]==400000,"ring storage is bounded while retaining five minutes");
        check(t.history(1,400000,60000).size()==241,"one-minute view filters retained five-minute data");
        byte[] a=new byte[40];a[20]=(byte)255;a[21]=121;t.additional(a,400100);
        check(near(t.sample(Telemetry.STEERING,400100).value,-13.5f),"raw signed SAS remains unchanged for packet analysis");
        check(near(Telemetry.displayValue(Telemetry.STEERING,t.sample(Telemetry.STEERING,400100).value),13.5f),"negative raw SAS reverses only at display boundary");
        a[20]=0;a[21]=(byte)135;t.additional(a,400250);
        check(near(Telemetry.displayValue(Telemetry.STEERING,t.sample(Telemetry.STEERING,400250).value),-13.5f),"opposite steering direction remains symmetric");
        check(Float.floatToIntBits(Telemetry.displayValue(Telemetry.STEERING,-0f))==Float.floatToIntBits(0f),"straight steering never displays negative zero");
        check(Telemetry.displayValue(1,95.5f)==95.5f,"other display values are untouched");
        System.out.println("Telemetry history and steering display tests passed");
    }
}
