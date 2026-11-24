/* SPDX-License-Identifier: GPL-3.0-only. See control/NOTICE.md. */
package com.veloster.carplay.control;
import com.veloster.carplay.tunnel.AudioWireFormat;
import java.io.IOException;
import java.util.Map;

/** Exact negotiated stream identity. Audio category is independent of stream type. */
public final class AudioDescription {
    public final int type,controlPort,inputPort,inputSamples;public final String audioType,connectionId,key;public final AudioWireFormat format;
    private AudioDescription(int type,String category,String id,AudioWireFormat format,int controlPort,int inputPort,int inputSamples){
        this.inputPort=inputPort;this.inputSamples=inputSamples;this.controlPort=controlPort;this.type=type;audioType=category;connectionId=id;this.format=format;key=type+":"+category;
    }
    public static AudioDescription parse(Map<String,Object> stream)throws IOException{
        Object raw=stream.get("type");if(!(raw instanceof Long))throw new IOException("Audio type required");
        long type=(Long)raw;if(type!=100&&type!=101&&type!=102)throw new IOException("Audio stream type");
        Object name=stream.get("audioType");String category=name==null?"default":name instanceof String?(String)name:null;
        if(category==null||!category.matches("default|compatibility|media|alert|telephony|speechRecognition"))throw new IOException("Audio category");
        Object bits=stream.get("audioFormat");if(!(bits instanceof Long))throw new IOException("Audio format required");
        AudioWireFormat f=AudioWireFormat.parse((Long)bits);
        boolean voiceEld=type==100&&f.bits==0x04000000L&&
            (category.equals("speechRecognition")||category.equals("telephony")||category.equals("default"));
        boolean outputEld=f.bits==0x02000000L&&
            (type==101&&(category.equals("alert")||category.equals("default"))||
             type==100&&(category.equals("alert")||category.equals("default")));
        if(type==102&&(!f.aac||f.bits==0x04000000L||f.bits==0x02000000L)||
            type!=102&&f.aac&&!voiceEld&&!outputEld)
            throw new IOException("Audio codec/stream type mismatch");
        String id=BinaryPlist.unsigned64(stream.get("streamConnectionID")).toString();
        int input=0,samples=voiceEld||outputEld?480:f.sampleRate/50;
        if(outputEld&&stream.containsKey("framesPerPacket")){
            Object frameCount=stream.get("framesPerPacket");
            if(!(frameCount instanceof Long)||(Long)frameCount!=480L)
                throw new IOException("ELD output AU frame count");
        }
        if(stream.containsKey("dataPort")){
            Object port=stream.get("dataPort");if(!(port instanceof Long)||(Long)port<0||(Long)port>65535)throw new IOException("Audio input port bounds");input=((Long)port).intValue();
        }
        if(input!=0){
            if(type!=100||!(category.equals("speechRecognition")||category.equals("telephony"))||
                (f.bits!=0x10L&&f.bits!=0x4L&&f.bits!=0x04000000L))throw new IOException("Unsupported microphone format/category");
            if(stream.containsKey("framesPerPacket")){
                Object frames=stream.get("framesPerPacket");
                if(!(frames instanceof Long))throw new IOException("Microphone frame bounds");
                long count=(Long)frames;
                if(voiceEld?count!=480:count<f.sampleRate/200||count>f.sampleRate*60/1000)
                    throw new IOException("Microphone frame bounds");
                samples=(int)count;
            }
        }
        int control=0;if(stream.containsKey("controlPort")){
            Object port=stream.get("controlPort");if(!(port instanceof Long)||(Long)port<0||(Long)port>65535)throw new IOException("Audio control port bounds");control=((Long)port).intValue();
        }
        return new AudioDescription((int)type,category,id,f,control,input,samples);
    }
}
