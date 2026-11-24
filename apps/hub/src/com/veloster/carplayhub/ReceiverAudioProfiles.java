package com.veloster.carplayhub;

import com.veloster.carplay.control.AudioCapabilities;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Builds the receiver declaration from separate, actually probed microphone sources.
 * A successful speech-recognition probe never grants a telephony format, or vice versa. */
final class ReceiverAudioProfiles {
    private ReceiverAudioProfiles(){}
    static AudioCapabilities create(long speechFormats,long callFormats)throws IOException {
        return create(speechFormats,callFormats,0);
    }
    /** Bit25 is receive-only and offered only with a current output decoder proof. */
    static AudioCapabilities create(long speechFormats,long callFormats,long outputFormats)throws IOException {
        if(speechFormats<0||callFormats<0||(speechFormats&~0x04000014L)!=0||(callFormats&~0x04000014L)!=0)
            throw new IOException("Unsupported probed microphone format");
        if(outputFormats!=0&&outputFormats!=0x02000000L)
            throw new IOException("Unsupported probed output format");
        List<AudioCapabilities.Output> outputs=new ArrayList<AudioCapabilities.Output>();
        long pcm=(1L<<2)|(1L<<3)|(1L<<4)|(1L<<5)|(1L<<6)|(1L<<7)|(1L<<8)|(1L<<9)|(1L<<10)|(1L<<11)|(1L<<14)|(1L<<15);
        outputs.add(new AudioCapabilities.Output(100,"media",pcm));
        outputs.add(new AudioCapabilities.Output(101,"alert",pcm|outputFormats));
        if(outputFormats!=0){
            outputs.add(new AudioCapabilities.Output(101,"default",outputFormats));
            outputs.add(new AudioCapabilities.Output(100,"alert",outputFormats));
            outputs.add(new AudioCapabilities.Output(100,"default",outputFormats));
        }
        outputs.add(new AudioCapabilities.Output(102,"media",0xc00000L));
        List<AudioCapabilities.Input> inputs=new ArrayList<AudioCapabilities.Input>();
        if(speechFormats!=0){outputs.add(new AudioCapabilities.Output(100,"speechRecognition",speechFormats));inputs.add(new AudioCapabilities.Input(100,"speechRecognition",speechFormats));}
        if(callFormats!=0){outputs.add(new AudioCapabilities.Output(100,"telephony",callFormats));inputs.add(new AudioCapabilities.Input(100,"telephony",callFormats));}
        return new AudioCapabilities(outputs,inputs);
    }
}
