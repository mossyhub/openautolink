/*
 * jni_channel_handlers.cpp Ã¢â‚¬â€ Handler implementations for all aasdk channels.
 *
 * Each handler: receives on its channel, responds to AA protocol messages,
 * and dispatches events to JniSession for JNI callbacks to Kotlin.
 */
#include "jni_channel_handlers.h"
#include "jni_session.h"
#include "jni_log_bridge.h"

#include <android/log.h>
#include <sstream>
#include <cstring>

#include <aap_protobuf/service/control/message/ChannelOpenResponse.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationState.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationStep.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationManeuver.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationRoad.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationLane.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationCue.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationDestination.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationCurrentPosition.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationDistance.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationStepDistance.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationDestinationDistance.pb.h>
#include <aap_protobuf/service/media/shared/message/Config.pb.h>
#include <aap_protobuf/service/media/source/message/Ack.pb.h>
#include <aap_protobuf/service/media/source/message/MicrophoneRequest.pb.h>
#include <aap_protobuf/service/media/source/message/MicrophoneResponse.pb.h>
#include <aap_protobuf/service/sensorsource/message/SensorRequest.pb.h>
#include <aap_protobuf/service/sensorsource/message/SensorStartResponseMessage.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationStatus.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationNextTurnEvent.pb.h>
#include <aap_protobuf/service/navigationstatus/message/NavigationNextTurnDistanceEvent.pb.h>
#include <aap_protobuf/service/mediaplayback/message/MediaPlaybackMetadata.pb.h>
#include <aap_protobuf/service/mediaplayback/message/MediaPlaybackStatus.pb.h>
#include <aap_protobuf/service/phonestatus/message/PhoneStatus.pb.h>
#include <aap_protobuf/service/bluetooth/message/BluetoothPairingRequest.pb.h>
#include <aap_protobuf/service/bluetooth/message/BluetoothPairingResponse.pb.h>
#include <aap_protobuf/service/bluetooth/message/BluetoothAuthenticationData.pb.h>
#include <aap_protobuf/shared/MessageStatus.pb.h>

#define LOG_TAG "OAL-Handlers"
// Route all handler logs through the JNI bridge so they appear in USB file logs
#define LOGI(...) openautolink::jni::oal_jni_log(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) openautolink::jni::oal_jni_log(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) openautolink::jni::oal_jni_log(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Hex-dump helper for raw protobuf bytes (energy forecast investigation)
static std::string hexDump(const uint8_t* data, size_t len, size_t maxBytes = 256) {
    std::ostringstream ss;
    size_t cap = (len < maxBytes) ? len : maxBytes;
    for (size_t i = 0; i < cap; ++i) {
        char buf[4];
        snprintf(buf, sizeof(buf), "%02x", data[i]);
        ss << buf;
        if (i + 1 < cap) ss << ' ';
    }
    if (len > maxBytes) ss << " ...(" << len << " total)";
    return ss.str();
}

// Log raw serialized bytes + unknown fields for any protobuf message.
// Use for protocol investigation — reveals fields our .proto doesn't define.
template<typename T>
static void logProtoRaw(const char* label, const T& msg) {
    std::string raw;
    msg.SerializeToString(&raw);
    if (raw.size() > 0) {
        LOGI("%s RAW (%zu bytes): %s", label, raw.size(),
             hexDump(reinterpret_cast<const uint8_t*>(raw.data()), raw.size(), 512).c_str());
    }
    const auto& uf = msg.unknown_fields();
    if (uf.field_count() > 0) {
        LOGI("%s has %d UNKNOWN fields", label, uf.field_count());
        for (int i = 0; i < uf.field_count(); ++i) {
            const auto& f = uf.field(i);
            switch (f.type()) {
                case google::protobuf::UnknownField::TYPE_VARINT:
                    LOGI("  %s UNKNOWN #%d varint=%llu", label, f.number(), (unsigned long long)f.varint());
                    break;
                case google::protobuf::UnknownField::TYPE_FIXED32: {
                    auto v = f.fixed32();
                    float fv; memcpy(&fv, &v, sizeof(fv));
                    LOGI("  %s UNKNOWN #%d fixed32=%u (float=%f)", label, f.number(), v, fv);
                    break;
                }
                case google::protobuf::UnknownField::TYPE_FIXED64:
                    LOGI("  %s UNKNOWN #%d fixed64=%llu", label, f.number(), (unsigned long long)f.fixed64());
                    break;
                case google::protobuf::UnknownField::TYPE_LENGTH_DELIMITED: {
                    const auto& ld = f.length_delimited();
                    LOGI("  %s UNKNOWN #%d bytes(%zu): %s", label, f.number(), ld.size(),
                         hexDump(reinterpret_cast<const uint8_t*>(ld.data()), ld.size()).c_str());
                    break;
                }
                default:
                    LOGI("  %s UNKNOWN #%d type=%d", label, f.number(), f.type());
                    break;
            }
        }
    }
}

