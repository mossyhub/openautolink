package com.openautolink.carplaylab.telecom

/** Normalized observations, not iAP2 numeric values. No decoder is implied. */
enum class RemoteCallState { RINGING, ACTIVE, HELD, ENDED }

/** Unwired pure seam; callers must supply authenticated, source-validated observations. */
class ObservedCallInventory(private val capacity: Int, private val freshnessMs: Long) {
    class Session internal constructor()
    private var owner: Session? = null
    private val calls = linkedMapOf<String, RemoteCallState>()
    private val observedAt = mutableMapOf<String, Long>()
    private var lastTime = -1L
    fun beginSession(): Session = Session().also { owner = it; calls.clear(); observedAt.clear(); lastTime = -1 }
    fun update(session: Session, id: String, state: RemoteCallState, nowMs: Long): Boolean {
        if (session !== owner || id.isBlank() || id.length > 256 || nowMs < 0 || nowMs < lastTime) return false
        if (state != RemoteCallState.ENDED && id !in calls && calls.size >= capacity) return false
        lastTime = nowMs
        if (state == RemoteCallState.ENDED) {
            calls.remove(id); observedAt.remove(id)
        } else {
            calls[id] = state; observedAt[id] = nowMs
        }
        return true
    }
    /** Local policy expiry only: returned identities lost observation, not remote-ended calls.
     * No iAP2 heartbeat/expiry contract is asserted. Caller chooses a conservative budget. */
    fun expire(session: Session, nowMs: Long): Set<String> {
        if (session !== owner || nowMs < 0 || nowMs < lastTime) return emptySet()
        lastTime = nowMs
        val expired = observedAt.filterValues { nowMs - it >= freshnessMs }.keys.toSet()
        expired.forEach { calls.remove(it); observedAt.remove(it) }
        return expired
    }
    /** Revokes local observation. Deliberately has no remote-command side effects. */
    fun transportLost(session: Session): Set<String> {
        if (session !== owner) return emptySet()
        val lost = calls.keys.toSet()
        calls.clear(); observedAt.clear(); owner = null
        return lost
    }
    fun snapshot(session: Session): Map<String, RemoteCallState> =
        if (session === owner) calls.toMap() else emptyMap()
}
