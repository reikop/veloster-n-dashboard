/* SPDX-License-Identifier: GPL-3.0-only. */
package com.veloster.carplay.location;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/** One runtime session's requested GPS stream. Raw NMEA or measured GPS fix encoding;
 * no coordinate fabrication or persistence. */
public interface LocationSource extends Closeable {
    int GGA=1,RMC=2,PASCD=4;
    /** Optional explicit profile. Legacy sources declare GGA/RMC only. Support
     * is a sensor capability, never manufactured from an unavailable value. */
    interface Typed extends LocationSource {int supportedMask();}
    boolean available();
    void start(int requestedMask)throws IOException;
    /** At most one fresh sentence per requested type, consumed once, with checksum and CRLF. */
    List<String> poll()throws IOException;
    void stop()throws IOException;
    void close()throws IOException;
}
