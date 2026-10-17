"""Lightweight application-ID rules shared by build and publication boundaries.

Keep this module free of signing/UI dependencies: the release verifier runs with
Python's standard library only, without site-packages or the build engine.
"""
import re

from .toolchain import BuilderError


def validate_app_id(value):
    if not isinstance(value, str) or len(value) > 150 or not re.fullmatch(r'[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+', value) or value == 'com.openautolink.app':
        raise BuilderError('Use a unique lowercase application ID, not com.openautolink.app')
    reserved = set('abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try void volatile while true false null _'.split())
    if any(part in reserved for part in value.split('.')):
        raise BuilderError('Application ID segments must not be Java keywords')


def validate_personal_app_id(value):
    """Require an identity usable by AAB personalization.

    Keep generic validation separate: publication uses the template identity,
    and existing legacy identities must remain readable and recoverable.
    """
    from .releases import TEMPLATE_APPLICATION_ID
    validate_app_id(value)
    if value in TEMPLATE_APPLICATION_ID or TEMPLATE_APPLICATION_ID in value:
        raise BuilderError(
            'This application ID is incompatible with AAB personalization because it overlaps '
            'the template application ID. Choose a different unique ID (use the '
            'suggested personal ID) for a new app. For an existing identity, keep '
            'its application ID and signing key and contact the publisher for migration guidance; '
            'do not reset or regenerate the identity.')
