// Real pinned VideoMediaSinkService dispatcher and receive message queue.
// Only IMessenger transport IO is substituted. No direct handler dispatch.
#include "cluster_video_handler.h"
#include <aasdk/Channel/MediaSink/Video/VideoMediaSinkService.hpp>
#include <aasdk/Messenger/ChannelReceiveMessageQueue.hpp>
#include <aasdk/Messenger/MessageId.hpp>
#include <aap_protobuf/service/media/sink/MediaMessageId.pb.h>
#include <cassert>
#include <deque>
#include <iostream>
#include <aasdk/Common/ModernLogger.hpp>
using namespace openautolink::jni;
namespace msg = aap_protobuf::service::media::shared::message;
namespace ids = aap_protobuf::service::media::sink;
namespace wire = aasdk::messenger;
struct Transport : wire::IMessenger {
    wire::ChannelReceiveMessageQueue messages;
    std::deque<wire::ReceivePromise::Pointer> pending;
    size_t maxPending=0, receives=0, delivered=0;
    bool stopped=false;
    void enqueueReceive(wire::ChannelId channel, wire::ReceivePromise::Pointer promise) override {
        assert(channel==wire::ChannelId::MEDIA_SINK_VIDEO);
        ++receives;
        if(stopped){promise->reject(aasdk::error::Error(aasdk::error::ErrorCode::OPERATION_ABORTED));return;}
        pending.push_back(promise);maxPending=std::max(maxPending,pending.size());flush();
    }
    void enqueueSend(wire::Message::Pointer, wire::SendPromise::Pointer promise) override {promise->resolve();}
    void flush(){while(!pending.empty()&&!messages.empty(wire::ChannelId::MEDIA_SINK_VIDEO)){
        auto p=pending.front();pending.pop_front();++delivered;p->resolve(messages.pop(wire::ChannelId::MEDIA_SINK_VIDEO));
    }}
    void push(int id,const aasdk::common::Data& payload={}) {
        auto message=std::make_shared<wire::Message>(wire::ChannelId::MEDIA_SINK_VIDEO,wire::EncryptionType::ENCRYPTED,wire::MessageType::SPECIFIC);
        message->insertPayload(wire::MessageId(id).getData());message->insertPayload(payload);messages.push(message);flush();
    }
    // Completed raw payload, deliberately WITHOUT the helper's two-byte ID.
    void raw(const aasdk::common::Data& payload) {
        auto message=std::make_shared<wire::Message>(wire::ChannelId::MEDIA_SINK_VIDEO,wire::EncryptionType::ENCRYPTED,wire::MessageType::SPECIFIC);
        message->insertPayload(payload);messages.push(message);flush();
    }
    template<class T> void proto(int id,const T& value){std::string bytes=value.SerializeAsString();push(id,{bytes.begin(),bytes.end()});}
    void stop() override {stopped=true;while(!pending.empty()){auto p=pending.front();pending.pop_front();p->reject(aasdk::error::Error(aasdk::error::ErrorCode::OPERATION_ABORTED));}}
};
// Actual primary JniSession bodies share only IO scheduling with optional dispatch.
// This wire records primary ACKs; it never consumes optional traffic.
struct Wire : aasdk::channel::mediasink::video::IVideoMediaSinkService {
    std::vector<int> acks;
    wire::ChannelId getId() const override{return wire::ChannelId::MEDIA_SINK;}
    void send(wire::Message::Pointer,aasdk::channel::SendPromise::Pointer) override{}
    void receive(aasdk::channel::mediasink::video::IVideoMediaSinkServiceEventHandler::Pointer) override{}
    void sendChannelOpenResponse(const aap_protobuf::service::control::message::ChannelOpenResponse&,aasdk::channel::SendPromise::Pointer) override{}
    void sendChannelSetupResponse(const msg::Config&,aasdk::channel::SendPromise::Pointer) override{}
    void sendMediaAckIndication(const aap_protobuf::service::media::source::message::Ack& a,aasdk::channel::SendPromise::Pointer) override{acks.push_back(a.session_id());}
    void sendVideoFocusIndication(const aap_protobuf::service::media::video::message::VideoFocusNotification&,aasdk::channel::SendPromise::Pointer) override{}
};
#include "cluster_main_harness.inc"
static void pump(boost::asio::io_service& io){io.restart();while(io.poll()){} }
int main(int argc,char** argv){
    aasdk::common::ModernLogger::getInstance().setLevel(aasdk::common::LogLevel::FATAL);
    const std::string mode=argc>1?argv[1]:"options";
    boost::asio::io_service io;boost::asio::io_service::strand strand(io);
    auto transport=std::make_shared<Transport>();
    auto checked=std::make_shared<ClusterLengthCheckedMessenger>(strand,transport);
    auto channel=std::make_shared<aasdk::channel::mediasink::video::VideoMediaSinkService>(strand,checked,wire::ChannelId::MEDIA_SINK_VIDEO);
    int states=0,frames=0;bool active=false;
    auto handler=std::make_shared<ClusterVideoHandler>(strand,channel,true,2,
        [&](uint64_t,int,int,int,bool value){++states;active=value;return mode!="state-failure";},
        [&](const uint8_t*,size_t,uint64_t,uint64_t,int){++frames;return mode!="frame-failure";});
    auto primaryWire=std::make_shared<Wire>();JniSession primary(strand,primaryWire);
    msg::Setup primarySetup;primarySetup.set_type(msg::MEDIA_CODEC_VIDEO_H265);primary.onMediaChannelSetupRequest(primarySetup);
    msg::Start primaryStart;primaryStart.set_session_id(501);primaryStart.set_configuration_index(0);primary.onMediaChannelStartIndication(primaryStart);
    const auto primaryEpoch=primary.selectedVideoEpoch_.load();
    const uint8_t mainNals[]={0,0,1,0x26};int mainFrames=0;
    auto mainPulse=[&]{primary.onMediaWithTimestampIndication(9,aasdk::common::DataConstBuffer(mainNals,sizeof mainNals));++mainFrames;
        assert(primary.env.frames==mainFrames && primaryWire->acks.back()==501 && primary.aborts==0);
        assert(primary.selectedVideoEpoch_==primaryEpoch && primary.negotiatedCodecType_==7);};
    handler->start();
    if(mode=="stop-queued"){
        handler->onChannelError(aasdk::error::Error(aasdk::error::ErrorCode::OPERATION_ABORTED));
        pump(io);assert(transport->receives==0 && states==1 && frames==0);mainPulse();
        std::cout<<"PASS real dispatcher stop-queued receives=0 primary_frames="<<mainFrames<<"\n";return 0;
    }
    pump(io);assert(transport->pending.size()==1);
    if(mode=="options"){
        for(int i=0;i<512;++i){transport->push(ids::MEDIA_MESSAGE_MEDIA_OPTIONS);pump(io);
            assert(transport->pending.size()==1 && "options dispatcher must own exactly one rearm");}
        assert(transport->maxPending==1);
    }else{
        msg::Setup setup;setup.set_type(msg::MEDIA_CODEC_VIDEO_H264_BP);transport->proto(ids::MEDIA_MESSAGE_SETUP,setup);pump(io);
        if(mode!="state-failure"){
            msg::Start start;start.set_session_id(82);start.set_configuration_index(0);transport->proto(ids::MEDIA_MESSAGE_START,start);pump(io);assert(active);
            if(mode=="raw-empty")transport->raw({});
            else if(mode=="raw-one")transport->raw({0xff});
            else if(mode=="malformed")transport->push(ids::MEDIA_MESSAGE_START,{0xff});
            else if(mode=="short-data")transport->push(ids::MEDIA_MESSAGE_DATA,{0xff});
            else if(mode=="oversized")transport->push(ids::MEDIA_MESSAGE_CODEC_CONFIG,aasdk::common::Data(ClusterVideoHandler::MAX_FRAME_BYTES+1));
            else if(mode=="frame-failure")transport->push(ids::MEDIA_MESSAGE_CODEC_CONFIG,{0,0,1,0x65});
            else {assert(mode=="retired");handler->retire();}
            pump(io);
        }
        assert(!active);const int revokedStates=states,revokedFrames=frames;
        // Queue before draining to exercise backlog and asynchronous rearm without recursion.
        for(int i=0;i<2048;++i){
            if(i%64==0)strand.post(mainPulse);
            if(mode=="raw-empty" && i%5==0){transport->raw({});continue;}
            if(mode=="raw-one" && i%5==0){transport->raw({0xff});continue;}
            switch(i%7){
            case 0:transport->push(ids::MEDIA_MESSAGE_START,{0xff});break;
            case 1:transport->push(ids::MEDIA_MESSAGE_DATA,{0xff});break;
            case 2:transport->push(ids::MEDIA_MESSAGE_CODEC_CONFIG,{0,0,1,0x65});break;
            case 3:transport->push(ids::MEDIA_MESSAGE_MEDIA_OPTIONS);break;
            case 4:transport->push(0x7fff);break;
            case 5:transport->proto(ids::MEDIA_MESSAGE_SETUP,setup);break;
            default:transport->push(ids::MEDIA_MESSAGE_STOP);break;
            }
        }
        pump(io);
        assert(transport->messages.empty(wire::ChannelId::MEDIA_SINK_VIDEO) && "retired optional traffic must be drained");
        assert(transport->pending.size()==1 && transport->maxPending==1);
        assert(states==revokedStates && frames==revokedFrames && "revocation forbids further decoder callbacks");
    }
    transport->stop();pump(io);const auto count=transport->receives;
    pump(io);assert(transport->pending.empty() && transport->receives==count);
    assert(transport->messages.empty(wire::ChannelId::MEDIA_SINK_VIDEO));
    mainPulse();
    std::cout<<"PASS real dispatcher "<<mode<<" primary_frames="<<mainFrames<<" delivered="<<transport->delivered<<" max_pending="<<transport->maxPending<<" stopped_receives="<<count<<"\n";
}
