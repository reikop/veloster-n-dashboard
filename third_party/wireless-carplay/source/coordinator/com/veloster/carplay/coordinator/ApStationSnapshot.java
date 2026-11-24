package com.veloster.carplay.coordinator;
import java.io.IOException;

/** One bounded root AP observation. A station/lease is NOT selected-phone proof.
 * sampledBoottimeMillis uses Linux CLOCK_BOOTTIME (Android elapsedRealtime), not
 * System.nanoTime. connectedSeconds is not an association token. */
public final class ApStationSnapshot {
    public final String sessionToken,stationMac,ipv4,associationToken;
    public final int interfaceIndex;
    public final long sampledBoottimeMillis,connectedSeconds;
    private ApStationSnapshot(String token,int index,long sampled,String mac,String ip,long connected,String association){
        sessionToken=token;interfaceIndex=index;sampledBoottimeMillis=sampled;stationMac=mac;ipv4=ip;connectedSeconds=connected;associationToken=association;
    }
    public static ApStationSnapshot parse(byte[] frame)throws IOException {
        try{
            if(frame==null||frame.length==0||frame.length>384)throw invalid();
            for(byte b:frame)if(b!=10&&(b<32||b>126))throw invalid();
            String[] lines=new String(frame,"US-ASCII").split("\n",-1);
            boolean watched=lines[0].equals("format=2");int end=watched?8:7;
            if(lines.length!=end+2||(!watched&&!lines[0].equals("format=1"))||!lines[end].equals("end")||!lines[end+1].equals(""))throw invalid();
            String token=field(lines[1],"session_token="),mac=field(lines[4],"station_mac="),ip=field(lines[5],"ipv4=");
            if(!token.matches("[0-9a-f]{32}"))throw invalid();
            long index=number(field(lines[2],"interface_index=")),sampled=number(field(lines[3],"sampled_boottime_ms=")),seconds=number(field(lines[6],"connected_seconds="));
            if(index<1||index>Integer.MAX_VALUE||sampled<1||seconds>0xffffffffL)throw invalid();
            if(mac.equals("-")){
                if(!ip.equals("-")||seconds!=0)throw invalid();mac=null;
            }else if(!mac.matches("[0-9a-f]{2}(:[0-9a-f]{2}){5}")||mac.equals("00:00:00:00:00:00")||(Integer.parseInt(mac.substring(0,2),16)&1)!=0)throw invalid();
            if(ip.equals("-"))ip=null;
            else if(!ip.matches("172\\.30\\.88\\.(1[0-9]|20)"))throw invalid();
            String association=watched?field(lines[7],"association_token="):null;
            if(watched){
                if(mac==null){if(!association.equals("-"))throw invalid();association=null;}
                else if(!association.matches("[0-9a-f]{32}")||association.substring(16).equals("0000000000000000"))throw invalid();
            }
            return new ApStationSnapshot(token,(int)index,sampled,mac,ip,seconds,association);
        }catch(RuntimeException failure){throw invalid();}
    }
    private static String field(String text,String prefix)throws IOException{if(!text.startsWith(prefix))throw invalid();return text.substring(prefix.length());}
    private static long number(String text)throws IOException{if(!text.matches("0|[1-9][0-9]{0,18}"))throw invalid();return Long.parseLong(text);}
    private static IOException invalid(){return new IOException("AP station observation rejected");}
    public String toString(){return "ApStationSnapshot[private]";}
}
