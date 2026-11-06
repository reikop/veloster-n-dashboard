/* SPDX-License-Identifier: Apache-2.0. Local API17 logging adapter, not SLF4J. */
package javax.jmdns.impl.compat;
import java.util.logging.Level;
/** The pinned engine uses only these level tests and placeholder calls. */
public final class Logger {
    private final java.util.logging.Logger target;
    Logger(String name){target=java.util.logging.Logger.getLogger(name);}
    public boolean isTraceEnabled(){return target.isLoggable(Level.FINEST);}
    public boolean isDebugEnabled(){return target.isLoggable(Level.FINE);}
    public void trace(String text,Object... args){write(Level.FINEST,text,args);}
    public void debug(String text,Object... args){write(Level.FINE,text,args);}
    public void info(String text,Object... args){write(Level.INFO,text,args);}
    public void warn(String text,Object... args){write(Level.WARNING,text,args);}
    private void write(Level level,String text,Object[] args){
        if(!target.isLoggable(level))return;
        Throwable error=args.length>0&&args[args.length-1] instanceof Throwable?(Throwable)args[args.length-1]:null;
        StringBuilder out=new StringBuilder();int cursor=0,index=0,at;
        while(index<args.length&&(at=text.indexOf("{}",cursor))>=0){out.append(text.substring(cursor,at));out.append(String.valueOf(args[index++]));cursor=at+2;}
        out.append(text.substring(cursor));
        if(error==null)target.log(level,out.toString());else target.log(level,out.toString(),error);
    }
}
