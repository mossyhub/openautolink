#include "secondary_input_association.h"
#include <cassert>
#include <deque>
#include <map>
#include <iostream>
using namespace openautolink::jni;
namespace w=aasdk::messenger;
struct Mux : w::IMessenger {
 std::map<int,w::ReceivePromise::Pointer> pending;
 std::vector<w::Message::Pointer> sent;
 void enqueueReceive(w::ChannelId c,w::ReceivePromise::Pointer p)override{assert(!pending[int(c)]);pending[int(c)]=p;}
 void enqueueSend(w::Message::Pointer m,w::SendPromise::Pointer p)override{sent.push_back(m);p->resolve();}
 void stop()override{assert(false && "optional input must never stop whole messenger");}
 void push(int c,int id,const std::string& data={}){auto m=std::make_shared<w::Message>(w::ChannelId(c),w::EncryptionType::ENCRYPTED,w::MessageType::CONTROL);m->insertPayload(w::MessageId(id).getData());m->insertPayload(aasdk::common::Data(data.begin(),data.end()));auto p=pending[c];pending[c].reset();assert(p);p->resolve(m);}
};
static void pump(boost::asio::io_service& io){io.restart();while(io.poll()){} }
int main(){
 boost::asio::io_service io;boost::asio::io_service::strand strand(io);auto mux=std::make_shared<Mux>();
 std::vector<std::string> diagnostics;
 auto a=std::make_shared<SecondaryInputAssociation>(strand,mux,254,[&](const auto& line){diagnostics.push_back(line);});
 auto b=std::make_shared<SecondaryInputAssociation>(strand,mux,253);
 a->start();b->start();pump(io);
 aap_protobuf::service::control::message::ChannelOpenRequest open;open.set_priority(0);open.set_service_id(254);
 mux->push(254,7,open.SerializeAsString());pump(io);
 assert(mux->sent.size()==1 && int(mux->sent.back()->getChannelId())==254);
 auto payload=mux->sent.back()->getPayload();assert(w::MessageId(payload).getId()==8);
 aap_protobuf::service::control::message::ChannelOpenResponse response;
 assert(response.ParseFromArray(payload.data()+2,payload.size()-2) && response.status()==aap_protobuf::shared::STATUS_SUCCESS);
 aap_protobuf::service::media::sink::message::KeyBindingRequest keys;keys.add_keycodes(84);
 mux->push(253,32770,keys.SerializeAsString());pump(io);
 assert(mux->sent.size()==2 && int(mux->sent.back()->getChannelId())==253);
 payload=mux->sent.back()->getPayload();assert(w::MessageId(payload).getId()==32771);
 aap_protobuf::service::media::sink::message::KeyBindingResponse bound;
 assert(bound.ParseFromArray(payload.data()+2,payload.size()-2) && bound.status()!=aap_protobuf::shared::STATUS_SUCCESS);
 // Real ReceivePromise dispatch preserves every encrypted reply and one rearm
 // while first-success and first-invalid receipts have a fixed per-owner budget.
 for(int i=0;i<1000;++i){
  open.set_service_id(i%2==0?254:253);
  const auto before=mux->sent.size();
  mux->push(254,7,open.SerializeAsString());pump(io);
  assert(mux->sent.size()==before+1 && mux->pending[254] && mux->pending[253]);
  const auto& reply=mux->sent.back();
  assert(int(reply->getChannelId())==254 && reply->getEncryptionType()==w::EncryptionType::ENCRYPTED && reply->getType()==w::MessageType::CONTROL);
  payload=reply->getPayload();assert(w::MessageId(payload).getId()==8);
  assert(response.ParseFromArray(payload.data()+2,payload.size()-2));
  assert(response.status()==(i%2==0?aap_protobuf::shared::STATUS_SUCCESS:aap_protobuf::shared::STATUS_INVALID_SERVICE));
 }
 assert(mux->sent.size()==1002);
 assert(diagnostics.size()==2);
 assert(diagnostics[0].find("status="+std::to_string(aap_protobuf::shared::STATUS_SUCCESS))!=std::string::npos);
 assert(diagnostics[1].find("status="+std::to_string(aap_protobuf::shared::STATUS_INVALID_SERVICE))!=std::string::npos);
 // No input reports, no main-channel traffic. Stop fences already queued callbacks,
 // including their diagnostics, even for an owner whose budget is not yet used.
 a->stop();mux->push(254,7,open.SerializeAsString());pump(io);assert(mux->sent.size()==1002 && !mux->pending[254] && diagnostics.size()==2);
 auto c=std::make_shared<SecondaryInputAssociation>(strand,mux,252,[&](const auto& line){diagnostics.push_back(line);});
 c->start();pump(io);open.set_service_id(252);mux->push(252,7,open.SerializeAsString());c->stop();pump(io);
 assert(mux->sent.size()==1002 && !mux->pending[252] && diagnostics.size()==2);
 mux->push(253,99);pump(io);assert(mux->pending[253]);
 b->stop();std::cout<<"PASS passive secondary input channel open/key reject/isolation/queued stop fence/no input reports\n";
}
