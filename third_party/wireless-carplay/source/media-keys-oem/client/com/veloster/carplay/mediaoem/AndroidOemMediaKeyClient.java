/* SPDX-License-Identifier: GPL-3.0-only. Exact-JAR-pinned optional Binder client. */
package com.veloster.carplay.mediaoem;

import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import com.veloster.carplay.mediakeys.ReceiverHardwareConsumerSource;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.NoSuchElementException;

/** Never opens a claim against an unpinned OEM JAR or a stale boot epoch. */
public final class AndroidOemMediaKeyClient implements ReceiverHardwareConsumerSource.Provider {
    private static final String SERVICE="com.veloster.carplay.oem_media_keys",ABI="com.veloster.carplay.OemMediaKeys.v1",CALLBACK=ABI+".callback";
    private final String pinnedJar;
    public AndroidOemMediaKeyClient(String pinnedAutomotiveJarSha256)throws IOException{
        if(pinnedAutomotiveJarSha256==null||!pinnedAutomotiveJarSha256.matches("[0-9a-f]{64}"))throw new IOException("OEM media JAR pin required");
        pinnedJar=pinnedAutomotiveJarSha256;
    }
    /** Read-only pre-declaration probe. Call on a worker; never hash or transact on the UI thread. */
    public boolean available(){
        if(Looper.myLooper()==Looper.getMainLooper())return false;
        try{
            String boot=readBoot();if(!pinnedJar.equals(sha256("/system/framework/automotive-service.jar")))return false;
            IBinder remote=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,SERVICE);
            if(remote==null||!ABI.equals(remote.getInterfaceDescriptor()))return false;
            Parcel data=Parcel.obtain(),reply=Parcel.obtain();try{
                data.writeInterfaceToken(ABI);
                if(!remote.transact(4,data,reply,0))return false;reply.readException();
                String sourceBoot=reply.readString(),sourceEpoch=reply.readString();int sourceUid=reply.readInt();
                return boot.equals(sourceBoot)&&sourceEpoch!=null&&sourceEpoch.matches("[0-9a-fA-F-]{36}")&&sourceUid==1000;
            }finally{reply.recycle();data.recycle();}
        }catch(Exception unavailable){return false;}catch(LinkageError unavailable){return false;}
    }
    public ReceiverHardwareConsumerSource.Lease open(String nonce,ReceiverHardwareConsumerSource.Listener listener)throws IOException{
        if(nonce==null||!nonce.matches("[0-9a-fA-F-]{36}")||listener==null)throw new IOException("OEM media claim shape");
        String boot=readBoot();if(!pinnedJar.equals(sha256("/system/framework/automotive-service.jar")))throw new IOException("OEM media JAR mismatch");
        try{
            IBinder remote=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,SERVICE);
            if(remote==null||!ABI.equals(remote.getInterfaceDescriptor()))throw new IOException("OEM media service absent");
            Lease lease=new Lease(remote,boot,listener);remote.linkToDeath(lease,0);
            Parcel data=Parcel.obtain(),reply=Parcel.obtain();try{
                data.writeInterfaceToken(ABI);data.writeString(nonce);data.writeStrongBinder(lease.callback);
                if(!remote.transact(1,data,reply,0))throw new IOException("OEM media claim rejected");reply.readException();
                String sourceBoot=reply.readString(),epoch=reply.readString(),token=reply.readString();long ttl=reply.readLong();
                if(!boot.equals(sourceBoot)||epoch==null||!epoch.matches("[0-9a-fA-F-]{36}")||token==null||!token.matches("[0-9a-f]{48}")||ttl!=750)
                    throw new IOException("OEM media claim identity mismatch");
                lease.epoch=epoch;lease.token=token;return lease;
            }catch(Exception failure){lease.retire();remote.unlinkToDeath(lease,0);if(failure instanceof IOException)throw (IOException)failure;throw new IOException("OEM media claim failed",failure);}finally{reply.recycle();data.recycle();}
        }catch(IOException error){throw error;}catch(Exception error){throw new IOException("OEM media binding failed",error);}catch(LinkageError error){throw new IOException("OEM media API unavailable",error);}
    }
    private static String readBoot()throws IOException{FileInputStream in=new FileInputStream("/proc/sys/kernel/random/boot_id");try{byte[] b=new byte[128];int n=in.read(b);if(n<36||n>=b.length)throw new IOException("OEM media boot ID");String boot=new String(b,0,n,"US-ASCII").trim();if(!boot.matches("[0-9a-fA-F-]{36}"))throw new IOException("OEM media boot ID");return boot;}finally{in.close();}}
    private static String sha256(String path)throws IOException{try{MessageDigest h=MessageDigest.getInstance("SHA-256");FileInputStream in=new FileInputStream(path);try{byte[] b=new byte[32768];for(int n;(n=in.read(b))!=-1;)h.update(b,0,n);}finally{in.close();}StringBuilder out=new StringBuilder();for(byte b:h.digest())out.append(String.format(Locale.US,"%02x",b&255));return out.toString();}catch(IOException error){throw error;}catch(Exception error){throw new IOException("OEM media hash unavailable",error);}}
    private static int code(ReceiverHardwareConsumerSource.Route route){return route==ReceiverHardwareConsumerSource.Route.CLAIMED?1:route==ReceiverHardwareConsumerSource.Route.QUARANTINED?2:0;}
    public static final class Lease implements ReceiverHardwareConsumerSource.Lease,IBinder.DeathRecipient {
        private final IBinder remote;private final String boot;private final ReceiverHardwareConsumerSource.Listener listener;
        private volatile String epoch,token;private boolean closed,releaseStarted,releaseDone;private IOException releaseFailure;
        private final Binder callback=new Binder(){protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            if(code<1||code>3)return super.onTransact(code,data,reply,flags);
            data.enforceInterface(CALLBACK);String sourceBoot=data.readString(),sourceEpoch=data.readString(),sourceToken=data.readString();int usage=data.readInt();
            if(Binder.getCallingUid()!=1000||!boot.equals(sourceBoot)||epoch==null||!epoch.equals(sourceEpoch)||token==null||!token.equals(sourceToken))throw new SecurityException("OEM media source mismatch");
            if(code==3){retire();if(reply!=null)reply.writeNoException();return true;}
            boolean active;synchronized(Lease.this){active=!closed;}
            ReceiverHardwareConsumerSource.Route result=ReceiverHardwareConsumerSource.Route.QUARANTINED;
            if(active){try{result=code==1?listener.down(usage):listener.up(usage);}catch(RuntimeException uncertain){result=ReceiverHardwareConsumerSource.Route.QUARANTINED;}catch(LinkageError uncertain){result=ReceiverHardwareConsumerSource.Route.QUARANTINED;}}
            boolean late;synchronized(Lease.this){late=closed;}
            if(late){listener.retired();result=ReceiverHardwareConsumerSource.Route.QUARANTINED;}
            if(reply!=null){reply.writeNoException();reply.writeInt(code(result));}return true;
        }};
        private Lease(IBinder remote,String boot,ReceiverHardwareConsumerSource.Listener listener){this.remote=remote;this.boot=boot;this.listener=listener;}
        private void retire(){boolean send;synchronized(this){send=!closed;closed=true;}if(send)listener.retired();}
        public void binderDied(){retire();}
        public void renew(boolean enabled)throws IOException{
            String current=token;synchronized(this){if(closed||current==null)throw new IOException("OEM media lease closed");}
            Parcel data=Parcel.obtain(),reply=Parcel.obtain();try{
                data.writeInterfaceToken(ABI);data.writeString(current);data.writeInt(enabled?1:0);
                if(!remote.transact(2,data,reply,0))throw new IOException("OEM media renew rejected");reply.readException();
                if(reply.readInt()!=1)throw new IOException("OEM media lease stale");
            }catch(Exception failure){retire();if(failure instanceof IOException)throw (IOException)failure;throw new IOException("OEM media renew failed",failure);}finally{reply.recycle();data.recycle();}
        }
        public void close()throws IOException{
            String old;synchronized(this){
                if(releaseStarted){if(!releaseDone)throw new IOException("OEM media release pending");
                    if(releaseFailure!=null)throw releaseFailure;return;}
                releaseStarted=true;old=token;
            }
            IOException failed=null;boolean acknowledged=false;Parcel data=null,reply=null;try{
                retire();
                try{remote.unlinkToDeath(this,0);}catch(NoSuchElementException alreadyRemoved){}
                if(old!=null){
                    data=Parcel.obtain();reply=Parcel.obtain();
                    data.writeInterfaceToken(ABI);data.writeString(old);
                    if(!remote.transact(3,data,reply,0))throw new IOException("OEM media release rejected");reply.readException();
                }
                acknowledged=true;
            }catch(Exception failure){failed=failure instanceof IOException?(IOException)failure:new IOException("OEM media release failed",failure);}
             catch(LinkageError failure){failed=new IOException("OEM media release API unavailable",failure);}
            finally{
                try{if(reply!=null)reply.recycle();if(data!=null)data.recycle();}
                catch(RuntimeException failure){if(failed==null)failed=new IOException("OEM media release cleanup failed",failure);}
                catch(LinkageError failure){if(failed==null)failed=new IOException("OEM media release cleanup API unavailable",failure);}
                synchronized(this){if(!acknowledged&&failed==null)failed=new IOException("OEM media release not confirmed");
                    releaseFailure=failed;releaseDone=true;}
            }
            if(failed!=null)throw failed;
        }
    }
}
