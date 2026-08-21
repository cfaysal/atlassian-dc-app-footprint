#!/usr/bin/env python3
"""Keep the version column of the README script table in step with the file headers.

A stale version number is how a document starts describing a state the code never had,
so this runs on every sync and fails loudly rather than leaving a wrong number in place.

Usage:
    readme-version-lockstep.py README.md <jira-version> <confluence-version>

Exit codes:
    0  table is in step, or was updated
    1  a table row could not be found, so nothing was verified
"""

import io
import re
import sys

WITHHELD_NOTICE = "not in this repository yet"


def bump(text, filename, version):
    """Set the version cell of the table row that mentions filename.

    The row is matched on the file name rather than on the markdown link syntax around
    it. The table has been written both with and without links, and a regex that
    silently matches nothing is worse than no lockstep at all: it would report success
    while the number quietly goes stale.
    """
    pattern = re.compile(
        r"^(\|[^|\n]*" + re.escape(filename) + r"[^|\n]*\|[^|\n]*\| *)"
        r"[0-9]+\.[0-9]+"
        r"( *\|)",
        re.MULTILINE,
    )
    updated, count = pattern.subn(lambda m: m.group(1) + version + m.group(2), text)
    if count == 0:
        sys.stderr.write("  ERROR: no README table row matched " + filename + "\n")
        sys.exit(1)
    return updated


def main():
    if len(sys.argv) != 4:
        sys.stderr.write(__doc__)
        sys.exit(2)

    path, jira_version, confluence_version = sys.argv[1], sys.argv[2], sys.argv[3]

    original = io.open(path, encoding="utf-8").read()
    text = bump(original, "jiraDCappFootprint.groovy", jira_version)
    text = bump(text, "confluenceDCappFootprint.groovy", confluence_version)

    if text != original:
        io.open(path, "w", encoding="utf-8", newline="\n").write(text)
        print("  README.md version table updated")
    else:
        print("  README.md already in step")

    # While the endpoint files are withheld the README says so. Once they are actually
    # in the repository that notice is false, but removing it is a publication decision
    # and belongs to a human, not to a sync script.
    if WITHHELD_NOTICE in text:
        print("  REMINDER: README still says the endpoint files are not published yet.")
        print("            Remove that notice and restore the table links before pushing.")


if __name__ == "__main__":
    main()
