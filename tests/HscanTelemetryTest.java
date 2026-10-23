import com.veloster.vehicle.Telemetry;

/** Packet fixtures exercise the documented layout; not claimed as new vehicle observations. */
public final class HscanTelemetryTest {
    private static int checks;
    private static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
    private static void equal(Telemetry t,int id,float expected,long now){
        Telemetry.Sample s=t.sample(id,now);check(s.valid&&Math.abs(s.value-expected)<0.001,"signal "+id+" expected "+expected);
    }
    public static void main(String[] args){
        Telemetry t=new Telemetry();byte[] bat={0,0,74,64,31,0,0,0};
        t.hscanJsn(0x8a,7,bat,1000);t.hscanJsn(0xc7,0x87,bat,1000);t.hscanJsn(0xc7,7,new byte[9],1000);
        check(!t.sample(Telemetry.BAT_SOC,1000).seen,"wrong CID, TP or length cannot create values");
        t.hscanJsn(0xc7,7,bat,1000);equal(t,Telemetry.BAT_SOC,74,1000);equal(t,Telemetry.BAT_VOLTAGE,14,1000);
        t.hscanJsn(0xc7,7,bat,60000);equal(t,Telemetry.BAT_SOC,74,119999);
        check(!t.sample(Telemetry.BAT_SOC,120000).valid,"identical new frame refreshes, exact 60 seconds expires");
        bat[6]=(byte)128;t.hscanJsn(0xc7,7,bat,120001);check(!t.sample(Telemetry.BAT_SOC,120001).valid,"invalid sensor suppresses values");
        bat[6]=0;bat[7]=(byte)128;t.hscanJsn(0xc7,7,bat,120002);check(!t.sample(Telemetry.BAT_VOLTAGE,120002).valid,"sensor error suppresses voltage");
        bat[7]=0;bat[2]=(byte)255;t.hscanJsn(0xc7,7,bat,120003);check(!t.sample(Telemetry.BAT_SOC,120003).valid,"SOC sentinel suppressed");
        byte[] sas={0,0,30,0,0};t.hscanJsn(0xc7,0x53,sas,1000);equal(t,Telemetry.STEERING_SPEED,120,1000);
        sas[2]=(byte)255;t.hscanJsn(0xc7,0x53,sas,1001);check(!t.sample(Telemetry.STEERING_SPEED,1001).valid,"SAS sentinel suppressed");
        byte[] intake={0,0,104,0,0,0,0,0};t.hscanJsn(0xc7,0x23,intake,1000);equal(t,Telemetry.INTAKE,30,1000);
        intake[2]=(byte)255;t.hscanJsn(0xc7,0x23,intake,1001);check(!t.sample(Telemetry.INTAKE,1001).valid,"unverified IAT raw255 conservatively withheld");
        byte[] rss=new byte[8];t.hscanJsn(0xc7,0x5a,rss,1000);equal(t,Telemetry.TCS,0,1000);equal(t,Telemetry.ESC,0,1000);equal(t,Telemetry.ABS,0,1000);
        rss[1]=2;t.hscanJsn(0xc7,0x5a,rss,2000);equal(t,Telemetry.TCS,1,2000);equal(t,Telemetry.ABS,0,2000);
        rss[1]=0x44;t.hscanJsn(0xc7,0x5a,rss,3000);equal(t,Telemetry.TCS,0,3000);equal(t,Telemetry.ESC,1,3000);equal(t,Telemetry.ABS,1,3000);
        check(!t.sample(Telemetry.ESC,63000).valid,"status also expires without a fresh frame");
        java.util.Arrays.fill(rss,(byte)255);t.hscanJsn(0xc7,0x5a,rss,64000);check(!t.sample(Telemetry.ESC,64000).valid,"all-FF packet is unknown");
        byte[] wheels={0,(byte)0xc4,32,(byte)0x84,64,0x44,96,4};t.hscanJsn(0xc7,0x6e,wheels,1000);
        for(int i=0;i<4;i++)equal(t,3+i,32+i,1000);
        wheels[0]=(byte)255;wheels[1]=(byte)255;t.hscanJsn(0xc7,0x6e,wheels,2000);check(!t.sample(3,2000).valid,"wheel sentinel suppressed independently");equal(t,4,33,2000);
        byte[] elsd={0,(byte)0xd2,4,0,0,0,0,0};t.hscanJsn(0xc7,0x70,elsd,1000);
        check(!t.sample(Telemetry.ELSD,1000).seen,"unmapped MICOM frame never assigned to e-LSD");
        t.canFrame(0x3f0,elsd,2000);equal(t,Telemetry.ELSD,123.4f,2000);check(Telemetry.UNITS[Telemetry.ELSD].equals(""),"no invented e-LSD physical unit");
        check(t.history(Telemetry.ELSD,2000,60000).size()==1,"numeric candidate retained in history after explicit CAN input");
        t.clear();check(!t.sample(Telemetry.INTAKE,2000).seen&&!t.sample(Telemetry.TCS,2000).seen,"ignition reset clears extras");
        System.out.println("PASS "+checks+" HSCAN model checks");
    }
}
