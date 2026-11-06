/* SPDX-License-Identifier: GPL-3.0-only. Dedicated radio pending record; no receiver STOP receipt. */
package com.veloster.carplay.coordinator;
import java.io.*;

public final class BluetoothRadioJournal implements BluetoothRadioHandoff.Journal {
    private final SessionJournal journal;private SessionJournal.Record clearing;
    public BluetoothRadioJournal(File directory,SessionJournal.DirectorySync durability)throws IOException{journal=new SessionJournal(directory,durability);}
    public synchronized SessionJournal.Record pending()throws IOException{return journal.pending();}
    public synchronized void prepare(SessionJournal.Record record)throws IOException{journal.prepare(record);}
    public synchronized void clear(SessionJournal.Record record)throws IOException{
        SessionJournal.Record pending=journal.pending();
        // A failed directory fsync after deletion may be retried only by this
        // still-owned adapter and the exact record whose removal it attempted.
        if(pending==null&&clearing!=null&&clearing.same(record)){journal.syncOwnedResourceDirectory();return;}
        if(!record.same(pending))throw new IOException("Bluetooth journal owner changed");clearing=record;journal.clearOwnedResource(record);
    }
    public synchronized void close()throws IOException{journal.close();}
}
