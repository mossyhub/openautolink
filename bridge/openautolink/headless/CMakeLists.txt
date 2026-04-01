cmake_minimum_required(VERSION 3.16)
project(openautolink_headless LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(PI_AA_OPENAUTO_SOURCE_DIR "" CACHE PATH "Optional path to a local OpenAuto source tree")
set(PI_AA_AASDK_SOURCE_DIR "" CACHE PATH "Optional path to a local aasdk source tree")
set(PI_AA_AASDK_PROTO_DIR "" CACHE PATH "Optional path to local aasdk .proto files")
option(PI_AA_ENABLE_AASDK_LIVE "Build with real aasdk transport support (requires Boost, OpenSSL, Protobuf, libusb)" OFF)

if(NOT PI_AA_AASDK_PROTO_DIR AND PI_AA_AASDK_SOURCE_DIR AND EXISTS "${PI_AA_AASDK_SOURCE_DIR}/aasdk_proto")
    set(PI_AA_AASDK_PROTO_DIR "${PI_AA_AASDK_SOURCE_DIR}/aasdk_proto" CACHE PATH "Optional path to local aasdk .proto files" FORCE)
endif()

if(PI_AA_OPENAUTO_SOURCE_DIR)
    message(STATUS "Configured local OpenAuto source: ${PI_AA_OPENAUTO_SOURCE_DIR}")
endif()

if(PI_AA_AASDK_SOURCE_DIR)
    message(STATUS "Configured local aasdk source: ${PI_AA_AASDK_SOURCE_DIR}")
endif()

if(PI_AA_AASDK_PROTO_DIR)
    message(STATUS "Configured local aasdk proto source: ${PI_AA_AASDK_PROTO_DIR}")
endif()

add_executable(openautolink-headless
    src/aasdk_control.cpp
    src/contract.cpp
    src/engine.cpp
    src/main.cpp
    src/oal_session.cpp
    src/service_catalog.cpp
    src/session.cpp
)

target_include_directories(openautolink-headless PRIVATE include)

if(PI_AA_OPENAUTO_SOURCE_DIR AND EXISTS "${PI_AA_OPENAUTO_SOURCE_DIR}/include")
    target_include_directories(openautolink-headless PRIVATE "${PI_AA_OPENAUTO_SOURCE_DIR}/include")
    target_compile_definitions(openautolink-headless PRIVATE PI_AA_HAVE_LOCAL_OPENAUTO_SOURCE=1)
endif()

if(PI_AA_AASDK_SOURCE_DIR AND EXISTS "${PI_AA_AASDK_SOURCE_DIR}/include")
    target_include_directories(openautolink-headless PRIVATE "${PI_AA_AASDK_SOURCE_DIR}/include")
    target_compile_definitions(openautolink-headless PRIVATE PI_AA_HAVE_LOCAL_AASDK_SOURCE=1)
endif()

# -------------------------------------------------------------------
# Real aasdk live transport mode
# -------------------------------------------------------------------
if(PI_AA_ENABLE_AASDK_LIVE)
    message(STATUS "Building with real aasdk live transport support")

    if(NOT PI_AA_AASDK_SOURCE_DIR OR NOT EXISTS "${PI_AA_AASDK_SOURCE_DIR}/CMakeLists.txt")
        message(FATAL_ERROR "PI_AA_ENABLE_AASDK_LIVE requires PI_AA_AASDK_SOURCE_DIR to point to a valid aasdk source tree")
    endif()

    # aasdk needs these system packages
    find_package(Boost REQUIRED COMPONENTS system log)
    find_package(OpenSSL REQUIRED)
    # Note: opencardev-aasdk bundles its own protobuf — no system protobuf needed

    # aasdk uses a custom Findlibusb-1.0.cmake
    list(APPEND CMAKE_MODULE_PATH "${PI_AA_AASDK_SOURCE_DIR}/cmake_modules")
    find_package(libusb-1.0 REQUIRED)

    # Build aasdk as a subdirectory.
    # Save and restore CMAKE_CXX_STANDARD since aasdk sets it to 14.
    set(_saved_cxx_standard ${CMAKE_CXX_STANDARD})
    add_subdirectory("${PI_AA_AASDK_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/aasdk_build" EXCLUDE_FROM_ALL)
    set(CMAKE_CXX_STANDARD ${_saved_cxx_standard})

    # Add live session sources
    target_sources(openautolink-headless PRIVATE
        src/live_session.cpp
        src/sco_audio.cpp
    )

    # Find libbluetooth for SCO audio (BT HFP phone calls)
    find_library(BLUETOOTH_LIBRARY bluetooth)
    find_path(BLUETOOTH_INCLUDE_DIR bluetooth/bluetooth.h)

    # Link against the aasdk shared library and its transitive dependencies
    target_link_libraries(openautolink-headless PRIVATE
        aasdk
        aap_protobuf
        ${Boost_LIBRARIES}
        ${OPENSSL_LIBRARIES}
        ${LIBUSB_1_LIBRARIES}
    )
    if(BLUETOOTH_LIBRARY)
        target_link_libraries(openautolink-headless PRIVATE ${BLUETOOTH_LIBRARY})
        message(STATUS "libbluetooth found: ${BLUETOOTH_LIBRARY}")
    else()
        message(WARNING "libbluetooth not found — SCO audio (HFP) will not build")
    endif()
    target_include_directories(openautolink-headless PRIVATE
        "${PI_AA_AASDK_SOURCE_DIR}/include"
        "${CMAKE_CURRENT_BINARY_DIR}/aasdk_build"              # for generated Version.hpp
        "${CMAKE_CURRENT_BINARY_DIR}/aasdk_build/include"      # for generated Version.hpp (alt)
        "${CMAKE_CURRENT_BINARY_DIR}/aasdk_build/protobuf"     # for generated proto headers
        ${Boost_INCLUDE_DIRS}
        ${OPENSSL_INCLUDE_DIR}
        ${LIBUSB_1_INCLUDE_DIRS}
    )
    if(BLUETOOTH_INCLUDE_DIR)
        target_include_directories(openautolink-headless PRIVATE ${BLUETOOTH_INCLUDE_DIR})
    endif()
    target_compile_definitions(openautolink-headless PRIVATE
        PI_AA_ENABLE_AASDK_LIVE=1
        BOOST_ALL_DYN_LINK
    )

    # Ensure our binary uses C++20 to match opencardev-aasdk
    set_target_properties(openautolink-headless PROPERTIES CXX_STANDARD 20)

elseif(PI_AA_AASDK_PROTO_DIR AND EXISTS "${PI_AA_AASDK_PROTO_DIR}")
    # Placeholder-only proto generation (existing behavior)
    find_package(Protobuf QUIET)
    if(Protobuf_FOUND)
        file(GLOB aasdk_proto_files "${PI_AA_AASDK_PROTO_DIR}/*.proto")

        protobuf_generate_cpp(aasdk_proto_sources aasdk_proto_headers ${aasdk_proto_files})
        target_sources(openautolink-headless PRIVATE ${aasdk_proto_sources} ${aasdk_proto_headers})
        target_include_directories(openautolink-headless PRIVATE "${CMAKE_CURRENT_BINARY_DIR}")
        target_compile_definitions(openautolink-headless PRIVATE PI_AA_HAVE_LOCAL_AASDK_CONTROL_PROTO=1)
        target_compile_definitions(openautolink-headless PRIVATE PI_AA_HAVE_LOCAL_AASDK_SERVICE_DISCOVERY_PROTO=1)

        if(TARGET protobuf::libprotobuf)
            target_link_libraries(openautolink-headless PRIVATE protobuf::libprotobuf)
        else()
            target_include_directories(openautolink-headless PRIVATE ${PROTOBUF_INCLUDE_DIRS})
            target_link_libraries(openautolink-headless PRIVATE ${PROTOBUF_LIBRARIES})
        endif()
    else()
        message(STATUS "Skipping aasdk proto generation because Protobuf was not found")
    endif()
endif()

target_compile_options(openautolink-headless PRIVATE
    $<$<CXX_COMPILER_ID:MSVC>:/W4 /permissive->
    $<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-Wall -Wextra -pedantic>
)
