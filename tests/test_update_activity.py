"""Execute the real update Activity with deterministic UI queue and backend doubles."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_quick_entry_runtime import java_tools
ROOT=Path(__file__).resolve().parents[1]
STUBS={
'com/veloster/carplayhub/update/AndroidTls.java':'package com.veloster.carplayhub.update;public class AndroidTls {public static UpdateTransport transport(android.content.Context c,UpdateTransport.Policy p){return new UpdateTransport(p);}}',

'com/veloster/carplayhub/update/NetworkSnapshot.java':'package com.veloster.carplayhub.update;public class NetworkSnapshot {public String summary="diagnostic";public static NetworkSnapshot capture(){return new NetworkSnapshot();}public void save(java.io.File f){}}',
'android/os/Bundle.java':'package android.os;public class Bundle {}',
'android/content/Context.java':'package android.content;public class Context {public java.io.File getFilesDir(){return new java.io.File(".");}public java.io.File getExternalFilesDir(String s){return null;}}',
'android/content/Intent.java':'package android.content;public class Intent {}',
'android/content/DialogInterface.java':'''package android.content;public interface DialogInterface {interface OnClickListener{void onClick(DialogInterface d,int w);}interface OnCancelListener{void onCancel(DialogInterface d);}}''',
'android/view/View.java':'''package android.view;public class View {public boolean enabled=true;public interface OnClickListener {void onClick(View v);}public void setOnClickListener(OnClickListener l){}public void setEnabled(boolean b){enabled=b;}}''',
'android/widget/TextView.java':'''package android.widget;public class TextView extends android.view.View {public static String lastText;public TextView(android.content.Context c){}public void setText(CharSequence s){lastText=s.toString();}public void setTextSize(float f){}public void setPadding(int a,int b,int c,int d){}}''',
'android/widget/Button.java':'package android.widget;public class Button extends TextView {public Button(android.content.Context c){super(c);}}',
'android/widget/LinearLayout.java':'package android.widget;public class LinearLayout extends TextView {public LinearLayout(android.content.Context c){super(c);}public void setOrientation(int i){}public void addView(android.view.View v){}}',
'android/widget/ScrollView.java':'package android.widget;public class ScrollView extends LinearLayout {public ScrollView(android.content.Context c){super(c);}}',
'android/app/Activity.java':'''package android.app;import android.content.*;import java.util.concurrent.*;public class Activity extends Context {public static BlockingQueue<Runnable> queue=new LinkedBlockingQueue<Runnable>();public static int launches;public void onCreate(android.os.Bundle b){}protected void onResume(){}protected void onPause(){}public void setContentView(android.view.View v){}public void finish(){}public void runOnUiThread(Runnable r){queue.add(r);}public void startActivity(Intent i){launches++;}}''',
'android/app/AlertDialog.java':'''package android.app;import android.content.*;public class AlertDialog implements DialogInterface {public static AlertDialog last;public boolean dismissed;public DialogInterface.OnClickListener yes;public void show(){last=this;}public void dismiss(){dismissed=true;}public static class Builder {AlertDialog d=new AlertDialog();public Builder(Context c){}public Builder setTitle(String s){return this;}public Builder setMessage(String s){return this;}public Builder setPositiveButton(String s,DialogInterface.OnClickListener l){d.yes=l;return this;}public Builder setNegativeButton(String s,DialogInterface.OnClickListener l){return this;}public Builder setOnCancelListener(DialogInterface.OnCancelListener l){return this;}public Builder setItems(String[] s,DialogInterface.OnClickListener l){return this;}public AlertDialog create(){return d;}}}''',
'com/veloster/i18n/UiText.java':'package com.veloster.i18n;public class UiText {public static String t(String s){return s;}}',
'com/veloster/i18n/LanguageSettings.java':'package com.veloster.i18n;public class LanguageSettings {public static void init(android.content.Context c){}}',
'com/veloster/diagnostics/ErrorLog.java':'package com.veloster.diagnostics;public class ErrorLog {public static void record(String s,Throwable t){}}',
'com/veloster/carplayhub/update/UpdateManifest.java':'''package com.veloster.carplayhub.update;public class UpdateManifest {public static final String DASHBOARD="dashboard",HUB="hub";public String packageName="dashboard",url="test",sha256="hash";public int version=7;}''',
'com/veloster/carplayhub/update/UpdateFiles.java':'''package com.veloster.carplayhub.update;import java.io.*;public class UpdateFiles {public interface Cancellation{boolean cancelled();}public static void alive(Cancellation t)throws IOException{if(t.cancelled())throw new IOException("cancelled");}public static void copy(InputStream in,File out,UpdateManifest m,Cancellation t)throws IOException{alive(t);out.createNewFile();}public static byte[] read(File f,int max){return new byte[0];}}''',
'com/veloster/carplayhub/update/UpdateNetwork.java':'package com.veloster.carplayhub.update;public class UpdateNetwork {public static void requireLocalConnection(android.content.Context c){}}',
'com/veloster/carplayhub/update/UpdateTransport.java':'''package com.veloster.carplayhub.update;import java.io.*;public class UpdateTransport {public interface Policy {void check()throws IOException;}public UpdateTransport(Policy p){}public static int requests;public static String feed(String p){return "test/";}public byte[] bytes(String u,int n,UpdateFiles.Cancellation t)throws IOException{requests++;if(AndroidUpdate.networkFailed)throw new IOException("offline");return new byte[0];}public interface Reader<T>{T read(InputStream i)throws Exception;}public <T>T fetch(String s,UpdateFiles.Cancellation t,Reader<T> r)throws Exception{requests++;return r.read(new ByteArrayInputStream(new byte[0]));}}''',
'com/veloster/carplayhub/update/AndroidUpdate.java':'''package com.veloster.carplayhub.update;import java.io.*;import java.util.concurrent.*;import android.content.*;
public class AndroidUpdate {public static File folder,last;public static int installs,reconciles;public static CountDownLatch entered,release;public AndroidUpdate(Context c){}public static int remembers;public static boolean cached,networkFailed;public void cleanup(){}public void pruneSaved(UpdateFiles.Cancellation t){}public void remember(Prepared p,byte[] d,byte[] s,UpdateFiles.Cancellation t){remembers++;}public Prepared saved(String n,UpdateFiles.Cancellation t)throws Exception{if(!cached)return null;File f=newStage();f.createNewFile();return new Prepared(new UpdateManifest(),f);}public void discard(File f){f.delete();}public String reconcile(){reconciles++;return "";}public File newStage(){last=new File(folder,"stage-"+System.nanoTime()+".apk");return last;}public UpdateManifest metadata(byte[] a,byte[] b,String s){return new UpdateManifest();}public Prepared finish(File f,UpdateManifest m,UpdateFiles.Cancellation t){return new Prepared(m,f);}public Intent installer(Prepared p,UpdateFiles.Cancellation t)throws Exception{installs++;if(entered!=null){entered.countDown();release.await(3,TimeUnit.SECONDS);}return new Intent();}public static class Prepared {public UpdateManifest manifest;public File file;public Prepared(UpdateManifest m,File f){manifest=m;file=f;}}}''',
}
HARNESS='''import com.veloster.carplayhub.UpdateActivity;import com.veloster.carplayhub.update.*;import android.app.*;import java.lang.reflect.*;import java.util.concurrent.*;
public class ActivityHarness {
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static void call(UpdateActivity a,String name)throws Exception{Method m=UpdateActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(a);}
 static void online(UpdateActivity a)throws Exception{Method m=UpdateActivity.class.getDeclaredMethod("online",String.class);m.setAccessible(true);m.invoke(a,"dashboard");}
 static Runnable result()throws Exception{Runnable r=Activity.queue.poll(4,TimeUnit.SECONDS);if(r==null)throw new AssertionError("worker did not complete");return r;}
 public static void main(String[] args)throws Exception{
  AndroidUpdate.folder=new java.io.File(args[0]);UpdateActivity a=new UpdateActivity();a.onCreate(null);call(a,"onResume");result().run();check(Activity.launches==0&&AndroidUpdate.reconciles==1,"resume only reconciles");
  online(a);online(a);result().run();check(UpdateTransport.requests==3,"duplicate click produces one fetch");check(AlertDialog.last!=null&&Activity.launches==0,"validated package still needs visible install action");
  call(a,"onPause");check(AlertDialog.last.dismissed&&!AndroidUpdate.last.exists(),"pause dismisses and deletes offered file");AlertDialog.last.yes.onClick(AlertDialog.last,1);check(AndroidUpdate.installs==0,"stale confirmation while paused cannot start handoff");
  call(a,"onResume");result().run();AlertDialog.last=null;online(a);Runnable late=result();call(a,"onPause");late.run();check(AlertDialog.last==null&&!AndroidUpdate.last.exists(),"late fetch does not open dialog after pause");
  call(a,"onResume");result().run();online(a);result().run();AndroidUpdate.entered=new CountDownLatch(1);AndroidUpdate.release=new CountDownLatch(1);AlertDialog.last.yes.onClick(AlertDialog.last,1);check(AndroidUpdate.entered.await(3,TimeUnit.SECONDS),"installer preparation started");call(a,"onPause");AndroidUpdate.release.countDown();result().run();check(Activity.launches==0,"pause during handoff blocks installer launch");
  AndroidUpdate.entered=null;call(a,"onResume");result().run();online(a);result().run();AlertDialog.last.yes.onClick(AlertDialog.last,1);result().run();check(Activity.launches==1,"foreground explicit action opens installer once");call(a,"onPause");call(a,"onResume");result().run();check(Activity.launches==1,"return does not launch again");
  int before=UpdateTransport.requests;AndroidUpdate.cached=true;online(a);result().run();check(UpdateTransport.requests==before+2,"cached identical APK skips download");call(a,"onPause");call(a,"onResume");result().run();
  before=UpdateTransport.requests;AndroidUpdate.networkFailed=true;online(a);result().run();check(AlertDialog.last!=null&&UpdateTransport.requests==before+1,"offline check offers verified cache");check(Activity.launches==1,"offline fallback never auto-installs");check(AndroidUpdate.remembers>0,"successful fetch saved before offer");
  System.out.println("PASS "+checks+" update Activity lifecycle checks");
 }
}'''
class UpdateActivityTests(unittest.TestCase):
    def test_activity_lifecycle(self):
        java,javac=java_tools()
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);sources=[]
            for name,body in STUBS.items():
                p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body);sources.append(p)
            p=root/'ActivityHarness.java';p.write_text(HARNESS);sources.append(p)
            sources.append(ROOT/'apps/hub/src/com/veloster/carplayhub/UpdateActivity.java')
            r=subprocess.run([javac,'-encoding','UTF-8','-source','7','-target','7','-d',str(root),*map(str,sources)],capture_output=True,text=True)
            self.assertEqual(r.returncode,0,r.stderr)
            r=subprocess.run([java,'-cp',str(root),'ActivityHarness',str(root)],capture_output=True,text=True,timeout=20)
            self.assertEqual(r.returncode,0,r.stdout+r.stderr)
            self.assertIn('PASS 14',r.stdout)
if __name__=='__main__':unittest.main()
