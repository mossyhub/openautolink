# Owner live diagnostics

While parked, open **Settings → Diagnostics → Owner live diagnostics**.
On a clean install the server field is blank. Enter your chosen HTTPS diagnostic-server
base URL and choose **Apply** before pairing. OAL does not select a maintainer server
or send diagnostic traffic on your behalf. Existing explicitly saved servers and
remembered pairings remain associated with their original endpoint.
Enter the five-word owner invitation for first pairing, then choose **Enable read-only**.
The device remembers the pairing encrypted in Android Keystore-backed, no-backup storage.
Do not share invitations or credentials in logs or chat.

**Auto-connect on OAL launch** is optional and initially Off. When saved On, opening OAL
restores that pairing without another invitation. It does not start OAL when the app is closed,
and adds no boot receiver, foreground service, wake lock or transport binding.
The non-secret launch choice is stored separately from the encrypted credential, also outside backup.

- **Off:** delivery is stopped. Pairing-required or storage recovery guidance appears here when needed.
- **Waiting for internet:** no validated default internet network; the bounded backlog is retained.
- **Connecting:** authentication/upload is in progress, or the server is being retried with bounded backoff.
- **Live:** the server acknowledged an upload. This does not establish physical OEM screen visibility.

A genuine unavailable-to-validated default-network transition wakes a disconnected retry promptly;
repeated capabilities callbacks on an already validated network do not bypass server backoff.
If Android cannot report network state, normal bounded upload retries remain available.
No separate pings or external health probes run from the app.

**Stop** pauses this run only and clears its backlog. It cannot be undone by a late network callback.
Turn auto-connect Off as well to stay Off on the next launch. Turning the launch choice Off alone
leaves a currently enabled run unchanged. **Revoke owner** first durably turns auto-connect Off,
then requests server credential/reader revocation. If the server is unavailable, the UI says revocation
is unconfirmed and offers retry; it does not claim that server revocation succeeded.

Storage/Keystore errors leave the remembered pairing intact and show an actionable retry message.
An unpaired launch does not send an empty invitation or repeatedly contact the pairing endpoint.

Live logging remains read-only, redacted and best effort with bounded, counted drops:
512 records / 2 MiB RAM backlog, 16 KiB per message, 64 KiB JSON per second. It does not
activate secondary output, change Off/Icons-only/Video intent, add video offers, initialize an
optional projection runtime, control transport, backfill historical files or change LogUploader.
Physical ignition return, black-page visibility and projected sizing remain separate vehicle checks.
