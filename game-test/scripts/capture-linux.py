#!/usr/bin/env python3
"""Request one vanilla Minecraft F2 screenshot on an isolated Linux X11 display.

The caller must launch a fresh client at 1280x720 with guiScale:2 and the default
L/F2 bindings, then invoke this script once per stage, in order. VisualGameTest
sends ``KTADVANCEMENTS_VISUAL_STAGE <stage>`` to the player's chat after updating
the advancements. Only the client's logs/latest.log is accepted as the readiness
signal; a server log or a merely visible launcher is not sufficient.

This script sends input only. The Gradle caller must detect the new Minecraft PNG,
validate/copy it, and acknowledge the stage. Run the whole client/test process in
one xvfb-run session, not a separate Xvfb session for each script invocation.
"""

import argparse
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time


STAGES = ("zero", "partial", "complete", "revoked")
CLIENT_WIDTH = 1280
CLIENT_HEIGHT = 720
HOVER_X = 680
HOVER_Y = 368
TITLE_PATTERN = re.compile(r"Minecraft\*?\s+([0-9]+\.[0-9]+(?:\.[0-9]+)?)(?:\s+-\s+.*)?")
STAGE_PATTERN = re.compile(r"KTADVANCEMENTS_VISUAL_STAGE (zero|partial|complete|revoked)(?:\s|$)")
LOG_TAIL_BYTES = 2 * 1024 * 1024


class CaptureError(Exception):
    """A failed precondition or bounded external operation."""


