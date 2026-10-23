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
    public String pair(String address)throws Exception{
        if(address==null||!address.matches("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}"))return "기기 주소를 확인할 수 없습니다.";
        if(!radio.enabled())return "블루투스가 꺼져 있습니다.";
        int state=radio.bondState(address);
        if(state==12)return "이미 페어링된 기기입니다. 프로필 연결은 해당 앱에서 진행하세요.";
        if(state==11)return "이 기기는 페어링을 진행 중입니다.";
        if(state!=10)return "기기 페어링 상태를 확인할 수 없습니다.";
        stop();
        return radio.pair(address)?"페어링을 요청했습니다. PIN 확인 창이 나타나면 차량 화면에서 확인하세요.":"페어링 요청이 거부되었습니다.";
    }
}
