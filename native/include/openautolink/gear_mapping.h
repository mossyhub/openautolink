#pragma once

#include <aap_protobuf/service/sensorsource/message/Gear.pb.h>
#include <optional>

namespace openautolink::sensors {

// GEAR_SELECTION is a VHAL enum, not an AA ordinal or a combination of bits.
// Keep the raw value upstream; convert only when constructing the AA message.
// VehicleGear defines numbered forward gears 1..9 (AA additionally has 10).
constexpr std::optional<aap_protobuf::service::sensorsource::message::Gear>
vhalToAaGear(int raw) {
    namespace AA = aap_protobuf::service::sensorsource::message;
    switch (raw) {
        case 0x0001: return AA::GEAR_NEUTRAL;
        case 0x0002: return AA::GEAR_REVERSE;
        case 0x0004: return AA::GEAR_PARK;
        case 0x0008: return AA::GEAR_DRIVE;
        case 0x0010: return AA::GEAR_1;
        case 0x0020: return AA::GEAR_2;
        case 0x0040: return AA::GEAR_3;
        case 0x0080: return AA::GEAR_4;
        case 0x0100: return AA::GEAR_5;
        case 0x0200: return AA::GEAR_6;
        case 0x0400: return AA::GEAR_7;
        case 0x0800: return AA::GEAR_8;
        case 0x1000: return AA::GEAR_9;
        // UNKNOWN=0, absent/unsupported values and combinations cannot establish
        // any gear. In particular, never manufacture Neutral or Park from them.
        default: return std::nullopt;
    }
}

// Legacy headless JSON explicitly carries already-converted AA ordinals.
// Do not reinterpret its AA GEAR_4=4 as VHAL Park=4 (or AA Neutral=0 as unknown).
inline std::optional<aap_protobuf::service::sensorsource::message::Gear>
aaGearFromOrdinal(int ordinal) {
    namespace AA = aap_protobuf::service::sensorsource::message;
    if (!AA::Gear_IsValid(ordinal)) return std::nullopt;
    return static_cast<AA::Gear>(ordinal);
}

}  // namespace openautolink::sensors
