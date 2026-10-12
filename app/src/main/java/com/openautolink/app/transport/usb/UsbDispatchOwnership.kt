package com.openautolink.app.transport.usb

/** One selected phone, including permission, AOA transition and native retirement. */
internal class UsbDispatchOwnership {
    enum class Route { APPLE, ANDROID }
    class Token internal constructor(val generation: Long, @Volatile var deviceName: String, val route: Route) {
        @Volatile internal var awaitingPermission = false
        @Volatile internal var retiring = false
        @Volatile internal var reenumerating = false
    }
    private var active: Token? = null

    @Synchronized fun select(deviceName: String, vendorId: Int): Token? {
        if (active != null) return null
        return Token(generations.incrementAndGet(), deviceName, route(vendorId)).also { active = it }
    }
    @Synchronized fun isCurrent(token: Token) = active === token
    @Synchronized fun current(): Token? = active
    @Synchronized fun beginPermission(token: Token): Boolean {
        if (active !== token || token.awaitingPermission || token.retiring) return false
        token.awaitingPermission = true
        return true
    }
    @Synchronized fun permission(deviceName: String, generation: Long): Token? {
        val token = active ?: return null
        if (token.deviceName != deviceName || token.generation != generation ||
            !token.awaitingPermission || token.retiring) return null
        token.awaitingPermission = false
        return token
    }
    @Synchronized fun detach(deviceName: String): Token? {
        val token = active ?: return null
        if (token.deviceName != deviceName || token.retiring || token.reenumerating) return null
        token.retiring = true
        token.awaitingPermission = false
        return token
    }
    @Synchronized fun beginReenumeration(token: Token): Boolean {
        if (active !== token || token.retiring || token.reenumerating) return false
        token.reenumerating = true
        return true
    }
    @Synchronized fun adoptReenumeration(token: Token, deviceName: String, vendorId: Int): Boolean {
        if (active !== token || token.retiring || !token.reenumerating || route(vendorId) != token.route) return false
        token.deviceName = deviceName
        token.reenumerating = false
        return true
    }
    @Synchronized fun retire(): Token? {
        val token = active ?: return null
        if (token.retiring) return null
        token.retiring = true
        token.reenumerating = false
        token.awaitingPermission = false
        return token
    }
    @Synchronized fun release(token: Token): Boolean {
        if (active !== token) return false
        active = null
        return true
    }
    companion object {
        private val generations = java.util.concurrent.atomic.AtomicLong()
        fun route(vendorId: Int) = if (vendorId == 0x05ac) Route.APPLE else Route.ANDROID
    }
}
