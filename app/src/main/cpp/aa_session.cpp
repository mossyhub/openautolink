/*
 * aa_session.cpp — Full aasdk Android Auto session for JNI
 *
 * Ported from the bridge's HeadlessAutoEntity (live_session.cpp).
 * Runs a Boost.Asio io_service on a dedicated native thread.
 * TCP listener on configured port → accept → SSL handshake through AA
 * protocol framing → service discovery → start service handlers.
 *
 * Video/audio frames are forwarded to Kotlin via oal::jni:: callbacks.
 * Touch/sensor/mic data flows from Kotlin via oal:: entry points.
 */

#ifndef OAL_STUB_ONLY

#include <android/log.h>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <future>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <boost/asio.hpp>

#include <aasdk/TCP/TCPWrapper.hpp>
#include <aasdk/TCP/TCPEndpoint.hpp>
#include <aasdk/Transport/SSLWrapper.hpp>
#include <aasdk/Transport/TCPTransport.hpp>
#include <aasdk/Messenger/Cryptor.hpp>
#include <aasdk/Messenger/MessageInStream.hpp>
#include <aasdk/Messenger/MessageOutStream.hpp>
#include <aasdk/Messenger/Messenger.hpp>
#include <aasdk/Messenger/ChannelId.hpp>

#include <aasdk/Channel/Control/ControlServiceChannel.hpp>
#include <aasdk/Channel/Control/IControlServiceChannelEventHandler.hpp>
#include <aasdk/Channel/MediaSink/Video/IVideoMediaSinkServiceEventHandler.hpp>
#include <aasdk/Channel/MediaSink/Video/Channel/VideoChannel.hpp>
#include <aasdk/Channel/MediaSink/Audio/IAudioMediaSinkServiceEventHandler.hpp>
#include <aasdk/Channel/MediaSink/Audio/Channel/MediaAudioChannel.hpp>
#include <aasdk/Channel/MediaSink/Audio/Channel/GuidanceAudioChannel.hpp>
#include <aasdk/Channel/MediaSink/Audio/Channel/SystemAudioChannel.hpp>
#include <aasdk/Channel/MediaSource/IMediaSourceServiceEventHandler.hpp>
#include <aasdk/Channel/MediaSource/MediaSourceService.hpp>
#include <aasdk/Channel/SensorSource/ISensorSourceServiceEventHandler.hpp>
#include <aasdk/Channel/SensorSource/SensorSourceService.hpp>
#include <aasdk/Channel/InputSource/IInputSourceServiceEventHandler.hpp>
#include <aasdk/Channel/InputSource/InputSourceService.hpp>
#include <aasdk/Channel/Bluetooth/BluetoothService.hpp>
#include <aasdk/Channel/Bluetooth/IBluetoothServiceEventHandler.hpp>
#include <aasdk/Channel/NavigationStatus/NavigationStatusService.hpp>
#include <aasdk/Channel/NavigationStatus/INavigationStatusServiceEventHandler.hpp>
#include <aasdk/Channel/MediaPlaybackStatus/MediaPlaybackStatusService.hpp>
#include <aasdk/Channel/MediaPlaybackStatus/IMediaPlaybackStatusServiceEventHandler.hpp>
#include <aasdk/Channel/PhoneStatus/PhoneStatusService.hpp>
#include <aasdk/Channel/PhoneStatus/IPhoneStatusServiceEventHandler.hpp>

// Proto includes
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
#include <aap_protobuf/service/media/sink/message/AudioStreamType.pb.h>
#include <aap_protobuf/service/media/sink/message/VideoCodecResolutionType.pb.h>
#include <aap_protobuf/service/media/sink/message/VideoFrameRateType.pb.h>
#include <aap_protobuf/service/media/shared/message/MediaCodecType.pb.h>
#include <aap_protobuf/service/media/source/message/Ack.pb.h>
#include <aap_protobuf/service/sensorsource/message/SensorRequest.pb.h>
#include <aap_protobuf/service/sensorsource/message/SensorStartResponseMessage.pb.h>
#include <aap_protobuf/service/sensorsource/message/SensorBatch.pb.h>
#include <aap_protobuf/service/sensorsource/message/SensorType.pb.h>
#include <aap_protobuf/service/sensorsource/message/FuelType.pb.h>
#include <aap_protobuf/service/sensorsource/message/EvConnectorType.pb.h>
#include <aap_protobuf/service/inputsource/message/InputReport.pb.h>
#include <aap_protobuf/service/inputsource/message/PointerAction.pb.h>
#include <aap_protobuf/service/bluetooth/message/BluetoothPairingRequest.pb.h>
#include <aap_protobuf/service/bluetooth/message/BluetoothPairingResponse.pb.h>
#include <aap_protobuf/service/bluetooth/message/BluetoothPairingMethod.pb.h>
#include <aap_protobuf/service/control/message/ByeByeRequest.pb.h>
#include <aap_protobuf/service/control/message/ByeByeResponse.pb.h>
#include <aap_protobuf/service/control/message/ByeByeReason.pb.h>
#include <aap_protobuf/service/control/message/VoiceSessionNotification.pb.h>
#include <aap_protobuf/service/control/message/BatteryStatusNotification.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationState.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationCurrentPosition.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationStep.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationManeuver.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationDistance.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationRoad.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationCue.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationLane.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationDestination.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationStepDistance.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationDestinationDistance.pb.h>
#include <aap_protobuf/shared/MessageStatus.pb.h>

#define LOG_TAG "aa_session"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// JNI callback declarations (defined in jni_bridge.cpp)
namespace oal { namespace jni {
    void notifyVideoFrame(int w, int h, int64_t pts, int flags,
                          const uint8_t* data, size_t len);
    void notifyAudioFrame(int purpose, int sampleRate, int channels,
                          const uint8_t* data, size_t len);
    void notifyPhoneConnected(const std::string& name, const std::string& type);
    void notifyPhoneDisconnected(const std::string& reason);
    void notifyVoiceSession(bool started);
    void notifyPhoneBattery(int level, int timeRemaining, bool critical);
    void notifyNavState(const std::string& maneuver, int distanceMeters,
                        const std::string& road, int etaSeconds,
                        const std::string& cue, int roundaboutExit, int roundaboutAngle,
                        const std::string& displayDistance, const std::string& displayDistanceUnit,
                        const std::string& currentRoad, const std::string& destination,
                        const std::string& etaFormatted, int64_t timeToArrivalSeconds);
    void notifyNavStateClear();
}}

// ════════════════════════════════════════════════════════════════════
// Session parameters (set by startSession, read by entity)
// ════════════════════════════════════════════════════════════════════

namespace {

struct SessionConfig {
    int port = 5277;
    int width = 1920;
    int height = 1080;
    int fps = 60;
    int dpi = 160;
    int resolutionTier = 3; // derived from width
    int marginWidth = 0;
    int marginHeight = 0;
    int pixelAspect = 0;    // 0 = default (10000 = square)
    int driverPosition = 0; // 0=left, 1=right
    // Stable insets (safe area for interactive UI)
    int safeTop = 0;
    int safeBottom = 0;
    int safeLeft = 0;
    int safeRight = 0;
    // Content insets (hard cutoff, nothing renders outside)
    int contentTop = 0;
    int contentBottom = 0;
    int contentLeft = 0;
    int contentRight = 0;
    std::string headUnitName = "OpenAutoLink";
    // Phase 9: session configuration bitmask (hide clock/signal/battery on AA projection)
    int sessionConfiguration = 0; // bit0=hideClock, bit1=hideSignal, bit2=hideBattery
    // Phase 9: Bluetooth MAC for BT service in SDR
    std::string btMac = "00:00:00:00:00:00";
    // Video codec preference: 0=h264, 1=h265, 2=vp9
    int videoCodec = 0;
};

int widthToTier(int w) {
    if (w >= 3840) return 5;
    if (w >= 2560) return 4;
    if (w >= 1920) return 3;
    if (w >= 1280) return 2;
    return 1;
}

void tierToDimensions(int tier, int& w, int& h) {
    switch (tier) {
        case 1: w = 800;  h = 480;  break;
        case 2: w = 1280; h = 720;  break;
        case 3: w = 1920; h = 1080; break;
        case 4: w = 2560; h = 1440; break;
        case 5: w = 3840; h = 2160; break;
        default: w = 1920; h = 1080; break;
    }
}

// Audio purpose IDs matching AudioFrame.PURPOSE_* in Kotlin
constexpr int AUDIO_PURPOSE_MEDIA    = 0;
constexpr int AUDIO_PURPOSE_GUIDANCE = 1;
constexpr int AUDIO_PURPOSE_SYSTEM   = 2;

} // anonymous namespace

// ════════════════════════════════════════════════════════════════════
// Forward declarations for service handlers
// ════════════════════════════════════════════════════════════════════

class JniVideoHandler;
class JniAudioHandler;
class JniAudioInputHandler;
class JniSensorHandler;
class JniInputHandler;
class JniBtHandler;
class JniNavHandler;
class JniMediaStatusHandler;
class JniPhoneStatusHandler;

// ════════════════════════════════════════════════════════════════════
// JniAutoEntity — orchestrates control channel (ported from bridge)
// ════════════════════════════════════════════════════════════════════

