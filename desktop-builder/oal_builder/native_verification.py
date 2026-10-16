"""Audited additive verification data for the immutable v0.1.526 source.

Provenance: artifacts fetched with certificate-validated HTTPS from Google's
Maven repository and Maven Central, SHA-256 computed over complete bytes.
Google's published .sha256 sidecars matched the two AAPT2 hashes. Maven Central
publishes .sha1 (not .sha256) for these protoc artifacts; those sidecars were
also checked against complete bytes. No mirror, trust wildcard or runtime
checksum generation is used. URLs and sidecar values are retained for review.
"""

SOURCE_REF = 'v0.1.526'
SOURCE_COMMIT = '580a68d772afc97e444019504178f1678731feb6'
OVERLAY_ID = 'native-gradle-verification-v0.1.526'

# group, name, version, authoritative repository, classifier SHA-256 and SHA-1
COMPONENTS = (
    ('com.android.tools.build', 'aapt2', '8.7.3-12006047',
     'https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/8.7.3-12006047/', (
         ('aapt2-8.7.3-12006047-windows.jar', '9406af8e4742da01e4ce65773e23bda40ed5f599917b38cacc261c9ba0952331', 'ba2ef9c808e169a4e8366bda8e7d3205a71dc42d'),
         ('aapt2-8.7.3-12006047-osx.jar', '8cfca8d3fcb8ad45cf46e3780cdd4a64cb38c70ca45b442655de9f3e7d19efc1', '21569692070e0c369ed147400f9ddd418e5d82c1'),
     )),
    ('com.google.protobuf', 'protoc', '4.29.3',
     'https://repo.maven.apache.org/maven2/com/google/protobuf/protoc/4.29.3/', (
         ('protoc-4.29.3-windows-x86_64.exe', 'daea6bfd3d3304665835c694ceeab304e4e0c3ee52d653428fdcde3b6e4d19e1', '52d8dfdda8e12071695275a07f51a3277f284556'),
         ('protoc-4.29.3-osx-x86_64.exe', '461e34e09b392b7f5ce6696b1b5387112ac56ccc44088c599dc430905fa35e47', '10383c6ee04373d9edb2d858b21d015308df8c21'),
         ('protoc-4.29.3-osx-aarch_64.exe', 'bdc43e54153911f625a50baf7d4f6bc05008712964141baedd5a61122f93a016', '489e8764bc1b939a2b93ac53860ea1df235eecc2'),
     )),
)
