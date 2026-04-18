#ifdef PI_AA_ENABLE_AASDK_LIVE

#include "openautolink/live_session.hpp"
#include "openautolink/oal_session.hpp"
#include "openautolink/oal_protocol.hpp"

#include <cstring>
#include <chrono>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <sys/uio.h>
#include <unistd.h>

#include <aasdk/USB/USBWrapper.hpp>
#include <aasdk/USB/USBHub.hpp>
#include <aasdk/USB/AOAPDevice.hpp>
#include <aasdk/USB/AccessoryModeQueryFactory.hpp>
#include <aasdk/USB/AccessoryModeQueryChainFactory.hpp>
#include <aasdk/USB/ConnectedAccessoriesEnumerator.hpp>
#include <aasdk/Transport/USBTransport.hpp>
#include <libusb-1.0/libusb.h>

#include <aasdk/Channel/MediaSink/Audio/Channel/MediaAudioChannel.hpp>
#include <aasdk/Channel/MediaSink/Audio/Channel/GuidanceAudioChannel.hpp>
#include <aasdk/Channel/MediaSink/Audio/Channel/SystemAudioChannel.hpp>
#include <aasdk/Channel/MediaSink/Video/Channel/VideoChannel.hpp>
#include <aasdk/Messenger/Cryptor.hpp>
#include <aasdk/Messenger/MessageInStream.hpp>
#include <aasdk/Messenger/MessageOutStream.hpp>
#include <aasdk/Messenger/Messenger.hpp>
#include <aasdk/TCP/TCPEndpoint.hpp>
#include <aasdk/TCP/TCPWrapper.hpp>
#include <aasdk/Transport/SSLWrapper.hpp>
#include <aasdk/Transport/TCPTransport.hpp>

#include <openssl/ssl.h>
#include <openssl/err.h>
#include <openssl/bio.h>
#include <openssl/pem.h>

// opencardev proto includes
#include <aap_protobuf/service/control/message/ChannelOpenResponse.pb.h>
#include <aap_protobuf/service/control/message/ServiceDiscoveryResponse.pb.h>
#include <aap_protobuf/service/control/message/PingRequest.pb.h>
#include <aap_protobuf/service/control/message/PingResponse.pb.h>
#include <aap_protobuf/service/control/message/AudioFocusNotification.pb.h>
#include <aap_protobuf/service/control/message/NavFocusNotification.pb.h>
#include <aap_protobuf/service/media/shared/message/Setup.pb.h>
#include <aap_protobuf/service/media/shared/message/Start.pb.h>
#include <aap_protobuf/service/media/shared/message/Stop.pb.h>
#include <aap_protobuf/service/media/video/message/VideoFocusNotification.pb.h>
#include <aap_protobuf/service/media/video/message/VideoFocusRequestNotification.pb.h>
#include <aap_protobuf/service/media/sink/message/KeyBindingRequest.pb.h>
#include <aap_protobuf/service/sensorsource/message/SensorRequest.pb.h>
#include <aap_protobuf/service/sensorsource/message/SensorStartResponseMessage.pb.h>
#include <aap_protobuf/service/sensorsource/message/SensorBatch.pb.h>
#include <aap_protobuf/service/sensorsource/message/VehicleEnergyModel.pb.h>
#include <aap_protobuf/service/bluetooth/message/BluetoothPairingRequest.pb.h>
#include <aap_protobuf/service/bluetooth/message/BluetoothPairingResponse.pb.h>
#include <aap_protobuf/service/bluetooth/message/BluetoothAuthenticationData.pb.h>
#include <aap_protobuf/service/bluetooth/message/BluetoothPairingMethod.pb.h>
#include <aap_protobuf/shared/MessageStatus.pb.h>
#include <aap_protobuf/service/media/shared/message/MediaCodecType.pb.h>
#include <aap_protobuf/service/media/sink/message/AudioStreamType.pb.h>
#include <aap_protobuf/service/media/sink/message/VideoCodecResolutionType.pb.h>
#include <aap_protobuf/service/media/sink/message/VideoFrameRateType.pb.h>
#include <aap_protobuf/service/sensorsource/message/SensorType.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationStatus.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationNextTurnEvent.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationNextTurnDistanceEvent.pb.h>
#include <aap_protobuf/service/mediaplayback/message/MediaPlaybackMetadata.pb.h>
#include <aap_protobuf/service/mediaplayback/message/MediaPlaybackStatus.pb.h>
#include <aap_protobuf/service/control/message/UpdateUiConfigRequest.pb.h>
#include <aap_protobuf/service/media/shared/message/UiConfig.pb.h>
#include <aap_protobuf/service/media/shared/message/UiTheme.pb.h>
#include <aap_protobuf/service/media/sink/MediaMessageId.pb.h>
#include <aap_protobuf/service/phonestatus/message/PhoneStatus.pb.h>
#include <aap_protobuf/service/control/message/CallAvailabilityStatus.pb.h>
#include <aap_protobuf/service/control/ControlMessageType.pb.h>

#include "openautolink/contract.hpp"

namespace openautolink {

namespace {

std::string format_ui_insets(const HeadlessConfig::UiInsets& insets) {
    std::ostringstream oss;
    oss << "top=" << insets.top
        << " bottom=" << insets.bottom
        << " left=" << insets.left
        << " right=" << insets.right;
    return oss.str();
}

void apply_ui_insets(aap_protobuf::service::media::shared::message::Insets* target,
                     const HeadlessConfig::UiInsets& source) {
    target->set_top(source.top);
    target->set_bottom(source.bottom);
    target->set_left(source.left);
    target->set_right(source.right);
}

void apply_ui_config(aap_protobuf::service::media::shared::message::UiConfig* ui_config,
                     const HeadlessConfig::UiInsets& margins,
                     const HeadlessConfig::UiInsets& content_insets,
                     const HeadlessConfig::UiInsets& stable_insets) {
    if (margins.any()) {
        apply_ui_insets(ui_config->mutable_margins(), margins);
    }
    if (content_insets.any()) {
        apply_ui_insets(ui_config->mutable_content_insets(), content_insets);
    }
    if (stable_insets.any()) {
        apply_ui_insets(ui_config->mutable_stable_content_insets(), stable_insets);
    }
}

// Auto-detect BT MAC address using multiple methods for SBC portability.
// Priority: sysfs → hciconfig → bluetoothctl → fallback
std::string detect_bt_mac() {
    // Method 1: sysfs (RPi, most Rockchip)
    {
        std::ifstream f("/sys/class/bluetooth/hci0/address");
        if (f.good()) {
            std::string mac;
            std::getline(f, mac);
            if (mac.size() >= 17) {
                for (auto& c : mac) c = std::toupper(static_cast<unsigned char>(c));
                std::cerr << "[aasdk] BT MAC from sysfs: " << mac << std::endl;
                return mac;
            }
        }
    }
    // Method 2: hciconfig (universal, needs hciconfig binary)
    {
        FILE* p = popen("hciconfig hci0 2>/dev/null | grep -oE '[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}' | head -1", "r");
        if (p) {
            char buf[32] = {};
            if (fgets(buf, sizeof(buf), p)) {
                std::string mac(buf);
                while (!mac.empty() && (mac.back() == '\n' || mac.back() == '\r')) mac.pop_back();
                if (mac.size() >= 17) {
                    for (auto& c : mac) c = std::toupper(static_cast<unsigned char>(c));
                    std::cerr << "[aasdk] BT MAC from hciconfig: " << mac << std::endl;
                    pclose(p);
                    return mac;
                }
            }
            pclose(p);
        }
    }
    // Method 3: bluetoothctl (BlueZ D-Bus, most portable)
    {
        FILE* p = popen("bluetoothctl show 2>/dev/null | grep -oE '[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}' | head -1", "r");
        if (p) {
            char buf[32] = {};
            if (fgets(buf, sizeof(buf), p)) {
                std::string mac(buf);
                while (!mac.empty() && (mac.back() == '\n' || mac.back() == '\r')) mac.pop_back();
                if (mac.size() >= 17) {
                    for (auto& c : mac) c = std::toupper(static_cast<unsigned char>(c));
                    std::cerr << "[aasdk] BT MAC from bluetoothctl: " << mac << std::endl;
                    pclose(p);
                    return mac;
                }
            }
            pclose(p);
        }
    }
    std::cerr << "[aasdk] BT MAC: auto-detect failed, using fallback" << std::endl;
    return "00:00:00:00:00:00";
}

std::string base64_encode(const uint8_t* data, size_t len) {
    static const char table[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string result;
    result.reserve(4 * ((len + 2) / 3));
    for (size_t i = 0; i < len; i += 3) {
        uint32_t n = static_cast<uint32_t>(data[i]) << 16;
        if (i + 1 < len) n |= static_cast<uint32_t>(data[i + 1]) << 8;
        if (i + 2 < len) n |= static_cast<uint32_t>(data[i + 2]);
        result.push_back(table[(n >> 18) & 0x3f]);
        result.push_back(table[(n >> 12) & 0x3f]);
        result.push_back(i + 1 < len ? table[(n >> 6) & 0x3f] : '=');
        result.push_back(i + 2 < len ? table[n & 0x3f] : '=');
    }
    return result;
}

// Build NDJSON messages matching the Python subprocess_contract.py format.
// CRITICAL: field names must exactly match decode_backend_message() expectations.
//   event ??? "event_type" (not "event")
//   video data ??? "data_b64" (not "data"), "pts_ms" (not "pts")
//   audio data ??? "data_b64", "decode_type", "audio_type", "volume"
std::string json_event(const std::string& event_type) {
    return R"({"type":"event","event_type":")" + event_type + R"("})";
}

[[maybe_unused]]
static std::string json_event_with_phone(const std::string& event_type, const std::string& phone_name,
                                   const std::string& link_type = "AndroidAuto", int phone_type = 5) {
    return R"({"type":"event","event_type":")" + event_type
        + R"(","phone_name":")" + phone_name
        + R"(","link_type":")" + link_type
        + R"(","phone_type":)" + std::to_string(phone_type)
        + R"(,"wifi_enabled":1})";
}

std::string json_command(int cmd_id) {
    return R"({"type":"command","command_id":)" + std::to_string(cmd_id) + "}";
}

std::string json_navi_focus(bool is_request) {
    return std::string(R"({"type":"navi_focus","is_request":)") + (is_request ? "true" : "false") + "}";
}

} // anonymous namespace

// ---- ThreadSafeOutputSink ----

ThreadSafeOutputSink::ThreadSafeOutputSink(OutputSink inner)
    : inner_(std::move(inner))
{
}

void ThreadSafeOutputSink::emit(const std::string& payload) {
    std::lock_guard<std::mutex> lock(mutex_);
    inner_(payload);
}

// ============================================================================
// HeadlessAutoEntity
// ============================================================================

HeadlessAutoEntity::HeadlessAutoEntity(
    boost::asio::io_service& io_service,
    aasdk::messenger::ICryptor::Pointer cryptor,
    aasdk::transport::ITransport::Pointer transport,
    aasdk::messenger::IMessenger::Pointer messenger,
    ThreadSafeOutputSink& output,
    const HeadlessConfig& config)
    : io_service_(io_service)
    , strand_(io_service)
    , cryptor_(std::move(cryptor))
    , transport_(std::move(transport))
    , messenger_(std::move(messenger))
    , output_(output)
    , config_(config)
    , ping_timer_(io_service)
    , shutdown_timer_(io_service)
    , is_tcp_server_(true)
{
    control_channel_ = std::make_shared<aasdk::channel::control::ControlServiceChannel>(strand_, messenger_);

    // Create service handlers
    // Video handler dimensions = AA resolution tier (what the phone actually encodes),
    // NOT the display surface dimensions. The app handles scaling via TextureView crop.
    int aa_w = 1920, aa_h = 1080;
    switch (config_.aa_resolution_tier) {
        case 1: aa_w = 800;  aa_h = 480;  break;
        case 2: aa_w = 1280; aa_h = 720;  break;
        case 3: aa_w = 1920; aa_h = 1080; break;
        case 4: aa_w = 2560; aa_h = 1440; break;
        case 5: aa_w = 3840; aa_h = 2160; break;
    }
    video_handler_ = std::make_shared<HeadlessVideoHandler>(
        io_service_, messenger_, output_, aa_w, aa_h, config_.video_fps, config_.video_dpi,
        config_.video_codec, config_.aa_ui_experiment,
        config_.media_fd, &media_pipe_mutex_);

    media_audio_handler_ = std::make_shared<HeadlessAudioHandler>(
        io_service_, messenger_, output_, HeadlessAudioHandler::ChannelType::Media,
        config_.media_fd, &media_pipe_mutex_);
    speech_audio_handler_ = std::make_shared<HeadlessAudioHandler>(
        io_service_, messenger_, output_, HeadlessAudioHandler::ChannelType::Speech,
        config_.media_fd, &media_pipe_mutex_);
    system_audio_handler_ = std::make_shared<HeadlessAudioHandler>(
        io_service_, messenger_, output_, HeadlessAudioHandler::ChannelType::System,
        config_.media_fd, &media_pipe_mutex_);

    audio_input_handler_ = std::make_shared<HeadlessAudioInputHandler>(io_service_, messenger_, output_);
    sensor_handler_ = std::make_shared<HeadlessSensorHandler>(io_service_, messenger_, output_);
    input_handler_ = std::make_shared<HeadlessInputHandler>(
        io_service_, messenger_, output_, config_.video_width, config_.video_height);
    bluetooth_handler_ = std::make_shared<HeadlessBluetoothHandler>(io_service_, messenger_, output_);
    nav_status_handler_ = std::make_shared<HeadlessNavStatusHandler>(io_service_, messenger_, output_);
    media_status_handler_ = std::make_shared<HeadlessMediaStatusHandler>(io_service_, messenger_, output_);
    phone_status_handler_ = std::make_shared<HeadlessPhoneStatusHandler>(io_service_, messenger_, output_);

    video_handler_->set_input_handler(input_handler_);
}

void HeadlessAutoEntity::set_oal_session(OalSession* session) {
    oal_session_ = session;
    if (video_handler_) video_handler_->set_oal_session(session);
    if (media_audio_handler_) media_audio_handler_->set_oal_session(session);
    if (speech_audio_handler_) speech_audio_handler_->set_oal_session(session);
    if (system_audio_handler_) system_audio_handler_->set_oal_session(session);
    if (audio_input_handler_) audio_input_handler_->set_oal_session(session);
    if (nav_status_handler_) nav_status_handler_->set_oal_session(session);
    if (media_status_handler_) media_status_handler_->set_oal_session(session);
    if (phone_status_handler_) phone_status_handler_->set_oal_session(session);
}

void HeadlessAutoEntity::start() {
    strand_.dispatch([this, self = shared_from_this()]() {
        output_.emit(json_event("session_starting"));

        // DON'T start service handlers yet ??? wait until after ServiceDiscovery
        // Starting them before TLS completes causes encrypted reads on wrong state.

        // Send version request to kick off handshake
        auto promise = aasdk::channel::SendPromise::defer(strand_);
        promise->then([]() {},
            [this, self = shared_from_this()](auto e) { this->onChannelError(e); });
        control_channel_->sendVersionRequest(std::move(promise));
        control_channel_->receive(shared_from_this());
    });
}

void HeadlessAutoEntity::stop() {
    strand_.dispatch([this, self = shared_from_this()]() {
        active_ = false;
        shutdown_timer_.cancel();
        video_handler_->stop();
        media_audio_handler_->stop();
        speech_audio_handler_->stop();
        system_audio_handler_->stop();
        audio_input_handler_->stop();
        sensor_handler_->stop();
        input_handler_->stop();
        bluetooth_handler_->stop();

        ping_timer_.cancel();
        messenger_->stop();
        transport_->stop();
        cryptor_->deinit();
    });
}

void HeadlessAutoEntity::graceful_shutdown(std::function<void()> completion_cb, int timeout_ms) {
    strand_.dispatch([this, self = shared_from_this(), cb = std::move(completion_cb), timeout_ms]() {
        if (!active_ || !control_channel_) {
            std::cerr << "[aasdk] graceful_shutdown: not active, completing immediately" << std::endl;
            if (cb) cb();
            return;
        }

        std::cerr << "[aasdk] graceful_shutdown: sending ByeByeRequest to phone" << std::endl;
        shutdown_pending_ = true;
        shutdown_completion_cb_ = cb;

        // Start timeout — if phone doesn't respond, force stop anyway
        shutdown_timer_.expires_from_now(boost::posix_time::milliseconds(timeout_ms));
        shutdown_timer_.async_wait([this, self](const boost::system::error_code& ec) {
            if (ec) return;  // cancelled = phone responded in time
            if (shutdown_pending_) {
                std::cerr << "[aasdk] graceful_shutdown: timeout, forcing stop" << std::endl;
                shutdown_pending_ = false;
                stop();
                if (shutdown_completion_cb_) {
                    auto cb = std::move(shutdown_completion_cb_);
                    shutdown_completion_cb_ = nullptr;
                    cb();
                }
            }
        });

        // Send ByeByeRequest
        aap_protobuf::service::control::message::ByeByeRequest request;
        request.set_reason(aap_protobuf::service::control::message::USER_SELECTION);
        auto promise = aasdk::channel::SendPromise::defer(strand_);
        promise->then([]() {
            std::cerr << "[aasdk] ByeByeRequest sent OK" << std::endl;
        }, [this, self](auto e) {
            std::cerr << "[aasdk] ByeByeRequest send failed: " << e.what() << std::endl;
            // Send failed — phone is probably already gone, force complete
            shutdown_timer_.cancel();
            shutdown_pending_ = false;
            stop();
            if (shutdown_completion_cb_) {
                auto cb = std::move(shutdown_completion_cb_);
                shutdown_completion_cb_ = nullptr;
                cb();
            }
        });
        control_channel_->sendShutdownRequest(request, std::move(promise));
    });
}

void HeadlessAutoEntity::onVersionResponse(
    uint16_t major, uint16_t minor,
    aap_protobuf::shared::MessageStatus status)
{
    std::cerr << "[aasdk] onVersionResponse: major=" << major << " minor=" << minor << " status=" << status << std::endl;
    if (status == aap_protobuf::shared::STATUS_NO_COMPATIBLE_VERSION) {
        output_.emit(json_event("version_mismatch"));
        triggerQuit();
        return;
    }

    output_.emit(R"({"type":"event","event_type":"version_ok","major":)" +
                 std::to_string(major) + R"(,"minor":)" + std::to_string(minor) + "}");

    try {
        std::cerr << "[aasdk] doHandshake + sendHandshake..." << std::endl;
        cryptor_->doHandshake();
        auto handshakeData = cryptor_->readHandshakeBuffer();
        std::cerr << "[aasdk] handshake buffer size=" << handshakeData.size() << std::endl;
        auto promise = aasdk::channel::SendPromise::defer(strand_);
        promise->then(
            [this]() { std::cerr << "[aasdk] handshake sent OK" << std::endl; },
            [this, self = shared_from_this()](auto e) {
                std::cerr << "[aasdk] handshake send FAILED: " << e.what() << std::endl;
                this->onChannelError(e);
            });
        control_channel_->sendHandshake(std::move(handshakeData), std::move(promise));
        control_channel_->receive(shared_from_this());
    } catch (const aasdk::error::Error& e) {
        onChannelError(e);
    }
}

void HeadlessAutoEntity::onHandshake(const aasdk::common::DataConstBuffer& payload) {
    std::cerr << "[aasdk] onHandshake: received " << payload.size << " bytes from phone" << std::endl;
    try {
        cryptor_->writeHandshakeBuffer(payload);
        std::cerr << "[aasdk] onHandshake: wrote to BIO, calling doHandshake..." << std::endl;
        if (!cryptor_->doHandshake()) {
            // Handshake needs another round
            auto handshakeData = cryptor_->readHandshakeBuffer();
            std::cerr << "[aasdk] onHandshake: sending " << handshakeData.size() << " bytes back" << std::endl;
            auto promise = aasdk::channel::SendPromise::defer(strand_);
            promise->then(
                [this]() { std::cerr << "[aasdk] onHandshake: round sent OK" << std::endl; },
                [this, self = shared_from_this()](auto e) {
                    std::cerr << "[aasdk] onHandshake: send FAILED: " << e.what() << std::endl;
                    this->onChannelError(e);
                });
            control_channel_->sendHandshake(std::move(handshakeData), std::move(promise));
        } else {
            // Handshake complete ??? send auth complete
            std::cerr << "[aasdk] onHandshake: TLS HANDSHAKE COMPLETE!" << std::endl;
            output_.emit(json_event("auth_complete"));

            aap_protobuf::service::control::message::AuthResponse auth;
            auth.set_status(aap_protobuf::shared::STATUS_SUCCESS);

            std::cerr << "[aasdk] sending AuthComplete..." << std::endl;
            auto promise = aasdk::channel::SendPromise::defer(strand_);
            promise->then(
                [this]() { std::cerr << "[aasdk] AuthComplete sent OK!" << std::endl; },
                [this, self = shared_from_this()](auto e) {
                    std::cerr << "[aasdk] AuthComplete send FAILED: " << e.what() << std::endl;
                    this->onChannelError(e);
                });
            control_channel_->sendAuthComplete(auth, std::move(promise));
        }
        control_channel_->receive(shared_from_this());
    } catch (const aasdk::error::Error& e) {
        std::cerr << "[aasdk] onHandshake EXCEPTION: " << e.what() << std::endl;
        onChannelError(e);
    }
}

