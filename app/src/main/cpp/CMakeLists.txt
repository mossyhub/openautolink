cmake_minimum_required(VERSION 3.22.1)
project(openautolink-jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# --------------------------------------------------------------------------
# All native dependencies are PREBUILT via scripts/ run in WSL:
#   scripts/build-openssl-android.sh  → third_party/openssl/arm64-v8a/
#   scripts/setup-ndk-deps.sh         → third_party/boost/include/
#   scripts/build-aasdk-android.sh    → third_party/aasdk/arm64-v8a/
# --------------------------------------------------------------------------

# aasdk source headers (submodule — needed for #include <aasdk/...>)
set(AASDK_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../external/opencardev-aasdk")
get_filename_component(AASDK_SOURCE_DIR "${AASDK_SOURCE_DIR}" ABSOLUTE)

set(THIRD_PARTY "${CMAKE_CURRENT_SOURCE_DIR}/third_party")

message(STATUS "OAL JNI: ANDROID_ABI = ${ANDROID_ABI}")

# --------------------------------------------------------------------------
# Prebuilt: Boost headers (header-only for Asio + System)
# --------------------------------------------------------------------------
set(BOOST_INCLUDE_DIR "${THIRD_PARTY}/boost/include")
if(NOT EXISTS "${BOOST_INCLUDE_DIR}/boost/asio.hpp")
    message(FATAL_ERROR "Boost headers not found. Run: scripts/setup-ndk-deps.sh")
endif()

# --------------------------------------------------------------------------
# Prebuilt: OpenSSL static libs
# --------------------------------------------------------------------------
set(OPENSSL_DIR "${THIRD_PARTY}/openssl/${ANDROID_ABI}")
if(NOT EXISTS "${OPENSSL_DIR}/lib/libssl.a")
    message(FATAL_ERROR "OpenSSL not found. Run: scripts/build-openssl-android.sh")
endif()

add_library(openssl_ssl STATIC IMPORTED)
set_target_properties(openssl_ssl PROPERTIES
    IMPORTED_LOCATION "${OPENSSL_DIR}/lib/libssl.a"
    INTERFACE_INCLUDE_DIRECTORIES "${OPENSSL_DIR}/include"
)
add_library(openssl_crypto STATIC IMPORTED)
set_target_properties(openssl_crypto PROPERTIES
    IMPORTED_LOCATION "${OPENSSL_DIR}/lib/libcrypto.a"
    INTERFACE_INCLUDE_DIRECTORIES "${OPENSSL_DIR}/include"
)

# --------------------------------------------------------------------------
# Prebuilt: aasdk + aap_protobuf + protobuf + abseil static libs
# --------------------------------------------------------------------------
set(AASDK_DIR "${THIRD_PARTY}/aasdk/${ANDROID_ABI}")
if(NOT EXISTS "${AASDK_DIR}/lib/libaasdk.a")
    message(FATAL_ERROR "aasdk not found. Run: scripts/build-aasdk-android.sh")
endif()

add_library(aasdk STATIC IMPORTED)
set_target_properties(aasdk PROPERTIES IMPORTED_LOCATION "${AASDK_DIR}/lib/libaasdk.a")

add_library(aap_protobuf STATIC IMPORTED)
set_target_properties(aap_protobuf PROPERTIES IMPORTED_LOCATION "${AASDK_DIR}/lib/libaap_protobuf.a")

# Protobuf runtime — may be named libprotobuf.a or libprotobuf-lite.a
if(EXISTS "${AASDK_DIR}/lib/libprotobuf.a")
    add_library(protobuf_lib STATIC IMPORTED)
    set_target_properties(protobuf_lib PROPERTIES IMPORTED_LOCATION "${AASDK_DIR}/lib/libprotobuf.a")
endif()

# utf8_validity (protobuf dependency)
if(EXISTS "${AASDK_DIR}/lib/libutf8_validity.a")
    add_library(utf8_validity STATIC IMPORTED)
    set_target_properties(utf8_validity PROPERTIES IMPORTED_LOCATION "${AASDK_DIR}/lib/libutf8_validity.a")
endif()

# Abseil — glob all libabsl_*.a into a list
file(GLOB ABSEIL_LIBS "${AASDK_DIR}/lib/libabsl_*.a")
set(ABSEIL_IMPORTED_TARGETS "")
foreach(ABSL_LIB ${ABSEIL_LIBS})
    get_filename_component(ABSL_NAME "${ABSL_LIB}" NAME_WE)
    add_library(${ABSL_NAME} STATIC IMPORTED)
    set_target_properties(${ABSL_NAME} PROPERTIES IMPORTED_LOCATION "${ABSL_LIB}")
    list(APPEND ABSEIL_IMPORTED_TARGETS ${ABSL_NAME})
endforeach()

# --------------------------------------------------------------------------
# libusb stub (USB not used on Android)
# --------------------------------------------------------------------------
add_library(usb_stub STATIC "${CMAKE_CURRENT_SOURCE_DIR}/stubs/libusb_stub.c")
target_include_directories(usb_stub PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/stubs")

# --------------------------------------------------------------------------
# JNI bridge library
# --------------------------------------------------------------------------
add_library(openautolink-jni SHARED
    aasdk_jni.cpp
    jni_transport.cpp
    jni_session.cpp
    jni_channel_handlers.cpp
    jni_service_catalog.cpp
    native_crash_handler.c
)

target_include_directories(openautolink-jni PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/include"
    "${AASDK_SOURCE_DIR}/include"
    "${AASDK_DIR}/include"
    "${AASDK_DIR}/include/protobuf"
    "${OPENSSL_DIR}/include"
    "${BOOST_INCLUDE_DIR}"
    "${CMAKE_CURRENT_SOURCE_DIR}/stubs"
)

# Link against prebuilt static libs + Android system libs
# Use --whole-archive for protobuf to ensure all symbols are available,
# and --start-group/--end-group to handle circular deps between static libs.
target_link_libraries(openautolink-jni PRIVATE
    -Wl,--start-group
    aasdk
    aap_protobuf
    protobuf_lib
    $<$<TARGET_EXISTS:utf8_validity>:utf8_validity>
    ${ABSEIL_IMPORTED_TARGETS}
    openssl_ssl
    openssl_crypto
    usb_stub
    -Wl,--end-group
    log          # Android __android_log_print
    android      # ANativeWindow, etc.
)

target_compile_definitions(openautolink-jni PRIVATE
    BOOST_ASIO_HAS_STD_INVOKE_RESULT=1
    BOOST_ALL_NO_LIB=1
)

# Separate public-AAudio diagnostic, packaged for the same ABI filters as the app.
add_library(oal-parked-capture SHARED
    parked_capture_jni.cpp
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../tools/native-aaudio-capture/capture.cpp")
target_link_libraries(oal-parked-capture PRIVATE aaudio log)
target_link_options(oal-parked-capture PRIVATE -Wl,--no-undefined)

# Strip in release for smaller APK
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    target_link_options(openautolink-jni PRIVATE -s)
endif()
