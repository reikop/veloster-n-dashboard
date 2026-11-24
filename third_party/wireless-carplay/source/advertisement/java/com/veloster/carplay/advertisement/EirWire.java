/* SPDX-License-Identifier: GPL-3.0-only. Bounded strict API17 Parcel encoding. */
package com.veloster.carplay.advertisement;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.io.IOException;
public final class EirWire {
    private EirWire(){}
    public static void writeScope(Parcel p,EirProtocol.Scope s){
        p.writeString(s.owner);p.writeString(s.ap);p.writeLong(s.selectionGeneration);
        // v2 is byte-for-byte unchanged; v3 has a required typed enrollment ID, no MAC slot.
        p.writeString(s.enrollment()?s.enrollmentId:s.phone);
    }
    public static EirProtocol.Scope readScope(Parcel p){return readScope(p,EirProtocol.VERSION);}
    public static EirProtocol.Scope readScope(Parcel p,int version){
        if(version==EirProtocol.VERSION)return new EirProtocol.Scope(p.readString(),p.readString(),p.readLong(),p.readString());
        if(version==EirProtocol.ENROLLMENT_VERSION)return new EirProtocol.EnrollmentScope(p.readString(),p.readString(),p.readLong(),p.readString());
        throw new IllegalArgumentException("Unsupported EIR scope version");
    }
    public static void writeSnapshot(Parcel p,EirProtocol.Snapshot s){writeScope(p,s.scope);p.writeLong(s.requestSequence);p.writeLong(s.carrierGeneration);p.writeInt(s.state);p.writeInt(s.nativeStatus);p.writeInt(s.localContribution?1:0);p.writeInt(s.localStopped?1:0);p.writeInt(s.controllerConfirmed?1:0);p.writeString(s.failure);}
    private static boolean bit(Parcel p){int b=p.readInt();if(b!=0&&b!=1)throw new IllegalArgumentException("Invalid EIR boolean");return b==1;}
    public static EirProtocol.Snapshot readSnapshot(Parcel p){return readSnapshot(p,EirProtocol.VERSION);}
    public static EirProtocol.Snapshot readSnapshot(Parcel p,int version){return new EirProtocol.Snapshot(readScope(p,version),p.readLong(),p.readLong(),p.readInt(),p.readInt(),bit(p),bit(p),bit(p),p.readString());}
    public static EirProtocol.Snapshot call(IBinder service,int command,IBinder token,long sequence,EirProtocol.Scope scope)throws IOException{
        Parcel request=Parcel.obtain(),reply=Parcel.obtain();try{
            if(sequence<1)throw new IOException("Invalid EIR request sequence");request.writeInterfaceToken(EirProtocol.DESCRIPTOR);request.writeInt(scope.version());request.writeStrongBinder(token);request.writeLong(sequence);writeScope(request,scope);
            if(!service.transact(command,request,reply,0))throw new IOException("EIR carrier protocol unavailable");reply.readException();
            EirProtocol.Snapshot result=readSnapshot(reply,scope.version());if(reply.dataAvail()!=0||!scope.equals(result.scope)||result.requestSequence!=sequence)throw new IOException("Foreign EIR status");return result;
        }catch(RemoteException e){throw new IOException("EIR carrier disconnected",e);}catch(RuntimeException e){throw new IOException("EIR carrier rejected request",e);}finally{reply.recycle();request.recycle();}
    }
}
