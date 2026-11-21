#!/usr/bin/env python3
"""
Proves a universal APK really carries the on-demand feature and only arm64 code.

Why this exists
---------------
`:proactive_trader` is delivered on demand (C-32), so the base APKs contain no
`com.alphapulse.proactive` classes and a sideloaded release correctly reports
"VIP unavailable" (C-33). CI now builds a universal APK from the same verified
`.aab` with `bundletool build-apks --mode=universal`, which fuses the feature
into one installable APK (C-03), and the release attaches it.

A build step succeeding proves only that bundletool exited zero. Two things can
still be wrong, and both are silent on a phone:

  * the module was not fused, because `dist:fusing dist:include` defaults to
    false for an on-demand module - the APK then installs and still says
    "VIP unavailable";
  * the merged native libraries include an ABI the base APKs never ship, which
    would reintroduce a non-arm64 build by the back door (C-03).

So the artifact itself is opened: every top-level `classes*.dex` is searched for
the exact component `TraderHostActivity` (the class name C-33 launches by
component name) and every `lib/` entry must sit under `lib/arm64-v8a/`.

Usage
-----
    python3 scripts/verify_universal_apk.py path/to/app-arm64-v8a-universal-debug.apk

Exit code 0 means the fused APK is installable and carries the feature; 1 prints
what is missing.
"""

from __future__ import annotations

import sys
import zipfile
from pathlib import Path

# The component C-33 starts by name once the module is available. Dex stores it in
# slash form; the dotted name is what the app and the contract use.
HOST_CLASS = "com.alphapulse.proactive.TraderHostActivity"
HOST_DESCRIPTOR = f"L{HOST_CLASS.replace('.', '/')};"
ARM64_PREFIX = "lib/arm64-v8a/"


def verify(path: Path) -> list[str]:
    """Every reason the APK is not a usable fused feature build, empty when it is."""
    problems: list[str] = []

    try:
        apk = zipfile.ZipFile(path)
    except Exception as error:  # noqa: BLE001 - any unreadable file is a failure
        return [f"{path} is not a readable APK/ZIP: {error}"]

    with apk:
        names = apk.namelist()

        if "AndroidManifest.xml" not in names:
            problems.append(f"{path} has no AndroidManifest.xml")
        if not any(name.startswith("classes") and name.endswith(".dex") for name in names):
            problems.append(f"{path} contains no dex file, so it is not an installable APK")

        dex_names = [
            name
            for name in names
            if name.startswith("classes") and name.endswith(".dex") and "/" not in name
        ]
        carries_host = False
        for name in dex_names:
            data = apk.read(name)
            if HOST_DESCRIPTOR.encode() in data or HOST_CLASS.encode() in data:
                carries_host = True
                break
        if not carries_host:
            problems.append(
                f"{path} does not contain {HOST_CLASS} in {', '.join(dex_names) or 'any dex'}. "
                "The on-demand module was not fused: check that its manifest declares "
                '<dist:fusing dist:include="true" /> and that bundletool ran with --mode=universal'
            )

        native = [name for name in names if name.startswith("lib/")]
        foreign = sorted(name for name in native if not name.startswith(ARM64_PREFIX))
        if foreign:
            problems.append(
                f"{path} ships native libraries outside lib/arm64-v8a (C-03 is arm64-only): "
                + ", ".join(foreign[:10])
            )
        if not any(name.startswith(ARM64_PREFIX) for name in native):
            problems.append(
                f"{path} has no lib/arm64-v8a entries; the Cactus engine could not load"
            )

    return problems


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(f"usage: {argv[0]} path/to/universal.apk", file=sys.stderr)
        return 2

    path = Path(argv[1])
    if not path.is_file():
        print(f"Universal APK verification FAILED:\n  - {path} does not exist", file=sys.stderr)
        return 1

    problems = verify(path)
    if problems:
        print("Universal APK verification FAILED:\n", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1

    with zipfile.ZipFile(path) as apk:
        dex_count = sum(1 for name in apk.namelist() if name.endswith(".dex") and "/" not in name)
        native_count = sum(1 for name in apk.namelist() if name.startswith(ARM64_PREFIX))
    print(
        f"Universal APK verified: {path} fuses {HOST_CLASS} "
        f"({dex_count} dex files, {native_count} arm64-v8a libraries)."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
