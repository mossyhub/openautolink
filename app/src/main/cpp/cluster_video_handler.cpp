#include "cluster_video_handler.h"
#include <algorithm>
#include <set>
namespace openautolink::jni {
namespace media = aap_protobuf::service::media::shared::message;
namespace video = aap_protobuf::service::media::video::message;

bool ClusterVideoOffer::valid() const {
    if(cropLeft<0 || cropLeft>=640 || cropTop<0 || cropTop>=360 ||
        cropRight<=640 || cropRight>1280 || cropBottom<=360 || cropBottom>720)return false;
    const int w=cropRight-cropLeft,h=cropBottom-cropTop;
    auto insets=[&](int l,int t,int r,int b) {
        return l>=0 && t>=0 && r>=0 && b>=0 && l<w && r<w-l && t<h && b<h-t;
    };
    const bool identity=(channelId==3 && protocolDisplayId==1 && displayType>=1 && displayType<=2) ||
        (channelId>=19 && channelId<=254 && protocolDisplayId>=2 && protocolDisplayId<=254 && displayType>=1 && displayType<=2);
    return identity && density>0 && density<=1000 && cropLeft>=0 && cropTop>=0 &&
        cropRight<=1280 && cropBottom<=720 && w>0 && h>0 &&
        cropLeft==1280-cropRight && cropTop==720-cropBottom &&
        insets(safeLeft,safeTop,safeRight,safeBottom) &&
        insets(stableLeft,stableTop,stableRight,stableBottom);
}
static void appendVideoService(
    aap_protobuf::service::control::message::ServiceDiscoveryResponse& response,
    const ClusterVideoOffer* offer) {
    auto* service=response.add_channels();service->set_id(offer->channelId);
    auto* sink=service->mutable_media_sink_service();
    sink->set_available_type(media::MEDIA_CODEC_VIDEO_H264_BP);
    sink->set_available_while_in_call(true);
    sink->set_display_id(offer->protocolDisplayId); // main is protocol display0
    sink->set_display_type(static_cast<aap_protobuf::service::media::sink::message::DisplayType>(offer->displayType));
    // AUXILIARY's root selector requires an explicit content key (UNKNOWN has no provider).
    // This requests navigation, not a passenger launcher or touch capability.
    if (offer->displayType == 2) sink->set_initial_content_keycode(
        aap_protobuf::service::media::sink::message::KEYCODE_NAVIGATION);
    auto* config=sink->add_video_configs();
    config->set_video_codec_type(media::MEDIA_CODEC_VIDEO_H264_BP);
    config->set_codec_resolution(aap_protobuf::service::media::sink::message::VIDEO_1280x720);
    config->set_frame_rate(aap_protobuf::service::media::sink::message::VIDEO_FPS_30);
    config->set_density(offer->density);config->set_real_density(offer->density);
    config->set_decoder_additional_depth(1);
    config->set_width_margin(1280-(offer->cropRight-offer->cropLeft));
    config->set_height_margin(720-(offer->cropBottom-offer->cropTop));
    auto edges=[](auto* target,int l,int t,int r,int b) {
        target->set_left(l);target->set_top(t);target->set_right(r);target->set_bottom(b);
    };
    auto* ui=config->mutable_ui_config();
    edges(ui->mutable_margins(),offer->cropLeft,offer->cropTop,1280-offer->cropRight,720-offer->cropBottom);
    edges(ui->mutable_content_insets(),offer->safeLeft,offer->safeTop,offer->safeRight,offer->safeBottom);
    edges(ui->mutable_stable_content_insets(),offer->stableLeft,offer->stableTop,offer->stableRight,offer->stableBottom);
}
bool validSecondaryVideoOffers(const std::vector<ClusterVideoOffer>& offers) {
    if(offers.size()>8)return false;
    std::set<int> channels,displays;
    for(const auto& offer:offers)
        if(!offer.valid() || !channels.insert(offer.channelId).second || !displays.insert(offer.protocolDisplayId).second)return false;
    return true;
}
std::vector<int> secondaryInputChannels(const std::vector<ClusterVideoOffer>& offers) {
    std::set<int> used;
    for (const auto& offer:offers) used.insert(offer.channelId);
    std::vector<int> result;
    for (const auto& offer:offers) {
        (void)offer;
        int id=254;
        while(id>18 && used.count(id)) --id;
        if(id<=18) return {};
        used.insert(id);result.push_back(id);
    }
    return result;
}
bool appendSecondaryVideoServices(
    aap_protobuf::service::control::message::ServiceDiscoveryResponse& response,
    const std::vector<ClusterVideoOffer>& offers) {
    if(!validSecondaryVideoOffers(offers))return false;
    const auto inputs=secondaryInputChannels(offers);
    if(inputs.size()!=offers.size())return false;
    for(size_t i=0;i<offers.size();++i)for(const auto& channel:response.channels()) {
        const auto& offer=offers[i];
        if(channel.id()==offer.channelId || channel.id()==inputs[i] ||
            (channel.has_input_source_service() && channel.input_source_service().display_id()==offer.protocolDisplayId) ||
            (channel.has_media_sink_service() && channel.media_sink_service().video_configs_size()>0 &&
             channel.media_sink_service().display_id()==offer.protocolDisplayId))return false;
    }
    // Gearhead iwl matches InputSourceService field5 against every video display,
    // including CLUSTER. It accepts empty capabilities: association is mandatory,
    // touch is not. Keep main's descriptor and coordinate space byte-identical.
    for(size_t i=0;i<offers.size();++i) {
        appendVideoService(response,&offers[i]);
        auto* input=response.add_channels();input->set_id(inputs[i]);
        input->mutable_input_source_service()->set_display_id(offers[i].protocolDisplayId);
    }
    return true;
}
void appendClusterVideoService(
    aap_protobuf::service::control::message::ServiceDiscoveryResponse& response,
    const std::optional<ClusterVideoOffer>& offer) {
    if(offer)appendSecondaryVideoServices(response,{*offer});
}
ClusterVideoHandler::ClusterVideoHandler(boost::asio::io_service::strand& strand,
    aasdk::channel::mediasink::video::IVideoMediaSinkService::Pointer channel,
    bool activeSessionIds,int maxUnacked,State state,Frame frame,Diagnostic diagnostic)
    : strand_(strand),channel_(std::move(channel)),activeSessionIds_(activeSessionIds),
      maxUnacked_(std::clamp(maxUnacked,1,2)),state_(std::move(state)),frame_(std::move(frame)),diagnostic_(std::move(diagnostic)) {}
void ClusterVideoHandler::diagnostic(const char* stage,const std::string& fields,bool terminal) {
    if(!diagnostic_)return;
    const bool first=std::find(firstStages_.begin(),firstStages_.end(),stage)==firstStages_.end() && firstStages_.size()<32;
    if(first)firstStages_.emplace_back(stage);
    if(!terminal && detailCount_>=64 && !first){++suppressed_;return;}
    if(detailCount_<64)++detailCount_;
    const auto line="channel="+std::to_string(static_cast<int>(channel_->getId()))+" stream="+std::to_string(epoch_)+
        " stage="+stage+" "+fields;
    try { diagnostic_(line); } catch(...) {} // Diagnostics never alters wire/retirement behavior.
}
aasdk::channel::SendPromise::Pointer ClusterVideoHandler::promise(const char* boundary,bool logCompletion) {
    auto result=aasdk::channel::SendPromise::defer(strand_);
    std::weak_ptr<ClusterVideoHandler> weak=shared_from_this();
    const auto sentEpoch=epoch_;
    result->then([weak,boundary,logCompletion,sentEpoch](){if(auto self=weak.lock())if(logCompletion)
        self->diagnostic("send_complete",std::string("boundary=")+boundary+" sendStream="+std::to_string(sentEpoch)+" result=sent");},
        [weak,boundary,sentEpoch](const auto& error){if(auto self=weak.lock())if(!self->retired_){
            self->diagnostic("send_failed",std::string("boundary=")+boundary+" sendStream="+std::to_string(sentEpoch)+" code="+std::to_string(static_cast<int>(error.getCode())),true);
            self->retire();}});
    return result;
}
void ClusterVideoHandler::receive(){
    if(stopped_ || receiveQueued_)return;
    receiveQueued_=true;
    // Never recurse through immediate transport completions or parser failures.
    strand_.post([self=shared_from_this()]{
        self->receiveQueued_=false;
        if(!self->stopped_)self->channel_->receive(self);
    });
}
void ClusterVideoHandler::start(){if(started_)return;started_=true;diagnostic("handler_started");receive();}
bool ClusterVideoHandler::invalidate() {
    ++epoch_;active_=false;sessionId_=0;configIndex_=-1;focus_=video::VIDEO_FOCUS_NATIVE;
    const bool ok=state_(epoch_,codec_,configIndex_,sessionId_,false);
    if(!ok){diagnostic("state_callback_failed","reason=invalidation_rejected",true);revoke(false);} // The failed invalidation already revoked admission.
    return ok;
}
void ClusterVideoHandler::revoke(bool notify){
    if(retired_)return;
    diagnostic("retire","frames="+std::to_string(frames_)+" ackSends="+std::to_string(ackSends_)+" suppressed="+std::to_string(suppressed_)+" peerReason=unknown",true);
    retired_=true;ready_=false;
    ++epoch_;active_=false;sessionId_=0;configIndex_=-1;focus_=video::VIDEO_FOCUS_NATIVE;
    if(notify)state_(epoch_,codec_,configIndex_,sessionId_,false);
    state_={};frame_={};
}
void ClusterVideoHandler::retire(){revoke(true);}
void ClusterVideoHandler::stop(){stopped_=true;retire();}
void ClusterVideoHandler::onChannelError(const aasdk::error::Error& error){
    if(!retired_)diagnostic("channel_error","code="+std::to_string(static_cast<int>(error.getCode()))+" peerReason=unknown",true);
    if(error.getCode()!=aasdk::error::ErrorCode::PARSE_PAYLOAD){stop();return;}
    // A bad optional packet is not the end of the multiplexed transport.
    retire();receive();
}
void ClusterVideoHandler::focus(bool unsolicited){
    video::VideoFocusNotification out;out.set_focus(static_cast<video::VideoFocusMode>(focus_));out.set_unsolicited(unsolicited);
    diagnostic("focus","mode="+std::to_string(focus_)+" unsolicited="+std::to_string(unsolicited));
    channel_->sendVideoFocusIndication(out,promise("focus"));
}
void ClusterVideoHandler::onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest&){
    diagnostic("channel_open","retired="+std::to_string(retired_));
    if(retired_){receive();return;}
    aap_protobuf::service::control::message::ChannelOpenResponse out;out.set_status(aap_protobuf::shared::STATUS_SUCCESS);
    channel_->sendChannelOpenResponse(out,promise("channel_open"));receive();
}
void ClusterVideoHandler::onMediaChannelSetupRequest(const media::Setup& setup){
    diagnostic("setup","codec="+std::to_string(setup.type())+" retired="+std::to_string(retired_));
    if(retired_){receive();return;}
    codec_=setup.type();ready_=false;
    if(!invalidate()){retire();receive();return;}
    ready_=codec_==media::MEDIA_CODEC_VIDEO_H264_BP;
    media::Config out;out.set_status(ready_?media::Config::STATUS_READY:media::Config::STATUS_WAIT);
    out.set_max_unacked(maxUnacked_);if(ready_)out.add_configuration_indices(0);
    diagnostic("setup_response","status="+std::to_string(out.status())+" maxUnacked="+std::to_string(out.max_unacked())+" config0="+std::to_string(ready_));
    channel_->sendChannelSetupResponse(out,promise("setup"));
    if(ready_){focus_=video::VIDEO_FOCUS_PROJECTED;focus(true);}receive();
}
void ClusterVideoHandler::onMediaChannelStartIndication(const media::Start& start){
    diagnostic("start","configIndex="+std::to_string(start.configuration_index())+" ready="+std::to_string(ready_)+" retired="+std::to_string(retired_));
    if(retired_){receive();return;}
    if(!invalidate()){retire();receive();return;}
    if(!ready_ || start.configuration_index()!=0){diagnostic("start_rejected",ready_?"reason=invalid_configuration":"reason=not_ready");receive();return;}
    sessionId_=start.session_id();configIndex_=0;active_=true;
    if(!state_(epoch_,codec_,configIndex_,sessionId_,true)){diagnostic("state_callback_failed","reason=selection_rejected",true);retire();receive();return;}
    firstFrame_=false;firstAck_=false;
    diagnostic("start_selected","configIndex=0");
    focus_=video::VIDEO_FOCUS_PROJECTED;focus(false);receive();
}
void ClusterVideoHandler::onMediaChannelStopIndication(const media::Stop&){
    diagnostic("stop","retired="+std::to_string(retired_));
    if(retired_){receive();return;}
    if(!invalidate()){retire();receive();return;}receive();
}
void ClusterVideoHandler::onMediaWithTimestampIndication(aasdk::messenger::Timestamp::ValueType ts,const aasdk::common::DataConstBuffer& buffer){
    if(retired_){receive();return;}
    aap_protobuf::service::media::source::message::Ack ack;
    ack.set_session_id(activeSessionIds_?sessionId_:0);ack.set_ack(1);
    ++ackSends_;
    const bool logAck=!firstAck_;firstAck_=true;
    if(logAck)diagnostic("ack","activeSessionIds="+std::to_string(activeSessionIds_)+" active="+std::to_string(active_));
    channel_->sendMediaAckIndication(ack,promise("ack",logAck));
    if(active_ && buffer.size>0 && buffer.size<=MAX_FRAME_BYTES){
        ++frames_;
        if(!firstFrame_){firstFrame_=true;diagnostic("first_encoded","ptsUs="+std::to_string(ts));}
        int flags=0;
        for(size_t p=0;p+3<buffer.size;++p){
            if(buffer.cdata[p]!=0 || buffer.cdata[p+1]!=0)continue;
            size_t n=p+2;
            if(buffer.cdata[n]==0)++n;
            if(n+1>=buffer.size || buffer.cdata[n]!=1)continue;
            const int type=buffer.cdata[n+1]&31;
            if(type==5)flags|=1;
            if(type==7 || type==8)flags|=2;
        }
        if(!frame_(buffer.cdata,buffer.size,ts,epoch_,flags)){diagnostic("frame_callback_failed","reason=delivery_rejected",true);retire();receive();return;}
    }else if(buffer.size>MAX_FRAME_BYTES){diagnostic("frame_rejected","reason=oversize",true);retire();receive();return;}
    receive();
}
void ClusterVideoHandler::onMediaIndication(const aasdk::common::DataConstBuffer& buffer){onMediaWithTimestampIndication(0,buffer);}
// The pinned service dispatcher rearms MEDIA_OPTIONS after this callback.
void ClusterVideoHandler::onMediaOptions(const aasdk::common::DataConstBuffer&){diagnostic("options");}
void ClusterVideoHandler::onVideoFocusRequest(const video::VideoFocusRequestNotification& request){
    diagnostic("focus_request","modePresent="+std::to_string(request.has_mode())+" mode="+std::to_string(request.mode()));
    if(retired_){receive();return;}
    if(request.has_mode() && request.mode()==video::VIDEO_FOCUS_NATIVE){if(!invalidate()){retire();receive();return;}}
    focus(false);receive(); // optional native focus NEVER calls JniSession::requestStop
}
}
