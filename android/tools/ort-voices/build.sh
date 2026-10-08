#!/bin/bash
# Builds libs/onnxruntime-voices-<version>.aar: ONNX Runtime for the natural
# voices only - arm64-v8a, the operators in voices_ops.config, no NNAPI,
# XNNPACK, WebGPU, ML ops or telemetry. Works on Windows (Git Bash), Linux and
# macOS hosts.
#
#   tools/ort-voices/build.sh <onnxruntime checkout at the app's version> <NDK dir>
#
# Use the NDK of Microsoft's own build of that version (1.30.0: NDK 27, clang
# 19). Pixel 8, SYSPIN fp16 decoder: this build, Microsoft's and an NDK 29
# build all ran at 2.7-3.2x real time (an apparent 10-15% gap was the phone
# heating up - alternate the order when comparing); a decoder session takes
# ~80 ms longer to create than with Microsoft's library.
#
# voices_ops.config lists the operators of every kind of voice model the app
# loads (Piper fp32 and INT8 weights, SYSPIN/Rasa Standard with fp16
# decoders, INT8 Compact, Kurmanji, Tashkeel), both as published and as ONNX
# Runtime optimized them on a Pixel 8 (ARM-only fused kernels such as
# NhwcFusedConv and QLinearConv appear only there). Regenerate it the same
# way after adding a new kind of model or changing the runtime version
# (onnxruntime/tools/python/create_reduced_build_config.py over both), or
# a voice fails to load with a missing kernel.
set -e
here="$(cd "$(dirname "$0")" && pwd)"
ort="$(cd "$1" && pwd)"
ndk="$2"
sdk="$(dirname "$(dirname "$ndk")")"
out="$here/build"
python "$ort/tools/ci_build/github/android/build_aar_package.py" \
  --build_dir "$out" --android_sdk_path "$sdk" --android_ndk_path "$ndk" \
  --include_ops_by_config "$here/voices_ops.config" \
  --config Release "$here/voices_aar_build_settings.json" || true
# The packaging step after the per-ABI build symlinks files, which Windows
# refuses without Developer Mode; the per-ABI AAR it would repackage is
# complete for one ABI (both .so files and the Java API), so it is used as is.
aar="$out/intermediates/arm64-v8a/Release/java/build/android/outputs/aar/onnxruntime-release.aar"
version="$(cat "$ort/VERSION_NUMBER")"
cp "$aar" "$here/../../libs/onnxruntime-voices-$version.aar"
echo "libs/onnxruntime-voices-$version.aar"