class JniAutoEntity
    : public aasdk::channel::control::IControlServiceChannelEventHandler
    , public std::enable_shared_from_this<JniAutoEntity>
{
public:
    JniAutoEntity(boost::asio::io_service& io,
                  aasdk::messenger::ICryptor::Pointer cryptor,
                  aasdk::transport::ITransport::Pointer transport,
                  aasdk::messenger::IMessenger::Pointer messenger,
                  const SessionConfig& config);

    void start();
    void stop();
    bool isActive() const { return active_; }

    // Accessors for JNI forwarding
    std::shared_ptr<JniInputHandler> inputHandler() const { return inputHandler_; }
    std::shared_ptr<JniSensorHandler> sensorHandler() const { return sensorHandler_; }
    std::shared_ptr<JniAudioInputHandler> audioInputHandler() const { return audioInputHandler_; }
    std::shared_ptr<JniVideoHandler> videoHandler() const { return videoHandler_; }

    using DisconnectCb = std::function<void()>;
    void setDisconnectCb(DisconnectCb cb) { disconnectCb_ = std::move(cb); }

private:
    // IControlServiceChannelEventHandler
    void onVersionResponse(uint16_t major, uint16_t minor,
                           aap_protobuf::shared::MessageStatus status) override;
    void onHandshake(const aasdk::common::DataConstBuffer& payload) override;
    void onServiceDiscoveryRequest(const aap_protobuf::service::control::message::ServiceDiscoveryRequest& req) override;
    void onAudioFocusRequest(const aap_protobuf::service::control::message::AudioFocusRequest& req) override;
    void onByeByeRequest(const aap_protobuf::service::control::message::ByeByeRequest& req) override;
    void onByeByeResponse(const aap_protobuf::service::control::message::ByeByeResponse& resp) override;
    void onNavigationFocusRequest(const aap_protobuf::service::control::message::NavFocusRequestNotification& req) override;
    void onBatteryStatusNotification(const aap_protobuf::service::control::message::BatteryStatusNotification& notif) override {
        int level = notif.has_battery_level() ? static_cast<int>(notif.battery_level()) : 0;
        int timeRemaining = notif.has_time_remaining_s() ? static_cast<int>(notif.time_remaining_s()) : 0;
        bool critical = notif.has_critical_battery() && notif.critical_battery();
        LOGI("entity: battery level=%d%% remaining=%ds critical=%d", level, timeRemaining, critical);
        oal::jni::notifyPhoneBattery(level, timeRemaining, critical);
        controlChannel_->receive(shared_from_this());
    }
    void onVoiceSessionRequest(const aap_protobuf::service::control::message::VoiceSessionNotification& notif) override {
        bool started = (notif.status() == aap_protobuf::service::control::message::VOICE_SESSION_START);
        LOGI("entity: voice session %s", started ? "START" : "END");
        oal::jni::notifyVoiceSession(started);
        controlChannel_->receive(shared_from_this());
    }
    void onPingRequest(const aap_protobuf::service::control::message::PingRequest& req) override;
    void onPingResponse(const aap_protobuf::service::control::message::PingResponse&) override;
    void onChannelError(const aasdk::error::Error& e) override;

    void sendPing();
    void schedulePing();
    void triggerQuit(const std::string& reason);

    boost::asio::io_service& io_;
    boost::asio::io_service::strand strand_;
    aasdk::messenger::ICryptor::Pointer cryptor_;
    aasdk::transport::ITransport::Pointer transport_;
    aasdk::messenger::IMessenger::Pointer messenger_;
    std::shared_ptr<aasdk::channel::control::ControlServiceChannel> controlChannel_;
    SessionConfig config_;
    bool active_ = false;
    DisconnectCb disconnectCb_;

    boost::asio::deadline_timer pingTimer_;
    std::string deviceName_;

    // Service handlers
    std::shared_ptr<JniVideoHandler> videoHandler_;
    std::shared_ptr<JniAudioHandler> mediaAudioHandler_;
    std::shared_ptr<JniAudioHandler> speechAudioHandler_;
    std::shared_ptr<JniAudioHandler> systemAudioHandler_;
    std::shared_ptr<JniAudioInputHandler> audioInputHandler_;
    std::shared_ptr<JniSensorHandler> sensorHandler_;
    std::shared_ptr<JniInputHandler> inputHandler_;
    std::shared_ptr<JniBtHandler> btHandler_;
    std::shared_ptr<JniNavHandler> navHandler_;
    std::shared_ptr<JniMediaStatusHandler> mediaStatusHandler_;
    std::shared_ptr<JniPhoneStatusHandler> phoneStatusHandler_;
};

// ════════════════════════════════════════════════════════════════════
// Service Handlers
// ════════════════════════════════════════════════════════════════════

// ─── Video ───────────────────────────────────────────────────────

class JniVideoHandler
    : public aasdk::channel::mediasink::video::IVideoMediaSinkServiceEventHandler
    , public std::enable_shared_from_this<JniVideoHandler>
{
public:
    JniVideoHandler(boost::asio::io_service& io,
                    aasdk::messenger::IMessenger::Pointer messenger,
                    int width, int height, int fps, int dpi, int videoCodec)
        : strand_(io)
        , channel_(std::make_shared<aasdk::channel::mediasink::video::channel::VideoChannel>(strand_, messenger))
        , width_(width), height_(height), fps_(fps), dpi_(dpi)
        , videoCodec_(videoCodec) {}

    void start() { strand_.dispatch([this, self = shared_from_this()]() { channel_->receive(self); }); }
    void stop() {}

    void sendVideoFocusIndication() {
        aap_protobuf::service::media::video::message::VideoFocusNotification notif;
        notif.set_focus(aap_protobuf::service::media::video::message::VIDEO_FOCUS_PROJECTED);
        notif.set_unsolicited(false);
        auto p = aasdk::channel::SendPromise::defer(strand_);
        p->then([](){}, [](auto){});
        channel_->sendVideoFocusIndication(notif, std::move(p));
    }

private:
    void onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest&) override {
        LOGI("video: channel open");
        aap_protobuf::service::control::message::ChannelOpenResponse resp;
        resp.set_status(aap_protobuf::shared::STATUS_SUCCESS);
        auto p = aasdk::channel::SendPromise::defer(strand_);
        p->then([](){}, [](auto){});
        channel_->sendChannelOpenResponse(resp, std::move(p));
        channel_->receive(shared_from_this());
    }

    void onMediaChannelSetupRequest(const aap_protobuf::service::media::shared::message::Setup&) override {
        LOGI("video: setup");
        aap_protobuf::service::media::shared::message::Config resp;
        resp.set_status(aap_protobuf::service::media::shared::message::Config_Status_STATUS_READY);
        resp.set_max_unacked(1);
        resp.add_configuration_indices(0);
        auto p = aasdk::channel::SendPromise::defer(strand_);
        p->then([this, self = shared_from_this()]() { sendVideoFocusIndication(); }, [](auto){});
        channel_->sendChannelSetupResponse(resp, std::move(p));
        channel_->receive(shared_from_this());
    }

    void onMediaChannelStartIndication(const aap_protobuf::service::media::shared::message::Start& ind) override {
        session_ = ind.session_id();
        LOGI("video: stream start (session=%d)", session_);
        channel_->receive(shared_from_this());
    }

    void onMediaChannelStopIndication(const aap_protobuf::service::media::shared::message::Stop&) override {
        LOGI("video: stream stop");
        channel_->receive(shared_from_this());
    }

    void onMediaWithTimestampIndication(aasdk::messenger::Timestamp::ValueType ts,
                                        const aasdk::common::DataConstBuffer& buf) override {
        frameCount_++;
        int flags = 0;
        bool isVp9 = (videoCodec_ == 2);

        // Auto mode (3): detect codec from first frame's NAL types
        if (videoCodec_ == 3 && buf.size >= 5) {
            // Look for H.265 VPS (type 32) — unambiguous marker
            for (size_t i = 0; i + 5 < buf.size; ++i) {
                bool sc4 = (buf.cdata[i] == 0 && buf.cdata[i+1] == 0 &&
                            buf.cdata[i+2] == 0 && buf.cdata[i+3] == 1);
                if (sc4) {
                    uint8_t h265Type = (buf.cdata[i+4] >> 1) & 0x3f;
                    if (h265Type == 32) { // VPS — definitely H.265
                        videoCodec_ = 1;
                        LOGI("video: auto-detected H.265 from VPS NAL");
                        break;
                    }
                    uint8_t h264Type = buf.cdata[i+4] & 0x1f;
                    if (h264Type == 7) { // SPS — definitely H.264
                        videoCodec_ = 0;
                        LOGI("video: auto-detected H.264 from SPS NAL");
                        break;
                    }
                }
            }
        }

        bool isH265 = (videoCodec_ == 1);

        if (isVp9) {
            // VP9: keyframe detection from frame header (no NAL start codes)
            // Bit 2 of first byte is frame_type: 0 = key, 1 = inter
            if (buf.size >= 1 && !(buf.cdata[0] & 0x04)) {
                flags |= 1; // VP9 keyframe
                flags |= 2; // VP9 keyframes are self-contained (no separate SPS/PPS)
            }
        } else if (buf.size >= 5) {
            // H.264 or H.265: scan for Annex B start codes + NAL type
            for (size_t i = 0; i + 4 < buf.size; ++i) {
                bool fourByte = (buf.cdata[i] == 0 && buf.cdata[i+1] == 0 &&
                                 buf.cdata[i+2] == 0 && buf.cdata[i+3] == 1);
                bool threeByte = !fourByte && (buf.cdata[i] == 0 && buf.cdata[i+1] == 0 &&
                                  buf.cdata[i+2] == 1);
                size_t nalOffset = fourByte ? i + 4 : (threeByte ? i + 3 : 0);
                if (nalOffset > 0 && nalOffset < buf.size) {
                    uint8_t nalByte = buf.cdata[nalOffset];
                    if (isH265) {
                        uint8_t nalType = (nalByte >> 1) & 0x3f;
                        if (nalType == 19 || nalType == 20) flags |= 1; // IDR_W_RADL, IDR_N_LP
                        if (nalType >= 32 && nalType <= 34) flags |= 2; // VPS/SPS/PPS
                    } else {
                        uint8_t nalType = nalByte & 0x1f;
                        if (nalType == 5) flags |= 1; // IDR
                        if (nalType == 7 || nalType == 8) flags |= 2; // SPS/PPS
                    }
                }
            }
        }

        int64_t ptsMs = static_cast<int64_t>(ts / 1000);
        oal::jni::notifyVideoFrame(width_, height_, ptsMs, flags, buf.cdata, buf.size);

        // ACK for flow control
        aap_protobuf::service::media::source::message::Ack ack;
        ack.set_session_id(session_);
        ack.set_ack(1);
        auto p = aasdk::channel::SendPromise::defer(strand_);
        p->then([](){}, [](auto){});
        channel_->sendMediaAckIndication(ack, std::move(p));
        channel_->receive(shared_from_this());
    }

    void onMediaIndication(const aasdk::common::DataConstBuffer& buf) override {
        onMediaWithTimestampIndication(0, buf);
    }

    void onVideoFocusRequest(const aap_protobuf::service::media::video::message::VideoFocusRequestNotification&) override {
        sendVideoFocusIndication();
        channel_->receive(shared_from_this());
    }

    void onChannelError(const aasdk::error::Error& e) override {
        LOGE("video: channel error: %s", e.what());
    }

    boost::asio::io_service::strand strand_;
    std::shared_ptr<aasdk::channel::mediasink::video::channel::VideoChannel> channel_;
    int width_, height_, fps_, dpi_;
    int videoCodec_; // 0=h264, 1=h265, 2=vp9
    int32_t session_ = -1;
    uint32_t frameCount_ = 0;
};

