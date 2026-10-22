#pragma once

#include <aap_protobuf/service/sensorsource/SensorSourceService.pb.h>
#include <sstream>
#include <string>
#include <vector>

namespace openautolink::ev {

// INFO_EV_CONNECTOR_TYPE is a raw VHAL enum, NOT android.car/AA ordinals.
// Convert only at the AA SDR boundary; retain raw values in the vehicle model.
constexpr aap_protobuf::service::sensorsource::message::EvConnectorType
vhalToAaEvConnector(int raw) {
    namespace AA = aap_protobuf::service::sensorsource::message;
    switch (raw) {
        case 1: return AA::EV_CONNECTOR_TYPE_J1772;
        case 2: return AA::EV_CONNECTOR_TYPE_MENNEKES;
        case 4: return AA::EV_CONNECTOR_TYPE_CHADEMO;
        case 5: return AA::EV_CONNECTOR_TYPE_COMBO_1;
        case 6: return AA::EV_CONNECTOR_TYPE_COMBO_2;
        case 7: return AA::EV_CONNECTOR_TYPE_TESLA_ROADSTER;
        case 8: return AA::EV_CONNECTOR_TYPE_TESLA_HPWC;
        case 9: return AA::EV_CONNECTOR_TYPE_TESLA_SUPERCHARGER;
        case 10: return AA::EV_CONNECTOR_TYPE_GBT;
        case 101: return AA::EV_CONNECTOR_TYPE_OTHER;
        // VHAL 3 is SCAME, absent in our AA enum. VHAL 11 is GBT_DC:
        // framework CarInfoManager maps it to a distinct framework 10, not 9.
        // Our linked AA schema has only GBT=9 (framework GBT_AC=9), so do
        // not conflate DC with AC or reinterpret it as a newer AA enum.
        // Likewise no NACS/adapters are inferred from unknown raw values.
        case 0:
        case 3:
        case 11:
        default: return AA::EV_CONNECTOR_TYPE_UNKNOWN;
    }
}

// Return a one-line SDR-construction receipt, not a per-frame sensor log.
inline std::string addSupportedEvConnectors(
        aap_protobuf::service::sensorsource::SensorSourceService& sensor,
        const std::vector<int>& raw) {
    namespace AA = aap_protobuf::service::sensorsource::message;
    const int first = sensor.supported_ev_connector_types_size();
    // Preserve the legacy US fallback ONLY when the raw property is absent.
    // Nonempty unsupported data must stay UNKNOWN, never become US connectors.
    if (raw.empty()) {
        sensor.add_supported_ev_connector_types(AA::EV_CONNECTOR_TYPE_J1772);
        sensor.add_supported_ev_connector_types(AA::EV_CONNECTOR_TYPE_COMBO_1);
    } else {
        for (int value : raw) {
            sensor.add_supported_ev_connector_types(vhalToAaEvConnector(value));
        }
    }
    std::ostringstream diagnostic;
    diagnostic << "OAL_EV_CONNECTORS_V1 source="
               << (raw.empty() ? "absent-fallback" : "vhal") << " raw=[";
    for (size_t i = 0; i < raw.size(); ++i) {
        if (i) diagnostic << ',';
        diagnostic << raw[i];
    }
    diagnostic << "] aa=[";
    for (int i = first; i < sensor.supported_ev_connector_types_size(); ++i) {
        if (i != first) diagnostic << ',';
        diagnostic << static_cast<int>(sensor.supported_ev_connector_types(i));
    }
    diagnostic << ']';
    return diagnostic.str();
}

}  // namespace openautolink::ev
