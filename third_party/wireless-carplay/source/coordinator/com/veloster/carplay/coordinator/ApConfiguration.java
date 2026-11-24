/* SPDX-License-Identifier: GPL-3.0-only. Private WPA2 AP configuration. */
package com.veloster.carplay.coordinator;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.Arrays;
public final class ApConfiguration implements Closeable {
    private byte[] ssid,password;public final int channel;
    public ApConfiguration(byte[] ssidUtf8,char[] passphrase,int channel)throws IOException{
        if(ssidUtf8==null||ssidUtf8.length<1||ssidUtf8.length>32||passphrase==null||passphrase.length<8||passphrase.length>63||
            !((channel>=1&&channel<=11)||channel==36||channel==40||channel==44||channel==48))throw invalid();
        String name=Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(ssidUtf8)).toString();
        for(int i=0;i<name.length();i++)if(name.charAt(i)<32||name.charAt(i)==127)throw invalid();
        for(char c:passphrase)if(c<32||c>126)throw invalid();
        ssid=ssidUtf8.clone();password=new byte[passphrase.length];for(int i=0;i<passphrase.length;i++)password[i]=(byte)passphrase[i];this.channel=channel;
    }
    private static IOException invalid(){return new IOException("AP configuration rejected");}
    private static void hex(ByteArrayOutputStream out,byte[] bytes){byte[] digits={'0','1','2','3','4','5','6','7','8','9','a','b','c','d','e','f'};for(byte b:bytes){out.write(digits[(b&255)>>>4]);out.write(digits[b&15]);}}
    synchronized byte[] frame(String token)throws IOException{
        if(ssid==null||password==null||token==null||!token.matches("[0-9a-f]{32}"))throw invalid();
        // Erase the builder's backing store too, not only the returned IPC frame.
        class PrivateBuffer extends ByteArrayOutputStream {void erase(){Arrays.fill(buf,(byte)0);reset();}}
        PrivateBuffer out=new PrivateBuffer();try{
            out.write(("format=3\nsession_token="+token+"\nssid_hex=").getBytes("US-ASCII"));hex(out,ssid);
            out.write("\npassphrase_hex=".getBytes("US-ASCII"));hex(out,password);out.write(("\nchannel="+channel+"\nend\n").getBytes("US-ASCII"));return out.toByteArray();
        }finally{out.erase();}
    }
    public synchronized void close(){if(ssid!=null)Arrays.fill(ssid,(byte)0);if(password!=null)Arrays.fill(password,(byte)0);ssid=password=null;}
    public String toString(){return "ApConfiguration[private]";}
}
