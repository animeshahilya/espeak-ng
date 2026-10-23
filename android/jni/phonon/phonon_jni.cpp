/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

// JNI for com.animeshahilya.espeakng.piper.Phonon: Gradium's Phonon on the
// Snapdragon NPU through QNN (the other files here are their runner,
// xn-ptts v0.4.0 qnn/runner, MIT OR Apache-2.0).

#include <jni.h>

#include <exception>
#include <string>
#include <vector>

#include "phonon.hpp"

namespace {

std::string str(JNIEnv* env, jstring s) {
  if (s == nullptr) return {};
  const char* c = env->GetStringUTFChars(s, nullptr);
  std::string out(c != nullptr ? c : "");
  if (c != nullptr) env->ReleaseStringUTFChars(s, c);
  return out;
}

void throwIo(JNIEnv* env, const char* what) {
  jclass io = env->FindClass("java/io/IOException");
  if (io != nullptr) env->ThrowNew(io, what);
}

phonon::Phonon* model(jlong handle) { return reinterpret_cast<phonon::Phonon*>(handle); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_animeshahilya_espeakng_piper_Phonon_nativeLoad(
    JNIEnv* env, jclass, jstring bundle, jstring soc, jstring libDir, jstring textLibrary, jstring lang) {
  try {
    phonon::Options o;
    o.bundle_dir = str(env, bundle);
    o.soc_model = str(env, soc);
    o.lib_dir = str(env, libDir);
    o.text_library = str(env, textLibrary);
    o.lang = str(env, lang);
    o.backend = "htp";
    return reinterpret_cast<jlong>(new phonon::Phonon(o));
  } catch (const std::exception& e) {
    throwIo(env, e.what());
    return 0;
  }
}

JNIEXPORT jobjectArray JNICALL Java_com_animeshahilya_espeakng_piper_Phonon_nativeVoices(
    JNIEnv* env, jclass, jlong handle) {
  const std::vector<std::string> voices = model(handle)->voices();
  jobjectArray out = env->NewObjectArray(static_cast<jsize>(voices.size()),
                                         env->FindClass("java/lang/String"), nullptr);
  for (size_t i = 0; i < voices.size(); i++) {
    jstring s = env->NewStringUTF(voices[i].c_str());
    env->SetObjectArrayElement(out, static_cast<jsize>(i), s);
    env->DeleteLocalRef(s);
  }
  return out;
}

JNIEXPORT jint JNICALL Java_com_animeshahilya_espeakng_piper_Phonon_nativeSampleRate(
    JNIEnv*, jclass, jlong handle) {
  return model(handle)->sample_rate();
}

// Hands each 80 ms frame to sink.frame(float[]) as it is made; false from it,
// or stop.stopped() (polled every frame), ends the text early. True when the
// whole text was said.
JNIEXPORT jboolean JNICALL Java_com_animeshahilya_espeakng_piper_Phonon_nativeSynthesize(
    JNIEnv* env, jclass, jlong handle, jstring text, jstring voice, jobject stop, jobject sink) {
  jmethodID stopped = env->GetMethodID(env->GetObjectClass(stop), "stopped", "()Z");
  jmethodID frame = env->GetMethodID(env->GetObjectClass(sink), "frame", "([F)Z");
  try {
    phonon::Stats stats;
    model(handle)->synthesize(
        str(env, text), str(env, voice),
        [&](const float* pcm, size_t n) {
          jfloatArray out = env->NewFloatArray(static_cast<jsize>(n));
          if (out == nullptr) return false;
          env->SetFloatArrayRegion(out, 0, static_cast<jsize>(n), pcm);
          const bool more = env->CallBooleanMethod(sink, frame, out) == JNI_TRUE;
          env->DeleteLocalRef(out);
          return more && !env->ExceptionCheck();
        },
        &stats,
        [&]() { return env->ExceptionCheck() || env->CallBooleanMethod(stop, stopped) == JNI_TRUE; },
        false);
    return stats.cancelled ? JNI_FALSE : JNI_TRUE;
  } catch (const std::exception& e) {
    if (!env->ExceptionCheck()) throwIo(env, e.what());
    return JNI_FALSE;
  }
}

JNIEXPORT void JNICALL Java_com_animeshahilya_espeakng_piper_Phonon_nativeFree(JNIEnv*, jclass, jlong handle) {
  delete model(handle);
}

}  // extern "C"
