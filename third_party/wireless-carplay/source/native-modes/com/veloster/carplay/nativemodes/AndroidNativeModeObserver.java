/* SPDX-License-Identifier: GPL-3.0-only. Exact 250902 read-only Binder contract. */
package com.veloster.carplay.nativemodes;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Construct/open on a worker. Registration and polling use no OEM setter.
 * GET_TASKS is needed for actual foreground confirmation on API17. The adapter
 * rejects unpinned jars/odex, unavailable tasks, service death and callbacks from
 * non-system UIDs. It provides no process-gap/native music restore guarantee. */
public final class AndroidNativeModeObserver implements Closeable,Runnable {
    private static final String SERVICE="com.hkmc.mode.IModeManager",CALLBACK="com.hkmc.mode.IModeChangeListener";
    private final ActivityManager activities;private final IBinder remote;private final NativeModeObserver observer;
    interface Threads {void start(Runnable run,String name);}
    private final Threads threads;
    private volatile boolean closed;private volatile IOException failure;
    private boolean started,starting,workerAlive,releaseAlive,registrationMayExist,linked;
    private final Binder callback=new Binder(){
        protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            if(code==IBinder.INTERFACE_TRANSACTION){if(reply!=null)reply.writeString(CALLBACK);return true;}
            if(code<1||code>9)return super.onTransact(code,data,reply,flags);
            if(Binder.getCallingUid()!=1000)throw new SecurityException("OEM mode callback UID");
            data.enforceInterface(CALLBACK);
            if(!closed){
                if(code==1){int mode=data.readInt(),running=data.readInt(),reason=data.readInt();long at=data.readLong();observer.request(mode,running,reason,at,SystemClock.uptimeMillis());}
                else if(code==2)observer.changed(data.readInt());
                else if(code==4||code==6||code==8)observer.cancel();
                else if(code==7)data.readInt();
            }
            return true; // OEM callback proxy is one-way and has no reply parcel.
        }
    };
    private final IBinder.DeathRecipient death=new IBinder.DeathRecipient(){public void binderDied(){failure=new IOException("OEM mode service died");closed=true;observer.cancel();synchronized(AndroidNativeModeObserver.this){registrationMayExist=false;linked=false;AndroidNativeModeObserver.this.notifyAll();}}};
    public static AndroidNativeModeObserver create(Context context,NativeModeObserver.Listener listener)throws IOException{
        pin("automotive.jar","dcbb3a23db582c5469d9d31f8f7e0a9cf7c9055ff1db25eafc98eb3e33ea2918");
        pin("automotive.odex","7558120aa847975fcb1c72cfc2ae3db5c20395b54a5b071232dcef3fc46845c8");
        pin("automotive-service.jar","42703fbbc68c64ce85987d4f9a82e3eaed7d0e038a123e79b4f1b440e93724e2");
        pin("automotive-service.odex","930e1de0cb57b945565e66d8856d1bccdebe36c6e44afd485c6894755ceb738a");
        try{
            IBinder remote=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"com.hkmc.micom.mode");
            if(remote==null||!SERVICE.equals(remote.getInterfaceDescriptor()))throw new IOException("OEM mode service unavailable");
            return new AndroidNativeModeObserver(context,remote,listener);
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException("OEM mode observer unavailable",e);}catch(LinkageError e){throw new IOException("OEM mode API unavailable",e);}
    }
    private AndroidNativeModeObserver(Context context,IBinder remote,NativeModeObserver.Listener listener)throws IOException{
        this(context,remote,listener,new Threads(){public void start(Runnable run,String name){Thread thread=new Thread(run,name);thread.setDaemon(true);thread.start();}});
    }
    AndroidNativeModeObserver(Context context,IBinder remote,NativeModeObserver.Listener listener,Threads threads)throws IOException{
        activities=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);if(activities==null)throw new IOException("Activity observer unavailable");
        this.remote=remote;this.threads=threads;observer=new NativeModeObserver(SystemClock.uptimeMillis(),listener);
    }
    /** Retain this handle before calling start: a failed registration may have
     * reached the server. close/retry + awaitStopped must drain that uncertainty. */
    public void start()throws IOException{
        synchronized(this){if(started||closed)throw new IOException("OEM observer already started/closed");started=true;starting=true;linked=true;}
        try{remote.linkToDeath(death,0);synchronized(this){if(closed)throw new IOException("OEM mode service died before registration");registrationMayExist=true;}registration(0x53);
            synchronized(this){if(closed)throw new IOException("OEM observer closed during registration");workerAlive=true;}
            threads.start(this,"vn-native-modes");
        }catch(Exception e){synchronized(this){workerAlive=false;closed=true;failure=new IOException("OEM mode registration failed",e);notifyAll();}throw failure;}
        catch(LinkageError e){synchronized(this){workerAlive=false;closed=true;failure=new IOException("OEM mode registration API failed",e);notifyAll();}throw failure;}
        finally{synchronized(this){starting=false;notifyAll();}}
    }
    public void run(){try{while(!closed){
        long generation=observer.generation();int current=integer(0x09);String pkg=string(0x34);String top=topPackage();
        if(!closed)observer.sample(generation,SystemClock.uptimeMillis(),current,pkg,top);
        synchronized(this){if(!closed)wait(100);}
    }}catch(InterruptedException e){Thread.currentThread().interrupt();if(!closed)failure=new IOException("OEM observer interrupted",e);}
      catch(Exception e){if(!closed)failure=new IOException("OEM observer read failed",e);}
      catch(LinkageError e){if(!closed)failure=new IOException("OEM observer API failed",e);}
      finally{closed=true;observer.cancel();releaseOnce();synchronized(this){workerAlive=false;notifyAll();}}}
    private String topPackage()throws IOException{
        List<ActivityManager.RunningTaskInfo> tasks=activities.getRunningTasks(1);
        if(tasks==null||tasks.isEmpty())return null;ComponentName top=tasks.get(0).topActivity;return top==null?null:top.getPackageName();
    }
    private void registration(int code)throws RemoteException,IOException{
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();try{data.writeInterfaceToken(SERVICE);data.writeStrongBinder(callback);if(!remote.transact(code,data,reply,0))throw new IOException("OEM registration rejected");reply.readException();}finally{reply.recycle();data.recycle();}
    }
    private int integer(int code)throws RemoteException,IOException{Parcel data=Parcel.obtain(),reply=Parcel.obtain();try{data.writeInterfaceToken(SERVICE);if(!remote.transact(code,data,reply,0))throw new IOException("OEM getter rejected");reply.readException();return reply.readInt();}finally{reply.recycle();data.recycle();}}
    private String string(int code)throws RemoteException,IOException{Parcel data=Parcel.obtain(),reply=Parcel.obtain();try{data.writeInterfaceToken(SERVICE);if(!remote.transact(code,data,reply,0))throw new IOException("OEM getter rejected");reply.readException();return reply.readString();}finally{reply.recycle();data.recycle();}}
    public IOException failure(){return failure;}
    public boolean isCurrent(NativeModeObserver.Selection selection){return !closed&&failure==null&&observer.isCurrent(selection,SystemClock.uptimeMillis())&&!closed&&failure==null;}
    public void close(){closed=true;observer.cancel();synchronized(this){notifyAll();if(starting||workerAlive||releaseAlive||(!registrationMayExist&&!linked))return;releaseAlive=true;
        try{threads.start(new Runnable(){public void run(){try{releaseOnce();}finally{synchronized(AndroidNativeModeObserver.this){releaseAlive=false;AndroidNativeModeObserver.this.notifyAll();}}}},"vn-native-mode-release");}
        catch(RuntimeException e){releaseAlive=false;failure=new IOException("OEM observer release worker failed",e);notifyAll();}
        catch(LinkageError e){releaseAlive=false;failure=new IOException("OEM observer release worker API failed",e);notifyAll();}
    }}
    private void releaseOnce(){
        boolean remove;synchronized(this){remove=registrationMayExist;}
        if(remove){try{registration(0x54);synchronized(this){registrationMayExist=false;}}
            catch(Exception e){failure=new IOException("OEM observer unregister unresolved; retry close",e);}
            catch(LinkageError e){failure=new IOException("OEM observer unregister API unresolved; retry close",e);}}
        boolean unlink;synchronized(this){unlink=linked;}
        if(unlink)try{remote.unlinkToDeath(death,0);synchronized(this){linked=false;}}catch(RuntimeException e){failure=new IOException("OEM observer death recipient release unresolved",e);}
        synchronized(this){notifyAll();}
    }
    public synchronized boolean awaitStopped(long millis)throws InterruptedException{
        if(millis<0)throw new IllegalArgumentException();long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        while(starting||workerAlive||releaseAlive||registrationMayExist||linked){long left=deadline-System.nanoTime();if(left<=0)return false;TimeUnit.NANOSECONDS.timedWait(this,left);}return true;
    }
    private static void pin(String name,String digest)throws IOException{try{MessageDigest hash=MessageDigest.getInstance("SHA-256");FileInputStream in=new FileInputStream("/system/framework/"+name);try{byte[] buffer=new byte[32768];for(int n;(n=in.read(buffer))!=-1;)hash.update(buffer,0,n);}finally{in.close();}StringBuilder hex=new StringBuilder();for(byte b:hash.digest())hex.append(String.format("%02x",b&255));if(!digest.equals(hex.toString()))throw new IOException("OEM source mismatch: "+name);}catch(IOException e){throw e;}catch(Exception e){throw new IOException("OEM pin unavailable",e);}}
}
