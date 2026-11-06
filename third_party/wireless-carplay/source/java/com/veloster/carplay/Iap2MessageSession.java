/* SPDX-License-Identifier: GPL-3.0-only. See TRANSPORT-NOTICE.md. */
package com.veloster.carplay;
import java.io.Closeable;
import java.io.IOException;
/** Ordered control-session messages over a connected iAP2 link. */
public interface Iap2MessageSession extends Closeable {
    boolean awaitReady(long timeoutMillis) throws IOException,InterruptedException;
    void send(Iap2Csm.Frame frame,long timeoutMillis) throws IOException,InterruptedException;
    Iap2Csm.Frame receive(long timeoutMillis) throws IOException,InterruptedException;
    boolean isClosed();
}
