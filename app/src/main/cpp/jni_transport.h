/*
 * jni_transport.h — aasdk ITransport backed by JNI byte pipe.
 *
 * The Kotlin side provides InputStream/OutputStream from a TCP socket
 * connected to the phone-side companion app (running on the shared WiFi
 * — Car Hotspot or Phone Hotspot). This transport reads/writes through
 * JNI callbacks to those streams.
 *
 * Data flow:
 *   Phone ↔ TCP (Kotlin) ↔ JniTransport (C++) ↔ aasdk Messenger
 */
#pragma once

#include <atomic>
#include <condition_variable>
#include <mutex>
#include <queue>
#include <thread>
#include <vector>

#include <jni.h>
#include <aasdk/Transport/ITransport.hpp>
#include <aasdk/IO/Promise.hpp>
#include <aasdk/Common/Data.hpp>
#include <boost/asio.hpp>

namespace openautolink::jni {

/**
 * Transport that bridges aasdk's async receive/send to JNI pipe I/O.
 *
 * Architecture:
 * - A read thread calls back into Kotlin to read bytes from the TCP InputStream.
 * - On receive, data is posted to the io_service strand for aasdk consumption.
 * - send() calls back into Kotlin to write bytes to the TCP OutputStream.
 *
 * Thread model:
 * - readThread_: pulls data from Java InputStream, posts to strand
 * - aasdk strand: dispatches receive promises, queues sends
 * - JNI calls to Java: always from readThread_ or sendStrand
 */
class JniTransport : public aasdk::transport::ITransport {
public:
    using Pointer = std::shared_ptr<JniTransport>;

    /**
     * @param ioService  Boost io_service for strand-based async dispatch
     * @param env        JNI environment (will be attached to read/write threads)
     * @param javaTransport  Global ref to Kotlin AasdkTransportPipe object
     */
    JniTransport(boost::asio::io_service& ioService, JavaVM* jvm, jobject javaTransport);
    ~JniTransport() override;

    void receive(size_t size, ReceivePromise::Pointer promise) override;
    void send(aasdk::common::Data data, SendPromise::Pointer promise) override;
    void stop() override;

    /** Called from Kotlin when TCP stream data arrives (push model). */
    void onDataReceived(const uint8_t* data, size_t length);

private:
    void readThreadFunc();
    void processReceiveQueue();

    boost::asio::io_service& ioService_;
    boost::asio::io_service::strand strand_;
    JavaVM* jvm_;
    jobject javaTransport_;  // Global ref to Kotlin transport pipe

    // Receive side: buffered data + pending promises
    std::mutex receiveMutex_;
    std::condition_variable receiveCv_;
    std::vector<uint8_t> receiveBuffer_;
    std::queue<std::pair<size_t, ReceivePromise::Pointer>> receiveQueue_;

    // Read thread pulls from Java InputStream
    std::thread readThread_;
    std::atomic<bool> stopped_{false};

    // JNI method IDs (cached on construction)
    jmethodID readMethodId_ = nullptr;
    jmethodID writeMethodId_ = nullptr;
};

} // namespace openautolink::jni
