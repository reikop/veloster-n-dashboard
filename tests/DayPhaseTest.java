import com.veloster.vehicle.DayPhase;

/** Sky of the 8-bit views: day, twilight or night from Seoul's sunrise and sunset by month. */
public final class DayPhaseTest {
    private static int checks;
    private static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
    public static void main(String[] args){
        check(DayPhase.of(1,12*60)==DayPhase.DAY,"January noon is day");
        check(DayPhase.of(1,22*60)==DayPhase.NIGHT,"January 22:00 is night");
        check(DayPhase.of(1,17*60+40)==DayPhase.TWILIGHT,"January sunset (17:35) is twilight");
        check(DayPhase.of(7,19*60+50)==DayPhase.TWILIGHT,"July 19:50 is twilight (sunset 19:55)");
        check(DayPhase.of(7,18*60)==DayPhase.DAY,"July 18:00 is still day");
        check(DayPhase.of(6,5*60)==DayPhase.TWILIGHT&&DayPhase.of(6,4*60)==DayPhase.NIGHT,"June dawn: 05:00 twilight, 04:00 night");
        check(DayPhase.of(13,12*60)==DayPhase.DAY&&DayPhase.of(0,0)==DayPhase.NIGHT,"out-of-range month is clamped");
        System.out.println("PASS "+checks+" day phase checks");
    }
}
