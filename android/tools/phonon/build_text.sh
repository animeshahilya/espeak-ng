#!/bin/bash
# Builds libs/phonon-jni/arm64-v8a/libptts_text.so: Phonon's text front end
# (normalization, chunking, tokenizer) from Gradium's xn-ptts, MIT OR Apache-2.0.
# The C++ runner it serves is vendored in jni/phonon (same release).
#
#   tools/phonon/build_text.sh <xn-ptts checkout at v0.4.0> <NDK dir>
#
# Needs Rust with the aarch64-linux-android target and cargo-ndk.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
src="$(cd "$1" && pwd)/qnn/text-ffi"
export ANDROID_NDK_HOME="$2"
unset RUSTFLAGS CARGO_ENCODED_RUSTFLAGS
rustup target add aarch64-linux-android >/dev/null
(cd "$src" && cargo ndk -t arm64-v8a -P 27 build --release --locked)
mkdir -p "$here/../../libs/phonon-jni/arm64-v8a"
cp "$src/target/aarch64-linux-android/release/libptts_text.so" "$here/../../libs/phonon-jni/arm64-v8a/"
echo "libs/phonon-jni/arm64-v8a/libptts_text.so"
