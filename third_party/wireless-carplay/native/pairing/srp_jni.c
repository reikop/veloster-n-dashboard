/* SPDX-License-Identifier: GPL-3.0-only. SRP-6a SHA512 server; see NOTICE.md.
 * Fixed RFC5054 3072-bit group/g=5. All modular arithmetic uses OpenSSL BN.
 * Native state is a bounded byte array, not a Java-visible native pointer. */
#include <jni.h>
#include <openssl/bn.h>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/rand.h>
#include <string.h>

#define WIDTH 384
#define STATE_BYTES (16+32+WIDTH+WIDTH)
typedef struct { const unsigned char *data; size_t size; } Part;
static int hash(unsigned char out[64],const Part *parts,size_t count){
    EVP_MD_CTX *ctx=EVP_MD_CTX_new();unsigned int n=0;int ok=ctx&&EVP_DigestInit_ex(ctx,EVP_sha512(),NULL)==1;
    for(size_t i=0;ok&&i<count;i++)ok=EVP_DigestUpdate(ctx,parts[i].data,parts[i].size)==1;
    if(ok)ok=EVP_DigestFinal_ex(ctx,out,&n)==1&&n==64;
    EVP_MD_CTX_free(ctx);return ok;
}
#define HASH(out,...) hash(out,(Part[]){__VA_ARGS__},sizeof((Part[]){__VA_ARGS__})/sizeof(Part))
static void fail(JNIEnv *e){
    if((*e)->ExceptionCheck(e))return;
    jclass c=(*e)->FindClass(e,"java/io/IOException");if(c)(*e)->ThrowNew(e,c,"SRP exchange rejected");
}
static int read_bytes(JNIEnv *e,jbyteArray a,unsigned char *out,int size){
    if(!a||(*e)->GetArrayLength(e,a)!=size)return 0;
    (*e)->GetByteArrayRegion(e,a,0,size,(jbyte*)out);return !(*e)->ExceptionCheck(e);
}
static jbyteArray bytes(JNIEnv *e,const unsigned char *p,int n){
    jbyteArray a=(*e)->NewByteArray(e,n);if(a)(*e)->SetByteArrayRegion(e,a,0,n,(const jbyte*)p);return a;
}
static const unsigned char USER[]="Pair-Setup",CREDENTIAL[]="Pair-Setup:3939";

JNIEXPORT jbyteArray JNICALL Java_com_veloster_carplay_pairing_Srp6a_start
(JNIEnv *e,jclass cls){
    (void)cls;jbyteArray out=NULL;int ok=0;unsigned char state[STATE_BYTES]={0},nb[WIDTH],gb[WIDTH]={0},d[64]={0};
    BN_CTX *ctx=BN_CTX_new();BIGNUM *n=BN_get_rfc3526_prime_3072(NULL),*g=BN_new(),*x=BN_new(),
      *v=BN_new(),*b=BN_new(),*k=BN_new(),*B=BN_new(),*t=BN_new();
    if(!ctx||!n||!g||!x||!v||!b||!k||!B||!t)goto done;
    if(!BN_set_word(g,5)||BN_bn2binpad(n,nb,WIDTH)!=WIDTH)goto done;gb[WIDTH-1]=5;
    if(RAND_bytes(state,16)!=1||RAND_priv_bytes(state+16,32)!=1||!BN_bin2bn(state+16,32,b)||BN_is_zero(b))goto done;
    if(!HASH(d,{CREDENTIAL,sizeof(CREDENTIAL)-1})||!HASH(d,{state,16},{d,64})||!BN_bin2bn(d,64,x))goto done;
    BN_set_flags(x,BN_FLG_CONSTTIME);BN_set_flags(b,BN_FLG_CONSTTIME);
    if(!BN_mod_exp_mont_consttime(v,g,x,n,ctx,NULL)||!HASH(d,{nb,WIDTH},{gb,WIDTH})||!BN_bin2bn(d,64,k))goto done;
    if(!BN_mod_mul(t,k,v,n,ctx)||!BN_mod_exp_mont_consttime(B,g,b,n,ctx,NULL)||!BN_mod_add(B,B,t,n,ctx)||BN_is_zero(B))goto done;
    if(BN_bn2binpad(v,state+48,WIDTH)!=WIDTH||BN_bn2binpad(B,state+48+WIDTH,WIDTH)!=WIDTH)goto done;
    out=bytes(e,state,STATE_BYTES);ok=1;
done:
    if(!ok)fail(e);
    BN_clear_free(x);BN_clear_free(v);BN_clear_free(b);BN_clear_free(k);BN_clear_free(B);BN_clear_free(t);BN_free(n);BN_free(g);BN_CTX_free(ctx);
    OPENSSL_cleanse(state,sizeof(state));OPENSSL_cleanse(d,sizeof(d));return out;
}

