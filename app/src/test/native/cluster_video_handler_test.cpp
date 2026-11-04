// Real production handler + protobuf; only the transport boundary is fake.
#include "cluster_video_handler.h"
#include <cassert>
#include <iostream>
using namespace openautolink::jni;
namespace msg = aap_protobuf::service::media::shared::message;
struct Wire : aasdk::channel::mediasink::video::IVideoMediaSinkService {
    int receives=0, focus=0, opens=0; std::vector<int> acks; msg::Config config;
    aasdk::messenger::ChannelId getId() const override { return aasdk::messenger::ChannelId::MEDIA_SINK_VIDEO; }
    void send(aasdk::messenger::Message::Pointer, aasdk::channel::SendPromise::Pointer) override {}
    void receive(aasdk::channel::mediasink::video::IVideoMediaSinkServiceEventHandler::Pointer) override { ++receives; }
    void sendChannelOpenResponse(const aap_protobuf::service::control::message::ChannelOpenResponse&, aasdk::channel::SendPromise::Pointer) override { ++opens; }
    void sendChannelSetupResponse(const msg::Config& c, aasdk::channel::SendPromise::Pointer) override { config=c; }
    void sendMediaAckIndication(const aap_protobuf::service::media::source::message::Ack& a, aasdk::channel::SendPromise::Pointer) override { acks.push_back(a.session_id()); }
    void sendVideoFocusIndication(const aap_protobuf::service::media::video::message::VideoFocusNotification&, aasdk::channel::SendPromise::Pointer) override { ++focus; }
};
#include "cluster_main_harness.inc"
int main() {
    boost::asio::io_service io; boost::asio::io_service::strand strand(io);
    auto mainWire=std::make_shared<Wire>(), clusterWire=std::make_shared<Wire>();
    struct Seen { int state=0, frames=0, flags=0; uint64_t epoch=0; bool active=false; } main, cluster;
    auto make=[&](auto wire, Seen& seen, bool activeIds) {
        return std::make_shared<ClusterVideoHandler>(strand,wire,activeIds,2,
            [&](uint64_t e,int,int,int,bool active){++seen.state;seen.epoch=e;seen.active=active;return true;},
            [&](const uint8_t*,size_t,uint64_t,uint64_t e,int flags){assert(e==seen.epoch);++seen.frames;seen.flags=flags;return true;});
    };
    auto a=make(mainWire,main,true), b=make(clusterWire,cluster,true);
    auto productionMainWire=std::make_shared<Wire>();
    JniSession productionMain(strand,productionMainWire);
    msg::Setup mainSetup;mainSetup.set_type(msg::MEDIA_CODEC_VIDEO_H265);
    productionMain.onMediaChannelSetupRequest(mainSetup);
    msg::Start mainStart;mainStart.set_configuration_index(0);mainStart.set_session_id(501);
    productionMain.onMediaChannelStartIndication(mainStart);
    assert(productionMain.env.codec==7 && productionMain.env.width==1920);
    const auto mainEpoch=productionMain.selectedVideoEpoch_.load();
    msg::Setup setup; setup.set_type(msg::MEDIA_CODEC_VIDEO_H264_BP);
    msg::Start start; start.set_configuration_index(0); start.set_session_id(41);
    a->start(); b->start();
    a->onMediaChannelSetupRequest(setup); a->onMediaChannelStartIndication(start);
    start.set_session_id(82); b->onMediaChannelSetupRequest(setup); b->onMediaChannelStartIndication(start);
    assert(main.active && cluster.active && clusterWire->config.configuration_indices_size()==1);
    assert(clusterWire->config.configuration_indices(0)==0 && clusterWire->config.max_unacked()==2);
    uint8_t nals[]={0,0,0,1,0x67,0,0,1,0x65}; aasdk::common::DataConstBuffer data(nals,sizeof nals);
    a->onMediaWithTimestampIndication(1,data); b->onMediaWithTimestampIndication(2,data);
    assert(mainWire->acks.back()==41 && clusterWire->acks.back()==82 && main.frames==1 && cluster.frames==1 && cluster.flags==3);
    uint8_t hevc[]={0,0,0,1,0x40,0,0,1,0x26};aasdk::common::DataConstBuffer mainData(hevc,sizeof hevc);
    productionMain.onMediaWithTimestampIndication(9,mainData);
    assert(productionMainWire->acks.back()==501 && productionMain.env.frames==1 && productionMain.env.flags==3);
    assert(productionMain.negotiatedCodecType_==7 && productionMain.selectedVideoEpoch_==mainEpoch);
    auto oldEpoch=cluster.epoch; int focus=clusterWire->focus;
    start.set_configuration_index(1); b->onMediaChannelStartIndication(start);
    assert(!cluster.active && cluster.epoch>oldEpoch && clusterWire->focus==focus);
    b->onMediaWithTimestampIndication(3,data); assert(cluster.frames==1);
    start.set_configuration_index(0); b->onMediaChannelStartIndication(start); assert(cluster.active);
    oldEpoch=cluster.epoch; b->onMediaChannelSetupRequest(setup);
    assert(!cluster.active && cluster.epoch>oldEpoch); b->onMediaWithTimestampIndication(4,data);assert(cluster.frames==1);
    b->onMediaChannelStartIndication(start); b->onMediaChannelStopIndication(msg::Stop{});
    assert(!cluster.active && main.active); a->onMediaWithTimestampIndication(5,data);assert(main.frames==2);
    productionMain.onMediaWithTimestampIndication(10,mainData);
    assert(productionMainWire->acks.back()==501 && productionMain.env.frames==2 && productionMain.aborts==0);
    assert(productionMain.activeVideoSessionId_==501 && productionMain.selectedVideoEpoch_==mainEpoch);
    setup.set_type(msg::MEDIA_CODEC_VIDEO_H265); b->onMediaChannelSetupRequest(setup);
    assert(clusterWire->config.status()==msg::Config::STATUS_WAIT && clusterWire->config.configuration_indices_size()==0);
    b->onMediaChannelStartIndication(start); assert(!cluster.active);
    auto legacy=make(std::make_shared<Wire>(),cluster,false);
    auto legacyWire=std::make_shared<Wire>(); legacy=make(legacyWire,cluster,false);
    setup.set_type(msg::MEDIA_CODEC_VIDEO_H264_BP);legacy->onMediaChannelSetupRequest(setup);legacy->onMediaChannelStartIndication(start);legacy->onMediaWithTimestampIndication(0,data);assert(legacyWire->acks.back()==0);
    b->onChannelError(aasdk::error::Error()); int received=clusterWire->receives;
    b->onMediaWithTimestampIndication(6,data);assert(clusterWire->receives==received && main.active);
    productionMain.onMediaWithTimestampIndication(11,mainData);assert(productionMain.env.frames==3 && productionMain.aborts==0);
    auto overflowWire=std::make_shared<Wire>(); Seen overflow;
    auto overflowHandler=make(overflowWire,overflow,true);
    overflowHandler->onMediaChannelSetupRequest(setup);overflowHandler->onMediaChannelStartIndication(start);
    std::vector<uint8_t> huge(ClusterVideoHandler::MAX_FRAME_BYTES+1);
    overflowHandler->onMediaWithTimestampIndication(0,aasdk::common::DataConstBuffer(huge));
    assert(!overflow.active && overflow.frames==0 && overflowWire->acks.back()==82);
    auto failedWire=std::make_shared<Wire>();
    auto failed=std::make_shared<ClusterVideoHandler>(strand,failedWire,true,2,
        [](uint64_t,int,int,int,bool){return false;},
        [](const uint8_t*,size_t,uint64_t,uint64_t,int){return false;});
    failed->onMediaChannelSetupRequest(setup);assert(failedWire->focus==0 && failedWire->receives==0);
    std::cout << "PASS production per-channel setup/start/replace/invalid/stop/ACK/codec/epoch isolation\n";
}
