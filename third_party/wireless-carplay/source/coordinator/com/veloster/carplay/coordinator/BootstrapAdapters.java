/* SPDX-License-Identifier: GPL-3.0-only. Linked with the isolated iAP2 module. */
package com.veloster.carplay.coordinator;

import android.os.IBinder;
import com.veloster.carplay.Iap2WirelessController;
import com.veloster.carplay.auth.OemAuthClient;
import java.io.IOException;
import java.util.Arrays;

/** Concrete AP/auth adapters; receiver/phone observations are deliberately abstract. */
public final class BootstrapAdapters {
    private BootstrapAdapters() {}
    public static Iap2WirelessController.Authentication authentication(IBinder service)throws IOException {
        final OemAuthClient client;
        try{client=new OemAuthClient(service);}catch(OemAuthClient.AuthException e){throw new IOException("OEM authentication service unavailable");}
        return new Iap2WirelessController.Authentication() {
            public byte[] certificate()throws IOException {
                try{return client.copyCertificate();}catch(OemAuthClient.AuthException e){throw new IOException("OEM certificate unavailable");}
            }
            public byte[] sign(byte[] challenge)throws IOException {
                try{return client.createSignature(challenge);}catch(OemAuthClient.AuthException e){throw new IOException("OEM signature unavailable");}
            }
        };
    }
    public abstract static class Environment implements Iap2WirelessController.Environment {
        private final ApManagerClient manager;
        protected Environment(ApManagerClient manager) {if(manager==null)throw new NullPointerException();this.manager=manager;}
        public final Iap2WirelessController.ApSnapshot accessPoint()throws IOException {
            ApCredentials credentials=manager.credentials();byte[] name=null,secret=null;char[] pass=null;
            try {
                name=credentials.ssidBytes();secret=credentials.passphraseBytes();pass=new char[secret.length];
                for(int i=0;i<secret.length;i++)pass[i]=(char)(secret[i]&255);
                // Same fixed address enforced by the native AP manager. BSSID is
                // omitted until observed; do not substitute the Bluetooth address.
                return new Iap2WirelessController.ApSnapshot(credentials.sessionToken,new String(name,"UTF-8"),pass,credentials.channel,"172.30.88.1",null);
            } finally {
                credentials.close();if(name!=null)Arrays.fill(name,(byte)0);if(secret!=null)Arrays.fill(secret,(byte)0);if(pass!=null)Arrays.fill(pass,'\0');
            }
        }
        public final boolean current(String session)throws IOException {return manager.current(session);}
        // Concrete subclass must supply real receiver identity, selected-phone
        // Wi-Fi/iAP2/native handoff observations and per-device Bluetooth teardown.
    }
}
