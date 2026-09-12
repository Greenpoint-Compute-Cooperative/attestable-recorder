#!/usr/bin/env python3
"""Compare two APKs by content, ignoring the APK signature.

Prints one line per difference and exits 1 if any; exits 0 when every zip entry (name, size, CRC,
bytes) matches. APK Signature Scheme v2/v3 blocks live outside the zip entries, so a signed APK and
the unsigned APK it was made from compare equal. JAR (v1) signature files under META-INF are ignored.

    scripts/apk-compare.py built-unsigned.apk published-signed.apk
"""
import sys, zipfile, hashlib

IGNORE_SUFFIXES = (".SF", ".RSA", ".DSA", ".EC")
IGNORE_NAMES = ("META-INF/MANIFEST.MF",)

def entries(path):
    out = {}
    with zipfile.ZipFile(path) as z:
        for i in z.infolist():
            n = i.filename
            if n in IGNORE_NAMES or (n.startswith("META-INF/") and n.upper().endswith(IGNORE_SUFFIXES)):
                continue
            out[n] = (i.file_size, i.CRC, hashlib.sha256(z.read(n)).hexdigest())
    return out

def main(a, b):
    ea, eb = entries(a), entries(b)
    diffs = []
    for n in sorted(set(ea) | set(eb)):
        if n not in ea: diffs.append(f"only in {b}: {n}")
        elif n not in eb: diffs.append(f"only in {a}: {n}")
        elif ea[n] != eb[n]: diffs.append(f"differs: {n} (size {ea[n][0]} vs {eb[n][0]})")
    for d in diffs: print(d)
    print(f"{len(ea)} entries compared, {len(diffs)} differences")
    return 1 if diffs else 0

if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__); sys.exit(2)
    sys.exit(main(sys.argv[1], sys.argv[2]))
