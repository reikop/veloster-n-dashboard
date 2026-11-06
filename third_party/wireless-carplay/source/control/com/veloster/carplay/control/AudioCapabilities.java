/* SPDX-License-Identifier: GPL-3.0-only. Output format wire profile; see control/NOTICE.md. */
package com.veloster.carplay.control;
import com.veloster.carplay.tunnel.AudioWireFormat;
import java.io.IOException;
import java.util.*;

/** Immutable output and optional Siri input declaration shared by /info and SETUP.
 * This is a configured capability contract, not a codec probe or a latency measurement.
 * Only explicitly configured input formats are published. No OEM voice routing,
 * redundant packets, plaintext or legacy AES is implied.
 */
public final class AudioCapabilities {
    public static final class Output {
        public final int type;public final String audioType;public final long formats;
        public Output(int type,String audioType,long formats)throws IOException{
            if(type<100||type>102||audioType==null||!audioType.matches("default|compatibility|media|alert|speechRecognition|telephony"))
                throw new IOException("Unsupported output category/type");
            if(formats<=0)throw new IOException("Empty audio format mask");
            if(audioType.equals("telephony")&&(type!=100||(formats&~0x04000014L)!=0))throw new IOException("Unsupported telephony output profile");
            for(int bit=0;bit<63;bit++)if((formats&(1L<<bit))!=0){
                AudioWireFormat f=AudioWireFormat.parse(1L<<bit);
                boolean voiceEld=type==100&&f.bits==0x04000000L&&
                    (audioType.equals("default")||audioType.equals("speechRecognition")||audioType.equals("telephony"));
                boolean outputEld=f.bits==0x02000000L&&
                    (type==101&&(audioType.equals("alert")||audioType.equals("default"))||
                     type==100&&(audioType.equals("alert")||audioType.equals("default")));
                if(type==102&&(f.bits==0x04000000L||f.bits==0x02000000L)||
                    f.bits==0x02000000L&&!outputEld||
                    f.aac!=(type==102)&&!voiceEld&&!outputEld)
                    throw new IOException("Output codec/type mismatch");
            }
            this.type=type;this.audioType=audioType;this.formats=formats;
        }
    }
    public static final class Input {
        public final int type;public final String audioType;public final long formats;
        public Input(int type,String audioType,long formats)throws IOException {
            if(type!=100||!("speechRecognition".equals(audioType)||"telephony".equals(audioType))||
                formats==0||(formats&~0x04000014L)!=0)throw new IOException("Unsupported microphone declaration");
            this.type=type;this.audioType=audioType;this.formats=formats;
        }
    }
    private final List<Output> outputs;private final List<Input> inputs;private final long features;
    public AudioCapabilities(List<Output> outputs)throws IOException{
        this(outputs,Collections.<Input>emptyList());
    }
    public AudioCapabilities(List<Output> outputs,List<Input> inputs)throws IOException{
        if(outputs==null||outputs.isEmpty()||outputs.size()>12)throw new IOException("Audio declaration bounds");
        List<Output> copy=new ArrayList<Output>();Set<String> keys=new HashSet<String>();long flags=1L<<9;
        for(Output out:outputs){
            if(out==null||!keys.add(out.type+":"+out.audioType))throw new IOException("Duplicate audio declaration");
            copy.add(out);flags|=1L<<(out.type==102?20:18);
        }
        if(inputs==null||inputs.size()>2)throw new IOException("Microphone declaration bounds");
        Set<String> inputKeys=new HashSet<String>();
        List<Input> microphone=new ArrayList<Input>();
        for(Input in:inputs){boolean found=false;if(in==null||!inputKeys.add(in.type+":"+in.audioType))throw new IOException("Missing/duplicate microphone declaration");
            for(Output out:copy)if(out.type==in.type&&out.audioType.equals(in.audioType)&&(out.formats&in.formats)==in.formats)found=true;
            if(!found)throw new IOException("Microphone needs matching actual output");microphone.add(in);
        }
        this.inputs=Collections.unmodifiableList(microphone);this.outputs=Collections.unmodifiableList(copy);features=flags;
    }
    public List<Output> outputs(){return outputs;}
    public long features(){return features;}
    public boolean hasInput(){return !inputs.isEmpty();}
    public boolean hasInput(String purpose){for(Input in:inputs)if(in.audioType.equals(purpose))return true;return false;}
    public boolean supportsInput(AudioDescription d){if(d==null||d.inputPort==0)return false;for(Input in:inputs)if(in.type==d.type&&in.audioType.equals(d.audioType)&&(in.formats&d.format.bits)==d.format.bits)return true;return false;}
    public boolean supports(AudioDescription d){
        if(d==null)return false;
        for(Output out:outputs)if(out.type==d.type&&out.audioType.equals(d.audioType))return (out.formats&d.format.bits)==d.format.bits;
        return false;
    }
    /** Fresh mutable wire maps: mutation by an encoder/caller cannot change future declarations. */
    public List<Object> wire(){
        List<Object> result=new ArrayList<Object>();
        for(Output out:outputs){Map<String,Object> entry=new LinkedHashMap<String,Object>();
            entry.put("type",out.type);entry.put("audioType",out.audioType);entry.put("audioOutputFormats",out.formats);
            for(Input in:inputs)if(in.type==out.type&&in.audioType.equals(out.audioType))entry.put("audioInputFormats",in.formats);result.add(entry);}
        return result;
    }
    public void validateFactory(IapSetupHandler.AudioSinkFactory factory)throws IOException{
        if(factory==null)throw new IOException("Audio declaration needs an output factory");
        for(Output out:outputs)for(int bit=0;bit<63;bit++)if((out.formats&(1L<<bit))!=0){
            Map<String,Object> stream=new LinkedHashMap<String,Object>();stream.put("type",(long)out.type);stream.put("audioType",out.audioType);
            stream.put("audioFormat",1L<<bit);stream.put("streamConnectionID",0L);
            if(!factory.supports(AudioDescription.parse(stream)))throw new IOException("Advertised audio format has no output");
        }
    }
    public void validateMicrophone(IapSetupHandler.MicrophoneFactory factory)throws IOException {
        if(!hasInput())return;if(factory==null||factory.capabilities()!=this)throw new IOException("Microphone declaration/factory mismatch");
        for(Input in:inputs)for(int bit=0;bit<63;bit++)if((in.formats&(1L<<bit))!=0){Map<String,Object> stream=new LinkedHashMap<String,Object>();stream.put("type",(long)in.type);stream.put("audioType",in.audioType);stream.put("audioFormat",1L<<bit);stream.put("streamConnectionID",0L);stream.put("dataPort",1L);
            if(!factory.supports(AudioDescription.parse(stream)))throw new IOException("Advertised microphone has no input path");}
    }
}
