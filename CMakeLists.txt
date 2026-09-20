cmake_minimum_required(VERSION 3.16...3.30)

project(espeak-ng
  VERSION 1.53.1
  DESCRIPTION "open source speech synthesizer that supports more than hundred languages and accents"
  HOMEPAGE_URL "https://github.com/espeak-ng/espeak-ng"
)

option(BUILD_ESPEAK_NG_EXE "Whether to build espeak-ng-bin" ON)
option(COMPILE_INTONATIONS "Intonation compilation" ON)

if (CMAKE_COMPILER_IS_GNUCC OR CMAKE_C_COMPILER_ID MATCHES "Clang")
  add_compile_options(-Wimplicit-fallthrough)
endif()

include(GNUInstallDirs)
include(cmake/deps.cmake)
include(cmake/config.cmake)
add_subdirectory(src)

if (COMPILE_INTONATIONS)
  if (CMAKE_CROSSCOMPILING)
    find_program(NATIVEBUILD NAMES espeak-ng PATHS ${NativeBuild_DIR} NO_DEFAULT_PATH)
    if(NATIVEBUILD)
      message(STATUS "Native build of espeak-ng is located at: ${NATIVEBUILD}")
      include(cmake/data.cmake)
    else()
      message(STATUS "Not building intonations as the build is a cross compile.")
      message(STATUS "Call CMake with the location of the native build of espeak-ng")
      message(STATUS "  e.g. -DNativeBuild=~/src/build-native")
      message(STATUS "to build the intonations.")
    endif()
  else()
    include(cmake/data.cmake)
  endif()
endif()

message(STATUS "Configuration:")
message(STATUS "  libsonic: ${USE_LIBSONIC} (${SONIC_LIB} ${SONIC_INC})")
message(STATUS "  libpcaudio: ${USE_LIBPCAUDIO} (${PCAUDIO_LIB} ${PCAUDIO_INC})")
message(STATUS "  klatt: ${USE_KLATT}")
message(STATUS "  speech-player: ${USE_SPEECHPLAYER}")
message(STATUS "  async: ${USE_ASYNC}")
