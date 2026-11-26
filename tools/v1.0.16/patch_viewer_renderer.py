#!/usr/bin/env python3
import io
import pathlib
import sys
import zipfile

APP_JAR = "app/saturn-pc-remote.jar"
RENDERER_CLASS = "com/saturn/remote/FrameRenderer.class"
LISTENER_CLASS = "com/saturn/remote/ViewerWindow$connect$1.class"


def replace_nested_jar(data: bytes, renderer: bytes) -> bytes:
    source = io.BytesIO(data)
    output = io.BytesIO()
    with zipfile.ZipFile(source, "r") as original, zipfile.ZipFile(output, "w") as patched:
        names = set(original.namelist())
        if LISTENER_CLASS not in names:
            raise SystemExit("RC2 Viewer listener class is missing")
        listener = original.read(LISTENER_CLASS)
        if b"FrameRenderer" not in listener or b"onBinary" not in listener:
            raise SystemExit("RC2 Viewer listener does not call the FrameRenderer helper")

        for item in original.infolist():
            if item.filename == RENDERER_CLASS:
                continue
            patched.writestr(item, original.read(item.filename))
        patched.writestr(RENDERER_CLASS, renderer, compress_type=zipfile.ZIP_DEFLATED)

    result = output.getvalue()
    with zipfile.ZipFile(io.BytesIO(result), "r") as check:
        bad = check.testzip()
        if bad:
            raise SystemExit(f"Nested JAR integrity failure: {bad}")
        if RENDERER_CLASS not in check.namelist():
            raise SystemExit("Updated FrameRenderer class was not injected")
    return result


def patch_package(source: pathlib.Path, destination: pathlib.Path, renderer_path: pathlib.Path) -> None:
    renderer = renderer_path.read_bytes()
    output = destination.with_suffix(destination.suffix + ".tmp")
    found = False
    with zipfile.ZipFile(source, "r") as original, zipfile.ZipFile(output, "w") as patched:
        for item in original.infolist():
            data = original.read(item.filename)
            if item.filename == APP_JAR:
                data = replace_nested_jar(data, renderer)
                found = True
            patched.writestr(item, data)
    if not found:
        output.unlink(missing_ok=True)
        raise SystemExit("Embedded application JAR not found in Viewer package")

    with zipfile.ZipFile(output, "r") as check:
        bad = check.testzip()
        if bad:
            output.unlink(missing_ok=True)
            raise SystemExit(f"Viewer ZIP integrity failure: {bad}")
        if APP_JAR not in check.namelist():
            output.unlink(missing_ok=True)
            raise SystemExit("Patched Viewer package is missing its application JAR")
    output.replace(destination)


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit("usage: patch_viewer_renderer.py input.zip output.zip FrameRenderer.class")
    patch_package(pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3]))
    print(f"Patched and verified Viewer stream renderer: {sys.argv[2]}")