namespace openautolink::jni {

// ============================================================================
// Audio Sink Handler
// ============================================================================

JniAudioSinkHandler::JniAudioSinkHandler(
    boost::asio::io_service::strand& strand,
    std::shared_ptr<aasdk::channel::mediasink::audio::AudioMediaSinkService> channel,
    JniSession& session,
    AudioType type)
    : strand_(strand), channel_(std::move(channel)), session_(session), type_(type)
{
}

void JniAudioSinkHandler::start()
{
    channel_->receive(shared_from_this());
}

int JniAudioSinkHandler::purposeFromType() const
{
    switch (type_) {
        case AudioType::Media:    return 0;
        case AudioType::Guidance: return 1;
        case AudioType::System:   return 2;
    }
    return 0;
}

void JniAudioSinkHandler::onChannelOpenRequest(
    const aap_protobuf::service::control::message::ChannelOpenRequest& request)
{
    LOGI("Audio channel open (type=%d)", static_cast<int>(type_));
    logProtoRaw("AudioChannelOpen", request);
    aap_protobuf::service::control::message::ChannelOpenResponse response;
    response.set_status(aap_protobuf::shared::STATUS_SUCCESS);
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [this](const auto& e) { this->onChannelError(e); });
    channel_->sendChannelOpenResponse(response, std::move(promise));
    channel_->receive(shared_from_this());
}

void JniAudioSinkHandler::onMediaChannelSetupRequest(
    const aap_protobuf::service::media::shared::message::Setup& request)
{
    LOGI("Audio setup (type=%d) codec=%d", static_cast<int>(type_), request.type());
    logProtoRaw("AudioSetup", request);
    aap_protobuf::service::media::shared::message::Config config;
    config.set_status(aap_protobuf::service::media::shared::message::Config::STATUS_READY);
    config.set_max_unacked(30);
    config.add_configuration_indices(0);
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [this](const auto& e) { this->onChannelError(e); });
    channel_->sendChannelSetupResponse(config, std::move(promise));
    channel_->receive(shared_from_this());

    // Send unsolicited audio focus GAIN (headunit-revived does this on every audio setup)
    session_.sendUnsolicitedAudioFocusGain();
}

void JniAudioSinkHandler::onMediaChannelStartIndication(
    const aap_protobuf::service::media::shared::message::Start& indication)
{
    LOGI("Audio start (type=%d) session=%d config_idx=%d", static_cast<int>(type_),
         indication.session_id(), indication.configuration_index());
    logProtoRaw("AudioStart", indication);
    channel_->receive(shared_from_this());
}

void JniAudioSinkHandler::onMediaChannelStopIndication(
    const aap_protobuf::service::media::shared::message::Stop& indication)
{
    LOGI("Audio stop (type=%d)", static_cast<int>(type_));
    logProtoRaw("AudioStop", indication);
    channel_->receive(shared_from_this());
}

void JniAudioSinkHandler::onMediaWithTimestampIndication(
    aasdk::messenger::Timestamp::ValueType /*timestamp*/,
    const aasdk::common::DataConstBuffer& buffer)
{
    // ACK immediately for flow control
    aap_protobuf::service::media::source::message::Ack ack;
    ack.set_session_id(0);
    ack.set_ack(1);
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [](const auto&) {});
    channel_->sendMediaAckIndication(ack, std::move(promise));

    // Dispatch to Kotlin via JniSession
    int purpose = purposeFromType();
    int sampleRate = (type_ == AudioType::Media) ? 48000 : 16000;
    int channels = (type_ == AudioType::Media) ? 2 : 1;
    session_.dispatchAudioFrame(buffer.cdata, buffer.size, purpose, sampleRate, channels);

    channel_->receive(shared_from_this());
}

void JniAudioSinkHandler::onMediaIndication(const aasdk::common::DataConstBuffer& buffer)
{
    onMediaWithTimestampIndication(0, buffer);
}

void JniAudioSinkHandler::onChannelError(const aasdk::error::Error& e)
{
    char name[32];
    snprintf(name, sizeof(name), "audio[%d]", static_cast<int>(type_));
    session_.reportChannelError(name, e);
}

// ============================================================================
// Sensor Source Handler
// ============================================================================

JniSensorHandler::JniSensorHandler(
    boost::asio::io_service::strand& strand,
    std::shared_ptr<aasdk::channel::sensorsource::SensorSourceService> channel,
    JniSession& session)
    : strand_(strand), channel_(std::move(channel)), session_(session)
{
}

void JniSensorHandler::start()
{
    channel_->receive(shared_from_this());
}

