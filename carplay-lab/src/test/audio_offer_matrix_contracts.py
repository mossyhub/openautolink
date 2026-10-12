"""No-build source-contract discriminator; not a replacement for parent JUnit/Gradle.

Evaluate only literal/bitwise row masks extracted from the real Kotlin catalog.
Do not simulate Android codec discovery or claim a decoder/device test.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1] / "main/java/com/shilapi/xcertplay/media"


def literal_mask(expression, constants):
    result = 0
    for term in expression.split(" or "):
        term = term.strip()
        result |= constants[term] if term in constants else int(term.removesuffix("L"), 0)
    return result


def catalog(available):
    source = (ROOT / "AudioOutputSupport.kt").read_text()
    implemented = {int(value, 16) for value in re.findall(r"(0x[0-9a-f]+)L to Spec", source)}
    body = source.split("fun wirelessRows(", 1)[1]
    constants = {name: int(value, 16) for name, value in
                 re.findall(r"val (\w+) = (0x[0-9a-f]+)L", body)}
    rows = []
    for stream, kind, expression in re.findall(r'row\((\d+),"([^"]+)",([^\n]+)\),', body):
        candidates = literal_mask(expression, constants)
        mask = 0
        for bit in available & implemented:
            mask |= bit & candidates
        if mask:
            rows.append((int(stream), kind, mask))
    return rows


class AudioOfferMatrixContracts(unittest.TestCase):
    def test_mixed_pcm_and_compressed_catalog(self):
        pcm = {4,8,16,32,64,128,256,512,1024,2048,16384,32768}
        available = pcm | {0x20000000,0x40000000,0x4000000,0x2000000,0x800000,
                           0x1000,0x2000,1 << 32}
        self.assertEqual([
            (100,"compatibility",0xc3fc),(101,"compatibility",0xc3fc),
            (100,"default",0x6000c3fc),(100,"alert",0x6000c3fc),(100,"media",0xc3fc),
            (100,"telephony",0x60004154),(100,"speechRecognition",0x60004154),
            (101,"default",0x6000c3fc),(102,"media",0x800000),
        ], catalog(available))

    def test_admission_uses_catalog_for_pcm_too(self):
        source = (ROOT / "AudioOutputSupport.kt").read_text()
        admission = source.split("fun negotiate(", 1)[1].split("fun withSamplesPerFrame(", 1)[0]
        self.assertIn("wirelessRows(implementedBits).none", admission)
        self.assertNotIn("format.codec != AudioCodecKind.LPCM && wirelessRows", admission)

    def test_inventory_includes_implemented_pcm_without_codec_enumeration(self):
        source = (ROOT / "AndroidAudioDecoderSupport.kt").read_text()
        self.assertIn("AudioOutputSupport.implementedPcmBits + decoders.keys", source)


if __name__ == "__main__":
    unittest.main()
