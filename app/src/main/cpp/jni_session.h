/*
 * jni_session.h — aasdk session coordinator + JNI callback dispatch.
 *
 * Implements IControlServiceChannelEventHandler + IVideoMediaSinkServiceEventHandler
 * directly. All other channels use separate handler classes (see jni_channel_handlers.h)
 * that delegate JNI callbacks through dispatch methods on this class.
 */
#pragma once

#include <memory>
#include <thread>
#include <atomic>
#include <map>
#include <mutex>
#include <string>

#include <jni.h>
#include <boost/asio.hpp>

#include <aasdk/Transport/ITransport.hpp>
#include <aasdk/Transport/SSLWrapper.hpp>
#include <aasdk/Messenger/ICryptor.hpp>
#include <aasdk/Messenger/Cryptor.hpp>
#include <aasdk/Messenger/IMessenger.hpp>
#include <aasdk/Messenger/Messenger.hpp>
#include <aasdk/Messenger/MessageInStream.hpp>
#include <aasdk/Messenger/MessageOutStream.hpp>
#include <aasdk/Channel/Control/ControlServiceChannel.hpp>
#include <aasdk/Channel/Control/IControlServiceChannelEventHandler.hpp>
#include <aasdk/Channel/MediaSink/Video/VideoMediaSinkService.hpp>
#include <aasdk/Channel/MediaSink/Video/IVideoMediaSinkServiceEventHandler.hpp>
#include <aasdk/Channel/MediaSink/Video/Channel/VideoChannel.hpp>
#include <aasdk/Channel/MediaSink/Audio/AudioMediaSinkService.hpp>
#include <aasdk/Channel/MediaSink/Audio/IAudioMediaSinkServiceEventHandler.hpp>
#include <aasdk/Channel/MediaSink/Audio/Channel/MediaAudioChannel.hpp>
#include <aasdk/Channel/MediaSink/Audio/Channel/GuidanceAudioChannel.hpp>
#include <aasdk/Channel/MediaSink/Audio/Channel/SystemAudioChannel.hpp>
#include <aasdk/Channel/MediaSource/MediaSourceService.hpp>
#include <aasdk/Channel/MediaSource/IMediaSourceServiceEventHandler.hpp>
#include <aasdk/Channel/SensorSource/SensorSourceService.hpp>
#include <aasdk/Channel/SensorSource/ISensorSourceServiceEventHandler.hpp>
#include <aasdk/Channel/InputSource/InputSourceService.hpp>
#include <aasdk/Channel/InputSource/IInputSourceServiceEventHandler.hpp>
#include <aasdk/Channel/MediaSource/MediaSourceService.hpp>
#include <aasdk/Channel/MediaSource/IMediaSourceServiceEventHandler.hpp>
#include <aasdk/Channel/MediaSource/Audio/MicrophoneAudioChannel.hpp>
#include <aasdk/Channel/Bluetooth/BluetoothService.hpp>
#include <aasdk/Channel/Bluetooth/IBluetoothServiceEventHandler.hpp>
#include <aasdk/Channel/NavigationStatus/NavigationStatusService.hpp>
#include <aasdk/Channel/NavigationStatus/INavigationStatusServiceEventHandler.hpp>
#include <aasdk/Channel/MediaPlaybackStatus/MediaPlaybackStatusService.hpp>
#include <aasdk/Channel/MediaPlaybackStatus/IMediaPlaybackStatusServiceEventHandler.hpp>
#include <aasdk/Channel/PhoneStatus/PhoneStatusService.hpp>
#include <aasdk/Channel/PhoneStatus/IPhoneStatusServiceEventHandler.hpp>

