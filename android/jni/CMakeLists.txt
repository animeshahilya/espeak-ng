cmake_minimum_required(VERSION 3.14)

project(espeak-android)

# Ensure 16 KB page-size alignment for Android 14/15 ARM64 kernels
add_link_options("-Wl,-z,max-page-size=16384")
# Release only: an unconditional -O3 also applied to debug builds, making
# native breakpoints/variable inspection unreliable during development.
add_compile_options("$<$<CONFIG:Release>:-O3>")

# Build libsonic from source for Android and pre-seed the find results so
# cmake/deps.cmake in the main project detects it (the NDK sysroot has no sonic).
# Pinned past fbf75c3 (2024) to pick up two upstream fixes directly relevant
# to correctness here: a buffer-allocation skip-calculation fix (PR #55) and
# a downSampleBuffer overflow fix (PR #57) in sonic.c, the only file of this
# library actually compiled in - not just following upstream for its own sake.
include(FetchContent)
FetchContent_Declare(sonic-git
        GIT_REPOSITORY https://github.com/waywardgeek/sonic.git
        GIT_TAG b93885dcb70aae50c6f76b0fe4e0868f029a077e
)
FetchContent_MakeAvailable(sonic-git)
add_library(sonic OBJECT ${sonic-git_SOURCE_DIR}/sonic.c)
# enable PIC in order to embed libsonic in libttsespeak.so
set_property(TARGET sonic PROPERTY POSITION_INDEPENDENT_CODE ON)
target_include_directories(sonic PUBLIC ${sonic-git_SOURCE_DIR})
set(SONIC_LIB sonic)
set(SONIC_INC ${sonic-git_SOURCE_DIR})

add_subdirectory(../../ espeakng)

add_custom_target(
        espeak-data
        COMMAND ${CMAKE_COMMAND} -B${CMAKE_CURRENT_BINARY_DIR}/../espeak-data ${CMAKE_CURRENT_SOURCE_DIR}/../../
        COMMAND ${CMAKE_COMMAND} --build ${CMAKE_CURRENT_BINARY_DIR}/../espeak-data --target data
        COMMAND ${CMAKE_COMMAND} -E copy_directory ${CMAKE_CURRENT_BINARY_DIR}/../espeak-data/espeak-ng-data ${CMAKE_SOURCE_DIR}/../build/generated/espeak-ng-data
)

message(STATUS "Data: ${CMAKE_CURRENT_SOURCE_DIR}")

find_library(A_LOG log)

add_library(ttsespeak SHARED
        jni/eSpeakService.c
        )
target_include_directories(ttsespeak PRIVATE
        include
)
target_link_libraries(ttsespeak PRIVATE
        espeak-ng ${A_LOG}
)
