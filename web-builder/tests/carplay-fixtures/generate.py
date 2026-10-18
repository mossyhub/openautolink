"""Generate fresh synthetic credentials ONLY in a caller-owned TMPDIR directory.

No owner files are read. No key bytes are logged. The JS test removes all output.
Run with CARPLAY_FIXTURE_PYTHON if the default verification venv is unavailable.
"""
import datetime as dt
import os
from pathlib import Path
import sys

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization as s
from cryptography.hazmat.primitives.asymmetric import ec, rsa
from cryptography.hazmat.primitives.serialization import pkcs7
from cryptography.x509.oid import NameOID

root = Path(sys.argv[1]).resolve()
tmp = Path(os.environ['TMPDIR']).resolve()
if not root.is_relative_to(tmp) or root == tmp or not root.is_dir():
    raise SystemExit('Fixtures require an existing child directory of TMPDIR')
os.umask(0o077)
name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'Synthetic test only')])
issuer = rsa.generate_private_key(public_exponent=65537, key_size=2048)

def certificate(key, padding=None):
    builder = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
               .public_key(key.public_key()).serial_number(1)
               .not_valid_before(dt.datetime(2000, 1, 1, tzinfo=dt.timezone.utc))
               .not_valid_after(dt.datetime(2001, 1, 1, tzinfo=dt.timezone.utc)))
    if padding is not None:
        builder = builder.add_extension(x509.UnrecognizedExtension(x509.ObjectIdentifier('1.2.3.4.5'), b'x' * padding), False)
    return builder.sign(issuer, hashes.SHA256())

def write(name, data):
    (root / name).write_bytes(data)

for label, key in [('p256', ec.generate_private_key(ec.SECP256R1())),
                   ('other', ec.generate_private_key(ec.SECP256R1())),
                   ('p384', ec.generate_private_key(ec.SECP384R1())), ('rsa', issuer)]:
    cert = certificate(key)
    write(label + '.pk8', key.private_bytes(s.Encoding.DER, s.PrivateFormat.PKCS8, s.NoEncryption()))
    write(label + '.der', cert.public_bytes(s.Encoding.DER))
    if label == 'p256':
        write('single.p7b', pkcs7.serialize_certificates([cert], s.Encoding.DER))
        write('multiple.p7b', pkcs7.serialize_certificates([cert, certificate(issuer)], s.Encoding.DER))
        write('sec1.der', key.private_bytes(s.Encoding.DER, s.PrivateFormat.TraditionalOpenSSL, s.NoEncryption()))
        write('encrypted.der', key.private_bytes(s.Encoding.DER, s.PrivateFormat.PKCS8, s.BestAvailableEncryption(b'synthetic-test-only')))
        write('key.pem', key.private_bytes(s.Encoding.PEM, s.PrivateFormat.PKCS8, s.NoEncryption()))
        write('certificate.pem', cert.public_bytes(s.Encoding.PEM))
        padding = 15000
        for _ in range(4):
            padded = certificate(key, padding).public_bytes(s.Encoding.DER)
            if len(padded) == 16384:
                break
            padding += 16384 - len(padded)
        assert len(padded) == 16384
        write('max.der', padded)
