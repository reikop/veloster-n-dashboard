import com.veloster.carplayhub.BluetoothSession;

public class BluetoothSessionTest {
    static class Radio implements BluetoothSession.Radio {
        boolean enabled=true,discovering,acceptScan=true,acceptCancel=true,acceptPair=true;
        int scanCalls,cancelCalls,pairCalls,state=10;
        public boolean enabled(){return enabled;}
        public boolean discovering(){return discovering;}
        public boolean scan(){scanCalls++;if(acceptScan)discovering=true;return acceptScan;}
        public boolean cancel(){cancelCalls++;if(acceptCancel)discovering=false;return acceptCancel;}
        public int bondState(String address){return state;}
        public boolean pair(String address){pairCalls++;return acceptPair;}
    }
    static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args)throws Exception{
        Radio r=new Radio();BluetoothSession s=new BluetoothSession(r);
        check(r.scanCalls==0&&r.cancelCalls==0&&r.pairCalls==0);
        r.discovering=true;s.scan();s.stop();check(r.scanCalls==0&&r.cancelCalls==0);
        r.discovering=false;r.enabled=false;s.scan();s.pair("00:11:22:33:44:55");check(r.scanCalls==0&&r.pairCalls==0);
        r.enabled=true;r.acceptScan=false;s.scan();s.stop();check(r.cancelCalls==0);
        r.acceptScan=true;s.scan();s.stop();check(r.scanCalls==2&&r.cancelCalls==1&&!r.discovering);
        s.scan();s.finished();r.discovering=false;s.stop();check(r.cancelCalls==1);
        r.state=12;s.pair("00:11:22:33:44:55");r.state=11;s.pair("00:11:22:33:44:55");r.state=0;s.pair("00:11:22:33:44:55");
        check(r.pairCalls==0);r.state=10;s.pair("invalid");s.pair(null);check(r.pairCalls==0);
        s.scan();s.pair("00:11:22:33:44:55");check(r.pairCalls==1&&r.cancelCalls==2&&!r.discovering);
        r.acceptPair=false;check(s.pair("00:11:22:33:44:55").contains("거부"));check(r.pairCalls==2);
        s.scan();r.acceptCancel=false;
        try{s.stop();throw new AssertionError();}catch(IllegalStateException expected){}
        try{s.pair("00:11:22:33:44:55");throw new AssertionError();}catch(IllegalStateException expected){}
        check(r.pairCalls==2);r.acceptCancel=true;s.stop();check(!r.discovering);
        System.out.println("PASS Bluetooth discovery ownership, disabled/unknown/bonded/bonding guards, explicit new pairing and cancellation failure handling");
    }
}
