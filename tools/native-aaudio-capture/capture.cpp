#include "capture.h"
#include <aaudio/AAudio.h>
#include <algorithm>
#include <array>
#include <cmath>

namespace capture_spike {
Runner::~Runner() {
    cancel();
    if (worker_.joinable()) worker_.join();
    if (ownsFence_ && summary_.closeReturned) fence_.store(false);
}
bool Runner::start() {
    if (admitted_) return false;
    bool expected=false;
    if (!fence_.compare_exchange_strong(expected,true)) return false;
    admitted_=true; ownsFence_=true;
    try { worker_=std::thread([this]{run();}); }
    catch (...) { admitted_=false; ownsFence_=false; fence_.store(false); return false; }
    return true;
}
bool Runner::await(std::chrono::milliseconds timeout) {
    if (!admitted_) return false;
    { std::unique_lock<std::mutex> lock(mutex_);
      if (!doneCondition_.wait_for(lock,timeout,[this]{return done_;})) return false; }
    if (worker_.joinable()) worker_.join();
    summary_.joined=true;
    if (ownsFence_ && summary_.closeReturned) { ownsFence_=false; fence_.store(false); }
    return true;
}
void Runner::run() {
    AAudioStreamBuilder* builder=nullptr;
    AAudioStream* stream=nullptr;
    auto& s=summary_;
    s.termination=5;
    if (revoked()) {
        s.termination=2; s.closeReturned=true;
        { std::lock_guard<std::mutex> lock(mutex_); done_=true; }
        doneCondition_.notify_all();
        return;
    }
    s.error=AAudio_createStreamBuilder(&builder);
    if (s.error==AAUDIO_OK) {
        AAudioStreamBuilder_setDirection(builder,AAUDIO_DIRECTION_INPUT);
        AAudioStreamBuilder_setSampleRate(builder,48000);
        AAudioStreamBuilder_setChannelCount(builder,2);
        AAudioStreamBuilder_setChannelMask(builder,AAUDIO_CHANNEL_STEREO);
        AAudioStreamBuilder_setFormat(builder,AAUDIO_FORMAT_PCM_I16);
        AAudioStreamBuilder_setInputPreset(builder,AAUDIO_INPUT_PRESET_VOICE_RECOGNITION);
        AAudioStreamBuilder_setSharingMode(builder,AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setPerformanceMode(builder,AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setDeviceId(builder,AAUDIO_UNSPECIFIED);
        AAudioStreamBuilder_setSessionId(builder,AAUDIO_SESSION_ID_NONE);
        AAudioStreamBuilder_setPrivacySensitive(builder,false);
        s.error=revoked()?AAUDIO_ERROR_INVALID_STATE:AAudioStreamBuilder_openStream(builder,&stream);
        AAudioStreamBuilder_delete(builder);
    }
    if (s.error==AAUDIO_OK && stream) {
        s.actualRate=AAudioStream_getSampleRate(stream);
        s.actualChannels=AAudioStream_getChannelCount(stream);
        s.actualFormat=AAudioStream_getFormat(stream);
        s.actualMask=AAudioStream_getChannelMask(stream);
        s.actualPreset=AAudioStream_getInputPreset(stream);
        s.actualSharing=AAudioStream_getSharingMode(stream);
        s.actualPerformance=AAudioStream_getPerformanceMode(stream);
        auto session=AAudioStream_getSessionId(stream);
        s.sessionTopology=session==AAUDIO_SESSION_ID_NONE?0:session>0?1:-1;
        s.privacySensitive=AAudioStream_isPrivacySensitive(stream);
        const int firstDevice=AAudioStream_getDeviceId(stream); // transient; never exported
        if (s.actualRate!=48000 || s.actualChannels!=2 || s.actualFormat!=AAUDIO_FORMAT_PCM_I16 ||
            s.actualPreset!=AAUDIO_INPUT_PRESET_VOICE_RECOGNITION ||
            s.actualMask!=AAUDIO_CHANNEL_STEREO || s.actualSharing!=AAUDIO_SHARING_MODE_SHARED) {
            s.error=AAUDIO_ERROR_INVALID_FORMAT;
        } else if (!revoked()) {
            s.error=AAudioStream_requestStart(stream);
            const auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(2);
            std::array<int16_t,960> pcm{};
            unsigned attempts=0;
            while (s.error==AAUDIO_OK && !revoked() && s.frames<96000 &&
                   std::chrono::steady_clock::now()<deadline && attempts++<400) {
                const int wanted=static_cast<int>(std::min<uint64_t>(480,96000-s.frames));
                const int n=AAudioStream_read(stream,pcm.data(),wanted,20000000);
                if (revoked()) { std::fill(pcm.begin(),pcm.end(),0); break; }
                if (n<0) {s.error=n;break;}
                if (n>wanted) {s.error=AAUDIO_ERROR_OUT_OF_RANGE;break;}
                if (n==0) ++s.emptyReads;
                for (int f=0;f<n;++f) for(int c=0;c<2;++c) {
                    const int32_t sample=pcm[f*2+c];
                    const uint32_t magnitude=sample<0?-sample:sample;
                    auto& channel=s.channel[c];
                    ++channel.samples; channel.nonzero+=sample!=0;
                    channel.peak=std::max(channel.peak,magnitude);
                    channel.clipped+=(sample==-32768 || sample==32767);
                    channel.squares+=static_cast<int64_t>(sample)*sample;
                }
                s.frames+=n;
                std::fill(pcm.begin(),pcm.end(),0);
            }
            s.termination=revoked()?2:s.error!=AAUDIO_OK?5:s.frames==96000?1:
                std::chrono::steady_clock::now()>=deadline?3:4;
        } else {
            s.termination=2;
        }
        const int lastDevice=AAudioStream_getDeviceId(stream);
        s.routeStable=(firstDevice<=0 || lastDevice<=0)?-1:(firstDevice==lastDevice?1:0);
        s.stopError=AAudioStream_requestStop(stream);
        if (s.stopError!=AAUDIO_OK) {s.error=s.stopError;s.termination=5;}
        s.closeError=AAudioStream_close(stream);
        // No callback is registered and every stream call is worker-confined. A close
        // failure here is not the AOSP callback-misuse case permitting another-thread
        // close. Treat handle validity as unknown; never call it again, even on error.
        stream=nullptr;
        s.closeReturned=s.closeError==AAUDIO_OK;
        if (!s.closeReturned) {
            s.error=s.closeError;s.termination=5;s.recoveryRequiresRestart=true;
        }
    } else {
        // No opened stream means there is nothing to close.
        s.closeReturned=true;
        if (s.error==AAUDIO_OK) s.error=AAUDIO_ERROR_NULL;
    }
    for (auto& channel:s.channel) if (channel.samples)
        channel.rms=std::sqrt(static_cast<double>(channel.squares)/channel.samples);
    { std::lock_guard<std::mutex> lock(mutex_); done_=true; }
    doneCondition_.notify_all();
}
}
