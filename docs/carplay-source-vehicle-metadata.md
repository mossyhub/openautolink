# CarPlay source-backed vehicle metadata

For setup and compatibility, see [EV data and Apple Maps routing](carplay-ev-pilot.md).

## What OAL shares

OAL reads eligible battery-electric vehicle data and uses it to identify the vehicle and advertise its available readings when CarPlay connects. Reconnect if readings become available after the connection starts. Changing a charging estimate also requires saving and reconnecting.

| Data | CarPlay handling |
|---|---|
| Vehicle name and plug types | Uses the car’s reported make and model, and a valid model year when available. Known connector types are translated; unknown types are omitted. Rated power is sent only for a matching, source-backed vehicle configuration. |
| Remaining energy and range | Uses fresh, valid vehicle readings. Current usable battery capacity is preferred. When it is unavailable, qualifying EV data can use the car’s reported capacity as an approximation. A percentage calculated from energy and capacity is not necessarily the percentage shown on the car’s gauge. |
| Charging state and low-range warning | Sent when the relevant source and routing status qualify. A connected cable or instantaneous power reading is not a substitute for explicit charging state. |
| Charging and consumption models | Use a matching automatic charging estimate when available or a deliberately saved Advanced estimate with eligible vehicle data. The consumption estimate uses remaining energy and range. The Advanced value is an estimated average into the battery, not a connector rating, peak power or live power. OAL does not invent a default model for an unmatched vehicle. |
| Outside temperature and location | Remain separate from the EV sharing switch and use their existing availability checks. |

**CarPlay EV data** defaults on for eligible data. A previously saved Off choice stays off. Valid battery and range data do not guarantee that Apple Maps can add the vehicle or plan EV routes. Check for a battery estimate at arrival and, on longer trips, verify that Maps plans suitable charging stops. Automatic diagnostic contribution is a separate opt-in.

## Technical reference

The CarPlay offer describes the source-backed capabilities for the current link. The authenticated status reporter sends requested, available fields. Connector IDs come from the raw [Android vehicle property definitions](https://developer.android.com/reference/android/car/VehiclePropertyIds), not the ordinals of a deprecated Java connector enum. The user-visible vehicle name is separate from the accessory identity and the charging and consumption model identifiers. Local transmission does not establish that Apple Maps accepted the offer or used its estimates.
