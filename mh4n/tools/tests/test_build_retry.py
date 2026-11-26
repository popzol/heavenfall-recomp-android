"""A stopped build on a phone says what is known, and a compile that fails once is tried again (android/phone_build.py):
the build's last words used to be "@@error compile " with nothing after it when the step that failed gave no message."""
from __future__ import annotations

import os
import subprocess
import sys

import pytest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tools"))
sys.path.insert(0, os.path.join(ROOT, "tools", "android"))

import phone_build  # noqa: E402

# a compiler that stops once with nothing on its stderr (a signal's kill, as Android's low-memory killer does it, shows
# so) and works every time after
FAKE_CC = """\
import os, signal, sys
mark = sys.argv[1]
if not os.path.exists(mark):
    open(mark, "w").close()
    if len(sys.argv) > 2 and sys.argv[2] == "kill":
        os.kill(os.getpid(), signal.SIGKILL)
    sys.exit(1)
"""


@pytest.fixture
def fake_cc(tmp_path):
    script = tmp_path / "cc.py"
    script.write_text(FAKE_CC)
    return [sys.executable, str(script), str(tmp_path / "mark")]


def a_build(cc, calls):
    """build_game.main's shape: the last step is a link whose silence build.py turns into SystemExit(its stderr)."""
    def run(cmd):
        calls.append(list(cmd))
        r = subprocess.run(cc, capture_output=True, text=True)
        if r.returncode:
            raise SystemExit(r.stderr[-4000:])
        return 0
    return run


def lines_of(capfd):
    return capfd.readouterr().out.splitlines()


def test_a_compile_that_stops_once_with_no_message_is_tried_again(fake_cc, capfd, tmp_path):
    """Before: the first SystemExit("") went out as "@@error compile " and the build was lost; now the translation's
    kept (--compile-only) and the step is run once more."""
    tap, errors, failures = phone_build.Stages(), phone_build.Tail(), phone_build.Failures()
    tap.stage = "compile"
    calls: list[list[str]] = []
    with failures.watching():
        phone_build.build_with_retry(a_build(fake_cc, calls), ["--dump", "x"], tap, errors, failures,
                                     str(tmp_path / "zig-cache"))
    assert calls == [["--dump", "x"], ["--dump", "x", "--compile-only"]]
    out = lines_of(capfd)
    assert any("gave no message" in line for line in out)
    assert any("exit 1" in line for line in out)


def test_a_failure_that_repeats_goes_out_as_it_did_after_one_more_try(fake_cc, tmp_path):
    tap, errors, failures = phone_build.Stages(), phone_build.Tail(), phone_build.Failures()
    tap.stage = "compile"
    calls: list[list[str]] = []

    def always(cmd):
        calls.append(list(cmd))
        raise SystemExit("error in table.c: nope")
    with pytest.raises(SystemExit) as e:
        phone_build.build_with_retry(always, ["--compile-only"], tap, errors, failures, str(tmp_path / "zig-cache"))
    assert e.value.code == "error in table.c: nope"
    assert len(calls) == 2    # (one retry, never a loop)


def test_a_stop_before_the_compile_is_not_tried_again(tmp_path):
    tap, errors, failures = phone_build.Stages(), phone_build.Tail(), phone_build.Failures()
    tap.stage = "recompile"
    calls: list[list[str]] = []

    def stops(cmd):
        calls.append(list(cmd))
        raise SystemExit("no address in this version")
    with pytest.raises(SystemExit):
        phone_build.build_with_retry(stops, [], tap, errors, failures, str(tmp_path / "zig-cache"))
    assert len(calls) == 1


@pytest.mark.skipif(sys.platform == "win32", reason="a kill by a signal is a POSIX thing")
def test_a_kill_by_a_signal_is_named_in_the_log(fake_cc, capfd):
    failures = phone_build.Failures()
    with failures.watching():
        with pytest.raises(SystemExit):
            a_build(fake_cc + ["kill"], [])(["x"])
    lines = phone_build.stop_report("compile", "", "", failures)
    text = "\n".join(lines)
    assert "gave no message" in text
    assert "SIGKILL" in text and "signal 9" in text


def test_the_stderr_and_the_stage_are_in_the_report():
    failures = phone_build.Failures()
    text = "\n".join(phone_build.stop_report("link", "cannot open x.o", "ld: cannot open x.o\n", failures))
    assert "link" in text and "ld: cannot open x.o" in text and "gave no message" not in text
    assert "nothing" in "\n".join(phone_build.stop_report("compile", "boom", "", failures))


def test_the_watch_puts_subprocess_run_back(fake_cc):
    real = subprocess.run
    failures = phone_build.Failures()
    with failures.watching():
        assert subprocess.run is not real
    assert subprocess.run is real
