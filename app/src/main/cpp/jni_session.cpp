/*
 * jni_session.cpp Ã¢â‚¬â€ aasdk session lifecycle + JNI callback dispatch.
 *
 * This is the core integration: creates the aasdk pipeline, implements
 * all AA channel event handlers, and fires JNI callbacks to Kotlin.
 *
 * Based on bridge/openautolink/headless/src/live_session.cpp Ã¢â‚¬â€ same
 * aasdk API calls, but output goes to JNI instead of OAL TCP.
 */
#include "jni_session.h"
#include "jni_transport.h"
#include "jni_channel_handlers.h"

#include <android/log.h>
#include <cmath>
#include <utility>
#include <vector>

// Protobuf messages for SDR building
#include <aap_protobuf/service/Service.pb.h>
#include <aap_protobuf/service/control/message/ServiceDiscoveryResponse.pb.h>
#include <aap_protobuf/service/control/message/ChannelOpenResponse.pb.h>
#include <aap_protobuf/service/control/message/AuthResponse.pb.h>
#include <aap_protobuf/service/control/message/AudioFocusNotification.pb.h>
#include <aap_protobuf/service/control/message/NavFocusNotification.pb.h>
#include <aap_protobuf/service/control/message/PingResponse.pb.h>
#include <aap_protobuf/service/control/message/PingRequest.pb.h>
#include <aap_protobuf/service/control/message/ByeByeResponse.pb.h>
#include <aap_protobuf/service/control/message/DriverPosition.pb.h>
#include <aap_protobuf/service/media/shared/message/Config.pb.h>
#include <aap_protobuf/service/media/source/message/Ack.pb.h>
#include <aap_protobuf/service/media/video/message/VideoFocusNotification.pb.h>
#include <aap_protobuf/service/media/sink/MediaSinkService.pb.h>
#include <aap_protobuf/service/media/sink/message/VideoConfiguration.pb.h>
#include <aap_protobuf/service/media/sink/message/VideoCodecResolutionType.pb.h>
#include <aap_protobuf/service/media/sink/message/VideoFrameRateType.pb.h>
#include <aap_protobuf/service/sensorsource/SensorSourceService.pb.h>
#include <aap_protobuf/service/inputsource/InputSourceService.pb.h>
#include <aap_protobuf/service/bluetooth/BluetoothService.pb.h>
#include <aap_protobuf/service/bluetooth/message/BluetoothPairingMethod.pb.h>
#include <aap_protobuf/service/inputsource/message/TouchScreenType.pb.h>
#include <aap_protobuf/service/navigationstatus/NavigationStatusService.pb.h>
#include <aap_protobuf/shared/MessageStatus.pb.h>

#include <aap_protobuf/service/inputsource/message/InputReport.pb.h>
#include <aap_protobuf/service/inputsource/message/TouchEvent.pb.h>
#include <aap_protobuf/service/inputsource/message/KeyEvent.pb.h>
#include <aap_protobuf/service/sensorsource/message/SensorBatch.pb.h>
#include <aap_protobuf/service/media/source/message/MicrophoneRequest.pb.h>
#include <aap_protobuf/service/media/source/message/MicrophoneResponse.pb.h>

#include <aasdk/Channel/Promise.hpp>
#include <aasdk/Messenger/ChannelId.hpp>
#include <aasdk/Messenger/Message.hpp>
#include <aasdk/Messenger/MessageId.hpp>
#include <aap_protobuf/service/control/ControlMessageType.pb.h>

