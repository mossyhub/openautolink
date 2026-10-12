from pathlib import Path
import subprocess,time
root=Path('/mnt/Temp/Hermes_Work/oal-carplay-bench/carplay-lab/build')
a=['/home/lance/.local/android-sdk/platform-tools/adb','-s','192.168.1.100:38277']
with (root/'pairing-attempt.log').open('wb') as output:
    logger=subprocess.Popen(a+['logcat','-v','threadtime','-T','1','OALCarPlayLab:I','AndroidRuntime:E','*:S'],stdout=output,stderr=subprocess.STDOUT)
    try:
        subprocess.run(a+['shell','input','tap','1000','790'],check=True)
        time.sleep(100)
    finally:
        logger.terminate(); logger.wait(timeout=10)
print((root/'pairing-attempt.log').read_text())
