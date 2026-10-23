import com.veloster.carplayhub.RadioTrial;
import java.util.Arrays;

public class RadioTrialTest {
    static class State implements RadioTrial.Radio, RadioTrial.Store, RadioTrial.Clock {
        int wifi=3,ap=11,writes; long time;
        byte[] config={1,2,3}; RadioTrial.Snapshot saved;
        boolean saveOk=true,apOk=true,wifiOk=true,configOk=true,settle=true,configSettles=true,interfaceAbsent=true;
        public int wifiState(){return wifi;}
        public int apState(){return ap;}
        public byte[] config(){return config==null?null:config.clone();}
        public boolean wifi(boolean on){assert saved!=null;writes++;if(wifiOk&&settle)wifi=on?3:1;return wifiOk;}
        public boolean ap(byte[] value,boolean on){assert saved!=null;writes++;if(apOk&&settle){ap=on?13:11;if(value!=null)config=value.clone();}return apOk;}
        public boolean config(byte[] value){assert saved!=null;writes++;if(configOk&&configSettles)config=value.clone();return configOk;}
        public boolean noWifiInterfaces(){return interfaceAbsent&&ap!=13;}
        public RadioTrial.Snapshot load(){return saved;}
        public boolean save(RadioTrial.Snapshot snapshot){if(saveOk)saved=snapshot;return saveOk;}
        public boolean clear(){saved=null;return true;}
        public long now(){return time;}
        public void sleep(long ms){time+=ms;}
        RadioTrial trial(){return new RadioTrial(this,this,this);}
    }
    public static void main(String[] args)throws Exception {
        State s=new State();String result=s.trial().start(new byte[]{9});assert s.saved!=null&&s.ap==13&&s.wifi==1;
        assert result.contains("13");assert Arrays.equals(s.saved.config(),new byte[]{1,2,3});
        assert s.trial().restore().contains("원상복구");assert s.saved==null&&s.ap==11&&s.wifi==3;
        assert Arrays.equals(s.config,new byte[]{1,2,3});
        s=new State();s.saveOk=false;s.trial().start(new byte[]{9});assert s.writes==0;
        s=new State();s.config=null;s.trial().start(new byte[]{9});assert s.writes==0&&s.saved==null;
        s=new State();s.ap=13;s.trial().start(new byte[]{9});assert s.writes==0&&s.saved==null;
        s=new State();s.wifi=2;s.trial().start(new byte[]{9});assert s.writes==0;
        s=new State();s.saved=new RadioTrial.Snapshot(true,new byte[]{1});s.trial().start(new byte[]{9});assert s.writes==0;
        s=new State();s.apOk=false;s.trial().start(new byte[]{9});assert s.saved!=null&&s.ap==11;
        s.apOk=true;s.trial().restore();assert s.saved==null&&s.wifi==3;
        s=new State();s.settle=false;s.trial().start(new byte[]{9});assert s.saved!=null&&s.time>=6000;
        s=new State();s.wifi=1;s.settle=false;s.trial().start(new byte[]{9});assert s.saved!=null&&s.ap==11&&s.time>=15000;
        s=new State();s.trial().start(new byte[]{9});s.configSettles=false;s.trial().restore();assert s.saved!=null;
        s=new State();s.trial().start(new byte[]{9});s.configOk=false;s.trial().restore();assert s.saved!=null;
        s.configOk=true;s.wifiOk=false;s.trial().restore();assert s.saved!=null;
        s.wifiOk=true;s.trial().restore();assert s.saved==null;
        s=new State();s.trial().start(new byte[]{9});s.apOk=false;s.trial().restore();assert s.saved!=null;
        s=new State();s.wifi=1;s.trial().start(new byte[]{9});s.trial().restore();assert s.wifi==1&&s.saved==null;
        s=new State();s.trial().restore();assert s.writes==0;
        s=new State();s.wifi=1;s.trial().start(new byte[]{9});s.ap=14;
        assert s.trial().restore().contains("실패 상태14");
        assert Arrays.equals(s.config,new byte[]{1,2,3})&&s.wifi==1&&s.saved!=null;
        s.ap=11;s.trial().restore();assert s.saved==null;
        s=new State();s.wifi=1;s.trial().start(new byte[]{9});s.ap=14;s.interfaceAbsent=false;s.apOk=false;
        s.trial().restore();assert s.saved!=null&&Arrays.equals(s.config,new byte[]{9});
        byte[] original={1};RadioTrial.Snapshot b=new RadioTrial.Snapshot(true,original);original[0]=9;
        byte[] copy=b.config();copy[0]=8;assert b.config()[0]==1;
        System.out.println("PASS: durable backup before writes, null/unknown/in-progress rejection, AP completion, bounded timeout, retryable restore, config equality, original Wi-Fi state");
    }
}
