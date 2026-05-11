/*
 * Minimal libusb-1.0 header stub — just enough types/functions for aasdk to link.
 * Real USB is not used on Android (the AA session runs over TCP via JniTransport).
 */
#pragma once

#include <stdint.h>
#include <sys/types.h>
#include <sys/time.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Endpoint direction constants */
#define LIBUSB_ENDPOINT_DIR_MASK  0x80
#define LIBUSB_ENDPOINT_IN        0x80
#define LIBUSB_ENDPOINT_OUT       0x00
#define LIBUSB_TRANSFER_TYPE_MASK 0x03

/* Request types */
#define LIBUSB_REQUEST_TYPE_STANDARD  (0x00 << 5)
#define LIBUSB_REQUEST_TYPE_CLASS     (0x01 << 5)
#define LIBUSB_REQUEST_TYPE_VENDOR    (0x02 << 5)
#define LIBUSB_RECIPIENT_DEVICE      0x00
#define LIBUSB_RECIPIENT_INTERFACE   0x01

/* Do NOT define USB_TYPE_VENDOR — aasdk defines its own as a constexpr */

/* Error codes — must be an enum, not #defines, because aasdk uses libusb_error::LIBUSB_SUCCESS */
enum libusb_error {
    LIBUSB_SUCCESS            = 0,
    LIBUSB_ERROR_IO           = -1,
    LIBUSB_ERROR_INVALID_PARAM = -2,
    LIBUSB_ERROR_ACCESS       = -3,
    LIBUSB_ERROR_NO_DEVICE    = -4,
    LIBUSB_ERROR_NOT_FOUND    = -5,
    LIBUSB_ERROR_BUSY         = -6,
    LIBUSB_ERROR_TIMEOUT      = -7,
    LIBUSB_ERROR_OVERFLOW     = -8,
    LIBUSB_ERROR_PIPE         = -9,
    LIBUSB_ERROR_INTERRUPTED  = -10,
    LIBUSB_ERROR_NO_MEM       = -11,
    LIBUSB_ERROR_NOT_SUPPORTED = -12,
    LIBUSB_ERROR_OTHER        = -99
};

typedef struct libusb_context libusb_context;
typedef struct libusb_device libusb_device;
typedef struct libusb_device_handle libusb_device_handle;
typedef int libusb_hotplug_callback_handle;

/* Transfer status enum */
enum libusb_transfer_status {
    LIBUSB_TRANSFER_COMPLETED = 0,
    LIBUSB_TRANSFER_ERROR,
    LIBUSB_TRANSFER_TIMED_OUT,
    LIBUSB_TRANSFER_CANCELLED,
    LIBUSB_TRANSFER_STALL,
    LIBUSB_TRANSFER_NO_DEVICE,
    LIBUSB_TRANSFER_OVERFLOW
};

/* Transfer callback type */
typedef void (*libusb_transfer_cb_fn)(struct libusb_transfer *transfer);

/* Transfer structure */
struct libusb_transfer {
    libusb_device_handle *dev_handle;
    uint8_t flags;
    unsigned char endpoint;
    unsigned char type;
    unsigned int timeout;
    enum libusb_transfer_status status;
    int length;
    int actual_length;
    libusb_transfer_cb_fn callback;
    void *user_data;
    unsigned char *buffer;
    int num_iso_packets;
};

/* Hotplug events */
enum libusb_hotplug_event {
    LIBUSB_HOTPLUG_EVENT_DEVICE_ARRIVED = 0x01,
    LIBUSB_HOTPLUG_EVENT_DEVICE_LEFT = 0x02
};

enum libusb_hotplug_flag {
    LIBUSB_HOTPLUG_ENUMERATE = 1
};

#define LIBUSB_HOTPLUG_MATCH_ANY -1

typedef int (*libusb_hotplug_callback_fn)(libusb_context *ctx,
    libusb_device *device, int event, void *user_data);

struct libusb_device_descriptor {
    uint8_t  bLength;
    uint8_t  bDescriptorType;
    uint16_t bcdUSB;
    uint8_t  bDeviceClass;
    uint8_t  bDeviceSubClass;
    uint8_t  bDeviceProtocol;
    uint8_t  bMaxPacketSize0;
    uint16_t idVendor;
    uint16_t idProduct;
    uint16_t bcdDevice;
    uint8_t  iManufacturer;
    uint8_t  iProduct;
    uint8_t  iSerialNumber;
    uint8_t  bNumConfigurations;
};

struct libusb_endpoint_descriptor {
    uint8_t  bLength;
    uint8_t  bDescriptorType;
    uint8_t  bEndpointAddress;
    uint8_t  bmAttributes;
    uint16_t wMaxPacketSize;
    uint8_t  bInterval;
};

