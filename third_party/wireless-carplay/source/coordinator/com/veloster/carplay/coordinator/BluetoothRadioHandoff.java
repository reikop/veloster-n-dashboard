/* SPDX-License-Identifier: GPL-3.0-only. Owned 2.4GHz whole-stack radio transition. */
package com.veloster.carplay.coordinator;
import com.veloster.carplay.control.ReceiverScope;
import com.veloster.carplay.pairing.BootstrapIdentityStore;
import java.io.*;

/** One selected AP/phone/generation. Public Android broadcasts have no requester ID:
 * ownership requires our accepted command, exact ordered broadcasts and queried state.
 * A simultaneous indistinguishable external OFF cannot be attributed by this API.
 * Notifications never perform I/O; commands and durable cleanup run on workers. */
public final class BluetoothRadioHandoff implements Closeable {
    public static final int OFF=10,TURNING_ON=11,ON=12,TURNING_OFF=13;
    public interface Radio {int state()throws IOException;boolean disable()throws IOException;boolean enable()throws IOException;}
    public interface Current {void checkCurrent(String ap,String phone,long generation)throws IOException;}
    public interface Readiness {/** Exact live AP, verified runtime and media grant, not a cached ACK. */void checkReady(ReceiverScope scope)throws IOException;}
    public interface Journal extends Closeable {
        SessionJournal.Record pending()throws IOException;
        void prepare(SessionJournal.Record record)throws IOException;
        void clear(SessionJournal.Record record)throws IOException;
    }
    private enum Phase { NEW,DISABLING,OFF_OWNED,RESTORING,RESTORED,CLOSED }
    private final String ap,phone;private final long generation,timeoutMillis;
    private final Current current;private final Radio radio;private final Journal journal;private final ProcessOwner process;
    private final Object operation=new Object(),events=new Object();
    private ReceiverScope scope;private SessionJournal.Record record;private volatile Phase phase=Phase.NEW;
    private volatile boolean invalid,unknown,issued,accepted,turning,terminal,manualOn,manualTurning;private boolean clearing;
    private volatile long deadline;
    public BluetoothRadioHandoff(String ap,String phone,long generation,Current current,Radio radio,Journal journal,ProcessOwner process,long timeoutMillis)throws IOException{
        if(ap==null||!ap.matches("[0-9a-f]{32}")||phone==null||!phone.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}")||generation<1||current==null||radio==null||journal==null||process==null||timeoutMillis<1||timeoutMillis>30000)throw new IOException("Invalid Bluetooth radio lease");
        this.ap=ap;this.phone=phone;this.generation=generation;this.current=current;this.radio=radio;this.journal=journal;this.process=process;this.timeoutMillis=timeoutMillis;
        record=journal.pending();unknown=record!=null;
    }
    private void check(ReceiverScope supplied,Readiness readiness)throws IOException{
        if(invalid||unknown||phase==Phase.CLOSED)throw new IOException("Bluetooth radio ownership unavailable");
        if(Thread.currentThread().isInterrupted())throw new IOException("Bluetooth radio operation interrupted");
        if(supplied==null||!ap.equals(supplied.apSession)||!phone.equals(supplied.selectedBluetooth)||generation!=supplied.generation)throw new IOException("Foreign Bluetooth radio generation");
        if(scope!=null)BootstrapIdentityStore.same(scope,supplied);current.checkCurrent(ap,phone,generation);readiness.checkReady(supplied);
    }
    private void exactRecord()throws IOException{if(record==null||record.process==null||!process.same(record.process)||!record.same(journal.pending()))throw new IOException("Bluetooth radio journal changed");}
    private void expire()throws IOException{if(System.nanoTime()-deadline>=0)throw new IOException("Bluetooth radio acknowledgement timed out");}
    private void waitHint()throws IOException{
        expire();synchronized(events){try{events.wait(Math.min(50,Math.max(1,(deadline-System.nanoTime())/1000000L)));}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Bluetooth radio operation interrupted",e);}}
    }
    /** Consume only exact expected transitions. Missing, duplicate, late or external changes revoke authority. */
    public boolean stateChanged(int previous,int next){
        synchronized(events){
            if(previous==OFF&&next==TURNING_ON){manualTurning=true;manualOn=false;}
            else if(manualTurning&&previous==TURNING_ON&&next==ON){manualOn=true;manualTurning=false;}
            else{manualTurning=false;manualOn=false;}
            boolean expected=false;
            if(!invalid&&!unknown&&System.nanoTime()-deadline<0){
                if(phase==Phase.DISABLING){
                    if(!turning&&previous==ON&&next==TURNING_OFF){turning=true;expected=true;}
                    else if(turning&&!terminal&&previous==TURNING_OFF&&next==OFF){terminal=true;expected=true;}
                }else if(phase==Phase.RESTORING){
                    if(!turning&&previous==OFF&&next==TURNING_ON){turning=true;expected=true;}
                    else if(turning&&!terminal&&previous==TURNING_ON&&next==ON){terminal=true;expected=true;}
                }
            }
            if(!expected)invalid=true;events.notifyAll();return expected;
        }
    }
    /** A bond/settings change is never an expected result of stack OFF. */
    public void externalChange(){synchronized(events){invalid=true;events.notifyAll();}}
    public boolean radioDisabled()throws IOException{
        synchronized(operation){if(unknown||invalid)throw new IOException("Bluetooth radio cleanup required");
            if(phase!=Phase.OFF_OWNED)return false;exactRecord();if(radio.state()!=OFF){externalChange();throw new IOException("Owned Bluetooth OFF changed");}return true;}
    }
    public boolean disable(ReceiverScope supplied,int channel,Readiness readiness)throws IOException{
        synchronized(operation){
            if(channel<1||channel>14||readiness==null)throw new IOException("Whole Bluetooth OFF is only a 2.4GHz handoff");
            try{
                check(supplied,readiness);if(phase==Phase.OFF_OWNED)return radioDisabled();if(phase!=Phase.NEW||record!=null)throw new IOException("Bluetooth radio operation already attempted");
                if(radio.state()!=ON)throw new IOException("Bluetooth must be ON before handoff");scope=supplied;
                record=new SessionJournal.Record(scope.owner,ap,phone,process);journal.prepare(record); // durable BEFORE disable, including unknown IPC outcome
                check(supplied,readiness);if(radio.state()!=ON)throw new IOException("Bluetooth changed before handoff");
                synchronized(events){phase=Phase.DISABLING;turning=false;terminal=false;manualOn=false;manualTurning=false;deadline=System.nanoTime()+timeoutMillis*1000000L;issued=true;}
                boolean result=radio.disable();accepted=result;if(!result)throw new IOException("Bluetooth OFF request refused");
                for(;;){check(supplied,readiness);expire();int value=radio.state();
                    if(value!=ON&&value!=TURNING_OFF&&value!=OFF)throw new IOException("Unexpected Bluetooth OFF state");
                    if(turning&&terminal&&value==OFF){exactRecord();check(supplied,readiness);phase=Phase.OFF_OWNED;return true;}waitHint();}
            }catch(IOException e){invalid=true;throw e;}catch(RuntimeException e){invalid=true;throw new IOException("Bluetooth radio handoff failed",e);}catch(LinkageError e){invalid=true;throw new IOException("Bluetooth radio API unavailable",e);}
        }
    }
    /** Worker only. No active-selection check: STOP intentionally withdraws the permit.
     * The exact still-owned OFF may be restored; ambiguous/late changes never call enable. */
    public void close()throws IOException{
        synchronized(operation){
            if(phase==Phase.CLOSED)return;
            if(unknown){recover(journal,radio,ProcessOwner.linux());record=null;journal.close();phase=Phase.CLOSED;return;}
            if(record==null){journal.close();phase=Phase.CLOSED;return;}
            if(!issued){SessionJournal.Record saved=journal.pending();if(saved!=null){exactRecord();clearing=true;}if(clearing)journal.clear(record);record=null;journal.close();phase=Phase.CLOSED;return;}
            if(phase==Phase.RESTORED){if(radio.state()!=ON)throw new IOException("Restored Bluetooth changed");journal.clear(record);record=null;journal.close();phase=Phase.CLOSED;return;}
            exactRecord();
            if(invalid){
                if((manualOn||(phase==Phase.OFF_OWNED&&accepted&&turning&&terminal))&&radio.state()==ON){phase=Phase.RESTORED;journal.clear(record);record=null;journal.close();phase=Phase.CLOSED;return;}
                throw new IOException("Bluetooth state is uncertain; turn it on manually and retry cleanup");
            }
            if(phase==Phase.OFF_OWNED&&accepted&&turning&&terminal&&radio.state()==ON){phase=Phase.RESTORED;journal.clear(record);record=null;journal.close();phase=Phase.CLOSED;return;}
            if(phase!=Phase.OFF_OWNED||!accepted||!turning||!terminal||radio.state()!=OFF){invalid=true;throw new IOException("Bluetooth OFF ownership unconfirmed");}
            try{
                synchronized(events){phase=Phase.RESTORING;turning=false;terminal=false;manualOn=false;manualTurning=false;deadline=System.nanoTime()+timeoutMillis*1000000L;}
                if(!radio.enable())throw new IOException("Bluetooth restore refused");
                for(;;){if(invalid)throw new IOException("Bluetooth changed during restore");expire();int value=radio.state();
                    if(value!=OFF&&value!=TURNING_ON&&value!=ON)throw new IOException("Unexpected Bluetooth restore state");
                    if(turning&&terminal&&value==ON){phase=Phase.RESTORED;break;}waitHint();}
            }catch(IOException e){invalid=true;throw e;}catch(RuntimeException e){invalid=true;throw new IOException("Bluetooth restore unavailable",e);}catch(LinkageError e){invalid=true;throw new IOException("Bluetooth restore API unavailable",e);}
            journal.clear(record);record=null;journal.close();phase=Phase.CLOSED;
        }
    }
    /** Restart recovery never toggles the adapter. A dead same-app prior owner plus
     * positively queried ON proves no old OFF remains to restore. */
    public static void recover(Journal journal,Radio radio,ProcessOwner.Probe probe)throws IOException{
        SessionJournal.Record previous=journal.pending();if(previous==null)return;
        if(previous.process==null||!probe.dead(previous.process))throw new IOException("Bluetooth prior owner is not proven dead");
        if(radio.state()!=ON)throw new IOException("Turn Bluetooth on manually, then retry cleanup");
        journal.clear(previous);
    }
}
