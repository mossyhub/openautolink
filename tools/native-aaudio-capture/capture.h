#pragma once
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <functional>
#include <utility>
#include <thread>

namespace capture_spike {
struct Channel { uint64_t samples=0, nonzero=0, squares=0, clipped=0; uint32_t peak=0; double rms=0; };
struct Summary {
    int requestedPreset=6, requestedRate=48000, requestedChannels=2, requestedDevice=0;
    int actualPreset=0, actualRate=0, actualChannels=0, actualFormat=0;
    int actualSharing=-1, actualPerformance=-1, actualMask=0;
    int sessionTopology=-1; // -1 unknown, 0 NONE, 1 allocated: never an identifier
    int routeStable=-1; // -1 unknown, 0 changed, 1 same logical endpoint
    bool privacySensitive=false, closeReturned=false, joined=false;
    int error=0, stopError=0, closeError=0;
    bool recoveryRequiresRestart=false;
    int termination=0; // 0 unknown, 1 frames, 2 cancel, 3 deadline, 4 read budget, 5 failed
    uint64_t frames=0, emptyReads=0;
    Channel channel[2];
};
// No Android entry point/JNI/app linkage. Caller must own existing admission/consent.
// Fence here is a standalone contract, NOT the application's capture lease.
class Runner {
 public:
    explicit Runner(std::atomic<bool>& fence, std::function<bool()> policy = []{return true;}):
        fence_(fence), policy_(std::move(policy)) {}
    // Fallback only: cancel + unbounded join. Never call on UI/JNI finalizer thread.
    ~Runner();
    bool start();
    void cancel() { cancelled_.store(true); }
    // Single cleanup/control thread only (also start, summary, destruction).
    // cancel alone may race the worker. Timeout bounds waiting for done, NOT join.
    bool await(std::chrono::milliseconds timeout);
    Summary summary() const { return summary_; } // only after await returned true
 private:
    void run();
    std::atomic<bool>& fence_;
    std::function<bool()> policy_;
    bool revoked() {
        if (cancelled_.load()) return true;
        try { if (policy_()) return cancelled_.load(); } catch (...) {}
        cancelled_.store(true); return true;
    }
    std::atomic<bool> cancelled_{false};
    std::thread worker_;
    std::mutex mutex_;
    std::condition_variable doneCondition_;
    bool done_=false, admitted_=false, ownsFence_=false;
    Summary summary_;
};
}
