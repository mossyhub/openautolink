#include "capture.h"
#include <aaudio/AAudio.h>
#include <algorithm>
#include <cstdlib>
#include <iostream>
#include <stdexcept>
#include <vector>
using namespace capture_spike;
struct AAudioStreamBuilderStruct {};
struct AAudioStreamStruct {};
static AAudioStreamBuilder builder;
static AAudioStream stream;
static std::vector<int> settings;
static int reads=0, closes=0, stops=0, stopError=0;
static int readMode=0, closeError=0, openError=0, actualRate=48000, startError=0;
static std::mutex fakeMutex;
static std::condition_variable fakeCondition;
static bool holdRead=false, readEntered=false, releaseRead=false;
static bool holdClose=false, closeEntered=false, releaseClose=false;
static void waitFlag(bool& flag) {
 std::unique_lock<std::mutex> lock(fakeMutex);
 if(!fakeCondition.wait_for(lock,std::chrono::seconds(2),[&]{return flag;}))
   throw std::runtime_error("fake platform barrier timed out");
}
static void resetFake() {
 settings.clear();reads=closes=stops=stopError=0;
 readMode=closeError=openError=startError=0;actualRate=48000;
 holdRead=readEntered=releaseRead=holdClose=closeEntered=releaseClose=false;
}
#define CHECK(x) do { if(!(x)) throw std::runtime_error(#x); } while(0)
extern "C" {
aaudio_result_t AAudio_createStreamBuilder(AAudioStreamBuilder** b){*b=&builder;return 0;}
aaudio_result_t AAudioStreamBuilder_delete(AAudioStreamBuilder*){return 0;}
void AAudioStreamBuilder_setDirection(AAudioStreamBuilder*,aaudio_direction_t v){settings.push_back(100+v);}
void AAudioStreamBuilder_setSampleRate(AAudioStreamBuilder*,int32_t v){settings.push_back(v);}
void AAudioStreamBuilder_setChannelCount(AAudioStreamBuilder*,int32_t v){settings.push_back(200+v);}
void AAudioStreamBuilder_setChannelMask(AAudioStreamBuilder*,aaudio_channel_mask_t v){settings.push_back(300+v);}
void AAudioStreamBuilder_setFormat(AAudioStreamBuilder*,aaudio_format_t v){settings.push_back(400+v);}
void AAudioStreamBuilder_setInputPreset(AAudioStreamBuilder*,aaudio_input_preset_t v){settings.push_back(500+v);}
void AAudioStreamBuilder_setSharingMode(AAudioStreamBuilder*,aaudio_sharing_mode_t v){settings.push_back(600+v);}
void AAudioStreamBuilder_setPerformanceMode(AAudioStreamBuilder*,aaudio_performance_mode_t v){settings.push_back(700+v);}
void AAudioStreamBuilder_setDeviceId(AAudioStreamBuilder*,int32_t v){settings.push_back(800+v);}
void AAudioStreamBuilder_setSessionId(AAudioStreamBuilder*,aaudio_session_id_t v){settings.push_back(900+v);}
void AAudioStreamBuilder_setPrivacySensitive(AAudioStreamBuilder*,bool v){settings.push_back(1000+v);}
aaudio_result_t AAudioStreamBuilder_openStream(AAudioStreamBuilder*,AAudioStream** s){*s=openError?nullptr:&stream;return openError;}
int32_t AAudioStream_getSampleRate(AAudioStream*){return actualRate;}
int32_t AAudioStream_getChannelCount(AAudioStream*){return 2;}
aaudio_channel_mask_t AAudioStream_getChannelMask(AAudioStream*){return AAUDIO_CHANNEL_STEREO;}
aaudio_format_t AAudioStream_getFormat(AAudioStream*){return AAUDIO_FORMAT_PCM_I16;}
aaudio_input_preset_t AAudioStream_getInputPreset(AAudioStream*){return AAUDIO_INPUT_PRESET_VOICE_RECOGNITION;}
aaudio_sharing_mode_t AAudioStream_getSharingMode(AAudioStream*){return AAUDIO_SHARING_MODE_SHARED;}
aaudio_performance_mode_t AAudioStream_getPerformanceMode(AAudioStream*){return AAUDIO_PERFORMANCE_MODE_LOW_LATENCY;}
int32_t AAudioStream_getDeviceId(AAudioStream*){return 47;}
aaudio_session_id_t AAudioStream_getSessionId(AAudioStream*){return AAUDIO_SESSION_ID_NONE;}
bool AAudioStream_isPrivacySensitive(AAudioStream*){return false;}
aaudio_result_t AAudioStream_requestStart(AAudioStream*){return startError;}
aaudio_result_t AAudioStream_requestStop(AAudioStream*){++stops;return stopError;}
aaudio_result_t AAudioStream_close(AAudioStream*){
 std::unique_lock<std::mutex> lock(fakeMutex);closeEntered=true;fakeCondition.notify_all();
 if(holdClose) fakeCondition.wait(lock,[]{return releaseClose;});
 ++closes;return closeError;
}
aaudio_result_t AAudioStream_read(AAudioStream*,void* p,int32_t frames,int64_t timeout){
 {std::unique_lock<std::mutex> lock(fakeMutex);readEntered=true;fakeCondition.notify_all();
 if(holdRead) fakeCondition.wait(lock,[]{return releaseRead;});}
 CHECK(timeout>0 && timeout<=20000000);CHECK(frames>0 && frames<=480);
 ++reads;
 if(readMode==1) return 0;
 if(readMode==3) return AAUDIO_ERROR_DISCONNECTED;
 if(readMode==4) frames=std::min(frames,251);
 if(readMode==5) std::this_thread::sleep_for(std::chrono::milliseconds(21));
 auto* b=static_cast<int16_t*>(p); for(int i=0;i<frames;++i){b[i*2]=readMode==2?0:-32768;b[i*2+1]=readMode==2?0:32767;}return frames;
}
}
static void fixed_shape_numeric_boundary(){
 resetFake();
 std::atomic<bool> fence{false};Runner r(fence);CHECK(r.start());CHECK(r.await(std::chrono::seconds(3)));
 auto s=r.summary();CHECK(s.frames==96000);CHECK(s.channel[0].samples==96000);CHECK(s.channel[0].nonzero==96000);
 CHECK(s.channel[0].peak==32768);CHECK(s.channel[1].peak==32767);CHECK(s.channel[0].clipped==96000);
 CHECK(s.channel[0].squares==uint64_t(96000)*32768*32768);CHECK(s.channel[1].squares==uint64_t(96000)*32767*32767);
 CHECK(s.actualPreset==6 && s.actualRate==48000 && s.actualChannels==2);CHECK(s.routeStable==1 && s.sessionTopology==0);
 CHECK(s.closeReturned && s.joined && !fence.load());CHECK(reads==200 && closes==1);
 for(int v: {101,48000,202,300+int(AAUDIO_CHANNEL_STEREO),401,506,601,712,800,899,1000})
   CHECK(std::count(settings.begin(),settings.end(),v)==1);
 CHECK(settings.size()==11);
}
static void cancellation_retains_fence_until_actual_close_and_join(){
 resetFake();holdRead=true;holdClose=true;
 std::atomic<bool> fence{false};Runner r(fence);CHECK(r.start());waitFlag(readEntered);
 r.cancel();r.cancel();CHECK(!r.await(std::chrono::milliseconds(20)));CHECK(fence.load());
 Runner successor(fence);CHECK(!successor.start());
 {std::lock_guard<std::mutex> lock(fakeMutex);releaseRead=true;}fakeCondition.notify_all();
 waitFlag(closeEntered);CHECK(!r.await(std::chrono::milliseconds(20)));CHECK(fence.load());
 {std::lock_guard<std::mutex> lock(fakeMutex);releaseClose=true;}fakeCondition.notify_all();
 // Give the completed worker time to return, without acknowledging its join.
 std::this_thread::sleep_for(std::chrono::milliseconds(100));CHECK(fence.load());
 CHECK(r.await(std::chrono::seconds(2)));CHECK(!fence.load());CHECK(r.summary().frames==0);
 CHECK(r.summary().joined && r.summary().closeReturned);CHECK(!r.start());
}
static void old_join_and_destruction_cannot_release_successor(){
 resetFake();std::atomic<bool> fence{false};
 auto old=std::make_unique<Runner>(fence);CHECK(old->start());CHECK(old->await(std::chrono::seconds(2)));
 // Successor is held in its own native read until the old owner is destroyed.
 holdRead=true;readEntered=false;Runner next(fence);CHECK(next.start());waitFlag(readEntered);
 CHECK(old->await(std::chrono::milliseconds(0)));
 bool retainedAfterRepeat=fence.load();old.reset();bool retainedAfterDestroy=fence.load();
 next.cancel();{std::lock_guard<std::mutex> lock(fakeMutex);releaseRead=true;}fakeCondition.notify_all();
 CHECK(next.await(std::chrono::seconds(2)));CHECK(retainedAfterRepeat && retainedAfterDestroy);
}
static Summary completed(std::atomic<bool>& fence) {
 Runner r(fence);CHECK(r.start());CHECK(r.await(std::chrono::seconds(4)));return r.summary();
}
static void terminal_reason_and_rms(){
 resetFake();std::atomic<bool> fence{false};auto s=completed(fence);
 CHECK(s.termination==1);CHECK(s.channel[0].rms==32768 && s.channel[1].rms==32767);
}
static void empty_reads_are_not_zero_samples(){
 resetFake();readMode=1;std::atomic<bool> fence{false};auto s=completed(fence);
 CHECK(s.frames==0 && s.emptyReads==400 && s.channel[0].samples==0);CHECK(s.termination==4 && s.error==0);
}
static void positive_frames_of_silence_are_counted(){
 resetFake();readMode=2;std::atomic<bool> fence{false};auto s=completed(fence);
 CHECK(s.frames==96000 && s.channel[0].samples==96000 && s.channel[0].nonzero==0);
 CHECK(s.channel[0].rms==0 && s.termination==1);
}
static void close_failure_never_releases_exclusion(){
 resetFake();closeError=AAUDIO_ERROR_INTERNAL;std::atomic<bool> fence{false};auto s=completed(fence);
 CHECK(fence.load() && !s.closeReturned && s.joined);CHECK(s.error==closeError && s.termination==5);
 Runner next(fence);CHECK(!next.start());
}
static void open_failure_is_not_zero_capture(){
 resetFake();openError=AAUDIO_ERROR_UNAVAILABLE;std::atomic<bool> fence{false};auto s=completed(fence);
 CHECK(s.frames==0 && reads==0 && closes==0 && !fence.load());CHECK(s.error==openError && s.termination==5);
}
static void negotiated_mismatch_is_not_read(){
 resetFake();actualRate=24000;std::atomic<bool> fence{false};auto s=completed(fence);
 CHECK(s.actualRate==24000 && reads==0 && closes==1);CHECK(s.error<0 && s.termination==5);
}
static void disconnected_read_closes_without_retry(){
 resetFake();readMode=3;std::atomic<bool> fence{false};auto s=completed(fence);
 CHECK(reads==1 && closes==1 && s.frames==0 && !fence.load());CHECK(s.error==AAUDIO_ERROR_DISCONNECTED && s.termination==5);
}
static void partial_reads_stop_at_exact_frame_budget(){
 resetFake();readMode=4;std::atomic<bool> fence{false};auto s=completed(fence);
 CHECK(s.frames==96000 && s.channel[0].samples==96000 && reads==383);CHECK(s.termination==1);
}
static void real_monotonic_deadline_is_bounded(){
 resetFake();readMode=5;std::atomic<bool> fence{false};auto begin=std::chrono::steady_clock::now();auto s=completed(fence);
 CHECK(s.termination==3 && s.frames>0 && s.frames<96000);
 CHECK(std::chrono::steady_clock::now()-begin<std::chrono::seconds(3));
}
static void stop_failure_is_not_reported_as_success(){
 resetFake();stopError=AAUDIO_ERROR_INTERNAL;std::atomic<bool> fence{false};auto s=completed(fence);
 CHECK(stops==1 && closes==1 && !fence.load());CHECK(s.error==stopError && s.termination==5);
}
static void stop_and_close_failures_are_separate_terminal_receipts(){
 resetFake();stopError=AAUDIO_ERROR_INVALID_STATE;closeError=AAUDIO_ERROR_INTERNAL;
 std::atomic<bool> fence{false};Runner r(fence);CHECK(r.start());CHECK(r.await(std::chrono::seconds(3)));
 auto s=r.summary();CHECK(s.stopError==stopError && s.closeError==closeError);
 CHECK(s.recoveryRequiresRestart && s.error==closeError && s.joined && fence.load());
 CHECK(r.await(std::chrono::milliseconds(0)));r.cancel();
 CHECK(stops==1 && closes==1);Runner next(fence);CHECK(!next.start());
}
static void cancelled_failed_close_retains_terminal_owner_without_retry(){
 resetFake();holdRead=true;closeError=AAUDIO_ERROR_INTERNAL;std::atomic<bool> fence{false};
 {
  Runner r(fence);CHECK(r.start());waitFlag(readEntered);r.cancel();
  {std::lock_guard<std::mutex> lock(fakeMutex);releaseRead=true;}fakeCondition.notify_all();
  CHECK(r.await(std::chrono::seconds(3)));auto s=r.summary();
  CHECK(s.frames==0 && s.recoveryRequiresRestart && s.joined && !s.closeReturned);
  CHECK(r.await(std::chrono::milliseconds(0)));CHECK(closes==1 && stops==1);
 }
 CHECK(fence.load() && closes==1);Runner successor(fence);CHECK(!successor.start());
}
static void destructor_is_an_off_control_thread_blocking_backstop(){
 resetFake();holdRead=true;holdClose=true;std::atomic<bool> fence{false};
 auto r=std::make_unique<Runner>(fence);CHECK(r->start());waitFlag(readEntered);
 std::atomic<bool> destroyed{false};
 std::thread cleanup([owner=std::move(r),&destroyed]() mutable {owner.reset();destroyed=true;});
 {std::lock_guard<std::mutex> lock(fakeMutex);releaseRead=true;}fakeCondition.notify_all();
 waitFlag(closeEntered);
 bool pending=!destroyed.load() && fence.load();
 {std::lock_guard<std::mutex> lock(fakeMutex);releaseClose=true;}fakeCondition.notify_all();
 cleanup.join();CHECK(pending && destroyed.load() && !fence.load() && closes==1);
}
static void start_failure_closes_without_read(){
 resetFake();startError=AAUDIO_ERROR_INVALID_STATE;std::atomic<bool> fence{false};auto s=completed(fence);
 CHECK(reads==0 && closes==1 && s.error==startError && s.termination==5);
}
static void revoked_before_begin_has_no_platform_effect(){
 resetFake();std::atomic<bool> fence{false};Runner r(fence);r.cancel();
 CHECK(r.start());CHECK(r.await(std::chrono::seconds(3)));
 CHECK(settings.empty());CHECK(r.summary().termination==2 && r.summary().closeReturned);
}
static void revoked_policy_before_open_has_no_platform_effect(){
 resetFake();std::atomic<bool> fence{false};Runner r(fence,[]{return false;});
 CHECK(r.start());CHECK(r.await(std::chrono::seconds(3)));
 CHECK(settings.empty());CHECK(r.summary().termination==2 && r.summary().closeReturned);
}
int main(){try{
 const std::vector<std::pair<const char*,void(*)()>> tests={
 {"revoked_policy_before_open_has_no_platform_effect",revoked_policy_before_open_has_no_platform_effect},
 {"revoked_before_begin_has_no_platform_effect",revoked_before_begin_has_no_platform_effect},
 {"fixed_shape_numeric_boundary",fixed_shape_numeric_boundary},
 {"cancellation_retains_fence_until_actual_close_and_join",cancellation_retains_fence_until_actual_close_and_join},
 {"old_join_and_destruction_cannot_release_successor",old_join_and_destruction_cannot_release_successor},
 {"terminal_reason_and_rms",terminal_reason_and_rms},
 {"empty_reads_are_not_zero_samples",empty_reads_are_not_zero_samples},
 {"positive_frames_of_silence_are_counted",positive_frames_of_silence_are_counted},
 {"close_failure_never_releases_exclusion",close_failure_never_releases_exclusion},
 {"open_failure_is_not_zero_capture",open_failure_is_not_zero_capture},
 {"negotiated_mismatch_is_not_read",negotiated_mismatch_is_not_read},
 {"disconnected_read_closes_without_retry",disconnected_read_closes_without_retry},
 {"partial_reads_stop_at_exact_frame_budget",partial_reads_stop_at_exact_frame_budget},
 {"real_monotonic_deadline_is_bounded",real_monotonic_deadline_is_bounded},
 {"start_failure_closes_without_read",start_failure_closes_without_read},
 {"stop_failure_is_not_reported_as_success",stop_failure_is_not_reported_as_success},
 {"stop_and_close_failures_are_separate_terminal_receipts",stop_and_close_failures_are_separate_terminal_receipts},
 {"cancelled_failed_close_retains_terminal_owner_without_retry",cancelled_failed_close_retains_terminal_owner_without_retry},
 {"destructor_is_an_off_control_thread_blocking_backstop",destructor_is_an_off_control_thread_blocking_backstop}};
 for(auto test:tests){test.second();std::cout<<"PASS "<<test.first<<"\n";}
 std::cout<<tests.size()<<" tests, 0 failures\n";return 0;}
 catch(const std::exception& e){std::cout<<"FAIL "<<e.what()<<"\n";return 1;}}
