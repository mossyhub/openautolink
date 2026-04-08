#include "openautolink/oal_session.hpp"
#include "openautolink/i_car_transport.hpp"

#ifdef PI_AA_ENABLE_AASDK_LIVE
#include "openautolink/sco_audio.hpp"
#include "openautolink/live_session.hpp"
#endif

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <iostream>
#include <sstream>
#include <unistd.h>
#include <sys/stat.h>

namespace openautolink {

OalSession::OalSession(ICarTransport& control_transport,
                       ICarTransport& video_transport,
                       ICarTransport& audio_transport,
                       HeadlessConfig config)
    : control_transport_(control_transport)
    , video_transport_(video_transport)
    , audio_transport_(audio_transport)
    , config_(std::move(config))
{
}

// ── App connection lifecycle ─────────────────────────────────────────

void OalSession::on_app_connected() {
    app_connected_ = true;
    {
        std::lock_guard<std::mutex> lock(video_mutex_);
        video_writes_.clear();
    }
    {
        std::lock_guard<std::mutex> lock(audio_mutex_);
        audio_writes_.clear();
    }

    // Send hello immediately
    send_hello();

    // If phone already connected, send phone_connected immediately
    if (phone_connected_) {
        send_phone_connected(phone_name_, "android");
        std::cerr << "[OAL] app connected (phone already connected, sent phone_connected)" << std::endl;

        // Note: SPS/PPS+IDR replay is deferred to on_video_client_connected()
        // so frames go directly to the connected video sink, not into a queue
        // that might be interleaved with live P-frames.

        // Replay active audio purposes so app starts AudioTracks
        {
            std::lock_guard<std::mutex> lock(audio_purposes_mutex_);
            for (const auto& ap : active_audio_purposes_) {
                std::ostringstream oss;
                oss << R"({"type":"audio_start","purpose":")"
                    << oal_purpose_to_string(ap.purpose)
                    << R"(","sample_rate":)" << ap.sample_rate
                    << R"(,"channels":)" << static_cast<int>(ap.channels) << "}";
                send_control_line(oss.str());
            }
            if (!active_audio_purposes_.empty()) {
                std::cerr << "[OAL] replayed " << active_audio_purposes_.size()
                          << " audio_start messages" << std::endl;
            }
        }
    } else {
        std::cerr << "[OAL] app connected (waiting for phone)" << std::endl;
    }
}

void OalSession::on_app_disconnected() {
    app_connected_ = false;
    {
        std::lock_guard<std::mutex> lock(video_mutex_);
        video_writes_.clear();
    }
    {
        std::lock_guard<std::mutex> lock(audio_mutex_);
        audio_writes_.clear();
    }
    // Clean up any in-progress update transfer
    if (update_fd_ >= 0) {
        close(update_fd_);
        update_fd_ = -1;
    }
    if (!update_temp_path_.empty()) {
        unlink(update_temp_path_.c_str());
        update_temp_path_.clear();
    }
    update_bytes_received_ = 0;
    update_expected_size_ = 0;
    update_pending_apply_ = false;
    std::cerr << "[OAL] app disconnected" << std::endl;
}

void OalSession::on_video_client_connected() {
    if (!phone_connected_) return;

    // Replay cached SPS/PPS+IDR now that the video sink is connected.
    // This ensures frames go directly to the TCP client, not into a queue
    // that might be flushed before the client connects.
#ifdef PI_AA_ENABLE_AASDK_LIVE
    if (aa_session_) {
        aa_session_->replay_cached_keyframe();
    }
#endif
}

// ── Phone lifecycle (from aasdk thread) ──────────────────────────────

void OalSession::on_phone_connected(const std::string& phone_name,
                                     const std::string& phone_type) {
    phone_connected_ = true;
    phone_name_ = phone_name;
    if (app_connected_) {
        send_phone_connected(phone_name, phone_type);
    }
    std::cerr << "[OAL] phone connected: " << phone_name << std::endl;
}

void OalSession::on_phone_disconnected(const std::string& reason) {
    phone_connected_ = false;
    session_active_ = false;
    if (app_connected_) {
        send_phone_disconnected(reason);
    }
    std::cerr << "[OAL] phone disconnected: " << reason << std::endl;

    // Apply deferred update now that the phone is gone
    if (update_pending_apply_ && !update_temp_path_.empty()) {
        update_pending_apply_ = false;
        std::cerr << "[OAL] applying deferred bridge update" << std::endl;
        if (app_connected_) {
            send_control_line(R"({"type":"bridge_update_status","status":"applying","message":"Phone disconnected — applying update..."})");
        }

        std::string apply_cmd = "/opt/openautolink/bin/apply-bridge-update.sh " + update_temp_path_ + " &";
        std::cerr << "[OAL] bridge update: running apply script" << std::endl;
        usleep(200000);
        system(apply_cmd.c_str());
    }
}

void OalSession::on_session_active() {
    session_active_ = true;
    std::cerr << "[OAL] session active" << std::endl;
}

// ── Video/Audio writes (hot path) ────────────────────────────────────

void OalSession::write_video_frame(
    uint16_t width, uint16_t height,
    uint32_t pts_ms, uint16_t flags,
    const uint8_t* codec_data, size_t codec_size)
{
    // Carry-forward fix #1: don't queue video until app is connected
    if (!app_connected_) return;

    // Build OAL video header + payload
    size_t total = OAL_VIDEO_HEADER_SIZE + codec_size;
    std::vector<uint8_t> pkt(total);

    OalVideoHeader hdr{};
    hdr.payload_length = static_cast<uint32_t>(codec_size);
    hdr.width = width;
    hdr.height = height;
    hdr.pts_ms = pts_ms;
    hdr.flags = flags;
    hdr.reserved = 0;
    pack_oal_video_header(pkt.data(), hdr);
    memcpy(pkt.data() + OAL_VIDEO_HEADER_SIZE, codec_data, codec_size);

    {
        std::lock_guard<std::mutex> lock(video_mutex_);
        video_writes_.push_back(std::move(pkt));
        video_frames_queued_++;
        // Cap buffer — drop oldest non-keyframe if possible
        while (video_writes_.size() > MAX_VIDEO_PENDING) {
            video_writes_.pop_front();
            video_frames_dropped_++;
        }
    }
}

void OalSession::write_audio_frame(
    const uint8_t* pcm_data, size_t pcm_size,
    uint8_t purpose, uint16_t sample_rate, uint8_t channels)
{
    if (!app_connected_) return;

    size_t total = OAL_AUDIO_HEADER_SIZE + pcm_size;
    std::vector<uint8_t> pkt(total);

    OalAudioHeader hdr{};
    hdr.direction = OalAudioDirection::PLAYBACK;
    hdr.purpose = purpose;
    hdr.sample_rate = sample_rate;
    hdr.channels = channels;
    hdr.payload_length = static_cast<uint32_t>(pcm_size);
    pack_oal_audio_header(pkt.data(), hdr);
    if (pcm_size > 0)
        memcpy(pkt.data() + OAL_AUDIO_HEADER_SIZE, pcm_data, pcm_size);

    // Write audio directly to TCP — bypass the queue+flush pattern.
    // Audio arrives at ~23 fps (48kHz stereo 16-bit, 8192B/frame = 42.7ms each).
    // The queue+flush pattern added unnecessary latency: the flush thread
    // sleeps 500µs between polls, and write_fully blocks on TCP.
    // Direct write from the aasdk IO thread is fine since audio frames are small.
    bool ok = audio_transport_.submit_write(pkt.data(), pkt.size());
    if (ok) {
        audio_frames_written_++;
        if (audio_frames_written_ <= 10 || audio_frames_written_ % 50 == 0) {
            std::cerr << "[OAL] audio: written=" << audio_frames_written_
                      << " size=" << pkt.size() << std::endl;
        }
    } else {
        audio_frames_queued_++; // count drops
        if (audio_frames_queued_ <= 5 || audio_frames_queued_ % 100 == 0) {
            std::cerr << "[OAL] audio WRITE FAILED: drops=" << audio_frames_queued_
                      << " connected=" << audio_transport_.is_connected() << std::endl;
        }
    }
}

