package com.veloster.vehicle;

/** 안내·통화 양보: the path opens, ducks or steps aside for the head unit's voices and comes back; our own MICOM
 *  events are told from the navigation's; the listener code table and 속도 따라 키우기. */
public final class SoundYieldTest {
    private static int checks;
    private static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
    private static boolean near(double a,double b){return Math.abs(a-b)<0.02;}

    public static void main(String[] args){
        // duck: a navigation prompt lowers the sound to 30 % and keeps the path
        SoundYield y=new SoundYield(SoundYield.DUCK);long t=1000;
        check(y.tick(t,true,1)==SoundYield.ACT_CLOSE,"first tick cleans up");
        check(y.tick(t+=10,true,1)==SoundYield.ACT_OPEN,"opens with rpm");y.openResult(true,t);
        check(y.tick(t+=50,true,1)==SoundYield.ACT_NONE&&near(y.gain(t),1),"open, full gain");
        y.source(SoundYield.source(0xf),SoundYield.isStart(0xf),t);
        check(near(y.gain(t+60),0.5)&&near(y.gain(t+200),SoundYield.DUCK_GAIN),"fades at 1 per 120 ms down to the duck level");
        check(y.tick(t+=300,true,y.gain(t))==SoundYield.ACT_NONE&&y.isOpen()&&y.ducking(t),"ducked, path kept");
        y.source("navi",SoundYield.isStart(0x10),t);
        check(near(y.gain(t+100),0.65)&&near(y.gain(t+200),1),"back up over 200 ms, no hold-off after a duck");
        // a call during a ducked prompt: full yield, path closed, back after the hold-off
        y.source("navi",true,t+=500);y.source(SoundYield.source(0x26),true,t+=50);
        check(y.gain(t+200)==0,"ringing silences it");
        check(y.tick(t+=200,true,0)==SoundYield.ACT_CLOSE&&!y.isOpen(),"closed for the call");
        y.source("navi",false,t+=1000);y.source("ring",false,t);   // the call ends last: a full yield, so the hold-off applies
        check(y.tick(t+100,true,0)==SoundYield.ACT_NONE,"no open during the hold-off");
        check(y.tick(t+=SoundYield.HOLDOFF_MS+10,true,0)==SoundYield.ACT_OPEN,"opens after the hold-off");y.openResult(true,t);
        check(near(y.gain(t+100),0.55),"ramps back in");

        // stop: a prompt silences it and closes the path
        y=new SoundYield(SoundYield.STOP);t=0;y.tick(t,true,1);y.tick(t+=10,true,1);y.openResult(true,t);
        y.source("navi",true,t+=100);
        check(y.gain(t+130)==0&&y.tick(t+=130,true,0)==SoundYield.ACT_CLOSE,"stop mode: silent and closed for a prompt");

        // our own AudioStart is ours; the navigation's chime on our type makes us step aside; its end: regrab
        y=new SoundYield(SoundYield.DUCK);t=0;y.tick(t,true,1);
        check(y.tick(t+=10,true,1)==SoundYield.ACT_OPEN,"open");y.ownStartCalled(t);y.openResult(true,t);
        check(y.ownType(true,t+5)==SoundYield.OURS,"our own AudioStart");
        check(y.ownType(true,t+=3000)==SoundYield.FOREIGN_START&&y.skipEnd(t),"someone else's start: never endAudio over it");
        check(y.ownType(false,t+=400)==SoundYield.FOREIGN_END&&y.gain(t)==0,"its end took the channel: silent");
        check(y.tick(t+50,true,0)==SoundYield.ACT_NONE&&y.tick(t+SoundYield.REGRAB_WAIT_MS+1,true,0)==SoundYield.ACT_REGRAB,"taken back after 100 ms");
        y.regrabResult(true,t+=SoundYield.REGRAB_WAIT_MS+1);
        check(near(y.gain(t+SoundYield.RAMP_MS),1),"back at full once the chime's duck ramp and the regrab ramp are done");

        // a refused startAudio waits 1, 2 .. s; silence closes after 2 s
        y=new SoundYield(SoundYield.DUCK);t=0;y.tick(t,true,1);
        check(y.tick(t+=10,true,1)==SoundYield.ACT_OPEN,"open");y.openResult(false,t);
        check(y.tick(t+500,true,1)==SoundYield.ACT_NONE&&y.tick(t+1001,true,1)==SoundYield.ACT_OPEN,"retried after 1 s");y.openResult(true,t+=1001);
        check(y.tick(t+=10,false,1)==SoundYield.ACT_NONE&&y.tick(t+1990,false,1)==SoundYield.ACT_NONE&&y.tick(t+SoundYield.SILENCE_CLOSE_MS,false,1)==SoundYield.ACT_CLOSE,"closed after 2 s of silence");

        // a yield without an end expires
        y=new SoundYield(SoundYield.STOP);y.source("vr",true,0);
        check(y.yielding(1000)&&!y.yielding(SoundYield.EXPIRY_MS+SoundYield.HOLDOFF_MS+1),"a lost end expires");

        // listener table (V141): every source has one start and one end code
        java.util.Map<String,int[]> seen=new java.util.HashMap<String,int[]>();
        for(int c=0;c<0x60;c++){String s=SoundYield.source(c);if(s==null)continue;int[] n=seen.get(s);if(n==null)seen.put(s,n=new int[2]);n[SoundYield.isStart(c)?0:1]++;}
        for(java.util.Map.Entry<String,int[]> e:seen.entrySet())check(e.getValue()[0]==1&&e.getValue()[1]==1,"one start and one end for "+e.getKey());
        check(seen.size()==14&&"navi".equals(SoundYield.source(0xf))&&SoundYield.isStart(0xf)&&!SoundYield.isStart(0x10)&&"btvoice".equals(SoundYield.source(0x15))&&!SoundYield.isStart(0x15),"V141 codes");
        check(SoundYield.source(SoundYield.CB_AUDIO_START)==null&&SoundYield.source(SoundYield.CB_AUDIO_END)==null,"AudioStart / AudioEnd are not sources");
        check(SoundYield.yieldsFor(SoundYield.NAVI)&&SoundYield.yieldsFor(SoundYield.RING)&&!SoundYield.yieldsFor(0x31),"yield for voices, not for music");
        check(SoundYield.duckable("navi")&&SoundYield.duckable("a"+SoundYield.NAVI_SOUNDEFFECT)&&!SoundYield.duckable("ring")&&!SoundYield.duckable("a"+SoundYield.RING),"prompts duck, calls do not");

        // 속도 따라 키우기
        check(SoundYield.speedBoost(0,120)==1&&SoundYield.speedBoost(3,40)==1&&SoundYield.speedBoost(3,Double.NaN)==1,"no boost off, slow or without speed");
        check(near(SoundYield.speedBoost(3,110),Math.pow(10,9/20.0))&&near(SoundYield.speedBoost(3,200),Math.pow(10,9/20.0))&&near(SoundYield.speedBoost(1,75),Math.pow(10,1.5/20)),"0..9 dB from 40 to 110 km/h");
        System.out.println("PASS "+checks+" sound yield checks");
    }
}
