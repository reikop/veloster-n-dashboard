/* SPDX-License-Identifier: GPL-3.0-only. OpenSSL Apache-2.0; see NOTICE.md. */
#include <jni.h>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/kdf.h>
#include <stdlib.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

static void error(JNIEnv *e,const char *s){
    if((*e)->ExceptionCheck(e))return;
    jclass c=(*e)->FindClass(e,"java/io/IOException");if(c)(*e)->ThrowNew(e,c,s);
}
static int get(JNIEnv *e,jbyteArray a,unsigned char *out,int n){
    if(!a||(*e)->GetArrayLength(e,a)!=n){error(e,"Invalid cryptographic input length");return 0;}
    (*e)->GetByteArrayRegion(e,a,0,n,(jbyte*)out);return !(*e)->ExceptionCheck(e);
}
static jbyteArray result(JNIEnv *e,const unsigned char *data,int n){
    jbyteArray a=(*e)->NewByteArray(e,n);if(a)(*e)->SetByteArrayRegion(e,a,0,n,(const jbyte*)data);return a;
}
static unsigned char *variable(JNIEnv *e,jbyteArray a,int maximum,int *n){
    if(!a){error(e,"Missing cryptographic input");return NULL;}
    *n=(*e)->GetArrayLength(e,a);
    if(*n>maximum){error(e,"Cryptographic input exceeds limit");return NULL;}
    unsigned char *b=malloc((size_t)*n+1);
    if(!b){error(e,"Crypto allocation failed");return NULL;}
    if(!get(e,a,b,*n)){OPENSSL_cleanse(b,(size_t)*n+1);free(b);return NULL;}return b;
}
JNIEXPORT jbyteArray JNICALL Java_com_veloster_carplay_pairing_PairingCrypto_generate
(JNIEnv *e,jclass c,jboolean ed){
    (void)c;unsigned char out[64]={0};size_t a=32,b=32;jbyteArray r=NULL;EVP_PKEY *key=NULL;
    EVP_PKEY_CTX *ctx=EVP_PKEY_CTX_new_id(ed?EVP_PKEY_ED25519:EVP_PKEY_X25519,NULL);
    if(!ctx||EVP_PKEY_keygen_init(ctx)<=0||EVP_PKEY_keygen(ctx,&key)<=0||
       EVP_PKEY_get_raw_private_key(key,out,&a)<=0||EVP_PKEY_get_raw_public_key(key,out+32,&b)<=0||a!=32||b!=32)
        error(e,"Key generation failed");
    else r=result(e,out,64);
    EVP_PKEY_free(key);EVP_PKEY_CTX_free(ctx);OPENSSL_cleanse(out,sizeof(out));return r;
}
JNIEXPORT jbyteArray JNICALL Java_com_veloster_carplay_pairing_PairingCrypto_publicKey
(JNIEnv *e,jclass c,jboolean ed,jbyteArray seed){
    (void)c;unsigned char raw[32]={0},out[32]={0};size_t n=32;jbyteArray r=NULL;EVP_PKEY *key=NULL;
    if(!get(e,seed,raw,32))goto done;
    key=EVP_PKEY_new_raw_private_key(ed?EVP_PKEY_ED25519:EVP_PKEY_X25519,NULL,raw,32);
    if(!key||EVP_PKEY_get_raw_public_key(key,out,&n)<=0||n!=32)error(e,"Public key derivation failed");
    else r=result(e,out,32);
done:EVP_PKEY_free(key);OPENSSL_cleanse(raw,32);OPENSSL_cleanse(out,32);return r;
}
JNIEXPORT jbyteArray JNICALL Java_com_veloster_carplay_pairing_PairingCrypto_shared
(JNIEnv *e,jclass c,jbyteArray seed,jbyteArray peer){
    (void)c;unsigned char raw[32]={0},pub[32]={0},out[32]={0};size_t n=32;
    EVP_PKEY *key=NULL,*remote=NULL;EVP_PKEY_CTX *ctx=NULL;jbyteArray r=NULL;
    if(!get(e,seed,raw,32)||!get(e,peer,pub,32))goto done;
    key=EVP_PKEY_new_raw_private_key(EVP_PKEY_X25519,NULL,raw,32);
    remote=EVP_PKEY_new_raw_public_key(EVP_PKEY_X25519,NULL,pub,32);
    if(key)ctx=EVP_PKEY_CTX_new(key,NULL);
    if(!ctx||!remote||EVP_PKEY_derive_init(ctx)<=0||EVP_PKEY_derive_set_peer(ctx,remote)<=0||
       EVP_PKEY_derive(ctx,out,&n)<=0||n!=32){error(e,"X25519 agreement failed");goto done;}
    {unsigned int any=0;for(size_t i=0;i<32;i++)any|=out[i];
     if(!any){error(e,"X25519 low order peer");goto done;}}
    r=result(e,out,32);
done:EVP_PKEY_CTX_free(ctx);EVP_PKEY_free(key);EVP_PKEY_free(remote);
    OPENSSL_cleanse(raw,32);OPENSSL_cleanse(pub,32);OPENSSL_cleanse(out,32);return r;
}
JNIEXPORT jbyteArray JNICALL Java_com_veloster_carplay_pairing_PairingCrypto_sign
(JNIEnv *e,jclass c,jbyteArray seed,jbyteArray data){
    (void)c;unsigned char raw[32]={0},sig[64]={0};int n=0;size_t size=64;jbyteArray r=NULL;
    unsigned char *message=NULL;EVP_PKEY *key=NULL;EVP_MD_CTX *ctx=NULL;
    if(!get(e,seed,raw,32))goto done;
    message=variable(e,data,4096,&n);if(!message)goto done;
    key=EVP_PKEY_new_raw_private_key(EVP_PKEY_ED25519,NULL,raw,32);ctx=EVP_MD_CTX_new();
    if(!key||!ctx||EVP_DigestSignInit(ctx,NULL,NULL,NULL,key)<=0||
       EVP_DigestSign(ctx,sig,&size,message,(size_t)n)<=0||size!=64)error(e,"Ed25519 signing failed");
    else r=result(e,sig,64);
done:EVP_MD_CTX_free(ctx);EVP_PKEY_free(key);if(message){OPENSSL_cleanse(message,(size_t)n+1);free(message);}
    OPENSSL_cleanse(raw,32);OPENSSL_cleanse(sig,64);return r;
}
JNIEXPORT jboolean JNICALL Java_com_veloster_carplay_pairing_PairingCrypto_verify
(JNIEnv *e,jclass c,jbyteArray pub,jbyteArray data,jbyteArray signature){
    (void)c;unsigned char raw[32]={0},sig[64]={0};int n=0;jboolean ok=JNI_FALSE;
    unsigned char *message=NULL;EVP_PKEY *key=NULL;EVP_MD_CTX *ctx=NULL;
    if(!get(e,pub,raw,32)||!get(e,signature,sig,64))goto done;
    message=variable(e,data,4096,&n);if(!message)goto done;
    key=EVP_PKEY_new_raw_public_key(EVP_PKEY_ED25519,NULL,raw,32);ctx=EVP_MD_CTX_new();
    if(!key||!ctx||EVP_DigestVerifyInit(ctx,NULL,NULL,NULL,key)<=0){error(e,"Ed25519 verification unavailable");goto done;}
    ok=EVP_DigestVerify(ctx,sig,64,message,(size_t)n)==1?JNI_TRUE:JNI_FALSE;
done:EVP_MD_CTX_free(ctx);EVP_PKEY_free(key);if(message){OPENSSL_cleanse(message,(size_t)n+1);free(message);}
    OPENSSL_cleanse(raw,32);OPENSSL_cleanse(sig,64);return ok;
}
JNIEXPORT jbyteArray JNICALL Java_com_veloster_carplay_pairing_PairingCrypto_hkdf
(JNIEnv *e,jclass c,jbyteArray secret,jbyteArray salt,jbyteArray info){
    (void)c;unsigned char out[32]={0};unsigned char *key=NULL,*s=NULL,*i=NULL;
    int kn=0,sn=0,in=0;size_t n=32;EVP_PKEY_CTX *ctx=NULL;jbyteArray r=NULL;
    key=variable(e,secret,128,&kn);if(!key)goto done;
    s=variable(e,salt,128,&sn);if(!s)goto done;
    i=variable(e,info,128,&in);if(!i)goto done;
    if(kn==0){error(e,"Empty HKDF secret");goto done;}
    ctx=EVP_PKEY_CTX_new_id(EVP_PKEY_HKDF,NULL);
    if(!ctx||EVP_PKEY_derive_init(ctx)<=0||EVP_PKEY_CTX_set_hkdf_md(ctx,EVP_sha512())<=0||
       EVP_PKEY_CTX_set1_hkdf_salt(ctx,s,sn)<=0||EVP_PKEY_CTX_set1_hkdf_key(ctx,key,kn)<=0||
       EVP_PKEY_CTX_add1_hkdf_info(ctx,i,in)<=0||EVP_PKEY_derive(ctx,out,&n)<=0||n!=32)error(e,"Pairing HKDF failed");
    else r=result(e,out,32);
done:EVP_PKEY_CTX_free(ctx);OPENSSL_cleanse(out,32);
    if(key){OPENSSL_cleanse(key,(size_t)kn+1);free(key);}if(s){OPENSSL_cleanse(s,(size_t)sn+1);free(s);}
    if(i){OPENSSL_cleanse(i,(size_t)in+1);free(i);}return r;
}
JNIEXPORT jbyteArray JNICALL Java_com_veloster_carplay_pairing_PairingCrypto_aead
(JNIEnv *e,jclass c,jboolean seal,jbyteArray key,jbyteArray nonce,jbyteArray data,jbyteArray aad){
    (void)c;unsigned char k[32]={0},iv[12]={0};unsigned char *input=NULL,*a=NULL,*out=NULL;
    int n=0,an=0,used=0,tail=0;EVP_CIPHER_CTX *ctx=NULL;jbyteArray r=NULL;
    if(!get(e,key,k,32)||!get(e,nonce,iv,12))goto done;
    input=variable(e,data,seal?65535:65551,&n);if(!input)goto done;
    a=variable(e,aad,2,&an);if(!a)goto done;
    if(!seal&&n<16){error(e,"Missing authentication tag");goto done;}
    out=malloc((size_t)n+32);ctx=EVP_CIPHER_CTX_new();
    if(!out||!ctx){error(e,"AEAD allocation failed");goto done;}
    if(seal){
        if(EVP_EncryptInit_ex(ctx,EVP_chacha20_poly1305(),NULL,k,iv)!=1||
           EVP_EncryptUpdate(ctx,NULL,&used,a,an)!=1||EVP_EncryptUpdate(ctx,out,&used,input,n)!=1||
           EVP_EncryptFinal_ex(ctx,out+used,&tail)!=1||used+tail!=n||
           EVP_CIPHER_CTX_ctrl(ctx,EVP_CTRL_AEAD_GET_TAG,16,out+n)!=1){error(e,"AEAD seal failed");goto done;}
        r=result(e,out,n+16);
    }else{
        if(EVP_DecryptInit_ex(ctx,EVP_chacha20_poly1305(),NULL,k,iv)!=1||
           EVP_DecryptUpdate(ctx,NULL,&used,a,an)!=1||EVP_DecryptUpdate(ctx,out,&used,input,n-16)!=1||
           EVP_CIPHER_CTX_ctrl(ctx,EVP_CTRL_AEAD_SET_TAG,16,input+n-16)!=1||
           EVP_DecryptFinal_ex(ctx,out+used,&tail)!=1||used+tail!=n-16){error(e,"AEAD authentication failed");goto done;}
        r=result(e,out,n-16);
    }
done:EVP_CIPHER_CTX_free(ctx);OPENSSL_cleanse(k,32);OPENSSL_cleanse(iv,12);
    if(input){OPENSSL_cleanse(input,(size_t)n+1);free(input);}if(a){OPENSSL_cleanse(a,(size_t)an+1);free(a);}
    if(out){OPENSSL_cleanse(out,(size_t)n+32);free(out);}return r;
}
JNIEXPORT void JNICALL Java_com_veloster_carplay_pairing_PairingCrypto_syncDirectory
(JNIEnv *e,jclass c,jstring path){
    (void)c;if(!path){error(e,"Missing directory");return;}
    const char *name=(*e)->GetStringUTFChars(e,path,NULL);if(!name)return;
    int fd=open(name,O_RDONLY|O_NOFOLLOW|O_DIRECTORY);struct stat st;
    if(fd<0||fstat(fd,&st)<0||!S_ISDIR(st.st_mode)||fsync(fd)<0)error(e,"Pairing directory fsync failed");
    if(fd>=0)close(fd);(*e)->ReleaseStringUTFChars(e,path,name);
}
