"""Compile-time OEM JAR expectation; never installation or admission authority."""
import re

PIN = re.compile(r'[0-9a-f]{64}\Z')
FIELD = re.compile(rb'\bOEM_SERVICES_JAR_SHA256\s*=\s*"([0-9a-f]{64})"\s*;')


def validate(app, override):
    if override is None:
        return
    if app != 'hub':
        raise ValueError('OEM service JAR selection is only available for Hub builds')
    if not isinstance(override, str) or not PIN.fullmatch(override) or override == '0' * 64:
        raise ValueError('OEM service JAR pin must be 64 lowercase nonzero hex digits')


def source(template, override=None):
    """Return the exact generated source (or None) and the resolved read-only pin."""
    validate('hub', override)
    rows = list(FIELD.finditer(template))
    if len(rows) != 1 or rows[0].group(1) == b'0' * 64:
        raise ValueError('OEM service build source must contain one exact OEM_SERVICES_JAR_SHA256 field')
    match = rows[0]
    default = match.group(1).decode('ascii')
    if override is None:
        return None, default
    return template[:match.start(1)] + override.encode('ascii') + template[match.end(1):], override
