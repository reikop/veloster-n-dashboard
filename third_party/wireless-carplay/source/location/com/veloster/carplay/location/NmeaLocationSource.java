/* SPDX-License-Identifier: GPL-3.0-only. Independent raw GPS subscription and validation. */
package com.veloster.carplay.location;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** The port supplies receiver sentences or an encoding of measured GPS fix fields.
 * Control methods are serialized; callbacks never hold that monitor. A failed release
 * retains its registration and prevents a successor until cleanup succeeds. */
public final class NmeaLocationSource implements LocationSource {
    public interface Clock {long elapsedMillis();long wallMillis();}
    public interface Listener {void sentence(long timestampMillis,String sentence);}
    public interface Registration extends Closeable {void start()throws IOException;void close()throws IOException;}
    public interface Port {
        boolean available();
        /** Construction has no subscription side effects; start may fail after partial registration. */
        Registration create(Listener listener)throws IOException;
    }
    private static final long MAX_AGE=5000;
    private final Port port;private final Clock clock;private final Object data=new Object();
    private Binding binding;private boolean closed;
    private final class Binding implements Listener {
        final int mask;Registration registration;boolean active;
        final String[] pending=new String[2];final long[] received=new long[2],lastTimestamp=new long[2];
        Binding(int mask){this.mask=mask;}
        public void sentence(long timestamp,String value){
            int type=type(value);if(type==0||(mask&type)==0)return;
            long wall=clock.wallMillis();if(timestamp<=0||wall<timestamp||wall-timestamp>MAX_AGE)return;
            synchronized(data){if(binding!=this||!active)return;int i=type==GGA?0:1;
                if(timestamp<=lastTimestamp[i])return;lastTimestamp[i]=timestamp;
                pending[i]=normalize(value);received[i]=clock.elapsedMillis()-(wall-timestamp);}
        }
        void clear(){for(int i=0;i<2;i++){pending[i]=null;received[i]=0;}}
    }
    public NmeaLocationSource(Port port,Clock clock){if(port==null||clock==null)throw new NullPointerException();this.port=port;this.clock=clock;}
    public synchronized boolean available(){return !closed&&port.available();}
    public synchronized void start(int mask)throws IOException{
        if(closed)throw new IOException("Location source retired");
        if(mask<0||(mask&~3)!=0)throw new IOException("Unsupported location request");
        stop();if(mask==0)return;if(!port.available())throw new IOException("GPS provider unavailable");
        final Binding next=new Binding(mask);next.registration=port.create(next);
        if(next.registration==null)throw new IOException("GPS registration unavailable");
        synchronized(data){binding=next;}
        // Keep ownership even on a partial start failure, for stop/close retry.
        next.registration.start();
        synchronized(data){next.active=true;}
    }
    public synchronized List<String> poll()throws IOException{
        if(closed)throw new IOException("Location source retired");
        if(!port.available()){stop();return new ArrayList<String>();}
        ArrayList<String> result=new ArrayList<String>(2);
        synchronized(data){Binding current=binding;if(current==null||!current.active)return result;
            long now=clock.elapsedMillis();
            for(int i=0;i<2;i++){String value=current.pending[i];long age=now-current.received[i];
                long wallAge=clock.wallMillis()-current.lastTimestamp[i];
                if(value!=null&&age>=0&&age<=MAX_AGE&&wallAge>=0&&wallAge<=MAX_AGE)result.add(value);current.pending[i]=null;}}
        return result;
    }
    public synchronized void stop()throws IOException{
        Binding old;synchronized(data){old=binding;if(old==null)return;old.active=false;old.clear();}
        old.registration.close();synchronized(data){if(binding==old)binding=null;}
    }
    public synchronized void close()throws IOException{closed=true;stop();}
    private static String normalize(String value){return value.endsWith("\r\n")?value:value+"\r\n";}
    /** Check framing and the required measured fields of receiver-reported valid fixes.
     * No-fix GGA quality 0 and RMC status V keep their original empty fields. */
    public static int type(String raw){
        if(raw==null)return 0;int size=raw.length();if(raw.endsWith("\r\n"))size-=2;
        if(size<10||size+2>82||raw.charAt(0)!='$'||raw.charAt(size-3)!='*')return 0;
        int result=raw.startsWith("$GPGGA,")?GGA:raw.startsWith("$GPRMC,")?RMC:0;if(result==0)return 0;
        int checksum=0,commas=0;
        for(int i=1;i<size-3;i++){char c=raw.charAt(i);if(c<32||c>126||c=='*'||c=='$')return 0;checksum^=c;if(c==',')commas++;}
        int hi=Character.digit(raw.charAt(size-2),16),lo=Character.digit(raw.charAt(size-1),16);
        if(hi<0||lo<0||checksum!=((hi<<4)|lo)||commas<(result==GGA?14:11))return 0;
        // Character.digit accepts non-ASCII numerals; the checksum itself is wire ASCII too.
        if(raw.charAt(size-2)>127||raw.charAt(size-1)>127)return 0;
        String[] fields=raw.substring(1,size-3).split(",",-1);
        if(result==GGA){
            // A valid GGA height is MSL in metres, not Android's ellipsoid altitude.
            double quality=decimal(fields[6],false);
            if(Double.isNaN(quality))return 0;
            if(quality>0&&(Double.isNaN(decimal(fields[9],true))||!"M".equals(fields[10])))return 0;
        }else if("A".equals(fields[2])){
            double speed=decimal(fields[7],false),course=decimal(fields[8],false);
            if(Double.isNaN(speed)||Double.isNaN(course)||course>=360)return 0;
        }
        return result;
    }
    /** Wire decimal only: no NaN, infinity, hexadecimal or exponent notation. */
    private static double decimal(String value,boolean signed){
        if(value.length()==0)return Double.NaN;int start=signed&&value.charAt(0)=='-'?1:0,digits=0;boolean point=false;
        for(int i=start;i<value.length();i++){char c=value.charAt(i);
            if(c>='0'&&c<='9')digits++;else if(c=='.'&&!point)point=true;else return Double.NaN;}
        if(digits==0)return Double.NaN;
        try{double number=Double.parseDouble(value);return Double.isInfinite(number)?Double.NaN:number;}
        catch(NumberFormatException e){return Double.NaN;}
    }
}
