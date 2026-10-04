#!/usr/bin/env python3
"""Copy only the locked public-domain C decoder into an effective source tree."""
import hashlib
import json
import re
from pathlib import Path
import sys
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SOURCES = "Bcj2 Bra Bra86 BraIA64 CpuArch Delta Lzma2Dec LzmaDec Ppmd7 Ppmd7Dec 7zCrc 7zCrcOpt 7zArcIn 7zBuf 7zBuf2 7zDec 7zFile 7zStream".split()


def prepare(source_root, archive_path=None):
    spec = json.loads((ROOT / "native-dependencies.lock").read_text())["sevenzip"]
    commit = spec["commit"]
    if not re.fullmatch(r"[0-9a-f]{40}", commit) or spec["url"] != "https://api.github.com/repos/ip7z/7zip/tarball/" + commit:
        raise RuntimeError("Fuente SDK fuera del repositorio HTTPS fijado")
    destination = Path(source_root) / "app/src/main/cpp/steamarchive/third_party/lzma_sdk"
    receipt = destination / "SOURCE_SHA256"
    if receipt.is_file() and receipt.read_text().strip() == spec["sha256"]:
        return
    with tempfile.TemporaryDirectory(prefix="droiddeck-sdk-") as directory:
        archive = Path(archive_path) if archive_path else Path(directory) / "sdk.tar.gz"
        if archive_path is None:
            # URL must match the fixed HTTPS host/repository/hex commit above; SHA is checked below.
            urllib.request.urlretrieve(spec["url"], archive)  # nosemgrep: python.lang.security.audit.dynamic-urllib-use-detected.dynamic-urllib-use-detected
        with archive.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        if digest != spec["sha256"]:
            raise RuntimeError("SHA inesperado del código fuente de 7-Zip")
        destination.mkdir(parents=True, exist_ok=True)
        copied = set()
        with tarfile.open(archive) as bundle:
            for member in bundle.getmembers():
                relative = Path(*Path(member.name).parts[1:])
                allowed = (relative.parent == Path("C") and
                           (relative.suffix == ".h" or relative.name in {name + ".c" for name in SOURCES}))
                allowed |= relative in [Path("DOC/License.txt"), Path("DOC/copying.txt"), Path("DOC/7zC.txt")]
                if not allowed or not member.isfile():
                    continue
                target = destination / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                with bundle.extractfile(member) as incoming, target.open("wb") as outgoing:
                    outgoing.write(incoming.read())
                copied.add(relative)
        for name in SOURCES:
            if Path("C", name + ".c") not in copied:
                raise RuntimeError("Fuente de decoder incompleta: " + name)
        receipt.write_text(spec["sha256"] + "\n")
    print("7-Zip C decoder preparado: " + spec["version"] + " · SHA verificado")


if __name__ == "__main__":
    prepare(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
