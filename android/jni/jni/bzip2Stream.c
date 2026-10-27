/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

/*
 * bzip2 decoding for util/Bzip2InputStream (natural voices imported as
 * .tar.bz2). Java feeds compressed bytes and takes decoded ones; the stream
 * state lives here. Concatenated streams (what pbzip2 writes) are read on
 * as one. Pixel 8, a first import of a 64 MB voice archive (release build):
 * 9 s in all; with a pure-Java decoder 65 s, as ART had not compiled it yet.
 */

#include <android/log.h>
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "bzlib.h"

/* Built with BZ_NO_STDIO, the library reports its own internal faults (never
 * bad input, which BZ2_bzDecompress returns as an error) here. */
void bz_internal_error(int errcode) {
  __android_log_print(ANDROID_LOG_ERROR, "Bzip2InputStream", "bzip2 internal error %d", errcode);
  abort();
}

typedef struct {
  bz_stream stream;
  int ended; /* the current stream reached its end marker */
} Decoder;

JNIEXPORT jlong JNICALL
Java_com_animeshahilya_espeakng_util_Bzip2InputStream_nativeOpen(JNIEnv *env, jclass clazz) {
  Decoder *d = calloc(1, sizeof(Decoder));
  if (d == NULL) return 0;
  if (BZ2_bzDecompressInit(&d->stream, 0, 0) != BZ_OK) {
    free(d);
    return 0;
  }
  return (jlong)(intptr_t)d;
}

/*
 * Decodes from in[inOff, inOff + inLen) into out[outOff, outOff + outLen).
 * used[0] = input bytes consumed. Returns the bytes written, -1 at the end of
 * a stream with no input left to start another, -2 on corrupt data.
 */
JNIEXPORT jint JNICALL
Java_com_animeshahilya_espeakng_util_Bzip2InputStream_nativeDecode(
    JNIEnv *env, jclass clazz, jlong handle, jbyteArray in, jint inOff, jint inLen,
    jbyteArray out, jint outOff, jint outLen, jintArray used) {
  Decoder *d = (Decoder *)(intptr_t)handle;
  if (d == NULL) return -2;
  if (d->ended) {
    if (inLen == 0) return -1;
    /* Another stream follows: start it. */
    BZ2_bzDecompressEnd(&d->stream);
    memset(&d->stream, 0, sizeof(d->stream));
    if (BZ2_bzDecompressInit(&d->stream, 0, 0) != BZ_OK) return -2;
    d->ended = 0;
  }
  jbyte *src = (*env)->GetPrimitiveArrayCritical(env, in, NULL);
  jbyte *dst = src == NULL ? NULL : (*env)->GetPrimitiveArrayCritical(env, out, NULL);
  if (dst == NULL) {
    if (src != NULL) (*env)->ReleasePrimitiveArrayCritical(env, in, src, JNI_ABORT);
    return -2;
  }
  d->stream.next_in = (char *)src + inOff;
  d->stream.avail_in = (unsigned int)inLen;
  d->stream.next_out = (char *)dst + outOff;
  d->stream.avail_out = (unsigned int)outLen;
  const int rc = BZ2_bzDecompress(&d->stream);
  const jint consumed = inLen - (jint)d->stream.avail_in;
  const jint produced = outLen - (jint)d->stream.avail_out;
  (*env)->ReleasePrimitiveArrayCritical(env, out, dst, 0);
  (*env)->ReleasePrimitiveArrayCritical(env, in, src, JNI_ABORT);
  (*env)->SetIntArrayRegion(env, used, 0, 1, &consumed);
  if (rc == BZ_STREAM_END) {
    d->ended = 1;
  } else if (rc != BZ_OK) {
    return -2;
  }
  return produced;
}

JNIEXPORT void JNICALL
Java_com_animeshahilya_espeakng_util_Bzip2InputStream_nativeClose(JNIEnv *env, jclass clazz,
                                                                  jlong handle) {
  Decoder *d = (Decoder *)(intptr_t)handle;
  if (d != NULL) {
    BZ2_bzDecompressEnd(&d->stream);
    free(d);
  }
}
