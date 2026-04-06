#pragma once

#ifdef PI_AA_ENABLE_AASDK_LIVE

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <boost/asio.hpp>
#include <aasdk/Channel/MediaSink/Video/IVideoMediaSinkServiceEventHandler.hpp>
#include <aasdk/Channel/MediaSink/Video/Channel/VideoChannel.hpp>
#include <aasdk/Channel/MediaSink/Audio/IAudioMediaSinkServiceEventHandler.hpp>
#include <aasdk/Channel/MediaSink/Audio/Channel/MediaAudioChannel.hpp>
#include <aasdk/Channel/MediaSink/Audio/Channel/SystemAudioChannel.hpp>
#include <aasdk/Channel/MediaSink/Audio/Channel/GuidanceAudioChannel.hpp>
#include <aasdk/Channel/MediaSource/IMediaSourceServiceEventHandler.hpp>
#include <aasdk/Channel/MediaSource/MediaSourceService.hpp>
#include <aasdk/Channel/Bluetooth/BluetoothService.hpp>
#include <aasdk/Channel/Bluetooth/IBluetoothServiceEventHandler.hpp>
#include <aasdk/Channel/Control/ControlServiceChannel.hpp>
#include <aasdk/Channel/Control/IControlServiceChannelEventHandler.hpp>
#include <aasdk/Channel/InputSource/IInputSourceServiceEventHandler.hpp>
#include <aasdk/Channel/InputSource/InputSourceService.hpp>
#include <aasdk/Channel/SensorSource/ISensorSourceServiceEventHandler.hpp>
#include <aasdk/Channel/SensorSource/SensorSourceService.hpp>
#include <aasdk/Channel/NavigationStatus/INavigationStatusServiceEventHandler.hpp>
#include <aasdk/Channel/NavigationStatus/NavigationStatusService.hpp>
#include <aasdk/Channel/MediaPlaybackStatus/IMediaPlaybackStatusServiceEventHandler.hpp>
#include <aasdk/Channel/MediaPlaybackStatus/MediaPlaybackStatusService.hpp>
#include <aasdk/Channel/PhoneStatus/IPhoneStatusServiceEventHandler.hpp>
#include <aasdk/Channel/PhoneStatus/PhoneStatusService.hpp>
#include <aasdk/Messenger/ICryptor.hpp>
#include <aasdk/Messenger/IMessenger.hpp>
#include <aasdk/Transport/ITransport.hpp>
#include <aasdk/TCP/TCPWrapper.hpp>
#include <aasdk/USB/USBHub.hpp>
#include <aasdk/USB/USBWrapper.hpp>
#include <aasdk/USB/AccessoryModeQueryFactory.hpp>
#include <aasdk/USB/AccessoryModeQueryChainFactory.hpp>

#include "openautolink/session.hpp"
#include "openautolink/headless_config.hpp"

