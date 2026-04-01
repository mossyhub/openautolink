# Bridge

The C++ headless binary that bridges a phone's Android Auto session to the car app over TCP, using the OAL protocol.

## Architecture

- **OAL Protocol**: 3 TCP channels — control (5288, JSON lines), audio (5289, binary), video (5290, binary)
- **aasdk v1.6**: Phone ↔ bridge communication via Android Auto protocol
- **SCO Audio**: BT HFP phone call audio via Bluetooth SCO sockets

### Key Source Files
- `headless/include/openautolink/oal_protocol.hpp` — OAL wire format (video/audio headers)
- `headless/include/openautolink/oal_session.hpp` — OAL session state machine
- `headless/include/openautolink/tcp_car_transport.hpp` — TCP server for car app
- `headless/src/live_session.cpp` — aasdk integration, service handlers
- `headless/src/main.cpp` — CLI entry point
- `scripts/aa_bt_all.py` — BT/WiFi pairing service
