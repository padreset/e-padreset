#!/usr/bin/env python3
"""Cross-check our ESC/P2 maintenance jobs against escapy's grammar.

Reads the .bin files written by `:app:dumpEscp2Jobs` (built by our own
builders, never pinned) and parses each with escapy, an independent ESC/P2
implementation. Demands, per file:

- no lexer failure (an UnexpectedToken aborts the parse),
- remote mode entered and exited (set_remote_mode / exit_remote_mode nodes),
- every maintenance command consumed as a length-prefixed REMOTE code with
  the expected argument bytes (this is what validates OUR length fields).

Alternative-mode cleanings are raw service bytes, not remote-mode traffic,
so the dumper does not emit them: escapy has no grammar for them and a
vacuous pass is worse than the stated gap in Escp2JobDumper.

escapy is AGPL-3.0, used here as an external check only (installed with the
pinned commit below, never vendored):

    pip install git+https://github.com/ysard/escapy@747055eaf12704a655c979bcb1c2de82db651870
"""
import logging
import re
import sys
from collections import Counter
from pathlib import Path

ESCAPY_PIN = "747055eaf12704a655c979bcb1c2de82db651870"

# filename -> sorted unknown REMOTE codes escapy must report. Anything else
# (known nodes, EJL wrappers) is asserted structurally, not by name.
EXPECTED_UNKNOWN = {
    "nozzle-check.bin": sorted(["VI", "NC", "NC", "JE", "LD", "LD"]),
}
for _group in range(6):
    for _variant in ("normal", "power"):
        EXPECTED_UNKNOWN["cleaning-group%d-%s.bin" % (_group, _variant)] = sorted(
            ["TI", "CH", "JE", "LD", "LD"]
        )


def main() -> int:
    bindir = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("build/escp2")
    try:
        from escapy.grammar import init_parser
    except ImportError:
        print(
            "escapy is not installed. Install the pinned revision first:\n"
            "  pip install git+https://github.com/ysard/escapy@%s" % ESCAPY_PIN
        )
        return 2

    unknowns: list[str] = []

    class Tap(logging.Handler):
        def emit(self, record: logging.LogRecord) -> None:
            message = record.getMessage()
            match = re.search(r"Unknown REMOTE code detected '([A-Z]{2})'", message)
            if match:
                unknowns.append(match.group(1))

    root_logger = logging.getLogger()
    tap = Tap()
    root_logger.addHandler(tap)

    failures = 0
    for name, expected in sorted(EXPECTED_UNKNOWN.items()):
        path = bindir / name
        if not path.is_file():
            print("MISSING %s (run :app:dumpEscp2Jobs first)" % name)
            failures += 1
            continue
        del unknowns[:]
        try:
            tree = init_parser(path.read_bytes())
        except Exception as error:  # noqa: BLE001 - any parse failure fails the gate
            print("PARSE-FAIL %s: %s: %s" % (name, type(error).__name__, error))
            failures += 1
            continue
        pretty = tree.pretty()
        problems = []
        if "set_remote_mode" not in pretty:
            problems.append("remote mode never entered")
        if "exit_remote_mode" not in pretty:
            problems.append("remote mode never exited")
        if sorted(unknowns) != expected:
            problems.append(
                "unknown REMOTE codes %s != expected %s" % (sorted(unknowns), expected)
            )
        if problems:
            print("MISMATCH %s: %s" % (name, "; ".join(problems)))
            failures += 1
        else:
            print("OK %s (unknown codes: %s)" % (name, " ".join(sorted(unknowns))))
    root_logger.removeHandler(tap)
    print("%d file(s) checked, %d failure(s)" % (len(EXPECTED_UNKNOWN), failures))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
