package com.veloster.carplayhub.update;

import java.io.*;
import java.util.Arrays;

/** Bounded local observations only. Never reads serials, MACs, SSIDs, credentials or IP addresses. */
public final class NetworkSnapshot {
 private static final String[] FIELDS={"idVendor","idProduct","bcdDevice","bDeviceClass","bDeviceSubClass","bDeviceProtocol","bInterfaceNumber","bInterfaceClass","bInterfaceSubClass","bInterfaceProtocol"};
 public final String descriptors,summary;
 private NetworkSnapshot(String d,String s){descriptors=d;summary=s;}
 private static String read(File f,int limit){try{return new String(UpdateFiles.read(f,limit),"US-ASCII").trim();}catch(Exception e){return "";}}
 private static File[] list(File dir){try{File[] f=dir.listFiles();if(f!=null){Arrays.sort(f);return f;}}catch(SecurityException ignored){}return new File[0];}
 public static NetworkSnapshot capture(File usb,File net,File routes,File release){
  StringBuilder d=new StringBuilder(),s=new StringBuilder("VN network observation v1\n");
  String kernel=read(release,128);s.append("kernel_release=").append(kernel.matches("[A-Za-z0-9._+-]+")?kernel:"unknown").append('\n');
  int fields=0,missing=0;File[] devices=list(usb);
  for(int i=0;i<devices.length&&i<256;i++){
   File device=devices[i];String name=device.getName();if(!name.matches("[0-9]+-[0-9.]+(:[0-9]+\\.[0-9]+)?|usb[0-9]+"))continue;
   for(String field:FIELDS){String value=read(new File(device,field),16);int width=(field.equals("idVendor")||field.equals("idProduct")||field.equals("bcdDevice"))?4:2;
    if(value.matches("[0-9a-fA-F]{"+width+"}")){d.append("/sys/bus/usb/devices/").append(name).append('/').append(field).append('\n').append(value).append('\n');fields++;}else missing++;
   }
  }
  s.append("usb_entries_seen=").append(devices.length).append("\nusb_descriptor_fields_read=").append(fields).append("\nusb_absent_or_unreadable_fields=").append(missing).append("\nusb_entry_limit_reached=").append(devices.length>256).append('\n');
  File[] interfaces=list(net);for(int i=0;i<interfaces.length&&i<64;i++){
   File n=interfaces[i];if(!n.getName().matches("[A-Za-z0-9_.-]{1,32}"))continue;
   String state=read(new File(n,"operstate"),32);s.append("interface=").append(n.getName()).append(" state=").append(state.matches("[a-z]+")?state:"unknown").append('\n');
  }
  String route=read(routes,32768);s.append("local_default_route=").append(route.length()==0?"unknown":UpdateRoute.localDefault(route)?"yes":"no").append('\n');
  s.append("Missing observations are unknown, not proof of missing hardware. No connectivity request or setting change.\n");
  return new NetworkSnapshot(d.toString(),s.toString());
 }
 public static NetworkSnapshot capture(){return capture(new File("/sys/bus/usb/devices"),new File("/sys/class/net"),new File("/proc/net/route"),new File("/proc/sys/kernel/osrelease"));}
 private static void write(File f,String text)throws IOException{FileOutputStream out=new FileOutputStream(f);try{out.write(text.getBytes("UTF-8"));out.flush();out.getFD().sync();}finally{out.close();}}
 public void save(File folder)throws IOException{
  if(!folder.mkdirs()&&!folder.isDirectory())throw new IOException("diagnostic directory");
  write(new File(folder,"usb-match-descriptors.stdout"),descriptors);write(new File(folder,"network-summary.txt"),summary);
 }
}
