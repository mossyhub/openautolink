#pragma once

#include <cstdint>

namespace openautolink {

// Abstract transport interface for car app communication.
// Implemented by TcpCarTransport (Ethernet TCP).
// Speaks OAL protocol (JSON control, binary video/audio).
class ICarTransport {
public:
    virtual ~ICarTransport() = default;

    // Write raw bytes to the connected client (thread-safe).
    virtual bool submit_write(const uint8_t* data, size_t len) = 0;

    // Write raw pre-built data (thread-safe).
    virtual bool write_raw(const uint8_t* data, size_t len) = 0;

    virtual bool is_running() const = 0;

    // Check if a client is connected.
    virtual bool is_connected() const = 0;
};

} // namespace openautolink