void JniSensorHandler::onChannelOpenRequest(
    const aap_protobuf::service::control::message::ChannelOpenRequest& request)
{
    LOGI("Sensor channel open");
    logProtoRaw("SensorChannelOpen", request);
    aap_protobuf::service::control::message::ChannelOpenResponse response;
    response.set_status(aap_protobuf::shared::STATUS_SUCCESS);
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [this](const auto& e) { this->onChannelError(e); });
    channel_->sendChannelOpenResponse(response, std::move(promise));
    channel_->receive(shared_from_this());
}

void JniSensorHandler::onSensorStartRequest(
    const aap_protobuf::service::sensorsource::message::SensorRequest& request)
{
    LOGI("Sensor start request: type=%d", request.type());
    logProtoRaw("SensorStartReq", request);

    // Respond with OK Ã¢â‚¬â€ phone expects acknowledgement before sending sensor polls
    aap_protobuf::service::sensorsource::message::SensorStartResponseMessage response;
    response.set_status(aap_protobuf::shared::STATUS_SUCCESS);
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [this](const auto& e) { this->onChannelError(e); });
    channel_->sendSensorStartResponse(response, std::move(promise));

    channel_->receive(shared_from_this());
}

void JniSensorHandler::onChannelError(const aasdk::error::Error& e)
{
    session_.reportChannelError("sensor", e);
}

// ============================================================================
// Input Source Handler
// ============================================================================

JniInputHandler::JniInputHandler(
    boost::asio::io_service::strand& strand,
    std::shared_ptr<aasdk::channel::inputsource::InputSourceService> channel,
    JniSession& session)
    : strand_(strand), channel_(std::move(channel)), session_(session)
{
}

void JniInputHandler::start()
{
    channel_->receive(shared_from_this());
}

void JniInputHandler::onChannelOpenRequest(
    const aap_protobuf::service::control::message::ChannelOpenRequest& request)
{
    LOGI("Input channel open");
    logProtoRaw("InputChannelOpen", request);
    aap_protobuf::service::control::message::ChannelOpenResponse response;
    response.set_status(aap_protobuf::shared::STATUS_SUCCESS);
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [this](const auto& e) { this->onChannelError(e); });
    channel_->sendChannelOpenResponse(response, std::move(promise));
    channel_->receive(shared_from_this());
}

void JniInputHandler::onKeyBindingRequest(
    const aap_protobuf::service::media::sink::message::KeyBindingRequest& request)
{
    LOGI("Key binding request");
    logProtoRaw("KeyBinding", request);
    channel_->receive(shared_from_this());
}

void JniInputHandler::onChannelError(const aasdk::error::Error& e)
{
    session_.reportChannelError("input", e);
}

// ============================================================================
// Navigation Status Handler
// ============================================================================

JniNavStatusHandler::JniNavStatusHandler(
    boost::asio::io_service::strand& strand,
    std::shared_ptr<aasdk::channel::navigationstatus::NavigationStatusService> channel,
    JniSession& session)
    : strand_(strand), channel_(std::move(channel)), session_(session)
{
}

void JniNavStatusHandler::start()
{
    channel_->receive(shared_from_this());
}

void JniNavStatusHandler::onChannelOpenRequest(
    const aap_protobuf::service::control::message::ChannelOpenRequest& request)
{
    LOGI("Nav status channel open");
    logProtoRaw("NavStatusChannelOpen", request);
    aap_protobuf::service::control::message::ChannelOpenResponse response;
    response.set_status(aap_protobuf::shared::STATUS_SUCCESS);
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [this](const auto& e) { this->onChannelError(e); });
    channel_->sendChannelOpenResponse(response, std::move(promise));
    channel_->receive(shared_from_this());
}

void JniNavStatusHandler::onStatusUpdate(
    const aap_protobuf::service::navigationstatus::message::NavigationStatus& navStatus)
{
    int status = navStatus.status();
    LOGI("Nav status: %d", status);
    session_.dispatchNavStatus(status);
    channel_->receive(shared_from_this());
}