namespace openautolink {

// Thread-safe output sink wrapper.
class ThreadSafeOutputSink {
public:
    explicit ThreadSafeOutputSink(OutputSink inner);
    void emit(const std::string& payload);

private:
    OutputSink inner_;
    std::mutex mutex_;
};

// Standalone struct for multi-touch pointer data.
// Defined here (not nested in HeadlessInputHandler) so it's available
// before HeadlessInputHandler is fully defined.
struct PointerInfo { uint32_t x; uint32_t y; uint32_t id; };

// Forward declarations for service handler classes.
class HeadlessVideoHandler;
class HeadlessAudioHandler;
class HeadlessAudioInputHandler;
class HeadlessSensorHandler;
class HeadlessInputHandler;
class HeadlessBluetoothHandler;
class HeadlessNavStatusHandler;
class HeadlessMediaStatusHandler;
class HeadlessPhoneStatusHandler;

// The core entity that orchestrates the aasdk control channel.
class HeadlessAutoEntity
    : public aasdk::channel::control::IControlServiceChannelEventHandler
    , public std::enable_shared_from_this<HeadlessAutoEntity>
{
public:
    HeadlessAutoEntity(boost::asio::io_service& io_service,
                       aasdk::messenger::ICryptor::Pointer cryptor,
                       aasdk::transport::ITransport::Pointer transport,
                       aasdk::messenger::IMessenger::Pointer messenger,
                       ThreadSafeOutputSink& output,
                       const HeadlessConfig& config);

    void start();
    void stop();

    std::shared_ptr<HeadlessVideoHandler> video_handler() const { return video_handler_; }
    std::shared_ptr<HeadlessAudioHandler> media_audio_handler() const { return media_audio_handler_; }
    std::shared_ptr<HeadlessInputHandler> input_handler() const { return input_handler_; }
    std::shared_ptr<HeadlessSensorHandler> sensor_handler() const { return sensor_handler_; }
    std::shared_ptr<HeadlessAudioInputHandler> audio_input_handler() const { return audio_input_handler_; }

    bool is_active() const { return active_; }

    // P6: Send call availability status to phone
    void sendCallAvailability(bool available);

    using DisconnectCallback = std::function<void()>;
    void set_disconnect_callback(DisconnectCallback cb) { disconnect_cb_ = std::move(cb); }

private:
    // IControlServiceChannelEventHandler
    void onVersionResponse(uint16_t major, uint16_t minor,
                           aap_protobuf::shared::MessageStatus status) override;
    void onHandshake(const aasdk::common::DataConstBuffer& payload) override;
    void onServiceDiscoveryRequest(const aap_protobuf::service::control::message::ServiceDiscoveryRequest& request) override;
    void onAudioFocusRequest(const aap_protobuf::service::control::message::AudioFocusRequest& request) override;
    void onByeByeRequest(const aap_protobuf::service::control::message::ByeByeRequest& request) override;
    void onByeByeResponse(const aap_protobuf::service::control::message::ByeByeResponse& response) override;
    void onNavigationFocusRequest(const aap_protobuf::service::control::message::NavFocusRequestNotification& request) override;
    void onBatteryStatusNotification(const aap_protobuf::service::control::message::BatteryStatusNotification& notification) override;
    void onVoiceSessionRequest(const aap_protobuf::service::control::message::VoiceSessionNotification& request) override;
    void onPingRequest(const aap_protobuf::service::control::message::PingRequest& request) override;
    void onPingResponse(const aap_protobuf::service::control::message::PingResponse& response) override;
    void onChannelError(const aasdk::error::Error& e) override;

    void triggerQuit();
    void schedulePing();
    void sendPing();
    void emitLifecycle(const std::string& event);
    void emitCommand(int command_id);

    boost::asio::io_service& io_service_;
    boost::asio::io_service::strand strand_;
    aasdk::messenger::ICryptor::Pointer cryptor_;
    aasdk::transport::ITransport::Pointer transport_;
    aasdk::messenger::IMessenger::Pointer messenger_;
    std::shared_ptr<aasdk::channel::control::ControlServiceChannel> control_channel_;

    ThreadSafeOutputSink& output_;
    HeadlessConfig config_;
    bool active_ = false;
    DisconnectCallback disconnect_cb_;
    bool is_tcp_server_ = false;

    // Ping timer
    boost::asio::deadline_timer ping_timer_;
    bool ping_outstanding_ = false;

    // Service handlers
    std::shared_ptr<HeadlessVideoHandler> video_handler_;
    std::shared_ptr<HeadlessAudioHandler> media_audio_handler_;
    std::shared_ptr<HeadlessAudioHandler> speech_audio_handler_;
    std::shared_ptr<HeadlessAudioHandler> system_audio_handler_;
    std::shared_ptr<HeadlessAudioInputHandler> audio_input_handler_;
    std::shared_ptr<HeadlessSensorHandler> sensor_handler_;
    std::shared_ptr<HeadlessInputHandler> input_handler_;
    std::shared_ptr<HeadlessBluetoothHandler> bluetooth_handler_;
    std::shared_ptr<HeadlessNavStatusHandler> nav_status_handler_;
    std::shared_ptr<HeadlessMediaStatusHandler> media_status_handler_;
    std::shared_ptr<HeadlessPhoneStatusHandler> phone_status_handler_;

    // OAL session — propagated to video/audio handlers
    class OalSession* oal_session_ = nullptr;

    // Mutex for binary media pipe (fd 3) writes — shared by video + audio handlers
    std::mutex media_pipe_mutex_;

public:
    // Set OAL session for OAL protocol writes.
    void set_oal_session(class OalSession* session);
};

// Active transport type for dual-mode (wired + wireless)
enum class TransportType { NONE, USB, WIRELESS };

// Session that uses real aasdk transport to talk to a phone.
class LiveAasdkSession final : public IAndroidAutoSession {
public:
    LiveAasdkSession(OutputSink sink, std::string phone_name, HeadlessConfig config);
    ~LiveAasdkSession() override;

