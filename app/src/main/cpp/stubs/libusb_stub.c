/*
 * libusb stub — satisfies aasdk linker without pulling in real USB support.
 * On Android the AA session runs over TCP via JniTransport, not libusb.
 */
#include "libusb.h"
#include <stdlib.h>

int libusb_init(libusb_context **ctx) { (void)ctx; return -1; }
void libusb_exit(libusb_context *ctx) { (void)ctx; }
ssize_t libusb_get_device_list(libusb_context *ctx, libusb_device ***list) {
    (void)ctx; (void)list; return 0;
}
void libusb_free_device_list(libusb_device **list, int unref_devices) {
    (void)list; (void)unref_devices;
}
int libusb_open(libusb_device *dev, libusb_device_handle **handle) {
    (void)dev; (void)handle; return -1;
}
void libusb_close(libusb_device_handle *handle) { (void)handle; }
int libusb_claim_interface(libusb_device_handle *handle, int iface) {
    (void)handle; (void)iface; return -1;
}
int libusb_release_interface(libusb_device_handle *handle, int iface) {
    (void)handle; (void)iface; return -1;
}
int libusb_get_device_descriptor(libusb_device *dev, struct libusb_device_descriptor *desc) {
    (void)dev; (void)desc; return -1;
}
int libusb_get_config_descriptor(libusb_device *dev, uint8_t config_index, struct libusb_config_descriptor **config) {
    (void)dev; (void)config_index; (void)config; return -1;
}
void libusb_free_config_descriptor(struct libusb_config_descriptor *config) { (void)config; }
int libusb_control_transfer(libusb_device_handle *handle, uint8_t bmRequestType,
    uint8_t bRequest, uint16_t wValue, uint16_t wIndex, unsigned char *data,
    uint16_t wLength, unsigned int timeout) {
    (void)handle; (void)bmRequestType; (void)bRequest; (void)wValue;
    (void)wIndex; (void)data; (void)wLength; (void)timeout;
    return -1;
}
int libusb_bulk_transfer(libusb_device_handle *handle, unsigned char endpoint,
    unsigned char *data, int length, int *transferred, unsigned int timeout) {
    (void)handle; (void)endpoint; (void)data; (void)length;
    (void)transferred; (void)timeout;
    return -1;
}
struct libusb_transfer *libusb_alloc_transfer(int iso_packets) {
    (void)iso_packets;
    return (struct libusb_transfer *)calloc(1, sizeof(struct libusb_transfer));
}
void libusb_free_transfer(struct libusb_transfer *transfer) { free(transfer); }
int libusb_submit_transfer(struct libusb_transfer *transfer) {
    (void)transfer; return -1;
}
int libusb_cancel_transfer(struct libusb_transfer *transfer) {
    (void)transfer; return -1;
}
void libusb_fill_bulk_transfer(struct libusb_transfer *transfer,
    libusb_device_handle *dev_handle, unsigned char endpoint,
    unsigned char *buffer, int length, libusb_transfer_cb_fn callback,
    void *user_data, unsigned int timeout) {
    if (!transfer) return;
    transfer->dev_handle = dev_handle;
    transfer->endpoint = endpoint;
    transfer->buffer = buffer;
    transfer->length = length;
    transfer->callback = callback;
    transfer->user_data = user_data;
    transfer->timeout = timeout;
}
int libusb_hotplug_register_callback(libusb_context *ctx, int events, int flags,
    int vendor_id, int product_id, int dev_class,
    libusb_hotplug_callback_fn cb_fn, void *user_data,
    libusb_hotplug_callback_handle *handle) {
    (void)ctx; (void)events; (void)flags; (void)vendor_id;
    (void)product_id; (void)dev_class; (void)cb_fn; (void)user_data; (void)handle;
    return -1;
}
void libusb_hotplug_deregister_callback(libusb_context *ctx,
    libusb_hotplug_callback_handle handle) {
    (void)ctx; (void)handle;
}
int libusb_handle_events_timeout_completed(libusb_context *ctx, struct timeval *tv, int *completed) {
    (void)ctx; (void)tv; (void)completed; return -1;
}
int libusb_handle_events(libusb_context *ctx) { (void)ctx; return -1; }
uint8_t libusb_get_bus_number(libusb_device *dev) { (void)dev; return 0; }
uint8_t libusb_get_device_address(libusb_device *dev) { (void)dev; return 0; }
libusb_device *libusb_ref_device(libusb_device *dev) { return dev; }
void libusb_unref_device(libusb_device *dev) { (void)dev; }
int libusb_reset_device(libusb_device_handle *handle) { (void)handle; return -1; }
int libusb_kernel_driver_active(libusb_device_handle *handle, int iface) {
    (void)handle; (void)iface; return 0;
}
int libusb_detach_kernel_driver(libusb_device_handle *handle, int iface) {
    (void)handle; (void)iface; return -1;
}
libusb_device *libusb_get_device(libusb_device_handle *handle) {
    (void)handle; return NULL;
}
libusb_device_handle *libusb_open_device_with_vid_pid(libusb_context *ctx,
    uint16_t vendor_id, uint16_t product_id) {
    (void)ctx; (void)vendor_id; (void)product_id; return NULL;
}
void libusb_fill_interrupt_transfer(struct libusb_transfer *transfer,
    libusb_device_handle *dev_handle, unsigned char endpoint,
    unsigned char *buffer, int length, libusb_transfer_cb_fn callback,
    void *user_data, unsigned int timeout) {
    if (!transfer) return;
    transfer->dev_handle = dev_handle;
    transfer->endpoint = endpoint;
    transfer->type = 3; /* LIBUSB_TRANSFER_TYPE_INTERRUPT */
    transfer->buffer = buffer;
    transfer->length = length;
    transfer->callback = callback;
    transfer->user_data = user_data;
    transfer->timeout = timeout;
}
void libusb_fill_control_transfer(struct libusb_transfer *transfer,
    libusb_device_handle *dev_handle, unsigned char *buffer,
    libusb_transfer_cb_fn callback, void *user_data, unsigned int timeout) {
    if (!transfer) return;
    transfer->dev_handle = dev_handle;
    transfer->type = 0; /* LIBUSB_TRANSFER_TYPE_CONTROL */
    transfer->buffer = buffer;
    transfer->callback = callback;
    transfer->user_data = user_data;
    transfer->timeout = timeout;
}
void libusb_fill_control_setup(unsigned char *buffer, uint8_t bmRequestType,
    uint8_t bRequest, uint16_t wValue, uint16_t wIndex, uint16_t wLength) {
    if (!buffer) return;
    buffer[0] = bmRequestType;
    buffer[1] = bRequest;
    buffer[2] = (unsigned char)(wValue & 0xff);
    buffer[3] = (unsigned char)(wValue >> 8);
    buffer[4] = (unsigned char)(wIndex & 0xff);
    buffer[5] = (unsigned char)(wIndex >> 8);
    buffer[6] = (unsigned char)(wLength & 0xff);
    buffer[7] = (unsigned char)(wLength >> 8);
}
const char *libusb_error_name(int errcode) {
    (void)errcode; return "LIBUSB_STUB";
}
int libusb_set_configuration(libusb_device_handle *handle, int configuration) {
    (void)handle; (void)configuration; return -1;
}