JNIEXPORT jbyteArray JNICALL Java_com_veloster_carplay_pairing_Srp6a_verify
(JNIEnv *e,jclass cls,jbyteArray input,jbyteArray peer,jbyteArray proof){
    (void)cls;int ok=0;jbyteArray out=NULL;
    unsigned char state[STATE_BYTES]={0},ab[WIDTH]={0},m[64]={0},nb[WIDTH],gb=5;
    unsigned char d[64]={0},hn[64],hg[64],hu[64],sb[WIDTH]={0},reply[128]={0};
    BN_CTX *ctx=BN_CTX_new();BIGNUM *n=BN_get_rfc3526_prime_3072(NULL),*a=BN_new(),*v=BN_new(),*b=BN_new(),*u=BN_new(),*s=BN_new(),*t=BN_new();
    if(!ctx||!n||!a||!v||!b||!u||!s||!t)goto done;
    if(!read_bytes(e,input,state,STATE_BYTES)||!read_bytes(e,peer,ab,WIDTH)||!read_bytes(e,proof,m,64))goto done;
    if(!BN_bin2bn(ab,WIDTH,a)||BN_is_zero(a)||BN_cmp(a,n)>=0||
       !BN_bin2bn(state+48,WIDTH,v)||BN_is_zero(v)||BN_cmp(v,n)>=0||
       !BN_bin2bn(state+16,32,b)||BN_is_zero(b))goto done;
    if(!HASH(d,{ab,WIDTH},{state+48+WIDTH,WIDTH})||!BN_bin2bn(d,64,u)||BN_is_zero(u))goto done;
    BN_set_flags(b,BN_FLG_CONSTTIME);
    if(!BN_mod_exp_mont_consttime(t,v,u,n,ctx,NULL)||!BN_mod_mul(t,t,a,n,ctx)||
       !BN_mod_exp_mont_consttime(s,t,b,n,ctx,NULL)||BN_is_zero(s))goto done;
    int sn=BN_num_bytes(s);if(sn<1||sn>WIDTH||BN_bn2bin(s,sb)!=sn||!HASH(reply,{sb,(size_t)sn}))goto done;
    if(BN_bn2binpad(n,nb,WIDTH)!=WIDTH||!HASH(hn,{nb,WIDTH})||!HASH(hg,{&gb,1})||!HASH(hu,{USER,sizeof(USER)-1}))goto done;
    for(int i=0;i<64;i++)hn[i]^=hg[i];
    if(!HASH(d,{hn,64},{hu,64},{state,16},{ab,WIDTH},{state+48+WIDTH,WIDTH},{reply,64})||CRYPTO_memcmp(d,m,64)!=0)goto done;
    if(!HASH(reply+64,{ab,WIDTH},{m,64},{reply,64}))goto done;
    out=bytes(e,reply,128);ok=1;
done:
    if(!ok)fail(e);
    BN_free(n);BN_clear_free(a);BN_clear_free(v);BN_clear_free(b);BN_clear_free(u);BN_clear_free(s);BN_clear_free(t);BN_CTX_free(ctx);
    OPENSSL_cleanse(state,sizeof(state));OPENSSL_cleanse(d,sizeof(d));OPENSSL_cleanse(sb,sizeof(sb));OPENSSL_cleanse(reply,sizeof(reply));
    return out;
}
