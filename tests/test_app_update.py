"""Exercise real updater cryptography/staging/network policy with temporary signed fixtures."""
from pathlib import Path
import importlib.util
import subprocess
import tempfile
import unittest
import zipfile
from test_quick_entry_runtime import java_tools

ROOT=Path(__file__).resolve().parents[1]
HARNESS=r'''import com.veloster.carplayhub.update.*;
import java.io.*;import java.net.*;import java.security.*;import java.security.cert.Certificate;import java.nio.file.*;import java.util.*;import javax.net.ssl.*;import java.util.jar.*;
public class UpdateHarness {
 static int checks;static KeyPair key;static String cert;static File apk;
 interface Action{void run()throws Exception;}
 static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
 static void reject(Action a,String why)throws Exception{boolean failed=false;try{a.run();}catch(IOException e){failed=true;}catch(GeneralSecurityException e){failed=true;}check(failed,why);}
 static byte[] raw(File f,int version)throws Exception{return ("VNUPDATE1\npackage=com.veloster.dashboard\nversion="+version+"\nbytes="+f.length()+"\nsha256="+UpdateFiles.hash(f,UpdateFiles.NEVER)+"\ncertificate="+cert+"\nproduct=jsn_kr\nincremental=eng.vmadmin.20250902.081552\nsdk=17\nurl=https://github.com/reikop/veloster-n-dashboard/releases/download/test/test.apk\n").getBytes("US-ASCII");}
 static byte[] sign(byte[] data)throws Exception{Signature s=Signature.getInstance("SHA256withRSA");s.initSign(key.getPrivate());s.update(data);return s.sign();}
 static UpdateManifest manifest(File f)throws Exception{byte[] b=raw(f,7);return UpdateManifest.verify(b,sign(b),key.getPublic());}
 static class Connection extends HttpsURLConnection {
  int response;String location;byte[] data;boolean closed;Connection(URL u,int code,String to,byte[] bytes){super(u);response=code;location=to;data=bytes;}
  public int getResponseCode(){return response;}public String getHeaderField(String name){return location;}public InputStream getInputStream(){return new ByteArrayInputStream(data);}
  public void disconnect(){closed=true;}public void connect(){}public boolean usingProxy(){return false;}public String getCipherSuite(){return "test";}public Certificate[] getLocalCertificates(){return null;}public Certificate[] getServerCertificates(){return null;}
 }
 public static void main(String[] args)throws Exception{
  apk=new File(args[0]);cert=args[1];KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);key=gen.generateKeyPair();
  final byte[] data=raw(apk,7),sig=sign(data);final UpdateManifest m=UpdateManifest.verify(data,sig,key.getPublic());
  check(m.version==7&&m.bytes==apk.length(),"valid release metadata");
  reject(new Action(){public void run()throws Exception{byte[] b=data.clone();b[30]^=1;UpdateManifest.verify(b,sig,key.getPublic());}},"tamper rejected");
  final PublicKey other=gen.generateKeyPair().getPublic();reject(new Action(){public void run()throws Exception{UpdateManifest.verify(data,sig,other);}},"wrong publisher");
  for(final String[] replacement:new String[][]{{"version=7","version=0"},{"version=7","version=2147483648"},{"sdk=17","sdk=19"},{"package=com.veloster.dashboard","package=evil.app"},{"bytes=","bytes=0"},{"https://github.com","http://github.com"},{"https://github.com","https://attacker.example"},{"version=7","version=7\nversion=8"}}){reject(new Action(){public void run()throws Exception{byte[] b=new String(data,"US-ASCII").replace(replacement[0],replacement[1]).getBytes("US-ASCII");UpdateManifest.verify(b,sign(b),key.getPublic());}},"invalid signed metadata");}
  m.checkTarget(UpdateManifest.DASHBOARD,6,"jsn_kr","eng.vmadmin.20250902.081552",17);checks++;
  reject(new Action(){public void run()throws Exception{m.checkTarget(UpdateManifest.HUB,6,"jsn_kr","eng.vmadmin.20250902.081552",17);}},"cross-package");
  reject(new Action(){public void run()throws Exception{m.checkTarget(UpdateManifest.DASHBOARD,7,"jsn_kr","eng.vmadmin.20250902.081552",17);}},"same/downgrade");
  reject(new Action(){public void run()throws Exception{m.checkTarget(UpdateManifest.DASHBOARD,6,"other","eng.vmadmin.20250902.081552",17);}},"wrong product");
  UpdateFiles.verifyApk(apk,m,UpdateFiles.NEVER);checks++;
  final File stage=new File(args[2],"copy.apk");InputStream input=new FileInputStream(apk);try{UpdateFiles.copy(input,stage,m,UpdateFiles.NEVER);}finally{input.close();}check(UpdateFiles.hash(stage,UpdateFiles.NEVER).equals(m.sha256),"staged copy");
  reject(new Action(){public void run()throws Exception{UpdateFiles.copy(new ByteArrayInputStream(new byte[0]),stage,m,UpdateFiles.NEVER);}},"cannot overwrite stage");
  final File partial=new File(args[2],"partial.apk");reject(new Action(){public void run()throws Exception{UpdateFiles.copy(new ByteArrayInputStream(new byte[]{1}),partial,m,UpdateFiles.NEVER);}},"short input");check(!partial.exists(),"partial removed");
  reject(new Action(){public void run()throws Exception{InputStream in=new FileInputStream(apk);try{UpdateFiles.copy(in,partial,m,new UpdateFiles.Cancellation(){public boolean cancelled(){return true;}});}finally{in.close();}}},"cancelled copy");check(!partial.exists(),"cancel cleanup");
  reject(new Action(){public void run()throws Exception{UpdateFiles.read(new ByteArrayInputStream(new byte[4097]),4096,UpdateFiles.NEVER);}},"manifest size cap");
  final File bad=new File(args[2],"unsigned.apk");Files.copy(new File(args[3]).toPath(),bad.toPath());reject(new Action(){public void run()throws Exception{UpdateFiles.verifyApk(bad,manifest(bad),UpdateFiles.NEVER);}},"unsigned APK entry");
  final File multidex=new File(args[4]);reject(new Action(){public void run()throws Exception{UpdateFiles.verifyApk(multidex,manifest(multidex),UpdateFiles.NEVER);}},"secondary classes10.dex rejected even without classes2.dex");
  SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,null,null);final UpdateTransport.Tls12Factory strictTls=new UpdateTransport.Tls12Factory(tls.getSocketFactory());SSLSocket socket=(SSLSocket)strictTls.createSocket();
  check(Arrays.equals(socket.getEnabledProtocols(),new String[]{"TLSv1.2"}),"explicit TLS1.2 only, no SSL3/TLS1 fallback");socket.close();reject(new Action(){public void run()throws Exception{strictTls.createSocket();}},"platform SSL3 retry cannot create a second socket");
  final Connection connection=new Connection(new URL("https://github.com/x"),200,null,new byte[]{1,2});UpdateTransport net=new UpdateTransport(new UpdateTransport.ConnectionFactory(){public HttpsURLConnection open(URL url){return connection;}});
  check(net.bytes("https://github.com/x",2,UpdateFiles.NEVER).length==2&&connection.closed,"bounded response/disconnect");
  final Connection switched=new Connection(new URL("https://github.com/x"),200,null,new byte[]{1,2});
  final UpdateTransport changing=new UpdateTransport(new UpdateTransport.ConnectionFactory(){public HttpsURLConnection open(URL url){return switched;}},new UpdateTransport.Policy(){int calls;public void check()throws IOException{if(++calls>1)throw new IOException("network changed");}});
  reject(new Action(){public void run()throws Exception{changing.bytes("https://github.com/x",2,UpdateFiles.NEVER);}},"network change before read aborts");check(switched.closed,"changed network connection closed");
  final UpdateTransport denied=new UpdateTransport(new UpdateTransport.ConnectionFactory(){public HttpsURLConnection open(URL url){throw new AssertionError("must not open denied network");}},new UpdateTransport.Policy(){public void check()throws IOException{throw new IOException("cellular");}});
  reject(new Action(){public void run()throws Exception{denied.bytes("https://github.com/x",2,UpdateFiles.NEVER);}},"policy checked before opening");
  final UpdateTransport redirect=new UpdateTransport(new UpdateTransport.ConnectionFactory(){public HttpsURLConnection open(URL url){return new Connection(url,302,"http://attacker.example/file",new byte[0]);}});
  reject(new Action(){public void run()throws Exception{redirect.bytes("https://github.com/x",2,UpdateFiles.NEVER);}},"redirect downgrade");
  final UpdateTransport looping=new UpdateTransport(new UpdateTransport.ConnectionFactory(){public HttpsURLConnection open(URL url){return new Connection(url,302,"https://github.com/x",new byte[0]);}});
  reject(new Action(){public void run()throws Exception{looping.bytes("https://github.com/x",2,UpdateFiles.NEVER);}},"redirect bound");
  reject(new Action(){public void run()throws Exception{UpdateTransport.checkUrl(new URL("https://user:pass@github.com/x"));}},"credentials rejected");
  reject(new Action(){public void run()throws Exception{UpdateTransport.checkUrl(new URL("https://example.org/x"));}},"unknown host rejected");
  String usb="usb0 00000000 0100000A 0003 0 0 10 00000000 0 0 0\n",sim="rmnet0 00000000 0100000A 0003 0 0 1 00000000 0 0 0\n";
  check(UpdateRoute.localDefault(usb),"USB network default allowed");check(!UpdateRoute.localDefault(sim),"SIM route rejected");check(!UpdateRoute.localDefault(usb+sim),"preferred SIM overrides USB");check(!UpdateRoute.localDefault(usb+sim.replace("0 0 1 ","0 0 10 ")),"ambiguous same metric rejected");check(!UpdateRoute.localDefault(""),"unknown network rejected");
  check(UpdateTransport.feed(UpdateManifest.HUB).endsWith("/hub/"),"separate feeds");
  System.out.println("PASS "+checks+" updater cryptography, APK, staging, transport and route checks");
 }
}'''

