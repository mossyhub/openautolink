package com.openautolink.app.session

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSessionDependencyContractTest {

    @Test
    fun `every native start funnels through one complete dependency preparer`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/session/SessionManager.kt",
        ).readText()
        val start = source.indexOf("private fun prepareNativeSessionStart(session: AasdkSession)")
        val end = source.indexOf("private fun startLocationForwarding", startIndex = start)

        assertTrue(start >= 0)
        assertTrue(end > start)
        val helper = source.substring(start, end)
        assertTrue(helper.contains("ensureVideoDecoder()"))
        assertTrue(helper.contains("ensureAudioPlayer()"))
        assertTrue(helper.contains("adoptSessionOwnership(session)"))
        assertTrue(helper.contains("bindSessionCollectors(session)"))
        assertTrue(helper.contains("Native session dependencies ready:"))

        assertEquals(
            1,
            Regex("""session\.onNativeSessionStarting\s*=\s*\{\s*prepareNativeSessionStart\(session\)\s*}""")
                .findAll(source)
                .count(),
        )

        assertTrue(
            "The dependency preparer must reject every native start while an exact session is retiring",
            helper.contains("retiringAasdkSession != null") &&
                helper.contains("return false"),
        )
        assertTrue(
            "The dependency preparer must report successful admission",
            helper.contains("return true"),
        )

        val aasdk = projectFile(
            "app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt",
        ).readText()
        assertTrue(aasdk.contains("var onNativeSessionStarting: (() -> Boolean)?"))
        assertEquals(
            "Both USB and TCP native starts must abort when their owner rejects admission",
            2,
            Regex(Regex.escape("onNativeSessionStarting?.invoke() == false"))
                .findAll(aasdk)
                .count(),
        )
        val tcpStart = aasdk.indexOf("private fun handleConnection(socket: Socket)")
        val tcpEnd = aasdk.indexOf("fun shutdownGracefully", tcpStart)
        assertTrue(tcpStart >= 0 && tcpEnd > tcpStart)
        val tcpStartBlock = aasdk.substring(tcpStart, tcpEnd)
        assertTrue(
            "WPP and direct TCP starts must share the same stop/start ownership lock",
            tcpStartBlock.indexOf("synchronized(connectionStartLock)") in
                0 until tcpStartBlock.indexOf("onNativeSessionStarting?.invoke()"),
        )
        val wppStart = aasdk.substringAfter("private fun startWpp(recovery: Boolean = false)")
            .substringBefore("var onNativeSessionStarting")
        assertTrue(
            "A socket queued by a stopped WPP server must not start after retirement clears",
            wppStart.contains("_wppServer !== server") &&
                wppStart.contains("wppSocket.close()"),
        )
    }

    private fun projectFile(relativePath: String): File {
        val workingDir = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(workingDir) { it.parentFile }
            .map { root -> File(root, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Could not locate $relativePath from $workingDir")
    }
}
