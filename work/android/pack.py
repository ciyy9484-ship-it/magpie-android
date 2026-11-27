"""Adds classes.dex and the bundled magpie binary to the aapt2 output APK.

Android 11+ refuses to install an APK whose resources.arsc is compressed
(targetSdk >= 30), so every entry keeps its original storage method and the
two entries the installer is picky about are stored uncompressed; zipalign
then puts them on 4-byte boundaries.
"""
import sys
import zipfile

base, so, dex, out = sys.argv[1:5]

STORE = {"resources.arsc", "classes.dex", "AndroidManifest.xml"}

with zipfile.ZipFile(base) as src:
    entries = [(i, src.read(i.filename)) for i in src.infolist()]

with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as dst:
    for info, data in entries:
        zi = zipfile.ZipInfo(info.filename, date_time=(2026, 11, 26, 0, 0, 0))
        zi.compress_type = zipfile.ZIP_STORED if info.filename in STORE else info.compress_type
        zi.external_attr = info.external_attr
        dst.writestr(zi, data)

    for path, arc in ((dex, "classes.dex"), (so, "lib/arm64-v8a/libmagpie-core.so")):
        zi = zipfile.ZipInfo(arc, date_time=(2026, 11, 26, 0, 0, 0))
        zi.compress_type = zipfile.ZIP_STORED if arc in STORE else zipfile.ZIP_DEFLATED
        zi.external_attr = (0o644 & 0xFFFF) << 16
        with open(path, "rb") as f:
            dst.writestr(zi, f.read())

print("packed", out)