#define LOG_TAG "OAL-JniSession"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Hex-dump helper for raw protobuf bytes
static std::string hexDump(const uint8_t* data, size_t len, size_t maxBytes = 512) {
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

// Log raw bytes + unknown fields for any protobuf message
template<typename T>
static void logProtoRaw(const char* label, const T& msg) {
    std::string raw;
    msg.SerializeToString(&raw);
    if (raw.size() > 0) {
        LOGI("%s RAW (%zu bytes): %s", label, raw.size(),
             hexDump(reinterpret_cast<const uint8_t*>(raw.data()), raw.size()).c_str());
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
// Lifecycle
// ============================================================================

JniSession::JniSession(JavaVM* jvm)
    : jvm_(jvm)
{
    ioService_ = std::make_unique<boost::asio::io_service>();
    ioWork_ = std::make_unique<boost::asio::io_service::work>(*ioService_);
    strand_ = std::make_unique<boost::asio::io_service::strand>(*ioService_);
    ioThread_ = std::thread(&JniSession::ioServiceThreadFunc, this);
    LOGI("JniSession created");
}

JniSession::~JniSession()
{
    stop();
    strand_.reset();
    LOGI("JniSession destroyed");
}

void JniSession::ioServiceThreadFunc()
{
    LOGI("io_service thread started");
    ioService_->run();
    LOGI("io_service thread exiting");
}

// ============================================================================
// JNI helpers
// ============================================================================

JNIEnv* JniSession::getEnv(bool& attached)
{
    JNIEnv* env = nullptr;
    attached = false;
    jint result = jvm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (result == JNI_EDETACHED) {
        jvm_->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    return env;
}

void JniSession::releaseEnv(bool attached)
{
    if (attached) jvm_->DetachCurrentThread();
}

void JniSession::callVoidCallback(jmethodID method)
{
    if (!callbackRef_ || !method) return;
    bool attached;
    JNIEnv* env = getEnv(attached);
    if (env) env->CallVoidMethod(callbackRef_, method);
    releaseEnv(attached);
}

// ============================================================================
// start() Ã¢â‚¬â€ build the aasdk pipeline
// ============================================================================

void JniSession::start(JNIEnv* env, jobject transportPipe, jobject callback, jobject sdrConfig)
{
    if (streaming_) {
        LOGW("Session already streaming, ignoring start()");
        return;
    }

    // Create global refs
    callbackRef_ = env->NewGlobalRef(callback);
    jobject transportRef = env->NewGlobalRef(transportPipe);

    // Cache callback method IDs
    jclass cbClass = env->GetObjectClass(callback);
    cbMethods_.onSessionStarted = env->GetMethodID(cbClass, "onSessionStarted", "()V");
    cbMethods_.onSessionStopped = env->GetMethodID(cbClass, "onSessionStopped", "(Ljava/lang/String;)V");
    cbMethods_.onVideoFrame = env->GetMethodID(cbClass, "onVideoFrame", "([BJIII)V");
    cbMethods_.onVideoCodecConfigured = env->GetMethodID(cbClass, "onVideoCodecConfigured", "(I)V");
    cbMethods_.onAudioFrame = env->GetMethodID(cbClass, "onAudioFrame", "([BIII)V");
    cbMethods_.onMicRequest = env->GetMethodID(cbClass, "onMicRequest", "(Z)V");
    cbMethods_.onNavigationStatus = env->GetMethodID(cbClass, "onNavigationStatus", "(I)V");
    cbMethods_.onNavigationTurn = env->GetMethodID(cbClass, "onNavigationTurn",
        "(Ljava/lang/String;Ljava/lang/String;[B)V");
    cbMethods_.onNavigationDistance = env->GetMethodID(cbClass, "onNavigationDistance",
        "(IILjava/lang/String;Ljava/lang/String;)V");
    cbMethods_.onNavigationFullState = env->GetMethodID(cbClass, "onNavigationFullState",
        "(Ljava/lang/String;Ljava/lang/String;[BIILjava/lang/String;Ljava/lang/String;"
        "Ljava/lang/String;Ljava/lang/String;I"
        "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JILjava/lang/String;Ljava/lang/String;)V");
    cbMethods_.onMediaMetadata = env->GetMethodID(cbClass, "onMediaMetadata",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[B)V");
    cbMethods_.onMediaPlayback = env->GetMethodID(cbClass, "onMediaPlayback", "(IJ)V");
    cbMethods_.onPhoneStatus = env->GetMethodID(cbClass, "onPhoneStatus", "(II)V");
    cbMethods_.onPhoneBattery = env->GetMethodID(cbClass, "onPhoneBattery", "(IZ)V");
    cbMethods_.onVoiceSession = env->GetMethodID(cbClass, "onVoiceSession", "(Z)V");
    cbMethods_.onAudioFocusRequest = env->GetMethodID(cbClass, "onAudioFocusRequest", "(I)V");
    cbMethods_.onError = env->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(cbClass);

    // Read SDR config from Kotlin
    jclass sdrClass = env->GetObjectClass(sdrConfig);
    sdrConfig_.videoWidth = env->GetIntField(sdrConfig, env->GetFieldID(sdrClass, "videoWidth", "I"));
    sdrConfig_.videoHeight = env->GetIntField(sdrConfig, env->GetFieldID(sdrClass, "videoHeight", "I"));
    sdrConfig_.videoFps = env->GetIntField(sdrConfig, env->GetFieldID(sdrClass, "videoFps", "I"));
    sdrConfig_.videoDpi = env->GetIntField(sdrConfig, env->GetFieldID(sdrClass, "videoDpi", "I"));
    sdrConfig_.marginWidth = env->GetIntField(sdrConfig, env->GetFieldID(sdrClass, "marginWidth", "I"));
    sdrConfig_.marginHeight = env->GetIntField(sdrConfig, env->GetFieldID(sdrClass, "marginHeight", "I"));
    sdrConfig_.pixelAspectE4 = env->GetIntField(sdrConfig, env->GetFieldID(sdrClass, "pixelAspectE4", "I"));
    sdrConfig_.driverPosition = env->GetIntField(sdrConfig, env->GetFieldID(sdrClass, "driverPosition", "I"));

    // Read string fields
    auto readString = [&](const char* field) -> std::string {
        jfieldID fid = env->GetFieldID(sdrClass, field, "Ljava/lang/String;");
        auto jstr = static_cast<jstring>(env->GetObjectField(sdrConfig, fid));
        if (!jstr) return "";
        const char* chars = env->GetStringUTFChars(jstr, nullptr);
        std::string result(chars);
        env->ReleaseStringUTFChars(jstr, chars);
        env->DeleteLocalRef(jstr);
        return result;
    };
    sdrConfig_.btMac = readString("btMacAddress");
    sdrConfig_.vehicleMake = readString("vehicleMake");
    sdrConfig_.vehicleModel = readString("vehicleModel");
    sdrConfig_.vehicleYear = readString("vehicleYear");
    sdrConfig_.hideClock = env->GetBooleanField(sdrConfig, env->GetFieldID(sdrClass, "hideClock", "Z"));
    sdrConfig_.hideSignal = env->GetBooleanField(sdrConfig, env->GetFieldID(sdrClass, "hideSignal", "Z"));
    sdrConfig_.hideBattery = env->GetBooleanField(sdrConfig, env->GetFieldID(sdrClass, "hideBattery", "Z"));
    sdrConfig_.autoNegotiate = env->GetBooleanField(sdrConfig, env->GetFieldID(sdrClass, "autoNegotiate", "Z"));
    sdrConfig_.videoCodec = readString("videoCodec");
    sdrConfig_.realDensity = env->GetIntField(sdrConfig, env->GetFieldID(sdrClass, "realDensity", "I"));
    sdrConfig_.safeAreaTop = env->GetIntField(sdrConfig, env->GetFieldID(sdrClass, "safeAreaTop", "I"));
    sdrConfig_.safeAreaBottom = env->GetIntField(sdrConfig, env->GetFieldID(sdrClass, "safeAreaBottom", "I"));
    sdrConfig_.safeAreaLeft = env->GetIntField(sdrConfig, env->GetFieldID(sdrClass, "safeAreaLeft", "I"));
    sdrConfig_.safeAreaRight = env->GetIntField(sdrConfig, env->GetFieldID(sdrClass, "safeAreaRight", "I"));
    sdrConfig_.targetLayoutWidthDp = env->GetIntField(sdrConfig, env->GetFieldID(sdrClass, "targetLayoutWidthDp", "I"));
    sdrConfig_.viewingDistanceMm = env->GetIntField(sdrConfig, env->GetFieldID(sdrClass, "viewingDistanceMm", "I"));
    sdrConfig_.decoderAdditionalDepth = env->GetIntField(sdrConfig, env->GetFieldID(sdrClass, "decoderAdditionalDepth", "I"));
    sdrConfig_.panelWidth = env->GetIntField(sdrConfig, env->GetFieldID(sdrClass, "panelWidth", "I"));
    sdrConfig_.panelHeight = env->GetIntField(sdrConfig, env->GetFieldID(sdrClass, "panelHeight", "I"));
    sdrConfig_.autoMargins = env->GetBooleanField(sdrConfig, env->GetFieldID(sdrClass, "autoMargins", "Z")) != JNI_FALSE;

    // Read int arrays: fuelTypes and evConnectorTypes
    auto readIntArray = [&](const char* field) -> std::vector<int> {
        jfieldID fid = env->GetFieldID(sdrClass, field, "[I");
        auto jarr = static_cast<jintArray>(env->GetObjectField(sdrConfig, fid));
        if (!jarr) return {};
        jsize len = env->GetArrayLength(jarr);
        std::vector<int> result(len);
        env->GetIntArrayRegion(jarr, 0, len, result.data());
        env->DeleteLocalRef(jarr);
        return result;
    };
    sdrConfig_.fuelTypes = readIntArray("fuelTypes");
    sdrConfig_.evConnectorTypes = readIntArray("evConnectorTypes");

    env->DeleteLocalRef(sdrClass);

    LOGI("Starting session: video=%dx%d@%dfps dpi=%d realDpi=%d pixelAspect=%d marginW=%d marginH=%d viewDistMm=%d decAddDepth=%d autoNeg=%d codec=%s targetLayoutDp=%d",
         sdrConfig_.videoWidth, sdrConfig_.videoHeight,
         sdrConfig_.videoFps, sdrConfig_.videoDpi, sdrConfig_.realDensity,
         sdrConfig_.pixelAspectE4, sdrConfig_.marginWidth, sdrConfig_.marginHeight,
         sdrConfig_.viewingDistanceMm, sdrConfig_.decoderAdditionalDepth,
         sdrConfig_.autoNegotiate, sdrConfig_.videoCodec.c_str(),
         sdrConfig_.targetLayoutWidthDp);
    if (sdrConfig_.safeAreaTop > 0 || sdrConfig_.safeAreaBottom > 0 ||
        sdrConfig_.safeAreaLeft > 0 || sdrConfig_.safeAreaRight > 0) {
        LOGI("Safe area insets: top=%d bottom=%d left=%d right=%d",
             sdrConfig_.safeAreaTop, sdrConfig_.safeAreaBottom,
             sdrConfig_.safeAreaLeft, sdrConfig_.safeAreaRight);
    }

    // Create JNI transport from the TCP stream pipe (Kotlin side)
    transport_ = std::make_shared<JniTransport>(*ioService_, jvm_, transportRef);
    rawTransport_ = transport_;

    // Build aasdk pipeline on the io_service thread
    ioService_->post([this, self = shared_from_this()]() {
        // 1. SSL + Cryptor
        LOGI("Creating SSL cryptor...");
        auto sslWrapper = std::make_shared<aasdk::transport::SSLWrapper>();
        cryptor_ = std::make_shared<aasdk::messenger::Cryptor>(sslWrapper);
        try {
            cryptor_->init();
            LOGI("Cryptor initialized OK (connect mode)");
        } catch (const std::exception& e) {
            LOGE("Cryptor init FAILED: %s", e.what());
            if (cbMethods_.onError && callbackRef_) {
                bool attached;
                JNIEnv* env = getEnv(attached);
                if (env) {
                    jstring msg = env->NewStringUTF(e.what());
                    env->CallVoidMethod(callbackRef_, cbMethods_.onError, msg);
                    env->DeleteLocalRef(msg);
                }
                releaseEnv(attached);
            }
            return;
        }

        // 2. Messenger
        LOGI("Creating messenger...");
        auto inStream = std::make_shared<aasdk::messenger::MessageInStream>(
            *ioService_, rawTransport_, cryptor_);
        auto outStream = std::make_shared<aasdk::messenger::MessageOutStream>(
            *ioService_, rawTransport_, cryptor_);
        messenger_ = std::make_shared<aasdk::messenger::Messenger>(
            *ioService_, inStream, outStream);

        // 3. Control channel
        controlChannel_ = std::make_shared<aasdk::channel::control::ControlServiceChannel>(
            *strand_, messenger_);

        // 4. Service channels (created now, started after SDR)
        // Video uses channel ID 2 (MEDIA_SINK) matching Java implementation
        videoChannel_ = std::make_shared<aasdk::channel::mediasink::video::VideoMediaSinkService>(
            *strand_, messenger_, aasdk::messenger::ChannelId::MEDIA_SINK);
        mediaAudioChannel_ = std::make_shared<aasdk::channel::mediasink::audio::channel::MediaAudioChannel>(
            *strand_, messenger_);
        guidanceAudioChannel_ = std::make_shared<aasdk::channel::mediasink::audio::channel::GuidanceAudioChannel>(
            *strand_, messenger_);
        systemAudioChannel_ = std::make_shared<aasdk::channel::mediasink::audio::channel::SystemAudioChannel>(
            *strand_, messenger_);
        // NOTE: TelephonyAudioChannel intentionally not created — phone-side
        // AA's CAR.AUDIO.AFM explicitly refuses focus events for phone calls,
        // and the obfuscated C0000a.m5F mapper crashes on AUDIO_STREAM_TELEPHONY.
        // See docs/aa-undocumented-features.md.
        inputChannel_ = std::make_shared<aasdk::channel::inputsource::InputSourceService>(
            *strand_, messenger_);
        sensorChannel_ = std::make_shared<aasdk::channel::sensorsource::SensorSourceService>(
            *strand_, messenger_);

        micChannel_ = std::make_shared<aasdk::channel::mediasource::MediaSourceService>(
            *strand_, messenger_, aasdk::messenger::ChannelId::MEDIA_SOURCE_MICROPHONE);

        // Nav/media/phone status channels
        navChannel_ = std::make_shared<aasdk::channel::navigationstatus::NavigationStatusService>(
            *strand_, messenger_);
        mediaStatusChannel_ = std::make_shared<aasdk::channel::mediaplaybackstatus::MediaPlaybackStatusService>(
            *strand_, messenger_);
        phoneStatusChannel_ = std::make_shared<aasdk::channel::phonestatus::PhoneStatusService>(
            *strand_, messenger_);

        // Bluetooth channel (only if BT MAC configured)
        if (!sdrConfig_.btMac.empty()) {
            bluetoothChannel_ = std::make_shared<aasdk::channel::bluetooth::BluetoothService>(
                *strand_, messenger_);
        }

        // 5. Initiate version exchange (request v1.7 — DHU uses 1.7, aasdk defaults to 1.6)
        LOGI("Sending version request (v1.7)...");
        {
            auto message = std::make_shared<aasdk::messenger::Message>(
                aasdk::messenger::ChannelId::CONTROL,
                aasdk::messenger::EncryptionType::PLAIN,
                aasdk::messenger::MessageType::SPECIFIC);
            message->insertPayload(
                aasdk::messenger::MessageId(
                    aap_protobuf::service::control::message::ControlMessageType::MESSAGE_VERSION_REQUEST).getData());
            aasdk::common::Data versionBuffer(4, 0);
            uint16_t major = 1, minor = 7;
            versionBuffer[0] = (major >> 8) & 0xFF;
            versionBuffer[1] = major & 0xFF;
            versionBuffer[2] = (minor >> 8) & 0xFF;
            versionBuffer[3] = minor & 0xFF;
            message->insertPayload(versionBuffer);
            auto promise = aasdk::channel::SendPromise::defer(*strand_);
            promise->then([]() {},
                [this](const auto& e) { this->onChannelError(e); });
            controlChannel_->send(std::move(message), std::move(promise));
        }
        controlChannel_->receive(shared_from_this());
    });

    // Handshake watchdog: if streaming_ hasn't gone true within 15s of TCP up,
    // the phone-side proxy is stuck (AA app dead, gearhead handshake hung, etc.).
    // Abort so Kotlin's auto-reconnect tears down and tries again.
    {
        auto watchdog = std::make_shared<boost::asio::deadline_timer>(
            *ioService_, boost::posix_time::seconds(15));
        watchdog->async_wait([this, watchdog, self = shared_from_this()]
                             (const boost::system::error_code& ec) {
            if (ec || stopped_ || aborted_) return;
            if (!streaming_) {
                triggerAbort("handshake timeout (15s, no SSL/version response)");
            }
        });
    }
}

// ============================================================================
// stop()
// ============================================================================

void JniSession::stop()
{
    if (stopped_.exchange(true)) return;
    LOGI("Stopping session");
    streaming_ = false;

    if (transport_) transport_->stop();
    if (messenger_) messenger_->stop();

    ioWork_.reset();
    ioService_->stop();
    if (ioThread_.joinable()) ioThread_.join();

    // Notify Kotlin (only if onSessionStopped hasn't already been fired by triggerAbort)
    if (callbackRef_) {
        bool attached;
        JNIEnv* env = getEnv(attached);
        if (env) {
            if (!sessionStoppedFired_.exchange(true) && cbMethods_.onSessionStopped) {
                jstring reason = env->NewStringUTF(stopReason_.c_str());
                env->CallVoidMethod(callbackRef_, cbMethods_.onSessionStopped, reason);
                env->DeleteLocalRef(reason);
            }
            env->DeleteGlobalRef(callbackRef_);
            callbackRef_ = nullptr;
        }
        releaseEnv(attached);
    }
    transport_.reset();
}

// ============================================================================
// IControlServiceChannelEventHandler Ã¢â‚¬â€ AA handshake + session control
// ============================================================================

void JniSession::onVersionResponse(uint16_t majorCode, uint16_t minorCode,
                                    aap_protobuf::shared::MessageStatus status)
{
    LOGI("Version response: %d.%d status=%d", majorCode, minorCode, static_cast<int>(status));

    try {
        cryptor_->doHandshake();
        auto hsData = cryptor_->readHandshakeBuffer();
        if (!hsData.empty()) {
            auto promise = aasdk::channel::SendPromise::defer(*strand_);
            promise->then([]() {},
                [this](const auto& e) { this->onChannelError(e); });
            controlChannel_->sendHandshake(std::move(hsData), std::move(promise));
        }
    } catch (const std::exception& e) {
        LOGE("Handshake initiation failed: %s", e.what());
    }
    controlChannel_->receive(shared_from_this());
}

void JniSession::onHandshake(const aasdk::common::DataConstBuffer& payload)
{
    LOGI("Handshake data received (%zu bytes)", payload.size);

    try {
        cryptor_->writeHandshakeBuffer(payload);
        auto complete = cryptor_->doHandshake();

        if (complete) {
            LOGI("TLS handshake complete Ã¢â‚¬â€ sending AuthComplete");
            aap_protobuf::service::control::message::AuthResponse authResponse;
            authResponse.set_status(0);
            auto promise = aasdk::channel::SendPromise::defer(*strand_);
            promise->then([]() {},
                [this](const auto& e) { this->onChannelError(e); });
            controlChannel_->sendAuthComplete(authResponse, std::move(promise));
        } else {
            auto hsData = cryptor_->readHandshakeBuffer();
            if (!hsData.empty()) {
                auto promise = aasdk::channel::SendPromise::defer(*strand_);
                promise->then([]() {},
                    [this](const auto& e) { this->onChannelError(e); });
                controlChannel_->sendHandshake(std::move(hsData), std::move(promise));
            }
        }
    } catch (const std::exception& e) {
        LOGE("Handshake processing failed: %s", e.what());
    }
    controlChannel_->receive(shared_from_this());
}

void JniSession::onServiceDiscoveryRequest(
    const aap_protobuf::service::control::message::ServiceDiscoveryRequest& request)
{
    LOGI("Service discovery request -- building response");
    // Log phone SDR request -- device identity + potential unknown fields
    if (request.has_device_name()) LOGI("  device_name: %s", request.device_name().c_str());
    if (request.has_label_text()) LOGI("  label_text: %s", request.label_text().c_str());
    if (request.has_phone_info()) {
        const auto& pi = request.phone_info();
        if (pi.has_instance_id()) LOGI("  phone instance_id: %s", pi.instance_id().c_str());
        if (pi.has_connectivity_lifetime_id()) LOGI("  phone connectivity_id: %s", pi.connectivity_lifetime_id().c_str());
        logProtoRaw("PhoneInfo", pi);
    }
    logProtoRaw("SDR-Request", request);

    aap_protobuf::service::control::message::ServiceDiscoveryResponse response;
    buildServiceDiscoveryResponse(response);

    auto promise = aasdk::channel::SendPromise::defer(*strand_);
    promise->then(
        [this]() {
            LOGI("SDR sent Ã¢â‚¬â€ starting all service handlers");
            startAllHandlers();
            streaming_ = true;
            callVoidCallback(cbMethods_.onSessionStarted);
        },
        [this](const auto& e) { this->onChannelError(e); });

    controlChannel_->sendServiceDiscoveryResponse(response, std::move(promise));
    controlChannel_->receive(shared_from_this());
}

void JniSession::onAudioFocusRequest(
    const aap_protobuf::service::control::message::AudioFocusRequest& request)
{
    auto focus_type = request.audio_focus_type();
    logProtoRaw("AudioFocusReq", request);
    // Match bridge logic: map request type → response state
    aap_protobuf::service::control::message::AudioFocusStateType state;
    switch (focus_type) {
        case aap_protobuf::service::control::message::AUDIO_FOCUS_GAIN:
            state = aap_protobuf::service::control::message::AUDIO_FOCUS_STATE_GAIN;
            break;
        case aap_protobuf::service::control::message::AUDIO_FOCUS_GAIN_TRANSIENT:
        case aap_protobuf::service::control::message::AUDIO_FOCUS_GAIN_TRANSIENT_MAY_DUCK:
            state = aap_protobuf::service::control::message::AUDIO_FOCUS_STATE_GAIN_TRANSIENT;
            break;
        case aap_protobuf::service::control::message::AUDIO_FOCUS_RELEASE:
            state = aap_protobuf::service::control::message::AUDIO_FOCUS_STATE_LOSS;
            break;
        default:
            state = aap_protobuf::service::control::message::AUDIO_FOCUS_STATE_LOSS;
            break;
    }
    LOGI("Audio focus request: type=%d → state=%d", (int)focus_type, (int)state);

    aap_protobuf::service::control::message::AudioFocusNotification response;
    response.set_focus_state(state);
    auto promise = aasdk::channel::SendPromise::defer(*strand_);
    promise->then([]() {}, [this](const auto& e) { this->onChannelError(e); });
    controlChannel_->sendAudioFocusResponse(response, std::move(promise));
    controlChannel_->receive(shared_from_this());

    if (cbMethods_.onAudioFocusRequest) {
        bool attached;
        JNIEnv* env = getEnv(attached);
        if (env) {
            env->CallVoidMethod(callbackRef_, cbMethods_.onAudioFocusRequest,
                                static_cast<jint>(request.audio_focus_type()));
        }
        releaseEnv(attached);
    }
}

void JniSession::sendUnsolicitedAudioFocusGain()
{
    LOGI("Sending unsolicited AUDIO_FOCUS_STATE_GAIN");
    aap_protobuf::service::control::message::AudioFocusNotification notification;
    notification.set_focus_state(aap_protobuf::service::control::message::AUDIO_FOCUS_STATE_GAIN);
    notification.set_unsolicited(true);
    auto promise = aasdk::channel::SendPromise::defer(*strand_);
    promise->then([]() {}, [this](const auto& e) { this->onChannelError(e); });
    controlChannel_->sendAudioFocusResponse(notification, std::move(promise));
}

void JniSession::onNavigationFocusRequest(
    const aap_protobuf::service::control::message::NavFocusRequestNotification& /*request*/)
{
    LOGI("Navigation focus request Ã¢â‚¬â€ granting");
    aap_protobuf::service::control::message::NavFocusNotification response;
    response.set_focus_type(aap_protobuf::service::control::message::NAV_FOCUS_PROJECTED);
    auto promise = aasdk::channel::SendPromise::defer(*strand_);
    promise->then([]() {}, [this](const auto& e) { this->onChannelError(e); });
    controlChannel_->sendNavigationFocusResponse(response, std::move(promise));
    controlChannel_->receive(shared_from_this());
}

void JniSession::onByeByeRequest(
    const aap_protobuf::service::control::message::ByeByeRequest& request)
{
    const char* reasonStr = "byebye_unknown";
    switch (request.reason()) {
        case aap_protobuf::service::control::message::USER_SELECTION:
            reasonStr = "byebye_user_selection"; break;
        case aap_protobuf::service::control::message::DEVICE_SWITCH:
            reasonStr = "byebye_device_switch"; break;
        case aap_protobuf::service::control::message::NOT_SUPPORTED:
            reasonStr = "byebye_not_supported"; break;
        case aap_protobuf::service::control::message::NOT_CURRENTLY_SUPPORTED:
            reasonStr = "byebye_not_currently_supported"; break;
        case aap_protobuf::service::control::message::PROBE_SUPPORTED:
            reasonStr = "byebye_probe_supported"; break;
        default: break;
    }
    LOGI("ByeBye request reason=%d (%s) - disconnecting",
         static_cast<int>(request.reason()), reasonStr);
    stopReason_ = reasonStr;
    aap_protobuf::service::control::message::ByeByeResponse response;
    auto promise = aasdk::channel::SendPromise::defer(*strand_);
    // See onVideoFocusRequest: stop() joins ioThread_, so detach it to avoid
    // joining the thread we're running on.
    auto self = shared_from_this();
    promise->then(
        [self]() { std::thread([self] { self->stop(); }).detach(); },
        [self](const auto&) { std::thread([self] { self->stop(); }).detach(); });
    controlChannel_->sendShutdownResponse(response, std::move(promise));
}
void JniSession::onByeByeResponse(
    const aap_protobuf::service::control::message::ByeByeResponse& /*response*/)
{
    LOGI("ByeBye response received");
    stop();
}

void JniSession::onBatteryStatusNotification(
    const aap_protobuf::service::control::message::BatteryStatusNotification& notification)
{
    int level = notification.battery_level();
    bool charging = notification.critical_battery();

    if (cbMethods_.onPhoneBattery) {
        bool attached;
        JNIEnv* env = getEnv(attached);
        if (env) {
            env->CallVoidMethod(callbackRef_, cbMethods_.onPhoneBattery,
                                static_cast<jint>(level), static_cast<jboolean>(charging));
        }
        releaseEnv(attached);
    }
    controlChannel_->receive(shared_from_this());
}

void JniSession::onVoiceSessionRequest(
    const aap_protobuf::service::control::message::VoiceSessionNotification& request)
{
    bool active = request.status() == aap_protobuf::service::control::message::VOICE_SESSION_START;

    if (cbMethods_.onVoiceSession) {
        bool attached;
        JNIEnv* env = getEnv(attached);
        if (env) {
            env->CallVoidMethod(callbackRef_, cbMethods_.onVoiceSession,
                                static_cast<jboolean>(active));
        }
        releaseEnv(attached);
    }
    controlChannel_->receive(shared_from_this());
}

void JniSession::onPingRequest(
    const aap_protobuf::service::control::message::PingRequest& request)
{
    aap_protobuf::service::control::message::PingResponse response;
    response.set_timestamp(request.timestamp());
    auto promise = aasdk::channel::SendPromise::defer(*strand_);
    promise->then([]() {}, [](const auto&) {});
    controlChannel_->sendPingResponse(response, std::move(promise));
    controlChannel_->receive(shared_from_this());
}

void JniSession::onPingResponse(
    const aap_protobuf::service::control::message::PingResponse& /*response*/)
{
    pingOutstanding_ = false;
    controlChannel_->receive(shared_from_this());
}

void JniSession::sendPing()
{
    if (stopped_ || aborted_ || !controlChannel_) return;
    pingOutstanding_ = true;
    pingSentAtMs_ = nowMs();
    aap_protobuf::service::control::message::PingRequest request;
    request.set_timestamp(std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count());
    auto promise = aasdk::channel::SendPromise::defer(*strand_);
    promise->then([]() {}, [this](const auto& e) { this->onChannelError(e); });
    controlChannel_->sendPingRequest(request, std::move(promise));
}

void JniSession::schedulePing()
{
    if (stopped_ || aborted_) return;
    auto timer = std::make_shared<boost::asio::deadline_timer>(
        *ioService_, boost::posix_time::milliseconds(1500));
    timer->async_wait([this, timer, self = shared_from_this()](const boost::system::error_code& ec) {
        if (ec || stopped_ || aborted_) return;
        // Watchdog: outstanding ping for >8s = phone is gone, abort.
        if (pingOutstanding_) {
            int64_t ageMs = nowMs() - pingSentAtMs_.load();
            if (ageMs > 8000) {
                triggerAbort("ping timeout (" + std::to_string(ageMs) + "ms)");
                return;
            }
        } else {
            sendPing();
        }
        schedulePing();
    });
}

int64_t JniSession::nowMs()
{
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

void JniSession::triggerAbort(const std::string& reason)
{
    if (aborted_.exchange(true)) return;
    LOGW("Session abort: %s", reason.c_str());
    streaming_ = false;

    // Stop the transport so any pending reads fail and the io loop becomes idle.
    // Do NOT call stop() here — we're on the io thread and it would deadlock
    // on ioThread_.join(). Kotlin's onSessionStopped handler will call
    // nativeStopSession asynchronously to do the real teardown.
    if (transport_) transport_->stop();

    if (sessionStoppedFired_.exchange(true)) return;
    if (callbackRef_ && cbMethods_.onSessionStopped) {
        bool attached;
        JNIEnv* env = getEnv(attached);
        if (env) {
            jstring r = env->NewStringUTF(reason.c_str());
            env->CallVoidMethod(callbackRef_, cbMethods_.onSessionStopped, r);
            env->DeleteLocalRef(r);
        }
        releaseEnv(attached);
    }
}

void JniSession::onChannelError(const aasdk::error::Error& e)
{
    reportChannelError("control", e);
}

void JniSession::reportChannelError(const char* channelName, const aasdk::error::Error& e)
{
    int64_t now = nowMs();
    bool shouldLog = true;
    bool escalate = false;
    int totalInWindow = 0;

    {
        std::lock_guard<std::mutex> lk(errorMu_);
        // Coalesce: at most one log per channel per second (with count summary)
        auto it = lastLogPerChannel_.find(channelName);
        if (it != lastLogPerChannel_.end() && (now - it->second) < 1000) {
            shouldLog = false;
        } else {
            lastLogPerChannel_[channelName] = now;
        }
        // Escalation: count errors across all channels in a 2s window
        if (firstErrorAtMs_ == 0 || (now - firstErrorAtMs_) > 2000) {
            firstErrorAtMs_ = now;
            errorCount_ = 1;
        } else {
            errorCount_++;
        }
        totalInWindow = errorCount_;
        // 5+ channel errors within 2s = remote disconnect, abort.
        if (errorCount_ >= 5) escalate = true;
    }

    if (shouldLog) {
        LOGE("%s channel error: %s (%d errors in 2s window)",
             channelName, e.what(), totalInWindow);
    }

    if (cbMethods_.onError && callbackRef_ && shouldLog) {
        bool attached;
        JNIEnv* env = getEnv(attached);
        if (env) {
            jstring msg = env->NewStringUTF(e.what());
            env->CallVoidMethod(callbackRef_, cbMethods_.onError, msg);
            env->DeleteLocalRef(msg);
        }
        releaseEnv(attached);
    }

    if (escalate && !aborted_) {
        triggerAbort("channel-error escalation (" + std::to_string(totalInWindow) +
                     " errors in 2s, last on " + std::string(channelName) + ")");
    }
}

// ============================================================================
// IVideoMediaSinkServiceEventHandler Ã¢â‚¬â€ video from phone
// ============================================================================

void JniSession::onChannelOpenRequest(
    const aap_protobuf::service::control::message::ChannelOpenRequest& request)
{
    LOGI("Video channel open request");
    logProtoRaw("VideoChannelOpen", request);
    aap_protobuf::service::control::message::ChannelOpenResponse response;
    response.set_status(aap_protobuf::shared::STATUS_SUCCESS);
    auto promise = aasdk::channel::SendPromise::defer(*strand_);
    promise->then([]() {}, [this](const auto& e) { this->onChannelError(e); });
    videoChannel_->sendChannelOpenResponse(response, std::move(promise));
    videoChannel_->receive(shared_from_this());
}

void JniSession::onMediaChannelSetupRequest(
    const aap_protobuf::service::media::shared::message::Setup& request)
{
    LOGI("Video setup from phone: type=%d (0=H264 1=H265 2=VP9)", request.type());
    logProtoRaw("VideoSetup", request);
    negotiatedCodecType_ = request.type();

    // Notify Kotlin of the negotiated codec type so the decoder configures correctly
    if (cbMethods_.onVideoCodecConfigured && callbackRef_) {
        bool attached;
        JNIEnv* env = getEnv(attached);
        if (env) {
            env->CallVoidMethod(callbackRef_, cbMethods_.onVideoCodecConfigured,
                                static_cast<jint>(request.type()));
        }
        releaseEnv(attached);
    }

    aap_protobuf::service::media::shared::message::Config config;
    config.set_status(aap_protobuf::service::media::shared::message::Config::STATUS_READY);
    config.set_max_unacked(30);
    if (sdrConfig_.autoNegotiate) {
        // Auto mode: accept every config we advertised.
        int n = advertisedVideoConfigCount_.load();
        if (n <= 0) n = 1;
        for (int i = 0; i < n; i++) {
            config.add_configuration_indices(i);
        }
    } else {
        // Manual mode: only accept the single configured resolution
        config.add_configuration_indices(0);
    }
    auto promise = aasdk::channel::SendPromise::defer(*strand_);
    promise->then([]() {}, [this](const auto& e) { this->onChannelError(e); });
    videoChannel_->sendChannelSetupResponse(config, std::move(promise));

    // Send VIDEO_FOCUS_PROJECTED immediately after setup — the phone's
    // ProjectionWindowManager waits for this before it can start projection.
    // unsolicited=true: we're declaring our state proactively (not in reply).
    currentVideoFocus_.store(
        aap_protobuf::service::media::video::message::VIDEO_FOCUS_PROJECTED);
    LOGI("Sending VIDEO_FOCUS_PROJECTED after setup (unsolicited)");
    aap_protobuf::service::media::video::message::VideoFocusNotification focus;
    focus.set_focus(aap_protobuf::service::media::video::message::VIDEO_FOCUS_PROJECTED);
    focus.set_unsolicited(true);
    auto focusPromise = aasdk::channel::SendPromise::defer(*strand_);
    focusPromise->then([]() {}, [this](const auto& e) { this->onChannelError(e); });
    videoChannel_->sendVideoFocusIndication(focus, std::move(focusPromise));

    videoChannel_->receive(shared_from_this());
}

void JniSession::onMediaChannelStartIndication(
    const aap_protobuf::service::media::shared::message::Start& indication)
{
    LOGI("Video stream starting: session=%d config_idx=%d",
         indication.session_id(), indication.configuration_index());
    logProtoRaw("VideoStart", indication);

    currentVideoFocus_.store(
        aap_protobuf::service::media::video::message::VIDEO_FOCUS_PROJECTED);
    aap_protobuf::service::media::video::message::VideoFocusNotification focus;
    focus.set_focus(aap_protobuf::service::media::video::message::VIDEO_FOCUS_PROJECTED);
    focus.set_unsolicited(false);
    auto promise = aasdk::channel::SendPromise::defer(*strand_);
    promise->then([]() {}, [this](const auto& e) { this->onChannelError(e); });
    videoChannel_->sendVideoFocusIndication(focus, std::move(promise));

    videoChannel_->receive(shared_from_this());
}

void JniSession::onMediaChannelStopIndication(
    const aap_protobuf::service::media::shared::message::Stop& /*indication*/)
{
    LOGI("Video stream stopping");
    videoChannel_->receive(shared_from_this());
}

void JniSession::onMediaWithTimestampIndication(
    aasdk::messenger::Timestamp::ValueType timestamp,
    const aasdk::common::DataConstBuffer& buffer)
{
    // Hot path Ã¢â‚¬â€ video frame. Send ACK immediately (flow control).
    aap_protobuf::service::media::source::message::Ack ack;
    ack.set_session_id(0);
    ack.set_ack(1);
    auto promise = aasdk::channel::SendPromise::defer(*strand_);
    promise->then([]() {}, [](const auto&) {});
    videoChannel_->sendMediaAckIndication(ack, std::move(promise));

    // Detect IDR (keyframe) and codec config from NAL headers.
    // Use the negotiated codec type to avoid false positives — H.265 NAL bytes
    // can accidentally match H.264 types (e.g., H.265 IDR_N_LP 0x28 matches H.264 PPS 8).
    bool isKeyFrame = false;
    bool isCodecConfig = false;
    int codec = negotiatedCodecType_.load();
    // Scan ALL NAL units in the buffer, not just the first one.
    // H.265 phones often send VPS+SPS+PPS+IDR combined in a single buffer.
    // Only checking the first NAL (VPS) would miss the embedded IDR, causing
    // the Kotlin side to never queue the keyframe → green video for 30-45s
    // until the phone's natural GOP interval sends a standalone IDR.
    if (buffer.size >= 5) {
        const uint8_t* d = buffer.cdata;
        size_t pos = 0;
        while (pos < buffer.size - 3) {
            // Find next start code (0x000001 or 0x00000001)
            size_t nalStart = 0;
            bool found = false;
            if (d[pos] == 0 && d[pos+1] == 0) {
                if (pos + 3 < buffer.size && d[pos+2] == 0 && d[pos+3] == 1) {
                    nalStart = pos + 4;
                    found = true;
                } else if (d[pos+2] == 1) {
                    nalStart = pos + 3;
                    found = true;
                }
            }
            if (!found) { pos++; continue; }
            if (nalStart >= buffer.size) break;

            if (codec == 7) {
                uint8_t hevcNalType = (d[nalStart] >> 1) & 0x3F;
                if (hevcNalType >= 16 && hevcNalType <= 21) isKeyFrame = true;
                if (hevcNalType == 32 || hevcNalType == 33 || hevcNalType == 34) isCodecConfig = true;
            } else {
                uint8_t nalType = d[nalStart] & 0x1F;
                if (nalType == 5) isKeyFrame = true;
                if (nalType == 7 || nalType == 8) isCodecConfig = true;
            }
            // Early exit if both flags found
            if (isKeyFrame && isCodecConfig) break;
            pos = nalStart + 1;
        }
    }

    // Build flags matching VideoFrame.FLAG_* constants
    jint flags = 0;
    if (isKeyFrame) flags |= 0x0001;     // FLAG_KEYFRAME
    if (isCodecConfig) flags |= 0x0002;  // FLAG_CODEC_CONFIG

    // Log combined config+keyframe detection (H.265 VPS+SPS+PPS+IDR in one buffer)
    if (isKeyFrame && isCodecConfig) {
        LOGI("Video frame: combined config+keyframe detected (%zu bytes, codec=%d)",
             buffer.size, codec);
    }

    // Dispatch to Kotlin
    if (cbMethods_.onVideoFrame && callbackRef_) {
        bool attached;
        JNIEnv* env = getEnv(attached);
        if (env) {
            jbyteArray jdata = env->NewByteArray(static_cast<jsize>(buffer.size));
            env->SetByteArrayRegion(jdata, 0, static_cast<jsize>(buffer.size),
                                    reinterpret_cast<const jbyte*>(buffer.cdata));
            env->CallVoidMethod(callbackRef_, cbMethods_.onVideoFrame,
                                jdata, static_cast<jlong>(timestamp),
                                static_cast<jint>(sdrConfig_.videoWidth),
                                static_cast<jint>(sdrConfig_.videoHeight),
                                flags);
            env->DeleteLocalRef(jdata);
        }
        releaseEnv(attached);
    }

    videoChannel_->receive(shared_from_this());
}

void JniSession::onMediaIndication(const aasdk::common::DataConstBuffer& buffer)
{
    onMediaWithTimestampIndication(0, buffer);
}

void JniSession::onVideoFocusRequest(
    const aap_protobuf::service::media::video::message::VideoFocusRequestNotification& request)
{
    int mode = request.has_mode() ? static_cast<int>(request.mode()) : -1;
    int reason = request.has_reason() ? static_cast<int>(request.reason()) : -1;
    LOGI("Video focus request from phone: mode=%d reason=%d "
         "(focus 1=PROJECTED 2=NATIVE 3=NATIVE_TRANSIENT; "
         "reason 1=PHONE_SCREEN_OFF 2=LAUNCH_NATIVE)", mode, reason);

    // VIDEO_FOCUS_NATIVE = user tapped "Exit" in the AA app launcher on the
    // phone (the phone never sends ByeBye for this; it just asks us to show
    // our native UI). Mirror headunit-revived's AapControl.kt behavior: ack
    // with NATIVE focus, mark this as a user exit, and tear down the session.
    // The Kotlin onSessionStopped("byebye_user_selection") handler then drives
    // MainActivity to finishAndRemoveTask() (returns AAOS to its launcher).
    if (mode == static_cast<int>(aap_protobuf::service::media::video::message::VIDEO_FOCUS_NATIVE)) {
        LOGI("VIDEO_FOCUS_NATIVE received - treating as user Exit, stopping session");
        currentVideoFocus_.store(
            aap_protobuf::service::media::video::message::VIDEO_FOCUS_NATIVE);
        aap_protobuf::service::media::video::message::VideoFocusNotification focus;
        focus.set_focus(aap_protobuf::service::media::video::message::VIDEO_FOCUS_NATIVE);
        focus.set_unsolicited(false);
        auto promise = aasdk::channel::SendPromise::defer(*strand_);
        // stop() joins ioThread_; if we called it directly from this promise
        // callback we'd be joining the thread we're running on (UB / silent
        // abort — observed: the Kotlin onSessionStopped JNI call never fires,
        // so cluster teardown is skipped). Detach onto a worker so stop() can
        // safely join the io_thread.
        auto self = shared_from_this();
        promise->then(
            [self]() {
                self->stopReason_ = "byebye_user_selection";
                std::thread([self] { self->stop(); }).detach();
            },
            [self](const auto&) {
                self->stopReason_ = "byebye_user_selection";
                std::thread([self] { self->stop(); }).detach();
            });
        videoChannel_->sendVideoFocusIndication(focus, std::move(promise));
        return;
    }

    // PROJECTED / NATIVE_TRANSIENT (e.g. day/night theme transitions, phone
    // screen off) — keep projecting. Reply with our current focus state.
    int reply = currentVideoFocus_.load();
    LOGI("Replying with current focus=%d", reply);
    aap_protobuf::service::media::video::message::VideoFocusNotification focus;
    focus.set_focus(
        static_cast<aap_protobuf::service::media::video::message::VideoFocusMode>(reply));
    focus.set_unsolicited(false);
    auto promise = aasdk::channel::SendPromise::defer(*strand_);
    promise->then([]() {}, [this](const auto& e) { this->onChannelError(e); });
    videoChannel_->sendVideoFocusIndication(focus, std::move(promise));

    videoChannel_->receive(shared_from_this());
}

// ============================================================================
// startAllHandlers() Ã¢â‚¬â€ begin receiving on all service channels
// ============================================================================

void JniSession::startAllHandlers()
{
    LOGI("Starting all service handlers");

    // Video channel — must start receiving before phone sends channel open request
    videoChannel_->receive(shared_from_this());

    // Audio handlers (3 instances Ã¢â‚¬â€ same class, different channel types)
    mediaAudioHandler_ = std::make_shared<JniAudioSinkHandler>(
        *strand_, mediaAudioChannel_, *this, JniAudioSinkHandler::AudioType::Media);
    mediaAudioHandler_->start();

    guidanceAudioHandler_ = std::make_shared<JniAudioSinkHandler>(
        *strand_, guidanceAudioChannel_, *this, JniAudioSinkHandler::AudioType::Guidance);
    guidanceAudioHandler_->start();

    systemAudioHandler_ = std::make_shared<JniAudioSinkHandler>(
        *strand_, systemAudioChannel_, *this, JniAudioSinkHandler::AudioType::System);
    systemAudioHandler_->start();

    // Sensor handler
    sensorHandler_ = std::make_shared<JniSensorHandler>(*strand_, sensorChannel_, *this);
    sensorHandler_->start();

    // Input handler
    inputHandler_ = std::make_shared<JniInputHandler>(*strand_, inputChannel_, *this);
    inputHandler_->start();

    // Mic handler
    micHandler_ = std::make_shared<JniMicHandler>(*strand_, micChannel_, *this);
    micHandler_->start();

    // Navigation status handler
    navHandler_ = std::make_shared<JniNavStatusHandler>(*strand_, navChannel_, *this);
    navHandler_->start();

    // Media playback status handler
    if (mediaStatusChannel_) {
        mediaStatusHandler_ = std::make_shared<JniMediaStatusHandler>(
            *strand_, mediaStatusChannel_, *this);
        mediaStatusHandler_->start();
    }

    // Phone status handler
    if (phoneStatusChannel_) {
        phoneStatusHandler_ = std::make_shared<JniPhoneStatusHandler>(
            *strand_, phoneStatusChannel_, *this);
        phoneStatusHandler_->start();
    }

    // Bluetooth handler
    if (bluetoothChannel_) {
        bluetoothHandler_ = std::make_shared<JniBluetoothHandler>(
            *strand_, bluetoothChannel_, *this);
        bluetoothHandler_->start();
    }

    LOGI("All %d handlers started", 9);

    // Send initial ping (bridge does this — phone expects HU to initiate pings)
    sendPing();
    schedulePing();

    // Don't send VideoFocusIndication here — wait for phone to open the video channel first.
    // The phone's projection state machine expects to control video focus timing.
}

// ============================================================================
// buildServiceDiscoveryResponse() Ã¢â‚¬â€ tell phone what we support
// ============================================================================

void JniSession::buildServiceDiscoveryResponse(
    aap_protobuf::service::control::message::ServiceDiscoveryResponse& response)
{
    response.mutable_channels()->Reserve(256);

    // v1.6 protocol fields (matches bridge-mode live_session.cpp)
    response.set_driver_position(
        sdrConfig_.driverPosition == 1
            ? aap_protobuf::service::control::message::DRIVER_POSITION_RIGHT
            : aap_protobuf::service::control::message::DRIVER_POSITION_LEFT);
    response.set_display_name("OpenAutoLink");
    response.set_probe_for_support(false);
    response.set_can_play_native_media_during_vr(false);

    // Connection config with ping
    auto* connCfg = response.mutable_connection_configuration();
    auto* ping = connCfg->mutable_ping_configuration();
    ping->set_timeout_ms(5000);
    ping->set_interval_ms(1500);
    ping->set_high_latency_threshold_ms(500);
    ping->set_tracked_ping_count(5);

    // HeadUnitInfo (v1.6)
    auto* hui = response.mutable_headunit_info();
    hui->set_make(sdrConfig_.vehicleMake);
    hui->set_model(sdrConfig_.vehicleModel);
    hui->set_year(sdrConfig_.vehicleYear);
    hui->set_vehicle_id("OAL-JNI-1");
    hui->set_head_unit_make("OpenAutoLink");
    hui->set_head_unit_model("Direct JNI");
    hui->set_head_unit_software_build("1");
    hui->set_head_unit_software_version("1.0");

    // Deprecated fields for backward compat (bridge sets these too)
    response.set_head_unit_make("OpenAutoLink");
    response.set_model(sdrConfig_.vehicleModel);
    response.set_year(sdrConfig_.vehicleYear);
    response.set_vehicle_id("OAL-JNI-1");
    response.set_head_unit_model("Direct JNI");
    response.set_head_unit_software_build("1");
    response.set_head_unit_software_version("1.0");

    // Session configuration (bitmask: bit0=hide clock, bit1=hide signal, bit2=hide battery)
    {
        int session_config = 0;
        if (sdrConfig_.hideClock)   session_config |= 1;
        if (sdrConfig_.hideSignal)  session_config |= 2;
        if (sdrConfig_.hideBattery) session_config |= 4;
        response.set_session_configuration(session_config);
    }

    // ---- Video channel (matches bridge exactly — NO audio_type) ----
    { auto* svc = response.add_channels();
      svc->set_id(2); // VIDEO — Java uses 2 (MEDIA_SINK), works with localhost proxy
      auto* ms = svc->mutable_media_sink_service();
      ms->set_available_type(aap_protobuf::service::media::shared::message::MEDIA_CODEC_VIDEO_H264_BP);
      ms->set_available_while_in_call(true);

      using VRes = aap_protobuf::service::media::sink::message::VideoCodecResolutionType;
      using VFps = aap_protobuf::service::media::sink::message::VideoFrameRateType;
      auto fps = sdrConfig_.videoFps >= 60 ? VFps::VIDEO_FPS_60 : VFps::VIDEO_FPS_30;

      // Codec frame size for each VRes enum value. Index 0 unused. 1-5 land-
      // scape tiers, 6-9 portrait variants of 720p/1080p/1440p/4K.
      struct CodecDims { int w, h; };
      static constexpr CodecDims kDims[] = {
          {0, 0},                 // 0 unused
          {800, 480},             // 1 VIDEO_800x480
          {1280, 720},            // 2 VIDEO_1280x720
          {1920, 1080},           // 3 VIDEO_1920x1080
          {2560, 1440},           // 4 VIDEO_2560x1440
          {3840, 2160},           // 5 VIDEO_3840x2160
          {720, 1280},            // 6 VIDEO_720x1280
          {1080, 1920},           // 7 VIDEO_1080x1920
          {1440, 2560},           // 8 VIDEO_1440x2560
          {2160, 3840},           // 9 VIDEO_2160x3840
      };

      // Auto-margin: pick wm/hm so the inner content rect of the codec
      // frame has the same aspect ratio as the panel. Renderer then zooms
      // and clips the margin pixels off-screen → uniform square-pixel scale.
      // Returns (wm, hm). Both 0 when codec aspect already matches panel.
      auto autoMargins = [&](int codecW, int codecH) -> std::pair<int,int> {
          if (sdrConfig_.panelWidth <= 0 || sdrConfig_.panelHeight <= 0) {
              return {0, 0};
          }
          double codecAR = static_cast<double>(codecW) / codecH;
          double panelAR = static_cast<double>(sdrConfig_.panelWidth) /
                           sdrConfig_.panelHeight;
          if (std::abs(codecAR - panelAR) / panelAR < 0.005) return {0, 0};
          if (codecAR > panelAR) {
              // Codec wider than panel → trim left/right
              int innerW = static_cast<int>(std::lround(codecH * panelAR));
              int wm = std::max(0, codecW - innerW);
              return {wm, 0};
          } else {
              // Codec taller than panel → trim top/bottom
              int innerH = static_cast<int>(std::lround(codecW / panelAR));
              int hm = std::max(0, codecH - innerH);
              return {0, hm};
          }
      };

      bool hasSafeArea = sdrConfig_.safeAreaTop > 0 || sdrConfig_.safeAreaBottom > 0 ||
                         sdrConfig_.safeAreaLeft > 0 || sdrConfig_.safeAreaRight > 0;
      auto applySafeArea = [&](auto* vc) {
          if (hasSafeArea) {
              auto* insets = vc->mutable_ui_config()->mutable_content_insets();
              if (sdrConfig_.safeAreaTop > 0) insets->set_top(sdrConfig_.safeAreaTop);
              if (sdrConfig_.safeAreaBottom > 0) insets->set_bottom(sdrConfig_.safeAreaBottom);
              if (sdrConfig_.safeAreaLeft > 0) insets->set_left(sdrConfig_.safeAreaLeft);
              if (sdrConfig_.safeAreaRight > 0) insets->set_right(sdrConfig_.safeAreaRight);
          }
      };

      auto applyVideoConfig = [&](auto* vc, int tier) {
          // Margins:
          //  - autoMargins=true (default): compute from panel AR per tier.
          //  - autoMargins=false: send literal user-set marginWidth/Height
          //    (0 = no margins, decoder stretches full codec frame).
          int wm = 0;
          int hm = 0;
          if (sdrConfig_.autoMargins) {
              auto pr = autoMargins(kDims[tier].w, kDims[tier].h);
              wm = pr.first;
              hm = pr.second;
          } else {
              wm = sdrConfig_.marginWidth;
              hm = sdrConfig_.marginHeight;
          }
          if (wm > 0) vc->set_width_margin(wm);
          if (hm > 0) vc->set_height_margin(hm);
          // Mirror GM: split the margin half/half via UiConfig.margins so
          // the phone centers its UI inside the inner rect. Without this,
          // the phone is free to place the entire margin on one side
          // (observed: bottom-only) which doesn't match our renderer's
          // centred zoom-and-clip and looks "shifted".
          if (wm > 0 || hm > 0) {
              auto* margins = vc->mutable_ui_config()->mutable_margins();
              if (wm > 0) {
                  margins->set_left(wm / 2);
                  margins->set_right((wm + 1) / 2);
              }
              if (hm > 0) {
                  margins->set_top(hm / 2);
                  margins->set_bottom((hm + 1) / 2);
              }
          }
          if (sdrConfig_.pixelAspectE4 > 0) vc->set_pixel_aspect_ratio_e4(sdrConfig_.pixelAspectE4);
          if (sdrConfig_.realDensity > 0) vc->set_real_density(sdrConfig_.realDensity);
          if (sdrConfig_.viewingDistanceMm > 0) vc->set_viewing_distance(sdrConfig_.viewingDistanceMm);
          if (sdrConfig_.decoderAdditionalDepth > 0) vc->set_decoder_additional_depth(sdrConfig_.decoderAdditionalDepth);
          applySafeArea(vc);
      };

      auto computeDensity = [&](int tier) -> int {
          if (sdrConfig_.targetLayoutWidthDp > 0) {
              int dpi = (kDims[tier].w * 160) / sdrConfig_.targetLayoutWidthDp;
              return std::max(dpi, 80);
          }
          return sdrConfig_.videoDpi;
      };

      if (sdrConfig_.autoNegotiate) {
          // Auto mode: advertise multiple resolution tiers for the user's
          // chosen codec only. We do NOT mix H.264 + H.265 in the same SDR:
          // the phone picks the first acceptable config, which historically
          // meant H.265 always won and triggered the ~30-60s "green startup"
          // from tiny seed IDRs. By advertising only one codec, the phone is
          // forced into that family.
          //
          // Codec preference (videoCodec):
          //   "h264" (default, recommended) → safe, instant clean startup, capped at 1080p
          //   "h265"                        → up to 4K, but tolerates green-startup window
          //   else → fall back to h264
          //
          // Mirrors GM's GALDisplayManager.getVideoConfigList() which only
          // ever advertises H.264 BP at [1920x1080, 1280x720, 800x480].
          bool useH265 = (sdrConfig_.videoCodec == "h265" ||
                          sdrConfig_.videoCodec == "hevc");
          bool portrait = (sdrConfig_.panelWidth > 0 &&
                           sdrConfig_.panelHeight > 0 &&
                           sdrConfig_.panelWidth < sdrConfig_.panelHeight);
          // Tier order: largest-to-smallest. Phone iterates and picks the
          // first it accepts. H.264 BP is reliable up to 1080p; H.265 is
          // needed for 1440p / 4K.
          int tiers[5];
          int tierCount;
          auto codecType = useH265
              ? aap_protobuf::service::media::shared::message::MEDIA_CODEC_VIDEO_H265
              : aap_protobuf::service::media::shared::message::MEDIA_CODEC_VIDEO_H264_BP;
          if (useH265) {
              if (portrait) {
                  tiers[0] = 9; tiers[1] = 8; tiers[2] = 7; tiers[3] = 6;
                  tierCount = 4;
              } else {
                  tiers[0] = 5; tiers[1] = 4; tiers[2] = 3; tiers[3] = 2; tiers[4] = 1;
                  tierCount = 5;
              }
          } else {
              // H.264 BP: cap at 1080p. Match GM exactly for landscape.
              if (portrait) {
                  tiers[0] = 7; tiers[1] = 6;
                  tierCount = 2;
              } else {
                  tiers[0] = 3; tiers[1] = 2; tiers[2] = 1;  // 1080p, 720p, 480p
                  tierCount = 3;
              }
          }
          LOGI("Auto SDR: codec=%s tiers=%d portrait=%d",
               useH265 ? "H.265" : "H.264 BP", tierCount, portrait ? 1 : 0);
          for (int i = 0; i < tierCount; ++i) {
              int t = tiers[i];
              auto* vc = ms->add_video_configs();
              vc->set_codec_resolution(static_cast<VRes>(t));
              vc->set_frame_rate(fps);
              vc->set_density(computeDensity(t));
              vc->set_video_codec_type(codecType);
              applyVideoConfig(vc, t);
          }
          advertisedVideoConfigCount_.store(tierCount);
      } else {
          // Manual mode: single config at the selected resolution and codec.
          // Pick tier by exact W×H match against kDims (supports all 9 enum
          // values). Falls back to landscape 1080p if the requested combo
          // isn't a known AA enum value.
          int tier = 3;  // fallback VIDEO_1920x1080
          for (int i = 1; i <= 9; ++i) {
              if (kDims[i].w == sdrConfig_.videoWidth &&
                  kDims[i].h == sdrConfig_.videoHeight) {
                  tier = i;
                  break;
              }
          }
          auto codecType = (sdrConfig_.videoCodec == "h264")
              ? aap_protobuf::service::media::shared::message::MEDIA_CODEC_VIDEO_H264_BP
              : aap_protobuf::service::media::shared::message::MEDIA_CODEC_VIDEO_H265;
          auto* vc = ms->add_video_configs();
          vc->set_codec_resolution(static_cast<VRes>(tier));
          vc->set_frame_rate(fps);
          vc->set_density(computeDensity(tier));
          vc->set_video_codec_type(codecType);
          applyVideoConfig(vc, tier);
          advertisedVideoConfigCount_.store(1);
      }
    }

    // ---- Media audio (48kHz stereo) ----
    { auto* svc = response.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::MEDIA_SINK_MEDIA_AUDIO));
      auto* ms = svc->mutable_media_sink_service();
      ms->set_available_type(aap_protobuf::service::media::shared::message::MEDIA_CODEC_AUDIO_PCM);
      ms->set_audio_type(aap_protobuf::service::media::sink::message::AUDIO_STREAM_MEDIA);
      ms->set_available_while_in_call(true);
      auto* ac = ms->add_audio_configs();
      ac->set_sampling_rate(48000); ac->set_number_of_bits(16); ac->set_number_of_channels(2);
    }

    // ---- Guidance/Speech audio (16kHz mono) ----
    { auto* svc = response.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::MEDIA_SINK_GUIDANCE_AUDIO));
      auto* ms = svc->mutable_media_sink_service();
      ms->set_available_type(aap_protobuf::service::media::shared::message::MEDIA_CODEC_AUDIO_PCM);
      ms->set_audio_type(aap_protobuf::service::media::sink::message::AUDIO_STREAM_GUIDANCE);
      ms->set_available_while_in_call(true);
      auto* ac = ms->add_audio_configs();
      ac->set_sampling_rate(16000); ac->set_number_of_bits(16); ac->set_number_of_channels(1);
    }

    // ---- System audio (16kHz mono) ----
    { auto* svc = response.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::MEDIA_SINK_SYSTEM_AUDIO));
      auto* ms = svc->mutable_media_sink_service();
      ms->set_available_type(aap_protobuf::service::media::shared::message::MEDIA_CODEC_AUDIO_PCM);
      ms->set_audio_type(aap_protobuf::service::media::sink::message::AUDIO_STREAM_SYSTEM_AUDIO);
      ms->set_available_while_in_call(true);
      auto* ac = ms->add_audio_configs();
      ac->set_sampling_rate(16000); ac->set_number_of_bits(16); ac->set_number_of_channels(1);
    }

    // NOTE: MEDIA_SINK_TELEPHONY_AUDIO intentionally not advertised — it is
    // dead infrastructure in phone-side AA. See docs/aa-undocumented-features.md.

    // ---- Mic input (16kHz mono) ----
    { auto* svc = response.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::MEDIA_SOURCE_MICROPHONE));
      auto* msrc = svc->mutable_media_source_service();
      msrc->set_available_type(aap_protobuf::service::media::shared::message::MEDIA_CODEC_AUDIO_PCM);
      msrc->set_available_while_in_call(true);
      auto* ac = msrc->mutable_audio_config();
      ac->set_sampling_rate(16000); ac->set_number_of_bits(16); ac->set_number_of_channels(1);
    }

    // ---- Sensor channel ----
    { auto* svc = response.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::SENSOR));
      namespace ST = aap_protobuf::service::sensorsource::message;
      auto* ss = svc->mutable_sensor_source_service();
      ss->add_sensors()->set_sensor_type(ST::SENSOR_DRIVING_STATUS_DATA);
      ss->add_sensors()->set_sensor_type(ST::SENSOR_LOCATION);
      ss->add_sensors()->set_sensor_type(ST::SENSOR_NIGHT_MODE);
      ss->add_sensors()->set_sensor_type(ST::SENSOR_SPEED);
      ss->add_sensors()->set_sensor_type(ST::SENSOR_GEAR);
      ss->add_sensors()->set_sensor_type(ST::SENSOR_PARKING_BRAKE);
      ss->add_sensors()->set_sensor_type(ST::SENSOR_FUEL);
      ss->add_sensors()->set_sensor_type(ST::SENSOR_ODOMETER);
      ss->add_sensors()->set_sensor_type(ST::SENSOR_ENVIRONMENT_DATA);
      ss->add_sensors()->set_sensor_type(ST::SENSOR_COMPASS);
      ss->add_sensors()->set_sensor_type(ST::SENSOR_ACCELEROMETER_DATA);
      ss->add_sensors()->set_sensor_type(ST::SENSOR_GYROSCOPE_DATA);
      ss->add_sensors()->set_sensor_type(ST::SENSOR_GPS_SATELLITE_DATA);
      ss->add_sensors()->set_sensor_type(ST::SENSOR_RPM);
      ss->add_sensors()->set_sensor_type(ST::SENSOR_VEHICLE_ENERGY_MODEL);

      // Fuel types and EV connector types — required for phone to recognize
      // this as an EV and request sensor type 23 (VehicleEnergyModel).
      // Falls back to ELECTRIC + J1772/COMBO_1 if VHAL data not yet available
      // (matches bridge-mode fallback logic).
      namespace FT = aap_protobuf::service::sensorsource::message;
      if (sdrConfig_.fuelTypes.empty()) {
          ss->add_supported_fuel_types(FT::FUEL_TYPE_ELECTRIC);
      } else {
          for (int ft : sdrConfig_.fuelTypes) {
              ss->add_supported_fuel_types(static_cast<FT::FuelType>(ft));
          }
      }
      if (sdrConfig_.evConnectorTypes.empty()) {
          ss->add_supported_ev_connector_types(FT::EV_CONNECTOR_TYPE_J1772);
          ss->add_supported_ev_connector_types(FT::EV_CONNECTOR_TYPE_COMBO_1);
      } else {
          for (int ct : sdrConfig_.evConnectorTypes) {
              ss->add_supported_ev_connector_types(static_cast<FT::EvConnectorType>(ct));
          }
      }
      LOGI("SDR sensor: %zu fuel types, %zu ev connectors",
           sdrConfig_.fuelTypes.empty() ? 1u : sdrConfig_.fuelTypes.size(),
           sdrConfig_.evConnectorTypes.empty() ? 2u : sdrConfig_.evConnectorTypes.size());
    }

    // ---- Input channel ----
    { auto* svc = response.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::INPUT_SOURCE));
      auto* is = svc->mutable_input_source_service();
      for (int kc : {84, 85, 86, 87, 88, 89, 90, 126, 127}) {
          is->add_keycodes_supported(kc);
      }
      auto* ts = is->add_touchscreen();
      ts->set_width(sdrConfig_.videoWidth);
      ts->set_height(sdrConfig_.videoHeight);
      ts->set_type(aap_protobuf::service::inputsource::message::CAPACITIVE);
    }

    // ---- Bluetooth channel ----
    if (!sdrConfig_.btMac.empty()) {
        auto* svc = response.add_channels();
        svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::BLUETOOTH));
        auto* bs = svc->mutable_bluetooth_service();
        bs->set_car_address(sdrConfig_.btMac);
        bs->add_supported_pairing_methods(
            aap_protobuf::service::bluetooth::message::BLUETOOTH_PAIRING_PIN);
        bs->add_supported_pairing_methods(
            aap_protobuf::service::bluetooth::message::BLUETOOTH_PAIRING_NUMERIC_COMPARISON);
    }

    // ---- Navigation Status (IMAGE mode, matches bridge) ----
    { auto* svc = response.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::NAVIGATION_STATUS));
      auto* ns = svc->mutable_navigation_status_service();
      ns->set_minimum_interval_ms(500);
      ns->set_type(aap_protobuf::service::navigationstatus::NavigationStatusService::IMAGE);
      auto* img = ns->mutable_image_options();
      img->set_width(256);
      img->set_height(256);
      img->set_colour_depth_bits(32);
    }

    // ---- Media Playback Status ----
    { auto* svc = response.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::MEDIA_PLAYBACK_STATUS));
      svc->mutable_media_playback_service();
    }

    // ---- Phone Status ----
    { auto* svc = response.add_channels();
      svc->set_id(static_cast<int32_t>(aasdk::messenger::ChannelId::PHONE_STATUS));
      svc->mutable_phone_status_service();
    }

    LOGI("SDR built: %d channels, %d bytes", response.channels_size(),
         static_cast<int>(response.ByteSizeLong()));

    // Debug: dump each channel's key fields
    for (int i = 0; i < response.channels_size(); i++) {
        const auto& ch = response.channels(i);
        if (ch.has_media_sink_service()) {
            const auto& ms = ch.media_sink_service();
            LOGI("  ch[%d] id=%d media_sink: avail_type=%d audio_type=%d "
                 "video_configs=%d audio_configs=%d in_call=%d",
                 i, ch.id(), (int)ms.available_type(),
                 ms.has_audio_type() ? (int)ms.audio_type() : -1,
                 ms.video_configs_size(), ms.audio_configs_size(),
                 (int)ms.available_while_in_call());
            for (int v = 0; v < ms.video_configs_size(); v++) {
                const auto& vc = ms.video_configs(v);
                LOGI("    vc[%d] res=%d fps=%d dpi=%d codec=%d pixel_aspect=%d",
                     v, (int)vc.codec_resolution(), (int)vc.frame_rate(),
                     vc.density(), (int)vc.video_codec_type(),
                     vc.has_pixel_aspect_ratio_e4() ? vc.pixel_aspect_ratio_e4() : 0);
            }
        } else if (ch.has_media_source_service()) {
            LOGI("  ch[%d] id=%d media_source: avail_type=%d", i, ch.id(),
                 (int)ch.media_source_service().available_type());
        } else if (ch.has_sensor_source_service()) {
            LOGI("  ch[%d] id=%d sensor: %d types", i, ch.id(),
                 ch.sensor_source_service().sensors_size());
        } else if (ch.has_input_source_service()) {
            LOGI("  ch[%d] id=%d input: %d keycodes, %d touch", i, ch.id(),
                 ch.input_source_service().keycodes_supported_size(),
                 ch.input_source_service().touchscreen_size());
        } else if (ch.has_navigation_status_service()) {
            LOGI("  ch[%d] id=%d nav_status: interval=%d type=%d", i, ch.id(),
                 ch.navigation_status_service().minimum_interval_ms(),
                 (int)ch.navigation_status_service().type());
        } else {
            LOGI("  ch[%d] id=%d (other service)", i, ch.id());
        }
    }
}

