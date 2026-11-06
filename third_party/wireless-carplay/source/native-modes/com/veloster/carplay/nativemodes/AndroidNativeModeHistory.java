/* SPDX-License-Identifier: GPL-3.0-only. Read-only optional producer adapter. */
package com.veloster.carplay.nativemodes;
import android.os.*;import java.io.*;import java.security.MessageDigest;import java.util.UUID;import java.util.concurrent.*;
public final class AndroidNativeModeHistory {
 private static final String NAME="com.veloster.carplay.native_mode_history",ABI="com.veloster.carplay.NativeModeHistory.v2",CALLBACK=ABI+".snapshot",SOURCE="250902:930e1de0cb57b945565e66d8856d1bccdebe36c6e44afd485c6894755ceb738a";
 private AndroidNativeModeHistory(){}
 /** Worker only. expectedJarSha256 must come from the reviewed private hook
  * manifest. No source artifact is eligible merely because a service exists. */
 public static NativeModeHistory.Snapshot read(String expectedJarSha256)throws IOException{
  if(expectedJarSha256==null||!expectedJarSha256.matches("[0-9a-f]{64}"))throw new IOException("Pinned hook digest required");
  try{MessageDigest digest=MessageDigest.getInstance("SHA-256");FileInputStream in=new FileInputStream("/system/framework/automotive-service.jar");try{byte[] b=new byte[32768];for(int n;(n=in.read(b))!=-1;)digest.update(b,0,n);}finally{in.close();}StringBuilder actual=new StringBuilder();for(byte b:digest.digest())actual.append(String.format("%02x",b&255));if(!expectedJarSha256.equals(actual.toString()))throw new IOException("OEM history hook digest mismatch");
   IBinder remote=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,NAME);if(remote==null||!ABI.equals(remote.getInterfaceDescriptor()))throw new IOException("OEM history producer absent");return readBinder(remote,bootId());
  }catch(IOException e){throw e;}catch(Exception e){throw new IOException("OEM history unavailable",e);}catch(LinkageError e){throw new IOException("OEM history API unavailable",e);}
 }
 private static String bootId()throws IOException{BufferedReader in=new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"));try{return in.readLine();}finally{in.close();}}
 /** Separate one-shot reply Binder establishes the producer's kernel sender
  * UID. A claimed uid field in a snapshot is not authentication. */
 static NativeModeHistory.Snapshot readBinder(IBinder remote,final String boot)throws IOException{
  final String nonce=UUID.randomUUID().toString();final CountDownLatch ready=new CountDownLatch(1);final Object lock=new Object();final boolean[] open={true};final NativeModeHistory.Snapshot[] result={null};final IOException[] failure={null};
  Binder callback=new Binder(){protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
   if(code!=1)return super.onTransact(code,data,reply,flags);
   synchronized(lock){if(!open[0])return true;try{if(Binder.getCallingUid()!=1000)throw new SecurityException("OEM history producer UID");data.enforceInterface(CALLBACK);if(!nonce.equals(data.readString())||!SOURCE.equals(data.readString()))throw new SecurityException("OEM history producer identity");String observedBoot=data.readString(),epoch=data.readString();if(!boot.equals(observedBoot))throw new SecurityException("OEM history boot mismatch");result[0]=new NativeModeHistory.Snapshot(observedBoot,epoch,data.readLong(),data.readLong(),data.readLong(),data.readLong(),data.readLong(),data.readLong(),data.readLong(),data.readLong(),data.readLong(),data.readLong(),data.readInt()!=0);}catch(Exception e){failure[0]=new IOException("OEM history reply rejected",e);}finally{open[0]=false;ready.countDown();}}return true;
  }};
  Parcel request=Parcel.obtain(),reply=Parcel.obtain();try{request.writeInterfaceToken(ABI);request.writeStrongBinder(callback);request.writeString(nonce);if(!remote.transact(1,request,reply,0))throw new IOException("OEM history request rejected");reply.readException();if(!ready.await(3000,TimeUnit.MILLISECONDS))throw new IOException("OEM history response timeout");synchronized(lock){if(failure[0]!=null)throw failure[0];if(result[0]==null)throw new IOException("OEM history response absent");return result[0];}}
  catch(IOException e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("OEM history interrupted",e);}catch(Exception e){throw new IOException("OEM history read failed",e);}finally{synchronized(lock){open[0]=false;}reply.recycle();request.recycle();}
 }
}
