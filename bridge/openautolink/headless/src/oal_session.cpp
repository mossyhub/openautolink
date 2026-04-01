#include "openautolink/oal_session.hpp"
#include "openautolink/i_car_transport.hpp"
#include "openautolink/sco_audio.hpp"

#ifdef PI_AA_ENABLE_AASDK_LIVE
#include "openautolink/live_session.hpp"
#endif

#include <cstring>
#include <iostream>
#include <sstream>

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

        // Replay cached SPS/PPS+IDR so app gets video immediately
#ifdef PI_AA_ENABLE_AASDK_LIVE
        if (aa_session_) {
            aa_session_->replay_cached_keyframe();
        }
#endif
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
    std::cerr << "[OAL] app disconnected" << std::endl;
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

    {
        std::lock_guard<std::mutex> lock(audio_mutex_);
        audio_writes_.push_back(std::move(pkt));
        audio_frames_queued_++;
        while (audio_writes_.size() > MAX_AUDIO_PENDING) {
            audio_writes_.pop_front();
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
    if (ok) audio_frames_written_++;
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
    if (hdr.purpose == OalAudioPurpose::CALL && sco_audio_) {
        sco_audio_->feed_mic_audio(pcm, len);
    }

#ifdef PI_AA_ENABLE_AASDK_LIVE
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

    std::ostringstream oss;
    oss << R"({"type":"hello","version":1,"name":")"
        << oal_json_escape(config_.head_unit_name)
        << R"(","capabilities":[)" << caps
        << R"(],"video_port":5290,"audio_port":5289})";
    send_control_line(oss.str());
    std::cerr << "[OAL] sent hello" << std::endl;
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
                                 const std::string& road, int eta_s) {
    std::ostringstream oss;
    oss << R"({"type":"nav_state","maneuver":")" << oal_json_escape(maneuver)
        << R"(","distance_meters":)" << distance_m
        << R"(,"road":")" << oal_json_escape(road)
        << R"(","eta_seconds":)" << eta_s << "}";
    send_control_line(oss.str());
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
        << R"(,"aa_resolution":")" << res_name << R"("})";
    send_control_line(oss.str());
    std::cerr << "[OAL] config echo sent" << std::endl;
}

void OalSession::send_error(int code, const std::string& message) {
    std::ostringstream oss;
    oss << R"({"type":"error","code":)" << code
        << R"(,"message":")" << oal_json_escape(message) << R"("})";
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
    } else if (type == "keyframe_request") {
        handle_keyframe_request();
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
    if (display_dpi > 0) config_.video_dpi = display_dpi;

    std::cerr << "[OAL] app hello: display=" << display_w << "x" << display_h
              << " dpi=" << display_dpi << std::endl;

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
            // Multi-touch event
            std::vector<HeadlessInputHandler::PointerInfo> pointer_infos;
            for (auto& [x, y, id] : ptrs) {
                pointer_infos.push_back({x, y, id});
            }
            aa_session_->forward_oal_multi_touch(action, 0, pointer_infos);
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

    if (config_changed) {
        std::cerr << "[OAL] config updated from app" << std::endl;

        // Persist to env file
        std::string env_update;
        if (!codec.empty())
            env_update += "sed -i 's/^OAL_AA_CODEC=.*/OAL_AA_CODEC=" + codec + "/' /etc/openautolink.env 2>/dev/null\n";
        if (fps > 0)
            env_update += "sed -i 's/^OAL_AA_FPS=.*/OAL_AA_FPS=" + std::to_string(fps) + "/' /etc/openautolink.env 2>/dev/null\n";
        if (!aa_res.empty())
            env_update += "sed -i 's/^OAL_AA_RESOLUTION=.*/OAL_AA_RESOLUTION=" + aa_res + "/' /etc/openautolink.env 2>/dev/null\n";
        if (!env_update.empty())
            system(env_update.c_str());

        // Restart AA session with new config
#ifdef PI_AA_ENABLE_AASDK_LIVE
        if (aa_session_) {
            aa_session_->restart_with_config(config_);
            send_phone_disconnected("config_changed");
        }
#endif
    }

    send_config_echo();
}

void OalSession::handle_keyframe_request() {
    std::cerr << "[OAL] keyframe request from app" << std::endl;
#ifdef PI_AA_ENABLE_AASDK_LIVE
    if (aa_session_) {
        // Carry-forward fix #4: bypass rate limit on first keyframe after reconnect
        // Always replay cached + request fresh IDR from phone
        aa_session_->replay_cached_keyframe();
        aa_session_->request_fresh_idr();
    }
#endif
}

} // namespace openautolink