void JniNavStatusHandler::onTurnEvent(
    const aap_protobuf::service::navigationstatus::message::NavigationNextTurnEvent& turnEvent)
{
    std::string road = turnEvent.road();

    // Map turn event to maneuver string (same logic as bridge)
    std::string maneuver = "unknown";
    if (turnEvent.has_event()) {
        auto ev = turnEvent.event();
        std::string side;
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

    // Extract icon PNG if present
    const uint8_t* iconData = nullptr;
    size_t iconSize = 0;
    if (turnEvent.has_image() && !turnEvent.image().empty()) {
        iconData = reinterpret_cast<const uint8_t*>(turnEvent.image().data());
        iconSize = turnEvent.image().size();
    }

    LOGI("Nav turn: %s road=%s icon=%zu", maneuver.c_str(), road.c_str(), iconSize);
    session_.dispatchNavTurn(maneuver, road, iconData, iconSize);

    channel_->receive(shared_from_this());
}

void JniNavStatusHandler::onDistanceEvent(
    const aap_protobuf::service::navigationstatus::message::NavigationNextTurnDistanceEvent& distanceEvent)
{
    int distanceMeters = distanceEvent.distance_meters();
    int etaSeconds = distanceEvent.time_to_turn_seconds();

    std::string displayDistance;
    std::string displayUnit;
    if (distanceEvent.has_display_distance_e3()) {
        displayDistance = std::to_string(distanceEvent.display_distance_e3());
    }
    if (distanceEvent.has_display_distance_unit()) {
        displayUnit = std::to_string(static_cast<int>(distanceEvent.display_distance_unit()));
    }

    session_.dispatchNavDistance(distanceMeters, etaSeconds, displayDistance, displayUnit);
    channel_->receive(shared_from_this());
}

// Helper: map NavigationManeuver.NavigationType enum to wire string
static std::string maneuverTypeToString(int type) {
    switch (type) {
        case 0:  return "unknown";
        case 1:  return "depart";
        case 2:  return "name_change";
        case 3:  return "keep_left";
        case 4:  return "keep_right";
        case 5:  return "turn_slight_left";
        case 6:  return "turn_slight_right";
        case 7:  return "turn_left";
        case 8:  return "turn_right";
        case 9:  return "turn_sharp_left";
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
        case 37: return "ferry";
        case 38: return "ferry_train";
        case 39: return "destination";
        case 40: return "destination_straight";
        case 41: return "destination_left";
        case 42: return "destination_right";
        default: return "unknown";
    }
}

// Helper: map LaneDirection.Shape enum to wire string
static std::string laneShapeToString(int shape) {
    switch (shape) {
        case 0:  return "unknown";
        case 1:  return "straight";
        case 2:  return "slight_left";
        case 3:  return "slight_right";
        case 4:  return "normal_left";
        case 5:  return "normal_right";
        case 6:  return "sharp_left";
        case 7:  return "sharp_right";
        case 8:  return "u_turn_left";
        case 9:  return "u_turn_right";
        default: return "unknown";
    }
}

// Helper: map NavigationDistance.DistanceUnits enum to wire string
static std::string distanceUnitToString(int unit) {
    switch (unit) {
        case 1:  return "meters";
        case 2:  return "kilometers";
        case 3:  return "kilometers_p1";
        case 4:  return "miles";
        case 5:  return "miles_p1";
        case 6:  return "feet";
        case 7:  return "yards";
        default: return "";
    }
}

void JniNavStatusHandler::onNavigationState(
    const aap_protobuf::service::navigationstatus::message::NavigationState& navState)
{
    // === EV ENERGY FORECAST INVESTIGATION ===
    // Log raw serialized bytes to detect unknown fields (energy forecast data)
    {
        std::string raw;
        navState.SerializeToString(&raw);
        LOGI("Nav state RAW (%zu bytes): %s", raw.size(),
             hexDump(reinterpret_cast<const uint8_t*>(raw.data()), raw.size()).c_str());
        LOGI("Nav state: steps=%d destinations=%d unknownFields=%d",
             navState.steps_size(), navState.destinations_size(),
             navState.unknown_fields().field_count());

        // Log unknown fields on NavigationState itself
        const auto& uf = navState.unknown_fields();
        for (int i = 0; i < uf.field_count(); ++i) {
            const auto& f = uf.field(i);
            LOGI("Nav state UNKNOWN field #%d type=%d", f.number(), f.type());
            if (f.type() == google::protobuf::UnknownField::TYPE_LENGTH_DELIMITED) {
                const auto& ld = f.length_delimited();
                LOGI("  -> bytes (%zu): %s", ld.size(),
                     hexDump(reinterpret_cast<const uint8_t*>(ld.data()), ld.size()).c_str());
            } else if (f.type() == google::protobuf::UnknownField::TYPE_VARINT) {
                LOGI("  -> varint: %llu", (unsigned long long)f.varint());
            } else if (f.type() == google::protobuf::UnknownField::TYPE_FIXED32) {
                LOGI("  -> fixed32: %u", f.fixed32());
            } else if (f.type() == google::protobuf::UnknownField::TYPE_FIXED64) {
                LOGI("  -> fixed64: %llu", (unsigned long long)f.fixed64());
            }
        }

        // Log unknown fields on each step
        for (int si = 0; si < navState.steps_size(); ++si) {
            const auto& step = navState.steps(si);
            const auto& suf = step.unknown_fields();
            if (suf.field_count() > 0) {
                LOGI("Nav step[%d] has %d unknown fields", si, suf.field_count());
                for (int i = 0; i < suf.field_count(); ++i) {
                    const auto& f = suf.field(i);
                    LOGI("  step[%d] UNKNOWN field #%d type=%d", si, f.number(), f.type());
                    if (f.type() == google::protobuf::UnknownField::TYPE_LENGTH_DELIMITED) {
                        const auto& ld = f.length_delimited();
                        LOGI("    -> bytes (%zu): %s", ld.size(),
                             hexDump(reinterpret_cast<const uint8_t*>(ld.data()), ld.size()).c_str());
                    }
                }
            }
        }

        // Log unknown fields on each destination
        for (int di = 0; di < navState.destinations_size(); ++di) {
            const auto& dest = navState.destinations(di);
            const auto& duf = dest.unknown_fields();
            if (duf.field_count() > 0) {
                LOGI("Nav dest[%d] has %d unknown fields", di, duf.field_count());
                for (int i = 0; i < duf.field_count(); ++i) {
                    const auto& f = duf.field(i);
                    LOGI("  dest[%d] UNKNOWN field #%d type=%d", di, f.number(), f.type());
                    if (f.type() == google::protobuf::UnknownField::TYPE_LENGTH_DELIMITED) {
                        const auto& ld = f.length_delimited();
                        LOGI("    -> bytes (%zu): %s", ld.size(),
                             hexDump(reinterpret_cast<const uint8_t*>(ld.data()), ld.size()).c_str());
                    } else if (f.type() == google::protobuf::UnknownField::TYPE_VARINT) {
                        LOGI("    -> varint: %llu", (unsigned long long)f.varint());
                    }
                }
            }
        }
    }
    // === END INVESTIGATION ===

    // Parse the first step (primary next-turn instruction)
    std::string maneuver = "";
    std::string road = "";
    std::string cue = "";
    int roundaboutExitNumber = -1;
    std::string lanes = "";

    if (navState.steps_size() > 0) {
        const auto& step = navState.steps(0);

        if (step.has_maneuver()) {
            const auto& m = step.maneuver();
            if (m.has_type()) {
                maneuver = maneuverTypeToString(static_cast<int>(m.type()));
            }
            if (m.has_roundabout_exit_number()) {
                roundaboutExitNumber = m.roundabout_exit_number();
            }
        }

        if (step.has_road() && step.road().has_name()) {
            road = step.road().name();
        }

        if (step.has_cue() && step.cue().alternate_text_size() > 0) {
            cue = step.cue().alternate_text(0);
        }

        // Serialize lanes: pipe-separated lanes, comma-separated directions
        // Format: "shape:highlighted,shape:highlighted|shape:highlighted,..."
        if (step.lanes_size() > 0) {
            std::ostringstream ss;
            for (int i = 0; i < step.lanes_size(); ++i) {
                if (i > 0) ss << '|';
                const auto& lane = step.lanes(i);
                for (int j = 0; j < lane.lane_directions_size(); ++j) {
                    if (j > 0) ss << ',';
                    const auto& dir = lane.lane_directions(j);
                    ss << laneShapeToString(dir.has_shape() ? static_cast<int>(dir.shape()) : 0);
                    ss << ':' << (dir.has_is_highlighted() && dir.is_highlighted() ? '1' : '0');
                }
            }
            lanes = ss.str();
        }
    }

    // Parse destination
    std::string destination = "";
    if (navState.destinations_size() > 0) {
        const auto& dest = navState.destinations(0);
        if (dest.has_address()) {
            destination = dest.address();
        }
    }

    // Distance/ETA come from onCurrentPosition (separate message) not NavigationState,
    // so pass zeroes — the merge logic in Kotlin will keep previous values
    LOGI("Nav state: maneuver=%s road=%s cue=%s lanes=%s dest=%s",
         maneuver.c_str(), road.c_str(), cue.c_str(),
         lanes.empty() ? "(none)" : lanes.c_str(),
         destination.empty() ? "(none)" : destination.c_str());

    session_.dispatchNavFullState(
        maneuver, road, nullptr, 0,
        0, 0, "", "",
        lanes, cue, roundaboutExitNumber,
        "", destination, "", 0, 0, "", "");

    channel_->receive(shared_from_this());
}

void JniNavStatusHandler::onCurrentPosition(
    const aap_protobuf::service::navigationstatus::message::NavigationCurrentPosition& position)
{
    // === EV ENERGY FORECAST INVESTIGATION ===
    {
        std::string raw;
        position.SerializeToString(&raw);
        LOGI("Nav position RAW (%zu bytes): %s", raw.size(),
             hexDump(reinterpret_cast<const uint8_t*>(raw.data()), raw.size()).c_str());
        LOGI("Nav position: hasStepDist=%d destDists=%d unknownFields=%d",
             position.has_step_distance() ? 1 : 0,
             position.destination_distances_size(),
             position.unknown_fields().field_count());

        // Unknown fields on CurrentPosition itself
        const auto& uf = position.unknown_fields();
        for (int i = 0; i < uf.field_count(); ++i) {
            const auto& f = uf.field(i);
            LOGI("Nav position UNKNOWN field #%d type=%d", f.number(), f.type());
            if (f.type() == google::protobuf::UnknownField::TYPE_LENGTH_DELIMITED) {
                const auto& ld = f.length_delimited();
                LOGI("  -> bytes (%zu): %s", ld.size(),
                     hexDump(reinterpret_cast<const uint8_t*>(ld.data()), ld.size()).c_str());
            } else if (f.type() == google::protobuf::UnknownField::TYPE_VARINT) {
                LOGI("  -> varint: %llu", (unsigned long long)f.varint());
            }
        }

        // Unknown fields on each DestinationDistance (energy forecast likely here)
        for (int di = 0; di < position.destination_distances_size(); ++di) {
            const auto& dd = position.destination_distances(di);
            const auto& duf = dd.unknown_fields();
            if (duf.field_count() > 0) {
                LOGI("Nav destDist[%d] has %d unknown fields", di, duf.field_count());
                for (int i = 0; i < duf.field_count(); ++i) {
                    const auto& f = duf.field(i);
                    LOGI("  destDist[%d] UNKNOWN field #%d type=%d", di, f.number(), f.type());
                    if (f.type() == google::protobuf::UnknownField::TYPE_LENGTH_DELIMITED) {
                        const auto& ld = f.length_delimited();
                        LOGI("    -> bytes (%zu): %s", ld.size(),
                             hexDump(reinterpret_cast<const uint8_t*>(ld.data()), ld.size()).c_str());
                    } else if (f.type() == google::protobuf::UnknownField::TYPE_VARINT) {
                        LOGI("    -> varint: %llu", (unsigned long long)f.varint());
                    } else if (f.type() == google::protobuf::UnknownField::TYPE_FIXED32) {
                        auto v = f.fixed32();
                        float fv;
                        memcpy(&fv, &v, sizeof(fv));
                        LOGI("    -> fixed32: %u (float=%f)", v, fv);
                    } else if (f.type() == google::protobuf::UnknownField::TYPE_FIXED64) {
                        LOGI("    -> fixed64: %llu", (unsigned long long)f.fixed64());
                    }
                }
            }
        }

        // Unknown fields on StepDistance
        if (position.has_step_distance()) {
            const auto& suf = position.step_distance().unknown_fields();
            if (suf.field_count() > 0) {
                LOGI("Nav stepDist has %d unknown fields", suf.field_count());
                for (int i = 0; i < suf.field_count(); ++i) {
                    const auto& f = suf.field(i);
                    LOGI("  stepDist UNKNOWN field #%d type=%d", f.number(), f.type());
                    if (f.type() == google::protobuf::UnknownField::TYPE_LENGTH_DELIMITED) {
                        const auto& ld = f.length_delimited();
                        LOGI("    -> bytes (%zu): %s", ld.size(),
                             hexDump(reinterpret_cast<const uint8_t*>(ld.data()), ld.size()).c_str());
                    }
                }
            }
        }
    }
    // === END INVESTIGATION ===

    int distanceMeters = 0;
    int etaSeconds = 0;
    std::string displayDistance;
    std::string displayUnit;
    std::string currentRoad;
    std::string etaFormatted;
    long long timeToArrivalSeconds = 0;
    int destDistanceMeters = 0;
    std::string destDistDisplay;
    std::string destDistUnit;

    // Step distance (next turn)
    if (position.has_step_distance()) {
        const auto& sd = position.step_distance();
        if (sd.has_distance()) {
            const auto& d = sd.distance();
            if (d.has_meters()) distanceMeters = d.meters();
            if (d.has_display_value()) displayDistance = d.display_value();
            if (d.has_display_units()) displayUnit = distanceUnitToString(static_cast<int>(d.display_units()));
        }
        if (sd.has_time_to_step_seconds()) etaSeconds = static_cast<int>(sd.time_to_step_seconds());
    }

    // Current road
    if (position.has_current_road() && position.current_road().has_name()) {
        currentRoad = position.current_road().name();
    }

    // Destination distance (first destination)
    if (position.destination_distances_size() > 0) {
        const auto& dd = position.destination_distances(0);
        if (dd.has_distance()) {
            const auto& d = dd.distance();
            if (d.has_meters()) destDistanceMeters = d.meters();
            if (d.has_display_value()) destDistDisplay = d.display_value();
            if (d.has_display_units()) destDistUnit = distanceUnitToString(static_cast<int>(d.display_units()));
        }
        if (dd.has_estimated_time_at_arrival()) etaFormatted = dd.estimated_time_at_arrival();
        if (dd.has_time_to_arrival_seconds()) timeToArrivalSeconds = dd.time_to_arrival_seconds();
    }

    LOGI("Nav position: dist=%dm eta=%ds destDist=%dm currentRoad=%s",
         distanceMeters, etaSeconds, destDistanceMeters, currentRoad.c_str());

    // Dispatch as full state with empty maneuver/road/lanes — merge logic in Kotlin
    // will keep previous turn data and update distance fields
    session_.dispatchNavFullState(
        "", "", nullptr, 0,
        distanceMeters, etaSeconds, displayDistance, displayUnit,
        "", "", -1,
        currentRoad, "", etaFormatted, timeToArrivalSeconds,
        destDistanceMeters, destDistDisplay, destDistUnit);

    channel_->receive(shared_from_this());
}

void JniNavStatusHandler::onChannelError(const aasdk::error::Error& e)
{
    session_.reportChannelError("nav", e);
}

// ============================================================================
// Microphone Handler
// ============================================================================

JniMicHandler::JniMicHandler(
    boost::asio::io_service::strand& strand,
    std::shared_ptr<aasdk::channel::mediasource::MediaSourceService> channel,
    JniSession& session)
    : strand_(strand), channel_(std::move(channel)), session_(session)
{
}

void JniMicHandler::start()
{
    channel_->receive(shared_from_this());
}

void JniMicHandler::onChannelOpenRequest(
    const aap_protobuf::service::control::message::ChannelOpenRequest& request)
{
    LOGI("Mic channel open");
    logProtoRaw("MicChannelOpen", request);
    aap_protobuf::service::control::message::ChannelOpenResponse response;
    response.set_status(aap_protobuf::shared::STATUS_SUCCESS);
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [this](const auto& e) { this->onChannelError(e); });
    channel_->sendChannelOpenResponse(response, std::move(promise));
    channel_->receive(shared_from_this());
}