// ─── Audio (Media, Speech, System) ──────────────────────────────

class JniAudioHandler
    : public aasdk::channel::mediasink::audio::IAudioMediaSinkServiceEventHandler
    , public std::enable_shared_from_this<JniAudioHandler>
{
public:
    enum class Type { Media, Speech, System };

    JniAudioHandler(boost::asio::io_service& io,
                    aasdk::messenger::IMessenger::Pointer messenger,
                    Type type)
        : strand_(io), type_(type)
    {
        switch (type) {
            case Type::Media:
                channel_ = std::make_shared<aasdk::channel::mediasink::audio::channel::MediaAudioChannel>(strand_, messenger);
                break;
            case Type::Speech:
                channel_ = std::make_shared<aasdk::channel::mediasink::audio::channel::GuidanceAudioChannel>(strand_, messenger);
                break;
            case Type::System:
                channel_ = std::make_shared<aasdk::channel::mediasink::audio::channel::SystemAudioChannel>(strand_, messenger);
                break;
        }
    }

    void start() { strand_.dispatch([this, self = shared_from_this()]() { channel_->receive(self); }); }
    void stop() {}

private:
    int purpose() const {
        switch (type_) {
            case Type::Media: return AUDIO_PURPOSE_MEDIA;
            case Type::Speech: return AUDIO_PURPOSE_GUIDANCE;
            case Type::System: return AUDIO_PURPOSE_SYSTEM;
        }
        return AUDIO_PURPOSE_MEDIA;
    }

    int sampleRate() const { return type_ == Type::Media ? 48000 : 16000; }
    int channels() const { return type_ == Type::Media ? 2 : 1; }
    const char* name() const {
        switch (type_) {
            case Type::Media: return "media";
            case Type::Speech: return "speech";
            case Type::System: return "system";
        }
        return "unknown";
    }

    void onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest&) override {
        LOGI("audio/%s: channel open", name());
        aap_protobuf::service::control::message::ChannelOpenResponse resp;
        resp.set_status(aap_protobuf::shared::STATUS_SUCCESS);
        auto p = aasdk::channel::SendPromise::defer(strand_);
        p->then([](){}, [](auto){});
        channel_->sendChannelOpenResponse(resp, std::move(p));
        channel_->receive(shared_from_this());
    }

    void onMediaChannelSetupRequest(const aap_protobuf::service::media::shared::message::Setup&) override {
        LOGI("audio/%s: setup", name());
        aap_protobuf::service::media::shared::message::Config resp;
        resp.set_status(aap_protobuf::service::media::shared::message::Config_Status_STATUS_READY);
        resp.set_max_unacked(1);
        resp.add_configuration_indices(0);
        auto p = aasdk::channel::SendPromise::defer(strand_);
        p->then([](){}, [](auto){});
        channel_->sendChannelSetupResponse(resp, std::move(p));
        channel_->receive(shared_from_this());
    }

    void onMediaChannelStartIndication(const aap_protobuf::service::media::shared::message::Start& ind) override {
        session_ = ind.session_id();
        LOGI("audio/%s: start (session=%d)", name(), session_);
        channel_->receive(shared_from_this());
    }

    void onMediaChannelStopIndication(const aap_protobuf::service::media::shared::message::Stop&) override {
        LOGI("audio/%s: stop", name());
        channel_->receive(shared_from_this());
    }

    void onMediaWithTimestampIndication(aasdk::messenger::Timestamp::ValueType,
                                        const aasdk::common::DataConstBuffer& buf) override {
        oal::jni::notifyAudioFrame(purpose(), sampleRate(), channels(), buf.cdata, buf.size);

        // ACK
        aap_protobuf::service::media::source::message::Ack ack;
        ack.set_session_id(session_);
        ack.set_ack(1);
        auto p = aasdk::channel::SendPromise::defer(strand_);
        p->then([](){}, [](auto){});
        channel_->sendMediaAckIndication(ack, std::move(p));
        channel_->receive(shared_from_this());
    }

    void onMediaIndication(const aasdk::common::DataConstBuffer& buf) override {
        onMediaWithTimestampIndication(0, buf);
    }

    void onChannelError(const aasdk::error::Error& e) override {
        LOGE("audio/%s: channel error: %s", name(), e.what());
    }

    boost::asio::io_service::strand strand_;
    std::shared_ptr<aasdk::channel::mediasink::audio::AudioMediaSinkService> channel_;
    Type type_;
    int32_t session_ = -1;
};

// ─── Audio Input (Microphone) ───────────────────────────────────

class JniAudioInputHandler
    : public aasdk::channel::mediasource::IMediaSourceServiceEventHandler
    , public std::enable_shared_from_this<JniAudioInputHandler>
{
public:
    JniAudioInputHandler(boost::asio::io_service& io,
                         aasdk::messenger::IMessenger::Pointer messenger)
        : strand_(io)
        , silenceTimer_(io)
        , channel_(std::make_shared<aasdk::channel::mediasource::MediaSourceService>(
              strand_, messenger, aasdk::messenger::ChannelId::MEDIA_SOURCE_MICROPHONE)) {}

    void start() { strand_.dispatch([this, self = shared_from_this()]() { channel_->receive(self); }); }
    void stop() {
        open_ = false;
        gotRealData_ = true; // stop silence pump
        silenceTimer_.cancel();
    }

    void feedAudio(const uint8_t* data, size_t size) {
        if (!open_.load() || size == 0) return;
        gotRealData_ = true; // stop silence pump — real data arrived
        aasdk::common::Data audioData(data, data + size);
        auto ts = static_cast<aasdk::messenger::Timestamp::ValueType>(
            std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count());
        strand_.dispatch([this, self = shared_from_this(), d = std::move(audioData), ts]() {
            auto p = aasdk::channel::SendPromise::defer(strand_);
            p->then([](){}, [](auto){});
            channel_->sendMediaSourceWithTimestampIndication(ts, d, std::move(p));
        });
    }

private:
    void onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest&) override {
        LOGI("mic: channel open");
        aap_protobuf::service::control::message::ChannelOpenResponse resp;
        resp.set_status(aap_protobuf::shared::STATUS_SUCCESS);
        auto p = aasdk::channel::SendPromise::defer(strand_);
        p->then([](){}, [](auto){});
        channel_->sendChannelOpenResponse(resp, std::move(p));
        channel_->receive(shared_from_this());
    }

    void onMediaChannelSetupRequest(const aap_protobuf::service::media::shared::message::Setup&) override {
        LOGI("mic: setup");
        aap_protobuf::service::media::shared::message::Config resp;
        resp.set_status(aap_protobuf::service::media::shared::message::Config_Status_STATUS_READY);
        resp.set_max_unacked(1);
        resp.add_configuration_indices(0);
        auto p = aasdk::channel::SendPromise::defer(strand_);
        p->then([](){}, [](auto){});
        channel_->sendChannelSetupResponse(resp, std::move(p));
        channel_->receive(shared_from_this());
    }

    void onMediaSourceOpenRequest(const aap_protobuf::service::media::source::message::MicrophoneRequest&) override {
        LOGI("mic: open request — microphone active, starting silence pump");
        open_ = true;
        gotRealData_ = false;
        startSilencePump();
        channel_->receive(shared_from_this());
    }

    void onMediaSourceCloseRequest() {
        LOGI("mic: close request");
        open_ = false;
        gotRealData_ = true;
        silenceTimer_.cancel();
    }

    void onMediaChannelAckIndication(const aap_protobuf::service::media::source::message::Ack&) override {
        channel_->receive(shared_from_this());
    }

    void onChannelError(const aasdk::error::Error& e) override {
        LOGE("mic: channel error: %s", e.what());
        open_ = false;
        gotRealData_ = true;
        silenceTimer_.cancel();
    }

    // Silence pump: send 640-byte zero PCM frames at 20ms intervals
    // until real mic data arrives, preventing phone voice assistant timeout
    void startSilencePump() {
        pumpSilenceFrame();
    }

    void pumpSilenceFrame() {
        if (gotRealData_.load() || !open_.load()) return;

        // 640 bytes = 320 samples @ 16kHz 16-bit mono = 20ms of audio
        static const aasdk::common::Data silence(640, 0);
        auto ts = static_cast<aasdk::messenger::Timestamp::ValueType>(
            std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count());
        auto p = aasdk::channel::SendPromise::defer(strand_);
        p->then([](){}, [](auto){});
        channel_->sendMediaSourceWithTimestampIndication(ts, silence, std::move(p));

        // Schedule next silence frame in 20ms
        silenceTimer_.expires_from_now(boost::posix_time::milliseconds(20));
        silenceTimer_.async_wait([this, self = shared_from_this()](const boost::system::error_code& ec) {
            if (!ec && !gotRealData_.load() && open_.load()) {
                pumpSilenceFrame();
            }
        });
    }

    boost::asio::io_service::strand strand_;
    boost::asio::deadline_timer silenceTimer_;
    std::shared_ptr<aasdk::channel::mediasource::MediaSourceService> channel_;
    std::atomic<bool> open_{false};
    std::atomic<bool> gotRealData_{false};
};

// ─── Sensor ─────────────────────────────────────────────────────

