/*
 * jni_transport.cpp — aasdk ITransport backed by JNI byte pipe.
 *
 * Bridges TCP socket streams (Kotlin InputStream/OutputStream from the
 * shared-WiFi connection to the companion app) to aasdk's async
 * Promise-based transport interface.
 */
#include "jni_transport.h"

#include <android/log.h>
#include <algorithm>
#include <cstring>

#define LOG_TAG "OAL-JniTransport"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace openautolink::jni {

static constexpr size_t kReadBufferSize = 16384;

JniTransport::JniTransport(boost::asio::io_service& ioService, JavaVM* jvm, jobject javaTransport)
    : ioService_(ioService)
    , strand_(ioService)
    , jvm_(jvm)
    , javaTransport_(javaTransport)
{
    // Cache JNI method IDs
    JNIEnv* env = nullptr;
    jvm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (env) {
        jclass cls = env->GetObjectClass(javaTransport_);
        readMethodId_ = env->GetMethodID(cls, "readBytes", "(I)[B");
        writeMethodId_ = env->GetMethodID(cls, "writeBytes", "([B)V");
        env->DeleteLocalRef(cls);
    }

    // Start read thread
    readThread_ = std::thread(&JniTransport::readThreadFunc, this);
    LOGI("JniTransport created");
}

JniTransport::~JniTransport()
{
    stop();

    // Safety net: if readThread self-exited (set stopped_ directly),
    // stop() early-returned without joining. Join here to prevent
    // ~std::thread calling std::terminate on a joinable thread.
    if (readThread_.joinable()) {
        readThread_.join();
    }

    // Release JNI global ref
    if (javaTransport_) {
        JNIEnv* env = nullptr;
        bool attached = false;
        jint result = jvm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (result == JNI_EDETACHED) {
            jvm_->AttachCurrentThread(&env, nullptr);
            attached = true;
        }
        if (env) {
            env->DeleteGlobalRef(javaTransport_);
            javaTransport_ = nullptr;
        }
        if (attached) jvm_->DetachCurrentThread();
    }

    LOGI("JniTransport destroyed");
}

void JniTransport::receive(size_t size, ReceivePromise::Pointer promise)
{
    strand_.dispatch([this, size, promise = std::move(promise)]() mutable {
        if (stopped_) {
            promise->reject(aasdk::error::Error(aasdk::error::ErrorCode::OPERATION_ABORTED));
            return;
        }

        aasdk::common::Data resolveData;
        bool canResolve = false;

        {
            std::lock_guard<std::mutex> lock(receiveMutex_);

            // If we already have enough buffered data, resolve immediately
            if (receiveBuffer_.size() >= size) {
                resolveData.assign(receiveBuffer_.begin(), receiveBuffer_.begin() + size);
                receiveBuffer_.erase(receiveBuffer_.begin(), receiveBuffer_.begin() + size);
                canResolve = true;
            } else {
                // Queue the promise for later fulfillment
                receiveQueue_.push({size, std::move(promise)});
            }
        }

        // Resolve outside the lock to avoid re-entrancy deadlock
        if (canResolve) {
            promise->resolve(std::move(resolveData));
        }
    });
}

void JniTransport::send(aasdk::common::Data data, SendPromise::Pointer promise)
{
    strand_.dispatch([this, data = std::move(data), promise = std::move(promise)]() mutable {
        if (stopped_) {
            promise->reject(aasdk::error::Error(aasdk::error::ErrorCode::OPERATION_ABORTED));
            return;
        }

        // Call Java writeBytes() from strand thread
        JNIEnv* env = nullptr;
        bool attached = false;
        jint result = jvm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (result == JNI_EDETACHED) {
            jvm_->AttachCurrentThread(&env, nullptr);
            attached = true;
        }

        if (!env || !writeMethodId_) {
            promise->reject(aasdk::error::Error(aasdk::error::ErrorCode::OPERATION_ABORTED));
            if (attached) jvm_->DetachCurrentThread();
            return;
        }

        jbyteArray jdata = env->NewByteArray(static_cast<jsize>(data.size()));
        env->SetByteArrayRegion(jdata, 0, static_cast<jsize>(data.size()),
                                reinterpret_cast<const jbyte*>(data.data()));
        env->CallVoidMethod(javaTransport_, writeMethodId_, jdata);

        bool hadException = env->ExceptionCheck();
        if (hadException) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            env->DeleteLocalRef(jdata);
            if (attached) jvm_->DetachCurrentThread();
            promise->reject(aasdk::error::Error(aasdk::error::ErrorCode::OPERATION_ABORTED));
            return;
        }

        env->DeleteLocalRef(jdata);
        if (attached) jvm_->DetachCurrentThread();

        promise->resolve();
    });
}

