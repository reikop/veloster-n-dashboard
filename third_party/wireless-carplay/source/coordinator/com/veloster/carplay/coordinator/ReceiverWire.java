package com.veloster.carplay.coordinator;

import java.io.*;

/** Fixed v1 wire framing; operation4 now rejects another generation's owner. */
public final class ReceiverWire {
    public static final int MAGIC=0x564e4350;
    private ReceiverWire() {}
    public static byte[] request(int operation,String token)throws IOException {
        if(operation<1 || operation>4 || token==null || !token.matches("[0-9a-f]{32}"))
            throw new IOException("Invalid receiver request");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(44);
        DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(MAGIC);out.writeInt(1);out.writeInt(operation);out.write(token.getBytes("US-ASCII"));
        return bytes.toByteArray();
    }
    public static ReceiverControl.Reply reply(byte[] bytes)throws IOException {
        if(bytes==null || bytes.length!=12)throw new IOException("Invalid receiver response size");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
        if(in.readInt()!=MAGIC)throw new IOException("Invalid receiver response magic");
        return new ReceiverControl.Reply(in.readInt(),in.readInt());
    }
}