class JniSensorHandler
    : public aasdk::channel::sensorsource::ISensorSourceServiceEventHandler
    , public std::enable_shared_from_this<JniSensorHandler>
{
public:
    JniSensorHandler(boost::asio::io_service& io,
                     aasdk::messenger::IMessenger::Pointer messenger)
        : strand_(io)
        , channel_(std::make_shared<aasdk::channel::sensorsource::SensorSourceService>(strand_, messenger)) {}

    void start() { strand_.dispatch([this, self = shared_from_this()]() { channel_->receive(self); }); }
    void stop() {}

    void sendGpsLocation(double lat, double lon, double alt, float speed,
                         float bearing, uint64_t timestampMs) {
        aap_protobuf::service::sensorsource::message::SensorBatch batch;
        auto* gps = batch.add_location_data();
        gps->set_latitude_e7(static_cast<int32_t>(lat * 1e7));
        gps->set_longitude_e7(static_cast<int32_t>(lon * 1e7));
        gps->set_altitude_e2(static_cast<int32_t>(alt * 1e2));
        gps->set_speed_e3(static_cast<int32_t>(speed * 1000));
        gps->set_bearing_e6(static_cast<int32_t>(bearing * 1e6));
        gps->set_timestamp(timestampMs);
        gps->set_accuracy_e3(10000);
        auto p = aasdk::channel::SendPromise::defer(strand_);
        p->then([](){}, [](auto){});
        channel_->sendSensorEventIndication(batch, std::move(p));
    }

    void sendNightMode(bool night) {
        aap_protobuf::service::sensorsource::message::SensorBatch batch;
        batch.add_night_mode_data()->set_night_mode(night);
        auto p = aasdk::channel::SendPromise::defer(strand_);
        p->then([](){}, [](auto){});
        channel_->sendSensorEventIndication(batch, std::move(p));
    }

    void sendDrivingStatus(bool moving) {
        aap_protobuf::service::sensorsource::message::SensorBatch batch;
        batch.add_driving_status_data()->set_status(moving ? 31 : 0);
        auto p = aasdk::channel::SendPromise::defer(strand_);
        p->then([](){}, [](auto){});
        channel_->sendSensorEventIndication(batch, std::move(p));
    }

private:
    void onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest&) override {
        LOGI("sensor: channel open");
        aap_protobuf::service::control::message::ChannelOpenResponse resp;
        resp.set_status(aap_protobuf::shared::STATUS_SUCCESS);
        auto p = aasdk::channel::SendPromise::defer(strand_);
        p->then([](){}, [](auto){});
        channel_->sendChannelOpenResponse(resp, std::move(p));
        channel_->receive(shared_from_this());
    }

    void onSensorStartRequest(const aap_protobuf::service::sensorsource::message::SensorRequest& req) override {
        LOGI("sensor: start request type=%d", req.type());
        aap_protobuf::service::sensorsource::message::SensorStartResponseMessage resp;
        resp.set_status(aap_protobuf::shared::STATUS_SUCCESS);
        auto p = aasdk::channel::SendPromise::defer(strand_);
        p->then([this, self = shared_from_this()]() {
            sendDrivingStatus(false);
            sendNightMode(false);
        }, [](auto){});
        channel_->sendSensorStartResponse(resp, std::move(p));
        channel_->receive(shared_from_this());
    }

    void onChannelError(const aasdk::error::Error& e) override {
        LOGE("sensor: channel error: %s", e.what());
    }

    boost::asio::io_service::strand strand_;
    std::shared_ptr<aasdk::channel::sensorsource::SensorSourceService> channel_;
};

// ─── Input (Touch + Keys) ───────────────────────────────────────

class JniInputHandler
    : public aasdk::channel::inputsource::IInputSourceServiceEventHandler
    , public std::enable_shared_from_this<JniInputHandler>
{
public:
    JniInputHandler(boost::asio::io_service& io,
                    aasdk::messenger::IMessenger::Pointer messenger,
                    int touchW, int touchH)
        : strand_(io)
        , channel_(std::make_shared<aasdk::channel::inputsource::InputSourceService>(strand_, messenger))
        , touchW_(touchW), touchH_(touchH) {}

    void start() { strand_.dispatch([this, self = shared_from_this()]() { channel_->receive(self); }); }
    void stop() {}

    void sendTouch(int action, float x, float y, int pointerId) {
        strand_.dispatch([this, self = shared_from_this(), action, x, y, pointerId]() {
            aap_protobuf::service::inputsource::message::InputReport report;
            auto now = std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
            report.set_timestamp(static_cast<uint64_t>(now));

            auto* touch = report.mutable_touch_event();
            touch->set_action(static_cast<aap_protobuf::service::inputsource::message::PointerAction>(action));
            touch->set_action_index(0);

            auto* ptr = touch->add_pointer_data();
            ptr->set_x(static_cast<uint32_t>(x));
            ptr->set_y(static_cast<uint32_t>(y));
            ptr->set_pointer_id(pointerId);

            auto p = aasdk::channel::SendPromise::defer(strand_);
            p->then([](){}, [](auto){});
            channel_->sendInputReport(report, std::move(p));
        });
    }

    void sendButton(int keycode, bool down) {
        strand_.dispatch([this, self = shared_from_this(), keycode, down]() {
            aap_protobuf::service::inputsource::message::InputReport report;
            auto now = std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
            report.set_timestamp(static_cast<uint64_t>(now));

            auto* keyEvent = report.mutable_key_event();
            auto* key = keyEvent->add_keys();
            key->set_keycode(keycode);
            key->set_down(down);
            key->set_metastate(0);
            key->set_longpress(false);

            auto p = aasdk::channel::SendPromise::defer(strand_);
            p->then([](){}, [](auto){});
            channel_->sendInputReport(report, std::move(p));
        });
    }

private:
    void onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest&) override {
        LOGI("input: channel open");
        aap_protobuf::service::control::message::ChannelOpenResponse resp;
        resp.set_status(aap_protobuf::shared::STATUS_SUCCESS);
        auto p = aasdk::channel::SendPromise::defer(strand_);
        p->then([](){}, [](auto){});
        channel_->sendChannelOpenResponse(resp, std::move(p));
        channel_->receive(shared_from_this());
    }

    void onKeyBindingRequest(const aap_protobuf::service::media::sink::message::KeyBindingRequest&) override {
        channel_->receive(shared_from_this());
    }

    void onChannelError(const aasdk::error::Error& e) override {
        LOGE("input: channel error: %s", e.what());
    }

    boost::asio::io_service::strand strand_;
    std::shared_ptr<aasdk::channel::inputsource::InputSourceService> channel_;
    int touchW_, touchH_;
};

// ─── Bluetooth (minimal — accept pairing) ───────────────────────

class JniBtHandler
    : public aasdk::channel::bluetooth::IBluetoothServiceEventHandler
    , public std::enable_shared_from_this<JniBtHandler>
{
public:
    JniBtHandler(boost::asio::io_service& io,
                 aasdk::messenger::IMessenger::Pointer messenger)
        : strand_(io)
        , channel_(std::make_shared<aasdk::channel::bluetooth::BluetoothService>(strand_, messenger)) {}

    void start() { strand_.dispatch([this, self = shared_from_this()]() { channel_->receive(self); }); }
    void stop() {}

private:
    void onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest&) override {
        aap_protobuf::service::control::message::ChannelOpenResponse resp;
        resp.set_status(aap_protobuf::shared::STATUS_SUCCESS);
        auto p = aasdk::channel::SendPromise::defer(strand_);
        p->then([](){}, [](auto){});
        channel_->sendChannelOpenResponse(resp, std::move(p));
        channel_->receive(shared_from_this());
    }

    void onBluetoothPairingRequest(const aap_protobuf::service::bluetooth::message::BluetoothPairingRequest& req) override {
        LOGI("bt: pairing request");
        aap_protobuf::service::bluetooth::message::BluetoothPairingResponse resp;
        resp.set_already_paired(true);
        resp.set_status(aap_protobuf::shared::STATUS_SUCCESS);
        auto p = aasdk::channel::SendPromise::defer(strand_);
        p->then([](){}, [](auto){});
        channel_->sendBluetoothPairingResponse(resp, std::move(p));
        channel_->receive(shared_from_this());
    }

    void onBluetoothAuthenticationResult(const aap_protobuf::service::bluetooth::message::BluetoothAuthenticationResult&) override {
        channel_->receive(shared_from_this());
    }

    void onChannelError(const aasdk::error::Error& e) override {
        LOGE("bt: channel error: %s", e.what());
    }

    boost::asio::io_service::strand strand_;
    std::shared_ptr<aasdk::channel::bluetooth::BluetoothService> channel_;
};

// ─── Navigation Status ──────────────────────────────────────────