// ── Flush (called by TCP transport threads) ──────────────────────────

bool OalSession::flush_one_video() {
    std::vector<uint8_t> pkt;
    {
        std::lock_guard<std::mutex> lock(video_mutex_);
        if (video_writes_.empty()) return false;
        pkt = std::move(video_writes_.front());
        video_writes_.pop_front();
    }
    bool ok = video_transport_.submit_write(pkt.data(), pkt.size());
    if (ok) {
        video_frames_written_++;
        if (video_frames_written_ <= 5 || video_frames_written_ % 300 == 0) {
            size_t pending;
            {
                std::lock_guard<std::mutex> lock(video_mutex_);
                pending = video_writes_.size();
            }
            std::cerr << "[OAL] video: written=" << video_frames_written_
                      << " queued=" << video_frames_queued_
                      << " dropped=" << video_frames_dropped_
                      << " pending=" << pending
                      << " size=" << pkt.size() << std::endl;
        }
    } else {
        video_frames_dropped_++;
    }
    return ok;
}

bool OalSession::flush_one_audio() {
    std::vector<uint8_t> pkt;
    {
        std::lock_guard<std::mutex> lock(audio_mutex_);
        if (audio_writes_.empty()) return false;
        pkt = std::move(audio_writes_.front());
        audio_writes_.pop_front();
    }
    bool ok = audio_transport_.submit_write(pkt.data(), pkt.size());
    if (ok) {
        audio_frames_written_++;
        if (audio_frames_written_ <= 5 || audio_frames_written_ % 500 == 0) {
            size_t pending;
            {
                std::lock_guard<std::mutex> lock(audio_mutex_);
                pending = audio_writes_.size();
            }
            std::cerr << "[OAL] audio: written=" << audio_frames_written_
                      << " queued=" << audio_frames_queued_
                      << " pending=" << pending
                      << " size=" << pkt.size() << std::endl;
        }
    }
    return ok;
}

// ── App → Bridge mic audio (called from audio TCP read thread) ───────

void OalSession::on_app_audio_frame(const OalAudioHeader& hdr,
                                     const uint8_t* pcm, size_t len) {
    if (hdr.direction != OalAudioDirection::MIC) return;
    if (len == 0) return;

    // Route mic audio based on purpose:
    // - CALL: forward to SCO socket (phone call uplink)
    // - ASSISTANT: forward to aasdk mic channel (AA voice activation)
    // - Other: forward to aasdk mic channel (default)
#ifdef PI_AA_ENABLE_AASDK_LIVE
    if (hdr.purpose == OalAudioPurpose::CALL && sco_audio_) {
        sco_audio_->feed_mic_audio(pcm, len);
    }

    if (aa_session_ && hdr.purpose != OalAudioPurpose::CALL) {
        // Forward to aasdk audio input channel (for AA voice/assistant)
        aa_session_->forward_oal_mic_audio(pcm, len);
    }
#endif
}

// ── Control messages: bridge → app ───────────────────────────────────

void OalSession::send_control_line(const std::string& json_line) {
    if (!app_connected_) return;
    // Append newline for JSON lines protocol
    std::string msg = json_line + "\n";
    control_transport_.submit_write(
        reinterpret_cast<const uint8_t*>(msg.data()), msg.size());
}

void OalSession::send_hello() {
    // Determine codec capabilities
    std::string caps = R"("h264")";
    if (config_.video_codec == 7) caps = R"("h264","h265")";
    else if (config_.video_codec == 5) caps = R"("h264","vp9")";

    // Compute binary SHA-256 once and cache
    if (binary_sha256_cache_.empty()) {
        binary_sha256_cache_ = compute_binary_sha256();
    }

    std::ostringstream oss;
    oss << R"({"type":"hello","version":1,"name":")"
        << oal_json_escape(config_.head_unit_name)
        << R"(","capabilities":[)" << caps
        << R"(],"video_port":5290,"audio_port":5289)"
        << R"(,"bridge_version":")" << oal_json_escape(config_.bridge_version)
        << R"(","bridge_sha256":")" << binary_sha256_cache_
        << R"("})";
    send_control_line(oss.str());
    std::cerr << "[OAL] sent hello (bridge " << config_.bridge_version << ")" << std::endl;
}

void OalSession::send_phone_connected(const std::string& phone_name,
                                       const std::string& phone_type) {
    std::ostringstream oss;
    oss << R"({"type":"phone_connected","phone_name":")"
        << oal_json_escape(phone_name)
        << R"(","phone_type":")" << oal_json_escape(phone_type) << R"("})";
    send_control_line(oss.str());
}

void OalSession::send_phone_disconnected(const std::string& reason) {
    std::ostringstream oss;
    oss << R"({"type":"phone_disconnected","reason":")"
        << oal_json_escape(reason) << R"("})";
    send_control_line(oss.str());
}

void OalSession::send_audio_start(uint8_t purpose, uint16_t sample_rate, uint8_t channels) {
    // Track active purpose for replay on reconnect
    {
        std::lock_guard<std::mutex> lock(audio_purposes_mutex_);
        // Remove existing entry for this purpose, then add updated one
        active_audio_purposes_.erase(
            std::remove_if(active_audio_purposes_.begin(), active_audio_purposes_.end(),
                [purpose](const AudioPurposeInfo& info) { return info.purpose == purpose; }),
            active_audio_purposes_.end());
        active_audio_purposes_.push_back({purpose, sample_rate, channels});
    }

    std::ostringstream oss;
    oss << R"({"type":"audio_start","purpose":")"
        << oal_purpose_to_string(purpose)
        << R"(","sample_rate":)" << sample_rate
        << R"(,"channels":)" << static_cast<int>(channels) << "}";
    send_control_line(oss.str());
}

void OalSession::send_audio_stop(uint8_t purpose) {
    std::ostringstream oss;
    oss << R"({"type":"audio_stop","purpose":")"
        << oal_purpose_to_string(purpose) << R"("})";
    send_control_line(oss.str());
}

void OalSession::send_mic_start(uint16_t sample_rate) {
    std::ostringstream oss;
    oss << R"({"type":"mic_start","sample_rate":)" << sample_rate << "}";
    send_control_line(oss.str());
}

void OalSession::send_mic_stop() {
    send_control_line(R"({"type":"mic_stop"})");
}

void OalSession::send_nav_state(const std::string& maneuver, int distance_m,
                                 const std::string& road, int eta_s,
                                 const std::string& nav_image_base64) {
    std::ostringstream oss;
    oss << R"({"type":"nav_state","maneuver":")" << oal_json_escape(maneuver)
        << R"(","distance_meters":)" << distance_m
        << R"(,"road":")" << oal_json_escape(road)
        << R"(","eta_seconds":)" << eta_s;
    if (!nav_image_base64.empty()) {
        oss << R"(,"nav_image_base64":")" << nav_image_base64 << R"(")";
    }
    oss << "}";
    send_control_line(oss.str());
}

void OalSession::send_nav_state_modern(const std::string& json_line) {
    send_control_line(json_line);
}

void OalSession::send_nav_state_clear() {
    send_control_line(R"({"type":"nav_state_clear"})");
}

void OalSession::send_media_metadata(const std::string& title, const std::string& artist,
                                      const std::string& album, int duration_ms,
                                      int position_ms, bool playing,
                                      const std::string& album_art_base64) {
    std::ostringstream oss;
    oss << R"({"type":"media_metadata","title":")" << oal_json_escape(title)
        << R"(","artist":")" << oal_json_escape(artist)
        << R"(","album":")" << oal_json_escape(album)
        << R"(","duration_ms":)" << duration_ms
        << R"(,"position_ms":)" << position_ms
        << R"(,"playing":)" << (playing ? "true" : "false");
    if (!album_art_base64.empty()) {
        oss << R"(,"album_art_base64":")" << album_art_base64 << R"(")";
    }
    oss << "}";
    send_control_line(oss.str());
}

