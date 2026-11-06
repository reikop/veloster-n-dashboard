package com.veloster.carplayhub;

/** Owns only discovery started here. Pairing never removes or reconnects another device. */
public final class BluetoothSession {
    public interface Radio {
        boolean enabled() throws Exception;
        boolean discovering() throws Exception;
        boolean scan() throws Exception;
        boolean cancel() throws Exception;
        int bondState(String address) throws Exception;
        boolean pair(String address) throws Exception;
    }
    private final Radio radio;
    private boolean ownsDiscovery;
    private static final long CLOCK_ORIGIN=System.nanoTime();
    private static long nowMillis(){return (System.nanoTime()-CLOCK_ORIGIN)/1000000L;}
    private String pairingAddress;private long pairingDeadline;
    public void retirePairing(){pairingAddress=null;pairingDeadline=0;}
    /** Only an exact real completed bond after this request may prompt a wireless selection.
     * This returns an address, never persists a selection or starts a receiver. */
    public String observedBond(String address,int broadcastState)throws Exception{return observedBond(address,broadcastState,nowMillis());}
    public String observedBond(String address,int broadcastState,long nowMillis)throws Exception{
        if(pairingAddress==null)return null;
        if(nowMillis<0||nowMillis>=pairingDeadline||!radio.enabled()){retirePairing();return null;}
        if(address==null||!pairingAddress.equalsIgnoreCase(address))return null;
        if(broadcastState==10){retirePairing();return null;}
        if(broadcastState!=12||radio.bondState(pairingAddress)!=12)return null;
        String result=pairingAddress;retirePairing();return result;
    }
    public String pendingPair(){return pairingAddress;}
    public String pair(String address,long nowMillis)throws Exception{
        retirePairing();String result=requestPair(address);
        if(pairAccepted){pairingAddress=address.toUpperCase(java.util.Locale.US);pairingDeadline=nowMillis+120000;}
        return result;
    }
    private boolean pairAccepted;
    public BluetoothSession(Radio radio){this.radio=radio;}
    public String scan()throws Exception{
        if(!radio.enabled())return "블루투스가 꺼져 있습니다.";
        if(radio.discovering())return "이미 기기를 검색 중입니다.";
        if(!radio.scan())return "기기 검색 요청이 거부되었습니다.";
        ownsDiscovery=true;return "주변 기기를 검색합니다.";
    }
    public void stop()throws Exception{
        if(ownsDiscovery){
            if(!radio.discovering()||radio.cancel())ownsDiscovery=false;
            else throw new IllegalStateException("Discovery cancellation was rejected");
        }
    }
    public void finished(){ownsDiscovery=false;}
    public String pair(String address)throws Exception{return pair(address,nowMillis());}
    private String requestPair(String address)throws Exception{
        pairAccepted=false;
        if(address==null||!address.matches("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}"))return "기기 주소를 확인할 수 없습니다.";
        if(!radio.enabled())return "블루투스가 꺼져 있습니다.";
        int state=radio.bondState(address);
        if(state==12)return "이미 페어링된 기기입니다. 프로필 연결은 해당 앱에서 진행하세요.";
        if(state==11)return "이 기기는 페어링을 진행 중입니다.";
        if(state!=10)return "기기 페어링 상태를 확인할 수 없습니다.";
        stop();
        pairAccepted=radio.pair(address);
        return pairAccepted?"페어링을 요청했습니다. PIN 확인 창이 나타나면 차량 화면에서 확인하세요.":"페어링 요청이 거부되었습니다.";
    }
}