// ============================================================================
// Input forwarding (app Ã¢â€ â€™ phone)
// ============================================================================

void JniSession::sendTouchEvent(int action, int pointerId, float x, float y, int pointerCount)
{
    if (!streaming_ || !inputChannel_) return;
    ioService_->post([this, action, pointerId, x, y, pointerCount]() {
        aap_protobuf::service::inputsource::message::InputReport report;

        auto now = std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
        report.set_timestamp(static_cast<uint64_t>(now));

        auto* touch = report.mutable_touch_event();
        touch->set_action(
            static_cast<aap_protobuf::service::inputsource::message::PointerAction>(action));
        touch->set_action_index(0);

        auto* ptr = touch->add_pointer_data();
        ptr->set_x(static_cast<uint32_t>(x));
        ptr->set_y(static_cast<uint32_t>(y));
        ptr->set_pointer_id(static_cast<uint32_t>(pointerId));

        auto promise = aasdk::channel::SendPromise::defer(*strand_);
        promise->then([]() {}, [](const auto&) {});
        inputChannel_->sendInputReport(report, std::move(promise));
    });
}

void JniSession::sendMultiTouchEvent(int action, int actionIndex,
    const int* ids, const float* xs, const float* ys, int count)
{
    if (!streaming_ || !inputChannel_ || count <= 0) return;

    // Copy data for async lambda
    std::vector<int> vIds(ids, ids + count);
    std::vector<float> vXs(xs, xs + count);
    std::vector<float> vYs(ys, ys + count);

    ioService_->post([this, action, actionIndex,
                      vIds = std::move(vIds), vXs = std::move(vXs),
                      vYs = std::move(vYs)]() {
        aap_protobuf::service::inputsource::message::InputReport report;

        auto now = std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
        report.set_timestamp(static_cast<uint64_t>(now));

        auto* touch = report.mutable_touch_event();
        touch->set_action(
            static_cast<aap_protobuf::service::inputsource::message::PointerAction>(action));
        touch->set_action_index(static_cast<uint32_t>(actionIndex));

        for (size_t i = 0; i < vIds.size(); i++) {
            auto* ptr = touch->add_pointer_data();
            ptr->set_x(static_cast<uint32_t>(vXs[i]));
            ptr->set_y(static_cast<uint32_t>(vYs[i]));
            ptr->set_pointer_id(static_cast<uint32_t>(vIds[i]));
        }

        auto promise = aasdk::channel::SendPromise::defer(*strand_);
        promise->then([]() {}, [](const auto&) {});
        inputChannel_->sendInputReport(report, std::move(promise));
    });
}

