package com.veloster.vehicle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

/** 기록실: the best few acceleration runs and circuit laps, kept across drives. A list is plain text, best first
 *  ("6.830@2026-11-18,7.010@2026-11-20", seconds), one per kind, so the store is a string per key. No Android here: the
 *  dashboard hands in the store (VehicleDash); on the host and before that, add() does nothing. */
public final class Records {
    public static final int KEEP=5;
    /** Where the lists live (SharedPreferences in the app). */
    public interface Store{String get(String key);void put(String key,String list);void clear();}
    private static volatile Store store;
    private static final HashMap<String,String[]> CACHE=new HashMap<String,String[]>();   // the records screen asks every frame
    private Records(){}

    public static void store(Store s){synchronized(CACHE){store=s;CACHE.clear();}}
    public static String runKey(int run){return "run_"+run;}
    /** By the circuit's name, not its index: the list of circuits can grow or be reordered. */
    public static String lapKey(String circuit){return "lap_"+circuit;}

    /** The list with the value added in its place (smaller is better) and the best KEEP kept; NaN or <= 0 changes nothing. */
    public static String insert(String list,float value,String date){
        if(list==null)list="";
        if(Float.isNaN(value)||value<=0)return list;
        ArrayList<String> e=new ArrayList<String>();
        if(list.length()>0)for(String x:list.split(","))e.add(x);
        int at=0;while(at<e.size()&&value(e.get(at))<=value)at++;   // a tie keeps the older record first
        e.add(at,String.format(Locale.US,"%.3f@%s",value,date));
        StringBuilder b=new StringBuilder();
        for(int i=0;i<Math.min(KEEP,e.size());i++)b.append(i==0?"":",").append(e.get(i));
        return b.toString();
    }
    public static float value(String entry){try{return Float.parseFloat(entry.substring(0,entry.indexOf('@')));}catch(RuntimeException x){return Float.NaN;}}
    public static String date(String entry){int i=entry.indexOf('@');return i<0?"":entry.substring(i+1);}

    /** A kind's records, best first; empty when there are none (or no store). */
    public static String[] entries(String key){
        synchronized(CACHE){
            String[] e=CACHE.get(key);
            if(e==null){Store s=store;String l=s==null?null:s.get(key);e=l==null||l.length()==0?new String[0]:l.split(",");CACHE.put(key,e);}
            return e;
        }
    }
    public static float best(String key){String[] e=entries(key);return e.length==0?Float.NaN:value(e[0]);}
    /** A finished run or lap, seconds; saved only when it makes the list. */
    public static void add(String key,float seconds){
        synchronized(CACHE){
            Store s=store;if(s==null)return;
            String old=s.get(key);if(old==null)old="";
            String now=insert(old,seconds,new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new java.util.Date()));
            if(!now.equals(old)){s.put(key,now);CACHE.remove(key);}
        }
    }
    public static void clear(){synchronized(CACHE){Store s=store;if(s!=null)s.clear();CACHE.clear();}}
}
