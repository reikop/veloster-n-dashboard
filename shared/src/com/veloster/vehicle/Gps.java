package com.veloster.vehicle;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;

/**
 * The head unit's GPS into GpsFix through LocationManager "gps" (the firmware's gps.tcc893x.so HAL over /dev/ttyGPS0, a Trimble
 * receiver with dead reckoning), for the taxi meter's fare area. Runs while the dashboard is attached. Once a minute it
 * logs how many fixes came, their accuracy and the fare table they fall in (시·도 level, never the position), so a
 * [진단 기록] shows whether the GPS reaches the app at all on a given car.
 */
final class Gps implements LocationListener {
    private static Gps running;
    private LocationManager lm;
    private int fixes;
    private long logAt,startAt;private boolean extrasLogged;

    static void start(Context c){
        if(running!=null)return;
        // The taxi areas (23,000 points) and circuits decode on first use, a few hundred ms on the head unit: do it now, off
        // the UI thread, rather than at the first fix inside a frame. Their decode is synchronized, so a frame that needs
        // them sooner waits for this one instead of decoding twice.
        new Thread("gps-areas"){public void run(){TaxiZones.at(0,0);}}.start();
        Gps g=new Gps();g.startAt=g.logAt=SystemClock.elapsedRealtime();
        try{
            g.lm=(LocationManager)c.getSystemService(Context.LOCATION_SERVICE);
            g.lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,g,Looper.getMainLooper());
            running=g;
            android.util.Log.i("VehicleDash","GPS requested (gps provider enabled="+g.lm.isProviderEnabled(LocationManager.GPS_PROVIDER)+")");
        }catch(RuntimeException e){android.util.Log.w("VehicleDash","GPS unavailable: "+e);}   // no permission in a host app, no provider
    }
    static void stop(){
        Gps g=running;running=null;
        if(g!=null)try{g.lm.removeUpdates(g);}catch(RuntimeException e){/* already gone */}
    }
    @Override public void onLocationChanged(Location l){
        long now=SystemClock.elapsedRealtime();
        if(!GpsFix.any())android.util.Log.i("VehicleDash","GPS first fix after "+(now-startAt)/1000+" s");
        GpsFix.set(l.getLatitude(),l.getLongitude(),l.hasAccuracy()?l.getAccuracy():Float.NaN,l.hasSpeed()?l.getSpeed()*3.6f:Float.NaN);fixes++;
        if(!extrasLogged){extrasLogged=true;logExtras(l);}
        if(now-logAt>=60000){
            int z=TaxiZones.ready()?TaxiZones.at(GpsFix.lat,GpsFix.lon):-1;   // not decoded again just for this line while the dashboard is away
            android.util.Log.i("VehicleDash","GPS "+fixes+" fixes in "+(now-logAt)/1000+" s, accuracy "+Math.round(GpsFix.accuracy)+" m, speed "+(Float.isNaN(GpsFix.speedKmh)?"none":Math.round(GpsFix.speedKmh)+" km/h")+", fare table "+(z<0?"none":TaxiZones.tariff(z)));
            fixes=0;logAt=now;
        }
    }
    /** Once: the firmware's own Location getters (Hyundai adds GNSS / dead-reckoning fields), so a [진단 기록] shows whether a
     *  pure satellite speed exists beside getSpeed (the Trimble receiver may blend in the wheel speed). Values only, no position. */
    private static void logExtras(Location l){
        StringBuilder b=new StringBuilder();
        for(java.lang.reflect.Method m:l.getClass().getMethods()){String n=m.getName().toLowerCase(java.util.Locale.US);
            if(m.getParameterTypes().length!=0||!(n.contains("gnss")||n.contains("dr")||n.contains("gpsstatus")))continue;
            try{b.append(m.getName()).append('=').append(m.invoke(l)).append(' ');}catch(Exception e){b.append(m.getName()).append("=? ");}}
        android.util.Log.i("VehicleDash","GPS fields: speed "+(l.hasSpeed()?l.getSpeed()*3.6f:-1)+" km/h, "+b.toString().trim());
    }
    @Override public void onStatusChanged(String provider,int status,Bundle extras){}
    @Override public void onProviderEnabled(String provider){android.util.Log.i("VehicleDash","GPS provider enabled");}
    @Override public void onProviderDisabled(String provider){android.util.Log.i("VehicleDash","GPS provider disabled");}
}
