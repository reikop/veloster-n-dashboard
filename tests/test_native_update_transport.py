"""Native download protocol/process lifecycle tests with process doubles, no network."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_quick_entry_runtime import java_tools
ROOT=Path(__file__).resolve().parents[1]
HARNESS=r'''import com.veloster.carplayhub.update.*;import java.io.*;import java.util.*;
public class NativeTransportHarness {
 static int count;interface Action{void run()throws Exception;}static void check(boolean b,String why){count++;if(!b)throw new AssertionError(why);}
 static void reject(Action a,String why)throws Exception{boolean failed=false;try{a.run();}catch(IOException expected){failed=true;}check(failed,why);}
 static final UpdateTransport.Policy ALLOW=new UpdateTransport.Policy(){public void check(){}};
 static class Child extends Process {final InputStream input;volatile boolean destroyed;int exit;Child(String response,int status)throws Exception{input=new ByteArrayInputStream(response.getBytes("US-ASCII"));exit=status;}public InputStream getInputStream(){return input;}public InputStream getErrorStream(){return new ByteArrayInputStream(new byte[0]);}public OutputStream getOutputStream(){return new ByteArrayOutputStream();}public int waitFor(){return exit;}public int exitValue(){return exit;}public void destroy(){destroyed=true;}}
 static NativeUpdateTransport client(final Child child){return new NativeUpdateTransport(new File("curl"),new File("ca.pem"),ALLOW,new NativeUpdateTransport.Processes(){public Process start(List<String> args){check(args.get(1).equals("-q"),"curl config disabled first");check(args.contains("--cacert")&&args.contains("--tlsv1.2")&&!args.contains("--insecure")&&!args.contains("--location"),"verification and manual redirect policy");return child;}});}
 public static void main(String[] args)throws Exception{
  Child ok=new Child("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok",0);check(new String(client(ok).bytes("https://github.com/x",2,UpdateFiles.NEVER),"US-ASCII").equals("ok"),"decoded body");check(ok.destroyed,"child closed");
  Child interim=new Child("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\n\r\nx",0);check(client(interim).bytes("https://github.com/x",1,UpdateFiles.NEVER).length==1,"interim headers");
  final Child missing=new Child("HTTP/1.1 404 Not Found\r\n\r\n",0);reject(new Action(){public void run()throws Exception{client(missing).bytes("https://github.com/x",8,UpdateFiles.NEVER);}},"missing feed");check(missing.destroyed,"missing child closed");
  final Child failed=new Child("HTTP/1.1 200 OK\r\n\r\nx",60);reject(new Action(){public void run()throws Exception{client(failed).bytes("https://github.com/x",8,UpdateFiles.NEVER);}},"nonzero process is never success");
  final Child redirect=new Child("HTTP/1.1 302 Found\r\nLocation: http://evil.example/x\r\n\r\n",0);reject(new Action(){public void run()throws Exception{client(redirect).bytes("https://github.com/x",8,UpdateFiles.NEVER);}},"redirect protocol and host validated");
  final Child duplicate=new Child("HTTP/1.1 302 Found\r\nLocation: /one\r\nLocation: /two\r\n\r\n",0);reject(new Action(){public void run()throws Exception{client(duplicate).bytes("https://github.com/x",8,UpdateFiles.NEVER);}},"ambiguous redirect");
  final Child tooLarge=new Child("HTTP/1.1 200 OK\r\n\r\n123456",0);reject(new Action(){public void run()throws Exception{client(tooLarge).bytes("https://github.com/x",2,UpdateFiles.NEVER);}},"response bounded");
  StringBuilder longHeader=new StringBuilder("HTTP/1.1 200 OK\r\nX: ");for(int i=0;i<9000;i++)longHeader.append('x');final Child huge=new Child(longHeader+"\r\n\r\n",0);reject(new Action(){public void run()throws Exception{client(huge).bytes("https://github.com/x",8,UpdateFiles.NEVER);}},"headers bounded");
  final NativeUpdateTransport forbidden=new NativeUpdateTransport(new File("curl"),new File("ca"),new UpdateTransport.Policy(){public void check()throws IOException{throw new IOException("no local network");}},new NativeUpdateTransport.Processes(){public Process start(List<String> c){throw new AssertionError("must not start");}});reject(new Action(){public void run()throws Exception{forbidden.bytes("https://github.com/x",8,UpdateFiles.NEVER);}},"network policy before subprocess");
  final boolean[] cancel={false};final Child blocked=new Child("",0){public InputStream getInputStream(){return new InputStream(){public int read(){while(!destroyed){try{Thread.sleep(10);}catch(InterruptedException ignored){}}return -1;}};}};
  Thread stop=new Thread(new Runnable(){public void run(){try{Thread.sleep(150);}catch(InterruptedException ignored){}synchronized(cancel){cancel[0]=true;}}});stop.start();final UpdateFiles.Cancellation cancellation=new UpdateFiles.Cancellation(){public boolean cancelled(){synchronized(cancel){return cancel[0];}}};long start=System.nanoTime();reject(new Action(){public void run()throws Exception{client(blocked).bytes("https://github.com/x",8,cancellation);}},"cancel interrupts blocked headers");check(blocked.destroyed&&System.nanoTime()-start<3000000000L,"watchdog destroys stalled child");
  System.out.println("PASS "+count+" native transport checks");
 }
}'''
class NativeTransportTests(unittest.TestCase):
    def test_native_protocol(self):
        java,javac=java_tools()
        with tempfile.TemporaryDirectory() as tmp:
            out=Path(tmp);h=out/'NativeTransportHarness.java';h.write_text(HARNESS)
            src=ROOT/'apps/hub/src/com/veloster/carplayhub/update'
            r=subprocess.run([javac,'-source','7','-target','7','-d',str(out),*[str(src/(n+'.java')) for n in ('NativeUpdateTransport','UpdateTransport','UpdateManifest','UpdateFiles')],str(ROOT/'shared/src/com/veloster/network/ModernHttps.java'),str(h)],capture_output=True,text=True)
            self.assertEqual(r.returncode,0,r.stderr)
            r=subprocess.run([java,'-cp',str(out),'NativeTransportHarness'],capture_output=True,text=True,timeout=10)
            self.assertEqual(r.returncode,0,r.stdout+r.stderr);self.assertIn('native transport checks',r.stdout)
            print(r.stdout.strip())
