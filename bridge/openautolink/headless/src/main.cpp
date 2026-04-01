#include <iostream>
#include <mutex>
#include <string>

#include <string_view>

#include "openautolink/engine.hpp"
#include "openautolink/oal_protocol.hpp"
#include "openautolink/oal_session.hpp"
#include "openautolink/sco_audio.hpp"
#include "openautolink/tcp_car_transport.hpp"

#ifdef PI_AA_ENABLE_AASDK_LIVE
#include "openautolink/live_session.hpp"
#endif

namespace {

std::string_view kSessionModeFlag = "--session-mode=";
std::string_view kPhoneNameFlag = "--phone-name=";
std::string_view kTcpPortFlag = "--tcp-port=";
std::string_view kVideoWidthFlag = "--video-width=";
std::string_view kVideoHeightFlag = "--video-height=";
std::string_view kVideoFpsFlag = "--video-fps=";
std::string_view kVideoDpiFlag = "--video-dpi=";
std::string_view kVideoCodecFlag = "--video-codec=";
std::string_view kAaResolutionFlag = "--aa-resolution=";
std::string_view kHeadUnitNameFlag = "--head-unit-name=";
std::string_view kMediaFdFlag = "--media-fd=";
std::string_view kTcpCarPortFlag = "--tcp-car-port=";
std::string_view kUsbFlag = "--usb";
std::string_view kBtMacFlag = "--bt-mac=";

} // namespace

