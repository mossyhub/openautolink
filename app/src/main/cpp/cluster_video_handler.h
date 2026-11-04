#pragma once
#include "secondary_input_association.h"
#include <aasdk/Channel/MediaSink/Video/IVideoMediaSinkService.hpp>
#include <aap_protobuf/service/control/message/ServiceDiscoveryResponse.pb.h>
#include <boost/asio.hpp>
#include <functional>
#include <memory>
#include <optional>
#include <vector>
#include <string>
#include <aasdk/Messenger/IMessenger.hpp>

namespace openautolink::jni {
// Validate the optional channel's completed message before the pinned dispatcher reads its ID.
// The primary messenger and its other channels retain their existing behavior.
class ClusterLengthCheckedMessenger final : public aasdk::messenger::IMessenger {
public:
    ClusterLengthCheckedMessenger(boost::asio::io_service::strand& strand, Pointer inner)
        : strand_(strand), inner_(std::move(inner)) {}
    void enqueueReceive(aasdk::messenger::ChannelId channel,
                        aasdk::messenger::ReceivePromise::Pointer promise) override {
        auto checked = aasdk::messenger::ReceivePromise::defer(strand_);
        checked->then([promise](aasdk::messenger::Message::Pointer message) {
            if (!message || message->getPayload().size() < 2) {
                promise->reject(aasdk::error::Error(aasdk::error::ErrorCode::PARSE_PAYLOAD));
                return;
            }
            promise->resolve(std::move(message));
        }, [promise](const aasdk::error::Error& error) { promise->reject(error); });
        inner_->enqueueReceive(channel, std::move(checked));
    }
    void enqueueSend(aasdk::messenger::Message::Pointer message,
                     aasdk::messenger::SendPromise::Pointer promise) override {
        inner_->enqueueSend(std::move(message), std::move(promise));
    }
    void stop() override { inner_->stop(); }
private:
    boost::asio::io_service::strand& strand_;
    Pointer inner_;
};
// Value copy of the validated Kotlin offer; protocol IDs are not Android display IDs.
struct ClusterVideoOffer {
    int channelId=3, protocolDisplayId=1, displayType=1;
    int density=160;
    int cropLeft=0, cropTop=0, cropRight=1280, cropBottom=720;
    int safeLeft=0, safeTop=0, safeRight=0, safeBottom=0;
    int stableLeft=0, stableTop=0, stableRight=0, stableBottom=0;
    bool valid() const;
};
// Separate input services use highest free protocol channels, never main/fixed IDs.
std::vector<int> secondaryInputChannels(const std::vector<ClusterVideoOffer>& offers);
bool validSecondaryVideoOffers(const std::vector<ClusterVideoOffer>& offers);
bool appendSecondaryVideoServices(
    aap_protobuf::service::control::message::ServiceDiscoveryResponse& response,
    const std::vector<ClusterVideoOffer>& offers);
void appendClusterVideoService(
    aap_protobuf::service::control::message::ServiceDiscoveryResponse& response,
    const std::optional<ClusterVideoOffer>& offer);

/** IO-strand confined optional video sink. Never escalates an optional failure to session error. */
class ClusterVideoHandler final
    : public aasdk::channel::mediasink::video::IVideoMediaSinkServiceEventHandler,
      public std::enable_shared_from_this<ClusterVideoHandler> {
public:
    using State = std::function<bool(uint64_t,int,int,int,bool)>;
    using Frame = std::function<bool(const uint8_t*,size_t,uint64_t,uint64_t,int)>;
    using Diagnostic = std::function<void(const std::string&)>;
    static constexpr size_t MAX_FRAME_BYTES=2*1024*1024;
    ClusterVideoHandler(boost::asio::io_service::strand& strand,
        aasdk::channel::mediasink::video::IVideoMediaSinkService::Pointer channel,
        bool activeSessionIds, int maxUnacked, State state, Frame frame, Diagnostic diagnostic = {});
    void start();
    // Revoke decoder admission, but continue consuming optional wire traffic.
    void retire();
    // Whole-transport retirement: no further receive rearm (also after IO joins).
    void stop();
    void onChannelOpenRequest(const aap_protobuf::service::control::message::ChannelOpenRequest&) override;
    void onMediaChannelSetupRequest(const aap_protobuf::service::media::shared::message::Setup&) override;
    void onMediaChannelStartIndication(const aap_protobuf::service::media::shared::message::Start&) override;
    void onMediaChannelStopIndication(const aap_protobuf::service::media::shared::message::Stop&) override;
    void onMediaWithTimestampIndication(aasdk::messenger::Timestamp::ValueType,const aasdk::common::DataConstBuffer&) override;
    void onMediaIndication(const aasdk::common::DataConstBuffer&) override;
    void onMediaOptions(const aasdk::common::DataConstBuffer&) override;
    void onVideoFocusRequest(const aap_protobuf::service::media::video::message::VideoFocusRequestNotification&) override;
    void onChannelError(const aasdk::error::Error&) override;
private:
    aasdk::channel::SendPromise::Pointer promise(const char* boundary, bool logCompletion = true);
    void diagnostic(const char* stage, const std::string& fields = "", bool terminal = false);
    Diagnostic diagnostic_;
    unsigned detailCount_=0, suppressed_=0;
    std::vector<std::string> firstStages_; // Only private literal call sites; capped at 32.
    uint64_t frames_=0, ackSends_=0;
    bool firstFrame_=false, firstAck_=false;
    void receive();
    void revoke(bool notify);
    bool invalidate();
    void focus(bool unsolicited);
    boost::asio::io_service::strand& strand_;
    aasdk::channel::mediasink::video::IVideoMediaSinkService::Pointer channel_;
    const bool activeSessionIds_;
    const int maxUnacked_;
    State state_;
    Frame frame_;
    uint64_t epoch_=0;
    int codec_=0, sessionId_=0, configIndex_=-1, focus_=0;
    bool ready_=false, active_=false, retired_=false;
    bool started_=false, stopped_=false, receiveQueued_=false;
};
}
