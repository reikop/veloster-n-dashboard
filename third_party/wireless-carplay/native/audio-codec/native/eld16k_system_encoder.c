/* SPDX-License-Identifier: GPL-3.0-only. No FDK source or binary is bundled. */
#include "eld16k_system_encoder.h"
#include <dlfcn.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#ifdef VN_ELD16_DIAGNOSTIC
#define REFUSE(stage) do{fprintf(stderr,"ELD16 probe refused at %s\n",stage);goto fail;}while(0)
#else
#define REFUSE(stage) goto fail
#endif

/* Minimal ABI facts checked against pinned AOSP jb-mr1 FDK headers and the
 * actual API17 system library. These definitions contain no codec algorithm.
 * Runtime initialization rejects missing symbols, wrong image location,
 * unexpected ASC, channel count, frame length, or partial input consumption. */
typedef struct {
    unsigned maxOutBufBytes,maxAncBytes,inBufFillLevel,inputChannels;
    unsigned frameLength,encoderDelay;
    unsigned char confBuf[64];unsigned confSize;
} VnFdkInfo;
typedef struct {int numBufs;void **bufs;int *identifiers,*sizes,*elementSizes;} VnFdkBuffers;
typedef struct {int numInSamples,numAncBytes;} VnFdkInput;
typedef struct {int numOutBytes,numInSamples,numAncBytes;} VnFdkOutput;
typedef char vn_fdk_info_size[(sizeof(VnFdkInfo)==92)?1:-1];
typedef char vn_fdk_buffer_size[(sizeof(VnFdkBuffers)==20)?1:-1];
typedef char vn_fdk_input_size[(sizeof(VnFdkInput)==8)?1:-1];
typedef char vn_fdk_output_size[(sizeof(VnFdkOutput)==12)?1:-1];

