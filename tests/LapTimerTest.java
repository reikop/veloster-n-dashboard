import com.veloster.vehicle.LapTimer;

/** Lap timer: tap starts and closes laps, hold stops then clears, quick double taps never make a lap. */
public final class LapTimerTest {
    private static int checks;
    private static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
    public static void main(String[] args){
        LapTimer l=new LapTimer();
        check(l.state()==LapTimer.IDLE&&l.current(5000)==0&&!l.hold(5000),"idle: nothing to stop");
        l.tap(1000);check(l.state()==LapTimer.RUNNING&&l.current(1500)==500,"tap starts lap 1");
        check(!l.tap(2000)&&l.laps==0,"a tap 1 s into the lap is ignored (double tap)");
        l.tap(91000);check(l.laps==1&&l.last==90000&&l.best==90000&&l.delta()==Long.MIN_VALUE,"lap 1: 1:30.00, nothing to compare");
        l.tap(176000);check(l.laps==2&&l.last==85000&&l.best==85000&&l.delta()==-5000,"lap 2 is 5 s quicker: a new best, -5.00");
        l.tap(264000);check(l.last==88000&&l.best==85000&&l.delta()==3000,"lap 3 is 3 s off the best");
        l.hold(270000);check(l.state()==LapTimer.STOPPED&&l.current(999999)==6000,"hold stops; the lap is frozen at 6 s");
        l.tap(300000);check(l.state()==LapTimer.RUNNING&&l.current(301000)==7000,"tap resumes the same lap without the pause");
        l.hold(302000);l.hold(303000);check(l.state()==LapTimer.IDLE&&l.laps==0&&l.best==-1,"hold twice: stopped, then cleared");
        check(LapTimer.format(83456).equals("1:23.45")&&LapTimer.format(-1).equals("—")&&LapTimer.format(605000).equals("10:05.00"),"m:ss.cc");
        check(LapTimer.formatDelta(-5000).equals("-5.00")&&LapTimer.formatDelta(420).equals("+0.42"),"delta format");
        System.out.println("PASS "+checks+" lap timer checks");
    }
}
