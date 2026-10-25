#!/usr/bin/env python3
"""Execute the production queued touch body with a held writer queue (host only)."""
import pathlib, subprocess, tempfile, hashlib
root = pathlib.Path(__file__).resolve().parents[4]
source = (root/'app/src/main/cpp/jni_session.cpp').read_text()
start = source.index('void JniSession::sendMultiTouchEvent(')
body = source[start:source.index('\nvoid JniSession::sendKeyEvent(', start)]
qualified = 'int64_t expectedSelection' in body
stub = r'''
#include <atomic>
#include <cassert>
#include <chrono>
#include <functional>
#include <memory>
#include <vector>
#include <cstdint>
#include <cstdio>
namespace aap_protobuf::service::inputsource::message {
enum PointerAction { DOWN=0 };
struct Pointer { void set_x(uint32_t){} void set_y(uint32_t){} void set_pointer_id(uint32_t){} };
struct Touch { Pointer p; void set_action(PointerAction){} void set_action_index(uint32_t){} Pointer* add_pointer_data(){return &p;} };
struct InputReport { Touch t; void set_timestamp(uint64_t){} Touch* mutable_touch_event(){return &t;} };
}
namespace aasdk::channel { struct SendPromise {
static std::shared_ptr<SendPromise> defer(int){return std::make_shared<SendPromise>();}
template<class A,class B> void then(A,B){}
}; }
struct Queue { std::vector<std::function<void()>> jobs; template<class F> void post(F f){jobs.emplace_back(f);} void drain(){auto j=std::move(jobs);for(auto& f:j)f();} };
struct Channel { int sends=0; template<class A,class B> void sendInputReport(A,B){++sends;} };
struct JniSession : std::enable_shared_from_this<JniSession> {
 std::atomic<bool> streaming_{true}, stopped_{false};
 std::atomic<int64_t> selectedVideoEpoch_{1};
 Queue queue; Queue* ioService_=&queue; Channel channel; Channel* inputChannel_=&channel; int strand=0; int* strand_=&strand;
 DECL
};
'''
decl = 'void sendMultiTouchEvent(int,int,const int*,const float*,const float*,int' + (',int64_t expectedSelection=0' if qualified else '') + ');'
stub = stub.replace('DECL', decl)
args = ',1' if qualified else ''
cases = r'''
int main(){
 int id=0; float x=100,y=200;
 auto s=std::make_shared<JniSession>();
 s->sendMultiTouchEvent(0,0,&id,&x,&y,1 ARGS);
 s->selectedVideoEpoch_=2; // New Start after enqueue, before actual writer.
 s->queue.drain();
 assert(s->channel.sends==0 && "old selected-geometry touch reached new projection");
 s->selectedVideoEpoch_=1;
 s->sendMultiTouchEvent(0,0,&id,&x,&y,1 ARGS);
 s->stopped_=true;
 s->queue.drain();
 assert(s->channel.sends==0 && "queued touch survived session retirement");
 s->stopped_=false;
 s->sendMultiTouchEvent(0,0,&id,&x,&y,1 ARGS);
 s->queue.drain();
 assert(s->channel.sends==1 && "current selected touch must reach phone");
 puts("PASS: stale selection, retired session rejected; current touch delivered");
}
'''.replace(' ARGS',args)
print('production body SHA256',hashlib.sha256(body.encode()).hexdigest(),flush=True)
with tempfile.TemporaryDirectory(prefix='queued-touch-') as tmp:
 p=pathlib.Path(tmp); (p/'test.cpp').write_text(stub+body+cases)
 subprocess.run(['g++','-std=c++17','-Wall','-Wextra','-O0',str(p/'test.cpp'),'-o',str(p/'test')],check=True)
 subprocess.run([str(p/'test')],check=True)