void JniMicHandler::onMediaChannelSetupRequest(
    const aap_protobuf::service::media::shared::message::Setup& request)
{
    LOGI("Mic setup codec=%d", request.type());
    logProtoRaw("MicSetup", request);
    aap_protobuf::service::media::shared::message::Config config;
    config.set_status(aap_protobuf::service::media::shared::message::Config::STATUS_READY);
    config.set_max_unacked(30);
    config.add_configuration_indices(0);
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [this](const auto& e) { this->onChannelError(e); });
    channel_->sendChannelSetupResponse(config, std::move(promise));
    channel_->receive(shared_from_this());
}

void JniMicHandler::onMediaSourceOpenRequest(
    const aap_protobuf::service::media::source::message::MicrophoneRequest& request)
{
    bool wantOpen = request.open();
    LOGI("Mic %s", wantOpen ? "open" : "close");
    open_ = wantOpen;
    session_.dispatchMicRequest(wantOpen);
    channel_->receive(shared_from_this());
}

void JniMicHandler::onMediaChannelAckIndication(
    const aap_protobuf::service::media::source::message::Ack& /*indication*/)
{
    channel_->receive(shared_from_this());
}

void JniMicHandler::feedAudio(const uint8_t* data, size_t size)
{
    if (!open_) return;

    aasdk::common::Data audioData(data, data + size);
    auto ts = static_cast<aasdk::messenger::Timestamp::ValueType>(
        std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count());
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [](const auto&) {});
    channel_->sendMediaSourceWithTimestampIndication(ts, audioData, std::move(promise));
}

