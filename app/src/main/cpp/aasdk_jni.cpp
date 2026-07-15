/*
 * aasdk_jni.cpp — JNI entry point for aasdk-in-app.
 *
 * Exposes native methods to Kotlin's AasdkNative class.
 * Manages the JniSession lifecycle and dispatches calls.
 */
#include <jni.h>
#include <android/log.h>

#include <memory>
#include <mutex>

#include "jni_session.h"
#include "jni_log_bridge.h"
#include "native_crash_handler.h"

#define LOG_TAG "OAL-AasdkJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM* gJvm = nullptr;
static std::mutex gSessionMutex;  // protects create/start/stop (rare)
static std::shared_ptr<openautolink::jni::JniSession> gSession;

// Lock-free snapshot for hot-path calls (touch, sensor, mic, keyframe).
// Writers (create/stop) update under gSessionMutex then store atomically.
static std::shared_ptr<openautolink::jni::JniSession> getSession() {
    return std::atomic_load(&gSession);
}

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/)
{
    gJvm = vm;
    JNIEnv* env = nullptr;
    vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (env) {
        openautolink::jni::oal_jni_log_init(env, vm);
    }
    LOGI("openautolink-jni loaded");
    return JNI_VERSION_1_6;
}

extern "C" {

/*
 * Class:     com_openautolink_app_transport_aasdk_AasdkNative
 * Method:    nativeInstallCrashHandler
 */
JNIEXPORT void JNICALL
Java_com_openautolink_app_transport_aasdk_AasdkNative_nativeInstallCrashHandler(
    JNIEnv* env, jclass /*clazz*/, jstring crashDir)
{
    const char* dir = env->GetStringUTFChars(crashDir, nullptr);
    oal_install_native_crash_handler(dir);
    env->ReleaseStringUTFChars(crashDir, dir);
}

/*
 * Class:     com_openautolink_app_transport_aasdk_AasdkNative
 * Method:    nativeCreateSession
 */
JNIEXPORT void JNICALL
Java_com_openautolink_app_transport_aasdk_AasdkNative_nativeCreateSession(
    JNIEnv* /*env*/, jclass /*clazz*/)
{
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gSession) {
        LOGW("Session already exists, destroying old one");
        gSession->stop();
        std::atomic_store(&gSession, std::shared_ptr<openautolink::jni::JniSession>());
    }
    auto session = std::make_shared<openautolink::jni::JniSession>(gJvm);
    std::atomic_store(&gSession, session);
    LOGI("Native session created");
}

/*
 * Class:     com_openautolink_app_transport_aasdk_AasdkNative
 * Method:    nativeStartSession
 */
JNIEXPORT void JNICALL
Java_com_openautolink_app_transport_aasdk_AasdkNative_nativeStartSession(
    JNIEnv* env, jclass /*clazz*/,
    jobject transportPipe, jobject callback, jobject sdrConfig)
{
    std::lock_guard<std::mutex> lock(gSessionMutex);
    auto session = std::atomic_load(&gSession);
    if (!session) {
        LOGE("No session — call createSession first");
        return;
    }
    session->start(env, transportPipe, callback, sdrConfig);
}

/*
 * Class:     com_openautolink_app_transport_aasdk_AasdkNative
 * Method:    nativeStopSession
 */
JNIEXPORT void JNICALL
Java_com_openautolink_app_transport_aasdk_AasdkNative_nativeStopSession(
    JNIEnv* /*env*/, jclass /*clazz*/)
{
    std::lock_guard<std::mutex> lock(gSessionMutex);
    auto session = std::atomic_load(&gSession);
    if (session) {
        session->stop();
        std::atomic_store(&gSession, std::shared_ptr<openautolink::jni::JniSession>());
    }
    LOGI("Native session stopped and destroyed");
}

/*
 * Class:     com_openautolink_app_transport_aasdk_AasdkNative
 * Method:    nativeSendTouchEvent
 */
JNIEXPORT void JNICALL
Java_com_openautolink_app_transport_aasdk_AasdkNative_nativeSendTouchEvent(
    JNIEnv* /*env*/, jclass /*clazz*/,
    jint action, jint pointerId, jfloat x, jfloat y, jint pointerCount)
{
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gSession) {
        gSession->sendTouchEvent(action, pointerId, x, y, pointerCount);
    }
}

/*
 * Class:     com_openautolink_app_transport_aasdk_AasdkNative
 * Method:    nativeSendMultiTouchEvent
 */
JNIEXPORT void JNICALL
Java_com_openautolink_app_transport_aasdk_AasdkNative_nativeSendMultiTouchEvent(
    JNIEnv* env, jclass /*clazz*/,
    jint action, jint actionIndex, jintArray ids, jfloatArray xs, jfloatArray ys)
{
    auto session = getSession();
    if (!session) return;

    jsize count = env->GetArrayLength(ids);
    jint* idArr = env->GetIntArrayElements(ids, nullptr);
    jfloat* xArr = env->GetFloatArrayElements(xs, nullptr);
    jfloat* yArr = env->GetFloatArrayElements(ys, nullptr);

    session->sendMultiTouchEvent(action, actionIndex, idArr, xArr, yArr, count);

    env->ReleaseIntArrayElements(ids, idArr, JNI_ABORT);
    env->ReleaseFloatArrayElements(xs, xArr, JNI_ABORT);
    env->ReleaseFloatArrayElements(ys, yArr, JNI_ABORT);
}

/*
 * Class:     com_openautolink_app_transport_aasdk_AasdkNative
 * Method:    nativeSendKeyEvent
 */
