/* SPDX-License-Identifier: GPL-3.0-only. Optional packaged engine; the existing AP/bootstrap owns admission. */
package com.veloster.carplay.coordinator;
import android.content.Context;
import com.veloster.carplay.*;
import com.veloster.carplay.control.*;
import com.veloster.carplay.discovery.AndroidReceiverSession;
import com.veloster.carplay.pairing.PairingStore;
import java.io.IOException;

public interface ReceiverEngineProvider {
    AudioCapabilities audioCapabilities()throws IOException;
    IndependentReceiverControl.Engine open(Attempt attempt)throws IOException,InterruptedException;
    /** Borrowed objects remain owned by this exact backend attempt. */
    abstract class Attempt {
        public final Context context;public final ReceiverScope scope;public final PairingStore pairings;
        public final ReceiverAdmission admission;public final ReceiverMetadata metadata;
        public final ReceiverMetadata.Configuration configuration;public final AndroidReceiverSession presentation;
        protected Attempt(Context context,ReceiverScope scope,PairingStore pairings,ReceiverAdmission admission,
                ReceiverMetadata metadata,ReceiverMetadata.Configuration configuration,AndroidReceiverSession presentation){
            this.context=context;this.scope=scope;this.pairings=pairings;this.admission=admission;
            this.metadata=metadata;this.configuration=configuration;this.presentation=presentation;
        }
        public abstract void checkCurrent()throws IOException;
        /** Worker-only; caller owns the returned auth/location resources. */
        public abstract RuntimeControlHost.Resources openRuntime()throws IOException,InterruptedException;
    }
}
