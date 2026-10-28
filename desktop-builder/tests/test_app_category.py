"""Synthetic protobuf regression for the unchanged 552 audio category."""
import zipfile
import pytest
from test_personalize import (attr, field, manifest, entries, transform, MANIFEST, NEW,
                              p, tools)


def category(value='audio', primitive=6, number=1, resource_id=0x01010545):
    return attr('appCategory', value, resource_id=resource_id,
                compiled=field(7, field(primitive, number)))


def test_audio_category_preserved_without_expanding_identity_edits(p, tools, tmp_path):
    data = entries()
    audio = category()
    data[MANIFEST] = manifest(app_attrs=[audio])
    before, after, metadata = transform(p, tmp_path, tools, data)
    with zipfile.ZipFile(after) as z:
        assert z.read(MANIFEST) == manifest(NEW, 42, app_attrs=[audio])
    assert metadata['changedMembers'] == ['base/manifest/AndroidManifest.xml', 'base/resources.pb']


@pytest.mark.parametrize('bad', [
    category(resource_id=0x01010011), category(value='video'), category(number=2),
    category(primitive=7), attr('appCategory', 'audio', resource_id=0x01010545),
    attr('appCategory', 'audio', resource_id=0x01010545, compiled=field(1, field(2, 0x7f010001))),
])
def test_audio_category_requires_exact_reviewed_enum(p, tools, tmp_path, bad):
    data = entries()
    data[MANIFEST] = manifest(app_attrs=[bad])
    with pytest.raises(p.BuilderError, match='OAL_PERSONALIZE_HELPER_FAILED'):
        transform(p, tmp_path, tools, data)
    assert not (tmp_path / 'output.aab').exists()


def test_audio_category_rejected_on_component(p, tools, tmp_path):
    from test_personalize import node
    data = entries()
    data[MANIFEST] = manifest(extra=[node('service', [attr('name', 'com.example.Service'), category()])])
    with pytest.raises(p.BuilderError, match='OAL_PERSONALIZE_HELPER_FAILED'):
        transform(p, tmp_path, tools, data)