void JniSession::sendKeyEvent(int keyCode, bool isDown)
{
    if (!streaming_ || !inputChannel_) return;
    ioService_->post([this, keyCode, isDown]() {
        aap_protobuf::service::inputsource::message::InputReport report;

        auto now = std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
        report.set_timestamp(static_cast<uint64_t>(now));

        auto* keyEvent = report.mutable_key_event();
        auto* key = keyEvent->add_keys();
        key->set_keycode(static_cast<uint32_t>(keyCode));
        key->set_down(isDown);
        key->set_metastate(0);
        key->set_longpress(false);

        auto promise = aasdk::channel::SendPromise::defer(*strand_);
        promise->then([]() {}, [](const auto&) {});
        inputChannel_->sendInputReport(report, std::move(promise));
    });
}

void JniSession::sendGpsLocation(double lat, double lon, double alt,
                                  float speed, float bearing, long long timestampMs)
{
    if (!streaming_ || !sensorChannel_) return;
    ioService_->post([this, lat, lon, alt, speed, bearing, timestampMs]() {
        aap_protobuf::service::sensorsource::message::SensorBatch batch;
        auto* gps = batch.add_location_data();
        gps->set_latitude_e7(static_cast<int32_t>(lat * 1e7));
        gps->set_longitude_e7(static_cast<int32_t>(lon * 1e7));
        gps->set_altitude_e2(static_cast<int32_t>(alt * 1e2));
        gps->set_speed_e3(static_cast<int32_t>(speed * 1000));
        gps->set_bearing_e6(static_cast<int32_t>(bearing * 1e6));
        gps->set_timestamp(static_cast<uint64_t>(timestampMs));
        gps->set_accuracy_e3(static_cast<uint32_t>(10 * 1000));

        auto promise = aasdk::channel::SendPromise::defer(*strand_);
        promise->then([]() {}, [](const auto&) {});
        sensorChannel_->sendSensorEventIndication(batch, std::move(promise));
    });
}

