"""Shared marketplace HTTPS policy, transfer and failure tests without a network."""
from pathlib import Path
import subprocess,tempfile,unittest
from test_quick_entry_runtime import java_tools
ROOT=Path(__file__).resolve().parents[1]
HARNESS=r'''import com.veloster.network.ModernHttps;import java.io.*;import java.net.*;import java.util.*;
public class ModernHarness {
 static int cases;static void ok(boolean value){cases++;if(!value)throw new AssertionError("case "+cases);}
 interface Action{void run()throws Exception;}
 static void rejects(ModernHttps.Reason reason,Action action)throws Exception{try{action.run();throw new AssertionError("accepted "+reason);}catch(ModernHttps.Failure failure){ok(failure.reason==reason);}}
 static class Child extends Process {String output;int code;volatile boolean destroyed;Child(String value,int code){this.output=value;this.code=code;}public InputStream getInputStream(){return new ByteArrayInputStream(output.getBytes());}public InputStream getErrorStream(){return new ByteArrayInputStream("sensitive server text".getBytes());}public OutputStream getOutputStream(){return new ByteArrayOutputStream();}public int exitValue(){return code;}public int waitFor(){return code;}public void destroy(){destroyed=true;}}
 static final ModernHttps.Network NETWORK=new ModernHttps.Network(){public void check(){}public List<String> arguments(){return Collections.emptyList();}};
 static List<List<String>> commands=new ArrayList<List<String>>();
 static ModernHttps client(final Child... children){return new ModernHttps(new File("curl"),new File("ca"),Arrays.asList("market.example","cdn.example"),NETWORK,new ModernHttps.Processes(){int index;public Process start(List<String> args){commands.add(args);return children[index++];}});}
 static String body(String value){return "HTTP/1.1 200 OK\r\nContent-Length: "+value.length()+"\r\n\r\n"+value;}
 public static void main(String[] argv)throws Exception{
  final ModernHttps policy=client();for(final String bad:new String[]{"http://market.example/x","https://user@market.example/x","https://market.example:444/x","https://market.example/x#fragment","https://market.example.evil/x","https://market.example./x","https://market.example/x y","https://market.example/a\\b"})rejects(ModernHttps.Reason.POLICY,new Action(){public void run()throws Exception{policy.bytes(bad,20,ModernHttps.NEVER);}});
  try{client().bytes("https://market.example/",1024*1024+1,ModernHttps.NEVER);throw new AssertionError("unbounded catalog");}catch(IllegalArgumentException expected){ok(true);}
  Child response=new Child(body("ok"),0);ok(new String(client(response).bytes("https://market.example/catalog",20,ModernHttps.NEVER)).equals("ok"));ok(response.destroyed);List<String> args=commands.get(commands.size()-1);ok(args.get(1).equals("-q")&&args.contains("--cacert")&&args.contains("--tlsv1.2")&&!args.contains("--insecure")&&!args.contains("--location"));
  for(final int code:new int[]{6,7,28,35,60,77}){final ModernHttps c=client(new Child("",code));rejects(ModernHttps.fromExit(code).reason,new Action(){public void run()throws Exception{c.bytes("https://market.example/",20,ModernHttps.NEVER);}});}
  ok(client(new Child("HTTP/1.1 404 Not Found\r\nContent-Length: 999999999\r\n\r\n",0)).probe("https://market.example/",ModernHttps.NEVER)==404);
  ok(!commands.get(commands.size()-1).contains("--max-filesize"));
  rejects(ModernHttps.Reason.HTTP,new Action(){public void run()throws Exception{client(new Child("HTTP/1.1 503 Unavailable\r\n\r\n",0)).bytes("https://market.example/",20,ModernHttps.NEVER);}});
  ok(new String(client(new Child("HTTP/1.1 302 Found\r\nLocation: https://cdn.example/file\r\n\r\n",0),new Child(body("apk"),0)).bytes("https://market.example/",20,ModernHttps.NEVER)).equals("apk"));
  rejects(ModernHttps.Reason.POLICY,new Action(){public void run()throws Exception{client(new Child("HTTP/1.1 302 Found\r\nLocation: https://evil.example/file\r\n\r\n",0)).bytes("https://market.example/",20,ModernHttps.NEVER);}});
  rejects(ModernHttps.Reason.PROTOCOL,new Action(){public void run()throws Exception{client(new Child("HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 1\r\n\r\nx",0)).bytes("https://market.example/",20,ModernHttps.NEVER);}});
  rejects(ModernHttps.Reason.PROTOCOL,new Action(){public void run()throws Exception{client(new Child("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nx",0)).bytes("https://market.example/",20,ModernHttps.NEVER);}});
  rejects(ModernHttps.Reason.LIMIT,new Action(){public void run()throws Exception{client(new Child(body("12345"),0)).bytes("https://market.example/",2,ModernHttps.NEVER);}});
  rejects(ModernHttps.Reason.LIMIT,new Action(){public void run()throws Exception{client(new Child("HTTP/1.1 200 OK\r\n\r\n12345",0)).bytes("https://market.example/",2,ModernHttps.NEVER);}});
  rejects(ModernHttps.Reason.PROTOCOL,new Action(){public void run()throws Exception{client(new Child(body("abc"),0)).fetch("https://market.example/",20,ModernHttps.NEVER,new ModernHttps.Reader<Integer>(){public Integer read(InputStream in){return 0;}});}});
  final File target=new File(argv[0],"app.apk");String hash="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
  client(new Child(body("abc"),0)).download("https://market.example/a",target,3,hash,ModernHttps.NEVER);ok(target.length()==3);
  rejects(ModernHttps.Reason.LOCAL,new Action(){public void run()throws Exception{client().download("https://market.example/a",target,3,"0000000000000000000000000000000000000000000000000000000000000000",ModernHttps.NEVER);}});ok(target.length()==3);target.delete();
  rejects(ModernHttps.Reason.INTEGRITY,new Action(){public void run()throws Exception{client(new Child(body("abc"),0)).download("https://market.example/a",target,3,"0000000000000000000000000000000000000000000000000000000000000000",ModernHttps.NEVER);}});ok(!target.exists());
  final String good=hash;rejects(ModernHttps.Reason.CERTIFICATE,new Action(){public void run()throws Exception{client(new Child(body("abc"),60)).download("https://market.example/a",target,3,good,ModernHttps.NEVER);}});ok(!target.exists());
  final Child blocked=new Child("",0){public InputStream getInputStream(){return new InputStream(){public int read(){while(!destroyed){try{Thread.sleep(10);}catch(InterruptedException ignored){}}return -1;}};}};
  final long start=System.nanoTime();rejects(ModernHttps.Reason.CANCELLED,new Action(){public void run()throws Exception{client(blocked).bytes("https://market.example/",20,new ModernHttps.Cancellation(){public boolean cancelled(){return System.nanoTime()-start>150000000L;}});}});ok(blocked.destroyed&&System.nanoTime()-start<3000000000L);
  final ModernHttps disconnected=new ModernHttps(new File("curl"),new File("ca"),Arrays.asList("market.example"),new ModernHttps.Network(){public void check()throws IOException{throw new IOException("changed");}public List<String> arguments(){return Collections.emptyList();}},new ModernHttps.Processes(){public Process start(List<String> args){throw new AssertionError("network blocked before process");}});
  rejects(ModernHttps.Reason.NETWORK,new Action(){public void run()throws Exception{disconnected.bytes("https://market.example/",20,ModernHttps.NEVER);}});
  System.out.println("PASS "+cases+" shared HTTPS checks");
 }
}'''
class ModernHttpsTests(unittest.TestCase):
 def test_shared_client(self):
  java,javac=java_tools()
  with tempfile.TemporaryDirectory() as tmp:
   out=Path(tmp);(out/'ModernHarness.java').write_text(HARNESS)
   r=subprocess.run([javac,'-source','7','-target','7','-d',str(out),str(ROOT/'shared/src/com/veloster/network/ModernHttps.java'),str(out/'ModernHarness.java')],capture_output=True,text=True);self.assertEqual(r.returncode,0,r.stderr)
   r=subprocess.run([java,'-cp',str(out),'ModernHarness',str(out)],capture_output=True,text=True,timeout=15);self.assertEqual(r.returncode,0,r.stdout+r.stderr);print(r.stdout.strip())