void OalSession::send_config_echo() {
    std::string codec_name;
    switch (config_.video_codec) {
        case 3: codec_name = "h264"; break;
        case 5: codec_name = "vp9"; break;
        case 7: codec_name = "h265"; break;
        default: codec_name = "h264"; break;
    }
    std::string res_name;
    switch (config_.aa_resolution_tier) {
        case 1: res_name = "480p"; break;
        case 2: res_name = "720p"; break;
        case 3: res_name = "1080p"; break;
        case 4: res_name = "1440p"; break;
        case 5: res_name = "4k"; break;
        default: res_name = "1080p"; break;
    }

    std::ostringstream oss;
    oss << R"({"type":"config_echo")"
        << R"(,"video_codec":")" << codec_name
        << R"(","video_width":)" << config_.video_width
        << R"(,"video_height":)" << config_.video_height
        << R"(,"video_fps":)" << config_.video_fps
        << R"(,"video_dpi":)" << config_.video_dpi
        << R"(,"aa_resolution":")" << res_name
        << R"(","aa_width_margin":)" << config_.aa_ui_experiment.width_margin
        << R"(,"aa_height_margin":)" << config_.aa_ui_experiment.height_margin
        << R"(,"aa_pixel_aspect":)" << config_.aa_ui_experiment.pixel_aspect_ratio_e4
        << R"(,"drive_side":")" << (config_.left_hand_drive ? "left" : "right")
        << R"(","head_unit_name":")" << oal_json_escape(config_.head_unit_name)
        << R"(","hide_clock":)" << (config_.hide_clock ? "true" : "false")
        << R"(,"hide_phone_signal":)" << (config_.hide_phone_signal ? "true" : "false")
        << R"(,"hide_battery_level":)" << (config_.hide_battery_level ? "true" : "false")
        << R"(,"aa_stable_insets":")"
            << config_.aa_ui_experiment.initial_stable_insets.top << ","
            << config_.aa_ui_experiment.initial_stable_insets.bottom << ","
            << config_.aa_ui_experiment.initial_stable_insets.left << ","
            << config_.aa_ui_experiment.initial_stable_insets.right
        << R"(","aa_content_insets":")"
            << config_.aa_ui_experiment.initial_content_insets.top << ","
            << config_.aa_ui_experiment.initial_content_insets.bottom << ","
            << config_.aa_ui_experiment.initial_content_insets.left << ","
            << config_.aa_ui_experiment.initial_content_insets.right
        << R"("})";
    send_control_line(oss.str());
    std::cerr << "[OAL] config echo sent" << std::endl;
}

void OalSession::send_error(int code, const std::string& message) {
    std::ostringstream oss;
    oss << R"({"type":"error","code":)" << code
        << R"(,"message":")" << oal_json_escape(message) << R"("})";
    send_control_line(oss.str());
}

void OalSession::send_phone_battery(int level, int time_remaining_s, bool critical) {
    std::ostringstream oss;
    oss << R"({"type":"phone_battery","level":)" << level
        << R"(,"time_remaining_s":)" << time_remaining_s
        << R"(,"critical":)" << (critical ? "true" : "false") << "}";
    send_control_line(oss.str());
}

void OalSession::send_voice_session(bool started) {
    std::ostringstream oss;
    oss << R"({"type":"voice_session","status":")" << (started ? "start" : "end") << R"("})";
    send_control_line(oss.str());
}

void OalSession::send_phone_status(int signal_strength, const std::string& calls_json) {
    std::ostringstream oss;
    oss << R"({"type":"phone_status","signal_strength":)" << signal_strength
        << R"(,"calls":)" << calls_json << "}";
    send_control_line(oss.str());
}

// ── App → Bridge JSON dispatch ───────────────────────────────────────

void OalSession::on_app_json_line(const std::string& line) {
    if (line.empty()) return;

    // Extract "type" field
    std::string type = oal_json_extract_string(line, "type");
    if (type.empty()) {
        std::cerr << "[OAL] ignoring line without type: " << line.substr(0, 80) << std::endl;
        return;
    }

    if (type == "hello") {
        handle_app_hello(line);
    } else if (type == "touch") {
        handle_touch(line);
    } else if (type == "button") {
        handle_button(line);
    } else if (type == "gnss") {
        handle_gnss(line);
    } else if (type == "vehicle_data") {
        handle_vehicle_data(line);
    } else if (type == "config_update") {
        handle_config_update(line);
    } else if (type == "restart_services") {
        handle_restart_services(line);
    } else if (type == "keyframe_request") {
        handle_keyframe_request();
    } else if (type == "app_log") {
        handle_app_log(line);
    } else if (type == "app_telemetry") {
        handle_app_telemetry(line);
    } else if (type == "list_paired_phones") {
        handle_list_paired_phones();
    } else if (type == "switch_phone") {
        handle_switch_phone(line);
    } else if (type == "forget_phone") {
        handle_forget_phone(line);
    } else if (type == "bridge_update_offer") {
        handle_bridge_update_offer(line);
    } else if (type == "bridge_update_data") {
        handle_bridge_update_data(line);
    } else if (type == "bridge_update_complete") {
        handle_bridge_update_complete(line);
    } else {
        std::cerr << "[OAL] unknown message type: " << type << std::endl;
    }

    if (control_forward_) {
        control_forward_(line);
    }
}

