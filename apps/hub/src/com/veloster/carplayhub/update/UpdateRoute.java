package com.veloster.carplayhub.update;
/** Best-route check for legacy OEM USB networking; equal-priority ambiguity fails closed. */
public final class UpdateRoute {
 private UpdateRoute(){}
 public static boolean localDefault(String routes){
  long best=Long.MAX_VALUE;boolean allowed=false;
  for(String row:routes.split("\n")){
   String[] f=row.trim().split("\\s+");if(f.length<8||!f[1].equals("00000000")||!f[7].equals("00000000"))continue;
   try{int flags=Integer.parseInt(f[3],16);long metric=Long.parseLong(f[6]);if((flags&1)==0||metric<0)continue;
    boolean local=f[0].matches("(wlan|eth|usb|rndis|bnep)[0-9]+");
    if(metric<best){best=metric;allowed=local;}else if(metric==best)allowed&=local;
   }catch(NumberFormatException ignored){return false;}
  }
  return best!=Long.MAX_VALUE&&allowed;
 }
}
