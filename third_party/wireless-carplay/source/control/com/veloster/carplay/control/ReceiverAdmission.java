/* SPDX-License-Identifier: GPL-3.0-only. Separate publication and selected peer leases. */
package com.veloster.carplay.control;
import com.veloster.carplay.pairing.PairVerify;
import java.io.IOException;
import java.net.InetAddress;

/** All callbacks must be nonblocking cached observations. Advertising may precede
 * association. Selected admission requires actual phone evidence. Provisional
 * admission must supply PhoneIdentityLease to hold media until received identity
 * matches; it must not label a DHCP candidate as the selected phone. */
public interface ReceiverAdmission {
    ReceiverScope scope();
    /** Check AP/owner/selected-phone generation, independent of whether the phone joined. */
    void checkCurrent()throws IOException;
    /** null means not admitted. IOException rejects this connection, not the publisher. */
    Peer admit(InetAddress remote)throws IOException;
    final class Peer {
        public final PairVerify.Context context;public final PairVerify.OwnerLease lease;
        public Peer(PairVerify.Context context,PairVerify.OwnerLease lease){if(context==null||lease==null)throw new NullPointerException();this.context=context;this.lease=lease;}
    }
    /** Compatibility with an already observed, fixed peer. Retains context identity. */
    final class Fixed implements ReceiverAdmission {
        private final ReceiverScope scope;private final Peer peer;
        public Fixed(PairVerify.Context context,PairVerify.OwnerLease owner){scope=ReceiverScope.from(context);peer=new Peer(context,owner);}
        public ReceiverScope scope(){return scope;}
        public void checkCurrent()throws IOException{peer.lease.checkCurrent(peer.context);}
        public Peer admit(InetAddress remote)throws IOException{checkCurrent();return peer.context.remoteAddress.equals(remote)?peer:null;}
    }
}