void JniSession::sendVehicleSensor(int sensorType, const uint8_t* data, size_t length)
{
    if (!streaming_ || !sensorChannel_) return;
    // sensorType maps to SensorBatch field numbers:
    // Vehicle data is sent as pre-serialized SensorBatch protobuf from Kotlin.
    std::vector<uint8_t> dataCopy(data, data + length);
    ioService_->post([this, sensorType, dataCopy = std::move(dataCopy)]() {
        aap_protobuf::service::sensorsource::message::SensorBatch batch;
        if (batch.ParseFromArray(dataCopy.data(), static_cast<int>(dataCopy.size()))) {
            auto promise = aasdk::channel::SendPromise::defer(*strand_);
            promise->then([]() {}, [](const auto&) {});
            sensorChannel_->sendSensorEventIndication(batch, std::move(promise));
        }
    });
}

void JniSession::sendMicAudio(const uint8_t* data, size_t length)
{
    if (!streaming_ || !micHandler_ || !micHandler_->isOpen()) return;
    std::vector<uint8_t> dataCopy(data, data + length);
    ioService_->post([this, dataCopy = std::move(dataCopy)]() {
        micHandler_->feedAudio(dataCopy.data(), dataCopy.size());
    });
}

void JniSession::requestKeyframe()
{
    if (!streaming_ || !videoChannel_) return;
    ioService_->post([this]() {
        LOGI("Requesting keyframe (VideoFocusIndication)");
        aap_protobuf::service::media::video::message::VideoFocusNotification focus;
        focus.set_focus(aap_protobuf::service::media::video::message::VIDEO_FOCUS_PROJECTED);
        focus.set_unsolicited(false);
        auto promise = aasdk::channel::SendPromise::defer(*strand_);
        promise->then([]() {}, [](const auto&) {});
        videoChannel_->sendVideoFocusIndication(focus, std::move(promise));
    });
}