namespace openautolink::jni {

class JniTransport;
class JniAudioSinkHandler;
class JniSensorHandler;
class JniInputHandler;
class JniNavStatusHandler;
class JniMicHandler;
class JniMediaStatusHandler;
class JniPhoneStatusHandler;
class JniBluetoothHandler;

/**
 * Owns the aasdk session lifecycle. Implements control + video handler
 * interfaces directly. All other channels use separate handler classes
 * (JniAudioSinkHandler, JniSensorHandler, etc.) that call dispatch
 * methods on this class for JNI callbacks to Kotlin.
 *
 * Lifecycle:
 *   create() → start(transport_pipe) → [streaming] → stop() → destroy()
 */
class JniSession
    : public aasdk::channel::control::IControlServiceChannelEventHandler
    , public aasdk::channel::mediasink::video::IVideoMediaSinkServiceEventHandler
    , public std::enable_shared_from_this<JniSession>
{
public:
    explicit JniSession(JavaVM* jvm);
    ~JniSession();

    /**
     * Start AA session with a connected transport pipe.
     * @param env JNI environment
     * @param transportPipe Kotlin AasdkTransportPipe (has readBytes/writeBytes)
     * @param callback Kotlin AasdkSessionCallback (receives events)
     * @param sdrConfig Kotlin AasdkSdrConfig (service discovery params)
     */
    void start(JNIEnv* env, jobject transportPipe, jobject callback, jobject sdrConfig);

    /** Stop the session gracefully (sends ByeBye). */
    void stop();

    /** Send touch event to phone. */
    void sendTouchEvent(int action, int pointerId, float x, float y, int pointerCount);

    /** Send multi-touch event to phone (all pointers in one InputReport). */
    void sendMultiTouchEvent(int action, int actionIndex,
        const int* ids, const float* xs, const float* ys, int count);

    /** Send key event to phone. */
    void sendKeyEvent(int keyCode, bool isDown);

    /** Send GPS location to phone. */
    void sendGpsLocation(double lat, double lon, double alt,
                         float speed, float bearing, long long timestampMs);

    /** Send vehicle sensor data to phone. */
    void sendVehicleSensor(int sensorType, const uint8_t* data, size_t length);

    /** Send microphone audio data to phone. */
    void sendMicAudio(const uint8_t* data, size_t length);

    /** Request a video keyframe (IDR). */
    void requestKeyframe();

    // Typed vehicle sensor methods — each builds a SensorBatch and sends
    void sendSpeedSensor(int speedMmPerS);
    void sendGearSensor(int gear);
    void sendParkingBrakeSensor(bool engaged);
    void sendNightModeSensor(bool night);
    void sendDrivingStatusSensor(bool moving);
    void sendFuelSensor(int levelPct, int rangeM, bool lowFuel);
    void sendEnergyModelSensor(int batteryLevelWh, int batteryCapacityWh, int rangeM, int chargeRateW,
        float drivingWhPerKm = -1.0f, float auxWhPerKm = -1.0f, float aeroCoef = -1.0f,
        float reservePct = -1.0f, int maxChargeW = -1, int maxDischargeW = -1);
    void sendAccelerometerSensor(int xE3, int yE3, int zE3);
    void sendGyroscopeSensor(int rxE3, int ryE3, int rzE3);
    void sendCompassSensor(int bearingE6, int pitchE6, int rollE6);
    void sendRpmSensor(int rpmE3);

    /** Is session actively streaming? */
    bool isStreaming() const { return streaming_; }

    /** Send unsolicited AUDIO_FOCUS_STATE_GAIN to phone. */
    void sendUnsolicitedAudioFocusGain();

    // ---- IControlServiceChannelEventHandler ----
    void onVersionResponse(uint16_t majorCode, uint16_t minorCode,
                           aap_protobuf::shared::MessageStatus status) override;
    void onHandshake(const aasdk::common::DataConstBuffer& payload) override;
    void onServiceDiscoveryRequest(
        const aap_protobuf::service::control::message::ServiceDiscoveryRequest& request) override;
    void onAudioFocusRequest(
        const aap_protobuf::service::control::message::AudioFocusRequest& request) override;
    void onByeByeRequest(
        const aap_protobuf::service::control::message::ByeByeRequest& request) override;
    void onByeByeResponse(
        const aap_protobuf::service::control::message::ByeByeResponse& response) override;
    void onBatteryStatusNotification(
        const aap_protobuf::service::control::message::BatteryStatusNotification& notification) override;
    void onNavigationFocusRequest(
        const aap_protobuf::service::control::message::NavFocusRequestNotification& request) override;
    void onVoiceSessionRequest(
        const aap_protobuf::service::control::message::VoiceSessionNotification& request) override;
    void onPingRequest(
        const aap_protobuf::service::control::message::PingRequest& request) override;
    void onPingResponse(
        const aap_protobuf::service::control::message::PingResponse& response) override;
    void onChannelError(const aasdk::error::Error& e) override;

    // ---- IVideoMediaSinkServiceEventHandler ----
    void onChannelOpenRequest(
        const aap_protobuf::service::control::message::ChannelOpenRequest& request) override;
    void onMediaChannelSetupRequest(
        const aap_protobuf::service::media::shared::message::Setup& request) override;
    void onMediaChannelStartIndication(
        const aap_protobuf::service::media::shared::message::Start& indication) override;
    void onMediaChannelStopIndication(
        const aap_protobuf::service::media::shared::message::Stop& indication) override;
    void onMediaWithTimestampIndication(
        aasdk::messenger::Timestamp::ValueType timestamp,
        const aasdk::common::DataConstBuffer& buffer) override;
    void onMediaIndication(const aasdk::common::DataConstBuffer& buffer) override;
    void onVideoFocusRequest(
        const aap_protobuf::service::media::video::message::VideoFocusRequestNotification& request) override;

    // ---- Dispatch methods (called by handler classes → JNI) ----
    void dispatchAudioFrame(const uint8_t* data, size_t size, int purpose, int sampleRate, int channels);
    void dispatchMicRequest(bool open);
    void dispatchNavStatus(int status);
    void dispatchNavTurn(const std::string& maneuver, const std::string& road,
                         const uint8_t* iconData, size_t iconSize);
    void dispatchNavDistance(int distanceMeters, int etaSeconds,
                            const std::string& displayDistance, const std::string& displayUnit);
    void dispatchNavFullState(const std::string& maneuver, const std::string& road,
                              const uint8_t* iconData, size_t iconSize,
                              int distanceMeters, int etaSeconds,
                              const std::string& displayDistance, const std::string& displayUnit,
                              const std::string& lanes, const std::string& cue,
                              int roundaboutExitNumber,
                              const std::string& currentRoad, const std::string& destination,
                              const std::string& etaFormatted, long long timeToArrivalSeconds,
                              int destDistanceMeters, const std::string& destDistDisplay,
                              const std::string& destDistUnit);
    void dispatchMediaMetadata(const std::string& title, const std::string& artist,
                               const std::string& album, const uint8_t* artData, size_t artSize);
    void dispatchMediaPlayback(int state, long long positionMs);
    void dispatchPhoneStatus(int signalStrength, int callState);

private:
    void ioServiceThreadFunc();
    void buildServiceDiscoveryResponse(
        aap_protobuf::service::control::message::ServiceDiscoveryResponse& response);
    void startAllHandlers();

    // JNI helpers
    JNIEnv* getEnv(bool& attached);
    void releaseEnv(bool attached);
    void callVoidCallback(jmethodID method);

    // Route a native diagnostic line into the Kotlin DiagnosticLog (the triaged
    // log that gets uploaded). level: 0=d 1=i 2=w 3=e. Safe to call from the io
    // thread; no-op if the callback isn't wired yet.
    void nativeDiag(int level, const char* tag, const std::string& msg);

    JavaVM* jvm_;
    jobject callbackRef_ = nullptr;

    // Boost.Asio event loop
    std::unique_ptr<boost::asio::io_service> ioService_;
    std::unique_ptr<boost::asio::io_service::work> ioWork_;
    std::thread ioThread_;
    std::unique_ptr<boost::asio::io_service::strand> strand_;

    // aasdk pipeline
    std::shared_ptr<JniTransport> transport_;
    aasdk::messenger::ICryptor::Pointer cryptor_;
    aasdk::transport::ITransport::Pointer rawTransport_;
    aasdk::messenger::IMessenger::Pointer messenger_;

    // Channels
    std::shared_ptr<aasdk::channel::control::ControlServiceChannel> controlChannel_;
    std::shared_ptr<aasdk::channel::mediasink::video::VideoMediaSinkService> videoChannel_;
    std::shared_ptr<aasdk::channel::mediasink::audio::AudioMediaSinkService> mediaAudioChannel_;
    std::shared_ptr<aasdk::channel::mediasink::audio::AudioMediaSinkService> guidanceAudioChannel_;
    std::shared_ptr<aasdk::channel::mediasink::audio::AudioMediaSinkService> systemAudioChannel_;
    std::shared_ptr<aasdk::channel::inputsource::InputSourceService> inputChannel_;
    std::shared_ptr<aasdk::channel::sensorsource::SensorSourceService> sensorChannel_;
    std::shared_ptr<aasdk::channel::navigationstatus::NavigationStatusService> navChannel_;
    std::shared_ptr<aasdk::channel::mediasource::MediaSourceService> micChannel_;
    std::shared_ptr<aasdk::channel::mediaplaybackstatus::MediaPlaybackStatusService> mediaStatusChannel_;
    std::shared_ptr<aasdk::channel::phonestatus::PhoneStatusService> phoneStatusChannel_;
    std::shared_ptr<aasdk::channel::bluetooth::BluetoothService> bluetoothChannel_;

    // Separate handler instances (one per channel type)
    std::shared_ptr<JniAudioSinkHandler> mediaAudioHandler_;
    std::shared_ptr<JniAudioSinkHandler> guidanceAudioHandler_;
    std::shared_ptr<JniAudioSinkHandler> systemAudioHandler_;
    std::shared_ptr<JniSensorHandler> sensorHandler_;
    std::shared_ptr<JniInputHandler> inputHandler_;
    std::shared_ptr<JniNavStatusHandler> navHandler_;
    std::shared_ptr<JniMicHandler> micHandler_;
    std::shared_ptr<JniMediaStatusHandler> mediaStatusHandler_;
    std::shared_ptr<JniPhoneStatusHandler> phoneStatusHandler_;
    std::shared_ptr<JniBluetoothHandler> bluetoothHandler_;

    std::atomic<bool> stopped_{false};
    std::atomic<bool> streaming_{false};
    std::atomic<bool> micOpen_{false};
    std::atomic<bool> pingOutstanding_{false};
    std::atomic<bool> aborted_{false};
    std::atomic<bool> sessionStoppedFired_{false};
    // Reason string passed to Kotlin onSessionStopped. Defaults to "stopped";
    // ByeByeRequest from phone overrides this (e.g. "byebye_user_selection" when
    // the user taps the Exit button in the AA app launcher) so Kotlin can decide
    // whether to suppress auto-reconnect.
    std::string stopReason_{"stopped"};
    std::atomic<int> negotiatedCodecType_{0};
    // Current video focus state for the main display.
    // 1 = VIDEO_FOCUS_PROJECTED (default — we always project on AAOS).
    // Tracking this lets onVideoFocusRequest reply with our actual current
    // state (mirroring GM's GALDisplayManager.onVideoFocusRequest), so we
    // stay correct if/when we ever surrender focus (e.g. cluster work, or
    // a future "back to AAOS Home while AA stays alive" feature).
    std::atomic<int> currentVideoFocus_{1};
    // Number of video configs we advertised in our SDR. Used by the video-
    // setup handler to populate configuration_indices honestly. Set during
    // buildServiceDiscoveryResponse(); read on the IO strand only after.
    std::atomic<int> advertisedVideoConfigCount_{0};
    std::atomic<int64_t> pingSentAtMs_{0};

    // Channel-error coalescing + escalation tracking (guarded by errorMu_)
    std::mutex errorMu_;
    int64_t firstErrorAtMs_ = 0;
    int errorCount_ = 0;
    std::map<std::string, int64_t> lastLogPerChannel_;

    void sendPing();
    void schedulePing();

public:
    /**
     * Centralized channel-error reporting. Called by per-channel handlers.
     * Coalesces log spam and escalates to triggerAbort() when many errors
     * fire in a short window (indicates remote disconnect / dead transport).
     */
    void reportChannelError(const char* channelName, const aasdk::error::Error& e);

    /**
     * Force-stop the session asynchronously without joining ioThread (safe
     * to call from io thread). Notifies Kotlin via onSessionStopped, which
     * triggers the auto-reconnect path. Idempotent.
     */
    void triggerAbort(const std::string& reason);

private:
    static int64_t nowMs();

    // SDR config (from Kotlin)
    struct SdrConfig {
        int videoWidth = 1920;
        int videoHeight = 1080;
        int videoFps = 60;
        int videoDpi = 160;
        int marginWidth = 0;
        int marginHeight = 0;
        int pixelAspectE4 = 0;
        std::string btMac;
        std::string vehicleMake;
        std::string vehicleModel;
        std::string vehicleYear;
        int driverPosition = 0;
        bool hideClock = false;
        bool hideSignal = false;
        bool hideBattery = false;
        bool autoNegotiate = true;
        std::string videoCodec = "h265";
        int realDensity = 0;
        int safeAreaTop = 0;
        int safeAreaBottom = 0;
        int safeAreaLeft = 0;
        int safeAreaRight = 0;
        int targetLayoutWidthDp = 0;
        std::vector<int> fuelTypes;
        std::vector<int> evConnectorTypes;
        int viewingDistanceMm = 0;
        int decoderAdditionalDepth = 0;
        // Panel rect in pixels — used to (a) auto-pick landscape vs portrait
        // codec tiers when auto-negotiating, and (b) compute per-tier
        // width/height margins so the inner content rect matches panel AR.
        // 0 = unknown (skip auto-margin; fall back to user override).
        int panelWidth = 0;
        int panelHeight = 0;
        // When true, ignore marginWidth/marginHeight and auto-compute from
        // panel AR. When false, send the literal user-set values.
        bool autoMargins = true;
    };
    SdrConfig sdrConfig_;

    // Cached JNI method IDs for the callback object
    struct CallbackMethods {
        jmethodID onSessionStarted = nullptr;
        jmethodID onSessionStopped = nullptr;
        jmethodID onVideoFrame = nullptr;
        jmethodID onVideoCodecConfigured = nullptr;
        jmethodID onAudioFrame = nullptr;
        jmethodID onMicRequest = nullptr;
        jmethodID onNavigationStatus = nullptr;
        jmethodID onNavigationTurn = nullptr;
        jmethodID onNavigationDistance = nullptr;
        jmethodID onNavigationFullState = nullptr;
        jmethodID onMediaMetadata = nullptr;
        jmethodID onMediaPlayback = nullptr;
        jmethodID onPhoneStatus = nullptr;
        jmethodID onPhoneBattery = nullptr;
        jmethodID onVoiceSession = nullptr;
        jmethodID onAudioFocusRequest = nullptr;
        jmethodID onError = nullptr;
        jmethodID onNativeLog = nullptr;
    };
    CallbackMethods cbMethods_;
};

} // namespace openautolink::jni
