package com.veloster.carplayhub.wifi;

import java.io.*;
import java.util.*;

/** Fixed manager operations. No user-supplied value is interpolated into a shell command. */
public final class UsbWifiClient {
 public static final String MANAGER="/data/local/tmp/vn-usb-wifi/bin/vn-wifi-manager";
 public interface Processes{Process start(List<String> command)throws IOException;}
 private final Processes processes;private final long timeoutMillis;
 public UsbWifiClient(){this(new Processes(){public Process start(List<String> command)throws IOException{return new ProcessBuilder(command).redirectErrorStream(true).start();}},45000);}
 public UsbWifiClient(Processes processes,long timeoutMillis){this.processes=processes;this.timeoutMillis=timeoutMillis;}
 public void configure(byte[] input)throws IOException{try{execute("configure",input);}finally{Arrays.fill(input,(byte)0);}}
 public void start()throws IOException{execute("start",null);}
 public void stop()throws IOException{execute("stop",null);}
 public String status()throws IOException{return execute("status",null);}
 public void remotePassword(char[] password)throws IOException{
  byte[] encoded=null;
  try{
   if(password==null||password.length<8||password.length>128)throw new IOException("Password length");
   for(char c:password)if(c==0)throw new IOException("Password character");
   java.nio.ByteBuffer data=java.nio.charset.Charset.forName("UTF-8").newEncoder().encode(java.nio.CharBuffer.wrap(password));
   encoded=new byte[data.remaining()];data.get(encoded);if(data.hasArray())Arrays.fill(data.array(),(byte)0);
   if(encoded.length>128)throw new IOException("Password length");
   remote("stop");executeAt("/data/local/tmp/vn-usb-wifi/remote/bin/vn-remote-control","password",encoded);
  }finally{if(encoded!=null)Arrays.fill(encoded,(byte)0);if(password!=null)Arrays.fill(password,'\0');}
 }
 public String certificates(String verb)throws IOException{
  if(!Arrays.asList("start","stop","status").contains(verb))throw new IOException("Unknown certificate action");
  return executeAt("/data/local/tmp/vn-usb-wifi/https-ca/bin/vn-ca-control",verb,null);
 }
 public String internet(String verb)throws IOException{
  if(!Arrays.asList("start","stop","status").contains(verb))throw new IOException("Unknown internet action");
  return executeAt("/data/local/tmp/vn-usb-wifi/internet/bin/vn-internet-control",verb,null);
 }
 public String remote(String verb)throws IOException{
  if(!Arrays.asList("start","stop","status","identity").contains(verb))throw new IOException("Unknown terminal action");
  return executeAt("/data/local/tmp/vn-usb-wifi/remote/bin/vn-remote-control",verb,null);
 }
 private String execute(String verb,byte[] input)throws IOException{
  if(!Arrays.asList("configure","start","stop","status").contains(verb))throw new IOException("Unknown Wi-Fi action");
  return executeAt(MANAGER,verb,input);
 }
 private String executeAt(String program,String verb,byte[] input)throws IOException{
  final Process child=processes.start(Arrays.asList("/system/bin/amossu","-c",program+" "+verb));
  final ByteArrayOutputStream captured=new ByteArrayOutputStream();final boolean[] overflow={false};
  Thread reader=new Thread(new Runnable(){public void run(){try{InputStream stream=child.getInputStream();byte[] chunk=new byte[512];int n;while((n=stream.read(chunk))!=-1){synchronized(captured){if(captured.size()+n>4096){overflow[0]=true;child.destroy();return;}captured.write(chunk,0,n);}}}catch(IOException ignored){}}},"usb-wifi-output");reader.setDaemon(true);reader.start();
  try{
   OutputStream pipe=child.getOutputStream();if(input!=null)pipe.write(input);pipe.close();
   long deadline=System.nanoTime()+timeoutMillis*1000000L;
   int code;
   while(true){try{code=child.exitValue();break;}catch(IllegalThreadStateException running){if(System.nanoTime()>=deadline)throw new IOException("Wi-Fi action timed out");Thread.sleep(50);}}
   reader.join(1000);
   synchronized(captured){if(code!=0||overflow[0]||reader.isAlive())throw new IOException("Wi-Fi action failed");return captured.toString("US-ASCII").trim();}
  }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Wi-Fi action interrupted");}
  finally{child.destroy();try{child.getOutputStream().close();}catch(IOException ignored){}try{child.getInputStream().close();}catch(IOException ignored){}try{child.getErrorStream().close();}catch(IOException ignored){}}
 }
}
