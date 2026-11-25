package com.veloster.dashboard;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import com.veloster.i18n.LanguageSettings;
import com.veloster.vehicle.VehicleDash;

/**
 * EMULATOR SCREENSHOT BUILD ONLY (never in a release): feeds a synthetic one-minute drive through the same public
 * VehicleDash entry points the car data client uses, then keeps cruising at about 104 km/h through gentle bends.
 * adb shell am broadcast -a com.veloster.dashboard.DEMO --ei page N --es lang ko|en
 */
final class DemoFeed {
    private static Thread thread;
    static final float[] K={0,118,70,50,38,31,26};
    static int gearFor(float kmh){return kmh<18?1:kmh<38?2:kmh<60?3:kmh<85?4:kmh<110?5:6;}
    static float kmh(double s){
        double c30=90+10*Math.cos(20/3.0);
        if(s<3)return 0; if(s<10)return (float)((s-3)/7*100); if(s<30)return (float)(90+10*Math.cos((s-10)/3));
        if(s<38)return (float)(c30-(s-30)/8*(c30-40)); if(s<50)return (float)(40+(s-38)/12*70);
        if(s<60)return (float)(110-(s-50)*0.6);
        return (float)(104+2*Math.sin((s-60)/4));
    }
    static float throttle(double s){return s<3?0:s<10?98:s<30?22:s<38?0:s<50?90:28;}
    static float brakeBar(double s){return s>=30&&s<38?18:0;}
    static double lat(double s){return s>=10&&s<30?0.42*Math.sin((s-10)/2.2):s>=60?0.32*Math.sin((s-60)/2.5):0.05*Math.sin(s);}
    static double steer(double s){return s>=10&&s<30?55*Math.sin((s-10)/2.2):s>=60?40*Math.sin((s-60)/2.5):2;}
    static byte[] be16(byte[] b,int at,int v){b[at]=(byte)(v>>8);b[at+1]=(byte)v;return b;}

    static synchronized void start(final Activity a){
        a.getApplicationContext().registerReceiver(new BroadcastReceiver(){public void onReceive(Context c,Intent i){
            if(i.hasExtra("lang")){LanguageSettings.save(a,i.getStringExtra("lang"));LanguageSettings.init(a);}
            if(i.hasExtra("page"))VehicleDash.demoPage(i.getIntExtra("page",0));
        }},new IntentFilter("com.veloster.dashboard.DEMO"));
        if(thread!=null)return;
        thread=new Thread(new Runnable(){public void run(){
            long t0=System.currentTimeMillis();float last=0;
            while(true){
                double s=(System.currentTimeMillis()-t0)/1000.0;
                float v=kmh(s);int g=v<1?0:gearFor(v);float rpm=g==0?820:Math.max(1100,v*K[g]);float thr=throttle(s);
                float lon=(float)((v-last)/3.6/0.1/9.81);last=v;
                VehicleDash.units(new byte[]{2,60,108});VehicleDash.speed(Math.round(v));VehicleDash.driveMode((byte)1,(byte)6);
                int boostRaw=(int)Math.round((1.333*(31*10.731613+458.98)+thr*9)/1.322);
                byte[] p=new byte[8];p[0]=(byte)Math.round((10+thr*0.6)/0.390625);be16(p,1,Math.round(rpm*4));p[3]=(byte)Math.round(thr*0.8/0.390625);
                p[4]=(byte)((31<<2)|3);p[5]=(byte)(boostRaw>>4);p[6]=(byte)(((boostRaw&15)<<4)|(36>>2));p[7]=(byte)((36&3)<<6);
                VehicleDash.rpm(p);
                VehicleDash.temperatures(new byte[]{(byte)Math.round((97+40)/0.75),(byte)Math.round((91+48)/0.75)});
                byte[] ad=new byte[40];be16(ad,0,Math.round(brakeBar(s)*10));ad[4]=(byte)Math.max(g,1);be16(ad,6,Math.round(thr/0.3906f));
                be16(ad,20,(short)Math.round(steer(s)*10));ad[22]=1;ad[23]=0;be16(ad,28,36);be16(ad,30,37);be16(ad,32,35);be16(ad,34,36);
                VehicleDash.additional(ad);
                byte[] gf=new byte[8];be16(gf,0,(int)Math.round((lat(s)+4.17677312)/0.000127465));be16(gf,4,(int)Math.round((-lon+4.17677312)/0.000127465));gf[3]=(byte)26;
                VehicleDash.gforce(gf);
                int volt=8250;VehicleDash.hscanJsn(0xc7,7,new byte[]{0,0,78,(byte)volt,(byte)((volt>>8)&63),0,0,0});
                VehicleDash.hscanJsn(0xc7,0x23,new byte[]{0,0,(byte)Math.round((31+48)/0.75),0,0,0,0,0});
                VehicleDash.hscanJsn(0xc7,0x5a,new byte[]{0,0,0,0,0,0,0,0});
                VehicleDash.hscanJsn(0xc7,0x53,new byte[]{0,0,(byte)Math.round(Math.abs(steer(s))/4),0,0});
                byte[] w=new byte[8];int wr=Math.round(v/0.03125f);for(int k=0;k<4;k++){w[2*k]=(byte)wr;w[2*k+1]=(byte)((wr>>8)&63);}
                VehicleDash.hscanJsn(0xc7,0x6e,w);
                VehicleDash.calculateObserved();
                try{Thread.sleep(100);}catch(InterruptedException e){return;}
            }
        }},"vn-demo-feed");
        thread.setDaemon(true);thread.start();
    }
}