void HeadlessAutoEntity::onServiceDiscoveryRequest(
    const aap_protobuf::service::control::message::ServiceDiscoveryRequest& request)
{
    std::cerr << "[aasdk] SERVICE DISCOVERY REQUEST from " << request.device_name() << " / " << request.device_name() << std::endl;
    output_.emit(R"({"type":"event","event_type":"discovery_request","device_name":")" +
                 request.device_name() + R"(","device_brand":")" + request.device_name() + R"("})");

    aap_protobuf::service::control::message::ServiceDiscoveryResponse response;
    response.mutable_channels()->Reserve(256);

    // v1.6 protocol fields
    response.set_driver_position(aap_protobuf::service::control::message::DRIVER_POSITION_LEFT);
    response.set_display_name(config_.head_unit_name);
    response.set_probe_for_support(false);

    auto* connConfig = response.mutable_connection_configuration();
    auto* pingConfig = connConfig->mutable_ping_configuration();
    pingConfig->set_timeout_ms(5000);
    pingConfig->set_interval_ms(1500);
    pingConfig->set_high_latency_threshold_ms(500);
    pingConfig->set_tracked_ping_count(5);

    auto* huInfo = response.mutable_headunit_info();
    huInfo->set_make(config_.car_make);
    huInfo->set_model(config_.car_model);
    huInfo->set_year(config_.car_year);
    huInfo->set_vehicle_id("oal-001");
    huInfo->set_head_unit_make("OpenAutoLink");
    huInfo->set_head_unit_model("Headless Bridge");
    huInfo->set_head_unit_software_build("1");
    huInfo->set_head_unit_software_version("1.0");

    // Deprecated fields for backward compat
    response.set_head_unit_make(config_.head_unit_name);
    response.set_model(config_.car_model);
    response.set_year(config_.car_year);
    response.set_vehicle_id("oal-001");
    response.set_head_unit_make("OpenAutoLink");
    response.set_head_unit_model("Headless Bridge");
    response.set_head_unit_software_build("1");
    response.set_head_unit_software_version("1.0");
    response.set_can_play_native_media_during_vr(false);
    response.set_can_play_native_media_during_vr(false);

    // Session configuration flags (P2: hide AA status bar elements)
    int session_config = 0;
    if (config_.hide_clock) session_config |= 1;       // UI_CONFIG_HIDE_CLOCK
    if (config_.hide_phone_signal) session_config |= 2; // UI_CONFIG_HIDE_PHONE_SIGNAL
    if (config_.hide_battery_level) session_config |= 4; // UI_CONFIG_HIDE_BATTERY_LEVEL
    if (session_config != 0) {
        response.set_session_configuration(session_config);
        std::cerr << "[aasdk] session_configuration=" << session_config
                  << " (clock=" << config_.hide_clock
                  << " signal=" << config_.hide_phone_signal
                  << " battery=" << config_.hide_battery_level << ")" << std::endl;
    }

    // v1.6 ServiceConfiguration channels
    // using namespace removed - opencardev uses typed service configs

    // Video
    { auto* svc = response.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::MEDIA_SINK_VIDEO));
      auto* ms = svc->mutable_media_sink_service();
      ms->set_available_type(static_cast<aap_protobuf::service::media::shared::message::MediaCodecType>(config_.video_codec));
      ms->set_available_while_in_call(true);
      auto fps = config_.video_fps >= 60
          ? aap_protobuf::service::media::sink::message::VIDEO_FPS_60
          : aap_protobuf::service::media::sink::message::VIDEO_FPS_30;
      if (config_.aa_ui_experiment.enabled()) {
          std::cerr << "[aasdk] AA UI experiment enabled"
                    << " width_margin=" << config_.aa_ui_experiment.width_margin
                    << " height_margin=" << config_.aa_ui_experiment.height_margin
                    << " pixel_aspect_e4=" << config_.aa_ui_experiment.pixel_aspect_ratio_e4
                    << " real_density=" << config_.aa_ui_experiment.real_density
                    << " initial_content={" << format_ui_insets(config_.aa_ui_experiment.initial_content_insets) << "}"
                    << " initial_stable={" << format_ui_insets(config_.aa_ui_experiment.initial_stable_insets) << "}"
                    << " runtime_delay_ms=" << config_.aa_ui_experiment.runtime_delay_ms
                    << " runtime_content={" << format_ui_insets(config_.aa_ui_experiment.runtime_content_insets) << "}"
                    << " runtime_stable={" << format_ui_insets(config_.aa_ui_experiment.runtime_stable_insets) << "}"
                    << std::endl;
      }
      auto add_video_config = [&](int tier, int codec_type) {
          auto* vc = ms->add_video_configs();
          vc->set_codec_resolution(static_cast<aap_protobuf::service::media::sink::message::VideoCodecResolutionType>(tier));
          vc->set_frame_rate(fps);
          vc->set_density(config_.video_dpi);
          if (codec_type > 0) {
              vc->set_video_codec_type(static_cast<aap_protobuf::service::media::shared::message::MediaCodecType>(codec_type));
          }
          vc->set_height_margin(config_.aa_ui_experiment.height_margin);
          vc->set_width_margin(config_.aa_ui_experiment.width_margin);
          if (config_.aa_ui_experiment.decoder_additional_depth > 0) {
              vc->set_decoder_additional_depth(config_.aa_ui_experiment.decoder_additional_depth);
          }
          if (config_.aa_ui_experiment.viewing_distance > 0) {
              vc->set_viewing_distance(config_.aa_ui_experiment.viewing_distance);
          }
          if (config_.aa_ui_experiment.pixel_aspect_ratio_e4 > 0) {
              vc->set_pixel_aspect_ratio_e4(config_.aa_ui_experiment.pixel_aspect_ratio_e4);
          }
          if (config_.aa_ui_experiment.real_density > 0) {
              vc->set_real_density(config_.aa_ui_experiment.real_density);
          }
          if (config_.aa_ui_experiment.has_initial_ui_config()) {
              apply_ui_config(vc->mutable_ui_config(),
                              config_.aa_ui_experiment.initial_margins,
                              config_.aa_ui_experiment.initial_content_insets,
                              config_.aa_ui_experiment.initial_stable_insets);
          }
      };

      int primary_tier = config_.aa_resolution_tier > 0 ? config_.aa_resolution_tier : 3; // default 1080p
      int primary_codec = config_.video_codec; // 0=auto, 3=H264, 5=VP9, 7=H265

      if (primary_codec == 0) {
          // Auto-negotiate: offer H.265 at all tiers (phone picks best),
          // then H.264 at ≤1080p as fallback
          for (int t : {5, 4, 3, 2, 1}) {
              add_video_config(t, 7); // H.265 at all tiers
          }
          for (int t : {3, 2, 1}) {
              add_video_config(t, 3); // H.264 at ≤1080p only
          }
      } else {
          // Manual: offer the configured codec at configured + alternative tiers
          add_video_config(primary_tier, primary_codec);
          int tiers[] = {5, 4, 3}; // 4K, 1440p, 1080p
          for (int t : tiers) {
              if (t != primary_tier) {
                  add_video_config(t, primary_codec);
              }
          }
      } }
    // Media Audio
    { auto* svc = response.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::MEDIA_SINK_MEDIA_AUDIO));
      auto* ms = svc->mutable_media_sink_service();
      ms->set_available_type(aap_protobuf::service::media::shared::message::MEDIA_CODEC_AUDIO_PCM);
      ms->set_audio_type(aap_protobuf::service::media::sink::message::AUDIO_STREAM_MEDIA);
      ms->set_available_while_in_call(true);
      auto* ac = ms->add_audio_configs();
      ac->set_sampling_rate(48000); ac->set_number_of_bits(16); ac->set_number_of_channels(2); }
    // Speech Audio
    { auto* svc = response.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::MEDIA_SINK_GUIDANCE_AUDIO));
      auto* ms = svc->mutable_media_sink_service();
      ms->set_available_type(aap_protobuf::service::media::shared::message::MEDIA_CODEC_AUDIO_PCM);
      ms->set_audio_type(aap_protobuf::service::media::sink::message::AUDIO_STREAM_GUIDANCE);
      ms->set_available_while_in_call(true);
      auto* ac = ms->add_audio_configs();
      ac->set_sampling_rate(16000); ac->set_number_of_bits(16); ac->set_number_of_channels(1); }
    // System Audio
    { auto* svc = response.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::MEDIA_SINK_SYSTEM_AUDIO));
      auto* ms = svc->mutable_media_sink_service();
      ms->set_available_type(aap_protobuf::service::media::shared::message::MEDIA_CODEC_AUDIO_PCM);
      ms->set_audio_type(aap_protobuf::service::media::sink::message::AUDIO_STREAM_SYSTEM_AUDIO);
      ms->set_available_while_in_call(true);
      auto* ac = ms->add_audio_configs();
      ac->set_sampling_rate(16000); ac->set_number_of_bits(16); ac->set_number_of_channels(1); }
    // Audio Input
    { auto* svc = response.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::MEDIA_SOURCE_MICROPHONE));
      auto* msrc = svc->mutable_media_source_service();
      msrc->set_available_type(aap_protobuf::service::media::shared::message::MEDIA_CODEC_AUDIO_PCM);
      auto* ac = msrc->mutable_audio_config();
      ac->set_sampling_rate(16000); ac->set_number_of_bits(16); ac->set_number_of_channels(1); }
    // Sensor
    { auto* svc = response.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::SENSOR));
      auto* ss = svc->mutable_sensor_source_service();
      ss->add_sensors()->set_sensor_type(aap_protobuf::service::sensorsource::message::SENSOR_DRIVING_STATUS_DATA);
      ss->add_sensors()->set_sensor_type(aap_protobuf::service::sensorsource::message::SENSOR_LOCATION);
      ss->add_sensors()->set_sensor_type(aap_protobuf::service::sensorsource::message::SENSOR_NIGHT_MODE);
      ss->add_sensors()->set_sensor_type(aap_protobuf::service::sensorsource::message::SENSOR_SPEED);
      ss->add_sensors()->set_sensor_type(aap_protobuf::service::sensorsource::message::SENSOR_GEAR);
      ss->add_sensors()->set_sensor_type(aap_protobuf::service::sensorsource::message::SENSOR_PARKING_BRAKE);
      ss->add_sensors()->set_sensor_type(aap_protobuf::service::sensorsource::message::SENSOR_FUEL);
      ss->add_sensors()->set_sensor_type(aap_protobuf::service::sensorsource::message::SENSOR_ODOMETER);
      ss->add_sensors()->set_sensor_type(aap_protobuf::service::sensorsource::message::SENSOR_ENVIRONMENT_DATA);
      ss->add_sensors()->set_sensor_type(aap_protobuf::service::sensorsource::message::SENSOR_DOOR_DATA);
      ss->add_sensors()->set_sensor_type(aap_protobuf::service::sensorsource::message::SENSOR_LIGHT_DATA);
      ss->add_sensors()->set_sensor_type(aap_protobuf::service::sensorsource::message::SENSOR_TIRE_PRESSURE_DATA);
      ss->add_sensors()->set_sensor_type(aap_protobuf::service::sensorsource::message::SENSOR_HVAC_DATA);
      ss->add_sensors()->set_sensor_type(aap_protobuf::service::sensorsource::message::SENSOR_ACCELEROMETER_DATA);
      ss->add_sensors()->set_sensor_type(aap_protobuf::service::sensorsource::message::SENSOR_GYROSCOPE_DATA);
      ss->add_sensors()->set_sensor_type(aap_protobuf::service::sensorsource::message::SENSOR_COMPASS);
      ss->add_sensors()->set_sensor_type(aap_protobuf::service::sensorsource::message::SENSOR_GPS_SATELLITE_DATA);
      ss->add_sensors()->set_sensor_type(aap_protobuf::service::sensorsource::message::SENSOR_RPM);
      // EV energy model sensor types — triggers phone to request sensor 23 for battery routing
      ss->add_sensors()->set_sensor_type(aap_protobuf::service::sensorsource::message::SENSOR_VEHICLE_ENERGY_MODEL);
      ss->add_sensors()->set_sensor_type(aap_protobuf::service::sensorsource::message::SENSOR_RAW_VEHICLE_ENERGY_MODEL);
      ss->add_sensors()->set_sensor_type(aap_protobuf::service::sensorsource::message::SENSOR_RAW_EV_TRIP_SETTINGS);
      // Declare fuel types and EV connectors for Google Maps EV range display.
      // Populated dynamically from app VHAL reads (INFO_FUEL_TYPE, INFO_EV_CONNECTOR_TYPE).
      // Falls back to ELECTRIC + J1772/COMBO_1 if app hasn't connected yet.
      if (config_.fuel_types.empty()) {
          ss->add_supported_fuel_types(aap_protobuf::service::sensorsource::message::FUEL_TYPE_ELECTRIC);
      } else {
          for (int ft : config_.fuel_types) {
              ss->add_supported_fuel_types(static_cast<aap_protobuf::service::sensorsource::message::FuelType>(ft));
          }
      }
      if (config_.ev_connector_types.empty()) {
          ss->add_supported_ev_connector_types(aap_protobuf::service::sensorsource::message::EV_CONNECTOR_TYPE_J1772);
          ss->add_supported_ev_connector_types(aap_protobuf::service::sensorsource::message::EV_CONNECTOR_TYPE_COMBO_1);
      } else {
          for (int ec : config_.ev_connector_types) {
              ss->add_supported_ev_connector_types(static_cast<aap_protobuf::service::sensorsource::message::EvConnectorType>(ec));
          }
      }
      // Location characterization bitmask — tells AA about available sensor fusion
      // Bits: RAW_GPS_ONLY=256, ACCELEROMETER=4, GYROSCOPE=2, COMPASS=8, CAR_SPEED=64
      ss->set_location_characterization(256 | 4 | 2 | 8 | 64); }
    // Input — touch dimensions match the AA resolution tier
    { auto* svc = response.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::INPUT_SOURCE));
      auto* is = svc->mutable_input_source_service();
      // Declare supported keycodes — must match what SteeringWheelController forwards
      // AA keycodes: 84=SEARCH/voice, 85=PLAY_PAUSE, 86=STOP, 87=NEXT, 88=PREV,
      //              89=REWIND, 90=FAST_FORWARD, 126=PLAY, 127=PAUSE
      for (int kc : {84, 85, 86, 87, 88, 89, 90, 126, 127}) {
          is->add_keycodes_supported(kc);
      }
      auto* ts = is->add_touchscreen();
      // Map tier to pixel dimensions for touch coordinate space
      int touch_w = 1920, touch_h = 1080;
      switch (config_.aa_resolution_tier) {
          case 1: touch_w = 800;  touch_h = 480;  break;
          case 2: touch_w = 1280; touch_h = 720;  break;
          case 3: touch_w = 1920; touch_h = 1080; break;
          case 4: touch_w = 2560; touch_h = 1440; break;
          case 5: touch_w = 3840; touch_h = 2160; break;
      }
      ts->set_width(touch_w); ts->set_height(touch_h);
      ts->set_type(aap_protobuf::service::inputsource::message::CAPACITIVE); }
    // Bluetooth — get BT MAC (from config override or auto-detect)
    { auto* svc = response.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::BLUETOOTH));
      auto* bs = svc->mutable_bluetooth_service();
      std::string bt_mac = config_.bt_mac;
      if (bt_mac.empty()) bt_mac = detect_bt_mac();
      std::cerr << "[aasdk] BT MAC for SDR: " << bt_mac << std::endl;
      bs->set_car_address(bt_mac);
      bs->add_supported_pairing_methods(aap_protobuf::service::bluetooth::message::BLUETOOTH_PAIRING_PIN);
      bs->add_supported_pairing_methods(aap_protobuf::service::bluetooth::message::BLUETOOTH_PAIRING_NUMERIC_COMPARISON); }
    // Navigation Status — receive turn-by-turn from phone (IMAGE mode for icon PNGs)
    { auto* svc = response.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::NAVIGATION_STATUS));
      auto* ns = svc->mutable_navigation_status_service();
      ns->set_minimum_interval_ms(500);
      ns->set_type(aap_protobuf::service::navigationstatus::NavigationStatusService::IMAGE);
      auto* img_opts = ns->mutable_image_options();
      img_opts->set_width(256);
      img_opts->set_height(256);
      img_opts->set_colour_depth_bits(32); }
    // Media Playback Status — receive track info from phone
    { auto* svc = response.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::MEDIA_PLAYBACK_STATUS));
      svc->mutable_media_playback_service(); }
    // Phone Status — receive signal strength and call state from phone
    { auto* svc = response.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::PHONE_STATUS));
      svc->mutable_phone_status_service(); }

    std::cerr << "[aasdk] sending ServiceDiscoveryResponse with " << response.channels_size() << " channels" << std::endl;
    std::cerr << "[aasdk] Cryptor active=" << cryptor_->isActive() << std::endl;
    std::cerr << "[aasdk] Response: head_unit=" << response.head_unit_make()
              << " car=" << response.model() << " year=" << response.year()
              << " lhd=" << response.driver_position() << std::endl;
    std::cerr << "[aasdk] Response size=" << response.ByteSizeLong() << " bytes" << std::endl;
    // Note: DebugString() crashes with protobuf v30 FetchContent (file descriptor not registered)
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then(
        [this, self = shared_from_this()]() {
            std::cerr << "[aasdk] ServiceDiscoveryResponse sent OK! Starting service handlers..." << std::endl;

            // NOW start all service handlers ??? after TLS + auth + discovery are complete
            video_handler_->start();
            media_audio_handler_->start();
            speech_audio_handler_->start();
            system_audio_handler_->start();
            audio_input_handler_->start();
            sensor_handler_->start();
            input_handler_->start();
            bluetooth_handler_->start();
            nav_status_handler_->start();
            media_status_handler_->start();
            phone_status_handler_->start();

            // Start pinging to keep connection alive
            sendPing();
            schedulePing();
            emitCommand(500); // REQUEST_VIDEO_FOCUS
        },
        [this, self = shared_from_this()](auto e) {
            std::cerr << "[aasdk] ServiceDiscoveryResponse send FAILED: " << e.what() << std::endl;
            this->onChannelError(e);
        });
    control_channel_->sendServiceDiscoveryResponse(response, std::move(promise));
    control_channel_->receive(shared_from_this());

    active_ = true;
    output_.emit(json_event("session_active"));
}

void HeadlessAutoEntity::onAudioFocusRequest(
    const aap_protobuf::service::control::message::AudioFocusRequest& request)
{
    auto focus_type = request.audio_focus_type();
    auto state = (focus_type == 0)
        ? aap_protobuf::service::control::message::AudioFocusStateType::AUDIO_FOCUS_STATE_LOSS
        : aap_protobuf::service::control::message::AudioFocusStateType::AUDIO_FOCUS_STATE_GAIN;

    aap_protobuf::service::control::message::AudioFocusNotification response;
    response.set_focus_state(state);

    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {},
        [this, self = shared_from_this()](auto e) { this->onChannelError(e); });
    control_channel_->sendAudioFocusResponse(response, std::move(promise));
    control_channel_->receive(shared_from_this());
}

void HeadlessAutoEntity::onNavigationFocusRequest(
    const aap_protobuf::service::control::message::NavFocusRequestNotification& request)
{
    auto req_type = request.focus_type();

    if (req_type == 1) {
        emitCommand(506); // REQUEST_NAVI_FOCUS
        output_.emit(json_navi_focus(true));
    } else {
        emitCommand(507); // RELEASE_NAVI_FOCUS
        output_.emit(json_navi_focus(false));
    }

    aap_protobuf::service::control::message::NavFocusNotification response;
    response.set_focus_type(aap_protobuf::service::control::message::NAV_FOCUS_PROJECTED);
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {},
        [this, self = shared_from_this()](auto e) { this->onChannelError(e); });
    control_channel_->sendNavigationFocusResponse(response, std::move(promise));
    control_channel_->receive(shared_from_this());
}

void HeadlessAutoEntity::onByeByeRequest(
    const aap_protobuf::service::control::message::ByeByeRequest& request)
{
    output_.emit(R"({"type":"event","event_type":"shutdown_request","reason":)" +
                 std::to_string(request.reason()) + "}");

    aap_protobuf::service::control::message::ByeByeResponse response;
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([this, self = shared_from_this()]() { this->triggerQuit(); },
        [this, self = shared_from_this()](auto e) { this->onChannelError(e); });
    control_channel_->sendShutdownResponse(response, std::move(promise));
}

void HeadlessAutoEntity::onByeByeResponse(
    const aap_protobuf::service::control::message::ByeByeResponse&)
{
    if (shutdown_pending_) {
        std::cerr << "[aasdk] ByeByeResponse received — graceful shutdown complete" << std::endl;
        shutdown_timer_.cancel();
        shutdown_pending_ = false;
        stop();
        if (shutdown_completion_cb_) {
            auto cb = std::move(shutdown_completion_cb_);
            shutdown_completion_cb_ = nullptr;
            cb();
        }
        return;
    }
    triggerQuit();
}

void HeadlessAutoEntity::onPingRequest(const aap_protobuf::service::control::message::PingRequest& request) {
    std::cerr << "[aasdk] PingRequest received, responding..." << std::endl;
    aap_protobuf::service::control::message::PingResponse resp;
    resp.set_timestamp(request.timestamp());
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([](){}, [this, self = shared_from_this()](auto e){ this->onChannelError(e); });
    control_channel_->sendPingResponse(resp, std::move(promise));
    control_channel_->receive(shared_from_this());
}

void HeadlessAutoEntity::onPingResponse(const aap_protobuf::service::control::message::PingResponse&) {
    ping_outstanding_ = false;
    control_channel_->receive(shared_from_this());
}

void HeadlessAutoEntity::onChannelError(const aasdk::error::Error& e) {
    std::cerr << "[aasdk] CHANNEL ERROR: " << e.what() << std::endl;
    output_.emit(R"({"type":"event","event_type":"channel_error","error":")" +
                 std::string(e.what()) + R"("})");
    // OPERATION_ABORTED is expected during shutdown when messenger stops
    if (e.getCode() == aasdk::error::ErrorCode::OPERATION_ABORTED) {
        std::cerr << "[aasdk] Operation aborted (expected during stop)" << std::endl;
        return;
    }
    // If we're in a graceful shutdown, complete it (phone died mid-handshake)
    if (shutdown_pending_) {
        std::cerr << "[aasdk] Channel error during graceful shutdown — completing" << std::endl;
        shutdown_timer_.cancel();
        shutdown_pending_ = false;
        stop();
        if (shutdown_completion_cb_) {
            auto cb = std::move(shutdown_completion_cb_);
            shutdown_completion_cb_ = nullptr;
            cb();
        }
        return;
    }
    triggerQuit();
}

