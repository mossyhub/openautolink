"""No-build integration/privacy guard; JVM behavior tests run by parent Gradle."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1] / 'main/java'
class MediaContracts(unittest.TestCase):
    def test_receiver_reuses_existing_identity(self):
        source = (ROOT / 'com/openautolink/carplaylab/media/ProjectionReceiver.kt').read_text()
        self.assertIn('PairingPersistence(File(context.noBackupFilesDir, "pairing-probe"))', source)
        self.assertNotIn('AirPlayIdentity.generate', source)
        self.assertIn('Proven pairing state required', source)
    def test_session_does_not_export_raw_diagnostics(self):
        source = (ROOT / 'com/shilapi/xcertplay/airplay/AirPlaySession.kt').read_text()
        for token in ('bodyHex=', 'wireHex=', 'payload=$', 'headers=${', 'request=$requestInfo', 'report.toHexString'):
            self.assertNotIn(token, source)
        self.assertIn('MAX_CONTROL_BYTES', source)
        self.assertIn('peerAddress != socket.inetAddress', source)
    def test_every_stream_filters_peer(self):
        for name in ('ScreenStream', 'AudioStream', 'IapTunnel', 'NtpClock'):
            self.assertIn('expectedPeer', (ROOT / f'com/shilapi/xcertplay/airplay/{name}.kt').read_text())
    def test_audio_offer_is_transport_specific(self):
        source = (ROOT / 'com/shilapi/xcertplay/airplay/AirPlayInfoPlist.kt').read_text()
        self.assertIn('config.lpcmOnly', source)
        self.assertIn('AudioOutputSupport.wirelessRows(availableAudioFormats)', source)
        self.assertIn('MicrophoneSupport.addInputOffers', source)
        self.assertIn('config.microphone && !config.lpcmOnly', source)
        self.assertIn('MicrophoneCapability.NONE', source)
        self.assertNotIn('listOf("vocoderInfo"', source)
    def test_android_sink_has_real_proof_callbacks(self):
        source = (ROOT / 'com/shilapi/xcertplay/media/AndroidMediaSink.kt').read_text()
        for token in ('setOnFrameRenderedListener', 'AudioTrack.WRITE_NON_BLOCKING', 'requestAudioFocus', 'abandonAudioFocusRequest'):
            self.assertIn(token, source)
if __name__ == '__main__': unittest.main()
