"""Source guards only; JUnit/device verification remains parent-owned."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1] / 'main/java'
class MicrophoneSourceGuard(unittest.TestCase):
    def test_genuine_encoder_and_uplink_exist(self):
        encoder = ROOT / 'com/shilapi/xcertplay/media/OpusEncoder.kt'
        self.assertTrue(encoder.is_file(), 'compressed microphone encoder is missing')
        text = encoder.read_text()
        self.assertIn('CONFIGURE_FLAG_ENCODE', text)
        self.assertIn('isEncoder', text)
        self.assertNotIn('Log.', text)
        self.assertNotIn('toHexString', text)
        uplink = (ROOT / 'com/shilapi/xcertplay/media/MicrophoneUplink.kt').read_text()
        self.assertIn('AudioRecord.READ_BLOCKING', uplink)
        self.assertIn('VOICE_COMMUNICATION', uplink)
        self.assertIn('VOICE_RECOGNITION', uplink)
        self.assertIn('MicrophonePacketizer.sealPacket', uplink)
        self.assertIn('isRetired', uplink)
        self.assertIn('ArrayBlockingQueue<OpusEncoder.Frame>(4)', uplink)
        self.assertNotIn('MediaRecorder.AudioSource.', uplink, 'Actual AudioRecord source wiring belongs in the capability helper')
        engine = (ROOT / 'com/shilapi/xcertplay/airplay/CarPlayMediaEngine.kt').read_text()
        self.assertIn('generation: Long', engine)
        sink = (ROOT / 'com/shilapi/xcertplay/media/AndroidMediaSink.kt').read_text()
        self.assertIn('MicrophoneOwnerSlot<MicrophoneStreamId, MicrophoneUplink>', sink)
        info = (ROOT / 'com/shilapi/xcertplay/airplay/AirPlayInfoPlist.kt').read_text()
        self.assertNotIn('listOf("vocoderInfo"', info)
if __name__ == '__main__':
    unittest.main()