int main(int argc, char* argv[])
{
    std::ios::sync_with_stdio(false);

    auto session_mode = openautolink::SessionMode::Stub;
    std::string phone_name = "OpenAuto Headless";
    [[maybe_unused]] int tcp_port = 5277;
    int video_width = 0, video_height = 0, video_fps = 0, video_dpi = 0;
    int video_codec = 0, aa_resolution = 0;
    int media_fd = -1;  // Binary media pipe fd (passed by Python parent)
    std::string head_unit_name;
    int tcp_car_port = 0;    // TCP port for car app (e.g. 5288)
    bool use_usb = false;    // Wired AA: phone via USB host port
    std::string bt_mac;      // BT MAC override (empty = auto-detect)
    for(int index = 1; index < argc; ++index) {
        const std::string_view argument(argv[index]);
        if(argument.rfind(kSessionModeFlag, 0) == 0) {
            const auto parsed = openautolink::parse_session_mode(argument.substr(kSessionModeFlag.size()));
            if(!parsed.has_value()) {
                std::cerr << "unsupported session mode: " << argument.substr(kSessionModeFlag.size()) << '\n';
                return 2;
            }
            session_mode = *parsed;
            continue;
        }

        if(argument.rfind(kPhoneNameFlag, 0) == 0) {
            phone_name = std::string(argument.substr(kPhoneNameFlag.size()));
            continue;
        }

        if(argument.rfind(kTcpPortFlag, 0) == 0) {
            tcp_port = std::stoi(std::string(argument.substr(kTcpPortFlag.size())));
            continue;
        }
        if(argument.rfind(kVideoWidthFlag, 0) == 0) {
            video_width = std::stoi(std::string(argument.substr(kVideoWidthFlag.size())));
            continue;
        }
        if(argument.rfind(kVideoHeightFlag, 0) == 0) {
            video_height = std::stoi(std::string(argument.substr(kVideoHeightFlag.size())));
            continue;
        }
        if(argument.rfind(kVideoFpsFlag, 0) == 0) {
            video_fps = std::stoi(std::string(argument.substr(kVideoFpsFlag.size())));
            continue;
        }
        if(argument.rfind(kVideoDpiFlag, 0) == 0) {
            video_dpi = std::stoi(std::string(argument.substr(kVideoDpiFlag.size())));
            continue;
        }
        if(argument.rfind(kVideoCodecFlag, 0) == 0) {
            // h264=3, vp9=5, av1=6, h265=7
            auto val = std::string(argument.substr(kVideoCodecFlag.size()));
            if (val == "h264") video_codec = 3;
            else if (val == "vp9") video_codec = 5;
            else if (val == "av1") video_codec = 6;
            else if (val == "h265") video_codec = 7;
            else video_codec = std::stoi(val);
            continue;
        }
        if(argument.rfind(kAaResolutionFlag, 0) == 0) {
            // 480p=1, 720p=2, 1080p=3, 1440p=4, 4k=5
            auto val = std::string(argument.substr(kAaResolutionFlag.size()));
            if (val == "480p") aa_resolution = 1;
            else if (val == "720p") aa_resolution = 2;
            else if (val == "1080p") aa_resolution = 3;
            else if (val == "1440p") aa_resolution = 4;
            else if (val == "4k") aa_resolution = 5;
            else aa_resolution = std::stoi(val);
            continue;
        }
        if(argument.rfind(kHeadUnitNameFlag, 0) == 0) {
            head_unit_name = std::string(argument.substr(kHeadUnitNameFlag.size()));
            continue;
        }
        if(argument.rfind(kMediaFdFlag, 0) == 0) {
            media_fd = std::stoi(std::string(argument.substr(kMediaFdFlag.size())));
            continue;
        }
        if(argument.rfind(kTcpCarPortFlag, 0) == 0) {
            tcp_car_port = std::stoi(std::string(argument.substr(kTcpCarPortFlag.size())));
            continue;
        }
        if(argument == kUsbFlag) {
            use_usb = true;
            continue;
        }
        if(argument.rfind(kBtMacFlag, 0) == 0) {
            bt_mac = std::string(argument.substr(kBtMacFlag.size()));
            continue;
        }
    }

    // Thread-safe output sink — aasdk callbacks run on a worker thread.
    std::mutex output_mutex;
    auto sink = [&output_mutex](const std::string& payload) {
        std::lock_guard<std::mutex> lock(output_mutex);
        std::cout << payload << '\n';
        std::cout.flush();
    };

    // Build config from CLI args (shared by all modes)
    auto build_config = [&]() {
        openautolink::HeadlessConfig c;
        c.tcp_port = static_cast<uint16_t>(tcp_port);
        if (video_width > 0) c.video_width = video_width;
        if (video_height > 0) c.video_height = video_height;
        if (video_fps > 0) c.video_fps = video_fps;
        if (video_dpi > 0) c.video_dpi = video_dpi;
        if (video_codec > 0) c.video_codec = video_codec;
        if (aa_resolution > 0) c.aa_resolution_tier = aa_resolution;
        if (!head_unit_name.empty()) c.head_unit_name = head_unit_name;
        if (media_fd >= 0) c.media_fd = media_fd;
        c.use_usb_host = use_usb;
        c.bt_mac = bt_mac;
        return c;
    };

#ifdef PI_AA_ENABLE_AASDK_LIVE
    // ── OAL protocol mode ────────────────────────────────────────────
    // Bridge speaks OAL protocol (JSON control, binary video/audio) to car app.
    // 3 TCP channels: control(5288), audio(5289), video(5290).
    if (tcp_car_port > 0 && session_mode == openautolink::SessionMode::AasdkLive) {
        std::cerr << "[main] OAL protocol mode: control=" << tcp_car_port
                  << " audio=" << (tcp_car_port + 1)
                  << " video=" << (tcp_car_port + 2) << std::endl;

        auto config = build_config();
        config.media_fd = -1;

        // 3 TCP transports: control(5288), audio(5289), video(5290)
        openautolink::TcpCarTransport tcp_control(tcp_car_port);
        openautolink::TcpCarTransport tcp_audio(tcp_car_port + 1);
        openautolink::TcpCarTransport tcp_video(tcp_car_port + 2);

        // Start UDP discovery responder + mDNS
        tcp_control.start_discovery();

        openautolink::OalSession oal(tcp_control, tcp_video, tcp_audio, config);

        oal.set_control_forward([&output_mutex](const std::string& json_line) {
            std::lock_guard<std::mutex> lock(output_mutex);
            std::cout << json_line << '\n';
            std::cout.flush();
        });

        auto live_session = std::make_unique<openautolink::LiveAasdkSession>(
            sink, phone_name, config);

        live_session->set_oal_session(&oal);
        oal.set_aa_session(live_session.get());

        // SCO audio: listen for incoming BT SCO connections (HFP call audio)
        openautolink::ScoAudio sco_audio(oal);
        oal.set_sco_audio(&sco_audio);
        sco_audio.start();

        // Video transport: accept connection + flush in background
        std::thread video_thread([&tcp_video, &oal]() {
            std::cerr << "[main] video TCP (OAL) listening" << std::endl;
            tcp_video.run_oal_sink(
                []() { std::cerr << "[main] video client connected (OAL)" << std::endl; },
                [&oal]() -> bool { return oal.flush_one_video(); }
            );
        });
        video_thread.detach();

        // Audio transport: bidirectional — flush writes + read mic frames
        std::thread audio_thread([&tcp_audio, &oal]() {
            std::cerr << "[main] audio TCP (OAL, bidirectional) listening" << std::endl;
            tcp_audio.run_oal_audio(
                []() { std::cerr << "[main] audio client connected (OAL)" << std::endl; },
                [&oal]() -> bool { return oal.flush_one_audio(); },
                [&oal](const openautolink::OalAudioHeader& hdr,
                       const uint8_t* pcm, size_t len) {
                    oal.on_app_audio_frame(hdr, pcm, len);
                }
            );
        });
        audio_thread.detach();

        // Control transport: accept + read JSON lines (blocking)
        std::cerr << "[main] starting OAL control loop on port " << tcp_car_port << std::endl;
        tcp_control.run_oal_control(
            [&oal](const std::string& line) {
                oal.on_app_json_line(line);
            },
            [&oal]() {
                oal.on_app_connected();
            },
            [&oal]() {
                oal.on_app_disconnected();
            }
        );
        return 0;
    }
#endif

    std::unique_ptr<openautolink::IAndroidAutoSession> session;

#ifdef PI_AA_ENABLE_AASDK_LIVE
    if (session_mode == openautolink::SessionMode::AasdkLive) {
        auto config = build_config();
        session = std::make_unique<openautolink::LiveAasdkSession>(
            sink, phone_name, std::move(config));
    }
#endif

    if (!session) {
        session = openautolink::create_session(session_mode, sink, phone_name);
    }

    openautolink::StubBackendEngine engine(std::move(session));

    std::string line;
    while(std::getline(std::cin, line)) {
        engine.handle_line(line);
    }

    return 0;
}