"""Synthetic driver-display profile boundaries; real575 parity is retained separately."""
import zipfile
import pytest
from test_personalize import (OLD, NEW, ANDROID, MANIFEST, RESOURCES, UNKNOWN,
    attr, node, field, resources, entries, transform, p, tools)

OBSERVER = 'com.openautolink.app.diagnostics.gm.GmPageObserverService'
AFFINITIES = [
    ('androidx.car.app.activity.CarAppActivity', '.templates'),
    ('com.openautolink.app.ui.settings.LiveGridWindowActivity', '.grid'),
    ('com.openautolink.app.cluster.ClusterDirectVideoActivity', '.cluster.direct'),
    ('com.openautolink.app.secondary.SecondaryProjectionActivity', '.secondary.projection'),
    ('com.openautolink.app.cluster.ClusterMapLaunchActivity', '.cluster.map.launch'),
]
RESIZE = {x[0] for x in AFFINITIES[1:4]}
NO_HISTORY = {x[0] for x in AFFINITIES[2:4]}

def driver_manifest(old=OLD, version=1, fault=None, index=0):
    declarations = []
    for suffix in ['.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION', '.permission.OBSERVE_CLUSTER_PAGE']:
        raw = '0x00000002'
        compiled = field(7, field(7, 2))
        if suffix.endswith('OBSERVE_CLUSTER_PAGE'):
            if fault == 'normal-protection': raw, compiled = '0', field(7, field(7, 0))
            if fault == 'missing-compiled-protection': compiled = None
        declarations += [node('permission', [attr('name', old+suffix), attr('protectionLevel', raw, compiled=compiled)]),
                         node('uses-permission', [attr('name', old+suffix)])]
    activities = []
    for number, (component, suffix) in enumerate(AFFINITIES):
        name = 'com.example.WrongActivity' if fault == 'wrong-component' and number == index else component
        value = old + ('.unknown' if fault == 'wrong-affinity' and number == index else suffix)
        attrs = [attr('name', name), attr('taskAffinity', value)]
        if fault == 'duplicate-affinity' and number == index: attrs.append(attr('taskAffinity', value))
        if fault == 'missing-affinity' and number == index: attrs = attrs[:1]
        if component in RESIZE:
            raw, primitive = 'true', 1
            if fault == 'boolean-disagreement' and number == index: primitive = 0
            attrs.append(attr('resizeableActivity', raw, resource_id=0x010104f6,
                              compiled=field(7, field(8, primitive))))
        if component in NO_HISTORY:
            raw, primitive = ('true', 1) if fault == 'no-history-true' and number == index else ('false', 0)
            attrs.append(attr('noHistory', raw, resource_id=0x0101022d,
                              compiled=field(7, field(8, primitive))))
        if fault == 'unknown-attribute' and number == index:
            attrs.append(attr('documentLaunchMode', 'never', resource_id=0x01010445,
                              compiled=field(7, field(6, 3))))
        activities.append(node('activity', attrs))
    service = [attr('name', 'com.example.WrongService' if fault == 'wrong-observer' else OBSERVER)]
    if fault != 'missing-observer-permission':
        service.append(attr('permission', old+'.permission.OBSERVE_CLUSTER_PAGE', resource_id=0x01010006))
    app = node('application', [attr('name', 'com.openautolink.app.OalApplication')], [
        *activities, node('service', service),
        node('provider', [attr('name', 'androidx.startup.InitializationProvider'),
                          attr('authorities', old+'.androidx-startup')])])
    return node('manifest', [attr('package', old, ''),
        attr('versionCode', str(version), compiled=field(7, field(6, version)))], [*declarations, app])

def test_driver_profile_edits_every_identity_site_and_preserves_payload(p, tools, tmp_path):
    data = entries(); data[MANIFEST] = driver_manifest()
    source, output, result = transform(p, tmp_path, tools, data)
    with zipfile.ZipFile(source) as before, zipfile.ZipFile(output) as after:
        assert after.read(MANIFEST) == driver_manifest(NEW, 42)
        assert after.read(RESOURCES) == resources(NEW)
        assert result['changedMembers'] == [MANIFEST, RESOURCES]
        for name in after.namelist():
            if name not in [MANIFEST, RESOURCES]: assert before.read(name) == after.read(name)
        assert UNKNOWN in after.read(MANIFEST)
        assert OLD.encode() not in after.read(MANIFEST)

@pytest.mark.parametrize('fault,index', [
    *[('wrong-component', i) for i in range(5)],
    *[('wrong-affinity', i) for i in range(5)],
    ('duplicate-affinity', 1), ('missing-affinity', 1),
    ('normal-protection', 0), ('missing-compiled-protection', 0),
    ('wrong-observer', 0), ('missing-observer-permission', 0),
    ('boolean-disagreement', 1), ('no-history-true', 2), ('unknown-attribute', 1),
], ids=lambda x: str(x))
def test_driver_profile_rejects_unreviewed_or_inconsistent_sites(p, tools, tmp_path, fault, index):
    data = entries(); data[MANIFEST] = driver_manifest(fault=fault, index=index)
    with pytest.raises(p.BuilderError, match='OAL_PERSONALIZE_'):
        transform(p, tmp_path, tools, data)
    assert not (tmp_path/'output.aab').exists()