void JniMicHandler::onChannelError(const aasdk::error::Error& e)
{
    session_.reportChannelError("mic", e);
}

// ============================================================================
// Media Playback Status Handler
// ============================================================================

JniMediaStatusHandler::JniMediaStatusHandler(
    boost::asio::io_service::strand& strand,
    std::shared_ptr<aasdk::channel::mediaplaybackstatus::MediaPlaybackStatusService> channel,
    JniSession& session)
    : strand_(strand), channel_(std::move(channel)), session_(session)
{
}

void JniMediaStatusHandler::start()
{
    channel_->receive(shared_from_this());
}

void JniMediaStatusHandler::onChannelOpenRequest(
    const aap_protobuf::service::control::message::ChannelOpenRequest& request)
{
    LOGI("Media status channel open");
    logProtoRaw("MediaStatusChannelOpen", request);
    aap_protobuf::service::control::message::ChannelOpenResponse response;
    response.set_status(aap_protobuf::shared::STATUS_SUCCESS);
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [this](const auto& e) { this->onChannelError(e); });
    channel_->sendChannelOpenResponse(response, std::move(promise));
    channel_->receive(shared_from_this());
}

void JniMediaStatusHandler::onMetadataUpdate(
    const aap_protobuf::service::mediaplayback::message::MediaPlaybackMetadata& metadata)
{
    logProtoRaw("MediaMetadata", metadata);
    std::string title = metadata.has_song() ? metadata.song() : "";
    std::string artist = metadata.has_artist() ? metadata.artist() : "";
    std::string album = metadata.has_album() ? metadata.album() : "";

    const uint8_t* artData = nullptr;
    size_t artSize = 0;
    if (metadata.has_album_art() && !metadata.album_art().empty()) {
        artData = reinterpret_cast<const uint8_t*>(metadata.album_art().data());
        artSize = metadata.album_art().size();
    }

    session_.dispatchMediaMetadata(title, artist, album, artData, artSize);
    channel_->receive(shared_from_this());
}

