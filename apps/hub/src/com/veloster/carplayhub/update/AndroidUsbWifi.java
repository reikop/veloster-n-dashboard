package com.veloster.carplayhub.update;

import android.os.SystemClock;
import java.io.*;
import java.net.*;
import java.util.Enumeration;

/** Reads only the connection manager's public lease; never handles credentials. */
public final class AndroidUsbWifi {
 private static final File STATE=new File("/data/local/tmp/vn-usb-wifi/state.properties");
 private AndroidUsbWifi(){}
 private static String read(File file,int limit)throws IOException{return new String(UpdateFiles.read(file,limit),"US-ASCII");}
 private static final ExternalWifiLease.Environment ENV=new ExternalWifiLease.Environment(){
  public String state()throws IOException{return read(STATE,2048);}
  public String bootId()throws IOException{return read(new File("/proc/sys/kernel/random/boot_id"),64).trim();}
  public long elapsedMillis(){return SystemClock.elapsedRealtime();}
  public String routes()throws IOException{return read(new File("/proc/net/route"),32768);}
  public boolean hasAddress(String name,String address)throws IOException{
   NetworkInterface device=NetworkInterface.getByName(name);if(device==null||!device.isUp())return false;
   for(Enumeration<InetAddress> all=device.getInetAddresses();all.hasMoreElements();){InetAddress ip=all.nextElement();if(ip instanceof Inet4Address&&address.equals(ip.getHostAddress()))return true;}
   return false;
  }
 };
 public static ExternalWifiLease currentOrNull()throws IOException{
  if(!STATE.exists())return null;
  return ExternalWifiLease.current(ENV);
 }
}
