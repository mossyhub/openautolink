/*
 * jni_bridge.cpp — JNI entry points for aasdk ↔ Kotlin communication
 *
 * This file maps Java/Kotlin native method declarations in AasdkJni.kt
 * to C++ implementations that drive the aasdk AA session.
 *
 * Kotlin → Native: startSession, stopSession, sendTouch, sendSensorData, etc.
 * Native → Kotlin: onVideoFrame, onAudioFrame, onPhoneConnected, etc.
 *                   (called via JNI AttachCurrentThread from aasdk IO thread)
 *
 * Part of Phase C-native — stub implementation for Phase E build verification.
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <atomic>

#define LOG_TAG "oal_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Forward declaration — implemented in aa_session.cpp
namespace oal {
    bool startSession(int port, int width, int height, int fps, int dpi);
    bool startSessionWithFd(int socketFd, int width, int height, int fps, int dpi,
                            int marginW, int marginH, int pixelAspect, int driverPos,
                            int safeT, int safeB, int safeL, int safeR,
                            int contentT, int contentB, int contentL, int contentR,
                            const char* headUnitName, int sessionConfig, const char* btMac,
                            int videoCodec);
    void stopSession();
    void sendTouch(int action, float x, float y, int pointerId);
    void sendSensorData(int type, const uint8_t* data, size_t len);
    void sendMicAudio(const uint8_t* pcm, size_t len);
    void sendButton(int keycode, bool down);
    void requestVideoFocus();
}

// Cached JVM reference for native → Kotlin callbacks
static JavaVM* g_jvm = nullptr;
static jclass g_aasdkJniClass = nullptr;

// Method IDs for callbacks (cached on JNI_OnLoad)
static jmethodID g_onVideoFrame = nullptr;
static jmethodID g_onAudioFrame = nullptr;
static jmethodID g_onPhoneConnected = nullptr;
static jmethodID g_onPhoneDisconnected = nullptr;
static jmethodID g_onVoiceSession = nullptr;
static jmethodID g_onPhoneBattery = nullptr;
static jmethodID g_onNavState = nullptr;
static jmethodID g_onNavStateClear = nullptr;

jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_jvm = vm;
    JNIEnv* env = nullptr;

    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOGE("JNI_OnLoad: GetEnv failed");
        return JNI_ERR;
    }

    jclass cls = env->FindClass("com/openautolink/app/transport/AasdkJni");
    if (!cls) {
        LOGE("JNI_OnLoad: AasdkJni class not found");
        return JNI_ERR;
    }

    g_aasdkJniClass = reinterpret_cast<jclass>(env->NewGlobalRef(cls));

    // Cache callback method IDs
    g_onVideoFrame = env->GetStaticMethodID(g_aasdkJniClass,
        "onVideoFrame", "(IIJI[B)V");
    g_onAudioFrame = env->GetStaticMethodID(g_aasdkJniClass,
        "onAudioFrame", "(III[B)V");
    g_onPhoneConnected = env->GetStaticMethodID(g_aasdkJniClass,
        "onPhoneConnected", "(Ljava/lang/String;Ljava/lang/String;)V");
    g_onPhoneDisconnected = env->GetStaticMethodID(g_aasdkJniClass,
        "onPhoneDisconnected", "(Ljava/lang/String;)V");

    g_onVoiceSession = env->GetStaticMethodID(g_aasdkJniClass,
        "onVoiceSession", "(Z)V");
    g_onPhoneBattery = env->GetStaticMethodID(g_aasdkJniClass,
        "onPhoneBattery", "(IIZ)V");
    g_onNavState = env->GetStaticMethodID(g_aasdkJniClass,
        "onNavState", "(Ljava/lang/String;ILjava/lang/String;ILjava/lang/String;IILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;J)V");
    g_onNavStateClear = env->GetStaticMethodID(g_aasdkJniClass,
        "onNavStateClear", "()V");

    if (!g_onVideoFrame || !g_onAudioFrame ||
        !g_onPhoneConnected || !g_onPhoneDisconnected ||
        !g_onVoiceSession || !g_onPhoneBattery ||
        !g_onNavState || !g_onNavStateClear) {
        LOGE("JNI_OnLoad: Failed to find callback methods");
        return JNI_ERR;
    }

    LOGI("JNI_OnLoad: liboal_jni initialized");
    return JNI_VERSION_1_6;
}

void JNI_OnUnload(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        if (g_aasdkJniClass) {
            env->DeleteGlobalRef(g_aasdkJniClass);
            g_aasdkJniClass = nullptr;
        }
    }
    g_jvm = nullptr;
    LOGI("JNI_OnUnload: liboal_jni cleaned up");
}

// ─── Helper: Get JNIEnv for current thread ──────────────────────
static JNIEnv* getEnv() {
    JNIEnv* env = nullptr;
    if (!g_jvm) return nullptr;

    int status = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGE("Failed to attach native thread to JVM");
            return nullptr;
        }
    }
    return env;
}

// ─── Native → Kotlin callbacks (called from aa_session.cpp) ─────

namespace oal { namespace jni {

void notifyVideoFrame(int w, int h, int64_t pts, int flags,
                      const uint8_t* data, size_t len) {
    JNIEnv* env = getEnv();
    if (!env || !g_onVideoFrame) return;

    jbyteArray arr = env->NewByteArray(static_cast<jsize>(len));
    if (!arr) return;
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(len),
                            reinterpret_cast<const jbyte*>(data));

    env->CallStaticVoidMethod(g_aasdkJniClass, g_onVideoFrame,
                              w, h, static_cast<jlong>(pts), flags, arr);
    env->DeleteLocalRef(arr);
}

void notifyAudioFrame(int purpose, int sampleRate, int channels,
                      const uint8_t* data, size_t len) {
    JNIEnv* env = getEnv();
    if (!env || !g_onAudioFrame) return;

    jbyteArray arr = env->NewByteArray(static_cast<jsize>(len));
    if (!arr) return;
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(len),
                            reinterpret_cast<const jbyte*>(data));

    env->CallStaticVoidMethod(g_aasdkJniClass, g_onAudioFrame,
                              purpose, sampleRate, channels, arr);
    env->DeleteLocalRef(arr);
}

void notifyPhoneConnected(const std::string& name, const std::string& type) {
    JNIEnv* env = getEnv();
    if (!env || !g_onPhoneConnected) return;

    jstring jName = env->NewStringUTF(name.c_str());
    jstring jType = env->NewStringUTF(type.c_str());
    env->CallStaticVoidMethod(g_aasdkJniClass, g_onPhoneConnected, jName, jType);
    env->DeleteLocalRef(jName);
    env->DeleteLocalRef(jType);
}

void notifyPhoneDisconnected(const std::string& reason) {
    JNIEnv* env = getEnv();
    if (!env || !g_onPhoneDisconnected) return;

    jstring jReason = env->NewStringUTF(reason.c_str());
    env->CallStaticVoidMethod(g_aasdkJniClass, g_onPhoneDisconnected, jReason);
    env->DeleteLocalRef(jReason);
}

void notifyVoiceSession(bool started) {
    JNIEnv* env = getEnv();
    if (!env || !g_onVoiceSession) return;
    env->CallStaticVoidMethod(g_aasdkJniClass, g_onVoiceSession, static_cast<jboolean>(started));
}

void notifyPhoneBattery(int level, int timeRemaining, bool critical) {
    JNIEnv* env = getEnv();
    if (!env || !g_onPhoneBattery) return;
    env->CallStaticVoidMethod(g_aasdkJniClass, g_onPhoneBattery,
                              static_cast<jint>(level),
                              static_cast<jint>(timeRemaining),
                              static_cast<jboolean>(critical));
}

void notifyNavState(const std::string& maneuver, int distanceMeters,
                    const std::string& road, int etaSeconds,
                    const std::string& cue, int roundaboutExit, int roundaboutAngle,
                    const std::string& displayDistance, const std::string& displayDistanceUnit,
                    const std::string& currentRoad, const std::string& destination,
                    const std::string& etaFormatted, int64_t timeToArrivalSeconds) {
    JNIEnv* env = getEnv();
    if (!env || !g_onNavState) return;

    jstring jManeuver = env->NewStringUTF(maneuver.c_str());
    jstring jRoad = env->NewStringUTF(road.c_str());
    jstring jCue = env->NewStringUTF(cue.c_str());
    jstring jDisplayDist = env->NewStringUTF(displayDistance.c_str());
    jstring jDisplayUnit = env->NewStringUTF(displayDistanceUnit.c_str());
    jstring jCurrentRoad = env->NewStringUTF(currentRoad.c_str());
    jstring jDestination = env->NewStringUTF(destination.c_str());
    jstring jEtaFormatted = env->NewStringUTF(etaFormatted.c_str());

    env->CallStaticVoidMethod(g_aasdkJniClass, g_onNavState,
                              jManeuver, static_cast<jint>(distanceMeters),
                              jRoad, static_cast<jint>(etaSeconds),
                              jCue, static_cast<jint>(roundaboutExit),
                              static_cast<jint>(roundaboutAngle),
                              jDisplayDist, jDisplayUnit,
                              jCurrentRoad, jDestination,
                              jEtaFormatted, static_cast<jlong>(timeToArrivalSeconds));

    env->DeleteLocalRef(jManeuver);
    env->DeleteLocalRef(jRoad);
    env->DeleteLocalRef(jCue);
    env->DeleteLocalRef(jDisplayDist);
    env->DeleteLocalRef(jDisplayUnit);
    env->DeleteLocalRef(jCurrentRoad);
    env->DeleteLocalRef(jDestination);
    env->DeleteLocalRef(jEtaFormatted);
}

void notifyNavStateClear() {
    JNIEnv* env = getEnv();
    if (!env || !g_onNavStateClear) return;
    env->CallStaticVoidMethod(g_aasdkJniClass, g_onNavStateClear);
}

}} // namespace oal::jni

// ─── Kotlin → Native JNI functions ──────────────────────────────

extern "C" {

JNIEXPORT void JNICALL
Java_com_openautolink_app_transport_AasdkJni_startSession(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jint port, jint width, jint height, jint fps, jint dpi) {
    LOGI("startSession: port=%d %dx%d @%dfps dpi=%d", port, width, height, fps, dpi);
    oal::startSession(port, width, height, fps, dpi);
}

JNIEXPORT void JNICALL
Java_com_openautolink_app_transport_AasdkJni_startSessionWithFd(
        JNIEnv* env, jobject /*thiz*/,
        jint socketFd, jint width, jint height, jint fps, jint dpi,
        jint marginW, jint marginH, jint pixelAspect, jint driverPos,
        jint safeT, jint safeB, jint safeL, jint safeR,
        jint contentT, jint contentB, jint contentL, jint contentR,
        jstring headUnitName, jint sessionConfig, jstring btMac,
        jint videoCodec) {
    const char* name = env->GetStringUTFChars(headUnitName, nullptr);
    const char* mac = env->GetStringUTFChars(btMac, nullptr);
    LOGI("startSessionWithFd: fd=%d %dx%d @%dfps dpi=%d margin=%dx%d pa=%d sessionCfg=%d codec=%d",
         socketFd, width, height, fps, dpi, marginW, marginH, pixelAspect, sessionConfig, videoCodec);
    oal::startSessionWithFd(socketFd, width, height, fps, dpi,
                            marginW, marginH, pixelAspect, driverPos,
                            safeT, safeB, safeL, safeR,
                            contentT, contentB, contentL, contentR,
                            name, sessionConfig, mac, videoCodec);
    env->ReleaseStringUTFChars(headUnitName, name);
    env->ReleaseStringUTFChars(btMac, mac);
}

