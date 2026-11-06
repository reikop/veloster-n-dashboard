package com.veloster.carplayhub.wifi;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** WPA2 credentials are passed only through the manager's standard input. */
public final class UsbWifiCredentials {
 private UsbWifiCredentials(){}
 private static byte[] utf8(String ssid)throws GeneralSecurityException{
  try{ByteBuffer buffer=Charset.forName("UTF-8").newEncoder().encode(CharBuffer.wrap(ssid));byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);return bytes;}
  catch(Exception bad){throw new GeneralSecurityException("Invalid SSID");}
 }
 private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte value:bytes){int n=value&255;out.append("0123456789abcdef".charAt(n>>>4)).append("0123456789abcdef".charAt(n&15));}return out.toString();}
 public static byte[] encode(String ssid,char[] password)throws GeneralSecurityException{
  if(ssid==null||password==null)throw new GeneralSecurityException("Missing credentials");
  byte[] name=utf8(ssid),key=null;PBEKeySpec spec=null;
  try{
   if(name.length<1||name.length>32)throw new GeneralSecurityException("SSID length");
   if(password.length==64){
    key=new byte[32];for(int i=0;i<64;i++){int n=Character.digit(password[i],16);if(password[i]>127||n<0)throw new GeneralSecurityException("Invalid PSK");key[i/2]|=(byte)(n<<(i%2==0?4:0));}
   }else{
    if(password.length<8||password.length>63)throw new GeneralSecurityException("Password length");
    for(char c:password)if(c<32||c>126)throw new GeneralSecurityException("Password characters");
    spec=new PBEKeySpec(password,name,4096,256);key=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
   }
   try{return ("ssid_hex="+hex(name)+"\npsk_hex="+hex(key)+"\n").getBytes("US-ASCII");}catch(java.io.UnsupportedEncodingException impossible){throw new GeneralSecurityException(impossible);}
  }finally{Arrays.fill(name,(byte)0);if(key!=null)Arrays.fill(key,(byte)0);if(spec!=null)spec.clearPassword();}
 }
}
