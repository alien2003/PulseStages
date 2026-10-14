"""Runs a command on a pseudo-terminal, like a user at a terminal (run-selftest.sh).

usage: pty_drive.py CTRL_C_AT CLOSE_AT TOTAL COMMAND...
Sends Ctrl-C at CTRL_C_AT seconds and closes the terminal (SIGHUP) at CLOSE_AT seconds (negative: never), kills the
command after TOTAL seconds. Prints what the terminal showed, then "exit N" or "signal N" and the duration on stderr.
"""

import os
import pty
import select
import signal
import sys
import time


def main() -> int:
    ctrl_c_at, close_at, total = (float(value) for value in sys.argv[1:4])
    pid, fd = pty.fork()
    if pid == 0:
        os.execvp(sys.argv[4], sys.argv[4:])
    shown = b""
    start = time.time()
    sent = closed = False
    status = None
    while status is None:
        elapsed = time.time() - start
        if elapsed > total:
            os.kill(pid, signal.SIGKILL)
            status = os.waitpid(pid, 0)[1]
            sys.stderr.write("pty_drive: killed after %.0f s\n" % total)
            break
        if not sent and 0 <= ctrl_c_at <= elapsed:
            os.write(fd, b"\x03")
            sent = True
        if not closed and 0 <= close_at <= elapsed:
            os.close(fd)
            closed = True
        if closed:
            done, code = os.waitpid(pid, os.WNOHANG)
            if done:
                status = code
            else:
                time.sleep(0.2)
            continue
        ready, _, _ = select.select([fd], [], [], 0.2)
        if ready:
            try:
                data = os.read(fd, 65536)
            except OSError:
                data = b""
            if not data:
                status = os.waitpid(pid, 0)[1]
            shown += data
    sys.stdout.write(shown.decode(errors="replace"))
    outcome = "signal %d" % os.WTERMSIG(status) if os.WIFSIGNALED(status) else "exit %d" % os.WEXITSTATUS(status)
    sys.stderr.write("pty_drive: %s after %.1f s\n" % (outcome, time.time() - start))
    return 0


if __name__ == "__main__":
    sys.exit(main())
