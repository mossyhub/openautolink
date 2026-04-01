#pragma once

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace openautolink {

// ── OAL Video Frame Header (16 bytes) ──────────────────────────────
// Sent on TCP 5290 (bridge → app).
//
// Offset  Size  Type    Field
// 0       4     u32le   payload_length
// 4       2     u16le   width
// 6       2     u16le   height
// 8       4     u32le   pts_ms
// 12      2     u16le   flags
// 14      2     u16le   reserved (0)

static constexpr size_t OAL_VIDEO_HEADER_SIZE = 16;

namespace OalVideoFlags {
    static constexpr uint16_t KEYFRAME     = 0x0001;
    static constexpr uint16_t CODEC_CONFIG = 0x0002;
    static constexpr uint16_t END_OF_STREAM = 0x0004;
}

struct OalVideoHeader {
    uint32_t payload_length;
    uint16_t width;
    uint16_t height;
    uint32_t pts_ms;
    uint16_t flags;
    uint16_t reserved;
};

inline void pack_oal_video_header(uint8_t* dst, const OalVideoHeader& h) {
    memcpy(dst + 0,  &h.payload_length, 4);
    memcpy(dst + 4,  &h.width, 2);
    memcpy(dst + 6,  &h.height, 2);
    memcpy(dst + 8,  &h.pts_ms, 4);
    memcpy(dst + 12, &h.flags, 2);
    memcpy(dst + 14, &h.reserved, 2);
}

// ── OAL Audio Frame Header (8 bytes) ───────────────────────────────
// Sent on TCP 5289 (bidirectional).
//
// Offset  Size  Type    Field
// 0       1     u8      direction (0=playback, 1=mic)
// 1       1     u8      purpose (0=media, 1=nav, 2=assistant, 3=call, 4=alert)
// 2       2     u16le   sample_rate
// 4       1     u8      channels
// 5       3     u24le   payload_length

static constexpr size_t OAL_AUDIO_HEADER_SIZE = 8;

namespace OalAudioDirection {
    static constexpr uint8_t PLAYBACK = 0;
    static constexpr uint8_t MIC      = 1;
}

namespace OalAudioPurpose {
    static constexpr uint8_t MEDIA     = 0;
    static constexpr uint8_t NAV       = 1;
    static constexpr uint8_t ASSISTANT = 2;
    static constexpr uint8_t CALL      = 3;
    static constexpr uint8_t ALERT     = 4;
}

struct OalAudioHeader {
    uint8_t  direction;
    uint8_t  purpose;
    uint16_t sample_rate;
    uint8_t  channels;
    uint32_t payload_length; // only lower 24 bits used on wire
};

inline void pack_oal_audio_header(uint8_t* dst, const OalAudioHeader& h) {
    dst[0] = h.direction;
    dst[1] = h.purpose;
    memcpy(dst + 2, &h.sample_rate, 2);
    dst[4] = h.channels;
    // u24le payload_length
    dst[5] = static_cast<uint8_t>(h.payload_length & 0xFF);
    dst[6] = static_cast<uint8_t>((h.payload_length >> 8) & 0xFF);
    dst[7] = static_cast<uint8_t>((h.payload_length >> 16) & 0xFF);
}

inline bool parse_oal_audio_header(const uint8_t* src, OalAudioHeader& out) {
    out.direction = src[0];
    out.purpose = src[1];
    memcpy(&out.sample_rate, src + 2, 2);
    out.channels = src[4];
    out.payload_length = static_cast<uint32_t>(src[5])
                       | (static_cast<uint32_t>(src[6]) << 8)
                       | (static_cast<uint32_t>(src[7]) << 16);
    return out.direction <= 1 && out.purpose <= 4;
}

// ── OAL Control: JSON line helpers ─────────────────────────────────
// Control channel (TCP 5288) uses newline-delimited JSON.

// Simple JSON string escaping (no external JSON library).
inline std::string oal_json_escape(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char hex[8];
                    snprintf(hex, sizeof(hex), "\\u%04x", static_cast<unsigned char>(c));
                    out += hex;
                } else {
                    out += c;
                }
        }
    }
    return out;
}

// Map aasdk audio channel type names to OAL purpose bytes.
// media → 0, speech/guidance → 1(nav), system → 4(alert)
inline uint8_t audio_channel_to_oal_purpose(const char* channel_name) {
    if (strcmp(channel_name, "media") == 0) return OalAudioPurpose::MEDIA;
    if (strcmp(channel_name, "speech") == 0) return OalAudioPurpose::NAV;
    if (strcmp(channel_name, "system") == 0) return OalAudioPurpose::ALERT;
    return OalAudioPurpose::MEDIA;
}

// Map OAL purpose byte to wire string for JSON.
inline const char* oal_purpose_to_string(uint8_t purpose) {
    switch (purpose) {
        case OalAudioPurpose::MEDIA:     return "media";
        case OalAudioPurpose::NAV:       return "navigation";
        case OalAudioPurpose::ASSISTANT: return "assistant";
        case OalAudioPurpose::CALL:      return "phone_call";
        case OalAudioPurpose::ALERT:     return "alert";
        default: return "media";
    }
}

// Map aasdk audio channel type to sample rate and channel count.
inline void audio_channel_params(const char* channel_name, uint16_t& sample_rate, uint8_t& channels) {
    if (strcmp(channel_name, "media") == 0) {
        sample_rate = 48000;
        channels = 2;
    } else {
        // speech, system — 16kHz mono
        sample_rate = 16000;
        channels = 1;
    }
}

// Simple JSON number extraction (no external library).
inline bool oal_json_extract_int(const std::string& json, const std::string& key, int& out) {
    auto needle = "\"" + key + "\":";
    auto pos = json.find(needle);
    if (pos == std::string::npos) return false;
    pos += needle.size();
    while (pos < json.size() && json[pos] == ' ') pos++;
    bool negative = false;
    if (pos < json.size() && json[pos] == '-') { negative = true; pos++; }
    int val = 0;
    bool found = false;
    while (pos < json.size() && json[pos] >= '0' && json[pos] <= '9') {
        val = val * 10 + (json[pos] - '0');
        pos++;
        found = true;
    }
    if (!found) return false;
    out = negative ? -val : val;
    return true;
}

inline bool oal_json_extract_float(const std::string& json, const std::string& key, float& out) {
    auto needle = "\"" + key + "\":";
    auto pos = json.find(needle);
    if (pos == std::string::npos) return false;
    pos += needle.size();
    while (pos < json.size() && json[pos] == ' ') pos++;
    auto start = pos;
    while (pos < json.size() && (json[pos] == '-' || json[pos] == '.' || (json[pos] >= '0' && json[pos] <= '9'))) pos++;
    if (pos == start) return false;
    out = std::stof(json.substr(start, pos - start));
    return true;
}

inline std::string oal_json_extract_string(const std::string& json, const std::string& key) {
    auto needle = "\"" + key + "\":\"";
    auto pos = json.find(needle);
    if (pos == std::string::npos) return "";
    pos += needle.size();
    auto end = json.find('"', pos);
    if (end == std::string::npos) return "";
    return json.substr(pos, end - pos);
}

} // namespace openautolink