    void on_host_command(int command_id) override;
    void on_host_open(const ParsedInputMessage& message) override;
    void on_host_box_settings(const ParsedInputMessage& message) override;
    void on_host_disconnect() override;
    void on_heartbeat() override;
    void on_touch(const ParsedInputMessage& message) override;
    void on_audio_input(const ParsedInputMessage& message) override;
    void on_gnss(const ParsedInputMessage& message) override;
    void on_vehicle_data(const ParsedInputMessage& message) override;
    void replay_cached_keyframe() override;
    const BackendState& state() const override;

    // Set OAL session for OAL protocol video/audio writes.
    void set_oal_session(class OalSession* session) { oal_session_ = session; }

    // Forward touch data from CPC host to the AA input channel.
    void forward_touch(const uint8_t* payload, size_t len);

    // Forward OAL touch (already in AA pixel coordinates).
    void forward_oal_touch(int action, uint32_t x, uint32_t y);
    void forward_oal_multi_touch(int action, uint32_t action_index,
                                  const std::vector<PointerInfo>& pointers);

    // Forward OAL button (key event) to the AA input channel.
    void forward_oal_button(uint32_t keycode, bool down, uint32_t metastate, bool longpress);

    // Forward mic audio from CPC host to the AA audio-input channel.
    void forward_audio_input(const uint8_t* payload, size_t len);

    // Forward OAL mic audio (raw PCM, no CPC header).
    void forward_oal_mic_audio(const uint8_t* pcm, size_t len);

    // Forward vehicle sensor data (JSON) to aasdk SensorBatch.
    void on_vehicle_data(const std::string& json);

    // Forward GNSS NMEA data to aasdk sensor channel.
    void on_vehicle_gnss(const uint8_t* nmea, size_t len);

    // Forward OAL GNSS (NMEA string).
    void forward_oal_gnss(const std::string& nmea);

    // Forward OAL vehicle data (JSON string).
    void forward_oal_vehicle_data(const std::string& json);

    // Request fresh IDR from phone (sends VideoFocusIndication).
    void request_fresh_idr();

    // Restart the AA session with updated config.
    void restart_with_config(const HeadlessConfig& new_config);

    // P6: Notify phone that calls are available/unavailable (BT HFP state)
    void notify_call_availability(bool available);

private:
    void start_dual_mode();
    void start_tcp_server();
    void start_usb_host();
    void start_usb_scanning();
    void accept_connection();
    void do_tcp_wireless_handshake(std::shared_ptr<boost::asio::ip::tcp::socket> socket);
    void create_entity(aasdk::transport::ITransport::Pointer transport);
    void create_entity_no_ssl(aasdk::transport::ITransport::Pointer transport);
    void run_io_thread();

    ThreadSafeOutputSink output_;
    std::string phone_name_;
    HeadlessConfig config_;
    BackendState state_;
    TransportType active_transport_ = TransportType::NONE;

    boost::asio::io_service io_service_;
    std::unique_ptr<boost::asio::io_service::work> io_work_;
    std::thread io_thread_;
    std::unique_ptr<boost::asio::ip::tcp::acceptor> tcp_acceptor_;
    aasdk::tcp::TCPWrapper tcp_wrapper_;

    std::shared_ptr<HeadlessAutoEntity> entity_;
    std::atomic<bool> running_{false};

