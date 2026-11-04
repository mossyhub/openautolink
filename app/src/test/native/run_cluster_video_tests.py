#!/usr/bin/env python3
"""Host production-handler and real-protobuf SDR tests. Not Android/device coverage.
Requires CXX, PROTOC, PROTOBUF_ROOT, BOOST_INCLUDE and AASDK_SOURCE; user-extracted
Ubuntu protobuf packages suffice. All builds must be wrapped in shared host lock.
"""
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
root=Path(__file__).resolve().parents[4]
cpp=root/'app/src/main/cpp'
handler_cpp=Path(os.environ.get('AA_HANDLER_CPP_DIR',str(cpp)))
# Optional source ablation keeps finalized regression tests byte-identical in RED.
dispatch_modes=os.environ.get('AA_DISPATCH_MODES','options,malformed,short-data,oversized,state-failure,frame-failure,retired,stop-queued,raw-empty,raw-one').split(',')
out=Path(os.environ['AA_TEST_OUTPUT']);out.mkdir(parents=True,exist_ok=True)
aasdk=Path(os.environ['AASDK_SOURCE'])
proto=aasdk/'protobuf'
protobuf=Path(os.environ['PROTOBUF_ROOT'])
source=(cpp/'jni_session.cpp').read_text()
header=(cpp/'jni_session.h').read_text()
begin=source.index('void JniSession::buildServiceDiscoveryResponse(')
end=source.index('\n// ============================================================================',begin)
body=source[begin:end]
mainBegin=source.index('void JniSession::onChannelOpenRequest(')
mainEnd=source.index('void JniSession::onVideoFocusRequest(',mainBegin)
mainBody=source[mainBegin:mainEnd]
print('main handler bodies SHA256',hashlib.sha256(mainBody.encode()).hexdigest(),flush=True)
template=(root/'app/src/test/native/cluster_main_harness.template').read_text()
(out/'cluster_main_harness.inc').write_text(template.replace('\nMAIN_METHODS\n','\n'+mainBody+'\n'))
begin=header.index('    struct SdrConfig {')
end=header.index('    SdrConfig sdrConfig_;',begin)
config=header[begin:end]
print('SDR body SHA256',hashlib.sha256(body.encode()).hexdigest(),flush=True)
stub='''
#include "cluster_video_handler.h"
#include "gal_video_policy.h"
#include "openautolink/ev_connector_mapping.h"
#include <atomic>
#include <cmath>
#include <set>
#include <cassert>
#include <iostream>
#define LOGI(...) ((void)0)
namespace openautolink::jni {
using Response=aap_protobuf::service::control::message::ServiceDiscoveryResponse;
struct JniSession {
CONFIG
SdrConfig sdrConfig_;
std::vector<std::pair<int,int>> advertisedVideoGeometry_;
std::atomic<int> advertisedVideoConfigCount_{0};
void buildServiceDiscoveryResponse(Response&);
};
BODY
CASES
}
int main(){return openautolink::jni::main();}
'''.replace('CONFIG',config).replace('BODY',body).replace('CASES',(root/'app/src/test/native/cluster_video_sdr_cases.inc').read_text())
(out/'sdr_test.cpp').write_text(stub)
# Only dependency closure of used real messages, generated with the host runtime version.
entries=['aap_protobuf/service/inputsource/InputMessageId.proto',
'aap_protobuf/service/media/sink/message/KeyBindingRequest.proto','aap_protobuf/service/media/sink/message/KeyBindingResponse.proto',
'aap_protobuf/service/control/message/ServiceDiscoveryResponse.proto',
'aap_protobuf/service/control/message/ChannelOpenRequest.proto','aap_protobuf/service/control/message/ChannelOpenResponse.proto',
'aap_protobuf/service/media/shared/message/Setup.proto','aap_protobuf/service/media/shared/message/Config.proto',
'aap_protobuf/service/media/shared/message/Start.proto','aap_protobuf/service/media/shared/message/Stop.proto',
'aap_protobuf/service/media/source/message/Ack.proto',
'aap_protobuf/service/media/video/message/VideoFocusNotification.proto','aap_protobuf/service/media/video/message/VideoFocusRequestNotification.proto',
'aap_protobuf/service/media/sink/MediaMessageId.proto','aap_protobuf/service/control/ControlMessageType.proto']
seen=set()
def visit(p):
    if p in seen:return
    seen.add(p)
    for child in re.findall(r'import\s+"([^"]+)"',(proto/p).read_text()):visit(child)
for p in entries:visit(p)
# Include every generated dependency to avoid relying on transitive header imports.
includes=''.join('#include <'+p[:-6]+'.pb.h>\n' for p in sorted(seen))
(out/'sdr_test.cpp').write_text(includes+stub)
cache=hashlib.sha256(''.join((proto/p).read_text() for p in sorted(seen)).encode()).hexdigest()
if not (out/'proto.sha256').exists() or (out/'proto.sha256').read_text()!=cache:
    subprocess.run([os.environ['PROTOC'],'-I'+str(proto),'--cpp_out='+str(out),*sorted(seen)],check=True)
    objects=[]
    for p in sorted(seen):
        src=out/(p[:-6]+'.pb.cc');obj=src.with_suffix('.o');objects.append(str(obj))
        subprocess.run(['g++','-std=c++17','-O0','-I'+str(out),'-I'+str(protobuf/'usr/include'),'-c',str(src),'-o',str(obj)],check=True)
    subprocess.run(['ar','rcs',str(out/'libtestproto.a'),*objects],check=True)
    (out/'proto.sha256').write_text(cache)
flags=['-std=c++20','-O0','-g','-pthread','-DBOOST_ERROR_CODE_HEADER_ONLY',
'-I'+str(handler_cpp),'-I'+str(cpp),'-I'+str(root/'native/include'),'-I'+str(aasdk/'include'),'-I'+str(out),
'-I'+str(protobuf/'usr/include'),'-I'+os.environ['BOOST_INCLUDE']]
for name,src in [('input',root/'app/src/test/native/secondary_input_test.cpp'),('handler',root/'app/src/test/native/cluster_video_handler_test.cpp'),('sdr',out/'sdr_test.cpp'),
                 ('dispatch',root/'app/src/test/native/cluster_video_dispatch_test.cpp'),
                 ('secondary',root/'app/src/test/native/secondary_video_dispatch_test.cpp')]:
    support=[str(aasdk/'src'/p) for p in ['Common/Data.cpp','IO/IOContextWrapper.cpp','Error/Error.cpp']]
    if name in ('input','dispatch','secondary'):
        support += [str(aasdk/'src'/p) for p in ['Channel/Channel.cpp',
            'Channel/MediaSink/Video/VideoMediaSinkService.cpp','Messenger/Message.cpp',
            'Messenger/MessageId.cpp','Messenger/Timestamp.cpp','Messenger/ChannelReceiveMessageQueue.cpp',
            'Common/ModernLogger.cpp']]
    command=['g++',*flags,str(src),str(handler_cpp/'cluster_video_handler.cpp'),*support,str(out/'libtestproto.a'),
        '-L'+str(protobuf/'usr/lib/x86_64-linux-gnu'),'-Wl,-rpath,'+str(protobuf/'usr/lib/x86_64-linux-gnu'),'-lprotobuf','-o',str(out/name)]
    print('BUILD',name,flush=True);subprocess.run(command,check=True)
    if name=='dispatch':
        for mode in dispatch_modes:
            subprocess.run([str(out/name),mode],check=True)
    else:
        subprocess.run([str(out/name)],check=True)
print('PASS host native packet',flush=True)
