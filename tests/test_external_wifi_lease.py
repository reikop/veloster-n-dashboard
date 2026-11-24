"""Lease/route/generation tests for USB Wi-Fi OTA; no network or device access."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_quick_entry_runtime import java_tools

ROOT = Path(__file__).resolve().parents[1]
HARNESS = r'''import com.veloster.carplayhub.update.*;import java.io.*;import java.util.*;
public class LeaseHarness {
 static int checks;interface Action{void run()throws Exception;}
 static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static void reject(Action action,String why)throws Exception{boolean failed=false;try{action.run();}catch(IOException e){failed=true;}check(failed,why);}
 static class Env implements ExternalWifiLease.Environment {
  String boot="00000000-0000-0000-0000-000000000001",generation="0123456789abcdef0123456789abcdef";
  long now=1000,expires=120000;boolean address=true;
  String routes="usbwifi0 00000000 0101A8C0 0003 0 0 300 00000000 0 0 0\n";
  public String state(){return "format=1\ninterface=usbwifi0\naddress=192.168.1.20\ndns=192.168.1.1,1.1.1.1\ngeneration="+generation+"\nboot_id="+boot+"\nexpires_elapsed_ms="+expires+"\n";}
  public String bootId(){return boot;}public long elapsedMillis(){return now;}
  public boolean hasAddress(String i,String a){return address;}public String routes(){return routes;}
 }
 static class Child extends Process {boolean destroyed;public InputStream getInputStream(){return new ByteArrayInputStream("HTTP/1.1 200 OK\r\n\r\nok".getBytes());}public InputStream getErrorStream(){return new ByteArrayInputStream(new byte[0]);}public OutputStream getOutputStream(){return new ByteArrayOutputStream();}public int waitFor(){return 0;}public int exitValue(){return 0;}public void destroy(){destroyed=true;}}
 public static void main(String[] args)throws Exception{
  final Env env=new Env();final ExternalWifiLease lease=ExternalWifiLease.current(env);
  check(lease.device.equals("usbwifi0"),"external interface");
  check(lease.arguments().equals(Arrays.asList("--ipv4","--interface","192.168.1.20","--dns-ipv4-addr","192.168.1.20","--dns-servers","192.168.1.1,1.1.1.1")),"exact local source and DNS args");
  for(final String value:new String[]{"0.1.2.3","127.0.0.1","224.1.2.3","169.254.0.1","1.2.3.999","01.2.3.4","1.2.3.4 --insecure","host.example"})check(!ExternalWifiLease.ipv4(value),"reject non-unicast/nonliteral address");
  for(final String state:new String[]{env.state()+"address=10.0.0.1\n",env.state()+"password=secret\n",env.state().replace("usbwifi0","rmnet0"),env.state().replace("expires_elapsed_ms=120000","expires_elapsed_ms=-1"),env.state().replace("1.1.1.1","192.168.1.1"),env.state().replace("\naddress=192.168.1.20",""),env.state().replace("format=1","format=2")})reject(new Action(){public void run()throws Exception{ExternalWifiLease.parse(state,env);}},"malformed lease");
  env.expires=240000;lease.check();check(true,"same-generation renewal allowed");env.now=150000;lease.check();check(true,"renewal extends validity");env.expires=120000;
  env.now=120000;reject(new Action(){public void run()throws Exception{lease.check();}},"expired lease");env.now=1000;
  env.address=false;reject(new Action(){public void run()throws Exception{lease.check();}},"detached interface");env.address=true;
  env.generation="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";reject(new Action(){public void run()throws Exception{lease.check();}},"renewed lease cancels old binding");env.generation=lease.generation;
  env.boot="00000000-0000-0000-0000-000000000002";reject(new Action(){public void run()throws Exception{lease.check();}},"reboot rejects stale state");env.boot=lease.bootId;
  String original=env.routes;env.routes=original+"rmnet0 00000000 0100000A 0003 0 0 300 00000000\n";
  reject(new Action(){public void run()throws Exception{lease.check();}},"ambiguous default");
  env.routes=original+"rmnet0 00000000 0100000A 0003 0 0 1 00000000\n";
  reject(new Action(){public void run()throws Exception{lease.check();}},"preferred cellular route");env.routes=original;
  final Child child=new Child();final List<List<String>> launched=new ArrayList<List<String>>();
  NativeUpdateTransport client=new NativeUpdateTransport(new File("curl"),new File("ca.pem"),new UpdateTransport.Policy(){public void check(){}},new NativeUpdateTransport.Processes(){public Process start(List<String> command){launched.add(command);return child;}},lease);
  check(client.bytes("https://github.com/x",2,UpdateFiles.NEVER).length==2,"native download with lease");
  check(launched.size()==1&&launched.get(0).contains("--dns-ipv4-addr")&&launched.get(0).contains("--dns-servers"),"binding reaches actual process args");
  check(child.destroyed,"process cleaned");env.generation="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
  final NativeUpdateTransport old=client;reject(new Action(){public void run()throws Exception{old.bytes("https://github.com/x",2,UpdateFiles.NEVER);}},"changed lease stops before next process");check(launched.size()==1,"no child after invalidation");
  System.out.println("PASS "+checks+" external Wi-Fi lease checks");
 }
}'''


class ExternalWifiLeaseTests(unittest.TestCase):
    def test_lease_and_native_binding(self):
        java, javac = java_tools()
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp)
            harness = out / 'LeaseHarness.java'
            harness.write_text(HARNESS)
            src = ROOT / 'apps/hub/src/com/veloster/carplayhub/update'
            files = [src/(n+'.java') for n in ('ExternalWifiLease', 'NativeUpdateTransport',
                                              'UpdateTransport', 'UpdateManifest', 'UpdateFiles')]
            r = subprocess.run([javac, '-source', '7', '-target', '7', '-d', str(out),
                                *map(str, files), str(ROOT/'shared/src/com/veloster/network/ModernHttps.java'), str(harness)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            r = subprocess.run([java, '-cp', str(out), 'LeaseHarness'], capture_output=True,
                               text=True, timeout=15)
            self.assertEqual(r.returncode, 0, r.stdout+r.stderr)
            print(r.stdout.strip())
