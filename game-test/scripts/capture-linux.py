#!/usr/bin/env python3
"""Join a local visual test or request a vanilla F2 screenshot on Linux X11.

The caller must launch a fresh client at 1280x720 with guiScale:2 and the default
L/F2 bindings, then invoke this script once per stage, in order. VisualGameTest
sends ``KTADVANCEMENTS_VISUAL_STAGE <stage>`` to the player's chat after updating
the advancements. Only the client's logs/latest.log is accepted as the readiness
signal; a server log or a merely visible launcher is not sufficient.

This script sends input only. The Gradle caller must detect the new Minecraft PNG,
validate/copy it, and acknowledge the stage. Run the whole client/test process in
one xvfb-run session, not a separate Xvfb session for each script invocation.

For releases without Quick Play, --join-server waits for vanilla's profiled
resource-reload completion before using Multiplayer > Direct Connection.
The caller must launch that client to its title screen with no --server argument.
"""

import argparse
import ctypes
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
RELOAD_FINISHED_PATTERN = re.compile(r"\[Render thread/INFO\]: Resource reload finished after \d+ ms$")
CONNECTION_PATTERN = re.compile(r"\[Render thread/INFO\]: Connecting to ([^,]+), (\d+)$")
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


def loopback_address(value):
    match = re.fullmatch(r"127\.0\.0\.1:([0-9]{1,5})", value)
    if match is None or not 1 <= int(match.group(1)) <= 65535:
        raise argparse.ArgumentTypeError("must be 127.0.0.1:PORT with a port between 1 and 65535")
    return value


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--stage", choices=STAGES)
    mode.add_argument("--join-server", type=loopback_address, help="join this loopback server after resource loading")
    parser.add_argument("--client-log", required=True, type=Path, help="fresh client's logs/latest.log")
    parser.add_argument("--version", help="exact release in the Minecraft window title, e.g. 1.17.1 or 26.2")
    parser.add_argument("--timeout", type=positive_timeout, default=45.0, help="total timeout in seconds (default: 45)")
    args = parser.parse_args(argv)
    if args.version and not re.fullmatch(r"[0-9]+\.[0-9]+(?:\.[0-9]+)?", args.version):
        parser.error("--version must be a numeric stable Minecraft release")
    if args.join_server and not args.version:
        parser.error("--version is required with --join-server")
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


def client_log_lines(log_path):
    try:
        with log_path.open("rb") as log:
            log.seek(0, os.SEEK_END)
            log.seek(max(0, log.tell() - LOG_TAIL_BYTES))
            tail = log.read(LOG_TAIL_BYTES).decode("utf-8", errors="replace")
    except FileNotFoundError:
        return []
    except OSError as error:
        raise CaptureError(f"Could not read --client-log: {error}") from error
    return tail.splitlines()


def client_stage(log_path):
    latest = None
    for line in client_log_lines(log_path):
        if "[CHAT]" in line:
            match = STAGE_PATTERN.search(line)
            if match:
                latest = match.group(1)
    return latest


def client_resources_ready(log_path):
    started = False
    ready = False
    for line in client_log_lines(log_path):
        if "[Render thread/INFO]: Reloading ResourceManager:" in line:
            started = True
            ready = False
        elif started and RELOAD_FINISHED_PATTERN.search(line):
            # ProfiledReloadInstance emits this only after all reload listeners
            # have completed successfully, including models, shaders and atlases.
            ready = True
        elif "[Render thread/FATAL]" in line or "[Render thread/ERROR]: Unreported exception thrown!" in line:
            raise CaptureError("Minecraft reported a fatal client error during resource loading")
    return ready


def client_connection(log_path):
    latest = None
    for line in client_log_lines(log_path):
        match = CONNECTION_PATTERN.search(line)
        if match:
            latest = f"{match.group(1)}:{match.group(2)}"
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


def assert_window_input_target(xdo, args, window_id, expected_geometry):
    if find_window(xdo, args.version) != window_id:
        raise CaptureError("The selected Minecraft window disappeared or changed")
    if geometry(xdo, window_id) != expected_geometry:
        raise CaptureError("Minecraft window geometry changed during capture")
    if integer(xdo.run("getwindowfocus", "-f")) != window_id:
        raise CaptureError("Minecraft lost keyboard focus; refusing to send input")


def assert_input_target(xdo, args, window_id, expected_geometry):
    assert_window_input_target(xdo, args, window_id, expected_geometry)
    if client_stage(args.client_log) != args.stage:
        raise CaptureError("Client chat no longer reports the requested visual-test stage")


def create_xdotool(args):
    if sys.platform != "linux":
        raise CaptureError("This driver supports Linux/X11 only; use manual F2 capture mode on Windows")
    if not os.environ.get("DISPLAY", "").strip():
        raise CaptureError("DISPLAY is not set; run the client and driver inside the same xvfb-run session")
    executable = shutil.which("xdotool")
    if executable is None:
        raise CaptureError("xdotool is not installed (Debian/Ubuntu: apt-get install xdotool xvfb xauth)")

    return Xdotool(executable, args.timeout)


