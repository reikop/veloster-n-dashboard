package com.veloster.carplayhub.update;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import java.io.*;

/** A manual check/download does not authorize use of the built-in cellular modem. */
public final class UpdateNetwork {
 private UpdateNetwork(){}
 public static void requireLocalConnection(Context context)throws IOException{
  // External Wi-Fi has no OEM ConnectivityManager agent. Its own lease must
  // prove a live address, current boot and sole best default route.
  if(AndroidUsbWifi.currentOrNull()!=null)return;
  // The active network label can lag behind a changed default route on this OEM.
  String routes=new String(UpdateFiles.read(new File("/proc/net/route"),32768),"US-ASCII");
  UpdateManifest.require(UpdateRoute.localDefault(routes),"local default route unavailable");
  ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
  NetworkInfo active=cm==null?null:cm.getActiveNetworkInfo();
  if(active!=null&&active.isConnected()){
   int type=active.getType();
   if(type==ConnectivityManager.TYPE_WIFI||type==ConnectivityManager.TYPE_ETHERNET||type==ConnectivityManager.TYPE_BLUETOOTH)return;
   if(type==ConnectivityManager.TYPE_MOBILE||(type>=2&&type<=6))throw new IOException("Vehicle cellular connection is not enabled for updates");
  }
  // OEM USB adapters may lack a ConnectivityManager agent. Accept only an unambiguous local default route.
  // The route was checked above; no agent is required for a legacy local adapter.
 }
}
