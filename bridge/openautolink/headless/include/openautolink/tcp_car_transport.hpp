#pragma once

#include "openautolink/oal_log.hpp"

#include <atomic>
#include <cstdint>
#include <cstring>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <arpa/inet.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include "openautolink/i_car_transport.hpp"
#include "openautolink/oal_protocol.hpp"

namespace openautolink {

// TCP transport for the OAL protocol.
// Serves data over a TCP socket to the car app.
// Car app connects via Ethernet (USB NIC or CDC-ECM gadget).
//
// The app connects as TCP client; this is the server.
// Three channels: control (JSON lines), video (binary), audio (binary).
class TcpCarTransport : public ICarTransport {
public:
    using LineCallback = std::function<void(const std::string& line)>;
    using EnableCallback = std::function<void()>;
    using DisconnectCallback = std::function<void()>;
    using FlushCallback = std::function<bool()>;
    using AudioFrameCallback = std::function<void(const OalAudioHeader& hdr, const uint8_t* pcm, size_t len)>;

    explicit TcpCarTransport(int port) : port_(port) {}
    ~TcpCarTransport() { stop(); stop_discovery(); }

    TcpCarTransport(const TcpCarTransport&) = delete;
    TcpCarTransport& operator=(const TcpCarTransport&) = delete;

