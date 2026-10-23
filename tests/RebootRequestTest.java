import com.veloster.carplayhub.RebootRequest;

public class RebootRequestTest {
    static class Backend implements RebootRequest.Backend {
        int checks,calls;boolean allowed=true,fail;
        public boolean permitted(){checks++;return allowed;}
        public void reboot(){calls++;if(fail)throw new IllegalStateException("rejected");}
    }
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
    public static void main(String[] args)throws Exception {
        Backend b=new Backend();RebootRequest r=new RebootRequest(b);
        check(b.calls==0&&!r.request(0),"construction/unarmed request has no effect");
        long first=r.arm();check(b.calls==0&&b.checks==0,"opening confirmation cannot request reboot");
        r.cancel();check(!r.request(first)&&b.calls==0,"cancel/back/pause invalidates confirmation");
        long old=r.arm(),current=r.arm();check(!r.request(old)&&b.calls==0,"superseded dialog cannot reboot");
        check(r.request(current)&&b.calls==1&&b.checks==1,"explicit confirmation makes exactly one permission-checked request");
        check(!r.request(current)&&r.arm()<0&&b.calls==1,"double tap/reopen after dispatch cannot repeat");
        b=new Backend();b.allowed=false;r=new RebootRequest(b);first=r.arm();
        try{r.request(first);throw new AssertionError("permission bypass");}catch(SecurityException expected){}
        check(b.calls==0&&!r.request(first),"permission denial sends no request and consumes ticket");
        b.allowed=true;check(r.request(r.arm())&&b.calls==1,"grant requires a fresh explicit confirmation");
        b=new Backend();b.fail=true;r=new RebootRequest(b);first=r.arm();
        try{r.request(first);throw new AssertionError("failure ignored");}catch(IllegalStateException expected){}
        check(!r.request(first)&&b.calls==1,"failed dispatch never retries old confirmation");
        b.fail=false;check(r.request(r.arm())&&b.calls==2,"failure permits retry only after a new confirmation");
        System.out.println("PASS reboot confirmation, cancel/pause, stale ticket, permission denial, duplicate dispatch and explicit retry; no real reboot");
    }
}
