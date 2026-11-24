package com.veloster.carplayhub;

import java.io.IOException;

/** Durable automatic-start intent for one Android boot and selected-phone revision.
 * Caller serializes I/O on its worker. A failed write withdraws automatic permission.
 * Android boot identity is not an OEM ignition signal. No radio or UI side effects. */
public final class AutomaticConnectionState {
    public interface Store {String read()throws IOException;boolean write(String record)throws IOException;}
    public static final int LIMIT=3;
    public static final class Snapshot {
        public final String boot,selection;public final boolean stopped,usable;public final int attempts;
        Snapshot(String boot,String selection,boolean stopped,int attempts,boolean usable){this.boot=boot;this.selection=selection;this.stopped=stopped;this.attempts=attempts;this.usable=usable;}
        public boolean allows(String key){return usable&&!stopped&&selection.equals(key)&&attempts<LIMIT;}
    }
    private final Store store;private final String boot;private volatile Snapshot state;
    public AutomaticConnectionState(Store store,String boot)throws IOException{
        if(store==null||boot==null||!boot.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IOException("Android boot identity unavailable");
        this.store=store;this.boot=boot;String raw=store.read();
        if(raw==null){save("-",false,0);return;}
        Snapshot read=decode(raw);state=read;
        if(!boot.equals(read.boot))save("-",false,0);
    }
    public Snapshot snapshot(){return state;}
    public synchronized Snapshot select(String key)throws IOException{
        key=key(key);requireUsable();if(!state.selection.equals(key))save(key,false,0);return state;
    }
    /** Commit a budget reservation before dispatching any automatic start. */
    public synchronized boolean reserve(String key)throws IOException{
        requireUsable();if(!state.allows(key(key)))return false;save(state.selection,false,state.attempts+1);return true;
    }
    public synchronized void stop(String key)throws IOException{
        select(key);save(state.selection,true,state.attempts);
    }
    /** Explicit Connect is a new user intent; process restart is not. */
    public synchronized void resume(String key)throws IOException{select(key);save(state.selection,false,0);}
    /** Only the caller's newly observed selected-phone presence edge may rearm.
     * A presence event never overrides an explicit Stop in this boot. */
    public synchronized boolean presence(String key)throws IOException{
        requireUsable();if(!state.selection.equals(key(key))||state.stopped)return false;save(state.selection,false,0);return true;
    }
    private void requireUsable()throws IOException{if(state==null||!state.usable)throw new IOException("Automatic connection state unavailable");}
    private static String key(String value)throws IOException{
        if(value==null||!value.matches("[A-Za-z0-9:._-]{1,160}"))throw new IOException("Automatic selection identity");return value;
    }
    private void save(String selection,boolean stopped,int attempts)throws IOException{
        state=new Snapshot(boot,selection,stopped,attempts,false);
        String value="1|"+boot+"|"+selection+"|"+(stopped?1:0)+"|"+attempts;
        if(!store.write(value))throw new IOException("Automatic connection intent was not saved");
        state=new Snapshot(boot,selection,stopped,attempts,true);
    }
    private static Snapshot decode(String value)throws IOException{
        String[] v=value.split("\\|",-1);
        if(v.length!=5||!v[0].equals("1")||!v[1].matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")||!v[3].matches("[01]")||!v[4].matches("[0-3]"))throw new IOException("Invalid automatic connection record");
        return new Snapshot(v[1],key(v[2]),v[3].equals("1"),Integer.parseInt(v[4]),true);
    }
}
