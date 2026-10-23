package com.veloster.vehicle;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.media.MediaMetadataRetriever;
import android.view.View;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

/** One background decoder with a replaceable pending job; never fetches artwork from a network. */
final class ArtworkLoader {
    private final Context context;
    private final View view;
    private long revision=-1;
    private String trackKey="";
    private MediaState.Snapshot pending;
    private boolean running,closed;
    private volatile Bitmap bitmap;
    ArtworkLoader(Context c,View v){context=c;view=v;}
    Bitmap bitmap(){return bitmap;}
    synchronized void request(MediaState.Snapshot track){
        if(closed||track.artworkRevision==revision)return;
        revision=track.artworkRevision;
        String nextKey=track.source+"\n"+track.title;
        if(!trackKey.equals(nextKey)||(track.artworkBytes==null && track.artworkUri.length()==0))bitmap=null;
        trackKey=nextKey;pending=track;
        if(running)return;
        running=true;
        new Thread(new Runnable(){public void run(){decodePending();}},"NMode-artwork").start();
    }
    synchronized void open(){closed=false;revision=-1;}
    synchronized void close(){closed=true;pending=null;bitmap=null;revision=-1;}
    private void decodePending(){
        while(true){
            final MediaState.Snapshot track;
            synchronized(this){
                if(closed||pending==null){running=false;return;}
                track=pending;pending=null;
            }
            Bitmap decoded=null;
            try{
                byte[] bytes=track.artworkBytes;
                if(bytes==null && track.artworkUri.length()>0)bytes=read(track.artworkUri);
                decoded=decode(bytes);
                if(decoded==null && "USB".equals(track.source) && track.artworkUri.length()>0)decoded=decode(embedded(track.artworkUri));
            }catch(Exception e){android.util.Log.i("VehicleDash","Album artwork unavailable");}
            catch(OutOfMemoryError e){android.util.Log.i("VehicleDash","Album artwork exceeds decoder memory");}
            synchronized(this){if(!closed && track.artworkRevision==revision)bitmap=decoded;}
            view.post(new Runnable(){public void run(){view.invalidate();}});
        }
    }
    private Bitmap decode(byte[] bytes){
        if(bytes==null || bytes.length==0 || bytes.length>2*1024*1024)return null;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
        if(options.outWidth<=0 || options.outHeight<=0 || options.outWidth>8192 || options.outHeight>8192)return null;
        options.inSampleSize=1;
        while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>512)options.inSampleSize*=2;
        options.inJustDecodeBounds=false;
        return BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
    }
    private byte[] embedded(String value)throws Exception{
        Uri uri=Uri.parse(value);
        if(uri.getScheme()!=null && !"file".equals(uri.getScheme()) && !"content".equals(uri.getScheme()))return null;
        MediaMetadataRetriever retriever=new MediaMetadataRetriever();
        try{
            if(uri.getScheme()==null)retriever.setDataSource(value);else retriever.setDataSource(context,uri);
            byte[] bytes=retriever.getEmbeddedPicture();
            return bytes!=null && bytes.length<=2*1024*1024?bytes:null;
        }finally{retriever.release();}
    }
    private byte[] read(String value)throws Exception{
        Uri uri=Uri.parse(value);
        if(uri.getScheme()==null)uri=Uri.fromFile(new File(value));
        String scheme=uri.getScheme();
        if(!"file".equals(scheme)&&!"content".equals(scheme))return null;
        InputStream in=context.getContentResolver().openInputStream(uri);
        if(in==null)return null;
        try{
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[8192];int count;
            while((count=in.read(block))!=-1){if(out.size()+count>2*1024*1024)return null;out.write(block,0,count);}
            return out.toByteArray();
        }finally{in.close();}
    }
}