void HeadlessAutoEntity::triggerQuit() {
    active_ = false;
    output_.emit(json_event("phone_disconnected"));
    if (disconnect_cb_) disconnect_cb_();
}

void HeadlessAutoEntity::schedulePing() {
    ping_timer_.expires_from_now(boost::posix_time::milliseconds(2000));
    ping_timer_.async_wait([this, self = shared_from_this()](const boost::system::error_code& ec) {
        if (!ec && active_) {
            // Don't quit on ping timeout ??? the phone may use a different
            // ping model (it sends PingRequests to us, not vice versa).
            ping_outstanding_ = false;
            sendPing();
            schedulePing();
        }
    });
}

void HeadlessAutoEntity::sendPing() {
    ping_outstanding_ = true;
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {},
        [this, self = shared_from_this()](auto e) { this->onChannelError(e); });
    aap_protobuf::service::control::message::PingRequest request;
    request.set_timestamp(std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count());
    control_channel_->sendPingRequest(request, std::move(promise));
}

void HeadlessAutoEntity::sendCallAvailability(bool available) {
    if (!active_) return;
    strand_.dispatch([this, self = shared_from_this(), available]() {
        // Send CallAvailabilityStatus as a control message
        auto message = std::make_shared<aasdk::messenger::Message>(
            aasdk::messenger::ChannelId::CONTROL,
            aasdk::messenger::EncryptionType::ENCRYPTED,
            aasdk::messenger::MessageType::SPECIFIC);
        message->insertPayload(
            aasdk::messenger::MessageId(
                aap_protobuf::service::control::message::ControlMessageType::MESSAGE_CALL_AVAILABILITY_STATUS).getData());

        aap_protobuf::service::control::message::CallAvailabilityStatus status;
        status.set_call_available(available);
        message->insertPayload(status);

        auto promise = aasdk::channel::SendPromise::defer(strand_);
        promise->then(
            [available]() {
                std::cerr << "[aasdk] call availability sent: " << (available ? "true" : "false") << std::endl;
            },
            [](auto e) {
                std::cerr << "[aasdk] call availability send failed: " << e.what() << std::endl;
            });
        control_channel_->send(std::move(message), std::move(promise));
    });
}

void HeadlessAutoEntity::emitLifecycle(const std::string& event) {
    output_.emit(json_event(event));
}

void HeadlessAutoEntity::emitCommand(int command_id) {
    output_.emit(json_command(command_id));
}

// ============================================================================
// LiveAasdkSession
// ============================================================================

LiveAasdkSession::LiveAasdkSession(OutputSink sink, std::string phone_name, HeadlessConfig config)
    : output_(std::move(sink))
    , phone_name_(std::move(phone_name))
    , config_(std::move(config))
{
    // Always start both USB host scanning AND wireless TCP listener.
    // USB takes priority — if phone is plugged in via USB, wired session is used.
    // If phone connects wirelessly while no USB device, wireless session is used.
    // If wireless active and USB plugged in → switch to wired.
    // If wired active and USB unplugged → fall back to wireless.
    start_dual_mode();
}

void LiveAasdkSession::start_dual_mode() {
    running_ = true;
    io_work_ = std::make_unique<boost::asio::io_service::work>(io_service_);

    // 1. Start USB host scanning (if libusb is available)
    std::cerr << "[aasdk] Starting dual mode (wired + wireless)" << std::endl;
    output_.emit(R"({"type":"event","event_type":"dual_mode_starting"})");

    libusb_context* usbContext = nullptr;
    if (libusb_init(&usbContext) == 0 && usbContext) {
        auto usbWrapper = std::make_shared<aasdk::usb::USBWrapper>(usbContext);
        auto queryFactory = std::make_shared<aasdk::usb::AccessoryModeQueryFactory>(
            *usbWrapper, io_service_);
        auto queryChainFactory = std::make_shared<aasdk::usb::AccessoryModeQueryChainFactory>(
            *usbWrapper, io_service_, *queryFactory);
        auto usbHub = std::make_shared<aasdk::usb::USBHub>(*usbWrapper, io_service_, *queryChainFactory);
        usb_wrapper_ = usbWrapper;
        usb_query_factory_ = queryFactory;
        usb_query_chain_factory_ = queryChainFactory;
        usb_hub_ = usbHub;
        usb_context_ = usbContext;

        // Start USB scanning
        start_usb_scanning();

        // Start libusb event loop thread
        usb_event_thread_ = std::thread([this, usbContext]() {
            std::cerr << "[aasdk] libusb event loop started" << std::endl;
            while (running_) {
                struct timeval tv = {1, 0};
                libusb_handle_events_timeout_completed(usbContext, &tv, nullptr);
            }
            std::cerr << "[aasdk] libusb event loop stopped" << std::endl;
        });
        std::cerr << "[aasdk] USB host scanning active" << std::endl;
    } else {
        std::cerr << "[aasdk] libusb init failed, USB host disabled" << std::endl;
    }

    // 2. Start wireless TCP listener for phone AA connections
    try {
        auto endpoint = boost::asio::ip::tcp::endpoint(
            boost::asio::ip::address::from_string("0.0.0.0"), config_.tcp_port);
        tcp_acceptor_ = std::make_unique<boost::asio::ip::tcp::acceptor>(io_service_, endpoint);
        output_.emit(R"({"type":"event","event_type":"tcp_listening","port":)" +
                     std::to_string(config_.tcp_port) + "}");
        accept_connection();
        std::cerr << "[aasdk] Wireless TCP listener on port " << config_.tcp_port << std::endl;
    } catch (const std::exception& e) {
        std::cerr << "[aasdk] TCP listener failed: " << e.what() << " (wireless disabled)" << std::endl;
    }

    // 3. Start io_service thread (processes both USB and TCP events)
    io_thread_ = std::thread(&LiveAasdkSession::run_io_thread, this);
}

LiveAasdkSession::~LiveAasdkSession() {
    running_ = false;
    if (usb_hub_) usb_hub_->cancel();
    if (entity_) {
        entity_->stop();
    }
    io_service_.stop();
    if (io_thread_.joinable()) {
        io_thread_.join();
    }
    if (usb_event_thread_.joinable()) {
        usb_event_thread_.join();
    }
    if (usb_context_) {
        libusb_exit(usb_context_);
        usb_context_ = nullptr;
    }
}

void LiveAasdkSession::on_host_command(int command_id) {
    // WIFI_CONNECT (1002) from the Python bridge means "start looking for a phone"
    if (command_id == 1002 && !running_) {
        output_.emit(json_event("discovery_started"));
        start_tcp_server();
    }
    // REQUEST_NAVI_SCREEN_FOCUS (508) — forward to video handler
    if (command_id == 508 && entity_ && entity_->video_handler()) {
        entity_->video_handler()->sendVideoFocusIndication();
    }
    // FRAME (12) — car app requesting keyframe. On first request, replay cached
    // SPS/PPS+IDR. Also periodically send VideoFocusIndication for fresh IDR from phone.
    if (command_id == 12 && entity_ && entity_->video_handler()) {
        auto now = std::chrono::steady_clock::now();
        if (now - last_frame_request_time_ > std::chrono::seconds(5)) {
            last_frame_request_time_ = now;
            // Try replay first (instant), then also ask phone for fresh IDR
            entity_->video_handler()->replayCachedKeyframe();
            entity_->video_handler()->sendVideoFocusIndication();
        }
    }
    // AUDIO_TRANSFER_ON (22) / AUDIO_TRANSFER_OFF (23) — log for now.
    // The AA protocol manages audio focus internally; these are informational.
    if (command_id == 22) {
        output_.emit(json_event("audio_transfer_on"));
    }
    if (command_id == 23) {
        output_.emit(json_event("audio_transfer_off"));
    }
}

void LiveAasdkSession::replay_cached_keyframe() {
    if (entity_ && entity_->video_handler()) {
        entity_->video_handler()->replayCachedKeyframe();
    } else {
        std::cerr << "[aasdk] replay_cached_keyframe skipped: entity="
                  << (entity_ ? "ok" : "null")
                  << " video_handler="
                  << (entity_ && entity_->video_handler() ? "ok" : "null")
                  << std::endl;
    }
}

void LiveAasdkSession::on_host_open(const ParsedInputMessage& message) {
    if (!message.payload_b64.has_value()) return;

    auto payload = base64_decode(*message.payload_b64);
    if (payload.size() < 28) return;

    auto read_u32 = [&payload](size_t offset) -> int {
        const auto* b = reinterpret_cast<const unsigned char*>(payload.data() + offset);
        return static_cast<int>(b[0] | (b[1] << 8) | (b[2] << 16) | (b[3] << 24));
    };

    config_.video_width = read_u32(0);
    config_.video_height = read_u32(4);
    config_.video_fps = read_u32(8);
    state_.video_width = config_.video_width;
    state_.video_height = config_.video_height;
    state_.video_fps = config_.video_fps;
}

void LiveAasdkSession::on_host_box_settings(const ParsedInputMessage& message) {
    if (!message.payload_b64.has_value()) return;

    auto payload = base64_decode(*message.payload_b64);
    if (payload.empty()) return;

    // BOX_SETTINGS payload is null-terminated JSON
    std::string json_str(payload.begin(), payload.end());
    // Trim null terminator
    while (!json_str.empty() && json_str.back() == '\0') json_str.pop_back();

    // Simple JSON field extraction (no library dependency)
    auto extract_string = [&json_str](const std::string& key) -> std::string {
        auto pos = json_str.find("\"" + key + "\"");
        if (pos == std::string::npos) return "";
        pos = json_str.find(':', pos);
        if (pos == std::string::npos) return "";
        pos = json_str.find('"', pos + 1);
        if (pos == std::string::npos) return "";
        auto end = json_str.find('"', pos + 1);
        if (end == std::string::npos) return "";
        return json_str.substr(pos + 1, end - pos - 1);
    };
    auto extract_int = [&json_str](const std::string& key) -> int {
        auto pos = json_str.find("\"" + key + "\"");
        if (pos == std::string::npos) return -1;
        pos = json_str.find(':', pos);
        if (pos == std::string::npos) return -1;
        // Skip whitespace
        pos++;
        while (pos < json_str.size() && (json_str[pos] == ' ' || json_str[pos] == '\t')) pos++;
        try { return std::stoi(json_str.substr(pos)); } catch (...) { return -1; }
    };

    // Note: We DON'T restart the session based on the app's BOX_SETTINGS.
    std::string codec_name = extract_string("videoCodec");
    int aa_width = extract_int("androidAutoSizeW");

    if (!codec_name.empty()) {
        int new_codec = config_.video_codec;
        if (codec_name == "h264") new_codec = 3;
        else if (codec_name == "vp9") new_codec = 5;
        else if (codec_name == "av1") new_codec = 6;
        else if (codec_name == "h265") new_codec = 7;
        if (new_codec != config_.video_codec) {
            std::cerr << "[aasdk] BOX_SETTINGS: codec changed " << config_.video_codec << " -> " << new_codec
                      << " (" << codec_name << ") — will apply on next session" << std::endl;
            config_.video_codec = new_codec;
        }
    }

    if (aa_width > 0) {
        int new_tier = config_.aa_resolution_tier;
        if (aa_width >= 3840) new_tier = 5;
        else if (aa_width >= 2560) new_tier = 4;
        else if (aa_width >= 1920) new_tier = 3;
        else if (aa_width >= 1280) new_tier = 2;
        else new_tier = 1;
        if (new_tier != config_.aa_resolution_tier) {
            std::cerr << "[aasdk] BOX_SETTINGS: resolution tier " << config_.aa_resolution_tier
                      << " -> " << new_tier << " (aa_width=" << aa_width << ") — will apply on next session" << std::endl;
            config_.aa_resolution_tier = new_tier;
        }
    }
}

void LiveAasdkSession::on_host_disconnect() {
    if (entity_) {
        entity_->stop();
        entity_.reset();
    }
    output_.emit(json_event("phone_disconnected"));
    state_.connected = false;
    state_.session_active = false;
    vem_ever_sent_ = false;  // Reset VEM throttle for next session
}

void LiveAasdkSession::on_heartbeat() {
    state_.heartbeat_tick++;
    // Nothing else needed ??? aasdk manages its own ping/pong.
}

void LiveAasdkSession::on_touch(const ParsedInputMessage& message) {
    state_.touch_count++;
    state_.last_touch_input_bytes = 0;
    std::string decoded;
    if (message.payload_b64.has_value()) {
        decoded = base64_decode(*message.payload_b64);
        state_.last_touch_input_bytes = static_cast<int>(decoded.size());
        state_.touch_uplink_bytes += state_.last_touch_input_bytes;
    }

    if (entity_ && entity_->input_handler() && decoded.size() >= 12) {
        // Stub touch format: [action:u32][x:u32][y:u32][flags:u32] (16 bytes)
        // Action codes from app: 14=DOWN, 15=MOVE, 16=UP
        // x/y are in 0-10000 range (normalized by app)
        uint32_t action_code = 0, raw_x = 0, raw_y = 0;
        std::memcpy(&action_code, decoded.data(), 4);
        std::memcpy(&raw_x, decoded.data() + 4, 4);
        std::memcpy(&raw_y, decoded.data() + 8, 4);

        // Scale from app's 0-10000 range to pixel coordinates matching SDR touchscreen
        int touch_w = 1920, touch_h = 1080;
        switch (config_.aa_resolution_tier) {
            case 1: touch_w = 800;  touch_h = 480;  break;
            case 2: touch_w = 1280; touch_h = 720;  break;
            case 3: touch_w = 1920; touch_h = 1080; break;
            case 4: touch_w = 2560; touch_h = 1440; break;
            case 5: touch_w = 3840; touch_h = 2160; break;
        }
        uint32_t x = static_cast<uint32_t>(raw_x * touch_w / 10000);
        uint32_t y = static_cast<uint32_t>(raw_y * touch_h / 10000);

        // Map action codes to aasdk PointerAction:
        // 14 (DOWN) → 0 (ACTION_DOWN), 15 (MOVE) → 2 (ACTION_MOVED), 16 (UP) → 1 (ACTION_UP)
        uint32_t aa_action = 0;
        if (action_code == 14) aa_action = 0;      // DOWN
        else if (action_code == 15) aa_action = 2;  // MOVE
        else if (action_code == 16) aa_action = 1;  // UP
        else aa_action = action_code; // pass through

        entity_->input_handler()->sendTouchEvent(aa_action, x, y);
    }
}

void LiveAasdkSession::on_audio_input(const ParsedInputMessage& message) {
    state_.microphone_uplink_count++;
    state_.last_audio_input_bytes = 0;
    if (message.payload_b64.has_value()) {
        auto decoded = base64_decode(*message.payload_b64);
        state_.last_audio_input_bytes = static_cast<int>(decoded.size());
        state_.microphone_uplink_bytes += state_.last_audio_input_bytes;

        if (entity_ && entity_->audio_input_handler() && decoded.size() > 12) {
            // Strip the 12-byte stub audio header: [decode_type:i32][volume:f32][audio_type:i32]
            // Feed only the raw PCM data to the aasdk microphone channel
            const auto* pcm_start = reinterpret_cast<const uint8_t*>(decoded.data()) + 12;
            size_t pcm_size = decoded.size() - 12;
            entity_->audio_input_handler()->feedAudio(pcm_start, pcm_size);
        }
    }
}

void LiveAasdkSession::on_gnss(const ParsedInputMessage& message) {
    state_.gnss_count++;
    state_.last_gnss_input_bytes = 0;
    if (message.payload_b64.has_value()) {
        auto decoded = base64_decode(*message.payload_b64);
        state_.last_gnss_input_bytes = static_cast<int>(decoded.size());
        state_.gnss_uplink_bytes += state_.last_gnss_input_bytes;
        // NMEA parsing is done in forward_oal_gnss() for OAL protocol path.
        // This legacy CPC path only tracks stats.
    }
}

void LiveAasdkSession::on_vehicle_data(const ParsedInputMessage& message) {
    if (!message.payload_b64.has_value()) return;
    auto decoded = base64_decode(*message.payload_b64);
    if (decoded.empty()) return;
    parse_and_forward_vehicle_data(decoded);
}

