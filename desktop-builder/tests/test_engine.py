"""Engine tests use generated disposable identities, never owner credentials."""
import json
import threading
from pathlib import Path

import pytest
from oal_builder import engine as e


def test_identity_is_local_immutable_and_missing_key_is_fatal(tmp_path):
    config = e.BuildConfig(tmp_path, 'com.example.personal')
    builder = e.Builder(config, lambda *_: None)
    state = builder._prepare_identity()
    assert state['applicationId'] == config.app_id
    assert state['lastVersionCode'] == 0
    assert len(state['signingCertificateSha256']) == 64
    assert builder._prepare_identity() == state
    assert e.load_state(tmp_path) == state
    with pytest.raises(e.BuilderError, match='immutable'):
        e.Builder(e.BuildConfig(tmp_path, 'com.example.other'), lambda *_: None)._prepare_identity()
    (tmp_path / 'private/identity/upload-key.p12').unlink()
    with pytest.raises(e.BuilderError, match='missing'):
        builder._prepare_identity()
    with pytest.raises(e.BuilderError):
        e.Builder(e.BuildConfig(tmp_path / 'bad', 'com.openautolink.app'), lambda *_: None)._prepare_identity()


def test_encrypted_backup_roundtrip_and_no_live_overwrite(tmp_path):
    original, restored = tmp_path / 'original', tmp_path / 'restored'
    state = e.Builder(e.BuildConfig(original, e.suggest_app_id()), lambda *_: None)._prepare_identity()
    backup = tmp_path / 'identity.oalbackup'
    e.backup_identity(original, backup, 'a strong test passphrase')
    content = backup.read_bytes()
    assert state['applicationId'].encode() not in content
    assert b'PK\x03\x04' not in content[:20]
    with pytest.raises(e.BuilderError, match='password|damaged'):
        e.restore_identity(restored, backup, 'wrong password')
    assert e.load_state(restored) is None
    e.restore_identity(restored, backup, 'a strong test passphrase')
    assert e.load_state(restored) == state
    assert (original / 'private/identity/upload-key.p12').read_bytes() == (restored / 'private/identity/upload-key.p12').read_bytes()
    with pytest.raises(e.BuilderError, match='existing|overwrite'):
        e.restore_identity(restored, backup, 'a strong test passphrase')


