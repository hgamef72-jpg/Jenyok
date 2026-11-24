#!/usr/bin/env python3
"""Repair Recovery handshake, Power shutdown semantics and Viewer gating."""
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
        value = int(b[pos]); pos += 1
        return value

    def u2():
        nonlocal pos
        value = struct.unpack_from(">H", b, pos)[0]; pos += 2
        return value

    def u4():
        nonlocal pos
        value = struct.unpack_from(">I", b, pos)[0]; pos += 4
        return value

    if u4() != 0xCAFEBABE:
        raise ValueError("Invalid class file")
    u2(); u2()
    cp_count = u2()
    cp = [None] * cp_count
    index = 1
    while index < cp_count:
        tag = u1()
        if tag == 1:
            length = u2()
            cp[index] = bytes(b[pos:pos + length]).decode("utf-8", "replace")
            pos += length
        elif tag in (3, 4):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            pos += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag == 15:
            pos += 3
        else:
            raise ValueError("Unknown constant-pool tag: " + str(tag))
        index += 1

    u2(); u2(); u2()
    interface_count = u2()
    pos += 2 * interface_count

    def skip_members():
        nonlocal pos
        for _ in range(u2()):
            pos += 6
            for __ in range(u2()):
                pos += 2
                length = u4()
                pos += length

    skip_members()  # fields
    result = {}
    for _ in range(u2()):  # methods
        u2()
        name = cp[u2()]
        descriptor = cp[u2()]
        for __ in range(u2()):
            attribute_name = cp[u2()]
            length = u4()
            start = pos
            if attribute_name == "Code":
                pos += 4
                code_length = u4()
                result[(name, descriptor)] = (pos, code_length)
            pos = start + length
    return result


def edit_method(data, name, nops=(), values=()):
    found = [(key, value) for key, value in methods(data).items() if key[0] == name]
    if len(found) != 1:
        raise ValueError("%s: expected exactly one method, found %d" % (name, len(found)))
    (_, _), (start, length) = found[0]
    output = bytearray(data)
    for begin, end in nops:
        if begin < 0 or end > length or end < begin:
            raise ValueError(name + ": patch range exceeds method")
        output[start + begin:start + end] = b"\x00" * (end - begin)
    for offset, opcode in values:
        if offset >= length:
            raise ValueError(name + ": patch offset exceeds method")
        output[start + offset] = opcode
    return bytes(output)


def patch_power_semantics(data):
    found = [(key, value) for key, value in methods(data).items() if key[0] == "pauseHostSession"]
    if len(found) != 1:
        raise ValueError("Power shutdown: expected pauseHostSession once, found %d" % len(found))
    (_, _), (start, length) = found[0]
    code = data[start:start + length]
    # Audited v1.5.77 bytecode: offsets 27..49 write host-session-stop.request.
    # The Recovery supervisor treats that same file as RECOVER_HOST, so Power
    # accidentally restarts the Host instead of leaving it stopped.
    if (length != 57 or code[27] != 0x2A or code[28] != 0x13 or
            code[31] != 0xB9 or code[46] != 0xB8 or
            code[49] != 0x57 or code[50] != 0x13):
        raise ValueError("Power shutdown bytecode differs from audited v1.5.77 layout")
    patched = edit_method(data, "pauseHostSession", nops=((27, 50),))
    patched_start, patched_length = [
        value for key, value in methods(patched).items() if key[0] == "pauseHostSession"
    ][0]
    if patched[patched_start + 27:patched_start + 50] != b"\x00" * 23:
        raise ValueError("Power shutdown patch verification failed")
    return patched


def patch_jar(data):
    source = zipfile.ZipFile(io.BytesIO(data), "r")
    output = io.BytesIO()
    seen = set()
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
                payload = patch_power_semantics(payload)
                seen.add("AppsKt")
            elif info.filename == VIEWER_CLASS:
                payload = edit_method(payload, "mainContent$lambda$32$lambda$31", nops=((7, 14),))
                payload = edit_method(payload, "mainContent$lambda$36$lambda$35", nops=((111, 118),))
                payload = edit_method(payload, "rebuildRemoteScreenTabs$lambda$69$lambda$68$lambda$67", values=((60, 0x04),))
                seen.add("ViewerWindow")
            elif info.filename == CONNECT_CLASS:
                payload = edit_method(payload, "onOpen", values=((93, 0x04),))
                payload = edit_method(payload, "onText$lambda$36", nops=((15, 22),))
                payload = edit_method(payload, "onText$lambda$37", nops=((52, 59),))
                seen.add("Connect")
            target.writestr(info, payload)
    source.close()

    required = {"AppsKt", "ViewerWindow", "Connect"}
    if seen != required:
        raise ValueError("Expected patch targets %s, patched %s" % (required, seen))

    result = output.getvalue()
    with zipfile.ZipFile(io.BytesIO(result), "r") as check:
        if check.testzip() is not None:
            raise ValueError("Patched JAR integrity check failed")
        apps = check.read(AGENT_CLASS)
        if apps.count(b"1.5.8") != 0 or apps.count(b"1.5.9") < 5:
            raise ValueError("Recovery Agent version verification failed after repack")
        app_methods = methods(apps)
        pause = [value for key, value in app_methods.items() if key[0] == "pauseHostSession"]
        if len(pause) != 1 or apps[pause[0][0] + 27:pause[0][0] + 50] != b"\x00" * 23:
            raise ValueError("Power shutdown semantics verification failed")
        methods(check.read(VIEWER_CLASS))
        methods(check.read(CONNECT_CLASS))
    return result


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
        with zipfile.ZipFile(io.BytesIO(check.read(APP_JAR)), "r") as jar:
            if jar.testzip() is not None:
                raise ValueError("Nested JAR integrity check failed")
    print("Patched and verified:", output_path, os.path.getsize(output_path))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: patch_windows_recovery.py input.zip output.zip")
    patch_package(sys.argv[1], sys.argv[2])
