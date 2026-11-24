import com.veloster.vehicle.DashboardPage;
public class DashboardPageTest {
 static void check(boolean v,String message){if(!v)throw new AssertionError(message);}
 public static void main(String[] args){
  DashboardPage.configure(null,0);
  for(int i=0;i<DashboardPage.COUNT;i++){
   float scroll=DashboardPage.reveal(i,0),center=DashboardPage.left(i,scroll)+DashboardPage.width(i)/2;
   check(DashboardPage.tap(center,20,center,21,scroll)==i,"tap selects every page once revealed: "+i);
   check(DashboardPage.tap(center,55,center,21,scroll)==-1,"drag from content is not page switch");
   check(DashboardPage.left(i,scroll)>=DashboardPage.STRIP_LEFT-0.01f&&DashboardPage.left(i,scroll)+DashboardPage.width(i)<=DashboardPage.STRIP_RIGHT+0.01f,"revealed tab fits the strip: "+i);
  }
  check(DashboardPage.maxScroll()>0,"seven tabs overflow the strip, so it scrolls");
  check(DashboardPage.clampScroll(-50)==0&&DashboardPage.clampScroll(9999)==DashboardPage.maxScroll(),"scroll is clamped");
  check(DashboardPage.hit(60,20,0)==-1,"the logo is not a tab");
  check(DashboardPage.hit(514,20,0)==-1&&DashboardPage.gear(514,20),"the gear is not a tab");
  check(DashboardPage.hit(560,20,0)==-1&&DashboardPage.hit(628,20,0)==-1&&DashboardPage.hit(700,20,0)==-1,"language, clock and back are not tabs");
  check(DashboardPage.tap(150,20,260,20,0)==-1,"drag between tabs rejected");
  check(DashboardPage.normalize(-1)==DashboardPage.OVERVIEW&&DashboardPage.normalize(99)==DashboardPage.OVERVIEW,"invalid stored page returns overview");
  // screen list: hide, order, last shown stays
  int h=DashboardPage.toggle(0,DashboardPage.TAXI);
  DashboardPage.configure(null,h);
  check(!DashboardPage.isShown(DashboardPage.TAXI)&&DashboardPage.hit(DashboardPage.left(DashboardPage.TAXI,0)+5,20,0)!=DashboardPage.TAXI,"hidden page has no tab");
  check(DashboardPage.resolve(DashboardPage.TAXI)==DashboardPage.RECORDS,"a hidden page opens the next shown one");
  int all=0;for(int i=1;i<DashboardPage.COUNT;i++)all|=1<<i;
  check(DashboardPage.toggle(all,DashboardPage.OVERVIEW)==all,"the last shown page cannot be hidden");
  check(DashboardPage.validHidden((1<<DashboardPage.COUNT)-1)==0,"a mask hiding everything reads as all shown");
  int[] o=DashboardPage.move(DashboardPage.naturalOrder(),0,1);
  check(o[0]==DashboardPage.F1&&o[1]==DashboardPage.OVERVIEW,"move swaps neighbours");
  check(DashboardPage.move(DashboardPage.naturalOrder(),0,-1)[0]==DashboardPage.OVERVIEW,"move at the end does nothing");
  int[] old=DashboardPage.validOrder(new int[]{5,4,3,2,1,0});
  check(old.length==DashboardPage.COUNT&&old[0]==5&&old[6]==DashboardPage.RECORDS,"an order saved before RECORDS keeps its arrangement, new page last");
  check(DashboardPage.validOrder(new int[]{0,0,1})[1]==1,"a corrupt order falls back to natural");
  check(java.util.Arrays.equals(DashboardPage.parse(DashboardPage.csv(o)),o),"order round-trips through its preference text");
  DashboardPage.configure(o,0);check(DashboardPage.shown()[0]==DashboardPage.F1,"tabs follow the order");
  DashboardPage.configure(null,0);
  System.out.println("PASS: seven scrollable dashboard tabs, screen list order/hide, reveal, clamp and drag rejection");
 }
}