struct libusb_interface_descriptor {
    uint8_t  bLength;
    uint8_t  bDescriptorType;
    uint8_t  bInterfaceNumber;
    uint8_t  bAlternateSetting;
    uint8_t  bNumEndpoints;
    uint8_t  bInterfaceClass;
    uint8_t  bInterfaceSubClass;
    uint8_t  bInterfaceProtocol;
    uint8_t  iInterface;
    const struct libusb_endpoint_descriptor *endpoint;
};

struct libusb_interface {
    const struct libusb_interface_descriptor *altsetting;
    int num_altsetting;
};

struct libusb_config_descriptor {
    uint8_t  bLength;
    uint8_t  bDescriptorType;
    uint16_t wTotalLength;
    uint8_t  bNumInterfaces;
    uint8_t  bConfigurationValue;
    uint8_t  iConfiguration;
    uint8_t  bmAttributes;
    uint8_t  MaxPower;
    const struct libusb_interface *interface;
    const unsigned char *extra;
    int extra_length;
};

int libusb_init(libusb_context **ctx);
void libusb_exit(libusb_context *ctx);
ssize_t libusb_get_device_list(libusb_context *ctx, libusb_device ***list);
void libusb_free_device_list(libusb_device **list, int unref_devices);
int libusb_open(libusb_device *dev, libusb_device_handle **handle);
void libusb_close(libusb_device_handle *handle);
int libusb_claim_interface(libusb_device_handle *handle, int iface);
int libusb_release_interface(libusb_device_handle *handle, int iface);
int libusb_get_device_descriptor(libusb_device *dev, struct libusb_device_descriptor *desc);
int libusb_get_config_descriptor(libusb_device *dev, uint8_t config_index, struct libusb_config_descriptor **config);
void libusb_free_config_descriptor(struct libusb_config_descriptor *config);
int libusb_control_transfer(libusb_device_handle *handle, uint8_t bmRequestType,
    uint8_t bRequest, uint16_t wValue, uint16_t wIndex, unsigned char *data,
    uint16_t wLength, unsigned int timeout);
int libusb_bulk_transfer(libusb_device_handle *handle, unsigned char endpoint,
    unsigned char *data, int length, int *transferred, unsigned int timeout);
struct libusb_transfer *libusb_alloc_transfer(int iso_packets);
void libusb_free_transfer(struct libusb_transfer *transfer);
int libusb_submit_transfer(struct libusb_transfer *transfer);
int libusb_cancel_transfer(struct libusb_transfer *transfer);
void libusb_fill_bulk_transfer(struct libusb_transfer *transfer,
    libusb_device_handle *dev_handle, unsigned char endpoint,
    unsigned char *buffer, int length, libusb_transfer_cb_fn callback,
    void *user_data, unsigned int timeout);
int libusb_hotplug_register_callback(libusb_context *ctx, int events, int flags,
    int vendor_id, int product_id, int dev_class,
    libusb_hotplug_callback_fn cb_fn, void *user_data,
    libusb_hotplug_callback_handle *handle);
void libusb_hotplug_deregister_callback(libusb_context *ctx,
    libusb_hotplug_callback_handle handle);
int libusb_handle_events_timeout_completed(libusb_context *ctx, struct timeval *tv, int *completed);
int libusb_handle_events(libusb_context *ctx);
uint8_t libusb_get_bus_number(libusb_device *dev);
uint8_t libusb_get_device_address(libusb_device *dev);
libusb_device *libusb_ref_device(libusb_device *dev);
void libusb_unref_device(libusb_device *dev);
int libusb_reset_device(libusb_device_handle *handle);
int libusb_kernel_driver_active(libusb_device_handle *handle, int iface);
int libusb_detach_kernel_driver(libusb_device_handle *handle, int iface);
libusb_device *libusb_get_device(libusb_device_handle *handle);
libusb_device_handle *libusb_open_device_with_vid_pid(libusb_context *ctx,
    uint16_t vendor_id, uint16_t product_id);
void libusb_fill_interrupt_transfer(struct libusb_transfer *transfer,
    libusb_device_handle *dev_handle, unsigned char endpoint,
    unsigned char *buffer, int length, libusb_transfer_cb_fn callback,
    void *user_data, unsigned int timeout);
void libusb_fill_control_transfer(struct libusb_transfer *transfer,
    libusb_device_handle *dev_handle, unsigned char *buffer,
    libusb_transfer_cb_fn callback, void *user_data, unsigned int timeout);
void libusb_fill_control_setup(unsigned char *buffer, uint8_t bmRequestType,
    uint8_t bRequest, uint16_t wValue, uint16_t wIndex, uint16_t wLength);
const char *libusb_error_name(int errcode);
int libusb_set_configuration(libusb_device_handle *handle, int configuration);

#ifdef __cplusplus
}
#endif
