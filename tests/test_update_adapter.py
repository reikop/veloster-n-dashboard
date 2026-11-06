"""Production API17 adapter with PackageManager/preferences doubles; no device installation."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_quick_entry_runtime import java_tools
ROOT=Path(__file__).resolve().parents[1]
STUBS={
'android/os/Build.java':'''package android.os;public class Build {public static String PRODUCT="jsn_kr";public static class VERSION {public static String INCREMENTAL="eng.vmadmin.20250902.081552";public static int SDK_INT=17;}}''',
'android/content/ComponentName.java':'''package android.content;public class ComponentName {public String pkg,name;public ComponentName(String p,String n){pkg=p;name=n;}}''',
'android/net/Uri.java':'''package android.net;import java.io.File;public class Uri {public File file;public static Uri fromFile(File f){Uri u=new Uri();u.file=f;return u;}}''',
'android/content/Intent.java':'''package android.content;import android.net.Uri;public class Intent {public static final String ACTION_VIEW="view";public ComponentName component;public Uri data;public Intent(String s){}public void setDataAndType(Uri u,String s){data=u;}public void setComponent(ComponentName c){component=c;}}''',
'android/content/SharedPreferences.java':'''package android.content;public interface SharedPreferences {String getString(String k,String d);int getInt(String k,int d);Editor edit();interface Editor {Editor putString(String k,String v);Editor putInt(String k,int v);Editor remove(String k);boolean commit();}}''',
'android/content/MemoryPreferences.java':'''package android.content;import java.util.*;public class MemoryPreferences implements SharedPreferences {public Map<String,Object> data=new HashMap<String,Object>();public boolean fail;public String getString(String k,String d){return data.containsKey(k)?(String)data.get(k):d;}public int getInt(String k,int d){return data.containsKey(k)?(Integer)data.get(k):d;}public Editor edit(){return new Editor(){Map<String,Object> next=new HashMap<String,Object>(data);public Editor putString(String k,String v){next.put(k,v);return this;}public Editor putInt(String k,int v){next.put(k,v);return this;}public Editor remove(String k){next.remove(k);return this;}public boolean commit(){if(fail)return false;data=next;return true;}};}}''',
'android/content/Context.java':'''package android.content;import java.io.File;import android.content.pm.PackageManager;public class Context {public File files;public PackageManager pm=new PackageManager();public MemoryPreferences prefs=new MemoryPreferences();public Context getApplicationContext(){return this;}public File getFilesDir(){return files;}public PackageManager getPackageManager(){return pm;}public SharedPreferences getSharedPreferences(String s,int n){return prefs;}}''',
'android/content/pm/Signature.java':'''package android.content.pm;public class Signature {byte[] bytes;public Signature(byte[] b){bytes=b;}public byte[] toByteArray(){return bytes;}}''',
'android/content/pm/PackageInfo.java':'''package android.content.pm;public class PackageInfo {public String packageName;public int versionCode;public Signature[] signatures;public ApplicationInfo applicationInfo=new ApplicationInfo();}''',
'android/content/pm/ApplicationInfo.java':'''package android.content.pm;public class ApplicationInfo {public static final int FLAG_SYSTEM=1,FLAG_UPDATED_SYSTEM_APP=128;public String sourceDir;public boolean enabled=true;public int flags=1;}''',
'android/content/pm/ActivityInfo.java':'''package android.content.pm;public class ActivityInfo {public boolean enabled=true,exported=true;public String packageName="com.android.packageinstaller",name="Installer";public ApplicationInfo applicationInfo=new ApplicationInfo();}''',
'android/content/pm/ResolveInfo.java':'''package android.content.pm;public class ResolveInfo {public ActivityInfo activityInfo=new ActivityInfo();}''',
'android/content/pm/PackageManager.java':'''package android.content.pm;import android.content.Intent;public class PackageManager {public static final int GET_SIGNATURES=64,MATCH_DEFAULT_ONLY=65536;public PackageInfo installed,archive;public Runnable onGet;public ResolveInfo resolver=new ResolveInfo();public PackageInfo getPackageInfo(String s,int n){if(onGet!=null){Runnable r=onGet;onGet=null;r.run();}return installed;}public PackageInfo getPackageArchiveInfo(String s,int n){return archive;}public ResolveInfo resolveActivity(Intent i,int n){return resolver;}}''',
}
HARNESS='''package com.veloster.carplayhub.update;
import android.content.*;import android.content.pm.*;import java.io.*;import java.security.*;import java.nio.file.*;
public class AdapterHarness {
 static int count;interface Action{void run()throws Exception;}static void check(boolean b,String m){count++;if(!b)throw new AssertionError(m);}
 static void reject(Action a,String m)throws Exception{boolean bad=false;try{a.run();}catch(IOException e){bad=true;}check(bad,m);}
 public static void main(String[] args)throws Exception{
  final Context c=new Context();c.files=new File(args[0]);final AndroidUpdate u=new AndroidUpdate(c);final File f=u.newStage();Files.write(f.toPath(),new byte[]{1,2,3});
  byte[] signer={4,5,6};String digest=UpdateFiles.hex(MessageDigest.getInstance("SHA-256").digest(signer));
  KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);KeyPair pair=gen.generateKeyPair();
  byte[] bytes=("VNUPDATE1\\npackage=com.veloster.dashboard\\nversion=7\\nbytes=3\\nsha256="+UpdateFiles.hash(f,UpdateFiles.NEVER)+"\\ncertificate="+digest+"\\nproduct=jsn_kr\\nincremental=eng.vmadmin.20250902.081552\\nsdk=17\\nurl=https://github.com/reikop/veloster-n-dashboard/releases/download/test/app.apk\\n").getBytes("US-ASCII");
  java.security.Signature sign=java.security.Signature.getInstance("SHA256withRSA");sign.initSign(pair.getPrivate());sign.update(bytes);final UpdateManifest m=UpdateManifest.verify(bytes,sign.sign(),pair.getPublic());
  PackageInfo installed=new PackageInfo();installed.packageName=m.packageName;installed.versionCode=6;installed.signatures=new android.content.pm.Signature[]{new android.content.pm.Signature(signer)};installed.applicationInfo.sourceDir=f.getPath();c.pm.installed=installed;
  u.target(m,m.packageName);count++;final AndroidUpdate.Prepared p=new AndroidUpdate.Prepared(m,f);
  android.os.Build.PRODUCT="other";reject(new Action(){public void run()throws Exception{u.target(m,m.packageName);}},"actual device gate");android.os.Build.PRODUCT="jsn_kr";
  installed.signatures=new android.content.pm.Signature[]{new android.content.pm.Signature(new byte[]{8})};reject(new Action(){public void run()throws Exception{u.target(m,m.packageName);}},"installed signer gate");installed.signatures=new android.content.pm.Signature[]{new android.content.pm.Signature(signer)};
  c.pm.resolver.activityInfo.applicationInfo.flags=0;reject(new Action(){public void run()throws Exception{u.installer(p,UpdateFiles.NEVER);}},"non-system installer rejected");check(c.prefs.data.isEmpty(),"no receipt before valid installer");c.pm.resolver.activityInfo.applicationInfo.flags=1;
  c.prefs.fail=true;reject(new Action(){public void run()throws Exception{u.installer(p,UpdateFiles.NEVER);}},"persist failure blocks launch");c.prefs.fail=false;
  reject(new Action(){public void run()throws Exception{u.installer(p,new UpdateFiles.Cancellation(){public boolean cancelled(){return true;}});}},"cancelled handoff rejected");
  Intent intent=u.installer(p,UpdateFiles.NEVER);check(intent.component.pkg.equals("com.android.packageinstaller"),"explicit system component");check(c.prefs.getString("pending_sha","").equals(m.sha256),"receipt before handoff");
  check(u.reconcile().equals("pending"),"launch is not success");installed.versionCode=7;File wrong=new File(c.files,"wrong.apk");Files.write(wrong.toPath(),new byte[]{9});installed.applicationInfo.sourceDir=wrong.getPath();check(u.reconcile().equals("pending"),"version match alone is not success");
  File actual=new File(c.files,"actual.apk");Files.copy(f.toPath(),actual.toPath());installed.applicationInfo.sourceDir=actual.getPath();
  final String oldId=c.prefs.getString("pending_id","");c.pm.onGet=new Runnable(){public void run(){c.prefs.edit().putString("pending_id","new-receipt").commit();}};
  check(u.reconcile().equals("pending")&&c.prefs.getString("pending_id","").equals("new-receipt")&&f.exists(),"older reconciliation cannot clear replacement receipt");
  c.prefs.edit().putString("pending_id",oldId).commit();
  c.prefs.fail=true;reject(new Action(){public void run()throws Exception{u.reconcile();}},"receipt clear failure preserves stage");check(f.exists(),"failed confirmation keeps APK");c.prefs.fail=false;
  check(u.reconcile().equals(m.packageName+" v7"),"installed hash confirms success");check(!f.exists()&&!c.prefs.data.containsKey("pending_package"),"receipt and stage cleaned after verification");check(new AndroidUpdate(c).reconcile().equals(""),"restart does not install/repeat");
  File abandoned=new File(c.files,"vn-update-0000.apk"),pending=u.newStage(),active=u.newStage();Files.write(active.toPath(),new byte[]{3});Files.write(abandoned.toPath(),new byte[]{1});Files.write(pending.toPath(),new byte[]{2});c.prefs.edit().putString("pending_file",pending.getName()).commit();u.cleanup();check(!abandoned.exists()&&pending.exists()&&actual.exists(),"cleanup only owned abandoned candidates");
  new AndroidUpdate(c).cleanup();check(active.exists(),"another Activity cannot delete active download");u.discard(active);check(!active.exists(),"cancellation releases active download");u.discard(pending);check(pending.exists(),"cancellation preserves persisted handoff");u.discard(actual);check(actual.exists(),"discard only owned stages");
  System.out.println("PASS "+count+" Android update adapter checks");
 }
}'''
class AdapterTests(unittest.TestCase):
    def test_adapter(self):
        java,javac=java_tools()
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);sources=[]
            for name,body in STUBS.items():
                p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body);sources.append(p)
            harness=root/'AdapterHarness.java';harness.write_text(HARNESS);sources.append(harness)
            src=ROOT/'apps/hub/src/com/veloster/carplayhub/update'
            sources += [src/(name+'.java') for name in ('AndroidUpdate','UpdateManifest','UpdateFiles','UpdateTrust')]
            r=subprocess.run([javac,'-source','7','-target','7','-d',str(root),*map(str,sources)],capture_output=True,text=True)
            self.assertEqual(r.returncode,0,r.stderr)
            r=subprocess.run([java,'-cp',str(root),'com.veloster.carplayhub.update.AdapterHarness',str(root)],capture_output=True,text=True,timeout=20)
            self.assertEqual(r.returncode,0,r.stdout+r.stderr)
            self.assertIn('PASS 22',r.stdout)
if __name__=='__main__':unittest.main()