void JniMediaStatusHandler::onPlaybackUpdate(
    const aap_protobuf::service::mediaplayback::message::MediaPlaybackStatus& playback)
{
    logProtoRaw("MediaPlayback", playback);
    int state = playback.has_state() ? static_cast<int>(playback.state()) : 0;
    long long positionMs = playback.has_playback_seconds() ? static_cast<long long>(playback.playback_seconds()) * 1000 : 0;
    session_.dispatchMediaPlayback(state, positionMs);
    channel_->receive(shared_from_this());
}

void JniMediaStatusHandler::onChannelError(const aasdk::error::Error& e)
{
    session_.reportChannelError("media-status", e);
}

// ============================================================================
// Phone Status Handler
// ============================================================================

JniPhoneStatusHandler::JniPhoneStatusHandler(
    boost::asio::io_service::strand& strand,
    std::shared_ptr<aasdk::channel::phonestatus::PhoneStatusService> channel,
    JniSession& session)
    : strand_(strand), channel_(std::move(channel)), session_(session)
{
}

void JniPhoneStatusHandler::start()
{
    channel_->receive(shared_from_this());
}

void JniPhoneStatusHandler::onChannelOpenRequest(
    const aap_protobuf::service::control::message::ChannelOpenRequest& request)
{
    LOGI("Phone status channel open");
    logProtoRaw("PhoneStatusChannelOpen", request);
    aap_protobuf::service::control::message::ChannelOpenResponse response;
    response.set_status(aap_protobuf::shared::STATUS_SUCCESS);
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [this](const auto& e) { this->onChannelError(e); });
    channel_->sendChannelOpenResponse(response, std::move(promise));
    channel_->receive(shared_from_this());
}

