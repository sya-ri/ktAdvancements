"""GUI-free driver tests: python3 -B -m unittest discover -s game-test/scripts -p 'test_*.py'."""

import argparse
from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import io
from pathlib import Path
import subprocess
import unittest
from unittest import mock


SPEC = importlib.util.spec_from_file_location(
    "ktadvancements_capture_linux", Path(__file__).with_name("capture-linux.py")
)
DRIVER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DRIVER)


class FakeXdotool:
    """Record input without opening a display, launching a process, or sleeping."""

    def __init__(self):
        self.commands = []
        self.delays = []
        self.titles = {100: "Minecraft* 26.2 - Multiplayer (3rd-party Server)"}
        self.focus = 100
        self.width = 1280
        self.pointer_window = 100
        self.pointer_x = 691

    def remaining(self):
        return 45

    def sleep(self, seconds):
        self.delays.append(seconds)

    def run(self, *arguments, **kwargs):
        self.commands.append(arguments)
        command = arguments[0]
        if command == "search":
            return "\n".join(map(str, self.titles))
        if command == "getwindowname":
            return self.titles[arguments[1]]
        if command == "getwindowgeometry":
            return f"WINDOW=100\nX=11\nY=22\nWIDTH={self.width}\nHEIGHT=720\nSCREEN=0"
        if command == "getwindowfocus":
            return str(self.focus)
        if command == "getmouselocation":
            return f"X={self.pointer_x}\nY=390\nSCREEN=0\nWINDOW={self.pointer_window}"
        if command in ("windowraise", "windowfocus", "mousemove", "key"):
            return ""
        raise AssertionError(f"Unexpected xdotool command: {arguments}")

    def keys(self):
        return [command[-1] for command in self.commands if command[0] == "key"]


