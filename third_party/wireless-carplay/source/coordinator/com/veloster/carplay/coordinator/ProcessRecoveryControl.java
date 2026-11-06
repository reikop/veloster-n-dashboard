/* SPDX-License-Identifier: GPL-3.0-only. Recovery requires independent evidence. */
package com.veloster.carplay.coordinator;
import java.io.IOException;

/** Only independent in-process receivers may use this adapter. External/OEM
 * receivers need their own actual STOP/drain acknowledgement after app death. */
public final class ProcessRecoveryControl implements ReceiverControl {
    public interface OwnedApRelease {void release(String apSession)throws IOException;}
    private final ReceiverControl live;private final SessionJournal journal;private final ProcessOwner.Probe processes;private final OwnedApRelease ap;
    public ProcessRecoveryControl(ReceiverControl live,SessionJournal journal,ProcessOwner.Probe processes,OwnedApRelease ap){
        if(live==null||journal==null||processes==null||ap==null)throw new NullPointerException();this.live=live;this.journal=journal;this.processes=processes;this.ap=ap;
    }
    public Reply request(int operation,String owner)throws IOException{
        if(operation!=STOP)return live.request(operation,owner);
        SessionJournal.Record record=journal.pending();
        if(record!=null&&record.owner.equals(owner)&&record.process!=null&&processes.dead(record.process)){
            // The locked private journal identifies this exact prior owner;
            // process death releases its sockets/workers, never its root AP.
            ap.release(record.apSession);return new Reply(0,0);
        }
        return live.request(operation,owner);
    }
}