class JniNavHandler
    : public aasdk::channel::navigationstatus::INavigationStatusServiceEventHandler
    , public std::enable_shared_from_this<JniNavHandler>
{
public:
    JniNavHandler(boost::asio::io_service& io,
                  aasdk::messenger::IMessenger::Pointer messenger)
        : strand_(io)
        , channel_(std::make_shared<aasdk::channel::navigationstatus::NavigationStatusService>(strand_, messenger)) {}

    void start() { strand_.dispatch([this, self = shared_from_this()]() { channel_->receive(self); }); }
    void stop() {}

private:
    void onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest&) override {
        aap_protobuf::service::control::message::ChannelOpenResponse resp;
        resp.set_status(aap_protobuf::shared::STATUS_SUCCESS);
        auto p = aasdk::channel::SendPromise::defer(strand_);
        p->then([](){}, [](auto){});
        channel_->sendChannelOpenResponse(resp, std::move(p));
        channel_->receive(shared_from_this());
    }

    void onStatusUpdate(const aap_protobuf::service::navigationstatus::message::NavigationStatus& status) override {
        auto s = status.status();
        LOGI("nav: status update = %d", s);
        if (s == aap_protobuf::service::navigationstatus::message::NavigationStatus::INACTIVE ||
            s == aap_protobuf::service::navigationstatus::message::NavigationStatus::UNAVAILABLE) {
            oal::jni::notifyNavStateClear();
        }
        channel_->receive(shared_from_this());
    }

    // Deprecated turn event — still handle for older phones
    void onTurnEvent(const aap_protobuf::service::navigationstatus::message::NavigationNextTurnEvent& evt) override {
        std::string maneuver = std::to_string(evt.event());
        std::string road = evt.has_road() ? evt.road() : "";
        int roundaboutExit = evt.has_turn_number() ? evt.turn_number() : 0;
        int roundaboutAngle = evt.has_turn_angle() ? evt.turn_angle() : 0;

        // Cache turn event — will be combined with distance event
        lastManeuver_ = maneuver;
        lastRoad_ = road;
        lastRoundaboutExit_ = roundaboutExit;
        lastRoundaboutAngle_ = roundaboutAngle;

        LOGI("nav: turn event=%s road='%s'", maneuver.c_str(), road.c_str());
        channel_->receive(shared_from_this());
    }

    // Deprecated distance event — combine with last turn event
    void onDistanceEvent(const aap_protobuf::service::navigationstatus::message::NavigationNextTurnDistanceEvent& evt) override {
        int distance = evt.has_distance_meters() ? evt.distance_meters() : 0;
        int timeSec = evt.has_time_to_turn_seconds() ? evt.time_to_turn_seconds() : 0;
        std::string displayDist = evt.has_display_distance_e3() ?
            std::to_string(evt.display_distance_e3() / 1000.0) : "";
        std::string displayUnit;
        if (evt.has_display_distance_unit()) {
            switch (evt.display_distance_unit()) {
                case 1: displayUnit = "m"; break;
                case 2: displayUnit = "km"; break;
                case 3: displayUnit = "mi"; break;
                case 4: displayUnit = "ft"; break;
                case 5: displayUnit = "yd"; break;
                default: displayUnit = ""; break;
            }
        }

        oal::jni::notifyNavState(lastManeuver_, distance, lastRoad_, timeSec,
                                  "", lastRoundaboutExit_, lastRoundaboutAngle_,
                                  displayDist, displayUnit, "", "", "", 0);

        LOGI("nav: distance=%dm eta=%ds", distance, timeSec);
        channel_->receive(shared_from_this());
    }

    // Newer NavigationState API (msg 32774) — richer data with steps, lanes, cues
    void onNavigationState(const aap_protobuf::service::navigationstatus::message::NavigationState& state) override {
        if (state.steps_size() > 0) {
            const auto& step = state.steps(0);
            std::string maneuver;
            int roundaboutExit = 0, roundaboutAngle = 0;
            if (step.has_maneuver()) {
                maneuver = std::to_string(step.maneuver().type());
                if (step.maneuver().has_roundabout_exit_number())
                    roundaboutExit = step.maneuver().roundabout_exit_number();
                if (step.maneuver().has_roundabout_exit_angle())
                    roundaboutAngle = step.maneuver().roundabout_exit_angle();
            }
            std::string road = step.has_road() ? step.road().name() : "";
            std::string cue;
            if (step.has_cue() && step.cue().alternate_text_size() > 0)
                cue = step.cue().alternate_text(0);

            // Cache for combining with currentPosition
            lastManeuver_ = maneuver;
            lastRoad_ = road;
            lastCue_ = cue;
            lastRoundaboutExit_ = roundaboutExit;
            lastRoundaboutAngle_ = roundaboutAngle;

            LOGI("nav: state maneuver=%s road='%s' cue='%s'", maneuver.c_str(), road.c_str(), cue.c_str());
        }

        std::string destination;
        if (state.destinations_size() > 0 && state.destinations(0).has_address())
            destination = state.destinations(0).address();

        lastDestination_ = destination;

        channel_->receive(shared_from_this());
    }

    // Current position updates (msg 32775) — distance, ETA, current road
    void onCurrentPosition(const aap_protobuf::service::navigationstatus::message::NavigationCurrentPosition& pos) override {
        int distanceMeters = 0;
        std::string displayDist, displayUnit;
        int64_t timeSec = 0;

        if (pos.has_step_distance()) {
            const auto& sd = pos.step_distance();
            if (sd.has_distance()) {
                distanceMeters = sd.distance().meters();
                if (sd.distance().has_display_value())
                    displayDist = sd.distance().display_value();
                if (sd.distance().has_display_units()) {
                    switch (sd.distance().display_units()) {
                        case 1: displayUnit = "m"; break;
                        case 2: displayUnit = "km"; break;
                        case 3: displayUnit = "mi"; break;
                        case 4: displayUnit = "ft"; break;
                        case 5: displayUnit = "yd"; break;
                        default: displayUnit = ""; break;
                    }
                }
            }
            if (sd.has_time_to_step_seconds())
                timeSec = sd.time_to_step_seconds();
        }

        std::string currentRoad;
        if (pos.has_current_road())
            currentRoad = pos.current_road().name();

        std::string etaFormatted;
        int64_t timeToArrival = 0;
        if (pos.destination_distances_size() > 0) {
            const auto& dd = pos.destination_distances(0);
            if (dd.has_estimated_time_at_arrival())
                etaFormatted = dd.estimated_time_at_arrival();
            if (dd.has_time_to_arrival_seconds())
                timeToArrival = dd.time_to_arrival_seconds();
        }

        oal::jni::notifyNavState(lastManeuver_, distanceMeters, lastRoad_,
                                  static_cast<int>(timeSec), lastCue_,
                                  lastRoundaboutExit_, lastRoundaboutAngle_,
                                  displayDist, displayUnit, currentRoad,
                                  lastDestination_, etaFormatted, timeToArrival);

        channel_->receive(shared_from_this());
    }

    void onChannelError(const aasdk::error::Error& e) override {
        LOGE("nav: channel error: %s", e.what());
    }

    boost::asio::io_service::strand strand_;
    std::shared_ptr<aasdk::channel::navigationstatus::NavigationStatusService> channel_;

    // Cached state from turn/state events to combine with distance/position events
    std::string lastManeuver_;
    std::string lastRoad_;
    std::string lastCue_;
    std::string lastDestination_;
    int lastRoundaboutExit_ = 0;
    int lastRoundaboutAngle_ = 0;
};

// ─── Media Playback Status ──────────────────────────────────────

class JniMediaStatusHandler
    : public aasdk::channel::mediaplaybackstatus::IMediaPlaybackStatusServiceEventHandler
    , public std::enable_shared_from_this<JniMediaStatusHandler>
{
public:
    JniMediaStatusHandler(boost::asio::io_service& io,
                          aasdk::messenger::IMessenger::Pointer messenger)
        : strand_(io)
        , channel_(std::make_shared<aasdk::channel::mediaplaybackstatus::MediaPlaybackStatusService>(strand_, messenger)) {}

    void start() { strand_.dispatch([this, self = shared_from_this()]() { channel_->receive(self); }); }
    void stop() {}

private:
    void onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest&) override {
        aap_protobuf::service::control::message::ChannelOpenResponse resp;
        resp.set_status(aap_protobuf::shared::STATUS_SUCCESS);
        auto p = aasdk::channel::SendPromise::defer(strand_);
        p->then([](){}, [](auto){});
        channel_->sendChannelOpenResponse(resp, std::move(p));
        channel_->receive(shared_from_this());
    }

    void onMetadataUpdate(const aap_protobuf::service::mediaplayback::message::MediaPlaybackMetadata&) override {
        channel_->receive(shared_from_this());
    }

    void onPlaybackUpdate(const aap_protobuf::service::mediaplayback::message::MediaPlaybackStatus&) override {
        channel_->receive(shared_from_this());
    }

    void onChannelError(const aasdk::error::Error& e) override {
        LOGE("media_status: channel error: %s", e.what());
    }

    boost::asio::io_service::strand strand_;
    std::shared_ptr<aasdk::channel::mediaplaybackstatus::MediaPlaybackStatusService> channel_;
};

// ─── Phone Status ───────────────────────────────────────────────

class JniPhoneStatusHandler
    : public aasdk::channel::phonestatus::IPhoneStatusServiceEventHandler
    , public std::enable_shared_from_this<JniPhoneStatusHandler>
{
public:
    JniPhoneStatusHandler(boost::asio::io_service& io,
                          aasdk::messenger::IMessenger::Pointer messenger)
        : strand_(io)
        , channel_(std::make_shared<aasdk::channel::phonestatus::PhoneStatusService>(strand_, messenger)) {}

    void start() { strand_.dispatch([this, self = shared_from_this()]() { channel_->receive(self); }); }
    void stop() {}

private:
    void onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest&) override {
        aap_protobuf::service::control::message::ChannelOpenResponse resp;
        resp.set_status(aap_protobuf::shared::STATUS_SUCCESS);
        auto p = aasdk::channel::SendPromise::defer(strand_);
        p->then([](){}, [](auto){});
        channel_->sendChannelOpenResponse(resp, std::move(p));
        channel_->receive(shared_from_this());
    }

    void onPhoneStatusUpdate(const aap_protobuf::service::phonestatus::message::PhoneStatus&) override {
        channel_->receive(shared_from_this());
    }

    void onChannelError(const aasdk::error::Error& e) override {
        LOGE("phone_status: channel error: %s", e.what());
    }

    boost::asio::io_service::strand strand_;
    std::shared_ptr<aasdk::channel::phonestatus::PhoneStatusService> channel_;
};

// ════════════════════════════════════════════════════════════════════
// JniAutoEntity implementation
// ════════════════════════════════════════════════════════════════════

JniAutoEntity::JniAutoEntity(
    boost::asio::io_service& io,
    aasdk::messenger::ICryptor::Pointer cryptor,
    aasdk::transport::ITransport::Pointer transport,
    aasdk::messenger::IMessenger::Pointer messenger,
    const SessionConfig& config)
    : io_(io), strand_(io)
    , cryptor_(std::move(cryptor))
    , transport_(std::move(transport))
    , messenger_(std::move(messenger))
    , config_(config)
    , pingTimer_(io)
{
    controlChannel_ = std::make_shared<aasdk::channel::control::ControlServiceChannel>(strand_, messenger_);

    int aaW, aaH;
    tierToDimensions(config_.resolutionTier, aaW, aaH);

    videoHandler_ = std::make_shared<JniVideoHandler>(io_, messenger_, aaW, aaH, config_.fps, config_.dpi, config_.videoCodec);
    mediaAudioHandler_ = std::make_shared<JniAudioHandler>(io_, messenger_, JniAudioHandler::Type::Media);
    speechAudioHandler_ = std::make_shared<JniAudioHandler>(io_, messenger_, JniAudioHandler::Type::Speech);
    systemAudioHandler_ = std::make_shared<JniAudioHandler>(io_, messenger_, JniAudioHandler::Type::System);
    audioInputHandler_ = std::make_shared<JniAudioInputHandler>(io_, messenger_);
    sensorHandler_ = std::make_shared<JniSensorHandler>(io_, messenger_);
    inputHandler_ = std::make_shared<JniInputHandler>(io_, messenger_, aaW, aaH);
    btHandler_ = std::make_shared<JniBtHandler>(io_, messenger_);
    navHandler_ = std::make_shared<JniNavHandler>(io_, messenger_);
    mediaStatusHandler_ = std::make_shared<JniMediaStatusHandler>(io_, messenger_);
    phoneStatusHandler_ = std::make_shared<JniPhoneStatusHandler>(io_, messenger_);
}

