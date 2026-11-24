package com.veloster.network;

import java.io.*;
import java.net.URL;
import java.security.MessageDigest;
import java.util.*;

/** API17-compatible HTTPS GET client for a curated catalog and its downloads.
 * The caller authenticates helper/CA files and supplies hosts from trusted code.
 * No shell, proxy, cookies, credentials, plaintext or certificate bypass. */
public final class ModernHttps {
 public interface Cancellation {boolean cancelled();}
 public interface Network {void check()throws IOException;List<String> arguments()throws IOException;}
 public interface Processes {Process start(List<String> args)throws IOException;}
 public interface Reader<T> {T read(InputStream in)throws Exception;}
 public static final Cancellation NEVER=new Cancellation(){public boolean cancelled(){return false;}};
 public static final int MAX_DOWNLOAD=64*1024*1024;
 public enum Reason {CANCELLED,NETWORK,DNS,CONNECT,TIMEOUT,CERTIFICATE,TLS,HTTP,POLICY,LIMIT,PROTOCOL,LOCAL,INTEGRITY}
 public static final class Failure extends IOException {
  public final Reason reason;public final int detail;
  public Failure(Reason reason,int detail){super("HTTPS "+reason.name()+" ("+detail+")");this.reason=reason;this.detail=detail;}
 }
 private final File binary,ca;private final Set<String> hosts;private final Network network;private final Processes processes;
 public ModernHttps(File binary,File ca,Collection<String> hosts,Network network){this(binary,ca,hosts,network,new Processes(){public Process start(List<String> args)throws IOException{return new ProcessBuilder(args).start();}});}
 public ModernHttps(File binary,File ca,Collection<String> hosts,Network network,Processes processes){
  if(binary==null||ca==null||network==null||processes==null||hosts==null||hosts.isEmpty())throw new IllegalArgumentException("HTTPS configuration");
  this.binary=binary;this.ca=ca;this.network=network;this.processes=processes;Set<String> safe=new HashSet<String>();
  for(String host:hosts){if(host==null||!host.matches("[a-z0-9]+(?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9]+(?:[a-z0-9-]*[a-z0-9])?)+")||host.length()>253||host.matches("[0-9.]+"))throw new IllegalArgumentException("HTTPS host");safe.add(host);}
  this.hosts=Collections.unmodifiableSet(safe);
 }
 public void checkUrl(URL url)throws Failure {
  if(!"https".equals(url.getProtocol())||url.getUserInfo()!=null||url.getRef()!=null||(url.getPort()!=-1&&url.getPort()!=443)||!hosts.contains(url.getHost()))throw new Failure(Reason.POLICY,0);
  String value=url.toExternalForm();for(int i=0;i<value.length();i++)if(value.charAt(i)<=32||value.charAt(i)==127||value.charAt(i)=='\\')throw new Failure(Reason.POLICY,0);
 }
 public static Failure fromExit(int code){
  Reason reason;switch(code){case 5:case 6:reason=Reason.DNS;break;case 7:reason=Reason.CONNECT;break;case 28:reason=Reason.TIMEOUT;break;case 35:case 59:reason=Reason.TLS;break;case 51:case 60:reason=Reason.CERTIFICATE;break;case 63:reason=Reason.LIMIT;break;case 18:case 52:case 56:reason=Reason.PROTOCOL;break;default:reason=Reason.LOCAL;}
  return new Failure(reason,code);
 }
 private static final class Job {volatile boolean done;volatile Failure failure;}
 private void check(Cancellation stop,long started)throws Failure {
  if(stop.cancelled()||Thread.currentThread().isInterrupted())throw new Failure(Reason.CANCELLED,0);
  if(System.nanoTime()-started>180000000000L)throw new Failure(Reason.TIMEOUT,0);
  try{network.check();}catch(IOException e){throw new Failure(Reason.NETWORK,0);}
 }
 private static void close(Closeable in){try{in.close();}catch(IOException ignored){}}
 private static String line(InputStream in,int[] remaining)throws IOException {
  StringBuilder result=new StringBuilder();for(;;){if(--remaining[0]<0)throw new Failure(Reason.LIMIT,0);int c=in.read();if(c<0)throw new EOFException();if(c=='\n'){if(result.length()>0&&result.charAt(result.length()-1)=='\r')result.setLength(result.length()-1);return result.toString();}if(c==0||result.length()>=8192)throw new Failure(Reason.PROTOCOL,0);result.append((char)c);}
 }
 private static int exited(Process child,boolean eof)throws InterruptedException {
  for(int i=0;i<(eof?25:1);i++){try{return child.exitValue();}catch(IllegalThreadStateException running){if(eof)Thread.sleep(10);}}return -1;
 }
 public <T>T fetch(String address,final int limit,final Cancellation stop,Reader<T> reader)throws Exception {return request(address,limit,stop,reader,false);}
 /** TLS/hostname verification only: HEAD, no redirect traversal, any final HTTP status. */
 public int probe(String address,Cancellation stop)throws Exception {return request(address,1,stop,new Reader<Integer>(){public Integer read(InputStream in){return 0;}},true);}
 private <T>T request(String address,final int limit,final Cancellation stop,Reader<T> reader,boolean head)throws Exception {
  if(limit<1||limit>MAX_DOWNLOAD||stop==null||reader==null)throw new IllegalArgumentException("HTTPS bounds");
  final long started=System.nanoTime();URL next;try{next=new URL(address);}catch(Exception bad){throw new Failure(Reason.POLICY,0);}
  for(int redirect=0;redirect<=4;redirect++){
   check(stop,started);checkUrl(next);
   List<String> args=new ArrayList<String>(Arrays.asList(binary.getPath(),"-q","--silent","--show-error","--proxy","","--noproxy","*","--proto","=https","--proto-redir","=https","--tlsv1.2","--connect-timeout","15","--max-time","180","--speed-time","15","--speed-limit","1","--http1.1","--dump-header","-","--cacert",ca.getPath()));
   if(head)args.addAll(Arrays.asList("--head","--output","/dev/null"));else args.addAll(Arrays.asList("--max-filesize",String.valueOf(limit)));
   args.addAll(network.arguments());args.add("--url");args.add(next.toExternalForm());
   final Process child;try{child=processes.start(args);}catch(IOException failure){throw new Failure(Reason.LOCAL,0);}
   final Job job=new Job();close(child.getOutputStream());
   Thread errors=new Thread(new Runnable(){public void run(){InputStream in=child.getErrorStream();try{byte[] b=new byte[1024];while(in.read(b)!=-1){}}catch(IOException ignored){}finally{close(in);}}},"vn-https-stderr");errors.setDaemon(true);errors.start();
   Thread watcher=new Thread(new Runnable(){public void run(){try{while(!job.done){check(stop,started);Thread.sleep(100);}}catch(InterruptedException done){}catch(Failure failure){job.failure=failure;child.destroy();}}},"vn-https-watch");watcher.setDaemon(true);watcher.start();
   InputStream in=new BufferedInputStream(child.getInputStream());
   try{
    int[] remaining={16384};int status=0;String location=null;long contentLength=-1;
    for(int interim=0;interim<5;interim++){
     check(stop,started);String first=line(in,remaining);if(!first.matches("HTTP/1\\.[01] [1-5][0-9][0-9]( .*)?"))throw new Failure(Reason.PROTOCOL,0);
     status=Integer.parseInt(first.substring(9,12));location=null;contentLength=-1;
     for(int count=0;;count++){
      String header=line(in,remaining);if(header.length()==0)break;if(count>=64)throw new Failure(Reason.LIMIT,0);int colon=header.indexOf(':');if(colon<1)throw new Failure(Reason.PROTOCOL,0);String key=header.substring(0,colon),value=header.substring(colon+1).trim();
      if(key.equalsIgnoreCase("Location")){if(location!=null)throw new Failure(Reason.PROTOCOL,0);location=value;}
      if(key.equalsIgnoreCase("Content-Length")){if(contentLength!=-1||!value.matches("[0-9]{1,10}"))throw new Failure(Reason.PROTOCOL,0);contentLength=Long.parseLong(value);}
     }
     if(status>=200)break;if(interim==4)throw new Failure(Reason.PROTOCOL,0);
    }
    if(head){int exit=child.waitFor();check(stop,started);if(job.failure!=null)throw job.failure;if(exit!=0)throw fromExit(exit);@SuppressWarnings("unchecked") T value=(T)Integer.valueOf(status);return value;}
    if(status==301||status==302||status==303||status==307||status==308){if(location==null)throw new Failure(Reason.PROTOCOL,0);next=new URL(next,location);checkUrl(next);continue;}
    if(status!=200)throw new Failure(Reason.HTTP,status);
    if(contentLength>limit)throw new Failure(Reason.LIMIT,0);
    final long declared=contentLength;final long[] count={0};
    InputStream bounded=new FilterInputStream(in){
     private void observed(int n)throws IOException{check(stop,started);if(n>0){count[0]+=n;if(count[0]>limit)throw new Failure(Reason.LIMIT,0);}else if(n==-1&&declared>=0&&count[0]!=declared)throw new Failure(Reason.PROTOCOL,0);}
     public int read()throws IOException{check(stop,started);int b=in.read();observed(b<0?-1:1);return b;}
     public int read(byte[] b,int off,int len)throws IOException{check(stop,started);int n=in.read(b,off,len);observed(n);return n;}
    };
    T result=reader.read(bounded);
    // Readers cannot return an authenticated result without consuming the full response.
    if(bounded.read()!=-1)throw new Failure(Reason.PROTOCOL,0);
    int exit=child.waitFor();check(stop,started);if(job.failure!=null)throw job.failure;if(exit!=0)throw fromExit(exit);return result;
   }catch(IOException failure){
    check(stop,started);if(job.failure!=null)throw job.failure;
    int exit=exited(child,failure instanceof EOFException);if(exit>0)throw fromExit(exit);
    if(failure instanceof Failure)throw failure;if(failure instanceof EOFException)throw new Failure(Reason.PROTOCOL,0);throw failure;
   }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new Failure(Reason.CANCELLED,0);}
   finally{job.done=true;watcher.interrupt();child.destroy();close(in);close(child.getErrorStream());}
  }
  throw new Failure(Reason.POLICY,0);
 }
 public byte[] bytes(String url,final int limit,Cancellation stop)throws Exception {
  if(limit>1024*1024)throw new IllegalArgumentException("In-memory response limit");
  return fetch(url,limit,stop,new Reader<byte[]>(){public byte[] read(InputStream in)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}});
 }
 /** expected hash/size must come from independently authenticated catalog metadata.
  * Caller supplies a new private staging path; every failure removes partial bytes. */
 public File download(String url,final File target,final int size,final String sha256,Cancellation stop)throws Exception {
  if(size<1||size>MAX_DOWNLOAD||sha256==null||!sha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Download metadata");
  if(!target.createNewFile())throw new Failure(Reason.LOCAL,0);boolean success=false;
  try{
   fetch(url,size,stop,new Reader<Void>(){public Void read(InputStream in)throws Exception{
    MessageDigest hash=MessageDigest.getInstance("SHA-256");FileOutputStream out=new FileOutputStream(target);int total=0;
    try{byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){total+=n;hash.update(b,0,n);out.write(b,0,n);}StringBuilder actual=new StringBuilder();for(byte v:hash.digest())actual.append(String.format(java.util.Locale.US,"%02x",v&255));if(total!=size||!actual.toString().equals(sha256))throw new Failure(Reason.INTEGRITY,0);out.getFD().sync();}finally{out.close();}return null;
   }});success=true;return target;
  }finally{if(!success)target.delete();}
 }
}