void OalSession::handle_app_hello(const std::string& json) {
    int display_w = 0, display_h = 0, display_dpi = 0;
    oal_json_extract_int(json, "display_width", display_w);
    oal_json_extract_int(json, "display_height", display_h);
    oal_json_extract_int(json, "display_dpi", display_dpi);

    if (display_w > 0) config_.video_width = display_w;
    if (display_h > 0) config_.video_height = display_h;
    // Don't overwrite video_dpi with device display DPI — video_dpi is the
    // AA UI density configured via CLI/env (e.g., 160). Device DPI (e.g., 200)
    // is for display metrics only. Overwriting would cause a config diff when
    // the app sends its configured AA DPI via config_update → spurious restart.

    // Read display cutout insets (physically curved/missing screen areas)
    int cut_top = 0, cut_bottom = 0, cut_left = 0, cut_right = 0;
    oal_json_extract_int(json, "cutout_top", cut_top);
    oal_json_extract_int(json, "cutout_bottom", cut_bottom);
    oal_json_extract_int(json, "cutout_left", cut_left);
    oal_json_extract_int(json, "cutout_right", cut_right);

    // Read system bar insets (status bar, nav bar — for logging/diagnostics)
    int bar_top = 0, bar_bottom = 0, bar_left = 0, bar_right = 0;
    oal_json_extract_int(json, "bar_top", bar_top);
    oal_json_extract_int(json, "bar_bottom", bar_bottom);
    oal_json_extract_int(json, "bar_left", bar_left);
    oal_json_extract_int(json, "bar_right", bar_right);

    std::cerr << "[OAL] app hello: display=" << display_w << "x" << display_h
              << " dpi=" << display_dpi
              << " cutout=T:" << cut_top << " B:" << cut_bottom
              << " L:" << cut_left << " R:" << cut_right
              << " bars=T:" << bar_top << " B:" << bar_bottom
              << " L:" << bar_left << " R:" << bar_right << std::endl;

    // Auto-compute AA stable_insets from display cutout.
    // The app uses SCALE_TO_FIT (letterbox) — the full 16:9 video frame is visible
    // with black bars on the sides. No content is cropped, so height_margin stays 0.
    // Stable insets tell AA where the physical screen curves are (in video coords)
    // so interactive UI stays in the safe area. Maps still render edge-to-edge.
    if (display_w > 0 && display_h > 0) {
        int video_w = 1920, video_h = 1080;
        switch (config_.aa_resolution_tier) {
            case 1: video_w = 800;  video_h = 480;  break;
            case 2: video_w = 1280; video_h = 720;  break;
            case 3: video_w = 1920; video_h = 1080; break;
            case 4: video_w = 2560; video_h = 1440; break;
            case 5: video_w = 3840; video_h = 2160; break;
        }

        // Auto-compute pixel_aspect_ratio from display aspect ratio.
        // When the display is wider than 16:9, this tells AA to layout its UI
        // for the wider display so crop mode doesn't look stretched/zoomed.
        // Only auto-compute when not already set (0 = auto, >0 = manual override).
        if (config_.aa_ui_experiment.pixel_aspect_ratio_e4 == 0) {
            double display_ar = static_cast<double>(display_w) / display_h;
            double video_ar = static_cast<double>(video_w) / video_h;
            if (display_ar > video_ar * 1.05) {
                auto computed = static_cast<uint32_t>((display_ar / video_ar) * 10000);
                config_.aa_ui_experiment.pixel_aspect_ratio_e4 = computed;
                std::cerr << "[OAL] auto pixel_aspect_ratio=" << computed
                          << " (display " << display_w << "x" << display_h
                          << " AR=" << display_ar << " vs video AR=" << video_ar << ")" << std::endl;
            }
        } else {
            std::cerr << "[OAL] pixel_aspect_ratio=" << config_.aa_ui_experiment.pixel_aspect_ratio_e4
                      << " (manual override)" << std::endl;
        }

        // height_margin: NOT auto-computed for letterbox mode (no cropping).
        // User can override via Settings for experimentation.
        if (config_.aa_ui_experiment.height_margin > 0) {
            std::cerr << "[OAL] height_margin=" << config_.aa_ui_experiment.height_margin
                      << " (manual override)" << std::endl;
        }

        // Auto stable_insets from display cutout only.
        // With letterbox, scale = display_h / video_h (height fills, bars on sides).
        if (cut_top > 0 || cut_bottom > 0 || cut_left > 0 || cut_right > 0) {
            double letterbox_scale = static_cast<double>(display_h) / video_h;

            int safe_top = (cut_top > 0) ? static_cast<int>(cut_top / letterbox_scale) : 0;
            int safe_bottom = (cut_bottom > 0) ? static_cast<int>(cut_bottom / letterbox_scale) : 0;
            int safe_left = (cut_left > 0) ? static_cast<int>(cut_left / letterbox_scale) : 0;
            int safe_right = (cut_right > 0) ? static_cast<int>(cut_right / letterbox_scale) : 0;

            auto& si = config_.aa_ui_experiment.initial_stable_insets;
            auto& floor = config_.aa_ui_experiment.cutout_stable_floor;
            floor.top = safe_top; floor.bottom = safe_bottom;
            floor.left = safe_left; floor.right = safe_right;
            if (safe_top > static_cast<int>(si.top)) si.top = safe_top;
            if (safe_bottom > static_cast<int>(si.bottom)) si.bottom = safe_bottom;
            if (safe_left > static_cast<int>(si.left)) si.left = safe_left;
            if (safe_right > static_cast<int>(si.right)) si.right = safe_right;

            std::cerr << "[OAL] auto stable_insets from cutout: T:" << si.top
                      << " B:" << si.bottom << " L:" << si.left << " R:" << si.right
                      << std::endl;
        }
    }

    // Send config echo so app knows current bridge settings
    send_config_echo();
}

void OalSession::handle_touch(const std::string& json) {
#ifndef PI_AA_ENABLE_AASDK_LIVE
    (void)json;
    return;
#else
    if (!aa_session_) return;

    int action = -1;
    oal_json_extract_int(json, "action", action);
    if (action < 0) return;

    // Determine AA touch coordinate space from resolution tier
    int touch_w = 1920, touch_h = 1080;
    switch (config_.aa_resolution_tier) {
        case 1: touch_w = 800;  touch_h = 480;  break;
        case 2: touch_w = 1280; touch_h = 720;  break;
        case 3: touch_w = 1920; touch_h = 1080; break;
        case 4: touch_w = 2560; touch_h = 1440; break;
        case 5: touch_w = 3840; touch_h = 2160; break;
    }

    // Check for multi-pointer "pointers" array
    auto pointers_pos = json.find("\"pointers\"");
    if (pointers_pos != std::string::npos) {
        // Multi-touch: parse pointers array
        // Format: [{"id":0,"x":100.5,"y":200.0},{"id":1,"x":300.0,"y":400.0}]
        auto arr_start = json.find('[', pointers_pos);
        auto arr_end = json.find(']', arr_start);
        if (arr_start == std::string::npos || arr_end == std::string::npos) return;

        std::string arr = json.substr(arr_start, arr_end - arr_start + 1);
        // Simple parsing of pointer objects
        std::vector<std::tuple<uint32_t, uint32_t, uint32_t>> ptrs; // x, y, id
        size_t pos = 0;
        while ((pos = arr.find('{', pos)) != std::string::npos) {
            auto obj_end = arr.find('}', pos);
            if (obj_end == std::string::npos) break;
            std::string obj = arr.substr(pos, obj_end - pos + 1);

            float fx = 0, fy = 0;
            int id = 0;
            oal_json_extract_float(obj, "x", fx);
            oal_json_extract_float(obj, "y", fy);
            oal_json_extract_int(obj, "id", id);

            // App sends coordinates in AA pixel space already (scaled for display)
            // Clamp to touch coordinate space
            uint32_t x = static_cast<uint32_t>(std::max(0.0f, std::min(fx, static_cast<float>(touch_w))));
            uint32_t y = static_cast<uint32_t>(std::max(0.0f, std::min(fy, static_cast<float>(touch_h))));
            ptrs.emplace_back(x, y, static_cast<uint32_t>(id));

            pos = obj_end + 1;
        }

        if (ptrs.size() > 1) {
            // Multi-touch event — extract action_index for pointer_down/up identification
            int action_index = 0;
            oal_json_extract_int(json, "action_index", action_index);
            std::vector<PointerInfo> pointer_infos;
            for (auto& [x, y, id] : ptrs) {
                pointer_infos.push_back({x, y, id});
            }
            aa_session_->forward_oal_multi_touch(action, static_cast<uint32_t>(action_index), pointer_infos);
        } else if (!ptrs.empty()) {
            auto& [x, y, id] = ptrs[0];
            aa_session_->forward_oal_touch(action, x, y);
        }
    } else {
        // Single-touch: x, y fields directly
        float fx = 0, fy = 0;
        oal_json_extract_float(json, "x", fx);
        oal_json_extract_float(json, "y", fy);

        uint32_t x = static_cast<uint32_t>(std::max(0.0f, std::min(fx, static_cast<float>(touch_w))));
        uint32_t y = static_cast<uint32_t>(std::max(0.0f, std::min(fy, static_cast<float>(touch_h))));

        aa_session_->forward_oal_touch(action, x, y);
    }
#endif
}

void OalSession::handle_button(const std::string& json) {
#ifndef PI_AA_ENABLE_AASDK_LIVE
    (void)json;
    return;
#else
    if (!aa_session_) return;

    int keycode = -1;
    oal_json_extract_int(json, "keycode", keycode);
    if (keycode < 0) return;

    // "down" field: true = key pressed, false = key released
    bool down = false;
    auto down_pos = json.find("\"down\"");
    if (down_pos != std::string::npos) {
        auto val_start = json.find_first_of("tf", down_pos + 6);
        if (val_start != std::string::npos) {
            down = (json[val_start] == 't');
        }
    }

    int metastate = 0;
    oal_json_extract_int(json, "metastate", metastate);

    bool longpress = false;
    auto lp_pos = json.find("\"longpress\"");
    if (lp_pos != std::string::npos) {
        auto val_start = json.find_first_of("tf", lp_pos + 11);
        if (val_start != std::string::npos) {
            longpress = (json[val_start] == 't');
        }
    }

    aa_session_->forward_oal_button(
        static_cast<uint32_t>(keycode), down,
        static_cast<uint32_t>(metastate), longpress);
#endif
}

