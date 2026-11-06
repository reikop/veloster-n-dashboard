package com.veloster.carplay.coordinator;

import java.io.*;
import java.util.Arrays;

/** Caller obtains the expected UID from the identity-verified OEM package.
 * The installed JAR and all ancestor directories must be root-owned, not writable
 * by applications. Installation/identity verification is a separate prerequisite.
 */
public final class RootReceiverClient implements ReceiverControl {
    private static final String ROOT_COMMAND="CLASSPATH=/data/local/tmp/vn-wireless-carplay/wireless-core.jar /system/bin/app_process /system/bin com.veloster.carplay.coordinator.ReceiverCommand";
    private final int expectedUid;
    public RootReceiverClient(int expectedUid){if(expectedUid<0)throw new IllegalArgumentException();this.expectedUid=expectedUid;}
    public Reply request(int operation,String token)throws IOException {
        byte[] wire=ReceiverWire.request(operation,token),response=null;
        ByteArrayOutputStream buffer=new ByteArrayOutputStream(48);
        DataOutputStream out=new DataOutputStream(buffer);out.writeInt(expectedUid);out.write(wire);
        byte[] request=buffer.toByteArray();
        try {
            response=ApManagerClient.readPrivate(new String[]{"/system/bin/amossu","-c",ROOT_COMMAND},8000,request);
            return ReceiverWire.reply(response);
        } catch(IOException unavailable){throw new IOException("OEM receiver request unavailable; outcome may be unknown");}
        finally{Arrays.fill(wire,(byte)0);Arrays.fill(request,(byte)0);if(response!=null)Arrays.fill(response,(byte)0);}
    }
}
