/*
 * Copyright (C) 2012-2017 Reece H. Dunn
 * Copyright (C) 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * This file contains the JNI bindings to eSpeak used by SpeechSynthesis.java.
 *
 * Android Version: 8.0 (Oreo)
 * API Version:     26
 *
 * Threading invariant: every espeak_Synth/Synchronize pair runs to completion
 * on its calling thread (single-flight). events->user_data is the caller's
 * local ref, so it must never outlive nativeSynthesize; keep it that way --
 * going async would require promoting it to a global ref.
 */

#include <stdio.h>
#include <unistd.h>
#include <stdlib.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <string.h>
#include <jni.h>

#include <espeak-ng/speak_lib.h>
#include <Log.h>

#define BUFFER_SIZE_IN_MILLISECONDS 80

/* These are helpers for converting a jstring to wchar_t*.
 *
 * This assumes that wchar_t is a 32-bit (UTF-32) value.
 */
//@{

static const char *utf8_read(const char *in, wchar_t *c)
{
	if (((uint8_t)*in) < 0x80)
		*c = *in++;
	else switch (((uint8_t)*in) & 0xF0)
	{
	default:
		*c = ((uint8_t)*in++) & 0x1F;
		*c = (*c << 6) + (((uint8_t)*in++) & 0x3F);
		break;
	case 0xE0:
		*c = ((uint8_t)*in++) & 0x0F;
		*c = (*c << 6) + (((uint8_t)*in++) & 0x3F);
		*c = (*c << 6) + (((uint8_t)*in++) & 0x3F);
		break;
	case 0xF0:
		*c = ((uint8_t)*in++) & 0x07;
		*c = (*c << 6) + (((uint8_t)*in++) & 0x3F);
		*c = (*c << 6) + (((uint8_t)*in++) & 0x3F);
		*c = (*c << 6) + (((uint8_t)*in++) & 0x3F);
		break;
	}
	return in;
}

static wchar_t *unicode_string(JNIEnv *env, jstring str)
{
  if (str == NULL) return NULL;

  const char *utf8 = (*env)->GetStringUTFChars(env, str, NULL);
  if (utf8 == NULL) return NULL;
  /* Modified UTF-8: size by byte length, not strlen, so an embedded
   * logical NUL (encoded 0xC0 0x80) can't truncate the allocation. */
  wchar_t *utf32 = (wchar_t *)malloc(((*env)->GetStringUTFLength(env, str) + 1) * sizeof(wchar_t));
  if (utf32 == NULL) {
    (*env)->ReleaseStringUTFChars(env, str, utf8);
    return NULL;
  }

  const char *utf8_current = utf8;
  wchar_t *utf32_current = utf32;
  while (*utf8_current)
  {
    utf8_current = utf8_read(utf8_current, utf32_current);
    ++utf32_current;
  }
  *utf32_current = 0;

  (*env)->ReleaseStringUTFChars(env, str, utf8);
  return utf32;
}

//@}

#define LOG_TAG "eSpeakService"
/* LOGV on every JNI entry used to ship in release builds (logcat spam plus
 * formatting cost on the synthesis path). NDEBUG is set for release CMake
 * configs, so verbose logging follows the build type automatically. */
#ifndef NDEBUG
#define DEBUG true
#else
#define DEBUG false
#endif

enum synthesis_result {
  SYNTH_CONTINUE = 0,
  SYNTH_ABORT = 1
};

static JavaVM *jvm = NULL;
jmethodID METHOD_nativeSynthCallback;
jmethodID METHOD_nativeSynthWordCallback;

/* Audio frames handed to the Java layer so far for the current request.
 * Reset by nativeSynthesize before each espeak_Synth call. */
static _Atomic int frames_delivered = 0;

/* Set by nativeStop from the framework's control thread while espeak_Synth is
 * still running on the synthesis thread.  espeak_ng_Cancel() cannot interrupt a
 * synthesis in progress -- its body is entirely #if USE_ASYNC, which this build
 * disables -- so returning SYNTH_ABORT from the callback is the only way to end
 * one early.  Without it a stop has to wait for the whole utterance to be
 * synthesized, which is silence for as long as the text the user just left. */