    // USB host mode — kept alive for continuous device scanning
    std::shared_ptr<aasdk::usb::USBWrapper> usb_wrapper_;
    std::shared_ptr<aasdk::usb::USBHub> usb_hub_;
    std::shared_ptr<aasdk::usb::AccessoryModeQueryFactory> usb_query_factory_;
    std::shared_ptr<aasdk::usb::AccessoryModeQueryChainFactory> usb_query_chain_factory_;
    libusb_context* usb_context_ = nullptr;
    std::thread usb_event_thread_;
    std::chrono::steady_clock::time_point last_frame_request_time_{};

    class OalSession* oal_session_ = nullptr;

    // P3: GNSS parsing state
    bool gnss_first_fix_logged_ = false;
    double last_gps_alt_ = 0.0;

    // Raw SSL for TCP wireless (TLS at socket level, not in aasdk cryptor)
    void* ssl_ = nullptr;      // SSL*
    void* ssl_ctx_ = nullptr;  // SSL_CTX*
};

// ---- Service handler classes ----

class HeadlessVideoHandler
    : public aasdk::channel::mediasink::video::IVideoMediaSinkServiceEventHandler
    , public std::enable_shared_from_this<HeadlessVideoHandler>
{
public:
    HeadlessVideoHandler(boost::asio::io_service& io_service,
                         aasdk::messenger::IMessenger::Pointer messenger,
                         ThreadSafeOutputSink& output,
                         int width, int height, int fps, int dpi,
                         int video_codec,
                         const HeadlessConfig::UiConfigExperiment& ui_experiment,
                         int video_fd = 3,
                         std::mutex* pipe_mutex = nullptr);

    void start();
    void stop();

    // Send a video focus indication to the phone
    void sendVideoFocusIndication();
    // Send a video ack for a received frame
    void sendAck(uint32_t session, uint32_t value);
    // Replay cached SPS/PPS+IDR to CPC session (call when car app connects)
    void replayCachedKeyframe();

    // Send AA UI theme update to phone (dark/light based on car night mode)
    void sendUiThemeUpdate(bool night_mode);
    void sendUiConfigUpdate(const HeadlessConfig::UiInsets& margins,
                            const HeadlessConfig::UiInsets& content_insets,
                            const HeadlessConfig::UiInsets& stable_insets,
                            const std::string& reason);

    // Forward a touch event into the AA input channel (if input handler is set)
    void set_input_handler(std::shared_ptr<HeadlessInputHandler> handler) { input_handler_ = handler; }

    // Set OAL session for OAL protocol video writes.
    void set_oal_session(class OalSession* session) { oal_session_ = session; }

private:
    void onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest& request) override;
    void onMediaChannelSetupRequest(const aap_protobuf::service::media::shared::message::Setup& request) override;
    void onMediaChannelStartIndication(const aap_protobuf::service::media::shared::message::Start& indication) override;
    void onMediaChannelStopIndication(const aap_protobuf::service::media::shared::message::Stop& indication) override;
    void onMediaWithTimestampIndication(aasdk::messenger::Timestamp::ValueType timestamp,
                                        const aasdk::common::DataConstBuffer& buffer) override;
    void onMediaIndication(const aasdk::common::DataConstBuffer& buffer) override;
    void onVideoFocusRequest(const aap_protobuf::service::media::video::message::VideoFocusRequestNotification& request) override;
    void onChannelError(const aasdk::error::Error& e) override;

    boost::asio::io_service::strand strand_;
    boost::asio::io_service& io_service_;
    std::shared_ptr<aasdk::channel::mediasink::video::channel::VideoChannel> channel_;
    ThreadSafeOutputSink& output_;
    int width_, height_, fps_, dpi_;
    int video_codec_;  // 3=H264, 5=VP9, 6=AV1, 7=H265
    HeadlessConfig::UiConfigExperiment ui_experiment_;
    int video_fd_;
    std::mutex* pipe_mutex_;
    int32_t session_ = -1;
    uint32_t frame_counter_ = 0;
    std::shared_ptr<HeadlessInputHandler> input_handler_;
    class OalSession* oal_session_ = nullptr;
    // Cached SPS/PPS + first IDR for replay when car app connects late
    std::vector<uint8_t> cached_sps_pps_;
    // Track last night mode sent to avoid redundant updates
    int last_night_mode_sent_ = -1;  // -1 = never sent
    std::shared_ptr<boost::asio::deadline_timer> runtime_ui_timer_;
    bool runtime_ui_config_sent_ = false;
    std::vector<uint8_t> cached_idr_;
    bool has_cached_keyframe_ = false;
};

