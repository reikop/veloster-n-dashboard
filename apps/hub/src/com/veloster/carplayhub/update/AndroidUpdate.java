package com.veloster.carplayhub.update;

import android.content.*;
import android.content.pm.*;
import android.os.Build;
import android.net.Uri;
import java.io.*;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

/** API17 adapter: stages privately, checks installed identity, hands off to the system installer. */
public final class AndroidUpdate {
 public static final String PREFS="app_updates";
 public static final class Prepared {
  public final UpdateManifest manifest;public final File file;
  Prepared(UpdateManifest m,File f){manifest=m;file=f;}
 }
 // Shared by Activity instances in this process; no network while holding this lock.
 private static final Object STATE=new Object();
 private static final Set<File> ACTIVE=new HashSet<File>();
 private static final Map<File,Integer> READERS=new HashMap<File,Integer>();
 private final Context context;
 private SharedPreferences preferences(){return context.getSharedPreferences(PREFS,0);}
 private boolean owned(File f){return context.getFilesDir().equals(f.getParentFile())&&f.getName().matches("vn-update-[a-f0-9-]+\\.apk");}
 private String savedKey(String name)throws IOException{UpdateManifest.require(UpdateManifest.DASHBOARD.equals(name)||UpdateManifest.HUB.equals(name),"saved package");return "saved_"+name;}
 private boolean retained(File f){SharedPreferences p=preferences();return f.getName().equals(p.getString("pending_file",""))||f.getName().equals(p.getString("saved_"+UpdateManifest.DASHBOARD,""))||f.getName().equals(p.getString("saved_"+UpdateManifest.HUB,""));}
 private File side(File f,String suffix){return new File(f.getPath()+suffix);}
 private void erase(File f){if(owned(f)&&!ACTIVE.contains(f)&&!READERS.containsKey(f)&&!retained(f)){f.delete();side(f,".manifest").delete();side(f,".signature").delete();}}
 public void discard(File f){synchronized(STATE){ACTIVE.remove(f);erase(f);}}
 private static final class Receipt {
  final String id,name,sha,file;final int version;
  Receipt(SharedPreferences p){id=p.getString("pending_id","");name=p.getString("pending_package","");sha=p.getString("pending_sha","");file=p.getString("pending_file","");version=p.getInt("pending_version",0);}
  boolean same(Receipt r){return id.equals(r.id)&&name.equals(r.name)&&sha.equals(r.sha)&&file.equals(r.file)&&version==r.version;}
 }
 public AndroidUpdate(Context c){context=c.getApplicationContext();}
 private PackageInfo installed(String name)throws Exception{return context.getPackageManager().getPackageInfo(name,PackageManager.GET_SIGNATURES);}
 private static String signer(PackageInfo p)throws Exception{
  UpdateManifest.require(p!=null&&p.signatures!=null&&p.signatures.length==1,"installed signer unavailable");
  return UpdateFiles.hex(MessageDigest.getInstance("SHA-256").digest(p.signatures[0].toByteArray()));
 }
 public void target(UpdateManifest m,String selected)throws Exception{
  PackageInfo old=installed(selected);m.checkTarget(selected,old.versionCode,Build.PRODUCT,Build.VERSION.INCREMENTAL,Build.VERSION.SDK_INT);
  UpdateManifest.require(signer(old).equals(m.certificate),"installed signer differs");
 }
 public UpdateManifest metadata(byte[] data,byte[] signature,String selected)throws Exception{
  UpdateManifest m=UpdateManifest.verify(data,signature,UpdateTrust.key());target(m,selected);return m;
 }
 public File newStage(){synchronized(STATE){File f=new File(context.getFilesDir(),"vn-update-"+UUID.randomUUID().toString()+".apk");ACTIVE.add(f);return f;}}
 public Prepared finish(File stage,UpdateManifest m,UpdateFiles.Cancellation stop)throws Exception{
  try{
   UpdateFiles.verifyApk(stage,m,stop);target(m,m.packageName);
   PackageInfo archive=context.getPackageManager().getPackageArchiveInfo(stage.getPath(),PackageManager.GET_SIGNATURES);
   UpdateManifest.require(archive!=null&&m.packageName.equals(archive.packageName)&&m.version==archive.versionCode,"APK metadata");
   UpdateManifest.require(signer(archive).equals(m.certificate),"APK certificate");return new Prepared(m,stage);
  }catch(Exception e){discard(stage);throw e;}
 }
 /** Keep only a fully verified, signed update. Existing saved bytes survive a failed replacement. */
 public void remember(Prepared p,byte[] data,byte[] signature,UpdateFiles.Cancellation stop)throws Exception{
  UpdateManifest verified=metadata(data,signature,p.manifest.packageName);
  UpdateManifest.require(owned(p.file)&&verified.version==p.manifest.version&&verified.sha256.equals(p.manifest.sha256),"saved metadata differs");
  UpdateManifest.require(p.file.length()==verified.bytes&&UpdateFiles.hash(p.file,stop).equals(verified.sha256),"saved APK changed");
  synchronized(STATE){
   UpdateFiles.alive(stop);String key=savedKey(verified.packageName),before=preferences().getString(key,"");
   // Do not replace a newer signed candidate with an older feed/import.
   File previous=new File(context.getFilesDir(),before);
   if(owned(previous)&&!previous.equals(p.file))try{
    UpdateManifest old=metadata(UpdateFiles.read(side(previous,".manifest"),4096),UpdateFiles.read(side(previous,".signature"),256),verified.packageName);
    if(old.version>verified.version&&previous.length()==old.bytes&&UpdateFiles.hash(previous,stop).equals(old.sha256))return;
   }catch(InterruptedIOException cancelled){throw cancelled;}catch(Exception invalid){}
   writeSide(side(p.file,".manifest"),data);writeSide(side(p.file,".signature"),signature);
   UpdateFiles.alive(stop);UpdateManifest.require(preferences().edit().putString(key,p.file.getName()).commit(),"saved receipt");
   if(!before.equals(p.file.getName()))erase(previous);
  }
 }
 private void writeSide(File file,byte[] bytes)throws IOException{
  File temp=new File(file.getPath()+".tmp");boolean done=false;
  try{FileOutputStream out=new FileOutputStream(temp);try{out.write(bytes);out.getFD().sync();}finally{out.close();}UpdateManifest.require(temp.renameTo(file),"saved metadata publish");done=true;}finally{if(!done)temp.delete();}
 }
 /** Revalidate the signed metadata, current target and every APK signature before an offline offer. */
 public Prepared saved(String name,UpdateFiles.Cancellation stop)throws Exception{
  final String key=savedKey(name),filename;final File file;File stage=null;boolean valid=false,success=false;
  synchronized(STATE){filename=preferences().getString(key,"");if(filename.length()==0)return null;file=new File(context.getFilesDir(),filename);if(owned(file)){Integer n=READERS.get(file);READERS.put(file,n==null?1:n+1);}}
  try{
   UpdateFiles.alive(stop);UpdateManifest.require(owned(file),"saved path");
   UpdateManifest m=metadata(UpdateFiles.read(side(file,".manifest"),4096),UpdateFiles.read(side(file,".signature"),256),name);
   UpdateFiles.verifyApk(file,m,stop);valid=true;
   // Each Activity owns a separate offer; releasing one cannot invalidate another.
   stage=newStage();InputStream in=new FileInputStream(file);try{UpdateFiles.copy(in,stage,m,stop);}finally{in.close();}
   Prepared result=finish(stage,m,stop);success=true;return result;
  }catch(InterruptedIOException cancelled){throw cancelled;}
  catch(Exception invalid){
   if(valid)throw invalid;
   synchronized(STATE){if(filename.equals(preferences().getString(key,"")))UpdateManifest.require(preferences().edit().remove(key).commit(),"saved receipt clear");}
   return null;
  }finally{
   if(!success&&stage!=null)discard(stage);
   synchronized(STATE){Integer n=READERS.get(file);if(n!=null){if(n<=1)READERS.remove(file);else READERS.put(file,n-1);}erase(file);}
  }
 }
 public void pruneSaved(UpdateFiles.Cancellation stop)throws Exception{
  for(String name:new String[]{UpdateManifest.DASHBOARD,UpdateManifest.HUB}){Prepared p=saved(name,stop);if(p!=null)discard(p.file);}
 }
 public Intent installer(Prepared p,UpdateFiles.Cancellation stop)throws Exception{
  target(p.manifest,p.manifest.packageName);
  UpdateManifest.require(UpdateFiles.hash(p.file,stop).equals(p.manifest.sha256),"staged file changed");
  Intent intent=new Intent(Intent.ACTION_VIEW);intent.setDataAndType(Uri.fromFile(p.file),"application/vnd.android.package-archive");
  ResolveInfo resolver=context.getPackageManager().resolveActivity(intent,PackageManager.MATCH_DEFAULT_ONLY);
  ActivityInfo activity=resolver==null?null:resolver.activityInfo;
  UpdateManifest.require(activity!=null&&activity.exported&&activity.enabled&&activity.applicationInfo.enabled&&(activity.applicationInfo.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))!=0,"system installer unavailable");
  intent.setComponent(new ComponentName(activity.packageName,activity.name));
  // API17 PackageInstaller accepts file/package URIs, not content://. Only the verified public APK becomes readable.
  UpdateManifest.require(p.file.setReadable(true,false),"installer file access");
  synchronized(STATE){UpdateFiles.alive(stop);SharedPreferences prefs=preferences();
  UpdateManifest.require(prefs.edit().putString("pending_id",UUID.randomUUID().toString()).putString("pending_package",p.manifest.packageName).putInt("pending_version",p.manifest.version).putString("pending_sha",p.manifest.sha256).putString("pending_file",p.file.getName()).commit(),"pending receipt");ACTIVE.remove(p.file);}
  return intent;
 }
 /** Never trusts a launch/result code as proof. Hashes the installed APK after returning/restarting. */
 public String reconcile()throws Exception{
  SharedPreferences prefs=preferences();final Receipt before;
  synchronized(STATE){before=new Receipt(prefs);}
  if(before.name.length()==0)return "";
  UpdateManifest.require(UpdateManifest.DASHBOARD.equals(before.name)||UpdateManifest.HUB.equals(before.name),"pending package");
  PackageInfo actual=installed(before.name);
  if(actual.versionCode!=before.version||!UpdateFiles.hash(new File(actual.applicationInfo.sourceDir),UpdateFiles.NEVER).equals(before.sha))return "pending";
  synchronized(STATE){
   // A different foreground task may have prepared another install while the APK was hashed.
   if(!before.same(new Receipt(prefs)))return "pending";
   UpdateManifest.require(prefs.edit().remove("pending_id").remove("pending_package").remove("pending_version").remove("pending_sha").remove("pending_file").putString("last_verified",before.name+" v"+actual.versionCode).commit(),"verified receipt");
   File file=new File(context.getFilesDir(),before.file);erase(file);
  }
  return before.name+" v"+actual.versionCode;
 }
 public void cleanup(){synchronized(STATE){
  File[] files=context.getFilesDir().listFiles();
  if(files!=null)for(File file:files){
   if(owned(file))erase(file);
   else if(file.getName().matches("vn-update-[a-f0-9-]+\\.apk\\.(manifest|signature)(\\.tmp)?")){
    File apk=new File(context.getFilesDir(),file.getName().substring(0,file.getName().indexOf(".apk")+4));
    if(!ACTIVE.contains(apk)&&!READERS.containsKey(apk)&&!retained(apk))file.delete();
   }
  }
 }}
}