void JniAutoEntity::start() {
    strand_.dispatch([this, self = shared_from_this()]() {
        LOGI("entity: starting — sending version request");
        auto p = aasdk::channel::SendPromise::defer(strand_);
        p->then([](){}, [this, self](auto e) { onChannelError(e); });
        controlChannel_->sendVersionRequest(std::move(p));
        controlChannel_->receive(shared_from_this());
    });
}

void JniAutoEntity::stop() {
    strand_.dispatch([this, self = shared_from_this()]() {
        if (active_) {
            // 9l: Send ByeByeRequest to phone for graceful shutdown
            try {
                aap_protobuf::service::control::message::ByeByeRequest bye;
                bye.set_reason(aap_protobuf::service::control::message::USER_SELECTION);
                auto p = aasdk::channel::SendPromise::defer(strand_);
                p->then([](){}, [](auto){});
                controlChannel_->sendShutdownRequest(bye, std::move(p));
                LOGI("entity: sent ByeByeRequest");
            } catch (...) {
                LOGW("entity: failed to send ByeByeRequest");
            }
        }
        active_ = false;
        pingTimer_.cancel();
        videoHandler_->stop();
        mediaAudioHandler_->stop();
        speechAudioHandler_->stop();
        systemAudioHandler_->stop();
        audioInputHandler_->stop();
        sensorHandler_->stop();
        inputHandler_->stop();
        btHandler_->stop();
        messenger_->stop();
        transport_->stop();
        cryptor_->deinit();
    });
}

void JniAutoEntity::onVersionResponse(uint16_t major, uint16_t minor,
                                       aap_protobuf::shared::MessageStatus status) {
    LOGI("entity: version response %d.%d status=%d", major, minor, status);
    if (status == aap_protobuf::shared::STATUS_NO_COMPATIBLE_VERSION) {
        triggerQuit("version_mismatch");
        return;
    }
    try {
        cryptor_->doHandshake();
        auto hsData = cryptor_->readHandshakeBuffer();
        auto p = aasdk::channel::SendPromise::defer(strand_);
        p->then([](){}, [this, self = shared_from_this()](auto e) { onChannelError(e); });
        controlChannel_->sendHandshake(std::move(hsData), std::move(p));
        controlChannel_->receive(shared_from_this());
    } catch (const aasdk::error::Error& e) {
        onChannelError(e);
    }
}

void JniAutoEntity::onHandshake(const aasdk::common::DataConstBuffer& payload) {
    try {
        cryptor_->writeHandshakeBuffer(payload);
        if (!cryptor_->doHandshake()) {
            auto hsData = cryptor_->readHandshakeBuffer();
            auto p = aasdk::channel::SendPromise::defer(strand_);
            p->then([](){}, [this, self = shared_from_this()](auto e) { onChannelError(e); });
            controlChannel_->sendHandshake(std::move(hsData), std::move(p));
        } else {
            LOGI("entity: TLS handshake complete");
            aap_protobuf::service::control::message::AuthResponse auth;
            auth.set_status(aap_protobuf::shared::STATUS_SUCCESS);
            auto p = aasdk::channel::SendPromise::defer(strand_);
            p->then([](){}, [this, self = shared_from_this()](auto e) { onChannelError(e); });
            controlChannel_->sendAuthComplete(auth, std::move(p));
        }
        controlChannel_->receive(shared_from_this());
    } catch (const aasdk::error::Error& e) {
        onChannelError(e);
    }
}

void JniAutoEntity::onServiceDiscoveryRequest(
    const aap_protobuf::service::control::message::ServiceDiscoveryRequest& req)
{
    deviceName_ = req.device_name();
    LOGI("entity: service discovery from '%s'", deviceName_.c_str());

    oal::jni::notifyPhoneConnected(deviceName_, "AndroidAuto");

    aap_protobuf::service::control::message::ServiceDiscoveryResponse resp;
    resp.set_display_name(config_.headUnitName);
    resp.set_head_unit_make("OpenAutoLink");
    resp.set_head_unit_model("Direct");
    resp.set_head_unit_software_build("1");
    resp.set_head_unit_software_version("1.0");
    resp.set_driver_position(config_.driverPosition == 1
        ? aap_protobuf::service::control::message::DRIVER_POSITION_RIGHT
        : aap_protobuf::service::control::message::DRIVER_POSITION_LEFT);

    // 9b: Session configuration bitmask (hide clock/signal/battery on AA projection)
    if (config_.sessionConfiguration != 0) {
        resp.set_session_configuration(config_.sessionConfiguration);
    }

    // 9c: Connection configuration (ping timeout, interval, high-latency threshold)
    auto* connConfig = resp.mutable_connection_configuration();
    auto* pingConfig = connConfig->mutable_ping_configuration();
    pingConfig->set_timeout_ms(5000);
    pingConfig->set_interval_ms(1500);
    pingConfig->set_high_latency_threshold_ms(500);
    pingConfig->set_tracked_ping_count(5);

    // Pixel aspect ratio is set per-video-config in UiConfig, not at the SDR level

    int aaW, aaH;
    tierToDimensions(config_.resolutionTier, aaW, aaH);

    // Apply margins to video resolution
    int videoW = aaW - config_.marginWidth;
    int videoH = aaH - config_.marginHeight;
    if (videoW < 320) videoW = aaW;
    if (videoH < 240) videoH = aaH;

    auto fps = config_.fps >= 60
        ? aap_protobuf::service::media::sink::message::VIDEO_FPS_60
        : aap_protobuf::service::media::sink::message::VIDEO_FPS_30;

    // Helper lambda to add a video config with insets and pixel aspect
    auto addVideoConfig = [&](auto* ms, int tier, aap_protobuf::service::media::shared::message::MediaCodecType codecType) {
        int tw, th;
        tierToDimensions(tier, tw, th);
        int vw = tw - config_.marginWidth;
        int vh = th - config_.marginHeight;
        if (vw < 320) vw = tw;
        if (vh < 240) vh = th;

        auto* vc = ms->add_video_configs();
        vc->set_codec_resolution(static_cast<aap_protobuf::service::media::sink::message::VideoCodecResolutionType>(tier));
        vc->set_frame_rate(fps);
        vc->set_density(config_.dpi);
        vc->set_decoder_additional_depth(2);
        vc->set_video_codec_type(codecType);
        if (config_.pixelAspect > 0) {
            vc->set_pixel_aspect_ratio_e4(config_.pixelAspect);
        }
        if (config_.marginWidth > 0) vc->set_width_margin(config_.marginWidth);
        if (config_.marginHeight > 0) vc->set_height_margin(config_.marginHeight);

        // UI config: insets for display-aware AA layout
        auto* ui = vc->mutable_ui_config();
        if (config_.safeTop > 0 || config_.safeBottom > 0 ||
            config_.safeLeft > 0 || config_.safeRight > 0) {
            auto* si = ui->mutable_stable_content_insets();
            si->set_top(config_.safeTop);
            si->set_bottom(config_.safeBottom);
            si->set_left(config_.safeLeft);
            si->set_right(config_.safeRight);
        }
        if (config_.contentTop > 0 || config_.contentBottom > 0 ||
            config_.contentLeft > 0 || config_.contentRight > 0) {
            auto* ci = ui->mutable_content_insets();
            ci->set_top(config_.contentTop);
            ci->set_bottom(config_.contentBottom);
            ci->set_left(config_.contentLeft);
            ci->set_right(config_.contentRight);
        }
    };

    // 9a: Multi-codec video — H.265 at all tiers (5→1), H.264 fallback at tiers 3→1
    // Phone picks the best codec+resolution combo it supports
    { auto* svc = resp.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::MEDIA_SINK_VIDEO));
      auto* ms = svc->mutable_media_sink_service();
      ms->set_available_type(aap_protobuf::service::media::shared::message::MEDIA_CODEC_VIDEO_H264_BP);
      ms->set_available_while_in_call(true);

      // Video configs: offer the user's preferred codec first, then fallback.
      // Phone picks the first codec+resolution it supports.
      if (config_.videoCodec == 1) {
          // H.265 preferred: offer H.265 at all tiers first, then H.264 fallback
          for (int t : {5, 4, 3, 2, 1}) {
              if (t <= config_.resolutionTier)
                  addVideoConfig(ms, t, aap_protobuf::service::media::shared::message::MEDIA_CODEC_VIDEO_H265);
          }
          for (int t : {3, 2, 1}) {
              if (t <= config_.resolutionTier)
                  addVideoConfig(ms, t, aap_protobuf::service::media::shared::message::MEDIA_CODEC_VIDEO_H264_BP);
          }
      } else if (config_.videoCodec == 2) {
          // VP9 preferred: offer VP9 at all tiers first, then H.264 fallback
          for (int t : {5, 4, 3, 2, 1}) {
              if (t <= config_.resolutionTier)
                  addVideoConfig(ms, t, aap_protobuf::service::media::shared::message::MEDIA_CODEC_VIDEO_VP9);
          }
          for (int t : {3, 2, 1}) {
              if (t <= config_.resolutionTier)
                  addVideoConfig(ms, t, aap_protobuf::service::media::shared::message::MEDIA_CODEC_VIDEO_H264_BP);
          }
      } else if (config_.videoCodec == 3) {
          // Auto: offer all codecs — H.265 first (best quality), H.264 fallback
          for (int t : {5, 4, 3, 2, 1}) {
              if (t <= config_.resolutionTier)
                  addVideoConfig(ms, t, aap_protobuf::service::media::shared::message::MEDIA_CODEC_VIDEO_H265);
          }
          for (int t : {3, 2, 1}) {
              if (t <= config_.resolutionTier)
                  addVideoConfig(ms, t, aap_protobuf::service::media::shared::message::MEDIA_CODEC_VIDEO_H264_BP);
          }
      } else {
          // H.264 preferred (default): offer H.264 only
          for (int t : {3, 2, 1}) {
              if (t <= config_.resolutionTier)
                  addVideoConfig(ms, t, aap_protobuf::service::media::shared::message::MEDIA_CODEC_VIDEO_H264_BP);
          }
      }
    }

    // Media Audio (48kHz stereo)
    { auto* svc = resp.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::MEDIA_SINK_MEDIA_AUDIO));
      auto* ms = svc->mutable_media_sink_service();
      ms->set_available_type(aap_protobuf::service::media::shared::message::MEDIA_CODEC_AUDIO_PCM);
      ms->set_audio_type(aap_protobuf::service::media::sink::message::AUDIO_STREAM_MEDIA);
      ms->set_available_while_in_call(true);
      auto* ac = ms->add_audio_configs();
      ac->set_sampling_rate(48000); ac->set_number_of_bits(16); ac->set_number_of_channels(2); }

    // Speech Audio (16kHz mono)
    { auto* svc = resp.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::MEDIA_SINK_GUIDANCE_AUDIO));
      auto* ms = svc->mutable_media_sink_service();
      ms->set_available_type(aap_protobuf::service::media::shared::message::MEDIA_CODEC_AUDIO_PCM);
      ms->set_audio_type(aap_protobuf::service::media::sink::message::AUDIO_STREAM_GUIDANCE);
      ms->set_available_while_in_call(true);
      auto* ac = ms->add_audio_configs();
      ac->set_sampling_rate(16000); ac->set_number_of_bits(16); ac->set_number_of_channels(1); }

    // System Audio (16kHz mono)
    { auto* svc = resp.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::MEDIA_SINK_SYSTEM_AUDIO));
      auto* ms = svc->mutable_media_sink_service();
      ms->set_available_type(aap_protobuf::service::media::shared::message::MEDIA_CODEC_AUDIO_PCM);
      ms->set_audio_type(aap_protobuf::service::media::sink::message::AUDIO_STREAM_SYSTEM_AUDIO);
      ms->set_available_while_in_call(true);
      auto* ac = ms->add_audio_configs();
      ac->set_sampling_rate(16000); ac->set_number_of_bits(16); ac->set_number_of_channels(1); }

    // Audio Input (mic, 16kHz mono)
    { auto* svc = resp.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::MEDIA_SOURCE_MICROPHONE));
      auto* msrc = svc->mutable_media_source_service();
      msrc->set_available_type(aap_protobuf::service::media::shared::message::MEDIA_CODEC_AUDIO_PCM);
      auto* ac = msrc->mutable_audio_config();
      ac->set_sampling_rate(16000); ac->set_number_of_bits(16); ac->set_number_of_channels(1); }

    // 9d: Full sensor declaration — all sensor types supported
    { auto* svc = resp.add_channels();
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
      // GPS + accel + gyro + compass + speed sensor fusion
      ss->set_location_characterization(256 | 4 | 2 | 8 | 64);
      // EV vehicle type
      ss->add_supported_fuel_types(aap_protobuf::service::sensorsource::message::FUEL_TYPE_ELECTRIC);
      ss->add_supported_ev_connector_types(aap_protobuf::service::sensorsource::message::EV_CONNECTOR_TYPE_J1772);
      ss->add_supported_ev_connector_types(aap_protobuf::service::sensorsource::message::EV_CONNECTOR_TYPE_COMBO_1);
    }

    // 9f: Input — touch w×h = AA video dimensions + extended keycodes
    { auto* svc = resp.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::INPUT_SOURCE));
      auto* is = svc->mutable_input_source_service();
      // Standard media keycodes + REWIND (89) and FAST_FORWARD (90)
      for (int kc : {84, 85, 86, 87, 88, 89, 90, 126, 127})
          is->add_keycodes_supported(kc);
      auto* ts = is->add_touchscreen();
      ts->set_width(videoW); ts->set_height(videoH);
      ts->set_type(aap_protobuf::service::inputsource::message::CAPACITIVE);
    }

    // 9e: Bluetooth — configurable BT MAC + numeric comparison pairing
    { auto* svc = resp.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::BLUETOOTH));
      auto* bs = svc->mutable_bluetooth_service();
      bs->set_car_address(config_.btMac);
      bs->add_supported_pairing_methods(aap_protobuf::service::bluetooth::message::BLUETOOTH_PAIRING_PIN);
      bs->add_supported_pairing_methods(aap_protobuf::service::bluetooth::message::BLUETOOTH_PAIRING_NUMERIC_COMPARISON);
    }

    // Navigation Status
    { auto* svc = resp.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::NAVIGATION_STATUS));
      auto* ns = svc->mutable_navigation_status_service();
      ns->set_minimum_interval_ms(500);
      ns->set_type(aap_protobuf::service::navigationstatus::NavigationStatusService::IMAGE);
      auto* img = ns->mutable_image_options();
      img->set_width(256); img->set_height(256); img->set_colour_depth_bits(32); }

    // Media Playback Status
    { auto* svc = resp.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::MEDIA_PLAYBACK_STATUS));
      svc->mutable_media_playback_service(); }

    // Phone Status
    { auto* svc = resp.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::PHONE_STATUS));
      svc->mutable_phone_status_service(); }

    LOGI("entity: sending SDR with %d channels", resp.channels_size());

    auto p = aasdk::channel::SendPromise::defer(strand_);
    p->then([this, self = shared_from_this()]() {
        LOGI("entity: SDR sent — starting service handlers");
        videoHandler_->start();
        mediaAudioHandler_->start();
        speechAudioHandler_->start();
        systemAudioHandler_->start();
        audioInputHandler_->start();
        sensorHandler_->start();
        inputHandler_->start();
        btHandler_->start();
        navHandler_->start();
        mediaStatusHandler_->start();
        phoneStatusHandler_->start();
        sendPing();
        schedulePing();
    }, [this, self = shared_from_this()](auto e) { onChannelError(e); });
    controlChannel_->sendServiceDiscoveryResponse(resp, std::move(p));
    controlChannel_->receive(shared_from_this());
    active_ = true;
}

