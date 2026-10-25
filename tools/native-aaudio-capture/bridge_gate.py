#!/usr/bin/env python3
"""Real host JVM -> verbatim JNI bridge -> production Runner + synthetic public AAudio boundary."""
import pathlib,subprocess,json,hashlib,os
r=pathlib.Path(__file__).resolve().parents[2]
e=pathlib.Path(os.environ['CAPTURE_EVIDENCE_DIR'])/'jni-host';e.mkdir(parents=True,exist_ok=True)
jdk=pathlib.Path('/home/lance/.local/jdks/temurin-17')
inputs=[r/'tools/native-aaudio-capture/capture.cpp',r/'tools/native-aaudio-capture/capture.h',r/'tools/native-aaudio-capture/tests.cpp',r/'app/src/main/cpp/parked_capture_jni.cpp',pathlib.Path(__file__)]
def hashes():return {str(p.relative_to(r)):hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}
before=hashes()
java='''package com.openautolink.app.ui.diagnostics;
public class NativeAAudioRun {
 private volatile boolean allowed=true;
 public boolean eligible(){return allowed;}
 private native long nativeCreate(); private native void nativeBegin(long h);
 private native void nativeCancel(long h); private native long[] nativePoll(long h);
 private native void nativeDispose(long h); private static native void fixture(int phase);
 private static void check(boolean value){if(!value)throw new AssertionError("JNI capture contract");}
 private long[] await(long h)throws Exception {long end=System.nanoTime()+3000000000L;long[] a;while((a=nativePoll(h))==null){check(System.nanoTime()<end);Thread.sleep(1);}return a;}
 public static void main(String[] args)throws Exception {
  System.load(args[0]);
  NativeAAudioRun r=new NativeAAudioRun();fixture(0);
  long h=r.nativeCreate();r.nativeBegin(h);long[] a=r.await(h);
  check(a.length==32&&a[0]==1&&a[19]==96000&&a[21]==96000&&a[26]==96000&&a[6]==1&&a[7]==1&&a[18]==-1);r.nativeDispose(h);
  System.out.println("PASS JNI positive aggregate + actual close/join/dispose");
  fixture(0);r.allowed=false;h=r.nativeCreate();r.nativeBegin(h);a=r.await(h);
  check(a[1]==2&&a[19]==0&&a[6]==1&&a[7]==1);r.nativeDispose(h);r.allowed=true;
  System.out.println("PASS JNI pre-effect policy revocation");
  fixture(1);h=r.nativeCreate();r.nativeBegin(h);fixture(2);
  check(r.nativePoll(h)==null);r.nativeCancel(h);check(r.nativePoll(h)==null);
  boolean rejected=false;try{r.nativeDispose(h);}catch(IllegalStateException x){rejected=true;}check(rejected);
  fixture(3);a=r.await(h);check(a[6]==1&&a[7]==1);r.nativeDispose(h);
  System.out.println("PASS JNI held close stays pending, no premature delete");
  fixture(4);h=r.nativeCreate();r.nativeBegin(h);a=r.await(h);
  check(a[4]!=0&&a[5]==1&&a[6]==1&&a[7]==0);
  rejected=false;try{r.nativeDispose(h);}catch(IllegalStateException x){rejected=true;}check(rejected);
  // Exact failed owner is deliberately retained, never retried or deleted.
  System.out.println("PASS JNI arbitrary failed-close tombstone, no disposal retry");
  System.out.println("4 JNI tests, 0 failures");
 }
}'''
j=e/'NativeAAudioRun.java';j.write_text(java)
f=e/'fixture.cpp';f.write_text('#include <jni.h>\n#define main capture_fixture_unused_main\n#include "'+str(r/'tools/native-aaudio-capture/tests.cpp')+'"\n#undef main\nextern "C" JNIEXPORT void JNICALL Java_com_openautolink_app_ui_diagnostics_NativeAAudioRun_fixture(JNIEnv*,jclass,jint phase){\n if(phase==0)resetFake();\n if(phase==1){resetFake();holdClose=true;}\n if(phase==2)waitFlag(closeEntered);\n if(phase==3){{std::lock_guard<std::mutex> l(fakeMutex);releaseClose=true;}fakeCondition.notify_all();}\n if(phase==4){resetFake();closeError=AAUDIO_ERROR_INTERNAL;}\n}\n')
lib=e/'liboal-parked-capture.so';base=r.parent/'carplay-bt-free/capture'
commands=[['c++','-std=c++17','-pthread','-shared','-fPIC','-Wall','-Wextra','-Werror','-D_Nonnull=','-D_Nullable=','-D_Null_unspecified=','-D__INTRODUCED_IN(x)=','-I'+str(base/'include'),'-I'+str(jdk/'include'),'-I'+str(jdk/'include/linux'),str(r/'tools/native-aaudio-capture/capture.cpp'),str(r/'app/src/main/cpp/parked_capture_jni.cpp'),str(f),'-o',str(lib)],[str(jdk/'bin/javac'),'-d',str(e),str(j)],[str(jdk/'bin/java'),'-cp',str(e),'com.openautolink.app.ui.diagnostics.NativeAAudioRun',str(lib)]]
rows=[]
for cmd in commands:
 p=subprocess.run(cmd,capture_output=True,text=True,timeout=60);rows.append(dict(command=cmd,exit=p.returncode,output=p.stdout+p.stderr));print(p.stdout+p.stderr,end='')
 if p.returncode:break
report=dict(before=before,after=hashes(),commands=rows,fixture_sha256=hashlib.sha256(f.read_bytes()).hexdigest(),java_sha256=hashlib.sha256(j.read_bytes()).hexdigest())
(e/'receipt.json').write_text(json.dumps(report,indent=2))
raise SystemExit(0 if len(rows)==3 and all(x['exit']==0 for x in rows) and before==report['after'] else 1)
