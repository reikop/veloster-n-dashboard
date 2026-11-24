"""WPA2 vectors and real process-boundary behavior; no USB/network access."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_quick_entry_runtime import java_tools
ROOT=Path(__file__).resolve().parents[1]
HARNESS=r'''import com.veloster.carplayhub.wifi.*;import java.util.*;import java.io.*;
public class WifiHarness {
 static int checks;static void check(boolean x,String why){checks++;if(!x)throw new AssertionError(why);}
 static class Child extends Process {
  ByteArrayOutputStream input=new ByteArrayOutputStream();String output;int code;boolean running,destroyed;
  Child(String out,int exit,boolean wait){output=out;code=exit;running=wait;}
  public OutputStream getOutputStream(){return input;}public InputStream getInputStream(){return new ByteArrayInputStream(output.getBytes());}public InputStream getErrorStream(){return new ByteArrayInputStream(new byte[0]);}public int waitFor(){return code;}public int exitValue(){if(running)throw new IllegalThreadStateException();return code;}public void destroy(){destroyed=true;}
 }
 static class Runner implements UsbWifiClient.Processes {List<List<String>> commands=new ArrayList<List<String>>();Child child=new Child("ok\n",0,false);public Process start(List<String> command){commands.add(command);return child;}}
 public static void main(String[] a)throws Exception{
  byte[] input=UsbWifiCredentials.encode("IEEE","password".toCharArray());String encoded=new String(input,"US-ASCII");
  check(encoded.equals("ssid_hex=49454545\npsk_hex=f42c6fc52df0ebef9ebb4b90b38a5f902e83fe1b135a70e23aed762e9710a12e\n"),"IEEE WPA2 vector");
  check(new String(UsbWifiCredentials.encode("핫스팟","12345678".toCharArray()),"US-ASCII").startsWith("ssid_hex=ed95abec8aa4ed8c9f\n"),"UTF8 SSID");
  check(new String(UsbWifiCredentials.encode("x","AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA".toCharArray()),"US-ASCII").contains("psk_hex=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n"),"raw key");
  for(String[] bad:new String[][]{{"","password"},{"x","short"},{"가가가가가가가가가가가","password"},{"x","한글password"},{"x","password\n"},{"\ud800","password"},{"x","ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ"}}){boolean rejected=false;try{UsbWifiCredentials.encode(bad[0],bad[1].toCharArray());}catch(java.security.GeneralSecurityException expected){rejected=true;}check(rejected,"invalid credential rejected");}
  Runner runner=new Runner();UsbWifiClient client=new UsbWifiClient(runner,100);client.configure(input);
  check(runner.commands.get(0).equals(Arrays.asList("/system/bin/amossu","-c",UsbWifiClient.MANAGER+" configure")),"fixed command");check(runner.child.input.toString("US-ASCII").equals(encoded),"credential stdin");boolean wiped=true;for(byte b:input)wiped&=b==0;check(wiped,"input wiped");check(runner.child.destroyed,"cleanup");
  runner.child=new Child("connected\n",0,false);check(client.status().equals("connected"),"status");
  runner.child=new Child("sensitive failure",1,false);try{client.stop();throw new AssertionError("accepted failure");}catch(IOException e){check(!e.getMessage().contains("sensitive"),"does not expose output");}
  runner.child=new Child("",0,true);long start=System.nanoTime();try{client.start();throw new AssertionError("accepted timeout");}catch(IOException expected){check((System.nanoTime()-start)/1000000<2000,"bounded timeout");}check(runner.child.destroyed,"timeout cleanup");
  runner.child=new Child("off\n",0,false);check(client.remote("status").equals("off"),"remote status");check(runner.commands.get(runner.commands.size()-1).get(2).equals("/data/local/tmp/vn-usb-wifi/remote/bin/vn-remote-control status"),"fixed terminal path");
  check(client.internet("status").equals("off"),"internet status");check(runner.commands.get(runner.commands.size()-1).get(2).equals("/data/local/tmp/vn-usb-wifi/internet/bin/vn-internet-control status"),"fixed internet path");
  for(String verb:new String[]{"start","stop"})client.internet(verb);
  for(String verb:new String[]{"start; id","identity","password", ""}){int count=runner.commands.size();try{client.internet(verb);throw new AssertionError("invalid internet verb accepted");}catch(IOException expected){check(count==runner.commands.size(),"internet action rejected before execution");}}
  check(client.certificates("status").equals("off"),"certificate status");check(runner.commands.get(runner.commands.size()-1).get(2).equals("/data/local/tmp/vn-usb-wifi/https-ca/bin/vn-ca-control status"),"fixed CA path");
  for(String verb:new String[]{"start","stop"})client.certificates(verb);
  for(String verb:new String[]{"start; id","identity","password", ""}){int count=runner.commands.size();try{client.certificates(verb);throw new AssertionError("invalid CA verb accepted");}catch(IOException expected){check(count==runner.commands.size(),"CA action rejected before execution");}}
  int before=runner.commands.size();try{client.remote("start; id");throw new AssertionError("injection accepted");}catch(IOException expected){check(before==runner.commands.size(),"invalid remote command rejected before process");}
  runner.child=new Child("ok",0,false);char[] secret="한글pass$()word".toCharArray();int begin=runner.commands.size();client.remotePassword(secret);
  check(runner.commands.size()==begin+2,"stop then save password");check(runner.commands.get(begin).get(2).endsWith(" stop"),"password change closes listener");check(runner.commands.get(begin+1).get(2).endsWith(" password"),"fixed password verb");
  check(runner.child.input.toString("UTF-8").equals("한글pass$()word"),"UTF8 password only on stdin");check(!runner.commands.toString().contains("pass$"),"password absent from arguments");boolean cleared=true;for(char c:secret)cleared&=c==0;check(cleared,"password chars cleared");
  for(String invalid:new String[]{"short","abcdefgh\u0000","abcdefgh\ud800",new String(new char[129]).replace('\0','a')}){char[] bad=invalid.toCharArray();int count=runner.commands.size();try{client.remotePassword(bad);throw new AssertionError("bad password accepted");}catch(IOException expected){check(runner.commands.size()==count,"invalid password rejected before process");}boolean zero=true;for(char c:bad)zero&=c==0;check(zero,"invalid password wiped");}
  runner.child=new Child("failure",1,false);char[] failed="valid-password".toCharArray();try{client.remotePassword(failed);throw new AssertionError("failed stop accepted");}catch(IOException expected){boolean zero=true;for(char c:failed)zero&=c==0;check(zero,"password wiped on failure");}
  char[] huge=new char[5000];Arrays.fill(huge,'x');runner.child=new Child(new String(huge),0,false);try{client.status();throw new AssertionError("accepted excessive output");}catch(IOException expected){check(runner.child.destroyed,"bounded output");}
  System.out.println("PASS "+checks+" Wi-Fi control checks");
 }
}'''
class UsbWifiControlTests(unittest.TestCase):
 def test_credentials_and_processes(self):
  java,javac=java_tools()
  with tempfile.TemporaryDirectory() as folder:
   out=Path(folder);h=out/'WifiHarness.java';h.write_text(HARNESS)
   src=ROOT/'apps/hub/src/com/veloster/carplayhub/wifi'
   r=subprocess.run([javac,'-source','7','-target','7','-d',str(out),*map(str,src.glob('*.java')),str(h)],capture_output=True,text=True)
   self.assertEqual(r.returncode,0,r.stderr)
   r=subprocess.run([java,'-cp',str(out),'WifiHarness'],capture_output=True,text=True,timeout=15)
   self.assertEqual(r.returncode,0,r.stdout+r.stderr);print(r.stdout.strip())
