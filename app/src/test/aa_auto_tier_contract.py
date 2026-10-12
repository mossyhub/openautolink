"""Execute the production native tier-selection block without Android JNI."""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
source = (root / 'app/src/main/cpp/jni_session.cpp').read_text()
start = source.index('          int tiers[5];')
end = source.index('          touchWidth = kDims[tiers[0]].w;', start)
block = source[start:end]
program = '#include <cassert>\nint main() {\n'
for hevc, portrait, expected in [(True, False, [4,3,2,1]), (True, True, [8,7,6]), (False,False,[3,2,1]), (False,True,[7,6])]:
    program += '{ bool useH265=' + str(hevc).lower() + '; bool portrait=' + str(portrait).lower() + ';\n' + block
    program += f'assert(tierCount=={len(expected)});\n'
    for i,tier in enumerate(expected):
        program += f'assert(tiers[{i}]=={tier});\n'
    program += '}\n'
program += '}\n'
with tempfile.TemporaryDirectory(prefix='aa-tier-', dir=os.environ.get('TMPDIR')) as directory:
    src = Path(directory) / 'tiers.cpp'
    exe = Path(directory) / 'tiers'
    src.write_text(program)
    subprocess.run(['c++', '-std=c++17', str(src), '-o', str(exe)], check=True)
    subprocess.run([str(exe)], check=True)
print('PASS: production native Auto tier ordering, both codecs/orientations; 4K excluded from Auto')
