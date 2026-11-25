import com.veloster.vehicle.Circuits;

/** Which circuit the car is at: each track's own start line, nothing in town, and KIC's two layouts told apart. */
public final class CircuitsTest {
    private static int checks;
    private static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
    private static int find(String id){for(int i=0;i<Circuits.count();i++)if(Circuits.id(i).equals(id))return i;throw new AssertionError(id);}
    public static void main(String[] args){
        check(Circuits.count()==6,"six circuits (open to any car on track days)");
        for(int i=0;i<Circuits.count();i++){
            double[] s=Circuits.start(i);
            check(Circuits.distance(i,s[0],s[1])<1,Circuits.id(i)+": its start line is on its track");
            check(Circuits.lengthM(i)>2000&&Circuits.lengthM(i)<6000,Circuits.id(i)+": a circuit's length "+Circuits.lengthM(i));
        }
        int inje=find("inje"),kic=find("kic"),perm=find("kic_permanent");
        check(!Circuits.lineEstimated(inje)&&!Circuits.lineEstimated(kic),"Inje (official map) and KIC (OSM finish node) have real lines");
        double[] il=Circuits.start(inje);   // the control line read off the 2025 SuperRace map
        check(Math.abs(il[0]-38.000451)*110540<15&&Math.abs(il[1]-128.291362)*87700<15,"Inje's line is where the official map puts it: "+il[0]+","+il[1]);
        int tb=find("taebaek");double[] tl=Circuits.start(tb);   // the circuit map's line, mid straight between T10 and T1
        check(!Circuits.lineEstimated(tb)&&Math.abs(tl[0]-37.082725)*110540<15&&Math.abs(tl[1]-129.017869)*88400<15,"Taebaek's line is where its map puts it: "+tl[0]+","+tl[1]);
        int amg=find("amg");double[] al=Circuits.start(amg);   // the circuit map's line on the T17-T1 straight
        check(!Circuits.lineEstimated(amg)&&Math.abs(al[0]-37.296167)*110540<15&&Math.abs(al[1]-127.206624)*88400<15,"AMG's line is where its map puts it: "+al[0]+","+al[1]);
        double[] s=Circuits.start(inje);
        check(Circuits.pick(s[0],s[1],-1)==inje,"at Inje's line: Inje");
        check(Circuits.pick(37.5663,126.9779,-1)<0,"Seoul City Hall: no circuit");
        check(Circuits.pick(37.5663,126.9779,inje)<0,"and leaving a circuit's area lets it go");
        double seoul=1e18;for(int i=0;i<Circuits.count();i++)seoul=Math.min(seoul,Circuits.distance(i,37.5663,126.9779));
        check(seoul>30000&&seoul<80000,"the nearest track to Seoul City Hall is some tens of km away: "+seoul);
        double[] k=Circuits.start(kic);
        int at=Circuits.pick(k[0],k[1],-1);
        check(at==kic||at==perm,"at KIC's finish line: one of its layouts");
        int far=-1;double fd=0;   // the permanent circuit's point farthest from the full course
        for(int i=0;i<Circuits.points(perm);i++){double[] p=Circuits.point(perm,i);double d=Circuits.distance(kic,p[0],p[1]);if(d>fd){fd=d;far=i;}}
        check(fd>100,"the permanent circuit has a part of its own: "+fd);
        double[] p=Circuits.point(perm,far);
        check(Circuits.pick(p[0],p[1],kic)==perm,"on that part the permanent circuit takes over");
        double[] q=Circuits.point(kic,Circuits.points(kic)/2);
        check(Circuits.pick(q[0],q[1],kic)==kic,"the full course keeps it where it is the one driven");
        // TrackPath: a 100 x 50 m rectangle, 300 m round.
        com.veloster.vehicle.TrackPath tp=new com.veloster.vehicle.TrackPath(new float[]{0,0,100,0,100,50,0,50});
        check(Math.abs(tp.length-300)<1e-6,"track length");
        check(Math.abs(tp.along(40,-3)-40)<1e-6&&Math.abs(tp.along(103,20)-120)<1e-6,"distance along for a point beside the track");
        double[] r=tp.at(130);check(Math.abs(r[0]-100)<1e-6&&Math.abs(r[1]-30)<1e-6,"the point 130 m on is round the corner, 30 m up the far side");
        double[] u=tp.at(-10);check(Math.abs(u[0])<1e-6&&Math.abs(u[1]-10)<1e-6,"it wraps backwards past the start");
        check(Math.abs(tp.delta(290,10)-20)<1e-6&&Math.abs(tp.delta(10,290)+20)<1e-6,"the short way round, either sense");
        System.out.println("PASS "+checks+" circuit area checks");
    }
}
