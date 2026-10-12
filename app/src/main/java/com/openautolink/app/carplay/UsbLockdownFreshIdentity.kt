// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.app.carplay

import java.util.Locale
import java.util.UUID

/** Fresh USB pairing identities only; never rewrites a persisted trust binding. */
internal class UsbLockdownFreshIdentity private constructor(
    val hostId: String,
    val systemBuid: String,
) {
    override fun toString(): String = "UsbLockdownFreshIdentity(redacted)"

    companion object {
        fun create(hostUuid: UUID = UUID.randomUUID(), buidUuid: UUID = UUID.randomUUID()) =
            UsbLockdownFreshIdentity(hostUuid.toString().uppercase(Locale.ROOT),
                buidUuid.toString().uppercase(Locale.ROOT))
    }
}
