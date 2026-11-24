package com.veloster.carplayhub.update;

import android.content.Context;
import java.io.*;
import java.util.*;
import com.veloster.network.ModernHttps;

/** Optional build-time TLS assets authenticated by the APK; never downloads executable code. */
public final class AndroidTls {
 private AndroidTls(){}
 public static UpdateTransport transport(Context context,UpdateTransport.Policy policy)throws Exception{
  ExternalWifiLease binding=AndroidUsbWifi.currentOrNull();
  Material material=material(context,binding!=null);
  if(material==null)return new UpdateTransport(policy);
  return new NativeUpdateTransport(material.executable,material.ca,policy,binding);
 }
 /** A future market supplies its curated hosts from trusted code, not a downloaded catalog. */
 public static ModernHttps modern(Context context,Collection<String> hosts,final UpdateTransport.Policy policy)throws Exception{
  final ExternalWifiLease binding=AndroidUsbWifi.currentOrNull();Material material=material(context,binding!=null);
  if(material==null)throw new IOException("Modern HTTPS module missing");
  return new ModernHttps(material.executable,material.ca,hosts,new ModernHttps.Network(){
   public void check()throws IOException{policy.check();if(binding!=null)binding.check();}
   public List<String> arguments()throws IOException{return binding==null?Collections.<String>emptyList():binding.arguments();}
  });
 }
 private static final class Material {final File executable,ca;Material(File executable,File ca){this.executable=executable;this.ca=ca;}}
 private static Material material(Context context,boolean binding)throws Exception{
  byte[] metadata;InputStream source;
  try{source=context.getAssets().open("updates/native.properties");}catch(FileNotFoundException absent){UpdateManifest.require(!binding,"USB Wi-Fi needs the isolated DNS transport");return null;}
  try{metadata=UpdateFiles.read(source,1024,UpdateFiles.NEVER);}finally{source.close();}
  Properties p=new Properties();p.load(new ByteArrayInputStream(metadata));
  String executableHash=p.getProperty("executable",""),caHash=p.getProperty("ca","");
  boolean isolated=p.size()==4&&"2".equals(p.getProperty("format"))&&"cares".equals(p.getProperty("dns"));
  UpdateManifest.require((isolated||(p.size()==3&&"1".equals(p.getProperty("format"))))&&executableHash.matches("[0-9a-f]{64}")&&caHash.matches("[0-9a-f]{64}"),"TLS asset metadata");
  UpdateManifest.require(!binding||isolated,"USB Wi-Fi needs the isolated DNS transport");
  File executable=new File(context.getApplicationInfo().nativeLibraryDir,"libvnnet.so");
  UpdateManifest.require(executable.isFile()&&executable.length()<=16*1024*1024&&UpdateFiles.hash(executable,UpdateFiles.NEVER).equals(executableHash),"TLS executable differs");
  File ca=new File(context.getFilesDir(),"vn-update-ca-"+caHash+".pem");
  synchronized(AndroidTls.class){
   if(!ca.isFile()||ca.length()>512*1024||!UpdateFiles.hash(ca,UpdateFiles.NEVER).equals(caHash)){
    source=context.getAssets().open("updates/ca.pem");byte[] data;try{data=UpdateFiles.read(source,512*1024,UpdateFiles.NEVER);}finally{source.close();}
    File temporary=File.createTempFile("vn-tls-",".tmp",context.getFilesDir());
    try{
     FileOutputStream out=new FileOutputStream(temporary);try{out.write(data);out.flush();out.getFD().sync();}finally{out.close();}
     UpdateManifest.require(UpdateFiles.hash(temporary,UpdateFiles.NEVER).equals(caHash),"TLS CA differs");
     UpdateManifest.require(temporary.renameTo(ca),"TLS CA staging");
    }finally{temporary.delete();}
   }
  }
  return new Material(executable,ca);
 }
}
