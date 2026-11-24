import com.veloster.vehicle.ShiftGuide;

/** Optional shift beep timing: one beep per rising edge of the shift cue, none again within 1.5 s. */
public final class ShiftBeepTest {
    private static int checks;
    private static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
    public static void main(String[] args){
        ShiftGuide.Beep b=new ShiftGuide.Beep();
        check(!b.fire(false,0),"no cue, no beep");
        check(b.fire(true,1000),"the cue lights: beep");
        check(!b.fire(true,1100)&&!b.fire(true,5000),"held cue: no repeat");
        check(!b.fire(false,5100)&&b.fire(true,5200),"off and on again after 1.5 s: beep");
        check(!b.fire(false,5300)&&!b.fire(true,5400),"flicker 0.2 s after a beep: quiet");
        check(!b.fire(false,6000)&&b.fire(true,6800),"next shift 1.6 s later: beep");
        System.out.println("PASS "+checks+" shift beep checks");
    }
}
