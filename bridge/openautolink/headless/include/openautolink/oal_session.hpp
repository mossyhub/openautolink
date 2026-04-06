#pragma once

#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "openautolink/oal_protocol.hpp"
#include "openautolink/headless_config.hpp"

namespace openautolink {

// Forward declarations
class ICarTransport;
class ScoAudio;
#ifdef PI_AA_ENABLE_AASDK_LIVE
class LiveAasdkSession;
#endif

// OAL session state machine.
//
// Speaks the OAL protocol (JSON control, binary video/audio)
// to the car app. The app already speaks OAL,
// so once this lands, end-to-end streaming works.
//
// Thread model:
//   - The TCP run() thread calls on_app_json_line / on_enable / flush_one_pending.
//   - The aasdk IO thread calls write_video_frame / write_audio_frame / on_phone_*.
//   - pending_writes_ is the only shared state, protected by pending_mutex_.
class OalSession {
public:
    // Callback to forward control messages to stdout (NDJSON).
    using ControlForwardCallback = std::function<void(const std::string& json_line)>;

    OalSession(ICarTransport& control_transport,
               ICarTransport& video_transport,
               ICarTransport& audio_transport,
               HeadlessConfig config);

    // ── App-side events (called from control TCP read thread) ────────

    // Handle a JSON line received from the car app on control channel.
    void on_app_json_line(const std::string& line);

    // Called when car app connects (TCP accept).
    void on_app_connected();

    // Called when car app disconnects.
    void on_app_disconnected();

    // Called when the video TCP client connects.
    // Replays cached SPS/PPS+IDR so the app gets video immediately.
    void on_video_client_connected();

    // Write ONE pending video packet. Returns true if a packet was written.
    bool flush_one_video();

    // Write ONE pending audio packet. Returns true if a packet was written.
    bool flush_one_audio();

    // Handle an incoming audio frame from the app (mic data, direction=1).
    void on_app_audio_frame(const OalAudioHeader& hdr,
                            const uint8_t* pcm, size_t len);

    // ── Phone-side lifecycle (called from aasdk IO thread) ───────────

    void on_phone_connected(const std::string& phone_name = "",
                            const std::string& phone_type = "android");
    void on_phone_disconnected(const std::string& reason = "");
    void on_session_active();

    // ── Media writes (called from aasdk IO thread) ───────────────────

    // Queue a video frame for delivery over the video channel.
    void write_video_frame(
        uint16_t width, uint16_t height,
        uint32_t pts_ms, uint16_t flags,
        const uint8_t* codec_data, size_t codec_size);

    // Queue an audio frame for delivery over the audio channel.
    void write_audio_frame(
        const uint8_t* pcm_data, size_t pcm_size,
        uint8_t purpose, uint16_t sample_rate, uint8_t channels);

    // ── Control messages (bridge → app) ──────────────────────────────

    void send_hello();
    void send_phone_connected(const std::string& phone_name, const std::string& phone_type);
    void send_phone_disconnected(const std::string& reason);
    void send_audio_start(uint8_t purpose, uint16_t sample_rate, uint8_t channels);
    void send_audio_stop(uint8_t purpose);
    void send_mic_start(uint16_t sample_rate = 16000);
    void send_mic_stop();
    void send_nav_state(const std::string& maneuver, int distance_m,
                        const std::string& road, int eta_s,
                        const std::string& nav_image_base64 = "");
    void send_nav_state_modern(const std::string& json_line);
    void send_nav_state_clear();
    void send_media_metadata(const std::string& title, const std::string& artist,
                             const std::string& album, int duration_ms, int position_ms, bool playing,
                             const std::string& album_art_base64 = "");
    void send_config_echo();
    void send_error(int code, const std::string& message);
    void send_phone_battery(int level, int time_remaining_s, bool critical);
    void send_voice_session(bool started);
    void send_phone_status(int signal_strength, const std::string& calls_json);
    void send_paired_phones();

    // ── Configuration ────────────────────────────────────────────────

    void set_control_forward(ControlForwardCallback cb) { control_forward_ = std::move(cb); }
    void set_sco_audio(ScoAudio* sco) { sco_audio_ = sco; }
#ifdef PI_AA_ENABLE_AASDK_LIVE
    void set_aa_session(LiveAasdkSession* session) { aa_session_ = session; }
#endif

    bool phone_connected() const { return phone_connected_; }
    bool app_connected() const { return app_connected_; }
    const HeadlessConfig& config() const { return config_; }

private:
    // Send a JSON line to the app via the control channel.
    void send_control_line(const std::string& json_line);

    // Handle specific app→bridge JSON message types.
    void handle_app_hello(const std::string& json);
    void handle_touch(const std::string& json);
    void handle_button(const std::string& json);
    void handle_gnss(const std::string& json);
    void handle_vehicle_data(const std::string& json);
    void handle_config_update(const std::string& json);
    void handle_restart_services(const std::string& json);
    void handle_keyframe_request();
    void handle_app_log(const std::string& json);
    void handle_app_telemetry(const std::string& json);
    void handle_list_paired_phones();
    void handle_switch_phone(const std::string& json);

    ICarTransport& control_transport_;
    ICarTransport& video_transport_;
    ICarTransport& audio_transport_;
    HeadlessConfig config_;

    // Session state
    bool app_connected_ = false;
    bool phone_connected_ = false;
    bool session_active_ = false;
    std::string phone_name_;

    // Thread-safe pending video write queue (aasdk thread → video TCP thread)
    std::mutex video_mutex_;
    std::deque<std::vector<uint8_t>> video_writes_;
    static constexpr size_t MAX_VIDEO_PENDING = 120;

    // Thread-safe pending audio write queue
    std::mutex audio_mutex_;
    std::deque<std::vector<uint8_t>> audio_writes_;

    // Track active audio purposes for replay on reconnect
    struct AudioPurposeInfo {
        uint8_t purpose;
        uint16_t sample_rate;
        uint8_t channels;
    };
    std::mutex audio_purposes_mutex_;
    std::vector<AudioPurposeInfo> active_audio_purposes_;
    static constexpr size_t MAX_AUDIO_PENDING = 60;

    // Stats
    uint64_t video_frames_queued_ = 0;
    uint64_t video_frames_written_ = 0;
    uint64_t video_frames_dropped_ = 0;
    uint64_t audio_frames_queued_ = 0;
    uint64_t audio_frames_written_ = 0;

    ControlForwardCallback control_forward_;
    ScoAudio* sco_audio_ = nullptr;
#ifdef PI_AA_ENABLE_AASDK_LIVE
    LiveAasdkSession* aa_session_ = nullptr;
#endif
};

} // namespace openautolink
