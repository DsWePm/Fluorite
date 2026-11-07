#!/usr/bin/env python3
"""Run one bounded Minecraft path probe, archive its inputs, and restore window options."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tomllib

REPO = Path(__file__).resolve().parent.parent
WINDOW = rb"(?m)^(overrideWidth|overrideHeight):[^\r\n]*"


def clients():
    if os.name == "nt":
        query = ("Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and "
                 "($_.CommandLine -match 'net.fabricmc.loader.impl.launch.knot.KnotClient|"
                 "net.minecraft.client.main.Main') } | Select-Object -ExpandProperty ProcessId")
        result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", query],
                                capture_output=True, text=True, check=True)
        return result.stdout.split()
    result = subprocess.run(["ps", "-eo", "pid,args"], capture_output=True, text=True, check=True)
    return [line.split()[0] for line in result.stdout.splitlines()
            if "java" in line and ("KnotClient" in line or "net.minecraft.client.main.Main" in line)]


def window_options(original, width, height):
    active = original
    for name, value in [(b"overrideWidth", width), (b"overrideHeight", height)]:
        active, count = re.subn(rb"(?m)^" + name + rb":[^\r\n]*",
                                name + b":" + str(value).encode(), active)
        if count != 1:
            raise ValueError("Expected exactly one " + name.decode() + " option")
    return active


def restore_window(original, active, current):
    normalize = lambda data: re.sub(WINDOW, rb"\1:BENCH", data)
    if normalize(current) == normalize(active):
        return original
    # Preserve unrelated options edited by the user or game during a run.
    values = dict(re.findall(rb"(?m)^(overrideWidth|overrideHeight):([^\r\n]*)", original))
    return re.sub(WINDOW, lambda match: match[1] + b":" + values[match[1]], current)


def source_hashes():
    return {str(path.relative_to(REPO)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted((REPO / "shaders").rglob("*")) if path.is_file()}


def save_json(path, data):
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")


def validate_config(config):
    if not config.get("composite", {}).get("path-reservoir", False):
        raise ValueError("composite.path-reservoir must be enabled for a bounded path probe")
    if not config.get("diagnostics", {}).get("path-reuse-identity-check", False):
        raise ValueError("diagnostics.path-reuse-identity-check must be enabled")
    if config.get("composite", {}).get("debug-view", 0) != 0:
        raise ValueError("composite.debug-view must be 0 so path readbacks can advance")


def restore(run):
    if clients():
        raise RuntimeError("Minecraft is still running; options restoration is deferred")
    options = REPO / "run/options.txt"
    original = (run / "original-options.txt").read_bytes()
    options.write_bytes(restore_window(original, (run / "active-options.txt").read_bytes(), options.read_bytes()))
    state = dict(options_byte_restored=options.read_bytes() == original,
                 configuration_unchanged=(REPO / "run/config/fluorite.toml").read_bytes()
                 == (run / "original-fluorite.toml").read_bytes())
    save_json(run / "restoration.json", state)
    (run / "RESTORE_PENDING.txt").unlink(missing_ok=True)
    return state


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="New evidence directory; existing directories are rejected")
    parser.add_argument("--restore", type=Path, help="Finish deferred restoration after Minecraft has exited")
    parser.add_argument("--world", default="restir-r1-smoke")
    parser.add_argument("--readbacks", type=int, default=20000)
    parser.add_argument("--width", type=int, default=640)
    parser.add_argument("--height", type=int, default=360)
    parser.add_argument("--keep-going", action="store_true", help="Continue after a numeric failure, up to the cap")
    args = parser.parse_args()
    if args.restore:
        print(json.dumps(restore(args.restore.resolve()), indent=2))
        return 0
    if args.output is None or min(args.readbacks, args.width, args.height) <= 0:
        parser.error("--output and positive readback/dimension values are required")
    if clients():
        parser.error("Minecraft is already running; close it before starting a probe")
    if not (REPO / "run/saves" / args.world).is_dir():
        parser.error("Benchmark world does not exist: " + args.world)
    validate_config(tomllib.loads((REPO / "run/config/fluorite.toml").read_text(encoding="utf-8")))
    run = args.output.resolve()
    run.mkdir(parents=True, exist_ok=False)
    options = REPO / "run/options.txt"
    original = options.read_bytes()
    active = window_options(original, args.width, args.height)
    (run / "original-options.txt").write_bytes(original)
    (run / "active-options.txt").write_bytes(active)
    (run / "original-fluorite.toml").write_bytes((REPO / "run/config/fluorite.toml").read_bytes())
    before = source_hashes()
    save_json(run / "source-hashes.json", before)
    java_home = Path(os.environ.get("JAVA_HOME", "")) / "bin" / ("java.exe" if os.name == "nt" else "java")
    java = str(java_home) if java_home.is_file() else shutil.which("java")
    if not java:
        raise RuntimeError("Java is not on PATH or JAVA_HOME")
    # Invoke the wrapper directly, so Windows batch-shell quoting cannot reinterpret a world name.
    command = [java, "-Xmx64m", "-Xms64m", "-classpath", str(REPO / "gradle/wrapper/gradle-wrapper.jar"),
               "org.gradle.wrapper.GradleWrapperMain", ":fabric:runClient", "--console=plain",
               f"-PbenchWorld={args.world}", f"-PbenchWidth={args.width}", f"-PbenchHeight={args.height}",
               f"-PbenchPathProbeReadbacks={args.readbacks}",
               "-Pfluorite.probes=true",
               "-PbenchPathProbeStopOnFailure=" + ("false" if args.keep_going else "true")]
    manifest = dict(command=command, world=args.world, readback_limit=args.readbacks,
                    display_dimensions=[args.width, args.height], source_commit=subprocess.check_output(
                        ["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip(),
                    started_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                    purpose="Numeric diagnosis; not performance or visual acceptance")
    save_json(run / "manifest.json", manifest)
    (run / "source-diff.patch").write_bytes(subprocess.check_output(["git", "diff", "--binary"], cwd=REPO))
    frame = REPO / "run/rt-frame-stats/frame.csv"
    previous_frame_time = frame.stat().st_mtime_ns if frame.is_file() else None
    options.write_bytes(active)
    try:
        print("Running bounded path probe; evidence: " + str(run), flush=True)
        with (run / "runtime.log").open("wb") as log:
            result = subprocess.run(command, cwd=REPO, stdout=log, stderr=subprocess.STDOUT)
        text = (run / "runtime.log").read_text(encoding="utf-8", errors="replace")
        exits = re.findall(r"RT path probe requesting normal exit: (\w+) after (\d+) readbacks", text)
        normal = result.returncode == 0 and "BUILD SUCCESSFUL" in text and "All dimensions are saved" in text and bool(exits)
        summary = dict(returncode=result.returncode, normal_exit=normal, exit_requests=exits,
                       source_unchanged=before == source_hashes(),
                       failed_readbacks=len(re.findall(r"RT path reuse failed readback", text)),
                       last_cumulative=next((line for line in reversed(text.splitlines())
                                             if "RT path reuse cumulative:" in line), None))
        modules = {}
        for path in sorted((REPO / "build/generated/shaders/fluorite/rt").glob("*.spv")):
            runtime = REPO / "fabric/build/resources/main/fluorite/rt" / path.name
            modules[path.name] = dict(sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                                      runtime_byte_match=runtime.is_file() and runtime.read_bytes() == path.read_bytes())
        save_json(run / "modules.json", modules)
        summary["runtime_modules_match"] = bool(modules) and all(m["runtime_byte_match"] for m in modules.values())
        summary["frame_csv_updated"] = frame.is_file() and frame.stat().st_mtime_ns != previous_frame_time
        if summary["frame_csv_updated"]:
            shutil.copy2(frame, run / "frame.csv")
        save_json(run / "result.json", summary)
        print(json.dumps(summary, indent=2), flush=True)
        return 0 if normal and summary["source_unchanged"] and summary["runtime_modules_match"] and summary["failed_readbacks"] == 0 else 2
    finally:
        try:
            print("Restoration: " + json.dumps(restore(run)), flush=True)
        except Exception as error:
            (run / "RESTORE_PENDING.txt").write_text(str(error), encoding="utf-8")
            print("Restoration deferred: " + str(error), file=sys.stderr, flush=True)
            raise RuntimeError("Probe restoration is pending; see " + str(run)) from error


if __name__ == "__main__":
    raise SystemExit(main())
