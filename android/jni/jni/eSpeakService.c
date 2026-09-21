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

/* Converts a Java jstring (UTF-16) to wchar_t* (UTF-32).
 *
 * Direct UTF-16 decoding handles surrogate pairs properly and avoids
 * double-conversion via Modified UTF-8 or out-of-bounds pointer reads
 * on malformed or truncated byte sequences.
 */
//@{

static wchar_t *unicode_string(JNIEnv *env, jstring str)
{
  if (str == NULL) return NULL;

  const jsize len = (*env)->GetStringLength(env, str);
  const jchar *chars = (*env)->GetStringChars(env, str, NULL);
  if (chars == NULL) return NULL;

  wchar_t *utf32 = (wchar_t *)malloc(((size_t)len + 1) * sizeof(wchar_t));
  if (utf32 == NULL) {
    (*env)->ReleaseStringChars(env, str, chars);
    return NULL;
  }

  jsize src = 0;
  size_t dst = 0;
  while (src < len) {
    jchar c = chars[src++];
    if (c >= 0xD800 && c <= 0xDBFF && src < len) {
      jchar low = chars[src];
      if (low >= 0xDC00 && low <= 0xDFFF) {
        src++;
        utf32[dst++] = (wchar_t)(((c - 0xD800) << 10) + (low - 0xDC00) + 0x10000);
        continue;
      }
    }
    utf32[dst++] = (wchar_t)c;
  }
  utf32[dst] = 0;

  (*env)->ReleaseStringChars(env, str, chars);
  return utf32;
}

/* Helper to free unicode_string result */
static void free_unicode_string(wchar_t *str)
{
  if (str) free(str);
}

/* Converts a Java jstring (UTF-16) to standard RFC 3629 UTF-8 char*.
 *
 * Unlike JNI's GetStringUTFChars, which produces Modified UTF-8 (encoding
 * supplementary code points U+10000+ as pairs of 3-byte surrogate sequences,
 * and \0 as 0xC0 0x80), this produces standard UTF-8 with true 4-byte
 * sequences for astral plane code points, exactly as expected by eSpeak's utf8_in.
 * Releases the pinned jchar buffer immediately upon transcoding.
 */
