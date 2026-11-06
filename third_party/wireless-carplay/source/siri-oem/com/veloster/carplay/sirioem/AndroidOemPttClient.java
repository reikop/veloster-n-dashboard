// SPDX-License-Identifier: GPL-3.0-only
package com.veloster.carplay.sirioem;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Locale;

/** Optional exact-session OEM PTT lease. All callbacks run on Binder threads. */
public final class AndroidOemPttClient {
    public interface Listener {boolean down();void up();void retired();}
    private AndroidOemPttClient(){}
    public static final String SERVICE="com.veloster.carplay.oem_ptt",ABI="com.veloster.carplay.OemPtt.v1",CALLBACK=ABI+".callback";
    public static Lease open(Context context,String pinnedAutomotiveJarSha256,String sessionNonce,Listener listener)throws IOException{
        if(context==null||!"com.veloster.carplayhub".equals(context.getPackageName())||listener==null||sessionNonce==null||!sessionNonce.matches("[0-9a-fA-F-]{36}"))throw new IOException("PTT client identity");
        if(pinnedAutomotiveJarSha256==null||!pinnedAutomotiveJarSha256.matches("[0-9a-f]{64}"))throw new IOException("PTT OEM JAR pin required");
        String boot=readBoot(),actual=sha256("/system/framework/automotive-service.jar");
        if(!pinnedAutomotiveJarSha256.equals(actual))throw new IOException("PTT OEM JAR mismatch");
        try{IBinder remote=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,SERVICE);
            if(remote==null||!ABI.equals(remote.getInterfaceDescriptor()))throw new IOException("PTT OEM service absent");
            Lease lease=new Lease(remote,boot,listener);remote.linkToDeath(lease,0);
            Parcel request=Parcel.obtain(),reply=Parcel.obtain();try{request.writeInterfaceToken(ABI);request.writeString(sessionNonce);request.writeStrongBinder(lease.callback);
                if(!remote.transact(1,request,reply,0))throw new IOException("PTT claim rejected");reply.readException();String returnedBoot=reply.readString(),epoch=reply.readString(),token=reply.readString();long ttl=reply.readLong();
                if(!boot.equals(returnedBoot)||epoch==null||!epoch.matches("[0-9a-fA-F-]{36}")||token==null||!token.matches("[0-9a-f]{48}")||ttl!=PttRouteCore.LEASE_MILLIS)throw new IOException("PTT claim identity mismatch");
                lease.epoch=epoch;lease.token=token;return lease;
            }catch(Exception failure){lease.localRetire();remote.unlinkToDeath(lease,0);if(failure instanceof IOException)throw (IOException)failure;throw new IOException("PTT claim failed",failure);}finally{reply.recycle();request.recycle();}
        }catch(IOException error){throw error;}catch(Exception error){throw new IOException("PTT OEM binding failed",error);}catch(LinkageError error){throw new IOException("PTT OEM API unavailable",error);}
    }
    private static String readBoot()throws IOException{FileInputStream in=new FileInputStream("/proc/sys/kernel/random/boot_id");try{byte[] bytes=new byte[128];int n=in.read(bytes);if(n<36||n>=bytes.length)throw new IOException("PTT boot ID");String boot=new String(bytes,0,n,"US-ASCII").trim();if(!boot.matches("[0-9a-fA-F-]{36}"))throw new IOException("PTT boot ID");return boot;}finally{in.close();}}
    private static String sha256(String path)throws IOException{try{MessageDigest hash=MessageDigest.getInstance("SHA-256");FileInputStream in=new FileInputStream(path);try{byte[] bytes=new byte[32768];for(int n;(n=in.read(bytes))!=-1;)hash.update(bytes,0,n);}finally{in.close();}StringBuilder hex=new StringBuilder();for(byte b:hash.digest())hex.append(String.format(Locale.US,"%02x",b&255));return hex.toString();}catch(IOException error){throw error;}catch(Exception error){throw new IOException("PTT OEM hash unavailable",error);}}
    public static final class Lease implements Closeable,IBinder.DeathRecipient {
        private final IBinder remote;private final String boot;private final Listener listener;
        private volatile String epoch,token;private volatile boolean closed,pressed;
        private long lastSequence;
        private final Binder callback=new Binder(){protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            if(code<1||code>3)return super.onTransact(code,data,reply,flags);
            data.enforceInterface(CALLBACK);String sourceBoot=data.readString(),sourceEpoch=data.readString(),sourceToken=data.readString();long sequence=data.readLong();
            if(Binder.getCallingUid()!=1000||!boot.equals(sourceBoot)||epoch==null||!epoch.equals(sourceEpoch)||token==null||!token.equals(sourceToken))throw new SecurityException("PTT source mismatch");
            if(code==1){boolean eligible; synchronized(Lease.this){eligible=!closed&&sequence>lastSequence;if(eligible)lastSequence=sequence;}
                boolean accepted=eligible&&listener.down();boolean late;
                synchronized(Lease.this){late=closed;if(accepted&&!late)pressed=true;}
                if(accepted&&late)listener.retired();if(reply!=null){reply.writeNoException();reply.writeInt(accepted?1:0);}return true;}
            if(code==2){boolean deliver=false;synchronized(Lease.this){if(sequence>lastSequence){lastSequence=sequence;deliver=pressed;pressed=false;}}if(deliver)listener.up();if(reply!=null)reply.writeNoException();return true;}
            localRetire();if(reply!=null)reply.writeNoException();return true;
        }};
        private Lease(IBinder remote,String boot,Listener listener){this.remote=remote;this.boot=boot;this.listener=listener;}
        public boolean alive(){return !closed&&remote.isBinderAlive()&&token!=null;}
        /** Renew on a session worker at least every 500 ms. false disables new DOWN, not a held UP. */
        public boolean renew(boolean downEnabled){if(!alive())return false;Parcel data=Parcel.obtain(),reply=Parcel.obtain();try{
            data.writeInterfaceToken(ABI);data.writeString(token);data.writeInt(downEnabled?1:0);
            if(!remote.transact(2,data,reply,0))throw new IOException("PTT renewal rejected");reply.readException();boolean ok=reply.readInt()!=0;if(!ok)localRetire();return ok;
        }catch(Exception error){localRetire();return false;}finally{reply.recycle();data.recycle();}}
        private void localRetire(){boolean notify;synchronized(this){notify=!closed;closed=true;}if(notify)listener.retired();}
        public void binderDied(){localRetire();}
        public void close(){String oldToken=token;localRetire();remote.unlinkToDeath(this,0);if(oldToken==null)return;
            Parcel data=Parcel.obtain(),reply=Parcel.obtain();try{data.writeInterfaceToken(ABI);data.writeString(oldToken);if(remote.transact(3,data,reply,0))reply.readException();}catch(Exception ignored){}finally{reply.recycle();data.recycle();}}
    }
}
