#!/usr/bin/env python3
"""Compile/link only: never run Android library or contact a device."""
import hashlib,json,os,pathlib,subprocess
root=pathlib.Path(__file__).resolve().parents[2]
out=pathlib.Path(os.environ.get('CAPTURE_EVIDENCE_DIR',str(root.parent/'carplay-bt-free/capture')))
out.mkdir(parents=True,exist_ok=True)
ndk=pathlib.Path('/home/lance/.local/android-sdk/ndk/28.2.13676358')
llvm=ndk/'toolchains/llvm/prebuilt/linux-x86_64'
source=root/'tools/native-aaudio-capture/capture.cpp'
paths=[source,source.with_name('capture.h')]
def hashes():return {p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
results=[]
for arch in ['aarch64','x86_64']:
 for api in [32,34]:
  lib=out/f'libcapture-spike-{arch}-api{api}.so'
  command=[str(llvm/'bin'/f'{arch}-linux-android{api}-clang++'),'-std=c++17','-pthread','-fPIC','-shared','-Wall','-Wextra','-Werror','-Wl,--no-undefined',str(source),'-laaudio','-o',str(lib)]
  before=hashes();r=subprocess.run(command,capture_output=True,text=True)
  result={'arch':arch,'api':api,'command':command,'exit':r.returncode,'output':r.stdout+r.stderr,'before':before,'after':hashes()}
  if r.returncode==0:
   result['sha256']=hashlib.sha256(lib.read_bytes()).hexdigest()
   rd=subprocess.run([str(llvm/'bin/llvm-readelf'),'--dyn-syms','--needed-libs',str(lib)],capture_output=True,text=True)
   result['readelf_exit']=rd.returncode
   (out/(lib.name+'.readelf.txt')).write_text(rd.stdout+rd.stderr)
   result['aaudio_imports']=sorted(set(l.split()[-1].split('@')[0] for l in rd.stdout.splitlines() if 'UND' in l and 'AAudio' in l))
  results.append(result)
  print(arch,api,'exit',r.returncode,result['output'])
(out/'cross-compile.json').write_text(json.dumps(results,indent=2))
assert len(results)==4 and all(r['exit']==0 and r.get('readelf_exit')==0 and r['before']==r['after'] for r in results)
