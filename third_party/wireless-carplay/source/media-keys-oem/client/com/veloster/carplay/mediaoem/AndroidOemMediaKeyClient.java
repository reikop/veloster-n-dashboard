/* SPDX-License-Identifier: GPL-3.0-only. Exact-JAR-pinned optional Binder client. */
package com.veloster.carplay.mediaoem;

import android.os.Binder;
import android.os.DeadObjectException;
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

/** Never opens a claim against an unpinned OEM JAR, a stale boot or a producer
 * epoch that this exact instance did not observe before the capability was declared. */
public final class AndroidOemMediaKeyClient implements ReceiverHardwareConsumerSource.Provider {
    private static final String SERVICE="com.veloster.carplay.oem_media_keys",ABI="com.veloster.carplay.OemMediaKeys.v1",CALLBACK=ABI+".callback";
    private static final String OEM_JAR="/system/framework/automotive-service.jar",EPOCH_SHAPE="[0-9a-fA-F-]{36}";
    /** READY: exact pin/boot and a trusted UID 1000 producer epoch observed. ABSENT: exact pin/boot
     * succeeded and the service name is not registered. REJECTED: every other outcome. */
    public enum Status { READY, ABSENT, REJECTED }
    /** Fixed, non-sensitive reason codes. They never carry OEM reply text or exception messages. */
    public static final String REASON_READY="ready",REASON_ABSENT="service_absent",REASON_MAIN_THREAD="main_thread",
        REASON_BOOT_UNREADABLE="boot_unreadable",REASON_JAR_UNREADABLE="jar_unreadable",REASON_JAR_MISMATCH="jar_mismatch",
        REASON_LOOKUP_FAILED="lookup_failed",REASON_DESCRIPTOR_MISMATCH="descriptor_mismatch",REASON_TRANSACT_REFUSED="transact_refused",
        REASON_PERMISSION_DENIED="permission_denied",REASON_TRANSPORT_FAILED="transport_failed",REASON_REPLY_MALFORMED="reply_malformed",
        REASON_SOURCE_BOOT_MISMATCH="source_boot_mismatch",REASON_EPOCH_MALFORMED="epoch_malformed",REASON_SOURCE_UID_MISMATCH="source_uid_mismatch",
        REASON_API_UNAVAILABLE="api_unavailable",REASON_OBSERVATION_CHANGED="observation_changed",REASON_OBSERVATION_REVOKED="observation_revoked";
    public static final class Probe {
        public final Status status;public final String reason;
        private Probe(Status status,String reason){this.status=status;this.reason=reason;}
        public boolean ready(){return status==Status.READY;}
    }
    private static final class Identity {
        final Status status;final String reason,boot,epoch;final IBinder remote;
        Identity(Status status,String reason,String boot,String epoch,IBinder remote){this.status=status;this.reason=reason;this.boot=boot;this.epoch=epoch;this.remote=remote;}
        static Identity rejected(String reason){return new Identity(Status.REJECTED,reason,null,null,null);}
    }
    private final String pinnedJar;
    /** Guarded by this. At most one positive observation per instance. Any completed worker probe
     * that is not READY with the identical (boot,epoch), and any open() failure after argument
     * validation, revokes it permanently; a later source needs a new instance and a new probe. */
    private String observedBoot,observedEpoch;private boolean revoked;
    public AndroidOemMediaKeyClient(String pinnedAutomotiveJarSha256)throws IOException{
        if(pinnedAutomotiveJarSha256==null||!pinnedAutomotiveJarSha256.matches("[0-9a-f]{64}"))throw new IOException("OEM media JAR pin required");
        pinnedJar=pinnedAutomotiveJarSha256;
    }
    private static boolean onMain(){return Looper.myLooper()==Looper.getMainLooper();}
    /** Legacy read-only pre-declaration probe. On the UI thread it returns false without
     * inspecting anything and without touching an earlier worker observation. */
    public boolean available(){
        if(onMain())return false;
        return probe().status==Status.READY;
    }
    /** Classified read-only pre-declaration probe. Call on a worker; it never claims ownership. */
    public Probe probe(){
        if(onMain())return new Probe(Status.REJECTED,REASON_MAIN_THREAD);
        Identity seen=inspect();
        synchronized(this){
            if(revoked)return new Probe(Status.REJECTED,REASON_OBSERVATION_REVOKED);
            if(seen.status!=Status.READY){revoked=true;return new Probe(seen.status,seen.reason);}
            if(observedEpoch==null){observedBoot=seen.boot;observedEpoch=seen.epoch;return new Probe(Status.READY,REASON_READY);}
            if(observedBoot.equals(seen.boot)&&observedEpoch.equals(seen.epoch))return new Probe(Status.READY,REASON_READY);
            // Never silently rebind a declared positive observation to a different producer.
            revoked=true;return new Probe(Status.REJECTED,REASON_OBSERVATION_CHANGED);
        }
    }
    private synchronized void revoke(){revoked=true;}
    /** Read-only identity inspection. Never throws for ordinary failures; never claims. */
    private Identity inspect(){
        String boot,digest;
        try{boot=readBoot();}catch(IOException error){return Identity.rejected(REASON_BOOT_UNREADABLE);}
         catch(RuntimeException error){return Identity.rejected(REASON_BOOT_UNREADABLE);}
        try{digest=sha256(OEM_JAR);}catch(IOException error){return Identity.rejected(REASON_JAR_UNREADABLE);}
         catch(RuntimeException error){return Identity.rejected(REASON_JAR_UNREADABLE);}
        if(!pinnedJar.equals(digest))return Identity.rejected(REASON_JAR_MISMATCH);
        IBinder remote;
        try{remote=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,SERVICE);}
        catch(Exception error){return Identity.rejected(REASON_LOOKUP_FAILED);}
        catch(LinkageError error){return Identity.rejected(REASON_API_UNAVAILABLE);}
        if(remote==null)return new Identity(Status.ABSENT,REASON_ABSENT,boot,null,null);
        Parcel data=null,reply=null;
        try{
            if(!ABI.equals(remote.getInterfaceDescriptor()))return Identity.rejected(REASON_DESCRIPTOR_MISMATCH);
            data=Parcel.obtain();reply=Parcel.obtain();data.writeInterfaceToken(ABI);
            if(!remote.transact(4,data,reply,0))return Identity.rejected(REASON_TRANSACT_REFUSED);
            reply.readException();
            String sourceBoot=reply.readString(),sourceEpoch=reply.readString();int sourceUid=reply.readInt();
            if(!boot.equals(sourceBoot))return Identity.rejected(REASON_SOURCE_BOOT_MISMATCH);
            if(sourceEpoch==null||!sourceEpoch.matches(EPOCH_SHAPE))return Identity.rejected(REASON_EPOCH_MALFORMED);
            if(sourceUid!=1000)return Identity.rejected(REASON_SOURCE_UID_MISMATCH);
            return new Identity(Status.READY,REASON_READY,boot,sourceEpoch,remote);
        }catch(SecurityException error){return Identity.rejected(REASON_PERMISSION_DENIED);}
         catch(RemoteException error){return Identity.rejected(REASON_TRANSPORT_FAILED);}
         catch(RuntimeException error){return Identity.rejected(REASON_REPLY_MALFORMED);}
         catch(LinkageError error){return Identity.rejected(REASON_API_UNAVAILABLE);}
        finally{
            try{if(reply!=null)reply.recycle();if(data!=null)data.recycle();}
            catch(RuntimeException ignored){}catch(LinkageError ignored){}
        }
    }
    /** One ownership claim against the producer epoch this instance observed. No retry. */
    public ReceiverHardwareConsumerSource.Lease open(String nonce,ReceiverHardwareConsumerSource.Listener listener)throws IOException{
        if(nonce==null||!nonce.matches(EPOCH_SHAPE)||listener==null)throw new IOException("OEM media claim shape");
        if(onMain())throw new IOException("OEM media claim requires a worker");
        String boot,epoch;
        synchronized(this){if(revoked||observedEpoch==null)throw new IOException("OEM media source not observed");boot=observedBoot;epoch=observedEpoch;}
        boolean claimed=false;
        try{
            // Fresh read-only identity inspection immediately before the claim is dispatched.
            Identity fresh=inspect();
            if(fresh.status!=Status.READY)throw new IOException("OEM media source not current: "+fresh.reason);
            if(!boot.equals(fresh.boot)||!epoch.equals(fresh.epoch))throw new IOException("OEM media source not current: "+REASON_OBSERVATION_CHANGED);
            IBinder remote=fresh.remote;
            try{
                Lease lease=new Lease(remote,boot,listener);remote.linkToDeath(lease,0);
                Parcel data=Parcel.obtain(),reply=Parcel.obtain();try{
                    data.writeInterfaceToken(ABI);data.writeString(nonce);data.writeStrongBinder(lease.callback);
                    if(!remote.transact(1,data,reply,0))throw new IOException("OEM media claim rejected");reply.readException();
                    String sourceBoot=reply.readString(),claimEpoch=reply.readString(),token=reply.readString();long ttl=reply.readLong();
                    // A changed or uncertain post-dispatch reply is a failure, never a fabricated success.
                    if(!boot.equals(sourceBoot)||!epoch.equals(claimEpoch)||token==null||!token.matches("[0-9a-f]{48}")||ttl!=750)
                        throw new IOException("OEM media claim identity mismatch");
                    lease.epoch=claimEpoch;lease.token=token;claimed=true;return lease;
                }catch(Exception failure){lease.retire();remote.unlinkToDeath(lease,0);if(failure instanceof IOException)throw (IOException)failure;throw new IOException("OEM media claim failed",failure);}finally{reply.recycle();data.recycle();}
            }catch(IOException error){throw error;}catch(Exception error){throw new IOException("OEM media binding failed",error);}catch(LinkageError error){throw new IOException("OEM media API unavailable",error);}
        }finally{if(!claimed)revoke();}
    }
    private static String readBoot()throws IOException{FileInputStream in=new FileInputStream("/proc/sys/kernel/random/boot_id");try{byte[] b=new byte[128];int n=in.read(b);if(n<36||n>=b.length)throw new IOException("OEM media boot ID");String boot=new String(b,0,n,"US-ASCII").trim();if(!boot.matches("[0-9a-fA-F-]{36}"))throw new IOException("OEM media boot ID");return boot;}finally{in.close();}}
    private static String sha256(String path)throws IOException{try{MessageDigest h=MessageDigest.getInstance("SHA-256");FileInputStream in=new FileInputStream(path);try{byte[] b=new byte[32768];for(int n;(n=in.read(b))!=-1;)h.update(b,0,n);}finally{in.close();}StringBuilder out=new StringBuilder();for(byte b:h.digest())out.append(String.format(Locale.US,"%02x",b&255));return out.toString();}catch(IOException error){throw error;}catch(Exception error){throw new IOException("OEM media hash unavailable",error);}}
    private static int code(ReceiverHardwareConsumerSource.Route route){return route==ReceiverHardwareConsumerSource.Route.CLAIMED?1:route==ReceiverHardwareConsumerSource.Route.QUARANTINED?2:0;}
    public static final class Lease implements ReceiverHardwareConsumerSource.Lease,IBinder.DeathRecipient {
        private final IBinder remote;private final String boot;private final ReceiverHardwareConsumerSource.Listener listener;
        private volatile String epoch,token;private volatile boolean deathConfirmed;
        private boolean closed,releaseStarted,releaseDone;private IOException releaseFailure;
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
        public void binderDied(){deathConfirmed=true;retire();}
        public void renew(boolean enabled)throws IOException{
            String current=token;synchronized(this){if(closed||current==null)throw new IOException("OEM media lease closed");}
            Parcel data=Parcel.obtain(),reply=Parcel.obtain();try{
                data.writeInterfaceToken(ABI);data.writeString(current);data.writeInt(enabled?1:0);
                if(!remote.transact(2,data,reply,0))throw new IOException("OEM media renew rejected");reply.readException();
                if(reply.readInt()!=1)throw new IOException("OEM media lease stale");
            }catch(DeadObjectException gone){binderDied();throw new IOException("OEM media service died",gone);}
            catch(Exception failure){retire();if(failure instanceof IOException)throw (IOException)failure;throw new IOException("OEM media renew failed",failure);}finally{reply.recycle();data.recycle();}
        }
        public void close()throws IOException{
            String old;synchronized(this){
                if(releaseStarted){if(!releaseDone)throw new IOException("OEM media release pending");
                    if(releaseFailure!=null)throw releaseFailure;return;}
                releaseStarted=true;old=token;
            }
            IOException failed=null;boolean acknowledged=false;Parcel data=null,reply=null;try{
                retire();
                // Death of this exact remote proves it cannot retain its claim.
                // Keep the death recipient linked until the release attempt ends.
                if(old!=null&&!deathConfirmed){
                    data=Parcel.obtain();reply=Parcel.obtain();
                    data.writeInterfaceToken(ABI);data.writeString(old);
                    if(!remote.transact(3,data,reply,0))throw new IOException("OEM media release rejected");reply.readException();
                }
                acknowledged=true;
            }catch(DeadObjectException gone){binderDied();acknowledged=true;}
             catch(Exception failure){failed=failure instanceof IOException?(IOException)failure:new IOException("OEM media release failed",failure);}
             catch(LinkageError failure){failed=new IOException("OEM media release API unavailable",failure);}
            finally{
                try{remote.unlinkToDeath(this,0);}catch(NoSuchElementException alreadyRemoved){}
                catch(RuntimeException failure){if(failed==null)failed=new IOException("OEM media death-link cleanup failed",failure);}
                catch(LinkageError failure){if(failed==null)failed=new IOException("OEM media death-link cleanup API unavailable",failure);}
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