JNIEXPORT void JNICALL
Java_com_openautolink_app_transport_AasdkJni_stopSession(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    LOGI("stopSession");
    oal::stopSession();
}

JNIEXPORT void JNICALL
Java_com_openautolink_app_transport_AasdkJni_sendTouch(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jint action, jfloat x, jfloat y, jint pointerId) {
    oal::sendTouch(action, x, y, pointerId);
}

JNIEXPORT void JNICALL
Java_com_openautolink_app_transport_AasdkJni_sendSensorData(
        JNIEnv* env, jobject /*thiz*/,
        jint type, jbyteArray data) {
    jsize len = env->GetArrayLength(data);
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes) {
        oal::sendSensorData(type, reinterpret_cast<const uint8_t*>(bytes),
                            static_cast<size_t>(len));
        env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_openautolink_app_transport_AasdkJni_sendMicAudio(
        JNIEnv* env, jobject /*thiz*/, jbyteArray pcm) {
    jsize len = env->GetArrayLength(pcm);
    jbyte* bytes = env->GetByteArrayElements(pcm, nullptr);
    if (bytes) {
        oal::sendMicAudio(reinterpret_cast<const uint8_t*>(bytes),
                          static_cast<size_t>(len));
        env->ReleaseByteArrayElements(pcm, bytes, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_openautolink_app_transport_AasdkJni_sendButton(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jint keycode, jboolean down) {
    oal::sendButton(keycode, down);
}

JNIEXPORT void JNICALL
Java_com_openautolink_app_transport_AasdkJni_requestVideoFocus(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    oal::requestVideoFocus();
}

} // extern "C"
