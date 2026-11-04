#!/usr/bin/env python3
"""Pure JVM packet gate, not an Android Gradle/JNI integration build."""
from pathlib import Path
import os
import subprocess
root=Path(__file__).resolve().parents[4]
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
def jar(group,name,version=None):
    p=cache/group/name
    matches=sorted((p/version if version else p).glob('**/*.jar'))
    if not matches:raise RuntimeError('missing cached jar '+str(p))
    return str(matches[-1])
compiler=[jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.1.0'),jar('org.jetbrains.kotlin','kotlin-stdlib','2.1.0'),jar('org.jetbrains.kotlin','kotlin-script-runtime','2.1.0'),jar('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),jar('org.jetbrains.intellij.deps','trove4j'),jar('org.jetbrains','annotations'),jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm')]
runtime=[compiler[1],compiler[-2],compiler[-1],jar('junit','junit','4.13.2'),jar('org.hamcrest','hamcrest-core'),jar('androidx.annotation','annotation-jvm','1.8.1')]
main=root/'app/src/main/java/com/openautolink/app'
test=root/'app/src/test/java/com/openautolink/app'
files=[main/'cluster/ClusterDisplayProfile.kt',main/'cluster/ClusterVideoGeometry.kt',main/'transport/aasdk/GalProtocolPolicy.kt',main/'transport/aasdk/AasdkSdrConfig.kt',test/'transport/aasdk/AasdkClusterVideoTest.kt']
classes=['com.openautolink.app.transport.aasdk.AasdkClusterVideoTest']
streamTest=test/'transport/aasdk/AasdkClusterVideoStreamTest.kt'
if streamTest.exists():
    files.extend([streamTest,main/'transport/aasdk/AasdkSessionCallback.kt'])
    classes.append('com.openautolink.app.transport.aasdk.AasdkClusterVideoStreamTest')
secondaryTest=test/'transport/aasdk/AasdkSecondaryVideoTest.kt'
if secondaryTest.exists():
    files.append(secondaryTest)
    classes.append('com.openautolink.app.transport.aasdk.AasdkSecondaryVideoTest')
for name in ['AasdkClusterVideoOffer.kt','AasdkClusterVideoStream.kt','AasdkSecondaryVideoOffer.kt','AasdkSecondaryVideoStreams.kt']:
    p=main/'transport/aasdk'/name
    if p.exists():files.append(p)
out=Path(os.environ['AA_TEST_OUTPUT']);out.mkdir(parents=True,exist_ok=True)
java=os.environ.get('JAVA') or str(Path(os.environ.get('JAVA_HOME',str(Path.home()/'.local/jdks/temurin-17')))/'bin/java')
if not os.access(java,os.X_OK):raise RuntimeError('JAVA/JAVA_HOME does not name an executable: '+java)
subprocess.run([java,'-Xmx768m','-cp',':'.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',':'.join(runtime),'-d',str(out/'packet.jar'),*map(str,files)],check=True)
subprocess.run([java,'-ea','-cp',':'.join([str(out/'packet.jar'),*runtime]),'org.junit.runner.JUnitCore',*classes],check=True)
