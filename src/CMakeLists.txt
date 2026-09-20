add_library(espeak-include INTERFACE)
target_include_directories(espeak-include INTERFACE include include/compat)
target_link_libraries(espeak-include INTERFACE ucd)

add_subdirectory(ucd-tools)
add_subdirectory(speechPlayer)
add_subdirectory(libespeak-ng)

if (BUILD_ESPEAK_NG_EXE)
  add_executable(espeak-ng-bin espeak-ng.c)
  set_target_properties(espeak-ng-bin PROPERTIES OUTPUT_NAME espeak-ng)
  target_link_libraries(
    espeak-ng-bin PRIVATE espeak-ng espeak-ng-config
  )
  set_target_properties(
    espeak-ng-bin PROPERTIES
    MACOSX_RPATH ON
    INSTALL_RPATH "${CMAKE_INSTALL_FULL_LIBDIR}"
  )
  if (MINGW)
    target_link_options(espeak-ng-bin PRIVATE "-static-libstdc++" "-static")
  endif()
  if (MSVC)
    target_sources(espeak-ng-bin PRIVATE compat/getopt.c)
  endif()
  install(TARGETS espeak-ng-bin)
endif()
install(DIRECTORY include/espeak include/espeak-ng TYPE INCLUDE)
