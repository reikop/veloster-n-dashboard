package com.veloster.carplay.coordinator;

import java.io.IOException;

/** Attempt-scoped OEM commands. Owner tokens are distinct from the AP generation.
 * A successful start is not a media session. */
public interface ReceiverControl {
    int BEGIN=1, NETWORK=2, STOP=3, STATUS=4;
    Reply request(int operation,String token) throws IOException;
    final class Reply {
        public final int result,flags;
        public Reply(int result,int flags) throws IOException {
            if(result<0 || result>4 || (flags&~15)!=0 || ((flags&3)==3) || ((flags&12)!=0 && (flags&1)==0))
                throw new IOException("Invalid receiver response");
            this.result=result;this.flags=flags;
        }
        public boolean owned(){return result==0 && (flags&1)!=0;}
        public boolean nativeStarted(){return owned() && (flags&8)!=0;}
        public boolean released(){return result==3 || (result==0 && (flags&1)==0);}
    }
}
