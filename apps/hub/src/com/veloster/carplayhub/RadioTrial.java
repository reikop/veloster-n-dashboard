package com.veloster.carplayhub;

import java.util.Arrays;

/** Explicit trial only. The first write always follows a durable, complete backup. */
public final class RadioTrial {
    public interface Radio {
        int wifiState() throws Exception;
        int apState() throws Exception;
        byte[] config() throws Exception;
        boolean wifi(boolean on) throws Exception;
        boolean ap(byte[] config, boolean on) throws Exception;
        boolean config(byte[] config) throws Exception;
        boolean noWifiInterfaces() throws Exception;
    }
    public interface Store {
        Snapshot load() throws Exception;
        boolean save(Snapshot snapshot) throws Exception;
        boolean clear() throws Exception;
    }
    public interface Clock {
        long now();
        void sleep(long millis) throws Exception;
    }
    public static final class Snapshot {
        public final boolean wifiOn;
        private final byte[] config;
        public Snapshot(boolean wifiOn, byte[] config) {
            if (config == null || config.length == 0) throw new IllegalArgumentException("Missing AP config");
            this.wifiOn = wifiOn;
            this.config = config.clone();
        }
        public byte[] config() { return config.clone(); }
    }
    private final Radio radio;
    private final Store store;
    private final Clock clock;
    public RadioTrial(Radio radio, Store store, Clock clock) {
        this.radio = radio; this.store = store; this.clock = clock;
    }
    private boolean await(boolean ap, int state, long timeout) throws Exception {
        long until = clock.now() + timeout;
        do {
            if ((ap ? radio.apState() : radio.wifiState()) == state) return true;
            clock.sleep(200);
        } while (clock.now() < until);
        return (ap ? radio.apState() : radio.wifiState()) == state;
    }
    public String start(byte[] trialConfig) throws Exception {
        if (store.load() != null) return "이전 시험 백업이 남아 있습니다. 먼저 원상복구하세요.";
        if (radio.apState() == 13) return "기존 핫스팟이 이미 켜져 있습니다. 설정을 변경하지 않았습니다.";
        int wifi = radio.wifiState();
        if (radio.apState() != 11 || (wifi != 1 && wifi != 3))
            return "Wi-Fi/AP 상태가 안정된 꺼짐/켜짐 상태가 아닙니다. 변경하지 않았습니다.";
        byte[] before = radio.config();
        if (before == null || before.length == 0 || trialConfig == null || trialConfig.length == 0)
            return "기존 핫스팟 설정을 읽지 못했습니다. 백업 불가로 변경하지 않았습니다.";
        Snapshot saved = new Snapshot(wifi == 3, before);
        if (!store.save(saved)) return "백업 저장 실패. 무선 설정을 변경하지 않았습니다.";
        if (wifi == 3 && (!radio.wifi(false) || !await(false, 1, 6000)))
            return "Wi-Fi 끄기 실패/시간 초과. 백업을 보존했습니다. 원상복구하세요.";
        if (!radio.ap(trialConfig, true) || !await(true, 13, 15000))
            return "핫스팟 요청 실패/시간 초과. 성공으로 판정하지 않습니다. 원상복구하세요.";
        return "AP 상태 13 확인. IP 주소와 iPhone의 SSID 검색을 다음으로 확인하세요.";
    }
    public String restore() throws Exception {
        Snapshot saved = store.load();
        if (saved == null) return "복구할 시험 백업이 없습니다. 변경하지 않았습니다.";
        boolean failedInactive = radio.apState() == 14 && radio.wifiState() == 1 && radio.noWifiInterfaces();
        if (!failedInactive && radio.apState() != 11 && (!radio.ap(null, false) || !await(true, 11, 15000)))
            return "핫스팟 끄기 확인 실패. 백업을 보존했습니다. 다시 복구하세요.";
        if (!radio.config(saved.config())) return "기존 AP 설정 복원 요청 실패. 백업 보존.";
        long until = clock.now() + 4000;
        while (!Arrays.equals(saved.config(), radio.config()) && clock.now() < until) clock.sleep(200);
        if (!Arrays.equals(saved.config(), radio.config())) return "기존 AP 설정 읽기 검증 실패. 백업 보존.";
        int target = saved.wifiOn ? 3 : 1;
        if (radio.wifiState() != target && (!radio.wifi(saved.wifiOn) || !await(false, target, 6000)))
            return "기존 Wi-Fi 상태 복원 확인 실패. 백업 보존.";
        if (radio.apState() == 14 && radio.wifiState() == 1 && radio.noWifiInterfaces())
            return "기존 AP 설정과 Wi-Fi 꺼짐 상태는 복구했습니다. AP 드라이버 실패 상태14가 남아 백업을 유지합니다. 다음 정상 시작 후 복구를 다시 확인하세요.";
        if (radio.apState() != 11) return "복구 후 AP 꺼짐 상태 재확인 실패. 백업 보존.";
        if (!store.clear()) return "설정 복원 확인. 백업 표시 삭제 실패로 다음 실행 때 재확인 필요.";
        return "기존 AP 설정과 Wi-Fi 상태를 읽어 원상복구를 확인했습니다.";
    }
}
