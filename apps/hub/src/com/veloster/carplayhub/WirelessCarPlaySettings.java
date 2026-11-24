package com.veloster.carplayhub;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import java.io.IOException;
import java.util.*;

/** Private selection/opt-in only. Loading, bond queries and commits belong to workers.
 * A changed selection withdraws the old generation BEFORE I/O. Consumers must check
 * current(), not rely solely on listener delivery. This does not start a receiver. */
public final class WirelessCarPlaySettings {
    public static final class Snapshot {
        public final String storeId,phone;public final long generation;public final boolean autoConnect,usable;
        private Snapshot(String id,long generation,String phone,boolean automatic,boolean usable){storeId=id;this.generation=generation;this.phone=phone;autoConnect=automatic;this.usable=usable;}
    }
    public static final class Device {public final String address,name;public Device(String address,String name){this.address=address(address);this.name=name==null?"":name;}}
    public interface Listener {/** Worker notification; enqueue cancellation and re-read latest snapshot. */void changed(Snapshot before,Snapshot after);}
    interface Store {String read()throws IOException;boolean write(String value)throws IOException;}
    interface Bonds {boolean enabled()throws IOException;List<Device> devices()throws IOException;}
    interface ThreadCheck {void worker();}
    private static WirelessCarPlaySettings instance;
    private final Store store;private final Bonds bonds;private final ThreadCheck threads;private final Object operations=new Object();
    private final List<Listener> listeners=new ArrayList<Listener>();private volatile Snapshot state;private boolean mutating;
    private WirelessCarPlaySettings(Store store,Bonds bonds,ThreadCheck threads)throws IOException{
        this.store=store;this.bonds=bonds;this.threads=threads;threads.worker();String raw=store.read();
        if(raw==null){Snapshot fresh=new Snapshot(UUID.randomUUID().toString().replace("-",""),1,null,false,true);if(!store.write(encode(fresh)))throw new IOException("Wireless settings initialization failed");state=fresh;}
        else state=decode(raw);
    }
    static WirelessCarPlaySettings fixture(Store store,Bonds bonds,ThreadCheck check)throws IOException{return new WirelessCarPlaySettings(store,bonds,check);}
    private static void worker(){if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Wireless settings require a worker");}
    /** Call once on a worker and retain in the Application/service. Same-process singleton. */
    public static synchronized WirelessCarPlaySettings open(Context supplied)throws IOException{
        worker();if(instance!=null)return instance;Context context=supplied.getApplicationContext();if(context==null)context=supplied;
        final SharedPreferences preferences=context.getSharedPreferences("wireless_carplay_settings",Context.MODE_PRIVATE);
        instance=new WirelessCarPlaySettings(new Store(){public String read()throws IOException{try{return preferences.getString("selection_v1",null);}catch(RuntimeException error){throw new IOException("Wireless settings invalid",error);}}
            public boolean write(String value)throws IOException{try{return preferences.edit().putString("selection_v1",value).commit();}catch(RuntimeException error){throw new IOException("Wireless settings unavailable",error);}}},new Bonds(){
                private BluetoothAdapter adapter()throws IOException{try{return BluetoothAdapter.getDefaultAdapter();}catch(RuntimeException error){throw new IOException("Bluetooth unavailable",error);}}
                public boolean enabled()throws IOException{try{BluetoothAdapter a=adapter();return a!=null&&a.isEnabled();}catch(RuntimeException error){throw new IOException("Bluetooth unavailable",error);}}
                public List<Device> devices()throws IOException{try{BluetoothAdapter a=adapter();if(a==null||!a.isEnabled())throw new IOException("Bluetooth disabled");Set<BluetoothDevice> bonded=a.getBondedDevices();if(bonded==null)throw new IOException("Bluetooth bonds unavailable");List<Device> result=new ArrayList<Device>();for(BluetoothDevice d:bonded)if(d.getBondState()==BluetoothDevice.BOND_BONDED)result.add(new Device(d.getAddress(),d.getName()));return result;}catch(RuntimeException error){throw new IOException("Bluetooth bonds unavailable",error);}}
            },new ThreadCheck(){public void worker(){WirelessCarPlaySettings.worker();}});return instance;
    }
    public Snapshot snapshot(){return state;}
    public synchronized Snapshot addListener(Listener listener){if(listener==null)throw new NullPointerException();if(!listeners.contains(listener))listeners.add(listener);return state;}
    public synchronized void removeListener(Listener listener){listeners.remove(listener);}
    public boolean current(String storeId,long generation,String phone){Snapshot s=state;return s.usable&&s.storeId.equals(storeId)&&s.generation==generation&&same(s.phone,phone);}
    private void publish(Snapshot next){Snapshot previous;List<Listener> notify;synchronized(this){previous=state;state=next;notify=new ArrayList<Listener>(listeners);}for(Listener listener:notify)try{listener.changed(previous,next);}catch(RuntimeException ignored){}catch(LinkageError ignored){}}
    public boolean bluetoothEnabled()throws IOException{threads.worker();return bonds.enabled();}
    public List<Device> bondedDevices()throws IOException{threads.worker();if(!bonds.enabled())return Collections.emptyList();List<Device> result=new ArrayList<Device>(bonds.devices());Collections.sort(result,new Comparator<Device>(){public int compare(Device a,Device b){int n=a.name.compareToIgnoreCase(b.name);return n==0?a.address.compareTo(b.address):n;}});return Collections.unmodifiableList(result);}
    private boolean bonded(String phone)throws IOException{if(!bonds.enabled())return false;for(Device d:bonds.devices())if(d.address.equals(phone))return true;return false;}
    public Snapshot select(long expected,String phone)throws IOException{return change(expected,address(phone),false,true);}
    public Snapshot setAutoConnect(long expected,boolean enabled)throws IOException{Snapshot s=state;if(s.phone==null&&enabled)throw new IOException("Select a bonded phone first");return change(expected,s.phone,enabled,enabled);}
    public Snapshot clear(long expected)throws IOException{return change(expected,null,false,false);}
    /** Confirmed unbond clears selection. Radio off preserves preferences; service must revoke its radio lease. */
    public Snapshot refreshBond()throws IOException{threads.worker();synchronized(operations){Snapshot s=state;if(!s.usable||s.phone==null||!bonds.enabled())return s;if(!bonded(s.phone))return change(s.generation,null,false,false);return s;}}
    private Snapshot change(long expected,String phone,boolean automatic,boolean verifyBond)throws IOException{
        threads.worker();synchronized(operations){if(mutating)throw new IOException("Reentrant wireless settings change");mutating=true;try{Snapshot before=state;if(before.generation!=expected)throw new IOException("Wireless selection changed");
            if(before.usable&&same(before.phone,phone)&&before.autoConnect==automatic&&!verifyBond)return before;
            if(before.generation==Long.MAX_VALUE)throw new IOException("Wireless selection generation exhausted");
            Snapshot withdrawn=new Snapshot(before.storeId,before.generation+1,phone,false,false);publish(withdrawn);
            try{if(verifyBond&&(phone==null||!bonded(phone)))throw new IOException("Selected phone is not bonded");
                Snapshot saved=new Snapshot(before.storeId,withdrawn.generation,phone,automatic,true);if(!store.write(encode(saved)))throw new IOException("Wireless settings were not saved");publish(saved);return saved;
            }catch(RuntimeException error){throw new IOException("Wireless settings unavailable",error);}
        }finally{mutating=false;}}
    }
    private static boolean same(String a,String b){return a==null?b==null:a.equals(b);}
    private static String address(String value){if(value==null||!value.matches("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}"))throw new IllegalArgumentException("Bluetooth address");return value.toUpperCase(Locale.US);}
    private static String encode(Snapshot s){return "1|"+s.storeId+"|"+s.generation+"|"+(s.phone==null?"-":s.phone)+"|"+(s.autoConnect?"1":"0");}
    private static Snapshot decode(String raw)throws IOException{try{String[] v=raw.split("\\|",-1);if(v.length!=5||!v[0].equals("1")||!v[1].matches("[0-9a-f]{32}")||!v[2].matches("[1-9][0-9]{0,18}")||!v[4].matches("[01]"))throw new IllegalArgumentException();long generation=Long.parseLong(v[2]);String phone=v[3].equals("-")?null:address(v[3]);boolean automatic=v[4].equals("1");if(phone==null&&automatic)throw new IllegalArgumentException();return new Snapshot(v[1],generation,phone,automatic,true);}catch(RuntimeException bad){throw new IOException("Wireless settings invalid",bad);}}
}
