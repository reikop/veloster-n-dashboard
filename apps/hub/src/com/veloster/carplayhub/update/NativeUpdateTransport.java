package com.veloster.carplayhub.update;

import com.veloster.network.ModernHttps;
import java.io.*;
import java.util.*;

/** Updater adapter over the shared API17 market/download HTTPS client.
 * The updater keeps its original three-host policy and 16 MiB response limit. */
public final class NativeUpdateTransport extends UpdateTransport {
 public interface Processes {Process start(List<String> command)throws IOException;}
 public interface Binding {List<String> arguments()throws IOException;void check()throws IOException;}
 private final ModernHttps client;
 public NativeUpdateTransport(File executable,File ca,Policy policy){this(executable,ca,policy,new Processes(){public Process start(List<String> command)throws IOException{return new ProcessBuilder(command).start();}});}
 public NativeUpdateTransport(File executable,File ca,Policy policy,Processes processes){this(executable,ca,policy,processes,null);}
 public NativeUpdateTransport(File executable,File ca,Policy policy,Binding binding){this(executable,ca,policy,new Processes(){public Process start(List<String> command)throws IOException{return new ProcessBuilder(command).start();}},binding);}
 public NativeUpdateTransport(File executable,File ca,final Policy policy,final Processes processes,final Binding binding){
  super(policy);
  client=new ModernHttps(executable,ca,Arrays.asList("raw.githubusercontent.com","github.com","release-assets.githubusercontent.com"),new ModernHttps.Network(){
   public void check()throws IOException{policy.check();if(binding!=null)binding.check();}
   public List<String> arguments()throws IOException{return binding==null?Collections.<String>emptyList():binding.arguments();}
  },new ModernHttps.Processes(){public Process start(List<String> args)throws IOException{return processes.start(args);}});
 }
 public <T>T fetch(String address,final UpdateFiles.Cancellation stop,final Reader<T> reader)throws Exception{
  try{return client.fetch(address,16*1024*1024,new ModernHttps.Cancellation(){public boolean cancelled(){return stop.cancelled();}},new ModernHttps.Reader<T>(){public T read(InputStream in)throws Exception{return reader.read(in);}});}
  catch(ModernHttps.Failure e){if(e.reason==ModernHttps.Reason.HTTP&&e.detail==404)throw new FileNotFoundException("No published update");throw e;}
 }
}
