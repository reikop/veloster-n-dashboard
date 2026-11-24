/* SPDX-License-Identifier: GPL-3.0-only. RTSP/HTTP framing; see control/NOTICE.md. */
package com.veloster.carplay.control;
import java.io.*;
import java.net.URI;
import java.util.*;

/** Bounded CarPlay control profile, not a general HTTP server. No chunked bodies or obs-fold. */
public final class ControlMessage {
    public static final int MAX_BODY=1048576,MAX_HEADER=16384,MAX_LINE=4096;
    private ControlMessage() {}
    public static final class Request {
        public final String method,target,path,protocol;
        public final Map<String,String> headers;
        public final byte[] body;
        private Request(String method,String target,String path,String protocol,Map<String,String> headers,byte[] body){
            this.method=method;this.target=target;this.path=path;this.protocol=protocol;
            this.headers=Collections.unmodifiableMap(headers);this.body=body;
        }
    }
    public static final class Response {
        public final int status;
        private final Map<String,String> headers;
        private final byte[] body;
        public Response(int status,Map<String,String> headers,byte[] body)throws IOException{
            reason(status);if(headers==null||body==null||body.length>MAX_BODY)throw new IOException("Response limit");
            Map<String,String> copy=new LinkedHashMap<String,String>();
            for(Map.Entry<String,String> entry:headers.entrySet()){
                String key=entry.getKey(),value=entry.getValue();
                if(!token(key)||!value(value))throw new IOException("Invalid response header");
                key=key.toLowerCase(Locale.US);
                if(key.equals("content-length")||key.equals("cseq")||key.equals("transfer-encoding")||copy.put(key,value)!=null)
                    throw new IOException("Reserved/duplicate response header");
            }
            if(copy.size()>64)throw new IOException("Response header count");
            this.status=status;this.headers=copy;this.body=body.clone();
        }
        public static Response empty(int status)throws IOException{return new Response(status,Collections.<String,String>emptyMap(),new byte[0]);}
        public static Response body(String type,byte[] body)throws IOException{
            return new Response(200,Collections.singletonMap("Content-Type",type),body);
        }
    }
    /** Event sockets carry both RTSP commands and their responses. */
    public static final class EventMessage {
        public final Request request;
        public final String protocol;
        public final int status;
        public final Map<String,String> headers;
        public final byte[] body;
        private EventMessage(Request request,String protocol,int status,Map<String,String> headers,byte[] body){
            this.request=request;this.protocol=protocol;this.status=status;
            this.headers=Collections.unmodifiableMap(headers);this.body=body;
        }
    }
    private static boolean token(String s){return s!=null&&s.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+");}
    private static boolean value(String s){
        if(s==null)return false;for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c!='\t'&&(c<32||c>126))return false;}return true;
    }
    private static String line(InputStream in,int[] budget,boolean allowEof)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(;;){
            int b=in.read();if(b<0){if(allowEof&&out.size()==0)return null;throw new EOFException("Truncated control header");}
            if(++budget[0]>MAX_HEADER||out.size()>MAX_LINE)throw new IOException("Control header limit");
            if(b==13){if(in.read()!=10)throw new IOException("Header requires CRLF");if(++budget[0]>MAX_HEADER)throw new IOException("Control header limit");return out.toString("US-ASCII");}
            if(b==10||b>126||b<32&&b!=9)throw new IOException("Invalid header byte");out.write(b);
        }
    }
    /** Reads exactly one message; never consumes bytes from the next encryption epoch. */
    public static Request read(InputStream in)throws IOException{
        EventMessage message=readMessage(in,false);return message==null?null:message.request;
    }
    public static EventMessage readEvent(InputStream in)throws IOException{return readMessage(in,true);}
    private static boolean protocol(String s){return s.equals("HTTP/1.1")||s.equals("HTTP/1.0")||s.equals("RTSP/1.0");}
    private static EventMessage readMessage(InputStream in,boolean event)throws IOException{
        int[] budget={0};String first=line(in,budget,true);if(first==null)return null;
        String[] parts=first.split(" ",3);boolean response=parts.length==3&&protocol(parts[0]);
        String path=null,protocol;int status=0;
        if(response){
            if(!event||!parts[1].matches("[1-5][0-9]{2}")||!value(parts[2]))throw new IOException("Invalid event response line");
            protocol=parts[0];status=Integer.parseInt(parts[1]);
        }else{
            if(parts.length!=3||!token(parts[0])||!parts[0].equals(parts[0].toUpperCase(Locale.US))||parts[1].length()==0||
                !protocol(parts[2]))throw new IOException("Invalid control request line");
            protocol=parts[2];
            try{
            URI uri=new URI(parts[1]);
            if(uri.getRawFragment()!=null||uri.getRawUserInfo()!=null)throw new IOException("Invalid request target");
            if(uri.isAbsolute()){
                if(!(uri.getScheme().equalsIgnoreCase("rtsp")||uri.getScheme().equalsIgnoreCase("http"))||uri.getHost()==null)throw new IOException("Invalid absolute target");
            }else if(!parts[1].startsWith("/")||parts[1].startsWith("//"))throw new IOException("Invalid relative target");
            path=uri.getRawPath();if(path==null||path.length()==0)path="/";
            }catch(java.net.URISyntaxException e){throw new IOException("Invalid request target",e);}
        }
        Map<String,String> headers=new LinkedHashMap<String,String>();
        for(;;){
            String field=line(in,budget,false);if(field.length()==0)break;
            int colon=field.indexOf(':');if(colon<1||!token(field.substring(0,colon)))throw new IOException("Invalid header name/folding");
            String key=field.substring(0,colon).toLowerCase(Locale.US),val=field.substring(colon+1).trim();
            if(headers.size()>=64||headers.put(key,val)!=null)throw new IOException("Duplicate/excess headers");
        }
        if(headers.containsKey("transfer-encoding")||headers.containsKey("expect"))throw new IOException("Unsupported body framing");
        String cseq=headers.get("cseq");
        if(cseq!=null&&(!cseq.matches("[0-9]{1,10}")||Long.parseLong(cseq)>2147483647L))throw new IOException("Invalid CSeq");
        if((protocol.equals("RTSP/1.0")||response)&&cseq==null)throw new IOException("Missing CSeq");
        int length=0;String cl=headers.get("content-length");
        if(cl!=null){if(!cl.matches("[0-9]{1,10}"))throw new IOException("Invalid Content-Length");long n=Long.parseLong(cl);
            if(n>MAX_BODY)throw new IOException("Control body limit");length=(int)n;}
        if(("/pair-setup".equals(path)||"/pair-verify".equals(path))&&length>8192)throw new IOException("Pairing body limit");
        byte[] body=new byte[length];int at=0;
        while(at<length){int n=in.read(body,at,length-at);if(n<0)throw new EOFException("Truncated control body");if(n==0)throw new IOException("No body progress");at+=n;}
        Request request=response?null:new Request(parts[0],parts[1],path,protocol,headers,body);
        return new EventMessage(request,protocol,status,headers,body);
    }
    public static byte[] iapCommand(int cseq,byte[] data)throws IOException{
        if(cseq<1||data==null||data.length>65535)throw new IOException("Invalid iAP event command");
        Map<String,Object> command=new LinkedHashMap<String,Object>();command.put("type","iAPSendMessage");
        command.put("params",Collections.singletonMap("data",data));return eventCommand(cseq,command);
    }
    /** null UUID addresses main screen; alternate must use the UUID declared in receiver display metadata. */
    public static byte[] keyframeCommand(int cseq,String uuid)throws IOException{
        if(uuid!=null)validateDisplayUuid(uuid);
        Map<String,Object> command=new LinkedHashMap<String,Object>();command.put("type","forceKeyFrame");
        if(uuid!=null)command.put("params",Collections.singletonMap("uuid",uuid));return eventCommand(cseq,command);
    }
    public static byte[] modeCommand(int cseq,ModeChanges changes)throws IOException{
        if(changes==null)throw new IOException("Missing mode request");
        Map<String,Object> command=new LinkedHashMap<String,Object>();command.put("type","changeModes");
        command.put("params",changes.wire());return eventCommand(cseq,command);
    }
    public static byte[] touchCommand(int cseq,byte[] report)throws IOException{
        if(report==null||report.length!=12)throw new IOException("Touch report size");
        Map<String,Object> command=new LinkedHashMap<String,Object>();command.put("type","hidSendReport");
        command.put("uuid",com.veloster.carplay.input.TouchHid.UUID);command.put("hidReport",report);
        return eventCommand(cseq,command);
    }
    /** One report for the separately declared Consumer Control HID device. */
    public static byte[] consumerCommand(int cseq,byte[] report)throws IOException{
        com.veloster.carplay.input.ConsumerHid.validate(report);
        Map<String,Object> command=new LinkedHashMap<String,Object>();command.put("type","hidSendReport");
        command.put("uuid",com.veloster.carplay.input.ConsumerHid.UUID);command.put("hidReport",report);
        return eventCommand(cseq,command);
    }
    /** Verified Siri button actions, not a Siri-dismiss or microphone-start command. */
    public static byte[] siriCommand(int cseq,int action)throws IOException{
        if(action!=2&&action!=3)throw new IOException("Unsupported Siri button action");
        Map<String,Object> command=new LinkedHashMap<String,Object>();command.put("type","requestSiri");
        command.put("params",Collections.singletonMap("siriAction",action));return eventCommand(cseq,command);
    }
    public static void validateDisplayUuid(String uuid)throws IOException{
        try{if(uuid==null||uuid.length()!=36||!UUID.fromString(uuid).toString().equalsIgnoreCase(uuid))throw new IllegalArgumentException();}
        catch(IllegalArgumentException e){throw new IOException("Invalid display UUID");}
    }
    private static byte[] eventCommand(int cseq,Map<String,Object> command)throws IOException{
        if(cseq<1)throw new IOException("Invalid event sequence");byte[] body=BinaryPlist.encode(command);
        byte[] head=("POST /command RTSP/1.0\r\nContent-Type: application/x-apple-binary-plist\r\nCSeq: "+cseq+
            "\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes("US-ASCII");
        try{byte[] wire=Arrays.copyOf(head,head.length+body.length);System.arraycopy(body,0,wire,head.length,body.length);return wire;}
        finally{Arrays.fill(body,(byte)0);}
    }
    public static byte[] encode(Request request,Response response)throws IOException{
        StringBuilder head=new StringBuilder();head.append(request.protocol).append(' ').append(response.status).append(' ').append(reason(response.status)).append("\r\n");
        for(Map.Entry<String,String> e:response.headers.entrySet())head.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        if(request.headers.containsKey("cseq"))head.append("CSeq: ").append(request.headers.get("cseq")).append("\r\n");
        head.append("Content-Length: ").append(response.body.length).append("\r\n\r\n");
        byte[] h=head.toString().getBytes("US-ASCII");if(h.length>MAX_HEADER)throw new IOException("Response header limit");
        byte[] wire=Arrays.copyOf(h,h.length+response.body.length);System.arraycopy(response.body,0,wire,h.length,response.body.length);return wire;
    }
    private static String reason(int status)throws IOException{
        switch(status){case 200:return "OK";case 400:return "Bad Request";case 401:return "Unauthorized";case 403:return "Forbidden";
        case 404:return "Not Found";case 405:return "Method Not Allowed";case 408:return "Request Timeout";case 413:return "Content Too Large";
        case 415:return "Unsupported Media Type";case 429:return "Too Many Requests";case 455:return "Method Not Valid in This State";case 500:return "Internal Server Error";
        case 501:return "Not Implemented";case 503:return "Service Unavailable";default:throw new IOException("Unsupported response status");}
    }
}
