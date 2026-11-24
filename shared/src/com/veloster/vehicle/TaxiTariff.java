package com.veloster.vehicle;

import java.util.HashMap;
import java.util.Map;

/**
 * Medium-taxi fares by 시·도, from 전국개인택시운송사업조합연합회 "시, 도별 개인택시 요금현황"
 * (gntx.kr/information/information_02_1.php, read 2026-11-04). A trip keeps the table of the area where the meter
 * started. Where the source leaves a gap: base distance 2 km (its column header), combined night + out-of-area rate
 * the sum of the two (blank, a range, or 시군별 자율조정), 경북's out-of-area rate 20 %, 광주's general 35 %, 경기 the
 * 표준형 row, 충북's "22~04시 20~40 %" read as Seoul's bands. 세종 is not in the table: it borrows Seoul's. No Android:
 * host-tested.
 */
public final class TaxiTariff {
    /** The fare-table name (서울, 경기, …), the one the meter shows. */
    public final String region;
    public final int base,baseM,stepM,stepS;
    /** Out-of-area (시계외) surcharge in %, 0 where there is none. */
    public final int out;
    /** This area has no row of its own (세종): Seoul's numbers. */
    public final boolean borrowed;
    /** Night surcharge % by hour of day. */
    private final int[] night=new int[24];
    /** Pairs of night % and the combined % the table gives for it with the out-of-area surcharge. */
    private final int[] combo;
    /** From this combined distance on, a step is farWon instead of 100 (제주: 20 km, 120 won). */
    private final int farM,farWon;

    private TaxiTariff(String region,boolean borrowed,int base,int baseM,int stepM,int stepS,String nights,int out,int[] combo,int farM,int farWon){
        this.region=region;this.borrowed=borrowed;this.base=base;this.baseM=baseM;this.stepM=stepM;this.stepS=stepS;
        this.out=out;this.combo=combo;this.farM=farM;this.farWon=farWon;
        for(String band:nights.split(",")){   // "from-to:%", hours, to exclusive, wrapping past midnight
            String[] a=band.split("[-:]");int from=Integer.parseInt(a[0])%24,to=Integer.parseInt(a[1])%24,pct=Integer.parseInt(a[2]);
            for(int h=from;h!=to;h=(h+1)%24)night[h]=pct;
        }
    }
    private static final Map<String,TaxiTariff> ALL=new HashMap<String,TaxiTariff>();
    private static void add(String r,int base,int baseM,int stepM,int stepS,String nights,int out,int... combo){
        ALL.put(r,new TaxiTariff(r,false,base,baseM,stepM,stepS,nights,out,combo,0,0));
    }
    private static final String SEOUL_BANDS="22-23:20,23-2:40,2-4:20",BUSAN_BANDS="23-24:20,0-2:30,2-4:20";
    static {
        add("서울",4800,1600,131,30,SEOUL_BANDS,20);
        add("부산",4800,2000,132,33,BUSAN_BANDS,30,20,40,30,50);
        add("대구",4500,2000,125,31,BUSAN_BANDS,35,20,40,30,40);
        add("인천",4800,2000,135,33,SEOUL_BANDS,30);
        add("광주",4800,1700,132,32,BUSAN_BANDS,35,20,50,30,50);
        add("대전",4300,1800,132,33,BUSAN_BANDS,30,20,50,30,50);
        add("울산",4500,2000,125,30,"22-4:20",30,20,50);
        add("경기",4800,1600,131,30,"23-4:30",20);
        add("강원",4600,2000,131,31,BUSAN_BANDS,20);
        add("충북",4000,2000,127,32,SEOUL_BANDS,20,20,35);
        add("충남",4000,1400,127,35,"22-4:30",32);
        add("전북",4300,2000,134,32,"0-4:20",20);
        add("전남",4300,2000,130,30,"0-4:20",35);
        add("경북",4500,1700,128,30,"23-4:20",20);
        add("경남",4600,2000,128,30,"22-4:20",30);
        ALL.put("제주",new TaxiTariff("제주",false,4300,2000,126,31,"23-4:20",0,new int[0],20000,120));
        ALL.put("세종",new TaxiTariff("세종",true,4800,1600,131,30,SEOUL_BANDS,20,new int[0],0,0));
    }
    /** The table for a fare-table name (TaxiZones.tariff); Seoul's for an unknown one. */
    public static TaxiTariff of(String region){TaxiTariff t=ALL.get(region);return t!=null?t:ALL.get("서울");}

    public int night(int hour){return hour<0?0:night[hour%24];}
    /** Surcharge fraction at this hour (-1: unknown, none), outside the starting area or not. */
    public float surcharge(int hour,boolean outside){
        int n=night(hour),o=outside?out:0;
        if(n==0||o==0)return (n+o)/100f;
        int c=n+o;
        for(int i=0;i+1<combo.length;i+=2)if(combo[i]==n)c=combo[i+1];
        return Math.max(c,Math.max(n,o))/100f;
    }
    /** Won for the next step at this combined distance. */
    public int stepWon(float meters){return farM>0&&meters>=farM?farWon:100;}
    /** The night hours as "22~04" and the highest night %, for the meter's note. */
    public String nightHours(){
        int start=-1;for(int h=0;h<24;h++)if(night[h]>0&&night[(h+23)%24]==0){start=h;break;}
        if(start<0)return "";
        int end=start;while(night[end]>0&&(end+1)%24!=start)end=(end+1)%24;
        return String.format(java.util.Locale.US,"%02d~%02d",start,end);
    }
    public int nightMax(){int m=0;for(int p:night)m=Math.max(m,p);return m;}
}
