/*
 * jni_channel_handlers.h — Separate handler classes for each aasdk channel.
 *
 * Follows the same pattern as the bridge (HeadlessAudioHandler, etc.):
 * each handler class inherits one event handler interface + enable_shared_from_this,
 * owns its channel, and delegates events to JniSession for JNI dispatch.
 *
 * This avoids the diamond problem from multiple inheritance of interfaces
 * that share method names (video/audio sinks share 7 identical methods).
 */
#pragma once

#include <memory>
#include <atomic>
#include <jni.h>
#include <boost/asio.hpp>

#include <aasdk/Channel/MediaSink/Audio/IAudioMediaSinkServiceEventHandler.hpp>
#include <aasdk/Channel/MediaSink/Audio/AudioMediaSinkService.hpp>
#include <aasdk/Channel/SensorSource/ISensorSourceServiceEventHandler.hpp>
#include <aasdk/Channel/SensorSource/SensorSourceService.hpp>
#include <aasdk/Channel/InputSource/IInputSourceServiceEventHandler.hpp>
#include <aasdk/Channel/InputSource/InputSourceService.hpp>
#include <aasdk/Channel/NavigationStatus/INavigationStatusServiceEventHandler.hpp>
#include <aasdk/Channel/NavigationStatus/NavigationStatusService.hpp>
#include <aasdk/Channel/MediaSource/IMediaSourceServiceEventHandler.hpp>
#include <aasdk/Channel/MediaSource/MediaSourceService.hpp>
#include <aasdk/Channel/MediaPlaybackStatus/IMediaPlaybackStatusServiceEventHandler.hpp>
#include <aasdk/Channel/MediaPlaybackStatus/MediaPlaybackStatusService.hpp>
#include <aasdk/Channel/PhoneStatus/IPhoneStatusServiceEventHandler.hpp>
#include <aasdk/Channel/PhoneStatus/PhoneStatusService.hpp>
#include <aasdk/Channel/Bluetooth/IBluetoothServiceEventHandler.hpp>
#include <aasdk/Channel/Bluetooth/BluetoothService.hpp>
#include <aasdk/Channel/Promise.hpp>

namespace openautolink::jni {

class JniSession;  // forward

// ============================================================================
// Audio Sink Handler — one instance per audio type (media, guidance, system)
// ============================================================================

class JniAudioSinkHandler
    : public aasdk::channel::mediasink::audio::IAudioMediaSinkServiceEventHandler
    , public std::enable_shared_from_this<JniAudioSinkHandler>
{
public:
    enum class AudioType { Media, Guidance, System };

    JniAudioSinkHandler(boost::asio::io_service::strand& strand,
                        std::shared_ptr<aasdk::channel::mediasink::audio::AudioMediaSinkService> channel,
                        JniSession& session,
                        AudioType type);

    void start();

private:
    void onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest& request) override;
    void onMediaChannelSetupRequest(const aap_protobuf::service::media::shared::message::Setup& request) override;
    void onMediaChannelStartIndication(const aap_protobuf::service::media::shared::message::Start& indication) override;
    void onMediaChannelStopIndication(const aap_protobuf::service::media::shared::message::Stop& indication) override;
    void onMediaWithTimestampIndication(aasdk::messenger::Timestamp::ValueType timestamp,
                                        const aasdk::common::DataConstBuffer& buffer) override;
    void onMediaIndication(const aasdk::common::DataConstBuffer& buffer) override;
    void onChannelError(const aasdk::error::Error& e) override;

    int purposeFromType() const;

    boost::asio::io_service::strand& strand_;
    std::shared_ptr<aasdk::channel::mediasink::audio::AudioMediaSinkService> channel_;
    JniSession& session_;
    AudioType type_;
};

// ============================================================================
// Sensor Source Handler
// ============================================================================

class JniSensorHandler
    : public aasdk::channel::sensorsource::ISensorSourceServiceEventHandler
    , public std::enable_shared_from_this<JniSensorHandler>
{
public:
    JniSensorHandler(boost::asio::io_service::strand& strand,
                     std::shared_ptr<aasdk::channel::sensorsource::SensorSourceService> channel,
                     JniSession& session);

    void start();

private:
    void onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest& request) override;
    void onSensorStartRequest(const aap_protobuf::service::sensorsource::message::SensorRequest& request) override;
    void onChannelError(const aasdk::error::Error& e) override;

    boost::asio::io_service::strand& strand_;
    std::shared_ptr<aasdk::channel::sensorsource::SensorSourceService> channel_;
    JniSession& session_;
};

// ============================================================================
// Input Source Handler
// ============================================================================

class JniInputHandler
    : public aasdk::channel::inputsource::IInputSourceServiceEventHandler
    , public std::enable_shared_from_this<JniInputHandler>
{
public:
    JniInputHandler(boost::asio::io_service::strand& strand,
                    std::shared_ptr<aasdk::channel::inputsource::InputSourceService> channel,
                    JniSession& session);

    void start();

private:
    void onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest& request) override;
    void onKeyBindingRequest(const aap_protobuf::service::media::sink::message::KeyBindingRequest& request) override;
    void onChannelError(const aasdk::error::Error& e) override;

    boost::asio::io_service::strand& strand_;
    std::shared_ptr<aasdk::channel::inputsource::InputSourceService> channel_;
    JniSession& session_;
};

// ============================================================================
// Navigation Status Handler
// ============================================================================

