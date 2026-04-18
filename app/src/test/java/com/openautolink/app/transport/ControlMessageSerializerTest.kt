package com.openautolink.app.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlMessageSerializerTest {

    // --- Deserialization: Bridge → App ---

    @Test
    fun `deserialize hello message`() {
        val json = """{"type":"hello","version":1,"name":"OpenAutoLink","capabilities":["h264","h265","vp9"],"video_port":5290,"audio_port":5289,"protocol_version":1,"min_protocol_version":1,"bridge_version":"0.1.54","bridge_sha256":"abc123","build_source":"github"}"""
        val msg = ControlMessageSerializer.deserialize(json)

        assertTrue(msg is ControlMessage.Hello)
        val hello = msg as ControlMessage.Hello
        assertEquals(1, hello.version)
        assertEquals("OpenAutoLink", hello.name)
        assertEquals(listOf("h264", "h265", "vp9"), hello.capabilities)
        assertEquals(5290, hello.videoPort)
        assertEquals(5289, hello.audioPort)
        assertEquals(1, hello.protocolVersion)
        assertEquals(1, hello.minProtocolVersion)
        assertEquals("0.1.54", hello.bridgeVersion)
        assertEquals("abc123", hello.bridgeSha256)
        assertEquals("github", hello.buildSource)
    }

    @Test
    fun `deserialize phone_connected message`() {
        val json = """{"type":"phone_connected","phone_name":"Pixel 10","phone_type":"android"}"""
        val msg = ControlMessageSerializer.deserialize(json)

        assertTrue(msg is ControlMessage.PhoneConnected)
        val pc = msg as ControlMessage.PhoneConnected
        assertEquals("Pixel 10", pc.phoneName)
        assertEquals("android", pc.phoneType)
    }

    @Test
    fun `deserialize phone_disconnected message`() {
        val json = """{"type":"phone_disconnected","reason":"user_disconnect"}"""
        val msg = ControlMessageSerializer.deserialize(json)

        assertTrue(msg is ControlMessage.PhoneDisconnected)
        assertEquals("user_disconnect", (msg as ControlMessage.PhoneDisconnected).reason)
    }

    @Test
    fun `deserialize audio_start message`() {
        val json = """{"type":"audio_start","purpose":"media","sample_rate":48000,"channels":2}"""
        val msg = ControlMessageSerializer.deserialize(json)

        assertTrue(msg is ControlMessage.AudioStart)
        val as_ = msg as ControlMessage.AudioStart
        assertEquals(AudioPurpose.MEDIA, as_.purpose)
        assertEquals(48000, as_.sampleRate)
        assertEquals(2, as_.channels)
    }

    @Test
    fun `deserialize audio_stop message`() {
        val json = """{"type":"audio_stop","purpose":"navigation"}"""
        val msg = ControlMessageSerializer.deserialize(json)

        assertTrue(msg is ControlMessage.AudioStop)
        assertEquals(AudioPurpose.NAVIGATION, (msg as ControlMessage.AudioStop).purpose)
    }

    @Test
    fun `deserialize mic_start message`() {
        val json = """{"type":"mic_start","sample_rate":16000}"""
        val msg = ControlMessageSerializer.deserialize(json)

        assertTrue(msg is ControlMessage.MicStart)
        assertEquals(16000, (msg as ControlMessage.MicStart).sampleRate)
    }

    @Test
    fun `deserialize mic_stop message`() {
        val json = """{"type":"mic_stop"}"""
        val msg = ControlMessageSerializer.deserialize(json)
        assertTrue(msg is ControlMessage.MicStop)
    }

    @Test
    fun `deserialize nav_state message`() {
        val json = """{"type":"nav_state","maneuver":"turn_right","distance_meters":150,"road":"Main St","eta_seconds":420}"""
        val msg = ControlMessageSerializer.deserialize(json)

        assertTrue(msg is ControlMessage.NavState)
        val ns = msg as ControlMessage.NavState
        assertEquals("turn_right", ns.maneuver)
        assertEquals(150, ns.distanceMeters)
        assertEquals("Main St", ns.road)
        assertEquals(420, ns.etaSeconds)
    }

    @Test
    fun `deserialize media_metadata message`() {
        val json = """{"type":"media_metadata","title":"Song","artist":"Artist","album":"Album","duration_ms":240000,"position_ms":60000,"playing":true}"""
        val msg = ControlMessageSerializer.deserialize(json)

        assertTrue(msg is ControlMessage.MediaMetadata)
        val mm = msg as ControlMessage.MediaMetadata
        assertEquals("Song", mm.title)
        assertEquals("Artist", mm.artist)
        assertEquals(true, mm.playing)
    }

    @Test
    fun `deserialize error message`() {
        val json = """{"type":"error","code":100,"message":"Phone connection lost"}"""
        val msg = ControlMessageSerializer.deserialize(json)

        assertTrue(msg is ControlMessage.Error)
        val err = msg as ControlMessage.Error
        assertEquals(100, err.code)
        assertEquals("Phone connection lost", err.message)
    }

    @Test
    fun `deserialize config_echo message`() {
        val json = """{"type":"config_echo","video_codec":"h264","video_width":"1920"}"""
        val msg = ControlMessageSerializer.deserialize(json)

        assertTrue(msg is ControlMessage.ConfigEcho)
        val cfg = msg as ControlMessage.ConfigEcho
        assertEquals("h264", cfg.config["video_codec"])
        assertEquals("1920", cfg.config["video_width"])
    }

    @Test
    fun `deserialize stats message`() {
        val json = """{"type":"stats","video_frames_sent":1200,"audio_frames_sent":3400,"uptime_seconds":120}"""
        val msg = ControlMessageSerializer.deserialize(json)

        assertTrue(msg is ControlMessage.Stats)
        val stats = msg as ControlMessage.Stats
        assertEquals(1200L, stats.videoFramesSent)
        assertEquals(3400L, stats.audioFramesSent)
        assertEquals(120L, stats.uptimeSeconds)
    }

    @Test
    fun `deserialize unknown type returns null`() {
        val json = """{"type":"unknown_future_type","data":"something"}"""
        assertNull(ControlMessageSerializer.deserialize(json))
    }

    @Test
    fun `deserialize invalid JSON returns null`() {
        assertNull(ControlMessageSerializer.deserialize("not json"))
        assertNull(ControlMessageSerializer.deserialize(""))
        assertNull(ControlMessageSerializer.deserialize("{"))
    }

    @Test
    fun `deserialize missing type returns null`() {
        assertNull(ControlMessageSerializer.deserialize("""{"version":1}"""))
    }

    @Test
    fun `deserialize audio_start with invalid purpose returns null`() {
        val json = """{"type":"audio_start","purpose":"unknown","sample_rate":48000,"channels":2}"""
        assertNull(ControlMessageSerializer.deserialize(json))
    }

    @Test
    fun `deserialize hello with missing optional fields uses defaults`() {
        val json = """{"type":"hello","version":1,"name":"Bridge"}"""
        val msg = ControlMessageSerializer.deserialize(json) as ControlMessage.Hello
        assertEquals(emptyList<String>(), msg.capabilities)
        assertEquals(5290, msg.videoPort)
        assertEquals(5289, msg.audioPort)
        assertNull(msg.protocolVersion)
        assertNull(msg.minProtocolVersion)
        assertNull(msg.buildSource)
    }

    // --- Serialization: App → Bridge ---

    @Test
    fun `serialize app hello includes protocol versions`() {
        val msg = ControlMessage.AppHello(1, "OpenAutoLink App", 2628, 800, 160)
        val json = ControlMessageSerializer.serialize(msg)

        assertTrue(json.contains(""""type":"hello""""))
        assertTrue(json.contains(""""display_width":2628"""))
        assertTrue(json.contains(""""display_height":800"""))
        assertTrue(json.contains(""""protocol_version":${ControlMessage.PROTOCOL_VERSION}"""))
        assertTrue(json.contains(""""min_protocol_version":${ControlMessage.MIN_PROTOCOL_VERSION}"""))
    }

    @Test
    fun `serialize single touch`() {
        val msg = ControlMessage.Touch(0, 500f, 300f, 0, null)
        val json = ControlMessageSerializer.serialize(msg)

        assertTrue(json.contains(""""type":"touch""""))
        assertTrue(json.contains(""""action":0"""))
        assertTrue(json.contains(""""x":500"""))
    }

    @Test
    fun `serialize multi-touch with pointers`() {
        val msg = ControlMessage.Touch(
            action = 2,
            x = null,
            y = null,
            pointerId = null,
            pointers = listOf(
                ControlMessage.Pointer(0, 100f, 200f),
                ControlMessage.Pointer(1, 300f, 400f)
            )
        )
        val json = ControlMessageSerializer.serialize(msg)

        assertTrue(json.contains(""""pointers""""))
        assertTrue(json.contains(""""id":0"""))
        assertTrue(json.contains(""""id":1"""))
    }

    @Test
    fun `serialize keyframe_request`() {
        val json = ControlMessageSerializer.serialize(ControlMessage.KeyframeRequest)
        assertTrue(json.contains(""""type":"keyframe_request""""))
    }

    @Test
    fun `serialize gnss`() {
        val nmea = "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A"
        val json = ControlMessageSerializer.serialize(ControlMessage.Gnss(nmea))
        assertTrue(json.contains(""""type":"gnss""""))
        assertTrue(json.contains("GPRMC"))
    }

    @Test
    fun `serialize config_update`() {
        val msg = ControlMessage.ConfigUpdate(mapOf("video_codec" to "h265", "video_fps" to "30"))
        val json = ControlMessageSerializer.serialize(msg)

        assertTrue(json.contains(""""type":"config_update""""))
        assertTrue(json.contains(""""video_codec":"h265""""))
    }

    @Test
    fun `serialize button`() {
        val msg = ControlMessage.Button(keycode = 87, down = true, metastate = 0, longpress = false)
        val json = ControlMessageSerializer.serialize(msg)

        assertTrue(json.contains(""""type":"button""""))
        assertTrue(json.contains(""""keycode":87"""))
        assertTrue(json.contains(""""down":true"""))
        assertTrue(json.contains(""""metastate":0"""))
        assertTrue(json.contains(""""longpress":false"""))
    }

    @Test
    fun `serialize button with longpress`() {
        val msg = ControlMessage.Button(keycode = 84, down = true, metastate = 0, longpress = true)
        val json = ControlMessageSerializer.serialize(msg)

        assertTrue(json.contains(""""keycode":84"""))
        assertTrue(json.contains(""""longpress":true"""))
    }

    // --- Round-trip ---

    @Test
    fun `round-trip app hello through serialize then deserialize`() {
        val original = ControlMessage.AppHello(1, "Test App", 1920, 1080, 240)
        val json = ControlMessageSerializer.serialize(original)
        val parsed = ControlMessageSerializer.deserialize(json)

        assertTrue(parsed is ControlMessage.Hello)
        val hello = parsed as ControlMessage.Hello
        assertEquals(1, hello.version)
        assertEquals("Test App", hello.name)
    }

    // --- Multi-phone support ---

    @Test
    fun `deserialize paired_phones message`() {
        val json = """{"type":"paired_phones","phones":[{"mac":"AA:BB:CC:DD:EE:FF","name":"Pixel 10","connected":true},{"mac":"11:22:33:44:55:66","name":"iPhone 15","connected":false}]}"""
        val msg = ControlMessageSerializer.deserialize(json)

        assertTrue(msg is ControlMessage.PairedPhones)
        val pp = msg as ControlMessage.PairedPhones
        assertEquals(2, pp.phones.size)
        assertEquals("AA:BB:CC:DD:EE:FF", pp.phones[0].mac)
        assertEquals("Pixel 10", pp.phones[0].name)
        assertTrue(pp.phones[0].connected)
        assertEquals("11:22:33:44:55:66", pp.phones[1].mac)
        assertEquals("iPhone 15", pp.phones[1].name)
        assertFalse(pp.phones[1].connected)
    }

    @Test
    fun `deserialize paired_phones empty list`() {
        val json = """{"type":"paired_phones","phones":[]}"""
        val msg = ControlMessageSerializer.deserialize(json)

        assertTrue(msg is ControlMessage.PairedPhones)
        assertEquals(0, (msg as ControlMessage.PairedPhones).phones.size)
    }

    @Test
    fun `serialize list_paired_phones`() {
        val json = ControlMessageSerializer.serialize(ControlMessage.ListPairedPhones)
        assertTrue(json.contains(""""type":"list_paired_phones""""))
    }

    @Test
    fun `serialize switch_phone`() {
        val msg = ControlMessage.SwitchPhone("AA:BB:CC:DD:EE:FF")
        val json = ControlMessageSerializer.serialize(msg)

        assertTrue(json.contains(""""type":"switch_phone""""))
        assertTrue(json.contains(""""mac":"AA:BB:CC:DD:EE:FF""""))
    }

    // --- Protocol version ---

    @Test
    fun `deserialize hello with protocol version fields`() {
        val json = """{"type":"hello","version":1,"name":"Bridge","protocol_version":2,"min_protocol_version":1,"build_source":"local"}"""
        val msg = ControlMessageSerializer.deserialize(json) as ControlMessage.Hello
        assertEquals(2, msg.protocolVersion)
        assertEquals(1, msg.minProtocolVersion)
        assertEquals("local", msg.buildSource)
    }

    @Test
    fun `deserialize nav_state with destination details`() {
        val json = """{"type":"nav_state","maneuver":"turn_right","distance_meters":150,"road":"Main St","eta_seconds":30,"destination":"123 Elm St","eta_formatted":"2:45 PM","time_to_arrival_seconds":1800,"dest_distance_meters":25000,"dest_distance_display":"15.5","dest_distance_unit":"miles"}"""
        val msg = ControlMessageSerializer.deserialize(json)

        assertTrue(msg is ControlMessage.NavState)
        val ns = msg as ControlMessage.NavState
        assertEquals("turn_right", ns.maneuver)
        assertEquals(150, ns.distanceMeters)
        assertEquals("123 Elm St", ns.destination)
        assertEquals("2:45 PM", ns.etaFormatted)
        assertEquals(1800L, ns.timeToArrivalSeconds)
        assertEquals(25000, ns.destDistanceMeters)
        assertEquals("15.5", ns.destDistanceDisplay)
        assertEquals("miles", ns.destDistanceUnit)
    }

    @Test
    fun `deserialize nav_state without destination details has null fields`() {
        val json = """{"type":"nav_state","maneuver":"straight","distance_meters":500,"road":"Highway 101","eta_seconds":60}"""
        val msg = ControlMessageSerializer.deserialize(json) as ControlMessage.NavState

        assertNull(msg.destination)
        assertNull(msg.etaFormatted)
        assertNull(msg.timeToArrivalSeconds)
        assertNull(msg.destDistanceMeters)
        assertNull(msg.destDistanceDisplay)
        assertNull(msg.destDistanceUnit)
    }
}