void JniTransport::stop()
{
    if (stopped_.exchange(true)) return;

    LOGI("JniTransport stopping");

    // Wake up read thread
    receiveCv_.notify_all();

    if (readThread_.joinable()) {
        readThread_.join();
    }

    // Reject all pending receive promises
    strand_.dispatch([this]() {
        std::lock_guard<std::mutex> lock(receiveMutex_);
        while (!receiveQueue_.empty()) {
            auto& [size, promise] = receiveQueue_.front();
            promise->reject(aasdk::error::Error(aasdk::error::ErrorCode::OPERATION_ABORTED));
            receiveQueue_.pop();
        }
    });
}

void JniTransport::onDataReceived(const uint8_t* data, size_t length)
{
    if (stopped_) return;

    {
        std::lock_guard<std::mutex> lock(receiveMutex_);
        receiveBuffer_.insert(receiveBuffer_.end(), data, data + length);
    }

    // Try to fulfill pending receive promises
    processReceiveQueue();
}

void JniTransport::readThreadFunc()
{
    JNIEnv* env = nullptr;
    jvm_->AttachCurrentThread(&env, nullptr);

    LOGI("Read thread started");

    std::vector<uint8_t> localBuf(kReadBufferSize);

    while (!stopped_) {
        if (!readMethodId_) {
            LOGE("readBytes method not found");
            break;
        }

        // Call Java readBytes(maxSize) — blocks until data available
        jbyteArray jdata = static_cast<jbyteArray>(
            env->CallObjectMethod(javaTransport_, readMethodId_, static_cast<jint>(kReadBufferSize)));

        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            LOGW("Read thread: Java exception, stopping");
            break;
        }

        if (!jdata) {
            LOGW("Read thread: null returned (stream closed)");
            break;
        }

        jsize len = env->GetArrayLength(jdata);
        if (len <= 0) {
            env->DeleteLocalRef(jdata);
            continue;
        }

        if (static_cast<size_t>(len) > localBuf.size()) {
            localBuf.resize(len);
        }
        env->GetByteArrayRegion(jdata, 0, len, reinterpret_cast<jbyte*>(localBuf.data()));
        env->DeleteLocalRef(jdata);

        // Feed into receive buffer (per-buffer log dropped — fires hundreds of
        // times/second on the AA hot path. Re-enable behind a verbose flag if
        // you need it.)
        onDataReceived(localBuf.data(), static_cast<size_t>(len));
    }

    LOGI("Read thread exiting");
    jvm_->DetachCurrentThread();

    // Signal that the transport is dead so consumers stop sending.
    // Do NOT set stopped_ here — that would cause stop() to skip
    // joining this thread, leading to ~thread calling std::terminate.
    // Instead, reject pending promises and let stop() handle the flag.
    strand_.post([this]() {
        std::lock_guard<std::mutex> lock(receiveMutex_);
        while (!receiveQueue_.empty()) {
            auto& [size, promise] = receiveQueue_.front();
            promise->reject(aasdk::error::Error(aasdk::error::ErrorCode::OPERATION_ABORTED));
            receiveQueue_.pop();
        }
    });
}

void JniTransport::processReceiveQueue()
{
    strand_.dispatch([this]() {
        // Collect resolved promises outside the lock to avoid re-entrancy deadlock
        std::vector<std::pair<ReceivePromise::Pointer, aasdk::common::Data>> resolved;

        {
            std::lock_guard<std::mutex> lock(receiveMutex_);

            while (!receiveQueue_.empty()) {
                auto& [needed, promise] = receiveQueue_.front();

                if (receiveBuffer_.size() >= needed) {
                    aasdk::common::Data data(receiveBuffer_.begin(),
                                             receiveBuffer_.begin() + needed);
                    receiveBuffer_.erase(receiveBuffer_.begin(),
                                         receiveBuffer_.begin() + needed);
                    resolved.emplace_back(std::move(promise), std::move(data));
                    receiveQueue_.pop();
                } else {
                    break; // Not enough data yet
                }
            }
        }

        // Resolve outside the lock
        for (auto& [p, data] : resolved) {
            p->resolve(std::move(data));
        }
    });
}

} // namespace openautolink::jni
