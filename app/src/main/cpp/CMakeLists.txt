# ───────────────────────────────────────────────────────────────────
# CMakeLists.txt — Build aasdk + JNI bridge for Android NDK
#
# This is NOT the upstream aasdk CMakeLists.txt. It's our own wrapper
# that compiles only the TCP/non-USB subset of aasdk for arm64-v8a,
# using header-only Boost, prebuilt OpenSSL, and FetchContent protobuf.
#
# Output: liboal_jni.so (single shared lib with aasdk + protobuf + JNI bridge)
#
# Build modes:
#   OAL_STUB_ONLY=ON  — JNI bridge stubs only (no aasdk, no deps)
#                        Use for local dev / verifying build pipeline
#   OAL_STUB_ONLY=OFF — Full aasdk + JNI bridge (requires Boost, OpenSSL, protobuf)
#                        Use for CI / release builds
# ───────────────────────────────────────────────────────────────────

cmake_minimum_required(VERSION 3.22)
project(oal_jni LANGUAGES CXX)

# C++17 is sufficient for aasdk and protobuf v3.21.
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ─── Paths ────────────────────────────────────────────────────────
set(AASDK_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../external/opencardev-aasdk")
get_filename_component(AASDK_ROOT "${AASDK_ROOT}" ABSOLUTE)

set(THIRD_PARTY "${CMAKE_CURRENT_SOURCE_DIR}/third_party")

set(AASDK_SRC "${AASDK_ROOT}/src")
set(AASDK_INCLUDE "${AASDK_ROOT}/include")
set(AASDK_PROTO_DIR "${AASDK_ROOT}/protobuf")
set(AASDK_PROTO_SRC_DIR "${AASDK_ROOT}/protobuf/aap_protobuf")
set(AASDK_CERT_DIR "${AASDK_ROOT}/cert")

# ─── Options ──────────────────────────────────────────────────────
option(OAL_NO_USB "Exclude USB/libusb transport (required for Android)" ON)
option(OAL_STUB_ONLY "Build JNI stubs only — no aasdk, no native deps" OFF)

# ─── Auto-detect stub mode ───────────────────────────────────────
# OpenSSL is stored per-ABI: third_party/openssl/<ABI>/
set(OPENSSL_ROOT "${THIRD_PARTY}/openssl/${ANDROID_ABI}")
if(NOT OAL_STUB_ONLY AND NOT EXISTS "${OPENSSL_ROOT}/include/openssl/ssl.h")
    message(WARNING
        "OpenSSL not found at ${OPENSSL_ROOT} — building in stub-only mode.\n"
        "Run: scripts/build-openssl-arch.sh ${ANDROID_ABI}")
    set(OAL_STUB_ONLY ON)
endif()

# ═══════════════════════════════════════════════════════════════════
# STUB-ONLY MODE: Just the JNI bridge with stub implementations
# ═══════════════════════════════════════════════════════════════════
if(OAL_STUB_ONLY)
    message(STATUS "Building liboal_jni.so in STUB mode (no aasdk)")

    add_library(oal_jni SHARED
        "${CMAKE_CURRENT_SOURCE_DIR}/jni_bridge.cpp"
        "${CMAKE_CURRENT_SOURCE_DIR}/aa_session.cpp"
    )

    target_link_libraries(oal_jni PRIVATE
        android
        log
    )

    target_compile_definitions(oal_jni PRIVATE OAL_STUB_ONLY)

    return()
endif()

# ═══════════════════════════════════════════════════════════════════
# FULL MODE: aasdk + protobuf + OpenSSL + JNI bridge
# ═══════════════════════════════════════════════════════════════════
message(STATUS "Building liboal_jni.so with full aasdk")

# ─── Boost (header-only) ─────────────────────────────────────────
set(BOOST_ROOT "${THIRD_PARTY}/boost")
if(NOT EXISTS "${BOOST_ROOT}/boost/asio.hpp")
    message(FATAL_ERROR
        "Boost headers not found at ${BOOST_ROOT}.\n"
        "Run scripts/setup-ndk-deps.ps1 (Windows) or scripts/setup-ndk-deps.sh (Linux/macOS).")
endif()

# ─── OpenSSL (prebuilt for Android) ──────────────────────────────
add_library(OpenSSL::SSL STATIC IMPORTED)
set_target_properties(OpenSSL::SSL PROPERTIES
    IMPORTED_LOCATION "${OPENSSL_ROOT}/lib/libssl.a"
    INTERFACE_INCLUDE_DIRECTORIES "${OPENSSL_ROOT}/include"
)

add_library(OpenSSL::Crypto STATIC IMPORTED)
set_target_properties(OpenSSL::Crypto PROPERTIES
    IMPORTED_LOCATION "${OPENSSL_ROOT}/lib/libcrypto.a"
    INTERFACE_INCLUDE_DIRECTORIES "${OPENSSL_ROOT}/include"
)

# ─── Protobuf (build from source via FetchContent) ───────────────
# v3.21.12 is the last release without Abseil dependency — C++17 compatible.
# Newer releases (v22+/v4+) require Abseil which needs C++20.
include(FetchContent)

set(protobuf_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(protobuf_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(protobuf_INSTALL OFF CACHE BOOL "" FORCE)
set(protobuf_BUILD_PROTOC_BINARIES OFF CACHE BOOL "" FORCE)
set(protobuf_WITH_ZLIB OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    protobuf
    GIT_REPOSITORY https://github.com/protocolbuffers/protobuf.git
    GIT_TAG        v3.21.12
    GIT_SHALLOW    TRUE
    GIT_PROGRESS   TRUE
)
FetchContent_MakeAvailable(protobuf)

# ─── Find host protoc for .proto compilation ─────────────────────
find_program(PROTOC_EXECUTABLE
    NAMES protoc
    HINTS
        "$ENV{LOCALAPPDATA}/protoc/bin"
        "$ENV{HOME}/.local/bin"
        "/usr/bin"
        "/usr/local/bin"
    DOC "Path to host protoc compiler"
)

if(NOT PROTOC_EXECUTABLE)
    message(FATAL_ERROR
        "Host 'protoc' not found. Install it:\n"
        "  Windows: choco install protoc  OR  download from github.com/protocolbuffers/protobuf/releases\n"
        "  Linux:   apt install protobuf-compiler\n"
        "  macOS:   brew install protobuf")
endif()

message(STATUS "Using host protoc: ${PROTOC_EXECUTABLE}")

# ─── Compile .proto files ────────────────────────────────────────
file(GLOB_RECURSE PROTO_FILES "${AASDK_PROTO_SRC_DIR}/*.proto")

set(PROTO_GEN_DIR "${CMAKE_CURRENT_BINARY_DIR}/proto_gen")
file(MAKE_DIRECTORY "${PROTO_GEN_DIR}")

set(PROTO_SRCS)
set(PROTO_HDRS)

foreach(PROTO_FILE ${PROTO_FILES})
    file(RELATIVE_PATH PROTO_REL "${AASDK_PROTO_DIR}" "${PROTO_FILE}")
    get_filename_component(PROTO_DIR "${PROTO_REL}" DIRECTORY)
    get_filename_component(PROTO_NAME "${PROTO_REL}" NAME_WE)

    set(PROTO_CC "${PROTO_GEN_DIR}/${PROTO_DIR}/${PROTO_NAME}.pb.cc")
    set(PROTO_H  "${PROTO_GEN_DIR}/${PROTO_DIR}/${PROTO_NAME}.pb.h")

    file(MAKE_DIRECTORY "${PROTO_GEN_DIR}/${PROTO_DIR}")

    add_custom_command(
        OUTPUT "${PROTO_CC}" "${PROTO_H}"
        COMMAND "${PROTOC_EXECUTABLE}"
            --proto_path ${AASDK_PROTO_DIR}
            --cpp_out ${PROTO_GEN_DIR}
            ${PROTO_FILE}
        DEPENDS "${PROTO_FILE}"
        COMMENT "Compiling ${PROTO_REL}"
        VERBATIM
    )

    list(APPEND PROTO_SRCS "${PROTO_CC}")
    list(APPEND PROTO_HDRS "${PROTO_H}")
endforeach()

add_library(aap_protobuf STATIC ${PROTO_SRCS} ${PROTO_HDRS})
target_include_directories(aap_protobuf PUBLIC "${PROTO_GEN_DIR}")
target_link_libraries(aap_protobuf PUBLIC protobuf::libprotobuf)

# ─── aasdk sources (excluding USB) ──────────────────────────────
file(GLOB_RECURSE AASDK_ALL_SRCS "${AASDK_SRC}/*.cpp")

# Remove unit test files
list(FILTER AASDK_ALL_SRCS EXCLUDE REGEX ".*\\.ut\\.cpp$")

if(OAL_NO_USB)
    list(FILTER AASDK_ALL_SRCS EXCLUDE REGEX ".*/USB/.*")
    list(FILTER AASDK_ALL_SRCS EXCLUDE REGEX ".*/Transport/USBTransport\\.cpp$")
endif()

# ─── Generate Version.hpp from template ──────────────────────────
set(LIBRARY_BUILD_MAJOR_RELEASE "2025")
set(LIBRARY_BUILD_MINOR_RELEASE "01")
set(LIBRARY_BUILD_PATCH_RELEASE "01")
set(LIBRARY_VERSION_STRING "${LIBRARY_BUILD_MAJOR_RELEASE}.${LIBRARY_BUILD_MINOR_RELEASE}.${LIBRARY_BUILD_PATCH_RELEASE}-oal")
set(GIT_COMMIT_ID "embedded")
set(GIT_BRANCH "oal-android")
set(GIT_DESCRIBE "oal-android")
set(GIT_DIRTY 0)

set(VERSION_HPP_DIR "${CMAKE_CURRENT_BINARY_DIR}/include/aasdk")
file(MAKE_DIRECTORY "${VERSION_HPP_DIR}")
configure_file(
    "${AASDK_ROOT}/include/aasdk/Version.hpp.in"
    "${VERSION_HPP_DIR}/Version.hpp"
    @ONLY
)

# ─── JNI bridge sources ─────────────────────────────────────────
set(JNI_SRCS
    "${CMAKE_CURRENT_SOURCE_DIR}/jni_bridge.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/aa_session.cpp"
)

# ─── Build liboal_jni.so ────────────────────────────────────────
add_library(oal_jni SHARED
    ${AASDK_ALL_SRCS}
    ${JNI_SRCS}
)

target_include_directories(oal_jni PRIVATE
    "${AASDK_INCLUDE}"
    "${CMAKE_CURRENT_BINARY_DIR}/include"   # Generated Version.hpp
    "${BOOST_ROOT}"
    "${OPENSSL_ROOT}/include"
    "${PROTO_GEN_DIR}"
    "${CMAKE_CURRENT_SOURCE_DIR}"
)

target_compile_definitions(oal_jni PRIVATE
    # Note: aasdk uses deprecated boost::asio::io_service; do NOT define
    # BOOST_ASIO_NO_DEPRECATED or the compatibility aliases will be removed.
    BOOST_ASIO_DISABLE_THREADS=0
    BOOST_ASIO_HAS_PTHREADS
)

# Force-include OpenSSL 3.x compat shims (no-op wrappers for removed APIs)
target_compile_options(oal_jni PRIVATE
    -include "${CMAKE_CURRENT_SOURCE_DIR}/openssl_compat.h"
)

if(OAL_NO_USB)
    target_compile_definitions(oal_jni PRIVATE OAL_NO_USB)
endif()

target_link_libraries(oal_jni PRIVATE
    aap_protobuf
    OpenSSL::SSL
    OpenSSL::Crypto
    android
    log
)

# Strip in release builds
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    set_target_properties(oal_jni PROPERTIES
        LINK_FLAGS "-Wl,--gc-sections"
    )
    target_compile_options(oal_jni PRIVATE -ffunction-sections -fdata-sections)
endif()