struct VnEld16Encoder {
    void *library,*handle;
    int (*set)(void *,int,unsigned);
    int (*encode)(void *,const VnFdkBuffers *,const VnFdkBuffers *,const VnFdkInput *,VnFdkOutput *);
    int (*close)(void **);
};
static void erase(void *pointer,size_t length){
    volatile unsigned char *bytes=(volatile unsigned char *)pointer;
    while(length--)*bytes++=0;
}
static unsigned read_bits(const unsigned char *data,unsigned start,unsigned count){
    unsigned value=0;
    for(unsigned i=0;i<count;i++){unsigned bit=start+i;value=(value<<1)|((data[bit/8]>>(7-bit%8))&1u);}
    return value;
}
static int symbol_in_system_image(const void *symbol){
    FILE *maps=fopen("/proc/self/maps","r");if(!maps)return 0;
    char line[512],permissions[5],path[256];int valid=0;
    uintptr_t address=(uintptr_t)symbol;
    while(fgets(line,sizeof line,maps)){
        unsigned long first=0,last=0;permissions[0]=0;path[0]=0;
        if(sscanf(line,"%lx-%lx %4s %*s %*s %*s %255s",&first,&last,permissions,path)!=4)continue;
        if(address>=(uintptr_t)first&&address<(uintptr_t)last){
#ifdef VN_ELD16_DIAGNOSTIC
            fprintf(stderr,"ELD16 mapped image: %s\n",path);
#endif
            valid=strchr(permissions,'x')!=NULL&&
                strcmp(path,"/system/lib/libstagefright_soft_aacenc.so")==0;
            break;
        }
    }
    fclose(maps);return valid;
}
static int valid_asc(const VnFdkInfo *info){
    static const unsigned char expected[]={0xf8,0xf0,0x30,0x00};
    if(info->confSize!=sizeof expected||
       memcmp(info->confBuf,expected,sizeof expected)!=0)return 0;
    unsigned aot=read_bits(info->confBuf,0,5),position=5;
    if(aot==31){aot=32+read_bits(info->confBuf,position,6);position+=6;}
    return aot==39&&read_bits(info->confBuf,position,4)==8&&
           read_bits(info->confBuf,position+4,4)==1;
}
void vn_eld16_encoder_close(VnEld16Encoder *encoder){
    if(!encoder)return;
    if(encoder->handle&&encoder->close)encoder->close(&encoder->handle);
    if(encoder->library)dlclose(encoder->library);
    erase(encoder,sizeof *encoder);free(encoder);
}
int vn_eld16_encoder_open(VnEld16Encoder **out,
                          uint8_t asc[VN_ELD16_MAX_ASC_BYTES],size_t *asc_len){
    if(!out||!asc||!asc_len)return -1;
    *out=NULL;*asc_len=0;memset(asc,0,VN_ELD16_MAX_ASC_BYTES);
    VnEld16Encoder *value=(VnEld16Encoder *)calloc(1,sizeof *value);
    if(!value)return -1;
    value->library=dlopen("/system/lib/libstagefright_soft_aacenc.so",RTLD_NOW|RTLD_LOCAL);
    if(!value->library)REFUSE("dlopen");
    int (*open_fn)(void **,unsigned,unsigned)=dlsym(value->library,"aacEncOpen");
    int (*info_fn)(void *,VnFdkInfo *)=dlsym(value->library,"aacEncInfo");
    value->set=dlsym(value->library,"aacEncoder_SetParam");
    value->encode=dlsym(value->library,"aacEncEncode");
    value->close=dlsym(value->library,"aacEncClose");
    if(!open_fn||!info_fn||!value->set||!value->encode||!value->close)REFUSE("symbols");
    Dl_info location;
    if(!dladdr((void *)open_fn,&location)||!location.dli_fname)REFUSE("image path lookup");
#ifdef VN_ELD16_DIAGNOSTIC
    fprintf(stderr,"ELD16 codec image: %s\n",location.dli_fname);
#endif
    const char *name=strrchr(location.dli_fname,'/');
    name=name?name+1:location.dli_fname;
    if(strcmp(name,"libstagefright_soft_aacenc.so")!=0||
       !symbol_in_system_image((void *)open_fn))REFUSE("image path");
    if(open_fn(&value->handle,0,1)!=0||!value->handle)REFUSE("open");
#define SET(parameter,setting) if(value->set(value->handle,parameter,setting)!=0)REFUSE(#parameter)
    SET(0x0100,39); /* AOT AAC-ELD. */
    SET(0x0103,16000); /* Native mono capture rate. */
    SET(0x0101,48000); /* Low latency bitrate ceiling for <=24 kHz. */
    SET(0x0106,1); /* One channel. */
    SET(0x0105,480); /* 30 ms at 16 kHz. */
    SET(0x0300,0); /* Raw access units, no ADTS/LATM wrapper. */
#undef SET
    if(value->encode(value->handle,NULL,NULL,NULL,NULL)!=0)REFUSE("init");
    VnFdkInfo info;memset(&info,0,sizeof info);
    int ok=info_fn(value->handle,&info)==0&&info.frameLength==480&&
           info.inputChannels==1&&valid_asc(&info);
    if(ok){memcpy(asc,info.confBuf,info.confSize);*asc_len=info.confSize;}
    erase(&info,sizeof info);
    if(!ok)REFUSE("info/ASC");
    *out=value;return 0;
fail:
    vn_eld16_encoder_close(value);return -1;
}
int vn_eld16_encoder_push(VnEld16Encoder *encoder,
                          const uint8_t pcm_le[VN_ELD16_INPUT_BYTES],
                          uint8_t au[VN_ELD16_MAX_ACCESS_UNIT],size_t *au_len){
    if(!encoder||!encoder->handle||!pcm_le||!au||!au_len)return -1;
    *au_len=0;memset(au,0,VN_ELD16_MAX_ACCESS_UNIT);
    short pcm[VN_ELD16_INPUT_FRAMES];
    for(unsigned i=0;i<VN_ELD16_INPUT_FRAMES;i++)
        pcm[i]=(short)((unsigned)pcm_le[2*i]|((unsigned)pcm_le[2*i+1]<<8));
    void *input=&pcm[0],*output=au;
    int input_id=0,output_id=3,input_size=(int)sizeof pcm,
        output_size=VN_ELD16_MAX_ACCESS_UNIT,input_element=sizeof(short),output_element=1;
    VnFdkBuffers in={1,&input,&input_id,&input_size,&input_element};
    VnFdkBuffers out={1,&output,&output_id,&output_size,&output_element};
    VnFdkInput args={VN_ELD16_INPUT_FRAMES,0};VnFdkOutput result;memset(&result,0,sizeof result);
    int status=encoder->encode(encoder->handle,&in,&out,&args,&result);
    erase(pcm,sizeof pcm);
    if(status!=0||result.numInSamples!=(int)VN_ELD16_INPUT_FRAMES||
       result.numOutBytes<0||result.numOutBytes>(int)VN_ELD16_MAX_ACCESS_UNIT){
        memset(au,0,VN_ELD16_MAX_ACCESS_UNIT);return -1;
    }
    *au_len=(size_t)result.numOutBytes;return 0;
}
