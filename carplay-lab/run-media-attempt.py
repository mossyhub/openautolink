from pathlib import Path
import datetime
import os
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

root = Path('/mnt/Temp/Hermes_Work/oal-carplay-bench/carplay-lab/build')
a = ['/home/lance/.local/android-sdk/platform-tools/adb', '-s', os.environ.get('OAL_ADB_SERIAL', '192.168.1.100:42151')]
# Dump stays on the tablet; only button metadata is consumed, never input values.
subprocess.run(a + ['shell', 'uiautomator', 'dump', '/sdcard/Download/oal-media-ui.xml'], check=True, stdout=subprocess.DEVNULL)
xml = subprocess.check_output(a + ['exec-out', 'dd', 'if=/sdcard/Download/oal-media-ui.xml', 'status=none'])
buttons = [n for n in ET.fromstring(xml).iter('node') if n.get('class') == 'android.widget.Button' and n.get('text') == 'START CARPLAY VIDEO + AUDIO' and n.get('enabled') == 'true']
if len(buttons) != 1:
    raise RuntimeError('Exactly one enabled media-start button required')
values = list(map(int, re.findall(r'\d+', buttons[0].get('bounds', ''))))
if len(values) != 4:
    raise RuntimeError('Invalid media-start bounds')
x1, y1, x2, y2 = values
stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
log = root / f'media-attempt-{stamp}.log'
with log.open('wb') as output:
    logger = subprocess.Popen(a + ['logcat', '-v', 'threadtime', '-T', '1', 'OALCarPlayLab:I', 'AndroidRuntime:E', '*:S'], stdout=output, stderr=subprocess.STDOUT)
    try:
        subprocess.run(a + ['shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2)], check=True)
        captureSeconds = int(os.environ.get('OAL_CAPTURE_SECONDS', '40'))
        if os.environ.get('OAL_TEST_TOUCH') == '1':
            time.sleep(8)
            subprocess.run(a + ['shell', 'input', 'tap', '405', '710'], check=True)
            time.sleep(2)
            with (root / f'touch-screen-{stamp}.png').open('wb') as image:
                subprocess.run(a + ['exec-out', 'screencap', '-p'], stdout=image, check=True)
            time.sleep(max(0, captureSeconds - 10))
        else:
            time.sleep(captureSeconds)
    finally:
        logger.terminate()
        logger.wait(timeout=10)
shutil.copyfile(log, root / 'media-attempt.log')
print(f'Capture: {log}')
for line in log.read_text().splitlines():
    if any(marker in line for marker in ('BENCH_DIAG', 'media stage=', 'IPHONE_AUTHENTICATION_ACCEPTED', 'TEST_FAILED', 'CONTROL_TX', 'FATAL EXCEPTION')):
        print(line)
with (root / f'media-screen-{stamp}.png').open('wb') as image:
    subprocess.run(a + ['exec-out', 'screencap', '-p'], stdout=image, check=True)
shutil.copyfile(root / f'media-screen-{stamp}.png', root / 'media-screen.png')
