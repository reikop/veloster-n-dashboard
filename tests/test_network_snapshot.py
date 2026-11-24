"""Bounded diagnostic collection using synthetic sysfs; no device/network access."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_quick_entry_runtime import java_tools
ROOT=Path(__file__).resolve().parents[1]
HARNESS=r'''import com.veloster.carplayhub.update.*;import java.io.*;import java.nio.file.*;
public class SnapshotHarness {
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static File put(File root,String name,String value)throws Exception{File f=new File(root,name);f.getParentFile().mkdirs();Files.write(f.toPath(),value.getBytes("US-ASCII"));return f;}
 public static void main(String[] args)throws Exception{
  File root=new File(args[0]),usb=new File(root,"usb"),net=new File(root,"net");
  put(usb,"1-1/idVendor","0b95\n");put(usb,"1-1/idProduct","7720\n");put(usb,"1-1/bcdDevice","0001\n");put(usb,"1-1/serial","DO-NOT-COLLECT");put(usb,"1-1:1.0/bInterfaceClass","02\n");put(usb,"1-1:1.0/bInterfaceNumber","00\n");put(usb,"1-1:1.0/bInterfaceProtocol","untrusted\n");put(usb,"unrelated/idVendor","1234\n");
  put(net,"eth0/operstate","up\n");put(net,"eth0/address","DO-NOT-COLLECT");
  File routes=put(root,"route","eth0 00000000 0100000A 0003 0 0 10 00000000 0 0 0\n"),release=put(root,"release","3.1.10-tcc\n");
  NetworkSnapshot s=NetworkSnapshot.capture(usb,net,routes,release);
  check(s.descriptors.contains("/sys/bus/usb/devices/1-1/idVendor\n0b95\n"),"matcher input format");
  check(s.descriptors.contains("1-1:1.0/bInterfaceNumber\n00\n"),"interface association");
  check(!s.descriptors.contains("DO-NOT")&&!s.summary.contains("DO-NOT"),"serial and MAC excluded");
  check(!s.descriptors.contains("untrusted")&&!s.descriptors.contains("unrelated"),"strict field/path values");
  check(s.summary.contains("interface=eth0 state=up")&&s.summary.contains("local_default_route=yes"),"local status without addresses");
  check(!s.summary.contains("0100000A"),"gateway not persisted");
  File output=new File(root,"output");s.save(output);check(new String(Files.readAllBytes(new File(output,"usb-match-descriptors.stdout").toPath()),"UTF-8").equals(s.descriptors),"export roundtrip");
  StringBuilder huge=new StringBuilder();for(int i=0;i<200;i++)huge.append('a');put(usb,"1-1/idVendor",huge.toString());s=NetworkSnapshot.capture(usb,net,routes,release);check(!s.descriptors.contains("idVendor"),"oversized field rejected");
  File absent=new File(root,"absent");s=NetworkSnapshot.capture(absent,absent,absent,absent);check(s.summary.contains("kernel_release=unknown")&&s.summary.contains("local_default_route=unknown")&&s.descriptors.equals(""),"unreadable is unknown");
  System.out.println("PASS "+checks+" network snapshot checks");
 }
}'''
class SnapshotTests(unittest.TestCase):
    def test_snapshot(self):
        java,javac=java_tools()
        with tempfile.TemporaryDirectory() as tmp:
            out=Path(tmp);h=out/'SnapshotHarness.java';h.write_text(HARNESS)
            source=ROOT/'apps/hub/src/com/veloster/carplayhub/update'
            r=subprocess.run([javac,'-source','7','-target','7','-d',str(out),*[str(source/(n+'.java')) for n in ('NetworkSnapshot','UpdateRoute','UpdateFiles','UpdateManifest')],str(h)],capture_output=True,text=True)
            self.assertEqual(r.returncode,0,r.stderr)
            r=subprocess.run([java,'-cp',str(out),'SnapshotHarness',str(out)],capture_output=True,text=True,timeout=20)
            self.assertEqual(r.returncode,0,r.stdout+r.stderr);self.assertIn('PASS 9',r.stdout)
