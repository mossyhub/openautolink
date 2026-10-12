package com.openautolink.app.carplay

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CarPlayConnectionLoggingWiringTest {
    @Test fun setupFailuresAndEndpointRepliesReachSanitizedRuntimeSink() {
        val source = File("src/main/java/com/openautolink/app/carplay/OalCarPlayRuntime.kt").readText()
        assertTrue("last setup stage must survive failure", source.contains("CarPlay connection stage=$"))
        assertTrue(source.contains("reason=${'$'}reason"))
        assertTrue(source.contains("CarPlay endpoint reply id=${'$'}{frame.messageId} result=sent"))
        assertTrue(source.contains("connectionStage(\"permissions\")"))
        assertTrue(source.contains("connectionStage(\"receiver-identity\")"))
        assertTrue(source.contains("connectionStage(\"listener\")"))
    }
}
