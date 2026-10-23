import com.veloster.vehicle.Telemetry;

/** Uses synthetic TPMS data and checks unknown/freshness boundaries. */
public class TirePressureTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args){
        Telemetry t=new Telemetry();
        byte[] b=new byte[40];b[22]=1;b[23]=0;
        int[] values={32,33,34,35};
        for(int i=0;i<4;i++)b[29+2*i]=(byte)values[i];
        t.additional(b,1000);
        for(int i=0;i<4;i++){
            check(t.sample(18+i,1000).valid&&t.sample(18+i,1000).value==values[i],"synthetic pressure position "+i);
            check(!t.sample(3+i,1000).seen,"pressure must never populate wheel speed "+i);
            check(t.sample(18+i,60999).valid&&!t.sample(18+i,61000).valid,"60-second expiry "+i);
        }
        t.additional(b,2000);check(!t.sample(18,2000).changed,"same frame value is not a pressure change");
        check(t.sample(18,61999).valid,"new reception of same value refreshes timestamp");
        b[22]=0;t.additional(b,3000);check(!t.sample(18,3000).valid,"unobserved status cannot reuse last pressure");
        b[22]=1;b[23]=1;t.additional(b,4000);check(!t.sample(18,4000).valid,"unverified unit cannot be mislabeled psi");
        b[23]=0;b[28]=1;b[29]=-1;t.additional(b,5000);
        check(!t.sample(18,5000).valid&&t.sample(19,5000).valid,"511 sentinel invalidates only its tire");
        b[28]=-1;b[29]=-1;t.additional(b,6000);check(!t.sample(18,6000).valid,"65535 unavailable raw suppressed");
        b[28]=0;b[29]=-1;t.additional(b,7000);check(!t.sample(18,7000).valid,"255 unavailable raw suppressed");
        b[29]=0;t.additional(b,8000);check(t.sample(18,8000).valid&&t.sample(18,8000).value==0,"zero is not clamped upward");
        b[29]=101;t.additional(b,9000);check(!t.sample(18,9000).valid,"unverified out-of-display-range value suppressed");
        t.additional(new byte[19],10000);check(!t.sample(19,10000).valid,"legacy short frame cannot keep pressures valid");
        t.additional(null,11000);check(!t.sample(19,11000).valid,"null frame has no pressure");
        t.clear();check(!t.sample(19,12000).seen,"ignition clear resets pressure and history");
        System.out.println("PASS: synthetic TPMS decoding, position identity, unknown unit/status, per-tire invalids and expiry");
    }
}
