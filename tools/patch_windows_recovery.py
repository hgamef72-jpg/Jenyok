#!/usr/bin/env python3
"""Patch the packaged Windows Viewer so Recovery is callable while connected.

The Recovery Agent can be alive even when its readiness event is delayed or lost.
The original Viewer disabled the button and refused to send RECOVER_HOST unless
HOST_AGENT_READY had already arrived. This patch removes that UI-only deadlock.
It does not fabricate an Agent response or claim a restart succeeded.
"""
import io
import os
import struct
import sys
import zipfile

APP_JAR = "app/saturn-pc-remote.jar"
VIEWER_CLASS = "com/saturn/remote/ViewerWindow.class"
CONNECT_CLASS = "com/saturn/remote/ViewerWindow$connect$1.class"
AGENT_CLASS = "com/saturn/remote/AppsKt.class"


def methods(data):
    b = memoryview(data)
    pos = 0

    def u1():
        nonlocal pos
        v = int(b[pos]); pos += 1
        return v

    def u2():
        nonlocal pos
        v = struct.unpack_from(">H", b, pos)[0]; pos += 2
        return v

    def u4():
        nonlocal pos
        v = struct.unpack_from(">I", b, pos)[0]; pos += 4
        return v

    if u4() != 0xCAFEBABE:
        raise ValueError("Invalid class file")
    u2(); u2()
    cp_count = u2()
    cp = [None] * cp_count
    i = 1
    while i < cp_count:
        tag = u1()
        if tag == 1:
            n = u2()
            cp[i] = bytes(b[pos:pos+n]).decode("utf-8", "replace")
            pos += n
        elif tag in (3, 4):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            pos += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag == 15:
            pos += 3
        else:
            raise ValueError("Unknown constant-pool tag: " + str(tag))
        i += 1

    u2(); u2(); u2()
    pos += 2 * u2()

    def skip_members():
        nonlocal pos
        for _ in range(u2()):
            pos += 6
            for __ in range(u2()):
                pos += 2
                attr_length = u4()
                pos += attr_length

    skip_members()
    result = {}
    for _ in range(u2()):
        u2()
        name = cp[u2()]
        desc = cp[u2()]
        attrs = u2()
        for __ in range(attrs):
            attr_name = cp[u2()]
            length = u4()
            start = pos
            if attr_name == "Code":
                pos += 4
                code_length = u4()
                result[(name, desc)] = (pos, code_length)
            pos = start + length
    return result


def edit_method(data, name, nops=(), values=()):
    found = [(key, value) for key, value in methods(data).items() if key[0] == name]
    if len(found) != 1:
        raise ValueError("%s: expected exactly one method, found %d" % (name, len(found)))
    (_, _), (start, length) = found[0]
    out = bytearray(data)
    for begin, end in nops:
        if end > length:
            raise ValueError("%s: patch range exceeds method" % name)
        out[start+begin:start+end] = b"\x00" * (end-begin)
    for offset, opcode in values:
        if offset >= length:
            raise ValueError("%s: patch offset exceeds method" % name)
        out[start+offset] = opcode
    return bytes(out)


def patch_jar(data):
    source = zipfile.ZipFile(io.BytesIO(data), "r")
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as target:
        for info in source.infolist():
            payload = source.read(info.filename)
            if info.filename == AGENT_CLASS:
                old = payload.count(b"1.5.8")
                new = payload.count(b"1.5.9")
                if old == 2:
                    payload = payload.replace(b"1.5.8", b"1.5.9")
                elif not (old == 0 and new >= 5):
                    raise ValueError("Unexpected Recovery Agent version constants")
                if payload.count(b"1.5.8") != 0 or payload.count(b"1.5.9") < 5:
                    raise ValueError("Recovery Agent version verification failed")
            elif info.filename == VIEWER_CLASS:
                payload = edit_method(payload, "mainContent$lambda$32$lambda$31", nops=((7, 14),))
                payload = edit_method(payload, "mainContent$lambda$36$lambda$35", nops=((111, 118),))
                payload = edit_method(payload, "rebuildRemoteScreenTabs$lambda$69$lambda$68$lambda$67", values=((60, 0x04),))
            elif info.filename == CONNECT_CLASS:
                payload = edit_method(payload, "onOpen", values=((93, 0x04),))
                payload = edit_method(payload, "onText$lambda$36", nops=((15, 22),))
                payload = edit_method(payload, "onText$lambda$37", nops=((52, 59),))
            target.writestr(info, payload)
    source.close()
    with zipfile.ZipFile(io.BytesIO(output.getvalue()), "r") as check:
        if check.testzip() is not None:
            raise ValueError("Patched JAR integrity check failed")
        for name in (VIEWER_CLASS, CONNECT_CLASS):
            methods(check.read(name))
    return output.getvalue()


def patch_package(source_path, output_path):
    with zipfile.ZipFile(source_path, "r") as source, zipfile.ZipFile(
        output_path, "w", zipfile.ZIP_DEFLATED, compresslevel=6
    ) as target:
        found = False
        for info in source.infolist():
            payload = source.read(info.filename)
            if info.filename == APP_JAR:
                payload = patch_jar(payload)
                found = True
            target.writestr(info, payload)
        if not found:
            raise ValueError("Portable package does not contain " + APP_JAR)
    with zipfile.ZipFile(output_path, "r") as check:
        if check.testzip() is not None:
            raise ValueError("Portable ZIP integrity check failed")
    print("Patched and verified:", output_path, os.path.getsize(output_path))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: patch_windows_recovery.py input.zip output.zip")
    patch_package(sys.argv[1], sys.argv[2])
