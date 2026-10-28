"""Trusted manual verifier recovery must leave the exact tagged app untouched."""
from pathlib import Path
import os
import subprocess
import pytest
ROOT = Path(__file__).resolve().parents[2]


def first_script():
    text = (ROOT / '.github/workflows/publish-personalization-template.yml').read_text()
    return '\n'.join(line[10:] for line in text.split('        run: |\n', 1)[1].split('\n      - ', 1)[0].splitlines() if line.startswith('          '))


@pytest.mark.parametrize('event,ref,allowed', [
    ('workflow_dispatch', 'refs/heads/main', True),
    ('workflow_dispatch', 'refs/heads/fix/unreviewed', False),
    ('workflow_dispatch', 'refs/tags/v0.1.552', False),
    ('release', 'refs/tags/v0.1.552', True),
])
def test_manual_verifier_requires_trusted_default_branch(tmp_path, event, ref, allowed):
    env = dict(os.environ, RELEASE_TAG='v0.1.552', EVENT_NAME=event, WORKFLOW_REF=ref,
               DEFAULT_BRANCH='main', GITHUB_OUTPUT=str(tmp_path / 'output'))
    result = subprocess.run(['bash', '-e', '-c', first_script()], env=env, capture_output=True)
    assert (result.returncode == 0) is allowed
    if not allowed:
        assert not (tmp_path / 'output').exists()


def test_app_source_and_publisher_tooling_are_independently_pinned():
    text = (ROOT / '.github/workflows/publish-personalization-template.yml').read_text()
    producer = text.split('\n  upload:\n', 1)[0]
    assert 'ref: refs/tags/${{ steps.tag.outputs.value }}' in producer
    assert 'ref: ${{ github.workflow_sha }}' in producer
    assert 'path: .publication-tooling' in producer
    assert 'test "$(git -C .publication-tooling rev-parse HEAD)" = "$TOOLING_COMMIT"' in producer
    assert 'python -I -S .publication-tooling/desktop-builder/scripts/publish_template.py' in producer
    assert 'git diff --exit-code HEAD' in producer
    assert 'builder_commit: ${{ github.workflow_sha }}' in text
    assert 'git apply' not in producer and 'cp .publication-tooling' not in producer
