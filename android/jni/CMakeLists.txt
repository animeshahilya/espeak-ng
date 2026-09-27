cmake_minimum_required(VERSION 3.16)

project(espeak-android)

# Ensure 16 KB page-size alignment for Android 14/15 ARM64 kernels.
# Scoped to Android so host helper targets never inherit it.
if(ANDROID)
  add_link_options("-Wl,-z,max-page-size=16384")
endif()
# Release only: an unconditional -O3 also applied to debug builds, making
# native breakpoints/variable inspection unreliable during development.
add_compile_options("$<$<CONFIG:Release>:-O3>")
# Release only: enable thin LTO for smaller, faster native code
add_compile_options("$<$<CONFIG:Release>:-flto=thin>")
add_link_options("$<$<CONFIG:Release>:-flto=thin>")

# Build libsonic from vendored source (src/sonic) for Android and pre-seed
# the find results so cmake/deps.cmake in the main project detects it (the NDK sysroot has no sonic).
set(SONIC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../src/sonic")
add_library(sonic OBJECT "${SONIC_DIR}/sonic.c")
# enable PIC in order to embed libsonic in libttsespeak.so
set_property(TARGET sonic PROPERTY POSITION_INDEPENDENT_CODE ON)
target_include_directories(sonic PUBLIC "${SONIC_DIR}")
set(SONIC_LIB sonic)
set(SONIC_INC "${SONIC_DIR}")

# Android-only fork: the NDK build needs just libttsespeak.so. Skip the
# host CLI binary, the intonation/data configure step, and install rules -
# voice data is compiled by the separate host configure in the espeak-data
# target below, which gets its own defaults (this scope only affects the
# NDK configure because option() never overrides an existing variable).
set(BUILD_ESPEAK_NG_EXE OFF)
set(COMPILE_INTONATIONS OFF)
set(ESPEAK_COMPAT OFF)

add_subdirectory(../../ espeakng)

add_custom_target(
        espeak-data
        COMMAND ${CMAKE_COMMAND} -B${CMAKE_CURRENT_BINARY_DIR}/../espeak-data ${CMAKE_CURRENT_SOURCE_DIR}/../../
        COMMAND ${CMAKE_COMMAND} --build ${CMAKE_CURRENT_BINARY_DIR}/../espeak-data --target data
        COMMAND ${CMAKE_COMMAND} -E make_directory ${CMAKE_SOURCE_DIR}/../build/generated/espeak-ng-data
        COMMAND ${CMAKE_COMMAND} -E copy_directory
                ${CMAKE_CURRENT_BINARY_DIR}/../espeak-data/espeak-ng-data
                ${CMAKE_SOURCE_DIR}/../build/generated/espeak-ng-data
)

message(STATUS "Data: ${CMAKE_CURRENT_SOURCE_DIR}")

find_library(A_LOG log)

add_library(ttsespeak SHARED
        jni/eSpeakService.c
        jni/piperPhonemizer.c
        )
# SONIC_DIR: the Piper path time-stretches its audio with the libsonic that
# is already linked into espeak-ng (see nativeSonicStretch), so only the
# header is needed here.
target_include_directories(ttsespeak PRIVATE
        include
        jni
        "${SONIC_DIR}"
)
target_link_libraries(ttsespeak PRIVATE
        espeak-ng ${A_LOG}
)
# Export only the JNI entry points; see ttsespeak.map.
target_link_options(ttsespeak PRIVATE
        "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/ttsespeak.map"
)
set_property(TARGET ttsespeak APPEND PROPERTY LINK_DEPENDS
        ${CMAKE_CURRENT_SOURCE_DIR}/ttsespeak.map
)