class AppUpdateTests(unittest.TestCase):
    def test_real_signed_fixtures(self):
        java,javac=map(Path,java_tools())
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            keytool=java.with_name('keytool');jarsigner=java.with_name('jarsigner')
            def run(cmd):
                r=subprocess.run(list(map(str,cmd)),capture_output=True,text=True,timeout=40)
                self.assertEqual(r.returncode,0,r.stdout+r.stderr)
                return r.stdout
            run([keytool,'-genkeypair','-alias','fixture','-keyalg','RSA','-keysize','2048','-validity','2','-dname','CN=Synthetic updater test','-keystore',root/'test.p12','-storepass','fixture-password','-keypass','fixture-password'])
            run([keytool,'-exportcert','-alias','fixture','-keystore',root/'test.p12','-storepass','fixture-password','-file',root/'cert.der'])
            with zipfile.ZipFile(root/'fixture.apk','w') as z:
                z.writestr('classes.dex',b'dex\n035\0'+b'fixture-not-executable')
                z.writestr('AndroidManifest.xml',b'synthetic-not-installable')
            run([jarsigner,'-keystore',root/'test.p12','-storepass','fixture-password','-sigalg','SHA256withRSA','-digestalg','SHA-256',root/'fixture.apk','fixture'])
            with zipfile.ZipFile(root/'unsigned.apk','w') as z:
                z.writestr('classes.dex',b'dex\n035\0');z.writestr('AndroidManifest.xml',b'fixture')
            with zipfile.ZipFile(root/'multidex.apk','w') as z:
                z.writestr('classes.dex',b'dex\n035\0');z.writestr('AndroidManifest.xml',b'fixture');z.writestr('classes10.dex',b'dex\n035\0')
            run([jarsigner,'-keystore',root/'test.p12','-storepass','fixture-password','-sigalg','SHA256withRSA','-digestalg','SHA-256',root/'multidex.apk','fixture'])
            source=ROOT/'apps/hub/src/com/veloster/carplayhub/update'
            (root/'UpdateHarness.java').write_text(HARNESS)
            names=('UpdateManifest','UpdateFiles','UpdateTransport','UpdateRoute','UpdateTrust')
            run([javac,'-source','7','-target','7','-d',root,*[source/(n+'.java') for n in names],root/'UpdateHarness.java'])
            import hashlib
            output=run([java,'-cp',root,'UpdateHarness',root/'fixture.apk',hashlib.sha256((root/'cert.der').read_bytes()).hexdigest(),root,root/'unsigned.apk',root/'multidex.apk'])
            self.assertIn('PASS 41',output)

    def test_authoring_uses_one_snapshot(self):
        from types import SimpleNamespace
        from unittest.mock import patch
        import hashlib
        spec=importlib.util.spec_from_file_location('prepare',ROOT/'scripts/prepare_app_update.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);apk=root/'input.apk';initial=b'original signed input';apk.write_bytes(initial)
            args=SimpleNamespace(apk=apk,out=root/'bundle',java='/mock/java',aapt2='/mock/aapt2',key=root/'key',url='https://github.com/reikop/veloster-n-dashboard/releases/download/v7/dashboard.apk')
            observed=[]
            def fake_run(command):
                words=list(map(str,command))
                if 'certificate' in words or 'badging' in words or 'verify' in words:
                    observed.append(Path(words[-1]).read_bytes())
                    apk.write_bytes(b'replaced during build')
                if 'certificate' in words:return '0'*64
                if 'badging' in words:return "package: name='com.veloster.dashboard' versionCode='7'\nminSdkVersion:'17'\ntargetSdkVersion:'17'"
                if words[0]=='openssl':Path(words[words.index('-out')+1]).write_bytes(b'mocked signature')
                return 'verified'
            with patch.object(module,'run',side_effect=fake_run),patch('builtins.print'):
                module.prepare(args)
            self.assertEqual(observed,[initial]*3)
            self.assertEqual((args.out/'package.apk').read_bytes(),initial)
            self.assertIn(hashlib.sha256(initial).hexdigest(),(args.out/'manifest.txt').read_text())
            args.out=root/'failed-bundle'
            with patch.object(module,'run',side_effect=fake_run),patch.object(module.shutil,'copyfile',side_effect=OSError('disk full')):
                with self.assertRaises(OSError):module.prepare(args)
            self.assertFalse(args.out.exists())

    def test_package_metadata(self):
        spec=importlib.util.spec_from_file_location('prepare',ROOT/'scripts/prepare_app_update.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as tmp:
            apk=Path(tmp)/'x.apk';apk.write_bytes(b'fixture')
            valid="package: name='com.veloster.dashboard' versionCode='7'\nminSdkVersion:'17'\ntargetSdkVersion:'17'"
            url='https://github.com/reikop/veloster-n-dashboard/releases/download/v7/dashboard.apk'
            self.assertTrue(module.metadata(valid,apk,url,'0'*64).startswith(b'VNUPDATE1\n'))
            for bad in [valid.replace("'17'","'19'"),valid.replace('com.veloster.dashboard','evil.app')]:
                with self.assertRaises(ValueError):module.metadata(bad,apk,url,'0'*64)
            with self.assertRaises(ValueError):module.metadata(valid,apk,'https://other.example/a.apk','0'*64)

if __name__=='__main__':unittest.main()
