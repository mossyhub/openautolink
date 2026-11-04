#!/usr/bin/env python3
"""Verbatim production-body lifetime/dispatch probes; fake and real Boost queues.

Not whole-JNI/session-stop or Android execution. Queue/handler/registry fixtures
expose ownership and scheduling only. Source overrides allow one-at-a-time old-
body negative controls without a manufactured test-only implementation.
"""
from pathlib import Path
import hashlib
import json
import os
import subprocess

root = Path(__file__).resolve().parents[4]
out = Path(os.environ['AA_TEST_OUTPUT'])
out.mkdir(parents=True, exist_ok=True)
session_path = Path(os.environ.get('AA_RETIREMENT_TASK_SOURCE', str(root/'app/src/main/cpp/jni_session.cpp')))
jni_path = Path(os.environ.get('AA_RETIREMENT_REGISTRY_SOURCE', str(root/'app/src/main/cpp/aasdk_jni.cpp')))

def extract(text, needle):
    start = text.index(needle)
    brace = text.index('{', start)
    depth, end = 1, brace+1
    while depth:
        if text[end] == '{': depth += 1
        elif text[end] == '}': depth -= 1
        end += 1
    return text[start:end]

body = extract(session_path.read_text(), 'void JniSession::retireSecondaryVideo(')
dispatch = extract(jni_path.read_text(), 'Java_com_openautolink_app_transport_aasdk_AasdkNative_nativeRetireSecondaryVideo(')
manifest = {
    'task_source': str(session_path), 'registry_source': str(jni_path),
    'task_source_sha256': hashlib.sha256(session_path.read_bytes()).hexdigest(),
    'registry_source_sha256': hashlib.sha256(jni_path.read_bytes()).hexdigest(),
    'task_body_sha256': hashlib.sha256(body.encode()).hexdigest(),
    'registry_body_sha256': hashlib.sha256(dispatch.encode()).hexdigest(),
    'combined_body_sha256': hashlib.sha256((body+dispatch).encode()).hexdigest(),
    'test_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
}
(out/'extraction.json').write_text(json.dumps(manifest, indent=2))
(out/'task-body.cpp').write_text(body)
(out/'registry-body.cpp').write_text(dispatch)
print(json.dumps(manifest, indent=2), flush=True)
source = r'''#include <atomic>
#include <memory>
#include <mutex>
#include <functional>
#include <vector>
#include <unordered_map>
#include <cassert>
#include <cstdint>
#include <iostream>
#include <string>
#ifdef REAL_ASIO
#include <boost/asio.hpp>
#endif
using jint=int;using jlong=long long;using jclass=void*;struct JNIEnv{};
namespace openautolink::jni {
struct Handler{int retirements=0;void retire(){++retirements;}};
struct Strand{
 std::function<void()> beforePost;
 size_t pending=0;bool stopped=false;
#ifdef REAL_ASIO
 boost::asio::io_service io;
 boost::asio::io_service::strand queue{io};
#else
 std::vector<std::function<void()>> work;
#endif
 template<class F>void post(F f){
  if(beforePost){auto hook=std::move(beforePost);beforePost=nullptr;hook();}
  ++pending;
#ifdef REAL_ASIO
  queue.post([this,f=std::move(f)]{--pending;f();});
#else
  work.push_back([this,f=std::move(f)]{--pending;f();});
#endif
 }
 void stop(){stopped=true;
#ifdef REAL_ASIO
  io.stop();
#endif
 }
 void drain(){
#ifdef REAL_ASIO
  io.reset();io.poll();
#else
  auto tasks=std::move(work);work.clear();for(auto&f:tasks)f();
#endif
 }
};
struct JniSession:std::enable_shared_from_this<JniSession>{
 std::atomic<int64_t> nativeGeneration_{0};std::atomic<bool> stopped_{false};
 std::shared_ptr<Strand> strand_=std::make_shared<Strand>();
 struct State{std::shared_ptr<Handler> handler=std::make_shared<Handler>();};
 std::unordered_map<int,State> optionalVideos_{{3,{}},{19,{}}};
 void retireSecondaryVideo(int,int64_t,std::function<bool()>);
};
BODY
}
static std::mutex gSessionMutex;
static bool gSessionAdmissionClosed=false;
static std::atomic<jlong> gSessionGeneration{0};
static std::shared_ptr<openautolink::jni::JniSession> gSession;
void DISPATCH
int main(int argc,char**argv){using openautolink::jni::JniSession;
 assert(argc==2);std::string mode=argv[1];
 auto call=[](int channel,jlong generation){Java_com_openautolink_app_transport_aasdk_AasdkNative_nativeRetireSecondaryVideo(nullptr,nullptr,channel,generation);};
 if(mode=="undrained" || mode=="stop-before-post"){
  // Retain every stopped queue independently: task disposal cannot cause expiry.
  std::vector<std::shared_ptr<openautolink::jni::Strand>> queues;
  std::vector<std::shared_ptr<openautolink::jni::Handler>> handlers;
  for(int generation=1;generation<=99;++generation){
   auto old=std::make_shared<JniSession>();old->nativeGeneration_=generation;
   gSession=old;gSessionGeneration=generation;
   auto queue=old->strand_;auto handler=old->optionalVideos_[19].handler;
   std::weak_ptr<JniSession> weak=old;
   if(mode=="stop-before-post")queue->beforePost=[&]{old->stopped_=true;queue->stop();gSession.reset();};
   call(19,generation);assert(queue->pending==1);
   old->stopped_=true;queue->stop();
   auto next=std::make_shared<JniSession>();next->nativeGeneration_=generation+1;
   gSession=next;gSessionGeneration=generation+1;
   old.reset();
   assert(queue->stopped && queue->pending==1);
   assert(weak.expired() && "stopped undrained queue retained old session");
   assert(handler->retirements==0);
   // Successor remains usable without draining any old queue.
   call(3,generation+1);next->strand_->drain();
   assert(next->optionalVideos_[3].handler->retirements==1);
   assert(next->optionalVideos_[19].handler->retirements==0);
   queues.push_back(queue);handlers.push_back(handler);
  }
  for(auto&q:queues)assert(q->stopped && q->pending==1);
  std::cout<<"PASS 99 expired owners; 99 stopped queues still undrained\n";
  // Only after every expiry assertion: resumed dead-owner work cannot retire/ACK.
  for(auto&q:queues){q->drain();assert(q->pending==0);}
  for(auto&h:handlers)assert(h->retirements==0);
 }else{
  assert(mode=="guards");
  for(int generation=1;generation<100;++generation){
   auto old=std::make_shared<JniSession>();old->nativeGeneration_=generation;
   gSession=old;gSessionGeneration=generation;
   call(19,0);call(19,-1);call(19,generation-1);assert(old->strand_->pending==0);
   gSessionAdmissionClosed=true;call(19,generation);assert(old->strand_->pending==0);gSessionAdmissionClosed=false;
   old->stopped_=true;call(19,generation);assert(old->strand_->pending==0);old->stopped_=false;
   call(19,generation);assert(old->strand_->pending==1);old->strand_->drain();
   assert(old->optionalVideos_[19].handler->retirements==1);
   assert(old->optionalVideos_[3].handler->retirements==0);
   call(99,generation);old->strand_->drain();
   old->optionalVideos_[3].handler.reset();call(3,generation);old->strand_->drain();
   auto check=[&]{old->strand_->drain();assert(old->optionalVideos_[19].handler->retirements==1);};
   call(19,generation);old->nativeGeneration_=generation+1;check();old->nativeGeneration_=generation;
   call(19,generation);gSessionGeneration=generation+1;check();gSessionGeneration=generation;
   call(19,generation);gSessionAdmissionClosed=true;check();gSessionAdmissionClosed=false;
   call(19,generation);auto next=std::make_shared<JniSession>();next->nativeGeneration_=generation;
   gSession=next;check();assert(next->optionalVideos_[19].handler->retirements==0);gSession=old;
   call(19,generation);old->stopped_=true;check();
  }
  gSession.reset();call(19,gSessionGeneration.load());
 }
 gSession.reset();std::cout<<"PASS "<<mode<<"\n";
}
'''.replace('BODY', body).replace('DISPATCH', dispatch)
(out/'retirement.cpp').write_text(source)
results = []
for queue in ['fake', 'asio']:
    binary = out/('retirement-'+queue)
    command = [os.environ.get('CXX', 'g++'), '-std=c++20', '-pthread', '-DBOOST_ERROR_CODE_HEADER_ONLY']
    if queue == 'asio':
        command += ['-DREAL_ASIO']
        if os.environ.get('BOOST_INCLUDE'): command += ['-I'+os.environ['BOOST_INCLUDE']]
    command += [str(out/'retirement.cpp'), '-o', str(binary)]
    print('COMPILE', command, flush=True)
    subprocess.run(command, check=True, timeout=120)
    for mode in ['undrained', 'stop-before-post', 'guards']:
        result = subprocess.run([str(binary), mode], capture_output=True, text=True, timeout=30)
        print(queue, mode, 'exit', result.returncode, result.stdout, result.stderr, flush=True)
        results.append({'queue': queue, 'case': mode, 'exit': result.returncode,
                        'stdout': result.stdout, 'stderr': result.stderr})
(out/'results.json').write_text(json.dumps(results, indent=2))
if any(r['exit'] != 0 for r in results): raise SystemExit(1)
print('PASS extracted production-body packet: fake + real Asio; not whole JNI/device proof', flush=True)
