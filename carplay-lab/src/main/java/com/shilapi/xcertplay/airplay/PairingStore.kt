// SPDX-License-Identifier: GPL-3.0-only
// From DiPlay e2fd8eadf5a6b705666f292055e72aa93387533d, shared/src/main/java/
// com/shilapi/xcertplay/airplay/; upstream shilapi/xcertplay. See PROVENANCE.md.
package com.shilapi.xcertplay.airplay

/** Store of paired controllers keyed by their long-term Ed25519 public key. */
class PairingStore(private val onSave: ((String, ByteArray) -> Unit)? = null) {
    private val entries = HashMap<String, ByteArray>()

    @Synchronized
    fun save(identifier: String, longTermPublicKey: ByteArray) {
        require(identifier.isNotEmpty() && identifier.toByteArray(Charsets.UTF_8).size <= 256)
        require(longTermPublicKey.size == 32)
        val previous = entries[identifier]
        require(previous == null || previous.contentEquals(longTermPublicKey)) { "Controller key replacement forbidden" }
        val copy = longTermPublicKey.copyOf()
        // Persist first: failure must not make the uncommitted key usable for verification.
        onSave?.invoke(identifier, copy.copyOf())
        entries[identifier] = copy
    }

    @Synchronized
    fun get(identifier: String): ByteArray? = entries[identifier]?.copyOf()

    /** Immutable startup admission snapshot; never export controller identifiers to diagnostics. */
    @Synchronized
    fun controllerIdentifiers(): Set<String> = entries.keys.toSet()

    @Synchronized
    fun clear() = entries.clear()
}