class HeadlessAudioHandler
    : public aasdk::channel::mediasink::audio::IAudioMediaSinkServiceEventHandler
    , public std::enable_shared_from_this<HeadlessAudioHandler>
{
public:
    enum class ChannelType { Media, Speech, System };

    HeadlessAudioHandler(boost::asio::io_service& io_service,
                         aasdk::messenger::IMessenger::Pointer messenger,
                         ThreadSafeOutputSink& output,
                         ChannelType type,
                         int media_fd = 3,
                         std::mutex* pipe_mutex = nullptr);

    void start();
    void stop();

    // Set OAL session for OAL protocol audio writes.
    void set_oal_session(class OalSession* session) { oal_session_ = session; }

private:
    void onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest& request) override;
    void onMediaChannelSetupRequest(const aap_protobuf::service::media::shared::message::Setup& request) override;
    void onMediaChannelStartIndication(const aap_protobuf::service::media::shared::message::Start& indication) override;
    void onMediaChannelStopIndication(const aap_protobuf::service::media::shared::message::Stop& indication) override;
    void onMediaWithTimestampIndication(aasdk::messenger::Timestamp::ValueType timestamp,
                                        const aasdk::common::DataConstBuffer& buffer) override;
    void onMediaIndication(const aasdk::common::DataConstBuffer& buffer) override;
    void onChannelError(const aasdk::error::Error& e) override;

    boost::asio::io_service::strand strand_;
    std::shared_ptr<aasdk::channel::mediasink::audio::AudioMediaSinkService> channel_;
    ThreadSafeOutputSink& output_;
    ChannelType type_;
    int media_fd_;
    std::mutex* pipe_mutex_;
    int32_t session_ = -1;
    uint64_t audio_frame_count_ = 0;
    class OalSession* oal_session_ = nullptr;
};

class HeadlessAudioInputHandler
    : public aasdk::channel::mediasource::IMediaSourceServiceEventHandler
    , public std::enable_shared_from_this<HeadlessAudioInputHandler>
{
public:
    HeadlessAudioInputHandler(boost::asio::io_service& io_service,
                              aasdk::messenger::IMessenger::Pointer messenger,
                              ThreadSafeOutputSink& output);

    void start();
    void stop();

    // Feed microphone audio from host into the AA channel.
    void feedAudio(const uint8_t* data, size_t size);

    // Set OAL session for mic_start/mic_stop signals.
    void set_oal_session(class OalSession* session) { oal_session_ = session; }

private:
    void onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest& request) override;
    void onMediaChannelSetupRequest(const aap_protobuf::service::media::shared::message::Setup& request) override;
    void onMediaSourceOpenRequest(const aap_protobuf::service::media::source::message::MicrophoneRequest& request) override;
    void onMediaChannelAckIndication(const aap_protobuf::service::media::source::message::Ack& indication) override;
    void onChannelError(const aasdk::error::Error& e) override;

    boost::asio::io_service::strand strand_;
    std::shared_ptr<aasdk::channel::mediasource::MediaSourceService> channel_;
    ThreadSafeOutputSink& output_;
    bool open_ = false;
    uint32_t session_ = 0;
    class OalSession* oal_session_ = nullptr;
};

