/* SPDX-License-Identifier: GPL-3.0-only. Dedicated wheel-sensor PASCD encoding. */
package com.veloster.carplay.location;

import java.util.List;
import java.util.Locale;

/** Wire profile cross-checked against pinned mhi2-carplay-cluster vehicle_hook
 * examples. These are dedicated left/right wheel samples, never GPS speed or
 * a refreshed timestamp on a cached OEM getter. Unknown slip is not encodable. */
public final class VehicleSpeedNmea {
    public static final int TYPE=4,MAX_SAMPLES=50,MAX_BYTES=2047;
    public static final class Sample {
        public final long elapsedMillis;
        public final int speedMillimetresPerSecond,slipDetect;
        public final char transmission;
        public Sample(long at,int speed,char gear,int slip){
            if(at<0||speed<0||speed>200000||"PRDNU".indexOf(gear)<0||(slip!=0&&slip!=1))throw new IllegalArgumentException("Unknown/invalid wheel sample");
            elapsedMillis=at;speedMillimetresPerSecond=speed;transmission=gear;slipDetect=slip;
        }
    }
    private VehicleSpeedNmea(){}
    private static String decimal(long scaled,int digits){long unit=digits==3?1000:100;return String.format(Locale.US,digits==3?"%d.%03d":"%d.%02d",scaled/unit,scaled%unit);}
    public static String encode(List<Sample> samples){
        if(samples==null||samples.isEmpty()||samples.size()>MAX_SAMPLES)throw new IllegalArgumentException("Wheel sample count");
        Sample first=samples.get(0);if(first==null)throw new IllegalArgumentException("Missing wheel sample");
        Sample last=samples.get(samples.size()-1);if(last==null)throw new IllegalArgumentException("Missing wheel sample");
        long previous=-1,previousOffset=-1;char gear=last.transmission;int slip=0;
        StringBuilder pairs=new StringBuilder();
        for(Sample sample:samples){
            if(sample==null||sample.elapsedMillis<first.elapsedMillis||sample.elapsedMillis<=previous)throw new IllegalArgumentException("Wheel sample order");
            long dt=sample.elapsedMillis-first.elapsedMillis;
            if(dt>2000)throw new IllegalArgumentException("Wheel sample gap");
            long offset=(dt+5)/10;
            if(offset<=previousOffset)throw new IllegalArgumentException("Indistinguishable wheel sample timestamps");
            previous=sample.elapsedMillis;previousOffset=offset;
            if(sample.transmission=='R')gear='R';slip|=sample.slipDetect;
            pairs.append(',').append(decimal(offset,2)).append(',').append(decimal(sample.speedMillimetresPerSecond,3));
        }
        String body="PASCD,"+decimal(first.elapsedMillis%86400000,3)+",C,"+gear+","+slip+","+samples.size()+pairs;
        int checksum=0;for(int i=0;i<body.length();i++)checksum^=body.charAt(i);
        String sentence="$"+body+String.format(Locale.US,"*%02X\r\n",checksum);
        if(sentence.length()>MAX_BYTES)throw new IllegalArgumentException("Wheel sentence length");
        return sentence;
    }
    /** Strictly validate the supported PASCD profile before any iAP2 transport. */
    public static int type(String sentence){
        if(sentence==null||!sentence.startsWith("$PASCD,")||!sentence.endsWith("\r\n")||sentence.length()>MAX_BYTES)return 0;
        int star=sentence.length()-5;if(star<0||sentence.charAt(star)!='*')return 0;int checksum=0;
        for(int i=1;i<star;i++){char c=sentence.charAt(i);if(c<32||c>126||c=='$'||c=='*')return 0;checksum^=c;}
        if(!String.format(Locale.US,"%02X",checksum).equals(sentence.substring(star+1,star+3)))return 0;
        String[] fields=sentence.substring(1,star).split(",",-1);
        try{
            if(fields.length<8||!fields[2].equals("C")||fields[3].length()!=1||"PRDNU".indexOf(fields[3].charAt(0))<0||(!fields[4].equals("0")&&!fields[4].equals("1")))return 0;
            if(!fields[1].matches("[0-9]{1,5}\\.[0-9]{3}")||Double.parseDouble(fields[1])>=86400)return 0;
            if(!fields[5].matches("[1-9][0-9]?"))return 0;int count=Integer.parseInt(fields[5]);
            if(count>MAX_SAMPLES||fields.length!=6+count*2)return 0;
            double prior=-1;
            for(int i=0;i<count;i++){
                String offset=fields[6+2*i],speed=fields[7+2*i];
                if(!offset.matches("[0-9]\\.[0-9]{2}")||!speed.matches("[0-9]{1,3}\\.[0-9]{3}"))return 0;
                double at=Double.parseDouble(offset),value=Double.parseDouble(speed);
                if(at<=prior||at>2||(i==0&&at!=0)||value>200)return 0;prior=at;
            }
            return TYPE;
        }catch(RuntimeException malformed){return 0;}
    }
}