def carplay_pair(directory):
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.serialization import pkcs7
    from cryptography.x509.oid import NameOID
    import datetime as dt
    directory.mkdir(parents=True, exist_ok=True)
    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'Disposable test only')])
    now = dt.datetime.now(dt.timezone.utc)
    cert = x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key()).serial_number(1).not_valid_before(now).not_valid_after(now + dt.timedelta(days=1)).sign(key, hashes.SHA256())
    a, b = directory / 'input.pk8', directory / 'input.p7b'
    a.write_bytes(key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    b.write_bytes(pkcs7.serialize_certificates([cert], serialization.Encoding.DER))
    return a, b


def test_carplay_pair_validation_copy_is_outside_source_and_optional(tmp_path):
    key, cert = carplay_pair(tmp_path / 'inputs')
    workspace = tmp_path / 'work'
    builder = e.Builder(e.BuildConfig(workspace, 'com.example.personal', key, cert), lambda *_: None)
    builder._prepare_identity()
    root = builder._prepare_carplay()
    assert root == workspace / 'private/carplay'
    assert {p.name for p in root.iterdir()} == {'offline-mfi'}
    assert {p.name for p in (root / 'offline-mfi').iterdir()} == {'identity.pk8', 'certificate.p7b'}
    assert (root / 'offline-mfi/identity.pk8').read_bytes() == key.read_bytes()
    _, wrong_cert = carplay_pair(tmp_path / 'wrong')
    with pytest.raises(e.BuilderError, match='CarPlay'):
        e.Builder(e.BuildConfig(workspace, 'com.example.personal', key, wrong_cert), lambda *_: None)._prepare_carplay()
    with pytest.raises(e.BuilderError, match='both'):
        e.Builder(e.BuildConfig(workspace, 'com.example.personal', key), lambda *_: None)._prepare_carplay()
    assert e.Builder(e.BuildConfig(workspace, 'com.example.personal'), lambda *_: None)._prepare_carplay() is None
    assert not root.exists()


def test_carplay_accepts_single_der_x509_certificate_like_runtime(tmp_path):
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.serialization import pkcs7
    key, certificate = carplay_pair(tmp_path / 'inputs')
    parsed = pkcs7.load_der_pkcs7_certificates(certificate.read_bytes())[0]
    certificate.write_bytes(parsed.public_bytes(serialization.Encoding.DER))
    builder = e.Builder(e.BuildConfig(tmp_path / 'work', 'com.example.personal', key, certificate), lambda *_: None)
    builder._prepare_identity()
    assert builder._prepare_carplay() is not None


def test_process_capture_failure_and_cancellation(tmp_path):
    import sys
    assert e.run_process([sys.executable, '-c', 'print("real subprocess")'], tmp_path, dict(__import__('os').environ), threading.Event(), lambda *_: None, 'test').strip() == 'real subprocess'
    with pytest.raises(e.BuilderError, match='exit code 7'):
        e.run_process([sys.executable, '-c', 'raise SystemExit(7)'], tmp_path, dict(__import__('os').environ), threading.Event(), lambda *_: None, 'test')
    cancel = threading.Event()
    timer = threading.Timer(0.2, cancel.set)
    timer.start()
    try:
        with pytest.raises(e.BuilderError, match='cancelled'):
            e.run_process([sys.executable, '-c', 'import time; time.sleep(60)'], tmp_path, dict(__import__('os').environ), cancel, lambda *_: None, 'test')
    finally:
        timer.cancel()


def test_cancellation_after_parent_exit_kills_stdout_holding_child(tmp_path):
    import os
    import subprocess
    import sys
    import time
    # Run the whole regression in a separate interpreter: a broken buffered
    # reader.close must fail by timeout rather than hang the pytest worker.
    probe = r'''
import os, sys, threading, time
from pathlib import Path
from oal_builder import engine as e
root = Path(sys.argv[1])
child = "import time; from pathlib import Path; Path('ready').touch(); time.sleep(3); Path('survived').touch()"
parent = "import subprocess,sys; from pathlib import Path; p=subprocess.Popen([sys.executable,'-c'," + repr(child) + "]); Path('child.pid').write_text(str(p.pid))"
cancel = threading.Event()
parent_exited = threading.Event()
real_start = e._WindowsProcess if os.name == 'nt' else e.subprocess.Popen
def observed_start(*args, **kwargs):
    process = real_start(*args, **kwargs)
    real_poll = process.poll
    def poll():
        result = real_poll()
        if result is not None:
            parent_exited.set()
        return result
    process.poll = poll
    return process
if os.name == 'nt':
    e._WindowsProcess = observed_start
else:
    e.subprocess.Popen = observed_start
def stop():
    assert parent_exited.wait(5), 'Launcher did not exit'
    while not (root / 'ready').exists():
        time.sleep(.01)
    cancel.set()  # observed exit, not a wall-clock guess
thread = threading.Thread(target=stop, daemon=True)
thread.start()
started = time.monotonic()
with e.workspace_lock(root / 'workspace'):
    try:
        e.run_process([sys.executable, '-c', parent], root, dict(os.environ), cancel, lambda *_: None, 'test')
    except e.BuilderError as exc:
        assert 'cancelled' in str(exc), str(exc)
    else:
        raise AssertionError('Parent exit was incorrectly reported as success despite cancellation')
    assert time.monotonic() - started < 2, 'Cancellation/drain was not bounded'
# A re-acquired lock must never overlap the still-active writer.
with e.workspace_lock(root / 'workspace'):
    time.sleep(3.2)
    assert not (root / 'survived').exists(), 'Owned child survived workspace release'
'''
    result = subprocess.run([sys.executable, '-c', probe, str(tmp_path)],
                            capture_output=True, text=True, timeout=12)
    assert result.returncode == 0, result.stdout + result.stderr


@pytest.mark.parametrize('mode', ['held_stdout', 'closed_stdout', 'failure_closed_stdout', 'flood_cancel'])
def test_parent_exit_cleans_descendants_and_bounds_drain(tmp_path, mode):
    import os
    import subprocess
    import sys
    # Keep any failure bounded outside the pytest process, and prove that a
    # descendant cannot write after run_process releases the workspace lock.
    probe = r'''
import os, sys, threading, time
from pathlib import Path
from oal_builder import engine as e
root = Path(sys.argv[1])
mode = sys.argv[2]
child = "import time; from pathlib import Path; Path('ready').touch(); time.sleep(3); Path('survived').touch()"
if mode == 'flood_cancel':
    child = "import os,time; from pathlib import Path; Path('ready').touch(); deadline=time.monotonic()+3; exec('while time.monotonic() < deadline: os.write(1, b\"x\" * 65536)'); Path('survived').touch()"
redirect = ', stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL' if 'closed_stdout' in mode else ''
parent = ("import subprocess,sys,time; from pathlib import Path; p=subprocess.Popen([sys.executable,'-c'," + repr(child) + "]" + redirect + "); Path('child.pid').write_text(str(p.pid)); "
          "exec(\"while not Path('ready').exists(): time.sleep(.01)\"); print('parent tail', flush=True)")
if mode == 'failure_closed_stdout':
    parent += '; sys.exit(7)'
cancel = threading.Event()
if mode == 'flood_cancel':
    def stop():
        while not (root / 'ready').exists(): time.sleep(.01)
        time.sleep(.3)
        cancel.set()
    threading.Thread(target=stop, daemon=True).start()
started = time.monotonic()
with e.workspace_lock(root / 'workspace'):
    try:
        output = e.run_process([sys.executable, '-c', parent], root, dict(os.environ), cancel, lambda *_: None, 'test')
    except e.BuilderError as exc:
        expected = {'held_stdout': 'output did not close', 'failure_closed_stdout': 'exit code 7', 'flood_cancel': 'cancelled'}
        assert mode in expected and expected[mode] in str(exc), str(exc)
    else:
        assert mode == 'closed_stdout', mode
        assert output.strip() == 'parent tail'
    assert time.monotonic() - started < 2.5, 'Unbounded output drain/cleanup'
with e.workspace_lock(root / 'workspace'):
    time.sleep(3.2)
    assert not (root / 'survived').exists(), 'Child wrote after workspace release'
'''
    result = subprocess.run([sys.executable, '-c', probe, str(tmp_path), mode],
                            capture_output=True, text=True, timeout=10)
    assert result.returncode == 0, result.stdout + result.stderr


@pytest.mark.skipif(__import__('os').name == 'nt', reason='POSIX setsid escape only')
def test_escaped_pipe_holder_cannot_hang_close(tmp_path):
    import os
    import signal
    import subprocess
    import sys
    probe = r'''
import os, sys, threading, time
from pathlib import Path
from oal_builder import engine as e
root = Path(sys.argv[1])
child = "import time; from pathlib import Path; Path('ready').touch(); time.sleep(30)"
parent = "import subprocess,sys,time; from pathlib import Path; p=subprocess.Popen([sys.executable,'-c'," + repr(child) + "],start_new_session=True); Path('child.pid').write_text(str(p.pid)); exec(\"while not Path('ready').exists(): time.sleep(.01)\")"
start = time.monotonic()
try:
    e.run_process([sys.executable, '-c', parent], root, dict(os.environ), threading.Event(), lambda *_: None, 'test')
except e.BuilderError as exc:
    assert 'output did not close' in str(exc), str(exc)
else:
    raise AssertionError('An incomplete output drain must not report success')
assert time.monotonic() - start < 2.5
'''
    try:
        result = subprocess.run([sys.executable, '-c', probe, str(tmp_path)],
                                capture_output=True, text=True, timeout=5)
        assert result.returncode == 0, result.stdout + result.stderr
    finally:
        # A process group is not a sandbox. The deliberately escaped child
        # belongs to the fixture, not the managed group; reap its writer here.
        if (tmp_path / 'child.pid').exists():
            try:
                os.kill(int((tmp_path / 'child.pid').read_text()), signal.SIGKILL)
            except ProcessLookupError:
                pass


@pytest.mark.skipif(__import__('os').name == 'nt', reason='POSIX cleanup hook')
def test_cancellation_during_final_cleanup_is_not_reported_as_success(tmp_path, monkeypatch):
    import os
    import sys
    cancel = threading.Event()
    killpg = os.killpg
    def cancel_while_stopping(group, sig):
        cancel.set()
        killpg(group, sig)
    monkeypatch.setattr(e.os, 'killpg', cancel_while_stopping)
    with pytest.raises(e.BuilderError, match='cancelled'):
        e.run_process([sys.executable, '-c', 'print("done")'], tmp_path,
                      dict(os.environ), cancel, lambda *_: None, 'test')


def test_process_output_tail_is_bounded_without_losing_final_bytes(tmp_path):
    import os
    import sys
    output = e.run_process([sys.executable, '-c',
                            'import sys; sys.stdout.buffer.write(b"x" * (5 * 1024**2) + b"final tail")'],
                           tmp_path, dict(os.environ), threading.Event(), lambda *_: None, 'test')
    assert len(output) == e.MAX_PROCESS_OUTPUT
    assert output.endswith('final tail')


class FakeWindowsAPI:
    """Lifecycle contract only; native Job/pipe behavior still needs Windows."""
    def __init__(self, fail=None):
        import io
        self.events = []
        self.fail = fail
        self.stdout = io.BytesIO()
        self.active = 1

    def record(self, name, *args):
        self.events.append((name, *args))
        if name == self.fail:
            raise OSError('injected ' + name)

    def create_job(self):
        self.record('create_job')
        return 'job'

    def start_suspended(self, argv, cwd, env):
        self.record('start_suspended')
        return 'process', 'thread', 123, self.stdout

    def assign(self, job, process):
        self.record('assign', job, process)

    def resume(self, thread):
        self.record('resume', thread)

    def close(self, handle):
        self.record('close', handle)

    def terminate_process(self, process):
        self.record('terminate_process', process)

    def wait_process(self, process, timeout):
        self.record('wait_process', process)
        return 0

    def poll_process(self, process):
        return 0  # the launcher already exited, but its Job has a live child

    def terminate_job(self, job):
        self.record('terminate_job', job)
        self.active = 0

    def active_processes(self, job):
        self.record('active_processes', job)
        return self.active


@pytest.fixture
def windows_create_process(monkeypatch):
    """Exercise run_process through the real native adapter, fake only Win32."""
    import os
    import sys
    from types import MethodType, SimpleNamespace

    calls = []
    api = FakeWindowsAPI()

    def create_process(application, commandline, *args):
        startup = args[-1]
        calls.append((application, commandline))
        os.write(startup.hStdOutput, b'launched\n')
        return 'process', 'thread', 123, 0

    api.winapi = SimpleNamespace(CreateProcess=create_process)
    api.start_suspended = MethodType(e._WindowsAPI.start_suspended, api)
    monkeypatch.setattr(e, '_WindowsAPI', lambda: api)
    # Do not mutate os.name globally: pathlib/pytest must retain the host OS.
    monkeypatch.setattr(e, 'os', SimpleNamespace(**{**vars(os), 'name': 'nt'}))
    monkeypatch.setitem(sys.modules, 'msvcrt', SimpleNamespace(get_osfhandle=lambda fd: fd))
    monkeypatch.setattr(e.subprocess, 'STARTUPINFO', SimpleNamespace, raising=False)
    for name, value in [('STARTF_USESTDHANDLES', 0x100), ('STARTF_USESHOWWINDOW', 1),
                        ('SW_HIDE', 0), ('CREATE_NEW_PROCESS_GROUP', 0x200)]:
        monkeypatch.setattr(e.subprocess, name, value, raising=False)
    return calls


@pytest.mark.parametrize(('wrapper', 'quoted_wrapper'), [
    (r'C:\OAL Work\source\gradlew.bat', r'"C:\OAL Work\source\gradlew.bat"'),
    (r'C:\OAL\gradlew.BAT', r'C:\OAL\gradlew.BAT'),
])
@pytest.mark.parametrize(('comspec', 'quoted_comspec'), [
    (r'C:\Windows\System32\cmd.exe', r'C:\Windows\System32\cmd.exe'),
    (r'C:\Windows Tools\cmd.exe', r'"C:\Windows Tools\cmd.exe"'),
])
def test_windows_gradle_cmd_payload_is_not_crt_quoted_twice(
        tmp_path, windows_create_process, wrapper, quoted_wrapper, comspec, quoted_comspec):
    argv = [wrapper, ':app:bundleRelease',
            '-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8',
            r'-PoalCarPlayMfiAssetsDir=C:\OAL Work\private\carplay']
    output = e.run_process(argv, tmp_path, {'COMSPEC': comspec},
                           threading.Event(), lambda *_: None, 'test')
    assert output == 'launched\n'
    assert windows_create_process == [(None,
        quoted_comspec + ' /d /s /c "' + quoted_wrapper + ' '
        r':app:bundleRelease "-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8" '
        r'"-PoalCarPlayMfiAssetsDir=C:\OAL Work\private\carplay""')]


@pytest.mark.parametrize('unsafe', list('&|<>^%!\r\n"'))
@pytest.mark.parametrize('position', ['wrapper', 'argument'])
def test_windows_gradle_rejects_shell_characters_before_launch(
        tmp_path, windows_create_process, unsafe, position):
    argv = [r'C:\OAL Work\gradlew.bat', ':app:bundleRelease']
    if position == 'wrapper':
        argv[0] = 'C:\\OAL' + unsafe + 'Work\\gradlew.bat'
    else:
        argv.append('-Pvalue=before' + unsafe + 'after')
    with pytest.raises(e.BuilderError, match='shell metacharacters'):
        e.run_process(argv, tmp_path, {}, threading.Event(), lambda *_: None, 'test')
    assert windows_create_process == []


def test_windows_executable_keeps_crt_argument_quoting(tmp_path, windows_create_process):
    argv = [r'C:\Java Tools\bin\java.exe', '', 'argument with spaces',
            'embedded "quote"', 'C:\\trailing slash\\', '&|<>^%!']
    output = e.run_process(argv, tmp_path, {}, threading.Event(), lambda *_: None, 'test')
    assert output == 'launched\n'
    assert windows_create_process == [(None, e.subprocess.list2cmdline(argv))]
    assert ' /c ' not in windows_create_process[0][1]


@pytest.mark.skipif(__import__('os').name != 'nt', reason='Real Windows cmd/CreateProcess integration')
@pytest.mark.parametrize('launcher', ['batch', 'exe'])
def test_native_windows_argument_roundtrip(tmp_path, launcher):
    import os
    import subprocess
    import sys

    # A Gradle-style wrapper forwards %* to a real CRT argv consumer. Unlike an
    # echo test, this checks argument boundaries, not just cmd's rendered text.
    work = tmp_path / 'OAL Work (fixture)'
    work.mkdir()
    recorder = work / 'record args.py'
    recorder.write_text('import json, sys; print(json.dumps(sys.argv[1:]))\n', encoding='utf-8')
    wrapper = work / 'gradlew.bat'
    wrapper.write_text('@echo off\n"%OAL_TEST_PYTHON%" "%~dp0record args.py" %*\nexit /b %errorlevel%\n',
                       encoding='utf-8')
    args = [':app:bundleRelease', '-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8',
            '-PoalCarPlayMfiAssetsDir=' + str(work / 'private carplay'),
            '', str(work) + '\\']
    if launcher == 'exe':
        args += ['embedded "quote"', '&|<>^%!']
    command = [str(wrapper)] if launcher == 'batch' else [sys.executable, str(recorder)]
    # An outer timeout bounds native adapter failures without hanging CI.
    probe = '''
import json, os, sys, threading
from oal_builder import engine as e
output = e.run_process(json.loads(sys.argv[1]), sys.argv[2], dict(os.environ),
                       threading.Event(), lambda *_: None, 'test')
print(output, end='')
'''
    result = subprocess.run([sys.executable, '-c', probe, json.dumps(command + args), str(work)],
                            env={**os.environ, 'OAL_TEST_PYTHON': sys.executable},
                            capture_output=True, text=True, timeout=20)
    assert result.returncode == 0, result.stdout + result.stderr
    assert json.loads(result.stdout) == args


def test_windows_job_owns_descendants_before_resume_and_after_parent_exit(tmp_path):
    api = FakeWindowsAPI()
    process = e._WindowsProcess(['tool'], tmp_path, {}, api=api)
    assert [event[0] for event in api.events] == ['create_job', 'start_suspended', 'assign', 'resume', 'close']
    assert api.events[-1] == ('close', 'thread')
    assert process.poll() == 0
    process.kill_tree()
    process.wait(timeout=1)
    process.close()
    assert ('terminate_job', 'job') in api.events
    assert ('active_processes', 'job') in api.events
    assert ('close', 'job') in api.events
    assert ('close', 'process') in api.events


@pytest.mark.parametrize('failure', ['start_suspended', 'assign', 'resume'])
def test_windows_job_start_failure_never_leaves_unowned_running_tool(tmp_path, failure):
    api = FakeWindowsAPI(failure)
    with pytest.raises(OSError, match=failure):
        e._WindowsProcess(['tool'], tmp_path, {}, api=api)
    assert ('close', 'job') in api.events
    if failure != 'start_suspended':
        assert ('terminate_process', 'process') in api.events
        assert ('wait_process', 'process') in api.events
        assert ('close', 'thread') in api.events
        assert ('close', 'process') in api.events
        assert api.stdout.closed
    if failure == 'assign':
        assert ('resume', 'thread') not in api.events


@pytest.mark.parametrize(('output', 'diagnostic', 'recovery'), [
    (b'Dependency verification failed for configuration :app:releaseRuntimeClasspath', 'Dependency verification failed', 'Do not disable strict verification'),
    (b'java.io.IOException: No space left on device', 'Disk space exhausted', 'Free disk space'),
    (b'Could not GET https://example.invalid/private?token=secret-token', 'Network download failed', 'Check connectivity'),
    (b'e: file:///secret/Home.kt:42:1 Unresolved reference: privateSymbol', 'Compiler failure', 'Retry with the latest stable source'),
])
def test_process_failure_has_safe_actionable_diagnostics(tmp_path, output, diagnostic, recovery):
    import os
    import sys
    events = []
    with pytest.raises(e.BuilderError) as failure:
        e.run_process([sys.executable, '-c', 'import sys; sys.stdout.buffer.write(' + repr(output) + '); sys.exit(7)'],
                      tmp_path, dict(os.environ), threading.Event(), lambda *v: events.append(v), 'build')
    message = str(failure.value)
    assert 'exit code 7' in message
    assert diagnostic in message
    assert recovery in message
    assert 'secret-token' not in message and 'privateSymbol' not in message and '/secret' not in message
    assert not events


@pytest.mark.parametrize('phase', ['build', 'sign', 'verify', '\x1b[31m/secret/path password=do-not-print\u202e'])
def test_failure_diagnostics_discard_sensitive_malformed_and_control_output(tmp_path, phase):
    import os
    import sys
    payload = (b'\xff\xfe\x00password=swordfish\r\n' + b'-----BEGIN PRIVATE KEY-----\nprivate-key-body\n' +
               b'-----END PRIVATE KEY-----\nhttps://user:password@example.invalid/?token=private-token\n' +
               b'C:\\Users\\Private\\upload-key.p12\n/secret/offline-mfi/identity.pk8\n' +
               b'Dependency \x1b[31mverification\x1b[0m failed\x07\n' +
               ('No\u202e space left on device\n').encode())
    with pytest.raises(e.BuilderError) as failure:
        e.run_process([sys.executable, '-c', 'import sys; sys.stdout.buffer.write(' + repr(payload) + '); sys.exit(8)'],
                      tmp_path, dict(os.environ), threading.Event(), lambda *_: None, phase)
    message = str(failure.value)
    assert 'Dependency verification failed' in message
    assert 'Disk space exhausted' in message
    for secret in ['swordfish', 'private-key-body', 'private-token', '/secret', 'C:\\Users', 'do-not-print', 'PRIVATE KEY']:
        assert secret not in message
    assert all(32 <= ord(c) < 127 for c in message)
    assert len(message) <= e.MAX_DIAGNOSTIC_CHARS


def test_failure_diagnostics_bound_unknown_and_noisy_output():
    payload = b'arbitrary-secret' * 500000
    message = e.process_failure(payload, 'build', 2)
    assert 'raw output withheld for privacy' in message
    assert 'arbitrary-secret' not in message
    assert len(message) <= e.MAX_DIAGNOSTIC_CHARS
    recognized = e.process_failure(b'Dependency verification failed\nNo space left on device\nCould not GET\nCompilation failed\n' * 50000, 'build', 1)
    assert recognized.count('Recovery:') == 4
    assert len(recognized) <= e.MAX_DIAGNOSTIC_CHARS


def test_workspace_lock_prevents_parallel_identity_changes(tmp_path):
    with e.workspace_lock(tmp_path):
        with pytest.raises(e.BuilderError, match='already'):
            with e.workspace_lock(tmp_path):
                pass


def test_build_pipeline_reserves_versions_verifies_and_keeps_signing_out_of_gradle(tmp_path, monkeypatch):
    import zipfile
    from cryptography.hazmat.primitives import serialization
    commands = []
    class Tools:
        def __init__(self, workspace, progress, cancel):
            self.workspace = workspace
        def install(self, accepted):
            assert accepted
            return {'java': 'java', 'jarsigner': 'jarsigner', 'keytool': 'keytool', 'bundletool': 'bundletool.jar', 'env': {}}
        def prepare_source(self):
            source = self.workspace / 'source'
            (source / 'gradle/wrapper').mkdir(parents=True, exist_ok=True)
            (source / 'gradle/wrapper/gradle-wrapper.properties').write_text('distributionSha256Sum=' + 'a' * 64)
            (source / 'gradle/verification-metadata.xml').write_text('<verification-metadata/>')
            (source / 'app').mkdir(exist_ok=True)
            (source / 'app/build.gradle.kts').write_text('oalBuilderUnsigned oalVersionCode oalVersionName appId oalCarPlayMfiAssetsDir')
            (source / 'gradlew').write_text('wrapper')
            return {'sourceCommit': 'a' * 40, 'versionName': '1.2.3', 'sourceRef': 'v1.2.3', 'sourceOverlays': ['test-reviewed-overlay']}
    def command(argv, cwd, env, cancel, progress, phase):
        argv = list(map(str, argv))
        commands.append(argv)
        if ':app:bundleRelease' in argv:
            assert '-PoalBuilderUnsigned=true' in argv
            assert '--no-build-cache' in argv and '--max-workers=2' in argv
            assert '--dependency-verification=strict' in argv
            assert not any('write-verification' in arg for arg in argv)
            assert not any('password' in arg or 'upload-key' in arg or 'private' in arg for arg in argv)
            built = tmp_path / 'source/app/build/outputs/bundle/release/app-release.aab'
            built.parent.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(built, 'w') as z:
                for abi in ('arm64-v8a', 'x86_64'):
                    z.writestr('base/lib/' + abi + '/libopenautolink-jni.so', 'test binary')
            return ''
        if argv[0] == 'jarsigner':
            if '-verify' in argv:
                return 'jar verified.'
            assert '-storepass:file' in argv and '-keypass:file' in argv
            return ''
        if argv[0] == 'keytool':
            state = e.load_state(tmp_path)
            _, cert = e.read_identity(tmp_path / 'private/identity', state)
            return cert.public_bytes(serialization.Encoding.PEM).decode()
        if 'manifest' in argv:
            code = e.load_state(tmp_path)['lastVersionCode']
            return '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.personal" android:versionCode="' + str(code) + '" android:versionName="1.2.3"/>'
        return ''
    monkeypatch.setattr(e, 'Toolchain', Tools)
    monkeypatch.setattr(e, 'run_process', command)
    builder = e.Builder(e.BuildConfig(tmp_path, 'com.example.personal', accept_licenses=True), lambda *_: None)
    first = builder.build()
    assert first['versionCode'] == 1 and Path(first['artifact']).is_file()
    assert first['sourceCommit'] == 'a' * 40
    assert first['sourceOverlays'] == ['test-reviewed-overlay']
    second = builder.build()
    assert second['versionCode'] == 2
    assert e.load_state(tmp_path)['lastVersionCode'] == 2
    assert json.loads((tmp_path / 'output/latest.json').read_text()) == second
    def failed(*args):
        raise e.BuilderError('deliberate compiler failure')
    monkeypatch.setattr(e, 'run_process', failed)
    with pytest.raises(e.BuilderError, match='compiler failure'):
        builder.build()
    assert e.load_state(tmp_path)['lastVersionCode'] == 3
    assert e.load_state(tmp_path)['lastAttemptStatus'] == 'failed'
    assert json.loads((tmp_path / 'output/latest.json').read_text()) == second


def test_verify_bundle_rejects_missing_abi_and_credentials(tmp_path):
    import zipfile
    bundle = tmp_path / 'bad.aab'
    with zipfile.ZipFile(bundle, 'w') as z:
        z.writestr('base/lib/arm64-v8a/libopenautolink-jni.so', 'binary')
    with pytest.raises(e.BuilderError, match='ABI'):
        e.verify_bundle_entries(bundle, False)
    with zipfile.ZipFile(bundle, 'a') as z:
        z.writestr('base/lib/x86_64/libopenautolink-jni.so', 'binary')
        z.writestr('base/assets/offline-mfi/identity.pk8', 'fixture')
    with pytest.raises(e.BuilderError, match='CarPlay'):
        e.verify_bundle_entries(bundle, False)
    with pytest.raises(e.BuilderError, match='CarPlay'):
        e.verify_bundle_entries(bundle, True)


def test_verification_rejects_wrong_identity_signature_and_manifest(tmp_path, monkeypatch):
    builder = e.Builder(e.BuildConfig(tmp_path, 'com.example.personal'), lambda *_: None)
    state = builder._prepare_identity()
    builder.tools = {'java': 'java', 'jarsigner': 'jarsigner', 'keytool': 'keytool', 'bundletool': 'jar', 'env': {}}
    expected = {'applicationId': 'com.example.personal', 'versionCode': 1, 'versionName': '1'}
    monkeypatch.setattr(builder, '_run', lambda argv, phase: 'jar is unsigned')
    with pytest.raises(e.BuilderError, match='signature'):
        builder._verify(tmp_path / 'not-read.aab', expected, state, False)
    def response(argv, phase):
        if 'jarsigner' in argv:
            return 'jar verified.'
        if 'manifest' in argv:
            return '<manifest package="com.wrong.app"/>'
        return ''
    monkeypatch.setattr(builder, '_run', response)
    with pytest.raises(e.BuilderError, match='manifest'):
        builder._verify(tmp_path / 'not-read.aab', expected, state, False)


def test_generated_identity_signs_with_real_managed_jdk(tmp_path):
    import os
    import zipfile
    java_home = os.environ.get('OAL_TEST_JAVA_HOME')
    if not java_home:
        pytest.skip('Set OAL_TEST_JAVA_HOME to run real JDK signature integration')
    builder = e.Builder(e.BuildConfig(tmp_path, 'com.example.integration'), lambda *_: None)
    state = builder._prepare_identity()
    identity = tmp_path / 'private/identity'
    jar = tmp_path / 'fixture.jar'
    with zipfile.ZipFile(jar, 'w') as z:
        z.writestr('payload.txt', 'real signing test')
    suffix = '.exe' if os.name == 'nt' else ''
    signer = str(Path(java_home) / 'bin' / ('jarsigner' + suffix))
    keytool = str(Path(java_home) / 'bin' / ('keytool' + suffix))
    env = {'JAVA_HOME': java_home, 'PATH': os.defpath, **{k: v for k, v in os.environ.items() if k in ('SystemRoot', 'TEMP', 'TMP')}}
    run = lambda args: e.run_process(args, tmp_path, env, threading.Event(), lambda *_: None, 'integration')
    run([signer, '-keystore', str(identity / 'upload-key.p12'), '-storetype', 'PKCS12', '-storepass:file', str(identity / 'password'), '-keypass:file', str(identity / 'password'), str(jar), 'upload'])
    assert 'jar verified.' in run([signer, '-verify', str(jar)])
    cert = e.x509.load_pem_x509_certificate(run([keytool, '-printcert', '-rfc', '-jarfile', str(jar)]).encode())
    assert cert.fingerprint(e.hashes.SHA256()).hex() == state['signingCertificateSha256']


def test_workspace_refuses_unowned_content_before_cleanup(tmp_path):
    source = tmp_path / 'source'
    source.mkdir()
    (source / 'precious.txt').write_text('keep me')
    with pytest.raises(e.BuilderError, match='dedicated|unmanaged'):
        e.Builder(e.BuildConfig(tmp_path, 'com.example.personal'), lambda *_: None)._prepare_identity()
    assert (source / 'precious.txt').read_text() == 'keep me'


def test_restore_rejects_authenticated_zip_traversal(tmp_path):
    import io
    import zipfile
    password = 'test backup passphrase'
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w') as z:
        z.writestr('../escape', 'unsafe')
    salt, nonce = b's' * 16, b'n' * 12
    header = e.BACKUP_MAGIC + salt + nonce
    payload = header + e.AESGCM(e.backup_key(password, salt)).encrypt(nonce, buffer.getvalue(), header)
    archive = tmp_path / 'bad.oalbackup'
    archive.write_bytes(payload)
    with pytest.raises(e.BuilderError, match='Unsafe'):
        e.restore_identity(tmp_path / 'restored', archive, password)
    assert not (tmp_path / 'escape').exists()
    assert e.load_state(tmp_path / 'restored') is None


@pytest.mark.parametrize('app_id', ['com.example.class', 'com.example.true', 'com._invalid.foo'])
def test_app_id_rejects_java_keywords_and_invalid_segments(app_id):
    with pytest.raises(e.BuilderError):
        e.validate_app_id(app_id)