class HeadlessSensorHandler
    : public aasdk::channel::sensorsource::ISensorSourceServiceEventHandler
    , public std::enable_shared_from_this<HeadlessSensorHandler>
{
public:
    HeadlessSensorHandler(boost::asio::io_service& io_service,
                          aasdk::messenger::IMessenger::Pointer messenger,
                          ThreadSafeOutputSink& output);

    void start();
    void stop();

    // Feed GNSS/night-mode/driving-status data from host.
    void sendNightMode(bool night);
    void sendDrivingStatus(bool moving);
    void sendGpsLocation(double lat, double lon, double alt, float speed, float bearing, uint64_t timestamp_ms);

    // Feed vehicle data from AAOS VHAL
    void sendSpeed(int speed_mm_per_s);
    void sendGear(int gear);
    void sendParkingBrake(bool engaged);
    void sendFuel(int fuel_level_pct, int range_m, bool low_fuel);
    void sendOdometer(int km_e1);
    void sendEnvironment(int temp_e3);
    void sendDoor(bool hood, bool trunk, const std::vector<bool>& doors);
    void sendLight(int headlight, int turn_indicator, bool hazard);
    void sendTirePressure(const std::vector<int>& pressures_e2);
    void sendHvac(int target_temp_e3, int current_temp_e3);

    // P5: IMU sensors
    void sendAccelerometer(int x_e3, int y_e3, int z_e3);
    void sendGyroscope(int rx_e3, int ry_e3, int rz_e3);
    void sendCompass(int bearing_e6, int pitch_e6, int roll_e6);
    void sendGpsSatellites(int in_use, int in_view);

    // P6: RPM
    void sendRpm(int rpm_e3);

private:
    void onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest& request) override;
    void onSensorStartRequest(const aap_protobuf::service::sensorsource::message::SensorRequest& request) override;
    void onChannelError(const aasdk::error::Error& e) override;

    boost::asio::io_service::strand strand_;
    std::shared_ptr<aasdk::channel::sensorsource::SensorSourceService> channel_;
    ThreadSafeOutputSink& output_;
};

class HeadlessInputHandler
    : public aasdk::channel::inputsource::IInputSourceServiceEventHandler
    , public std::enable_shared_from_this<HeadlessInputHandler>
{
public:
    HeadlessInputHandler(boost::asio::io_service& io_service,
                         aasdk::messenger::IMessenger::Pointer messenger,
                         ThreadSafeOutputSink& output,
                         int display_width, int display_height);

    void start();
    void stop();

    // Send a touch event from host to phone.
    void sendTouchEvent(uint32_t action, uint32_t x, uint32_t y);

    using PointerInfo = openautolink::PointerInfo;
    void sendMultiTouchEvent(uint32_t action, uint32_t action_index,
                             const std::vector<PointerInfo>& pointers);

    // Send a key event (button press) from host to phone.
    void sendKeyEvent(uint32_t keycode, bool down, uint32_t metastate, bool longpress);

private:
    void onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest& request) override;
    void onKeyBindingRequest(const aap_protobuf::service::media::sink::message::KeyBindingRequest& request) override;
    void onChannelError(const aasdk::error::Error& e) override;

    boost::asio::io_service::strand strand_;
    std::shared_ptr<aasdk::channel::inputsource::InputSourceService> channel_;
    ThreadSafeOutputSink& output_;
    int display_width_, display_height_;
};

class HeadlessBluetoothHandler
    : public aasdk::channel::bluetooth::IBluetoothServiceEventHandler
    , public std::enable_shared_from_this<HeadlessBluetoothHandler>
{
public:
    HeadlessBluetoothHandler(boost::asio::io_service& io_service,
                             aasdk::messenger::IMessenger::Pointer messenger,
                             ThreadSafeOutputSink& output);

    void start();
    void stop();

private:
    void onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest& request) override;
    void onBluetoothPairingRequest(const aap_protobuf::service::bluetooth::message::BluetoothPairingRequest& request) override;
    void onBluetoothAuthenticationResult(const aap_protobuf::service::bluetooth::message::BluetoothAuthenticationResult& result) override;
    void onChannelError(const aasdk::error::Error& e) override;

    boost::asio::io_service::strand strand_;
    std::shared_ptr<aasdk::channel::bluetooth::BluetoothService> channel_;
    ThreadSafeOutputSink& output_;
    bool bt_connect_attempted_ = false;
};

