#pragma once
#include <aasdk/Messenger/IMessenger.hpp>
#include <aasdk/Messenger/MessageId.hpp>
#include <aap_protobuf/service/control/message/ChannelOpenRequest.pb.h>
#include <aap_protobuf/service/control/message/ChannelOpenResponse.pb.h>
#include <aap_protobuf/service/media/sink/message/KeyBindingRequest.pb.h>
#include <aap_protobuf/service/media/sink/message/KeyBindingResponse.pb.h>
#include <aap_protobuf/shared/MessageStatus.pb.h>
#include <boost/asio.hpp>
#include <functional>

namespace openautolink::jni {
// Noninteractive display association. No touch/key report API by design. All
// state is IO-strand confined; session teardown stops it after joining IO.
// A retired video can retain this harmless protocol association for draining.
class SecondaryInputAssociation : public std::enable_shared_from_this<SecondaryInputAssociation> {
public:
    using Diagnostic=std::function<void(const std::string&)>;
    SecondaryInputAssociation(boost::asio::io_service::strand& strand,
        aasdk::messenger::IMessenger::Pointer messenger,int id,Diagnostic diagnostic={})
        : strand_(strand),messenger_(std::move(messenger)),id_(static_cast<aasdk::messenger::ChannelId>(id)),diagnostic_(std::move(diagnostic)) {}
    void start(){if(started_)return;started_=true;receive();}
    void stop(){stopped_=true;}
private:
    void receive(){
        if(stopped_)return;
        strand_.post([self=shared_from_this()]{
            if(self->stopped_)return;
            auto promise=aasdk::messenger::ReceivePromise::defer(self->strand_);
            promise->then([self](auto message){self->handle(message);},[self](const auto& error){
                if(error.getCode()==aasdk::error::ErrorCode::PARSE_PAYLOAD)self->receive();
                else self->stop();
            });
            self->messenger_->enqueueReceive(self->id_,std::move(promise));
        });
    }
    template<class T> void send(int type,aasdk::messenger::MessageType kind,const T& response){
        if(stopped_)return;
        auto message=std::make_shared<aasdk::messenger::Message>(id_,aasdk::messenger::EncryptionType::ENCRYPTED,kind);
        message->insertPayload(aasdk::messenger::MessageId(type).getData());message->insertPayload(response);
        auto promise=aasdk::messenger::SendPromise::defer(strand_);
        promise->then([]{},[self=shared_from_this()](const auto&){self->stop();});
        messenger_->enqueueSend(std::move(message),std::move(promise));
    }
    void handle(const aasdk::messenger::Message::Pointer& message){
        if(stopped_)return;
        if(!message || message->getPayload().size()<2){receive();return;}
        const auto& bytes=message->getPayload();
        const int type=aasdk::messenger::MessageId(bytes).getId();
        if(type==7){
            aap_protobuf::service::control::message::ChannelOpenRequest request;
            if(request.ParseFromArray(bytes.data()+2,bytes.size()-2)){
                aap_protobuf::service::control::message::ChannelOpenResponse response;
                response.set_status(request.service_id()==static_cast<int>(id_)?aap_protobuf::shared::STATUS_SUCCESS:aap_protobuf::shared::STATUS_INVALID_SERVICE);
                send(8,aasdk::messenger::MessageType::CONTROL,response);
                bool& logged=response.status()==aap_protobuf::shared::STATUS_SUCCESS?successLogged_:invalidLogged_;
                if(diagnostic_ && !logged){
                    logged=true;
                    diagnostic_("stage=input_channel_open inputChannel="+std::to_string(static_cast<int>(id_))+" status="+std::to_string(response.status())+" capabilities=none");
                }
            }
        }else if(type==32770){
            aap_protobuf::service::media::sink::message::KeyBindingRequest request;
            if(request.ParseFromArray(bytes.data()+2,bytes.size()-2)){
                aap_protobuf::service::media::sink::message::KeyBindingResponse response;
                response.set_status(request.keycodes_size()==0?aap_protobuf::shared::STATUS_SUCCESS:aap_protobuf::shared::STATUS_KEYCODE_NOT_BOUND);
                send(32771,aasdk::messenger::MessageType::SPECIFIC,response);
            }
        }
        receive();
    }
    boost::asio::io_service::strand& strand_;
    aasdk::messenger::IMessenger::Pointer messenger_;
    aasdk::messenger::ChannelId id_;
    Diagnostic diagnostic_;
    bool started_=false,stopped_=false;
    // Strand-confined, lifetime budget: first success plus first invalid service.
    bool successLogged_=false,invalidLogged_=false;
};
}
