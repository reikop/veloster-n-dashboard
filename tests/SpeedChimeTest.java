import com.veloster.vehicle.SpeedChime;

public final class SpeedChimeTest {
    private static int checks;
    private static void check(boolean ok,String what){checks++;if(!ok)throw new AssertionError(what);}
    public static void main(String[] a){
        SpeedChime c=new SpeedChime();
        SpeedChime.kmh=0;check(!c.due(150,0),"off: never");
        SpeedChime.kmh=105;SpeedChime.sound=0;long p=SpeedChime.PERIOD_MS[0];
        check(p==1150,"AE86: a pair every 1.15 s (measured)");
        check(!c.due(104.9f,0),"under the speed: quiet");
        check(c.due(105,10),"at the speed: due");c.rang(10);
        check(!c.due(110,10+p-1),"within a period: not again");
        check(c.due(110,10+p),"a period later: due");
        check(c.due(110,10+p+60),"not rung (sound still playing): still due on the next frame");c.rang(10+p+60);
        check(c.due(103,10+2*p+60),"just under (hysteresis 3): keeps ringing");c.rang(10+2*p+60);
        check(!c.due(101.9f,10+3*p+60),"3 km/h under: stops");
        check(!c.due(104,10+4*p),"back up but under the speed: still quiet");
        check(c.due(105,10+5*p),"at the speed again: due");
        check(!c.due(Float.NaN,10+6*p),"unknown speed: quiet");
        check(SpeedChime.NAMES.length==SpeedChime.PERIOD_MS.length,"a period per sound");
        check(SpeedChime.next(0)==100&&SpeedChime.next(105)==110&&SpeedChime.next(120)==0&&SpeedChime.next(7)==0,"speed cycle: off, 100, 105, 110, 120, off");
        System.out.println("PASS "+checks+" speed chime checks");
    }
}