void LiveAasdkSession::parse_and_forward_vehicle_data(const std::string& json) {

    auto extract_int = [&](std::string_view key) -> std::optional<int> {
        auto needle = "\"" + std::string(key) + "\":";
        auto pos = json.find(needle);
        if (pos == std::string::npos) return std::nullopt;
        pos += needle.size();
        while (pos < json.size() && json[pos] == ' ') ++pos;
        bool negative = false;
        if (pos < json.size() && json[pos] == '-') { negative = true; ++pos; }
        int val = 0;
        bool found = false;
        while (pos < json.size() && json[pos] >= '0' && json[pos] <= '9') {
            val = val * 10 + (json[pos] - '0');
            ++pos;
            found = true;
        }
        if (!found) return std::nullopt;
        return negative ? -val : val;
    };

    auto extract_bool = [&](std::string_view key) -> std::optional<bool> {
        auto needle = "\"" + std::string(key) + "\":";
        auto pos = json.find(needle);
        if (pos == std::string::npos) return std::nullopt;
        pos += needle.size();
        while (pos < json.size() && json[pos] == ' ') ++pos;
        if (pos + 3 < json.size() && json.substr(pos, 4) == "true") return true;
        if (pos + 4 < json.size() && json.substr(pos, 5) == "false") return false;
        return std::nullopt;
    };

    auto extract_int_array = [&](std::string_view key) -> std::vector<int> {
        std::vector<int> result;
        auto needle = "\"" + std::string(key) + "\":[";
        auto pos = json.find(needle);
        if (pos == std::string::npos) return result;
        pos += needle.size();
        while (pos < json.size() && json[pos] != ']') {
            while (pos < json.size() && (json[pos] == ' ' || json[pos] == ',')) ++pos;
            if (pos >= json.size() || json[pos] == ']') break;
            bool neg = false;
            if (json[pos] == '-') { neg = true; ++pos; }
            int val = 0;
            while (pos < json.size() && json[pos] >= '0' && json[pos] <= '9') {
                val = val * 10 + (json[pos] - '0');
                ++pos;
            }
            result.push_back(neg ? -val : val);
        }
        return result;
    };

    auto extract_bool_array = [&](std::string_view key) -> std::vector<bool> {
        std::vector<bool> result;
        auto needle = "\"" + std::string(key) + "\":[";
        auto pos = json.find(needle);
        if (pos == std::string_view::npos) return result;
        pos += needle.size();
        while (pos < json.size() && json[pos] != ']') {
            while (pos < json.size() && (json[pos] == ' ' || json[pos] == ',')) ++pos;
            if (pos >= json.size() || json[pos] == ']') break;
            if (pos + 3 < json.size() && json.substr(pos, 4) == "true") {
                result.push_back(true); pos += 4;
            } else if (pos + 4 < json.size() && json.substr(pos, 5) == "false") {
                result.push_back(false); pos += 5;
            } else {
                break;
            }
        }
        return result;
    };

    if (!entity_ || !entity_->sensor_handler()) return;
    auto sh = entity_->sensor_handler();

    // Speed: speed_mm_s (int, mm/s from VHAL m/s × 1000)
    if (auto v = extract_int("speed_mm_s")) {
        sh->sendSpeed(*v);
    }
    // Gear: gear (int, mapped to AA Gear enum by app)
    if (auto v = extract_int("gear")) {
        sh->sendGear(*v);
    }
    // Parking brake: parking_brake (bool)
    if (auto v = extract_bool("parking_brake")) {
        sh->sendParkingBrake(*v);
    }
    // Night mode: night_mode (bool)
    if (auto v = extract_bool("night_mode")) {
        sh->sendNightMode(*v);
        // P2: Sync AA UI theme with car night mode
        if (entity_ && entity_->video_handler()) {
            entity_->video_handler()->sendUiThemeUpdate(*v);
        }
    }
    // Driving status: driving (bool) — true=moving (unrestricted AA UI)
    if (auto v = extract_bool("driving")) {
        sh->sendDrivingStatus(*v);
    }
    // Fuel/EV range: fuel_level_pct, range_m, low_fuel
    if (auto fl = extract_int("fuel_level_pct")) {
        auto range = extract_int("range_m").value_or(0);
        auto low = extract_bool("low_fuel").value_or(false);
        sh->sendFuel(*fl, range, low);
    }
    // Odometer: odometer_km_e1 (km × 10)
    if (auto v = extract_int("odometer_km_e1")) {
        sh->sendOdometer(*v);
    }
    // Environment: temp_e3 (°C × 1000)
    if (auto v = extract_int("temp_e3")) {
        sh->sendEnvironment(*v);
    }
    // Door: hood_open, trunk_open, doors_open (bool array)
    if (auto hood = extract_bool("hood_open")) {
        auto trunk = extract_bool("trunk_open").value_or(false);
        auto doors = extract_bool_array("doors_open");
        sh->sendDoor(*hood, trunk, doors);
    }
    // Lights: headlight (int), turn_indicator (int), hazard (bool)
    if (auto hl = extract_int("headlight")) {
        auto ti = extract_int("turn_indicator").value_or(0);
        auto hz = extract_bool("hazard").value_or(false);
        sh->sendLight(*hl, ti, hz);
    }
    // Tire pressure: tire_pressures_e2 (array of int, kPa × 100)
    {
        auto tires = extract_int_array("tire_pressures_e2");
        if (!tires.empty()) {
            sh->sendTirePressure(tires);
        }
    }
    // HVAC: hvac_target_e3, hvac_current_e3 (°C × 1000)
    if (auto target = extract_int("hvac_target_e3")) {
        auto current = extract_int("hvac_current_e3").value_or(0);
        sh->sendHvac(*target, current);
    }

    // P5: Accelerometer: accel_x_e3, accel_y_e3, accel_z_e3 (m/s² × 1000)
    if (auto ax = extract_int("accel_x_e3")) {
        auto ay = extract_int("accel_y_e3").value_or(0);
        auto az = extract_int("accel_z_e3").value_or(0);
        sh->sendAccelerometer(*ax, ay, az);
    }
    // P5: Gyroscope: gyro_rx_e3, gyro_ry_e3, gyro_rz_e3 (rad/s × 1000)
    if (auto rx = extract_int("gyro_rx_e3")) {
        auto ry = extract_int("gyro_ry_e3").value_or(0);
        auto rz = extract_int("gyro_rz_e3").value_or(0);
        sh->sendGyroscope(*rx, ry, rz);
    }
    // P5: Compass: compass_bearing_e6 (degrees × 1e6)
    if (auto bearing = extract_int("compass_bearing_e6")) {
        auto pitch = extract_int("compass_pitch_e6").value_or(0);
        auto roll = extract_int("compass_roll_e6").value_or(0);
        sh->sendCompass(*bearing, pitch, roll);
    }
    // P5: GPS satellites: sat_in_use, sat_in_view, satellites[]
    if (auto in_use = extract_int("sat_in_use")) {
        auto in_view = extract_int("sat_in_view").value_or(*in_use);
        // Parse per-satellite detail array if present
        std::vector<std::tuple<int,int,bool,int,int>> sats;
        auto sat_pos = json.find("\"satellites\"");
        if (sat_pos != std::string::npos) {
            auto arr_start = json.find('[', sat_pos);
            auto arr_end = json.find(']', arr_start);
            if (arr_start != std::string::npos && arr_end != std::string::npos) {
                std::string arr(json.substr(arr_start, arr_end - arr_start + 1));
                size_t pos = 0;
                while ((pos = arr.find('{', pos)) != std::string::npos) {
                    auto obj_end = arr.find('}', pos);
                    if (obj_end == std::string::npos) break;
                    std::string obj = arr.substr(pos, obj_end - pos + 1);
                    int prn = 0, snr = 0, az = 0, el = 0;
                    oal_json_extract_int(obj, "prn", prn);
                    oal_json_extract_int(obj, "snr_e3", snr);
                    bool used = oal_json_extract_string(obj, "used") == "true";
                    oal_json_extract_int(obj, "az_e3", az);
                    oal_json_extract_int(obj, "el_e3", el);
                    sats.emplace_back(prn, snr, used, az, el);
                    pos = obj_end + 1;
                }
            }
        }
        sh->sendGpsSatellites(*in_use, in_view, sats);
    }
    // P6: RPM: rpm_e3 (RPM × 1000)
    if (auto rpm = extract_int("rpm_e3")) {
        sh->sendRpm(*rpm);
    }

    // Vehicle identity — persist to config if changed (used in next AA service discovery)
    std::string car_make = oal_json_extract_string(json, "car_make");
    std::string car_model = oal_json_extract_string(json, "car_model");
    std::string car_year = oal_json_extract_string(json, "car_year");
    bool identity_changed = false;
    if (!car_make.empty() && car_make != config_.car_make) {
        config_.car_make = car_make;
        identity_changed = true;
    }
    if (!car_model.empty() && car_model != config_.car_model) {
        config_.car_model = car_model;
        identity_changed = true;
    }
    if (!car_year.empty() && car_year != config_.car_year) {
        config_.car_year = car_year;
        identity_changed = true;
    }
    if (identity_changed) {
        std::cerr << "[OAL] vehicle identity updated: " << config_.car_make
                  << " " << config_.car_model << " " << config_.car_year << std::endl;
        // Persist to env file so next boot uses correct identity
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
        std::string cmd;
        cmd += "sed -i 's/^OAL_CAR_MAKE=.*/OAL_CAR_MAKE=" + sanitize(car_make) + "/' /etc/openautolink.env 2>/dev/null\n";
        cmd += "sed -i 's/^OAL_CAR_MODEL=.*/OAL_CAR_MODEL=" + sanitize(car_model) + "/' /etc/openautolink.env 2>/dev/null\n";
        cmd += "sed -i 's/^OAL_CAR_YEAR=.*/OAL_CAR_YEAR=" + sanitize(car_year) + "/' /etc/openautolink.env 2>/dev/null\n";
        (void)system(cmd.c_str());
    }

    // Fuel type and EV connector arrays — parse JSON int arrays
    auto parse_int_array = [&](const char* field) -> std::vector<int> {
        std::vector<int> result;
        auto pos = json.find(std::string("\"") + field + "\"");
        if (pos == std::string::npos) return result;
        auto arr_start = json.find('[', pos);
        auto arr_end = json.find(']', arr_start);
        if (arr_start == std::string::npos || arr_end == std::string::npos) return result;
        std::string arr = json.substr(arr_start + 1, arr_end - arr_start - 1);
        size_t p = 0;
        while (p < arr.size()) {
            while (p < arr.size() && (arr[p] == ' ' || arr[p] == ',')) ++p;
            if (p >= arr.size()) break;
            try {
                size_t next = 0;
                int val = std::stoi(arr.substr(p), &next);
                result.push_back(val);
                p += next;
            } catch (...) { break; }
        }
        return result;
    };

    auto fuel_types = parse_int_array("fuel_types");
    auto ev_connectors = parse_int_array("ev_connector_types");
    if (!fuel_types.empty() && fuel_types != config_.fuel_types) {
        config_.fuel_types = fuel_types;
        std::string ft_str;
        for (auto ft : fuel_types) { if (!ft_str.empty()) ft_str += ","; ft_str += std::to_string(ft); }
        std::cerr << "[OAL] fuel_types updated: [" << ft_str << "]" << std::endl;
        std::string cmd = "sed -i 's/^OAL_FUEL_TYPES=.*/OAL_FUEL_TYPES=" + ft_str + "/' /etc/openautolink.env 2>/dev/null";
        (void)system(cmd.c_str());
    }
    if (!ev_connectors.empty() && ev_connectors != config_.ev_connector_types) {
        config_.ev_connector_types = ev_connectors;
        std::string ec_str;
        for (auto ec : ev_connectors) { if (!ec_str.empty()) ec_str += ","; ec_str += std::to_string(ec); }
        std::cerr << "[OAL] ev_connector_types updated: [" << ec_str << "]" << std::endl;
        std::string cmd = "sed -i 's/^OAL_EV_CONNECTOR_TYPES=.*/OAL_EV_CONNECTOR_TYPES=" + ec_str + "/' /etc/openautolink.env 2>/dev/null";
        (void)system(cmd.c_str());
    }

    // EV battery data for Vehicle Energy Model (VEM) — sensor type 23
    // Extract float values from JSON for Wh fields
    auto extract_float = [&](std::string_view key) -> std::optional<float> {
        auto needle = "\"" + std::string(key) + "\":";
        auto pos = json.find(needle);
        if (pos == std::string::npos) return std::nullopt;
        pos += needle.size();
        while (pos < json.size() && json[pos] == ' ') ++pos;
        try { return std::stof(json.substr(pos)); } catch (...) { return std::nullopt; }
    };

    auto ev_cap_wh = extract_float("ev_battery_capacity_wh");
    auto ev_level_wh = extract_float("ev_battery_level_wh");
    auto range_m_val = extract_int("range_m");

    // Cache EV values — they persist across phone reconnects so VEM can be
    // sent immediately when the phone requests sensor 23 on next session
    if (ev_cap_wh && *ev_cap_wh > 0)
        cached_ev_cap_wh_ = static_cast<int>(*ev_cap_wh);
    if (ev_level_wh && *ev_level_wh > 0)
        cached_ev_level_wh_ = static_cast<int>(*ev_level_wh);
    if (range_m_val && *range_m_val > 0)
        cached_range_m_ = *range_m_val;

    // Also cache on the sensor handler so it can send immediately on sensor 23 request
    if (sh && cached_ev_cap_wh_ > 0 && cached_ev_level_wh_ > 0 && cached_range_m_ > 0) {
        sh->cacheEvValues(cached_ev_cap_wh_, cached_ev_level_wh_, cached_range_m_);
    }

    // Send VEM if we have all three values (from this message or cache)
    if (cached_ev_cap_wh_ > 0 && cached_ev_level_wh_ > 0 && cached_range_m_ > 0) {
        auto now = std::chrono::steady_clock::now();
        bool should_send = false;

        if (!vem_ever_sent_) {
            // First send: immediate (bypass throttle)
            should_send = true;
        } else if (now - last_vem_send_time_ > std::chrono::seconds(10)) {
            // Subsequent sends: at most once every 10 seconds
            should_send = true;
        }

        if (should_send) {
            last_vem_send_time_ = now;
            vem_ever_sent_ = true;
            sh->sendVehicleEnergyModel(
                cached_ev_cap_wh_,
                cached_ev_level_wh_,
                cached_range_m_
            );
        }
    }

    std::cerr << "[aasdk] vehicle_data processed" << std::endl;
}

void LiveAasdkSession::forward_touch(const uint8_t* payload, size_t len) {
    if (!entity_ || !entity_->input_handler() || len < 16) return;

    int touch_w = 1920, touch_h = 1080;
    switch (config_.aa_resolution_tier) {
        case 1: touch_w = 800;  touch_h = 480;  break;
        case 2: touch_w = 1280; touch_h = 720;  break;
        case 3: touch_w = 1920; touch_h = 1080; break;
        case 4: touch_w = 2560; touch_h = 1440; break;
        case 5: touch_w = 3840; touch_h = 2160; break;
    }

    uint32_t action_code = 0;
    std::memcpy(&action_code, payload, 4);

    uint32_t aa_action = 0;
    if (action_code == 14) aa_action = 0;       // DOWN → ACTION_DOWN
    else if (action_code == 15) aa_action = 2;  // MOVE → ACTION_MOVED
    else if (action_code == 16) aa_action = 1;  // UP → ACTION_UP
    else if (action_code == 17) aa_action = 5;  // PTR_DOWN → ACTION_POINTER_DOWN
    else if (action_code == 18) aa_action = 6;  // PTR_UP → ACTION_POINTER_UP
    else aa_action = action_code;

    // Multi-pointer format: len > 16 means [action][action_index][count][flags][ptr0][ptr1]...
    if (len > 16) {
        uint32_t action_index = 0, pointer_count = 0;
        std::memcpy(&action_index, payload + 4, 4);
        std::memcpy(&pointer_count, payload + 8, 4);

        size_t expected = 16 + pointer_count * 12;
        if (len < expected || pointer_count == 0 || pointer_count > 10) {
            std::cerr << "[touch] bad multi-touch: len=" << len << " count=" << pointer_count << std::endl;
            return;
        }

        std::vector<PointerInfo> pointers;
        for (uint32_t i = 0; i < pointer_count; i++) {
            uint32_t raw_x = 0, raw_y = 0, ptr_id = 0;
            std::memcpy(&raw_x, payload + 16 + i * 12, 4);
            std::memcpy(&raw_y, payload + 16 + i * 12 + 4, 4);
            std::memcpy(&ptr_id, payload + 16 + i * 12 + 8, 4);
            pointers.push_back({
                static_cast<uint32_t>(raw_x * touch_w / 10000),
                static_cast<uint32_t>(raw_y * touch_h / 10000),
                ptr_id
            });
        }

        std::cerr << "[touch] multi-touch: action=" << aa_action << " idx=" << action_index
                  << " ptrs=" << pointer_count << std::endl;
        entity_->input_handler()->sendMultiTouchEvent(aa_action, action_index, pointers);
    } else {
        // Legacy single-pointer format: [action][x][y][flags] (16 bytes)
        uint32_t raw_x = 0, raw_y = 0;
        std::memcpy(&raw_x, payload + 4, 4);
        std::memcpy(&raw_y, payload + 8, 4);
        uint32_t x = static_cast<uint32_t>(raw_x * touch_w / 10000);
        uint32_t y = static_cast<uint32_t>(raw_y * touch_h / 10000);

        std::cerr << "[touch] touch: action=" << aa_action << " x=" << x << " y=" << y << std::endl;
        entity_->input_handler()->sendTouchEvent(aa_action, x, y);
    }
}

void LiveAasdkSession::forward_audio_input(const uint8_t* payload, size_t len) {
    if (!entity_ || !entity_->audio_input_handler() || len <= 12) return;
    // Strip 12-byte stub audio header, feed raw PCM
    entity_->audio_input_handler()->feedAudio(payload + 12, len - 12);
}

// ── OAL forwarding methods (from OalSession) ────────────────────────

void LiveAasdkSession::forward_oal_touch(int action, uint32_t x, uint32_t y) {
    if (!entity_ || !entity_->input_handler()) return;
    // OAL touch actions already match AA PointerAction codes (0=down, 1=up, 2=move, etc.)
    entity_->input_handler()->sendTouchEvent(static_cast<uint32_t>(action), x, y);
}

void LiveAasdkSession::forward_oal_multi_touch(int action, uint32_t action_index,
                                                const std::vector<PointerInfo>& pointers) {
    if (!entity_ || !entity_->input_handler()) return;
    entity_->input_handler()->sendMultiTouchEvent(static_cast<uint32_t>(action), action_index, pointers);
}

void LiveAasdkSession::forward_oal_button(uint32_t keycode, bool down,
                                           uint32_t metastate, bool longpress) {
    if (!entity_ || !entity_->input_handler()) return;
    entity_->input_handler()->sendKeyEvent(keycode, down, metastate, longpress);
}

void LiveAasdkSession::forward_oal_mic_audio(const uint8_t* pcm, size_t len) {
    if (!entity_ || !entity_->audio_input_handler() || len == 0) return;
    entity_->audio_input_handler()->feedAudio(pcm, len);
}

void LiveAasdkSession::forward_oal_gnss(const std::string& nmea) {
    if (!entity_ || !entity_->sensor_handler()) return;

    // Parse NMEA sentences to extract GPS data for aasdk SensorBatch
    // Supported: $GPRMC (lat, lon, speed, bearing, date/time), $GPGGA (altitude, fix, sats)
    if (nmea.size() < 10) return;

    auto sh = entity_->sensor_handler();

    // Helper: parse NMEA ddmm.mmmm → decimal degrees
    auto parse_coord = [](const std::string& field, const std::string& dir) -> double {
        if (field.empty() || dir.empty()) return 0.0;
        auto dot_pos = field.find('.');
        if (dot_pos == std::string::npos || dot_pos < 2) return 0.0;
        double degrees = std::stod(field.substr(0, dot_pos - 2));
        double minutes = std::stod(field.substr(dot_pos - 2));
        double result = degrees + minutes / 60.0;
        if (dir == "S" || dir == "W") result = -result;
        return result;
    };

    // Split NMEA sentence by commas
    auto split = [](const std::string& s) -> std::vector<std::string> {
        std::vector<std::string> parts;
        std::string part;
        for (char c : s) {
            if (c == ',' || c == '*') {
                parts.push_back(part);
                part.clear();
            } else {
                part += c;
            }
        }
        parts.push_back(part);
        return parts;
    };

    auto parts = split(nmea);
    if (parts.empty()) return;

    // $GPRMC or $GNRMC: lat, lon, speed (knots), bearing, date/time
    if ((parts[0] == "$GPRMC" || parts[0] == "$GNRMC") && parts.size() >= 10) {
        // Field 2: status (A=valid, V=void)
        if (parts[2] != "A") return;

        double lat = parse_coord(parts[3], parts[4]);
        double lon = parse_coord(parts[5], parts[6]);
        float speed_knots = parts[7].empty() ? 0.0f : std::stof(parts[7]);
        float speed_ms = speed_knots * 0.514444f;
        float bearing = parts[8].empty() ? 0.0f : std::stof(parts[8]);

        // Parse timestamp from fields 1 (hhmmss.ss) and 9 (ddmmyy)
        uint64_t timestamp_ms = 0;
        if (parts[1].size() >= 6 && parts[9].size() >= 6) {
            // Simple epoch calculation (approximate — good enough for GPS forwarding)
            struct tm t = {};
            t.tm_hour = std::stoi(parts[1].substr(0, 2));
            t.tm_min = std::stoi(parts[1].substr(2, 2));
            t.tm_sec = std::stoi(parts[1].substr(4, 2));
            t.tm_mday = std::stoi(parts[9].substr(0, 2));
            t.tm_mon = std::stoi(parts[9].substr(2, 2)) - 1;
            int year = std::stoi(parts[9].substr(4, 2));
            t.tm_year = (year < 80 ? year + 100 : year);
            time_t epoch = timegm(&t);
            if (epoch > 0) timestamp_ms = static_cast<uint64_t>(epoch) * 1000;
        }

        if (!gnss_first_fix_logged_) {
            std::cerr << "[aasdk] first GNSS fix: lat=" << lat << " lon=" << lon
                      << " speed=" << speed_ms << " bearing=" << bearing << std::endl;
            gnss_first_fix_logged_ = true;
        }

        sh->sendGpsLocation(lat, lon, last_gps_alt_, speed_ms, bearing, timestamp_ms);
    }
    // $GPGGA or $GNGGA: altitude, fix quality, satellite count
    else if ((parts[0] == "$GPGGA" || parts[0] == "$GNGGA") && parts.size() >= 10) {
        // Field 6: fix quality (0=invalid)
        int fix_quality = parts[6].empty() ? 0 : std::stoi(parts[6]);
        if (fix_quality == 0) return;

        if (!parts[9].empty()) {
            last_gps_alt_ = std::stod(parts[9]);
        }
    }
}

void LiveAasdkSession::forward_oal_vehicle_data(const std::string& json) {
    if (!entity_ || !entity_->sensor_handler()) return;
    // Direct parsing of the vehicle_data JSON — same logic as the CPC path
    // in on_vehicle_data(ParsedInputMessage) but without the base64 decode step.
    parse_and_forward_vehicle_data(json);
}

void LiveAasdkSession::request_fresh_idr() {
    if (entity_ && entity_->video_handler()) {
        entity_->video_handler()->sendVideoFocusIndication();
    }
}

void LiveAasdkSession::notify_call_availability(bool available) {
    if (entity_) {
        entity_->sendCallAvailability(available);
    }
}

void LiveAasdkSession::on_vehicle_data(const std::string& json) {
    // Called by the legacy CPC path — delegate to shared parser
    if (!entity_ || !entity_->sensor_handler()) return;
    parse_and_forward_vehicle_data(json);
}

void LiveAasdkSession::on_vehicle_gnss(const uint8_t* nmea, size_t len) {
    // Legacy CPC path — GNSS parsing is done in forward_oal_gnss() for OAL protocol.
    std::cerr << "[aasdk] gnss_data received (" << len << " bytes)" << std::endl;
}

void LiveAasdkSession::restart_with_config(const HeadlessConfig& new_config) {
    std::cerr << "[aasdk] Restarting AA session with new config (res_tier="
        << new_config.aa_resolution_tier << " fps=" << new_config.video_fps
        << " codec=" << new_config.video_codec << " dpi=" << new_config.video_dpi
        << ")" << std::endl;

    // Update config first so it's ready when phone reconnects
    config_ = new_config;

    auto finish_restart = [this]() {
        // Phone will auto-reconnect via BT/WiFi and get new SDR
        output_.emit(R"({"type":"event","event_type":"config_changed","message":"AA session restarting with new config"})");
        state_.connected = false;
        active_transport_ = TransportType::NONE;
        entity_.reset();

        // Notify OAL session that phone disconnected (sends phone_disconnected to app,
        // cleans up audio state, etc.)
        if (oal_session_) {
            oal_session_->on_phone_disconnected("config_changed");
        }

        // Restart both USB scanning and wireless TCP (dual mode)
        io_service_.post([this]() {
            if (usb_hub_ && running_) {
                std::cerr << "[aasdk] restart: USB scan in 2s..." << std::endl;
                auto timer = std::make_shared<boost::asio::deadline_timer>(io_service_);
                timer->expires_from_now(boost::posix_time::seconds(2));
                timer->async_wait([this, timer](const boost::system::error_code& ec) {
                    if (!ec && running_) start_usb_scanning();
                });
            }
            // TCP listener is always active, new wireless connections accepted automatically
        });
    };

    // Gracefully disconnect phone (ByeByeRequest + wait for response)
    if (entity_ && entity_->is_active()) {
        entity_->graceful_shutdown(std::move(finish_restart));
    } else {
        if (entity_) {
            entity_->stop();
        }
        finish_restart();
    }
}

void LiveAasdkSession::graceful_disconnect_phone(std::function<void()> completion_cb) {
    if (entity_ && entity_->is_active()) {
        std::cerr << "[aasdk] graceful_disconnect_phone: sending ByeByeRequest" << std::endl;
        entity_->graceful_shutdown([this, cb = std::move(completion_cb)]() {
            state_.connected = false;
            active_transport_ = TransportType::NONE;
            entity_.reset();
            if (oal_session_) {
                oal_session_->on_phone_disconnected("graceful_shutdown");
            }
            if (cb) cb();
        });
    } else {
        std::cerr << "[aasdk] graceful_disconnect_phone: no active session" << std::endl;
        if (completion_cb) completion_cb();
    }
}

void LiveAasdkSession::update_config(const HeadlessConfig& new_config) {
    config_ = new_config;
    std::cerr << "[aasdk] config updated (no restart): pixel_aspect="
              << config_.aa_ui_experiment.pixel_aspect_ratio_e4
              << " stable={T:" << config_.aa_ui_experiment.initial_stable_insets.top
              << " B:" << config_.aa_ui_experiment.initial_stable_insets.bottom
              << " L:" << config_.aa_ui_experiment.initial_stable_insets.left
              << " R:" << config_.aa_ui_experiment.initial_stable_insets.right << "}"
              << std::endl;
}

const BackendState& LiveAasdkSession::state() const {
    return state_;
}