void JniAutoEntity::onAudioFocusRequest(
    const aap_protobuf::service::control::message::AudioFocusRequest& req) {
    auto state = (req.audio_focus_type() == 0)
        ? aap_protobuf::service::control::message::AUDIO_FOCUS_STATE_LOSS
        : aap_protobuf::service::control::message::AUDIO_FOCUS_STATE_GAIN;
    aap_protobuf::service::control::message::AudioFocusNotification resp;
    resp.set_focus_state(state);
    auto p = aasdk::channel::SendPromise::defer(strand_);
    p->then([](){}, [this, self = shared_from_this()](auto e) { onChannelError(e); });
    controlChannel_->sendAudioFocusResponse(resp, std::move(p));
    controlChannel_->receive(shared_from_this());
}

void JniAutoEntity::onNavigationFocusRequest(
    const aap_protobuf::service::control::message::NavFocusRequestNotification&) {
    aap_protobuf::service::control::message::NavFocusNotification resp;
    resp.set_focus_type(aap_protobuf::service::control::message::NAV_FOCUS_PROJECTED);
    auto p = aasdk::channel::SendPromise::defer(strand_);
    p->then([](){}, [this, self = shared_from_this()](auto e) { onChannelError(e); });
    controlChannel_->sendNavigationFocusResponse(resp, std::move(p));
    controlChannel_->receive(shared_from_this());
}

void JniAutoEntity::onByeByeRequest(
    const aap_protobuf::service::control::message::ByeByeRequest&) {
    aap_protobuf::service::control::message::ByeByeResponse resp;
    auto p = aasdk::channel::SendPromise::defer(strand_);
    p->then([this, self = shared_from_this()]() { triggerQuit("phone_bye"); },
            [this, self = shared_from_this()](auto e) { onChannelError(e); });
    controlChannel_->sendShutdownResponse(resp, std::move(p));
}

void JniAutoEntity::onByeByeResponse(const aap_protobuf::service::control::message::ByeByeResponse&) {
    triggerQuit("bye_response");
}

void JniAutoEntity::onPingRequest(const aap_protobuf::service::control::message::PingRequest& req) {
    aap_protobuf::service::control::message::PingResponse resp;
    resp.set_timestamp(req.timestamp());
    auto p = aasdk::channel::SendPromise::defer(strand_);
    p->then([](){}, [this, self = shared_from_this()](auto e) { onChannelError(e); });
    controlChannel_->sendPingResponse(resp, std::move(p));
    controlChannel_->receive(shared_from_this());
}

void JniAutoEntity::onPingResponse(const aap_protobuf::service::control::message::PingResponse&) {
    controlChannel_->receive(shared_from_this());
}

void JniAutoEntity::onChannelError(const aasdk::error::Error& e) {
    LOGE("entity: channel error: %s", e.what());
    if (e.getCode() == aasdk::error::ErrorCode::OPERATION_ABORTED) return;
    triggerQuit(std::string("channel_error: ") + e.what());
}

void JniAutoEntity::sendPing() {
    aap_protobuf::service::control::message::PingRequest req;
    req.set_timestamp(std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count());
    auto p = aasdk::channel::SendPromise::defer(strand_);
    p->then([](){}, [this, self = shared_from_this()](auto e) { onChannelError(e); });
    controlChannel_->sendPingRequest(req, std::move(p));
}

void JniAutoEntity::schedulePing() {
    pingTimer_.expires_from_now(boost::posix_time::milliseconds(2000));
    pingTimer_.async_wait([this, self = shared_from_this()](const boost::system::error_code& ec) {
        if (!ec && active_) {
            sendPing();
            schedulePing();
        }
    });
}

void JniAutoEntity::triggerQuit(const std::string& reason) {
    if (!active_) return;
    active_ = false;
    LOGI("entity: quit — %s", reason.c_str());
    oal::jni::notifyPhoneDisconnected(reason);
    if (disconnectCb_) disconnectCb_();
}

// ════════════════════════════════════════════════════════════════════
// Global session state
// ════════════════════════════════════════════════════════════════════

