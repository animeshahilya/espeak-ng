list(APPEND CPACK_SOURCE_IGNORE_FILES
  /\.git/
  /\.gitignore
  /\.github/
  /\.tx/
  /\.travis.yml
  /_layouts/
  /android/
  /build/
  /build-asan/
  /docs/
  /emscripten/
  /fastlane/
  /tools/
  /vim/
)

# dist.yml unpacks build/espeak-ng-*-Source.tar.bz2, so the source
# generator must actually produce .tar.bz2 (CMake default is .tar.gz).
set(CPACK_SOURCE_GENERATOR "TBZ2")

set(PACKAGE_NAME ${PROJECT_NAME})
set(VERSION ${PROJECT_VERSION})

set(prefix ${CMAKE_INSTALL_PREFIX})
set(exec_prefix ${CMAKE_INSTALL_PREFIX})
set(libdir ${CMAKE_INSTALL_FULL_LIBDIR})
set(includedir ${CMAKE_INSTALL_FULL_INCLUDEDIR})

configure_file(
  ${CMAKE_CURRENT_SOURCE_DIR}/${PROJECT_NAME}.pc.in
  ${CMAKE_CURRENT_BINARY_DIR}/${PROJECT_NAME}.pc
  @ONLY
)

install(
  FILES ${CMAKE_CURRENT_BINARY_DIR}/${PROJECT_NAME}.pc DESTINATION ${CMAKE_INSTALL_LIBDIR}/pkgconfig
)