static atomic_int stop_requested;

static JNIEnv *getJniEnv() {
  JNIEnv *env = NULL;
  if ((*jvm)->GetEnv(jvm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) {
    return env;
  }
  /* Not attached (shouldn't normally happen -- the callback runs on the
   * nativeSynthesize caller's thread -- but attach rather than crash). */
  if ((*jvm)->AttachCurrentThread(jvm, &env, NULL) != JNI_OK) {
    return NULL;
  }
  return env;
}

/* A pending Java exception plus further JNI calls is undefined behaviour, so
 * every upcall site bails out through here instead of pressing on. The error
 * log keeps the abort visible in logcat; synthesis failures from it surface
 * on the Java side as a truncated/completed request, never a crash. */
static int check_jni_exception(JNIEnv *env) {
  if ((*env)->ExceptionCheck(env)) {
    if (DEBUG) LOGE("Clearing pending Java exception in synth callback");
    (*env)->ExceptionClear(env);
    return 1;
  }
  return 0;
}

/* Callback from espeak.  Should call back to the TTS API */
static int SynthCallback(short *audioData, int numSamples,
                         espeak_EVENT *events) {
  JNIEnv *env = getJniEnv();
  if (env == NULL || events == NULL || events->user_data == NULL) {
    return SYNTH_ABORT;
  }
  jobject object = (jobject)events->user_data;

  /* espeak marks the end of the request with a NULL buffer, not with a zero
   * sample count -- an empty buffer can legitimately occur mid-stream. */
  if (audioData == NULL || atomic_load(&stop_requested)) {
    /* Report completion either way: espeak returns ENS_SPEECH_STOPPED without
     * a final NULL-buffer callback when aborted, so this is the only place the
     * Java side hears that the request is over and can call done(). */
    (*env)->CallVoidMethod(env, object, METHOD_nativeSynthCallback, NULL);
    return SYNTH_ABORT;
  }

  for (espeak_EVENT *event = events;
       event->type != espeakEVENT_LIST_TERMINATED; ++event) {
    if (event->type != espeakEVENT_WORD)
      continue;

    /* event->sample is an absolute frame index within the request, but it is
     * recorded inside WavegenFill2() before libsonic compresses the buffer, so
     * at sonic-accelerated rates (wpm above espeakRATE_MAXIMUM) it can point
     * past the audio actually produced.  Clamping to the current buffer keeps
     * it exact while sonic is idle and bounded by one buffer when it is not. */
    const int delivered = atomic_load(&frames_delivered);
    int marker = event->sample;
    if (marker < delivered)
      marker = delivered;
    else if (marker > delivered + numSamples)
      marker = delivered + numSamples;

    (*env)->CallVoidMethod(env, object, METHOD_nativeSynthWordCallback,
                           (jint) event->text_position, (jint) event->length,
                           (jint) marker);
    if (check_jni_exception(env)) {
      return SYNTH_ABORT;
    }
  }

  if (numSamples > 0) {
    jbyteArray arrayAudioData = (*env)->NewByteArray(env, numSamples * 2);
    if (arrayAudioData == NULL) {
      return SYNTH_ABORT;
    }
    (*env)->SetByteArrayRegion(env, arrayAudioData, 0, (numSamples * 2), (jbyte *) audioData);
    if (check_jni_exception(env)) {
      (*env)->DeleteLocalRef(env, arrayAudioData);
      return SYNTH_ABORT;
    }
    (*env)->CallVoidMethod(env, object, METHOD_nativeSynthCallback, arrayAudioData);
    /* The callback runs many times per request without returning to Java, so
     * the local reference has to be released here or the table overflows. */
    (*env)->DeleteLocalRef(env, arrayAudioData);
    if (check_jni_exception(env)) {
      return SYNTH_ABORT;
    }
    atomic_fetch_add(&frames_delivered, numSamples);
  }

  return SYNTH_CONTINUE;
}

#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */

JNIEXPORT jint
JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
  jvm = vm;
  JNIEnv *env;

  if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
    LOGE("Failed to get the environment using GetEnv()");
    return -1;
  }

  return JNI_VERSION_1_6;
}

