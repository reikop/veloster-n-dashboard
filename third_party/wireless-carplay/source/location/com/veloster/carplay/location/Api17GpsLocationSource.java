/* SPDX-License-Identifier: GPL-3.0-only. Android API17 raw GPS NMEA adapter. */
package com.veloster.carplay.location;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Never enables GPS, changes system settings, uses a last-known fix, or invents measured fields.
 * Prefer raw NMEA; when the HAL exposes only GPS Location callbacks, encode their
 * actual fields after a short raw-NMEA grace period. Unknown fields stay empty.
 * One registration owns both listeners. Start/close execute on its own API17 Looper;
 * timed-out or failed removal remains owned for explicit cleanup retry. */
public final class Api17GpsLocationSource {
    private Api17GpsLocationSource(){}
    public static LocationSource create(final Context context){
        if(context==null)throw new NullPointerException();
        final LocationManager manager=(LocationManager)context.getSystemService(Context.LOCATION_SERVICE);
        return new NmeaLocationSource(new NmeaLocationSource.Port(){
            public boolean available(){
                try{return manager!=null&&context.checkCallingOrSelfPermission("android.permission.ACCESS_FINE_LOCATION")==PackageManager.PERMISSION_GRANTED
                    &&manager.getAllProviders().contains(LocationManager.GPS_PROVIDER)&&manager.isProviderEnabled(LocationManager.GPS_PROVIDER);}
                catch(RuntimeException e){return false;}catch(LinkageError e){return false;}
            }
            public NmeaLocationSource.Registration create(NmeaLocationSource.Listener listener){return new OwnedRegistration(manager,listener);}
        },new NmeaLocationSource.Clock(){public long elapsedMillis(){return SystemClock.elapsedRealtime();}public long wallMillis(){return System.currentTimeMillis();}});
    }
    private static final class Operation implements Runnable {
        final CountDownLatch done=new CountDownLatch(1);final Action action;volatile IOException failure;
        Operation(Action action){this.action=action;}
        public void run(){try{action.run();}catch(IOException e){failure=e;}catch(RuntimeException e){failure=new IOException("GPS operation failed",e);}
            catch(LinkageError e){failure=new IOException("GPS API unavailable",e);}finally{done.countDown();}}
        void await()throws IOException{
            try{if(!done.await(3000,TimeUnit.MILLISECONDS))throw new IOException("GPS operation pending");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("GPS operation interrupted",e);}
            if(failure!=null)throw failure;
        }
    }
    private interface Action {void run()throws IOException;}
    private static final class OwnedRegistration implements NmeaLocationSource.Registration {
        final LocationManager manager;final GpsStatus.NmeaListener nmea;final LocationListener fixes;
        HandlerThread thread;Handler handler;Operation release;boolean started;
        // Only the owned Looper changes the subscription flags.
        boolean nmeaAttempted,fixesAttempted;volatile boolean released;
        final long[] lastRaw={-1,-1},lastRawTimestamp={0,0};long fixGeneration;boolean accepting=true;Runnable pendingFix;
        OwnedRegistration(LocationManager manager,final NmeaLocationSource.Listener listener){
            this.manager=manager;nmea=new GpsStatus.NmeaListener(){public void onNmeaReceived(long timestamp,String sentence){
                if(!accepting)return;int type=NmeaLocationSource.type(sentence);long age=System.currentTimeMillis()-timestamp;
                if(type!=0&&timestamp>0&&age>=0&&age<=5000){int i=type==1?0:1;
                    if(timestamp>lastRawTimestamp[i]){lastRawTimestamp[i]=timestamp;lastRaw[i]=SystemClock.elapsedRealtime();}}
                listener.sentence(timestamp,sentence);}};
            fixes=new LocationListener(){public void onLocationChanged(Location value){
                if(!accepting||value==null||!LocationManager.GPS_PROVIDER.equals(value.getProvider()))return;
                final Location fix=new Location(value);final long generation=++fixGeneration,received=SystemClock.elapsedRealtime();
                if(pendingFix!=null)handler.removeCallbacks(pendingFix);
                pendingFix=new Runnable(){public void run(){
                    if(!accepting||generation!=fixGeneration)return;pendingFix=null;
                    String[] sentences=GpsFixNmea.encode(fix.getTime(),fix.getLatitude(),fix.getLongitude(),fix.hasSpeed()?fix.getSpeed():Double.NaN,fix.hasBearing()?fix.getBearing():Double.NaN);
                    for(String sentence:sentences){int type=NmeaLocationSource.type(sentence);int i=type==LocationSource.GGA?0:1;
                        if(type!=0&&lastRaw[i]<received)listener.sentence(fix.getTime(),sentence);}
                }};handler.postDelayed(pendingFix,250);
            }public void onStatusChanged(String provider,int status,Bundle extras){}
                public void onProviderEnabled(String provider){}public void onProviderDisabled(String provider){}};
        }
        public synchronized void start()throws IOException{
            if(started||released)throw new IOException("GPS registration already used");started=true;
            thread=new HandlerThread("vn-carplay-gps");thread.start();handler=new Handler(thread.getLooper());
            Operation operation=new Operation(new Action(){public void run()throws IOException{
                if(manager==null)throw new IOException("GPS service unavailable");
                nmeaAttempted=true;if(!manager.addNmeaListener(nmea))throw new IOException("GPS NMEA registration rejected");
                fixesAttempted=true;manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,fixes,thread.getLooper());
            }});
            if(!handler.post(operation))throw new IOException("GPS Looper rejected start");operation.await();
        }
        public synchronized void close()throws IOException{
            if(released){drain();return;}if(!started){released=true;return;}
            if(handler==null){if(thread!=null)thread.quit();released=true;return;}
            if(release==null||release.done.getCount()==0&&release.failure!=null){
                release=new Operation(new Action(){public void run()throws IOException{
                    accepting=false;++fixGeneration;
                    if(pendingFix!=null){handler.removeCallbacks(pendingFix);pendingFix=null;}
                    IOException failure=null;
                    if(nmeaAttempted)try{manager.removeNmeaListener(nmea);nmeaAttempted=false;}catch(RuntimeException e){failure=new IOException("GPS NMEA removal pending",e);}
                    if(fixesAttempted)try{manager.removeUpdates(fixes);fixesAttempted=false;}catch(RuntimeException e){failure=new IOException("GPS location removal pending",e);}
                    if(failure!=null)throw failure;released=true;thread.quit();
                }});
                if(!handler.post(release)){release=null;throw new IOException("GPS Looper rejected cleanup");}
            }
            release.await();
            drain();
        }
        private void drain()throws IOException{
            if(thread==null)return;
            try{thread.join(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("GPS thread drain interrupted",e);}
            if(thread.isAlive())throw new IOException("GPS thread drain pending");
        }
    }
}