void LiveAasdkSession::start_tcp_server() {
    running_ = true;
    io_work_ = std::make_unique<boost::asio::io_service::work>(io_service_);

    try {
        auto endpoint = boost::asio::ip::tcp::endpoint(
            boost::asio::ip::address::from_string("0.0.0.0"), config_.tcp_port);
        tcp_acceptor_ = std::make_unique<boost::asio::ip::tcp::acceptor>(io_service_, endpoint);
        output_.emit(R"({"type":"event","event_type":"tcp_listening","port":)" +
                     std::to_string(config_.tcp_port) + "}");
        accept_connection();
    } catch (const std::exception& e) {
        output_.emit(R"({"type":"event","event_type":"tcp_listen_error","error":")" +
                     std::string(e.what()) + R"("})");
        running_ = false;
        return;
    }

    io_thread_ = std::thread(&LiveAasdkSession::run_io_thread, this);
}

void LiveAasdkSession::start_usb_host() {
    running_ = true;
    io_work_ = std::make_unique<boost::asio::io_service::work>(io_service_);

    std::cerr << "[aasdk] Starting USB host mode (wired AA)" << std::endl;
    output_.emit(R"({"type":"event","event_type":"usb_host_starting"})");

    libusb_context* usbContext = nullptr;
    if (libusb_init(&usbContext) != 0 || !usbContext) {
        std::cerr << "[aasdk] libusb_init failed!" << std::endl;
        output_.emit(R"({"type":"event","event_type":"usb_init_error"})");
        running_ = false;
        return;
    }

    auto usbWrapper = std::make_shared<aasdk::usb::USBWrapper>(usbContext);
    auto queryFactory = std::make_shared<aasdk::usb::AccessoryModeQueryFactory>(
        *usbWrapper, io_service_);
    auto queryChainFactory = std::make_shared<aasdk::usb::AccessoryModeQueryChainFactory>(
        *usbWrapper, io_service_, *queryFactory);
    auto usbHub = std::make_shared<aasdk::usb::USBHub>(*usbWrapper, io_service_, *queryChainFactory);

    // Store as members so they stay alive
    usb_wrapper_ = usbWrapper;
    usb_query_factory_ = queryFactory;
    usb_query_chain_factory_ = queryChainFactory;
    usb_hub_ = usbHub;

    start_usb_scanning();

    // Start libusb event loop thread — required for hotplug callbacks to fire.
    // aasdk doesn't call libusb_handle_events internally; we must pump it.
    usb_context_ = usbContext;
    usb_event_thread_ = std::thread([this, usbContext]() {
        std::cerr << "[aasdk] libusb event loop started" << std::endl;
        while (running_) {
            struct timeval tv = {1, 0}; // 1 second timeout
            libusb_handle_events_timeout_completed(usbContext, &tv, nullptr);
        }
        std::cerr << "[aasdk] libusb event loop stopped" << std::endl;
    });

    io_thread_ = std::thread(&LiveAasdkSession::run_io_thread, this);
}

void LiveAasdkSession::start_usb_scanning() {
    if (!usb_hub_ || !running_) return;
    std::cerr << "[aasdk] Starting USB device scan..." << std::endl;
    auto promise = aasdk::usb::IUSBHub::Promise::defer(io_service_);
    auto wrapper = usb_wrapper_;
    promise->then(
        [this, wrapper](aasdk::usb::DeviceHandle deviceHandle) {
            std::cerr << "[aasdk] USB device found in AOA mode!" << std::endl;
            // USB takes priority — if wireless session is active, tear it down
            if (entity_ && active_transport_ == TransportType::WIRELESS) {
                std::cerr << "[aasdk] Preempting wireless session for wired USB" << std::endl;
                entity_->stop();
                entity_.reset();
            }
            output_.emit(R"({"type":"event","event_type":"phone_connected","transport":"usb"})");
            active_transport_ = TransportType::USB;
            state_.connected = true;
            try {
                auto aoapDevice = aasdk::usb::AOAPDevice::create(*wrapper, io_service_, std::move(deviceHandle));
                auto transport = std::make_shared<aasdk::transport::USBTransport>(io_service_, std::move(aoapDevice));
                create_entity(std::move(transport));
            } catch (const std::exception& e) {
                std::cerr << "[aasdk] USB device setup failed: " << e.what() << std::endl;
                if (running_) {
                    auto timer = std::make_shared<boost::asio::deadline_timer>(io_service_);
                    timer->expires_from_now(boost::posix_time::seconds(2));
                    timer->async_wait([this, timer](const boost::system::error_code& ec) {
                        if (!ec && running_) start_usb_scanning();
                    });
                }
            }
        },
        [this](const aasdk::error::Error& e) {
            // Don't spam on OPERATION_ABORTED — delay retry
            if (running_) {
                auto timer = std::make_shared<boost::asio::deadline_timer>(io_service_);
                timer->expires_from_now(boost::posix_time::seconds(3));
                timer->async_wait([this, timer](const boost::system::error_code& ec) {
                    if (!ec && running_) start_usb_scanning();
                });
            }
        });
    usb_hub_->start(std::move(promise));
}

void LiveAasdkSession::accept_connection() {
    auto socket = std::make_shared<boost::asio::ip::tcp::socket>(io_service_);
    tcp_acceptor_->async_accept(*socket,
        [this, socket](const boost::system::error_code& ec) {
            if (!ec) {
                // If USB session is active, reject wireless connection
                if (entity_ && active_transport_ == TransportType::USB) {
                    std::cerr << "[aasdk] Wireless connection rejected (USB session active)" << std::endl;
                    socket->close();
                    accept_connection();
                    return;
                }

                std::cerr << "[aasdk] TCP client connected (wireless)!" << std::endl;
                output_.emit(R"({"type":"event","event_type":"phone_connected","transport":"wireless"})");
                state_.connected = true;
                active_transport_ = TransportType::WIRELESS;

                // Tear down old entity if exists (phone reconnect)
                if (entity_) {
                    std::cerr << "[aasdk] Cleaning up previous entity for reconnect" << std::endl;
                    entity_->stop();
                    entity_.reset();
                }

                do_tcp_wireless_handshake(socket);

                // Keep accepting new connections (phone may reconnect)
                accept_connection();
            } else if (running_) {
                accept_connection();
            }
        });
}

void LiveAasdkSession::do_tcp_wireless_handshake(
    std::shared_ptr<boost::asio::ip::tcp::socket> socket)
{
    // Use the standard aasdk flow: version exchange + SSL through AA framing.
    // This gets version response (v1.7 status=0) and sends 293-byte ClientHello.
    // Capturing traffic to debug why phone drops after receiving ClientHello.
    auto tcpEndpoint = std::make_shared<aasdk::tcp::TCPEndpoint>(
        tcp_wrapper_, std::move(socket));
    auto transport = std::make_shared<aasdk::transport::TCPTransport>(
        io_service_, std::move(tcpEndpoint));
    create_entity(std::move(transport));
}

void LiveAasdkSession::create_entity(aasdk::transport::ITransport::Pointer transport) {
    std::cerr << "[aasdk] create_entity: creating SSL cryptor..." << std::endl;
    auto sslWrapper = std::make_shared<aasdk::transport::SSLWrapper>();
    auto cryptor = std::make_shared<aasdk::messenger::Cryptor>(std::move(sslWrapper));
    try {
        cryptor->init();
        // Head unit always initiates SSL (connect state), even for TCP wireless
        std::cerr << "[aasdk] create_entity: cryptor initialized OK (connect mode)" << std::endl;
    } catch (const std::exception& e) {
        std::cerr << "[aasdk] create_entity: cryptor init FAILED: " << e.what() << std::endl;
        output_.emit(R"({"type":"event","event_type":"cryptor_error","error":")" + std::string(e.what()) + R"("})");
        return;
    }

    std::cerr << "[aasdk] create_entity: creating messenger..." << std::endl;    auto messenger = std::make_shared<aasdk::messenger::Messenger>(
        io_service_,
        std::make_shared<aasdk::messenger::MessageInStream>(io_service_, transport, cryptor),
        std::make_shared<aasdk::messenger::MessageOutStream>(io_service_, transport, cryptor));

    entity_ = std::make_shared<HeadlessAutoEntity>(
        io_service_, std::move(cryptor), std::move(transport),
        std::move(messenger), output_, config_);

    // On phone disconnect: notify OAL + restart scanning
    entity_->set_disconnect_callback([this]() {
        auto was_transport = active_transport_;
        std::cerr << "[aasdk] disconnect callback: transport="
                  << (was_transport == TransportType::USB ? "USB" : "wireless")
                  << " running=" << running_ << std::endl;
        state_.connected = false;
        state_.session_active = false;
        active_transport_ = TransportType::NONE;
        entity_.reset();
        if (oal_session_) {
            oal_session_->on_phone_disconnected();
        }
        if (running_) {
            io_service_.post([this, was_transport]() {
                // Always restart USB scanning (with delay for USB reset)
                if (usb_hub_) {
                    std::cerr << "[aasdk] disconnect: restarting USB scan in 2s..." << std::endl;
                    auto timer = std::make_shared<boost::asio::deadline_timer>(io_service_);
                    timer->expires_from_now(boost::posix_time::seconds(2));
                    timer->async_wait([this, timer](const boost::system::error_code& ec) {
                        if (!ec && running_) start_usb_scanning();
                    });
                }
            });
        }
    });

    // Propagate session to entity (which propagates to handlers)
    if (oal_session_) {
        entity_->set_oal_session(oal_session_);
        oal_session_->on_phone_connected(phone_name_);
    }

    entity_->start();
    state_.session_active = true;
}

void LiveAasdkSession::create_entity_no_ssl(aasdk::transport::ITransport::Pointer transport) {
    std::cerr << "[aasdk] create_entity_no_ssl: TLS already at socket level" << std::endl;

    // Create a cryptor but don't use it for encryption ??? TLS is already on the socket.
    // The aasdk messenger still needs a cryptor reference but it won't do crypto.
    // We use a normal cryptor in "already active" mode by marking it active after init.
    auto sslWrapper = std::make_shared<aasdk::transport::SSLWrapper>();
    auto cryptor = std::make_shared<aasdk::messenger::Cryptor>(std::move(sslWrapper));
    try {
        cryptor->init();
    } catch (const std::exception& e) {
        std::cerr << "[aasdk] cryptor init failed: " << e.what() << std::endl;
        return;
    }

    auto messenger = std::make_shared<aasdk::messenger::Messenger>(
        io_service_,
        std::make_shared<aasdk::messenger::MessageInStream>(io_service_, transport, cryptor),
        std::make_shared<aasdk::messenger::MessageOutStream>(io_service_, transport, cryptor));

    entity_ = std::make_shared<HeadlessAutoEntity>(
        io_service_, std::move(cryptor), std::move(transport),
        std::move(messenger), output_, config_);

    // On phone disconnect: same unified callback as USB path
    entity_->set_disconnect_callback([this]() {
        auto was_transport = active_transport_;
        std::cerr << "[aasdk] disconnect callback (no-ssl): transport="
                  << (was_transport == TransportType::USB ? "USB" : "wireless")
                  << " running=" << running_ << std::endl;
        state_.connected = false;
        state_.session_active = false;
        active_transport_ = TransportType::NONE;
        entity_.reset();
        if (oal_session_) {
            oal_session_->on_phone_disconnected();
        }
        if (running_) {
            io_service_.post([this]() {
                if (usb_hub_) {
                    auto timer = std::make_shared<boost::asio::deadline_timer>(io_service_);
                    timer->expires_from_now(boost::posix_time::seconds(2));
                    timer->async_wait([this, timer](const boost::system::error_code& ec) {
                        if (!ec && running_) start_usb_scanning();
                    });
                }
            });
        }
    });

    // Propagate session to entity
    if (oal_session_) {
        entity_->set_oal_session(oal_session_);
        oal_session_->on_phone_connected(phone_name_);
    }

    // Start the entity — it will send version request as the first thing inside the TLS tunnel
    entity_->start();
    state_.session_active = true;
}

void LiveAasdkSession::run_io_thread() {
    try {
        io_service_.run();
    } catch (const std::exception& e) {
        output_.emit(R"({"type":"event","event_type":"io_thread_error","error":")" +
                     std::string(e.what()) + R"("})");
    }
    running_ = false;
}

// ============================================================================
// HeadlessVideoHandler
// ============================================================================

HeadlessVideoHandler::HeadlessVideoHandler(
    boost::asio::io_service& io_service,
    aasdk::messenger::IMessenger::Pointer messenger,
    ThreadSafeOutputSink& output,
    int width, int height, int fps, int dpi,
    int video_codec,
    const HeadlessConfig::UiConfigExperiment& ui_experiment,
    int video_fd,
    std::mutex* pipe_mutex)
    : strand_(io_service)
    , io_service_(io_service)
    , channel_(std::make_shared<aasdk::channel::mediasink::video::channel::VideoChannel>(strand_, std::move(messenger)))
    , output_(output)
    , width_(width), height_(height), fps_(fps), dpi_(dpi)
    , video_codec_(video_codec)
    , ui_experiment_(ui_experiment)
    , video_fd_(video_fd)
    , pipe_mutex_(pipe_mutex)
{
}

void HeadlessVideoHandler::start() {
    strand_.dispatch([this, self = shared_from_this()]() {
        channel_->receive(self);
    });
}

void HeadlessVideoHandler::stop() {
    if (runtime_ui_timer_) {
        runtime_ui_timer_->cancel();
        runtime_ui_timer_.reset();
    }
    // Channel will be stopped when transport stops.
}

// fillFeatures removed (SDR built inline)

void HeadlessVideoHandler::sendVideoFocusIndication() {
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [](auto) {});
    aap_protobuf::service::media::video::message::VideoFocusNotification indication;
    indication.set_focus(aap_protobuf::service::media::video::message::VIDEO_FOCUS_PROJECTED);
    indication.set_unsolicited(false);
    channel_->sendVideoFocusIndication(indication, std::move(promise));
}

void HeadlessVideoHandler::sendUiThemeUpdate(bool night_mode) {
    int mode = night_mode ? 1 : 0;
    if (mode == last_night_mode_sent_) return;  // already sent this mode
    last_night_mode_sent_ = mode;

    // Build UpdateUiConfigRequest and send via video channel's messenger
    auto message = std::make_shared<aasdk::messenger::Message>(
        aasdk::messenger::ChannelId::MEDIA_SINK_VIDEO,
        aasdk::messenger::EncryptionType::ENCRYPTED,
        aasdk::messenger::MessageType::SPECIFIC);
    message->insertPayload(
        aasdk::messenger::MessageId(
            aap_protobuf::service::media::sink::MediaMessageId::MEDIA_MESSAGE_UPDATE_UI_CONFIG_REQUEST).getData());

    aap_protobuf::service::control::message::UpdateUiConfigRequest request;
    auto* ui_config = request.mutable_ui_config();
    ui_config->set_ui_theme(night_mode
        ? aap_protobuf::service::media::shared::message::UI_THEME_DARK
        : aap_protobuf::service::media::shared::message::UI_THEME_LIGHT);
    message->insertPayload(request);

    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then(
        [night_mode]() {
            std::cerr << "[aasdk] UI theme update sent: "
                      << (night_mode ? "dark" : "light") << std::endl;
        },
        [](auto e) {
            std::cerr << "[aasdk] UI theme update send failed: " << e.what() << std::endl;
        });
    channel_->send(std::move(message), std::move(promise));
}

void HeadlessVideoHandler::sendUiConfigUpdate(
    const HeadlessConfig::UiInsets& margins,
    const HeadlessConfig::UiInsets& content_insets,
    const HeadlessConfig::UiInsets& stable_insets,
    const std::string& reason)
{
    if (!margins.any() && !content_insets.any() && !stable_insets.any()) {
        return;
    }

    auto message = std::make_shared<aasdk::messenger::Message>(
        aasdk::messenger::ChannelId::MEDIA_SINK_VIDEO,
        aasdk::messenger::EncryptionType::ENCRYPTED,
        aasdk::messenger::MessageType::SPECIFIC);
    message->insertPayload(
        aasdk::messenger::MessageId(
            aap_protobuf::service::media::sink::MediaMessageId::MEDIA_MESSAGE_UPDATE_UI_CONFIG_REQUEST).getData());

    aap_protobuf::service::control::message::UpdateUiConfigRequest request;
    auto* ui_config = request.mutable_ui_config();
    apply_ui_config(ui_config, margins, content_insets, stable_insets);
    if (last_night_mode_sent_ == 1) {
        ui_config->set_ui_theme(aap_protobuf::service::media::shared::message::UI_THEME_DARK);
    } else if (last_night_mode_sent_ == 0) {
        ui_config->set_ui_theme(aap_protobuf::service::media::shared::message::UI_THEME_LIGHT);
    }
    message->insertPayload(request);

    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then(
        [reason, margins, content_insets, stable_insets]() {
            std::cerr << "[aasdk] UI config update sent (" << reason << ")"
                      << " margins={" << format_ui_insets(margins) << "}"
                      << " content={" << format_ui_insets(content_insets) << "}"
                      << " stable={" << format_ui_insets(stable_insets) << "}"
                      << std::endl;
        },
        [reason](auto e) {
            std::cerr << "[aasdk] UI config update send failed (" << reason
                      << "): " << e.what() << std::endl;
        });
    channel_->send(std::move(message), std::move(promise));
}

void HeadlessVideoHandler::replayCachedKeyframe() {
    if (!has_cached_keyframe_) {
        std::cerr << "[aasdk] replay skipped: no cached keyframe" << std::endl;
        return;
    }
    uint32_t w = static_cast<uint32_t>(width_);
    uint32_t h = static_cast<uint32_t>(height_);

    if (oal_session_) {
        // Only replay SPS/PPS (codec config) so the app can configure its decoder.
        // Do NOT replay the cached IDR — it's stale and P-frames from the live
        // phone stream reference a different (newer) IDR. Feeding the stale IDR
        // followed by live P-frames corrupts the decoder's reference picture buffer,
        // producing green/blocky artifacts that persist until a fresh IDR arrives.
        // Instead, the caller should also call request_fresh_idr() to get a clean
        // IDR from the phone that matches the current P-frame stream.
        if (!cached_sps_pps_.empty()) {
            std::cerr << "[aasdk] replaying cached SPS/PPS (" << cached_sps_pps_.size() << " bytes), skipping stale IDR" << std::endl;
            oal_session_->write_video_frame(
                static_cast<uint16_t>(w), static_cast<uint16_t>(h), 0,
                OalVideoFlags::CODEC_CONFIG,
                cached_sps_pps_.data(), cached_sps_pps_.size());
        } else {
            std::cerr << "[aasdk] no cached SPS/PPS to replay (H.265 combined frames only)" << std::endl;
        }
    } else {
        std::cerr << "[aasdk] replay skipped: oal_session_ null" << std::endl;
    }
}

void HeadlessVideoHandler::onChannelOpenRequest(
    const aap_protobuf::service::control::message::ChannelOpenRequest& request)
{
    std::cerr << "[aasdk] VIDEO CHANNEL OPEN REQUEST!" << std::endl;
    aap_protobuf::service::control::message::ChannelOpenResponse response;
    response.set_status(aap_protobuf::shared::STATUS_SUCCESS);

    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {},
        [this, self = shared_from_this()](auto e) { this->onChannelError(e); });
    channel_->sendChannelOpenResponse(response, std::move(promise));
    channel_->receive(shared_from_this());
}

void HeadlessVideoHandler::onMediaChannelSetupRequest(
    const aap_protobuf::service::media::shared::message::Setup& request)
{
    aap_protobuf::service::media::shared::message::Config response;
    response.set_status(aap_protobuf::service::media::shared::message::Config_Status_STATUS_READY);
    response.set_max_unacked(1);
    response.add_configuration_indices(0);

    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([this, self = shared_from_this()]() {
            this->sendVideoFocusIndication();
        },
        [this, self = shared_from_this()](auto) {});
    channel_->sendChannelSetupResponse(response, std::move(promise));
    channel_->receive(shared_from_this());
}

void HeadlessVideoHandler::onMediaChannelStartIndication(
    const aap_protobuf::service::media::shared::message::Start& indication)
{
    session_ = indication.session_id();
    output_.emit(R"({"type":"event","event_type":"video_stream_start","session":)" +
                 std::to_string(session_) + "}");
    if (!runtime_ui_config_sent_ && ui_experiment_.has_runtime_ui_config()) {
        runtime_ui_config_sent_ = true;
        runtime_ui_timer_ = std::make_shared<boost::asio::deadline_timer>(io_service_);
        runtime_ui_timer_->expires_from_now(
            boost::posix_time::milliseconds(std::max(0, ui_experiment_.runtime_delay_ms)));
        runtime_ui_timer_->async_wait(strand_.wrap(
            [this, self = shared_from_this(), timer = runtime_ui_timer_](const boost::system::error_code& ec) {
                if (ec) return;
                this->sendUiConfigUpdate(
                    ui_experiment_.runtime_margins,
                    ui_experiment_.runtime_content_insets,
                    ui_experiment_.runtime_stable_insets,
                    "runtime_experiment");
                runtime_ui_timer_.reset();
            }));
    }
    channel_->receive(shared_from_this());
}

void HeadlessVideoHandler::onMediaChannelStopIndication(
    const aap_protobuf::service::media::shared::message::Stop&)
{
    output_.emit(json_event("video_stream_stop"));
    channel_->receive(shared_from_this());
}