JNIEXPORT void
JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
  // Cleanup global references if any
  METHOD_nativeSynthCallback = NULL;
  METHOD_nativeSynthWordCallback = NULL;
}

JNIEXPORT jboolean
JNICALL Java_com_animeshahilya_espeakng_SpeechSynthesis_nativeClassInit(
    JNIEnv* env, jclass clazz) {
  if (DEBUG) LOGV("%s", __FUNCTION__);
  METHOD_nativeSynthCallback = (*env)->GetMethodID(env, clazz, "nativeSynthCallback", "([B)V");
  METHOD_nativeSynthWordCallback = (*env)->GetMethodID(env, clazz, "nativeSynthWordCallback", "(III)V");

  return JNI_TRUE;
}

static atomic_int s_sampleRate = 0;

JNIEXPORT jint
JNICALL Java_com_animeshahilya_espeakng_SpeechSynthesis_nativeCreate(
    JNIEnv *env, jobject object, jstring path) {
  if (DEBUG) LOGV("%s [env=%p, object=%p]", __FUNCTION__, env, object);

  const int cachedRate = atomic_load(&s_sampleRate);
  if (cachedRate > 0) {
    if (DEBUG) LOGV("Already initialized with sample rate %d", cachedRate);
    return cachedRate;
  }

  const char *c_path = path ? (*env)->GetStringUTFChars(env, path, NULL) : NULL;

  if (DEBUG) LOGV("Initializing with path %s", c_path ? c_path : "(null)");
  const int rate = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, BUFFER_SIZE_IN_MILLISECONDS, c_path, 0);

  if (c_path) (*env)->ReleaseStringUTFChars(env, path, c_path);
  if (rate > 0) {
    atomic_store(&s_sampleRate, rate);
  }

  return rate;
}

JNIEXPORT jobjectArray
JNICALL Java_com_animeshahilya_espeakng_SpeechSynthesis_nativeGetAvailableVoices(
    JNIEnv *env, jobject object) {
  if (DEBUG) LOGV("%s", __FUNCTION__);

  const espeak_VOICE **voices = espeak_ListVoices(NULL);
  if (voices == NULL) return NULL;

  int count;

  // First, count the number of voices returned.
  for (count = 0; voices[count] != NULL; count++);

  // Next, create a Java String array.
  jclass stringClass = (*env)->FindClass(env, "java/lang/String");
  if (stringClass == NULL) return NULL;
  jobjectArray voicesArray = (jobjectArray) (*env)->NewObjectArray(
      env, count * 4, stringClass, NULL);
  (*env)->DeleteLocalRef(env, stringClass);
  if (voicesArray == NULL) return NULL;

  const espeak_VOICE *v;
  char gender_buf[12];
  char age_buf[12];

  // Finally, populate the array. A NULL entry is legal (Java skips it), so
  // OOM on one string doesn't poison the whole voice list.
  for (int i = 0, voicesIndex = 0; (v = voices[i]) != NULL; i++) {
    const char *lang_name = (v->languages != NULL) ? v->languages + 1 : "";
    const char *identifier = (v->identifier != NULL) ? v->identifier : "";
    snprintf(gender_buf, sizeof(gender_buf), "%d", v->gender);
    snprintf(age_buf, sizeof(age_buf), "%d", v->age);

    jstring lang = (*env)->NewStringUTF(env, lang_name);
    (*env)->SetObjectArrayElement(env, voicesArray, voicesIndex++, lang);
    if (lang != NULL) (*env)->DeleteLocalRef(env, lang);
    jstring ident = (*env)->NewStringUTF(env, identifier);
    (*env)->SetObjectArrayElement(env, voicesArray, voicesIndex++, ident);
    if (ident != NULL) (*env)->DeleteLocalRef(env, ident);
    jstring gender = (*env)->NewStringUTF(env, gender_buf);
    (*env)->SetObjectArrayElement(env, voicesArray, voicesIndex++, gender);
    if (gender != NULL) (*env)->DeleteLocalRef(env, gender);
    jstring age = (*env)->NewStringUTF(env, age_buf);
    (*env)->SetObjectArrayElement(env, voicesArray, voicesIndex++, age);
    if (age != NULL) (*env)->DeleteLocalRef(env, age);
    if (check_jni_exception(env)) {
      return NULL;
    }
  }

  return voicesArray;
}

