package com.veloster.vehicle;

import java.util.Arrays;

/**
 * Which taxi business area (TaxiZoneData) a position is in: a box check, then even-odd over all of the area's rings,
 * so holes need no flag. The rings are decoded on first use (about 23,000 points). Simplified to 200 m, so within a
 * couple of hundred metres of a border the answer can be the neighbour; TaxiCluster waits for a few agreeing fixes.
 * No Android: host-tested.
 */
public final class TaxiZones {
    private TaxiZones(){}
    /** Area, ring, then latitude and longitude interleaved in 1e-5 degrees. */
    private static int[][][] rings;
    /** Area: min latitude, min longitude, max latitude, max longitude. */
    private static int[][] box;

    public static String name(int z){return TaxiZoneData.ZONES[z][1];}
    /** The fare-table name (TaxiTariff.of). */
    public static String tariff(int z){return TaxiZoneData.ZONES[z][0];}

    /** The area at this position in degrees, or -1 (sea, abroad). */
    public static synchronized int at(double lat,double lon){
        if(rings==null)decode();
        int y=(int)Math.round(lat*1e5),x=(int)Math.round(lon*1e5);
        for(int z=0;z<rings.length;z++){
            int[] b=box[z];if(y<b[0]||y>b[2]||x<b[1]||x>b[3])continue;
            boolean in=false;
            for(int[] r:rings[z])for(int i=0,j=r.length-2;i<r.length;j=i,i+=2){
                int yi=r[i],xi=r[i+1],yj=r[j],xj=r[j+1];
                if((yi>y)!=(yj>y)&&x<(double)(xj-xi)*(y-yi)/(yj-yi)+xi)in=!in;
            }
            if(in)return z;
        }
        return -1;
    }
    /** The rings are decoded (at() answers without the few hundred ms of decoding). */
    public static synchronized boolean ready(){return rings!=null;}
    /** Lets go of the decoded rings (about 0.2 MB) while the dashboard is out of sight; the next at() decodes again. */
    public static synchronized void release(){rings=null;box=null;}
    private static void decode(){
        String[][] zs=TaxiZoneData.ZONES;int[][][] rs=new int[zs.length][][];int[][] bs=new int[zs.length][];
        for(int z=0;z<zs.length;z++){
            String[] parts=zs[z][2].split(" ");rs[z]=new int[parts.length][];
            int[] b={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};
            for(int k=0;k<parts.length;k++){
                int[] r=rs[z][k]=polyline(parts[k]);
                for(int i=0;i<r.length;i+=2){b[0]=Math.min(b[0],r[i]);b[1]=Math.min(b[1],r[i+1]);b[2]=Math.max(b[2],r[i]);b[3]=Math.max(b[3],r[i+1]);}
            }
            bs[z]=b;
        }
        box=bs;rings=rs;
    }
    /** Google's polyline coding: zig-zag deltas, 5 bits a character from '?', latitude then longitude. */
    static int[] polyline(String s){
        int[] out=new int[s.length()];int n=0,i=0,lat=0,lon=0;
        while(i<s.length())for(int k=0;k<2;k++){
            int v=0,shift=0,b;
            do{b=s.charAt(i++)-63;v|=(b&0x1f)<<shift;shift+=5;}while(b>=0x20);
            int d=(v&1)!=0?~(v>>1):v>>1;
            if(k==0)out[n++]=lat+=d;else out[n++]=lon+=d;
        }
        return Arrays.copyOf(out,n);
    }
}