def unmodified_keycode(name):
    """Resolve a physical key without xdotool's implicit modifier selection."""
    try:
        x11 = ctypes.CDLL("libX11.so.6")
        x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
        x11.XOpenDisplay.restype = ctypes.c_void_p
        x11.XStringToKeysym.argtypes = [ctypes.c_char_p]
        x11.XStringToKeysym.restype = ctypes.c_ulong
        x11.XKeysymToKeycode.argtypes = [ctypes.c_void_p, ctypes.c_ulong]
        x11.XKeysymToKeycode.restype = ctypes.c_ubyte
        x11.XkbKeycodeToKeysym.argtypes = [ctypes.c_void_p, ctypes.c_ubyte, ctypes.c_int, ctypes.c_int]
        x11.XkbKeycodeToKeysym.restype = ctypes.c_ulong
        x11.XCloseDisplay.argtypes = [ctypes.c_void_p]
    except (OSError, AttributeError) as error:
        raise CaptureError(f"Could not load X11 keyboard mapping functions: {error}") from error
    display = x11.XOpenDisplay(None)
    if not display:
        raise CaptureError("Could not open DISPLAY to resolve the screenshot key")
    try:
        symbol = x11.XStringToKeysym(name.encode("ascii"))
        keycode = x11.XKeysymToKeycode(display, symbol)
        if not symbol or not 8 <= keycode <= 255 or x11.XkbKeycodeToKeysym(display, keycode, 0, 0) != symbol:
            raise CaptureError(f"{name} has no unmodified physical key in X11 keyboard group 0")
        return keycode
    finally:
        x11.XCloseDisplay(display)


def capture(args):
    xdo = create_xdotool(args)
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
    # xdotool can resolve the F2 keysym to Alt+F2. Older GLFW drops the F2
    # press when it shares Alt's timestamp (its duplicate-ibus-event filter).
    # A decimal keycode tells xdotool to use no implicit modifiers. Resolve
    # the current X11 map instead of assuming the usual physical keycode 68.
    screenshot_keycode = unmodified_keycode("F2")
    assert_input_target(xdo, args, window_id, bounds)
    xdo.run("key", "--clearmodifiers", "--delay", 100, str(screenshot_keycode))
    print(f"Sent F2 for stage '{args.stage}'; Gradle must verify the new PNG before acknowledging", flush=True)


def assert_join_target(xdo, args, window_id, bounds):
    assert_window_input_target(xdo, args, window_id, bounds)
    if not client_resources_ready(args.client_log):
        raise CaptureError("Client resources are not ready; refusing to send connection input")
    if client_connection(args.client_log) is not None or client_stage(args.client_log) is not None:
        raise CaptureError("Minecraft has already connected or started connecting; refusing to navigate its menus")


def click_client(xdo, args, window_id, bounds, x, y):
    assert_join_target(xdo, args, window_id, bounds)
    target_x, target_y = bounds["X"] + x, bounds["Y"] + y
    xdo.run("mousemove", "--screen", bounds["SCREEN"], target_x, target_y)
    xdo.sleep(0.1)
    pointer = shell_fields(xdo.run("getmouselocation", "--shell"), ("X", "Y", "SCREEN", "WINDOW"))
    if (pointer["X"], pointer["Y"], pointer["SCREEN"], pointer["WINDOW"]) != (
        target_x, target_y, bounds["SCREEN"], window_id
    ):
        raise CaptureError("Connection button is covered or its pointer target was not reached")
    assert_join_target(xdo, args, window_id, bounds)
    xdo.run("click", "--clearmodifiers", 1)


def join_server(args):
    xdo = create_xdotool(args)
    print(f"Waiting for Minecraft {args.version} resource reload before joining {args.join_server}", flush=True)
    while True:
        xdo.remaining()
        if client_connection(args.client_log) is not None or client_stage(args.client_log) is not None:
            raise CaptureError("Minecraft has already connected or started connecting")
        window_id = find_window(xdo, args.version)
        if window_id is not None and client_resources_ready(args.client_log):
            break
        xdo.sleep(0.25)
    bounds = geometry(xdo, window_id)
    xdo.run("windowraise", window_id)
    xdo.run("windowfocus", "--sync", window_id)
    # LoadingOverlay and TitleScreen fade after the completed resource future.
    # This is animation settling, not a substitute for the completion barrier.
    xdo.sleep(4.0)
    # Verified vanilla layout at 1280x720 / guiScale:2:
    # TitleScreen multiplayer y=height/4+48+24; Direct Connection y=height-52.
    click_client(xdo, args, window_id, bounds, 640, 344)
    xdo.sleep(1.0)
    click_client(xdo, args, window_id, bounds, 640, 636)
    xdo.sleep(1.0)
    # DirectJoinServerScreen's address field is at GUI y=116, height=20.
    click_client(xdo, args, window_id, bounds, 640, 252)
    assert_join_target(xdo, args, window_id, bounds)
    xdo.run("key", "--clearmodifiers", "ctrl+a")
    xdo.run("type", "--clearmodifiers", "--delay", 50, args.join_server)
    assert_join_target(xdo, args, window_id, bounds)
    xdo.run("key", "--clearmodifiers", "Return")
    while True:
        xdo.remaining()
        target = client_connection(args.client_log)
        if target is not None:
            if target != args.join_server:
                raise CaptureError(f"Minecraft connected to {target}, expected {args.join_server}")
            print(f"Minecraft began connecting to {target} after resource reload", flush=True)
            return
        xdo.sleep(0.25)


def main(argv=None):
    args = parse_args(argv)
    try:
        if args.join_server:
            join_server(args)
        else:
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
