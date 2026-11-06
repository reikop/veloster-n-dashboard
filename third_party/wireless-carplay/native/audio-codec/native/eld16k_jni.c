/* SPDX-License-Identifier: GPL-3.0-only. API17 dynamic system codec JNI. */
#include "eld16k_system_encoder.h"
#include <jni.h>
#include <stdint.h>
#include <string.h>

static void io_error(JNIEnv *env,const char *message){
    jclass cls=(*env)->FindClass(env,"java/io/IOException");
    if(cls)(*env)->ThrowNew(env,cls,message);
}
static void erase(void *pointer,size_t length){
    volatile uint8_t *bytes=(volatile uint8_t *)pointer;
    while(length--)*bytes++=0;
}
JNIEXPORT jlong JNICALL
Java_com_veloster_carplay_media_SystemEld16kEncoder_nativeOpen(JNIEnv *env,jclass ignored,
                                                                 jbyteArray configuration,jintArray length){
    (void)ignored;
    if(!configuration||!length||(*env)->GetArrayLength(env,configuration)!=VN_ELD16_MAX_ASC_BYTES||
       (*env)->GetArrayLength(env,length)!=1){io_error(env,"ELD config destination bounds");return 0;}
    VnEld16Encoder *encoder=NULL;uint8_t asc[VN_ELD16_MAX_ASC_BYTES];size_t size=0;
    if(vn_eld16_encoder_open(&encoder,asc,&size)!=0){
        io_error(env,"ELD system encoder ABI or profile unavailable");return 0;
    }
    (*env)->SetByteArrayRegion(env,configuration,0,(jsize)size,(const jbyte *)asc);
    jint count=(jint)size;(*env)->SetIntArrayRegion(env,length,0,1,&count);
    erase(asc,sizeof asc);
    if((*env)->ExceptionCheck(env)){vn_eld16_encoder_close(encoder);return 0;}
    return (jlong)(intptr_t)encoder;
}
JNIEXPORT jbyteArray JNICALL
Java_com_veloster_carplay_media_SystemEld16kEncoder_nativeEncode(JNIEnv *env,jclass ignored,
                                                                   jlong handle,jbyteArray pcm){
    (void)ignored;
    if(!handle||!pcm||(*env)->GetArrayLength(env,pcm)!=VN_ELD16_INPUT_BYTES){
        io_error(env,"ELD input PCM bounds");return NULL;
    }
    uint8_t input[VN_ELD16_INPUT_BYTES],au[VN_ELD16_MAX_ACCESS_UNIT];size_t length=0;
    (*env)->GetByteArrayRegion(env,pcm,0,VN_ELD16_INPUT_BYTES,(jbyte *)input);
    if((*env)->ExceptionCheck(env)){erase(input,sizeof input);return NULL;}
    int result=vn_eld16_encoder_push((VnEld16Encoder *)(intptr_t)handle,input,au,&length);
    erase(input,sizeof input);
    if(result!=0){erase(au,sizeof au);io_error(env,"ELD system encoder frame failed");return NULL;}
    if(length==0){erase(au,sizeof au);return NULL;}
    jbyteArray output=(*env)->NewByteArray(env,(jsize)length);
    if(output)(*env)->SetByteArrayRegion(env,output,0,(jsize)length,(const jbyte *)au);
    erase(au,sizeof au);return output;
}
JNIEXPORT void JNICALL
Java_com_veloster_carplay_media_SystemEld16kEncoder_nativeClose(JNIEnv *env,jclass ignored,jlong handle){
    (void)env;(void)ignored;
    vn_eld16_encoder_close((VnEld16Encoder *)(intptr_t)handle);
}
