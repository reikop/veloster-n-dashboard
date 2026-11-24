/* SPDX-License-Identifier: GPL-3.0-only. Nonblocking presentation mailbox. */
package com.veloster.carplay.media;
import com.veloster.carplay.control.ModeControl;
import java.io.IOException;
public interface AudioReadiness extends ModeControl.Listener {
    void audioReady(ModeControl owner,boolean ready)throws IOException;
    /** Alternate output is not gated by mainAudio resource ownership. */
    interface Roles extends AudioReadiness {void audioReady(ModeControl owner,boolean mainReady,boolean alternateReady)throws IOException;}
}
