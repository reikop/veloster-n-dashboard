import com.veloster.carplayhub.update.*;
import java.io.*;
import java.security.*;
import java.security.cert.Certificate;
import java.util.jar.*;

/** Host helper: uses the same signature and ZIP verifier as the API17 app. */
public final class UpdatePackageTool {
 public static void main(String[] args)throws Exception{
  if(args.length==2&&args[0].equals("certificate")){
   JarFile jar=new JarFile(args[1],true);try{JarEntry dex=jar.getJarEntry("classes.dex");if(dex==null)throw new IOException("No classes.dex");InputStream in=jar.getInputStream(dex);try{byte[] b=new byte[8192];while(in.read(b)!=-1){}}finally{in.close();}Certificate[] certs=dex.getCertificates();if(certs==null||certs.length!=1)throw new IOException("Requires one V1 APK signer");System.out.println(UpdateFiles.hex(MessageDigest.getInstance("SHA-256").digest(certs[0].getEncoded())));}finally{jar.close();}
  }else if(args.length==4&&args[0].equals("verify")){
   UpdateManifest m=UpdateManifest.verify(UpdateFiles.read(new File(args[1]),4096),UpdateFiles.read(new File(args[2]),256),UpdateTrust.key());UpdateFiles.verifyApk(new File(args[3]),m,UpdateFiles.NEVER);System.out.println("PASS signed update bundle: "+m.packageName+" v"+m.version);
  }else throw new IllegalArgumentException("certificate APK | verify MANIFEST SIGNATURE APK");
 }
}