// Force a fresh IDR by performing a real focus TRANSITION rather than
// re-asserting the same PROJECTED state (issue #35). The plain requestKeyframe()
// above re-sends VIDEO_FOCUS_PROJECTED, but if the head unit already holds
// projected focus (e.g. after a phone-call / GM-UI takeover that backgrounded us
// without ever dropping focus, or a cold-connect where the phone thinks we are
// already projecting), the phone sees no state change and emits no out-of-cycle
// IDR — leaving video frozen until the next natural GOP (~2 min).
//
// Bouncing NATIVE_TRANSIENT -> PROJECTED is a genuine yield-then-reacquire, which
// is exactly what makes the phone send a fresh IDR (the same transition that
// produces the IDR on initial projection). NATIVE_TRANSIENT (not plain NATIVE) is
// deliberate: the phone treats it as a temporary handover, and our own
// onVideoFocusRequest() only escalates plain NATIVE to a user-Exit teardown — a
// NATIVE_TRANSIENT we send outbound never routes through that inbound handler, so
// it cannot trigger teardown. Both indications are posted on the io_service in
// order (and their promises deferred on strand_) so they serialize correctly.
void JniSession::requestKeyframeForceFocus()
{
    if (!streaming_ || !videoChannel_) return;
    ioService_->post([this]() {
        LOGI("Requesting keyframe via focus bounce (NATIVE_TRANSIENT -> PROJECTED)");

        // 1. Yield: announce transient native focus.
        currentVideoFocus_.store(
            aap_protobuf::service::media::video::message::VIDEO_FOCUS_NATIVE_TRANSIENT);
        aap_protobuf::service::media::video::message::VideoFocusNotification yield;
        yield.set_focus(
            aap_protobuf::service::media::video::message::VIDEO_FOCUS_NATIVE_TRANSIENT);
        yield.set_unsolicited(true);
        auto yieldPromise = aasdk::channel::SendPromise::defer(*strand_);
        yieldPromise->then([]() {}, [](const auto&) {});
        videoChannel_->sendVideoFocusIndication(yield, std::move(yieldPromise));

        // 2. Reacquire: announce projected focus again. The PROJECTED-after-yield
        //    transition is what prompts the phone to emit a fresh IDR.
        currentVideoFocus_.store(
            aap_protobuf::service::media::video::message::VIDEO_FOCUS_PROJECTED);
        aap_protobuf::service::media::video::message::VideoFocusNotification reacquire;
        reacquire.set_focus(
            aap_protobuf::service::media::video::message::VIDEO_FOCUS_PROJECTED);
        reacquire.set_unsolicited(true);
        auto reacquirePromise = aasdk::channel::SendPromise::defer(*strand_);
        reacquirePromise->then([]() {}, [](const auto&) {});
        videoChannel_->sendVideoFocusIndication(reacquire, std::move(reacquirePromise));
    });
}

