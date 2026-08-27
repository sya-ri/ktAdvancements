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
        subprocess_guard.start()
        sleep_guard.start()
        self.addCleanup(subprocess_guard.stop)
        self.addCleanup(sleep_guard.stop)

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
            mock.patch.object(DRIVER, "client_stage", return_value=stage, side_effect=client_stages),
            redirect_stdout(io.StringIO()),
        ):
            DRIVER.capture(args)
        return self.xdo

    def test_all_stages_input_order_and_root_offset(self):
        for stage in DRIVER.STAGES:
            with self.subTest(stage=stage):
                xdo = self.capture(stage)
                self.assertEqual(xdo.keys(), ["l", "F2"] if stage == "zero" else ["F2"])
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


if __name__ == "__main__":
    unittest.main()