void HeadlessVideoHandler::onMediaWithTimestampIndication(
    aasdk::messenger::Timestamp::ValueType timestamp,
    const aasdk::common::DataConstBuffer& buffer)
{
    frame_counter_++;

    if (frame_counter_ % 300 == 1) {
        std::cerr << "[aasdk] video frame #" << frame_counter_
                  << " size=" << buffer.size << " ts=" << timestamp << std::endl;
    }

    // Detect keyframes — codec-aware.
    // H.264/H.265: scan for Annex B start codes + NAL type
    // VP9: check frame header byte (no start codes)
    // AV1: treat all frames as potential keyframes for now
    int flags = 0;
    bool has_sps = false;
    bool has_idr = false;

    if (video_codec_ == 5) {
        // VP9: keyframe detection from frame header
        // VP9 uncompressed header: bit 0 = frame_marker (2 bits), then profile,
        // then show_existing_frame, then frame_type (0=key, 1=inter)
        // Simplified: for profile 0/1, (first_byte & 0x04) == 0 means keyframe
        if (buffer.size >= 1) {
            // VP9 has no SPS/PPS concept — each keyframe is self-contained
            bool is_keyframe = (buffer.cdata[0] & 0x04) == 0;
            if (is_keyframe) {
                has_idr = true;
            }
        }
    } else if (video_codec_ == 3 || video_codec_ == 7) {
        // H.264 (3) or H.265 (7): scan for Annex B start codes
        bool is_h265 = (video_codec_ == 7);

        if (buffer.size >= 5) {
            for (size_t i = 0; i + 4 < buffer.size; ++i) {
                bool sc4 = (buffer.cdata[i] == 0 && buffer.cdata[i+1] == 0 &&
                            buffer.cdata[i+2] == 0 && buffer.cdata[i+3] == 1);
                bool sc3 = (buffer.cdata[i] == 0 && buffer.cdata[i+1] == 0 &&
                            buffer.cdata[i+2] == 1);
                if (sc4 || sc3) {
                    size_t nal_offset = sc4 ? i + 4 : i + 3;
                    if (nal_offset < buffer.size) {
                        uint8_t first_byte = buffer.cdata[nal_offset];
                        if (is_h265) {
                            uint8_t nal_type = (first_byte >> 1) & 0x3f;
                            if (nal_type >= 32 && nal_type <= 34) has_sps = true;  // VPS/SPS/PPS
                            if (nal_type == 19 || nal_type == 20) has_idr = true;  // IDR
                        } else {
                            uint8_t nal_type = first_byte & 0x1f;
                            if (nal_type == 7 || nal_type == 8) has_sps = true;  // SPS/PPS
                            if (nal_type == 5) has_idr = true;  // IDR
                        }
                    }
                }
            }
        }
    } else {
        // AV1 or unknown: treat first frame as keyframe, rest as regular
        if (frame_counter_ == 1) {
            has_idr = true;
        }
    }

    // Cache SPS/PPS and IDR for replay when car app connects late.
    // Only cache standalone SPS/PPS (no IDR in same frame). When SPS/PPS is
    // prepended to an IDR (common in H.265), cache the whole thing as IDR instead.
    if (has_sps && !has_idr) {
        cached_sps_pps_.assign(buffer.cdata, buffer.cdata + buffer.size);
        has_cached_keyframe_ = true;
        std::cerr << "[aasdk] cached SPS/PPS (" << buffer.size << " bytes)" << std::endl;
    }
    if (has_idr) {
        cached_idr_.assign(buffer.cdata, buffer.cdata + buffer.size);
        has_cached_keyframe_ = true;
        if (frame_counter_ % 300 == 0 || frame_counter_ <= 5) {
            std::cerr << "[aasdk] cached IDR (" << buffer.size << " bytes)" << std::endl;
        }
    }

    // Convert aasdk microsecond timestamp to millisecond PTS
    uint32_t pts_ms = static_cast<uint32_t>(timestamp / 1000);
    uint32_t w = static_cast<uint32_t>(width_);
    uint32_t h = static_cast<uint32_t>(height_);

    // OAL path: write OAL video frame directly
    if (oal_session_) {
        uint16_t oal_flags = 0;
        if (has_idr) {
            // IDR frame (may include inline VPS/SPS/PPS — codec handles it)
            oal_flags |= OalVideoFlags::KEYFRAME;
        } else if (has_sps) {
            // Standalone SPS/PPS/VPS (no IDR in same frame)
            oal_flags |= OalVideoFlags::CODEC_CONFIG;
        }

        oal_session_->write_video_frame(
            static_cast<uint16_t>(w), static_cast<uint16_t>(h),
            pts_ms, oal_flags,
            buffer.cdata, buffer.size);
    }
    // Media pipe path: write to fd for Python to read (legacy stub)
    else if (video_fd_ >= 0) {
        uint32_t enc = 3;
        uint32_t payload_len = static_cast<uint32_t>(20 + buffer.size);
        uint8_t header[25];
        header[0] = 0x01; // VIDEO_DATA type tag
        memcpy(header + 1,  &payload_len, 4);
        memcpy(header + 5,  &w, 4);
        memcpy(header + 9,  &h, 4);
        memcpy(header + 13, &enc, 4);
        memcpy(header + 17, &pts_ms, 4);
        memcpy(header + 21, &flags, 4);

        struct iovec iov[2];
        iov[0].iov_base = header;
        iov[0].iov_len = 25;
        iov[1].iov_base = const_cast<uint8_t*>(buffer.cdata);
        iov[1].iov_len = buffer.size;
        if (pipe_mutex_) {
            std::lock_guard<std::mutex> lock(*pipe_mutex_);
            writev(video_fd_, iov, 2);
        } else {
            writev(video_fd_, iov, 2);
        }
    }

    // Send ack to phone (required for flow control)
    sendAck(session_, 1);
    channel_->receive(shared_from_this());
}

void HeadlessVideoHandler::onMediaIndication(const aasdk::common::DataConstBuffer& buffer) {
    // Fallback for frames without timestamp
    onMediaWithTimestampIndication(0, buffer);
}

void HeadlessVideoHandler::onVideoFocusRequest(
    const aap_protobuf::service::media::video::message::VideoFocusRequestNotification& request)
{
    std::cerr << "[aasdk] VideoFocusRequest received, responding with FOCUSED" << std::endl;
    output_.emit(R"({"type":"event","event_type":"video_focus_request","disp_index":)" +
                 std::to_string(0) + "}");
    this->sendVideoFocusIndication();
    channel_->receive(shared_from_this());
}

void HeadlessVideoHandler::onChannelError(const aasdk::error::Error& e) {
    output_.emit(R"({"type":"event","event_type":"video_channel_error","error":")" +
                 std::string(e.what()) + R"("})");
}

void HeadlessVideoHandler::sendAck(uint32_t session, uint32_t value) {
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [](auto) {});

    aap_protobuf::service::media::source::message::Ack ack;
    ack.set_session_id(session);
    ack.set_ack(value);
    channel_->sendMediaAckIndication(ack, std::move(promise));
}

// ============================================================================
// HeadlessAudioHandler
// ============================================================================

HeadlessAudioHandler::HeadlessAudioHandler(
    boost::asio::io_service& io_service,
    aasdk::messenger::IMessenger::Pointer messenger,
    ThreadSafeOutputSink& output,
    ChannelType type,
    int media_fd,
    std::mutex* pipe_mutex)
    : strand_(io_service)
    , output_(output)
    , type_(type)
    , media_fd_(media_fd)
    , pipe_mutex_(pipe_mutex)
{
    switch (type) {
    case ChannelType::Media:
        channel_ = std::make_shared<aasdk::channel::mediasink::audio::channel::MediaAudioChannel>(strand_, std::move(messenger));
        break;
    case ChannelType::Speech:
        channel_ = std::make_shared<aasdk::channel::mediasink::audio::channel::GuidanceAudioChannel>(strand_, std::move(messenger));
        break;
    case ChannelType::System:
        channel_ = std::make_shared<aasdk::channel::mediasink::audio::channel::SystemAudioChannel>(strand_, std::move(messenger));
        break;
    }
}

void HeadlessAudioHandler::start() {
    strand_.dispatch([this, self = shared_from_this()]() {
        channel_->receive(self);
    });
}

void HeadlessAudioHandler::stop() {}

void HeadlessAudioHandler::onChannelOpenRequest(
    const aap_protobuf::service::control::message::ChannelOpenRequest&)
{
    std::cerr << "[aasdk] AUDIO CHANNEL OPEN REQUEST" << std::endl;
    aap_protobuf::service::control::message::ChannelOpenResponse response;
    response.set_status(aap_protobuf::shared::STATUS_SUCCESS);

    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {},
        [this, self = shared_from_this()](auto e) { this->onChannelError(e); });
    channel_->sendChannelOpenResponse(response, std::move(promise));
    channel_->receive(shared_from_this());
}

void HeadlessAudioHandler::onMediaChannelSetupRequest(
    const aap_protobuf::service::media::shared::message::Setup&)
{
    aap_protobuf::service::media::shared::message::Config response;
    response.set_status(aap_protobuf::service::media::shared::message::Config_Status_STATUS_READY);
    response.set_max_unacked(1);
    response.add_configuration_indices(0);

    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {},
        [this, self = shared_from_this()](auto e) { this->onChannelError(e); });
    channel_->sendChannelSetupResponse(response, std::move(promise));
    channel_->receive(shared_from_this());
}

void HeadlessAudioHandler::onMediaChannelStartIndication(
    const aap_protobuf::service::media::shared::message::Start& indication)
{
    session_ = indication.session_id();
    const char* name = (type_ == ChannelType::Media) ? "media" :
                       (type_ == ChannelType::Speech) ? "speech" : "system";
    output_.emit(R"({"type":"event","event_type":"audio_stream_start","channel":")" +
                 std::string(name) + R"(","session":)" + std::to_string(session_) + "}");

    // Send audio_start control message
    if (oal_session_) {
        uint8_t purpose = audio_channel_to_oal_purpose(name);
        uint16_t sr; uint8_t ch;
        audio_channel_params(name, sr, ch);
        oal_session_->send_audio_start(purpose, sr, ch);
    } else {
        // Fallback: emit audio command to stdout
        int cmd = 1;
        int audio_type = 1;
        if (type_ == ChannelType::Media) { cmd = 10; audio_type = 1; }
        else if (type_ == ChannelType::Speech) { cmd = 6; audio_type = 3; }
        else if (type_ == ChannelType::System) { cmd = 12; audio_type = 2; }
        output_.emit(R"({"type":"audio_command","command":)" + std::to_string(cmd) +
                     R"(,"decode_type":4,"audio_type":)" + std::to_string(audio_type) +
                     R"(,"volume":0.0})");
    }

    channel_->receive(shared_from_this());
}

void HeadlessAudioHandler::onMediaChannelStopIndication(
    const aap_protobuf::service::media::shared::message::Stop&)
{
    const char* name = (type_ == ChannelType::Media) ? "media" :
                       (type_ == ChannelType::Speech) ? "speech" : "system";
    output_.emit(R"({"type":"event","event_type":"audio_stream_stop","channel":")" +
                 std::string(name) + R"("})");

    // Send audio_stop control message
    if (oal_session_) {
        uint8_t purpose = audio_channel_to_oal_purpose(name);
        oal_session_->send_audio_stop(purpose);
    } else {
        // Fallback: emit audio command to stdout
        int cmd = 2;
        int audio_type = 1;
        if (type_ == ChannelType::Media) { cmd = 11; audio_type = 1; }
        else if (type_ == ChannelType::Speech) { cmd = 7; audio_type = 3; }
        else if (type_ == ChannelType::System) { cmd = 13; audio_type = 2; }
        output_.emit(R"({"type":"audio_command","command":)" + std::to_string(cmd) +
                     R"(,"decode_type":2,"audio_type":)" + std::to_string(audio_type) +
                     R"(,"volume":0.0})");
    }

    channel_->receive(shared_from_this());
}

void HeadlessAudioHandler::onMediaWithTimestampIndication(
    aasdk::messenger::Timestamp::ValueType /* timestamp */,
    const aasdk::common::DataConstBuffer& buffer)
{
    audio_frame_count_++;

    if (audio_frame_count_ <= 5 || audio_frame_count_ % 500 == 0) {
        const char* channel_name = (type_ == ChannelType::Media) ? "media" :
                                   (type_ == ChannelType::Speech) ? "speech" : "system";
        std::cerr << "[aasdk] audio " << channel_name
                  << " frame #" << audio_frame_count_
                  << " size=" << buffer.size << std::endl;
    }

    // Determine OAL purpose and audio params from channel type
    const char* channel_name = (type_ == ChannelType::Media) ? "media" :
                               (type_ == ChannelType::Speech) ? "speech" : "system";

    // Write OAL audio frame
    if (oal_session_) {
        uint8_t purpose = audio_channel_to_oal_purpose(channel_name);
        uint16_t sr; uint8_t ch;
        audio_channel_params(channel_name, sr, ch);
        oal_session_->write_audio_frame(buffer.cdata, buffer.size,
                                        purpose, sr, ch);
    }

    // ACK the frame — tells the phone we consumed it and it can send the next.
    // Without this, the phone sends max_unacked frames in a burst then stalls.
    // With ACK + max_unacked=1, the phone paces to exactly real-time.
    {
        aap_protobuf::service::media::source::message::Ack indication;
        indication.set_session_id(session_);
        indication.set_ack(1);
        auto promise = aasdk::channel::SendPromise::defer(strand_);
        promise->then([]() {},
            [this, self = shared_from_this()](auto e) { this->onChannelError(e); });
        channel_->sendMediaAckIndication(indication, std::move(promise));
    }

    channel_->receive(shared_from_this());
}

void HeadlessAudioHandler::onMediaIndication(const aasdk::common::DataConstBuffer& buffer) {
    onMediaWithTimestampIndication(0, buffer);
}

void HeadlessAudioHandler::onChannelError(const aasdk::error::Error& e) {
    const char* name = (type_ == ChannelType::Media) ? "media" :
                       (type_ == ChannelType::Speech) ? "speech" : "system";
    output_.emit(R"({"type":"event","event_type":"audio_channel_error","channel":")" +
                 std::string(name) + R"(","error":")" + std::string(e.what()) + R"("})");

    // Send audio_stop so the app doesn't leave the AudioTrack active
    if (oal_session_) {
        uint8_t purpose = audio_channel_to_oal_purpose(name);
        oal_session_->send_audio_stop(purpose);
    }
}

// ============================================================================
// HeadlessAudioInputHandler
// ============================================================================

HeadlessAudioInputHandler::HeadlessAudioInputHandler(
    boost::asio::io_service& io_service,
    aasdk::messenger::IMessenger::Pointer messenger,
    ThreadSafeOutputSink& output)
    : strand_(io_service)
    , channel_(std::make_shared<aasdk::channel::mediasource::MediaSourceService>(strand_, std::move(messenger), aasdk::messenger::ChannelId::MEDIA_SOURCE_MICROPHONE))
    , output_(output)
{
}

void HeadlessAudioInputHandler::start() {
    strand_.dispatch([this, self = shared_from_this()]() {
        channel_->receive(self);
    });
}

void HeadlessAudioInputHandler::stop() {}

// fillFeatures removed (SDR built inline)

void HeadlessAudioInputHandler::feedAudio(const uint8_t* data, size_t size) {
    if (!open_.load() || size == 0) return;

    has_real_audio_.store(true);

    // Copy PCM data and dispatch send to strand — feedAudio is called from
    // the audio TCP read thread; channel operations must run on the strand.
    aasdk::common::Data audio_data(data, data + size);
    auto ts = static_cast<aasdk::messenger::Timestamp::ValueType>(
        std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count());

    strand_.post([this, self = shared_from_this(), ts,
                  audio_data = std::move(audio_data)]() mutable {
        auto promise = aasdk::channel::SendPromise::defer(strand_);
        promise->then([]() {}, [](auto) {});
        channel_->sendMediaSourceWithTimestampIndication(ts, audio_data, std::move(promise));
    });
}

void HeadlessAudioInputHandler::startSilencePump() {
    if (!open_.load()) return;
    has_real_audio_.store(false);
    silence_running_.store(true);
    pumpOneSilenceFrame();
}

void HeadlessAudioInputHandler::pumpOneSilenceFrame() {
    if (!silence_running_.load() || !open_.load() || has_real_audio_.load()) return;

    // 20ms of 16kHz 16-bit mono silence = 640 bytes
    constexpr size_t frame_size = 640;
    aasdk::common::Data silence(frame_size, 0);

    auto ts = static_cast<aasdk::messenger::Timestamp::ValueType>(
        std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count());

    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then(
        [this, self = shared_from_this()]() {
            // After phone ACKs this frame, send another
            pumpOneSilenceFrame();
        },
        [this, self = shared_from_this()](auto e) {
            std::cerr << "[aasdk] silence pump send error: " << e.what() << std::endl;
            silence_running_.store(false);
        });
    channel_->sendMediaSourceWithTimestampIndication(ts, silence, std::move(promise));
}

void HeadlessAudioInputHandler::stopSilencePump() {
    silence_running_.store(false);
    // No thread to join anymore — pump runs inline on strand
}

void HeadlessAudioInputHandler::onChannelOpenRequest(
    const aap_protobuf::service::control::message::ChannelOpenRequest&)
{
    std::cerr << "[aasdk] AUDIO INPUT CHANNEL OPEN REQUEST" << std::endl;
    aap_protobuf::service::control::message::ChannelOpenResponse response;
    response.set_status(aap_protobuf::shared::STATUS_SUCCESS);

    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {},
        [this, self = shared_from_this()](auto e) { this->onChannelError(e); });
    channel_->sendChannelOpenResponse(response, std::move(promise));
    channel_->receive(shared_from_this());
}

void HeadlessAudioInputHandler::onMediaChannelSetupRequest(
    const aap_protobuf::service::media::shared::message::Setup&)
{
    aap_protobuf::service::media::shared::message::Config response;
    response.set_status(aap_protobuf::service::media::shared::message::Config_Status_STATUS_READY);
    response.set_max_unacked(1);
    response.add_configuration_indices(0);

    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {},
        [this, self = shared_from_this()](auto e) { this->onChannelError(e); });
    channel_->sendChannelSetupResponse(response, std::move(promise));
    channel_->receive(shared_from_this());
}

void HeadlessAudioInputHandler::onMediaSourceOpenRequest(
    const aap_protobuf::service::media::source::message::MicrophoneRequest& request)
{
    open_.store(request.open());
    std::cerr << "[aasdk] mic " << (open_ ? "OPEN" : "CLOSE") << " request" << std::endl;
    output_.emit(R"({"type":"event","event_type":"audio_input_open","open":)" +
                 std::string(open_ ? "true" : "false") + "}");

    // Send mic_start/mic_stop control messages to car app
    if (oal_session_) {
        if (open_) {
            oal_session_->send_mic_start(16000);
        } else {
            oal_session_->send_mic_stop();
        }
    }

    // Start silence pump immediately on open — don't wait for response ACK.
    // The phone expects audio frames as soon as it processes the request;
    // delaying until the response ACK causes a gap that can make the phone
    // timeout and cancel the voice session.
    stopSilencePump();
    if (open_) {
        std::cerr << "[aasdk] starting silence pump" << std::endl;
        startSilencePump();
    }

    aap_protobuf::service::media::source::message::MicrophoneResponse response;
    response.set_status(aap_protobuf::shared::STATUS_SUCCESS);
    response.set_session_id(session_);

    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then(
        []() {},
        [this, self = shared_from_this()](auto e) { this->onChannelError(e); });
    channel_->sendMicrophoneOpenResponse(response, std::move(promise));
    channel_->receive(shared_from_this());
}

void HeadlessAudioInputHandler::onMediaChannelAckIndication(
    const aap_protobuf::service::media::source::message::Ack&)
{
    // Phone acknowledged our mic data
    channel_->receive(shared_from_this());
}

void HeadlessAudioInputHandler::onChannelError(const aasdk::error::Error& e) {
    output_.emit(R"({"type":"event","event_type":"audio_input_error","error":")" +
                 std::string(e.what()) + R"("})");
}

// ============================================================================
// HeadlessSensorHandler
// ============================================================================

HeadlessSensorHandler::HeadlessSensorHandler(
    boost::asio::io_service& io_service,
    aasdk::messenger::IMessenger::Pointer messenger,
    ThreadSafeOutputSink& output)
    : strand_(io_service)
    , channel_(std::make_shared<aasdk::channel::sensorsource::SensorSourceService>(strand_, std::move(messenger)))
    , output_(output)
{
}

void HeadlessSensorHandler::start() {
    strand_.dispatch([this, self = shared_from_this()]() {
        channel_->receive(self);
    });
}

void HeadlessSensorHandler::stop() {}

// fillFeatures removed (SDR built inline)

void HeadlessSensorHandler::sendNightMode(bool night) {
    aap_protobuf::service::sensorsource::message::SensorBatch indication;
    auto* night_mode = indication.add_night_mode_data();
    night_mode->set_night_mode(night);

    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [](auto) {});
    channel_->sendSensorEventIndication(indication, std::move(promise));
}

void HeadlessSensorHandler::sendDrivingStatus(bool moving) {
    aap_protobuf::service::sensorsource::message::SensorBatch indication;
    auto* driving = indication.add_driving_status_data();
    // AA DrivingStatus: 0 = UNRESTRICTED (parked), non-zero = restriction flags
    // moving=false (parked) → 0 (unrestricted), moving=true → 31 (fully restricted)
    driving->set_status(moving ? 31 : 0);

    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [](auto) {});
    channel_->sendSensorEventIndication(indication, std::move(promise));
}

void HeadlessSensorHandler::sendGpsLocation(
    double lat, double lon, double alt, float speed, float bearing, uint64_t timestamp_ms)
{
    aap_protobuf::service::sensorsource::message::SensorBatch indication;
    auto* gps = indication.add_location_data();
    gps->set_latitude_e7(static_cast<int32_t>(lat * 1e7));
    gps->set_longitude_e7(static_cast<int32_t>(lon * 1e7));
    gps->set_altitude_e2(static_cast<int32_t>(alt * 1e2));
    gps->set_speed_e3(static_cast<int32_t>(speed * 1000));
    gps->set_bearing_e6(static_cast<int32_t>(bearing * 1e6));
    gps->set_timestamp(timestamp_ms);
    gps->set_accuracy_e3(static_cast<uint32_t>(10 * 1000));

    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [](auto) {});
    channel_->sendSensorEventIndication(indication, std::move(promise));
}

