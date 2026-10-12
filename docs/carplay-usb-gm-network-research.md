# Wired CarPlay networking: GM path and ordinary-app fallback

## Decision

Prefer a demonstrably usable system-created USB network when available. Do not attempt GM property writes, role-control/sysfs operations, or privileged Binder calls from OAL. For a Play-installed receiver with native CarPlay disabled, use the USB-host NCM/TUN fallback already implemented, subject to real consent and device validation. A userspace TCP/IP rewrite is not a prerequisite under the owner's clarified direction: inspect GM first, then use the DiPlay approach if its native path cannot be reproduced by the app.

This decision is based on static source and Android API evidence, not a physical GM USB test. No new release has been uploaded for this work.

## GM artifact evidence

Corpus: research/gm-carplay-oal-20261006/delayed-manifest-only, smali_classes5.

- com/gm/server/screenprojection/RemoteDeviceService/UsbUtils.smali, startWiredCarplay: checks the host-to-host interface, writes sys.usb.carplay=start/reinit and waits for OEM state changes.
- sendNativeRoleReversalForDevice: reads /sys/bus/platform/devices/a800000.ssusb/mode, writes sys.usb.native=peripheral and awaits pDone. This is an OEM path, not ordinary UsbManager enumeration.
- initCarplayFFS_EP0: explicitly states endpoint initialization is done in init.rc.
- UsbUtils also uses UsbManager.grantPermission, unlike the public user-consent requestPermission API used by OAL.
- com/gm/server/iap2/utils/UsbNetworkManager.smali: obtains network_management through ServiceManager/INetworkManagementService and registers a privileged observer. getUsbIpAddress falls back to usb0 and obtains a link-local IPv6 address.
- UsbNetworkManager$UsbNetworkObserver tracks usb0/usb1 address updates/removals. Its interfaceAdded callback is a no-op: this observer does not itself prove how the kernel interface is provisioned.
- targeted/com.gm.server.iap2.feature.CarPlayStartSession.java, getWiredAttributes: passes the selected USB IP address into the Cinemo wired StartSession attributes. No VPN bridge appears in this examined startup path.

The presence of this code does not prove native CarPlay is enabled on the Blazer, that usb0 appears for OAL, or that public-app sockets can reach it.

## Public-app boundary

Android NetworkManagementService Android-12 source explicitly calls NetworkStack.checkNetworkStackPermission in registerObserver and setInterfaceUp. System-property accessibility is controlled by SELinux. Public USB host APIs provide USB control/bulk transfers, not permission to provision OEM USB gadget endpoints or change system networking.

An already-configured, accessible interface may be discoverable through ordinary NetworkInterface APIs without the privileged observer. That possibility remains untested. It must not be treated as a guaranteed native backend. Likewise an interface name alone is insufficient: prove it belongs to the selected phone and can bind the actual listener and auxiliary sockets.

Do not force-claim NCM while evaluating a kernel-backed interface: UsbDeviceConnection.claimInterface(intf, true) can detach its kernel driver. The current NcmUsbBridge claims control/data interfaces with force=true, so it is a separate USB-host backend, not a passive way to reuse a native netdev.

## Fallback architecture

The implemented USB-host path uses USBMUX/Lockdown/carkit and iAP2 authentication, then USB NCM Ethernet frames. Its app-only TUN translates those frames into the kernel TCP/UDP sockets used by the existing receiver. The VPN is Android plumbing, not a CarPlay requirement or an Internet VPN service.

The current builder allows only OAL and routes fe80::/64; no IPv4 or default route. It still requires Android VPN consent and may replace an existing VPN. Vehicle VPN availability, inbound routing, NDP, Trust prompts, real media, re-enumeration and retirement require physical validation. Denial must not silently start another backend or replace user VPN state.

A no-VPN userspace implementation is technically plausible with an IPv6 TCP/UDP stack such as lwIP, but there is no such integrated stack in the inspected workspace. It would need receiver transport adapters for all control/event/video/timing/audio sockets plus flow control, retransmission and teardown validation; it is not merely exchanging one NCM bridge class.

## Remaining implementation gates

- Register the wired-only service and test actual generation-qualified Activity consent flow.
- Export strict USB stage/failure diagnostics.
- Run combined app/receiver tests and release build, not only worker slices.
- Verify initial USB permission ownership: runtime currently requires hasPermission, whereas manager delegates Apple permission to runtime; resolve before device testing.
- Validate physical USB on the released lab phone before describing support as working.
- Keep the existing USB selection: enabled iPhone goes to CarPlay, Android goes to AA regardless of the CarPlay flag. No new protocol-specific USB mode.

## External references

- Android public USB host APIs: https://developer.android.com/develop/connectivity/usb/host
- force-claim kernel-driver semantics: https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection
- Android-12 NetworkManagementService: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/services/core/java/com/android/server/NetworkManagementService.java
- SELinux system-property access: https://source.android.com/docs/core/architecture/configuration/add-system-properties
- EthernetTracker product-dependent interface matching: https://android.googlesource.com/platform/packages/modules/Connectivity/+/2519a78731526d2eb20ae8812acdcab6ef7a09b6/service-t/src/com/android/server/ethernet/EthernetTracker.java
- lwIP netif IPv6/custom packet input: https://www.nongnu.org/lwip/2_1_x/group__netif.html
