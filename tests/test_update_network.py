"""Run the actual Android network gate with route/type mismatches."""
from pathlib import Path
import subprocess,tempfile,unittest
from test_quick_entry_runtime import java_tools
ROOT=Path(__file__).resolve().parents[1]
STUBS={
'android/content/Context.java':'package android.content;public class Context{public static final String CONNECTIVITY_SERVICE="net";public Object getSystemService(String n){return new android.net.ConnectivityManager();}}',
'android/net/ConnectivityManager.java':'package android.net;public class ConnectivityManager{public static final int TYPE_WIFI=1,TYPE_ETHERNET=9,TYPE_BLUETOOTH=7,TYPE_MOBILE=0;public static int type=-1;public NetworkInfo getActiveNetworkInfo(){return type<0?null:new NetworkInfo();}}',
'android/net/NetworkInfo.java':'package android.net;public class NetworkInfo{public boolean isConnected(){return true;}public int getType(){return ConnectivityManager.type;}}',
'com/veloster/carplayhub/update/AndroidUsbWifi.java':'package com.veloster.carplayhub.update;public class AndroidUsbWifi{public static boolean lease;public static Object currentOrNull(){return lease?new Object():null;}}',
'com/veloster/carplayhub/update/UpdateFiles.java':'package com.veloster.carplayhub.update;import java.io.*;public class UpdateFiles{public static String routes="";public static byte[] read(File f,int n)throws IOException{if(routes==null)throw new IOException("unreadable");return routes.getBytes("US-ASCII");}}'
}
HARNESS='''import android.net.*;import android.content.*;import com.veloster.carplayhub.update.*;
public class NetworkGateHarness{
 static int checks;static void test(int type,String routes,boolean allowed)throws Exception{ConnectivityManager.type=type;UpdateFiles.routes=routes;boolean passed=true;try{UpdateNetwork.requireLocalConnection(new Context());}catch(java.io.IOException e){passed=false;}checks++;if(passed!=allowed)throw new AssertionError("type="+type+" routes="+routes);}
 public static void main(String[]a)throws Exception{
 String wifi="wlan0 00000000 0100000A 0003 0 0 10 00000000 0 0 0\\n",sim="rmnet0 00000000 0100000A 0003 0 0 1 00000000 0 0 0\\n";
 test(1,wifi,true);test(1,wifi+sim,false);test(7,sim,false);test(9,sim,false);test(1,wifi+sim.replace("0 0 1 ","0 0 10 "),false);test(1,"",false);test(1,null,false);test(0,wifi,false);test(-1,wifi.replace("wlan0","usb0"),true);test(-1,sim,false);
 AndroidUsbWifi.lease=true;test(0,null,true);System.out.println("PASS "+checks+" actual network gate checks");
 }}'''
class NetworkGateTests(unittest.TestCase):
 def test_route_precedes_stale_wifi_label(self):
  java,javac=java_tools()
  with tempfile.TemporaryDirectory() as t:
   root=Path(t);files=[]
   for name,body in STUBS.items():
    p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body);files.append(p)
   h=root/'NetworkGateHarness.java';h.write_text(HARNESS);files.append(h)
   src=ROOT/'apps/hub/src/com/veloster/carplayhub/update';files.extend(src/(n+'.java') for n in ['UpdateNetwork','UpdateRoute','UpdateManifest'])
   r=subprocess.run([javac,'-source','7','-target','7','-d',str(root),*map(str,files)],capture_output=True,text=True);self.assertEqual(r.returncode,0,r.stderr)
   r=subprocess.run([java,'-cp',str(root),'NetworkGateHarness'],capture_output=True,text=True);self.assertEqual(r.returncode,0,r.stdout+r.stderr);print(r.stdout.strip())
