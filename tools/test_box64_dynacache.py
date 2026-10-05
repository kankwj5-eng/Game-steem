#!/usr/bin/env python3
"""Functional cache test of the locked ARM64 Box64 under QEMU; never an FPS benchmark."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

SOURCE = r'''#include <stdint.h>
#include <stdio.h>
__attribute__((noinline)) uint64_t step(uint64_t x, int i) {
    return (x ^ (x >> 9)) * 6364136223846793005ULL + (unsigned)i;
}
int main(void) {
    uint64_t x = 1234567;
    for (int i = 0; i < 1000000; ++i) x = step(x, i);
    printf("RESULT=%llu\n", (unsigned long long)x);
    return 0;
}
'''
LIBRARIES = ["libc.so.6", "libm.so.6", "libpthread.so.0", "libdl.so.2", "libz.so.1",
             "libz.so.1.3.1", "ld-linux-aarch64.so.1", "libgcc_s.so.1", "libresolv.so.2"]


def main():
    source = Path(sys.argv[1] if len(sys.argv) > 1 else "winlator-app").resolve()
    evidence = Path(sys.argv[2] if len(sys.argv) > 2 else "out/box64-cache-proof").resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    qemu = os.environ.get("QEMU_AARCH64") or shutil.which("qemu-aarch64-static")
    if not qemu:
        raise SystemExit("QEMU_AARCH64 or qemu-aarch64-static is required")
    assets = source / "app/src/main/assets"
    with tempfile.TemporaryDirectory(prefix="box64-cache-probe-") as temporary:
        root = Path(temporary)
        native = root / "native"
        native.mkdir()
        subprocess.run(["tar", "--no-same-owner", "--zstd", "-xf", str(assets / "rootfs.tzst"),
                        "-C", str(native), *["./usr/lib/" + name for name in LIBRARIES]], check=True)
        subprocess.run(["tar", "--no-same-owner", "--zstd", "-xf", str(assets / "box64/box64-0.4.4.tzst"),
                        "-C", str(native), "./usr/local/bin/box64"], check=True)
        code, executable = root / "probe.c", root / "probe-x86_64"
        code.write_text(SOURCE)
        subprocess.run(["cc", "-O2", "-no-pie", "-o", str(executable), str(code)], check=True)
        expected = subprocess.check_output([str(executable)], text=True).strip()
        command = [qemu, "-cpu", "max", str(native / "usr/lib/ld-linux-aarch64.so.1"),
                   "--library-path", str(native / "usr/lib"), str(native / "usr/local/bin/box64"), str(executable)]
        version = subprocess.check_output(command[:-1] + ["--version"], text=True, stderr=subprocess.STDOUT).strip()
        assert "v0.4.4" in version, "Unexpected Box64 binary"
        env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": str(root),
               "LD_LIBRARY_PATH": str(native / "usr/lib"), "BOX64_DYNACACHE": "1",
               "BOX64_DYNACACHE_FOLDER": str(root / "cache"), "BOX64_DYNACACHE_LIMIT": "128",
               "BOX64_DYNACACHE_MIN": "0", "BOX64_DYNAREC_LOG": "1", "BOX64_LOG": "1"}

        def run(name, overrides=None, output=expected):
            process = subprocess.run(command, env=env | (overrides or {}), stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, timeout=50)
            log = process.stdout.decode(errors="replace")
            (evidence / (name + ".log")).write_text(log)
            if process.returncode != 0 or output not in log:
                raise AssertionError(f"{name}: exit={process.returncode}; expected={output}; see evidence")
            return log

        assert "Loaded DynaCache" not in run("cold")
        files = list((root / "cache").glob("*.box64"))
        assert files, "Cold launch did not save translated code"
        assert "Loaded DynaCache" in run("warm")
        cache = files[0]
        def digest():
            return hashlib.sha256(cache.read_bytes()).hexdigest()
        before = digest()
        assert "Loaded DynaCache" in run("readonly", {"BOX64_DYNACACHE": "2"})
        assert digest() == before, "Read-only mode changed cached code"
        with cache.open("r+b") as stream:
            stream.write(b"INVALID-CACHE-HEADER")
        assert "Loaded DynaCache" not in run("corrupt")
        assert "Loaded DynaCache" in run("regenerated")
        assert "Loaded DynaCache" not in run("disabled", {"BOX64_DYNACACHE": "0"})
        unavailable = root / "folder-is-file"
        unavailable.write_text("not a directory")
        assert "Loaded DynaCache" not in run("unavailable", {"BOX64_DYNACACHE_FOLDER": str(unavailable)})
        previous_size = executable.stat().st_size
        code.write_text(SOURCE.replace("6364136223846793005ULL", "6364136223846793007ULL"))
        subprocess.run(["cc", "-O2", "-no-pie", "-o", str(executable), str(code)], check=True)
        updated = subprocess.check_output([str(executable)], text=True).strip()
        assert updated != expected and executable.stat().st_size == previous_size
        run("changed-same-size", output=updated)
        result = {"result": "PASS", "version": version,
                  "cases": ["cold saves", "warm loads", "read-only preserves code", "corrupt rejected and rebuilt",
                            "disabled", "unusable storage fallback", "changed same-size binary returns updated result"],
                  "initialOutput": expected, "updatedOutput": updated,
                  "minimumCacheKiB": 0,
                  "limits": "Small x86_64 ELF with actual Winlator ARM64 Box64 and ARM64 glibc under QEMU. "
                            "Test lowers minimum cache threshold only for its small fixture. No Steam, Android seccomp or Mali FPS measurement."}
        (evidence / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
