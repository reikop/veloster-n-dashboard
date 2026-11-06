/* SPDX-License-Identifier: GPL-3.0-only. Dynamic system-codec boundary. */
#ifndef VN_ELD16K_SYSTEM_ENCODER_H
#define VN_ELD16K_SYSTEM_ENCODER_H
#include <stddef.h>
#include <stdint.h>

#define VN_ELD16_INPUT_FRAMES 480u
#define VN_ELD16_INPUT_BYTES 960u
#define VN_ELD16_MAX_ACCESS_UNIT 1416u
#define VN_ELD16_MAX_ASC_BYTES 64u

typedef struct VnEld16Encoder VnEld16Encoder;

/* Only an ABI-verified, runtime-probed system library is used. The caller
 * owns one encoder on one thread and the returned ASC. No FDK code is linked
 * or redistributed by this implementation. */
int vn_eld16_encoder_open(VnEld16Encoder **out,
                          uint8_t asc[VN_ELD16_MAX_ASC_BYTES],size_t *asc_len);
/* A successful call may produce zero bytes while the codec buffers input.
 * Each later nonzero output is one raw AAC-ELD access unit of 480 samples. */
int vn_eld16_encoder_push(VnEld16Encoder *encoder,
                          const uint8_t pcm_le[VN_ELD16_INPUT_BYTES],
                          uint8_t au[VN_ELD16_MAX_ACCESS_UNIT],size_t *au_len);
void vn_eld16_encoder_close(VnEld16Encoder *encoder);

#endif
