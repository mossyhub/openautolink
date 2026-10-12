# CarPlay network UI/runtime contract

Parent UI/VM owns persistence validation and mapping. Network worker owns runtime and library.

Append to CarPlaySettings and CarPlayRuntimeConfig (existing positional args unchanged):
- networkFamily: String = "auto"; allowed auto/ipv4/ipv6. AP auto selects IPv6 link-local; shared Wi-Fi auto selects proven IPv4.
- apFrequencyMhz setting: Int = 5180 (runtime already has frequency arg; use saved CarPlay value, NOT Android Auto WPP frequency).
- apSecurity: String = "wpa2"; wpa2/wpa3 maps to iAP2 security 2/4. Shared mode derives actual security/frequency unchanged.
- localAddress: String = ""; optional numeric EXACT interface-owned local endpoint, never fabricated and never wildcard.
- interfaceName: existing default ap_br_swlan0 remains authoritative; no VPN/default network fallback.
- peerAddress: explicit numeric optional for AP; mandatory for shared LAN. IPv6 peer scope optional; when omitted use selected local interface index, when supplied it must match that interface. A named scope is accepted only if literally the configured interface name; explicit zero or mismatched scope fails closed. Advertise only one selected AP local endpoint WITHOUT its local %scope.

Unknown AP peer contract: listener bound to exact scoped local IPv6 before endpoint offer. At most one bounded pre-auth candidate; only PairVerify against an already persisted single controller can establish session peer. Fresh PairSetup disabled in this mode. No controller store -> fail closed with provisioning required, not wide-open media. Media endpoints are created only after authenticated admission and bind exact session local address/family; no auxiliary IPv4 wildcard tunnel.

PhoneActivation requires selected peer IP, so skip it when AP peer is unknown. This is NOT inferred discovery, automatic phone selection, or a guarantee phone initiates ingress. Bluetooth iAP2 MFi/identification bootstrap and endpoint offer remain real. Ordinary-app GM ingress and phone initiation remain hardware gates.

No privileged AP creation, association, VPN, credential defaults, release/device/commit operations.
