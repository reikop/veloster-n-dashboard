/* SPDX-License-Identifier: GPL-3.0-only. OpenSSL: Apache-2.0; see NOTICE.md. */
#include <jni.h>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/kdf.h>
#include <stdlib.h>
#include <stdint.h>

static void error(JNIEnv *env, const char *message) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls) (*env)->ThrowNew(env, cls, message);
}
static int copy(JNIEnv *env, jbyteArray src, unsigned char *dst, int length) {
    if (!src || (*env)->GetArrayLength(env, src) != length) {
        error(env, "Invalid crypto input length"); return 0;
    }
    (*env)->GetByteArrayRegion(env, src, 0, length, (jbyte *)dst);
    return !(*env)->ExceptionCheck(env);
}
JNIEXPORT jbyteArray JNICALL Java_com_veloster_carplay_tunnel_TunnelCrypto_hkdf
  (JNIEnv *env, jclass cls, jbyteArray secret, jbyteArray salt, jbyteArray info) {
    (void)cls;
    unsigned char key[32] = {0}, s[128] = {0}, i[128] = {0}, out[32] = {0};
    EVP_PKEY_CTX *ctx = NULL;
    jbyteArray result = NULL;
    size_t length = sizeof(out);
    int sl = salt ? (*env)->GetArrayLength(env, salt) : 0;
    int il = info ? (*env)->GetArrayLength(env, info) : 0;
    if (sl <= 0 || sl > 128 || il <= 0 || il > 128) { error(env, "Invalid HKDF label"); goto done; }
    if (!copy(env, secret, key, 32) || !copy(env, salt, s, sl) || !copy(env, info, i, il)) goto done;
    ctx = EVP_PKEY_CTX_new_id(EVP_PKEY_HKDF, NULL);
    if (!ctx || EVP_PKEY_derive_init(ctx) <= 0 ||
        EVP_PKEY_CTX_set_hkdf_md(ctx, EVP_sha512()) <= 0 ||
        EVP_PKEY_CTX_set1_hkdf_salt(ctx, s, sl) <= 0 ||
        EVP_PKEY_CTX_set1_hkdf_key(ctx, key, sizeof(key)) <= 0 ||
        EVP_PKEY_CTX_add1_hkdf_info(ctx, i, il) <= 0 ||
        EVP_PKEY_derive(ctx, out, &length) <= 0 || length != 32) {
        error(env, "HKDF derivation failed"); goto done;
    }
    result = (*env)->NewByteArray(env, 32);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, 32, (jbyte *)out);
done:
    EVP_PKEY_CTX_free(ctx);
    OPENSSL_cleanse(key, sizeof(key)); OPENSSL_cleanse(s, sizeof(s));
    OPENSSL_cleanse(i, sizeof(i)); OPENSSL_cleanse(out, sizeof(out));
    return result;
}
static jbyteArray open_record(JNIEnv *env, jbyteArray key, jbyteArray nonce,
                              jbyteArray sealed, jbyteArray aad, int profile) {
    unsigned char k[32] = {0}, n[12] = {0}, a[128] = {0};
    unsigned char *cipher = NULL, *plain = NULL;
    EVP_CIPHER_CTX *ctx = NULL;
    jbyteArray result = NULL;
    int size = sealed ? (*env)->GetArrayLength(env, sealed) : 0;
    int used = 0, tail = 0;
    int screen = profile == 1, audio = profile == 2;
    int aad_size = screen ? 128 : audio ? 8 : 2;
    if (size < ((screen || audio) ? 17 : 16) || size > (screen ? 8 * 1024 * 1024 : audio ? 65487 : 65535 + 16)) { error(env, "Invalid sealed record length"); goto done; }
    if (!copy(env, key, k, 32) || !copy(env, nonce, n, 12) || !copy(env, aad, a, aad_size)) goto done;
    if (screen) {
        uint32_t declared = (uint32_t)a[0] | ((uint32_t)a[1] << 8) |
                            ((uint32_t)a[2] << 16) | ((uint32_t)a[3] << 24);
        if (declared != (uint32_t)size || a[4] != 0) { error(env, "Screen header mismatch"); goto done; }
    } else if (!audio && ((int)a[0] | ((int)a[1] << 8)) != size-16) { error(env, "Record length mismatch"); goto done; }
    cipher = malloc((size_t)size); plain = malloc((size_t)size);
    if (!cipher || !plain) { error(env, "Crypto allocation failed"); goto done; }
    if (!copy(env, sealed, cipher, size)) goto done;
    ctx = EVP_CIPHER_CTX_new();
    if (!ctx || EVP_DecryptInit_ex(ctx, EVP_chacha20_poly1305(), NULL, k, n) != 1 ||
        EVP_DecryptUpdate(ctx, NULL, &used, a, aad_size) != 1 ||
        EVP_DecryptUpdate(ctx, plain, &used, cipher, size-16) != 1 ||
        EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_SET_TAG, 16, cipher+size-16) != 1 ||
        EVP_DecryptFinal_ex(ctx, plain+used, &tail) != 1 || used+tail != size-16) {
        error(env, "DataStream authentication failed"); goto done;
    }
    // No plaintext crosses JNI before the authentication tag has been checked.
    result = (*env)->NewByteArray(env, used+tail);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, used+tail, (jbyte *)plain);
