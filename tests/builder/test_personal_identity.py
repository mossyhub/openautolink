"""Personalization regressions independent of the desktop dependencies."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ANDROID = '{http://schemas.android.com/apk/res/android}'


class PersonalIdentityTests(unittest.TestCase):
    def test_cluster_task_is_scoped_to_personal_application_id(self):
        manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml')
        activity = next(a for a in manifest.findall('./application/activity')
                        if a.get(ANDROID + 'name') == 'androidx.car.app.activity.CarAppActivity')
        self.assertEqual('${applicationId}.templates', activity.get(ANDROID + 'taskAffinity'))

    def test_noninteractive_new_app_never_prompts_for_default_version(self):
        with tempfile.TemporaryDirectory() as work:
            env = dict(os.environ, OAL_LIB_ONLY='1', OAL_NONINTERACTIVE='1',
                       OAL_APP_ID='com.example.personal', OAL_SIGNER_NAME='Personal',
                       OAL_COUNTRY='US', OAL_BUILDER_STATE_DIR=work)
            env.pop('OAL_START_VERSION_CODE', None)
            Path(work, 'config').mkdir()
            script = '''set -euo pipefail
source "$1"
prompt_value() { printf 'Unexpected interactive prompt' >&2; return 99; }
configure
'''
            result = subprocess.run(['bash', '-c', script, 'test', str(ROOT / 'build-aab.sh')],
                                    env=env, stdin=subprocess.DEVNULL, capture_output=True,
                                    text=True, timeout=5)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual('0', Path(work, 'config/starting-version-code').read_text().strip())


if __name__ == '__main__':
    unittest.main()