class CaptureLinuxTests(unittest.TestCase):
    def setUp(self):
        self.xdo = FakeXdotool()
        # These guards also keep future tests safe if they accidentally omit a mock.
        subprocess_guard = mock.patch.object(
            DRIVER.subprocess, "run", side_effect=AssertionError("Tests must not launch subprocesses")
        )
        sleep_guard = mock.patch.object(
            DRIVER.time, "sleep", side_effect=AssertionError("Tests must not sleep")
        )
        x11_guard = mock.patch.object(
            DRIVER.ctypes, "CDLL", side_effect=AssertionError("Tests must not load native display libraries")
        )
        subprocess_guard.start()
        sleep_guard.start()
        x11_guard.start()
        self.addCleanup(subprocess_guard.stop)
        self.addCleanup(sleep_guard.stop)
        self.addCleanup(x11_guard.stop)

    def capture(self, stage="zero", client_stages=None, **changes):
        self.xdo = FakeXdotool()
        for name, value in changes.items():
            setattr(self.xdo, name, value)
        args = argparse.Namespace(stage=stage, version="26.2", client_log=Path("unused.log"), timeout=45)
        with (
            mock.patch.object(DRIVER.sys, "platform", "linux"),
            mock.patch.dict(DRIVER.os.environ, {"DISPLAY": ":99"}),
            mock.patch.object(DRIVER.shutil, "which", return_value="/usr/bin/xdotool"),
            mock.patch.object(DRIVER, "Xdotool", return_value=self.xdo),
            mock.patch.object(DRIVER, "unmodified_keycode", return_value=104),
            mock.patch.object(DRIVER, "client_stage", return_value=stage, side_effect=client_stages),
            redirect_stdout(io.StringIO()),
        ):
            DRIVER.capture(args)
        return self.xdo

    def test_all_stages_input_order_and_root_offset(self):
        for stage in DRIVER.STAGES:
            with self.subTest(stage=stage):
                xdo = self.capture(stage)
                self.assertEqual(xdo.keys(), ["l", "104"] if stage == "zero" else ["104"])
                self.assertIn(("mousemove", "--screen", 0, 691, 390), xdo.commands)
                self.assertIn(("windowfocus", "--sync", 100), xdo.commands)
                self.assertIn(("getwindowfocus", "-f"), xdo.commands)
                self.assertIn(3.0, xdo.delays)
                self.assertEqual(5.0 in xdo.delays, stage == "zero")
                self.assertFalse(any(command[0] == "windowactivate" for command in xdo.commands))
                for command in xdo.commands:
                    if command[0] == "key":
                        self.assertNotIn("--window", command)
                        self.assertIn("--clearmodifiers", command)

    def test_wrong_focus_never_sends_key(self):
        with self.assertRaisesRegex(DRIVER.CaptureError, "lost keyboard focus"):
            self.capture(focus=999)
        self.assertEqual(self.xdo.keys(), [])

    def test_bad_resolution_never_sends_key(self):
        with self.assertRaisesRegex(DRIVER.CaptureError, "Expected a 1280x720 client area"):
            self.capture(width=854)
        self.assertEqual(self.xdo.keys(), [])

    def test_multiple_matching_windows_never_sends_key(self):
        with self.assertRaisesRegex(DRIVER.CaptureError, "Multiple matching Minecraft windows"):
            self.capture(titles={100: "Minecraft 26.2", 101: "Minecraft 26.2"})
        self.assertEqual(self.xdo.keys(), [])

    def test_covered_hover_never_sends_f2(self):
        with self.assertRaisesRegex(DRIVER.CaptureError, "Hover target was not reached or is covered"):
            self.capture(pointer_window=999)
        self.assertEqual(self.xdo.keys(), ["l"])

    def test_unreached_hover_never_sends_f2(self):
        with self.assertRaisesRegex(DRIVER.CaptureError, "Hover target was not reached or is covered"):
            self.capture(pointer_x=640)
        self.assertEqual(self.xdo.keys(), ["l"])

    def test_exact_version_and_launcher_exclusion(self):
        self.xdo.titles = {100: "Minecraft Launcher", 101: "Minecraft 26.1.1", 102: "Minecraft 26.1"}
        self.assertEqual(DRIVER.find_window(self.xdo, "26.1"), 102)
        self.assertIsNone(DRIVER.find_window(self.xdo, "26.2"))
        self.xdo.titles = {100: "Minecraft Launcher", 101: "Minecraft 1.17.1"}
        self.assertEqual(DRIVER.find_window(self.xdo, None), 101)

    def test_only_client_chat_markers_count(self):
        cases = (
            ("[INFO] KTADVANCEMENTS_VISUAL_STAGE zero\n", None),
            ("[Render thread/INFO]: [CHAT] KTADVANCEMENTS_VISUAL_STAGE zero\n", "zero"),
            ("[Render thread/INFO]: [System] [CHAT] KTADVANCEMENTS_VISUAL_STAGE complete\n", "complete"),
            ("[CHAT] KTADVANCEMENTS_VISUAL_STAGE zero\n[CHAT] KTADVANCEMENTS_VISUAL_STAGE partial\n", "partial"),
            ("[CHAT] KTADVANCEMENTS_VISUAL_STAGE revoked_extra\n", None),
        )
        for content, expected in cases:
            with self.subTest(content=content):
                log = mock.Mock()
                log.open.return_value = io.BytesIO(content.encode())
                self.assertEqual(DRIVER.client_stage(log), expected)

    def test_missing_and_unreadable_client_logs(self):
        log = mock.Mock()
        log.open.side_effect = FileNotFoundError("not created yet")
        self.assertIsNone(DRIVER.client_stage(log))
        log.open.side_effect = PermissionError("not readable")
        with self.assertRaisesRegex(DRIVER.CaptureError, "Could not read --client-log"):
            DRIVER.client_stage(log)

    def test_stage_is_rechecked_before_f2(self):
        with self.assertRaisesRegex(DRIVER.CaptureError, "no longer reports the requested"):
            self.capture(client_stages=["zero", "zero", "zero", "partial"])
        self.assertEqual(self.xdo.keys(), ["l"])

    def test_physical_f2_resolves_current_keymap_and_closes_display(self):
        for keycode in (68, 104):
            with self.subTest(keycode=keycode):
                x11 = mock.Mock()
                x11.XOpenDisplay.return_value = 1234
                x11.XStringToKeysym.return_value = 0xFFBF
                x11.XKeysymToKeycode.return_value = keycode
                x11.XkbKeycodeToKeysym.return_value = 0xFFBF
                with mock.patch.object(DRIVER.ctypes, "CDLL", return_value=x11):
                    self.assertEqual(DRIVER.unmodified_keycode("F2"), keycode)
                x11.XStringToKeysym.assert_called_once_with(b"F2")
                x11.XKeysymToKeycode.assert_called_once_with(1234, 0xFFBF)
                x11.XkbKeycodeToKeysym.assert_called_once_with(1234, keycode, 0, 0)
                x11.XCloseDisplay.assert_called_once_with(1234)

    def test_physical_f2_rejects_missing_or_modified_mapping(self):
        for keycode, symbol in ((0, 0xFFBF), (68, 0xFFBE)):
            with self.subTest(keycode=keycode, symbol=symbol):
                x11 = mock.Mock()
                x11.XOpenDisplay.return_value = 1234
                x11.XStringToKeysym.return_value = 0xFFBF
                x11.XKeysymToKeycode.return_value = keycode
                x11.XkbKeycodeToKeysym.return_value = symbol
                with mock.patch.object(DRIVER.ctypes, "CDLL", return_value=x11):
                    with self.assertRaisesRegex(DRIVER.CaptureError, "no unmodified physical key"):
                        DRIVER.unmodified_keycode("F2")
                x11.XCloseDisplay.assert_called_once_with(1234)

    def test_physical_f2_rejects_unavailable_library_and_display(self):
        with mock.patch.object(DRIVER.ctypes, "CDLL", side_effect=OSError("missing library")):
            with self.assertRaisesRegex(DRIVER.CaptureError, "Could not load X11"):
                DRIVER.unmodified_keycode("F2")
        x11 = mock.Mock()
        x11.XOpenDisplay.return_value = None
        with mock.patch.object(DRIVER.ctypes, "CDLL", return_value=x11):
            with self.assertRaisesRegex(DRIVER.CaptureError, "Could not open DISPLAY"):
                DRIVER.unmodified_keycode("F2")
        x11.XKeysymToKeycode.assert_not_called()
        x11.XCloseDisplay.assert_not_called()

    def test_wait_for_client_times_out_without_input(self):
        args = argparse.Namespace(stage="zero", version="26.2", client_log=Path("unused.log"))
        self.xdo.titles = {}
        self.xdo.remaining = mock.Mock(side_effect=[45, DRIVER.CaptureError("Timed out")])
        with redirect_stdout(io.StringIO()), self.assertRaisesRegex(DRIVER.CaptureError, "Timed out"):
            DRIVER.wait_for_client(self.xdo, args)
        self.assertEqual(self.xdo.keys(), [])

    def test_no_match_exit_is_not_a_failure(self):
        xdo = DRIVER.Xdotool("xdotool", 45)
        result = subprocess.CompletedProcess(["xdotool"], 1, stdout="", stderr="")
        with mock.patch.object(DRIVER.subprocess, "run", return_value=result):
            self.assertEqual(xdo.run("search", allow_no_match=True), "")

    def test_xdotool_errors_and_timeouts_are_failures(self):
        xdo = DRIVER.Xdotool("xdotool", 45)
        cases = (
            (1, "Cannot open display"),
            (0, "Warning: XTEST extension unavailable on :99"),
        )
        for code, message in cases:
            with self.subTest(message=message):
                result = subprocess.CompletedProcess(["xdotool"], code, stdout="", stderr=message)
                with mock.patch.object(DRIVER.subprocess, "run", return_value=result):
                    with self.assertRaises(DRIVER.CaptureError):
                        xdo.run("search", allow_no_match=True)
        with mock.patch.object(
            DRIVER.subprocess, "run", side_effect=subprocess.TimeoutExpired(["xdotool"], 5)
        ):
            with self.assertRaisesRegex(DRIVER.CaptureError, "windowfocus timed out"):
                xdo.run("windowfocus")

    def test_expired_deadline_is_failure(self):
        xdo = DRIVER.Xdotool("xdotool", 45)
        xdo.deadline = DRIVER.time.monotonic() - 1
        with self.assertRaisesRegex(DRIVER.CaptureError, "Timed out"):
            xdo.remaining()

    def test_render_delay_cannot_overrun_deadline(self):
        xdo = DRIVER.Xdotool("xdotool", 45)
        with mock.patch.object(xdo, "remaining", return_value=1):
            with self.assertRaisesRegex(DRIVER.CaptureError, "rendering delay"):
                xdo.sleep(3)

    def test_shell_output_is_data(self):
        for output in ("X=1\nX=2", "X=not-an-integer", "X=$(whoami)", "Y=2", "X"):
            with self.subTest(output=output), self.assertRaises(DRIVER.CaptureError):
                DRIVER.shell_fields(output, ("X",))
        self.assertEqual(DRIVER.shell_fields("WINDOW=0x64\nX=-12", ("WINDOW", "X")), {"WINDOW": 100, "X": -12})

    def test_nonlinux_rejected_before_xdotool(self):
        with mock.patch.object(DRIVER.sys, "platform", "win32"), mock.patch.object(DRIVER.shutil, "which") as which:
            with self.assertRaisesRegex(DRIVER.CaptureError, "manual F2 capture mode"):
                DRIVER.capture(argparse.Namespace())
            which.assert_not_called()

    def test_missing_display_or_xdotool_rejected(self):
        with mock.patch.object(DRIVER.sys, "platform", "linux"):
            with mock.patch.dict(DRIVER.os.environ, {"DISPLAY": ""}):
                with self.assertRaisesRegex(DRIVER.CaptureError, "DISPLAY is not set"):
                    DRIVER.capture(argparse.Namespace())
            with (
                mock.patch.dict(DRIVER.os.environ, {"DISPLAY": ":99"}),
                mock.patch.object(DRIVER.shutil, "which", return_value=None),
            ):
                with self.assertRaisesRegex(DRIVER.CaptureError, "xdotool is not installed"):
                    DRIVER.capture(argparse.Namespace())

    def test_timeout_cli_rejects_nonfinite_nonpositive_and_invalid_values(self):
        for timeout in ("nan", "inf", "+inf", "-inf", "0", "-1", "invalid"):
            with self.subTest(timeout=timeout), redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as error:
                    DRIVER.parse_args(["--stage", "zero", "--client-log", "unused.log", f"--timeout={timeout}"])
                self.assertEqual(error.exception.code, 2)
        args = DRIVER.parse_args(["--stage", "zero", "--client-log", "unused.log", "--timeout=0.25"])
        self.assertEqual(args.timeout, 0.25)

    def test_cli_defaults_help_and_invalid_version(self):
        args = DRIVER.parse_args(["--stage", "zero", "--client-log", "unused.log"])
        self.assertEqual(args.timeout, 45)
        self.assertIsNone(args.version)
        with redirect_stdout(io.StringIO()), self.assertRaises(SystemExit) as error:
            DRIVER.parse_args(["--help"])
        self.assertEqual(error.exception.code, 0)
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
            DRIVER.parse_args(["--stage", "zero", "--client-log", "unused.log", "--version", "26.2-snapshot"])
        self.assertEqual(error.exception.code, 2)

    def test_main_returns_clear_failure_exit(self):
        errors = io.StringIO()
        with mock.patch.object(DRIVER.sys, "platform", "win32"), redirect_stderr(errors):
            result = DRIVER.main(["--stage", "zero", "--client-log", "unused.log"])
        self.assertEqual(result, 1)
        self.assertIn("capture-linux:", errors.getvalue())
        self.assertIn("manual F2 capture mode", errors.getvalue())


