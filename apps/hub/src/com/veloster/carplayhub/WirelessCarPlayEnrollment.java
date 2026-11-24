package com.veloster.carplayhub;

import com.veloster.carplay.advertisement.EirProtocol;
import com.veloster.carplay.coordinator.*;
import java.io.Closeable;
import java.io.IOException;

/** One foreground discovery grant, never a selected-phone/session permission.
 * Retains every partial backend until exact EIR/AP/auth cleanup completes. */
final class WirelessCarPlayEnrollment implements Closeable {
    interface Consent {void checkCurrent()throws IOException;}
    interface Listener {void status(String message,boolean drained);}
    private final HubApplication application;private final Consent consent;private final Listener listener;
    private final EirProtocol.EnrollmentScope scope;
    private volatile boolean closing,drained;private Thread worker;
    WirelessCarPlayEnrollment(HubApplication application,Consent consent,Listener listener,long generation){
        if(application==null||consent==null||listener==null)throw new NullPointerException();
        this.application=application;this.consent=consent;this.listener=listener;
        scope=new EirProtocol.EnrollmentScope(WirelessSession.newOwner(),WirelessSession.newOwner(),generation,WirelessSession.newOwner());
    }
    private static final class ConsentEnded extends IOException {private static final long serialVersionUID=1L;}
    private void current()throws IOException{
        if(closing)throw new ConsentEnded();
        try{consent.checkCurrent();}catch(IOException revoked){throw new ConsentEnded();}
    }
    void start(){
        if(worker!=null||closing)throw new IllegalStateException("Enrollment already used");
        try{worker=new Thread(new Runnable(){public void run(){work();}},"CarPlay-first-registration");worker.setDaemon(true);worker.start();}
        catch(RuntimeException unavailable){closing=true;drained=true;announce("등록 준비 작업을 시작하지 못했습니다.",true);}
        catch(LinkageError unavailable){closing=true;drained=true;announce("등록 준비 작업을 시작하지 못했습니다.",true);}
    }
    public void close(){closing=true;Thread t=worker;if(t==null){drained=true;return;}t.interrupt();}
    boolean drained(){return drained;}
    private void announce(String text,boolean complete){try{listener.status(text,complete);}catch(RuntimeException ignored){}}
    private void work(){
        IndependentSessionBackend backend=null;SessionServiceController.Backend allocated=null;SessionCoordinator coordinator=null;String result="새 휴대폰 추가를 종료했습니다.";
        try{
            current();announce("등록 전 CarPlay 수신 준비를 확인하고 있습니다.",false);
            allocated=application.wirelessBackend(application).open();
            if(!(allocated instanceof IndependentSessionBackend))throw new IOException("Independent enrollment backend required");
            backend=(IndependentSessionBackend)allocated;current();coordinator=backend.coordinator();
            // Shares the actual receiver's journal lock. An active connection cannot be borrowed/replaced.
            if(!coordinator.recover())throw new IOException("Previous receiver cleanup pending");backend.recoverOwnedResources();current();
            final IndependentSessionBackend.Enrollment lease=backend.prepareEnrollment(scope,new IndependentSessionBackend.EnrollmentConsent(){public void checkCurrent()throws IOException{current();}});
            current();announce("새 휴대폰을 찾을 수 있습니다. iPhone Bluetooth 설정에서 차량을 선택하거나 아래에서 기기를 검색하세요. 시스템 PIN 확인 뒤 사용할 휴대폰을 직접 선택하세요.",false);
            while(!closing){current();lease.checkCurrent();Thread.sleep(100);}
        }catch(ConsentEnded cancelled){result="새 휴대폰 추가를 종료했습니다.";}
         catch(InterruptedException cancelled){result="새 휴대폰 추가를 종료했습니다.";}
         catch(Exception failure){result="CarPlay 등록 준비를 완료하지 못했습니다. 기존 연결을 중지하고 Bluetooth·수신기 설치 상태를 확인하세요.";}
         catch(LinkageError unavailable){result="CarPlay 등록에 필요한 수신기 모듈을 불러오지 못했습니다.";}
        finally{
            closing=true;Thread.interrupted();
            for(;;){
                try{
                    if(backend!=null)backend.discardEnrollment();
                    if(allocated!=null)allocated.close();
                    if(coordinator!=null)coordinator.close();
                    drained=true;announce(result,true);break;
                }catch(Exception pending){announce("등록 광고와 AP 정리가 진행 중입니다. 정리가 끝나야 휴대폰을 선택하거나 다시 연결할 수 있습니다.",false);}
                 catch(LinkageError pending){announce("등록 자원 정리를 확인하지 못했습니다. 다시 연결할 수 없습니다.",false);}
                try{Thread.sleep(500);}catch(InterruptedException retry){Thread.interrupted();}
            }
        }
    }
}
