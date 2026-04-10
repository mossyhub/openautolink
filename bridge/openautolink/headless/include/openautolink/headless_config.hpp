#pragma once

#include <cstdint>
#include <string>

#ifndef OAL_BRIDGE_VERSION
#define OAL_BRIDGE_VERSION "dev"
#endif

#ifndef OAL_BUILD_SOURCE
#define OAL_BUILD_SOURCE "local"
#endif

// OAL protocol version. Bump when making breaking wire-format changes.
// Both sides send protocol_version and min_protocol_version in hello.
// If peer.protocol_version < my.min_protocol_version → incompatible.
constexpr int OAL_PROTOCOL_VERSION = 1;
constexpr int OAL_MIN_PROTOCOL_VERSION = 1;

namespace openautolink {

// Configuration for the headless Android Auto head unit.
// Shared between OalSession, LiveAasdkSession, and HeadlessAutoEntity.
struct HeadlessConfig {
    struct UiInsets {
        uint32_t top = 0;
        uint32_t bottom = 0;
        uint32_t left = 0;
        uint32_t right = 0;

        bool any() const {
            return top != 0 || bottom != 0 || left != 0 || right != 0;
        }
    };

    struct UiConfigExperiment {
        uint32_t width_margin = 0;
        uint32_t height_margin = 0;
        uint32_t pixel_aspect_ratio_e4 = 0;
        uint32_t real_density = 0;
        uint32_t viewing_distance = 0;
        uint32_t decoder_additional_depth = 0;
        UiInsets initial_margins;
        UiInsets initial_content_insets;
        UiInsets initial_stable_insets;
        int runtime_delay_ms = 0;
        UiInsets runtime_margins;
        UiInsets runtime_content_insets;
        UiInsets runtime_stable_insets;

        // Floor for stable insets from display cutout (auto-computed in handle_app_hello).
        // When user-configured stable_insets arrive via config_update, final values
        // are max(user, cutout_floor) per edge.
        UiInsets cutout_stable_floor;

        bool has_video_overrides() const {
            return width_margin != 0 || height_margin != 0 || pixel_aspect_ratio_e4 != 0 ||
                   real_density != 0 || viewing_distance != 0 || decoder_additional_depth != 0;
        }

        bool has_initial_ui_config() const {
            return initial_margins.any() || initial_content_insets.any() || initial_stable_insets.any();
        }

        bool has_runtime_ui_config() const {
            return runtime_margins.any() || runtime_content_insets.any() || runtime_stable_insets.any();
        }

        bool enabled() const {
            return has_video_overrides() || has_initial_ui_config() || has_runtime_ui_config();
        }
    };

    int video_width = 2400;
    int video_height = 960;
    int video_fps = 60;
    int video_dpi = 160;
    // AA resolution tier for SDR (mapped from video_width)
    // 0=auto, 1=800x480, 2=1280x720, 3=1920x1080, 4=2560x1440, 5=3840x2160
    int aa_resolution_tier = 0;
    // Video codec: 0=auto, 3=H264_BP, 5=VP9, 6=AV1, 7=H265
    int video_codec = 0;
    bool left_hand_drive = true;
    std::string head_unit_name = "OpenAutoLink";
    std::string car_make = "OpenAutoLink";    // Updated from app VHAL INFO_MAKE
    std::string car_model = "Universal";       // Updated from app VHAL INFO_MODEL
    std::string car_year = "2024";             // Updated from app VHAL INFO_MODEL_YEAR
    std::vector<int> fuel_types;               // From app VHAL INFO_FUEL_TYPE (e.g. [10]=ELECTRIC)
    std::vector<int> ev_connector_types;       // From app VHAL INFO_EV_CONNECTOR_TYPE (e.g. [1,5])
    uint16_t tcp_port = 5277;
    int media_fd = -1;  // Binary media pipe fd (-1 = disabled)
    bool use_usb_host = false;  // true = wired AA (phone via USB), false = wireless AA (phone via WiFi)
    std::string bt_mac;  // BT MAC for ServiceDiscovery (empty = auto-detect)

    // P2: Session UI flags (AA status bar)
    bool hide_clock = false;
    bool hide_phone_signal = false;
    bool hide_battery_level = false;

    // Bridge version (injected at compile time)
    std::string bridge_version = OAL_BRIDGE_VERSION;

    // Build source: "github" (CI), "local" (WSL/dev) — injected at compile time
    std::string build_source = OAL_BUILD_SOURCE;

    // Bridge update mode: "auto" (accept OTA from app) or "disabled" (dev builds)
    std::string update_mode = "auto";

    UiConfigExperiment aa_ui_experiment;
};

} // namespace openautolink
