// Exactly one association; empty capabilities mean noninteractive output.
static void assertInputAssociation(const Response& response) {
    std::set<int> ids;
    for (const auto& ch : response.channels()) assert(ids.insert(ch.id()).second);
    for (const auto& video : response.channels()) {
        if (!video.has_media_sink_service() || video.media_sink_service().video_configs_size()==0 ||
            video.media_sink_service().display_id()==0) continue;
        int count=0;
        for (const auto& input : response.channels()) {
            if (!input.has_input_source_service() || input.input_source_service().display_id()!=video.media_sink_service().display_id()) continue;
            ++count;
            const auto& d=input.input_source_service();
            assert(d.has_display_id());
            assert(input.id()!=video.id() && input.id()>18 && input.id()<255);
            assert(d.keycodes_supported_size()==0 && d.touchscreen_size()==0 &&
                   d.touchpad_size()==0 && d.feedback_events_supported_size()==0);
        }
        assert(count==1 && "secondary display needs exactly one no-capability input association");
    }
}
static void removeOptional(Response& response) {
    for(int i=response.channels_size()-1;i>=0;--i) {
        const auto& ch=response.channels(i);
        if ((ch.has_input_source_service() && ch.input_source_service().display_id()!=0) ||
            (ch.has_media_sink_service() && ch.media_sink_service().video_configs_size()>0 && ch.media_sink_service().display_id()!=0))
            response.mutable_channels()->DeleteSubrange(i,1);
    }
}
int main() {
    JniSession s;
    Response disabled; s.buildServiceDiscoveryResponse(disabled);
    const auto baseline=disabled.SerializeAsString();
    // Null must be byte-identical, including config order, input and touch geometry.
    Response again;s.buildServiceDiscoveryResponse(again);assert(again.SerializeAsString()==baseline);
    ClusterVideoOffer offer;offer.cropTop=119;offer.cropBottom=601;
    offer.safeLeft=372;offer.safeRight=376;offer.safeTop=72;offer.safeBottom=54;
    offer.stableLeft=373;offer.stableRight=377;offer.stableTop=73;offer.stableBottom=55;
    assert(offer.valid());s.sdrConfig_.clusterOffer=offer;
    Response enabled;s.buildServiceDiscoveryResponse(enabled);
    Response decoded;assert(decoded.ParseFromString(enabled.SerializeAsString()));
    assertInputAssociation(decoded);
    assert(decoded.channels_size()==disabled.channels_size()+2 && "missing optional second video sink");
    std::set<int> ids;int clusterCount=0, touchCount=0;
    for(const auto& ch:decoded.channels()) {
        assert(ids.insert(ch.id()).second);
        if(ch.has_input_source_service()) {touchCount+=ch.input_source_service().touchscreen_size();}
        if(ch.id()!=3)continue;
        ++clusterCount;assert(!ch.has_input_source_service());
        const auto& ms=ch.media_sink_service();assert(ms.display_id()==1 && ms.display_type()==aap_protobuf::service::media::sink::message::DISPLAY_TYPE_CLUSTER);
        assert(ms.has_display_id() && !ms.has_audio_type() && ms.video_configs_size()==1);
        assert(ms.available_type()==aap_protobuf::service::media::shared::message::MEDIA_CODEC_VIDEO_H264_BP);
        const auto& vc=ms.video_configs(0);
        assert(vc.codec_resolution()==aap_protobuf::service::media::sink::message::VIDEO_1280x720 && vc.frame_rate()==aap_protobuf::service::media::sink::message::VIDEO_FPS_30);
        assert(vc.density()==160 && vc.width_margin()==0 && vc.height_margin()==238);
        assert(vc.ui_config().margins().top()==119 && vc.ui_config().margins().bottom()==119);
        assert(vc.ui_config().content_insets().left()==372 && vc.ui_config().content_insets().top()==72);
        assert(vc.ui_config().stable_content_insets().left()==373 && vc.ui_config().stable_content_insets().top()==73);
    }
    assert(clusterCount==1 && touchCount==1);
    // Delete ONLY the cluster service: the complete disabled message must remain identical.
    removeOptional(decoded);
    assert(decoded.SerializeAsString()==baseline);
    offer.density=0;s.sdrConfig_.clusterOffer=offer;
    Response invalid;s.buildServiceDiscoveryResponse(invalid);assert(invalid.SerializeAsString()==baseline);
    // Both auto and manual main retain exact config ordering and touch dimensions.
    for(bool autoMode:{false,true})for(const std::string codec:{"h264","h265"}) {
        s.sdrConfig_.autoNegotiate=autoMode;s.sdrConfig_.videoCodec=codec;
        s.sdrConfig_.clusterOffer.reset();Response before;s.buildServiceDiscoveryResponse(before);
        offer.density=160;s.sdrConfig_.clusterOffer=offer;Response after;s.buildServiceDiscoveryResponse(after);
        assertInputAssociation(after);removeOptional(after);
        assert(after.SerializeAsString()==before.SerializeAsString());
    }
    std::cout << "PASS real serialized SDR disabled/enabled/IDs/CLUSTER/H264/config/crop/content/stable/no-touch/main-byte-equivalence\n";
    // Independent Cluster/Auxiliary services survive actual protobuf serialization.
    s.sdrConfig_.clusterOffer.reset();
    offer.density=160;
    ClusterVideoOffer auxiliary; auxiliary.channelId=19; auxiliary.protocolDisplayId=2; auxiliary.displayType=2;
    s.sdrConfig_.secondaryOffers={offer,auxiliary};
    Response multi;s.buildServiceDiscoveryResponse(multi);
    Response roundtrip;assert(roundtrip.ParseFromString(multi.SerializeAsString()));
    assertInputAssociation(roundtrip);
    assert(roundtrip.channels_size()==disabled.channels_size()+4);
    std::set<int> multiIds;int optional=0;
    for(const auto& ch:roundtrip.channels()){
        assert(multiIds.insert(ch.id()).second);
        if(ch.id()==3 || ch.id()==19){++optional;assert(!ch.has_input_source_service());
            assert(ch.media_sink_service().display_id()==(ch.id()==3?1:2));
            assert(static_cast<int>(ch.media_sink_service().display_type())==(ch.id()==3?1:2));}
    }
    assert(optional==2);
    for(int reserved=0;reserved<=18;++reserved){if(reserved==3)continue;
        auxiliary.channelId=reserved;s.sdrConfig_.secondaryOffers={offer,auxiliary};
        Response rejected;s.buildServiceDiscoveryResponse(rejected);
        assert(rejected.channels_size()==disabled.channels_size());
    }
    auxiliary.channelId=19;
    for(auto invalidSet: {std::vector<ClusterVideoOffer>{offer,offer},std::vector<ClusterVideoOffer>(9,auxiliary)}){
        s.sdrConfig_.secondaryOffers=invalidSet;
        Response rejected;s.buildServiceDiscoveryResponse(rejected);assert(rejected.channels_size()==disabled.channels_size());
    }
    std::cout << "PASS multi-output serialized SDR + atomic optional-set rejection\n";
    // High video channel IDs must not collide with derived input IDs.
    auxiliary.channelId=254;s.sdrConfig_.secondaryOffers={offer,auxiliary};
    Response high;s.buildServiceDiscoveryResponse(high);assertInputAssociation(high);
    // Existing input associations must reject the entire optional append atomically.
    Response collision=disabled;auto* c=collision.add_channels();c->set_id(250);
    c->mutable_input_source_service()->set_display_id(2);
    const auto collisionBytes=collision.SerializeAsString();
    assert(!appendSecondaryVideoServices(collision,{auxiliary}));
    assert(collision.SerializeAsString()==collisionBytes);
    std::cout << "PASS no-capability input association + channel collision + unchanged complete main SDR\n";
    return 0;
}
