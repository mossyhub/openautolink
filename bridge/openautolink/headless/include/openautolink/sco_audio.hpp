#pragma once

// SCO audio capture/playback for BT HFP phone calls.
//
// Architecture:
//   - A BT SCO socket carries 8kHz/16kHz PCM between phone and bridge.
//   - This class opens/manages the SCO connection and bridges it to OAL:
//       Phone call audio (SCO read)  → OAL audio frame (purpose=CALL, dir=PLAYBACK) → app
//       App mic audio (OAL dir=MIC)  → SCO write → phone
//   - SCO is low-latency, fixed-rate — the read/write loops run on
//     dedicated threads with SCHED_FIFO priority when possible.
//
// Lifecycle:
//   1. Python BT script establishes HFP SLC (AT commands on RFCOMM)
//   2. When a call starts, phone opens SCO connection to the bridge
//   3. BlueZ accepts the SCO connection (kernel-level)
//   4. This class listens for incoming SCO connections on the adapter
//   5. On SCO connect: start read/write threads
//   6. On SCO disconnect: stop threads, notify OalSession
//
// Thread model:
//   - sco_listen_thread_: accepts incoming SCO connections (blocking)
//   - sco_read_thread_: reads PCM from SCO → feeds to OalSession::write_audio_frame
//   - sco_write_thread_: drains mic queue → writes to SCO socket
//   - All threads are owned by ScoAudio, started/stopped with start()/stop().

#include <atomic>
#include <cstdint>
#include <deque>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace openautolink {

class OalSession;

class ScoAudio {
public:
    explicit ScoAudio(OalSession& oal_session);
    ~ScoAudio();

    ScoAudio(const ScoAudio&) = delete;
    ScoAudio& operator=(const ScoAudio&) = delete;

    // Start listening for incoming SCO connections on hci0.
    void start();

    // Stop all threads and close SCO socket.
    void stop();

    // Feed mic audio from the app (OAL direction=MIC, purpose=CALL).
    // Called from the audio TCP read thread — must be thread-safe.
    void feed_mic_audio(const uint8_t* pcm, size_t len);

    // Is an SCO connection currently active?
    bool is_connected() const { return sco_fd_.load() >= 0; }

    // SCO codec: CVSD (8kHz) or mSBC (16kHz).
    uint16_t sample_rate() const { return sample_rate_; }

private:
    void listen_thread_func();
    void read_thread_func(int fd);
    void write_thread_func(int fd);

    OalSession& oal_session_;

    std::atomic<int> sco_fd_{-1};
    int listen_fd_ = -1;
    std::atomic<bool> running_{false};
    uint16_t sample_rate_ = 8000;  // CVSD default; 16000 for mSBC

    std::thread listen_thread_;
    std::thread read_thread_;
    std::thread write_thread_;

    // Mic audio queue: app→bridge mic PCM waiting to be written to SCO
    std::mutex mic_mutex_;
    std::deque<std::vector<uint8_t>> mic_queue_;
    static constexpr size_t MAX_MIC_PENDING = 30;
};

} // namespace openautolink
