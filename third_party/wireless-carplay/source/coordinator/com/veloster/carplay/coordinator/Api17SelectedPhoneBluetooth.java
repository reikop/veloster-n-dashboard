/* SPDX-License-Identifier: GPL-3.0-only. OEM API17 selected ACL adapter. */
package com.veloster.carplay.coordinator;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** The captured JSN API17 framework supplies IBluetooth.getAclState(device) and
 * removeAcl(device). Removing this one device's ACL also tears down MAP/PBAP,
 * which cannot safely be inferred from three audio/headset profile getters.
 * Unsupported platforms fail closed; no global Bluetooth disable/fallback.
 * Binder calls are synchronous: the deadline bounds acknowledgement polling,
 * not an unresponsive system Binder transaction. */
public final class Api17SelectedPhoneBluetooth {
    private Api17SelectedPhoneBluetooth(){}
    public static SelectedPhoneBluetoothHandoff open(String ap,final String selected,long generation,SelectedPhoneBluetoothHandoff.Current current,long timeoutMillis)throws IOException {
        if(Looper.myLooper()!=null&&Looper.myLooper()==Looper.getMainLooper())throw new IOException("Bluetooth handoff requires worker");
        try {
            final BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();
            if(adapter==null||!adapter.isEnabled())throw new IOException("Bluetooth adapter unavailable");
            final BluetoothDevice device=adapter.getRemoteDevice(selected);
            if(device.getBondState()!=BluetoothDevice.BOND_BONDED)throw new IOException("Selected Bluetooth bond unavailable");
            Class<?> api=Class.forName("android.bluetooth.IBluetooth");
            Method service=BluetoothDevice.class.getDeclaredMethod("getService");service.setAccessible(true);
            final Object remote=invoke(service,null);if(!api.isInstance(remote)||!(remote instanceof IInterface))throw new IOException("OEM Bluetooth interface unavailable");
            final IBinder binder=((IInterface)remote).asBinder();
            if(binder==null||!"android.bluetooth.IBluetooth".equals(binder.getInterfaceDescriptor())||!binder.isBinderAlive())throw new IOException("OEM Bluetooth Binder unavailable");
            final Method state=api.getMethod("getAclState",BluetoothDevice.class),disconnect=api.getMethod("removeAcl",BluetoothDevice.class);
            if(state.getReturnType()!=Boolean.TYPE||disconnect.getReturnType()!=Boolean.TYPE)throw new IOException("Unsupported OEM Bluetooth signature");
            SelectedPhoneBluetoothHandoff.Step link=new SelectedPhoneBluetoothHandoff.Step(){
                private void exact(String phone)throws IOException {
                    if(!selected.equals(phone)||!selected.equals(device.getAddress())||!binder.isBinderAlive()||!adapter.isEnabled()||device.getBondState()!=BluetoothDevice.BOND_BONDED)
                        throw new IOException("Selected Bluetooth scope unavailable");
                }
                public boolean connected(String phone)throws IOException{exact(phone);boolean value=bool(invoke(state,remote,device));exact(phone);return value;}
                public boolean disconnect(String phone)throws IOException{exact(phone);boolean value=bool(invoke(disconnect,remote,device));exact(phone);return value;}
            };
            return new SelectedPhoneBluetoothHandoff(ap,selected,generation,current,new SelectedPhoneBluetoothHandoff.Step[]{link},timeoutMillis);
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException("OEM selected Bluetooth API unavailable",e);}catch(LinkageError e){throw new IOException("OEM selected Bluetooth API unavailable",e);}
    }
    private static boolean bool(Object value)throws IOException{if(!(value instanceof Boolean))throw new IOException("Invalid Bluetooth acknowledgement");return ((Boolean)value).booleanValue();}
    private static Object invoke(Method method,Object receiver,Object... args)throws IOException {
        try{return method.invoke(receiver,args);}
        catch(InvocationTargetException e){throw new IOException("OEM Bluetooth Binder call failed",e.getCause());}
        catch(Exception e){throw new IOException("OEM Bluetooth API call failed",e);}
    }
}
