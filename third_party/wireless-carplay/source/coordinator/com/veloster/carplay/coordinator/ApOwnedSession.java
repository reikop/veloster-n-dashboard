/* SPDX-License-Identifier: GPL-3.0-only. Exact-token AP lifecycle lease. */
package com.veloster.carplay.coordinator;
import java.io.*;
import java.net.InetAddress;
import java.util.Arrays;
public final class ApOwnedSession implements Closeable {
    public enum State { NEW, STARTING, READY, CLEANUP_REQUIRED, CLOSED }
    private final ApManagerClient manager;private final String token;private byte[] configuration;
    private State state=State.NEW;private boolean cancelled,starting,closing,attempted;private Thread starter;private int index;
    ApOwnedSession(ApManagerClient manager,ApConfiguration configuration,String token)throws IOException{
        if(manager==null||configuration==null)throw new NullPointerException();
        if(token==null||!token.matches("[0-9a-f]{32}"))throw new IOException("AP session token rejected");
        this.manager=manager;this.token=token;this.configuration=configuration.frame(token);
    }
    public String sessionToken(){return token;}
    public synchronized State state(){return state;}
    public synchronized int interfaceIndex()throws IOException{ready();return index;}
    public synchronized InetAddress localAddress()throws IOException{ready();return InetAddress.getByAddress(new byte[]{(byte)172,30,88,1});}
    private void ready()throws IOException{if(state!=State.READY||cancelled)throw new IOException("Owned AP is not ready");}
    private synchronized void check()throws IOException{if(cancelled)throw new IOException("AP start cancelled");}
    /** Worker only. Caller retains this lease even if start throws. */
    public void start()throws IOException{
        synchronized(this){if(state!=State.NEW||cancelled)throw new IOException("AP start unavailable");state=State.STARTING;starting=true;starter=Thread.currentThread();attempted=true;}
        boolean success=false;
        try{
            check();manager.prepareOwned(configuration);check();manager.ownedCommand("start-owned",token,"ready",100000);check();
            ApCredentials credentials=manager.credentials();try{if(!token.equals(credentials.sessionToken))throw new IOException("AP generation changed");}finally{credentials.close();}
            ApStationSnapshot observation=manager.station();if(!token.equals(observation.sessionToken))throw new IOException("AP generation changed");
            synchronized(this){check();index=observation.interfaceIndex;state=State.READY;success=true;}
        }catch(RuntimeException failure){throw new IOException("AP startup failed",failure);}
         catch(LinkageError failure){throw new IOException("AP startup unavailable",failure);}
        finally{
            synchronized(this){starting=false;starter=null;if(configuration!=null)Arrays.fill(configuration,(byte)0);configuration=null;if(!success)state=State.CLEANUP_REQUIRED;}
            if(!success){boolean interrupted=Thread.interrupted();try{close();}catch(IOException pending){/* retain CLEANUP_REQUIRED for explicit retry */}finally{if(interrupted)Thread.currentThread().interrupt();}}
        }
    }
    /** Nonblocking: interrupt only the thread currently executing this lease's start. */
    public void cancel(){Thread thread;synchronized(this){cancelled=true;thread=starter;}if(thread!=null)thread.interrupt();}
    public ApCredentials credentials()throws IOException{
        synchronized(this){ready();}ApCredentials result=manager.credentials();
        synchronized(this){if(cancelled||state!=State.READY||!token.equals(result.sessionToken)){result.close();throw new IOException("AP generation changed");}}
        return result;
    }
    /** Worker only. No global stop. A failed receipt or in-flight startup remains owned. */
    public void close()throws IOException{
        cancel();
        synchronized(this){
            if(state==State.CLOSED)return;
            if(starting||closing)throw new IOException("AP cleanup still pending");
            if(!attempted){if(configuration!=null)Arrays.fill(configuration,(byte)0);configuration=null;state=State.CLOSED;return;}
            closing=true;state=State.CLEANUP_REQUIRED;
        }
        boolean clean=false;
        try{manager.ownedCommand("stop-owned",token,"released",60000);clean=true;}
        finally{synchronized(this){closing=false;if(clean){state=State.CLOSED;index=0;}else state=State.CLEANUP_REQUIRED;}}
    }
    public String toString(){return "ApOwnedSession[private]";}
}