JNIEXPORT jboolean
JNICALL Java_com_animeshahilya_espeakng_SpeechSynthesis_nativeSetVoiceByName(
    JNIEnv *env, jobject object, jstring name) {
  const char *c_name = name ? (*env)->GetStringUTFChars(env, name, NULL) : NULL;

  if (DEBUG) LOGV("%s(name=%s)", __FUNCTION__, c_name ? c_name : "(null)");

  const espeak_ERROR result = espeak_SetVoiceByName(c_name);

  if (c_name) (*env)->ReleaseStringUTFChars(env, name, c_name);

  switch (result) {
    case EE_OK:             return JNI_TRUE;
    case EE_INTERNAL_ERROR: LOGE("espeak_SetVoiceByName: internal error."); break;
    case EE_BUFFER_FULL:    LOGE("espeak_SetVoiceByName: buffer full."); break;
    case EE_NOT_FOUND:      LOGE("espeak_SetVoiceByName: not found."); break;
  }

  return JNI_FALSE;
}

JNIEXPORT jboolean
JNICALL Java_com_animeshahilya_espeakng_SpeechSynthesis_nativeSetVoiceByProperties(
    JNIEnv *env, jobject object, jstring language, jint gender, jint age) {
  const char *c_language = language ? (*env)->GetStringUTFChars(env, language, NULL) : NULL;

  if (DEBUG) LOGV("%s(language=%s, gender=%d, age=%d)", __FUNCTION__, c_language ? c_language : "(null)", gender, age);

  espeak_VOICE voice_select;
  memset(&voice_select, 0, sizeof(espeak_VOICE));
  voice_select.languages = c_language;
  voice_select.gender = (int) gender;
  voice_select.age = (int) age;

  const espeak_ERROR result = espeak_SetVoiceByProperties(&voice_select);

  if (c_language) (*env)->ReleaseStringUTFChars(env, language, c_language);

  switch (result) {
    case EE_OK:             return JNI_TRUE;
    case EE_INTERNAL_ERROR: LOGE("espeak_SetVoiceByProperties: internal error."); break;
    case EE_BUFFER_FULL:    LOGE("espeak_SetVoiceByProperties: buffer full."); break;
    case EE_NOT_FOUND:      LOGE("espeak_SetVoiceByProperties: not found."); break;
  }

  return JNI_FALSE;
}

JNIEXPORT jboolean
JNICALL Java_com_animeshahilya_espeakng_SpeechSynthesis_nativeSetParameter(
    JNIEnv *env, jobject object, jint parameter, jint value) {
  if (DEBUG) LOGV("%s(parameter=%d, value=%d)", __FUNCTION__, parameter, value);
  const espeak_ERROR result = espeak_SetParameter((espeak_PARAMETER)parameter, (int)value, 0);

  switch (result) {
    case EE_OK:             return JNI_TRUE;
    case EE_INTERNAL_ERROR: LOGE("espeak_SetParameter: internal error."); break;
    case EE_BUFFER_FULL:    LOGE("espeak_SetParameter: buffer full."); break;
    case EE_NOT_FOUND:      LOGE("espeak_SetParameter: not found."); break;
  }

  return JNI_FALSE;
}

