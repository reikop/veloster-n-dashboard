"""Execute actual quick-entry service/receiver against a small Android lifecycle harness.

This tests Java control flow, not Android rendering, permission enforcement, OEM
notification visibility or real boot delivery. No emulator or vehicle is used.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
"android/content/SharedPreferences.java": '''package android.content;
public interface SharedPreferences {
 int getInt(String k,int d); String getString(String k,String d); float getFloat(String k,float d); boolean getBoolean(String k,boolean d);
 Editor edit(); interface Editor {Editor putInt(String k,int v);Editor putString(String k,String v);Editor putFloat(String k,float v);Editor putBoolean(String k,boolean v);boolean commit();}
}''',
"android/content/MemoryPreferences.java": '''package android.content;
import java.util.HashMap;
public final class MemoryPreferences implements SharedPreferences, SharedPreferences.Editor {
 public final HashMap<String,Object> values=new HashMap<String,Object>();public boolean fail;
 public int getInt(String k,int d){return values.containsKey(k)?(Integer)values.get(k):d;}
 public String getString(String k,String d){return values.containsKey(k)?(String)values.get(k):d;}
 public float getFloat(String k,float d){return values.containsKey(k)?(Float)values.get(k):d;}
 public boolean getBoolean(String k,boolean d){return values.containsKey(k)?(Boolean)values.get(k):d;}
 public Editor edit(){return this;} public Editor putInt(String k,int v){values.put(k,v);return this;}
 public Editor putString(String k,String v){values.put(k,v);return this;}public Editor putFloat(String k,float v){values.put(k,v);return this;}
 public Editor putBoolean(String k,boolean v){values.put(k,v);return this;}public boolean commit(){return !fail;}
}''',
"android/content/Intent.java": '''package android.content;
public class Intent {
 public static final String ACTION_SCREEN_OFF="android.intent.action.SCREEN_OFF",ACTION_SCREEN_ON="android.intent.action.SCREEN_ON",ACTION_USER_PRESENT="android.intent.action.USER_PRESENT",ACTION_BOOT_COMPLETED="android.intent.action.BOOT_COMPLETED";
 public static final int FLAG_ACTIVITY_NEW_TASK=0x10000000,FLAG_ACTIVITY_CLEAR_TOP=0x4000000,FLAG_ACTIVITY_SINGLE_TOP=0x20000000;
 private String action;public Class<?> component;public int flags;
 public Intent(){} public Intent(String a){action=a;}public Intent(Context c,Class<?> cls){component=cls;}
 public Intent setAction(String a){action=a;return this;}public String getAction(){return action;}
 public Intent addFlags(int f){flags|=f;return this;}
}''',
"android/content/IntentFilter.java": '''package android.content;public class IntentFilter {public void addAction(String a){}}''',
"android/content/BroadcastReceiver.java": '''package android.content;public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}''',
"android/content/ComponentName.java": '''package android.content;public class ComponentName {}''',
"android/content/Context.java": '''package android.content;
import android.app.Service;import android.app.KeyguardManager;import android.app.NotificationManager;import android.os.PowerManager;import android.view.WindowManager;
public class Context {
 public static final int MODE_PRIVATE=0;public static final String WINDOW_SERVICE="window",POWER_SERVICE="power",KEYGUARD_SERVICE="keyguard",NOTIFICATION_SERVICE="notification";
 public static Env env=new Env();
 public static class Env {
  public MemoryPreferences prefs=new MemoryPreferences();public WindowManager windows=new WindowManager();public PowerManager power=new PowerManager();public KeyguardManager keyguard=new KeyguardManager();public NotificationManager notifications=new NotificationManager();
  public int starts,foregroundStarts,stops,activities,stopSelf,unregisters;public boolean foreground,failForeground,failRegister,failStart;
  public BroadcastReceiver receiver;public Service running;public Intent lastStarted,lastActivity;
 }
 public SharedPreferences getSharedPreferences(String name,int mode){return env.prefs;}
 public Object getSystemService(String name){if(name.equals(WINDOW_SERVICE))return env.windows;if(name.equals(POWER_SERVICE))return env.power;if(name.equals(KEYGUARD_SERVICE))return env.keyguard;if(name.equals(NOTIFICATION_SERVICE))return env.notifications;return null;}
 public ComponentName startService(Intent i){if(env.failStart)throw new SecurityException("denied");env.starts++;env.lastStarted=i;return new ComponentName();}
 public ComponentName startForegroundService(Intent i){env.foregroundStarts++;return startService(i);}
 public boolean stopService(Intent i){env.stops++;if(env.running!=null)env.running.onDestroy();return true;}
 public void startActivity(Intent i){env.activities++;env.lastActivity=i;}
 public Intent registerReceiver(BroadcastReceiver r,IntentFilter f){if(env.failRegister)throw new SecurityException("denied");env.receiver=r;return null;}
 public void unregisterReceiver(BroadcastReceiver r){env.unregisters++;if(env.receiver==r)env.receiver=null;}
}''',
"android/content/DialogInterface.java": '''package android.content;public interface DialogInterface {interface OnClickListener {void onClick(DialogInterface d,int n);}}''',
"android/app/Activity.java": '''package android.app;public class Activity extends android.content.Context {}''',
"android/app/AlertDialog.java": '''package android.app;import android.content.Context;import android.content.DialogInterface;
public class AlertDialog {public static class Builder {
 public Builder(Context c){}public Builder setTitle(CharSequence s){return this;}public Builder setMessage(CharSequence s){return this;}
 public Builder setSingleChoiceItems(CharSequence[] s,int n,DialogInterface.OnClickListener l){return this;}
 public Builder setPositiveButton(CharSequence s,DialogInterface.OnClickListener l){return this;}public Builder setNegativeButton(CharSequence s,DialogInterface.OnClickListener l){return this;}
 public AlertDialog show(){return new AlertDialog();}
}}''',
"android/app/Service.java": '''package android.app;
import android.content.Context;import android.content.Intent;import android.content.res.Configuration;import android.os.IBinder;
public abstract class Service extends Context {
 public static final int START_NOT_STICKY=2,START_STICKY=1;
 public void onCreate(){env.running=this;}public int onStartCommand(Intent i,int f,int id){return 0;}
 public void startForeground(int id,Notification n){if(env.failForeground)throw new SecurityException("denied");env.foreground=true;}
 public void stopForeground(boolean remove){env.foreground=false;}public void stopSelf(){env.stopSelf++;}
 public void onConfigurationChanged(Configuration c){}public void onDestroy(){env.running=null;}public abstract IBinder onBind(Intent i);
}''',
"android/app/KeyguardManager.java": '''package android.app;public class KeyguardManager {public boolean locked;public boolean isKeyguardLocked(){return locked;}}''',
"android/app/Notification.java": '''package android.app;import android.content.Context;
public class Notification {public static class Builder {
 public Builder(Context c){}public Builder setSmallIcon(int v){return this;}public Builder setContentTitle(CharSequence s){return this;}
 public Builder setContentText(CharSequence s){return this;}public Builder setContentIntent(PendingIntent p){return this;}
 public Builder setOngoing(boolean v){return this;}public Builder setOnlyAlertOnce(boolean v){return this;}
 public Builder addAction(int icon,CharSequence s,PendingIntent p){return this;}public Builder setChannelId(String s){return this;}public Notification build(){return new Notification();}
}}''',
"android/app/NotificationChannel.java": '''package android.app;public class NotificationChannel {public NotificationChannel(String id,CharSequence name,int importance){}}''',
"android/app/NotificationManager.java": '''package android.app;public class NotificationManager {public int channels;public void createNotificationChannel(NotificationChannel c){channels++;}}''',
"android/app/PendingIntent.java": '''package android.app;import android.content.Context;import android.content.Intent;
public class PendingIntent {public static final int FLAG_UPDATE_CURRENT=0x8000000;
 public static PendingIntent getActivity(Context c,int r,Intent i,int f){return new PendingIntent();}
 public static PendingIntent getService(Context c,int r,Intent i,int f){return new PendingIntent();}
}''',
"android/content/res/Configuration.java": '''package android.content.res;public class Configuration {}''',
"android/graphics/PixelFormat.java": '''package android.graphics;public class PixelFormat {public static final int TRANSLUCENT=-3;}''',
"android/os/Build.java": '''package android.os;public class Build {public static class VERSION {public static int SDK_INT=17;}}''',
"android/os/IBinder.java": '''package android.os;public interface IBinder {}''',
"android/os/PowerManager.java": '''package android.os;public class PowerManager {public boolean on=true;public boolean isScreenOn(){return on;}}''',
"android/util/DisplayMetrics.java": '''package android.util;public class DisplayMetrics {public int widthPixels,heightPixels;public float density;}''',
"android/util/Log.java": '''package android.util;public class Log {public static int e(String a,String b,Throwable e){return 0;}public static int w(String a,String b,Throwable e){return 0;}}''',
"android/view/Gravity.java": '''package android.view;public class Gravity {public static final int TOP=48,LEFT=3;}''',
"android/view/MotionEvent.java": '''package android.view;public class MotionEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3;
 int action;float x,y;public MotionEvent(int a,float x,float y){action=a;this.x=x;this.y=y;}
 public int getActionMasked(){return action;}public float getRawX(){return x;}public float getRawY(){return y;}
}''',
"android/view/View.java": '''package android.view;public class View {
 public interface OnClickListener {void onClick(View v);}public interface OnTouchListener {boolean onTouch(View v,MotionEvent e);}
 public OnClickListener click;public OnTouchListener touch;public void setOnClickListener(OnClickListener l){click=l;}
 public void setOnTouchListener(OnTouchListener l){touch=l;}public boolean performClick(){if(click!=null)click.onClick(this);return true;}
 public void setContentDescription(CharSequence s){}
}''',
"android/view/ViewConfiguration.java": '''package android.view;import android.content.Context;public class ViewConfiguration {public static ViewConfiguration get(Context c){return new ViewConfiguration();}public int getScaledTouchSlop(){return 8;}}''',
"android/view/WindowManager.java": '''package android.view;import android.util.DisplayMetrics;
public class WindowManager {
 public int adds,removes,updates,width=800,height=480;public boolean denyAdd,denyUpdate;public View view;public LayoutParams last;
 public static class LayoutParams {
  public static final int TYPE_PHONE=2002,FLAG_NOT_FOCUSABLE=8,FLAG_NOT_TOUCH_MODAL=32;
  public int x,y,gravity,width,height,type,flags;
  public LayoutParams(int w,int h,int t,int f,int format){width=w;height=h;type=t;flags=f;}
 }
 public class Display {public void getMetrics(DisplayMetrics m){m.widthPixels=width;m.heightPixels=height;m.density=1;}}
 public Display getDefaultDisplay(){return new Display();}
 public void addView(View v,LayoutParams p){adds++;if(denyAdd)throw new SecurityException("overlay denied");view=v;last=p;}
 public void removeView(View v){removes++;if(view==v)view=null;}
 public void updateViewLayout(View v,LayoutParams p){updates++;if(denyUpdate)throw new IllegalStateException("window disappeared");last=p;}
}''',
"android/widget/Button.java": '''package android.widget;import android.content.Context;import android.view.View;
public class Button extends View {public String label;public Button(Context c){}public void setText(CharSequence s){label=s.toString();}public void setTextSize(float s){}
 public void setPadding(int a,int b,int c,int d){}public void setMinWidth(int i){}public void setMinimumWidth(int i){}public void setMinHeight(int i){}public void setMinimumHeight(int i){}
}''',
"android/R.java": '''package android;public class R {public static class drawable {public static final int ic_menu_view=1,ic_menu_close_clear_cancel=2;}}''',
"com/veloster/carplayhub/HubActivity.java": '''package com.veloster.carplayhub;public class HubActivity {}''',
}
HARNESS = r'''
package com.veloster.carplayhub;
import android.content.*;import android.app.Service;import android.os.Build;import android.view.*;import android.content.res.Configuration;
public final class QuickEntryRuntimeHarness {
 static void check(boolean b,String label){if(!b)throw new AssertionError(label);}
 static Context reset(){Context.env=new Context.Env();Build.VERSION.SDK_INT=17;return new Context();}
 static void mode(int m){Context.env.prefs.edit().putInt("mode",m).commit();}
 static QuickEntryService start(int m){mode(m);QuickEntryService s=new QuickEntryService();s.onCreate();s.onStartCommand(null,0,1);return s;}
 static void broadcast(String a){check(Context.env.receiver!=null,"screen receiver registered");Context.env.receiver.onReceive(new Context(),new Intent(a));}
 static void optedOutBoot(){
  Context c=reset();QuickEntryReceiver r=new QuickEntryReceiver();r.onReceive(c,new Intent(Intent.ACTION_BOOT_COMPLETED));
  check(Context.env.starts==0&&Context.env.activities==0,"default boot must remain off");
  mode(9);r.onReceive(c,new Intent(Intent.ACTION_BOOT_COMPLETED));check(Context.env.starts==0,"corrupt mode not enabled");
  mode(2);r.onReceive(c,new Intent("external.ENABLE"));check(Context.env.starts==0,"unrelated broadcast ignored");
 }
 static void enabledBoot(){
  Context c=reset();mode(2);QuickEntryReceiver r=new QuickEntryReceiver();r.onReceive(c,new Intent(Intent.ACTION_BOOT_COMPLETED));
  check(Context.env.starts==1&&Context.env.activities==0,"boot restores service only");
  check(Context.env.lastStarted.component==QuickEntryService.class,"explicit service component");
  r.onReceive(c,new Intent("android.intent.action.MY_PACKAGE_REPLACED"));check(Context.env.starts==2&&Context.env.activities==0,"upgrade restores service only");
 }
 static void unknownStartCannotEnable(){
  reset();QuickEntryService s=new QuickEntryService();s.onCreate();int result=s.onStartCommand(new Intent("external.ENABLE"),0,1);
  check(result==Service.START_NOT_STICKY&&Context.env.stopSelf==1,"unknown intent cannot enable stopped setting");
  check(!Context.env.foreground&&Context.env.windows.adds==0&&QuickEntryService.mode(s)==0,"off state remains off");s.onDestroy();
 }
 static void floatingAndScreenLifecycle(){
  reset();QuickEntryService s=start(2);check(Context.env.foreground&&Context.env.windows.view!=null,"floating shortcut and notification created");
  check(Context.env.activities==0,"starting service never opens screen");
  check((Context.env.windows.last.flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)!=0,"overlay cannot take keyboard focus");
  Context.env.windows.view.performClick();check(Context.env.activities==1,"explicit click opens app");
  broadcast(Intent.ACTION_SCREEN_OFF);check(Context.env.windows.view==null,"screen-off immediately removes overlay");
  Context.env.power.on=false;broadcast(Intent.ACTION_SCREEN_ON);check(Context.env.windows.view==null,"power state remains off");
  Context.env.power.on=true;Context.env.keyguard.locked=true;broadcast(Intent.ACTION_SCREEN_ON);check(Context.env.windows.view==null,"locked screen stays hidden");
  Context.env.keyguard.locked=false;broadcast(Intent.ACTION_USER_PRESENT);check(Context.env.windows.view!=null,"unlocked user restores overlay");
  s.onDestroy();check(Context.env.windows.view==null&&!Context.env.foreground&&Context.env.receiver==null,"destroy removes all entries");
 }
 static void modeChangeAndOff(){
  Context c=reset();QuickEntryService s=start(2);mode(1);s.onStartCommand(null,0,2);
  check(Context.env.windows.view==null&&Context.env.foreground,"notification-only removes existing overlay");
  check(QuickEntryService.apply(c,0),"off preference saved");
  check(Context.env.stops==1&&!Context.env.foreground&&Context.env.receiver==null,"off stops service, receiver and foreground notification");
  check(QuickEntryService.mode(c)==0,"off persists");
 }
 static void deniedOverlayFallsBack(){
  reset();Context.env.windows.denyAdd=true;QuickEntryService s=start(2);
  check(Context.env.foreground&&Context.env.windows.view==null&&Context.env.stopSelf==0,"overlay denial preserves notification");
  check(Context.env.prefs.getString("error","").length()>0,"denial visible in status");
  int attempts=Context.env.windows.adds;broadcast(Intent.ACTION_USER_PRESENT);check(Context.env.windows.adds==attempts,"failure does not loop add attempts");
  Context.env.windows.denyAdd=false;s.onStartCommand(null,0,2);check(Context.env.windows.view!=null,"explicit retry recovers overlay");s.onDestroy();
 }
 static void missingWindowServiceFallsBack(){
  reset();Context.env.windows=null;QuickEntryService s=start(2);check(Context.env.foreground&&Context.env.stopSelf==0,"missing window service keeps notification");s.onDestroy();
 }
 static void receiverFailureFallsBack(){
  reset();Context.env.failRegister=true;QuickEntryService s=start(2);
  check(Context.env.foreground&&Context.env.windows.view==null,"screen tracking denial must not leave overlay");s.onDestroy();
 }
 static void notificationFailureStops(){
  reset();Context.env.failForeground=true;QuickEntryService s=start(2);
  check(Context.env.stopSelf==1&&Context.env.windows.view==null,"notification failure stops before overlay");s.onDestroy();check(!Context.env.foreground,"destroy cleans notification failure");
 }
 static void offActionAndStickyRestart(){
  reset();QuickEntryService s=start(2);check(s.onStartCommand(null,0,2)==Service.START_STICKY,"enabled restarts remain sticky");
  check(Context.env.windows.adds==1,"no duplicate overlay on repeated start");
  int result=s.onStartCommand(new Intent("com.veloster.carplayhub.QUICK_ENTRY_OFF"),0,3);
  check(result==Service.START_NOT_STICKY&&QuickEntryService.mode(s)==0,"notification off action persists off");s.onDestroy();
  check(Context.env.windows.view==null&&!Context.env.foreground,"off destruction cleans view and notification");
 }
 static void rotationAndDrag(){
  reset();QuickEntryService s=start(2);View v=Context.env.windows.view;
  v.touch.onTouch(v,new MotionEvent(MotionEvent.ACTION_DOWN,760,240));v.touch.onTouch(v,new MotionEvent(MotionEvent.ACTION_MOVE,-1000,1000));v.touch.onTouch(v,new MotionEvent(MotionEvent.ACTION_UP,-1000,1000));
  check(Context.env.activities==0,"drag does not launch activity");check(Context.env.windows.last.x==0&&Context.env.windows.last.y==432,"drag bounded and edge-snapped");
  check(!Context.env.prefs.getBoolean("right",true)&&Context.env.prefs.getFloat("relative_y",0)==1f,"position persisted");
  Context.env.windows.width=480;Context.env.windows.height=800;s.onConfigurationChanged(new Configuration());
  check(Context.env.windows.last.x==0&&Context.env.windows.last.y==752,"rotation reuses normalized position");s.onDestroy();
 }
 static void updateFailureRemovesOverlay(){
  reset();QuickEntryService s=start(2);View v=Context.env.windows.view;Context.env.windows.denyUpdate=true;
  v.touch.onTouch(v,new MotionEvent(MotionEvent.ACTION_DOWN,760,240));v.touch.onTouch(v,new MotionEvent(MotionEvent.ACTION_MOVE,200,240));
  check(Context.env.windows.view==null&&Context.env.foreground,"lost overlay during drag falls back to notification");s.onDestroy();
 }
 static void modernReflectionPath(){
  Context c=reset();Build.VERSION.SDK_INT=26;mode(2);check(QuickEntryService.restore(c),"modern restore succeeds");
  check(Context.env.foregroundStarts==1,"modern path requests foreground service");QuickEntryService s=start(2);
  check(Context.env.notifications.channels==1&&Context.env.windows.last.type==2038,"modern channel and overlay type used");s.onDestroy();
 }
 static void deniedServiceStart(){
  Context c=reset();mode(2);Context.env.failStart=true;check(!QuickEntryService.restore(c),"service-start denial reported");
  check(Context.env.activities==0&&Context.env.prefs.getString("error","").length()>0,"denied restore never opens Activity");
 }
 static void languagePreferences(){
  Context c=reset();com.veloster.i18n.LanguageSettings.init(c);
  check("ko".equals(com.veloster.i18n.UiText.language()),"auto follows Korean system");
  check(com.veloster.i18n.LanguageSettings.save(c,"en"),"save English preference");
  com.veloster.i18n.LanguageSettings.init(c);
  check("en".equals(com.veloster.i18n.UiText.language()),"saved override survives re-init");
  check(java.util.Locale.getDefault().equals(java.util.Locale.KOREAN),"never change system locale");
  Context.env.prefs.values.put("language",Integer.valueOf(3));com.veloster.i18n.LanguageSettings.init(c);
  check("ko".equals(com.veloster.i18n.UiText.language()),"wrong preference type falls back safely");
 }
 static void languageRefresh(){
  Context c=reset();QuickEntryService s=start(2);
  check("앱".equals(((android.widget.Button)Context.env.windows.view).label),"Korean overlay");
  com.veloster.i18n.LanguageSettings.save(c,"en");s.onStartCommand(null,0,2);
  check("Apps".equals(((android.widget.Button)Context.env.windows.view).label),"existing overlay rebuilt in English");
  Context.env.prefs.edit().putString("error","바로가기 서비스 종료 요청에 실패했습니다.").commit();
  check(QuickEntryService.status(c).contains("Could not request"),"persisted error key translated at display");
  check(Context.env.windows.adds-Context.env.windows.removes==1,"language refresh leaves exactly one overlay");
  com.veloster.i18n.LanguageSettings.save(c,"ko");s.onStartCommand(null,0,3);
  check("앱".equals(((android.widget.Button)Context.env.windows.view).label),"Korean switch back");s.onDestroy();
 }
 public static void main(String[] args){
  java.util.Locale.setDefault(java.util.Locale.KOREAN);
  optedOutBoot();enabledBoot();unknownStartCannotEnable();floatingAndScreenLifecycle();modeChangeAndOff();deniedOverlayFallsBack();
  missingWindowServiceFallsBack();receiverFailureFallsBack();notificationFailureStops();offActionAndStickyRestart();rotationAndDrag();
  updateFailureRemovesOverlay();modernReflectionPath();deniedServiceStart();
  languagePreferences();languageRefresh();
  System.out.println("PASS: 16 actual service/receiver lifecycle scenarios under host Android stubs (not device runtime)");
 }
}
'''


def java_tools():
    home = os.environ.get("JAVA_HOME")
    candidates = [Path(home)] if home else []
    candidates += [Path("/opt/homebrew/opt/openjdk@17"), Path("/usr/lib/jvm/java-17-openjdk-amd64")]
    for root in candidates:
        if (root / "bin/java").is_file() and (root / "bin/javac").is_file():
            return str(root / "bin/java"), str(root / "bin/javac")
    java, javac = shutil.which("java"), shutil.which("javac")
    if not java or not javac:
        raise unittest.SkipTest("Java/Javac unavailable; set JAVA_HOME to JDK17")
    return java, javac


class QuickEntryRuntimeTests(unittest.TestCase):
    def test_actual_service_and_receiver_lifecycle(self):
        java, javac = java_tools()
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            sources = []
            for relative, contents in STUBS.items():
                path = base / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(contents, encoding="utf-8")
                sources.append(path)
            harness = base / "com/veloster/carplayhub/QuickEntryRuntimeHarness.java"
            harness.write_text(HARNESS, encoding="utf-8")
            sources.append(harness)
            production = ROOT / "apps/hub/src/com/veloster/carplayhub"
            sources.extend((ROOT / "shared/src/com/veloster/i18n" / (name + ".java")) for name in ("UiText", "LanguageSettings"))
            sources.extend(production / (name + ".java") for name in ("QuickEntryPolicy", "QuickEntryService", "QuickEntryReceiver"))
            classes = base / "classes"
            classes.mkdir()
            compiled = subprocess.run([javac, "-encoding", "UTF-8", "-source", "7", "-target", "7", "-d", str(classes),
                                       *map(str, sources)], capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            ran = subprocess.run([java, "-cp", str(classes), "com.veloster.carplayhub.QuickEntryRuntimeHarness"],
                                 capture_output=True, text=True)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
            self.assertIn("PASS: 16", ran.stdout)

    def test_manifest_keeps_controls_private_and_boot_scoped(self):
        manifest = ET.parse(ROOT / "apps/hub/AndroidManifest.xml").getroot()
        a = "{http://schemas.android.com/apk/res/android}"
        app = manifest.find("application")
        service = next(item for item in app.findall("service") if item.get(a + "name").endswith("QuickEntryService"))
        receiver = next(item for item in app.findall("receiver") if item.get(a + "name").endswith("QuickEntryReceiver"))
        self.assertEqual(service.get(a + "exported"), "false")
        self.assertEqual(receiver.get(a + "exported"), "false")
        actions = {item.get(a + "name") for item in receiver.findall("intent-filter/action")}
        self.assertEqual(actions, {"android.intent.action.BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED"})
        self.assertFalse(app.findall("activity/intent-filter/category[@" + a + "name='android.intent.category.HOME']"))


if __name__ == "__main__":
    unittest.main()