void OalSession::handle_gnss(const std::string& json) {
#ifdef PI_AA_ENABLE_AASDK_LIVE
    if (!aa_session_) return;
    std::string nmea = oal_json_extract_string(json, "nmea");
    if (!nmea.empty()) {
        aa_session_->forward_oal_gnss(nmea);
    }
#else
    (void)json;
#endif
}

void OalSession::handle_vehicle_data(const std::string& json) {
#ifdef PI_AA_ENABLE_AASDK_LIVE
    if (!aa_session_) return;
    aa_session_->forward_oal_vehicle_data(json);
#else
    (void)json;
#endif
}

void OalSession::handle_config_update(const std::string& json) {
    bool config_changed = false;
    bool infra_changed = false;  // WiFi/BT/identity changes (don't restart AA session)

    std::string codec = oal_json_extract_string(json, "video_codec");
    if (!codec.empty()) {
        int new_codec = config_.video_codec;
        if (codec == "h264") new_codec = 3;
        else if (codec == "h265") new_codec = 7;
        else if (codec == "vp9") new_codec = 5;
        if (new_codec != config_.video_codec) {
            config_.video_codec = new_codec;
            config_changed = true;
        }
    }

    int fps = 0;
    if (oal_json_extract_int(json, "video_fps", fps) && fps > 0 && fps != config_.video_fps) {
        config_.video_fps = fps;
        config_changed = true;
    }

    std::string aa_res = oal_json_extract_string(json, "aa_resolution");
    if (!aa_res.empty()) {
        int tier = config_.aa_resolution_tier;
        if (aa_res == "480p") tier = 1;
        else if (aa_res == "720p") tier = 2;
        else if (aa_res == "1080p") tier = 3;
        else if (aa_res == "1440p") tier = 4;
        else if (aa_res == "4k") tier = 5;
        if (tier != config_.aa_resolution_tier) {
            config_.aa_resolution_tier = tier;
            config_changed = true;
        }
    }

    int dpi = 0;
    if (oal_json_extract_int(json, "aa_dpi", dpi) && dpi > 0 && dpi != config_.video_dpi) {
        config_.video_dpi = dpi;
        config_changed = true;
    }

    int wm = 0;
    if (oal_json_extract_int(json, "aa_width_margin", wm) && wm >= 0 &&
        wm != static_cast<int>(config_.aa_ui_experiment.width_margin)) {
        config_.aa_ui_experiment.width_margin = wm;
        config_changed = true;
        std::cerr << "[OAL] width_margin override: " << wm << std::endl;
    }
    int hm = 0;
    if (oal_json_extract_int(json, "aa_height_margin", hm) && hm >= 0 &&
        hm != static_cast<int>(config_.aa_ui_experiment.height_margin)) {
        config_.aa_ui_experiment.height_margin = hm;
        config_changed = true;
        std::cerr << "[OAL] height_margin override: " << hm << std::endl;
    }
    int pa = 0;
    if (oal_json_extract_int(json, "aa_pixel_aspect", pa) && pa > 0 &&
        pa != static_cast<int>(config_.aa_ui_experiment.pixel_aspect_ratio_e4)) {
        config_.aa_ui_experiment.pixel_aspect_ratio_e4 = pa;
        config_changed = true;
        std::cerr << "[OAL] pixel_aspect_ratio override: " << pa << std::endl;
    }

    std::string drive_side = oal_json_extract_string(json, "drive_side");
    if (!drive_side.empty()) {
        bool lhd = (drive_side == "left");
        if (lhd != config_.left_hand_drive) {
            config_.left_hand_drive = lhd;
            config_changed = true;
        }
    }

    std::string head_unit = oal_json_extract_string(json, "head_unit_name");
    if (!head_unit.empty() && head_unit != config_.head_unit_name) {
        config_.head_unit_name = head_unit;
        infra_changed = true;
    }

    // AA UI flags
    std::string hide_clock_str = oal_json_extract_string(json, "hide_clock");
    if (!hide_clock_str.empty()) {
        bool hide = (hide_clock_str == "true");
        if (hide != config_.hide_clock) {
            config_.hide_clock = hide;
            config_changed = true;
            std::cerr << "[OAL] hide_clock updated: " << (hide ? "true" : "false") << std::endl;
        }
    }

    std::string hide_phone_signal_str = oal_json_extract_string(json, "hide_phone_signal");
    if (!hide_phone_signal_str.empty()) {
        bool hide = (hide_phone_signal_str == "true");
        if (hide != config_.hide_phone_signal) {
            config_.hide_phone_signal = hide;
            config_changed = true;
            std::cerr << "[OAL] hide_phone_signal updated: " << (hide ? "true" : "false") << std::endl;
        }
    }

    std::string hide_battery_str = oal_json_extract_string(json, "hide_battery_level");
    if (!hide_battery_str.empty()) {
        bool hide = (hide_battery_str == "true");
        if (hide != config_.hide_battery_level) {
            config_.hide_battery_level = hide;
            config_changed = true;
            std::cerr << "[OAL] hide_battery_level updated: " << (hide ? "true" : "false") << std::endl;
        }
    }

    // AA safe area insets — format: "top,bottom,left,right"
    auto parse_insets = [](const std::string& str, HeadlessConfig::UiInsets& out) -> bool {
        unsigned long parsed[4] = {};
        size_t start = 0;
        for (int i = 0; i < 4; ++i) {
            const size_t end = str.find(',', start);
            const bool last = (i == 3);
            if ((end == std::string::npos) != last) return false;
            try {
                parsed[i] = std::stoul(str.substr(start, last ? std::string::npos : end - start));
            } catch (...) { return false; }
            if (!last) start = end + 1;
        }
        out.top = static_cast<uint32_t>(parsed[0]);
        out.bottom = static_cast<uint32_t>(parsed[1]);
        out.left = static_cast<uint32_t>(parsed[2]);
        out.right = static_cast<uint32_t>(parsed[3]);
        return true;
    };

    std::string stable_insets = oal_json_extract_string(json, "aa_stable_insets");
    if (!stable_insets.empty()) {
        HeadlessConfig::UiInsets insets;
        if (parse_insets(stable_insets, insets)) {
            // Merge user-configured insets with auto-computed cutout floor
            const auto& floor = config_.aa_ui_experiment.cutout_stable_floor;
            insets.top = std::max(insets.top, floor.top);
            insets.bottom = std::max(insets.bottom, floor.bottom);
            insets.left = std::max(insets.left, floor.left);
            insets.right = std::max(insets.right, floor.right);
            auto& cur = config_.aa_ui_experiment.initial_stable_insets;
            if (insets.top != cur.top || insets.bottom != cur.bottom ||
                insets.left != cur.left || insets.right != cur.right) {
                cur = insets;
                config_changed = true;
                std::cerr << "[OAL] safe area insets updated: "
                          << "T:" << insets.top << " B:" << insets.bottom
                          << " L:" << insets.left << " R:" << insets.right << std::endl;
            }
        }
    }

    std::string content_insets = oal_json_extract_string(json, "aa_content_insets");
    if (!content_insets.empty()) {
        HeadlessConfig::UiInsets insets;
        if (parse_insets(content_insets, insets)) {
            auto& cur = config_.aa_ui_experiment.initial_content_insets;
            if (insets.top != cur.top || insets.bottom != cur.bottom ||
                insets.left != cur.left || insets.right != cur.right) {
                cur = insets;
                config_changed = true;
                std::cerr << "[OAL] content insets updated: " << content_insets << std::endl;
            }
        }
    }

    std::string bt_mac_val = oal_json_extract_string(json, "bt_mac");
    if (!bt_mac_val.empty() && bt_mac_val != config_.bt_mac) {
        config_.bt_mac = bt_mac_val;
        infra_changed = true;
    }

    std::string phone_mode = oal_json_extract_string(json, "phone_mode");
    std::string wifi_band = oal_json_extract_string(json, "wifi_band");
    std::string wifi_country = oal_json_extract_string(json, "wifi_country");
    std::string wifi_ssid = oal_json_extract_string(json, "wifi_ssid");
    std::string wifi_password = oal_json_extract_string(json, "wifi_password");

    if (!phone_mode.empty() || !wifi_band.empty() || !wifi_country.empty() ||
        !wifi_ssid.empty() || !wifi_password.empty() || !head_unit.empty() || !bt_mac_val.empty()) {
        infra_changed = true;
    }

    // Helper: sanitize a value for safe use in sed commands (strip shell-dangerous chars)
    auto sanitize = [](const std::string& val) -> std::string {
        std::string safe;
        for (char c : val) {
            if (c != '\'' && c != '"' && c != '\\' && c != '`' && c != '$' &&
                c != '!' && c != ';' && c != '|' && c != '&' && c != '\n' && c != '\r') {
                safe += c;
            }
        }
        return safe;
    };

    if (config_changed || infra_changed) {
        std::cerr << "[OAL] config updated from app" << std::endl;

        // Persist to env file
        std::string env_update;
        if (!codec.empty())
            env_update += "sed -i 's/^OAL_AA_CODEC=.*/OAL_AA_CODEC=" + sanitize(codec) + "/' /etc/openautolink.env 2>/dev/null\n";
        if (fps > 0)
            env_update += "sed -i 's/^OAL_AA_FPS=.*/OAL_AA_FPS=" + std::to_string(fps) + "/' /etc/openautolink.env 2>/dev/null\n";
        if (!aa_res.empty())
            env_update += "sed -i 's/^OAL_AA_RESOLUTION=.*/OAL_AA_RESOLUTION=" + sanitize(aa_res) + "/' /etc/openautolink.env 2>/dev/null\n";
        if (dpi > 0)
            env_update += "sed -i 's/^OAL_AA_DPI=.*/OAL_AA_DPI=" + std::to_string(dpi) + "/' /etc/openautolink.env 2>/dev/null\n";
        // Margin/pixel_aspect overrides: >0 writes value, 0 writes blank (= auto on next boot)
        {
            auto margin_to_env = [](int val) -> std::string { return val > 0 ? std::to_string(val) : ""; };
            env_update += "sed -i 's/^OAL_AA_WIDTH_MARGIN=.*/OAL_AA_WIDTH_MARGIN=" + margin_to_env(wm) + "/' /etc/openautolink.env 2>/dev/null\n";
            env_update += "sed -i 's/^OAL_AA_HEIGHT_MARGIN=.*/OAL_AA_HEIGHT_MARGIN=" + margin_to_env(hm) + "/' /etc/openautolink.env 2>/dev/null\n";
            env_update += "sed -i 's/^OAL_AA_PIXEL_ASPECT_E4=.*/OAL_AA_PIXEL_ASPECT_E4=" + margin_to_env(pa) + "/' /etc/openautolink.env 2>/dev/null\n";
        }
        if (!phone_mode.empty())
            env_update += "sed -i 's/^OAL_PHONE_MODE=.*/OAL_PHONE_MODE=" + sanitize(phone_mode) + "/' /etc/openautolink.env 2>/dev/null\n";
        if (!wifi_band.empty())
            env_update += "sed -i 's/^OAL_WIRELESS_BAND=.*/OAL_WIRELESS_BAND=" + sanitize(wifi_band) + "/' /etc/openautolink.env 2>/dev/null\n";
        if (!wifi_country.empty())
            env_update += "sed -i 's/^OAL_WIRELESS_COUNTRY=.*/OAL_WIRELESS_COUNTRY=" + sanitize(wifi_country) + "/' /etc/openautolink.env 2>/dev/null\n";
        if (!wifi_ssid.empty())
            env_update += "sed -i 's/^OAL_WIRELESS_SSID=.*/OAL_WIRELESS_SSID=" + sanitize(wifi_ssid) + "/' /etc/openautolink.env 2>/dev/null\n";
        if (!wifi_password.empty())
            env_update += "sed -i 's/^OAL_WIRELESS_PASSWORD=.*/OAL_WIRELESS_PASSWORD=" + sanitize(wifi_password) + "/' /etc/openautolink.env 2>/dev/null\n";
        if (!head_unit.empty())
            env_update += "sed -i 's/^OAL_HEAD_UNIT_NAME=.*/OAL_HEAD_UNIT_NAME=" + sanitize(head_unit) + "/' /etc/openautolink.env 2>/dev/null\n";
        if (!bt_mac_val.empty())
            env_update += "sed -i 's/^OAL_BT_MAC=.*/OAL_BT_MAC=" + sanitize(bt_mac_val) + "/' /etc/openautolink.env 2>/dev/null\n";
        if (!stable_insets.empty())
            env_update += "sed -i 's/^OAL_AA_INIT_STABLE_INSETS=.*/OAL_AA_INIT_STABLE_INSETS=" + sanitize(stable_insets) + "/' /etc/openautolink.env 2>/dev/null\n";
        if (!content_insets.empty())
            env_update += "sed -i 's/^OAL_AA_INIT_CONTENT_INSETS=.*/OAL_AA_INIT_CONTENT_INSETS=" + sanitize(content_insets) + "/' /etc/openautolink.env 2>/dev/null\n";
        if (!hide_clock_str.empty())
            env_update += "sed -i 's/^OAL_AA_HIDE_CLOCK=.*/OAL_AA_HIDE_CLOCK=" + sanitize(hide_clock_str) + "/' /etc/openautolink.env 2>/dev/null\n";
        if (!hide_phone_signal_str.empty())
            env_update += "sed -i 's/^OAL_AA_HIDE_PHONE_SIGNAL=.*/OAL_AA_HIDE_PHONE_SIGNAL=" + sanitize(hide_phone_signal_str) + "/' /etc/openautolink.env 2>/dev/null\n";
        if (!hide_battery_str.empty())
            env_update += "sed -i 's/^OAL_AA_HIDE_BATTERY=.*/OAL_AA_HIDE_BATTERY=" + sanitize(hide_battery_str) + "/' /etc/openautolink.env 2>/dev/null\n";
        if (!env_update.empty())
            system(env_update.c_str());

        // Only restart AA session for stream-affecting changes (codec, fps, resolution, dpi, drive side)
        if (config_changed) {
#ifdef PI_AA_ENABLE_AASDK_LIVE
            if (aa_session_) {
                aa_session_->restart_with_config(config_);
                send_phone_disconnected("config_changed");
            }
#endif
        }
    }

    send_config_echo();
}

