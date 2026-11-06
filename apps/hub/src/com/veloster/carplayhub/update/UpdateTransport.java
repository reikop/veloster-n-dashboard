package com.veloster.carplayhub.update;

import java.io.*;
import java.net.*;
import javax.net.ssl.*;
import java.security.GeneralSecurityException;

/** User-initiated HTTPS only; system trust and hostname verification are retained. */
public class UpdateTransport {
 public interface ConnectionFactory {HttpsURLConnection open(URL url)throws IOException;}
 public interface Policy {void check()throws IOException;}
 private static final Policy ALLOW=new Policy(){public void check(){}};
 private final ConnectionFactory factory;private final Policy policy;
 public UpdateTransport(){this(ALLOW);}
 public UpdateTransport(Policy policy){this(new ConnectionFactory(){public HttpsURLConnection open(URL url)throws IOException{
  HttpsURLConnection c=(HttpsURLConnection)url.openConnection();
  try{SSLContext tls=SSLContext.getInstance("TLSv1.2");tls.init(null,null,null);c.setSSLSocketFactory(new Tls12Factory(tls.getSocketFactory()));}
  catch(GeneralSecurityException e){throw new IOException("TLS 1.2 unavailable");}return c;
 }},policy);}
 public UpdateTransport(ConnectionFactory factory){this(factory,ALLOW);}
 public UpdateTransport(ConnectionFactory factory,Policy policy){this.factory=factory;this.policy=policy;}
 /** API16-19 support TLS1.2 but do not enable it by default on every socket. */
 public static final class Tls12Factory extends SSLSocketFactory {
  private final SSLSocketFactory delegate;private boolean socketCreated;
  // API17 HttpsURLConnection retries a failed handshake with SSLv3. Refuse its second socket.
  private synchronized void permit()throws IOException{if(socketCreated)throw new IOException("Legacy TLS retry blocked");socketCreated=true;}
  public Tls12Factory(SSLSocketFactory factory){delegate=factory;}
  private Socket enable(Socket socket)throws IOException{
   try{if(!(socket instanceof SSLSocket))throw new IOException("TLS socket required");((SSLSocket)socket).setEnabledProtocols(new String[]{"TLSv1.2"});return socket;}
   catch(IllegalArgumentException e){try{socket.close();}catch(IOException ignored){}throw new IOException("TLS1.2 socket unavailable");}
   catch(IOException e){socket.close();throw e;}
  }
  public String[] getDefaultCipherSuites(){return delegate.getDefaultCipherSuites();}
  public String[] getSupportedCipherSuites(){return delegate.getSupportedCipherSuites();}
  public Socket createSocket()throws IOException{permit();return enable(delegate.createSocket());}
  public Socket createSocket(Socket s,String h,int p,boolean close)throws IOException{permit();return enable(delegate.createSocket(s,h,p,close));}
  public Socket createSocket(String h,int p)throws IOException{permit();return enable(delegate.createSocket(h,p));}
  public Socket createSocket(String h,int p,InetAddress l,int lp)throws IOException{permit();return enable(delegate.createSocket(h,p,l,lp));}
  public Socket createSocket(InetAddress h,int p)throws IOException{permit();return enable(delegate.createSocket(h,p));}
  public Socket createSocket(InetAddress h,int p,InetAddress l,int lp)throws IOException{permit();return enable(delegate.createSocket(h,p,l,lp));}
 }
 public static String feed(String packageName)throws IOException{
  UpdateManifest.require(UpdateManifest.DASHBOARD.equals(packageName)||UpdateManifest.HUB.equals(packageName),"feed app");
  return "https://raw.githubusercontent.com/reikop/veloster-n-dashboard/main/updates/stable/"+(UpdateManifest.DASHBOARD.equals(packageName)?"dashboard":"hub")+"/";
 }
 public static void checkUrl(URL url)throws IOException{
  String h=url.getHost();UpdateManifest.require("https".equals(url.getProtocol())&&url.getUserInfo()==null&&url.getRef()==null&&(url.getPort()==-1||url.getPort()==443),"HTTPS required");
  UpdateManifest.require(h.equals("raw.githubusercontent.com")||h.equals("github.com")||h.equals("release-assets.githubusercontent.com"),"download host");
 }
 public interface Reader<T>{T read(InputStream input)throws Exception;}
 public <T>T fetch(String address,final UpdateFiles.Cancellation requested,Reader<T> reader)throws Exception{
  final long started=System.nanoTime();final UpdateFiles.Cancellation stop=new UpdateFiles.Cancellation(){public boolean cancelled(){return requested.cancelled()||System.nanoTime()-started>180000000000L;}};
  URL next=new URL(address);
  for(int redirect=0;redirect<=4;redirect++){
   UpdateFiles.alive(stop);policy.check();checkUrl(next);HttpsURLConnection c=factory.open(next);
   try{
    c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(15000);c.setUseCaches(false);c.setRequestProperty("Accept-Encoding","identity");
    int code=c.getResponseCode();
    if(code==301||code==302||code==303||code==307||code==308){String location=c.getHeaderField("Location");UpdateManifest.require(location!=null,"redirect");next=new URL(next,location);continue;}
    if(code==404)throw new FileNotFoundException("No published update");UpdateManifest.require(code==200,"HTTP status "+code);
    InputStream in=new FilterInputStream(c.getInputStream()){public int read()throws IOException{UpdateFiles.alive(stop);policy.check();return super.read();}public int read(byte[] b,int off,int len)throws IOException{UpdateFiles.alive(stop);policy.check();return super.read(b,off,len);}};try{return reader.read(in);}finally{in.close();}
   }finally{c.disconnect();}
  }
  throw new IOException("Update rejected: too many redirects");
 }
 public byte[] bytes(String url,final int limit,final UpdateFiles.Cancellation stop)throws Exception{return fetch(url,stop,new Reader<byte[]>(){public byte[] read(InputStream in)throws Exception{return UpdateFiles.read(in,limit,stop);}});}
}