class HeadlessNavStatusHandler
    : public aasdk::channel::navigationstatus::INavigationStatusServiceEventHandler
    , public std::enable_shared_from_this<HeadlessNavStatusHandler>
{
public:
    HeadlessNavStatusHandler(boost::asio::io_service& io_service,
                             aasdk::messenger::IMessenger::Pointer messenger,
                             ThreadSafeOutputSink& output);

    void start();
    void stop();

    void set_oal_session(class OalSession* session) { oal_session_ = session; }

private:
    void onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest& request) override;
    void onChannelError(const aasdk::error::Error& e) override;
    void onStatusUpdate(const aap_protobuf::service::navigationstatus::message::NavigationStatus& navStatus) override;
    void onTurnEvent(const aap_protobuf::service::navigationstatus::message::NavigationNextTurnEvent& turnEvent) override;
    void onDistanceEvent(const aap_protobuf::service::navigationstatus::message::NavigationNextTurnDistanceEvent& distanceEvent) override;
    void onNavigationState(const aap_protobuf::service::navigationstatus::message::NavigationState& navState) override;
    void onCurrentPosition(const aap_protobuf::service::navigationstatus::message::NavigationCurrentPosition& position) override;

    boost::asio::io_service::strand strand_;
    std::shared_ptr<aasdk::channel::navigationstatus::NavigationStatusService> channel_;
    ThreadSafeOutputSink& output_;
    class OalSession* oal_session_ = nullptr;

    // Cached nav state for composing full updates
    std::string last_road_;
    std::string last_maneuver_;
    std::string last_nav_image_base64_;
    int last_distance_m_ = 0;
    int last_eta_s_ = 0;
    bool has_modern_nav_ = false;  // true once we receive msg 32774
};

class HeadlessMediaStatusHandler
    : public aasdk::channel::mediaplaybackstatus::IMediaPlaybackStatusServiceEventHandler
    , public std::enable_shared_from_this<HeadlessMediaStatusHandler>
{
public:
    HeadlessMediaStatusHandler(boost::asio::io_service& io_service,
                               aasdk::messenger::IMessenger::Pointer messenger,
                               ThreadSafeOutputSink& output);

    void start();
    void stop();

    void set_oal_session(class OalSession* session) { oal_session_ = session; }

private:
    void onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest& request) override;
    void onChannelError(const aasdk::error::Error& e) override;
    void onMetadataUpdate(const aap_protobuf::service::mediaplayback::message::MediaPlaybackMetadata& metadata) override;
    void onPlaybackUpdate(const aap_protobuf::service::mediaplayback::message::MediaPlaybackStatus& playback) override;

    boost::asio::io_service::strand strand_;
    std::shared_ptr<aasdk::channel::mediaplaybackstatus::MediaPlaybackStatusService> channel_;
    ThreadSafeOutputSink& output_;
    class OalSession* oal_session_ = nullptr;

    // Cached metadata for composing full updates
    std::string last_title_;
    std::string last_artist_;
    std::string last_album_;
    std::string last_album_art_base64_;
    int last_duration_ms_ = 0;
    int last_position_ms_ = 0;
    bool last_playing_ = false;
};

class HeadlessPhoneStatusHandler
    : public aasdk::channel::phonestatus::IPhoneStatusServiceEventHandler
    , public std::enable_shared_from_this<HeadlessPhoneStatusHandler>
{
public:
    HeadlessPhoneStatusHandler(boost::asio::io_service& io_service,
                               aasdk::messenger::IMessenger::Pointer messenger,
                               ThreadSafeOutputSink& output);

    void start();
    void stop();

    void set_oal_session(class OalSession* session) { oal_session_ = session; }

private:
    void onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest& request) override;
    void onPhoneStatusUpdate(const aap_protobuf::service::phonestatus::message::PhoneStatus& status) override;
    void onChannelError(const aasdk::error::Error& e) override;

    boost::asio::io_service::strand strand_;
    std::shared_ptr<aasdk::channel::phonestatus::PhoneStatusService> channel_;
    ThreadSafeOutputSink& output_;
    class OalSession* oal_session_ = nullptr;
};

} // namespace openautolink

#endif // PI_AA_ENABLE_AASDK_LIVE
