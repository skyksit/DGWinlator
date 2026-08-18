#!/usr/bin/env python3
"""Rebrand .tzst assets in place: com.winlator -> com.dgplayer (same byte length).

The rootfs and driver archives embed /data/data/com.winlator/... absolute paths
(ELF PT_INTERP, ld.so.cache, locale-archive, configs, one absolute symlink
target). Because the replacement is exactly the same length, every offset-based
structure stays valid, so a plain byte substitution is safe.

The tar stream is rewritten member-by-member with tarfile so that symlink
linkname fields get patched with a correct header checksum, and so that no
symlink is ever materialized on NTFS. Compression is standard zstd frames
(level 19) readable by the on-device zstd-jni decoder.

For each archive the script:
  1. counts occurrences in the fully decompressed original stream (baseline)
  2. rewrites the tar, replacing OLD -> NEW in file contents and in
     name/linkname header fields (ASCII and UTF-16LE patterns)
  3. re-scans the produced archive: residual must be 0 and the number of
     replaced occurrences must equal the baseline
Only then is <file>.patched renamed over the original. Any mismatch aborts.
"""

import io
import os
import sys
import tarfile

import zstandard

OLD = b"com.winlator"
NEW = b"com.dgplayer"
OLD16 = "com.winlator".encode("utf-16-le")
NEW16 = "com.dgplayer".encode("utf-16-le")
assert len(OLD) == len(NEW) == 12
assert len(OLD16) == len(NEW16) == 24

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# expected content-hit counts from the pre-patch survey (advisory cross-check;
# the hard gate is baseline == replaced and residual == 0)
TARGETS = {
    "app/app/src/main/assets/rootfs.tzst": 447,
    "app/app/src/main/assets/container_pattern.tzst": 1,
    "app/app/src/main/assets/box64/box64-0.4.0.tzst": 2,
    "app/app/src/main/assets/graphics_driver/vortek-2.1.tzst": 3,
    "app/app/src/main/assets/graphics_driver/gladio-1.0.tzst": 2,
    "app/app/src/main/assets/graphics_driver/virgl-23.1.9.tzst": 2,
    "app/app/src/main/assets/graphics_driver/turnip-26.1.0.tzst": 1,
    "app/app/src/main/assets/rootfs_patches.tzst": 1,
    "installable_components/box64/box64-0.3.3.tzst": 2,
    "installable_components/box64/box64-0.3.5.tzst": 2,
    "installable_components/box64/box64-0.3.7.tzst": 2,
    "installable_components/turnip/turnip-24.1.0.tzst": 1,
    "installable_components/turnip/turnip-25.0.0.tzst": 1,
    "installable_components/turnip/turnip-26.0.3.tzst": 1,
}


def scan_stream(path, patterns):
    """Count pattern occurrences in the decompressed tar stream of a .tzst."""
    counts = {p: 0 for p in patterns}
    overlap = max(len(p) for p in patterns) - 1
    dctx = zstandard.ZstdDecompressor()
    with open(path, "rb") as f, dctx.stream_reader(f) as zr:
        tail = b""
        while True:
            chunk = zr.read(1 << 20)
            if not chunk:
                break
            buf = tail + chunk
            for p in patterns:
                counts[p] += buf.count(p)
                # occurrences fully inside `tail` were counted by the previous
                # iteration too; subtract them to avoid double counting
                counts[p] -= tail.count(p)
            tail = buf[-overlap:]
    return counts


def patch_archive(path):
    replaced = 0
    dctx = zstandard.ZstdDecompressor()
    # level 22 to stay close to upstream's archive sizes; still a standard frame
    # (window log <= 27) that the on-device zstd-jni decoder accepts by default
    cctx = zstandard.ZstdCompressor(level=22)
    out_path = path + ".patched"
    with open(path, "rb") as fin, dctx.stream_reader(fin) as zr, \
            tarfile.open(fileobj=zr, mode="r|") as src, \
            open(out_path, "wb") as fout, \
            cctx.stream_writer(fout, closefd=False) as zw, \
            tarfile.open(fileobj=zw, mode="w|", format=tarfile.GNU_FORMAT) as dst:
        for m in src:
            for attr in ("name", "linkname"):
                v = getattr(m, attr).encode("utf-8", "surrogateescape")
                if OLD in v:
                    replaced += v.count(OLD)
                    setattr(m, attr, v.replace(OLD, NEW).decode("utf-8", "surrogateescape"))
            if m.isreg():
                buf = src.extractfile(m).read()
                hits = buf.count(OLD)
                hits16 = buf.count(OLD16)
                if hits16:
                    print(f"    note: {hits16} UTF-16LE hit(s) in {m.name}")
                if hits or hits16:
                    replaced += hits + hits16
                    buf = buf.replace(OLD, NEW).replace(OLD16, NEW16)
                assert len(buf) == m.size, m.name
                dst.addfile(m, io.BytesIO(buf))
            else:
                dst.addfile(m)
    return out_path, replaced


def main():
    failures = []
    print(f"{'archive':<62}{'baseline':>9}{'replaced':>9}{'expected':>9}{'residual':>9}")
    for rel, expected in TARGETS.items():
        path = os.path.join(REPO, rel.replace("/", os.sep))
        if not os.path.isfile(path):
            failures.append(f"{rel}: missing")
            print(f"{rel:<62}{'MISSING':>9}")
            continue
        base = scan_stream(path, [OLD, OLD16])
        baseline = base[OLD] + base[OLD16]
        out_path, replaced = patch_archive(path)
        res = scan_stream(out_path, [OLD, OLD16])
        residual = res[OLD] + res[OLD16]
        note = "" if baseline == replaced and residual == 0 else "  <-- MISMATCH"
        if expected not in (None, baseline):
            note += f"  (survey expected {expected})"
        print(f"{rel:<62}{baseline:>9}{replaced:>9}{expected if expected is not None else '-':>9}{residual:>9}{note}")
        if baseline == replaced and residual == 0:
            os.replace(out_path, path)
        else:
            failures.append(f"{rel}: baseline={baseline} replaced={replaced} residual={residual}")
            os.remove(out_path)
    if failures:
        print("\nFAILED — originals left untouched for:")
        for f in failures:
            print("  " + f)
        return 1
    print("\nAll archives patched and verified (0 residual).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