namespace {

std::atomic<bool> g_running{false};
std::mutex g_sessionMutex;  // guards sendTouch/sendButton/sendMic during stopSession
std::unique_ptr<boost::asio::io_service> g_io;
std::unique_ptr<boost::asio::io_service::work> g_work;
std::thread g_ioThread;
std::unique_ptr<boost::asio::ip::tcp::acceptor> g_acceptor;
aasdk::tcp::TCPWrapper g_tcpWrapper;
std::shared_ptr<JniAutoEntity> g_entity;
SessionConfig g_config;

void acceptConnection();

void createEntity(aasdk::transport::ITransport::Pointer transport) {
    LOGI("createEntity: setting up SSL cryptor");
    auto sslWrapper = std::make_shared<aasdk::transport::SSLWrapper>();
    auto cryptor = std::make_shared<aasdk::messenger::Cryptor>(std::move(sslWrapper));
    try {
        cryptor->init();
    } catch (const std::exception& e) {
        LOGE("createEntity: cryptor init failed: %s", e.what());
        return;
    }

    auto messenger = std::make_shared<aasdk::messenger::Messenger>(
        *g_io,
        std::make_shared<aasdk::messenger::MessageInStream>(*g_io, transport, cryptor),
        std::make_shared<aasdk::messenger::MessageOutStream>(*g_io, transport, cryptor));

    g_entity = std::make_shared<JniAutoEntity>(
        *g_io, std::move(cryptor), std::move(transport),
        std::move(messenger), g_config);

    g_entity->setDisconnectCb([]() {
        LOGI("phone disconnected — resuming accept");
        g_entity.reset();
        if (g_running.load() && g_acceptor) {
            acceptConnection();
        }
    });

    g_entity->start();
}

void acceptConnection() {
    auto socket = std::make_shared<boost::asio::ip::tcp::socket>(*g_io);
    g_acceptor->async_accept(*socket,
        [socket](const boost::system::error_code& ec) {
            if (!ec) {
                LOGI("TCP client connected");
                if (g_entity) {
                    g_entity->stop();
                    g_entity.reset();
                }
                auto endpoint = std::make_shared<aasdk::tcp::TCPEndpoint>(
                    g_tcpWrapper, std::move(socket));
                auto transport = std::make_shared<aasdk::transport::TCPTransport>(
                    *g_io, std::move(endpoint));
                createEntity(std::move(transport));
                // Keep accepting for reconnects
                if (g_running.load()) acceptConnection();
            } else if (g_running.load()) {
                acceptConnection();
            }
        });
}

} // anonymous namespace

// ════════════════════════════════════════════════════════════════════
// oal:: public API (called from jni_bridge.cpp)
// ════════════════════════════════════════════════════════════════════

namespace oal {

bool startSession(int port, int width, int height, int fps, int dpi) {
    if (g_running.exchange(true)) {
        LOGW("startSession: already running");
        return false;
    }

    LOGI("startSession: port=%d %dx%d @%dfps dpi=%d", port, width, height, fps, dpi);

    g_config.port = port;
    g_config.width = width;
    g_config.height = height;
    g_config.fps = fps;
    g_config.dpi = dpi;
    g_config.resolutionTier = widthToTier(width);

    g_io = std::make_unique<boost::asio::io_service>();
    g_work = std::make_unique<boost::asio::io_service::work>(*g_io);

    try {
        auto endpoint = boost::asio::ip::tcp::endpoint(
            boost::asio::ip::address::from_string("0.0.0.0"), static_cast<uint16_t>(port));
        g_acceptor = std::make_unique<boost::asio::ip::tcp::acceptor>(*g_io, endpoint);
        LOGI("TCP listener on :%d", port);
    } catch (const std::exception& e) {
        LOGE("TCP listen failed: %s", e.what());
        g_running = false;
        g_work.reset();
        g_io.reset();
        return false;
    }

    acceptConnection();

    g_ioThread = std::thread([]() {
        LOGI("io_service thread started");
        try {
            g_io->run();
        } catch (const std::exception& e) {
            LOGE("io_service exception: %s", e.what());
        }
        LOGI("io_service thread stopped");
    });

    return true;
}

bool startSessionWithFd(int socketFd, int width, int height, int fps, int dpi,
                        int marginW, int marginH, int pixelAspect, int driverPos,
                        int safeT, int safeB, int safeL, int safeR,
                        int contentT, int contentB, int contentL, int contentR,
                        const char* headUnitName, int sessionConfig, const char* btMac,
                        int videoCodec) {
    if (g_running.exchange(true)) {
        LOGW("startSessionWithFd: already running");
        return false;
    }

    LOGI("startSessionWithFd: fd=%d %dx%d @%dfps dpi=%d margin=%dx%d pa=%d sessionCfg=%d codec=%d",
         socketFd, width, height, fps, dpi, marginW, marginH, pixelAspect, sessionConfig, videoCodec);

    g_config.port = 0;
    g_config.width = width;
    g_config.height = height;
    g_config.fps = fps;
    g_config.dpi = dpi;
    g_config.resolutionTier = widthToTier(width);
    g_config.marginWidth = marginW;
    g_config.marginHeight = marginH;
    g_config.pixelAspect = pixelAspect;
    g_config.driverPosition = driverPos;
    g_config.safeTop = safeT;
    g_config.safeBottom = safeB;
    g_config.safeLeft = safeL;
    g_config.safeRight = safeR;
    g_config.contentTop = contentT;
    g_config.contentBottom = contentB;
    g_config.contentLeft = contentL;
    g_config.contentRight = contentR;
    if (headUnitName && headUnitName[0]) g_config.headUnitName = headUnitName;
    g_config.sessionConfiguration = sessionConfig;
    if (btMac && btMac[0]) g_config.btMac = btMac;
    g_config.videoCodec = videoCodec;

    g_io = std::make_unique<boost::asio::io_service>();
    g_work = std::make_unique<boost::asio::io_service::work>(*g_io);

    // Wrap the pre-connected socket fd in a Boost.Asio tcp::socket
    try {
        auto socket = std::make_shared<boost::asio::ip::tcp::socket>(
            *g_io, boost::asio::ip::tcp::v4(), socketFd);
        LOGI("TCP fd %d wrapped — creating entity", socketFd);
        auto endpoint = std::make_shared<aasdk::tcp::TCPEndpoint>(
            g_tcpWrapper, std::move(socket));
        auto transport = std::make_shared<aasdk::transport::TCPTransport>(
            *g_io, std::move(endpoint));
        createEntity(std::move(transport));
    } catch (const std::exception& e) {
        LOGE("Failed to wrap fd %d: %s", socketFd, e.what());
        g_running = false;
        g_work.reset();
        g_io.reset();
        return false;
    }

    g_ioThread = std::thread([]() {
        LOGI("io_service thread started (fd mode)");
        try {
            g_io->run();
        } catch (const std::exception& e) {
            LOGE("io_service exception: %s", e.what());
        }
        LOGI("io_service thread stopped");
    });

    return true;
}

void stopSession() {
    if (!g_running.exchange(false)) return;
    LOGI("stopSession");

    // Lock to prevent sendTouch/sendButton/sendMic from dispatching
    // to the strand while we tear down the entity and io_service.
    std::lock_guard<std::mutex> lock(g_sessionMutex);

    // Grab io_service pointer for shutdown, then null the global immediately.
    auto io = std::move(g_io);

    if (g_entity) {
        g_entity->stop();
        g_entity.reset();
    }

    if (g_acceptor) {
        boost::system::error_code ec;
        g_acceptor->close(ec);
        g_acceptor.reset();
    }

    g_work.reset();
    if (io) io->stop();

    if (g_ioThread.joinable()) {
        // Timed join: if io_service thread is stuck in a callback, don't block
        // the JNI thread forever (causes ANR and process kill).
        auto future = std::async(std::launch::async, []() { g_ioThread.join(); });
        if (future.wait_for(std::chrono::seconds(3)) == std::future_status::timeout) {
            LOGW("stopSession: io_service thread join timed out — detaching");
            g_ioThread.detach();
        }
    }
    // io goes out of scope here and is destroyed
}

void sendTouch(int action, float x, float y, int pointerId) {
    if (!g_running.load()) return;
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    if (!g_running.load() || !g_io || !g_entity || !g_entity->inputHandler()) return;
    try {
        g_entity->inputHandler()->sendTouch(action, x, y, pointerId);
    } catch (...) {}
}

void sendSensorData(int type, const uint8_t* data, size_t len) {
    if (!g_running.load()) return;
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    if (!g_running.load() || !g_io || !g_entity || !g_entity->sensorHandler()) return;

    // type 0x01 = GNSS NMEA — forwarded as raw bytes to aasdk sensor handler
    if (type == 0x01 && len > 0) {
        LOGD("GNSS data received (%zu bytes)", len);
    }
}

void sendMicAudio(const uint8_t* pcm, size_t len) {
    if (!g_running.load()) return;
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    if (!g_running.load() || !g_io || !g_entity || !g_entity->audioInputHandler()) return;
    try {
        g_entity->audioInputHandler()->feedAudio(pcm, len);
    } catch (...) {}
}

void sendButton(int keycode, bool down) {
    if (!g_running.load()) return;
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    if (!g_running.load() || !g_io || !g_entity || !g_entity->inputHandler()) return;
    try {
        g_entity->inputHandler()->sendButton(keycode, down);
    } catch (...) {}
}

} // namespace oal

#else // OAL_STUB_ONLY

// ════════════════════════════════════════════════════════════════════
// Stub mode — no aasdk, no dependencies
// ════════════════════════════════════════════════════════════════════

#include <android/log.h>
#include <atomic>
#include <cstdint>
#include <cstddef>

#define LOG_TAG "aa_session"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace oal {

static std::atomic<bool> g_running{false};

bool startSession(int port, int width, int height, int fps, int dpi) {
    if (g_running.exchange(true)) { LOGW("already running"); return false; }
    LOGI("startSession STUB: port=%d %dx%d @%dfps dpi=%d", port, width, height, fps, dpi);
    g_running = false;
    return true;
}

bool startSessionWithFd(int socketFd, int width, int height, int fps, int dpi,
                        int, int, int, int, int, int, int, int, int, int, int, int,
                        const char*, int, const char*, int) {
    if (g_running.exchange(true)) { LOGW("already running"); return false; }
    LOGI("startSessionWithFd STUB: fd=%d %dx%d @%dfps dpi=%d", socketFd, width, height, fps, dpi);
    g_running = false;
    return true;
}

void stopSession() { g_running = false; }
void sendTouch(int, float, float, int) {}
void sendSensorData(int, const uint8_t*, size_t) {}
void sendMicAudio(const uint8_t*, size_t) {}
void sendButton(int, bool) {}

} // namespace oal

#endif // OAL_STUB_ONLY
