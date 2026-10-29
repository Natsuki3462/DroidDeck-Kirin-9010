#!/usr/bin/env python3
"""Build the CPU-renderer driver pack that DroidDeck imports on a Kirin device.

The pack is not committed (it is ~58 MB), but it is reproducible from two unmodified
Arch Linux ARM packages. Pick the versions that match the runtime the app installs:
`linuxfs r9` carries Mesa 26.2 / loader 1.4.357 / libLLVM 22, and Arch's current
`vulkan-swrast` is built against LLVM 23 — hence llvm-libs travels with the pack.

    python3 build-lavapipe-pack.py            # writes DroidDeck-Lavapipe-arm64.zip

Why not Debian bullseye's `mesa-vulkan-drivers`? Its lavapipe is Mesa 20.3, i.e. Vulkan
1.1, and gamescope 3.16 refuses a 1.1 device ("failed to find physical device").
"""

import io
import json
import os
import sys
import tarfile
import urllib.request
import zipfile

MIRROR = "https://mirrors.tuna.tsinghua.edu.cn/archlinuxarm/aarch64/extra"
PACKAGES = [
    ("vulkan-swrast-1%3A26.2.5-1-aarch64.pkg.tar.xz", "usr/lib/libvulkan_lvp.so"),
    ("llvm-libs-23.1.4-1.1-aarch64.pkg.tar.xz", "usr/lib/libLLVM.so.23.1"),
]

META = {
    "schemaVersion": 1,
    "kind": "linux-vulkan-icd",
    "name": "lavapipe (Mesa 26.2)",
    "driverVersion": "Mesa 26.2.5 lavapipe",
    "libc": "glibc",
    "minGlibc": "2.38",
    "library": "libvulkan_lvp.so",
    # The manifest the importer generates states this; without it a 1.4 driver is
    # treated as 1.1 and a compositor needing 1.3 reports no physical device.
    "apiVersion": "1.4.0",
    "renderer": "llvmpipe",
    "note": "CPU Vulkan, unmodified Arch Linux ARM packages matching the linuxfs r9 runtime. "
            "The runtime ships LLVM 22, so LLVM 23.1 travels with the pack.",
}

OUT = "DroidDeck-Lavapipe-arm64.zip"


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "droiddeck-pack"})
    with urllib.request.urlopen(req, timeout=300) as response:
        return response.read()


def main():
    staging = "pack"
    os.makedirs(staging, exist_ok=True)

    for filename, member in PACKAGES:
        print(f"fetching {filename}")
        blob = fetch(f"{MIRROR}/{filename}")
        with tarfile.open(fileobj=io.BytesIO(blob)) as archive:
            entry = next((m for m in archive if m.name.lstrip("./") == member), None)
            if entry is None:
                sys.exit(f"{member} not in {filename}")
            data = archive.extractfile(entry).read()
        target = os.path.join(staging, os.path.basename(member))
        with open(target, "wb") as out:
            out.write(data)
        print(f"  {os.path.basename(member)}  {len(data) / 1048576:.1f} MiB")

    with open(os.path.join(staging, "meta.json"), "w", encoding="utf-8") as out:
        json.dump(META, out, indent=2)

    names = sorted(os.listdir(staging))
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name in names:
            archive.write(os.path.join(staging, name), name)

    print(f"\n{OUT}  {os.path.getsize(OUT) / 1048576:.1f} MiB")
    print("import it from Components -> runtime driver -> import .zip")


if __name__ == "__main__":
    main()