    // Start UDP discovery responder on port+1 (e.g. 5289).
    // Responds to "OALINK_DISCOVER" with "OALINK_HERE:<port>".
    // Also publishes mDNS service via avahi so the bridge is
    // discoverable as "_openautolink._tcp" / "openautolink.local".
    void start_discovery() {
        discovery_running_.store(true);

        // mDNS: publish service via avahi-publish-service (non-blocking subprocess)
        char cmd[256];
        snprintf(cmd, sizeof(cmd),
            "avahi-publish-service OpenAutoLink _openautolink._tcp %d &", port_);
        if (system(cmd) == 0) {
            oal_log("[TcpCar] mDNS: published _openautolink._tcp on port %d\n", port_);
        }

        discovery_thread_ = std::thread([this]() {
            int udp_fd = socket(AF_INET, SOCK_DGRAM, 0);
            if (udp_fd < 0) {
                oal_log("[TcpCar] discovery socket failed: %s\n", strerror(errno));
                return;
            }

            int opt = 1;
            setsockopt(udp_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
            setsockopt(udp_fd, SOL_SOCKET, SO_BROADCAST, &opt, sizeof(opt));

            struct sockaddr_in addr{};
            addr.sin_family = AF_INET;
            addr.sin_addr.s_addr = INADDR_ANY;
            addr.sin_port = htons(port_ + 1);  // discovery port = TCP port + 1

            if (bind(udp_fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
                oal_log("[TcpCar] discovery bind(%d) failed: %s\n", port_ + 1, strerror(errno));
                close(udp_fd);
                return;
            }

            oal_log("[TcpCar] discovery responder on UDP %d\n", port_ + 1);

            // Set receive timeout so we can check discovery_running_ periodically
            struct timeval tv{2, 0};
            setsockopt(udp_fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

            char buf[64];
            while (discovery_running_.load()) {
                struct sockaddr_in from{};
                socklen_t from_len = sizeof(from);
                ssize_t n = recvfrom(udp_fd, buf, sizeof(buf) - 1, 0,
                                     (struct sockaddr*)&from, &from_len);
                if (n <= 0) continue;
                buf[n] = '\0';

                if (strncmp(buf, "OALINK_DISCOVER", 15) == 0) {
                    char resp[64];
                    int resp_len = snprintf(resp, sizeof(resp), "OALINK_HERE:%d", port_);

                    char ip_str[INET_ADDRSTRLEN];
                    inet_ntop(AF_INET, &from.sin_addr, ip_str, sizeof(ip_str));
                    oal_log("[TcpCar] discovery request from %s — responding\n", ip_str);

                    sendto(udp_fd, resp, resp_len, 0,
                           (struct sockaddr*)&from, from_len);
                }
            }

            close(udp_fd);
            oal_log("[TcpCar] discovery responder stopped\n");
        });
    }

    void stop_discovery() {
        discovery_running_.store(false);
        if (discovery_thread_.joinable()) discovery_thread_.join();
    }

    void stop() {
        running_.store(false);
        // Close listen socket to unblock accept()
        if (listen_fd_ >= 0) { close(listen_fd_); listen_fd_ = -1; }
        std::lock_guard<std::mutex> lock(write_mutex_);
        if (client_fd_ >= 0) { close(client_fd_); client_fd_ = -1; }
    }

    // ── JSON-lines control channel ───────────────────────────────────

    // Run in OAL mode: reads newline-delimited JSON lines from app.
    // For video/audio channels, use run_oal_sink (bridge→app, write-only + flush).
    void run_oal_control(LineCallback line_cb, EnableCallback connect_cb,
                         DisconnectCallback disconnect_cb) {
        running_.store(true);

        listen_fd_ = socket(AF_INET, SOCK_STREAM, 0);
        if (listen_fd_ < 0) {
            oal_log("[TcpCar:%d] socket() failed: %s\n", port_, strerror(errno));
            return;
        }

        int opt = 1;
        setsockopt(listen_fd_, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

        struct sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_addr.s_addr = INADDR_ANY;
        addr.sin_port = htons(port_);

        if (bind(listen_fd_, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
            oal_log("[TcpCar:%d] bind failed: %s\n", port_, strerror(errno));
            close(listen_fd_); listen_fd_ = -1;
            return;
        }

        if (listen(listen_fd_, 1) < 0) {
            oal_log("[TcpCar:%d] listen failed: %s\n", port_, strerror(errno));
            close(listen_fd_); listen_fd_ = -1;
            return;
        }

        oal_log("[TcpCar:%d] OAL control listening\n", port_);

        // poll()-based control loop: multiplex listen_fd_ and client_fd_.
        // When a new connection arrives while we already have a client,
        // close the old one immediately and accept the new one.
        // This eliminates zombie half-open sockets that black-hole messages
        // when the app reconnects (e.g., display mode change, Settings nav).
        int client = -1;
        std::string line_buf;

        while (running_.load()) {
            struct pollfd fds[2];
            int nfds = 0;

            // Always watch the listen socket for new connections
            fds[0].fd = listen_fd_;
            fds[0].events = POLLIN;
            nfds = 1;

            // If we have a client, also watch it for data/disconnect
            if (client >= 0) {
                fds[1].fd = client;
                fds[1].events = POLLIN;
                nfds = 2;
            }

            int ret = poll(fds, nfds, 1000); // 1s timeout for running_ check
            if (ret < 0) {
                if (errno == EINTR) continue;
                oal_log("[TcpCar:%d] poll error: %s\n", port_, strerror(errno));
                break;
            }
            if (ret == 0) continue; // timeout — recheck running_

            // New connection pending on listen socket?
            if (fds[0].revents & POLLIN) {
                struct sockaddr_in client_addr{};
                socklen_t client_len = sizeof(client_addr);
                int new_client = accept(listen_fd_, (struct sockaddr*)&client_addr, &client_len);
                if (new_client < 0) {
                    if (running_.load()) oal_log("[TcpCar:%d] accept failed: %s\n", port_, strerror(errno));
                    break;
                }

                char ip_str[INET_ADDRSTRLEN];
                inet_ntop(AF_INET, &client_addr.sin_addr, ip_str, sizeof(ip_str));

                // Evict old client if one exists
                if (client >= 0) {
                    oal_log("[TcpCar:%d] new connection from %s — evicting previous client\n", port_, ip_str);
                    if (disconnect_cb) disconnect_cb();
                    {
                        std::lock_guard<std::mutex> lock(write_mutex_);
                        close(client);
                        client_fd_ = -1;
                    }
                    line_buf.clear();
                } else {
                    oal_log("[TcpCar:%d] app connected from %s\n", port_, ip_str);
                }

                int nodelay = 1;
                setsockopt(new_client, IPPROTO_TCP, TCP_NODELAY, &nodelay, sizeof(nodelay));

                client = new_client;
                {
                    std::lock_guard<std::mutex> lock(write_mutex_);
                    client_fd_ = client;
                }

                if (connect_cb) connect_cb();
            }

            // Data or disconnect from current client?
            if (nfds > 1 && (fds[1].revents & (POLLIN | POLLHUP | POLLERR))) {
                char buf[4096];
                ssize_t n = ::read(client, buf, sizeof(buf));
                if (n <= 0) {
                    oal_log("[TcpCar:%d] client disconnected\n", port_);
                    if (disconnect_cb) disconnect_cb();
                    {
                        std::lock_guard<std::mutex> lock(write_mutex_);
                        close(client);
                        client_fd_ = -1;
                    }
                    client = -1;
                    line_buf.clear();
                    oal_log("[TcpCar:%d] control session ended, waiting for reconnect\n", port_);
                } else {
                    for (ssize_t i = 0; i < n; i++) {
                        if (buf[i] == '\n') {
                            if (!line_buf.empty() && line_cb) {
                                line_cb(line_buf);
                            }
                            line_buf.clear();
                        } else {
                            line_buf += buf[i];
                        }
                    }
                }
            }
        }

        // Cleanup on shutdown
        if (client >= 0) {
            if (disconnect_cb) disconnect_cb();
            std::lock_guard<std::mutex> lock(write_mutex_);
            close(client);
            client_fd_ = -1;
        }

        close(listen_fd_);
        listen_fd_ = -1;
        running_.store(false);
    }

    // Run in OAL mode: write-only sink with flush. For video/audio channels.
    void run_oal_sink(EnableCallback connect_cb, FlushCallback flush_cb) {
        running_.store(true);

        listen_fd_ = socket(AF_INET, SOCK_STREAM, 0);
        if (listen_fd_ < 0) {
            oal_log("[TcpCar:%d] socket() failed: %s\n", port_, strerror(errno));
            return;
        }

        int opt = 1;
        setsockopt(listen_fd_, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

        struct sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_addr.s_addr = INADDR_ANY;
        addr.sin_port = htons(port_);

        if (bind(listen_fd_, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
            oal_log("[TcpCar:%d] bind failed: %s\n", port_, strerror(errno));
            close(listen_fd_); listen_fd_ = -1;
            return;
        }

        if (listen(listen_fd_, 1) < 0) {
            oal_log("[TcpCar:%d] listen failed: %s\n", port_, strerror(errno));
            close(listen_fd_); listen_fd_ = -1;
            return;
        }

        oal_log("[TcpCar:%d] OAL sink listening\n", port_);

        while (running_.load()) {
            struct sockaddr_in client_addr{};
            socklen_t client_len = sizeof(client_addr);
            int client = accept(listen_fd_, (struct sockaddr*)&client_addr, &client_len);
            if (client < 0) {
                if (running_.load()) oal_log("[TcpCar:%d] accept failed: %s\n", port_, strerror(errno));
                break;
            }

            int nodelay = 1;
            setsockopt(client, IPPROTO_TCP, TCP_NODELAY, &nodelay, sizeof(nodelay));

            {
                std::lock_guard<std::mutex> lock(write_mutex_);
                client_fd_ = client;
            }

            oal_log("[TcpCar:%d] sink client connected\n", port_);
            if (connect_cb) connect_cb();

            // Flush loop — continuously drain pending writes
            while (running_.load()) {
                if (flush_cb && flush_cb()) {
                    continue;
                }
                // Nothing to flush — check if client is still connected
                // Use recv with MSG_PEEK | MSG_DONTWAIT as a non-blocking connection check
                uint8_t peek;
                ssize_t r = recv(client, &peek, 1, MSG_PEEK | MSG_DONTWAIT);
                if (r == 0) {
                    // Client disconnected
                    oal_log("[TcpCar:%d] sink client disconnected\n", port_);
                    break;
                }
                std::this_thread::sleep_for(std::chrono::microseconds(500));
            }

            {
                std::lock_guard<std::mutex> lock(write_mutex_);
                close(client_fd_);
                client_fd_ = -1;
            }
        }

        close(listen_fd_);
        listen_fd_ = -1;
        running_.store(false);
    }

    // ── Bidirectional audio channel ──────────────────────────────────

    // Run OAL audio channel: bidirectional (flush writes + read mic frames).
    // Reads 8-byte OAL audio headers from the app for mic data (direction=1),
    // while flushing bridge→app playback frames in parallel.
    void run_oal_audio(EnableCallback connect_cb, FlushCallback flush_cb,
                       AudioFrameCallback mic_cb) {
        running_.store(true);

        listen_fd_ = socket(AF_INET, SOCK_STREAM, 0);
        if (listen_fd_ < 0) {
            oal_log("[TcpCar:%d] socket() failed: %s\n", port_, strerror(errno));
            return;
        }

        int opt = 1;
        setsockopt(listen_fd_, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

        struct sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_addr.s_addr = INADDR_ANY;
        addr.sin_port = htons(port_);

        if (bind(listen_fd_, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
            oal_log("[TcpCar:%d] bind failed: %s\n", port_, strerror(errno));
            close(listen_fd_); listen_fd_ = -1;
            return;
        }

        if (listen(listen_fd_, 1) < 0) {
            oal_log("[TcpCar:%d] listen failed: %s\n", port_, strerror(errno));
            close(listen_fd_); listen_fd_ = -1;
            return;
        }

        oal_log("[TcpCar:%d] OAL audio (bidirectional) listening\n", port_);

        while (running_.load()) {
            struct sockaddr_in client_addr{};
            socklen_t client_len = sizeof(client_addr);
            int client = accept(listen_fd_, (struct sockaddr*)&client_addr, &client_len);
            if (client < 0) {
                if (running_.load()) oal_log("[TcpCar:%d] accept failed: %s\n", port_, strerror(errno));
                break;
            }

            int nodelay = 1;
            setsockopt(client, IPPROTO_TCP, TCP_NODELAY, &nodelay, sizeof(nodelay));

            {
                std::lock_guard<std::mutex> lock(write_mutex_);
                client_fd_ = client;
            }

            oal_log("[TcpCar:%d] audio client connected (bidirectional)\n", port_);
            if (connect_cb) connect_cb();

            // Flush thread: drain bridge→app audio writes
            std::atomic<bool> session_running{true};
            std::thread flush_thread([&flush_cb, &session_running]() {
                while (session_running.load()) {
                    if (flush_cb && flush_cb()) continue;
                    std::this_thread::sleep_for(std::chrono::microseconds(500));
                }
            });

            // Read loop: read app→bridge mic frames (OAL 8-byte header + PCM)
            uint8_t hdr_buf[OAL_AUDIO_HEADER_SIZE];
            while (running_.load()) {
                // Non-blocking peek to check for incoming data
                uint8_t peek;
                ssize_t r = recv(client, &peek, 1, MSG_PEEK | MSG_DONTWAIT);
                if (r == 0) {
                    // Client disconnected
                    oal_log("[TcpCar:%d] audio client disconnected\n", port_);
                    break;
                }
                if (r < 0) {
                    // EAGAIN/EWOULDBLOCK = no data yet, yield
                    if (errno == EAGAIN || errno == EWOULDBLOCK) {
                        std::this_thread::sleep_for(std::chrono::microseconds(500));
                        continue;
                    }
                    // Real error
                    oal_log("[TcpCar:%d] audio recv error: %s\n", port_, strerror(errno));
                    break;
                }

                // Data available — read the 8-byte header (blocking)
                if (!read_fully(client, hdr_buf, OAL_AUDIO_HEADER_SIZE)) {
                    oal_log("[TcpCar:%d] audio header read failed\n", port_);
                    break;
                }

                OalAudioHeader hdr{};
                if (!parse_oal_audio_header(hdr_buf, hdr)) {
                    oal_log("[TcpCar:%d] bad audio header\n", port_);
                    break;
                }

                if (hdr.payload_length == 0 || hdr.payload_length > 1024 * 1024) {
                    oal_log("[TcpCar:%d] bad audio payload length: %u\n",
                            port_, hdr.payload_length);
                    break;
                }

                // Read PCM payload
                std::vector<uint8_t> pcm(hdr.payload_length);
                if (!read_fully(client, pcm.data(), hdr.payload_length)) {
                    oal_log("[TcpCar:%d] audio payload read failed\n", port_);
                    break;
                }

                // Forward to callback (mic data from app)
                if (mic_cb) {
                    mic_cb(hdr, pcm.data(), pcm.size());
                }
            }

            session_running.store(false);
            flush_thread.join();

            {
                std::lock_guard<std::mutex> lock(write_mutex_);
                close(client_fd_);
                client_fd_ = -1;
            }
        }

        close(listen_fd_);
        listen_fd_ = -1;
        running_.store(false);
    }

    // Write raw pre-built data — thread-safe.
    bool write_raw(const uint8_t* data, size_t len) override {
        std::lock_guard<std::mutex> lock(write_mutex_);
        if (client_fd_ < 0) return false;
        return write_fully(client_fd_, data, len);
    }

    // submit_write is the same as write_raw for TCP (no AIO needed).
    bool submit_write(const uint8_t* data, size_t len) override {
        return write_raw(data, len);
    }

    bool is_running() const override { return running_.load(); }

    bool is_connected() const override {
        // Lock-free check (client_fd_ is only set/cleared under write_mutex_,
        // but reading a stale value is harmless here)
        return client_fd_ >= 0;
    }

private:
    static bool read_fully(int fd, uint8_t* buf, size_t count) {
        size_t total = 0;
        while (total < count) {
            ssize_t n = ::read(fd, buf + total, count - total);
            if (n <= 0) return false;
            total += n;
        }
        return true;
    }

    static bool write_fully(int fd, const uint8_t* buf, size_t count) {
        size_t total = 0;
        while (total < count) {
            ssize_t n = ::write(fd, buf + total, count - total);
            if (n <= 0) return false;
            total += n;
        }
        return true;
    }

    int port_;
    int listen_fd_ = -1;
    int client_fd_ = -1;
    std::mutex write_mutex_;
    std::atomic<bool> running_{false};

    // UDP discovery
    std::thread discovery_thread_;
    std::atomic<bool> discovery_running_{false};
};

} // namespace openautolink
