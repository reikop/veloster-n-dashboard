package com.veloster.carplayhub.update;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
/** Release authorization key, distinct from the OEM-compatible APK signer. */
public final class UpdateTrust {
 private UpdateTrust(){}
 private static final String DER="30820122300d06092a864886f70d01010105000382010f003082010a0282010100b8a1e8fa4a2ffb2e11eceb9de27d9734cb57c6be5bb4931eef75806b4464a6e8af463d6114488bbdc6ab9732aa6827f2f188587dc381cfb4f001d6613224fd0f7834344f921d6340b0db45934bfdc85d8d542bb1734eeb3d88867edc9bb352a7919b7f40e9be53a471422ba680ebb8963f99d56a5d729998f7993b8dba6636c50e73261403bfa0725879996c4244262899aa45c1be56d84199c5ea2699a0a8a4d50bce2f46c6498e12b36140881a0fabdfeb84d1d01677200d988085e8c93a7101bcf0160d4209249efeae133ad031235b04bd24b2d5359c8cefff6ef72ed594ab979473242a8ae167a429db84445234e7a68b31112e5fa8d40307476770791f0203010001";
 public static PublicKey key()throws GeneralSecurityException{
  byte[] bytes=new byte[DER.length()/2];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)Integer.parseInt(DER.substring(i*2,i*2+2),16);
  return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
 }
}
