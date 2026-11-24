/* SPDX-License-Identifier: GPL-3.0-only. DiPlay two-contact profile; see input/NOTICE.md. */
package com.veloster.carplay.input;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable declaration shared by /info and its exact event sender. */
public final class TouchHid {
    public static final String UUID="2a2a2a2a";
    public final int width,height;
    public TouchHid(int width,int height){
        if(width<2||width>1920||height<2||height>1088)throw new IllegalArgumentException("Touch geometry");
        this.width=width;this.height=height;
    }
    public byte[] descriptor(){
        ByteArrayOutputStream b=new ByteArrayOutputStream();put(b,0x05,0x0d,0x09,0x04,0xa1,0x01);
        for(int slot=0;slot<2;slot++){
            put(b,0x05,0x0d,0x09,0x22,0xa1,0x02,
                0x15,0x00,0x25,0x01,0x09,0x38,0x75,0x08,0x95,0x01,0x81,0x02,
                0x09,0x33,0x75,0x01,0x95,0x01,0x81,0x02,0x95,0x07,0x81,0x03,
                0x05,0x01,0x26,(width-1)&255,(width-1)>>>8,0x09,0x30,0x75,0x10,0x95,0x01,0x81,0x02,
                0x26,(height-1)&255,(height-1)>>>8,0x09,0x31,0x81,0x02,0xc0);
        }put(b,0xc0);return b.toByteArray();
    }
    private static void put(ByteArrayOutputStream b,int...values){for(int value:values)b.write(value);}
    public Map<String,Object> declaration(String displayUuid){
        Map<String,Object> value=new LinkedHashMap<String,Object>();value.put("uuid",UUID);value.put("name","VN Touchscreen");
        value.put("displayUUID",displayUuid);value.put("hidDescriptor",descriptor());return value;
    }
    public void validate(byte[] report)throws IOException{
        if(report==null||report.length!=12)throw new IOException("Touch report size");
        for(int slot=0;slot<2;slot++){
            int at=slot*6,x=(report[at+2]&255)|((report[at+3]&255)<<8),y=(report[at+4]&255)|((report[at+5]&255)<<8);
            if((report[at]&255)!=slot||(report[at+1]&255)>1||x>=width||y>=height)throw new IOException("Touch report outside declaration");
        }
    }
}
