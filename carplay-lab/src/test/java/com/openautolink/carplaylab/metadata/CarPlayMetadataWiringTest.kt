package com.openautolink.carplaylab.metadata

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Production bridge contract: no second control consumer, metadata dispatch on existing runtime loop. */
class CarPlayMetadataWiringTest {
    @Test fun exactAuthenticatedRuntimeOwnsMetadataAndArtworkDispatch() {
        val source = File("src/main/java/com/openautolink/carplaylab/media/ProjectionReceiver.kt").readText()
        assertTrue(source.contains("runtimeMetadataFactory"))
        assertTrue(source.contains("metadata?.authenticate()"))
        assertTrue(source.contains("metadata?.handle(frame)"))
        assertTrue(source.contains("metadata?.tick()"))
        assertTrue(source.contains("runtimeMetadata[session]?.offerArtwork"))
        assertTrue(source.contains("runtimeMetadata.values.forEach { it.revoke() }"))
        assertTrue(source.indexOf("metadata?.authenticate()") > source.indexOf("Iap2MfiAuthenticationClient(mfi).run(channel"))
        assertTrue(source.indexOf("metadata?.authenticate()") < source.indexOf("Iap2ControlMessages.subscriptions().forEach { channel.send(it) }"))
    }
}
