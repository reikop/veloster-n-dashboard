/* SPDX-License-Identifier: GPL-3.0-only. See audio-oem/NOTICE.md. */
package com.veloster.carplay.audiooem;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import com.veloster.carplay.control.AudioDescription;
import com.veloster.carplay.control.ModeControl;
import com.veloster.carplay.pairing.PairVerify;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/** Optional client for the exact pinned 250902 MICOM producer. */
public final class AndroidOemAlternateGateway implements OemAlternateAudioFactory.Gateway {
    private static final String OEM_JAR="/system/framework/automotive-service.jar";
    private final String expectedJarSha256;
    public AndroidOemAlternateGateway(Context context,String expectedJarSha256)throws IOException{
        if(context==null||!"com.veloster.carplayhub".equals(context.getPackageName())||
           expectedJarSha256==null||!expectedJarSha256.matches("[0-9a-f]{64}"))
            throw new IOException("OEM alternate client identity");
        this.expectedJarSha256=expectedJarSha256;
    }
    /** Service absence permits ordinary Android output. A present service needs proof. */
    public boolean servicePresent()throws IOException{return lookup()!=null;}
    public void verifyPinnedService()throws IOException{pin();service();}
    private IBinder lookup()throws IOException{
        try{return (IBinder)Class.forName("android.os.ServiceManager")
            .getMethod("getService",String.class).invoke(null,OemAlternateProtocol.SERVICE);}
        catch(Exception e){throw new IOException("OEM alternate lookup failed",e);}
    }
    private IBinder service()throws IOException{
        try{
            IBinder found=lookup();
            if(found==null||!found.isBinderAlive()||
               !OemAlternateProtocol.ABI.equals(found.getInterfaceDescriptor()))
                throw new IOException("OEM alternate service unavailable");
            return found;
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException("OEM alternate descriptor unavailable",e);}
    }
    private String pin()throws IOException{
        String boot=readBoot();
        if(!expectedJarSha256.equals(sha256(OEM_JAR)))throw new IOException("OEM alternate JAR mismatch");
        return boot;
    }
    public OemAlternateAudioFactory.RouteLease acquire(PairVerify.VerifiedSession owner,
            ModeControl modes,AudioDescription description)throws IOException{
        if(owner==null||modes==null||description==null||description.type!=101)throw new IOException("OEM route role");
        owner.assertCurrent();String boot=pin();IBinder remote=service();
        Lease lease=new Lease(remote,owner,boot,UUID.randomUUID().toString());
        try{
            remote.linkToDeath(lease,0);lease.linked=true;
            Parcel data=Parcel.obtain(),reply=Parcel.obtain();
            try{
                data.writeInterfaceToken(OemAlternateProtocol.ABI);data.writeString(lease.nonce);
                data.writeStrongBinder(lease.callback);
                if(!remote.transact(1,data,reply,0))throw new IOException("OEM alternate claim unavailable");
                reply.readException();String sourceBoot=reply.readString(),sourceEpoch=reply.readString();
                String sourceToken=reply.readString();long duration=reply.readLong();
                if(!boot.equals(sourceBoot)||!lease.proved||!lease.epoch.equals(sourceEpoch)||
                   !lease.token.equals(sourceToken)||duration!=OemAlternateProtocol.LEASE_MILLIS)
                    throw new IOException("OEM alternate source mismatch");
                owner.assertCurrent();lease.assertCurrent();return lease;
            }finally{reply.recycle();data.recycle();}
        }catch(IOException e){lease.close();throw e;}
         catch(Exception e){lease.close();throw new IOException("OEM alternate claim failed",e);}
         catch(LinkageError e){lease.close();throw new IOException("OEM alternate Binder API unavailable",e);}
    }
    private static String readBoot()throws IOException{
        FileInputStream in=new FileInputStream("/proc/sys/kernel/random/boot_id");try{
            byte[] data=new byte[128];int n=in.read(data);
            if(n<36||n>=data.length)throw new IOException("OEM alternate boot ID");
            String value=new String(data,0,n,"US-ASCII").trim();
            if(!value.matches("[0-9a-fA-F-]{36}"))throw new IOException("OEM alternate boot ID");
            return value;
        }finally{in.close();}
    }
    private static String sha256(String path)throws IOException{
        try{
            MessageDigest hash=MessageDigest.getInstance("SHA-256");FileInputStream in=new FileInputStream(path);
            try{byte[] data=new byte[32768];for(int n;(n=in.read(data))!=-1;)hash.update(data,0,n);}
            finally{in.close();}
            StringBuilder value=new StringBuilder();for(byte b:hash.digest())value.append(String.format(Locale.US,"%02x",b&255));
            return value.toString();
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException("OEM alternate hash unavailable",e);}
    }
    private static final class Lease implements OemAlternateAudioFactory.RouteLease,IBinder.DeathRecipient {
        private final IBinder remote;private final PairVerify.VerifiedSession owner;private final String boot,nonce;
        private volatile String epoch,token;private volatile boolean proved,closed,linked,released;
        private final Binder callback=new Binder(){protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            if(code!=1)return super.onTransact(code,data,reply,flags);
            data.enforceInterface(OemAlternateProtocol.CALLBACK);
            String sourceBoot=data.readString(),sourceEpoch=data.readString(),sourceNonce=data.readString(),sourceToken=data.readString();
            if(Binder.getCallingUid()!=1000||!boot.equals(sourceBoot)||!nonce.equals(sourceNonce)||
               sourceEpoch==null||!sourceEpoch.matches("[0-9a-fA-F-]{36}")||
               sourceToken==null||!sourceToken.matches("[0-9a-f]{48}"))
                throw new SecurityException("OEM alternate source UID/claim");
            synchronized(Lease.this){
                if(closed||proved)throw new SecurityException("OEM alternate challenge reused");
                epoch=sourceEpoch;token=sourceToken;proved=true;
            }
            if(reply!=null){reply.writeNoException();reply.writeInt(1);}return true;
        }};
        Lease(IBinder remote,PairVerify.VerifiedSession owner,String boot,String nonce){
            this.remote=remote;this.owner=owner;this.boot=boot;this.nonce=nonce;
        }
        public void binderDied(){closed=true;}
        public void assertCurrent()throws IOException{
            if(closed||!proved||token==null)throw new IOException("OEM alternate lease retired");
            try{owner.assertCurrent();}catch(IOException e){close();throw e;}
            Parcel data=Parcel.obtain(),reply=Parcel.obtain();
            try{
                data.writeInterfaceToken(OemAlternateProtocol.ABI);data.writeString(token);
                if(!remote.transact(2,data,reply,0))throw new IOException("OEM alternate lease unavailable");
                reply.readException();if(reply.readInt()!=1)throw new IOException("OEM alternate lease replaced");
            }catch(IOException e){close();throw e;}
             catch(RemoteException e){close();throw new IOException("OEM alternate service died",e);}
             catch(RuntimeException e){close();throw new IOException("OEM alternate lease invalid",e);}
            finally{reply.recycle();data.recycle();}
        }
        public void close(){
            String oldToken;boolean unlink;
            synchronized(this){
                if(released)return;released=true;oldToken=token;unlink=linked;linked=false;closed=true;
            }
            if(unlink)try{remote.unlinkToDeath(this,0);}catch(RuntimeException ignored){}
            if(oldToken==null)return;
            Parcel data=Parcel.obtain(),reply=Parcel.obtain();
            try{data.writeInterfaceToken(OemAlternateProtocol.ABI);data.writeString(oldToken);
                if(remote.transact(3,data,reply,0))reply.readException();}
            catch(Exception ignored){}finally{reply.recycle();data.recycle();}
        }
    }
}