class JniNavStatusHandler
    : public aasdk::channel::navigationstatus::INavigationStatusServiceEventHandler
    , public std::enable_shared_from_this<JniNavStatusHandler>
{
public:
    JniNavStatusHandler(boost::asio::io_service::strand& strand,
                        std::shared_ptr<aasdk::channel::navigationstatus::NavigationStatusService> channel,
                        JniSession& session);

    void start();

private:
    void onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest& request) override;
    void onChannelError(const aasdk::error::Error& e) override;
    void onStatusUpdate(const aap_protobuf::service::navigationstatus::message::NavigationStatus& navStatus) override;
    void onTurnEvent(const aap_protobuf::service::navigationstatus::message::NavigationNextTurnEvent& turnEvent) override;
    void onDistanceEvent(const aap_protobuf::service::navigationstatus::message::NavigationNextTurnDistanceEvent& distanceEvent) override;
    void onNavigationState(const aap_protobuf::service::navigationstatus::message::NavigationState& navState) override;
    void onCurrentPosition(const aap_protobuf::service::navigationstatus::message::NavigationCurrentPosition& position) override;

    boost::asio::io_service::strand& strand_;
    std::shared_ptr<aasdk::channel::navigationstatus::NavigationStatusService> channel_;
    JniSession& session_;
};

// ============================================================================
// Media Source Handler (Microphone)
// ============================================================================

class JniMicHandler
    : public aasdk::channel::mediasource::IMediaSourceServiceEventHandler
    , public std::enable_shared_from_this<JniMicHandler>
{
public:
    JniMicHandler(boost::asio::io_service::strand& strand,
                  std::shared_ptr<aasdk::channel::mediasource::MediaSourceService> channel,
                  JniSession& session);

    void start();

    /** Feed mic audio from Kotlin into the AA channel. */
    void feedAudio(const uint8_t* data, size_t size);

    bool isOpen() const { return open_; }

private:
    void onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest& request) override;
    void onMediaChannelSetupRequest(const aap_protobuf::service::media::shared::message::Setup& request) override;
    void onMediaSourceOpenRequest(const aap_protobuf::service::media::source::message::MicrophoneRequest& request) override;
    void onMediaChannelAckIndication(const aap_protobuf::service::media::source::message::Ack& indication) override;
    void onChannelError(const aasdk::error::Error& e) override;

    boost::asio::io_service::strand& strand_;
    std::shared_ptr<aasdk::channel::mediasource::MediaSourceService> channel_;
    JniSession& session_;
    std::atomic<bool> open_{false};
};

// ============================================================================
// Media Playback Status Handler
// ============================================================================

class JniMediaStatusHandler
    : public aasdk::channel::mediaplaybackstatus::IMediaPlaybackStatusServiceEventHandler
    , public std::enable_shared_from_this<JniMediaStatusHandler>
{
public:
    JniMediaStatusHandler(boost::asio::io_service::strand& strand,
                          std::shared_ptr<aasdk::channel::mediaplaybackstatus::MediaPlaybackStatusService> channel,
                          JniSession& session);

    void start();

private:
    void onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest& request) override;
    void onChannelError(const aasdk::error::Error& e) override;
    void onMetadataUpdate(const aap_protobuf::service::mediaplayback::message::MediaPlaybackMetadata& metadata) override;
    void onPlaybackUpdate(const aap_protobuf::service::mediaplayback::message::MediaPlaybackStatus& playback) override;

    boost::asio::io_service::strand& strand_;
    std::shared_ptr<aasdk::channel::mediaplaybackstatus::MediaPlaybackStatusService> channel_;
    JniSession& session_;
};

// ============================================================================
// Phone Status Handler
// ============================================================================

class JniPhoneStatusHandler
    : public aasdk::channel::phonestatus::IPhoneStatusServiceEventHandler
    , public std::enable_shared_from_this<JniPhoneStatusHandler>
{
public:
    JniPhoneStatusHandler(boost::asio::io_service::strand& strand,
                          std::shared_ptr<aasdk::channel::phonestatus::PhoneStatusService> channel,
                          JniSession& session);

    void start();

private:
    void onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest& request) override;
    void onPhoneStatusUpdate(const aap_protobuf::service::phonestatus::message::PhoneStatus& status) override;
    void onChannelError(const aasdk::error::Error& e) override;

    boost::asio::io_service::strand& strand_;
    std::shared_ptr<aasdk::channel::phonestatus::PhoneStatusService> channel_;
    JniSession& session_;
};

// ============================================================================
// Bluetooth Handler
// ============================================================================

class JniBluetoothHandler
    : public aasdk::channel::bluetooth::IBluetoothServiceEventHandler
    , public std::enable_shared_from_this<JniBluetoothHandler>
{
public:
    JniBluetoothHandler(boost::asio::io_service::strand& strand,
                        std::shared_ptr<aasdk::channel::bluetooth::BluetoothService> channel,
                        JniSession& session);

    void start();

private:
    void onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest& request) override;
    void onBluetoothPairingRequest(const aap_protobuf::service::bluetooth::message::BluetoothPairingRequest& request) override;
    void onBluetoothAuthenticationResult(const aap_protobuf::service::bluetooth::message::BluetoothAuthenticationResult& request) override;
    void onChannelError(const aasdk::error::Error& e) override;

    boost::asio::io_service::strand& strand_;
    std::shared_ptr<aasdk::channel::bluetooth::BluetoothService> channel_;
    JniSession& session_;
};

} // namespace openautolink::jni