void HeadlessSensorHandler::sendSpeed(int speed_mm_per_s) {
    aap_protobuf::service::sensorsource::message::SensorBatch indication;
    auto* sd = indication.add_speed_data();
    sd->set_speed_e3(speed_mm_per_s);
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [](auto) {});
    channel_->sendSensorEventIndication(indication, std::move(promise));
}

void HeadlessSensorHandler::sendGear(int gear) {
    aap_protobuf::service::sensorsource::message::SensorBatch indication;
    auto* gd = indication.add_gear_data();
    gd->set_gear(static_cast<aap_protobuf::service::sensorsource::message::Gear>(gear));
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [](auto) {});
    channel_->sendSensorEventIndication(indication, std::move(promise));
}

void HeadlessSensorHandler::sendParkingBrake(bool engaged) {
    aap_protobuf::service::sensorsource::message::SensorBatch indication;
    auto* pb = indication.add_parking_brake_data();
    pb->set_parking_brake(engaged);
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [](auto) {});
    channel_->sendSensorEventIndication(indication, std::move(promise));
}

void HeadlessSensorHandler::sendFuel(int fuel_level_pct, int range_m, bool low_fuel) {
    aap_protobuf::service::sensorsource::message::SensorBatch indication;
    auto* fd = indication.add_fuel_data();
    fd->set_fuel_level(fuel_level_pct);
    fd->set_range(range_m);
    fd->set_low_fuel_warning(low_fuel);
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [](auto) {});
    channel_->sendSensorEventIndication(indication, std::move(promise));
}

void HeadlessSensorHandler::sendOdometer(int km_e1) {
    aap_protobuf::service::sensorsource::message::SensorBatch indication;
    auto* od = indication.add_odometer_data();
    od->set_kms_e1(km_e1);
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [](auto) {});
    channel_->sendSensorEventIndication(indication, std::move(promise));
}

void HeadlessSensorHandler::sendEnvironment(int temp_e3) {
    aap_protobuf::service::sensorsource::message::SensorBatch indication;
    auto* ed = indication.add_environment_data();
    ed->set_temperature_e3(temp_e3);
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [](auto) {});
    channel_->sendSensorEventIndication(indication, std::move(promise));
}

void HeadlessSensorHandler::sendDoor(bool hood, bool trunk, const std::vector<bool>& doors) {
    aap_protobuf::service::sensorsource::message::SensorBatch indication;
    auto* dd = indication.add_door_data();
    dd->set_hood_open(hood);
    dd->set_trunk_open(trunk);
    for (bool d : doors) { dd->add_door_open(d); }
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [](auto) {});
    channel_->sendSensorEventIndication(indication, std::move(promise));
}

void HeadlessSensorHandler::sendLight(int headlight, int turn_indicator, bool hazard) {
    aap_protobuf::service::sensorsource::message::SensorBatch indication;
    auto* ld = indication.add_light_data();
    ld->set_head_light_state(
        static_cast<aap_protobuf::service::sensorsource::message::HeadLightState>(headlight));
    ld->set_turn_indicator_state(
        static_cast<aap_protobuf::service::sensorsource::message::TurnIndicatorState>(turn_indicator));
    ld->set_hazard_lights_on(hazard);
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [](auto) {});
    channel_->sendSensorEventIndication(indication, std::move(promise));
}

void HeadlessSensorHandler::sendTirePressure(const std::vector<int>& pressures_e2) {
    aap_protobuf::service::sensorsource::message::SensorBatch indication;
    auto* tp = indication.add_tire_pressure_data();
    for (int p : pressures_e2) { tp->add_tire_pressures_e2(p); }
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [](auto) {});
    channel_->sendSensorEventIndication(indication, std::move(promise));
}

void HeadlessSensorHandler::sendHvac(int target_temp_e3, int current_temp_e3) {
    aap_protobuf::service::sensorsource::message::SensorBatch indication;
    auto* hd = indication.add_hvac_data();
    hd->set_target_temperature_e3(target_temp_e3);
    hd->set_current_temperature_e3(current_temp_e3);
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [](auto) {});
    channel_->sendSensorEventIndication(indication, std::move(promise));
}

void HeadlessSensorHandler::sendAccelerometer(int x_e3, int y_e3, int z_e3) {
    aap_protobuf::service::sensorsource::message::SensorBatch indication;
    auto* ad = indication.add_accelerometer_data();
    ad->set_acceleration_x_e3(x_e3);
    ad->set_acceleration_y_e3(y_e3);
    ad->set_acceleration_z_e3(z_e3);
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [](auto) {});
    channel_->sendSensorEventIndication(indication, std::move(promise));
}

void HeadlessSensorHandler::sendGyroscope(int rx_e3, int ry_e3, int rz_e3) {
    aap_protobuf::service::sensorsource::message::SensorBatch indication;
    auto* gd = indication.add_gyroscope_data();
    gd->set_rotation_speed_x_e3(rx_e3);
    gd->set_rotation_speed_y_e3(ry_e3);
    gd->set_rotation_speed_z_e3(rz_e3);
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [](auto) {});
    channel_->sendSensorEventIndication(indication, std::move(promise));
}

void HeadlessSensorHandler::sendCompass(int bearing_e6, int pitch_e6, int roll_e6) {
    aap_protobuf::service::sensorsource::message::SensorBatch indication;
    auto* cd = indication.add_compass_data();
    cd->set_bearing_e6(bearing_e6);
    cd->set_pitch_e6(pitch_e6);
    cd->set_roll_e6(roll_e6);
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [](auto) {});
    channel_->sendSensorEventIndication(indication, std::move(promise));
}

void HeadlessSensorHandler::sendGpsSatellites(int in_use, int in_view,
                                              const std::vector<std::tuple<int,int,bool,int,int>>& satellites) {
    aap_protobuf::service::sensorsource::message::SensorBatch indication;
    auto* sd = indication.add_gps_satellite_data();
    sd->set_number_in_use(in_use);
    sd->set_number_in_view(in_view);
    for (const auto& [prn, snr_e3, used, az_e3, el_e3] : satellites) {
        auto* sat = sd->add_satellites();
        sat->set_prn(prn);
        sat->set_snr_e3(snr_e3);
        sat->set_used_in_fix(used);
        sat->set_azimuth_e3(az_e3);
        sat->set_elevation_e3(el_e3);
    }
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [](auto) {});
    channel_->sendSensorEventIndication(indication, std::move(promise));
}

void HeadlessSensorHandler::sendRpm(int rpm_e3) {
    aap_protobuf::service::sensorsource::message::SensorBatch indication;
    auto* rd = indication.add_rpm_data();
    rd->set_rpm_e3(rpm_e3);
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [](auto) {});
    channel_->sendSensorEventIndication(indication, std::move(promise));
}

void HeadlessSensorHandler::sendVehicleEnergyModel(int capacityWh, int currentWh, int rangeM) {
    if (capacityWh <= 0 || currentWh <= 0 || rangeM <= 0) return;
    if (!vemRequested_) return;

    // Build VehicleEnergyModel protobuf
    aap_protobuf::service::sensorsource::message::VehicleEnergyModel vem;

    // Battery config
    auto* batt = vem.mutable_battery();
    batt->set_config_id(1);
    batt->mutable_max_capacity()->set_watt_hours(capacityWh);
    // Min usable = 95% of total (reasonable estimate for usable vs gross)
    batt->mutable_min_usable_capacity()->set_watt_hours(static_cast<int>(capacityWh * 0.95));
    // Reserve = 5% of capacity
    batt->mutable_reserve_energy()->set_watt_hours(static_cast<int>(capacityWh * 0.05));
    batt->set_regen_braking_capable(true);

    // Energy consumption derived from car's own range estimate
    // consumption = currentEnergy / rangeRemaining (Wh/m) * 1000 (Wh/km)
    auto* cons = vem.mutable_consumption();
    float whPerKm = (static_cast<float>(currentWh) / static_cast<float>(rangeM)) * 1000.0f;
    cons->mutable_driving()->set_rate(whPerKm);
    cons->mutable_auxiliary()->set_rate(2.0f);  // typical aux consumption

    // Charging prefs
    auto* prefs = vem.mutable_charging_prefs();
    prefs->set_mode(1);  // standard

    // Add to SensorBatch field 23 as a message.
    // On the phone side, wrc is an empty pass-through message — our VEM proto
    // fields become "unknown fields" that wrc preserves and re-serializes to GMS.
    aap_protobuf::service::sensorsource::message::SensorBatch batch;
    auto* vemMsg = batch.add_vehicle_energy_model_data();
    *vemMsg = vem;

    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [](auto) {});
    channel_->sendSensorEventIndication(batch, std::move(promise));
    std::cerr << "[aasdk] sensor: sent VehicleEnergyModel (capacity=" << capacityWh
              << "Wh, current=" << currentWh << "Wh, range=" << rangeM
              << "m, consumption=" << whPerKm << "Wh/km)" << std::endl;
}

void HeadlessSensorHandler::onChannelOpenRequest(
    const aap_protobuf::service::control::message::ChannelOpenRequest&)
{
    std::cerr << "[aasdk] SENSOR CHANNEL OPEN REQUEST" << std::endl;
    aap_protobuf::service::control::message::ChannelOpenResponse response;
    response.set_status(aap_protobuf::shared::STATUS_SUCCESS);

    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {},
        [this, self = shared_from_this()](auto e) { this->onChannelError(e); });
    channel_->sendChannelOpenResponse(response, std::move(promise));
    channel_->receive(shared_from_this());
}

void HeadlessSensorHandler::onSensorStartRequest(
    const aap_protobuf::service::sensorsource::message::SensorRequest& request)
{
    output_.emit(R"({"type":"event","event_type":"sensor_start","sensor_type":)" +
                 std::to_string(request.type()) + "}");

    // Track if phone requests VEM sensor types (23 or 25)
    if (request.type() == 23 || request.type() == 25) {
        vemRequested_ = true;
        std::cerr << "[aasdk] sensor: phone requested VEM — will send when VHAL data available" << std::endl;
        // If we have cached values, send immediately
        if (cached_ev_cap_wh_ > 0 && cached_ev_level_wh_ > 0 && cached_range_m_ > 0) {
            std::cerr << "[aasdk] sensor: sending cached VEM immediately (cap="
                      << cached_ev_cap_wh_ << " level=" << cached_ev_level_wh_
                      << " range=" << cached_range_m_ << ")" << std::endl;
            sendVehicleEnergyModel(cached_ev_cap_wh_, cached_ev_level_wh_, cached_range_m_);
        }
    }

    aap_protobuf::service::sensorsource::message::SensorStartResponseMessage response;
    response.set_status(aap_protobuf::shared::STATUS_SUCCESS);

    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then(
        [this, self = shared_from_this()]() {
            // After sensor start response sent, send initial sensor data
            this->sendDrivingStatus(false);
            this->sendNightMode(false);
        },
        [this, self = shared_from_this()](auto e) { this->onChannelError(e); });
    channel_->sendSensorStartResponse(response, std::move(promise));
    channel_->receive(shared_from_this());

}

void HeadlessSensorHandler::onChannelError(const aasdk::error::Error& e) {
    output_.emit(R"({"type":"event","event_type":"sensor_error","error":")" +
                 std::string(e.what()) + R"("})");
}

// ============================================================================
// HeadlessInputHandler
// ============================================================================

HeadlessInputHandler::HeadlessInputHandler(
    boost::asio::io_service& io_service,
    aasdk::messenger::IMessenger::Pointer messenger,
    ThreadSafeOutputSink& output,
    int display_width, int display_height)
    : strand_(io_service)
    , channel_(std::make_shared<aasdk::channel::inputsource::InputSourceService>(
          strand_, std::move(messenger)))
    , output_(output)
    , display_width_(display_width), display_height_(display_height)
{
}

void HeadlessInputHandler::start() {
    strand_.dispatch([this, self = shared_from_this()]() {
        channel_->receive(self);
    });
}

void HeadlessInputHandler::stop() {}

// fillFeatures removed (SDR built inline)

void HeadlessInputHandler::sendTouchEvent(uint32_t action, uint32_t x, uint32_t y) {
    aap_protobuf::service::inputsource::message::InputReport indication;

    // timestamp is a required field — phone may ignore events without it
    auto now = std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
    indication.set_timestamp(static_cast<uint64_t>(now));

    auto* touch_event = indication.mutable_touch_event();

    // action: 0=DOWN, 1=UP, 2=MOVE (matches PointerAction enum)
    touch_event->set_action(static_cast<aap_protobuf::service::inputsource::message::PointerAction>(action));
    touch_event->set_action_index(0);

    auto* location = touch_event->add_pointer_data();
    location->set_x(x);
    location->set_y(y);
    location->set_pointer_id(0);

    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then(
        [action, x, y]() {
            std::cerr << "[aasdk] touch SENT OK: action=" << action << " x=" << x << " y=" << y << std::endl;
        },
        [action, x, y](const aasdk::error::Error& e) {
            std::cerr << "[aasdk] touch SEND FAILED: action=" << action << " x=" << x << " y=" << y
                      << " error=" << e.what() << std::endl;
        });
    channel_->sendInputReport(indication, std::move(promise));
}

void HeadlessInputHandler::sendMultiTouchEvent(uint32_t action, uint32_t action_index,
                                                const std::vector<PointerInfo>& pointers) {
    aap_protobuf::service::inputsource::message::InputReport indication;

    auto now = std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
    indication.set_timestamp(static_cast<uint64_t>(now));

    auto* touch_event = indication.mutable_touch_event();
    touch_event->set_action(static_cast<aap_protobuf::service::inputsource::message::PointerAction>(action));
    touch_event->set_action_index(action_index);

    for (const auto& p : pointers) {
        auto* loc = touch_event->add_pointer_data();
        loc->set_x(p.x);
        loc->set_y(p.y);
        loc->set_pointer_id(p.id);
    }

    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then(
        [action, n = pointers.size()]() {
            std::cerr << "[aasdk] multi-touch SENT OK: action=" << action << " ptrs=" << n << std::endl;
        },
        [action](const aasdk::error::Error& e) {
            std::cerr << "[aasdk] multi-touch SEND FAILED: action=" << action
                      << " error=" << e.what() << std::endl;
        });
    channel_->sendInputReport(indication, std::move(promise));
}

void HeadlessInputHandler::sendKeyEvent(uint32_t keycode, bool down,
                                         uint32_t metastate, bool longpress) {
    aap_protobuf::service::inputsource::message::InputReport indication;

    auto now = std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
    indication.set_timestamp(static_cast<uint64_t>(now));

    auto* key_event = indication.mutable_key_event();
    auto* key = key_event->add_keys();
    key->set_keycode(keycode);
    key->set_down(down);
    key->set_metastate(metastate);
    key->set_longpress(longpress);

    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then(
        [keycode, down]() {
            std::cerr << "[aasdk] key SENT OK: keycode=" << keycode << " down=" << down << std::endl;
        },
        [keycode, down](const aasdk::error::Error& e) {
            std::cerr << "[aasdk] key SEND FAILED: keycode=" << keycode << " down=" << down
                      << " error=" << e.what() << std::endl;
        });
    channel_->sendInputReport(indication, std::move(promise));
}

void HeadlessInputHandler::onChannelOpenRequest(
    const aap_protobuf::service::control::message::ChannelOpenRequest&)
{
    std::cerr << "[aasdk] INPUT CHANNEL OPEN REQUEST" << std::endl;
    aap_protobuf::service::control::message::ChannelOpenResponse response;
    response.set_status(aap_protobuf::shared::STATUS_SUCCESS);

    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {},
        [this, self = shared_from_this()](auto e) { this->onChannelError(e); });
    channel_->sendChannelOpenResponse(response, std::move(promise));
    channel_->receive(shared_from_this());
}

void HeadlessInputHandler::onKeyBindingRequest(
    const aap_protobuf::service::media::sink::message::KeyBindingRequest&)
{
    output_.emit(json_event("input_binding_request"));

    aap_protobuf::service::media::sink::message::KeyBindingResponse response;
    response.set_status(0);

    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {},
        [this, self = shared_from_this()](auto e) { this->onChannelError(e); });
    channel_->sendKeyBindingResponse(response, std::move(promise));
    channel_->receive(shared_from_this());
}

void HeadlessInputHandler::onChannelError(const aasdk::error::Error& e) {
    output_.emit(R"({"type":"event","event_type":"input_error","error":")" +
                 std::string(e.what()) + R"("})");
}

// ============================================================================
// HeadlessBluetoothHandler
// ============================================================================

HeadlessBluetoothHandler::HeadlessBluetoothHandler(
    boost::asio::io_service& io_service,
    aasdk::messenger::IMessenger::Pointer messenger,
    ThreadSafeOutputSink& output)
    : strand_(io_service)
    , channel_(std::make_shared<aasdk::channel::bluetooth::BluetoothService>(
          strand_, std::move(messenger)))
    , output_(output)
{
}

void HeadlessBluetoothHandler::start() {
    strand_.dispatch([this, self = shared_from_this()]() {
        channel_->receive(self);
    });
}

void HeadlessBluetoothHandler::stop() {}

// fillFeatures removed (SDR built inline)

void HeadlessBluetoothHandler::onChannelOpenRequest(
    const aap_protobuf::service::control::message::ChannelOpenRequest&)
{
    std::cerr << "[aasdk] BLUETOOTH CHANNEL OPEN REQUEST" << std::endl;
    aap_protobuf::service::control::message::ChannelOpenResponse response;
    response.set_status(aap_protobuf::shared::STATUS_SUCCESS);

    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {},
        [this, self = shared_from_this()](auto e) { this->onChannelError(e); });
    channel_->sendChannelOpenResponse(response, std::move(promise));
    channel_->receive(shared_from_this());
}

void HeadlessBluetoothHandler::onBluetoothPairingRequest(
    const aap_protobuf::service::bluetooth::message::BluetoothPairingRequest& request)
{
    std::string phone_addr = request.phone_address();
    std::cerr << "[aasdk] BT: pairing request from " << phone_addr
              << " method=" << request.pairing_method() << std::endl;
    output_.emit(R"({"type":"event","event_type":"bt_pairing_request","phone_address":")" +
                 phone_addr + R"("})");

    // Always respond with already_paired=true + SUCCESS.
    // This prevents the phone from trying AA-protocol PIN pairing
    // which conflicts with modern SSP (Secure Simple Pairing).
    // Actual BT pairing happens separately via BlueZ JustWorks.
    aap_protobuf::service::bluetooth::message::BluetoothPairingResponse response;
    response.set_already_paired(true);
    response.set_status(aap_protobuf::shared::STATUS_SUCCESS);

    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() { std::cerr << "[aasdk] BT: pairing response sent" << std::endl; },
        [this, self = shared_from_this()](auto e) { this->onChannelError(e); });
    channel_->sendBluetoothPairingResponse(response, std::move(promise));
    channel_->receive(shared_from_this());

    // Trust + connect via BlueZ (one-shot, JustWorks auto-accepts)
    if (!bt_connect_attempted_ && !phone_addr.empty()) {
        bt_connect_attempted_ = true;
        std::thread([phone_addr]() {
            std::cerr << "[aasdk] BT: trust+pair+connect " << phone_addr << std::endl;
            std::string cmd = "echo -e 'trust " + phone_addr +
                              "\\npair " + phone_addr +
                              "\\nconnect " + phone_addr +
                              "\\nquit' | bluetoothctl > /dev/null 2>&1";
            (void)system(cmd.c_str());
        }).detach();
    }
}

void HeadlessBluetoothHandler::onChannelError(const aasdk::error::Error& e) {
    output_.emit(R"({"type":"event","event_type":"bt_error","error":")" +
                 std::string(e.what()) + R"("})");
}

std::unique_ptr<IAndroidAutoSession> create_live_session(
    OutputSink sink, std::string phone_name, HeadlessConfig config)
{
    return std::make_unique<LiveAasdkSession>(
        std::move(sink), std::move(phone_name), std::move(config));
}

void HeadlessAutoEntity::onVoiceSessionRequest(
    const aap_protobuf::service::control::message::VoiceSessionNotification& request) {
    bool started = request.has_status() &&
        request.status() == aap_protobuf::service::control::message::VOICE_SESSION_START;
    std::cerr << "[aasdk] voice session: " << (started ? "start" : "end") << std::endl;
    if (oal_session_) {
        oal_session_->send_voice_session(started);
    }
    control_channel_->receive(shared_from_this());
}

void HeadlessAutoEntity::onBatteryStatusNotification(
    const aap_protobuf::service::control::message::BatteryStatusNotification& notification) {
    int level = notification.battery_level();
    int time_remaining = notification.has_time_remaining_s() ? notification.time_remaining_s() : 0;
    bool critical = notification.has_critical_battery() && notification.critical_battery();
    std::cerr << "[aasdk] battery: level=" << level << " remaining=" << time_remaining
              << "s critical=" << critical << std::endl;
    if (oal_session_) {
        oal_session_->send_phone_battery(level, time_remaining, critical);
    }
    control_channel_->receive(shared_from_this());
}
void HeadlessBluetoothHandler::onBluetoothAuthenticationResult(
    const aap_protobuf::service::bluetooth::message::BluetoothAuthenticationResult& result) {
    std::cerr << "[aasdk] BT auth result" << std::endl;
    channel_->receive(shared_from_this());
}

// ============================================================================
// HeadlessNavStatusHandler
// ============================================================================

HeadlessNavStatusHandler::HeadlessNavStatusHandler(
    boost::asio::io_service& io_service,
    aasdk::messenger::IMessenger::Pointer messenger,
    ThreadSafeOutputSink& output)
    : strand_(io_service)
    , channel_(std::make_shared<aasdk::channel::navigationstatus::NavigationStatusService>(strand_, std::move(messenger)))
    , output_(output)
{
}

void HeadlessNavStatusHandler::start() {
    strand_.dispatch([this, self = shared_from_this()]() {
        channel_->receive(self);
    });
}

void HeadlessNavStatusHandler::stop() {}

