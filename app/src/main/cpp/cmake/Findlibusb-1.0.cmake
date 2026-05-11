# Findlibusb-1.0.cmake shim for Android NDK build.
# aasdk calls find_package(libusb-1.0 REQUIRED).
# We provide a stub since USB is not used on Android (TCP transport via JniTransport).
# LIBUSB_1_LIBRARIES and LIBUSB_1_INCLUDE_DIRS are set by the parent CMake.

if(LIBUSB_1_LIBRARIES AND LIBUSB_1_INCLUDE_DIRS)
    set(LIBUSB_1_FOUND TRUE)
    set(libusb-1.0_FOUND TRUE)
    message(STATUS "Findlibusb-1.0 shim: using stub library")
else()
    message(FATAL_ERROR "Findlibusb-1.0 shim: LIBUSB_1_LIBRARIES not set")
endif()