def positive_timeout(value):
    try:
        seconds = float(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be a positive number of seconds") from error
    if not math.isfinite(seconds) or seconds <= 0:
        raise argparse.ArgumentTypeError("must be a finite positive number of seconds")
    return seconds


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--stage", required=True, choices=STAGES)
    parser.add_argument("--client-log", required=True, type=Path, help="fresh client's logs/latest.log")
    parser.add_argument("--version", help="exact release in the Minecraft window title, e.g. 1.17.1 or 26.2")
    parser.add_argument("--timeout", type=positive_timeout, default=45.0, help="total timeout in seconds (default: 45)")
    args = parser.parse_args(argv)
    if args.version and not re.fullmatch(r"[0-9]+\.[0-9]+(?:\.[0-9]+)?", args.version):
        parser.error("--version must be a numeric stable Minecraft release")
    return args


class Xdotool:
    def __init__(self, executable, timeout):
        self.executable = executable
        self.deadline = time.monotonic() + timeout

    def remaining(self):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise CaptureError("Timed out waiting for the client window/stage or completing screenshot input")
        return remaining

    def sleep(self, seconds):
        if self.remaining() < seconds:
            raise CaptureError("Timed out before the required client rendering delay could complete")
        time.sleep(seconds)

    def run(self, *arguments, allow_no_match=False):
        try:
            result = subprocess.run(
                [self.executable, *map(str, arguments)],
                stdin=subprocess.DEVNULL,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=min(5.0, self.remaining()),
                env={**os.environ, "LC_ALL": "C"},
                check=False,
            )
        except subprocess.TimeoutExpired as error:
            raise CaptureError(f"xdotool {arguments[0]} timed out") from error
        except OSError as error:
            raise CaptureError(f"Could not run xdotool: {error}") from error
        if allow_no_match and result.returncode == 1 and not result.stderr.strip():
            return ""
        if result.returncode != 0 or "XTEST extension unavailable" in result.stderr:
            detail = result.stderr.strip()[:500] or f"exit status {result.returncode}"
            raise CaptureError(f"xdotool {arguments[0]} failed: {detail}")
        return result.stdout.strip()


def integer(value):
    try:
        return int(value, 16 if value.lower().startswith("0x") else 10)
    except ValueError as error:
        raise CaptureError("xdotool returned an invalid integer") from error


def shell_fields(output, required):
    """Parse xdotool's --shell output as data, never as executable shell text."""
    fields = {}
    for line in output.splitlines():
        key, separator, value = line.partition("=")
        if not separator or key in fields:
            raise CaptureError("xdotool returned malformed or duplicate geometry fields")
        fields[key] = integer(value)
    if not set(required).issubset(fields):
        raise CaptureError("xdotool omitted required window/mouse geometry fields")
    return fields


def find_window(xdo, version):
    ids = xdo.run("search", "--onlyvisible", "--name", "^Minecraft", allow_no_match=True)
    matches = []
    for window_id in sorted({integer(value) for value in ids.splitlines()}):
        title = xdo.run("getwindowname", window_id)
        match = TITLE_PATTERN.fullmatch(title)
        if match and (version is None or match.group(1) == version):
            matches.append(window_id)
    if len(matches) > 1:
        raise CaptureError("Multiple matching Minecraft windows; use an isolated Xvfb display with one client")
    return matches[0] if matches else None


def client_stage(log_path):
    try:
        with log_path.open("rb") as log:
            log.seek(0, os.SEEK_END)
            log.seek(max(0, log.tell() - LOG_TAIL_BYTES))
            tail = log.read(LOG_TAIL_BYTES).decode("utf-8", errors="replace")
    except FileNotFoundError:
        return None
    except OSError as error:
        raise CaptureError(f"Could not read --client-log: {error}") from error
    latest = None
    for line in tail.splitlines():
        if "[CHAT]" in line:
            match = STAGE_PATTERN.search(line)
            if match:
                latest = match.group(1)
    return latest


def wait_for_client(xdo, args):
    print(f"Waiting for one Minecraft window and client chat stage '{args.stage}'", flush=True)
    while True:
        xdo.remaining()
        window_id = find_window(xdo, args.version)
        if window_id is not None and client_stage(args.client_log) == args.stage:
            return window_id
        xdo.sleep(0.25)


def geometry(xdo, window_id):
    values = shell_fields(
        xdo.run("getwindowgeometry", "--shell", window_id),
        ("WINDOW", "X", "Y", "WIDTH", "HEIGHT", "SCREEN"),
    )
    if values["WINDOW"] != window_id:
        raise CaptureError("Window geometry belonged to a different window")
    if (values["WIDTH"], values["HEIGHT"]) != (CLIENT_WIDTH, CLIENT_HEIGHT):
        raise CaptureError(
            f"Expected a {CLIENT_WIDTH}x{CLIENT_HEIGHT} client area, "
            f"got {values['WIDTH']}x{values['HEIGHT']}; check client resolution/fullscreen settings"
        )
    return values


def assert_input_target(xdo, args, window_id, expected_geometry):
    if find_window(xdo, args.version) != window_id:
        raise CaptureError("The selected Minecraft window disappeared or changed")
    if geometry(xdo, window_id) != expected_geometry:
        raise CaptureError("Minecraft window geometry changed during capture")
    if integer(xdo.run("getwindowfocus", "-f")) != window_id:
        raise CaptureError("Minecraft lost keyboard focus; refusing to send input")
    if client_stage(args.client_log) != args.stage:
        raise CaptureError("Client chat no longer reports the requested visual-test stage")


def capture(args):
    if sys.platform != "linux":
        raise CaptureError("This driver supports Linux/X11 only; use manual F2 capture mode on Windows")
    if not os.environ.get("DISPLAY", "").strip():
        raise CaptureError("DISPLAY is not set; run the client and driver inside the same xvfb-run session")
    executable = shutil.which("xdotool")
    if executable is None:
        raise CaptureError("xdotool is not installed (Debian/Ubuntu: apt-get install xdotool xvfb xauth)")

    xdo = Xdotool(executable, args.timeout)
    window_id = wait_for_client(xdo, args)
    bounds = geometry(xdo, window_id)
    xdo.run("windowraise", window_id)
    # XSetInputFocus works without a window manager. windowactivate/getactivewindow
    # require EWMH support and therefore must not be used on bare Xvfb.
    xdo.run("windowfocus", "--sync", window_id)
    xdo.sleep(0.25)

    if args.stage == "zero":
        # Packet receipt precedes the initial terrain rendering. Allow the fresh
        # world to settle, then send L exactly once; retrying it could close the UI.
        xdo.sleep(5.0)
        assert_input_target(xdo, args, window_id, bounds)
        # A separate command with no --window/window stack uses XTEST, not the
        # XSendEvent path that GLFW applications may ignore.
        xdo.run("key", "--clearmodifiers", "--delay", 100, "l")
        xdo.sleep(1.0)

    assert_input_target(xdo, args, window_id, bounds)
    # getwindowgeometry uses the client origin in root coordinates, not the outer
    # decoration/frame origin, so no guessed title-bar offset is needed.
    target_x = bounds["X"] + HOVER_X
    target_y = bounds["Y"] + HOVER_Y
    # --sync waits for an actual move and can hang when the previous stage
    # already left the pointer here. Position is checked explicitly below.
    xdo.run("mousemove", "--screen", bounds["SCREEN"], target_x, target_y)
    xdo.sleep(3.0)
    assert_input_target(xdo, args, window_id, bounds)
    pointer = shell_fields(xdo.run("getmouselocation", "--shell"), ("X", "Y", "SCREEN", "WINDOW"))
    if (pointer["X"], pointer["Y"], pointer["SCREEN"], pointer["WINDOW"]) != (
        target_x, target_y, bounds["SCREEN"], window_id
    ):
        raise CaptureError("Hover target was not reached or is covered; the advancement screen may not be open")
    xdo.run("key", "--clearmodifiers", "--delay", 100, "F2")
    print(f"Sent F2 for stage '{args.stage}'; Gradle must verify the new PNG before acknowledging", flush=True)


def main(argv=None):
    args = parse_args(argv)
    try:
        capture(args)
    except CaptureError as error:
        print(f"capture-linux: {error}", file=sys.stderr, flush=True)
        return 1
    except KeyboardInterrupt:
        print("capture-linux: interrupted", file=sys.stderr, flush=True)
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
