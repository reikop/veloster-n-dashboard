package com.veloster.vehicle;

import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Short OEM seek/track key pair, routed by ModeManager to the active media service. */
public final class MediaControl {
    private static int mode(Context context)throws Exception{
        Class<?> type=Class.forName("com.hkmc.mode.ModeManager");
        Object manager=type.getConstructor(Context.class).newInstance(context);
        return ((Integer)type.getMethod("getCurrentMode").invoke(manager)).intValue();
    }
    /** Native transport requests. Success means dispatch only; displayed state comes from OEM updates. */
    public static boolean toggle(Context context,int displayedMode,int playState)throws Exception{
        if(mode(context)!=displayedMode)return false;
        if(displayedMode==0x31||displayedMode==0x34){
            if(playState!=0&&playState!=1)return false;
            Intent request=new Intent("android.intent.action.VIT_NOTI_AV_PLAY_CONTROL");
            request.setPackage(displayedMode==0x31?"com.daudio.av.app.usbmusic":"com.daudio.av.app.ipodmusic");
            request.putExtra("Value1",displayedMode==0x31?9:12);request.putExtra("Value2",playState==1?0:1);
            context.sendBroadcast(request);return true;
        }
        if(displayedMode==0x36)return bound(context,displayedMode,"com.android.bluetooth",
            "com.broadcom.bt.service.avrcp.AvrcpControllerService","com.broadcom.bt.avrcp.IBluetoothAvrcpController",27,new int[]{3,0},true);
        if(displayedMode==0x74){
            if(playState!=0&&playState!=1)return false;
            return bound(context,displayedMode,"com.google.android.projection.sink",
                "com.google.android.projection.service.GalReceiverService","com.google.android.projection.service.IGalReceiverService",6,new int[]{playState==1?127:126},false);
        }
        return false;
    }
    private static boolean bound(Context context,int displayedMode,String pkg,String cls,final String descriptor,int transaction,int[] arguments,boolean intResult)throws Exception{
        final CountDownLatch ready=new CountDownLatch(1);
        final AtomicReference<IBinder> service=new AtomicReference<IBinder>();
        ServiceConnection connection=new ServiceConnection(){
            public void onServiceConnected(ComponentName name,IBinder binder){service.set(binder);ready.countDown();}
            public void onServiceDisconnected(ComponentName name){service.set(null);ready.countDown();}
        };
        // No AUTO_CREATE: attach only to the already running media service.
        if(!context.bindService(new Intent(descriptor).setClassName(pkg,cls),connection,0))return false;
        try{
            if(!ready.await(1500,TimeUnit.MILLISECONDS)||mode(context)!=displayedMode)return false;
            IBinder binder=service.get();if(binder==null||!descriptor.equals(binder.getInterfaceDescriptor()))return false;
            Parcel request=Parcel.obtain(),reply=Parcel.obtain();
            try{
                request.writeInterfaceToken(descriptor);for(int value:arguments)request.writeInt(value);
                if(!binder.transact(transaction,request,reply,0))return false;
                reply.readException();return !intResult||reply.readInt()==0;
            }finally{reply.recycle();request.recycle();}
        }finally{context.unbindService(connection);}
    }
    public static boolean step(Context context,boolean next,int displayedMode)throws Exception{
        Class<?> type=Class.forName("com.hkmc.mode.ModeManager");
        Object manager=type.getConstructor(Context.class).newInstance(context);
        int mode=((Integer)type.getMethod("getCurrentMode").invoke(manager)).intValue();
        if(mode!=displayedMode)return false;
        if(mode!=0x31 && mode!=0x34 && mode!=0x36 && mode!=0x73 && mode!=0x74)return false;
        Method send=type.getMethod("handleKeyForService",Integer.TYPE,Integer.TYPE,Integer.TYPE,
            Integer.TYPE,Integer.TYPE,Boolean.TYPE);
        int key=next?0xde:0xdf;
        try{send.invoke(manager,Integer.valueOf(0),Integer.valueOf(key),Integer.valueOf(0),Integer.valueOf(0),Integer.valueOf(0),Boolean.FALSE);}
        finally{send.invoke(manager,Integer.valueOf(1),Integer.valueOf(key),Integer.valueOf(0),Integer.valueOf(0),Integer.valueOf(0),Boolean.FALSE);}
        android.util.Log.i("VehicleDash","Track "+(next?"next":"previous")+" requested for mode="+mode);
        return true;
    }
    private MediaControl(){}
}