// ============================================================================
// Typed vehicle sensor methods Ã¢â‚¬â€ each builds SensorBatch and sends
// ============================================================================

void JniSession::sendSpeedSensor(int speedMmPerS)
{
    if (!streaming_ || !sensorChannel_) return;
    ioService_->post([this, speedMmPerS]() {
        aap_protobuf::service::sensorsource::message::SensorBatch batch;
        auto* sd = batch.add_speed_data();
        sd->set_speed_e3(speedMmPerS);
        auto promise = aasdk::channel::SendPromise::defer(*strand_);
        promise->then([]() {}, [](const auto&) {});
        sensorChannel_->sendSensorEventIndication(batch, std::move(promise));
    });
}

void JniSession::sendGearSensor(int gear)
{
    if (!streaming_ || !sensorChannel_) return;
    ioService_->post([this, gear]() {
        aap_protobuf::service::sensorsource::message::SensorBatch batch;
        auto* gd = batch.add_gear_data();
        gd->set_gear(static_cast<aap_protobuf::service::sensorsource::message::Gear>(gear));
        auto promise = aasdk::channel::SendPromise::defer(*strand_);
        promise->then([]() {}, [](const auto&) {});
        sensorChannel_->sendSensorEventIndication(batch, std::move(promise));
    });
}

void JniSession::sendParkingBrakeSensor(bool engaged)
{
    if (!streaming_ || !sensorChannel_) return;
    ioService_->post([this, engaged]() {
        aap_protobuf::service::sensorsource::message::SensorBatch batch;
        auto* pb = batch.add_parking_brake_data();
        pb->set_parking_brake(engaged);
        auto promise = aasdk::channel::SendPromise::defer(*strand_);
        promise->then([]() {}, [](const auto&) {});
        sensorChannel_->sendSensorEventIndication(batch, std::move(promise));
    });
}

void JniSession::sendNightModeSensor(bool night)
{
    if (!streaming_ || !sensorChannel_) return;
    ioService_->post([this, night]() {
        aap_protobuf::service::sensorsource::message::SensorBatch batch;
        auto* nm = batch.add_night_mode_data();
        nm->set_night_mode(night);
        auto promise = aasdk::channel::SendPromise::defer(*strand_);
        promise->then([]() {}, [](const auto&) {});
        sensorChannel_->sendSensorEventIndication(batch, std::move(promise));
    });
}

void JniSession::sendDrivingStatusSensor(bool moving)
{
    if (!streaming_ || !sensorChannel_) return;
    ioService_->post([this, moving]() {
        aap_protobuf::service::sensorsource::message::SensorBatch batch;
        auto* ds = batch.add_driving_status_data();
        ds->set_status(moving ? 31 : 0);
        auto promise = aasdk::channel::SendPromise::defer(*strand_);
        promise->then([]() {}, [](const auto&) {});
        sensorChannel_->sendSensorEventIndication(batch, std::move(promise));
    });
}

void JniSession::sendFuelSensor(int levelPct, int rangeM, bool lowFuel)
{
    if (!streaming_ || !sensorChannel_) return;
    ioService_->post([this, levelPct, rangeM, lowFuel]() {
        aap_protobuf::service::sensorsource::message::SensorBatch batch;
        auto* fd = batch.add_fuel_data();
        fd->set_fuel_level(levelPct);
        fd->set_range(rangeM);
        fd->set_low_fuel_warning(lowFuel);
        auto promise = aasdk::channel::SendPromise::defer(*strand_);
        promise->then([]() {}, [](const auto&) {});
        sensorChannel_->sendSensorEventIndication(batch, std::move(promise));
    });
}

void JniSession::sendEnergyModelSensor(int batteryLevelWh, int batteryCapacityWh,
    int rangeM, int chargeRateW,
    float drivingWhPerKmOverride, float auxWhPerKmOverride, float aeroCoefOverride,
    float reservePctOverride, int maxChargeWOverride, int maxDischargeWOverride)
{
    if (!streaming_ || !sensorChannel_) return;
    // Guard: skip if values are invalid (matches bridge-mode logic)
    if (batteryCapacityWh <= 0 || batteryLevelWh <= 0 || rangeM <= 0) return;

    ioService_->post([this, batteryLevelWh, batteryCapacityWh, rangeM, chargeRateW,
                      drivingWhPerKmOverride, auxWhPerKmOverride, aeroCoefOverride,
                      reservePctOverride, maxChargeWOverride, maxDischargeWOverride]() {
        aap_protobuf::service::sensorsource::message::SensorBatch batch;
        aap_protobuf::service::sensorsource::message::VehicleEnergyModel vem;

        // Battery config — matches bridge-mode (verified working on real car)
        auto* batt = vem.mutable_battery();
        batt->set_config_id(1);

        batt->mutable_max_capacity()->set_watt_hours(batteryCapacityWh);

        // CRITICAL: Maps reads min_usable_capacity as the CURRENT battery level (Wh)!
        // From AAOS Maps decompile (rah.m33791O): SOC = min_usable_capacity / max_capacity.
        batt->mutable_min_usable_capacity()->set_watt_hours(batteryLevelWh);

        // Reserve = small buffer below which Maps warns "low battery"
        float reservePct = (reservePctOverride >= 0.0f) ? reservePctOverride : 5.0f;
        batt->mutable_reserve_energy()->set_watt_hours(
            static_cast<int>(batteryCapacityWh * (reservePct / 100.0f)));

        batt->set_regen_braking_capable(true);

        // Charge/discharge power — needed for server-side EV routing computation
        int chargePower;
        if (maxChargeWOverride > 0) {
            chargePower = maxChargeWOverride;
        } else {
            chargePower = (chargeRateW > 0) ? chargeRateW : 150000;
        }
        batt->set_max_charge_power_w(chargePower);
        batt->set_max_discharge_power_w(maxDischargeWOverride > 0 ? maxDischargeWOverride : 150000);

        // Energy consumption — derived from car range unless override provided
        auto* cons = vem.mutable_consumption();
        float derivedWhPerKm = (static_cast<float>(batteryLevelWh) /
                                static_cast<float>(rangeM)) * 1000.0f;
        float whPerKm = (drivingWhPerKmOverride >= 0.0f) ? drivingWhPerKmOverride : derivedWhPerKm;
        cons->mutable_driving()->set_rate(whPerKm);
        cons->mutable_auxiliary()->set_rate(auxWhPerKmOverride >= 0.0f ? auxWhPerKmOverride : 2.0f);
        cons->mutable_aerodynamic()->set_rate(aeroCoefOverride >= 0.0f ? aeroCoefOverride : 0.36f);

        // Charging prefs
        vem.mutable_charging_prefs()->set_mode(1);  // standard

        auto* vemMsg = batch.add_vehicle_energy_model_data();
        *vemMsg = vem;

        auto promise = aasdk::channel::SendPromise::defer(*strand_);
        promise->then([]() {}, [](const auto&) {});
        sensorChannel_->sendSensorEventIndication(batch, std::move(promise));
    });
}

