# jni/jni/eSpeakService.c binds to com.animeshahilya.espeakng.SpeechSynthesis by exact
# class and member name: the 10 `native` methods via JNI's Java_com_animeshahilya_espeakng_
# SpeechSynthesis_* symbol naming, plus nativeSynthCallback(byte[])/nativeSynthWordCallback
# (int,int,int) via explicit GetMethodID() lookups in nativeClassInit() - those two are
# private Java methods invoked FROM native code, with no Java-side call site for R8 to see,
# so nothing marks them as used without this rule. Renaming, inlining, or stripping any
# member of this class breaks synthesis at runtime with no compile-time warning. See
# android/CLAUDE.md's "Release signing" section for why minification was off until this
# rule existed and was verified on a real device.
-keep class com.animeshahilya.espeakng.SpeechSynthesis {
    *;
}

# Release-variant instrumentation tests minify the test APK too; these are
# compile-time-only annotations on androidx.test classes, safe to ignore.
# (AGP-generated suggestion from minifyReleaseAndroidTestWithR8.)
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.MustBeClosed
