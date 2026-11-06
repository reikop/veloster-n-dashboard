/* SPDX-License-Identifier: GPL-3.0-only. Exact package, UID, version and signer admission. */
package com.veloster.carplay.advertisement;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.io.IOException;
import java.security.MessageDigest;
public final class EirIdentity {
    private EirIdentity(){}
    public static void check(PackageManager pm,String name,int uid,int minimum,boolean singlePackage)throws IOException{
        try{
            PackageInfo info=pm.getPackageInfo(name,PackageManager.GET_SIGNATURES);
            if(!name.equals(info.packageName)||info.applicationInfo==null||info.applicationInfo.uid!=uid||info.versionCode<minimum||info.signatures==null||info.signatures.length!=1)throw new IOException("EIR package identity mismatch");
            if(singlePackage){String[] packages=pm.getPackagesForUid(uid);if(packages==null||packages.length!=1||!name.equals(packages[0]))throw new IOException("EIR caller UID is shared or foreign");}
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(info.signatures[0].toByteArray());StringBuilder hex=new StringBuilder();for(byte b:hash){hex.append("0123456789abcdef".charAt((b&255)>>4));hex.append("0123456789abcdef".charAt(b&15));}
            if(!EirProtocol.SIGNER_SHA256.equals(hex.toString()))throw new IOException("EIR signer mismatch");
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException("EIR package unavailable",e);}
    }
}