class FakeJoinXdotool(FakeXdotool):
    def __init__(self):
        super().__init__()
        self.titles = {100: "Minecraft 1.17.1"}
        self.pointer_y = 390
        self.connected = None
        self.address = None

    def run(self, *arguments, **kwargs):
        command = arguments[0]
        if command == "mousemove":
            self.pointer_x, self.pointer_y = arguments[-2:]
        elif command == "getmouselocation":
            self.commands.append(arguments)
            return f"X={self.pointer_x}\nY={self.pointer_y}\nSCREEN=0\nWINDOW={self.pointer_window}"
        elif command == "type":
            self.address = arguments[-1]
            self.commands.append(arguments)
            return ""
        elif command == "click":
            self.commands.append(arguments)
            return ""
        elif command == "key" and arguments[-1] == "Return":
            self.connected = self.address
        return super().run(*arguments, **kwargs)


class JoinLinuxTests(unittest.TestCase):
    def setUp(self):
        self.xdo = FakeJoinXdotool()
        self.args = argparse.Namespace(
            stage=None, join_server="127.0.0.1:25565", version="1.17.1", client_log=Path("unused.log"), timeout=45
        )
        for patcher in (
            mock.patch.object(DRIVER.subprocess, "run", side_effect=AssertionError("Tests must not launch subprocesses")),
            mock.patch.object(DRIVER.time, "sleep", side_effect=AssertionError("Tests must not sleep")),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)

    def join(self, readiness=True, stages=None, connection=None):
        with (
            mock.patch.object(DRIVER.sys, "platform", "linux"),
            mock.patch.dict(DRIVER.os.environ, {"DISPLAY": ":99"}),
            mock.patch.object(DRIVER.shutil, "which", return_value="/usr/bin/xdotool"),
            mock.patch.object(DRIVER, "Xdotool", return_value=self.xdo),
            mock.patch.object(DRIVER, "client_resources_ready", side_effect=readiness if isinstance(readiness, list) else None,
                              return_value=readiness),
            mock.patch.object(DRIVER, "client_stage", return_value=stages),
            mock.patch.object(DRIVER, "client_connection", side_effect=connection or (lambda _: self.xdo.connected)),
            redirect_stdout(io.StringIO()),
        ):
            DRIVER.join_server(self.args)

    def input_commands(self):
        return [command for command in self.xdo.commands if command[0] in ("key", "type", "click", "mousemove")]

    def test_join_after_reload_uses_verified_coordinates_and_one_connection(self):
        self.join()
        self.assertEqual(self.xdo.keys(), ["ctrl+a", "Return"])
        for point in ((651, 366), (651, 658), (651, 274)):
            self.assertIn(("mousemove", "--screen", 0, *point), self.xdo.commands)
        self.assertEqual(sum(command[0] == "click" for command in self.xdo.commands), 3)
        self.assertIn(("type", "--clearmodifiers", "--delay", 50, "127.0.0.1:25565"), self.xdo.commands)
        self.assertNotIn("F2", self.xdo.keys())
        self.assertEqual(self.xdo.connected, self.args.join_server)

    def test_join_waits_for_actual_completion_without_sending_input(self):
        self.xdo.remaining = mock.Mock(side_effect=[45, DRIVER.CaptureError("Timed out")])
        with self.assertRaisesRegex(DRIVER.CaptureError, "Timed out"):
            self.join(readiness=False)
        self.assertEqual(self.input_commands(), [])

    def test_readiness_is_rechecked_before_menu_input(self):
        with self.assertRaisesRegex(DRIVER.CaptureError, "resources are not ready"):
            self.join(readiness=[True, False])
        self.assertEqual(self.input_commands(), [])

    def test_existing_connection_or_stage_rejects_menu_input(self):
        for stage, connection in (("zero", None), (None, "127.0.0.1:25565")):
            with self.subTest(stage=stage, connection=connection):
                self.xdo = FakeJoinXdotool()
                self.xdo.connected = connection
                with self.assertRaisesRegex(DRIVER.CaptureError, "already connected"):
                    self.join(stages=stage)
                self.assertEqual(self.input_commands(), [])

    def test_join_wrong_focus_never_sends_input(self):
        self.xdo.focus = 999
        with self.assertRaisesRegex(DRIVER.CaptureError, "lost keyboard focus"):
            self.join()
        self.assertEqual(self.input_commands(), [])

    def test_join_covered_button_never_clicks(self):
        self.xdo.pointer_window = 999
        with self.assertRaisesRegex(DRIVER.CaptureError, "Connection button is covered"):
            self.join()
        self.assertFalse(any(command[0] in ("click", "key", "type") for command in self.input_commands()))

    def test_join_checks_the_actual_connection_target(self):
        with self.assertRaisesRegex(DRIVER.CaptureError, "expected 127.0.0.1:25565"):
            self.join(connection=lambda _: "127.0.0.1:1" if self.xdo.connected else None)

    def test_join_connection_confirmation_is_bounded(self):
        self.xdo.remaining = mock.Mock(side_effect=[45, 45, DRIVER.CaptureError("Timed out")])
        with self.assertRaisesRegex(DRIVER.CaptureError, "Timed out"):
            self.join(connection=lambda _: None)
        self.assertEqual(self.xdo.keys(), ["ctrl+a", "Return"])

    def test_profiled_completion_not_atlas_creation_is_required(self):
        start = "[13:00:00] [Render thread/INFO]: Reloading ResourceManager: Default\n"
        done = "[13:00:12] [Render thread/INFO]: Resource reload finished after 12000 ms\n"
        cases = (
            (start, False),
            (start + "[Render thread/INFO]: Created: 1024x1024x4 minecraft:textures/atlas/blocks.png-atlas\n", False),
            (done, False),
            (start + done, True),
            (start + done + start, False),
            (start + "[Server thread/INFO]: Resource reload finished after 1 ms\n", False),
        )
        for content, expected in cases:
            with self.subTest(content=content):
                log = mock.Mock()
                log.open.return_value = io.BytesIO(content.encode())
                self.assertEqual(DRIVER.client_resources_ready(log), expected)

    def test_fatal_resource_reload_is_a_clear_failure(self):
        for error in ("[Render thread/FATAL]: Reported exception thrown!",
                      "[Render thread/ERROR]: Unreported exception thrown!"):
            log = mock.Mock()
            log.open.return_value = io.BytesIO(error.encode())
            with self.assertRaisesRegex(DRIVER.CaptureError, "fatal client error"):
                DRIVER.client_resources_ready(log)

    def test_actual_connection_log_parser(self):
        log = mock.Mock()
        log.open.return_value = io.BytesIO(b"[13:00:00] [Render thread/INFO]: Connecting to 127.0.0.1, 25565\n")
        self.assertEqual(DRIVER.client_connection(log), "127.0.0.1:25565")

    def test_join_cli_is_loopback_only_and_mutually_exclusive(self):
        base = ["--client-log", "unused.log", "--version", "1.17.1"]
        for address in ("example.com:25565", "127.0.0.1:0", "127.0.0.1:65536", "127.0.0.1", "127.0.0.1:1;exit"):
            with self.subTest(address=address), redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                DRIVER.parse_args(base + ["--join-server", address])
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            DRIVER.parse_args(base + ["--join-server", "127.0.0.1:1", "--stage", "zero"])
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            DRIVER.parse_args(["--client-log", "unused.log", "--join-server", "127.0.0.1:1"])
        self.assertEqual(DRIVER.parse_args(base + ["--join-server", "127.0.0.1:65535"]).join_server, "127.0.0.1:65535")


if __name__ == "__main__":
    unittest.main()
