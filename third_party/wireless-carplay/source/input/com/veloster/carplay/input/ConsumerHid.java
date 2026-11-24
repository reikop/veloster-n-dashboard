/* SPDX-License-Identifier: GPL-3.0-only. Independently authored USB HID Consumer declaration. */
package com.veloster.carplay.input;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** A separate one-byte Consumer Control device; never shares the touch UUID or report. */
public final class ConsumerHid {
    public static final String UUID="4d4b4559"; // MKEY; distinct from TouchHid.UUID.
    public static final int NEXT=0xb5, PREVIOUS=0xb6;
    private static final byte[] DESCRIPTOR={
        0x05,0x0c, 0x09,0x01, (byte)0xa1,0x01,
        0x09,(byte)0xb5, 0x09,(byte)0xb6,
        0x15,0x00, 0x25,0x01, 0x75,0x01, (byte)0x95,0x02, (byte)0x81,0x06,
        0x75,0x06, (byte)0x95,0x01, (byte)0x81,0x03, (byte)0xc0
    };
    public ConsumerHid(){}
    public static byte[] descriptor(){return DESCRIPTOR.clone();}
    public static Map<String,Object> declaration(String displayUuid){
        if(displayUuid==null||displayUuid.length()==0)throw new IllegalArgumentException("Display UUID");
        Map<String,Object> value=new LinkedHashMap<String,Object>();
        value.put("uuid",UUID);value.put("name","VN Media Keys");
        value.put("displayUUID",displayUuid);value.put("hidDescriptor",descriptor());return value;
    }
    public static byte[] press(int usage)throws IOException{
        if(usage==NEXT)return new byte[]{1};
        if(usage==PREVIOUS)return new byte[]{2};
        throw new IOException("Unsupported Consumer usage");
    }
    public static byte[] release(){return new byte[]{0};}
    public static void validate(byte[] report)throws IOException{
        if(report==null||report.length!=1||(report[0]&0xfc)!=0)throw new IOException("Consumer report outside declaration");
        if(report[0]==3)throw new IOException("Simultaneous Consumer keys unsupported");
    }
}
