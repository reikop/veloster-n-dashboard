/* SPDX-License-Identifier: GPL-3.0-only. Encode measured GPS fields; unknown fields remain empty. */
package com.veloster.carplay.location;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** GGA altitude is mean-sea-level, whereas Android Location altitude is ellipsoidal.
 * Only raw receiver GGA can supply that field here. This fallback emits RMC when
 * all its required measured fields exist; it never invents speed, course or height. */
public final class GpsFixNmea {
    private GpsFixNmea(){}
    private static boolean finite(double value){return !Double.isNaN(value)&&!Double.isInfinite(value);}
    private static String format(String pattern,Object... values){return String.format(Locale.US,pattern,values);}
    private static String coordinate(double value,boolean latitude){
        // Round once in integer minute units, including carry at degree boundaries.
        long scaled=Math.round(Math.abs(value)*60*10000);long degrees=scaled/600000,minutes=scaled%600000;
        return format(latitude?"%02d%02d.%04d":"%03d%02d.%04d",degrees,minutes/10000,minutes%10000);
    }
    private static String frame(String body){int sum=0;for(int i=0;i<body.length();i++)sum^=body.charAt(i);return "$"+body+format("*%02X\r\n",sum);}
    /** Called only for an actual GPS-provider fix. Unknown speed/course suppress RMC. */
    public static String[] encode(long timestamp,double latitude,double longitude,double speed,double bearing){
        if(timestamp<=0||!finite(latitude)||latitude< -90||latitude>90||!finite(longitude)||longitude< -180||longitude>180)return new String[0];
        if(!finite(speed)||speed<0||speed>1000||!finite(bearing)||bearing<0||bearing>=360)return new String[0];
        Calendar utc=Calendar.getInstance(TimeZone.getTimeZone("UTC"),Locale.US);utc.setTimeInMillis(timestamp);
        String time=format("%02d%02d%02d.%02d",utc.get(Calendar.HOUR_OF_DAY),utc.get(Calendar.MINUTE),utc.get(Calendar.SECOND),utc.get(Calendar.MILLISECOND)/10);
        String date=format("%02d%02d%02d",utc.get(Calendar.DAY_OF_MONTH),utc.get(Calendar.MONTH)+1,utc.get(Calendar.YEAR)%100);
        String position=coordinate(latitude,true)+","+(latitude<0?"S":"N")+","+coordinate(longitude,false)+","+(longitude<0?"W":"E");
        String knots=format("%.2f",speed*3600/1852);
        String course=format("%.2f",(Math.round(bearing*100)%36000)/100.0);
        String rmc=frame("GPRMC,"+time+",A,"+position+","+knots+","+course+","+date+",,");
        return new String[]{rmc};
    }
}
