#!/usr/bin/env python3
"""Verify packaged NativeScript metadata exposes the SDK methods an app uses."""
import argparse
import sys
import zipfile

DEFAULT_METHODS = ("registerGlanceboardWidgets", "publishGlanceboardWidget")

def verify_metadata(apk, methods=DEFAULT_METHODS):
    with zipfile.ZipFile(apk) as archive:
        entry = archive.getinfo("assets/metadata/treeStringsStream.dat")
        if entry.file_size > 16 * 1024 * 1024:
            raise ValueError("NativeScript metadata exceeds verification limit")
        metadata = archive.read(entry)
    missing = [method for method in methods if method.encode("utf-8") not in metadata]
    if missing:
        raise ValueError("NativeScript SDK metadata missing " + ", ".join(missing) +
                         "; regenerate Android metadata and rebuild the APK")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk")
    parser.add_argument("--require", action="append", help="Required Java method (repeatable; overrides defaults)")
    args = parser.parse_args()
    try:
        verify_metadata(args.apk, args.require or DEFAULT_METHODS)
    except (OSError, KeyError, ValueError, zipfile.BadZipFile) as error:
        print(str(error), file=sys.stderr)
        return 1
    print("PASS NativeScript SDK bridge metadata")
    return 0

if __name__ == "__main__":
    sys.exit(main())