done:
    EVP_CIPHER_CTX_free(ctx);
    if (cipher) { OPENSSL_cleanse(cipher, (size_t)size); free(cipher); }
    if (plain) { OPENSSL_cleanse(plain, (size_t)size); free(plain); }
    OPENSSL_cleanse(k, sizeof(k)); OPENSSL_cleanse(n, sizeof(n)); OPENSSL_cleanse(a, sizeof(a));
    return result;
}

JNIEXPORT jbyteArray JNICALL Java_com_veloster_carplay_tunnel_TunnelCrypto_open
  (JNIEnv *env, jclass cls, jbyteArray key, jbyteArray nonce, jbyteArray sealed, jbyteArray aad) {
    (void)cls; return open_record(env, key, nonce, sealed, aad, 0);
}
JNIEXPORT jbyteArray JNICALL Java_com_veloster_carplay_tunnel_TunnelCrypto_openScreen
  (JNIEnv *env, jclass cls, jbyteArray key, jbyteArray nonce, jbyteArray sealed, jbyteArray aad) {
    (void)cls; return open_record(env, key, nonce, sealed, aad, 1);
}
JNIEXPORT jbyteArray JNICALL Java_com_veloster_carplay_tunnel_TunnelCrypto_openAudio
  (JNIEnv *env, jclass cls, jbyteArray key, jbyteArray nonce, jbyteArray sealed, jbyteArray aad) {
    (void)cls; return open_record(env, key, nonce, sealed, aad, 2);
}

JNIEXPORT jbyteArray JNICALL Java_com_veloster_carplay_tunnel_TunnelCrypto_sealAudio
  (JNIEnv *env, jclass cls, jbyteArray key, jbyteArray nonce, jbyteArray plain, jbyteArray aad) {
    (void)cls;
    unsigned char k[32]={0},n[12]={0},a[8]={0};
    unsigned char *input=NULL,*sealed=NULL;EVP_CIPHER_CTX *ctx=NULL;jbyteArray result=NULL;
    int size=plain?(*env)->GetArrayLength(env,plain):0,used=0,tail=0;
    if(size<1||size>65471){error(env,"Invalid microphone frame length");goto done;}
    if(!copy(env,key,k,32)||!copy(env,nonce,n,12)||!copy(env,aad,a,8))goto done;
    input=malloc((size_t)size);sealed=malloc((size_t)size+16);
    if(!input||!sealed){error(env,"Microphone crypto allocation failed");goto done;}
    if(!copy(env,plain,input,size))goto done;
    ctx=EVP_CIPHER_CTX_new();
    if(!ctx||EVP_EncryptInit_ex(ctx,EVP_chacha20_poly1305(),NULL,k,n)!=1||
       EVP_EncryptUpdate(ctx,NULL,&used,a,8)!=1||EVP_EncryptUpdate(ctx,sealed,&used,input,size)!=1||
       EVP_EncryptFinal_ex(ctx,sealed+used,&tail)!=1||used+tail!=size||
       EVP_CIPHER_CTX_ctrl(ctx,EVP_CTRL_AEAD_GET_TAG,16,sealed+size)!=1){error(env,"Microphone encryption failed");goto done;}
    result=(*env)->NewByteArray(env,size+16);
    if(result)(*env)->SetByteArrayRegion(env,result,0,size+16,(jbyte*)sealed);
done:
    EVP_CIPHER_CTX_free(ctx);
    if(input){OPENSSL_cleanse(input,(size_t)size);free(input);}
    if(sealed){OPENSSL_cleanse(sealed,(size_t)size+16);free(sealed);}
    OPENSSL_cleanse(k,sizeof(k));OPENSSL_cleanse(n,sizeof(n));OPENSSL_cleanse(a,sizeof(a));
    return result;
}