static char *utf16_to_utf8(JNIEnv *env, jstring str, size_t *out_len)
{
  if (out_len) *out_len = 0;
  if (str == NULL) return NULL;

  const jsize len = (*env)->GetStringLength(env, str);
  const jchar *chars = (*env)->GetStringChars(env, str, NULL);
  if (chars == NULL) return NULL;

  // First pass: compute exact standard UTF-8 byte count
  size_t utf8_len = 0;
  for (jsize i = 0; i < len; i++) {
    jchar c = chars[i];
    if (c < 0x80) {
      utf8_len += 1;
    } else if (c < 0x800) {
      utf8_len += 2;
    } else if (c >= 0xD800 && c <= 0xDBFF && (i + 1) < len &&
               chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
      utf8_len += 4;
      i++;
    } else {
      utf8_len += 3;
    }
  }

  char *utf8 = (char *)malloc(utf8_len + 1);
  if (utf8 == NULL) {
    (*env)->ReleaseStringChars(env, str, chars);
    return NULL;
  }

  // Second pass: encode standard UTF-8
  size_t dst = 0;
  for (jsize i = 0; i < len; i++) {
    jchar c = chars[i];
    if (c < 0x80) {
      utf8[dst++] = (char)c;
    } else if (c < 0x800) {
      utf8[dst++] = (char)(0xC0 | (c >> 6));
      utf8[dst++] = (char)(0x80 | (c & 0x3F));
    } else if (c >= 0xD800 && c <= 0xDBFF && (i + 1) < len &&
               chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
      uint32_t cp = (((uint32_t)(c - 0xD800) << 10) | (chars[i + 1] - 0xDC00)) + 0x10000;
      i++;
      utf8[dst++] = (char)(0xF0 | (cp >> 18));
      utf8[dst++] = (char)(0x80 | ((cp >> 12) & 0x3F));
      utf8[dst++] = (char)(0x80 | ((cp >> 6) & 0x3F));
      utf8[dst++] = (char)(0x80 | (cp & 0x3F));
    } else {
      utf8[dst++] = (char)(0xE0 | (c >> 12));
      utf8[dst++] = (char)(0x80 | ((c >> 6) & 0x3F));
      utf8[dst++] = (char)(0x80 | (c & 0x3F));
    }
  }
  utf8[dst] = '\0';

  (*env)->ReleaseStringChars(env, str, chars);
  if (out_len) *out_len = dst;
  return utf8;
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
static jmethodID METHOD_nativeSynthCallback;
static jmethodID METHOD_nativeSynthWordCallback;

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

/* Generation counter: each nativeSynthesize call increments this.
 * The callback checks the generation it was started with; if nativeStop()
 * is called, it increments the generation, invalidating the running synthesis.
 * This avoids the race where nativeStop() sets the old boolean flag, then
 * a new nativeSynthesize() immediately clears it, leaving the old callback
 * still running without seeing the stop. */
static atomic_int stop_generation;
static atomic_int current_synthesis_generation;

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
  if ((*env)->EnsureLocalCapacity(env, 16) != 0) {
    return SYNTH_ABORT;
  }
  jobject object = (jobject)events->user_data;

  /* If an abort was requested (stop_generation bumped), notify Java and return SYNTH_ABORT
   * to immediately halt synthesis in libespeak-ng. */
  if (atomic_load(&stop_generation) != atomic_load(&current_synthesis_generation)) {
    (*env)->CallVoidMethod(env, object, METHOD_nativeSynthCallback, NULL);
    if (check_jni_exception(env)) {
      return SYNTH_ABORT;
    }
    return SYNTH_ABORT;
  }

  /* espeak marks the end of the request with a NULL buffer, not with a zero
   * sample count -- an empty buffer can legitimately occur mid-stream.
   * Notify Java of completion and return SYNTH_CONTINUE (0) so espeak_Synth
   * completes cleanly with EE_OK. */
  if (audioData == NULL) {
    (*env)->CallVoidMethod(env, object, METHOD_nativeSynthCallback, NULL);
    if (check_jni_exception(env)) {
      return SYNTH_ABORT;
    }
    return SYNTH_CONTINUE;
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
  if (METHOD_nativeSynthCallback == NULL || METHOD_nativeSynthWordCallback == NULL) {
    LOGE("nativeClassInit: GetMethodID failed (callback methods not found)");
    return JNI_FALSE;
  }

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
  if (path != NULL && c_path == NULL) return 0; // JNI OOM: exception pending

  if (DEBUG) LOGV("Initializing with path %s", c_path ? c_path : "(null)");
  const int rate = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, BUFFER_SIZE_IN_MILLISECONDS, c_path, 0);

  if (c_path) (*env)->ReleaseStringUTFChars(env, path, c_path);
  if (rate > 0) {
    atomic_store(&s_sampleRate, rate);
    return rate;
  }

  /* JNI contract: strictly positive sample rate on success, 0 on failure.
   * espeak_Initialize reports failure as -1 (EE_INTERNAL_ERROR); never leak
   * that through, Java treats any value <= 0 as "not initialized". */
  if (DEBUG) LOGV("espeak_Initialize failed with rate %d", rate);
  return 0;
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
  // OOM on one string doesn't poison the whole voice list. Each NewStringUTF
  // is checked for a pending exception right away, before any further JNI
  // call touches it - calling SetObjectArrayElement (or another
  // NewStringUTF) while an exception is pending is undefined behavior per
  // the JNI spec, even though the value being stored would be NULL.
  for (int i = 0, voicesIndex = 0; (v = voices[i]) != NULL; i++) {
    const char *lang_name = (v->languages != NULL && v->languages[0] != '\0') ? v->languages + 1 : "";
    const char *identifier = (v->identifier != NULL) ? v->identifier : "";
    snprintf(gender_buf, sizeof(gender_buf), "%d", v->gender);
    snprintf(age_buf, sizeof(age_buf), "%d", v->age);

    jstring lang = (*env)->NewStringUTF(env, lang_name);
    if (check_jni_exception(env)) return NULL;
    (*env)->SetObjectArrayElement(env, voicesArray, voicesIndex++, lang);
    if (lang != NULL) (*env)->DeleteLocalRef(env, lang);

    jstring ident = (*env)->NewStringUTF(env, identifier);
    if (check_jni_exception(env)) return NULL;
    (*env)->SetObjectArrayElement(env, voicesArray, voicesIndex++, ident);
    if (ident != NULL) (*env)->DeleteLocalRef(env, ident);

    jstring gender = (*env)->NewStringUTF(env, gender_buf);
    if (check_jni_exception(env)) return NULL;
    (*env)->SetObjectArrayElement(env, voicesArray, voicesIndex++, gender);
    if (gender != NULL) (*env)->DeleteLocalRef(env, gender);

    jstring age = (*env)->NewStringUTF(env, age_buf);
    if (check_jni_exception(env)) return NULL;
    (*env)->SetObjectArrayElement(env, voicesArray, voicesIndex++, age);
    if (age != NULL) (*env)->DeleteLocalRef(env, age);
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
  if (characters == NULL) return JNI_FALSE;

  wchar_t *list = unicode_string(env, characters);
  if (list == NULL && characters != NULL) {
    return JNI_FALSE;
  }
  const espeak_ERROR result = espeak_SetPunctuationList(list);
  free_unicode_string(list);
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
  /* Transcode Java UTF-16 directly into standard UTF-8. Unlike GetStringUTFChars
   * (which returns Modified UTF-8, splitting astral plane characters U+10000+
   * into surrogate pairs), utf16_to_utf8 encodes true 4-byte UTF-8 sequences
   * and unpins the Java string characters immediately before the synchronous
   * espeak_Synth call runs. A null jstring synthesizes as empty. */
  char *c_text = NULL;
  size_t c_length = 0;
  if (text != NULL) {
    c_text = utf16_to_utf8(env, text, &c_length);
    if (c_text == NULL) {
      return JNI_FALSE;
    }
  }
  unsigned int unique_identifier;

  espeak_SetSynthCallback(SynthCallback);
  atomic_store(&frames_delivered, 0);
  int current_generation = atomic_fetch_add(&stop_generation, 1) + 1;
  atomic_store(&current_synthesis_generation, current_generation);
  const char *synth_input = c_text ? c_text : "";
  /* espeakPHONEMES is safe for non-SSML text: it only makes espeak recognize
   * the [[ ]] Kirshenbaum-phoneme escape when that literal sequence appears,
   * which is exactly why the Java layer (TtsService.sanitizeText's "NVDA
   * eSpeak driver fix") splits any incidental "[[" in ordinary text into
   * "[ [" first - needed so UserDictionary phoneme-override entries (built
   * as "[[ ... ]]") actually take effect instead of being read as literal
   * bracket characters.
   *
   * Gated on !isSsml, though, because that Java-side escaping - and
   * sanitizeForWatchdog's bracket handling - both explicitly skip SSML text
   * ("bracket runs are left intact, so SSML markup is never mangled", by
   * TtsService's own doc comment): an SSML <speak> body whose text content
   * legitimately contains a literal "[[" was previously spoken as-is, and
   * unconditionally enabling this flag here would make espeak reinterpret
   * that text as entering phoneme mode with no closing "]]" ever escaped
   * for it, corrupting the rest of the utterance. UserDictionary phoneme
   * injection only ever runs on the non-SSML path (TtsService gates
   * applyRules/applyCharacterRule on !isSsml too), so SSML callers never
   * needed this flag on in the first place. */
  const espeak_ERROR result = espeak_Synth(synth_input, (size_t)c_length, 0,  // position
               POS_CHARACTER, 0, // end position (0 means no end position)
               isSsml ? espeakCHARS_UTF8 | espeakSSML                // UTF-8 encoded SSML
                      : espeakCHARS_UTF8 | espeakPHONEMES,           // UTF-8 encoded text
               &unique_identifier, object);
  espeak_Synchronize();

  free(c_text);

  if (result == EE_OK) {
    return JNI_TRUE;
  }

  if (atomic_load(&stop_generation) != atomic_load(&current_synthesis_generation)) {
    if (DEBUG) LOGV("espeak_Synth: stopped early");
    return JNI_TRUE;
  }

  switch (result) {
    case EE_INTERNAL_ERROR: LOGE("espeak_Synth: internal error."); break;
    case EE_BUFFER_FULL:    LOGE("espeak_Synth: buffer full."); break;
    case EE_NOT_FOUND:      LOGE("espeak_Synth: not found."); break;
    default: break;
  }

  return JNI_FALSE;
}

JNIEXPORT jboolean
JNICALL Java_com_animeshahilya_espeakng_SpeechSynthesis_nativeStop(
    JNIEnv *env, jobject object) {
  if (DEBUG) LOGV("%s", __FUNCTION__);
  atomic_fetch_add(&stop_generation, 1);
  espeak_Cancel();

  return JNI_TRUE;
}

JNIEXPORT void
JNICALL Java_com_animeshahilya_espeakng_SpeechSynthesis_nativeTerminate(
    JNIEnv *env, jobject object) {
  if (DEBUG) LOGV("%s", __FUNCTION__);
  espeak_Terminate();
  atomic_store(&s_sampleRate, 0);
}

#ifdef __cplusplus
}
#endif /* __cplusplus */
