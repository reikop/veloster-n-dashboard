package com.veloster.carplay.coordinator;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;

/** Bounded private frame from the root AP manager. Never use toString for secrets. */
public final class ApCredentials implements Closeable {
    public final String sessionToken;
    public final int channel;
    private byte[] ssid,passphrase;
    private ApCredentials(String token,int channel,byte[] ssid,byte[] passphrase) {
        this.sessionToken=token;this.channel=channel;this.ssid=ssid;this.passphrase=passphrase;
    }
    public static ApCredentials parse(byte[] frame) throws IOException {
        byte[] name=null,password=null;
        try {
            if(frame==null || frame.length<1 || frame.length>384)throw invalid();
            for(byte b:frame)if(b!=10 && (b<32 || b>126))throw invalid();
            String text=new String(frame,"US-ASCII");String[] lines=text.split("\n",-1);
            if(lines.length!=7 || !lines[0].equals("format=2") || !lines[5].equals("end") || !lines[6].equals(""))throw invalid();
            if(!lines[1].startsWith("session_token=") || !lines[1].substring(14).matches("[0-9a-f]{32}"))throw invalid();
            if(!lines[2].startsWith("ssid_hex=") || !lines[3].startsWith("passphrase_hex=") || !lines[4].startsWith("channel="))throw invalid();
            name=hex(lines[2].substring(9),1,32);password=hex(lines[3].substring(15),8,63);
            // Decoder reports bad UTF-8 instead of silently changing the SSID.
            String decoded=Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(name)).toString();
            for(int i=0;i<decoded.length();i++)if(decoded.charAt(i)<32)throw invalid();
            for(byte b:password)if(b<32 || b>126)throw invalid();
            String chan=lines[4].substring(8);if(!chan.matches("[1-9][0-9]?"))throw invalid();int channel=Integer.parseInt(chan);
            if(!((channel>=1&&channel<=11)||channel==36||channel==40||channel==44||channel==48))throw invalid();
            ApCredentials result=new ApCredentials(lines[1].substring(14),channel,name,password);name=null;password=null;return result;
        } catch(IOException error) { throw invalid(); }
        catch(RuntimeException error) { throw invalid(); }
        finally { if(name!=null)Arrays.fill(name,(byte)0);if(password!=null)Arrays.fill(password,(byte)0); }
    }
    private static byte[] hex(String text,int min,int max)throws IOException {
        if((text.length()&1)!=0 || text.length()<min*2 || text.length()>max*2 || !text.matches("[0-9a-f]+"))throw invalid();
        byte[] bytes=new byte[text.length()/2];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)Integer.parseInt(text.substring(i*2,i*2+2),16);return bytes;
    }
    private static IOException invalid(){return new IOException("AP credential frame rejected");}
    public synchronized byte[] ssidBytes()throws IOException { if(ssid==null)throw invalid();return ssid.clone(); }
    public synchronized byte[] passphraseBytes()throws IOException { if(passphrase==null)throw invalid();return passphrase.clone(); }
    public synchronized void close(){if(ssid!=null)Arrays.fill(ssid,(byte)0);if(passphrase!=null)Arrays.fill(passphrase,(byte)0);ssid=null;passphrase=null;}
    public String toString(){return "ApCredentials[private]";}
}