void JniPhoneStatusHandler::onPhoneStatusUpdate(
    const aap_protobuf::service::phonestatus::message::PhoneStatus& status)
{
    logProtoRaw("PhoneStatus", status);
    int signal = status.has_signal_strength() ? status.signal_strength() : -1;
    int callState = 0;
    if (status.calls_size() > 0) {
        callState = static_cast<int>(status.calls(0).phone_state());
    }
    session_.dispatchPhoneStatus(signal, callState);
    channel_->receive(shared_from_this());
}

void JniPhoneStatusHandler::onChannelError(const aasdk::error::Error& e)
{
    session_.reportChannelError("phone-status", e);
}

// ============================================================================
// Bluetooth Handler
// ============================================================================

JniBluetoothHandler::JniBluetoothHandler(
    boost::asio::io_service::strand& strand,
    std::shared_ptr<aasdk::channel::bluetooth::BluetoothService> channel,
    JniSession& session)
    : strand_(strand), channel_(std::move(channel)), session_(session)
{
}

void JniBluetoothHandler::start()
{
    channel_->receive(shared_from_this());
}

void JniBluetoothHandler::onChannelOpenRequest(
    const aap_protobuf::service::control::message::ChannelOpenRequest& request)
{
    LOGI("Bluetooth channel open");
    logProtoRaw("BTChannelOpen", request);
    aap_protobuf::service::control::message::ChannelOpenResponse response;
    response.set_status(aap_protobuf::shared::STATUS_SUCCESS);
    auto promise = aasdk::channel::SendPromise::defer(strand_);
    promise->then([]() {}, [this](const auto& e) { this->onChannelError(e); });
    channel_->sendChannelOpenResponse(response, std::move(promise));
    channel_->receive(shared_from_this());
}

void JniBluetoothHandler::onBluetoothPairingRequest(
    const aap_protobuf::service::bluetooth::message::BluetoothPairingRequest& request)
{
    LOGI("Bluetooth pairing request");
    logProtoRaw("BTPairing", request);
    // In JNI mode the AA session runs over TCP -- BT pairing is handled by
    // the companion app outside the AA wire protocol. Acknowledge silently.
    channel_->receive(shared_from_this());
}

void JniBluetoothHandler::onBluetoothAuthenticationResult(
    const aap_protobuf::service::bluetooth::message::BluetoothAuthenticationResult& result)
{
    LOGI("Bluetooth auth result");
    logProtoRaw("BTAuth", result);
    channel_->receive(shared_from_this());
}

void JniBluetoothHandler::onChannelError(const aasdk::error::Error& e)
{
    session_.reportChannelError("bluetooth", e);
}

} // namespace openautolink::jni
