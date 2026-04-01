#pragma once

#include <cstdint>
#include <string>

namespace openautolink {

// Configuration for the headless Android Auto head unit.
// Shared between OalSession, LiveAasdkSession, and HeadlessAutoEntity.
struct HeadlessConfig {
    int video_width = 2400;
    int video_height = 960;
    int video_fps = 60;
    int video_dpi = 160;
    // AA resolution tier for SDR (mapped from video_width)
    // 1=800x480, 2=1280x720, 3=1920x1080, 4=2560x1440, 5=3840x2160
    int aa_resolution_tier = 3;
    // Video codec: 3=H264_BP, 5=VP9, 6=AV1, 7=H265
    int video_codec = 3;
    bool left_hand_drive = true;
    std::string head_unit_name = "OpenAutoLink";
    std::string car_model = "Chevrolet Blazer EV";
    std::string car_year = "2024";
    uint16_t tcp_port = 5277;
    int media_fd = -1;  // Binary media pipe fd (-1 = disabled)
    bool use_usb_host = false;  // true = wired AA (phone via USB), false = wireless AA (phone via WiFi)
    std::string bt_mac;  // BT MAC for ServiceDiscovery (empty = auto-detect)
};

} // namespace openautolink
