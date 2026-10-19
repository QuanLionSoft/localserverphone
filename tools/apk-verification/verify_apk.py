import hashlib
import json
import re
import struct
import sys
import zipfile
from pathlib import Path

apk = Path(sys.argv[1]).absolute()
project = Path(sys.argv[2]).absolute()
with zipfile.ZipFile(apk) as archive:
    entries = archive.infolist()
    names = [entry.filename for entry in entries]
    assert len(names) == len(set(names)), "APK contains duplicate ZIP entries"
    assert archive.testzip() is None, "APK ZIP CRC failed"
    assert archive.getinfo("resources.arsc").compress_type == zipfile.ZIP_STORED
    assert archive.read("AndroidManifest.xml")[:4] == b"\x03\x00\x08\x00"
    assert archive.read("resources.arsc")[:2] == b"\x02\x00"
    dex_files = sorted(name for name in names if re.fullmatch(r"classes\d*\.dex", name))
    assert dex_files, "APK contains no DEX code"
    dex_data = [archive.read(name) for name in dex_files]
    for data in dex_data:
        assert data[:4] == b"dex\n" and data[7] == 0, "Invalid DEX magic"
        assert struct.unpack_from("<I", data, 32)[0] == len(data), "DEX file size mismatch"
        assert hashlib.sha1(data[32:]).digest() == data[12:32], "DEX SHA-1 mismatch"
    classes = [
        "Ldev/pocketpaas/MainActivity;",
        "Ldev/pocketpaas/ApplicationClass;",
        "Ldev/pocketpaas/presentation/dashboard/MainViewModel;",
        "Ldev/pocketpaas/data/remote/android/RemoteServerService;",
        "Ldev/pocketpaas/data/deployment/DeploymentOrchestrator;",
        "Ldev/pocketpaas/presentation/setup/SetupViewModel;",
        "Ldev/pocketpaas/presentation/setup/SetupScreenKt;",
        "Ldev/pocketpaas/data/setup/TermuxSetupVerifier;",
        "Ldev/pocketpaas/domain/setup/TermuxSetupInstructions;",
        "Ldev/pocketpaas/data/system/AndroidSystemManager;",
        "Ldev/pocketpaas/domain/setup/TermuxPermissionStatus;",
        "Ldev/pocketpaas/domain/setup/ForegroundStartGate;",
        "Ldev/pocketpaas/data/process/termux/BoundedProcessInputStream;",
        "Ldev/pocketpaas/data/process/termux/RemoteProcess;",
        "Ldev/pocketpaas/data/process/ShellExecutor;",
    ]
    for name in classes:
        assert any(name.encode() in data for data in dex_data), f"Missing application class: {name}"
    asset_hashes = {}
    for name in ["pocketpaas_agent.py", "pocketpaas_files.py", "pocketpaas_deploy.py"]:
        actual = archive.read("assets/" + name)
        assert actual == (project / "app/src/main/assets" / name).read_bytes(), f"Stale runtime helper: {name}"
        asset_hashes[name] = hashlib.sha256(actual).hexdigest()
    forbidden = [name for name in names if name.endswith((".keystore", ".jks", ".env", ".pyc"))]
    assert not forbidden, "APK contains unexpected private/build files"
    elf_alignments = {}
    for name in names:
        if not (name.startswith("lib/") and name.endswith(".so")):
            continue
        data = archive.read(name)
        assert data[:4] == b"\x7fELF", f"Invalid native library: {name}"
        endian = "<" if data[5] == 1 else ">"
        is64 = data[4] == 2
        phoff = struct.unpack_from(endian + ("Q" if is64 else "I"), data, 32 if is64 else 28)[0]
        size, count = struct.unpack_from(endian + "HH", data, 54 if is64 else 42)
        alignments = []
        for number in range(count):
            offset = phoff + number * size
            if struct.unpack_from(endian + "I", data, offset)[0] == 1:
                alignments.append(struct.unpack_from(endian + ("Q" if is64 else "I"), data, offset + (48 if is64 else 28))[0])
        assert alignments and all(value >= 16384 for value in alignments), f"Native load segment alignment is below 16 KiB: {name}"
        elf_alignments[name] = alignments
    report = {
        "apk": str(apk),
        "bytes": apk.stat().st_size,
        "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "entries": len(entries),
        "zipCrcValid": True,
        "resourceTableStored": True,
        "dex": dex_files,
        "requiredApplicationClassesPresent": classes,
        "nativeLibraries": [name for name in names if name.startswith("lib/") and name.endswith(".so")],
        "elfLoadSegmentAlignments": elf_alignments,
        "runtimeAssetSha256": asset_hashes,
    }
    print(json.dumps(report, indent=2))
