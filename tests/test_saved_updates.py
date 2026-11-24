"""Saved updates with real V1 APK/release signatures and API17 adapter doubles."""
from pathlib import Path
import hashlib,subprocess,tempfile,unittest,zipfile
from test_quick_entry_runtime import java_tools
from test_update_adapter import STUBS
ROOT=Path(__file__).resolve().parents[1]
HARNESS=r'''package com.veloster.carplayhub.update;
import java.io.*;import java.nio.file.*;import java.security.*;import android.content.*;import android.content.pm.*;
public class SavedHarness{
 static Context c;static AndroidUpdate u;static File apk,root;static String certificate;static KeyPair keys;static int checks;
 static void check(boolean b,String text){checks++;if(!b)throw new AssertionError(text);}
 static byte[] metadata(int v)throws Exception{return ("VNUPDATE1\npackage=com.veloster.dashboard\nversion="+v+"\nbytes="+apk.length()+"\nsha256="+UpdateFiles.hash(apk,UpdateFiles.NEVER)+"\ncertificate="+certificate+"\nproduct=jsn_kr\nincremental=eng.vmadmin.20250902.081552\nsdk=17\nurl=https://github.com/reikop/veloster-n-dashboard/releases/download/test/app.apk\n").getBytes("US-ASCII");}
 static byte[] signature(byte[] data)throws Exception{java.security.Signature s=java.security.Signature.getInstance("SHA256withRSA");s.initSign(keys.getPrivate());s.update(data);return s.sign();}
 static AndroidUpdate.Prepared candidate(int v)throws Exception{byte[] data=metadata(v);UpdateManifest m=u.metadata(data,signature(data),UpdateManifest.DASHBOARD);File stage=u.newStage();InputStream in=new FileInputStream(apk);try{UpdateFiles.copy(in,stage,m,UpdateFiles.NEVER);}finally{in.close();}c.pm.archive.versionCode=v;return u.finish(stage,m,UpdateFiles.NEVER);}
 static AndroidUpdate.Prepared save(int v)throws Exception{AndroidUpdate.Prepared p=candidate(v);byte[] d=metadata(v);u.remember(p,d,signature(d),UpdateFiles.NEVER);u.discard(p.file);return p;}
 static String key(){return "saved_"+UpdateManifest.DASHBOARD;}
 public static void main(String[]args)throws Exception{
  apk=new File(args[0]);root=new File(args[1]);c=new Context();c.files=root;u=new AndroidUpdate(c);
  byte[] cert=Files.readAllBytes(new File(args[2]).toPath());certificate=UpdateFiles.hex(MessageDigest.getInstance("SHA-256").digest(cert));KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);keys=gen.generateKeyPair();UpdateTrust.value=keys.getPublic();
  PackageInfo installed=new PackageInfo();installed.packageName=UpdateManifest.DASHBOARD;installed.versionCode=6;installed.signatures=new android.content.pm.Signature[]{new android.content.pm.Signature(cert)};c.pm.installed=installed;
  PackageInfo archive=new PackageInfo();archive.packageName=installed.packageName;archive.versionCode=7;archive.signatures=installed.signatures;c.pm.archive=archive;
  AndroidUpdate.Prepared first=save(7);check(first.file.exists(),"decline retains verified APK");u=new AndroidUpdate(c);u.cleanup();check(first.file.exists(),"recreated Activity retains cache");
  AndroidUpdate.Prepared held=u.saved(installed.packageName,UpdateFiles.NEVER);check(held!=null&&!held.file.equals(first.file),"offline revalidation returns owned copy");u.discard(held.file);check(!held.file.exists()&&first.file.exists(),"discard offer preserves cache");
  try{u.saved(installed.packageName,new UpdateFiles.Cancellation(){public boolean cancelled(){return true;}});throw new AssertionError("cancel ignored");}catch(InterruptedIOException expected){check(first.file.exists(),"cancel does not invalidate cache");}
  AndroidUpdate.Prepared broken=candidate(8);Files.write(broken.file.toPath(),new byte[]{9});try{byte[] d=metadata(8);u.remember(broken,d,signature(d),UpdateFiles.NEVER);throw new AssertionError("bad replacement accepted");}catch(IOException expected){check(c.prefs.getString(key(),"").equals(first.file.getName()),"failed replacement keeps old cache");}u.discard(broken.file);
  AndroidUpdate.Prepared next=candidate(8);c.prefs.fail=true;try{byte[] d=metadata(8);u.remember(next,d,signature(d),UpdateFiles.NEVER);throw new AssertionError("commit accepted");}catch(IOException expected){check(first.file.exists(),"receipt failure preserves old APK");}c.prefs.fail=false;u.discard(next.file);
  c.pm.archive.versionCode=7;AndroidUpdate.Prepared a=u.saved(installed.packageName,UpdateFiles.NEVER),b=u.saved(installed.packageName,UpdateFiles.NEVER);u.discard(a.file);check(b.file.exists(),"independent concurrent offers");
  next=save(8);check(!first.file.exists()&&next.file.exists()&&b.file.exists(),"replacement keeps another Activity offer alive");u.discard(b.file);
  AndroidUpdate.Prepared older=candidate(7);byte[] d=metadata(7);u.remember(older,d,signature(d),UpdateFiles.NEVER);u.discard(older.file);check(c.prefs.getString(key(),"").equals(next.file.getName()),"older import does not replace newer saved version");
  c.pm.archive.versionCode=8;installed.versionCode=8;u.pruneSaved(UpdateFiles.NEVER);check(!next.file.exists()&&c.prefs.getString(key(),"").equals(""),"USB/external upgrade removes obsolete saved APK");installed.versionCode=6;
  first=save(7);Files.write(new File(first.file+".signature").toPath(),new byte[256]);check(u.saved(installed.packageName,UpdateFiles.NEVER)==null&&!first.file.exists(),"tampered release signature rejected and removed");
  first=save(7);Files.write(first.file.toPath(),new byte[]{5});check(u.saved(installed.packageName,UpdateFiles.NEVER)==null&&!first.file.exists(),"tampered APK rejected and removed");
  File foreign=new File(root,"unrelated.apk");Files.write(foreign.toPath(),new byte[]{1});c.prefs.edit().putString(key(),"../unrelated.apk").commit();check(u.saved(installed.packageName,UpdateFiles.NEVER)==null&&foreign.exists(),"unsafe pointer cannot delete unrelated files");
  first=save(7);android.os.Build.PRODUCT="other";check(u.saved(installed.packageName,UpdateFiles.NEVER)==null,"current target rechecked offline");android.os.Build.PRODUCT="jsn_kr";
  File partial=new File(root,"vn-update-0000.apk");Files.write(partial.toPath(),new byte[]{1});File side=new File(partial+".manifest.tmp");Files.write(side.toPath(),new byte[]{1});u.cleanup();check(!partial.exists()&&!side.exists()&&foreign.exists(),"abandoned partial and metadata cleanup only owned files");
  first=save(7);u.installer(first,UpdateFiles.NEVER);u.discard(first.file);check(first.file.exists(),"pending installer cache kept");File actual=new File(root,"installed.apk");Files.copy(first.file.toPath(),actual.toPath());installed.applicationInfo.sourceDir=actual.toString();installed.versionCode=7;check(u.reconcile().length()>0,"installed hash reconciled");u.pruneSaved(UpdateFiles.NEVER);check(!first.file.exists(),"completed installation clears saved copy");
  System.out.println("PASS "+checks+" signed saved-update checks");
 }
}'''
class SavedUpdateTests(unittest.TestCase):
 def test_signed_cache_lifecycle(self):
  java,javac=map(Path,java_tools())
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp);data=root/'data';data.mkdir()
   def run(cmd):
    r=subprocess.run(list(map(str,cmd)),capture_output=True,text=True,timeout=40);self.assertEqual(r.returncode,0,r.stdout+r.stderr);return r.stdout
   run([java.with_name('keytool'),'-genkeypair','-alias','fixture','-keyalg','RSA','-keysize','2048','-validity','2','-dname','CN=Saved update test','-keystore',root/'test.p12','-storepass','fixture-password','-keypass','fixture-password'])
   run([java.with_name('keytool'),'-exportcert','-alias','fixture','-keystore',root/'test.p12','-storepass','fixture-password','-file',root/'cert.der'])
   with zipfile.ZipFile(root/'fixture.apk','w') as z:z.writestr('classes.dex',b'dex\n035\0fixture');z.writestr('AndroidManifest.xml',b'synthetic-not-installable')
   run([java.with_name('jarsigner'),'-keystore',root/'test.p12','-storepass','fixture-password','-sigalg','SHA256withRSA','-digestalg','SHA-256',root/'fixture.apk','fixture'])
   sources=[]
   for name,body in STUBS.items():
    p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body);sources.append(p)
   p=root/'UpdateTrust.java';p.write_text('package com.veloster.carplayhub.update;public class UpdateTrust{public static java.security.PublicKey value;public static java.security.PublicKey key(){return value;}}');sources.append(p)
   p=root/'SavedHarness.java';p.write_text(HARNESS);sources.append(p)
   src=ROOT/'apps/hub/src/com/veloster/carplayhub/update';sources.extend(src/(n+'.java') for n in ['AndroidUpdate','UpdateManifest','UpdateFiles'])
   run([javac,'-source','7','-target','7','-d',root,*sources]);print(run([java,'-cp',root,'com.veloster.carplayhub.update.SavedHarness',root/'fixture.apk',data,root/'cert.der']).strip())
