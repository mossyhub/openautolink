// Real pinned dispatcher, multiplexed channel3+19, protobuf ACKs, independent drain.
#include "cluster_video_handler.h"
#include <aasdk/Channel/MediaSink/Video/VideoMediaSinkService.hpp>
#include <aasdk/Messenger/ChannelReceiveMessageQueue.hpp>
#include <aasdk/Messenger/MessageId.hpp>
#include <aap_protobuf/service/media/sink/MediaMessageId.pb.h>
#include <cassert>
#include <deque>
#include <map>
#include <iostream>
using namespace openautolink::jni;
namespace wire=aasdk::messenger;
namespace msg=aap_protobuf::service::media::shared::message;
namespace ids=aap_protobuf::service::media::sink;
struct Multiplex : wire::IMessenger {
    wire::ChannelReceiveMessageQueue messages;
    std::map<int,std::deque<wire::ReceivePromise::Pointer>> pending;
    std::map<int,std::vector<int>> acks;
    void flush(int id){auto ch=static_cast<wire::ChannelId>(id);auto& q=pending[id];while(!q.empty()&&!messages.empty(ch)){
        auto p=q.front();q.pop_front();p->resolve(messages.pop(ch));}}
    void enqueueReceive(wire::ChannelId ch,wire::ReceivePromise::Pointer p)override{
        auto& q=pending[static_cast<int>(ch)];q.push_back(p);assert(q.size()==1);flush(static_cast<int>(ch));}
    void enqueueSend(wire::Message::Pointer m,wire::SendPromise::Pointer p)override{
        const auto& bytes=m->getPayload();assert(bytes.size()>=2);
        if(wire::MessageId(bytes).getId()==ids::MEDIA_MESSAGE_ACK){
            aap_protobuf::service::media::source::message::Ack ack;
            assert(ack.ParseFromArray(bytes.data()+2,bytes.size()-2));assert(ack.ack()==1);
            acks[static_cast<int>(m->getChannelId())].push_back(ack.session_id());}
        p->resolve();}
    void push(int ch,int id,const aasdk::common::Data& data={}){
        auto m=std::make_shared<wire::Message>(static_cast<wire::ChannelId>(ch),wire::EncryptionType::ENCRYPTED,wire::MessageType::SPECIFIC);
        m->insertPayload(wire::MessageId(id).getData());m->insertPayload(data);messages.push(m);flush(ch);}
    void raw(int ch,const aasdk::common::Data& data){
        auto m=std::make_shared<wire::Message>(static_cast<wire::ChannelId>(ch),wire::EncryptionType::ENCRYPTED,wire::MessageType::SPECIFIC);
        m->insertPayload(data);messages.push(m);flush(ch);}
    template<class T>void proto(int ch,int id,const T& p){auto bytes=p.SerializeAsString();push(ch,id,{bytes.begin(),bytes.end()});}
    void stop()override{}
};
static void pump(boost::asio::io_service& io){io.restart();while(io.poll()){} }
int main(){
    boost::asio::io_service io;boost::asio::io_service::strand strand(io);
    auto mux=std::make_shared<Multiplex>();
    std::map<int,int> frames,sessions;std::map<int,uint64_t> epochs;
    std::map<int,std::shared_ptr<ClusterVideoHandler>> handlers;
    for(int id:{3,19}){
        auto channel=std::make_shared<aasdk::channel::mediasink::video::VideoMediaSinkService>(strand,
            std::make_shared<ClusterLengthCheckedMessenger>(strand,mux),static_cast<wire::ChannelId>(id));
        handlers[id]=std::make_shared<ClusterVideoHandler>(strand,channel,true,2,
            [&,id](uint64_t epoch,int,int,int session,bool active){epochs[id]=epoch;if(active)sessions[id]=session;return true;},
            [&,id](const uint8_t*,size_t,uint64_t,uint64_t epoch,int){assert(epoch==epochs[id]);++frames[id];return true;});
        handlers[id]->start();
    }
    pump(io);
    msg::Setup setup;setup.set_type(msg::MEDIA_CODEC_VIDEO_H264_BP);
    for(int id:{3,19}){mux->proto(id,ids::MEDIA_MESSAGE_SETUP,setup);pump(io);
        msg::Start start;start.set_configuration_index(0);start.set_session_id(id==3?31:191);
        mux->proto(id,ids::MEDIA_MESSAGE_START,start);pump(io);}
    for(int i=0;i<100;++i){for(int id:{19,3}){mux->push(id,ids::MEDIA_MESSAGE_CODEC_CONFIG,{0,0,1,0x65});pump(io);}}
    assert(frames[3]==100 && frames[19]==100);
    assert(mux->acks[3].size()==100 && mux->acks[19].size()==100);
    assert(mux->acks[3].back()==31 && mux->acks[19].back()==191);
    handlers[3]->retire();const auto liveEpoch=epochs[19];
    // Retired Cluster drains options and raw short packets, never touches Auxiliary.
    for(int i=0;i<100;++i){mux->push(3,ids::MEDIA_MESSAGE_MEDIA_OPTIONS);pump(io);
        mux->push(3,ids::MEDIA_MESSAGE_CODEC_CONFIG,{1});pump(io);
        mux->push(19,ids::MEDIA_MESSAGE_CODEC_CONFIG,{0,0,1,0x65});pump(io);}
    mux->raw(3,{});pump(io);mux->raw(3,{0xff});pump(io);
    assert(frames[3]==100 && frames[19]==200 && epochs[19]==liveEpoch);
    assert(mux->acks[19].back()==191 && mux->pending[3].size()==1 && mux->pending[19].size()==1);
    for(auto& [id,handler]:handlers)handler->stop();pump(io);
    std::cout<<"PASS real multiplex dispatcher channel3+19 interleaved frames/ACK/session/epoch/retire/options/short-drain isolation\n";
}
