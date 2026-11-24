/* SPDX-License-Identifier: GPL-3.0-only. Optional receiver-owned local EIR publication. */
package com.veloster.carplay.advertisement;
import java.io.Closeable;
import java.io.IOException;
public interface ReceiverAdvertisement extends Closeable {
    void start(long timeoutMillis)throws IOException,InterruptedException;
    void checkCurrent()throws IOException;
    boolean awaitStopped(long millis)throws InterruptedException;
}
