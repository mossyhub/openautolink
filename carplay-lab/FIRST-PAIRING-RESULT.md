# Physical iPhone AirPlay pairing result

Device-log timestamp 2026-10-09 10:23:00–10:24:34 (tablet clock; not synchronized cross-device timing).
Evidence: build/pairing-attempt.log.

Observed on TCL over the home LAN:
- Bluetooth RFCOMM, iAP2 identification and explicit 0xaa05 authentication success.
- Actual ephemeral listener bound [::]:42663; advertised tablet IPv4 in 0x4301 following iPhone 0x4300. Wi-Fi configuration request/reply 0x5702/0x5703 also exchanged.
- Selected iPhone connected to the listener and completed pair-setup cryptographic proof; controller persisted.
- Pair-verify completed with verified controller signature and encrypted control enabled.
- Encrypted auth-setup request arrived, MFi SAP response sent; phone then proceeded to a media-method request with 727-byte body.
- Probe explicitly declined media and closed the connection as designed. No video/audio session offered or proven.

This is real iPhone-to-tablet IPv4 ingress and cryptographically verified AirPlay pairing, not merely an outgoing activation response. NSD logged discovery started but no resolved endpoint/control activation response in this attempt; do not attribute success to activation GET. GM AP/scoped IPv6 remains untested. MFi SAP response alone is not a separate explicit phone success acknowledgment; subsequent media request is progression evidence.