void JniSession::sendAccelerometerSensor(int xE3, int yE3, int zE3)
{
    if (!streaming_ || !sensorChannel_) return;
    ioService_->post([this, xE3, yE3, zE3]() {
        aap_protobuf::service::sensorsource::message::SensorBatch batch;
        auto* ad = batch.add_accelerometer_data();
        ad->set_acceleration_x_e3(xE3);
        ad->set_acceleration_y_e3(yE3);
        ad->set_acceleration_z_e3(zE3);
        auto promise = aasdk::channel::SendPromise::defer(*strand_);
        promise->then([]() {}, [](const auto&) {});
        sensorChannel_->sendSensorEventIndication(batch, std::move(promise));
    });
}

void JniSession::sendGyroscopeSensor(int rxE3, int ryE3, int rzE3)
{
    if (!streaming_ || !sensorChannel_) return;
    ioService_->post([this, rxE3, ryE3, rzE3]() {
        aap_protobuf::service::sensorsource::message::SensorBatch batch;
        auto* gd = batch.add_gyroscope_data();
        gd->set_rotation_speed_x_e3(rxE3);
        gd->set_rotation_speed_y_e3(ryE3);
        gd->set_rotation_speed_z_e3(rzE3);
        auto promise = aasdk::channel::SendPromise::defer(*strand_);
        promise->then([]() {}, [](const auto&) {});
        sensorChannel_->sendSensorEventIndication(batch, std::move(promise));
    });
}

void JniSession::sendCompassSensor(int bearingE6, int pitchE6, int rollE6)
{
    if (!streaming_ || !sensorChannel_) return;
    ioService_->post([this, bearingE6, pitchE6, rollE6]() {
        aap_protobuf::service::sensorsource::message::SensorBatch batch;
        auto* cd = batch.add_compass_data();
        cd->set_bearing_e6(bearingE6);
        cd->set_pitch_e6(pitchE6);
        cd->set_roll_e6(rollE6);
        auto promise = aasdk::channel::SendPromise::defer(*strand_);
        promise->then([]() {}, [](const auto&) {});
        sensorChannel_->sendSensorEventIndication(batch, std::move(promise));
    });
}

void JniSession::sendRpmSensor(int rpmE3)
{
    if (!streaming_ || !sensorChannel_) return;
    ioService_->post([this, rpmE3]() {
        aap_protobuf::service::sensorsource::message::SensorBatch batch;
        auto* rd = batch.add_rpm_data();
        rd->set_rpm_e3(rpmE3);
        auto promise = aasdk::channel::SendPromise::defer(*strand_);
        promise->then([]() {}, [](const auto&) {});
        sensorChannel_->sendSensorEventIndication(batch, std::move(promise));
    });
}

// ============================================================================
// Dispatch methods Ã¢â‚¬â€ called by handler classes to fire JNI callbacks
// ============================================================================

void JniSession::dispatchAudioFrame(const uint8_t* data, size_t size,
                                     int purpose, int sampleRate, int channels)
{
    if (!cbMethods_.onAudioFrame || !callbackRef_) return;
    bool attached;
    JNIEnv* env = getEnv(attached);
    if (env) {
        jbyteArray jdata = env->NewByteArray(static_cast<jsize>(size));
        env->SetByteArrayRegion(jdata, 0, static_cast<jsize>(size),
                                reinterpret_cast<const jbyte*>(data));
        env->CallVoidMethod(callbackRef_, cbMethods_.onAudioFrame,
                            jdata, static_cast<jint>(purpose),
                            static_cast<jint>(sampleRate),
                            static_cast<jint>(channels));
        env->DeleteLocalRef(jdata);
    }
    releaseEnv(attached);
}

void JniSession::dispatchMicRequest(bool open)
{
    if (!cbMethods_.onMicRequest || !callbackRef_) return;
    bool attached;
    JNIEnv* env = getEnv(attached);
    if (env) {
        env->CallVoidMethod(callbackRef_, cbMethods_.onMicRequest,
                            static_cast<jboolean>(open));
    }
    releaseEnv(attached);
}

void JniSession::dispatchNavStatus(int status)
{
    if (!cbMethods_.onNavigationStatus || !callbackRef_) return;
    bool attached;
    JNIEnv* env = getEnv(attached);
    if (env) {
        env->CallVoidMethod(callbackRef_, cbMethods_.onNavigationStatus,
                            static_cast<jint>(status));
    }
    releaseEnv(attached);
}

void JniSession::dispatchNavTurn(const std::string& maneuver, const std::string& road,
                                  const uint8_t* iconData, size_t iconSize)
{
    if (!cbMethods_.onNavigationTurn || !callbackRef_) return;
    bool attached;
    JNIEnv* env = getEnv(attached);
    if (env) {
        jstring jmaneuver = env->NewStringUTF(maneuver.c_str());
        jstring jroad = env->NewStringUTF(road.c_str());
        jbyteArray jicon = nullptr;
        if (iconData && iconSize > 0) {
            jicon = env->NewByteArray(static_cast<jsize>(iconSize));
            env->SetByteArrayRegion(jicon, 0, static_cast<jsize>(iconSize),
                                    reinterpret_cast<const jbyte*>(iconData));
        }
        env->CallVoidMethod(callbackRef_, cbMethods_.onNavigationTurn,
                            jmaneuver, jroad, jicon);
        env->DeleteLocalRef(jmaneuver);
        env->DeleteLocalRef(jroad);
        if (jicon) env->DeleteLocalRef(jicon);
    }
    releaseEnv(attached);
}

void JniSession::dispatchNavDistance(int distanceMeters, int etaSeconds,
                                     const std::string& displayDistance,
                                     const std::string& displayUnit)
{
    if (!cbMethods_.onNavigationDistance || !callbackRef_) return;
    bool attached;
    JNIEnv* env = getEnv(attached);
    if (env) {
        jstring jdist = displayDistance.empty() ? nullptr : env->NewStringUTF(displayDistance.c_str());
        jstring junit = displayUnit.empty() ? nullptr : env->NewStringUTF(displayUnit.c_str());
        env->CallVoidMethod(callbackRef_, cbMethods_.onNavigationDistance,
                            static_cast<jint>(distanceMeters),
                            static_cast<jint>(etaSeconds),
                            jdist, junit);
        if (jdist) env->DeleteLocalRef(jdist);
        if (junit) env->DeleteLocalRef(junit);
    }
    releaseEnv(attached);
}

void JniSession::dispatchNavFullState(
    const std::string& maneuver, const std::string& road,
    const uint8_t* iconData, size_t iconSize,
    int distanceMeters, int etaSeconds,
    const std::string& displayDistance, const std::string& displayUnit,
    const std::string& lanes, const std::string& cue,
    int roundaboutExitNumber,
    const std::string& currentRoad, const std::string& destination,
    const std::string& etaFormatted, long long timeToArrivalSeconds,
    int destDistanceMeters, const std::string& destDistDisplay,
    const std::string& destDistUnit)
{
    if (!cbMethods_.onNavigationFullState || !callbackRef_) return;
    bool attached;
    JNIEnv* env = getEnv(attached);
    if (env) {
        jstring jmaneuver = maneuver.empty() ? nullptr : env->NewStringUTF(maneuver.c_str());
        jstring jroad = road.empty() ? nullptr : env->NewStringUTF(road.c_str());
        jbyteArray jicon = nullptr;
        if (iconData && iconSize > 0) {
            jicon = env->NewByteArray(static_cast<jsize>(iconSize));
            env->SetByteArrayRegion(jicon, 0, static_cast<jsize>(iconSize),
                                    reinterpret_cast<const jbyte*>(iconData));
        }
        jstring jdisplayDist = displayDistance.empty() ? nullptr : env->NewStringUTF(displayDistance.c_str());
        jstring jdisplayUnit = displayUnit.empty() ? nullptr : env->NewStringUTF(displayUnit.c_str());
        jstring jlanes = lanes.empty() ? nullptr : env->NewStringUTF(lanes.c_str());
        jstring jcue = cue.empty() ? nullptr : env->NewStringUTF(cue.c_str());
        jstring jcurrentRoad = currentRoad.empty() ? nullptr : env->NewStringUTF(currentRoad.c_str());
        jstring jdestination = destination.empty() ? nullptr : env->NewStringUTF(destination.c_str());
        jstring jetaFormatted = etaFormatted.empty() ? nullptr : env->NewStringUTF(etaFormatted.c_str());
        jstring jdestDistDisplay = destDistDisplay.empty() ? nullptr : env->NewStringUTF(destDistDisplay.c_str());
        jstring jdestDistUnit = destDistUnit.empty() ? nullptr : env->NewStringUTF(destDistUnit.c_str());

        env->CallVoidMethod(callbackRef_, cbMethods_.onNavigationFullState,
            jmaneuver, jroad, jicon,
            static_cast<jint>(distanceMeters), static_cast<jint>(etaSeconds),
            jdisplayDist, jdisplayUnit,
            jlanes, jcue, static_cast<jint>(roundaboutExitNumber),
            jcurrentRoad, jdestination, jetaFormatted,
            static_cast<jlong>(timeToArrivalSeconds),
            static_cast<jint>(destDistanceMeters),
            jdestDistDisplay, jdestDistUnit);

        if (jmaneuver) env->DeleteLocalRef(jmaneuver);
        if (jroad) env->DeleteLocalRef(jroad);
        if (jicon) env->DeleteLocalRef(jicon);
        if (jdisplayDist) env->DeleteLocalRef(jdisplayDist);
        if (jdisplayUnit) env->DeleteLocalRef(jdisplayUnit);
        if (jlanes) env->DeleteLocalRef(jlanes);
        if (jcue) env->DeleteLocalRef(jcue);
        if (jcurrentRoad) env->DeleteLocalRef(jcurrentRoad);
        if (jdestination) env->DeleteLocalRef(jdestination);
        if (jetaFormatted) env->DeleteLocalRef(jetaFormatted);
        if (jdestDistDisplay) env->DeleteLocalRef(jdestDistDisplay);
        if (jdestDistUnit) env->DeleteLocalRef(jdestDistUnit);
    }
    releaseEnv(attached);
}

void JniSession::dispatchMediaMetadata(const std::string& title, const std::string& artist,
                                        const std::string& album,
                                        const uint8_t* artData, size_t artSize)
{
    if (!cbMethods_.onMediaMetadata || !callbackRef_) return;
    bool attached;
    JNIEnv* env = getEnv(attached);
    if (env) {
        jstring jtitle = env->NewStringUTF(title.c_str());
        jstring jartist = env->NewStringUTF(artist.c_str());
        jstring jalbum = env->NewStringUTF(album.c_str());
        jbyteArray jart = nullptr;
        if (artData && artSize > 0) {
            jart = env->NewByteArray(static_cast<jsize>(artSize));
            env->SetByteArrayRegion(jart, 0, static_cast<jsize>(artSize),
                                    reinterpret_cast<const jbyte*>(artData));
        }
        env->CallVoidMethod(callbackRef_, cbMethods_.onMediaMetadata,
                            jtitle, jartist, jalbum, jart);
        env->DeleteLocalRef(jtitle);
        env->DeleteLocalRef(jartist);
        env->DeleteLocalRef(jalbum);
        if (jart) env->DeleteLocalRef(jart);
    }
    releaseEnv(attached);
}

void JniSession::dispatchMediaPlayback(int state, long long positionMs)
{
    if (!cbMethods_.onMediaPlayback || !callbackRef_) return;
    bool attached;
    JNIEnv* env = getEnv(attached);
    if (env) {
        env->CallVoidMethod(callbackRef_, cbMethods_.onMediaPlayback,
                            static_cast<jint>(state),
                            static_cast<jlong>(positionMs));
    }
    releaseEnv(attached);
}

void JniSession::dispatchPhoneStatus(int signalStrength, int callState)
{
    if (!cbMethods_.onPhoneStatus || !callbackRef_) return;
    bool attached;
    JNIEnv* env = getEnv(attached);
    if (env) {
        env->CallVoidMethod(callbackRef_, cbMethods_.onPhoneStatus,
                            static_cast<jint>(signalStrength),
                            static_cast<jint>(callState));
    }
    releaseEnv(attached);
}

} // namespace openautolink::jni
