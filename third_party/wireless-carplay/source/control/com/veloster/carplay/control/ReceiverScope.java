/* SPDX-License-Identifier: GPL-3.0-only. Receiver generation before peer association. */
package com.veloster.carplay.control;
import com.veloster.carplay.RfcommConnector;
import com.veloster.carplay.pairing.PairVerify;
import java.net.InetAddress;

/** Local endpoint/selected Bluetooth identity. Deliberately has no guessed remote IP. */
public final class ReceiverScope {
    public final String owner,apSession,selectedBluetooth,interfaceName;
    public final long generation;public final InetAddress localAddress;
    public ReceiverScope(String owner,String apSession,long generation,String bluetooth,String iface,InetAddress local){
        if(owner==null||!owner.matches("[0-9a-f]{32}")||apSession==null||!apSession.matches("[0-9a-f]{32}")||owner.equals(apSession)||generation<1
            ||iface==null||iface.length()==0||iface.length()>64||local==null||local.isAnyLocalAddress()||local.isMulticastAddress())
            throw new IllegalArgumentException("Incomplete receiver generation");
        this.owner=owner;this.apSession=apSession;this.generation=generation;selectedBluetooth=RfcommConnector.normalizeAddress(bluetooth);
        interfaceName=iface;localAddress=local;
    }
    public static ReceiverScope from(PairVerify.Context value){if(value==null)throw new NullPointerException();return new ReceiverScope(value.owner,value.apSession,value.generation,value.selectedBluetooth,value.interfaceName,value.localAddress);}
    public PairVerify.Context peer(InetAddress remote){return new PairVerify.Context(owner,apSession,generation,selectedBluetooth,interfaceName,localAddress,remote);}
    public boolean contains(PairVerify.Context value){return value!=null&&generation==value.generation&&owner.equals(value.owner)&&apSession.equals(value.apSession)
        &&selectedBluetooth.equals(value.selectedBluetooth)&&interfaceName.equals(value.interfaceName)&&localAddress.equals(value.localAddress);}
}
