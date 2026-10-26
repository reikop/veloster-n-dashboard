import com.veloster.carplayhub.QuickEntryPolicy;

public final class QuickEntryPolicyTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        for(int mode=-2;mode<=4;mode++){
            boolean enabled=mode==1||mode==2;
            check(QuickEntryPolicy.restore(mode)==enabled,"unknown/off setting must never restore");
            for(boolean screen:new boolean[]{false,true})for(boolean locked:new boolean[]{false,true})
                check(QuickEntryPolicy.visible(mode,screen,locked)==(mode==2&&screen&&!locked),"locked/off/notification-only overlay hidden");
        }
        check(QuickEntryPolicy.fraction(Float.NaN)==0.5f,"corrupt NaN preference");
        check(QuickEntryPolicy.fraction(Float.POSITIVE_INFINITY)==0.5f,"corrupt infinite preference");
        check(QuickEntryPolicy.fraction(-1)==0&&QuickEntryPolicy.fraction(2)==1,"out of range preference");
        for(int width:new int[]{0,24,48,800,1280})for(int height:new int[]{0,24,48,480,720}){
            int size=48;
            for(int x:new int[]{-999,0,20,600,99999}){
                int bound=QuickEntryPolicy.bound(x,width,size);
                check(bound>=0&&bound<=Math.max(0,width-size),"drag outside screen");
                int edge=QuickEntryPolicy.edge(QuickEntryPolicy.right(bound,width,size),width,size);
                check(edge==0||edge==Math.max(0,width-size),"snap to either edge");
            }
            for(float fraction:new float[]{-1,0,0.3f,0.5f,1,2,Float.NaN}){
                int y=QuickEntryPolicy.y(fraction,height,size);
                check(y>=0&&y<=Math.max(0,height-size),"vertical bound on rotation/small display");
                float normalized=QuickEntryPolicy.relativeY(y,height,size);
                check(normalized>=0&&normalized<=1,"stored relative position");
                check(QuickEntryPolicy.y(normalized,height,size)==y,"position roundtrip");
            }
        }
        float saved=QuickEntryPolicy.relativeY(216,480,48);
        check(QuickEntryPolicy.y(saved,800,48)==376,"relative position survives rotation");
        System.out.println("PASS: quick-entry opt-in restore, lock/screen visibility, drag bounds and rotation position");
    }
}
