#!/usr/bin/env python3
"""Build only JNI against retained prebuilt dependencies, never rebuild aasdk."""
from pathlib import Path
import hashlib
import os
import subprocess
root=Path(__file__).resolve().parents[4]
cpp=root/'app/src/main/cpp'
deps=Path(os.environ['AA_PREBUILT_DEPS'])
link=cpp/'third_party'
if not link.exists():link.symlink_to(deps,target_is_directory=True)
if link.resolve()!=deps.resolve():raise RuntimeError('unexpected third_party owner')
source=Path(os.environ.get('AASDK_SOURCE',str(root/'external/opencardev-aasdk')))
assert subprocess.check_output(['git','-C',str(source),'rev-parse','HEAD'],text=True).strip()=='7211802c7a2d390d2d178cfa873344e7b90c9357'
subprocess.run(['git','-C',str(source),'diff','--exit-code','HEAD','--','include','protobuf'],check=True)
sdk=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'.local/android-sdk')))
cmake=str(sdk/'cmake/3.22.1/bin/cmake')
out=Path(os.environ['AA_TEST_OUTPUT'])
for abi in ['arm64-v8a','x86_64']:
    build=out/abi
    print('BUILD JNI',abi,flush=True)
    subprocess.run([cmake,'-S',str(cpp),'-B',str(build),'-G','Ninja',
        '-DAASDK_SOURCE_DIR='+str(source),
        '-DCMAKE_MAKE_PROGRAM='+str(sdk/'cmake/3.22.1/bin/ninja'),
        '-DCMAKE_TOOLCHAIN_FILE='+str(sdk/'ndk/28.2.13676358/build/cmake/android.toolchain.cmake'),
        '-DANDROID_ABI='+abi,'-DANDROID_PLATFORM=android-32','-DANDROID_STL=c++_shared','-DCMAKE_BUILD_TYPE=Debug'],check=True)
    subprocess.run([cmake,'--build',str(build),'--target','openautolink-jni','--parallel','2'],check=True)
    binary=build/'libopenautolink-jni.so'
    print(abi,'ELF SHA256',hashlib.sha256(binary.read_bytes()).hexdigest(),flush=True)
    subprocess.run(['file',str(binary)],check=True)
print('PASS linked JNI both ABIs against retained prebuilt dependencies',flush=True)
