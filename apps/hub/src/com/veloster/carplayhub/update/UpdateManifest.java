package com.veloster.carplayhub.update;

import java.io.IOException;
import java.net.URL;
import java.security.*;

/** Strict, signed ASCII metadata shared by HTTPS and removable-media imports. */
public final class UpdateManifest {
 public static final String DASHBOARD="com.veloster.dashboard",HUB="com.veloster.carplayhub";
 public static final int MAX_MANIFEST=4096,MAX_APK=16*1024*1024;
 private static final String[] FIELDS={"package","version","bytes","sha256","certificate","product","incremental","sdk","url"};
 public final String packageName,sha256,certificate,product,incremental,url;
 public final int version,bytes,sdk;
 private UpdateManifest(String[] v)throws IOException{
  packageName=v[0];version=positive(v[1]);bytes=positive(v[2]);sha256=v[3];certificate=v[4];product=v[5];incremental=v[6];sdk=positive(v[7]);url=v[8];
  require(DASHBOARD.equals(packageName)||HUB.equals(packageName),"package");require(bytes<=MAX_APK,"size");
  require(sha256.matches("[0-9a-f]{64}")&&certificate.matches("[0-9a-f]{64}"),"digest");
  require("jsn_kr".equals(product)&&"eng.vmadmin.20250902.081552".equals(incremental)&&sdk==17,"target");
  URL u=new URL(url);require("https".equals(u.getProtocol())&&"github.com".equals(u.getHost())&&u.getPort()==-1&&u.getUserInfo()==null&&u.getRef()==null&&u.getQuery()==null,"URL");
  require(u.getPath().matches("/reikop/veloster-n-dashboard/releases/download/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+\\.apk"),"release path");
 }
 public static void require(boolean ok,String reason)throws IOException{if(!ok)throw new IOException("Update rejected: "+reason);}
 private static int positive(String value)throws IOException{
  require(value.matches("[1-9][0-9]{0,9}"),"number");try{return Integer.parseInt(value);}catch(NumberFormatException e){throw new IOException("Update rejected: number overflow");}
 }
 public static UpdateManifest verify(byte[] data,byte[] signature,PublicKey key)throws GeneralSecurityException,IOException{
  require(data.length>0&&data.length<=MAX_MANIFEST&&signature.length==256,"metadata size");
  Signature verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(key);verifier.update(data);require(verifier.verify(signature),"release signature");
  for(byte b:data)require(b==10||(b>=32&&b<=126),"ASCII metadata");
  String[] lines=new String(data,"US-ASCII").split("\n",-1);require(lines.length==FIELDS.length+2&&"VNUPDATE1".equals(lines[0])&&lines[lines.length-1].length()==0,"format");
  String[] values=new String[FIELDS.length];
  for(int i=0;i<FIELDS.length;i++){String prefix=FIELDS[i]+"=";require(lines[i+1].startsWith(prefix),"field order");values[i]=lines[i+1].substring(prefix.length());}
  return new UpdateManifest(values);
 }
 public void checkTarget(String expectedPackage,int installedVersion,String liveProduct,String liveIncremental,int liveSdk)throws IOException{
  require(packageName.equals(expectedPackage),"selected app");require(version>installedVersion&&installedVersion>0,"not a newer installed app");
  require(product.equals(liveProduct)&&incremental.equals(liveIncremental)&&sdk==liveSdk,"device");
 }
}
