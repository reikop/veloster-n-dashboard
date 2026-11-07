import com.veloster.carplayhub.BoundedProbeLane;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Pure-Java behaviour of the bounded optional-probe lane: latches and fake work only. */
public final class BoundedProbeLaneTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static final BoundedProbeLane.Current ALWAYS=new BoundedProbeLane.Current(){public boolean current(){return true;}};
    private static long millisSince(long start){return (System.nanoTime()-start)/1000000L;}
    /** Work that blocks like an unresponsive read-only Binder call until released. */
    private static final class Blocked implements BoundedProbeLane.Work<String> {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);final AtomicInteger runs=new AtomicInteger();
        final String value;Blocked(String value){this.value=value;}
        public String run()throws Exception{runs.incrementAndGet();entered.countDown();
            while(true){try{release.await();return value;}catch(InterruptedException ignored){/* Binder calls ignore Java interrupts */}}}
    }
    private static final class Counting implements BoundedProbeLane.Work<String> {
        final AtomicInteger runs=new AtomicInteger();final String value;Counting(String value){this.value=value;}
        public String run(){runs.incrementAndGet();return value;}
    }
    private static void awaitIdle(BoundedProbeLane lane)throws InterruptedException{
        long start=System.nanoTime();while(lane.workerActive()){check(millisSince(start)<3000,"worker exits after release");Thread.sleep(5);}
    }
    private static void blockedTimesOutAndHoldsSlot()throws Exception{
        BoundedProbeLane lane=new BoundedProbeLane("probe-test",300);Blocked stuck=new Blocked("late");
        long start=System.nanoTime();BoundedProbeLane.Result<String> first=lane.run(stuck,ALWAYS);long waited=millisSince(start);
        check(first.outcome==BoundedProbeLane.Outcome.TIMEOUT&&first.value()==null,"blocked probe times out without a value");
        check(waited>=250&&waited<1500,"caller wait bounded near the configured limit: "+waited);
        check(lane.workerActive(),"blocked worker is not reported drained");
        Counting second=new Counting("second");
        start=System.nanoTime();BoundedProbeLane.Result<String> busy=lane.run(second,ALWAYS);
        check(busy.outcome==BoundedProbeLane.Outcome.BUSY&&millisSince(start)<100,"reconnect while blocked is refused immediately");
        for(int i=0;i<5;i++)check(lane.run(second,ALWAYS).outcome==BoundedProbeLane.Outcome.BUSY,"repeated reconnects do not spawn");
        stuck.release.countDown();awaitIdle(lane);
        check(second.runs.get()==0,"refused work was never queued or run later");
        check(stuck.runs.get()==1,"exactly one worker ran");
        // The late value is gone; a fresh attempt after actual exit runs its own work.
        Counting fresh=new Counting("fresh");BoundedProbeLane.Result<String> next=lane.run(fresh,ALWAYS);
        check(next.outcome==BoundedProbeLane.Outcome.COMPLETED&&"fresh".equals(next.value())&&fresh.runs.get()==1,"late success never reused");
    }
    private static void withdrawalDuringWait()throws Exception{
        BoundedProbeLane lane=new BoundedProbeLane("probe-test",1500);final Blocked stuck=new Blocked("stale");final AtomicBoolean selected=new AtomicBoolean(true);
        Thread withdraw=new Thread(new Runnable(){public void run(){try{stuck.entered.await();Thread.sleep(50);}catch(InterruptedException e){return;}selected.set(false);}});
        withdraw.start();long start=System.nanoTime();
        BoundedProbeLane.Result<String> result=lane.run(stuck,new BoundedProbeLane.Current(){public boolean current(){return selected.get();}});
        long waited=millisSince(start);
        check(result.outcome==BoundedProbeLane.Outcome.WITHDRAWN&&result.value()==null,"withdrawn selection discards the probe");
        check(waited<400,"withdrawal wakes the caller promptly: "+waited);
        stuck.release.countDown();awaitIdle(lane);
        // Completed work whose selection was withdrawn at completion is discarded too.
        final AtomicBoolean live=new AtomicBoolean(true);
        BoundedProbeLane.Result<String> late=lane.run(new BoundedProbeLane.Work<String>(){public String run(){live.set(false);return "ready";}},
            new BoundedProbeLane.Current(){public boolean current(){return live.get();}});
        check(late.outcome==BoundedProbeLane.Outcome.WITHDRAWN&&late.value()==null,"result rechecked against the current selection before return");
        awaitIdle(lane);
        Counting never=new Counting("never");
        BoundedProbeLane.Result<String> early=lane.run(never,new BoundedProbeLane.Current(){public boolean current(){return false;}});
        check(early.outcome==BoundedProbeLane.Outcome.WITHDRAWN&&never.runs.get()==0&&!lane.workerActive(),"stale selection spawns nothing");
        BoundedProbeLane.Result<String> throwing=lane.run(new Counting("x"),new BoundedProbeLane.Current(){public boolean current(){throw new IllegalStateException("gone");}});
        check(throwing.outcome==BoundedProbeLane.Outcome.WITHDRAWN,"failing selection check is withdrawal");
    }
    private static void interruptDuringWait()throws Exception{
        final BoundedProbeLane lane=new BoundedProbeLane("probe-test",1500);final Blocked stuck=new Blocked("stale");
        final AtomicReference<BoundedProbeLane.Result<String>> seen=new AtomicReference<BoundedProbeLane.Result<String>>();final AtomicBoolean flag=new AtomicBoolean();
        Thread caller=new Thread(new Runnable(){public void run(){seen.set(lane.run(stuck,ALWAYS));flag.set(Thread.currentThread().isInterrupted());}});
        caller.start();check(stuck.entered.await(2,TimeUnit.SECONDS),"worker entered");Thread.sleep(30);
        long start=System.nanoTime();caller.interrupt();caller.join(2000);
        check(!caller.isAlive()&&millisSince(start)<400,"interrupt wakes the caller promptly");
        check(seen.get().outcome==BoundedProbeLane.Outcome.INTERRUPTED&&seen.get().value()==null&&flag.get(),"interrupt discards the result and keeps the flag");
        check(lane.workerActive(),"interrupted caller does not claim the worker drained");
        check(lane.run(new Counting("busy"),ALWAYS).outcome==BoundedProbeLane.Outcome.BUSY,"slot still occupied after interrupt");
        stuck.release.countDown();awaitIdle(lane);
    }
    private static void closeDuringWait()throws Exception{
        final BoundedProbeLane lane=new BoundedProbeLane("probe-test",1500);final Blocked stuck=new Blocked("stale");
        Thread closer=new Thread(new Runnable(){public void run(){try{stuck.entered.await();Thread.sleep(50);}catch(InterruptedException e){return;}lane.close();}});
        closer.start();long start=System.nanoTime();BoundedProbeLane.Result<String> result=lane.run(stuck,ALWAYS);long waited=millisSince(start);
        check(result.outcome==BoundedProbeLane.Outcome.CLOSED&&result.value()==null,"close discards the in-flight result");
        check(waited<400,"close wakes the caller promptly: "+waited);
        check(lane.closed()&&lane.workerActive(),"close does not pretend the blocked worker exited");
        Counting after=new Counting("after");
        check(lane.run(after,ALWAYS).outcome==BoundedProbeLane.Outcome.CLOSED&&after.runs.get()==0,"closed lane admits nothing");
        stuck.release.countDown();awaitIdle(lane);
        check(lane.run(after,ALWAYS).outcome==BoundedProbeLane.Outcome.CLOSED&&after.runs.get()==0,"closed lane stays closed after the worker exits");
    }
    private static void completedAndFailed()throws Exception{
        BoundedProbeLane lane=new BoundedProbeLane("probe-test",1500);
        BoundedProbeLane.Result<String> ok=lane.run(new Counting("ready"),ALWAYS);
        check(ok.outcome==BoundedProbeLane.Outcome.COMPLETED&&"ready".equals(ok.value()),"prompt completion returns its own value");
        awaitIdle(lane);
        BoundedProbeLane.Result<String> failed=lane.run(new BoundedProbeLane.Work<String>(){public String run()throws Exception{throw new java.io.IOException("x");}},ALWAYS);
        check(failed.outcome==BoundedProbeLane.Outcome.FAILED&&failed.value()==null,"failing work yields no value");
        awaitIdle(lane);
        BoundedProbeLane.Result<String> empty=lane.run(new Counting(null),ALWAYS);
        check(empty.outcome==BoundedProbeLane.Outcome.FAILED,"null result is not a completion");
        awaitIdle(lane);
        BoundedProbeLane.Result<String> linkage=lane.run(new BoundedProbeLane.Work<String>(){public String run(){throw new NoClassDefFoundError("x");}},ALWAYS);
        check(linkage.outcome==BoundedProbeLane.Outcome.FAILED,"missing API is a failure");
        awaitIdle(lane);
        for(long bad:new long[]{0,-1,5001}){boolean refused=false;try{new BoundedProbeLane("probe-test",bad);}catch(IllegalArgumentException expected){refused=true;}check(refused,"unbounded wait refused");}
    }
    private static void completedValueCannotOutliveTheCallerDeadline()throws Exception{
        BoundedProbeLane lane=new BoundedProbeLane("probe-test",100);
        final AtomicInteger checks=new AtomicInteger();
        BoundedProbeLane.Result<String> result=lane.run(new Counting("ready"),new BoundedProbeLane.Current(){
            public boolean current(){
                // Model a paused caller after work completed, while checking its selection.
                if(checks.incrementAndGet()==2)try{Thread.sleep(200);}catch(InterruptedException error){Thread.currentThread().interrupt();}
                return true;
            }
        });
        check(result.outcome==BoundedProbeLane.Outcome.TIMEOUT&&result.value()==null,
            "a completed value must be discarded when the caller deadline passed: "+result.outcome);
        awaitIdle(lane);
    }
    public static void main(String[] args)throws Exception{
        blockedTimesOutAndHoldsSlot();withdrawalDuringWait();interruptDuringWait();closeDuringWait();completedAndFailed();completedValueCannotOutliveTheCallerDeadline();
        System.out.println("PASS: bounded probe lane timeout, no queue/spawn, no late reuse, withdrawal/interrupt/close discard, fresh attempt after real exit");
    }
}
