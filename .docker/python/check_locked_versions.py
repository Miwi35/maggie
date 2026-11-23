"""Fail the image build if an installed package differs from agent/uv.lock (MAG-942).

Reads the requirements `uv export` wrote from the lock (`name==version` lines)
and compares each pin with the installed distribution. A pin whose platform
marker excludes this image is not installed, and is skipped.
"""

import re
import sys
from importlib.metadata import PackageNotFoundError, version

PIN = re.compile(r"^([A-Za-z0-9_.-]+)==([^\s;\\]+)\s*(;)?")

mismatches = []
for line in open(sys.argv[1]):
    match = PIN.match(line)
    if not match:
        continue
    name, locked, has_marker = match.groups()
    try:
        installed = version(name)
    except PackageNotFoundError:
        if not has_marker:
            mismatches.append(f"{name}: locked {locked}, not installed")
        continue
    if installed != locked:
        mismatches.append(f"{name}: locked {locked}, installed {installed}")

if mismatches:
    sys.exit("Installed packages differ from agent/uv.lock:\n" + "\n".join(mismatches))

print("anthropic", version("anthropic"), "· sentry-sdk", version("sentry-sdk"), "· as locked")
