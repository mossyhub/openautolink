package com.openautolink.app.transport

/**
 * Pure predicate for "can the hotspot/TCP connect path actually dial this host?"
 *
 * Extracted as a dependency-free helper (no Android, no ViewModel) so it can be
 * unit-tested and reused at every phone-selection site instead of each call
 * re-deriving its own ad-hoc check.
 *
 * ## Why reject ALL IPv6, not just link-local (issue #48)
 *
 * OpenAutoLink's local-link model is IPv4-only on every path the *companion*
 * actually serves on:
 *
 *  - the companion's TCP server, identity probe, and UDP discovery all bind
 *    IPv4 sockets ([com.openautolink.companion] `TcpAdvertiser`),
 *  - the car-side gateway fallback ([com.openautolink.app.transport.hotspot.TcpConnector.getGatewayIp])
 *    derives an IPv4 address from DHCP,
 *  - the /24 subnet sweep ([PhoneDiscovery]) is IPv4-only.
 *
 * On a phone-hotspot link the phone's *global* IPv6 address (e.g. a cellular
 * `2607:…` GUA) is advertised over mDNS but is **not routable over the SoftAP
 * bridge**, so a `connect()` to it never completes. Worse, in Car-Hotspot mode
 * the resolved host is fed straight to [TcpConnector.manualIp], which forces the
 * direct-dial branch and *skips* the IPv4 gateway/sweep fallback — so a single
 * IPv6 mDNS answer would pin the connector to an unreachable address and retry
 * it forever (issue #48: 54 dead dials to `2607:…` while the working IPv4
 * `10.218.15.62` sat unused in the sweep plan, escalating into an AASDK-30
 * reconnect storm).
 *
 * Since the companion never serves on IPv6 on the local link, any IPv6 literal
 * is unusable for our connect path — link-local (needs a scope id NSD doesn't
 * surface) *and* global (not bridged on the hotspot). Rejecting all IPv6 makes
 * selection fall through to the IPv4 address that discovery already surfaces.
 *
 * If OAL ever gains a genuinely IPv6-reachable transport, narrow this predicate
 * rather than re-introducing per-site checks.
 */
object HostUsability {

    /**
     * True when [host] must NOT be handed to the connect path.
     *
     * Treats a host as unusable when it is blank or any IPv6 literal. IPv6 is
     * detected structurally (a literal containing more than one ':' — which an
     * IPv4 `a.b.c.d` or `a.b.c.d:port` never does) so it needs no InetAddress
     * allocation and is safe on a raw mDNS-resolved address string. A trailing
     * `:port` on an IPv4 host (one colon) is preserved as usable.
     */
    fun isUnusable(host: String?): Boolean {
        if (host.isNullOrBlank()) return true
        // IPv6 literals contain at least two ':' (e.g. "fe80::1", "2607:fb91::9c").
        // IPv4 "10.0.0.5" has none; "10.0.0.5:5277" has exactly one. A bracketed
        // "[2607:..]" form also trips the >1 colon test.
        return host.count { it == ':' } > 1
    }

    /** Convenience inverse — readable at filter sites. */
    fun isUsable(host: String?): Boolean = !isUnusable(host)
}
