"""Exercise the actual passive subscription client with deterministic OEM/Handler doubles."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_quick_entry_runtime import java_tools

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    "com/veloster/diagnostics/ErrorLog.java": "package com.veloster.diagnostics;public class ErrorLog {public static void record(String s,Throwable e){}}",
    'android/content/Context.java': 'package android.content; public class Context {public Context getApplicationContext(){return this;}}',
    'android/util/Log.java': 'package android.util; public class Log {public static int e(String t,String m,Throwable e){return 0;}}',
    'android/os/Handler.java': '''package android.os; import java.util.*;
public class Handler {
 public static List<Runnable> jobs=new ArrayList<Runnable>();public static long delay;
 public void removeCallbacks(Runnable r){while(jobs.remove(r)){} }
 public boolean postDelayed(Runnable r,long ms){jobs.add(r);delay=ms;return true;}
 public static void next(){if(jobs.size()!=1)throw new AssertionError("one scheduled retry required");jobs.remove(0).run();}
}''',
    'com/veloster/vehicle/VehicleDash.java': '''package com.veloster.vehicle;
public class VehicleDash {public static int writes,clears;
 public static void ignition(boolean b){clears++;}public static void calculateObserved(){}
 public static void rpm(byte[] b){writes++;}public static void gforce(byte[] b){writes++;}
 public static void temperatures(byte[] b){writes++;}public static void units(byte[] b){writes++;}
 public static void additional(byte[] b){writes++;}public static void wheels(byte[] b){writes++;}
 public static void speed(int s){writes++;}public static void rearView(int s){writes++;}
 public static void driveMode(byte v,byte m){writes++;}
}''',
    'com/veloster/dashboard/HscanDataClient.java': '''package com.veloster.dashboard;
final class HscanDataClient {interface Gate {boolean accepts();}static int fail,creates,starts,stops;static Gate gate;
 boolean live;HscanDataClient(Gate g){creates++;if(fail-->0)throw new IllegalStateException("HSCAN unavailable");gate=g;}
 void start(){if(!live){live=true;starts++;}}void stop(){if(live){live=false;stops++;}}
}''',
    'com/hkmc/VehicleInfoManager.java': '''package com.hkmc;import android.content.Context;import com.hkmc.bcm.BCMManager;
public class VehicleInfoManager {public static final String BCM_SERVICE="bcm";public static VehicleInfoManager getInstance(Context c){return new VehicleInfoManager();}
 public Object getService(String name){return new BCMManager(null);}}
''',
    'com/hkmc/micom/NModeListener.java': '''package com.hkmc.micom;public class NModeListener {
 public void onPowerTorqueTurboBoostReceived(byte[] b){} public void onGForceDataReceived(byte[] b){}
 public void onOilCoolantTemperature(byte[] b){}public void onSpeedTempUnitTempReceived(byte[] b){}
 public void onNAddInfoReceived(byte[] b){}public void onNModeZeroSpeedChanged(byte[] b){}public void onIndividualDriveModeChanged(DriveMode m){}}
''',
    'com/hkmc/micom/VehicleInfoListener.java': 'package com.hkmc.micom;public class VehicleInfoListener {public void onVehicleSpeedChanged(int n){}}',
    'com/hkmc/bcm/BCMPowerListener.java': 'package com.hkmc.bcm;public class BCMPowerListener {public void onIgnitionChanged(boolean b){}}',
    'com/hkmc/micom/DriveMode.java': 'package com.hkmc.micom;public class DriveMode {public byte mIsValid,mCurrentDrviceMode;}',
    'com/veloster/vehicle/DayNight.java': 'package com.veloster.vehicle;public class DayNight {public static volatile int oemDim=-1,oemIllum=-1;}',
    'com/hkmc/setup/VideoSetupListener.java': 'package com.hkmc.setup;public class VideoSetupListener {public void onDimChanged(int d){}public void onIlluminateStatusChanged(boolean on){}}',
    'com/hkmc/setup/VideoSetupManager.java': 'package com.hkmc.setup;public class VideoSetupManager {public VideoSetupManager(android.content.Context c){}public void addListener(VideoSetupListener l){}public void removeListener(VideoSetupListener l){}public boolean getIlluminateStatus(){return false;}public int getDim(){return 0;}}',
    'com/hkmc/pgs/PgsStatusListener.java': 'package com.hkmc.pgs;public class PgsStatusListener {public void onRVMViewStatusChanged(int n){}}',
}
for pkg, cls, listener, add, remove in (
    ('micom', 'NModeManager', 'NModeListener', 'registerNModeListener', 'unregisterNModeListener'),
    ('micom', 'VehicleInfoManager', 'VehicleInfoListener', 'addListener', 'removeListener'),
    ('bcm', 'BCMManager', 'BCMPowerListener', 'addPowerListener', 'removePowerListener'),
    ('pgs', 'PgsManager', 'PgsStatusListener', 'registerPgsStatusListener', 'unregisterPgsStatusListener'),
):
    STUBS[f'com/hkmc/{pkg}/{cls}.java'] = f'''package com.hkmc.{pkg};import android.content.Context;
public class {cls} {{
 public static int creates,adds,removes,failCreate,failAdd;public static boolean failRemove,ignition=true;
 public static {listener} current,previous;
 public {cls}(Context c){{creates++;if(failCreate-->0)throw new IllegalStateException("service not ready");}}
 public void {add}({listener} l){{adds++;previous=current;current=l;if(failAdd-->0)throw new IllegalStateException("partial registration");}}
 public void {remove}({listener} l){{removes++;if(failRemove)throw new IllegalStateException("unregister failed");}}
 public boolean getIGNStatus(){{return ignition;}}
 public com.hkmc.micom.DriveMode getIndividualDriveMode(){{return null;}}
 public static void reset(){{creates=adds=removes=failCreate=failAdd=0;failRemove=false;ignition=true;current=previous=null;}}
}}'''

HARNESS = '''package com.veloster.dashboard;
import android.content.Context;import android.os.Handler;import com.hkmc.micom.*;import com.hkmc.bcm.*;import com.hkmc.pgs.*;import com.veloster.vehicle.VehicleDash;
public class VehicleDataClientHarness {
 static void check(boolean ok,String s){if(!ok)throw new AssertionError(s);}
 static VehicleDataClient fresh(){Handler.jobs.clear();NModeManager.reset();VehicleInfoManager.reset();BCMManager.reset();PgsManager.reset();HscanDataClient.fail=HscanDataClient.creates=HscanDataClient.starts=HscanDataClient.stops=0;VehicleDash.writes=VehicleDash.clears=0;return new VehicleDataClient(new Context());}
 static void ready(){VehicleDataClient c=fresh();c.start();c.start();check(Handler.jobs.isEmpty(),"all ready stops retrying");check(NModeManager.adds==1&&PgsManager.adds==1&&HscanDataClient.starts==1,"start idempotent");c.stop();check(NModeManager.removes==1&&PgsManager.removes==1&&HscanDataClient.stops==1,"all subscriptions released");}
 static void delayedRear(){VehicleDataClient c=fresh();PgsManager.failCreate=2;c.start();check(Handler.jobs.size()==1&&Handler.delay==2000,"PGS-only startup failure retried");Handler.next();check(Handler.delay==4000,"retry backoff");Handler.next();check(Handler.jobs.isEmpty()&&PgsManager.adds==1,"late PGS connects");check(NModeManager.creates==1&&VehicleInfoManager.creates==1&&BCMManager.creates==1,"ready peers not duplicated");c.stop();}
 static void delayedHscan(){VehicleDataClient c=fresh();HscanDataClient.fail=1;c.start();check(Handler.jobs.size()==1,"HSCAN-only failure retried");Handler.next();check(HscanDataClient.starts==1&&Handler.jobs.isEmpty(),"HSCAN recovered");c.stop();}
 static void failedRegistration(){VehicleDataClient c=fresh();NModeManager.failAdd=1;c.start();NModeListener stale=NModeManager.current;check(NModeManager.removes==1,"partial registration cleaned up");stale.onPowerTorqueTurboBoostReceived(new byte[0]);check(VehicleDash.writes==0,"failed registration callback ignored");Handler.next();stale.onPowerTorqueTurboBoostReceived(new byte[0]);check(VehicleDash.writes==0,"same-session old registration ignored");NModeManager.current.onPowerTorqueTurboBoostReceived(new byte[0]);check(VehicleDash.writes==1,"replacement listener delivers");c.stop();}
 static void failedCleanup(){VehicleDataClient c=fresh();NModeManager.failAdd=1;NModeManager.failRemove=true;c.start();NModeListener stale=NModeManager.current;Handler.next();stale.onOilCoolantTemperature(new byte[0]);check(VehicleDash.writes==0,"failed unregister cannot revive old listener");NModeManager.failRemove=false;c.stop();}
 static void powerFailure(){VehicleDataClient c=fresh();BCMManager.failAdd=1;c.start();BCMPowerListener stale=BCMManager.current;check(!HscanDataClient.gate.accepts()&&HscanDataClient.starts==0,"failed BCM must not enable telemetry");stale.onIgnitionChanged(true);check(!HscanDataClient.gate.accepts(),"failed power callback ignored");Handler.next();check(HscanDataClient.starts==1&&HscanDataClient.gate.accepts(),"power registration recovered");c.stop();}
 static void stopInvalidates(){VehicleDataClient c=fresh();c.start();NModeListener n=NModeManager.current;VehicleInfoListener v=VehicleInfoManager.current;PgsStatusListener r=PgsManager.current;BCMPowerListener p=BCMManager.current;c.stop();c.start();n.onOilCoolantTemperature(new byte[0]);v.onVehicleSpeedChanged(42);r.onRVMViewStatusChanged(1);p.onIgnitionChanged(false);check(VehicleDash.writes==0&&HscanDataClient.gate.accepts(),"old generation cannot mutate resumed session");c.stop();}
 static void boundedRetry(){VehicleDataClient c=fresh();PgsManager.failCreate=100;c.start();for(int i=0;i<12;i++){c.run();check(Handler.jobs.size()==1&&Handler.delay<=30000,"bounded single retry");}check(Handler.delay==30000,"delay caps at 30 sec");c.stop();check(Handler.jobs.isEmpty(),"stop cancels retries");c.start();check(Handler.delay==2000,"fresh session resets backoff");c.stop();}
 static void ignitionGate(){VehicleDataClient c=fresh();BCMManager.ignition=false;c.start();NModeManager.current.onOilCoolantTemperature(new byte[0]);check(VehicleDash.writes==0&&HscanDataClient.starts==0,"off gates receive");BCMManager.current.onIgnitionChanged(true);check(HscanDataClient.starts==1,"ignition starts receive");NModeManager.current.onOilCoolantTemperature(new byte[0]);check(VehicleDash.writes==1,"on delivers");BCMManager.current.onIgnitionChanged(false);check(HscanDataClient.stops==1&&!HscanDataClient.gate.accepts(),"off stops receive");c.stop();}
 public static void main(String[] a){ready();delayedRear();delayedHscan();failedRegistration();failedCleanup();powerFailure();stopInvalidates();boundedRetry();ignitionGate();System.out.println("PASS 9 vehicle subscription lifecycle scenarios");}
}'''

class VehicleDataClientTests(unittest.TestCase):
    def test_lifecycle(self):
        java, javac = java_tools()
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            sources = []
            for name, text in STUBS.items():
                p = root / name
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(text)
                sources.append(p)
            harness = root / 'com/veloster/dashboard/VehicleDataClientHarness.java'
            harness.write_text(HARNESS)
            source = Path(os.environ.get('VN_VEHICLE_CLIENT_SOURCE', ROOT / 'apps/dashboard/src/com/veloster/dashboard/VehicleDataClient.java'))
            result = subprocess.run([javac, '-source', '7', '-target', '7', '-d', str(root), *map(str, sources), str(harness), str(source)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run([java, '-cp', str(root), 'com.veloster.dashboard.VehicleDataClientHarness'], capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('PASS 9', result.stdout)

if __name__ == '__main__':
    unittest.main()