JNIEXPORT void JNICALL
Java_com_openautolink_app_transport_aasdk_AasdkNative_nativeSendKeyEvent(
    JNIEnv* /*env*/, jclass /*clazz*/,
    jint keyCode, jboolean isDown)
{
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gSession) {
        gSession->sendKeyEvent(keyCode, isDown);
    }
}

/*
 * Class:     com_openautolink_app_transport_aasdk_AasdkNative
 * Method:    nativeSendGpsLocation
 */
JNIEXPORT void JNICALL
Java_com_openautolink_app_transport_aasdk_AasdkNative_nativeSendGpsLocation(
    JNIEnv* /*env*/, jclass /*clazz*/,
    jdouble lat, jdouble lon, jdouble alt,
    jfloat speed, jfloat bearing, jlong timestampMs)
{
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gSession) {
        gSession->sendGpsLocation(lat, lon, alt, speed, bearing, timestampMs);
    }
}

/*
 * Class:     com_openautolink_app_transport_aasdk_AasdkNative
 * Method:    nativeSendVehicleSensor
 */
JNIEXPORT void JNICALL
Java_com_openautolink_app_transport_aasdk_AasdkNative_nativeSendVehicleSensor(
    JNIEnv* env, jclass /*clazz*/,
    jint sensorType, jbyteArray data)
{
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (!gSession) return;

    jsize len = env->GetArrayLength(data);
    auto* bytes = env->GetByteArrayElements(data, nullptr);
    gSession->sendVehicleSensor(sensorType,
        reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
}

/*
 * Class:     com_openautolink_app_transport_aasdk_AasdkNative
 * Method:    nativeSendMicAudio
 */
JNIEXPORT void JNICALL
Java_com_openautolink_app_transport_aasdk_AasdkNative_nativeSendMicAudio(
    JNIEnv* env, jclass /*clazz*/,
    jbyteArray data)
{
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (!gSession) return;

    jsize len = env->GetArrayLength(data);
    auto* bytes = env->GetByteArrayElements(data, nullptr);
    gSession->sendMicAudio(
        reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
}

/*
 * Class:     com_openautolink_app_transport_aasdk_AasdkNative
 * Method:    nativeRequestKeyframe
 */
JNIEXPORT void JNICALL
Java_com_openautolink_app_transport_aasdk_AasdkNative_nativeRequestKeyframe(
    JNIEnv* /*env*/, jclass /*clazz*/)
{
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gSession) {
        gSession->requestKeyframe();
    }
}

// Typed vehicle sensor JNI methods — each calls the corresponding C++ method
// that builds the correct SensorBatch protobuf and sends via sensorChannel_.

#define SENSOR_JNI(Name, ...) \
JNIEXPORT void JNICALL \
Java_com_openautolink_app_transport_aasdk_AasdkNative_nativeSend##Name( \
    JNIEnv* /*env*/, jclass /*clazz*/, __VA_ARGS__)

SENSOR_JNI(Speed, jint speedMmPerS) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gSession) gSession->sendSpeedSensor(speedMmPerS);
}

SENSOR_JNI(Gear, jint gear) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gSession) gSession->sendGearSensor(gear);
}

SENSOR_JNI(ParkingBrake, jboolean engaged) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gSession) gSession->sendParkingBrakeSensor(engaged);
}

SENSOR_JNI(NightMode, jboolean night) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gSession) gSession->sendNightModeSensor(night);
}

SENSOR_JNI(DrivingStatus, jboolean moving) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gSession) gSession->sendDrivingStatusSensor(moving);
}

SENSOR_JNI(Fuel, jint levelPct, jint rangeM, jboolean lowFuel) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gSession) gSession->sendFuelSensor(levelPct, rangeM, lowFuel);
}

SENSOR_JNI(EnergyModel, jint batteryLevelWh, jint batteryCapacityWh, jint rangeM, jint chargeRateW,
                       jfloat drivingWhPerKm, jfloat auxWhPerKm, jfloat aeroCoef,
                       jfloat reservePct, jint maxChargeW, jint maxDischargeW) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gSession) gSession->sendEnergyModelSensor(
        batteryLevelWh, batteryCapacityWh, rangeM, chargeRateW,
        drivingWhPerKm, auxWhPerKm, aeroCoef, reservePct, maxChargeW, maxDischargeW);
}

SENSOR_JNI(Accelerometer, jint xE3, jint yE3, jint zE3) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gSession) gSession->sendAccelerometerSensor(xE3, yE3, zE3);
}

SENSOR_JNI(Gyroscope, jint rxE3, jint ryE3, jint rzE3) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gSession) gSession->sendGyroscopeSensor(rxE3, ryE3, rzE3);
}

SENSOR_JNI(Compass, jint bearingE6, jint pitchE6, jint rollE6) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gSession) gSession->sendCompassSensor(bearingE6, pitchE6, rollE6);
}

SENSOR_JNI(Rpm, jint rpmE3) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gSession) gSession->sendRpmSensor(rpmE3);
}

/*
 * Class:     com_openautolink_app_transport_aasdk_AasdkNative
 * Method:    nativeIsStreaming
 */
JNIEXPORT jboolean JNICALL
Java_com_openautolink_app_transport_aasdk_AasdkNative_nativeIsStreaming(
    JNIEnv* /*env*/, jclass /*clazz*/)
{
    std::lock_guard<std::mutex> lock(gSessionMutex);
    return gSession ? gSession->isStreaming() : JNI_FALSE;
}

} // extern "C"