void OalSession::handle_restart_services(const std::string& json) {
    // Restart bridge and optionally WiFi/BT services.
    // The app sends this after config_update to force the phone to renegotiate (e.g., codec change).
    bool restart_wireless = oal_json_extract_string(json, "wireless") == "true";
    bool restart_bt = oal_json_extract_string(json, "bluetooth") == "true";

    std::cerr << "[OAL] restart_services: wireless=" << restart_wireless
              << " bt=" << restart_bt << std::endl;

    // Build the systemd restart command. The bridge service restarts itself last,
    // which terminates this process — the app will reconnect automatically.
    std::string cmd;
    if (restart_wireless) {
        cmd += "sudo systemctl restart openautolink-wireless 2>/dev/null; ";
    }
    if (restart_bt) {
        cmd += "sudo systemctl restart openautolink-bt 2>/dev/null; ";
    }
    // Always restart the bridge itself (this kills us, app auto-reconnects)
    cmd += "sudo systemctl restart openautolink.service &";

    // Notify app that restart is happening
    send_control_line(R"({"type":"event","event_type":"restarting","wireless":)" +
                 std::string(restart_wireless ? "true" : "false") +
                 R"(,"bluetooth":)" + std::string(restart_bt ? "true" : "false") + "}");

    // Short delay so the event reaches the app before we die
    usleep(200000);
    system(cmd.c_str());
}

