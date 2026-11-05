package com.openautolink.app.carplay

import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter

/** Unit OS boundary only: retain real writes/AtomicFile rename and exported bytes, but do not
 * turn a 2s receipt FIFO assertion into a latency test of the host filesystem's physical fsync.
 * This does not claim power-loss durability. The injectable hold still exercises the REAL
 * production queue and its unchanged timeout/recovery behavior. No production sink is replaced.
 */
@Implements(className="android.os.FileUtils",isInAndroidSdk=false)
class CallReceiptFileSyncShadow {
    companion object {
        @Volatile var beforeSync: (() -> Unit)? = null
        val syncCalls=AtomicInteger()
        @JvmStatic @Implementation fun sync(stream:FileOutputStream?):Boolean {
            beforeSync?.invoke()
            if(stream==null)return true
            return try {
                stream.flush()
                syncCalls.incrementAndGet()
                true
            } catch (_:java.io.IOException) { false }
        }
        @JvmStatic @Resetter fun reset() { beforeSync=null;syncCalls.set(0) }
    }
}
