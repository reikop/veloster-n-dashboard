package com.veloster.carplayhub.update;

import java.io.IOException;
import java.util.*;

/** Secret-free snapshot from the separately installed USB Wi-Fi connection manager. */
public final class ExternalWifiLease implements NativeUpdateTransport.Binding {
 public interface Environment {
  String state()throws IOException;String bootId()throws IOException;long elapsedMillis();
  boolean hasAddress(String device,String address)throws IOException;String routes()throws IOException;
 }
 public final String device,address,dns,generation,bootId;public final long expires;
 private final Environment environment;
 private ExternalWifiLease(Map<String,String> p,Environment environment)throws IOException{
  this.environment=environment;device=p.get("interface");address=p.get("address");dns=p.get("dns");generation=p.get("generation");bootId=p.get("boot_id");
  require("1".equals(p.get("format"))&&device.matches("usbwifi[0-9]{1,2}"),"Wi-Fi state format");
  require(ipv4(address)&&generation.matches("[0-9a-f]{32}")&&bootId.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"),"Wi-Fi state identity");
  String[] servers=dns.split(",",-1);require(servers.length>=1&&servers.length<=3,"Wi-Fi DNS count");
  Set<String> unique=new HashSet<String>();for(String server:servers)require(ipv4(server)&&unique.add(server),"Wi-Fi DNS address");
  String time=p.get("expires_elapsed_ms");require(time.matches("[0-9]{1,16}"),"Wi-Fi lease expiry");
  try{expires=Long.parseLong(time);}catch(NumberFormatException e){throw new IOException("Wi-Fi lease expiry");}
 }
 private static void require(boolean value,String message)throws IOException{if(!value)throw new IOException(message);}
 public static boolean ipv4(String value){
  if(value==null)return false;String[] parts=value.split("\\.",-1);if(parts.length!=4)return false;
  int[] n=new int[4];for(int i=0;i<4;i++){if(!parts[i].matches("0|[1-9][0-9]{0,2}"))return false;try{n[i]=Integer.parseInt(parts[i]);}catch(NumberFormatException bad){return false;}if(n[i]>255)return false;}
  return n[0]>0&&n[0]<224&&n[0]!=127&&!(n[0]==169&&n[1]==254)&&!(n[0]==255&&n[1]==255&&n[2]==255&&n[3]==255);
 }
 public static ExternalWifiLease parse(String text,Environment env)throws IOException{
  require(text!=null&&text.length()<=2048&&text.endsWith("\n"),"Wi-Fi state length");
  Map<String,String> p=new HashMap<String,String>();
  for(String line:text.split("\n",-1)){
   if(line.length()==0)continue;require(line.matches("[a-z_]+=[ -~]+"),"Wi-Fi state syntax");int equal=line.indexOf('=');String key=line.substring(0,equal);
   require(p.put(key,line.substring(equal+1))==null,"Duplicate Wi-Fi field");
  }
  Set<String> expected=new HashSet<String>(Arrays.asList("format","interface","address","dns","generation","boot_id","expires_elapsed_ms"));
  require(p.keySet().equals(expected),"Wi-Fi state fields");return new ExternalWifiLease(p,env);
 }
 public static ExternalWifiLease current(Environment env)throws IOException{ExternalWifiLease lease=parse(env.state(),env);lease.validate();return lease;}
 private void validate()throws IOException{
  long now=environment.elapsedMillis();require(now>=0&&expires>now&&expires-now<=7L*24*60*60*1000,"Wi-Fi lease expired");
  require(bootId.equals(environment.bootId()),"Wi-Fi lease belongs to another boot");
  require(environment.hasAddress(device,address),"Wi-Fi interface changed");
  require(ownsDefault(environment.routes(),device),"Wi-Fi default route changed");
 }
 public void check()throws IOException{
  ExternalWifiLease fresh=parse(environment.state(),environment);
  require(device.equals(fresh.device)&&address.equals(fresh.address)&&dns.equals(fresh.dns)&&generation.equals(fresh.generation)&&bootId.equals(fresh.bootId),"Wi-Fi lease changed");
  fresh.validate();
 }
 public List<String> arguments()throws IOException{check();return Arrays.asList("--ipv4","--interface",address,"--dns-ipv4-addr",address,"--dns-servers",dns);}
 public static boolean ownsDefault(String routes,String device){
  long best=Long.MAX_VALUE;int winners=0;boolean own=false;
  for(String row:routes.split("\n")){
   String[] f=row.trim().split("\\s+");if(f.length<8||!f[1].equals("00000000")||!f[7].equals("00000000"))continue;
   try{int flags=Integer.parseInt(f[3],16);long metric=Long.parseLong(f[6]);if(metric<0)return false;if((flags&1)==0)continue;
    if(metric<best){best=metric;own=f[0].equals(device);winners=1;}else if(metric==best){winners++;}
   }catch(NumberFormatException bad){return false;}
  }
  return own&&winners==1;
 }
}
