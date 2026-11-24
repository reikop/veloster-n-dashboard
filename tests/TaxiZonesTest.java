import com.veloster.vehicle.TaxiMeter;
import com.veloster.vehicle.TaxiTariff;
import com.veloster.vehicle.TaxiZones;

/** Taxi fare areas from GPS and each area's table: city halls land in their own area, the sea in none. */
public final class TaxiZonesTest {
    private static int checks;
    private static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
    private static void area(double lat,double lon,String tariff,String name){
        int z=TaxiZones.at(lat,lon);
        check(z>=0&&TaxiZones.tariff(z).equals(tariff)&&TaxiZones.name(z).equals(name),
            lat+","+lon+" should be "+tariff+"/"+name+": "+(z<0?"none":TaxiZones.tariff(z)+"/"+TaxiZones.name(z)));
    }
    private static void near(float got,float want,String s){check(Math.abs(got-want)<1e-4f,s+": "+got);}
    public static void main(String[] args){
        area(37.5663,126.9779,"서울","서울");          // Seoul City Hall
        area(37.6584,127.0390,"서울","서울");          // Dobong-gu office (a 구, still the one Seoul area)
        area(37.2636,127.0286,"경기","수원시");        // Suwon City Hall (일반구 merged into the 시)
        area(37.2411,127.1776,"경기","용인시");        // Yongin City Hall
        area(37.5034,126.7660,"경기","부천시");        // Bucheon City Hall (구 re-established 2024)
        area(37.1995,126.8312,"경기","화성시");        // Hwaseong City Hall (구 from 2026)
        area(35.1798,129.0750,"부산","부산");          // Busan City Hall
        area(35.3350,129.0373,"경남","양산시");        // Yangsan City Hall
        area(35.1601,126.8514,"광주","광주");          // Gwangju City Hall: its own table since the 2026 merger
        area(34.8118,126.3922,"전남","목포시");        // Mokpo City Hall
        area(36.4800,127.2890,"세종","세종");          // Sejong government complex
        area(33.4996,126.5312,"제주","제주");          // Jeju City Hall
        area(33.2541,126.5600,"제주","제주");          // Seogwipo: the island is one area
        area(37.7470,128.8760,"강원","강릉시");        // Gangneung
        check(TaxiZones.at(37.0,125.5)<0,"the Yellow Sea is in no area");
        check(TaxiZones.at(35.6762,139.6503)<0,"Tokyo is in no area");

        TaxiTariff seoul=TaxiTariff.of("서울"),busan=TaxiTariff.of("부산"),jeju=TaxiTariff.of("제주");
        near(seoul.surcharge(12,false),0,"Seoul at noon");
        near(seoul.surcharge(12,true),0.2f,"Seoul out of the city at noon");
        near(seoul.surcharge(22,true),0.4f,"Seoul 22 h out of the city: 20 + 20");
        near(seoul.surcharge(0,true),0.6f,"Seoul midnight out of the city: 40 + 20");
        near(busan.surcharge(23,true),0.4f,"Busan's combined rate is 40, not 20 + 30");
        near(busan.surcharge(1,true),0.5f,"Busan 24-02 h combined");
        near(jeju.surcharge(12,true),0,"Jeju has no out-of-area surcharge");
        check(TaxiTariff.of("세종").borrowed&&TaxiTariff.of("세종").base==4800,"Sejong borrows Seoul's table");
        check(TaxiTariff.of("어딘가")==seoul,"an unknown name falls back to Seoul");
        check(seoul.nightHours().equals("22~04")&&seoul.nightMax()==40,"Seoul night note: "+seoul.nightHours());
        check(TaxiTariff.of("전북").nightHours().equals("00~04"),"Jeonbuk night note: "+TaxiTariff.of("전북").nightHours());

        // The meter on an area table (TaxiMeter.korea)
        TaxiMeter m=new TaxiMeter();m.setArea("부산");m.start(12);
        for(int i=0;i<200;i++)m.update(36,100,12);   // 200 m
        check(m.fare()==4800&&m.steps()==0,"Busan's base covers 2 km: "+m.fare());
        TaxiMeter far=new TaxiMeter();far.setArea("제주");far.start(12);
        for(int i=0;i<25000;i++)far.update(72,100,12);   // 50 km
        check(far.stepsCharged()>far.steps()*100,"past 20 km a Jeju step is 120 won");
        // GPS areas: the trip keeps its starting table, another area adds its out-of-area surcharge after 3 seconds
        int seoulZ=TaxiZones.at(37.5663,126.9779),suwon=TaxiZones.at(37.2636,127.0286);
        TaxiMeter g=new TaxiMeter();
        check(g.place(suwon,TaxiZones.tariff(suwon))&&g.area().equals("경기"),"vacant: the table follows the car");
        g.start(12);check(!g.place(seoulZ,TaxiZones.tariff(seoulZ))&&g.area().equals("경기"),"during a trip the start's table stays");
        g.place(seoulZ,"서울");check(!g.outOfCity(),"two seconds out is not enough (simplified borders)");
        g.place(seoulZ,"서울");check(g.outOfCity()&&g.autoOutside(),"three seconds in Seoul: out of the trip's area");
        near(g.rate(12),0.2f,"Gyeonggi's out-of-area 20 % at noon");
        g.place(suwon,"경기");g.place(suwon,"경기");g.place(suwon,"경기");check(!g.outOfCity(),"back in the start area");
        g.place(-1,null);check(!g.located()&&g.area().equals("경기"),"a lost fix keeps what was known");
        // Auto start: moving off starts a trip; after paying the car must stop before the next one
        TaxiMeter a=new TaxiMeter();
        check(!a.autoStart(2,12)&&a.state()==TaxiMeter.VACANT,"crawling does not start");
        check(a.autoStart(10,12)&&a.state()==TaxiMeter.HIRED,"moving off starts a trip");
        a.stop();check(!a.autoStart(20,12)&&a.state()==TaxiMeter.PAID,"paid while moving: the receipt stays");
        check(!a.autoStart(0,12)&&a.autoStart(8,12)&&a.state()==TaxiMeter.HIRED,"after a stop the next departure starts a trip");
        a.reset();check(!a.autoStart(30,12),"cleared while moving: waits for a stop");
        TaxiMeter jp=new TaxiMeter();jp.setRegion(TaxiMeter.JAPAN);check(!jp.setArea("부산")||jp.region()==TaxiMeter.JAPAN,"an area does not leave Japan mode");
        jp.setRegion(TaxiMeter.KOREA);check(jp.area().equals("부산")&&jp.tariff().baseM==2000,"back to Korea on the remembered area");
        System.out.println("PASS "+checks+" taxi area checks");
    }
}
