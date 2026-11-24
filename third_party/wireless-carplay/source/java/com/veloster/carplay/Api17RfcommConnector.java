/* SPDX-License-Identifier: GPL-3.0-only. See TRANSPORT-NOTICE.md. */
package com.veloster.carplay;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

/** API17 adapter: requires manifest BLUETOOTH and an existing user-selected bond.
 * Does not use BluetoothManager (API18), discovery or hidden channel-number APIs.
 */
public final class Api17RfcommConnector {
    private Api17RfcommConnector() {}
    public static RfcommConnector create() throws IOException {
        final BluetoothAdapter adapter;
        try {adapter=BluetoothAdapter.getDefaultAdapter();}
        catch(RuntimeException e){throw new IOException("Bluetooth adapter unavailable",e);}
        catch(LinkageError e){throw new IOException("Bluetooth adapter API unavailable",e);}
        if(adapter==null)throw new IOException("Bluetooth adapter unavailable");
        return new RfcommConnector(new RfcommConnector.Backend() {
            public boolean enabled() throws IOException {
                try{return adapter.isEnabled();}catch(RuntimeException e){throw new IOException("Bluetooth state unavailable",e);}
            }
            public boolean bonded(String address) throws IOException {
                try {
                    Set<BluetoothDevice> devices=adapter.getBondedDevices();
                    if(devices==null)return false;
                    for(BluetoothDevice device:devices)if(address.equalsIgnoreCase(device.getAddress()) && device.getBondState()==BluetoothDevice.BOND_BONDED)return true;
                    return false;
                }catch(RuntimeException e){throw new IOException("Bluetooth bonds unavailable",e);}
            }
            public String localAddress() throws IOException {
                try{return adapter.getAddress();}catch(RuntimeException e){throw new IOException("Bluetooth identity unavailable",e);}
            }
            public RfcommConnector.SocketHandle secureSocket(String address,UUID uuid) throws IOException {
                final BluetoothSocket socket;
                try {socket=adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(uuid);}
                catch(RuntimeException e){throw new IOException("Secure RFCOMM unavailable",e);}
                catch(LinkageError e){throw new IOException("Secure RFCOMM API unavailable",e);}
                return new RfcommConnector.SocketHandle() {
                    public void connect() throws IOException {socket.connect();}
                    public InputStream input() throws IOException {return socket.getInputStream();}
                    public OutputStream output() throws IOException {return socket.getOutputStream();}
                    public void close() throws IOException {socket.close();}
                };
            }
        });
    }
}
