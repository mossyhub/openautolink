#!/usr/bin/env python3
"""Host-only build gates; no Android runtime/ADB/Gradle invoked."""
import hashlib,json,os,pathlib,subprocess,sys
root=pathlib.Path(__file__).resolve().parents[2]
base_evidence=root.parent/'carplay-bt-free/capture'
evidence=pathlib.Path(os.environ.get('CAPTURE_EVIDENCE_DIR',str(base_evidence)))
label=sys.argv[1]
evidence.mkdir(parents=True,exist_ok=True)
paths=sorted(pathlib.Path(__file__).parent.glob('*'))
def hashes():return {p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in paths if p.is_file()}
before=hashes()
cmd=['c++','-std=c++17','-pthread','-Wall','-Wextra','-Werror','-D_Nonnull=','-D_Nullable=','-D_Null_unspecified=','-D__INTRODUCED_IN(x)=','-I'+str(base_evidence/'include'),str(root/'tools/native-aaudio-capture/capture.cpp'),str(root/'tools/native-aaudio-capture/tests.cpp'),'-o',str(evidence/label)]
if label.startswith('asan'):cmd[1:1]=['-fsanitize=address,undefined','-fno-omit-frame-pointer']
r=subprocess.run(cmd,capture_output=True,text=True)
log=r.stdout+r.stderr
if r.returncode==0:
 r=subprocess.run([str(evidence/label)],capture_output=True,text=True,timeout=20);log+=r.stdout+r.stderr
(evidence/(label+'.log')).write_text(log)
(evidence/(label+'.json')).write_text(json.dumps({'command':cmd,'exit':r.returncode,'before':before,'after':hashes()},indent=2))
print(log,end='');print('EXIT',r.returncode);sys.exit(r.returncode)