JNIEXPORT jint
JNICALL Java_com_animeshahilya_espeakng_SpeechSynthesis_nativeGetParameter(
    JNIEnv *env, jobject object, jint parameter, jint current) {
  if (DEBUG) LOGV("%s(parameter=%d, pitch=%d)", __FUNCTION__, parameter, current);
  return espeak_GetParameter((espeak_PARAMETER)parameter, (int)current);
}

JNIEXPORT jboolean
JNICALL Java_com_animeshahilya_espeakng_SpeechSynthesis_nativeSetPunctuationCharacters(
    JNIEnv *env, jobject object, jstring characters) {
  if (DEBUG) LOGV("%s)", __FUNCTION__);

  wchar_t *list = unicode_string(env, characters);
  if (list == NULL && characters != NULL) {
    return JNI_FALSE;
  }
  const espeak_ERROR result = espeak_SetPunctuationList(list);
  free(list);
  switch (result) {
    case EE_OK:             return JNI_TRUE;
    case EE_INTERNAL_ERROR: LOGE("espeak_SetPunctuationList: internal error."); break;
    case EE_BUFFER_FULL:    LOGE("espeak_SetPunctuationList: buffer full."); break;
    case EE_NOT_FOUND:      LOGE("espeak_SetPunctuationList: not found."); break;
  }

  return JNI_FALSE;
}

JNIEXPORT jboolean
JNICALL Java_com_animeshahilya_espeakng_SpeechSynthesis_nativeSynthesize(
    JNIEnv *env, jobject object, jstring text, jboolean isSsml) {
  if (DEBUG) LOGV("%s", __FUNCTION__);
  /* Copy the text off the Java string first: holding GetStringUTFChars
   * across the whole espeak_Synth + Synchronize pins the string and blocks
   * GC for the entire utterance. A null jstring synthesizes as empty. */
  char *c_text = NULL;
  jsize c_length = 0;
  if (text != NULL) {
    const char *pinned = (*env)->GetStringUTFChars(env, text, NULL);
    if (pinned == NULL) {
      return JNI_FALSE;
    }
    c_length = (*env)->GetStringUTFLength(env, text);
    c_text = (char *)malloc((size_t)c_length + 1);
    if (c_text == NULL) {
      (*env)->ReleaseStringUTFChars(env, text, pinned);
      return JNI_FALSE;
    }
    memcpy(c_text, pinned, (size_t)c_length);
    c_text[c_length] = '\0';
    (*env)->ReleaseStringUTFChars(env, text, pinned);
  }
  unsigned int unique_identifier;

  espeak_SetSynthCallback(SynthCallback);
  atomic_store(&frames_delivered, 0);
  atomic_store(&stop_requested, 0);
  const espeak_ERROR result = espeak_Synth(c_text, (size_t)c_length, 0,  // position
               POS_CHARACTER, 0, // end position (0 means no end position)
               isSsml ? espeakCHARS_UTF8 | espeakSSML // UTF-8 encoded SSML
                      : espeakCHARS_UTF8,             // UTF-8 encoded text
               &unique_identifier, object);
  espeak_Synchronize();

  free(c_text);

  switch (result) {
    case EE_OK:             return JNI_TRUE;
    case EE_INTERNAL_ERROR: LOGE("espeak_Synth: internal error."); break;
    case EE_BUFFER_FULL:    LOGE("espeak_Synth: buffer full."); break;
    case EE_NOT_FOUND:      LOGE("espeak_Synth: not found."); break;
  }

  return JNI_FALSE;
}

JNIEXPORT jboolean
JNICALL Java_com_animeshahilya_espeakng_SpeechSynthesis_nativeStop(
    JNIEnv *env, jobject object) {
  if (DEBUG) LOGV("%s", __FUNCTION__);
  atomic_store(&stop_requested, 1);
  espeak_Cancel();

  return JNI_TRUE;
}

#ifdef __cplusplus
}
#endif /* __cplusplus */
