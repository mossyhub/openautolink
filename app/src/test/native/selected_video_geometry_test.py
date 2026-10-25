#!/usr/bin/env python3
"""Execute verbatim production VideoStart body with fake transport/JNI boundaries.
This is a host function-body test, not an Android/JNI integration test.
"""
import hashlib
import pathlib
import subprocess
import tempfile

root = pathlib.Path(__file__).resolve().parents[4]
source = (root / 'app/src/main/cpp/jni_session.cpp').read_text()
start = source.index('void JniSession::onMediaChannelStartIndication(')
end = source.index('\nvoid JniSession::onMediaChannelStopIndication(', start)
body = source[start:end]
print('production Start SHA256:', hashlib.sha256(body.encode()).hexdigest(), flush=True)
stubs = r'''
#include <atomic>
#include <cassert>
#include <memory>
#include <string>
#include <utility>
#include <vector>
#include <cstdio>
using jint = int;
using jlong = long long;
struct JNIEnv {
 std::pair<int,int> seen{0,0}; bool throwCallback=false, pending=false;
 bool ExceptionCheck() { return pending; }
 void ExceptionClear() { pending=false; }
 void CallVoidMethod(void*, int, jint w, jint h, jlong) { seen={w,h}; pending=throwCallback; }
};
namespace aap_protobuf::service::media::shared::message {
struct Start { int idx; int session_id() const { return 42; } int configuration_index() const { return idx; } };
}
namespace aap_protobuf::service::media::video::message {
constexpr int VIDEO_FOCUS_PROJECTED=1;
struct VideoFocusNotification { void set_focus(int) {} void set_unsolicited(bool) {} };
}
namespace aasdk::channel { struct SendPromise {
 static std::shared_ptr<SendPromise> defer(int) { return std::make_shared<SendPromise>(); }
 template<class A,class B> void then(A,B) {}
}; }
struct Channel {
 int focusSends=0;
 template<class A,class B> void sendVideoFocusIndication(A,B) { ++focusSends; }
 void receive(int) {}
};
#define LOGI(...) ((void)0)
#define LOGW(...) ((void)0)
struct JniSession {
 std::atomic<int> activeVideoSessionId_{0}, currentVideoFocus_{0};
 std::atomic<bool> stopped_{false};
 std::atomic<int64_t> selectedVideoEpoch_{0};
 std::vector<std::pair<int,int>> advertisedVideoGeometry_;
 struct { int onVideoGeometrySelected=1; } cbMethods_;
 void* callbackRef_=this;
 JNIEnv env;
 JNIEnv* getEnv(bool& attached) { attached=false; return &env; }
 void releaseEnv(bool) {}
 int strandValue=0; int* strand_=&strandValue;
 Channel channel; Channel* videoChannel_=&channel;
 int shared_from_this() { return 0; }
 template<class T> void onChannelError(T) {}
 template<class T> void logProtoRaw(const char*,T) {}
 template<class T> void reportGalStartEnvelope(const char*,T) {}
 void nativeDiag(int,const char*,const std::string&) {}
 void onMediaChannelStartIndication(const aap_protobuf::service::media::shared::message::Start&);
};
'''
cases = r'''
int main() {
 // Exact advertised order, not enum arithmetic or the saved preference.
 JniSession s;
 s.advertisedVideoGeometry_={{3840,2160},{2560,1440},{1920,1080},{1280,720},{800,480}};
 s.onMediaChannelStartIndication({3});
 assert((s.env.seen == std::pair<int,int>{1280,720}));
 s.onMediaChannelStartIndication({4});
 assert((s.env.seen == std::pair<int,int>{800,480}));
 s.advertisedVideoGeometry_={{1080,1920},{720,1280}};
 s.onMediaChannelStartIndication({1});
 assert((s.env.seen == std::pair<int,int>{720,1280}));
 // Manual unsupported dimensions use the actual advertised 1080p fallback.
 s.advertisedVideoGeometry_={{1920,1080}};
 s.onMediaChannelStartIndication({0});
 assert((s.env.seen == std::pair<int,int>{1920,1080}));
 s.onMediaChannelStartIndication({1});
 assert((s.env.seen == std::pair<int,int>{0,0}));
 s.onMediaChannelStartIndication({-1});
 assert((s.env.seen == std::pair<int,int>{0,0}));
 s.advertisedVideoGeometry_.clear();
 s.onMediaChannelStartIndication({0});
 assert((s.env.seen == std::pair<int,int>{0,0}));
 // A new callback exception must not leak to thread detach or acknowledge focus.
 s.advertisedVideoGeometry_={{1280,720}};
 s.env.throwCallback=true;
 const int beforeFocus=s.channel.focusSends;
 s.onMediaChannelStartIndication({0});
 assert(!s.env.pending && "selected-geometry callback exception leaked past JNI boundary");
 assert(s.channel.focusSends==beforeFocus && "focus acknowledged after failed geometry publication");
 puts("PASS: lower tier, 480p, portrait, manual fallback, invalid indices, empty offer");
}
'''
with tempfile.TemporaryDirectory(prefix='selected-video-') as tmp:
    path = pathlib.Path(tmp)
    (path/'test.cpp').write_text(stubs + body + cases)
    subprocess.run(['g++', '-std=c++17', '-Wall', '-Wextra', '-O0', str(path/'test.cpp'), '-o', str(path/'test')], check=True)
    subprocess.run([str(path/'test')], check=True)
