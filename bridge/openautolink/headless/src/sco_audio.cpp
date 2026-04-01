#include "openautolink/sco_audio.hpp"
#include "openautolink/oal_session.hpp"
#include "openautolink/oal_protocol.hpp"

#include <cerrno>
#include <cstring>
#include <iostream>

#include <bluetooth/bluetooth.h>
#include <bluetooth/sco.h>
#include <sys/socket.h>
#include <unistd.h>

namespace openautolink {

ScoAudio::ScoAudio(OalSession& oal_session)
    : oal_session_(oal_session)
{
}

ScoAudio::~ScoAudio() {
    stop();
}

void ScoAudio::start() {
    if (running_.load()) return;
    running_.store(true);

    listen_thread_ = std::thread(&ScoAudio::listen_thread_func, this);
    std::cerr << "[SCO] started listening for incoming SCO connections" << std::endl;
}

void ScoAudio::stop() {
    running_.store(false);

    // Close listen socket to unblock accept()
    if (listen_fd_ >= 0) {
        close(listen_fd_);
        listen_fd_ = -1;
    }

    // Close active SCO socket
    int fd = sco_fd_.exchange(-1);
    if (fd >= 0) {
        close(fd);
    }

    if (listen_thread_.joinable()) listen_thread_.join();
    if (read_thread_.joinable()) read_thread_.join();
    if (write_thread_.joinable()) write_thread_.join();

    std::cerr << "[SCO] stopped" << std::endl;
}

void ScoAudio::feed_mic_audio(const uint8_t* pcm, size_t len) {
    if (sco_fd_.load() < 0 || len == 0) return;

    std::lock_guard<std::mutex> lock(mic_mutex_);
    mic_queue_.emplace_back(pcm, pcm + len);
    while (mic_queue_.size() > MAX_MIC_PENDING) {
        mic_queue_.pop_front();
    }
}

void ScoAudio::listen_thread_func() {
    // Create SCO listening socket on the default adapter (BDADDR_ANY).
    // BlueZ will route incoming SCO connections here when HFP audio starts.
    listen_fd_ = socket(PF_BLUETOOTH, SOCK_SEQPACKET, BTPROTO_SCO);
    if (listen_fd_ < 0) {
        std::cerr << "[SCO] socket() failed: " << strerror(errno) << std::endl;
        return;
    }

    struct sockaddr_sco addr{};
    addr.sco_family = AF_BLUETOOTH;
    bacpy(&addr.sco_bdaddr, BDADDR_ANY);

    if (bind(listen_fd_, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) < 0) {
        std::cerr << "[SCO] bind() failed: " << strerror(errno) << std::endl;
        close(listen_fd_);
        listen_fd_ = -1;
        return;
    }

    if (listen(listen_fd_, 1) < 0) {
        std::cerr << "[SCO] listen() failed: " << strerror(errno) << std::endl;
        close(listen_fd_);
        listen_fd_ = -1;
        return;
    }

    std::cerr << "[SCO] listening for incoming SCO connections" << std::endl;

    while (running_.load()) {
        struct sockaddr_sco remote_addr{};
        socklen_t addr_len = sizeof(remote_addr);
        int client = accept(listen_fd_, reinterpret_cast<struct sockaddr*>(&remote_addr),
                            &addr_len);
        if (client < 0) {
            if (running_.load()) {
                std::cerr << "[SCO] accept() failed: " << strerror(errno) << std::endl;
            }
            break;
        }

        // Get the remote BT address for logging
        char addr_str[18];
        ba2str(&remote_addr.sco_bdaddr, addr_str);
        std::cerr << "[SCO] connection from " << addr_str << std::endl;

        // Determine codec from SCO socket options
        // If mSBC is negotiated, MTU is typically 60 bytes (for 16kHz)
        // CVSD uses 48 bytes (for 8kHz)
        struct sco_options opts{};
        socklen_t opts_len = sizeof(opts);
        if (getsockopt(client, SOL_SCO, SCO_OPTIONS, &opts, &opts_len) == 0) {
            std::cerr << "[SCO] MTU=" << opts.mtu << std::endl;
            // mSBC typically uses MTU=60 with 16kHz, CVSD uses MTU=48 with 8kHz
            sample_rate_ = (opts.mtu >= 60) ? 16000 : 8000;
        } else {
            sample_rate_ = 8000;  // Default to CVSD
        }

        std::cerr << "[SCO] codec: " << (sample_rate_ == 16000 ? "mSBC" : "CVSD")
                  << " (" << sample_rate_ << " Hz)" << std::endl;

        // Close any previous SCO connection
        int old_fd = sco_fd_.exchange(client);
        if (old_fd >= 0) {
            close(old_fd);
        }

        // Wait for old read/write threads to finish
        if (read_thread_.joinable()) read_thread_.join();
        if (write_thread_.joinable()) write_thread_.join();

        // Clear stale mic data
        {
            std::lock_guard<std::mutex> lock(mic_mutex_);
            mic_queue_.clear();
        }

        // Notify app that call audio is starting
        oal_session_.send_audio_start(OalAudioPurpose::CALL, sample_rate_, 1);

        // Start read/write threads for this SCO connection
        read_thread_ = std::thread(&ScoAudio::read_thread_func, this, client);
        write_thread_ = std::thread(&ScoAudio::write_thread_func, this, client);

        // Wait for threads to finish (SCO disconnected)
        read_thread_.join();
        write_thread_.join();

        // Notify that call audio stopped
        oal_session_.send_audio_stop(OalAudioPurpose::CALL);

        // Clear fd
        sco_fd_.store(-1);
        std::cerr << "[SCO] disconnected" << std::endl;
    }
}

void ScoAudio::read_thread_func(int fd) {
    // Read PCM from SCO socket → forward as OAL audio with purpose=CALL
    uint8_t buf[1024];
    uint64_t frames = 0;

    while (running_.load() && sco_fd_.load() == fd) {
        ssize_t n = read(fd, buf, sizeof(buf));
        if (n <= 0) {
            if (n < 0 && errno == EINTR) continue;
            std::cerr << "[SCO] read ended: " << (n == 0 ? "EOF" : strerror(errno)) << std::endl;
            break;
        }

        frames++;
        if (frames <= 3 || frames % 500 == 0) {
            std::cerr << "[SCO] read frame #" << frames << " size=" << n << std::endl;
        }

        // Forward to OAL session as call audio (bridge→app, purpose=CALL)
        oal_session_.write_audio_frame(
            buf, static_cast<size_t>(n),
            OalAudioPurpose::CALL, sample_rate_, 1);
    }

    // Signal write thread to stop by closing our end
    int expected = fd;
    sco_fd_.compare_exchange_strong(expected, -1);
}

void ScoAudio::write_thread_func(int fd) {
    // Drain mic queue → write PCM to SCO socket (uplink to phone)
    while (running_.load() && sco_fd_.load() == fd) {
        std::vector<uint8_t> pcm;
        {
            std::lock_guard<std::mutex> lock(mic_mutex_);
            if (mic_queue_.empty()) {
                // No data — yield briefly
            } else {
                pcm = std::move(mic_queue_.front());
                mic_queue_.pop_front();
            }
        }

        if (pcm.empty()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            continue;
        }

        // Write PCM to SCO socket
        size_t total = 0;
        while (total < pcm.size() && sco_fd_.load() == fd) {
            ssize_t n = write(fd, pcm.data() + total, pcm.size() - total);
            if (n <= 0) {
                if (n < 0 && errno == EINTR) continue;
                std::cerr << "[SCO] write failed: " << strerror(errno) << std::endl;
                return;
            }
            total += static_cast<size_t>(n);
        }
    }
}

} // namespace openautolink