void OalSession::handle_keyframe_request() {
    std::cerr << "[OAL] keyframe request from app" << std::endl;
#ifdef PI_AA_ENABLE_AASDK_LIVE
    if (aa_session_) {
        // Replay cached SPS/PPS+IDR for instant recovery (e.g. app surface recreated
        // after navigating away and back — video TCP stayed open so
        // on_video_client_connected() didn't fire).
        aa_session_->replay_cached_keyframe();
        // Also ask the phone for a fresh IDR so content updates promptly.
        aa_session_->request_fresh_idr();
    }
#endif
}

void OalSession::handle_app_log(const std::string& json) {
    // Extract fields and format for journalctl output
    std::string level = oal_json_extract_string(json, "level");
    std::string tag   = oal_json_extract_string(json, "tag");
    std::string msg   = oal_json_extract_string(json, "msg");

    // Pad level to 5 chars for alignment
    while (level.size() < 5) level += ' ';

    std::cerr << "[CAR] " << level << " " << tag << "\t" << msg << std::endl;
}

void OalSession::handle_app_telemetry(const std::string& json) {
    // Strip the "type" and "ts" wrappers — just forward the raw JSON content
    // so the full telemetry snapshot is visible in the journal
    std::cerr << "[CAR] TELEM " << json << std::endl;
}

void OalSession::send_paired_phones() {
    // Query BlueZ for paired devices via bluetoothctl.
    // Output format: "Device XX:XX:XX:XX:XX:XX DeviceName"
    FILE* pipe = popen("bluetoothctl devices Paired 2>/dev/null || bluetoothctl paired-devices 2>/dev/null", "r");
    if (!pipe) {
        std::cerr << "[OAL] failed to run bluetoothctl for paired devices" << std::endl;
        send_control_line(R"({"type":"paired_phones","phones":[]})");
        return;
    }

    // Also get connected devices to mark connection status
    std::string connected_macs;
    FILE* conn_pipe = popen("bluetoothctl devices Connected 2>/dev/null", "r");
    if (conn_pipe) {
        char cbuf[256];
        while (fgets(cbuf, sizeof(cbuf), conn_pipe)) {
            connected_macs += cbuf;
        }
        pclose(conn_pipe);
    }

    std::ostringstream oss;
    oss << R"({"type":"paired_phones","phones":[)";

    char buf[256];
    bool first = true;
    while (fgets(buf, sizeof(buf), pipe)) {
        // Parse "Device XX:XX:XX:XX:XX:XX Name..."
        std::string line(buf);
        // Trim trailing newline
        while (!line.empty() && (line.back() == '\n' || line.back() == '\r'))
            line.pop_back();

        if (line.substr(0, 7) != "Device ") continue;
        if (line.size() < 25) continue; // "Device " (7) + MAC (17) = 24 minimum

        std::string mac = line.substr(7, 17);
        std::string name = line.size() > 25 ? line.substr(25) : "";

        bool connected = connected_macs.find(mac) != std::string::npos;

        if (!first) oss << ",";
        first = false;
        oss << R"({"mac":")" << oal_json_escape(mac)
            << R"(","name":")" << oal_json_escape(name)
            << R"(","connected":)" << (connected ? "true" : "false") << "}";
    }
    pclose(pipe);

    oss << "]}";
    send_control_line(oss.str());
    std::cerr << "[OAL] sent paired_phones" << std::endl;
}

void OalSession::handle_list_paired_phones() {
    std::cerr << "[OAL] app requested paired phones list" << std::endl;
    send_paired_phones();
}

void OalSession::handle_switch_phone(const std::string& json) {
    std::string mac = oal_json_extract_string(json, "mac");
    if (mac.empty()) {
        std::cerr << "[OAL] switch_phone: no MAC provided" << std::endl;
        send_error(400, "switch_phone requires a mac field");
        return;
    }

    std::cerr << "[OAL] switching to phone: " << mac << std::endl;

    // Disconnect current phone first (if connected), then connect to target.
    // This runs asynchronously via bluetoothctl to avoid blocking the control thread.
    // The BT script's event handler will pick up the new connection and trigger
    // the RFCOMM WiFi credential exchange, leading to a new AA session.
    std::string cmd = "( ";

    // Disconnect all currently connected devices
    cmd += "for dev in $(bluetoothctl devices Connected 2>/dev/null | awk '{print $2}'); do "
           "bluetoothctl disconnect $dev 2>/dev/null; "
           "done; ";

    // Small delay for cleanup
    cmd += "sleep 1; ";

    // Connect to target device
    cmd += "bluetoothctl connect " + mac + " 2>/dev/null; ";

    cmd += ") &";

    int ret = system(cmd.c_str());
    if (ret != 0) {
        std::cerr << "[OAL] switch_phone: command launch failed" << std::endl;
    }

    // Notify app that phone disconnected (the new connection will trigger
    // a phone_connected message when the AA session starts)
    if (phone_connected_) {
        on_phone_disconnected("phone_switch");
    }
}

void OalSession::handle_forget_phone(const std::string& json) {
    std::string mac = oal_json_extract_string(json, "mac");
    if (mac.empty()) {
        std::cerr << "[OAL] forget_phone: no MAC provided" << std::endl;
        send_error(400, "forget_phone requires a mac field");
        return;
    }

    // Validate MAC format (XX:XX:XX:XX:XX:XX) to prevent command injection
    if (mac.size() != 17) {
        send_error(400, "invalid MAC address");
        return;
    }
    for (size_t i = 0; i < 17; ++i) {
        if (i % 3 == 2) {
            if (mac[i] != ':') { send_error(400, "invalid MAC address"); return; }
        } else {
            char c = mac[i];
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f'))) {
                send_error(400, "invalid MAC address");
                return;
            }
        }
    }

    std::cerr << "[OAL] forgetting phone: " << mac << std::endl;

    // If the phone being forgotten is currently connected, disconnect first
    std::string cmd = "bluetoothctl disconnect " + mac + " 2>/dev/null; "
                      "bluetoothctl remove " + mac + " 2>/dev/null";
    int ret = system(cmd.c_str());
    if (ret != 0) {
        std::cerr << "[OAL] forget_phone: bluetoothctl command returned " << ret << std::endl;
    }

    // If we just removed the currently connected phone, notify
    if (phone_connected_) {
        on_phone_disconnected("phone_forgotten");
    }

    // Send updated paired phones list
    send_paired_phones();
}

// ── Bridge update handlers ───────────────────────────────────────────

std::string OalSession::compute_binary_sha256() const {
    // Read /proc/self/exe and compute SHA-256 via sha256sum
    FILE* pipe = popen("sha256sum /proc/self/exe 2>/dev/null", "r");
    if (!pipe) return "";
    char buf[128];
    std::string result;
    if (fgets(buf, sizeof(buf), pipe)) {
        result = buf;
        // sha256sum output: "hexhash  filename\n"
        auto pos = result.find(' ');
        if (pos != std::string::npos) result = result.substr(0, pos);
        // Trim whitespace
        while (!result.empty() && (result.back() == '\n' || result.back() == '\r' || result.back() == ' '))
            result.pop_back();
    }
    pclose(pipe);
    return result;
}

