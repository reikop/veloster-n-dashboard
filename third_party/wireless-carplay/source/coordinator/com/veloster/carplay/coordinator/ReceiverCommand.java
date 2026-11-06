package com.veloster.carplay.coordinator;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.*;
import java.util.Arrays;

/** Fixed app_process root helper. No arbitrary command/path/socket arguments.
 * stdin: expected OEM package UID (u32), then the44-byte receiver request.
 * stdout: exactly12 response bytes; generic exit1 on an unavailable/invalid peer.
 */
public final class ReceiverCommand {
    private ReceiverCommand() {}
    public static void main(String[] args) {
        LocalSocket socket=null;byte[] request=new byte[44],reply=new byte[12];
        int code=1;
        try {
            if(args.length!=0 || android.os.Process.myUid()!=0)throw new IOException();
            DataInputStream input=new DataInputStream(System.in);
            int expectedUid=input.readInt();
            if(expectedUid<0)throw new IOException();
            input.readFully(request);if(input.read()!=-1)throw new IOException();
            DataInputStream fields=new DataInputStream(new ByteArrayInputStream(request));
            if(fields.readInt()!=ReceiverWire.MAGIC || fields.readInt()!=1)throw new IOException();
            int operation=fields.readInt();byte[] token=new byte[32];fields.readFully(token);
            if(!Arrays.equals(request,ReceiverWire.request(operation,new String(token,"US-ASCII"))))throw new IOException();
            socket=new LocalSocket();socket.connect(new LocalSocketAddress("vn.carplay.receiver.v1"));
            if(socket.getPeerCredentials().getUid()!=expectedUid)throw new IOException();
            socket.setSoTimeout(6000);socket.getOutputStream().write(request);socket.getOutputStream().flush();
            DataInputStream response=new DataInputStream(socket.getInputStream());response.readFully(reply);
            if(response.read()!=-1)throw new IOException();
            ReceiverWire.reply(reply);System.out.write(reply);System.out.flush();code=0;
        } catch(Exception unavailable) { /* Never print request/remote exception content. */ }
        finally {
            if(socket!=null)try{socket.close();}catch(IOException ignored){}
            Arrays.fill(request,(byte)0);Arrays.fill(reply,(byte)0);
        }
        System.exit(code);
    }
}
