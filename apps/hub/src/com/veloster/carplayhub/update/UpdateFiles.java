package com.veloster.carplayhub.update;

import java.io.*;
import java.security.*;
import java.security.cert.Certificate;
import java.util.*;
import java.util.jar.*;

/** Bounded staging; never installs, extracts archives or changes firmware. */
public final class UpdateFiles {
 public interface Cancellation {boolean cancelled();}
 public static final Cancellation NEVER=new Cancellation(){public boolean cancelled(){return false;}};
 private UpdateFiles(){}
 public static void alive(Cancellation stop)throws IOException{if(stop.cancelled()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Update cancelled");}
 public static byte[] read(InputStream in,int max,Cancellation stop)throws IOException{
  ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
  while((n=in.read(b))!=-1){alive(stop);if(n==0)continue;UpdateManifest.require(out.size()+n<=max,"download size");out.write(b,0,n);}alive(stop);return out.toByteArray();
 }
 public static byte[] read(File file,int max)throws IOException{InputStream in=new FileInputStream(file);try{return read(in,max,NEVER);}finally{in.close();}}
 public static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes){int n=b&255;if(n<16)out.append('0');out.append(Integer.toHexString(n));}return out.toString();}
 public static String hash(File file,Cancellation stop)throws IOException,GeneralSecurityException{
  MessageDigest md=MessageDigest.getInstance("SHA-256");InputStream in=new FileInputStream(file);try{byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){alive(stop);md.update(b,0,n);}}finally{in.close();}return hex(md.digest());
 }
 /** Caller supplies a private, newly created output file; removes all partial bytes on failure. */
 public static void copy(InputStream in,File target,UpdateManifest manifest,Cancellation stop)throws IOException,GeneralSecurityException{
  UpdateManifest.require(target.createNewFile(),"staging file exists");boolean success=false;
  try{
   MessageDigest md=MessageDigest.getInstance("SHA-256");FileOutputStream out=new FileOutputStream(target);
   try{byte[] b=new byte[8192];int n,total=0;while((n=in.read(b))!=-1){alive(stop);if(n==0)continue;total+=n;UpdateManifest.require(total<=manifest.bytes,"APK too large");md.update(b,0,n);out.write(b,0,n);}alive(stop);UpdateManifest.require(total==manifest.bytes&&hex(md.digest()).equals(manifest.sha256),"APK hash/size");out.getFD().sync();}finally{out.close();}
   success=true;
  }finally{if(!success)target.delete();}
 }
 /** Fully read each non-META-INF entry to verify V1 JAR signatures on API17. */
 public static void verifyApk(File file,UpdateManifest manifest,Cancellation stop)throws IOException,GeneralSecurityException{
  UpdateManifest.require(file.length()==manifest.bytes&&hash(file,stop).equals(manifest.sha256),"staged APK changed");
  JarFile jar=new JarFile(file,true);boolean dex=false,androidManifest=false;long expanded=0;Set<String> seen=new HashSet<String>();
  try{
   Enumeration<JarEntry> entries=jar.entries();byte[] buffer=new byte[8192];
   while(entries.hasMoreElements()){
    alive(stop);JarEntry e=entries.nextElement();String name=e.getName();
    UpdateManifest.require(seen.add(name)&&seen.size()<=4096&&!name.startsWith("/")&&!name.contains("..")&&!name.contains("\\"),"ZIP entry");
    if(e.isDirectory())continue;
    InputStream input=jar.getInputStream(e);ByteArrayOutputStream prefix=new ByteArrayOutputStream();
    try{int n;while((n=input.read(buffer))!=-1){alive(stop);expanded+=n;UpdateManifest.require(expanded<=128L*1024*1024,"expanded APK size");if(prefix.size()<8)prefix.write(buffer,0,Math.min(n,8-prefix.size()));}}finally{input.close();}
    if(name.startsWith("META-INF/"))continue;
    Certificate[] certificates=e.getCertificates();UpdateManifest.require(certificates!=null&&certificates.length==1,"unsigned/multiple signer entry");
    UpdateManifest.require(hex(MessageDigest.getInstance("SHA-256").digest(certificates[0].getEncoded())).equals(manifest.certificate),"APK signer");
    if(name.equals("classes.dex")){dex=Arrays.equals(prefix.toByteArray(),new byte[]{100,101,120,10,48,51,53,0});}
    if(name.equals("AndroidManifest.xml"))androidManifest=true;
    UpdateManifest.require(!name.matches("classes[0-9]+\\.dex"),"multidex");
   }
   UpdateManifest.require(dex&&androidManifest,"API17 APK structure");
  }catch(SecurityException e){throw new IOException("Update rejected: APK signature verification");}finally{jar.close();}
 }
}