void OalSession::handle_bridge_update_offer(const std::string& json) {
    std::string version = oal_json_extract_string(json, "version");
    std::string sha256 = oal_json_extract_string(json, "sha256");
    int size = 0;
    oal_json_extract_int(json, "size", size);

    // Parse auto_apply flag (default true if not present)
    std::string auto_apply_str = oal_json_extract_string(json, "auto_apply");
    update_auto_apply_ = (auto_apply_str != "false");

    std::cerr << "[OAL] bridge update offer: v" << version << " size=" << size
              << " auto_apply=" << (update_auto_apply_ ? "true" : "false") << std::endl;

    // Check if updates are disabled (dev mode)
    if (config_.update_mode == "disabled") {
        std::cerr << "[OAL] bridge update rejected: update mode disabled" << std::endl;
        send_control_line(R"({"type":"bridge_update_reject","reason":"disabled"})");
        return;
    }

    if (size <= 0 || sha256.empty()) {
        std::cerr << "[OAL] bridge update rejected: invalid offer" << std::endl;
        send_control_line(R"({"type":"bridge_update_reject","reason":"invalid_offer"})");
        return;
    }

    // Clean up any previous partial update
    if (update_fd_ >= 0) {
        close(update_fd_);
        update_fd_ = -1;
    }
    if (!update_temp_path_.empty()) {
        unlink(update_temp_path_.c_str());
    }

    // Create temp file for incoming binary
    update_temp_path_ = "/tmp/openautolink-update-XXXXXX";
    // mkstemp needs a non-const char*
    std::vector<char> tmpl(update_temp_path_.begin(), update_temp_path_.end());
    tmpl.push_back('\0');
    update_fd_ = mkstemp(tmpl.data());
    if (update_fd_ < 0) {
        std::cerr << "[OAL] bridge update: failed to create temp file" << std::endl;
        send_control_line(R"({"type":"bridge_update_reject","reason":"disk_space"})");
        return;
    }
    update_temp_path_ = tmpl.data();
    update_expected_sha_ = sha256;
    update_expected_size_ = static_cast<size_t>(size);
    update_bytes_received_ = 0;

    std::cerr << "[OAL] bridge update accepted, writing to " << update_temp_path_ << std::endl;
    send_control_line(R"({"type":"bridge_update_accept"})");
}

// Simple base64 decode — no external dependency needed for this one-shot use
static std::vector<uint8_t> base64_decode(const std::string& input) {
    static const int b64_table[256] = {
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,62,-1,-1,-1,63,
        52,53,54,55,56,57,58,59,60,61,-1,-1,-1,-1,-1,-1,
        -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9,10,11,12,13,14,
        15,16,17,18,19,20,21,22,23,24,25,-1,-1,-1,-1,-1,
        -1,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,
        41,42,43,44,45,46,47,48,49,50,51,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
    };

    std::vector<uint8_t> out;
    out.reserve(input.size() * 3 / 4);
    uint32_t val = 0;
    int bits = 0;
    for (unsigned char c : input) {
        if (c == '=' || c == '\n' || c == '\r') continue;
        int v = b64_table[c];
        if (v < 0) continue;
        val = (val << 6) | static_cast<uint32_t>(v);
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out.push_back(static_cast<uint8_t>((val >> bits) & 0xFF));
        }
    }
    return out;
}

void OalSession::handle_bridge_update_data(const std::string& json) {
    if (update_fd_ < 0) {
        std::cerr << "[OAL] bridge update data: no active update" << std::endl;
        return;
    }

    std::string data_b64 = oal_json_extract_string(json, "data");
    if (data_b64.empty()) return;

    auto decoded = base64_decode(data_b64);
    if (decoded.empty()) return;

    ssize_t written = write(update_fd_, decoded.data(), decoded.size());
    if (written < 0 || static_cast<size_t>(written) != decoded.size()) {
        std::cerr << "[OAL] bridge update: write failed" << std::endl;
        close(update_fd_);
        update_fd_ = -1;
        unlink(update_temp_path_.c_str());
        send_control_line(R"({"type":"bridge_update_status","status":"failed","message":"Write failed"})");
        return;
    }

    update_bytes_received_ += decoded.size();

    // Log progress every ~500KB
    if (update_bytes_received_ <= decoded.size() ||
        (update_bytes_received_ / 500000) != ((update_bytes_received_ - decoded.size()) / 500000)) {
        int pct = (update_expected_size_ > 0)
            ? static_cast<int>(update_bytes_received_ * 100 / update_expected_size_) : 0;
        std::cerr << "[OAL] bridge update: received " << update_bytes_received_
                  << "/" << update_expected_size_ << " bytes (" << pct << "%)" << std::endl;
    }
}

void OalSession::handle_bridge_update_complete(const std::string& json) {
    if (update_fd_ < 0) {
        std::cerr << "[OAL] bridge update complete: no active update" << std::endl;
        return;
    }

    std::string expected_sha = oal_json_extract_string(json, "sha256");
    if (expected_sha.empty()) expected_sha = update_expected_sha_;

    // Close the file so we can verify it
    close(update_fd_);
    update_fd_ = -1;

    // Verify size
    struct stat st;
    if (stat(update_temp_path_.c_str(), &st) != 0 || st.st_size <= 0) {
        std::cerr << "[OAL] bridge update: temp file stat failed" << std::endl;
        unlink(update_temp_path_.c_str());
        send_control_line(R"({"type":"bridge_update_status","status":"failed","message":"Empty or missing file"})");
        return;
    }

    if (update_expected_size_ > 0 && static_cast<size_t>(st.st_size) != update_expected_size_) {
        std::cerr << "[OAL] bridge update: size mismatch (got " << st.st_size
                  << ", expected " << update_expected_size_ << ")" << std::endl;
        unlink(update_temp_path_.c_str());
        send_control_line(R"({"type":"bridge_update_status","status":"failed","message":"Size mismatch"})");
        return;
    }

    // Verify SHA-256
    std::string cmd = "sha256sum " + update_temp_path_ + " 2>/dev/null";
    FILE* pipe = popen(cmd.c_str(), "r");
    std::string actual_sha;
    if (pipe) {
        char buf[128];
        if (fgets(buf, sizeof(buf), pipe)) {
            actual_sha = buf;
            auto pos = actual_sha.find(' ');
            if (pos != std::string::npos) actual_sha = actual_sha.substr(0, pos);
            while (!actual_sha.empty() && (actual_sha.back() == '\n' || actual_sha.back() == '\r'))
                actual_sha.pop_back();
        }
        pclose(pipe);
    }

    if (actual_sha.empty() || actual_sha != expected_sha) {
        std::cerr << "[OAL] bridge update: SHA-256 mismatch (got " << actual_sha
                  << ", expected " << expected_sha << ")" << std::endl;
        unlink(update_temp_path_.c_str());
        send_control_line(R"({"type":"bridge_update_status","status":"failed","message":"SHA-256 mismatch"})");
        return;
    }

    std::cerr << "[OAL] bridge update: verified OK (" << st.st_size << " bytes)" << std::endl;
    send_control_line(R"({"type":"bridge_update_status","status":"verified","message":"Update verified, applying..."})");

    // Make executable
    chmod(update_temp_path_.c_str(), 0755);

    // If phone is connected and auto_apply is off, defer the restart.
    // When auto_apply is on (default), apply immediately — the 5-second
    // interruption and auto-reconnect is acceptable for most users.
    if (phone_connected_ && !update_auto_apply_) {
        std::cerr << "[OAL] bridge update: phone connected, auto_apply=false — deferring" << std::endl;
        send_control_line(R"({"type":"bridge_update_status","status":"deferred","message":"Update ready — will apply when phone disconnects"})");
        update_pending_apply_ = true;
        return;
    }

    // Apply update via the update script
    send_control_line(R"({"type":"bridge_update_status","status":"applying","message":"Swapping binary..."})");

    std::string apply_cmd = "/opt/openautolink/bin/apply-bridge-update.sh " + update_temp_path_ + " &";
    std::cerr << "[OAL] bridge update: running apply script" << std::endl;

    // Give the status message time to reach the app before we restart
    usleep(200000);
    system(apply_cmd.c_str());
    // The apply script will restart the service, killing this process.
    // The app's auto-reconnect will handle reconnection.
}

} // namespace openautolink