void HeadlessNavStatusHandler::onChannelOpenRequest(
    const aap_protobuf::service::control::message::ChannelOpenRequest&)
{
    std::cerr << "[aasdk] NAV STATUS CHANNEL OPEN REQUEST" << std::endl;
    aap_protobuf::service::control::message::ChannelOpenResponse response;
    response.set_status(aap_protobuf::shared::STATUS_SUCCESS);

    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {},
        [this, self = shared_from_this()](auto e) { this->onChannelError(e); });
    channel_->sendChannelOpenResponse(response, std::move(promise));
    channel_->receive(shared_from_this());
}

void HeadlessNavStatusHandler::onStatusUpdate(
    const aap_protobuf::service::navigationstatus::message::NavigationStatus& navStatus)
{
    auto status = navStatus.status();
    const char* status_str = "unknown";
    switch (status) {
        case 0: status_str = "unavailable"; break;
        case 1: status_str = "active"; break;
        case 2: status_str = "inactive"; break;
        case 3: status_str = "rerouting"; break;
    }
    std::cerr << "[aasdk] nav status: " << status_str << std::endl;
    output_.emit(R"({"type":"event","event_type":"nav_status","status":")" +
                 std::string(status_str) + R"("})");

    // When navigation becomes inactive/unavailable, clear state and notify app
    if (status != 1) {
        last_maneuver_.clear();
        last_road_.clear();
        last_nav_image_base64_.clear();
        last_distance_m_ = 0;
        last_eta_s_ = 0;
        has_modern_nav_ = false;
        last_destination_.clear();
        last_dest_distance_m_ = 0;
        last_dest_display_value_.clear();
        last_dest_display_unit_.clear();
        if (oal_session_) {
            oal_session_->send_nav_state_clear();
        }
    }

    channel_->receive(shared_from_this());
}

void HeadlessNavStatusHandler::onTurnEvent(
    const aap_protobuf::service::navigationstatus::message::NavigationNextTurnEvent& turnEvent)
{
    last_road_ = turnEvent.road();

    // Extract maneuver icon PNG (available when SDR uses IMAGE mode)
    // Always extract the image even in modern mode — it's used by onNavigationState
    if (turnEvent.has_image() && !turnEvent.image().empty()) {
        const auto& img = turnEvent.image();
        last_nav_image_base64_ = base64_encode(
            reinterpret_cast<const uint8_t*>(img.data()), img.size());
        std::cerr << "[aasdk] nav icon: " << img.size() << " bytes" << std::endl;
    }

    // If modern nav is active, don't send legacy turn events — the image is
    // cached and will be included in the next modern nav_state
    if (has_modern_nav_) {
        channel_->receive(shared_from_this());
        return;
    }

    // Map turn event+side to maneuver string
    std::string maneuver = "unknown";
    if (turnEvent.has_event()) {
        auto ev = turnEvent.event();
        std::string side = "";
        if (turnEvent.has_turn_side()) {
            switch (turnEvent.turn_side()) {
                case 1: side = "_left"; break;
                case 2: side = "_right"; break;
                default: break;
            }
        }
        switch (ev) {
            case 0: maneuver = "unknown"; break;
            case 1: maneuver = "depart"; break;
            case 2: maneuver = "name_change"; break;
            case 3: maneuver = "slight_turn" + side; break;
            case 4: maneuver = "turn" + side; break;
            case 5: maneuver = "sharp_turn" + side; break;
            case 6: maneuver = "u_turn" + side; break;
            case 7: maneuver = "on_ramp" + side; break;
            case 8: maneuver = "off_ramp" + side; break;
            case 9: maneuver = "fork" + side; break;
            case 10: maneuver = "merge" + side; break;
            case 11: maneuver = "roundabout_enter"; break;
            case 12: maneuver = "roundabout_exit"; break;
            case 13: maneuver = "roundabout_enter_and_exit"; break;
            case 14: maneuver = "straight"; break;
            case 19: maneuver = "destination"; break;
            default: maneuver = "turn_" + std::to_string(ev) + side; break;
        }
    }
    last_maneuver_ = maneuver;

    std::cerr << "[aasdk] nav turn: " << maneuver << " road=" << last_road_ << std::endl;

    // Send combined nav_state to app (include image on turn events)
    if (oal_session_) {
        oal_session_->send_nav_state(last_maneuver_, last_distance_m_,
                                     last_road_, last_eta_s_,
                                     last_nav_image_base64_);
    }

    channel_->receive(shared_from_this());
}

void HeadlessNavStatusHandler::onDistanceEvent(
    const aap_protobuf::service::navigationstatus::message::NavigationNextTurnDistanceEvent& distanceEvent)
{
    // If modern nav is active, legacy distance events are redundant — skip
    if (has_modern_nav_) {
        channel_->receive(shared_from_this());
        return;
    }

    last_distance_m_ = distanceEvent.distance_meters();
    last_eta_s_ = distanceEvent.time_to_turn_seconds();

    std::cerr << "[aasdk] nav distance: " << last_distance_m_ << "m, "
              << last_eta_s_ << "s" << std::endl;

    // Distance-only updates omit the image to save bandwidth
    if (oal_session_) {
        oal_session_->send_nav_state(last_maneuver_, last_distance_m_,
                                     last_road_, last_eta_s_);
    }

    channel_->receive(shared_from_this());
}

// Map NavigationManeuver.NavigationType enum to wire string
static std::string map_modern_maneuver_type(int type) {
    switch (type) {
        case 1: return "depart";
        case 2: return "name_change";
        case 3: return "keep_left";
        case 4: return "keep_right";
        case 5: return "turn_slight_left";
        case 6: return "turn_slight_right";
        case 7: return "turn_left";
        case 8: return "turn_right";
        case 9: return "turn_sharp_left";
        case 10: return "turn_sharp_right";
        case 11: return "u_turn_left";
        case 12: return "u_turn_right";
        case 13: return "on_ramp_slight_left";
        case 14: return "on_ramp_slight_right";
        case 15: return "on_ramp_left";
        case 16: return "on_ramp_right";
        case 17: return "on_ramp_sharp_left";
        case 18: return "on_ramp_sharp_right";
        case 19: return "on_ramp_u_turn_left";
        case 20: return "on_ramp_u_turn_right";
        case 21: return "off_ramp_slight_left";
        case 22: return "off_ramp_slight_right";
        case 23: return "off_ramp_left";
        case 24: return "off_ramp_right";
        case 25: return "fork_left";
        case 26: return "fork_right";
        case 27: return "merge_left";
        case 28: return "merge_right";
        case 29: return "merge_unspecified";
        case 30: return "roundabout_enter";
        case 31: return "roundabout_exit";
        case 32: return "roundabout_enter_and_exit_cw";
        case 33: return "roundabout_enter_and_exit_cw_with_angle";
        case 34: return "roundabout_enter_and_exit_ccw";
        case 35: return "roundabout_enter_and_exit_ccw_with_angle";
        case 36: return "straight";
        case 37: return "ferry_boat";
        case 38: return "ferry_train";
        case 39: return "destination";
        case 40: return "destination_straight";
        case 41: return "destination_left";
        case 42: return "destination_right";
        default: return "unknown";
    }
}

// Map NavigationLane.LaneDirection.Shape enum to wire string
static std::string map_lane_shape(int shape) {
    switch (shape) {
        case 0: return "unknown";
        case 1: return "straight";
        case 2: return "normal_left";
        case 3: return "normal_right";
        case 4: return "slight_left";
        case 5: return "slight_right";
        case 6: return "sharp_left";
        case 7: return "sharp_right";
        case 8: return "u_turn_left";
        case 9: return "u_turn_right";
        default: return "unknown";
    }
}

// Map DistanceUnits enum to wire string
static std::string map_distance_unit(int unit) {
    switch (unit) {
        // AA navigation DistanceUnits starts at 1; 0 is UNKNOWN_DISTANCE_UNIT.
        case 1: return "meters";
        case 2: return "kilometers";
        case 3: return "kilometers_p1";
        case 4: return "miles";
        case 5: return "miles_p1";
        case 6: return "feet";
        case 7: return "yards";
        default: return "unknown";
    }
}

void HeadlessNavStatusHandler::onNavigationState(
    const aap_protobuf::service::navigationstatus::message::NavigationState& navState)
{
    has_modern_nav_ = true;

    if (navState.steps_size() == 0) {
        std::cerr << "[aasdk] modern nav state: no steps — clearing navigation" << std::endl;
        last_maneuver_.clear();
        last_road_.clear();
        last_nav_image_base64_.clear();
        last_distance_m_ = 0;
        last_eta_s_ = 0;
        has_modern_nav_ = false;
        last_destination_.clear();
        last_dest_distance_m_ = 0;
        last_dest_display_value_.clear();
        last_dest_display_unit_.clear();
        if (oal_session_) {
            oal_session_->send_nav_state_clear();
        }
        channel_->receive(shared_from_this());
        return;
    }

    // Build enriched nav_state JSON with all modern data
    std::ostringstream oss;
    oss << R"({"type":"nav_state")";

    // First step is the current/next maneuver
    const auto& step0 = navState.steps(0);

    // Maneuver
    if (step0.has_maneuver()) {
        const auto& m = step0.maneuver();
        std::string mtype = m.has_type() ? map_modern_maneuver_type(m.type()) : "unknown";
        last_maneuver_ = mtype;
        oss << R"(,"maneuver":")" << mtype << R"(")";

        if (m.has_roundabout_exit_number()) {
            oss << R"(,"roundabout_exit_number":)" << m.roundabout_exit_number();
        }
        if (m.has_roundabout_exit_angle()) {
            oss << R"(,"roundabout_exit_angle":)" << m.roundabout_exit_angle();
        }
    }

    // Road name
    if (step0.has_road() && step0.road().has_name()) {
        last_road_ = step0.road().name();
        oss << R"(,"road":")" << oal_json_escape(step0.road().name()) << R"(")";
    }

    // Cue text (turn instruction like "Turn right onto Main St")
    if (step0.has_cue() && step0.cue().alternate_text_size() > 0) {
        oss << R"(,"cue":")" << oal_json_escape(step0.cue().alternate_text(0)) << R"(")";
    }

    // Lane guidance
    if (step0.lanes_size() > 0) {
        oss << R"(,"lanes":[)";
        for (int i = 0; i < step0.lanes_size(); ++i) {
            if (i > 0) oss << ",";
            const auto& lane = step0.lanes(i);
            oss << R"({"directions":[)";
            for (int j = 0; j < lane.lane_directions_size(); ++j) {
                if (j > 0) oss << ",";
                const auto& ld = lane.lane_directions(j);
                oss << R"({"shape":")" << map_lane_shape(ld.has_shape() ? ld.shape() : 0) << R"(")";
                oss << R"(,"highlighted":)" << (ld.has_is_highlighted() && ld.is_highlighted() ? "true" : "false");
                oss << "}";
            }
            oss << "]}";
        }
        oss << "]";
    }

    // Carry forward cached image from legacy turn events
    if (!last_nav_image_base64_.empty()) {
        oss << R"(,"nav_image_base64":")" << last_nav_image_base64_ << R"(")";
    }

    // Carry forward cached distance (updated by onCurrentPosition)
    oss << R"(,"distance_meters":)" << last_distance_m_;
    oss << R"(,"eta_seconds":)" << last_eta_s_;

    // Destinations
    if (navState.destinations_size() > 0) {
        const auto& dest = navState.destinations(0);
        if (dest.has_address()) {
            last_destination_ = dest.address();
            oss << R"(,"destination":")" << oal_json_escape(dest.address()) << R"(")";
        }
    }

    oss << "}";

    std::cerr << "[aasdk] modern nav state: " << step0.lanes_size()
              << " lanes, maneuver=" << last_maneuver_ << std::endl;

    if (oal_session_) {
        oal_session_->send_nav_state_modern(oss.str());
    }

    channel_->receive(shared_from_this());
}

void HeadlessNavStatusHandler::onCurrentPosition(
    const aap_protobuf::service::navigationstatus::message::NavigationCurrentPosition& position)
{
    // Update cached distance values from modern position data
    if (position.has_step_distance()) {
        const auto& sd = position.step_distance();
        if (sd.has_distance() && sd.distance().has_meters()) {
            last_distance_m_ = sd.distance().meters();
        }
        if (sd.has_time_to_step_seconds()) {
            last_eta_s_ = static_cast<int>(sd.time_to_step_seconds());
        }
    }

    // Build nav_state update with position data
    std::ostringstream oss;
    oss << R"({"type":"nav_state")";

    // Include cached maneuver + road
    if (!last_maneuver_.empty()) {
        oss << R"(,"maneuver":")" << last_maneuver_ << R"(")";
    }
    if (!last_road_.empty()) {
        oss << R"(,"road":")" << oal_json_escape(last_road_) << R"(")";
    }

    oss << R"(,"distance_meters":)" << last_distance_m_;
    oss << R"(,"eta_seconds":)" << last_eta_s_;

    // Pre-formatted display distance (e.g. "0.3 mi")
    if (position.has_step_distance() && position.step_distance().has_distance()) {
        const auto& dist = position.step_distance().distance();
        if (dist.has_display_value()) {
            oss << R"(,"display_distance":")" << oal_json_escape(dist.display_value()) << R"(")";
        }
        if (dist.has_display_units()) {
            oss << R"(,"display_distance_unit":")" << map_distance_unit(dist.display_units()) << R"(")";
        }
    }

    // Current road (the road you're on, vs. the road you're turning onto)
    if (position.has_current_road() && position.current_road().has_name()) {
        oss << R"(,"current_road":")" << oal_json_escape(position.current_road().name()) << R"(")";
    }

    // Carry forward cached destination address
    if (!last_destination_.empty()) {
        oss << R"(,"destination":")" << oal_json_escape(last_destination_) << R"(")";
    }

    // Destination distance + ETA
    if (position.destination_distances_size() > 0) {
        const auto& dd = position.destination_distances(0);
        if (dd.has_estimated_time_at_arrival()) {
            oss << R"(,"eta_formatted":")" << oal_json_escape(dd.estimated_time_at_arrival()) << R"(")";
        }
        if (dd.has_time_to_arrival_seconds()) {
            oss << R"(,"time_to_arrival_seconds":)" << dd.time_to_arrival_seconds();
        }
        // Destination remaining distance
        if (dd.has_distance()) {
            const auto& dest_dist = dd.distance();
            if (dest_dist.has_meters()) {
                last_dest_distance_m_ = dest_dist.meters();
                oss << R"(,"dest_distance_meters":)" << dest_dist.meters();
            }
            if (dest_dist.has_display_value()) {
                last_dest_display_value_ = dest_dist.display_value();
                oss << R"(,"dest_distance_display":")" << oal_json_escape(dest_dist.display_value()) << R"(")";
            }
            if (dest_dist.has_display_units()) {
                last_dest_display_unit_ = map_distance_unit(dest_dist.display_units());
                oss << R"(,"dest_distance_unit":")" << last_dest_display_unit_ << R"(")";
            }
        }
    }

    oss << "}";

    std::cerr << "[aasdk] nav position: " << last_distance_m_ << "m, "
              << last_eta_s_ << "s" << std::endl;

    if (oal_session_) {
        oal_session_->send_nav_state_modern(oss.str());
    }

    channel_->receive(shared_from_this());
}

void HeadlessNavStatusHandler::onChannelError(const aasdk::error::Error& e) {
    output_.emit(R"({"type":"event","event_type":"nav_status_error","error":")" +
                 std::string(e.what()) + R"("})");
}

// ============================================================================
// HeadlessMediaStatusHandler
// ============================================================================

HeadlessMediaStatusHandler::HeadlessMediaStatusHandler(
    boost::asio::io_service& io_service,
    aasdk::messenger::IMessenger::Pointer messenger,
    ThreadSafeOutputSink& output)
    : strand_(io_service)
    , channel_(std::make_shared<aasdk::channel::mediaplaybackstatus::MediaPlaybackStatusService>(strand_, std::move(messenger)))
    , output_(output)
{
}

void HeadlessMediaStatusHandler::start() {
    strand_.dispatch([this, self = shared_from_this()]() {
        channel_->receive(self);
    });
}

void HeadlessMediaStatusHandler::stop() {}

void HeadlessMediaStatusHandler::onChannelOpenRequest(
    const aap_protobuf::service::control::message::ChannelOpenRequest&)
{
    std::cerr << "[aasdk] MEDIA STATUS CHANNEL OPEN REQUEST" << std::endl;
    aap_protobuf::service::control::message::ChannelOpenResponse response;
    response.set_status(aap_protobuf::shared::STATUS_SUCCESS);

    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {},
        [this, self = shared_from_this()](auto e) { this->onChannelError(e); });
    channel_->sendChannelOpenResponse(response, std::move(promise));
    channel_->receive(shared_from_this());
}

void HeadlessMediaStatusHandler::onMetadataUpdate(
    const aap_protobuf::service::mediaplayback::message::MediaPlaybackMetadata& metadata)
{
    last_title_ = metadata.has_song() ? metadata.song() : "";
    last_artist_ = metadata.has_artist() ? metadata.artist() : "";
    last_album_ = metadata.has_album() ? metadata.album() : "";
    if (metadata.has_duration_seconds())
        last_duration_ms_ = metadata.duration_seconds() * 1000;

    // Extract album art if present (PNG/JPEG bytes from the phone)
    if (metadata.has_album_art() && !metadata.album_art().empty()) {
        const auto& art = metadata.album_art();
        last_album_art_base64_ = base64_encode(
            reinterpret_cast<const uint8_t*>(art.data()), art.size());
        std::cerr << "[aasdk] media album art: " << art.size() << " bytes" << std::endl;
    }

    std::cerr << "[aasdk] media metadata: \"" << last_title_
              << "\" by \"" << last_artist_
              << "\" (" << last_album_ << ")" << std::endl;

    if (oal_session_) {
        oal_session_->send_media_metadata(last_title_, last_artist_, last_album_,
                                          last_duration_ms_, last_position_ms_,
                                          last_playing_, last_album_art_base64_);
    }

    channel_->receive(shared_from_this());
}

void HeadlessMediaStatusHandler::onPlaybackUpdate(
    const aap_protobuf::service::mediaplayback::message::MediaPlaybackStatus& playback)
{
    // state: STOPPED=1, PLAYING=2, PAUSED=3
    last_playing_ = (playback.state() == aap_protobuf::service::mediaplayback::message::MediaPlaybackStatus::PLAYING);
    if (playback.has_playback_seconds())
        last_position_ms_ = static_cast<int>(playback.playback_seconds()) * 1000;

    std::cerr << "[aasdk] media playback: " << (last_playing_ ? "playing" : "paused")
              << " pos=" << last_position_ms_ << "ms" << std::endl;

    if (oal_session_) {
        // Don't resend album art on playback-only updates (position/state changes)
        oal_session_->send_media_metadata(last_title_, last_artist_, last_album_,
                                          last_duration_ms_, last_position_ms_,
                                          last_playing_);
    }

    channel_->receive(shared_from_this());
}

void HeadlessMediaStatusHandler::onChannelError(const aasdk::error::Error& e) {
    output_.emit(R"({"type":"event","event_type":"media_status_error","error":")" +
                 std::string(e.what()) + R"("})");
}

// ============================================================================
// HeadlessPhoneStatusHandler
// ============================================================================

HeadlessPhoneStatusHandler::HeadlessPhoneStatusHandler(
    boost::asio::io_service& io_service,
    aasdk::messenger::IMessenger::Pointer messenger,
    ThreadSafeOutputSink& output)
    : strand_(io_service)
    , channel_(std::make_shared<aasdk::channel::phonestatus::PhoneStatusService>(strand_, std::move(messenger)))
    , output_(output)
{
}

void HeadlessPhoneStatusHandler::start() {
    strand_.dispatch([this, self = shared_from_this()]() {
        channel_->receive(self);
    });
}

void HeadlessPhoneStatusHandler::stop() {
}

void HeadlessPhoneStatusHandler::onChannelOpenRequest(
    const aap_protobuf::service::control::message::ChannelOpenRequest& request) {
    std::cerr << "[aasdk] PhoneStatus channel open request" << std::endl;

    aap_protobuf::service::control::message::ChannelOpenResponse response;
    response.set_status(aap_protobuf::shared::STATUS_SUCCESS);
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [](auto) {});
    channel_->sendChannelOpenResponse(response, std::move(promise));
    channel_->receive(shared_from_this());
}

void HeadlessPhoneStatusHandler::onPhoneStatusUpdate(
    const aap_protobuf::service::phonestatus::message::PhoneStatus& status) {
    int signal = status.has_signal_strength() ? status.signal_strength() : -1;
    std::cerr << "[aasdk] phone status: signal=" << signal
              << " calls=" << status.calls_size() << std::endl;

    // Build calls JSON array
    std::ostringstream calls_oss;
    calls_oss << "[";
    for (int i = 0; i < status.calls_size(); ++i) {
        auto& call = status.calls(i);
        if (i > 0) calls_oss << ",";
        calls_oss << R"({"state":)";
        switch (call.phone_state()) {
            case aap_protobuf::service::phonestatus::message::PhoneStatus::IN_CALL:
                calls_oss << R"("in_call")";
                break;
            default:
                calls_oss << R"("unknown")";
                break;
        }
        calls_oss << R"(,"duration_s":)" << call.call_duration_seconds();
        if (call.has_caller_number()) {
            calls_oss << R"(,"caller_number":")" << oal_json_escape(call.caller_number()) << R"(")";
        }
        if (call.has_caller_id()) {
            calls_oss << R"(,"caller_id":")" << oal_json_escape(call.caller_id()) << R"(")";
        }
        calls_oss << "}";
    }
    calls_oss << "]";

    if (oal_session_) {
        oal_session_->send_phone_status(signal, calls_oss.str());
    }

    channel_->receive(shared_from_this());
}

void HeadlessPhoneStatusHandler::onChannelError(const aasdk::error::Error& e) {
    std::cerr << "[aasdk] PhoneStatus channel error: " << e.what() << std::endl;
}

} // namespace openautolink

#endif // PI_AA_ENABLE_AASDK_LIVE
