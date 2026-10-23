package com.veloster.dashboard;

import android.content.Context;
import android.os.Handler;
import com.hkmc.bcm.BCMManager;
import com.hkmc.bcm.BCMPowerListener;
import com.hkmc.micom.NModeListener;
import com.hkmc.micom.NModeManager;
import com.hkmc.micom.VehicleInfoListener;
import com.hkmc.micom.VehicleInfoManager;
import com.hkmc.pgs.PgsManager;
import com.hkmc.pgs.PgsStatusListener;
import com.veloster.vehicle.VehicleDash;

/** Passive framework subscriptions only. No drive-mode setters, stream commands or OEM app IPC. */
final class VehicleDataClient implements Runnable {
    private final Context context;
    private final Handler handler=new Handler();
    private volatile boolean active,ignition;
    private volatile int generation;
    private volatile HscanDataClient hscan;
    private NModeManager nmode;
    private VehicleInfoManager vehicle;
    private BCMManager power;
    private PgsManager rear;
    private NModeListener nlistener;
    private VehicleInfoListener vlistener;
    private BCMPowerListener plistener;
    private PgsStatusListener rlistener;
    VehicleDataClient(Context context){this.context=context;}
    synchronized void start(){if(active)return;active=true;generation++;ignition=false;VehicleDash.ignition(false);run();}
    private boolean accepts(int token){return active&&token==generation&&ignition;}
    public synchronized void run(){
        if(!active)return;
        final int token=generation;
        if(power==null)try{
            power=(BCMManager)com.hkmc.VehicleInfoManager.getInstance(context).getService(com.hkmc.VehicleInfoManager.BCM_SERVICE);
            if(power==null)throw new IllegalStateException("BCM service unavailable");
            ignition=power.getIGNStatus();
            plistener=new BCMPowerListener(){public void onIgnitionChanged(boolean on){
                synchronized(VehicleDataClient.this){
                if(!active||token!=generation)return;
                if(ignition!=on){
                    ignition=on;
                    HscanDataClient current=hscan;
                    if(current!=null)current.stop();
                    VehicleDash.ignition(on);
                    if(on&&current!=null)current.start();
                }
                }
            }};
            power.addPowerListener(plistener);
        }catch(RuntimeException e){power=null;log("power",e);}catch(LinkageError e){power=null;log("power",e);}
        if(nmode==null)try{
            nmode=new NModeManager(context);
            nlistener=new NModeListener(){
                public void onPowerTorqueTurboBoostReceived(byte[] b){if(accepts(token)){VehicleDash.rpm(b);VehicleDash.calculateObserved();}}
                public void onGForceDataReceived(byte[] b){if(accepts(token)){VehicleDash.gforce(b);VehicleDash.calculateObserved();}}
                public void onOilCoolantTemperature(byte[] b){if(accepts(token))VehicleDash.temperatures(b);}
                public void onSpeedTempUnitTempReceived(byte[] b){if(accepts(token))VehicleDash.units(b);}
                public void onNAddInfoReceived(byte[] b){if(accepts(token))VehicleDash.additional(b);}
                // Some OEM app subclasses declare this; captured250902 base/proxy lacks dispatch.
                public void onNModeZeroSpeedChanged(byte[] b){if(accepts(token))VehicleDash.wheels(b);}
            };
            nmode.registerNModeListener(nlistener);
        }catch(RuntimeException e){nmode=null;log("NMode",e);}catch(LinkageError e){nmode=null;log("NMode",e);}
        if(vehicle==null)try{
            vehicle=new VehicleInfoManager(context);
            vlistener=new VehicleInfoListener(){public void onVehicleSpeedChanged(int speed){if(accepts(token))VehicleDash.speed(speed);}};
            vehicle.addListener(vlistener);
        }catch(RuntimeException e){vehicle=null;log("speed",e);}catch(LinkageError e){vehicle=null;log("speed",e);}
        if(rear==null)try{
            rear=new PgsManager(context);
            rlistener=new PgsStatusListener(){public void onRVMViewStatusChanged(int view){if(accepts(token))VehicleDash.rearView(view);}};
            rear.registerPgsStatusListener(rlistener);
        }catch(RuntimeException e){rear=null;log("rear view",e);}catch(LinkageError e){rear=null;log("rear view",e);}
        if(power==null||nmode==null||vehicle==null)handler.postDelayed(this,2000);
        if(hscan==null)try{
            hscan=new HscanDataClient(new HscanDataClient.Gate(){public boolean accepts(){return VehicleDataClient.this.accepts(token);}});
        }catch(RuntimeException e){hscan=null;log("HSCAN",e);}catch(LinkageError e){hscan=null;log("HSCAN",e);}
        if(hscan!=null&&ignition)hscan.start();
    }
    synchronized void stop(){
        if(!active)return;active=false;generation++;handler.removeCallbacks(this);
        if(hscan!=null){hscan.stop();hscan=null;}
        try{if(nmode!=null)nmode.unregisterNModeListener(nlistener);}catch(RuntimeException e){log("NMode stop",e);}catch(LinkageError e){log("NMode stop",e);}
        try{if(vehicle!=null)vehicle.removeListener(vlistener);}catch(RuntimeException e){log("speed stop",e);}catch(LinkageError e){log("speed stop",e);}
        try{if(power!=null)power.removePowerListener(plistener);}catch(RuntimeException e){log("power stop",e);}catch(LinkageError e){log("power stop",e);}
        try{if(rear!=null)rear.unregisterPgsStatusListener(rlistener);}catch(RuntimeException e){log("rear view stop",e);}catch(LinkageError e){log("rear view stop",e);}
        nmode=null;vehicle=null;power=null;nlistener=null;vlistener=null;plistener=null;
        rear=null;rlistener=null;
    }
    private static void log(String source,Throwable error){android.util.Log.e("NDashboard","Subscription unavailable: "+source,error);}
}
